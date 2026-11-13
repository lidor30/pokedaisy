// The app's Nintendo DS cores behind a small C interface, which pokedaisy_jni.c calls when the loaded ROM is a DS
// cart (pk_nds_is_rom): melonDS (pk_nds.cpp) or DSperate (pk_nds_dsp.cpp, for devices too slow for melonDS), picked
// by pk_nds_set_core before pk_nds_init (pk_nds_core.cpp passes each call to the one that's up). One DS at a time,
// owned by the emu thread like the mGBA core; pk_nds_read is the only call other threads make, under
// pokedaisy_jni.c's core lock. Some calls only melonDS has (EXPAND, DS RESOLUTION, STEADY music, the game's own
// functions, DS CPU BOOST): on DSperate they do nothing and report off.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PK_NDS_W 256
#define PK_NDS_H 192

// Whether the file at path has a DS cart header (the Nintendo logo's CRC at 0x15C).
bool pk_nds_is_rom(const char* path);

#define PK_NDS_CORE_MELONDS 0
#define PK_NDS_CORE_DSPERATE 1
// The core the next pk_nds_init starts (PK_NDS_CORE_*; melonDS until told otherwise). Emu thread, no DS up.
void pk_nds_set_core(int core);
// The core that's up, or -1 with no DS game.
int pk_nds_core(void);

// Boots the ROM at romPath (FreeBIOS, generated firmware: no BIOS / firmware files) with the save at savePath
// (read if it exists, written each time the game saves; NULL = none), audio resampled to sampleRate.
bool pk_nds_init(const char* romPath, const char* savePath, int sampleRate);
void pk_nds_deinit(void);
bool pk_nds_active(void);

// GBA key bits 0-9 (A .. L, as MgbaCore.Key) plus X (bit 10) and Y (bit 11); 1 = held.
void pk_nds_set_keys(uint32_t keys);
// The stylus on the bottom screen at (x, y) in DS pixels, or lifted (down = false).
void pk_nds_touch(bool down, int x, int y);
void pk_nds_run_frame(void);

// Each screen's last frame, PK_NDS_W x PK_NDS_H, RGBA8888 bytes (mGBA's color_t layout). Emu thread, between frames.
uint32_t* pk_nds_top(void);
uint32_t* pk_nds_bottom(void);

// Up to maxFrames interleaved L/R s16 frames of audio from the last run frame; returns the frames copied.
int pk_nds_read_audio(int16_t* out, int maxFrames);
// Samples per emulated second the audio is resampled to (EmulatorEngine's vsync pacing).
void pk_nds_set_audio_rate(double rate);

// Writes a save the game made (or is still making) to the file. Returns whether one was pending.
bool pk_nds_flush_save(void);
// Runs frames until the game's save writes have been quiet for a while, at most maxFrames; returns the frames run.
int pk_nds_finish_save(int maxFrames);

bool pk_nds_save_state(const char* path);
bool pk_nds_load_state(const char* path);
// 1 = the save inside the state at path is the save file as it is now, 0 = it isn't, -1 = can't tell, 2 = the state is
// the other DS core's (it won't load: each core reads only its own).
int pk_nds_state_matches_save(const char* path);

// Reads len bytes at addr: main RAM (0x02000000-0x023FFFFF, mirrored as the DS does), shared WRAM, the ARM9's
// other memory through its bus, or the cart's ROM file at 0x08000000 + offset (DS carts aren't memory mapped).
// Any thread, under the caller's core lock.
void pk_nds_read(uint32_t addr, uint8_t* dst, int len);
// Writes main RAM only (false for any other address). Emu thread, between frames.
bool pk_nds_write(uint32_t addr, const uint8_t* src, int len);

// The field read's PK_FIELD_* / PK_NDS_FIELD_* bits for this frame (pk_nds_field.h); 0 for a cart it has no table for.
// Emu thread, between frames.
int pk_nds_field_bits(void);
// The game's own functions (pk_nds_call.h): posts PkNdsAction [action] with its four arguments for the next frame - 0 when the field isn't free,
// else the job's number - and how that job did (1 ran, 0 waiting, -1 never will). Emu thread, between frames.
int pk_nds_action(int action, const uint32_t args[4]);
int pk_nds_action_status(int seq);
// The job's results once it ran (PK_NDS_MAX_RESULTS words), false if it hasn't.
bool pk_nds_action_results(int seq, uint32_t* out);
// WILD ENCOUNTERS OFF: no random encounters (the field read holds the map's grass / surf rates at 0). Emu thread.
void pk_nds_set_wild_off(bool off);
/** DS LAYOUT floats the X menu: it comes back after a menu screen left by touch (the game closes it then). */
void pk_nds_set_keep_start_menu(bool on);
/** DS CPU BOOST: the ARM9 at twice the DS's clock (fewer of a game's own slow frames). Emu thread, between frames. */
void pk_nds_set_cpu_boost(bool on);
// Action Replay DS codes into melonDS's AREngine (it runs them on the ARM7's VBlank), replacing the ones before:
// [lengths] words of [words] per cheat, [nCheats] cheats (0 = none). The count loaded; -1 with no DS game. Emu thread.
int pk_nds_set_cheats(const uint32_t* words, int nWords, const int32_t* lengths, int nCheats);
// The cart's grass / surf rates by map header id, [map, grass, surf] x n (pk_nds_field_set_rates). Emu thread.
void pk_nds_set_wild_rates(const int32_t* triples, int n);

