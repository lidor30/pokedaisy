#include "pd_canvas.h"

#include <math.h>
#include <string.h>

#include "pd_font.h"
#include "pd_style.h"

void pd_canvas_init(struct pd_canvas* c, int w, int h, uint32_t* px) {
    c->w = w;
    c->h = h;
    c->px = px;
    c->lx0 = c->ly0 = c->lx1 = c->ly1 = 0;
    pd_unclip(c);
}

void pd_clip(struct pd_canvas* c, int x, int y, int w, int h) {
    const bool limited = c->lx1 > c->lx0;
    const int x0 = limited ? c->lx0 : 0, y0 = limited ? c->ly0 : 0;
    const int x1 = limited && c->lx1 < c->w ? c->lx1 : c->w, y1 = limited && c->ly1 < c->h ? c->ly1 : c->h;
    c->cx0 = x < x0 ? x0 : x;
    c->cy0 = y < y0 ? y0 : y;
    c->cx1 = x + w > x1 ? x1 : x + w;
    c->cy1 = y + h > y1 ? y1 : y + h;
}

void pd_unclip(struct pd_canvas* c) {
    pd_clip(c, 0, 0, c->w, c->h);
}

void pd_canvas_limit(struct pd_canvas* c, int x, int y, int w, int h) {
    c->lx0 = 0;
    c->lx1 = 0;
    pd_clip(c, x, y, w, h);
    c->lx0 = c->cx0;
    c->ly0 = c->cy0;
    c->lx1 = c->cx1;
    c->ly1 = c->cy1;
}

void pd_canvas_unlimit(struct pd_canvas* c) {
    c->lx0 = c->ly0 = c->lx1 = c->ly1 = 0;
    pd_unclip(c);
}

static inline void plot(struct pd_canvas* c, int x, int y, uint32_t color) {
    if (x >= c->cx0 && x < c->cx1 && y >= c->cy0 && y < c->cy1) c->px[y * c->w + x] = color;
}

void pd_fill(struct pd_canvas* c, int x, int y, int w, int h, uint32_t color) {
    int x0 = x < c->cx0 ? c->cx0 : x;
    int y0 = y < c->cy0 ? c->cy0 : y;
    int x1 = x + w > c->cx1 ? c->cx1 : x + w;
    int y1 = y + h > c->cy1 ? c->cy1 : y + h;
    for (int yy = y0; yy < y1; yy++) {
        uint32_t* row = c->px + yy * c->w;
        for (int xx = x0; xx < x1; xx++) row[xx] = color;
    }
}

// Row j of a corner (from the outer edge) is inset this many pixels - the
// same formula as the app's PixelShapes.kt.
static int corner_inset(int r, int j) {
    if (j >= r) return 0;
    return r - (int) floorf(sqrtf((float) (r * r - (r - j) * (r - j))));
}

void pd_round_rect(struct pd_canvas* c, int x, int y, int w, int h, int r, uint32_t color) {
    if (w <= 0 || h <= 0) return;
    if (r * 2 > w) r = w / 2;
    if (r * 2 > h) r = h / 2;
    for (int j = 0; j < h; j++) {
        int fromEdge = j < h - 1 - j ? j : h - 1 - j;
        int in = corner_inset(r, fromEdge);
        pd_fill(c, x + in, y + j, w - 2 * in, 1, color);
    }
}

