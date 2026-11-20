// The Windows DS core: the app's own melonDS engine (app/src/main/cpp/nds/pk_nds.cpp, the same code the Android build
// runs) behind libretro. What the engine gives the app it gives here instead: EXPAND's wide overworld, the game's own
// functions (PC, FLY, ...), saves written the app's way, and melonDS states.
//
// The picture is the screen the game is on, in DS pixels, and the frontend scales it to the window:
//   - EXPAND on (pokedaisy_aspect wide, the host's WIDESCREEN) and the field on show: the window is the whole canvas, the
//     top screen in the middle and EXPAND's margins around it, as far as the window reaches (pokedaisy_panel, the
//     frontend's window, is read every frame, so resizing and fullscreen re-lay it).
//   - otherwise the two screens one above the other (top first), 256 x 384.
// R2 shows the bottom screen (the touch screen) by itself, at the game's own size, until it's pressed again.
#include "libretro.h"
#include "pd_ds_overlay.h"
#include "pk_nds_cores.h"
#include "pk_nds_field.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

retro_environment_t env_cb;
retro_video_refresh_t video_cb;
retro_audio_sample_batch_t audio_cb;
retro_input_poll_t poll_cb;
retro_input_state_t input_cb;

constexpr int SAMPLE_RATE = 48000;
constexpr size_t STATE_MAX = 32u << 20;   // melonDS's states are ~8 MB: room to read one back
size_t stateSize = 0;                     // the size the first state of this game came out at

// The geometry of this frame (compose_layout): canvas in DS pixels, the top screen's corner in it, and which of the two
// views is on show.
struct Layout {
    enum Mode { Stacked, Wide, Touch } mode = Stacked;
    int w = PK_NDS_W, h = PK_NDS_H * 2;
    int x = 0, y = 0;   // the top screen's corner in a Wide canvas
} layout;
int panelW = 512, panelH = 384;
bool wideAspect = true;
bool touchView = false;
bool padCombo = false;
bool wideEnabled = false;
bool haveFrame = false;
int presentedW = PK_NDS_W, presentedH = PK_NDS_H * 2;   // the frame the frontend has (the overlay's size while it shows)
std::vector<uint32_t> canvas;   // the frame as it goes out (XRGB8888)
std::vector<uint32_t> scratch;  // a wide view asked for while the camera may have stopped
std::vector<uint32_t> lastWide; // the last wide frame, kept through a fade
bool lastWideValid = false;
long frameNo = 0;
std::string saveDir;
std::string savePath;
std::string romPath;

bool option(const char* key, std::string& value)
{
    retro_variable var {key, nullptr};
    if (!env_cb || !env_cb(RETRO_ENVIRONMENT_GET_VARIABLE, &var) || !var.value) return false;
    value = var.value;
    return true;
}

