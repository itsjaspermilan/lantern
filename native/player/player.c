/*
 * Lantern player host: loads a libretro core (a shared library), runs it on its own thread, shows
 * its video on an Android surface, plays its audio through AAudio, and feeds it the buttons.
 *
 * Also owns what a menu needs: pause, numbered save-state slots plus a resume point, a copy of the
 * latest frame (for screenshots), battery saves, cheats, and fast-forward.
 *
 * Limits: software-rendered cores only (no hardware rendering), no core options, one controller on
 * port 0, the video is scaled by the system compositor, and speed follows the audio clock.
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
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
    void (*cheat_reset)(void);
    void (*cheat_set)(unsigned, bool, const char *);
} C;

/* ---- shared state ---- */
static char g_core_path[512], g_rom_path[512], g_sys_dir[512], g_save_dir[512], g_name[256], g_sram_path[600];
static ANativeWindow *g_win;
static int g_win_w, g_win_h;
static unsigned g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555; /* the libretro default */
static atomic_uint g_buttons;  /* buttons held right now */
static atomic_uint g_pressed;  /* buttons pressed since the last frame, even if already let go */
static unsigned g_latched;     /* the presses the core sees this frame */
static atomic_bool g_run;
static atomic_bool g_paused;
static atomic_int g_speed = 1; /* 1 normal, 2 to 4 faster, 0 as fast as possible */
static int g_start_slot = -1;  /* state to load right after the game loads; -1 none, 0 resume point */
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

static double now_s(void);
static double g_lock_s, g_run_s; /* seconds in the last stretch spent waiting for a window buffer / inside the core */
static unsigned g_video_calls, g_dupe_calls; /* video callbacks with a picture / with "repeat the last frame" */
static int g_lit_percent = -1;               /* share of the last sampled picture that is not black */
static unsigned g_raw_first;                 /* first two bytes of the last sampled picture, as the core sent them */

/* ---- the latest frame, kept as ARGB for screenshots ---- */
static pthread_mutex_t g_snap_lock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t *g_snap;
static size_t g_snap_cap;
static unsigned g_snap_w, g_snap_h;

/* ---- audio: the emulator writes, the AAudio callback reads ---- */
static int16_t g_ring[RING_FRAMES * 2];
static atomic_uint g_wr, g_rd, g_underruns;
static AAudioStream *g_stream;

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    if (atomic_load(&g_speed) != 1) return frames; /* silent while fast-forwarding */
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

/* ---- video: copy the core's frame into the window (RGBX 8888) and keep an ARGB copy ---- */
static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !g_win) { g_dupe_calls++; return; } /* NULL means "repeat the last frame" */
    g_video_calls++;
    if ((int)w != g_win_w || (int)h != g_win_h) {
        ANativeWindow_setBuffersGeometry(g_win, (int32_t)w, (int32_t)h, WINDOW_FORMAT_RGBX_8888);
        g_win_w = (int)w;
        g_win_h = (int)h;
    }
    ANativeWindow_Buffer buf;
    double t_lock = now_s();
    if (ANativeWindow_lock(g_win, &buf, NULL) != 0) return;
    g_lock_s += now_s() - t_lock; /* time spent waiting for a free buffer */
    unsigned rows = h < (unsigned)buf.height ? h : (unsigned)buf.height;
    unsigned cols = w < (unsigned)buf.width ? w : (unsigned)buf.width;

    pthread_mutex_lock(&g_snap_lock);
    size_t need = (size_t)cols * rows;
    if (need > g_snap_cap) {
        free(g_snap);
        g_snap = malloc(need * sizeof *g_snap);
        g_snap_cap = g_snap ? need : 0;
    }
    uint32_t *snap = g_snap_cap >= need ? g_snap : NULL;
    if (snap) { g_snap_w = cols; g_snap_h = rows; }

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
        if (snap) { /* window pixels are 0xFFBBGGRR; Android bitmaps want 0xFFRRGGBB */
            uint32_t *out = snap + (size_t)y * cols;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t p = dst[x];
                out[x] = (p & 0xFF00FF00u) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
            }
        }
    }
    /* For diagnosis: what share of the last picture is not black (checked on one frame in 30). */
    if (snap && (g_video_calls % 30) == 0) {
        size_t lit = 0, total = (size_t)cols * rows;
        for (size_t i = 0; i < total; i += 7) if (snap[i] & 0x00FFFFFF) lit++;
        g_lit_percent = (int)(lit * 7 * 100 / (total ? total : 1));
        g_raw_first = ((const uint8_t *)data)[0] | (((const uint8_t *)data)[1] << 8);
    }
    pthread_mutex_unlock(&g_snap_lock);
    ANativeWindow_unlockAndPost(g_win);
}

