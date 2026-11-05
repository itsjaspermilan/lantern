/*
 * Lantern test core: a libretro core that needs no game and no BIOS, used to check the player.
 *   video  320x240 at 60 fps: a scrolling gradient, a progress bar, a square you move with the D-pad
 *   audio  48 kHz sine, 440 Hz, or 880 Hz while A (right face button) is held
 *   input  the top row shows 16 lights, one per RetroPad button, lit while pressed
 *   state  save states cover the square, the frame counter and the tone phase
 * Open an empty file ending in .test to "play" it.
 */
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "libretro.h"

#define W 320
#define H 240
#define RATE 48000
#define FPS 60
#define AUDIO_FRAMES (RATE / FPS)

static retro_environment_t env_cb;
static retro_video_refresh_t video_cb;
static retro_audio_sample_batch_t audio_batch_cb;
static retro_input_poll_t poll_cb;
static retro_input_state_t input_cb;

static uint32_t fb[W * H];
static int16_t audio[AUDIO_FRAMES * 2];

struct state {
    int32_t x, y;
    uint32_t frame;
    float phase;
};
static struct state st;

static void reset_state(void) {
    st.x = 148;
    st.y = 108;
    st.frame = 0;
    st.phase = 0.f;
}

void retro_set_environment(retro_environment_t cb) {
    env_cb = cb;
    bool no_game = true;
    cb(RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME, &no_game);
}
void retro_set_video_refresh(retro_video_refresh_t cb) { video_cb = cb; }
void retro_set_audio_sample(retro_audio_sample_t cb) { (void)cb; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_batch_cb = cb; }
void retro_set_input_poll(retro_input_poll_t cb) { poll_cb = cb; }
void retro_set_input_state(retro_input_state_t cb) { input_cb = cb; }

void retro_init(void) { reset_state(); }
void retro_deinit(void) {}
unsigned retro_api_version(void) { return RETRO_API_VERSION; }

void retro_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof(*info));
    info->library_name = "Lantern Test Core";
    info->library_version = "0.1";
    info->valid_extensions = "test";
    info->need_fullpath = false;
    info->block_extract = false;
}

void retro_get_system_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = W;
    info->geometry.base_height = H;
    info->geometry.max_width = W;
    info->geometry.max_height = H;
    info->geometry.aspect_ratio = 4.0f / 3.0f;
    info->timing.fps = FPS;
    info->timing.sample_rate = RATE;
}

void retro_set_controller_port_device(unsigned port, unsigned device) { (void)port; (void)device; }
void retro_reset(void) { reset_state(); }

bool retro_load_game(const struct retro_game_info *game) {
    (void)game;
    enum retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
    if (!env_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt)) return false;
    reset_state();
    return true;
}
bool retro_load_game_special(unsigned type, const struct retro_game_info *info, size_t num) {
    (void)type; (void)info; (void)num;
    return false;
}
void retro_unload_game(void) {}
unsigned retro_get_region(void) { return RETRO_REGION_NTSC; }

static void rect(int x0, int y0, int w, int h, uint32_t color) {
    for (int y = y0; y < y0 + h; y++) {
        if (y < 0 || y >= H) continue;
        for (int x = x0; x < x0 + w; x++) {
            if (x >= 0 && x < W) fb[y * W + x] = color;
        }
    }
}

void retro_run(void) {
    poll_cb();
    bool down[16];
    for (unsigned i = 0; i < 16; i++) down[i] = input_cb(0, RETRO_DEVICE_JOYPAD, 0, i) != 0;

    if (down[RETRO_DEVICE_ID_JOYPAD_UP]) st.y -= 2;
    if (down[RETRO_DEVICE_ID_JOYPAD_DOWN]) st.y += 2;
    if (down[RETRO_DEVICE_ID_JOYPAD_LEFT]) st.x -= 2;
    if (down[RETRO_DEVICE_ID_JOYPAD_RIGHT]) st.x += 2;
    if (st.x < 0) st.x = 0;
    if (st.y < 12) st.y = 12;
    if (st.x > W - 24) st.x = W - 24;
    if (st.y > H - 28) st.y = H - 28;
    bool invert = down[RETRO_DEVICE_ID_JOYPAD_B];

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            uint32_t r = (uint32_t)(x + st.frame) & 255, g = (uint32_t)(y * 255 / H), b = (st.frame * 2) & 255;
            if (invert) { r ^= 255; g ^= 255; b ^= 255; }
            fb[y * W + x] = (r << 16) | (g << 8) | b;
        }
    }
    for (int i = 0; i < 16; i++) rect(8 + i * 19, 6, 16, 10, down[i] ? 0xFFFFFFFFu : 0xFF202020u);
    rect((int)(st.frame % W), H - 8, 8, 8, 0xFFFFFFFFu);
    rect(st.x - 1, st.y - 1, 26, 26, 0xFF000000u);
    rect(st.x, st.y, 24, 24, 0xFFFFFFFFu);
    video_cb(fb, W, H, W * sizeof(uint32_t));

    float freq = down[RETRO_DEVICE_ID_JOYPAD_A] ? 880.f : 440.f;
    for (int i = 0; i < AUDIO_FRAMES; i++) {
        int16_t s = (int16_t)(sinf(st.phase) * 6000.f);
        st.phase += 6.2831853f * freq / RATE;
        if (st.phase > 6.2831853f) st.phase -= 6.2831853f;
        audio[i * 2] = s;
        audio[i * 2 + 1] = s;
    }
    audio_batch_cb(audio, AUDIO_FRAMES);
    st.frame++;
}

size_t retro_serialize_size(void) { return sizeof(st); }
bool retro_serialize(void *data, size_t size) {
    if (size < sizeof(st)) return false;
    memcpy(data, &st, sizeof(st));
    return true;
}
bool retro_unserialize(const void *data, size_t size) {
    if (size < sizeof(st)) return false;
    memcpy(&st, data, sizeof(st));
    return true;
}

void retro_cheat_reset(void) {}
void retro_cheat_set(unsigned index, bool enabled, const char *code) { (void)index; (void)enabled; (void)code; }
void *retro_get_memory_data(unsigned id) { (void)id; return NULL; }
size_t retro_get_memory_size(unsigned id) { (void)id; return 0; }
