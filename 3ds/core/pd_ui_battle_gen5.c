// BATTLE STYLE > GEN 5 (BattleControlsGen5.kt): the battle controls laid out and coloured like Black / White's battle touch
// screen, drawn in our own pieces. The pane is the game's bottom screen edge to edge: a Poké Ball, red over pale lilac, the
// dark band across it swelling into the FIGHT! lens, BAG / RUN / POKéMON as round plates rising from the bottom edge, the
// party's balls between. FIGHT grows the lens into the dark field of the four move plates; POKéMON is the game's party
// screen; between turns the closed ball in the dark. Lengths are the game's pixels (256 x 176) over the pane: placed by its
// width and height, sized by the smaller scale, every edge stepped in [d] canvas pixels (one game pixel, rounded).
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_binput.h"
#include "pd_font.h"
#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

// The game's colours, sampled from its frames (BattleControlsGen5.kt's).
#define RED 0xB20028
#define RED_RING 0xA20028
#define RED_RING_OUTER 0x920020
#define BAND_SHADE_HI 0x9A0020
#define BAND_SHADE 0x820018
#define BAND_EDGE 0x282020
#define BAND_FILL 0x413030
#define PALE 0xDBDBE3
#define PALE_LILAC 0xD3D3EB
#define PALE_LILAC_DEEP 0xCBC3EB
#define PALE_SHADE1 0x9A92AA
#define PALE_SHADE2 0xBABACB
#define PALE_SHADE3 0xCBCBE3
#define FIELD_FILL 0x514949
#define FIELD_EDGE 0x413838
#define FIELD_OUTLINE 0x181818
#define FIELD_SHADE 0x8A1830
#define FIELD_PALE_SHADE1 0xC3C3CB
#define FIELD_PALE_SHADE2 0xCBCBE3
#define FIGHT_RED 0xCB0038
#define LABEL_OUTLINE 0x202020
#define RIM_OUTER_GREY 0x717171
#define RIM_PALE 0xCBCBCB
#define RIM_WHITE 0xFBFBFB
#define RIM_INNER 0x595959
#define PLATE_TEXT 0xFBFBFB
#define PLATE_TEXT_SHADOW 0x717171
#define PLATE_MUTED 0xC3C3CB
#define EMPTY_RIM 0x828282
#define BALL_LINE 0x595959
#define NIGHT 0x181818
#define BALL_OUTLINE 0x080808
#define BALL_TOP_RIM 0x300810
#define BALL_TOP 0x380810
#define BALL_BOTTOM_RIM 0x383838
#define BALL_BOTTOM 0x414141
#define MSG_BAND 0x202024
#define MSG_LINE 0xB20028
#define MSG_LINE_SHADOW 0x590010
#define MSG_SHADOW 0x515159
#define PARTY_RED 0x710018
#define PARTY_RED_DARK 0x590010
#define PARTY_RED_DEEPER 0x490000
#define PARTY_BAND 0x281818
#define PARTY_BAND_EDGE 0x201010
#define PARTY_LILAC 0x797982
#define PARTY_LILAC_DEEP 0x616171
#define SLOT_OUTLINE 0x181818
#define EMPTY_SLOT_FACE 0x080808
#define EMPTY_SLOT_STRIP 0x203010
#define STRIP 0xCBCBCB
#define STRIP_LINE 0x828282
#define BAR_FRAME 0x303030
#define HP_GREEN 0x18C320
#define HP_YELLOW 0xF3B300
#define HP_RED 0xFB3030
#define HP_LABEL 0x00FB49
#define SLOT_TEXT 0xEBEBEB
#define SLOT_TEXT_SHADOW 0x828282
#define MALE_BLUE 0x2849FB
#define FEMALE_RED 0xFB4949
#define MSG_BOX_FILL 0xFBFBFB
#define MSG_BOX_LINE 0x515151
#define MSG_BOX_TEXT 0x595151
#define MSG_BOX_SHADOW 0xA2A2AA
#define SUGGEST_GOLD 0xE8B830
#define VERDICT_GREY 0x8890A0

static const uint32_t FACE[5] = { 0x383838, 0x353535, 0x303030, 0x2A2A2A, 0x242424 };
static const uint32_t GREEN_RIM[3] = { 0x8AD318, 0x619A28, 0x385930 };
static const uint32_t FAINT_RIM[3] = { 0x8A8A8A, 0x616161, 0x383838 };
static const uint32_t EMPTY_SLOT_RIM[3] = { 0x304910, 0x203010, 0x101810 };

struct label_colors { uint32_t top, bottom; };
static const struct label_colors BAG_COLORS = { 0xFBD25A, 0xFBB228 };
static const struct label_colors RUN_COLORS = { 0xA2DBFB, 0x28BAF3 };
static const struct label_colors MON_COLORS = { 0x71FB51, 0x51CB28 };
static const struct label_colors INFO_COLORS = { 0xE3CBFB, 0xB28AF3 };
static const struct label_colors SUGGEST_COLORS = { 0xFBEB8A, 0xFBC330 };

// ---- Geometry ---------------------------------------------------------------------------------------------------------

struct g5 {
    float w, h, sx, sy, s, bandY;
    int d;
};

static struct g5 g5_of(int w, int h) {
    struct g5 g;
    g.w = (float) w;
    g.h = (float) h;
    g.sx = g.w / 256.f;
    g.sy = g.h / 176.f;
    g.s = g.sx < g.sy ? g.sx : g.sy;
    g.d = (int) lroundf(g.s);
    if (g.d < 1) g.d = 1;
    g.bandY = 71.5f * g.sy; // the band's middle (y 87.5 on the game's screen)
    return g;
}

static int snap(const struct g5* g, float v) { return (int) lroundf(v / g->d) * g->d; }
static int gx(const struct g5* g, float v) { return snap(g, v * g->sx); }
static int gy(const struct g5* g, float v) { return snap(g, v * g->sy); }
static int glen(const struct g5* g, float v) { return snap(g, v * g->s); }

// The lens's half height at [u] (0 = the middle, 1 = either side), in game pixels: the band's 9 rows out to a quarter in from
// the sides, a steep shoulder, then a long gentle rise to 26.5 in the middle.
static float lens_half(float u) {
    float dx = u * 128.f;
    return dx <= 4.f ? 26.5f : dx <= 64.f ? 26.5f - (dx - 4.f) / 8.f : dx <= 80.f ? 19.f - (dx - 64.f) / 16.f * 10.f : 9.f;
}

// The move plates' field (the lens grown): its top and bottom edge at [u], in game pixels.
static float field_top(float u) { return -4.f + 12.f * u * u; }
static float field_bottom(float u) { return 135.f - 22.f * u * u; }

struct pt { float x, y; };

static float profile(const struct pt* pts, int n, float t) {
    if (t <= pts[0].x) return pts[0].y;
    for (int i = 1; i < n; i++)
        if (t <= pts[i].x) return pts[i - 1].y + (pts[i].y - pts[i - 1].y) * (t - pts[i - 1].x) / (pts[i].x - pts[i - 1].x);
    return pts[n - 1].y;
}

