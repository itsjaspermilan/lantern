/*
 * Lantern player host: loads a libretro core (a shared library), runs it on its own thread, shows
 * its video on an Android surface, plays its audio through AAudio, and feeds it the buttons.
 *
 * Spike limits: software-rendered cores only (no hardware rendering), no core options, one
 * controller on port 0, the video is scaled by the system compositor (no aspect or integer
 * scaling here), and the emulation speed follows the audio clock.
 */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "libretro.h"

#define TAG "LanternPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define RING_FRAMES 16384 /* stereo frames, power of two */

/* ---- the loaded core ---- */
static struct {
    void *lib;
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    unsigned (*api_version)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
} C;

/* ---- shared state ---- */
static char g_core_path[512], g_rom_path[512], g_sys_dir[512], g_save_dir[512], g_state_path[600];
static ANativeWindow *g_win;
static int g_win_w, g_win_h;
static unsigned g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555; /* the libretro default */
static atomic_uint g_buttons;
static atomic_bool g_run;
static atomic_int g_request; /* 0 none, 1 save state, 2 load state */
static pthread_t g_thread;
static bool g_started;
static volatile float g_aspect = 4.0f / 3.0f;
static double g_fps = 60.0, g_rate = 48000.0;

static pthread_mutex_t g_status_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_status[320] = "starting";
static char g_note[96] = "";

static void set_status(const char *fmt, ...) {
    char buf[320];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_status_lock);
    snprintf(g_status, sizeof g_status, "%s", buf);
    pthread_mutex_unlock(&g_status_lock);
}

static void set_note(const char *fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_status_lock);
    snprintf(g_note, sizeof g_note, "%s", buf);
    pthread_mutex_unlock(&g_status_lock);
}

/* ---- audio: the emulator writes, the AAudio callback reads ---- */
static int16_t g_ring[RING_FRAMES * 2];
static atomic_uint g_wr, g_rd, g_underruns;
static AAudioStream *g_stream;

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    unsigned wr = atomic_load(&g_wr), rd = atomic_load(&g_rd);
    size_t room = RING_FRAMES - (wr - rd);
    size_t n = frames > room ? room : frames;
    for (size_t i = 0; i < n; i++) {
        unsigned idx = (wr + (unsigned)i) & (RING_FRAMES - 1);
        g_ring[idx * 2] = data[i * 2];
        g_ring[idx * 2 + 1] = data[i * 2 + 1];
    }
    atomic_store(&g_wr, wr + (unsigned)n);
    return frames;
}

static void audio_sample_cb(int16_t l, int16_t r) {
    int16_t pair[2] = {l, r};
    audio_batch_cb(pair, 1);
}

static aaudio_data_callback_result_t audio_out_cb(AAudioStream *s, void *user, void *audio, int32_t frames) {
    (void)s; (void)user;
    int16_t *out = audio;
    unsigned rd = atomic_load(&g_rd), wr = atomic_load(&g_wr);
    unsigned avail = wr - rd;
    int32_t n = avail < (unsigned)frames ? (int32_t)avail : frames;
    for (int32_t i = 0; i < n; i++) {
        unsigned idx = (rd + (unsigned)i) & (RING_FRAMES - 1);
        out[i * 2] = g_ring[idx * 2];
        out[i * 2 + 1] = g_ring[idx * 2 + 1];
    }
    if (n < frames) {
        memset(out + n * 2, 0, (size_t)(frames - n) * 2 * sizeof(int16_t));
        atomic_fetch_add(&g_underruns, 1);
    }
    atomic_store(&g_rd, rd + (unsigned)n);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static bool audio_open(int32_t rate) {
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setDataCallback(b, audio_out_cb, NULL);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &g_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("AAudio open failed: %s", AAudio_convertResultToText(r)); g_stream = NULL; return false; }
    if (AAudioStream_requestStart(g_stream) != AAUDIO_OK) { LOGE("AAudio start failed"); AAudioStream_close(g_stream); g_stream = NULL; return false; }
    return true;
}

static void audio_close(void) {
    if (!g_stream) return;
    AAudioStream_requestStop(g_stream);
    AAudioStream_close(g_stream);
    g_stream = NULL;
}

