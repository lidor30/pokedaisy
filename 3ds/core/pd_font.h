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
};

extern const struct pd_glyph pd_font_glyphs[];
extern const int pd_font_glyph_count;

// Optional caller-owned overrides. Missing glyphs use Pixel Operator, then '?'.
// Keep the array alive until reset. NULL or count <= 0 restores the default.
// Process-wide: set/reset on the render thread, including before ROM unload.
void pd_font_set(const struct pd_glyph* glyphs, int count);

// The glyph for a codepoint ('?' when the font lacks it).
const struct pd_glyph* pd_font_glyph(uint32_t codepoint);

#endif
