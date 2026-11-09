/*
 * Lantern player host: loads a libretro core (a shared library), runs it on its own thread, shows
 * its video on an Android surface, plays its audio through AAudio, and feeds it the buttons.
 *
 * Also owns what a menu needs: pause, numbered save-state slots plus a resume point, a copy of the
 * latest frame (for screenshots), battery saves, cheats, and fast-forward.
 *
 * Cores that draw with OpenGL ES (Nintendo 64) get an EGL context on the window and a frame buffer to draw into;
 * each finished picture is copied to the window. Software cores are copied pixel by pixel.
 *
 * Limits: no core options (cores use their defaults), one controller on port 0, the video is scaled by the
 * system compositor, and speed follows the audio clock.
 */
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
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
    void (*set_controller_port_device)(unsigned, unsigned);
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
static atomic_int g_axes[4];   /* left X, left Y, right X, right Y, -32768 to 32767 */
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
    if (buf[0]) LOGI("note: %s", buf);
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

/* ---- hardware rendering: an OpenGL ES context on the window, a frame buffer for the core, a copy to the window ---- */
static struct retro_hw_render_callback g_hw;
static bool g_hw_wanted, g_hw_on;
static EGLDisplay g_dpy = EGL_NO_DISPLAY;
static EGLSurface g_surf = EGL_NO_SURFACE;
static EGLContext g_ctx = EGL_NO_CONTEXT;
static GLuint g_fbo, g_fbo_tex, g_fbo_depth;
static unsigned g_fbo_w, g_fbo_h;
static int g_surf_w, g_surf_h;

static uintptr_t hw_get_framebuffer(void) { return g_fbo; }
static retro_proc_address_t hw_get_proc(const char *name) { return (retro_proc_address_t)eglGetProcAddress(name); }

static void hw_destroy(void) {
    if (g_dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_ctx != EGL_NO_CONTEXT) eglDestroyContext(g_dpy, g_ctx);
        if (g_surf != EGL_NO_SURFACE) eglDestroySurface(g_dpy, g_surf);
        eglTerminate(g_dpy);
    }
    g_dpy = EGL_NO_DISPLAY;
    g_ctx = EGL_NO_CONTEXT;
    g_surf = EGL_NO_SURFACE;
    g_fbo = g_fbo_tex = g_fbo_depth = 0;
    g_hw_on = false;
}

static bool hw_make_fbo(const struct retro_game_geometry *geo);

/* On the emulation thread, after the game is loaded: the window gets the core's base size and the frame buffer its biggest. */
static bool hw_init(const struct retro_game_geometry *geo) {
    g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_dpy == EGL_NO_DISPLAY || !eglInitialize(g_dpy, NULL, NULL)) { set_status("ERROR: no graphics display"); return false; }
    const EGLint cfg_attr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                               EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(g_dpy, cfg_attr, &cfg, 1, &n) || n < 1) { set_status("ERROR: no OpenGL ES 3 graphics"); return false; }
    EGLint fmt = 0;
    eglGetConfigAttrib(g_dpy, cfg, EGL_NATIVE_VISUAL_ID, &fmt);
    g_surf_w = (int)geo->base_width;
    g_surf_h = (int)geo->base_height;
    ANativeWindow_setBuffersGeometry(g_win, g_surf_w, g_surf_h, fmt);
    g_surf = eglCreateWindowSurface(g_dpy, cfg, g_win, NULL);
    const EGLint ctx_attr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g_ctx = eglCreateContext(g_dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (g_surf == EGL_NO_SURFACE || g_ctx == EGL_NO_CONTEXT || !eglMakeCurrent(g_dpy, g_surf, g_surf, g_ctx)) {
        set_status("ERROR: could not start OpenGL ES (egl %x)", eglGetError());
        return false;
    }
    eglSwapInterval(g_dpy, 0); /* the audio clock paces the game */
    if (!hw_make_fbo(geo)) return false;
    g_hw_on = true;
    LOGI("OpenGL ES: %s, frame buffer %ux%u", (const char *)glGetString(GL_RENDERER), g_fbo_w, g_fbo_h);
    return true;
}