/* ---- video: copy the core's frame into the window, converting to RGBX 8888 ---- */
static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !g_win) return; /* NULL means "repeat the last frame" */
    if ((int)w != g_win_w || (int)h != g_win_h) {
        ANativeWindow_setBuffersGeometry(g_win, (int32_t)w, (int32_t)h, WINDOW_FORMAT_RGBX_8888);
        g_win_w = (int)w;
        g_win_h = (int)h;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_win, &buf, NULL) != 0) return;
    unsigned rows = h < (unsigned)buf.height ? h : (unsigned)buf.height;
    unsigned cols = w < (unsigned)buf.width ? w : (unsigned)buf.width;
    for (unsigned y = 0; y < rows; y++) {
        uint32_t *dst = (uint32_t *)buf.bits + (size_t)y * buf.stride;
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        if (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *src = (const uint32_t *)row;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t s = src[x];
                dst[x] = 0xFF000000u | ((s & 0xFF) << 16) | (s & 0xFF00) | ((s >> 16) & 0xFF);
            }
        } else if (g_pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *src = (const uint16_t *)row;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t s = src[x], r = (s >> 11) & 31, g = (s >> 5) & 63, b = s & 31;
                r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        } else { /* 0RGB1555 */
            const uint16_t *src = (const uint16_t *)row;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t s = src[x], r = (s >> 10) & 31, g = (s >> 5) & 31, b = s & 31;
                r = (r << 3) | (r >> 2); g = (g << 3) | (g >> 2); b = (b << 3) | (b >> 2);
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
    }
    ANativeWindow_unlockAndPost(g_win);
}

/* ---- input ---- */
static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    unsigned mask = atomic_load(&g_buttons);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(mask & 0xFFFF);
    return id < 16 ? (int16_t)((mask >> id) & 1) : 0;
}

/* ---- the core's questions about us ---- */
static void log_cb(enum retro_log_level level, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int prio = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN
             : level == RETRO_LOG_DEBUG ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;
    __android_log_vprint(prio, "LibretroCore", fmt, ap);
    va_end(ap);
}

static bool environment_cb(unsigned cmd, void *data) {
    switch (cmd & 0xFFFF) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        unsigned f = *(const unsigned *)data;
        if (f != RETRO_PIXEL_FORMAT_XRGB8888 && f != RETRO_PIXEL_FORMAT_RGB565 && f != RETRO_PIXEL_FORMAT_0RGB1555) return false;
        g_pixel_format = f;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = g_sys_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = g_save_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = log_cb;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE:
        ((struct retro_variable *)data)->value = NULL;
        return false;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false;
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
        const struct retro_game_geometry *g = data;
        if (g->aspect_ratio > 0) g_aspect = g->aspect_ratio;
        else if (g->base_height) g_aspect = (float)g->base_width / (float)g->base_height;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
        const struct retro_system_av_info *a = data;
        if (a->geometry.aspect_ratio > 0) g_aspect = a->geometry.aspect_ratio;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
        return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        return true;
    default:
        return false; /* includes hardware rendering: software cores only for now */
    }
}

/* ---- loading ---- */
#define SYM(field, name) do { *(void **)&C.field = dlsym(C.lib, name); if (!C.field) { set_status("ERROR: core has no %s", name); return false; } } while (0)

static bool load_core(void) {
    C.lib = dlopen(g_core_path, RTLD_NOW | RTLD_LOCAL);
    if (!C.lib) { set_status("ERROR: %s", dlerror()); return false; }
    SYM(set_environment, "retro_set_environment");
    SYM(set_video_refresh, "retro_set_video_refresh");
    SYM(set_audio_sample, "retro_set_audio_sample");
    SYM(set_audio_sample_batch, "retro_set_audio_sample_batch");
    SYM(set_input_poll, "retro_set_input_poll");
    SYM(set_input_state, "retro_set_input_state");
    SYM(init, "retro_init");
    SYM(deinit, "retro_deinit");
    SYM(api_version, "retro_api_version");
    SYM(get_system_info, "retro_get_system_info");
    SYM(get_system_av_info, "retro_get_system_av_info");
    SYM(load_game, "retro_load_game");
    SYM(unload_game, "retro_unload_game");
    SYM(run, "retro_run");
    SYM(serialize_size, "retro_serialize_size");
    SYM(serialize, "retro_serialize");
    SYM(unserialize, "retro_unserialize");
    if (C.api_version() != RETRO_API_VERSION) { set_status("ERROR: core speaks libretro API %u", C.api_version()); return false; }
    return true;
}

