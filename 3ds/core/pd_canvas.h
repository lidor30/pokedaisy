// A small software canvas the companion draws into: 0xRRGGBB pixels, row
// major. Each host converts it for its screen (the 3DS's rotated BGR
// framebuffer, an SDL texture, a PNG). Corners step in whole pixels and text
// is the 1-bit Pixel Operator, so it all stays pixel art (CLAUDE.md: corners
// are pixel art, never smooth arcs).
#ifndef PD_CANVAS_H
#define PD_CANVAS_H

#include <stdbool.h>
#include <stdint.h>

struct pd_canvas {
    int w, h;
    uint32_t* px;
    // Drawing is clipped to this rectangle (pd_clip / pd_unclip).
    int cx0, cy0, cx1, cy1;
};

struct pd_layer {
    uint32_t color;
    int width;
};

void pd_canvas_init(struct pd_canvas* c, int w, int h, uint32_t* px);
void pd_clip(struct pd_canvas* c, int x, int y, int w, int h);
void pd_unclip(struct pd_canvas* c);

void pd_fill(struct pd_canvas* c, int x, int y, int w, int h, uint32_t color);
// A filled rectangle whose corners step like the app's PixelRoundedShape(r).
void pd_round_rect(struct pd_canvas* c, int x, int y, int w, int h, int r, uint32_t color);
// Nested rounded rects, outermost first, then the fill - the OPTION screen's
// window frames (the app's drawLayeredBox). Returns the frame's total width.
int pd_layered_box(struct pd_canvas* c, int x, int y, int w, int h, int r,
                   const struct pd_layer* layers, int n, uint32_t fill);

// HeartGold's button face (the app's drawTwoTone): [top] down to just under half way, two lines of [line] stepping between
// the colours, then [low]; in pd_round_rect's shape.
void pd_two_tone(struct pd_canvas* c, int x, int y, int w, int h, int r, uint32_t top, uint32_t low, int line);
// pd_layered_box with a two-tone face (drawLayeredBox(twoTone = true)); [low] 0 = pd_two_tone_low(fill).
int pd_layered_two_tone(struct pd_canvas* c, int x, int y, int w, int h, int r,
                        const struct pd_layer* layers, int n, uint32_t fill, uint32_t low);
// A window-coloured button's lower colour (twoToneLow): [fill] a step toward slate.
uint32_t pd_two_tone_low(uint32_t fill);
// a toward b by k / 256, per channel (alpha included).
uint32_t pd_mix(uint32_t a, uint32_t b, int k256);
// The Android Companion's soft two-tone button face inside a layered frame.
void pd_button_box(struct pd_canvas* c, int x, int y, int w, int h, int r,
                   const struct pd_layer* layers, int n, uint32_t fill);

// GBA text: the shadow one pixel right, down and diagonally, then the glyphs.
// shadow 0xFF000000 (PD_NO_SHADOW) = none. Returns the advance in pixels.
#define PD_NO_SHADOW 0xFF000000u
int pd_text(struct pd_canvas* c, int x, int y, const char* utf8, uint32_t color, uint32_t shadow);
// Each font pixel `scale` canvas pixels square, the shadow one font pixel down and right.
int pd_text_scaled(struct pd_canvas* c, int x, int y, const char* utf8, uint32_t color, uint32_t shadow, int scale);
// Text [scale] tall with each font pixel num / den canvas pixels wide (a label narrowed to fit, not shrunk), no shadow.
int pd_text_narrow(struct pd_canvas* c, int x, int y, const char* utf8, uint32_t color, int scale, int num, int den);
// The same, every font column moved [shift] columns over (an outline's copies, on the narrowed grid).
int pd_text_narrow_shifted(struct pd_canvas* c, int x, int y, const char* utf8, uint32_t color, int scale, int num, int den,
                           int shift);
int pd_text_width(const char* utf8);
// Like pd_text, cut with "..." to fit max_w.
int pd_text_fit(struct pd_canvas* c, int x, int y, int max_w, const char* utf8, uint32_t color, uint32_t shadow);
// Right-aligned at right.
void pd_text_right(struct pd_canvas* c, int right, int y, const char* utf8, uint32_t color, uint32_t shadow);
// Word-wrapped into max_w, lineH apart, at most maxLines (the last cut with
// "..."). Returns the lines drawn; with c NULL it only counts them.
int pd_text_wrap(struct pd_canvas* c, int x, int y, int max_w, int lineH, int maxLines, const char* utf8,
                 uint32_t color, uint32_t shadow);

// Darkens everything drawn so far towards black: alpha 0-255 (the app's
// black-out overlays are 0xE6).
void pd_dim(struct pd_canvas* c, int alpha);

// A filled pixel triangle `size` px from base to tip, pointing dir
// (PD_LEFT / PD_RIGHT / PD_UP / PD_DOWN) - drawn cursors, since the font has none.
enum pd_dir { PD_LEFT, PD_RIGHT, PD_UP, PD_DOWN };
void pd_triangle(struct pd_canvas* c, int x, int y, int size, enum pd_dir dir, uint32_t color);
// A 1-bit icon: rows[i] bit j (bit 0 = left) is pixel (j, i).
void pd_bitmap(struct pd_canvas* c, int x, int y, const uint16_t* rows, int h, uint32_t color);

#endif