static bool hw_make_fbo(const struct retro_game_geometry *geo) {
    g_fbo_w = geo->max_width ? geo->max_width : geo->base_width;
    g_fbo_h = geo->max_height ? geo->max_height : geo->base_height;
    glGenTextures(1, &g_fbo_tex);
    glBindTexture(GL_TEXTURE_2D, g_fbo_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)g_fbo_w, (GLsizei)g_fbo_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glGenRenderbuffers(1, &g_fbo_depth);
    glBindRenderbuffer(GL_RENDERBUFFER, g_fbo_depth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, (GLsizei)g_fbo_w, (GLsizei)g_fbo_h);
    glGenFramebuffers(1, &g_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_fbo_tex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, g_fbo_depth);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { set_status("ERROR: no frame buffer for the game"); return false; }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

/* The core changed its picture size (a new resolution option): a bigger frame buffer if it needs one, and the window follows. */
static void hw_resize(const struct retro_game_geometry *geo) {
    unsigned w = geo->max_width ? geo->max_width : geo->base_width, h = geo->max_height ? geo->max_height : geo->base_height;
    if (w > g_fbo_w || h > g_fbo_h) {
        GLuint tex = g_fbo_tex, depth = g_fbo_depth, fbo = g_fbo;
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &tex);
        glDeleteRenderbuffers(1, &depth);
        if (!hw_make_fbo(geo)) LOGE("could not make a bigger frame buffer");
        else LOGI("frame buffer now %ux%u", g_fbo_w, g_fbo_h);
    }
    if (geo->base_width && geo->base_height && ((int)geo->base_width != g_surf_w || (int)geo->base_height != g_surf_h)) {
        g_surf_w = (int)geo->base_width;
        g_surf_h = (int)geo->base_height;
        ANativeWindow_setBuffersGeometry(g_win, g_surf_w, g_surf_h, 0);
    }
}

/* A picture the core drew into our frame buffer: keep a copy now and then for screenshots, then show it. */
static void hw_present(unsigned w, unsigned h) {
    if (w > g_fbo_w) w = g_fbo_w;
    if (h > g_fbo_h) h = g_fbo_h;
    if (!w || !h) return;
    g_video_calls++;
    if (g_video_calls == 1 || (g_video_calls % 15) == 0) {
        uint32_t *px = malloc((size_t)w * h * sizeof *px);
        if (px) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, g_fbo);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glReadPixels(0, 0, (GLsizei)w, (GLsizei)h, GL_RGBA, GL_UNSIGNED_BYTE, px);
            pthread_mutex_lock(&g_snap_lock);
            size_t need = (size_t)w * h;
            if (need > g_snap_cap) {
                free(g_snap);
                g_snap = malloc(need * sizeof *g_snap);
                g_snap_cap = g_snap ? need : 0;
            }
            if (g_snap_cap >= need) {
                for (unsigned y = 0; y < h; y++) { /* GL rows run bottom to top; pixels are 0xAABBGGRR */
                    const uint32_t *src = px + (size_t)(h - 1 - y) * w;
                    uint32_t *out = g_snap + (size_t)y * w;
                    for (unsigned x = 0; x < w; x++) {
                        uint32_t p = src[x];
                        out[x] = (p & 0xFF00FF00u) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
                    }
                }
                g_snap_w = w;
                g_snap_h = h;
                size_t lit = 0;
                for (size_t i = 0; i < need; i += 7) if (g_snap[i] & 0x00FFFFFF) lit++;
                g_lit_percent = (int)(lit * 7 * 100 / need);
            }
            pthread_mutex_unlock(&g_snap_lock);
            free(px);
        }
    }
    double t = now_s();
    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    glBlitFramebuffer(0, 0, (GLint)w, (GLint)h, 0, 0, g_surf_w, g_surf_h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    eglSwapBuffers(g_dpy, g_surf);
    g_lock_s += now_s() - t;
}

