#include "../core/pd_canvas.h"
#include "../core/pd_font.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static const struct pd_glyph overrides[] = {
    { 'A', 3, { 1, 2, 4 } },
    { 0x00E9, 4, { 3, 2 } },
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
    puts("PASS: override pixels, UTF-8, fallback, metrics, clipping and reset");
    return 0;
}