// The round plates' outline: a 16-sided near circle with a short flat top - (depth from the top, half width), in radii.
static const struct pt DISC[6] = { { 0, .107f }, { .32f, .72f }, { .93f, 1 }, { 1.07f, 1 }, { 1.68f, .72f }, { 2, .107f } };
// The move plates' outline: a shallow roof, then the sides out to a point halfway down.
static const struct pt PLATE[5] = { { 0, .143f }, { .104f, .825f }, { .5f, 1 }, { .896f, .825f }, { 1, .143f } };

// A shape, row by row: its left / right edge at a row's middle (false = nothing on that row).
struct shape {
    int kind; // 0 disc, 1 plate, 2 slot (corners cut), 3 circle
    float cx, cy, r, barTop, w; // disc / circle
    int bar;
    float l, t, sw, sh, cut;    // plate / slot
};

static bool span(const struct shape* s, float y, float* l, float* r) {
    switch (s->kind) {
    case 0: {
        float hw = profile(DISC, 6, (y - (s->cy - s->r)) / s->r) * s->r;
        *l = s->cx - hw;
        *r = s->cx + hw;
        if (y >= s->barTop) {
            if (s->bar < 0) *l = 0;
            if (s->bar > 0) *r = s->w;
        }
        return true;
    }
    case 1: {
        float hw = profile(PLATE, 5, (y - s->t) / s->sh) * s->sw / 2;
        *l = s->l + s->sw / 2 - hw;
        *r = s->l + s->sw / 2 + hw;
        return true;
    }
    case 2: {
        float k = y - s->t < s->t + s->sh - y ? y - s->t : s->t + s->sh - y, cut = s->cut - k;
        if (cut < 0) cut = 0;
        *l = s->l + cut;
        *r = s->l + s->sw - cut;
        return true;
    }
    default: {
        float dy = (y - s->cy) / s->r;
        if (fabsf(dy) >= 1) return false;
        float hw = s->r * sqrtf(1 - dy * dy);
        *l = s->cx - hw;
        *r = s->cx + hw;
        return true;
    }
    }
}

// Fills [s] from row [top] to [bottom], [d] rows at a time, edges snapped to [d].
static void fill_shape(struct pd_canvas* c, const struct shape* s, float top, float bottom, int d, uint32_t color) {
    for (float y = top; y < bottom - .01f; y += d) {
        float y2 = y + d < bottom ? y + d : bottom, l, r;
        if (!span(s, (y + y2) / 2, &l, &r)) continue;
        int ls = (int) lroundf(l / d) * d, rs = (int) lroundf(r / d) * d;
        if (rs > ls) pd_fill(c, ls, (int) y, rs - ls, (int) (y2 - y), color);
    }
}

// Fills [outer] less [inner] (inner's rows from [innerTop] to [innerBottom]): a frame layer as a ring, so each pixel of a
// plate is written once.
static void fill_ring(struct pd_canvas* c, const struct shape* outer, const struct shape* inner, float top, float bottom,
                      float innerTop, float innerBottom, int d, uint32_t color) {
    for (float y = top; y < bottom - .01f; y += d) {
        float y2 = y + d < bottom ? y + d : bottom, mid = (y + y2) / 2, l, r, il, ir;
        if (!span(outer, mid, &l, &r)) continue;
        int ls = (int) lroundf(l / d) * d, rs = (int) lroundf(r / d) * d, yy = (int) y, hh = (int) (y2 - y);
        if (rs <= ls) continue;
        if (mid >= innerTop && mid < innerBottom && span(inner, mid, &il, &ir)) {
            int ils = (int) lroundf(il / d) * d, irs = (int) lroundf(ir / d) * d;
            if (irs > ils) {
                if (ils > ls) pd_fill(c, ls, yy, ils - ls, hh, color);
                if (rs > irs) pd_fill(c, irs, yy, rs - irs, hh, color);
                continue;
            }
        }
        pd_fill(c, ls, yy, rs - ls, hh, color);
    }
}

// The plates' face: charcoal dotted lighter (a 2 x 2 dot every 5 game pixels, a softer cross round it), darker in steps
// toward the bottom; [lit] while pressed. Written a row at a time straight into the canvas: the pattern repeats every 5
// game pixels, so a row is 5 colours in turn.
static void fill_face(struct pd_canvas* c, const struct shape* s, float top, float bottom, int d, bool lit, int ax) {
    const int n = 5;
    float bh = (bottom - top) / n;
    int ay = (int) lroundf(top / d) * d + d;
    for (float y = top; y < bottom - .01f; y += d) {
        float y2 = y + d < bottom ? y + d : bottom, l, r;
        if (!span(s, (y + y2) / 2, &l, &r)) continue;
        int x0 = (int) lroundf(l / d) * d, x1 = (int) lroundf(r / d) * d, yy = (int) y;
        if (x0 < c->cx0) x0 = c->cx0;
        if (x1 > c->cx1) x1 = c->cx1;
        if (x1 <= x0 || x0 < ax) continue;
        int band = (int) ((y - top) / bh);
        if (band < 0) band = 0;
        if (band >= n) band = n - 1;
        uint32_t base = FACE[band], dot = pd_mix(base, 0xFFFFFF, 18), soft = pd_mix(base, 0xFFFFFF, 9);
        if (lit) {
            base = pd_mix(base, 0xFFFFFF, 36);
            dot = pd_mix(dot, 0xFFFFFF, 36);
            soft = pd_mix(soft, 0xFFFFFF, 36);
        }
        int v = (((yy - ay) / d) % 5 + 5) % 5;
        uint32_t pat[5];
        for (int u = 0; u < 5; u++) {
            bool inDot = u <= 1 && v <= 1;
            bool inSoft = (v <= 1 && (u <= 2 || u == 4)) || (u <= 1 && (v <= 2 || v == 4));
            pat[u] = inDot ? dot : inSoft ? soft : base;
        }
        for (int row = yy; row < (int) y2; row++) {
            if (row < c->cy0 || row >= c->cy1) continue;
            uint32_t* px = c->px + row * c->w;
            int u = ((x0 - ax) / d) % 5, j = (x0 - ax) % d;
            if (d == 1) {
                // A game pixel a canvas pixel (the 3DS): the 5 colours in turn, 5 at a time.
                const uint32_t a0 = pat[u], a1 = pat[(u + 1) % 5], a2 = pat[(u + 2) % 5], a3 = pat[(u + 3) % 5], a4 = pat[(u + 4) % 5];
                uint32_t* q = px + x0;
                int n = x1 - x0;
                for (; n >= 5; n -= 5, q += 5) {
                    q[0] = a0;
                    q[1] = a1;
                    q[2] = a2;
                    q[3] = a3;
                    q[4] = a4;
                }
                const uint32_t tail[4] = { a0, a1, a2, a3 };
                for (int i = 0; i < n; i++) q[i] = tail[i];
                continue;
            }
            for (int x = x0; x < x1; x++) {
                px[x] = pat[u];
                if (++j == d) {
                    j = 0;
                    if (++u == 5) u = 0;
                }
            }
        }
    }
}

// ---- Lettering ----------------------------------------------------------------------------------------------------------