static void do_state_request(int req) {
    size_t size = C.serialize_size();
    if (size == 0) { set_note("no save state support"); return; }
    void *buf = malloc(size);
    if (!buf) return;
    if (req == 1) {
        if (C.serialize(buf, size)) {
            FILE *f = fopen(g_state_path, "wb");
            if (f && fwrite(buf, 1, size, f) == size) set_note("state saved (%zu bytes)", size);
            else set_note("could not write %s", g_state_path);
            if (f) fclose(f);
        } else set_note("core refused to save");
    } else {
        FILE *f = fopen(g_state_path, "rb");
        if (!f) set_note("no saved state yet");
        else {
            size_t got = fread(buf, 1, size, f);
            fclose(f);
            set_note(got == size && C.unserialize(buf, size) ? "state loaded" : "state did not load");
        }
    }
    free(buf);
}

static double now_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static void *emulation_thread(void *arg) {
    (void)arg;
    void *rom_data = NULL;
    bool loaded = false;
    if (!load_core()) goto done;

    C.set_environment(environment_cb);
    C.init();
    C.set_video_refresh(video_cb);
    C.set_audio_sample(audio_sample_cb);
    C.set_audio_sample_batch(audio_batch_cb);
    C.set_input_poll(input_poll_cb);
    C.set_input_state(input_state_cb);

    struct retro_system_info si;
    memset(&si, 0, sizeof si);
    C.get_system_info(&si);
    LOGI("core: %s %s", si.library_name, si.library_version);

    struct retro_game_info gi;
    memset(&gi, 0, sizeof gi);
    gi.path = g_rom_path;
    if (!si.need_fullpath && g_rom_path[0]) {
        FILE *f = fopen(g_rom_path, "rb");
        if (!f) { set_status("ERROR: cannot open %s", g_rom_path); goto done; }
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        fseek(f, 0, SEEK_SET);
        rom_data = malloc(n > 0 ? (size_t)n : 1);
        if (!rom_data || fread(rom_data, 1, (size_t)n, f) != (size_t)n) { fclose(f); set_status("ERROR: cannot read the game file"); goto done; }
        fclose(f);
        gi.data = rom_data;
        gi.size = (size_t)n;
    }
    if (!C.load_game(&gi)) { set_status("ERROR: the core would not load this game"); goto done; }
    loaded = true;

    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    C.get_system_av_info(&av);
    g_fps = av.timing.fps > 1 ? av.timing.fps : 60.0;
    g_rate = av.timing.sample_rate > 8000 ? av.timing.sample_rate : 48000.0;
    g_aspect = av.geometry.aspect_ratio > 0 ? av.geometry.aspect_ratio
             : av.geometry.base_height ? (float)av.geometry.base_width / (float)av.geometry.base_height : 4.0f / 3.0f;
    ANativeWindow_setBuffersGeometry(g_win, (int32_t)av.geometry.base_width, (int32_t)av.geometry.base_height, WINDOW_FORMAT_RGBX_8888);
    g_win_w = (int)av.geometry.base_width;
    g_win_h = (int)av.geometry.base_height;
    LOGI("av: %ux%u aspect %.3f, %.2f fps, %.0f Hz", av.geometry.base_width, av.geometry.base_height, g_aspect, g_fps, g_rate);

    const unsigned target = (unsigned)(g_rate / g_fps * 3.0); /* keep about three frames of sound queued */
    bool audio_on = false;
    unsigned frames = 0, shown = 0;
    double t_start = now_s(), t_report = t_start, t_next = t_start;

    while (atomic_load(&g_run)) {
        int req = atomic_exchange(&g_request, 0);
        if (req) do_state_request(req);

        if (audio_on) {
            if (atomic_load(&g_wr) - atomic_load(&g_rd) > target) { usleep(1000); continue; }
        } else {
            /* No audio yet (first frames) or no audio device: follow the clock instead. */
            double t = now_s();
            if (t < t_next) { usleep(1000); continue; }
            t_next = (t - t_next > 0.25 ? t : t_next) + 1.0 / g_fps;
        }
        C.run();
        frames++;
        shown++;
        if (!audio_on && !g_stream && frames == 3) audio_on = audio_open((int32_t)g_rate);
        double t = now_s();
        if (t - t_report >= 1.0) {
            char note[96];
            pthread_mutex_lock(&g_status_lock);
            snprintf(note, sizeof note, "%s", g_note);
            pthread_mutex_unlock(&g_status_lock);
            set_status("%s  %dx%d  %.1f fps  audio %s, underruns %u  %s", si.library_name, g_win_w, g_win_h,
                       shown / (t - t_report), audio_on ? "on" : "OFF", atomic_load(&g_underruns), note);
            shown = 0;
            t_report = t;
        }
    }

done:
    audio_close();
    if (loaded) C.unload_game();
    if (C.lib) {
        if (C.deinit) C.deinit();
        dlclose(C.lib);
    }
    memset(&C, 0, sizeof C);
    free(rom_data);
    return NULL;
}