// The save file the host keeps for a ROM: saves/<the ROM's own file name>.sav (extension kept), so the one the host
// loads into the save memory is the one the engine reads and writes.
std::string file_name(const std::string& path)
{
    const size_t slash = path.find_last_of("/\\");
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

void read_options()
{
    std::string value;
    // The window itself first: the host's PANEL preset names a screen to lay out for (a handheld's), and a canvas laid
    // out for it inside a differently shaped window would sit in bars. Falls back to the panel for a frontend without it.
    const bool haveWindow = option("pokedaisy_window", value) || option("pokedaisy_panel", value);
    if (haveWindow) {
        int w = 0, h = 0;
        if (sscanf(value.c_str(), "%dx%d", &w, &h) == 2 && w > 0 && h > 0) {
            panelW = w;
            panelH = h;
        }
    }
    // The host's WIDESCREEN sends "expand" / "original" (libretro/pd_libretro.c's name for it).
    if (option("pokedaisy_aspect", value))
        wideAspect = value != "original" && value != "4:3" && value != "0";
}

// Where this frame goes: EXPAND's canvas when it can be wide (at least 3:2 and the field on show), the two screens when
// it can't, and the bottom screen alone while R2 holds the view there.
void compose_layout()
{
    read_options();
    layout = Layout {};
    if (touchView) {
        layout.mode = Layout::Touch;
        layout.w = PK_NDS_W;
        layout.h = PK_NDS_H;
        return;
    }
    if (wideAspect && (long long) panelW * 10 >= (long long) panelH * 15) {
        // EXPAND extends the field sideways, not above and below the screen: the canvas is exactly the screen's height
        // and as wide as the window's shape makes it, so every margin it has is one the 3D fills. The width follows the
        // window's own aspect, so the frontend has nothing left to letterbox either.
        layout.mode = Layout::Wide;
        layout.w = std::max(PK_NDS_W, (int) llround((double) PK_NDS_H * panelW / panelH));
        layout.h = PK_NDS_H;
        layout.x = (layout.w - PK_NDS_W) / 2;
        layout.y = 0;
        return;
    }
    layout.mode = Layout::Stacked;
    layout.w = PK_NDS_W;
    layout.h = PK_NDS_H * 2;
}

inline uint32_t xrgb(uint32_t rgba)
{
    return 0xFF000000u | ((rgba & 0xFFu) << 16) | (rgba & 0xFF00u) | ((rgba >> 16) & 0xFFu);
}

// Whether the wide picture is held through this scene instead of the screen standing alone: a fade, or the field
// stopped for a warp (a door, a leader change) rather than a battle.
bool hold_wide(int scene)
{
    if (scene == 5) return true;
    return scene == 4 && !(pk_ndsm_field_bits() & PK_NDS_FIELD_BATTLE);
}

// A full-screen card on the game's screen (the area name, the season): black on nearly every pixel, with its text on
// top. The picture then covers the whole window, as the card does on a DS, instead of sitting in a frame of world.
bool screen_is_card(const uint32_t* screen)
{
    if (!screen) return false;
    int dark = 0, sampled = 0;
    for (int y = 0; y < PK_NDS_H; y += 4)
        for (int x = 0; x < PK_NDS_W; x += 4, sampled++) {
            const uint32_t pixel = screen[(size_t) y * PK_NDS_W + x];
            if (std::max({pixel & 0xFFu, (pixel >> 8) & 0xFFu, (pixel >> 16) & 0xFFu}) < 16) dark++;
        }
    return dark * 100 >= sampled * 90;
}

// Whether the wide view has anything to show (the camera stopped: it is all black then).
bool has_picture(const std::vector<uint32_t>& pixels)
{
    for (const uint32_t pixel : pixels)
        if (pixel & 0x00FFFFFFu) return true;
    return false;
}

// Dims a frame by the game's own fade: k of 16, as the screen fades to black (a door, a warp).
void fade_frame(std::vector<uint32_t>& pixels, int k)
{
    if (k <= 0) return;
    if (k > 16) k = 16;
    const uint32_t f = (uint32_t) (16 - k);
    for (auto& pixel : pixels)
        pixel = 0xFF000000u | ((((pixel >> 16) & 0xFFu) * f / 16) << 16)
                             | ((((pixel >> 8) & 0xFFu) * f / 16) << 8)
                             | (((pixel & 0xFFu) * f) / 16);
}

void blit(uint32_t* dst, int dstW, int dstH, int x, int y, const uint32_t* src, int w, int h)
{
    if (!src) return;
    for (int row = 0; row < h; row++) {
        const int outY = y + row;
        if (outY < 0 || outY >= dstH) continue;
        const int from = std::max(0, -x), to = std::min(w, dstW - x);
        for (int col = from; col < to; col++) dst[(size_t) outY * dstW + x + col] = xrgb(src[(size_t) row * w + col]);
    }
}

void compose()
{
    canvas.assign((size_t) layout.w * layout.h, 0xFF000000u);
    const uint32_t* top = pk_ndsm_top();
    const uint32_t* bottom = pk_ndsm_bottom();
    switch (layout.mode) {
    case Layout::Touch:
        blit(canvas.data(), layout.w, layout.h, 0, 0, bottom, PK_NDS_W, PK_NDS_H);
        break;
    case Layout::Wide: {
        // EXPAND draws the whole canvas (the screen's middle and the margins around it) when it has the field's camera
        // this frame (scene 0-2: the field, a text box over it, the extra overlays).
        const int scene = pk_ndsm_wide_scene();
        bool wide = false;
        if (scene <= 2) {
            wide = pk_ndsm_wide_draw(canvas.data(), -layout.x, -layout.y, layout.w, layout.h);
        } else if (hold_wide(scene)) {
            // A fade or a warp (the field stops for a door, a leader change): the camera may have stopped, so the last
            // wide frame is kept and faded with the screen rather than falling back to the small screen in a black frame.
            scratch.resize(canvas.size());
            if (pk_ndsm_wide_draw(scratch.data(), -layout.x, -layout.y, layout.w, layout.h) && has_picture(scratch)) {
                canvas = scratch;
                wide = true;
            }
        }
        // PD_DS_SCENE: the scene number and whether EXPAND had the picture, when either changes.
        static const bool sceneLog = std::getenv("PD_DS_SCENE") != nullptr;
        static int lastScene = -1, lastWideScene = -1;
        if (sceneLog && (scene != lastScene || (int) wide != lastWideScene))
            fprintf(stderr, "ds scene: frame %ld scene %d wide %d\n", frameNo, scene, (int) wide);
        lastScene = scene;
        lastWideScene = wide;
        if (wide) {
            for (auto& pixel : canvas) pixel = xrgb(pixel);
            if (hold_wide(scene)) fade_frame(canvas, pk_ndsm_fade() & 0xFF);
            if (screen_is_card(top)) {
                std::fill(canvas.begin(), canvas.end(), 0xFF000000u);
                blit(canvas.data(), layout.w, layout.h, layout.x, layout.y, top, PK_NDS_W, PK_NDS_H);
            } else {
                lastWide = canvas;
                lastWideValid = true;
            }
        } else if (hold_wide(scene) && lastWideValid && lastWide.size() == canvas.size()) {
            canvas = lastWide;
            fade_frame(canvas, pk_ndsm_fade() & 0xFF);
        } else {
            blit(canvas.data(), layout.w, layout.h, layout.x, layout.y, top, PK_NDS_W, PK_NDS_H);
        }
        break;
    }
    case Layout::Stacked:
        blit(canvas.data(), layout.w, layout.h, 0, 0, top, PK_NDS_W, PK_NDS_H);
        blit(canvas.data(), layout.w, layout.h, 0, PK_NDS_H, bottom, PK_NDS_W, PK_NDS_H);
        break;
    }
    haveFrame = true;
    // The companion or the quick menu over it: the overlay's canvas at twice the game's size. The HUD goes on the top
    // screen's own pixels first, so the overlay is told where they sit in this picture.
    int outW = layout.w, outH = layout.h;
    overlay_view(layout.x, layout.y, layout.mode != Layout::Touch);
    overlay_compose(canvas, outW, outH);
    presentedW = outW;
    presentedH = outH;
    video_cb(canvas.data(), outW, outH, outW * (int) sizeof(uint32_t));
}

// The pointer as the game's touch screen takes it: where it is on the canvas, in DS pixels, if that is the screen shown.
// [overlayOnly]: the companion has the picture, so the pointer is only the overlay's (the game gets no touch at all).
void update_touch(bool overlayOnly)
{
    // libretro's pointer is normalised over the frame (-0x8000 .. 0x7FFF both ways): its place on the canvas first.
    const int16_t nx = input_cb(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_X);
    const int16_t ny = input_cb(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_Y);
    bool down = input_cb(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED) != 0;
    // The overlay first, where its own picture is what the frontend shows: a click on the HUD or the quick menu.
    if (overlay_pointer((int) ((long) (nx + 0x7FFF) * presentedW / 0xFFFE),
                        (int) ((long) (ny + 0x7FFF) * presentedH / 0xFFFE), down)) {
        pk_ndsm_touch(false, 0, 0);
        return;
    }
    if (overlayOnly) {
        pk_ndsm_touch(false, 0, 0);
        return;
    }
    int px = (int) ((long) (nx + 0x7FFF) * layout.w / 0xFFFE);
    int py = (int) ((long) (ny + 0x7FFF) * layout.h / 0xFFFE);
    int x = px, y = py;
    switch (layout.mode) {
    case Layout::Touch:
        break;
    case Layout::Wide:
        x = px - layout.x;
        y = py - layout.y;
        break;
    case Layout::Stacked:
        y = py - PK_NDS_H;   // the bottom screen is the second one
        break;
    }
    down = down && x >= 0 && x < PK_NDS_W && y >= 0 && y < PK_NDS_H;
    // PD_DS_TOUCH: the taps as the game gets them, for a pointer that can't be felt on the window.
    static const bool touchLog = std::getenv("PD_DS_TOUCH") != nullptr;
    if (touchLog && down) fprintf(stderr, "ds touch: %d,%d\n", x, y);
    pk_ndsm_touch(down, x, y);
}

// Every pad button in libretro's ids: what the overlay works in (the DS mask below only has the console's).
uint32_t read_input_mask()
{
    uint32_t keys = 0;
    for (unsigned id = RETRO_DEVICE_ID_JOYPAD_B; id <= RETRO_DEVICE_ID_JOYPAD_R3; ++id)
        if (input_cb(0, RETRO_DEVICE_JOYPAD, 0, id)) keys |= 1u << id;
    return keys;
}
uint32_t lastInputMask = 0;

// The overlay's keys, from its libretro ids to the engine's DS bits (bit 0 A, 1 B, 2 SELECT, ...).
uint32_t overlay_to_ds(uint32_t libretro)
{
    static const struct { unsigned lr, ds; } map[] = {
        {RETRO_DEVICE_ID_JOYPAD_A, 0}, {RETRO_DEVICE_ID_JOYPAD_B, 1},
        {RETRO_DEVICE_ID_JOYPAD_SELECT, 2}, {RETRO_DEVICE_ID_JOYPAD_START, 3},
        {RETRO_DEVICE_ID_JOYPAD_RIGHT, 4}, {RETRO_DEVICE_ID_JOYPAD_LEFT, 5},
        {RETRO_DEVICE_ID_JOYPAD_UP, 6}, {RETRO_DEVICE_ID_JOYPAD_DOWN, 7},
        {RETRO_DEVICE_ID_JOYPAD_R, 8}, {RETRO_DEVICE_ID_JOYPAD_L, 9},
        {RETRO_DEVICE_ID_JOYPAD_X, 10}, {RETRO_DEVICE_ID_JOYPAD_Y, 11},
    };
    uint32_t keys = 0;
    for (const auto& key : map)
        if (libretro & (1u << key.lr)) keys |= 1u << key.ds;
    return keys;
}

uint32_t read_keys()
{
    uint32_t keys = 0;
    static const struct { unsigned pad; unsigned ds; } map[] = {
        {RETRO_DEVICE_ID_JOYPAD_A, 0}, {RETRO_DEVICE_ID_JOYPAD_B, 1},
        {RETRO_DEVICE_ID_JOYPAD_SELECT, 2}, {RETRO_DEVICE_ID_JOYPAD_START, 3},
        {RETRO_DEVICE_ID_JOYPAD_RIGHT, 4}, {RETRO_DEVICE_ID_JOYPAD_LEFT, 5},
        {RETRO_DEVICE_ID_JOYPAD_UP, 6}, {RETRO_DEVICE_ID_JOYPAD_DOWN, 7},
        {RETRO_DEVICE_ID_JOYPAD_R, 8}, {RETRO_DEVICE_ID_JOYPAD_L, 9},
        {RETRO_DEVICE_ID_JOYPAD_X, 10}, {RETRO_DEVICE_ID_JOYPAD_Y, 11},
    };
    for (const auto& key : map)
        if (input_cb(0, RETRO_DEVICE_JOYPAD, 0, key.pad)) keys |= 1u << key.ds;
    return keys;
}

std::string stateFile()
{
    const std::string dir = saveDir.empty() ? std::string(".") : saveDir;
    return dir + "/pokedaisy-ds.state";
}

bool read_file(const std::string& path, std::vector<uint8_t>& out)
{
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    const long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(n > 0 ? (size_t) n : 0);
    const bool ok = n <= 0 || fread(out.data(), 1, (size_t) n, f) == (size_t) n;
    fclose(f);
    return ok;
}

bool write_file(const std::string& path, const void* data, size_t len)
{
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    const bool ok = !len || fwrite(data, 1, len, f) == len;
    fclose(f);
    return ok;
}

} // namespace