static uint32_t utf8_next(const char** p) {
    const unsigned char* s = (const unsigned char*) *p;
    uint32_t cp = *s++;
    if (cp >= 0xC0 && cp < 0xE0 && *s) cp = (cp & 0x1F) << 6 | (*s++ & 0x3F);
    else if (cp >= 0xE0 && cp < 0xF0 && s[0] && s[1]) {
        cp = (cp & 0x0F) << 12 | (s[0] & 0x3F) << 6 | (s[1] & 0x3F);
        s += 2;
    }
    *p = (const char*) s;
    return cp;
}

// The capitals' rows in the font (H's first and last), for two-tone lettering split at their middle.
static void caps_rows(int* first, int* last) {
    static int a = -1, b = -1;
    if (a < 0) {
        const struct pd_glyph* h = pd_font_glyph('H');
        for (int r = 0; r < PD_FONT_HEIGHT; r++)
            if (h->rows[r]) {
                if (a < 0) a = r;
                b = r;
            }
        if (a < 0) a = 3, b = 11;
    }
    *first = a;
    *last = b;
}

// Lays out [text] with [spacing] font pixels between letters into [mask] (a byte per font pixel, [mw] wide, rows 0..15
// shifted down 1 and right 1 to leave the outline room; NULL just measures). Returns the letters' width in font pixels.
static int letters(uint8_t* mask, int mw, const char* text, int spacing) {
    int at = 0;
    for (const char* p = text; *p;) {
        const struct pd_glyph* gl = pd_font_glyph(utf8_next(&p));
        if (mask)
            for (int r = 0; r < PD_FONT_HEIGHT; r++) {
                uint16_t bits = gl->rows[r];
                for (int i = 0; bits; i++, bits >>= 1)
                    if ((bits & 1) && 1 + at + i < mw - 2) {
                        mask[(1 + r) * mw + 1 + at + i] = 1;
                        mask[(1 + r) * mw + 2 + at + i] = 1; // the bold copy, a font pixel right
                    }
            }
        at += gl->advance + spacing;
    }
    return at - spacing;
}

// A label the way the game letters its plates (Gen5Label): pixel bold (each glyph twice, a font pixel apart), outlined a
// font pixel all round, [top] over the capitals' upper half and [bottom] under it. [k] the largest font pixel tried; with
// [maxW] > 0 a size or the letters' spacing gives way until it fits. Centred on (cx, cy): cy the capitals' middle. Drawn
// from a mask in one pass: letters, then the outline round them.
#define LABEL_MAX_W 200
static void label5(struct pd_canvas* c, int cx, int cy, const char* text, struct label_colors col, uint32_t outline, int k, int maxW) {
    int spacing = 1, w = 0;
    for (; k >= 1; k--) {
        spacing = 1;
        w = letters(NULL, 0, text, spacing) + 3;
        if (maxW <= 0 || w * k <= maxW) break;
        spacing = 0;
        w = letters(NULL, 0, text, spacing) + 3;
        if (w * k <= maxW || k == 1) break;
    }
    if (k < 1) k = 1;
    const int mw = w + 1, mh = PD_FONT_HEIGHT + 2;
    if (mw > LABEL_MAX_W) return;
    static uint8_t mask[LABEL_MAX_W * (PD_FONT_HEIGHT + 2)];
    memset(mask, 0, (size_t) mw * mh);
    letters(mask, mw, text, spacing);
    int a, b;
    caps_rows(&a, &b);
    int split = (a + b + 1) / 2 + 1, x0 = cx - (w * k) / 2, y0 = cy - split * k;
    for (int r = 0; r < mh; r++) {
        for (int i = 0; i < mw; i++) {
            uint32_t color;
            if (mask[r * mw + i]) color = r < split ? col.top : col.bottom;
            else {
                // The outline: next to a letter pixel (the bold copy's left neighbour reaches 2 right of the glyph's).
                bool near = false;
                for (int dy = -1; dy <= 1 && !near; dy++)
                    for (int dx = -1; dx <= 1 && !near; dx++) {
                        int rr = r + dy, ii = i + dx;
                        near = rr >= 0 && rr < mh && ii >= 0 && ii < mw && mask[rr * mw + ii];
                    }
                if (!near) continue;
                color = outline;
            }
            pd_fill(c, x0 + i * k, y0 + r * k, k, k, color);
        }
    }
}

// ---- Backdrops ----------------------------------------------------------------------------------------------------------

// The ball: red above (two faint rings toward the top corners), pale lilac below, the dark band between swelling into
// FIGHT!'s lens; [open] grows the lens into the move plates' warm grey field. Each
// column's edges first, then the screen a row at a time, every pixel written once (columns of fills missed the 3DS's cache
// on every pixel: ~25 ms a redraw).
#define BALL_MAX_W 1024
static void ball_render(struct pd_canvas* c, const struct g5* g, bool open) {
    static const struct { int inTop, inBottom; uint32_t band, field; } LAYERS[8] = {
        { -3, 0, BAND_SHADE_HI, FIELD_SHADE }, { -2, 0, BAND_SHADE, FIELD_SHADE }, { 0, -4, PALE_SHADE3, FIELD_PALE_SHADE2 },
        { 0, -3, PALE_SHADE2, FIELD_PALE_SHADE2 }, { 0, -1, PALE_SHADE1, FIELD_PALE_SHADE1 }, { 0, 0, 0x000000, FIELD_OUTLINE },
        { 3, 3, BAND_EDGE, FIELD_EDGE }, { 5, 5, BAND_FILL, FIELD_FILL },
    };
    static int ring1[BALL_MAX_W], ring2[BALL_MAX_W], lilac1[BALL_MAX_W], lilac2[BALL_MAX_W], bandT[BALL_MAX_W], bandB[BALL_MAX_W];
    const int d = g->d, w = c->w, h = c->h, bandY = (int) g->bandY;
    if (w > BALL_MAX_W) return;
    uint32_t layer[8];
    for (int i = 0; i < 8; i++) layer[i] = open ? LAYERS[i].field : LAYERS[i].band;
    for (int x = 0; x < w; x += d) {
        float mid = x + d / 2.f;
        // The rings: circles round the ball's middle at the bottom edge (centre (128, 192), radii 193 / 197): red above them.
        int rings[2], lilacs[2];
        for (int i = 0; i < 2; i++) {
            float r = i ? 197.f : 193.f, u = (mid - w / 2.f) / (r * g->sx);
            rings[i] = fabsf(u) < 1 ? snap(g, 176.f * g->sy - r * g->sy * sqrtf(1 - u * u)) : h / 2;
            // Lilac toward the bottom corners: below circles round a point above the screen's middle.
            float rl = i ? 192.f : 189.f, ul = (mid - w / 2.f) / (rl * g->sx);
            lilacs[i] = fabsf(ul) < 1 ? snap(g, -30.f * g->sy + rl * g->sy * sqrtf(1 - ul * ul)) : bandY;
            if (lilacs[i] < bandY) lilacs[i] = bandY;
        }
        float u = fabsf(mid - w / 2.f) / (w / 2.f);
        int t = open ? snap(g, field_top(u) * g->sy) : snap(g, g->bandY - lens_half(u) * g->sy);
        int b = open ? snap(g, field_bottom(u) * g->sy) : snap(g, g->bandY + lens_half(u) * g->sy);
        for (int j = 0; j < d && x + j < w; j++) {
            ring1[x + j] = rings[0];
            ring2[x + j] = rings[1];
            lilac1[x + j] = lilacs[0];
            lilac2[x + j] = lilacs[1];
            bandT[x + j] = t;
            bandB[x + j] = b;
        }
    }
    for (int y = c->cy0; y < c->cy1; y++) {
        uint32_t* px = c->px + y * c->w;
        for (int x = c->cx0; x < c->cx1; x++) {
            int t = bandT[x], b = bandB[x];
            uint32_t col = 0;
            bool done = false;
            // The band's layers, the innermost (drawn last) first.
            if (y >= t - 3 * d && y < b + 4 * d) {
                for (int i = 7; i >= 0 && !done; i--)
                    if (y >= t + LAYERS[i].inTop * d && y < b - LAYERS[i].inBottom * d) {
                        col = layer[i];
                        done = true;
                    }
            }
            if (!done)
                col = y >= lilac2[x] ? PALE_LILAC_DEEP : y >= lilac1[x] ? PALE_LILAC : y >= bandY ? PALE
                    : y < ring2[x] ? RED_RING_OUTER : y < ring1[x] ? RED_RING : RED;
            px[x] = col;
        }
    }
}