/* Nintendo DS, one screen bigger: melonDS draws the two screens side by side (512x192, top on the left). When the
 * person wants one screen big (g_ds_big: 1 top, 2 bottom) they are redrawn here at 2x and 1x, the top still on the
 * left and the bottom on the right, in a 768x384 picture. */
static atomic_int g_ds_big;
static bool g_ds_composed;
static uint32_t *g_ds_buf;

static const uint32_t *ds_compose(const uint32_t *src, size_t pitch_px) {
    enum { OW = 768, OH = 384 };
    if (!g_ds_buf) g_ds_buf = malloc((size_t)OW * OH * sizeof *g_ds_buf);
    if (!g_ds_buf) return NULL;
    memset(g_ds_buf, 0, (size_t)OW * OH * sizeof *g_ds_buf);
    int big = atomic_load(&g_ds_big);
    const uint32_t *top = src, *bottom = src + 256;
    const uint32_t *bigsrc = big == 1 ? top : bottom, *smallsrc = big == 1 ? bottom : top;
    unsigned bigx = big == 1 ? 0 : 256, smallx = big == 1 ? 512 : 0;
    for (unsigned y = 0; y < 384; y++) {
        const uint32_t *s = bigsrc + (size_t)(y / 2) * pitch_px;
        uint32_t *d = g_ds_buf + (size_t)y * OW + bigx;
        for (unsigned x = 0; x < 512; x++) d[x] = s[x / 2];
    }
    for (unsigned y = 0; y < 192; y++) {
        const uint32_t *s = smallsrc + (size_t)y * pitch_px;
        memcpy(g_ds_buf + (size_t)(y + 96) * OW + smallx, s, 256 * sizeof *s);
    }
    return g_ds_buf;
}

/* While fast-forwarding, a picture is drawn at most about 60 times a second. Drawing waits for the screen's refresh,
 * so drawing every frame would hold the game to the screen's speed however fast the core could run. */
static double g_last_post;
static bool skip_picture(void) {
    if (atomic_load(&g_speed) == 1) return false;
    double t = now_s();
    if (t - g_last_post < 0.0165) return true;
    g_last_post = t;
    return false;
}

/* ---- video: copy the core's frame into the window (RGBX 8888) and keep an ARGB copy ---- */
static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (data == RETRO_HW_FRAME_BUFFER_VALID) { if (g_hw_on && !skip_picture()) hw_present(w, h); return; }
    if (!data || !g_win) { g_dupe_calls++; return; } /* NULL means "repeat the last frame" */
    if (skip_picture()) return;
    if (atomic_load(&g_ds_big) && w == 512 && h == 192 && g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
        const uint32_t *c = ds_compose((const uint32_t *)data, pitch / 4);
        if (c) {
            data = c;
            w = 768;
            h = 384;
            pitch = 768 * 4;
            g_aspect = 2.0f;
            g_ds_composed = true;
        }
    } else if (g_ds_composed) {
        g_ds_composed = false;
        g_aspect = h ? (float)w / (float)h : g_aspect;
    }
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
    if (port == 0 && device == RETRO_DEVICE_ANALOG) {
        if (index > RETRO_DEVICE_INDEX_ANALOG_RIGHT || id > RETRO_DEVICE_ID_ANALOG_Y) return 0;
        return (int16_t)atomic_load(&g_axes[index * 2 + id]);
    }
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    (void)index;
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

/* Core options: the core lists them with SET_VARIABLES ("Name; first|second", the first is the default) and asks for
 * their values with GET_VARIABLE. The person's choices are kept in <saves>/<core>.options, one "key=value" per line;
 * g_better holds Lantern's own defaults for this device, used instead of the core's first value. */
#define MAX_OPTIONS 256
static struct opt { char *key, *desc, **vals; int n, cur, def; } g_opts[MAX_OPTIONS];
static int g_opt_count;
static pthread_mutex_t g_opt_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool g_opt_changed;
static char g_opt_path[620];