/* ---- input ---- */
static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    unsigned mask = atomic_load(&g_buttons) | g_latched;
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
    SYM(get_memory_data, "retro_get_memory_data");
    SYM(get_memory_size, "retro_get_memory_size");
    SYM(cheat_reset, "retro_cheat_reset");
    SYM(cheat_set, "retro_cheat_set");
    if (C.api_version() != RETRO_API_VERSION) { set_status("ERROR: core speaks libretro API %u", C.api_version()); return false; }
    return true;
}

/* ---- save states: slot 0 is the resume point, 1 to 9 are the person's slots ---- */
static void state_path(int slot, char *out, size_t cap) {
    if (slot == 0) snprintf(out, cap, "%s/%s.state.auto", g_save_dir, g_name);
    else snprintf(out, cap, "%s/%s.state%d", g_save_dir, g_name, slot);
}

/* Returns the size written, or 0. Writes a temp file and renames it so a failure keeps the old state. */
static int write_state(int slot) {
    size_t size = C.serialize_size();
    if (size == 0) { set_note("this core has no save states"); return 0; }
    void *buf = malloc(size);
    if (!buf) return 0;
    int result = 0;
    char path[640], tmp[660];
    state_path(slot, path, sizeof path);
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    if (C.serialize(buf, size)) {
        FILE *f = fopen(tmp, "wb");
        bool ok = f && fwrite(buf, 1, size, f) == size;
        if (f) ok = (fclose(f) == 0) && ok;
        if (ok && rename(tmp, path) == 0) { result = (int)size; set_note("state saved (%zu bytes)", size); }
        else set_note("could not write the state");
    } else set_note("the core refused to save");
    free(buf);
    return result;
}

static bool read_state(int slot) {
    size_t size = C.serialize_size();
    if (size == 0) return false;
    char path[640];
    state_path(slot, path, sizeof path);
    FILE *f = fopen(path, "rb");
    if (!f) { set_note("no saved state"); return false; }
    void *buf = malloc(size);
    size_t got = buf ? fread(buf, 1, size, f) : 0;
    fclose(f);
    bool ok = got == size && C.unserialize(buf, size);
    free(buf);
    set_note(ok ? "state loaded" : "state did not load");
    return ok;
}

/* The menu thread asks, the emulation thread answers between frames (also while paused). */
static pthread_mutex_t g_req_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_req_cv = PTHREAD_COND_INITIALIZER;
static int g_req_kind, g_req_slot, g_req_done, g_req_result; /* kind: 0 none, 1 save, 2 load */

static void service_request(void) {
    pthread_mutex_lock(&g_req_lock);
    int kind = g_req_kind, slot = g_req_slot;
    pthread_mutex_unlock(&g_req_lock);
    if (!kind) return;
    int result = kind == 1 ? write_state(slot) : (read_state(slot) ? 1 : 0);
    pthread_mutex_lock(&g_req_lock);
    g_req_result = result;
    g_req_kind = 0;
    g_req_done = 1;
    pthread_cond_broadcast(&g_req_cv);
    pthread_mutex_unlock(&g_req_lock);
}