// A picture kept as runs of one colour a row: drawing it again only writes (a kept copy of the pixels cost a read of every
// one too, ~1.5 ms a redraw on the 3DS).
struct runs {
    int w, h;
    int* rowStart; // h + 1 entries
    uint16_t* x;   // where each run starts
    uint32_t* color;
};

static bool runs_encode(struct runs* r, const uint32_t* px, int w, int h) {
    free(r->rowStart);
    free(r->x);
    free(r->color);
    r->rowStart = NULL;
    r->x = NULL;
    r->color = NULL;
    int n = 0;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            if (x == 0 || px[y * w + x] != px[y * w + x - 1]) n++;
    r->rowStart = malloc((size_t) (h + 1) * sizeof(int));
    r->x = malloc((size_t) n * sizeof(uint16_t));
    r->color = malloc((size_t) n * sizeof(uint32_t));
    if (!r->rowStart || !r->x || !r->color) {
        r->w = r->h = 0;
        return false;
    }
    n = 0;
    for (int y = 0; y < h; y++) {
        r->rowStart[y] = n;
        for (int x = 0; x < w; x++)
            if (x == 0 || px[y * w + x] != px[y * w + x - 1]) {
                r->x[n] = (uint16_t) x;
                r->color[n++] = px[y * w + x];
            }
    }
    r->rowStart[h] = n;
    r->w = w;
    r->h = h;
    return true;
}

// Draws the kept picture into [c]'s clip; on rows litTop..litBottom a run of [from] is drawn [to] instead.
static void runs_draw(const struct runs* r, struct pd_canvas* c, uint32_t from, uint32_t to, int litTop, int litBottom) {
    int y1 = c->cy1 < r->h ? c->cy1 : r->h;
    for (int y = c->cy0; y < y1; y++) {
        uint32_t* row = c->px + y * c->w;
        bool lit = y >= litTop && y < litBottom;
        for (int i = r->rowStart[y], end = r->rowStart[y + 1]; i < end; i++) {
            int xs = r->x[i], xe = i + 1 < end ? r->x[i + 1] : r->w;
            if (xs < c->cx0) xs = c->cx0;
            if (xe > c->cx1) xe = c->cx1;
            uint32_t col = lit && r->color[i] == from ? to : r->color[i];
            for (uint32_t* q = row + xs; q < row + xe; q++) *q = col;
        }
    }
}

// Draws a kept picture into [c]: from its runs once they're kept, else rendered with [render] straight into [c] and its runs
// read back from there (no scratch copy: a 300 KB buffer a build is a lot for the 3DS's heap). Only a whole-canvas draw
// (nothing clipped) is kept; anything else is just rendered.
static void runs_show(struct runs* r, struct pd_canvas* c, void (*render)(struct pd_canvas*, const void*), const void* arg,
                      uint32_t from, uint32_t to, int litTop, int litBottom) {
    if (!(r->rowStart && r->w == c->w && r->h == c->h)) {
        render(c, arg);
        bool whole = c->cx0 == 0 && c->cy0 == 0 && c->cx1 == c->w && c->cy1 == c->h;
        if (!whole || !runs_encode(r, c->px, c->w, c->h)) return;
        if (litBottom <= litTop) return;
    }
    runs_draw(r, c, from, to, litTop, litBottom);
}

struct ball_arg { const struct g5* g; bool open; };

static void ball_render_cb(struct pd_canvas* c, const void* arg) {
    const struct ball_arg* a = arg;
    ball_render(c, a->g, a->open);
}

// The ball, kept (one open, one closed) and drawn from its runs; FIGHT! held lights the lens's inside.
static void ball_screen(struct pd_canvas* c, const struct g5* g, bool open, bool lit) {
    static struct runs kept[2];
    struct ball_arg arg = { g, open };
    uint32_t from = open ? FIELD_FILL : BAND_FILL;
    int top = lit ? snap(g, g->bandY - 26.5f * g->sy) : 0, bottom = lit ? snap(g, g->bandY + 26.5f * g->sy) : 0;
    runs_show(&kept[open ? 1 : 0], c, ball_render_cb, &arg, from, pd_mix(from, 0xFFFFFF, 31), top, bottom);
}

// The battle's party screen: the ball again, darkened - deep red, a wide dark band, grey lilac under it.
static void party_screen(struct pd_canvas* c, const struct g5* g) {
    const int d = g->d, w = c->w, h = c->h;
    int bandTop = snap(g, h * .34f), bandBottom = snap(g, h * .62f);
    pd_fill(c, 0, 0, w, h, PARTY_RED);
    pd_fill(c, 0, 0, w, 2 * d, PARTY_RED_DARK);
    pd_fill(c, 0, bandTop - 5 * d, w, 3 * d, PARTY_RED_DARK);
    pd_fill(c, 0, bandTop - 2 * d, w, 2 * d, PARTY_RED_DEEPER);
    pd_fill(c, 0, bandTop, w, bandBottom - bandTop, PARTY_BAND);
    pd_fill(c, 0, bandTop, w, 2 * d, PARTY_BAND_EDGE);
    pd_fill(c, 0, bandBottom - 2 * d, w, 2 * d, PARTY_BAND_EDGE);
    for (int y = bandBottom; y < h; y++)
        pd_fill(c, 0, y, w, 1, pd_mix(PARTY_LILAC, PARTY_LILAC_DEEP, (y - bandBottom) * 256 / (h - bandBottom)));
}

