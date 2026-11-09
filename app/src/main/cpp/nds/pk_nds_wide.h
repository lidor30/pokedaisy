// EXPAND and ZOOM for a DS game's overworld: its 3D drawn past the top screen's edges, every way.
//
// How: every polygon the game submits (melonDS's GPU3D WideSubmitHook) is also clipped again, shifted in clip space by
// about a screen, into polygon lists of our own - one per slice round the screen: left, right, above, below and the
// four corners - swapped with the game's at VBlank (WideSwapHook). Software renderers of our own draw the slices on
// helper threads while the next frame runs (done by its VBlank), only the rows and columns the window shown needs,
// and the wide frame is the game's own 256 x 192, untouched, with the slices round it. The game's polygon RAM, counters and picture are never touched: the
// middle is the real frame, pixel for pixel.
//
// Shared by the app (pk_nds.cpp) and the headless tool (native-capture/nds_dump's wide / wideshot / widecheck).
#pragma once

#include <cstdint>

namespace melonDS { class NDS; }

/** The widest margins, in DS pixels: left / right and above / below. A slice is a screen less its overlap with the
 * screen, so up to 252 / 188 (less SPARE); the 3x zoom step needs 192 / 84 on the Thor (640 x 360), 242 on a 20:9 phone. */
constexpr int PK_NDS_WIDE_MAX_MARGIN_X = 248;
constexpr int PK_NDS_WIDE_MAX_MARGIN_Y = 96;
constexpr int PK_NDS_WIDE_MAX_W = 256 + 2 * PK_NDS_WIDE_MAX_MARGIN_X;
constexpr int PK_NDS_WIDE_MAX_H = 192 + 2 * PK_NDS_WIDE_MAX_MARGIN_Y;

/** Hooks [nds]'s 3D engine ([on] false: the hooks removed). Between frames. */
void pk_nds_wide_set(melonDS::NDS* nds, bool on);

/** [battle]: a battle on the top screen - its margins are its background, BG3 (512 pixels wide: the game's own picture
 * goes on past the screen, both platforms included), scrolled as the screen is; clear there, the backdrop.
 *
 * After each frame: composes the wide frame from the game's top screen ([topArgb]: melonDS's 0xAARRGGBB, 256 x 192)
 * and the slices drawn after the last frame, then hands the slices for the next one to the helpers. [draw] false skips the composing
 * (a frame no one sees), [drawNext] false the slices (the next frame won't be seen either: fast-forward shows one frame
 * of each group, and the slices were two thirds of a frame's cost); [margins] false leaves them black (off the
 * overworld: a battle, a menu). Returns whether a wide frame was composed. */
bool pk_nds_wide_frame(melonDS::NDS* nds, const uint32_t* topArgb, bool draw, bool margins = true, bool drawNext = true,
    bool battle = false);

/** How many pixels past each edge the window shown needs (left, right, above, below; clamped to the largest
 * margins): the slices draw only those, the rest of the frame stays black. Between frames. */
void pk_nds_wide_need(int left, int right, int top, int bottom);

/** The last wide frame's window [x, x + w) x [y, y + h) in the game frame's pixels (its top-left (0, 0)) into [out]
 * (w x h, RGBA words: 0xAABBGGRR, the screen's upload format): black past what was composed. */
void pk_nds_wide_view(uint32_t* out, int x, int y, int w, int h);

/** What the last wide frame covers, in the game frame's pixels (for nds_dump's wideshot). */
void pk_nds_wide_rect(int* x, int* y, int* w, int* h);

/** Checks the slices against the game's frame where they overlap (the screen's outer rows and columns, past its and
 * the slices' outlines): pixels compared, those that differ and those that differ by more than a texel's colour (a
 * polygon cut at other planes samples a texture a texel off here and there), over the 3D layer's pixels in [topArgb]. For
 * nds_dump's widecheck: call after pk_nds_wide_frame, before the next frame (it compares against the slices drawn for
 * the frame shown, kept from before that call). */
void pk_nds_wide_check(melonDS::NDS* nds, const uint32_t* topArgb, int* compared, int* differ, int* far);

