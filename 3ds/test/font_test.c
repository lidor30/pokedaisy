#include "../core/pd_canvas.h"
#include "../core/pd_font.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int pd_gb_look; // pd_style.c's, which pd_canvas.c reads (not linked here)

static const struct pd_glyph overrides[] = {
    { 'A', 3, { 1, 2, 4 }, 0, 0 },
    { 0x00E9, 4, { 3, 2 }, 0, 0 },
};

int main(void) {
    uint32_t expected[64 * 32] = {0}, actual[64 * 32] = {0};
    struct pd_canvas c;
    const struct pd_glyph* original_a = pd_font_glyph('A');
    const struct pd_glyph* original_b = pd_font_glyph('B');
    const struct pd_glyph* original_question = pd_font_glyph('?');
    int original_width = pd_text_width("AB\xc3\xa9");

    // Baseline pixels and advances must survive an override/reset cycle.
    pd_canvas_init(&c, 64, 32, expected);
    int advance = pd_text(&c, 1, 1, "AB\xc3\xa9", 0xFFFFFF, PD_NO_SHADOW);
    assert(advance == original_width);

    pd_font_set(overrides, 2);
    assert(pd_font_glyph('A') == &overrides[0]);
    assert(pd_font_glyph(0x00E9) == &overrides[1]);
    assert(pd_font_glyph('B') == original_b);
    assert(pd_font_glyph(0x2603) == original_question);
    assert(pd_text_width("AB\xc3\xa9") == 3 + original_b->advance + 4);

    pd_canvas_init(&c, 64, 32, actual);
    assert(pd_text(&c, 1, 1, "A", 0xFFFFFF, PD_NO_SHADOW) == 3);
    for (int y = 0; y < 32; y++) {
        for (int x = 0; x < 64; x++) {
            uint32_t want = ((x == 1 && y == 1) || (x == 2 && y == 2) ||
                             (x == 3 && y == 3)) ? 0xFFFFFF : 0;
            assert(actual[y * 64 + x] == want);
        }
    }
    // UTF-8 lookup, clipping and shadow use the same override path.
    memset(actual, 0, sizeof(actual));
    assert(pd_text(&c, 0, 0, "\xc3\xa9", 0xFFFFFF, PD_NO_SHADOW) == 4);
    assert(actual[0] == 0xFFFFFF && actual[1] == 0xFFFFFF);
    assert(actual[65] == 0xFFFFFF && actual[64] == 0);
    memset(actual, 0, sizeof(actual));
    pd_clip(&c, 2, 2, 1, 1);
    pd_text(&c, 1, 1, "A", 0xFFFFFF, 0x123456);
    assert(actual[2 * 64 + 2] == 0xFFFFFF);
    int lit = 0;
    for (int i = 0; i < 64 * 32; i++) lit += actual[i] != 0;
    assert(lit == 1);
    pd_unclip(&c);

    pd_font_set(NULL, 2);
    assert(pd_font_glyph('A') == original_a);
    pd_font_set(overrides, 0);
    assert(pd_font_glyph('A') == original_a);
    pd_font_set(overrides, -1);
    assert(pd_font_glyph('A') == original_a);
    pd_font_set(overrides, 1);
    assert(pd_font_glyph(0x00E9) != &overrides[1]);
    pd_font_set(NULL, 0);
    memset(actual, 0, sizeof(actual));
    assert(pd_text(&c, 1, 1, "AB\xc3\xa9", 0xFFFFFF, PD_NO_SHADOW) == advance);
    assert(memcmp(expected, actual, sizeof(expected)) == 0);

    // TEXT SIZE SMALL: the HUD's pixel font for text, Pixel Operator for scaled / narrowed labels and lists, lines 13 / 16.
    pd_font_size_set(1);
    assert(pd_font_glyph('A') == &pd_font_small_glyphs['A' - 32] && pd_font_glyph('A')->alpha && pd_font_glyph('A')->cover);
    for (int i = 0; i < 95; i++) assert(pd_font_small_glyphs[i].codepoint == (uint16_t) (32 + i));
    assert(pd_list_row(18) == 16 && pd_list_row(14) == 14);
    assert(pd_font_pixel_glyph('A') == original_a);
    assert(pd_text_width_pixel("AB") == original_a->advance + original_b->advance);
    assert(pd_text_width_scaled("AB", 2) == 2 * pd_text_width_pixel("AB"));
    assert(pd_text_width_scaled("AB", 1) == pd_text_width("AB"));
    assert(pd_text_width("This guide was written") < pd_text_width_pixel("This guide was written"));
    assert(pd_lh(16) == pd_font_small_line && pd_lh(18) > pd_lh(16) && pd_lh(18) < 18 && pd_lh(13) >= pd_font_small_line - 1);
    assert(pd_font_glyph(0x2640) != pd_font_glyph('?') && pd_font_glyph(0x2603) == pd_font_glyph('?'));
    // A game's own font wins over the size.
    pd_font_set(overrides, 2);
    assert(pd_font_glyph('A') == &overrides[0]);
    pd_font_set(NULL, 0);
    // Opaque canvas: whole-pixel strokes in the colour, the softened steps a grey between.
    memset(actual, 0, sizeof(actual));
    pd_canvas_init(&c, 64, 32, actual);
    pd_text(&c, 0, 0, "H", 0xFFFFFF, PD_NO_SHADOW);
    int full = 0, part = 0;
    for (int i = 0; i < 64 * 32; i++) {
        assert(actual[i] >> 24 == 0);
        full += actual[i] == 0xFFFFFF;
        part += actual[i] && actual[i] != 0xFFFFFF;
    }
    assert(full >= 16 && part == 0); // H has no diagonal step
    memset(actual, 0, sizeof(actual));
    pd_text(&c, 0, 0, "O", 0xFFFFFF, PD_NO_SHADOW);
    part = 0;
    for (int i = 0; i < 64 * 32; i++) part += actual[i] && actual[i] != 0xFFFFFF;
    assert(part == 4); // its four outside corners
    // The HUD's overlay: over its clear key a letter's pixel is the colour at its coverage, never the key's magenta.
    for (int i = 0; i < 64 * 32; i++) actual[i] = 0x00FF00FFu;
    pd_text(&c, 0, 0, "o", 0xFFFFFF, PD_NO_SHADOW);
    int seen = 0;
    for (int i = 0; i < 64 * 32; i++)
        if (actual[i] != 0x00FF00FFu) {
            assert((actual[i] & 0xFFFFFF) == 0xFFFFFF);
            seen++;
        }
    assert(seen > 4);
    pd_font_size_set(0);
    assert(pd_lh(18) == 18 && pd_font_glyph('A') == original_a);
    puts("PASS: override pixels, UTF-8, fallback, metrics, clipping and reset; TEXT SIZE SMALL");
    return 0;
}