static int request_blocking(int kind, int slot) {
    if (!g_started || !atomic_load(&g_run)) return 0;
    struct timespec dl;
    clock_gettime(CLOCK_REALTIME, &dl);
    dl.tv_sec += 3;
    pthread_mutex_lock(&g_req_lock);
    g_req_kind = kind;
    g_req_slot = slot;
    g_req_done = 0;
    g_req_result = 0;
    while (!g_req_done) {
        if (pthread_cond_timedwait(&g_req_cv, &g_req_lock, &dl) != 0) break;
    }
    int r = g_req_done ? g_req_result : 0;
    g_req_kind = 0;
    pthread_mutex_unlock(&g_req_lock);
    return r;
}

/* ---- in-game saves (battery RAM): the core owns the memory, we keep it in <saves>/<game>.srm ---- */
static unsigned char *g_sram_shadow; /* what is on disk, to notice changes */
static size_t g_sram_size;

static void sram_load(void) {
    g_sram_size = C.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    unsigned char *mem = C.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!g_sram_size || !mem) { g_sram_size = 0; return; }
    g_sram_shadow = malloc(g_sram_size);
    if (!g_sram_shadow) { g_sram_size = 0; return; }
    FILE *f = fopen(g_sram_path, "rb");
    if (f) {
        size_t got = fread(mem, 1, g_sram_size, f);
        fclose(f);
        LOGI("loaded %zu bytes of save RAM from %s", got, g_sram_path);
    }
    memcpy(g_sram_shadow, mem, g_sram_size);
}

/* Writes the save RAM if it changed (or always when forced), through a temp file and a rename. */
static void sram_flush(bool force) {
    if (!g_sram_shadow) return;
    unsigned char *mem = C.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!mem) return;
    if (!force && memcmp(g_sram_shadow, mem, g_sram_size) == 0) return;
    char tmp[620];
    snprintf(tmp, sizeof tmp, "%s.tmp", g_sram_path);
    FILE *f = fopen(tmp, "wb");
    if (!f) { LOGE("cannot write %s", tmp); return; }
    bool ok = fwrite(mem, 1, g_sram_size, f) == g_sram_size;
    ok = (fclose(f) == 0) && ok;
    if (ok && rename(tmp, g_sram_path) == 0) memcpy(g_sram_shadow, mem, g_sram_size);
    else LOGE("saving %s failed", g_sram_path);
}

/* ---- cheats: the codes that should be on. The emulation thread gives them to the core between frames. ---- */
static pthread_mutex_t g_cheat_lock = PTHREAD_MUTEX_INITIALIZER;
static char **g_cheats;
static int g_cheat_count;
static atomic_bool g_cheats_changed;

