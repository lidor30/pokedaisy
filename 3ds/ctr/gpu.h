// The screens, drawn by the GPU (citro3d / citro2d): the game frame and the
// two canvases are written into textures by the CPU (in the GPU's tiled
// order) and drawn as quads. The smooth screen modes use mGBA's own "sharp
// bilinear" look: the frame at 2x nearest, sampled linearly down to size.
#ifndef GPU_H
#define GPU_H

#include <stdbool.h>
#include <stdint.h>

#define GPU_GAME_STRIDE 240 // the game buffer's row length in pixels

bool gpu_init(void);
void gpu_exit(void);

// mGBA's video buffer: 32-bit pixels (0xXXBBGGRR), GPU_GAME_STRIDE x 160, in linear memory.
uint32_t* gpu_game_buffer(void);
// Two buffers in turn, so the draw thread reads a frame where it lies while the next one is drawn. Only for a GBA game:
// its renderer redraws every line of a new buffer, a Game Boy's leaves a blank screen's alone. No draw thread: one.
void gpu_double_game(bool on);
// The colour beside the game's picture (0 = black): a Game Boy game's is white.
void gpu_set_bars(uint32_t rgb);
// A frame is whole and is what's shown next; the buffer the next one goes in.
uint32_t* gpu_frame_done(void);
// The core's picture size: 240 x 160, a Game Boy's 160 x 144.
void gpu_set_game_size(int w, int h);
// New canvases (0xRRGGBB): the bottom screen's 320x240, the top's 400x240.
void gpu_set_bottom(const uint32_t* px);
void gpu_set_top(const uint32_t* px);

// EXPAND's window for this frame (w x h, 0xXXBBGGRR, at most 400 x 240): shown instead of the game; NULL = the game.
void gpu_set_wide(const uint32_t* px, int w, int h);
// The window's rectangle at (x, y), w x h, that the game's frame fills from (gameX, gameY); w 0: none.
void gpu_set_wide_hole(int x, int y, int w, int h, int gameX, int gameY);
// The overlay over the top screen, 400x240 (0xAARRGGBB), when it changed; shown says whether there's one, dim darkens
// the game under it.
void gpu_set_overlay(const uint32_t* px);
void gpu_show_overlay(bool shown, bool dim);
// Keep showing the game's last frame (the touch controls drive its party menu behind it).
void gpu_hold_game(bool hold);

// `work` on a core of its own each time gpu_wide_go is called, the frame's draw waiting for it. False: no such core.
bool gpu_wide_thread(void (*work)(void));
void gpu_wide_go(void);
void gpu_wide_join(void);

// FPS / CPU over everything: a new picture (128 x 16, 0xAARRGGBB) when px isn't NULL, then where it goes; w 0 hides it.
void gpu_set_perf(const uint32_t* px, int x, int y, int w);

// One frame: the game (in screenMode, enum pd_screen_mode) or the top canvas
// on the top screen, the bottom canvas below. Everything is copied before it returns, so the caller can go on; it
// waits for the screen's refresh only when the last frame is still being taken.
void gpu_draw(bool game, int screenMode);
// Frames drawn since the start, whichever thread drew them.
unsigned gpu_frames_shown(void);

#endif