/** Engine A's text BG [bg] at BG pixel ([bx], [by]) - the scroll already added, wrapped to the BG's size - as the
 * hardware reads it (its map and tiles in VRAM, 16 / 256 colours, flips, the standard palette): 0x8000 | BGR555, or 0
 * where clear. For a battle's wide frame and nds_dump's bgdump. */
uint16_t pk_nds_bg_pixel(melonDS::NDS* nds, int bg, int bx, int by);

/** Waits for the slices still being drawn (they draw while the next frame runs, until its VBlank): call before
 * anything that changes the emulator's state other than running it (a state load or save, a reset, another 3D
 * renderer). */
void pk_nds_wide_sync();

/** How many polygons each slice got last frame (8: above-left, above, above-right, left, right, below-left, below,
 * below-right): a debugging read. */
void pk_nds_wide_counts(int out[8]);

// ---- GPU mode (pk_nds_gl.cpp: melonDS's OpenGL renderer at 2x and up) ----
//
// The slices are a software renderer's; the GPU renderer draws the margins and the screen as one picture instead: the
// same submissions, mapped from the window's wider view onto clip space (as the box test's retry does) and clipped
// once, into one list of our own (gpuList) that the renderer draws in place of the game's. The screen's 2D and the
// margins' are then put together by pk_nds_gl.cpp's compositor, which follows the margins' plan below.

namespace melonDS { struct WidePolygonList; }

/** GPU mode on ([scale] texels a DS pixel, at most [maxTexture] texels a side) or off (the slices). Between frames. */
void pk_nds_wide_gpu(bool on, int scale, int maxTexture);

/** The list the GPU renderer draws while it's Active (GLRenderer::Wide points at it): set at the lists' swap. */
const melonDS::WidePolygonList* pk_nds_wide_gpu_list();

/** Whether the margins show the 3D in the frames to come (the overworld, a battle): only then is the next flush's list
 * built. */
void pk_nds_wide_gpu_want(bool threeD);

/** A margin pixel's plan, the word's top byte: PLAN_COLOR = its colour as is (RGB in the low bytes); PLAN_3D / PLAN_3D_OVER
 * = the 3D there, put on the screen as the 2D engine would (alone, or under the 2D colour in the low bytes - 6 bits a
 * channel - blended by BLDCNT); PLAN_SCREEN = the screen's own pixel at (low byte | bit 16's 256, byte 1); PLAN_3D_ON =
 * the 3D over the 2D colour in the low bytes (6 bits a channel: a battle's background, or with PLAN_UNDER_OBJ a sprite),
 * its alpha blended onto it where BLDCNT has that (BG3 / OBJ) as the second target. */
constexpr uint32_t PK_NDS_PLAN_COLOR = 0, PK_NDS_PLAN_3D = 1, PK_NDS_PLAN_3D_OVER = 2, PK_NDS_PLAN_SCREEN = 3,
    PK_NDS_PLAN_3D_ON = 4;
constexpr uint32_t PK_NDS_PLAN_UNDER_OBJ = 0x80;

/** After pk_nds_wide_frame in GPU mode: the margins' plan and what the compositor needs to follow it. */
struct PkNdsWidePlan {
    bool valid = false;
    const uint32_t* px = nullptr;   // the screen's (0, 0) at px[0]; stride words a row
    int stride = 0;
    int x0 = 0, y0 = 0, x1 = 0, y1 = 0;   // what it covers, in the screen's pixels (the screen itself not filled)
    uint32_t backdrop = 0;   // the backdrop, 6 bits a channel (r | g << 8 | b << 16)
    uint32_t blendCnt = 0, eva = 0, evb = 0;
    uint32_t brightness = 0;   // the top screen's master brightness (0: none)
    uint32_t fx = 0;           // BLDCNT's brightness up / down on the 3D / backdrop alone (mode << 14 | EVY; 0: none)
    uint32_t fxTargets = 0;    // its first targets: 1 the 3D, 0x20 the backdrop
};
void pk_nds_wide_plan(PkNdsWidePlan* out);