// pd_round_rect(outer) less pd_round_rect(the same box [d] in, radius ri): a frame's layer, painted once. The result is what
// drawing the whole outer shape and then the inner one over it gave, without filling the middle twice (a window's
// three layers and fill used to paint it four times: on a 3DS that was most of a redraw).
static void ring(struct pd_canvas* c, int x, int y, int w, int h, int r, int ri, int d, uint32_t color) {
    if (w <= 0 || h <= 0) return;
    int iw = w - 2 * d, ih = h - 2 * d;
    if (d <= 0) return;
    if (iw <= 0 || ih <= 0) {
        pd_round_rect(c, x, y, w, h, r, color);
        return;
    }
    if (r < 0) r = 0;
    if (r * 2 > w) r = w / 2;
    if (r * 2 > h) r = h / 2;
    if (ri < 0) ri = 0;
    if (ri * 2 > iw) ri = iw / 2;
    if (ri * 2 > ih) ri = ih / 2;
    for (int j = 0; j < h; j++) {
        int in = corner_inset(r, j < h - 1 - j ? j : h - 1 - j);
        int ox0 = x + in, ox1 = x + w - in, jj = j - d;
        if (jj < 0 || jj >= ih) {
            pd_fill(c, ox0, y + j, ox1 - ox0, 1, color);
            continue;
        }
        int iin = corner_inset(ri, jj < ih - 1 - jj ? jj : ih - 1 - jj);
        int ix0 = x + d + iin, ix1 = x + w - d - iin;
        pd_fill(c, ox0, y + j, ix0 - ox0, 1, color);
        pd_fill(c, ix1, y + j, ox1 - ix1, 1, color);
    }
}

// The layers as rings, outermost first; returns the frame's width and leaves the inner box's radius in [rIn].
static int layer_rings(struct pd_canvas* c, int x, int y, int w, int h, int r, const struct pd_layer* layers, int n, int* rIn) {
    int inset = 0;
    for (int i = 0; i < n; i++) {
        int d = layers[i].width;
        ring(c, x + inset, y + inset, w - 2 * inset, h - 2 * inset, r > 0 ? r : 0, r - d > 0 ? r - d : 0, d, layers[i].color);
        inset += d;
        r -= d;
    }
    *rIn = r > 0 ? r : 0;
    return inset;
}

int pd_layered_box(struct pd_canvas* c, int x, int y, int w, int h, int r,
                   const struct pd_layer* layers, int n, uint32_t fill) {
    int rIn, inset = layer_rings(c, x, y, w, h, r, layers, n, &rIn);
    pd_round_rect(c, x + inset, y + inset, w - 2 * inset, h - 2 * inset, rIn, fill);
    return inset;
}

// Rows [r0, r1) of pd_round_rect's shape (the rest of it left alone).
static void round_rect_rows(struct pd_canvas* c, int x, int y, int w, int h, int r, uint32_t color, int r0, int r1) {
    if (w <= 0 || h <= 0) return;
    if (r * 2 > w) r = w / 2;
    if (r * 2 > h) r = h / 2;
    if (r0 < 0) r0 = 0;
    if (r1 > h) r1 = h;
    for (int j = r0; j < r1; j++) {
        int fromEdge = j < h - 1 - j ? j : h - 1 - j;
        int in = corner_inset(r, fromEdge);
        pd_fill(c, x + in, y + j, w - 2 * in, 1, color);
    }
}

uint32_t pd_mix(uint32_t a, uint32_t b, int k256) {
    uint32_t out = 0;
    for (int sh = 0; sh <= 24; sh += 8) { // alpha too: the HUD's see-through faces
        int x = (int) (a >> sh & 0xFF), y = (int) (b >> sh & 0xFF);
        out |= (uint32_t) (x + (y - x) * k256 / 256) << sh;
    }
    return out;
}

uint32_t pd_two_tone_low(uint32_t fill) {
    return pd_mix(fill, 0x40485A, 77); // 0.3
}

void pd_two_tone(struct pd_canvas* c, int x, int y, int w, int h, int r, uint32_t top, uint32_t low, int line) {
    int split = h * 47 / 100;
    if (line < 1) line = 1;
    round_rect_rows(c, x, y, w, h, r, top, 0, split);
    round_rect_rows(c, x, y, w, h, r, pd_mix(top, low, 90), split, split + line);       // 0.35
    round_rect_rows(c, x, y, w, h, r, pd_mix(top, low, 179), split + line, split + 2 * line); // 0.7
    round_rect_rows(c, x, y, w, h, r, low, split + 2 * line, h);
}