// Between turns: the dark screen and the ball shut - dark red over grey, each half lighter inside its rim, the gap between
// them the band's lens again (a sliver at the sides, open in the middle).
static void closed_ball_render(struct pd_canvas* c, const void* arg) {
    (void) arg;
    struct g5 gg = g5_of(c->w, c->h - 32);
    const struct g5* g = &gg;
    const int d = g->d;
    pd_fill(c, 0, 0, c->w, c->h, NIGHT);
    float r = (float) glen(g, 70.f);
    struct shape s = { .kind = 3, .cx = (float) snap(g, c->w / 2.f), .cy = (float) snap(g, g->h * .5f), .r = r };
    float cy = s.cy;
    fill_shape(c, &s, cy - r, cy + r, d, BALL_OUTLINE);
    s.r = r - d;
    fill_shape(c, &s, cy - r, cy, d, BALL_TOP_RIM);
    fill_shape(c, &s, cy, cy + r, d, BALL_BOTTOM_RIM);
    s.r = r - 5 * d;
    fill_shape(c, &s, cy - r, cy, d, BALL_TOP);
    fill_shape(c, &s, cy, cy + r, d, BALL_BOTTOM);
    // The gap, column by column: 1.5 game pixels each way at the rim, 8.5 in the middle, outlined.
    for (float x = s.cx - r; x < s.cx + r; x += d) {
        float u = fabsf(x + d / 2.f - s.cx) / r, k = 1 - u * u;
        if (k < 0) k = 0;
        int half = snap(g, (1.5f + 7.f * k * k) * g->s);
        pd_fill(c, (int) x, (int) cy - half - d, d, 2 * half + 2 * d, BALL_OUTLINE);
        pd_fill(c, (int) x, (int) cy - half, d, 2 * half, NIGHT);
    }
}

void ui_gen5_closed_ball(struct pd_canvas* c) {
    static struct runs kept;
    runs_show(&kept, c, closed_ball_render, NULL, 0, 0, 0, 0);
}

// ---- The plates ---------------------------------------------------------------------------------------------------------

static bool pressed(const struct pd_ui* ui, int id) { return ui->pressedId == id && id >= 0; }

// One round plate (BAG, RUN, POKéMON, the U-turn): its centre at (cxDs, cyDs) on the game's screen, [radius] game pixels
// round; [bar] -1 / +1 joins it to the left / right edge from 0.63 radii down. Returns its centre x and label's centre y.
static void disc(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, float cxDs, float cyDs, float radius, int bar,
                 int id, bool enabled, int* labelX, int* labelY) {
    const int d = g->d;
    float r = (float) glen(g, radius);
    float cx = bar < 0 ? (float) glen(g, cxDs) : bar > 0 ? g->w - glen(g, 256.f - cxDs) : (float) gx(g, cxDs);
    float top = (float) gy(g, cyDs - radius), cy = top + r;
    float left = bar < 0 ? 0 : cx - r, right = bar > 0 ? g->w : cx + r;
    float barTop = top + snap(g, .63f * r);
    static const uint32_t RIM[4] = { RIM_OUTER_GREY, RIM_PALE, RIM_WHITE, RIM_INNER };
    static const int RIM_N[4] = { 1, 1, 3, 1 };
    float inset = 0;
    struct shape s = { .kind = 0, .cx = cx, .cy = cy, .bar = bar, .w = g->w };
    for (int i = 0; i < 4; i++) {
        s.r = r - inset;
        s.barTop = barTop + inset;
        struct shape in = s;
        in.r = r - inset - RIM_N[i] * d;
        in.barTop = barTop + inset + RIM_N[i] * d;
        fill_ring(c, &s, &in, cy - s.r, g->h, cy - in.r, g->h, d, RIM[i]);
        inset += RIM_N[i] * d;
    }
    s.r = r - inset;
    s.barTop = barTop + inset;
    fill_face(c, &s, cy - s.r, g->h, d, pressed(ui, id), 0);
    if (enabled) ui_add_hit(ui, (int) left, (int) top, (int) (right - left), (int) (g->h - top), id);
    *labelX = (int) cx;
    *labelY = (int) (top + (bar == 0 ? .76f : .92f) * r);
}

static void word_disc(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, float cxDs, float cyDs, int bar, const char* word,
                      struct label_colors col, int id, bool enabled) {
    int lx, ly;
    disc(ui, c, g, cxDs, cyDs, 38.f, bar, id, enabled, &lx, &ly);
    label5(c, lx, ly, word, col, LABEL_OUTLINE, g->d, (int) (glen(g, 38.f) * 1.85f));
}

// The game's back arrow, a U-turn: K its outline, B its blue, L the lit edge, D the shade.
static const char* const UTURN[12] = {
    "...K..........", "..KLK.........", ".KLBBK........", "KLBBBBK.......", "KKKBBKKK.KKKK.", "..KLBK...KLDK.",
    "..KLBK...KLDK.", "..KLBK...KLDK.", "..KLBBK.KLBDK.", "...KLBBKLBDK..", "....KLBBBDK...", ".....KKKKK....",
};

static void back_disc(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, bool enabled, float radius, float cyDs) {
    int lx, ly;
    disc(ui, c, g, 256.f - 40.5f * radius / 38.f, cyDs, radius, 1, HIT_BATTLE_CANCEL, enabled, &lx, &ly);
    int k = (int) (36.f * g->d * radius / 38.f) / 14;
    if (k < 1) k = 1;
    int x0 = lx - 7 * k, y0 = ly - 6 * k;
    for (int r = 0; r < 12; r++)
        for (int i = 0; i < 14; i++) {
            char ch = UTURN[r][i];
            if (ch == '.') continue;
            uint32_t col = ch == 'K' ? 0x202020 : ch == 'B' ? 0x28BAF3 : ch == 'L' ? 0xA2DBFB : 0x3882AA;
            pd_fill(c, x0 + i * k, y0 + r * k, k, k, col);
        }
}

// INFO and SUGGESTIONS: small plates in the red corners above the band (ours; the game has its clock there).
static void small_plate(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, int x, int y, int w, int h, const char* word,
                        struct label_colors col, int id) {
    const int d = g->d;
    static const uint32_t RIM[3] = { RIM_OUTER_GREY, RIM_WHITE, RIM_INNER };
    static const int RIM_N[3] = { 1, 2, 1 };
    int inset = 0;
    struct shape s = { .kind = 1 };
    for (int i = 0; i < 3; i++) {
        s.l = (float) (x + inset);
        s.t = (float) (y + inset);
        s.sw = (float) (w - 2 * inset);
        s.sh = (float) (h - 2 * inset);
        int n = RIM_N[i] * d;
        struct shape in = { .kind = 1, .l = s.l + n, .t = s.t + n, .sw = s.sw - 2 * n, .sh = s.sh - 2 * n };
        fill_ring(c, &s, &in, s.t, s.t + s.sh, in.t, in.t + in.sh, d, RIM[i]);
        inset += n;
    }
    s.l = (float) (x + inset);
    s.t = (float) (y + inset);
    s.sw = (float) (w - 2 * inset);
    s.sh = (float) (h - 2 * inset);
    fill_face(c, &s, s.t, s.t + s.sh, d, pressed(ui, id), x);
    label5(c, x + w / 2, y + h / 2, word, col, LABEL_OUTLINE, d, w - h);
    ui_add_hit(ui, x, y, w, h, id);
}