static const char *const g_better[][2] = {
    {"mupen64plus-43screensize", "1280x960"}, /* four times the console's own picture: easy for this chip */
    {"melonds_threaded_renderer", "disabled"}, /* with it on, the picture went white for seconds and the sound starved */
    {"melonds_touch_mode", "Joystick"}, /* the right stick moves a pointer on the lower screen: no touch needed */
};

static void options_clear(void) {
    for (int i = 0; i < g_opt_count; i++) {
        free(g_opts[i].key);
        free(g_opts[i].desc);
        for (int j = 0; j < g_opts[i].n; j++) free(g_opts[i].vals[j]);
        free(g_opts[i].vals);
    }
    g_opt_count = 0;
}

static int option_index(const struct opt *o, const char *value) {
    for (int j = 0; j < o->n; j++) if (strcmp(o->vals[j], value) == 0) return j;
    return -1;
}

static void options_save(void) {
    char tmp[640];
    snprintf(tmp, sizeof tmp, "%s.tmp", g_opt_path);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    for (int i = 0; i < g_opt_count; i++)
        if (g_opts[i].cur != g_opts[i].def) fprintf(f, "%s=%s\n", g_opts[i].key, g_opts[i].vals[g_opts[i].cur]);
    if (fclose(f) == 0) rename(tmp, g_opt_path);
}

