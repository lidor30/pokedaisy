// The screens, drawn by the GPU (citro3d / citro2d): the game frame and the
// two canvases are written into textures by the CPU (in the GPU's tiled
// order) and drawn as quads. The smooth screen modes use mGBA's own "sharp
// bilinear" look: the frame at 2x nearest, sampled linearly down to size.
#ifndef GPU_H
#define GPU_H

#include <stdbool.h>
#include <stdint.h>

#define GPU_GAME_STRIDE 256 // the game buffer's row length in pixels

bool gpu_init(void);
void gpu_exit(void);

// mGBA's video buffer: 32-bit pixels (0xXXBBGGRR), GPU_GAME_STRIDE x 160, in linear memory.
uint32_t* gpu_game_buffer(void);
// The core's picture size: 240 x 160, a Game Boy's 160 x 144.
void gpu_set_game_size(int w, int h);
// New canvases (0xRRGGBB): the bottom screen's 320x240, the top's 400x240.
void gpu_set_bottom(const uint32_t* px);
void gpu_set_top(const uint32_t* px);

// EXPAND's window for this frame (w x h, 0xXXBBGGRR, at most 400 x 240): shown instead of the game; NULL = the game.
void gpu_set_wide(const uint32_t* px, int w, int h);
// The overlay over the top screen, 400x240 (0xAARRGGBB), when it changed; shown says whether there's one, dim darkens
// the game under it.
void gpu_set_overlay(const uint32_t* px);
void gpu_show_overlay(bool shown, bool dim);
// Keep showing the game's last frame (the touch controls drive its party menu behind it).
void gpu_hold_game(bool hold);

// One frame: the game (in screenMode, enum pd_screen_mode) or the top canvas
// on the top screen, the bottom canvas below. Waits for the screen's refresh.
void gpu_draw(bool game, int screenMode);

#endif