static void info_plates(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g) {
    int m = glen(g, 3.f), h = glen(g, 21.f);
    small_plate(ui, c, g, m, m, gx(g, 70.f), h, "INFO", INFO_COLORS, HIT_BATTLE_INFO);
    int sw = gx(g, 110.f);
    small_plate(ui, c, g, c->w - m - sw, m, sw, h, "SUGGESTIONS", SUGGEST_COLORS, HIT_BATTLE_SUGGEST);
}

// The party's balls in a row of six: a ball per member (greyed once fainted), a dot per empty slot - yours from the left
// (13 game pixels each), the foe's from the right in the red (7 each).
static const char* const BALL_BIG[13] = {
    "....ooooo....", "..oorrrrroo..", ".orrrrrrrrro.", ".orrrrrrrrso.", "orrrrooorrsso", "osssowwwossso", "ooooowwwooooo",
    "owwwowwwowwso", "owwwwooowwwso", ".owwwwwwwwso.", ".owwwwwwwsso.", "..oowwwwsoo..", "....ooooo....",
};
static const char* const BALL_SMALL[7] = { "..ooo..", ".orrro.", "orrrrso", "ooowooo", "owwwwso", ".owwso.", "..ooo.." };

static void party_balls(struct pd_canvas* c, const struct g5* g, const struct pd_mon* mons, int count, float centreY, bool small) {
    const int px = g->d, n = small ? 7 : 13;
    const char* const* ball = small ? BALL_SMALL : BALL_BIG;
    float cell = small ? 8.f : 16.f, total = 6 * cell * g->s;
    int left = snap(g, g->w / 2 - total / 2 + (small ? 3.f * g->s : 0)), top = snap(g, gy(g, centreY) - n * px / 2.f);
    if (count > PD_PARTY_SIZE) count = PD_PARTY_SIZE;
    for (int slot = 0; slot < PD_PARTY_SIZE; slot++) {
        // The foe's fill from the right: its empty dots come first.
        int i = small ? slot - (PD_PARTY_SIZE - count) : slot;
        int x0 = left + (int) lroundf(slot * cell * g->s / px) * px;
        if (i < 0 || i >= count) {
            int dot = small ? 2 : 4, o = (n - dot) / 2 * px;
            pd_fill(c, x0 + o, top + o, dot * px, dot * px, BALL_LINE);
            continue;
        }
        bool alive = mons[i].hp > 0 && !mons[i].egg;
        for (int r = 0; r < n; r++)
            for (int k = 0; ball[r][k]; k++) {
                char ch = ball[r][k];
                if (ch == '.') continue;
                uint32_t col = ch == 'o' ? 0x595959 : ch == 'r' ? (alive ? 0xFB6982 : 0x9A9AA2)
                    : ch == 's' ? (alive ? 0xA26969 : 0x79797F) : (alive ? 0xEBE3F3 : 0xC3C3CB);
                pd_fill(c, x0 + k * px, top + r * px, px, px, col);
            }
    }
}

// One move plate (the game's hexagon): its type's colour round the charcoal, then the name, the type and PP, then (ours)
// the power and, with BATTLE HINTS, the verdict.
static void move_plate(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, int x, int y, int w, int h, int move, int pp,
                       const struct pd_battle_mon* foe, int index, bool busy, bool hints) {
    const int d = g->d, id = HIT_BATTLE_MOVE + index;
    bool usable = move && pp > 0;
    int type = move ? pd_move_type(ui_game, move) : 0;
    uint32_t rim = move ? ui_type_color(type) : EMPTY_RIM;
    static uint32_t before[160 * 80];
    bool fade = move && !usable && w * h <= (int) (sizeof(before) / sizeof(before[0]));
    if (fade)
        for (int r = 0; r < h; r++) memcpy(before + r * w, c->px + (y + r) * c->w + x, (size_t) w * 4);
    struct shape s = { .kind = 1, .l = (float) x, .t = (float) y, .sw = (float) w, .sh = (float) h };
    struct shape in = { .kind = 1, .l = (float) (x + d), .t = (float) (y + d), .sw = (float) (w - 2 * d), .sh = (float) (h - 2 * d) };
    struct shape f = { .kind = 1, .l = (float) (x + 4 * d), .t = (float) (y + 3 * d), .sw = (float) (w - 8 * d), .sh = (float) (h - 6 * d) };
    fill_ring(c, &s, &in, s.t, s.t + s.sh, in.t, in.t + in.sh, d, 0x000000);
    float mid = y + h / 2.f;
    fill_ring(c, &in, &f, in.t, mid, f.t, f.t + f.sh, d, rim);
    fill_ring(c, &in, &f, mid, in.t + in.sh, f.t, f.t + f.sh, d, pd_mix(rim, 0, 56));
    fill_face(c, &f, f.t, f.t + f.sh, d, pressed(ui, id), x);
    if (usable && !busy) ui_add_hit(ui, x, y, w, h, id);
    if (!move) {
        pd_text(c, x + (w - pd_text_width("-")) / 2, pd_text_y(y, h), "-", EMPTY_RIM, PD_NO_SHADOW);
        return;
    }
    // Three lines centred, closer together on a short plate.
    char buf[40], ppText[12], pwr[16];
    int pitch = h >= 62 ? 18 : 15, ty = y + (h - 2 * pitch - 14) / 2 - 2, iw = w - w / 5;
    pd_move_name(ui_game, move, buf, sizeof(buf));
    int nw = pd_text_width(buf);
    if (nw > iw) nw = iw;
    pd_text_fit(c, x + (w - nw) / 2, ty, iw, buf, PLATE_TEXT, PLATE_TEXT_SHADOW);
    snprintf(ppText, sizeof(ppText), "PP %d", pp);
    int ppW = pd_text_width(ppText), tagW = ui_type_badge(NULL, 0, 0, type), lineW = tagW + 4 + ppW;
    int lx = x + (w - lineW) / 2;
    ui_type_badge(c, lx, ty + pitch + 2, type);
    pd_text(c, lx + tagW + 4, ty + pitch, ppText, PLATE_TEXT, PLATE_TEXT_SHADOW);
    int power = pd_move_power(ui_game, move);
    if (power > 0) snprintf(pwr, sizeof(pwr), "PWR %d", power);
    else snprintf(pwr, sizeof(pwr), "PWR -");
    int pct = hints && foe && foe->species && power > 0 ? pd_type_multiplier_pct(ui_game, type, foe->type1, foe->type2) : -1;
    int chipW = pct >= 0 ? ui_mult_chip(NULL, 0, 0, pct) + 3 : 0, pw = pd_text_width(pwr);
    int px = x + (w - pw - chipW) / 2;
    pd_text(c, px, ty + 2 * pitch, pwr, PLATE_MUTED, PLATE_TEXT_SHADOW);
    if (pct >= 0) ui_mult_chip(c, px + pw + 3, ty + 2 * pitch + 2, pct);
    if (fade) ui_fade_rect(c, before, x, y, w, h, 128);
}