RETRO_API void retro_set_environment(retro_environment_t cb) { env_cb = cb; }
RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb) { video_cb = cb; }
RETRO_API void retro_set_audio_sample(retro_audio_sample_t) {}
RETRO_API void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_cb = cb; }
RETRO_API void retro_set_input_poll(retro_input_poll_t cb) { poll_cb = cb; }
RETRO_API void retro_set_input_state(retro_input_state_t cb) { input_cb = cb; }

RETRO_API void retro_init(void) {}
RETRO_API void retro_deinit(void) {}

RETRO_API unsigned retro_api_version(void) { return RETRO_API_VERSION; }

RETRO_API void retro_get_system_info(struct retro_system_info* info)
{
    memset(info, 0, sizeof *info);
    info->library_name = "PokeDaisy DS";
    info->library_version = "1.0";
    info->valid_extensions = "nds";
    info->need_fullpath = true;
    info->block_extract = true;
}

RETRO_API void retro_get_system_av_info(struct retro_system_av_info* info)
{
    memset(info, 0, sizeof *info);
    info->geometry.base_width = PK_NDS_W;
    info->geometry.base_height = PK_NDS_H * 2;
    info->geometry.max_width = 2048;
    info->geometry.max_height = 2048;
    info->geometry.aspect_ratio = 0.0f;
    info->timing.fps = 60.0;
    info->timing.sample_rate = SAMPLE_RATE;
}