/* Starts over each time: mGBA, for one, ignores the "enabled" flag, so a cheat is only ever turned off by leaving it out. */
static void apply_cheats(void) {
    pthread_mutex_lock(&g_cheat_lock);
    C.cheat_reset();
    for (int i = 0; i < g_cheat_count; i++) C.cheat_set((unsigned)i, true, g_cheats[i]);
    LOGI("%d cheats on", g_cheat_count);
    pthread_mutex_unlock(&g_cheat_lock);
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
        if (n <= 0) { fclose(f); set_status("ERROR: the game file is empty"); goto done; }
        if (!rom_data || fread(rom_data, 1, (size_t)n, f) != (size_t)n) { fclose(f); set_status("ERROR: cannot read the game file"); goto done; }
        fclose(f);
        gi.data = rom_data;
        gi.size = (size_t)n;
    }
    if (!C.load_game(&gi)) { set_status("ERROR: the core would not load this game"); goto done; }
    loaded = true;
    sram_load();

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

    if (g_start_slot >= 0) set_note(read_state(g_start_slot) ? "resumed" : "no resume point");

    const unsigned target = (unsigned)(g_rate / g_fps * 3.0); /* keep about three frames of sound queued */
    bool audio_on = false, audio_paused = false;
    int last_speed = 1;
    unsigned frames = 0, shown = 0;
    double t_start = now_s(), t_report = t_start, t_next = t_start, t_sram = t_start;

    while (atomic_load(&g_run)) {
        service_request();
        if (atomic_exchange(&g_cheats_changed, false)) apply_cheats(); /* also while paused: the menu changes them */

        if (atomic_load(&g_paused)) {
            if (audio_on && !audio_paused) { AAudioStream_requestPause(g_stream); audio_paused = true; }
            usleep(4000);
            t_next = now_s();
            continue;
        }
        if (audio_paused) {
            atomic_store(&g_rd, atomic_load(&g_wr)); /* drop stale sound */
            AAudioStream_requestStart(g_stream);
            audio_paused = false;
        }
        int speed = atomic_load(&g_speed);
        if (speed != last_speed) {
            LOGI("speed %d (0 means unlimited)", speed);
            if (speed == 1) atomic_store(&g_rd, atomic_load(&g_wr));
            last_speed = speed;
            t_next = now_s();
        }

        if (audio_on && speed == 1) {
            if (atomic_load(&g_wr) - atomic_load(&g_rd) > target) { usleep(1000); continue; }
        } else if (speed != 0) {
            /* No audio yet, no audio device, or fast-forward: follow the clock instead. */
            double t = now_s();
            if (t < t_next) { usleep(500); continue; }
            t_next = (t - t_next > 0.25 ? t : t_next) + 1.0 / (g_fps * speed);
        }
        /* A tap shorter than one frame must still reach the game once. */
        g_latched = atomic_exchange(&g_pressed, 0);
        double t_run = now_s();
        C.run();
        g_run_s += now_s() - t_run;
        frames++;
        shown++;
        if (!audio_on && !g_stream && frames == 3) audio_on = audio_open((int32_t)g_rate);
        double t = now_s();
        if (t - t_sram >= 5.0) { sram_flush(false); t_sram = t; }
        if (t - t_report >= 1.0) {
            char note[96];
            pthread_mutex_lock(&g_status_lock);
            snprintf(note, sizeof note, "%s", g_note);
            pthread_mutex_unlock(&g_status_lock);
            set_status("%s  %dx%d fmt %u  %.1f fps  core %.1f ms, video wait %.1f ms, %.1f pictures + %.1f repeats  lit %d%% raw %04x  audio %s, underruns %u  %s",
                       si.library_name, g_win_w, g_win_h, g_pixel_format, shown / (t - t_report),
                       shown ? g_run_s * 1000.0 / shown : 0.0, shown ? g_lock_s * 1000.0 / shown : 0.0,
                       shown ? (double)g_video_calls / shown : 0.0, shown ? (double)g_dupe_calls / shown : 0.0,
                       g_lit_percent, g_raw_first, audio_on ? "on" : "OFF", atomic_load(&g_underruns), note);
            g_run_s = g_lock_s = 0;
            g_video_calls = g_dupe_calls = 0;
            shown = 0;
            t_report = t;
        }
    }

done:
    audio_close();
    if (loaded) {
        sram_flush(false); /* before unloading: the core frees this memory */
        C.unload_game();
    }
    free(g_sram_shadow);
    g_sram_shadow = NULL;
    g_sram_size = 0;
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
                                                                jstring sys_dir, jstring save_dir, jobject surface,
                                                                jint start_slot) {
    (void)cls;
    if (g_started) return JNI_FALSE;
    copy_jstring(env, core, g_core_path, sizeof g_core_path);
    copy_jstring(env, rom, g_rom_path, sizeof g_rom_path);
    copy_jstring(env, sys_dir, g_sys_dir, sizeof g_sys_dir);
    copy_jstring(env, save_dir, g_save_dir, sizeof g_save_dir);
    /* RetroArch's file names: the game's name without its extension, plus .srm or .state. */
    const char *base = strrchr(g_rom_path, '/');
    snprintf(g_name, sizeof g_name, "%s", base ? base + 1 : "game");
    char *dot = strrchr(g_name, '.');
    if (dot && dot != g_name) *dot = 0;
    snprintf(g_sram_path, sizeof g_sram_path, "%s/%s.srm", g_save_dir, g_name);

    g_win = ANativeWindow_fromSurface(env, surface);
    if (!g_win) return JNI_FALSE;
    g_win_w = g_win_h = 0;
    g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    g_start_slot = start_slot;
    atomic_store(&g_buttons, 0);
    atomic_store(&g_pressed, 0);
    g_latched = 0;
    atomic_store(&g_wr, 0);
    atomic_store(&g_rd, 0);
    atomic_store(&g_underruns, 0);
    atomic_store(&g_paused, false);
    atomic_store(&g_speed, 1);
    pthread_mutex_lock(&g_snap_lock);
    g_snap_w = g_snap_h = 0;
    pthread_mutex_unlock(&g_snap_lock);
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
    if (down) {
        atomic_fetch_or(&g_buttons, 1u << id);
        atomic_fetch_or(&g_pressed, 1u << id);
    } else {
        atomic_fetch_and(&g_buttons, ~(1u << id));
    }
}