static void move_plates(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, const struct pd_snapshot* s, float top,
                        float height, bool busy, bool hints) {
    const struct pd_battle_mon* you = &s->battlers[PD_POS_PLAYER_LEFT];
    int gap = glen(g, 1.5f), plateW = (c->w - 3 * gap) / 2, rowH = (gy(g, top + height) - gy(g, top)) / 2;
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        int l = snap(g, (float) (gap + (i % 2) * (plateW + gap))), t = snap(g, (float) (gy(g, top) + (i / 2) * rowH));
        move_plate(ui, c, g, l, t, snap(g, (float) plateW), snap(g, (float) rowH), you->moves[i], you->pp[i],
                   &s->battlers[PD_POS_OPPONENT_LEFT], i, busy, hints);
    }
}

// Not your turn: the game's message band along the bottom (dark, a red line along its top), the words in white.
static void message_band(struct pd_canvas* c, const struct g5* g, const char* text) {
    const int d = g->d, bandH = glen(g, 30.f), y = c->h - bandH;
    pd_fill(c, 0, y, c->w, bandH, MSG_BAND);
    pd_fill(c, 0, y, c->w, d, MSG_LINE);
    pd_fill(c, 0, y + d, c->w, d, MSG_LINE_SHADOW);
    pd_text(c, glen(g, 8.f), pd_text_y(y + 2 * d, bandH - 2 * d), text, 0xFFFFFF, MSG_SHADOW);
}

// ---- The party screen -----------------------------------------------------------------------------------------------------

// "HP" in the strip, a 3 x 5 pixel tag (the app's is a half-size font, which the 3DS's can't go below).
static void hp_tag(struct pd_canvas* c, int x, int y, int d) {
    static const char* const HP[5] = { "X.X.XX.", "X.X.X.X", "XXX.XX.", "X.X.X..", "X.X.X.." };
    for (int r = 0; r < 5; r++)
        for (int i = 0; HP[r][i]; i++)
            if (HP[r][i] == 'X') pd_fill(c, x + i * d, y + r * d, d, d, HP_LABEL);
}

static void party_slot(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, const struct pd_game* game, int x, int y,
                       int w, int h, const struct pd_mon* m, int i, bool selected, bool best, bool out, bool enabled) {
    const int d = g->d, id = HIT_SLOT + i, cut = 5 * d;
    bool fainted = m && m->hp <= 0 && !m->egg;
    const uint32_t* rim = !m ? EMPTY_SLOT_RIM : fainted ? FAINT_RIM : GREEN_RIM;
    struct shape o = { .kind = 2, .l = (float) x, .t = (float) y, .sw = (float) w, .sh = (float) h, .cut = (float) cut };
    struct shape s = { .kind = 2, .l = (float) (x + d), .t = (float) (y + d), .sw = (float) (w - 2 * d), .sh = (float) (h - 2 * d), .cut = (float) cut };
    struct shape in = { .kind = 2, .l = (float) (x + 3 * d), .t = (float) (y + 3 * d), .sw = (float) (w - 6 * d), .sh = (float) (h - 6 * d), .cut = (float) (cut - d) };
    struct shape f = { .kind = 2, .l = (float) (x + 4 * d), .t = (float) (y + 4 * d), .sw = (float) (w - 8 * d), .sh = (float) (h - 8 * d), .cut = (float) (cut - 2 * d) };
    float lower = (float) (y + (int) (h * .55f));
    fill_ring(c, &o, &s, o.t, o.t + o.sh, s.t, s.t + s.sh, d, SLOT_OUTLINE);
    fill_ring(c, &s, &in, s.t, lower, in.t, in.t + in.sh, d, rim[0]);
    fill_ring(c, &s, &in, lower, s.t + s.sh, in.t, in.t + in.sh, d, rim[1]);
    fill_ring(c, &in, &f, in.t, in.t + in.sh, f.t, f.t + f.sh, d, rim[2]);
    int stripY = y + snap(g, h * .52f);
    if (!m) {
        fill_shape(c, &f, f.t, f.t + f.sh, d, EMPTY_SLOT_FACE);
        pd_fill(c, x + 5 * d, y + snap(g, h * .55f), w - 10 * d, 2 * d, EMPTY_SLOT_STRIP);
        return;
    }
    fill_face(c, &f, f.t, f.t + f.sh, d, pressed(ui, id) || selected, x);
    // The pale strip the HP bar sits in.
    pd_fill(c, x + 5 * d, stripY, w - 10 * d, 7 * d, STRIP_LINE);
    pd_fill(c, x + 5 * d, stripY + d, w - 10 * d, 5 * d, STRIP);
    if (!m->egg) {
        int barL = x + snap(g, w * .46f), barR = x + w - 10 * d;
        pd_fill(c, barL, stripY + 2 * d, barR - barL, 3 * d, BAR_FRAME);
        int fillW = m->maxHp > 0 ? (barR - barL - 2 * d) * m->hp / m->maxHp : 0;
        if (fillW == 0 && m->hp > 0) fillW = d;
        uint32_t col = m->hp * 2 > m->maxHp ? HP_GREEN : m->hp * 5 > m->maxHp ? HP_YELLOW : HP_RED;
        pd_fill(c, barL + d, stripY + 3 * d, snap(g, (float) fillW), d, col);
        hp_tag(c, barL - 9 * d, stripY + d, d);
    }
    // The icon top left, the name beside it, the gender mark at the right.
    pd_draw_mon_icon(c, game, m->species, x + 2 * d, y - d, fainted);
    int nx = x + PD_ICON + 3 * d, ny = y + 7 * d, right = x + w - 7 * d;
    const char* mark = m->egg ? NULL : m->gender == PD_GENDER_MALE ? "♂" : m->gender == PD_GENDER_FEMALE ? "♀" : NULL;
    int markW = mark ? pd_text_width(mark) + 2 : 0;
    pd_text_fit(c, nx, ny, right - nx - markW, m->egg ? "EGG" : m->nickname, SLOT_TEXT, SLOT_TEXT_SHADOW);
    if (mark) pd_text(c, right - markW + 2, ny, mark, m->gender == PD_GENDER_MALE ? MALE_BLUE : FEMALE_RED, PD_NO_SHADOW);
    // Ours: BEST (BATTLE HINTS' pick) / OUT on the rim's top right.
    if (best || out) {
        const char* tag = best ? "BEST" : "OUT";
        int tw = pd_text_width(tag) + 6;
        pd_round_rect(c, x + w - 10 * d - tw, y - 2 * d, tw, 12, 2, best ? SUGGEST_GOLD : VERDICT_GREY);
        pd_text(c, x + w - 10 * d - tw + 3, y - 4 * d, tag, 0xFFFFFF, PD_NO_SHADOW);
    }
    if (!m->egg) {
        // The level and HP under the strip.
        char t[16];
        int ly = stripY + 6 * d;
        snprintf(t, sizeof(t), "Lv.%d", m->level);
        int lw = pd_text(c, x + 7 * d, ly, t, SLOT_TEXT, SLOT_TEXT_SHADOW);
        if (m->status) ui_status_badge(c, x + 7 * d + lw + 6, ly + 3, m->status);
        snprintf(t, sizeof(t), "%d/%d", m->hp, m->maxHp);
        pd_text_right(c, right, ly, t, SLOT_TEXT, SLOT_TEXT_SHADOW);
    }
    if (enabled) ui_add_hit(ui, x, y, w, h, id);
}