RETRO_API void retro_set_controller_port_device(unsigned, unsigned) {}

RETRO_API void retro_reset(void)
{
    overlay_reset();
    pk_ndsm_deinit();
    wideEnabled = false;
    lastWideValid = false;
    haveFrame = false;
    stateSize = 0;
    if (!romPath.empty() && pk_ndsm_init(romPath.c_str(), savePath.c_str(), SAMPLE_RATE)) {
        overlay_load(romPath.c_str(), saveDir.c_str());
        compose_layout();
    }
}

RETRO_API void retro_run(void)
{
    if (!pk_ndsm_active()) return;
    frameNo++;
    compose_layout();
    if (poll_cb) poll_cb();
    // The overlay first: L2 opens the companion (a tap toggles it, held for a moment it opens the quick menu), and while
    // it shows it takes the pad, so the game doesn't walk away underneath it.
    const uint32_t heldInput = read_input_mask();
    const uint32_t eaten = overlay_keys(heldInput & ~lastInputMask, heldInput);
    lastInputMask = heldInput;
    pk_ndsm_set_keys(read_keys() & ~overlay_to_ds(eaten));
    overlay_tick(pk_ndsm_field_bits());
    const bool showing = overlay_open();
    // R2: the bottom screen on show, at its own size, until pressed again (the touch screen, for a game that asks for it).
    // The host's DS hotkeys need SELECT held (its modifier), so R2 alone is the core's.
    const bool combo = !showing && input_cb(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R2) != 0;
    if (combo && !padCombo) {
        touchView = !touchView;
        compose_layout();
    }
    padCombo = combo;
    // The wheel the frontend reports (the mouse's, in notches): the companion's lists scroll with it.
    const int wheelUp = input_cb(0, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_WHEELUP);
    const int wheelDown = input_cb(0, RETRO_DEVICE_MOUSE, 0, RETRO_DEVICE_ID_MOUSE_WHEELDOWN);
    if (wheelUp || wheelDown) overlay_wheel(wheelUp - wheelDown);
    update_touch(showing);
    pk_ndsm_set_draw(true, true);
    // EXPAND: the engine's own widescreen, on while the picture is laid out for a wide window. Without this the 3D is
    // only drawn for the screen and the margins would stay black.
    const bool wantWide = layout.mode == Layout::Wide;
    if (wantWide != wideEnabled) {
        pk_ndsm_wide_enable(wantWide);
        wideEnabled = wantWide;
    }
    if (wantWide) pk_ndsm_wide_window(-layout.x, -layout.y, layout.w, layout.h);
    pk_ndsm_run_frame();
    int16_t audio[2048 * 2];
    const int frames = pk_ndsm_read_audio(audio, 2048);
    if (frames > 0 && audio_cb) audio_cb(audio, frames);
    // PD_DS_AUDIO: a line a second of the audio the engine hands out, for a build without a sound card to check it.
    static const bool audioLog = std::getenv("PD_DS_AUDIO") != nullptr;
    static int audioRuns = 0;
    if (audioLog && (++audioRuns % 60) == 0) fprintf(stderr, "ds audio: %d frames\n", frames);
    compose();
}