int pd_layered_two_tone(struct pd_canvas* c, int x, int y, int w, int h, int r,
                        const struct pd_layer* layers, int n, uint32_t fill, uint32_t low) {
    // A Game Boy game's buttons are its own: one flat face in the window's frame, no HeartGold two-tone.
    if (pd_gb_look) return pd_layered_box(c, x, y, w, h, r, layers, n, fill);
    int rIn, inset = layer_rings(c, x, y, w, h, r, layers, n, &rIn);
    pd_two_tone(c, x + inset, y + inset, w - 2 * inset, h - 2 * inset, rIn, fill, low ? low : pd_two_tone_low(fill),
                n > 0 ? layers[n - 1].width : 1);
    return inset;
}

// The Android Companion's soft two-tone face inside a layered frame: pd_layered_two_tone's (one face everywhere, flat in a
// Game Boy game).
void pd_button_box(struct pd_canvas* c, int x, int y, int w, int h, int r,
                   const struct pd_layer* layers, int n, uint32_t fill) {
    pd_layered_two_tone(c, x, y, w, h, r, layers, n, fill, 0);
}

static uint32_t next_codepoint(const char** s) {
    const unsigned char* p = (const unsigned char*) *s;
    uint32_t cp;
    if (p[0] < 0x80) {
        cp = p[0];
        *s += 1;
    } else if ((p[0] & 0xE0) == 0xC0 && p[1]) {
        cp = ((p[0] & 0x1F) << 6) | (p[1] & 0x3F);
        *s += 2;
    } else if ((p[0] & 0xF0) == 0xE0 && p[1] && p[2]) {
        cp = ((p[0] & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
        *s += 3;
    } else {
        cp = '?';
        *s += 1;
    }
    return cp;
}

static const struct pd_glyph* custom_font;
static int custom_count;
void pd_font_set(const struct pd_glyph* glyphs, int count) {
    custom_font = glyphs;
    custom_count = glyphs && count > 0 ? count : 0;
}
void pd_font_get(const struct pd_glyph** glyphs, int* count) {
    *glyphs = custom_font;
    *count = custom_count;
}
static int small_font;
void pd_font_size_set(int small) { small_font = small ? 1 : 0; }
int pd_font_size_small(void) { return small_font; }
int pd_font_text_small(void) { return small_font && !custom_count; }
const struct pd_glyph* pd_font_glyph(uint32_t cp) {
    // A game's own font (a Game Boy game's) is the game's look: it wins over the size.
    if (!small_font || custom_count) return pd_font_pixel_glyph(cp);
    if (cp >= 32 && cp < 127) return &pd_font_small_glyphs[cp - 32];
    for (int i = 95; i < pd_font_small_glyph_count; i++)
        if (pd_font_small_glyphs[i].codepoint == cp) return &pd_font_small_glyphs[i];
    return &pd_font_small_glyphs['?' - 32];
}
const struct pd_glyph* pd_font_pixel_glyph(uint32_t cp) {
    for (int i = 0; i < custom_count; i++)
        if (custom_font[i].codepoint == cp) return &custom_font[i];
    if (cp >= 32 && cp < 127) return &pd_font_glyphs[cp - 32];
    for (int i = 95; i < pd_font_glyph_count; i++) {
        if (pd_font_glyphs[i].codepoint == cp) return &pd_font_glyphs[i];
    }
    return &pd_font_glyphs['?' - 32];
}

// A smoothed letter's pixel, [k] / 256 of [color] over [dst]. The HUD / QUICK MENU's overlay keeps opacity in the top
// byte (0 = opaque, else that much of it shows) and 0x00FF00FF for "nothing here" (quick_menu.h's QUICK_CLEAR): an edge
// pixel goes toward opaque there, and over nothing it's the letter's colour at its coverage.
#define OVERLAY_CLEAR 0x00FF00FFu
static inline uint32_t text_blend(uint32_t dst, uint32_t color, int k) {
    if (!((dst | color) >> 24) && dst != OVERLAY_CLEAR) {
        // Both opaque (the companion's canvas): red and blue in one multiply, green in another.
        uint32_t j = 256 - (uint32_t) k;
        return (((dst & 0xFF00FF) * j + (color & 0xFF00FF) * (uint32_t) k) >> 8 & 0xFF00FF)
             | (((dst & 0xFF00) * j + (color & 0xFF00) * (uint32_t) k) >> 8 & 0xFF00);
    }
    if (dst == OVERLAY_CLEAR) return (uint32_t) (k < 1 ? 1 : k) << 24 | (color & 0xFFFFFF);
    int da = dst >> 24 ? (int) (dst >> 24) : 255, ca = color >> 24 ? (int) (color >> 24) : 255;
    uint32_t rgb = pd_mix(dst & 0xFFFFFF, color & 0xFFFFFF, k);
    if (da == 255 && ca == 255) return rgb;
    int a = da + (ca - da) * k / 256;
    return (a >= 255 ? 0u : (uint32_t) (a < 1 ? 1 : a) << 24) | rgb;
}

// A glyph, clipped a row at a time; [shadow]: the union of it moved right, down and both (GBA text's shadow in one pass,
// one row taller). A glyph wholly outside the clip (a list's rows scrolled out of view) costs nothing.
// A glyph with its coverage (pd_glyph.alpha): blended into what's behind; the shadow is the coverage moved right, down and
// both, the most of the three (the bitmap shadow's union, softened the same way).
static void draw_glyph_alpha(struct pd_canvas* c, int x, int y, const struct pd_glyph* g, uint32_t color, bool shadow, int r0, int r1) {
    const uint8_t* a = g->alpha;
    const uint16_t* cover = g->cover;
    for (int row = r0; row < r1; row++) {
        uint32_t* line = c->px + (y + row) * c->w;
        // The pixels this row can touch: the covered ones, and for the shadow those moved right, down and both.
        uint32_t mask = 0x1FFFF;
        if (cover) {
            uint32_t cur = row < PD_FONT_HEIGHT ? cover[row] : 0, above = row > 0 && row - 1 < PD_FONT_HEIGHT ? cover[row - 1] : 0;
            mask = shadow ? cur << 1 | above | above << 1 : cur;
            if (!mask) continue;
        }
        for (int col = 0; mask; col++, mask >>= 1) {
            if (!(mask & 1)) continue;
            int px = x + col;
            if (px < c->cx0 || px >= c->cx1) continue;
            int k = row < PD_FONT_HEIGHT && col < 16 ? a[row * 16 + col] : 0;
            if (shadow) {
                int l = row < PD_FONT_HEIGHT && col > 0 ? a[row * 16 + col - 1] : 0;
                int u = row > 0 && row - 1 < PD_FONT_HEIGHT && col < 16 ? a[(row - 1) * 16 + col] : 0;
                int ul = row > 0 && row - 1 < PD_FONT_HEIGHT && col > 0 ? a[(row - 1) * 16 + col - 1] : 0;
                k = l > u ? l : u;
                if (ul > k) k = ul;
            }
            if (k >= 250) line[px] = color;
            else if (k > 0) line[px] = text_blend(line[px], color, k);
        }
    }
}

static void draw_glyph(struct pd_canvas* c, int x, int y, const struct pd_glyph* g, uint32_t color, bool shadow) {
    int rows = PD_FONT_HEIGHT + (shadow ? 1 : 0);
    if (y >= c->cy1 || y + rows <= c->cy0 || x >= c->cx1 || x + 18 <= c->cx0) return;
    int r0 = c->cy0 > y ? c->cy0 - y : 0, r1 = c->cy1 - y < rows ? c->cy1 - y : rows;
    if (g->alpha) {
        draw_glyph_alpha(c, x, y, g, color, shadow, r0, r1);
        return;
    }
    for (int row = r0; row < r1; row++) {
        uint32_t bits = row < PD_FONT_HEIGHT ? g->rows[row] : 0;
        if (shadow) {
            uint32_t above = row > 0 ? g->rows[row - 1] : 0;
            bits = bits << 1 | above | above << 1;
        }
        if (!bits) continue;
        uint32_t* line = c->px + (y + row) * c->w;
        for (int col = 0; bits; col++, bits >>= 1) {
            int px = x + col;
            if ((bits & 1) && px >= c->cx0 && px < c->cx1) line[px] = color;
        }
    }
}

static int draw_run(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, bool shadow) {
    int x0 = x;
    while (*s) {
        const struct pd_glyph* g = pd_font_glyph(next_codepoint(&s));
        draw_glyph(c, x, y, g, color, shadow);
        x += g->advance;
    }
    return x - x0;
}

int pd_text(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, uint32_t shadow) {
    // Every glyph's shadow first, then the letters: one string's shadow never covers its own letters.
    if (shadow != PD_NO_SHADOW) draw_run(c, x, y, s, shadow, true);
    return draw_run(c, x, y, s, color, false);
}

// The string at [num] / [den] of its size (smaller), smoothed: its coverage sampled 4 x 4 per pixel from the font's, a
// shadow a pixel right / down of the result as pd_text's. For the QUICK MENU's tile labels.
#define MINI_W 256
int pd_text_mini_width(const char* s, int num, int den) {
    return (pd_text_width(s) * num + den - 1) / den;
}
int pd_text_mini(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, uint32_t shadow, int num, int den) {
    static uint8_t src[PD_FONT_HEIGHT][MINI_W];
    static uint8_t out[PD_FONT_HEIGHT + 1][MINI_W + 1];
    memset(src, 0, sizeof src);
    int sw = 0;
    for (const char* p = s; *p;) {
        const struct pd_glyph* g = pd_font_glyph(next_codepoint(&p));
        for (int row = 0; row < PD_FONT_HEIGHT; row++)
            for (int col = 0; col < 16 && sw + col < MINI_W; col++) {
                int k = g->alpha ? g->alpha[row * 16 + col] : (g->rows[row] >> col & 1) * 255;
                if (k > src[row][sw + col]) src[row][sw + col] = (uint8_t) k;
            }
        sw += g->advance;
    }
    if (sw > MINI_W) sw = MINI_W;
    int ow = (sw * num + den - 1) / den, oh = (PD_FONT_HEIGHT * num + den - 1) / den;
    memset(out, 0, sizeof out);
    for (int oy = 0; oy < oh; oy++)
        for (int ox = 0; ox < ow; ox++) {
            int sum = 0;
            for (int j = 0; j < 4; j++) {
                int sy = ((oy * 4 + j) * den + den / 2) / (4 * num);
                if (sy >= PD_FONT_HEIGHT) continue;
                for (int i = 0; i < 4; i++) {
                    int sx = ((ox * 4 + i) * den + den / 2) / (4 * num);
                    if (sx < sw) sum += src[sy][sx];
                }
            }
            out[oy][ox] = (uint8_t) (sum / 16);
        }
    for (int pass = shadow == PD_NO_SHADOW ? 1 : 0; pass < 2; pass++) {
        uint32_t col = pass ? color : shadow;
        for (int oy = 0; oy <= oh; oy++) {
            int py = y + oy;
            if (py < c->cy0 || py >= c->cy1) continue;
            uint32_t* line = c->px + py * c->w;
            for (int ox = 0; ox <= ow; ox++) {
                int px = x + ox;
                if (px < c->cx0 || px >= c->cx1) continue;
                int k = oy < oh && ox < ow ? out[oy][ox] : 0;
                if (!pass) {
                    int l = ox > 0 && oy < oh ? out[oy][ox - 1] : 0, u = oy > 0 && ox < ow ? out[oy - 1][ox] : 0;
                    int ul = ox > 0 && oy > 0 ? out[oy - 1][ox - 1] : 0;
                    k = l > u ? l : u;
                    if (ul > k) k = ul;
                }
                if (k >= 250) line[px] = col;
                else if (k > 8) line[px] = text_blend(line[px], col, k);
            }
        }
    }
    return ow;
}

int pd_text_scaled(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, uint32_t shadow, int scale) {
    if (scale <= 1) return pd_text(c, x, y, s, color, shadow);
    int x0 = x;
    for (const char* p = s; *p;) {
        const struct pd_glyph* g = pd_font_pixel_glyph(next_codepoint(&p));
        for (int pass = shadow != PD_NO_SHADOW ? 0 : 1; pass < 2; pass++) {
            int d = pass ? 0 : scale;
            uint32_t col = pass ? color : shadow;
            for (int row = 0; row < PD_FONT_HEIGHT; row++) {
                uint16_t bits = g->rows[row];
                for (int k = 0; bits; k++, bits >>= 1) {
                    if (bits & 1) pd_fill(c, x + k * scale + d, y + row * scale + d, scale, scale, col);
                }
            }
        }
        x += g->advance * scale;
    }
    return x - x0;
}

int pd_text_narrow(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, int scale, int num, int den) {
    return pd_text_narrow_shifted(c, x, y, s, color, scale, num, den, 0);
}

int pd_text_narrow_shifted(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, int scale, int num, int den,
                           int shift) {
    // Font column k covers [x + k * num / den, x + (k + 1) * num / den): whole canvas pixels, some columns a pixel
    // narrower than others (FittedGbaText's 3 / 2 / 1.5 / 1 wide letters).
    int col = 0;
    for (const char* p = s; *p;) {
        const struct pd_glyph* g = pd_font_pixel_glyph(next_codepoint(&p));
        for (int row = 0; row < PD_FONT_HEIGHT; row++) {
            uint16_t bits = g->rows[row];
            for (int k = 0; bits; k++, bits >>= 1) {
                if (!(bits & 1)) continue;
                int x0 = (col + k + shift) * num / den, x1 = (col + k + shift + 1) * num / den;
                pd_fill(c, x + x0, y + row * scale, x1 - x0, scale, color);
            }
        }
        col += g->advance;
    }
    return col * num / den;
}

int pd_text_width(const char* s) {
    int w = 0;
    while (*s) w += pd_font_glyph(next_codepoint(&s))->advance;
    return w;
}

int pd_text_width_pixel(const char* s) {
    int w = 0;
    while (*s) w += pd_font_pixel_glyph(next_codepoint(&s))->advance;
    return w;
}

int pd_text_width_scaled(const char* s, int scale) {
    return scale <= 1 ? pd_text_width(s) : pd_text_width_pixel(s) * scale;
}

int pd_text_fit(struct pd_canvas* c, int x, int y, int max_w, const char* s, uint32_t color, uint32_t shadow) {
    if (pd_text_width(s) <= max_w) return pd_text(c, x, y, s, color, shadow);
    char buf[128];
    int ell = pd_text_width("...");
    int w = 0;
    size_t n = 0;
    const char* p = s;
    while (*p) {
        const char* before = p;
        int adv = pd_font_glyph(next_codepoint(&p))->advance;
        if (w + adv + ell > max_w || (size_t) (p - s) + 4 >= sizeof(buf)) {
            p = before;
            break;
        }
        w += adv;
        n = (size_t) (p - s);
    }
    memcpy(buf, s, n);
    memcpy(buf + n, "...", 4);
    return pd_text(c, x, y, buf, color, shadow);
}

void pd_text_right(struct pd_canvas* c, int right, int y, const char* s, uint32_t color, uint32_t shadow) {
    pd_text(c, right - pd_text_width(s), y, s, color, shadow);
}

int pd_text_wrap(struct pd_canvas* c, int x, int y, int max_w, int lineH, int maxLines, const char* s,
                 uint32_t color, uint32_t shadow) {
    char line[160];
    int lines = 0;
    while (lines < maxLines) {
        while (*s == ' ') s++;
        if (!*s) break;
        if (*s == '\n') { ++s; ++lines; continue; }
        // The longest run of whole words that fits; a single word too long
        // for the line is taken whole and cut.
        const char* end = NULL;
        const char* p = s;
        for (;;) {
            const char* wordEnd = p;
            while (*wordEnd && *wordEnd != ' ' && *wordEnd != '\n') wordEnd++;
            size_t n = (size_t) (wordEnd - s);
            if (n >= sizeof(line)) break;
            memcpy(line, s, n);
            line[n] = 0;
            if (pd_text_width(line) > max_w) break;
            end = wordEnd;
            p = wordEnd;
            while (*p == ' ') p++;
            if (!*p || *p == '\n') break;
        }
        bool cut = false;
        if (!end) {
            end = s;
            while (*end && *end != ' ' && *end != '\n') end++;
            cut = true;
        }
        size_t n = (size_t) (end - s);
        if (n >= sizeof(line)) n = sizeof(line) - 1;
        memcpy(line, s, n);
        line[n] = 0;
        s = end;
        if (*s == '\n') ++s;
        while (*s == ' ') s++;
        if (lines == maxLines - 1 && *s) {
            // The last line takes the rest, cut with "...".
            size_t rest = strlen(s);
            if (n + 1 + rest < sizeof(line)) {
                line[n] = ' ';
                memcpy(line + n + 1, s, rest + 1);
            }
            cut = true;
        }
        // No canvas: only counting the lines.
        if (c && cut) {
            pd_text_fit(c, x, y + lines * lineH, max_w, line, color, shadow);
        } else if (c) {
            pd_text(c, x, y + lines * lineH, line, color, shadow);
        }
        lines++;
    }
    return lines;
}

void pd_dim(struct pd_canvas* c, int alpha) {
    uint32_t keep = (uint32_t) (255 - alpha);
    // Each channel times keep, divided by 255 exactly as (x + 1 + (x >> 8)) >> 8 (true for every x up to 255 * 255).
    // Red and blue go together in one multiply: each product fits its own 16 bits, so nothing carries between them.
    // Inside the clip only, like every other draw: under pd_canvas_limit (a list's redraw) a whole-canvas dim darkened
    // what lay outside the list again on every step, until the screen around MAP's PLACES / FLY lists was black.
    for (int y = c->cy0; y < c->cy1; y++) {
        for (int i = y * c->w + c->cx0, end = y * c->w + c->cx1; i < end; i++) {
            uint32_t p = c->px[i];
            uint32_t rb = (p & 0x00FF00FFu) * keep;
            rb = ((rb + 0x00010001u + ((rb >> 8) & 0x00FF00FFu)) >> 8) & 0x00FF00FFu;
            uint32_t g = ((p >> 8) & 0xFF) * keep;
            g = (g + 1 + (g >> 8)) >> 8;
            c->px[i] = rb | (g << 8);
        }
    }
}

void pd_triangle(struct pd_canvas* c, int x, int y, int size, enum pd_dir dir, uint32_t color) {
    // Column / row i is 2 * (size - i) - 1 long, centred: a pixel-stepped point.
    for (int i = 0; i < size; i++) {
        int len = 2 * (size - i) - 1;
        int off = i;
        switch (dir) {
        case PD_RIGHT: pd_fill(c, x + i, y + off, 1, len, color); break;
        case PD_LEFT: pd_fill(c, x + size - 1 - i, y + off, 1, len, color); break;
        case PD_DOWN: pd_fill(c, x + off, y + i, len, 1, color); break;
        case PD_UP: pd_fill(c, x + off, y + size - 1 - i, len, 1, color); break;
        }
    }
}

void pd_bitmap(struct pd_canvas* c, int x, int y, const uint16_t* rows, int h, uint32_t color) {
    for (int r = 0; r < h; r++) {
        uint16_t bits = rows[r];
        for (int col = 0; bits; col++, bits >>= 1) {
            if (bits & 1) plot(c, x + col, y + r, color);
        }
    }
}