/* ---- JNI ---- */
static void copy_jstring(JNIEnv *env, jstring s, char *dst, size_t cap) {
    const char *c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
    snprintf(dst, cap, "%s", c ? c : "");
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
}

JNIEXPORT jboolean JNICALL Java_app_lantern_Player_nativeStart(JNIEnv *env, jclass cls, jstring core, jstring rom,
                                                                jstring sys_dir, jstring save_dir, jobject surface) {
    (void)cls;
    if (g_started) return JNI_FALSE;
    copy_jstring(env, core, g_core_path, sizeof g_core_path);
    copy_jstring(env, rom, g_rom_path, sizeof g_rom_path);
    copy_jstring(env, sys_dir, g_sys_dir, sizeof g_sys_dir);
    copy_jstring(env, save_dir, g_save_dir, sizeof g_save_dir);
    const char *base = strrchr(g_rom_path, '/');
    snprintf(g_state_path, sizeof g_state_path, "%s/%s.state0", g_save_dir, base ? base + 1 : "game");

    g_win = ANativeWindow_fromSurface(env, surface);
    if (!g_win) return JNI_FALSE;
    g_win_w = g_win_h = 0;
    g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    atomic_store(&g_buttons, 0);
    atomic_store(&g_wr, 0);
    atomic_store(&g_rd, 0);
    atomic_store(&g_underruns, 0);
    atomic_store(&g_request, 0);
    atomic_store(&g_run, true);
    set_status("loading core");
    set_note("%s", "");
    if (pthread_create(&g_thread, NULL, emulation_thread, NULL) != 0) {
        ANativeWindow_release(g_win);
        g_win = NULL;
        return JNI_FALSE;
    }
    g_started = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_app_lantern_Player_nativeStop(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (!g_started) return;
    atomic_store(&g_run, false);
    pthread_join(g_thread, NULL);
    if (g_win) ANativeWindow_release(g_win);
    g_win = NULL;
    g_started = false;
}

JNIEXPORT void JNICALL Java_app_lantern_Player_nativeButton(JNIEnv *env, jclass cls, jint id, jboolean down) {
    (void)env; (void)cls;
    if (id < 0 || id > 15) return;
    if (down) atomic_fetch_or(&g_buttons, 1u << id);
    else atomic_fetch_and(&g_buttons, ~(1u << id));
}

JNIEXPORT void JNICALL Java_app_lantern_Player_nativeStateRequest(JNIEnv *env, jclass cls, jint what) {
    (void)env; (void)cls;
    atomic_store(&g_request, what);
}

JNIEXPORT jstring JNICALL Java_app_lantern_Player_nativeStatus(JNIEnv *env, jclass cls) {
    (void)cls;
    char buf[320];
    pthread_mutex_lock(&g_status_lock);
    snprintf(buf, sizeof buf, "%s", g_status);
    pthread_mutex_unlock(&g_status_lock);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jfloat JNICALL Java_app_lantern_Player_nativeAspect(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return g_aspect;
}