RETRO_API size_t retro_serialize_size(void)
{
    if (!pk_ndsm_active()) return 0;
    // melonDS's states are variable length (they grow as sections are written), so the size a frontend allocates comes
    // from one taken once: the same game's stays the same.
    if (!stateSize) {
        const std::string path = stateFile();
        if (pk_ndsm_save_state(path.c_str())) {
            FILE* f = fopen(path.c_str(), "rb");
            if (f) {
                fseek(f, 0, SEEK_END);
                const long n = ftell(f);
                fclose(f);
                if (n > 0 && (size_t) n <= STATE_MAX) stateSize = (size_t) n;
            }
            remove(path.c_str());
        }
    }
    return stateSize ? stateSize : STATE_MAX;
}

RETRO_API bool retro_serialize(void* data, size_t size)
{
    if (!pk_ndsm_active() || !size || size > STATE_MAX) return false;
    const std::string path = stateFile();
    if (!pk_ndsm_save_state(path.c_str())) return false;
    std::vector<uint8_t> bytes;
    if (!read_file(path, bytes) || bytes.size() > size) {
        remove(path.c_str());
        return false;
    }
    remove(path.c_str());
    memcpy(data, bytes.data(), bytes.size());
    memset((uint8_t*) data + bytes.size(), 0, size - bytes.size());
    return true;
}

