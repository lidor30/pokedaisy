// Pixel Operator as a 1-bit bitmap font (pd_font_gen.c, from
// 3ds/tools/gen_font.py): 16-pixel cells, one screen pixel per font pixel.
#ifndef PD_FONT_H
#define PD_FONT_H

#include <stdint.h>

#define PD_FONT_HEIGHT 16

struct pd_glyph {
    uint16_t codepoint;
    uint8_t advance;
    uint16_t rows[PD_FONT_HEIGHT]; // bit 0 = leftmost pixel
    // Optional: how much of each pixel the letter covers (16 x 16, row by row, 0-255), for a font drawn at a size it wasn't
    // made at (a Game Boy game's 8 rows drawn as 10): its edges blended rather than rows repeated. NULL = [rows] alone.
    const uint8_t* alpha;
    // Optional with [alpha]: each row's pixels with any coverage (bit 0 = left), so drawing visits only those. NULL = the
    // whole cell is looked at.
    const uint16_t* cover;
};

extern const struct pd_glyph pd_font_glyphs[];
extern const int pd_font_glyph_count;
// The small text (SETTINGS > TEXT SIZE SMALL): the HUD's drawn pixel font, capitals 8 tall, its diagonal steps softened,
// its baseline on Pixel Operator's.
extern const struct pd_glyph pd_font_small_glyphs[];
extern const int pd_font_small_glyph_count;
extern const int pd_font_small_line; // a NORMAL 16 px line at the small size

// Which font pd_text and friends draw: 0 = Pixel Operator (NORMAL), 1 = the small one. pd_text_scaled / pd_text_narrow
// (big labels, buttons' words) always draw Pixel Operator. Process-wide, set on the render thread.
void pd_font_size_set(int small);
int pd_font_size_small(void);
// Whether text drawn now is the small font (SMALL, without a game's own font): pd_lh's spacing.
int pd_font_text_small(void);

// Optional caller-owned overrides. Missing glyphs use Pixel Operator, then '?'.
// Keep the array alive until reset. NULL or count <= 0 restores the default.
// Process-wide: set/reset on the render thread, including before ROM unload.
void pd_font_set(const struct pd_glyph* glyphs, int count);
void pd_font_get(const struct pd_glyph** glyphs, int* count);

// A text row or line [n] pixels apart at NORMAL, scaled to the size in use: SMALL's are pd_font_small_line / 16 of it (13:
// an 18 row 15, a 16 line 13), never under that less one (its capitals, descenders and a pixel between).
static inline int pd_lh(int n) {
    if (!pd_font_text_small()) return n;
    int s = (n * pd_font_small_line + 8) / 16;
    return s < pd_font_small_line - 1 ? pd_font_small_line - 1 : s;
}

// A list's row [n] pixels tall at NORMAL: 16 at most at SMALL (the capitals and their shadow, 3-4 above and below, room
// for the divider line between rows).
static inline int pd_list_row(int n) {
    return pd_font_size_small() && n > 16 ? 16 : n;
}

// The glyph for a codepoint ('?' when the font lacks it).
const struct pd_glyph* pd_font_glyph(uint32_t codepoint);
// Pixel Operator's (or an installed game font's) glyph, whatever the size: text drawn scaled or narrowed.
const struct pd_glyph* pd_font_pixel_glyph(uint32_t codepoint);

#endif