// STEADY FF music (pk_nds_music.h). The song the game is playing (its sound system's current BGM id; 0 = none or a fade
// to the next), -1 for a cart whose sound system isn't mapped. Emu thread.
int pk_nds_bgm(void);
// Whether the song can be copied now, and its player's state (pk_nds_bgm_settle in pk_nds_field.h): -1 not yet (a
// fade runs, or the song's player is paused / missing), else a state a running copy compares (another = copy again).
int pk_nds_bgm_state(void);
// Starts the music copy from the game as it is now; false if it couldn't. Emu thread, between frames.
bool pk_nds_music_start(void);
// One frame (1/60 s) of the copy's audio (stereo) into out, at most maxFrames; returns the frames written.
int pk_nds_music_read(int16_t* out, int maxFrames);
// Fades the copy out over frames frames (the game fading its song before the next).
void pk_nds_music_fade_out(int frames);
// Stops the copy (kept for the next start).
void pk_nds_music_stop(void);

// EXPAND (pk_nds_wide.h) on or off: the overworld's 3D drawn past the screen's edges (as far as the window needs). Emu thread.
void pk_nds_wide_enable(bool on);
// Whether the coming frame / the one after it are shown (fast-forward runs several a refresh and shows the last): an
// unseen frame skips the screens' conversion and EXPAND's composing, and EXPAND's slices are drawn only before a seen
// one. Both true by default. Emu thread, before pk_nds_run_frame.
void pk_nds_set_draw(bool draw, bool drawNext);
// Whether the last pk_nds_run_frame drew its frame: not when pk_nds_set_draw said so, nor when the frame before it
// promised it wouldn't be seen (drawNext false) - its 3D was never rendered. An undrawn frame leaves the screens and
// EXPAND's frame as the last drawn one left them. Emu thread.
bool pk_nds_frame_drawn(void);
// After pk_nds_run_frame: the window [x, x + w) x [y, y + h) of the last frame into out (w x h RGBA words) - the
// margins black off the overworld. Returns false when EXPAND is off. Emu thread.
bool pk_nds_wide_draw(uint32_t* out, int x, int y, int w, int h);
// The camera's scene (WideCamera.SCENE_*) from the field read: field, text box, a menu screen, off (a battle, no map).
int pk_nds_wide_scene(void);
/** Both engines' master brightness while it fades towards black or white (mode 1 / 2): its factor 0-16, engine A (the top
 * screen) in bits 0-7, B in bits 8-15; 0 = no fade. The games fade between two screens this way. Emu thread. */
int pk_nds_fade(void);
// The camera's window (pkWideSetView), so the slices draw only what it shows.
void pk_nds_wide_window(int x, int y, int w, int h);

// DS RESOLUTION (pk_nds_gl.h): the 3D at [scale] times the DS's (2-8, on the GPU) or the DS's own (1, the software
// renderer, pixel for pixel). Taken before the next frame runs. Emu thread.
void pk_nds_set_scale(int scale);
// The scale in effect (1: software - asked for, or the GPU path isn't available).
int pk_nds_scale(void);
// Whether the GPU path can run here: -1 not tried yet, 0 no, 1 yes.
int pk_nds_gpu_supported(void);
// Whether the last frame went out through the GPU path (into the window pk_nds_gl_set_window gave it); otherwise the
// screen shows pk_nds_top (in GPU mode its 1x copy). Emu thread.
bool pk_nds_presented(void);
// The GPU path's frame presented at [timestamp]: its window (x, y, w, h in DS pixels) and texel size into out[6].
bool pk_nds_gpu_frame_info(int64_t timestamp, int32_t out[6]);
// The game screen's window for the GPU path's frames (EmulatorView's SurfaceTexture), or NULL; takes the reference.
// Any thread, with or without a DS up.
struct ANativeWindow;
void pk_nds_set_window(struct ANativeWindow* window);

// The cart header's 4-letter game code (CPUE for Platinum) into out[5], and its revision byte.
bool pk_nds_rom_code(char out[5]);
int pk_nds_rom_revision(void);
uint32_t pk_nds_rom_size(void);
// The ARM9's data TCM (16 KB), RetroAchievements' $1000000; NULL with no DS up. Emu thread.
const uint8_t* pk_nds_dtcm(void);

#ifdef __cplusplus
}
#endif