static void party_picker5(struct pd_ui* ui, struct pd_canvas* c, const struct g5* g, const struct pd_game* game,
                          const struct pd_snapshot* s, bool busy, bool hints) {
    const int d = g->d;
    party_screen(c, g);
    int best = hints ? ui_recommended_switch(s) : -1, out = ui_out_slots(s);
    int cursor = best >= 0 ? best : -1;
    for (int i = 0; cursor < 0 && i < s->partyCount; i++)
        if (out >> i & 1) cursor = i;
    int gap = glen(g, 2.f), bottom = glen(g, 34.f);
    int slotW = snap(g, (c->w - 3 * gap) / 2.f), slotH = snap(g, (c->h - bottom - 4 * gap) / 3.f);
    for (int i = 0; i < PD_PARTY_SIZE; i++) {
        const struct pd_mon* m = i < s->partyCount ? &s->party[i] : NULL;
        bool canPick = m && m->hp > 0 && !m->egg && !(out >> i & 1);
        party_slot(ui, c, g, game, gap + (i % 2) * (slotW + gap), gap + (i / 2) * (slotH + gap), slotW, slotH, m, i,
                   i == cursor, i == best, out >> i & 1, canPick && !busy);
    }
    if (cursor >= 0) ui->startId = HIT_SLOT + cursor;
    // "Choose a POKéMON." in the game's white box, left of the U-turn.
    int boxH = glen(g, 24.f), boxL = glen(g, 6.f), boxR = c->w - glen(g, 70.f), boxY = c->h - glen(g, 5.f) - boxH;
    pd_fill(c, boxL, boxY, boxR - boxL, boxH, 0x000000);
    pd_fill(c, boxL + d, boxY + d, boxR - boxL - 2 * d, boxH - 2 * d, MSG_BOX_LINE);
    pd_fill(c, boxL + 2 * d, boxY + 2 * d, boxR - boxL - 4 * d, boxH - 4 * d, MSG_BOX_FILL);
    pd_text(c, boxL + 6 * d, pd_text_y(boxY, boxH), "Choose a POKéMON.", MSG_BOX_TEXT, MSG_BOX_SHADOW);
    back_disc(ui, c, g, !busy, 30.f, 170.f);
}

// ---- The pane -----------------------------------------------------------------------------------------------------------

void ui_battle_gen5(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* game, const struct pd_snapshot* s, bool busy) {
    const struct g5 gg = g5_of(c->w, c->h);
    const struct g5* g = &gg;
    bool hints = ui_battle_hints_on(ui);
    bool oneTap = ui->settings && ui->settings->oneTapMoves;
    bool canSwitch = ui->battleInput && pd_binput_can_switch(ui->battleInput);
    enum pd_battle_state st = s->battleState;
    bool picking = (st == PD_BATTLE_ACTION && ui->battleChoosingMon) || (st == PD_BATTLE_PARTY_OPEN && canSwitch);
    if (picking) {
        party_picker5(ui, c, g, game, s, busy, hints);
        return;
    }
    bool moves = st == PD_BATTLE_MOVE || (st == PD_BATTLE_ACTION && ui->battleChoosingMove);
    if (moves) {
        ball_screen(c, g, true, false);
        move_plates(ui, c, g, s, 5.f, 112.f, busy, hints);
        party_balls(c, g, s->party, s->partyCount, 126.f, false);
        back_disc(ui, c, g, !busy, 38.f, 154.f);
        return;
    }
    if (st == PD_BATTLE_ACTION) {
        bool enabled = !busy;
        if (oneTap) {
            ball_screen(c, g, true, false);
            move_plates(ui, c, g, s, 3.f, 96.f, busy, hints);
            party_balls(c, g, s->party, s->partyCount, 108.f, false);
        } else {
            ball_screen(c, g, false, pressed(ui, HIT_BATTLE_FIGHT));
            if (s->isTrainer && s->foeCount > 0) party_balls(c, g, s->foes, s->foeCount, 31.f, true);
            // FIGHT!: the lens itself is the button.
            int lx = gx(g, 48.f), top = snap(g, g->bandY - lens_half(.5f) * g->sy), bot = snap(g, g->bandY + lens_half(.5f) * g->sy);
            if (enabled) ui_add_hit(ui, lx, top, c->w - 2 * lx, bot - top, HIT_BATTLE_FIGHT);
            ui->startId = HIT_BATTLE_FIGHT;
            int k = (int) lroundf(g->s * 1.3f);
            if (k < 1) k = 1;
            label5(c, c->w / 2, (int) g->bandY, "FIGHT!", (struct label_colors) { FIGHT_RED, FIGHT_RED }, 0x000000, k, 0);
            party_balls(c, g, s->party, s->partyCount, 119.f, false);
        }
        word_disc(ui, c, g, 39.5f, 154.f, -1, "BAG", BAG_COLORS, HIT_BATTLE_BAG, enabled);
        word_disc(ui, c, g, 127.5f, 170.f, 0, "RUN", RUN_COLORS, HIT_BATTLE_RUN, enabled);
        word_disc(ui, c, g, 215.5f, 154.f, 1, "POKéMON", MON_COLORS, HIT_BATTLE_POKEMON, enabled);
        if (!oneTap && ui_battle_info_on(ui)) info_plates(ui, c, g);
        return;
    }
    // Between turns, or the game's bag / party up: the closed ball in the dark, the message, the way back.
    struct pd_canvas k = *c;
    k.h += 32; // the closed ball is drawn for the whole companion, as its backdrop is
    if (k.cy1 > c->h) k.cy1 = c->h;
    ui_gen5_closed_ball(&k);
    if (st == PD_BATTLE_BAG_OPEN || st == PD_BATTLE_PARTY_OPEN) {
        back_disc(ui, c, g, !busy, 38.f, 154.f);
        return;
    }
    if (st == PD_BATTLE_TARGET) message_band(c, g, "Confirming target...");
    else if (!ui->ranAway && !busy) message_band(c, g, "Waiting for your turn...");
    if (ui_battle_info_on(ui)) info_plates(ui, c, g);
}

void ui_gen5_backdrop(struct pd_ui* ui, struct pd_canvas* c, const struct pd_snapshot* s) {
    bool covered = s && s->inBattle && s->battlers[PD_POS_PLAYER_LEFT].species && ui_battle_controls_usable(ui, s) &&
        !ui->battleInfoOpen && !ui->battlePane;
    if (covered) pd_fill(c, 0, BAR_Y(c) - 2, c->w, c->h - BAR_Y(c) + 2, NIGHT);
    else ui_gen5_closed_ball(c);
}

bool ui_gen5_style(const struct pd_ui* ui, const struct pd_game* g) {
    return ui->settings && ui->settings->battleGen5 && !(g && g->cfg && g->cfg->gb);
}