RETRO_API bool retro_unserialize(const void* data, size_t size)
{
    if (!pk_ndsm_active() || !data || size > STATE_MAX) return false;
    const std::string path = stateFile();
    if (!write_file(path, data, size)) return false;
    overlay_reset();
    const bool ok = pk_ndsm_load_state(path.c_str());
    remove(path.c_str());
    return ok;
}

RETRO_API void* retro_get_memory_data(unsigned id)
{
    if (id == RETRO_MEMORY_SAVE_RAM) return const_cast<uint8_t*>(pk_ndsm_save_data());
    return nullptr;
}

RETRO_API size_t retro_get_memory_size(unsigned id)
{
    return id == RETRO_MEMORY_SAVE_RAM ? pk_ndsm_save_length() : 0;
}

RETRO_API bool retro_load_game(const struct retro_game_info* game)
{
    if (!game || !game->path) return false;
    const char* dir = nullptr;
    if (env_cb && env_cb(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &dir) && dir) saveDir = dir;
    romPath = game->path;
    savePath = (saveDir.empty() ? std::string(".") : saveDir) + "/" + file_name(game->path) + ".sav";

    retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
    if (env_cb) env_cb(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);

    read_options();
    wideEnabled = false;
    touchView = false;
    padCombo = false;
    lastWideValid = false;
    frameNo = 0;
    stateSize = 0;
    if (!pk_ndsm_init(game->path, savePath.c_str(), SAMPLE_RATE)) return false;
    // The host loads the .sav into the save memory it reads here; the engine has read the same file at boot.
    overlay_load(game->path, saveDir.c_str());
    compose_layout();
    return true;
}

RETRO_API void retro_unload_game(void)
{
    overlay_reset();
    pk_ndsm_flush_save();
    pk_ndsm_deinit();
    haveFrame = false;
    romPath.clear();
}

RETRO_API unsigned retro_get_region(void) { return RETRO_REGION_NTSC; }

RETRO_API bool retro_load_game_special(unsigned, const struct retro_game_info*, size_t) { return false; }

RETRO_API void retro_cheat_reset(void) {}

RETRO_API void retro_cheat_set(unsigned, bool, const char*) {}