static void options_set(const struct retro_variable *vars) {
    pthread_mutex_lock(&g_opt_lock);
    options_clear();
    for (const struct retro_variable *v = vars; v && v->key && g_opt_count < MAX_OPTIONS; v++) {
        const char *sep = v->value ? strstr(v->value, "; ") : NULL;
        if (!sep) continue;
        struct opt *o = &g_opts[g_opt_count];
        o->key = strdup(v->key);
        o->desc = strndup(v->value, (size_t)(sep - v->value));
        const char *p = sep + 2;
        o->vals = NULL;
        o->n = 0;
        for (;;) {
            size_t n = strcspn(p, "|");
            o->vals = realloc(o->vals, (size_t)(o->n + 1) * sizeof *o->vals);
            o->vals[o->n++] = strndup(p, n);
            if (!p[n]) break;
            p += n + 1;
        }
        o->cur = 0;
        for (size_t k = 0; k < sizeof g_better / sizeof g_better[0]; k++) {
            int j = strcmp(g_better[k][0], o->key) == 0 ? option_index(o, g_better[k][1]) : -1;
            if (j >= 0) o->cur = j;
        }
        o->def = o->cur;
        g_opt_count++;
    }
    int kept = 0;
    FILE *f = fopen(g_opt_path, "r");
    char line[256];
    while (f && fgets(line, sizeof line, f)) {
        char *eq = strchr(line, '=');
        line[strcspn(line, "\r\n")] = 0;
        if (!eq) continue;
        *eq = 0;
        for (int i = 0; i < g_opt_count; i++) {
            if (strcmp(g_opts[i].key, line) != 0) continue;
            int j = option_index(&g_opts[i], eq + 1);
            if (j >= 0) { g_opts[i].cur = j; kept++; }
        }
    }
    if (f) fclose(f);
    LOGI("%d core options, %d of them chosen by the person", g_opt_count, kept);
    pthread_mutex_unlock(&g_opt_lock);
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
    {
        struct retro_variable *v = data;
        for (int i = 0; i < g_opt_count; i++)
            if (v->key && strcmp(v->key, g_opts[i].key) == 0) { v->value = g_opts[i].vals[g_opts[i].cur]; return true; }
        v->value = NULL;
        return false;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = atomic_exchange(&g_opt_changed, false);
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
        const struct retro_game_geometry *g = data;
        if (g_hw_on) hw_resize(g);
        if (g->aspect_ratio > 0) g_aspect = g->aspect_ratio;
        else if (g->base_height) g_aspect = (float)g->base_width / (float)g->base_height;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
        const struct retro_system_av_info *a = data;
        if (g_hw_on) hw_resize(&a->geometry);
        if (a->geometry.aspect_ratio > 0) g_aspect = a->geometry.aspect_ratio;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
        return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        struct retro_hw_render_callback *hw = data;
        if (hw->context_type != RETRO_HW_CONTEXT_OPENGLES2 && hw->context_type != RETRO_HW_CONTEXT_OPENGLES3
            && hw->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION) return false;
        hw->get_current_framebuffer = hw_get_framebuffer;
        hw->get_proc_address = hw_get_proc;
        g_hw = *hw;
        g_hw_wanted = true;
        LOGI("the core draws with OpenGL ES (type %d, version %u.%u)", (int)hw->context_type, hw->version_major, hw->version_minor);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        *(unsigned *)data = RETRO_HW_CONTEXT_OPENGLES3;
        return true;
    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        return true;
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: {
        const struct retro_controller_info *ci = data;
        for (int p = 0; ci && ci[p].types; p++)
            for (unsigned k = 0; k < ci[p].num_types; k++)
                LOGI("port %d controller type %u: %s", p, ci[p].types[k].id, ci[p].types[k].desc);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
        options_set(data);
        return true;
    default:
        return false;
    }
}

/* ---- loading ---- */
/* Some cores (Play!) call back into Java from their own threads and expect the JavaVM that the app's own
 * JNI_OnLoad would have given them: they are loaded with dlopen here, so that never runs for them. */
static JavaVM *g_vm;
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

#define SYM(field, name) do { *(void **)&C.field = dlsym(C.lib, name); if (!C.field) { set_status("ERROR: core has no %s", name); return false; } } while (0)

static bool load_core(void) {
    C.lib = dlopen(g_core_path, RTLD_NOW | RTLD_LOCAL);
    if (!C.lib) { set_status("ERROR: %s", dlerror()); return false; }
    void (*set_vm)(JavaVM *) = (void (*)(JavaVM *))dlsym(C.lib, "_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM");
    if (set_vm && g_vm) set_vm(g_vm);
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
    *(void **)&C.set_controller_port_device = dlsym(C.lib, "retro_set_controller_port_device"); /* optional */
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

/* ---- Action Replay codes, for the Nintendo DS. The melonDS build here accepts cheats but has no engine behind them, so
 * Lantern runs the codes itself: after each frame it writes into the core's main RAM (the 4 MB at 0x02000000).
 * Supported lines: 0/1/2 write 32/16/8 bits; FA000000 xxxxxxxx finds that 32-bit value in RAM (an "if"; the game's code moves around), then FB00oooo / FC00oooo write 16 / 32 bits at that place plus oooo, and FD00oooo writes a Thumb BL there to the address in the second word; 3/4/5/6 if greater/less/equal/different (32 bit); 94000130 if the
 * pad's keys are held (a 0 bit in the value's top half means "this key must be down"); D0 end if; D2 end all.
 * A condition covers the lines up to its D0 or D2, and conditions nest. Other lines are skipped. ---- */
static bool g_cheat_ar;       /* the core is melonDS: use the interpreter, not retro_cheat_set */
static uint32_t *g_ar;        /* the lines of every cheat that is on, each cheat closed by D2000000 00000000 */
static int g_ar_n;            /* number of 32-bit words */

static void ar_load(const char *code) {
    uint32_t w;
    for (const char *p = code; *p;) {
        int n = 0;
        while (p[n] && ((p[n] >= '0' && p[n] <= '9') || (p[n] >= 'A' && p[n] <= 'F') || (p[n] >= 'a' && p[n] <= 'f'))) n++;
        if (n == 8) {
            char tmp[9];
            memcpy(tmp, p, 8);
            tmp[8] = 0;
            w = (uint32_t)strtoul(tmp, NULL, 16);
            uint32_t *g = realloc(g_ar, (size_t)(g_ar_n + 1) * sizeof *g);
            if (!g) return;
            g_ar = g;
            g_ar[g_ar_n++] = w;
        }
        p += n ? n : 1;
    }
    if (g_ar_n & 1) g_ar_n--; /* a code is made of pairs */
    uint32_t *g = realloc(g_ar, (size_t)(g_ar_n + 2) * sizeof *g);
    if (!g) return;
    g_ar = g;
    g_ar[g_ar_n++] = 0xD2000000u;
    g_ar[g_ar_n++] = 0;
}

static uint32_t ar_pad_bits(void) { /* the pad as AR knows it: A0 B1 Select2 Start3 Right4 Left5 Up6 Down7 R8 L9, 1 = down */
    unsigned b = atomic_load(&g_buttons);
    static const int map[10] = {8, 0, 2, 3, 7, 6, 4, 5, 11, 10}; /* RetroPad id for each bit */
    uint32_t out = 0;
    for (int i = 0; i < 10; i++) if (b & (1u << map[i])) out |= 1u << i;
    return out;
}

static struct { int line; uint32_t base; bool ok; } g_fa[16]; /* what each "find" line found the last time it looked */
static int g_fa_n;

static void ar_run(void) {
    if (!g_ar_n) return;
    uint8_t *ram = C.get_memory_data(2);
    size_t size = C.get_memory_size(2);
    if (!ram || size < 0x400000) return;
    static unsigned tick;
    bool look = (tick++ % 6) == 0; /* looking through RAM costs a little: every sixth frame */
    int skip = 0;
    uint32_t base = 0;
    bool base_ok = false;
    for (int i = 0; i + 1 < g_ar_n; i += 2) {
        uint32_t a = g_ar[i], b = g_ar[i + 1], top = a >> 28, off = a & 0x003FFFFFu, code = a >> 24;
        if (top == 0xD) {
            if (code == 0xD0) { if (skip) skip--; }
            else if (code == 0xD2) skip = 0;
            continue;
        }
        if ((top >= 3 && top <= 6) || top == 9 || code == 0xFA) {
            if (skip) { skip++; continue; }
            bool ok = false;
            if (code == 0xFA) { /* find: the 32-bit value b somewhere in RAM; the lines after it work relative to it */
                int s = -1;
                for (int k = 0; k < g_fa_n; k++) if (g_fa[k].line == i) s = k;
                if (s < 0 && g_fa_n < 16) { s = g_fa_n++; g_fa[s].line = i; look = true; }
                if (s >= 0) {
                    if (look) {
                        g_fa[s].ok = false;
                        for (uint32_t o = 0x100000; o < 0x300000; o += 4) {
                            uint32_t v;
                            memcpy(&v, ram + o, 4);
                            if (v == b) { g_fa[s].base = o; g_fa[s].ok = true; break; }
                        }
                    }
                    ok = g_fa[s].ok;
                    if (ok) { base = g_fa[s].base; base_ok = true; }
                }
            } else if (top == 9) {
                uint32_t want = ~(b >> 16) & 0x3FFu;
                ok = (ar_pad_bits() & want) == want;
            } else {
                uint32_t v;
                memcpy(&v, ram + (off & ~3u), 4);
                ok = top == 3 ? b > v : top == 4 ? b < v : top == 5 ? b == v : b != v;
            }
            if (!ok) skip = 1;
            continue;
        }
        if (skip) continue;
        if (code >= 0xFB && code <= 0xFD) { /* relative to what the last find found */
            uint32_t at = base + (a & 0xFFFFu);
            if (!base_ok || at + 4 > 0x400000) continue;
            if (code == 0xFB) { uint16_t v = (uint16_t)b; memcpy(ram + at, &v, 2); }       /* write 16 bits */
            else if (code == 0xFC) memcpy(ram + at, &b, 4);                                  /* write 32 bits */
            else { /* a Thumb BL here, to the address b */
                int32_t d = (int32_t)(b & 0x3FFFFFu) - (int32_t)((at + 4) & 0x3FFFFFu);
                uint32_t w = ((0xF800u | (((uint32_t)d >> 1) & 0x7FFu)) << 16) | (0xF000u | (((uint32_t)d >> 12) & 0x7FFu));
                memcpy(ram + at, &w, 4);
            }
            continue;
        }
        if (top == 0) { uint32_t v = b; memcpy(ram + (off & ~3u), &v, 4); }
        else if (top == 1) { uint16_t v = (uint16_t)b; memcpy(ram + (off & ~1u), &v, 2); }
        else if (top == 2) ram[off] = (uint8_t)b;
    }
}

/* Starts over each time: mGBA, for one, ignores the "enabled" flag, so a cheat is only ever turned off by leaving it out. */
static void apply_cheats(void) {
    pthread_mutex_lock(&g_cheat_lock);
    C.cheat_reset();
    free(g_ar);
    g_ar = NULL;
    g_ar_n = 0;
    g_fa_n = 0;
    for (int i = 0; i < g_cheat_count; i++) {
        if (g_cheat_ar) ar_load(g_cheats[i]);
        else C.cheat_set((unsigned)i, true, g_cheats[i]);
    }
    LOGI("%d cheats on", g_cheat_count);
    pthread_mutex_unlock(&g_cheat_lock);
}

static double now_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

/* Some cores (Mupen64Plus-Next's recompiler) leave the floating-point registers d8 to d15 changed, which a C function
 * must not do. Listing them as clobbered here makes the compiler save and restore them around the core's call. */
__attribute__((noinline)) static void run_core(void) {
    C.run();
    __asm__ volatile("" ::: "d8", "d9", "d10", "d11", "d12", "d13", "d14", "d15", "memory");
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
    g_cheat_ar = strstr(si.library_name, "melonDS") != NULL;

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
    if (C.set_controller_port_device) C.set_controller_port_device(0, RETRO_DEVICE_JOYPAD); /* some cores (Dolphin) leave the port empty until asked */
    loaded = true;
    sram_load();

    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    C.get_system_av_info(&av);
    g_fps = av.timing.fps > 1 ? av.timing.fps : 60.0;
    g_rate = av.timing.sample_rate > 8000 ? av.timing.sample_rate : 48000.0;
    g_aspect = av.geometry.aspect_ratio > 0 ? av.geometry.aspect_ratio
             : av.geometry.base_height ? (float)av.geometry.base_width / (float)av.geometry.base_height : 4.0f / 3.0f;
    g_win_w = (int)av.geometry.base_width;
    g_win_h = (int)av.geometry.base_height;
    if (g_hw_wanted) {
        if (!hw_init(&av.geometry)) goto done;
        if (g_hw.context_reset) g_hw.context_reset();
    } else {
        ANativeWindow_setBuffersGeometry(g_win, (int32_t)av.geometry.base_width, (int32_t)av.geometry.base_height, WINDOW_FORMAT_RGBX_8888);
    }
    LOGI("av: %ux%u aspect %.3f, %.2f fps, %.0f Hz", av.geometry.base_width, av.geometry.base_height, g_aspect, g_fps, g_rate);

    /* Cores that draw with OpenGL ES start their emulation thread on the first frames and refuse a state before that. */
    int pending_slot = g_start_slot;
    if (pending_slot >= 0 && !g_hw_on) { set_note(read_state(pending_slot) ? "resumed" : "no resume point"); pending_slot = -1; }

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
        run_core();
        if (g_cheat_ar) ar_run();
        g_run_s += now_s() - t_run;
        if (now_s() - t_run > 0.1) LOGI("retro_run took %.0f ms (frame %u)", (now_s() - t_run) * 1000.0, frames);
        frames++;
        shown++;
        if (pending_slot >= 0 && frames == 10) {
            set_note(read_state(pending_slot) ? "resumed" : "no resume point");
            pending_slot = -1;
        }
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
            static int reports;
            if (++reports % 10 == 1) LOGI("%s", g_status);
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
    if (g_hw_on && g_hw.context_destroy) g_hw.context_destroy();
    free(g_sram_shadow);
    g_sram_shadow = NULL;
    g_sram_size = 0;
    if (C.lib) {
        if (C.deinit) C.deinit();
        dlclose(C.lib);
    }
    hw_destroy();
    options_clear();
    g_hw_wanted = false;
    memset(&g_hw, 0, sizeof g_hw);
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
    {
        const char *core = strrchr(g_core_path, '/');
        char stem[128];
        snprintf(stem, sizeof stem, "%s", core ? core + 1 : "core");
        char *ext = strstr(stem, ".so");
        if (ext) *ext = 0;
        snprintf(g_opt_path, sizeof g_opt_path, "%s/%s.options", g_save_dir, stem);
    }

    g_win = ANativeWindow_fromSurface(env, surface);
    if (!g_win) return JNI_FALSE;
    g_win_w = g_win_h = 0;
    g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    g_start_slot = start_slot;
    atomic_store(&g_buttons, 0);
    atomic_store(&g_pressed, 0);
    for (int i = 0; i < 4; i++) atomic_store(&g_axes[i], 0);
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

/* A stick position: axis 0 left X, 1 left Y, 2 right X, 3 right Y; value -32768 to 32767 (down and right are positive). */
JNIEXPORT void JNICALL Java_app_lantern_Player_nativeAxis(JNIEnv *env, jclass cls, jint axis, jint value) {
    (void)env; (void)cls;
    if (axis < 0 || axis > 3) return;
    atomic_store(&g_axes[axis], value < -32768 ? -32768 : value > 32767 ? 32767 : value);
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

/* The core's options as "key|description|value1;value2;...|current index" strings (empty before the core lists them). */
JNIEXPORT jobjectArray JNICALL Java_app_lantern_Player_nativeOptions(JNIEnv *env, jclass cls) {
    jclass str = (*env)->FindClass(env, "java/lang/String");
    pthread_mutex_lock(&g_opt_lock);
    jobjectArray out = (*env)->NewObjectArray(env, g_opt_count, str, NULL);
    for (int i = 0; i < g_opt_count; i++) {
        char buf[2048];
        int n = snprintf(buf, sizeof buf, "%s\x01%s\x01", g_opts[i].key, g_opts[i].desc);
        for (int j = 0; j < g_opts[i].n && n < (int)sizeof buf - 80; j++)
            n += snprintf(buf + n, sizeof buf - (size_t)n, "%s%s", j ? "\x02" : "", g_opts[i].vals[j]);
        snprintf(buf + n, sizeof buf - (size_t)n, "\x01%d", g_opts[i].cur);
        jstring js = (*env)->NewStringUTF(env, buf);
        (*env)->SetObjectArrayElement(env, out, i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    pthread_mutex_unlock(&g_opt_lock);
    (void)cls;
    return out;
}

/* Sets one option (by key and the text of one of its values), keeps it for next time, and tells the core. */
JNIEXPORT jboolean JNICALL Java_app_lantern_Player_nativeSetOption(JNIEnv *env, jclass cls, jstring key, jstring value) {
    (void)cls;
    char k[128], v[128];
    copy_jstring(env, key, k, sizeof k);
    copy_jstring(env, value, v, sizeof v);
    jboolean ok = JNI_FALSE;
    if (strcmp(k, "lantern_screen_big") == 0) { /* Lantern's own, not the core's: see ds_compose */
        atomic_store(&g_ds_big, atoi(v));
        return JNI_TRUE;
    }
    pthread_mutex_lock(&g_opt_lock);
    for (int i = 0; i < g_opt_count && !ok; i++) {
        if (strcmp(g_opts[i].key, k) != 0) continue;
        int j = option_index(&g_opts[i], v);
        if (j < 0) break;
        g_opts[i].cur = j;
        options_save();
        atomic_store(&g_opt_changed, true);
        ok = JNI_TRUE;
    }
    pthread_mutex_unlock(&g_opt_lock);
    return ok;
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
