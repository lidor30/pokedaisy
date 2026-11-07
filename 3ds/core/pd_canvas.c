#include "pd_canvas.h"

#include <math.h>
#include <string.h>

#include "pd_font.h"

void pd_canvas_init(struct pd_canvas* c, int w, int h, uint32_t* px) {
    c->w = w;
    c->h = h;
    c->px = px;
    pd_unclip(c);
}

void pd_clip(struct pd_canvas* c, int x, int y, int w, int h) {
    c->cx0 = x < 0 ? 0 : x;
    c->cy0 = y < 0 ? 0 : y;
    c->cx1 = x + w > c->w ? c->w : x + w;
    c->cy1 = y + h > c->h ? c->h : y + h;
}

void pd_unclip(struct pd_canvas* c) {
    c->cx0 = 0;
    c->cy0 = 0;
    c->cx1 = c->w;
    c->cy1 = c->h;
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

int pd_layered_box(struct pd_canvas* c, int x, int y, int w, int h, int r,
                   const struct pd_layer* layers, int n, uint32_t fill) {
    int inset = 0;
    for (int i = 0; i < n; i++) {
        pd_round_rect(c, x + inset, y + inset, w - 2 * inset, h - 2 * inset, r > 0 ? r : 0, layers[i].color);
        inset += layers[i].width;
        r -= layers[i].width;
    }
    pd_round_rect(c, x + inset, y + inset, w - 2 * inset, h - 2 * inset, r > 0 ? r : 0, fill);
    return inset;
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
const struct pd_glyph* pd_font_glyph(uint32_t cp) {
    for (int i = 0; i < custom_count; i++)
        if (custom_font[i].codepoint == cp) return &custom_font[i];
    if (cp >= 32 && cp < 127) return &pd_font_glyphs[cp - 32];
    for (int i = 95; i < pd_font_glyph_count; i++) {
        if (pd_font_glyphs[i].codepoint == cp) return &pd_font_glyphs[i];
    }
    return &pd_font_glyphs['?' - 32];
}

static void draw_glyph(struct pd_canvas* c, int x, int y, const struct pd_glyph* g, uint32_t color) {
    for (int row = 0; row < PD_FONT_HEIGHT; row++) {
        uint16_t bits = g->rows[row];
        for (int col = 0; bits; col++, bits >>= 1) {
            if (bits & 1) plot(c, x + col, y + row, color);
        }
    }
}

static int draw_run(struct pd_canvas* c, int x, int y, const char* s, uint32_t color) {
    int x0 = x;
    while (*s) {
        const struct pd_glyph* g = pd_font_glyph(next_codepoint(&s));
        draw_glyph(c, x, y, g, color);
        x += g->advance;
    }
    return x - x0;
}

int pd_text(struct pd_canvas* c, int x, int y, const char* s, uint32_t color, uint32_t shadow) {
    if (shadow != PD_NO_SHADOW) {
        draw_run(c, x + 1, y, s, shadow);
        draw_run(c, x, y + 1, s, shadow);
        draw_run(c, x + 1, y + 1, s, shadow);
    }
    return draw_run(c, x, y, s, color);
}

int pd_text_width(const char* s) {
    int w = 0;
    while (*s) w += pd_font_glyph(next_codepoint(&s))->advance;
    return w;
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
    int keep = 255 - alpha;
    for (int i = 0; i < c->w * c->h; i++) {
        uint32_t p = c->px[i];
        uint32_t r = ((p >> 16) & 0xFF) * (uint32_t) keep / 255;
        uint32_t g = ((p >> 8) & 0xFF) * (uint32_t) keep / 255;
        uint32_t b = (p & 0xFF) * (uint32_t) keep / 255;
        c->px[i] = (r << 16) | (g << 8) | b;
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