/* Replaces the cheats that are on. Safe before nativeStart: the game then starts with them. */
JNIEXPORT void JNICALL Java_app_lantern_Player_nativeCheats(JNIEnv *env, jclass cls, jobjectArray codes) {
    (void)cls;
    int n = codes ? (*env)->GetArrayLength(env, codes) : 0;
    char **list = n ? calloc((size_t)n, sizeof *list) : NULL;
    if (n && !list) return;
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, codes, i);
        const char *c = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        list[i] = strdup(c ? c : "");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    pthread_mutex_lock(&g_cheat_lock);
    for (int i = 0; i < g_cheat_count; i++) free(g_cheats[i]);
    free(g_cheats);
    g_cheats = list;
    g_cheat_count = n;
    pthread_mutex_unlock(&g_cheat_lock);
    atomic_store(&g_cheats_changed, true);
}

JNIEXPORT void JNICALL Java_app_lantern_Player_nativePause(JNIEnv *env, jclass cls, jboolean paused) {
    (void)env; (void)cls;
    atomic_store(&g_paused, paused == JNI_TRUE);
    if (paused) atomic_store(&g_buttons, 0); /* a button held when the menu opened must not stay down */
}

JNIEXPORT void JNICALL Java_app_lantern_Player_nativeSpeed(JNIEnv *env, jclass cls, jint speed) {
    (void)env; (void)cls;
    atomic_store(&g_speed, speed < 0 || speed > 8 ? 1 : speed);
}

/* Slot 0 is the resume point, 1 to 9 the person's slots. Returns bytes written, or 0. */
JNIEXPORT jint JNICALL Java_app_lantern_Player_nativeSaveState(JNIEnv *env, jclass cls, jint slot) {
    (void)env; (void)cls;
    return slot < 0 || slot > 9 ? 0 : request_blocking(1, slot);
}

JNIEXPORT jboolean JNICALL Java_app_lantern_Player_nativeLoadState(JNIEnv *env, jclass cls, jint slot) {
    (void)env; (void)cls;
    return slot < 0 || slot > 9 ? JNI_FALSE : request_blocking(2, slot) ? JNI_TRUE : JNI_FALSE;
}

/* Width in the high 16 bits, height in the low 16, or 0 before the first frame. */
JNIEXPORT jint JNICALL Java_app_lantern_Player_nativeFrameSize(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    pthread_mutex_lock(&g_snap_lock);
    jint r = (jint)((g_snap_w << 16) | g_snap_h);
    pthread_mutex_unlock(&g_snap_lock);
    return r;
}

/* Copies the latest frame as ARGB pixels. Returns true if the array was big enough. */
JNIEXPORT jboolean JNICALL Java_app_lantern_Player_nativeSnapshot(JNIEnv *env, jclass cls, jintArray out) {
    (void)cls;
    jboolean ok = JNI_FALSE;
    pthread_mutex_lock(&g_snap_lock);
    jsize len = (*env)->GetArrayLength(env, out);
    size_t need = (size_t)g_snap_w * g_snap_h;
    if (g_snap && need > 0 && (size_t)len >= need) {
        (*env)->SetIntArrayRegion(env, out, 0, (jsize)need, (const jint *)g_snap);
        ok = JNI_TRUE;
    }
    pthread_mutex_unlock(&g_snap_lock);
    return ok;
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
