#include <stdio.h>
#include <string.h>

#include "pd_party_art.h"
#include "pd_tr.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define HP_FRAME 0x525252
#define HP_LABEL 0xFFD652
#define HP_EMPTY 0x737373
struct hp_colors {
    uint32_t top, main;
};
// FireRedPartyPalette's HP bar (PartyScreen.kt).
static const struct hp_colors HP_GREEN = { 0x5AD684, 0x73FFAD };
static const struct hp_colors HP_YELLOW = { 0xCEAD08, 0xFFE739 };
static const struct hp_colors HP_RED = { 0xC63900, 0xFF7331 };

#define STATUS_FILL_PSN 0xA040A0
#define STATUS_FILL_PAR 0xB8B818
#define STATUS_FILL_SLP 0x8C8C94
#define STATUS_FILL_BRN 0xE05030
#define STATUS_FILL_FRZ 0x58A8D8

static void add(struct pd_ui* ui, int x, int y, int w, int h, int id, bool scroll) {
    if (ui->hitCount >= PD_UI_MAX_HITS) return;
    ui->hits[ui->hitCount++] = (struct pd_hit) { x, y, w, h, id, scroll };
}

void ui_add_hit(struct pd_ui* ui, int x, int y, int w, int h, int id) {
    add(ui, x, y, w, h, id, false);
}

void ui_add_scroll_hit(struct pd_ui* ui, int x, int y, int w, int h, int id) {
    add(ui, x, y, w, h, id, true);
}

void ui_mono(struct pd_canvas* c, const struct pd_mono* icon, int x, int y, int scale, uint32_t color) {
    for (int r = 0; r < icon->h; r++)
        for (int col = 0; icon->rows[r][col]; col++)
            if (icon->rows[r][col] == '1') pd_fill(c, x + col * scale, y + r * scale, scale, scale, color);
}

void ui_row_divider(struct pd_canvas* c, int x, int y, int w) { pd_row_divider(c, x, y, w); }

void ui_icon_smooth(struct pd_canvas* c, const struct pd_art* art, const struct pd_mono* mono, uint32_t color, int x, int y, int size) {
    enum { MAX = 32 };
    const int w = art ? art->w : mono->w, h = art ? art->h : mono->h;
    if (w > MAX || h > MAX || size <= 0) return;
    // The picture, 0 = see-through (else 0xFF000000 | rgb), then Scale2x.
    uint32_t src[MAX * MAX], big[2 * MAX * 2 * MAX];
    for (int r = 0; r < h; r++)
        for (int col = 0; col < w; col++) {
            uint32_t p = 0;
            char k = art ? art->rows[r][col] : mono->rows[r][col];
            if (art) {
                const char* at = k && k != '.' ? strchr(art->keys, k) : NULL;
                if (at) p = 0xFF000000u | (art->colors[at - art->keys] & 0xFFFFFF);
            } else if (k == '1') p = 0xFF000000u | (color & 0xFFFFFF);
            src[r * w + col] = p;
        }
    const int bw = 2 * w, bh = 2 * h;
    for (int r = 0; r < h; r++)
        for (int col = 0; col < w; col++) {
            uint32_t e = src[r * w + col];
            uint32_t b = r > 0 ? src[(r - 1) * w + col] : e, hh = r < h - 1 ? src[(r + 1) * w + col] : e;
            uint32_t d = col > 0 ? src[r * w + col - 1] : e, f = col < w - 1 ? src[r * w + col + 1] : e;
            bool on = b != hh && d != f;
            big[(2 * r) * bw + 2 * col] = on && d == b ? d : e;
            big[(2 * r) * bw + 2 * col + 1] = on && b == f ? f : e;
            big[(2 * r + 1) * bw + 2 * col] = on && d == hh ? d : e;
            big[(2 * r + 1) * bw + 2 * col + 1] = on && hh == f ? f : e;
        }
    // Down to size x size (the picture's longer side), 4 x 4 samples a pixel.
    const int side = bw > bh ? bw : bh;
    for (int oy = 0; oy < size; oy++) {
        int py = y + oy;
        if (py < c->cy0 || py >= c->cy1) continue;
        for (int ox = 0; ox < size; ox++) {
            int px = x + ox;
            if (px < c->cx0 || px >= c->cx1) continue;
            unsigned ra = 0, ga = 0, ba = 0, aa = 0;
            for (int j = 0; j < 4; j++)
                for (int i = 0; i < 4; i++) {
                    int sx = ((ox * 4 + i) * 2 + 1) * side / (size * 8), sy = ((oy * 4 + j) * 2 + 1) * side / (size * 8);
                    if (sx >= bw || sy >= bh) continue;
                    uint32_t p = big[sy * bw + sx];
                    if (!p) continue;
                    ra += p >> 16 & 0xFF; ga += p >> 8 & 0xFF; ba += p & 0xFF; aa++;
                }
            if (!aa) continue;
            uint32_t rgb = (ra / aa) << 16 | (ga / aa) << 8 | (ba / aa);
            uint32_t* d = &c->px[py * c->w + px];
            *d = aa >= 16 ? rgb : pd_mix(*d & 0xFFFFFF, rgb, (int) (aa * 16));
        }
    }
}

void ui_art(struct pd_canvas* c, const struct pd_art* art, int x, int y, int scale) {
    for (int r = 0; r < art->h; r++) {
        for (int col = 0; art->rows[r][col]; col++) {
            const char* k = strchr(art->keys, art->rows[r][col]);
            if (k && art->rows[r][col] != '.') pd_fill(c, x + col * scale, y + r * scale, scale, scale, art->colors[k - art->keys]);
        }
    }
}

uint32_t ui_type_color(int type) {
    static const uint32_t COLORS[18] = {
        0xA8A878, 0xC03028, 0xA890F0, 0xA040A0, 0xE0C068, 0xB8A038, 0xA8B820, 0x705898, 0xB8B8D0,
        0x68A090, 0xF08030, 0x6890F0, 0x78C850, 0xF8D030, 0xF85888, 0x98D8D8, 0x7038F8, 0x705848,
    };
    return type >= 0 && type < 18 ? COLORS[type] : PD_MUTED;
}

int ui_type_badge(struct pd_canvas* c, int x, int y, int type) {
    char text[12];
    const char* name = pd_type_name(ui_game, type);
    int n = 0;
    for (; name[n] && n < 11; n++) text[n] = name[n] >= 'a' && name[n] <= 'z' ? (char) (name[n] - 32) : name[n];
    text[n] = 0;
    // The game's own tag where its ROM has the sheet (English FireRed / Emerald engines), else the drawn badge.
    const uint32_t* tags = pd_type_tags(ui_game);
    int tx, ty;
    if (tags && pd_type_tag_at(text, &tx, &ty)) {
        if (c)
            for (int r = 0; r < 12; r++)
                for (int i = 0; i < 32; i++) {
                    uint32_t v = tags[(ty + r) * 128 + tx + i];
                    if (v) pd_fill(c, x + i, y + r, 1, 1, v & 0xFFFFFF);
                }
        return 32;
    }
    int w = pd_text_width(text) + 6;
    if (!c) return w; // measuring
    pd_round_rect(c, x, y, w, 12, 1, ui_type_color(type));
    pd_text(c, x + 3, y - 2, text, 0xFFFFFF, PD_NO_SHADOW);
    return w;
}

void ui_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* label, int id) {
    bool pressed = ui->pressedId == id;
    label = pd_tr(label);
    // OptionButton: the title window's frame round a two-tone face.
    pd_layered_two_tone(c, x, y, w, h, 3, pd_cur->title, pd_cur->titleCount, pressed ? PD_LIST_FILL : PD_TITLE_FILL, 0);
    int tw = pd_text_width(label);
    pd_text(c, x + (w - tw) / 2, pd_text_y(y, h), label, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    ui_add_hit(ui, x, y, w, h, id);
}

// GetHPBarLevel's thresholds on the bar's own pixel width.
static const struct hp_colors* hp_colors_for(int hp, int maxHp, int barW) {
    if (hp >= maxHp) return &HP_GREEN;
    int f = maxHp > 0 ? hp * barW / maxHp : 0;
    if (f == 0 && hp > 0) f = 1;
    if (f > barW * 50 / 100) return &HP_GREEN;
    if (f > barW * 20 / 100) return &HP_YELLOW;
    return &HP_RED;
}

void ui_hp_bar(struct pd_canvas* c, int x, int y, int barW, int hp, int maxHp) {
    pd_fill(c, x, y, 16 + barW + 2, 7, HP_FRAME);
    // "HP" in 3x5 pixel letters, like the game's tag: H in columns 0-2, P in 4-6.
    static const uint16_t HP_TAG[5] = { 0x5 | 0x30, 0x5 | 0x50, 0x7 | 0x30, 0x5 | 0x10, 0x5 | 0x10 };
    pd_bitmap(c, x + 2, y + 1, HP_TAG, 5, HP_LABEL);
    int bx = x + 15, by = y + 1;
    pd_fill(c, bx, by, barW + 2, 5, 0xFFFFFF);
    pd_fill(c, bx + 1, by + 1, barW, 3, HP_EMPTY);
    int f = maxHp > 0 ? hp * barW / maxHp : 0;
    if (f == 0 && hp > 0) f = 1;
    if (f > barW) f = barW;
    const struct hp_colors* col = hp_colors_for(hp, maxHp, barW);
    pd_fill(c, bx + 1, by + 1, f, 1, col->top);
    pd_fill(c, bx + 1, by + 2, f, 2, col->main);
}

void ui_status_badge(struct pd_canvas* c, int x, int y, uint32_t status) {
    const char* label = pd_status_label(status);
    if (!*label) return;
    uint32_t fill = STATUS_FILL_SLP;
    if (!strcmp(label, "PSN") || !strcmp(label, "TOX")) fill = STATUS_FILL_PSN;
    else if (!strcmp(label, "PAR")) fill = STATUS_FILL_PAR;
    else if (!strcmp(label, "BRN")) fill = STATUS_FILL_BRN;
    else if (!strcmp(label, "FRZ")) fill = STATUS_FILL_FRZ;
    int w = pd_text_width(label) + 6;
    pd_round_rect(c, x, y, w, 14, 2, fill);
    pd_text(c, x + 3, y - 2, label, 0xFFFFFF, PD_NO_SHADOW);
}

void ui_notice(struct pd_canvas* c, const char* title, const char* line1, const char* line2) {
    int y = CONTENT_Y + 30;
    title = pd_tr(title);
    line1 = pd_tr(line1);
    line2 = pd_tr(line2);
    int f = pd_list_box(c, MARGIN, y, c->w - 2 * MARGIN, 76);
    int x = MARGIN + f + 6, w = c->w - 2 * (MARGIN + f + 6);
    pd_text(c, x, y + f + 2, title, PD_VALUE, PD_VALUE_SHADOW);
    if (line1) pd_text_fit(c, x, y + f + 22, w, line1, PD_LABEL, PD_LABEL_SHADOW);
    if (line2) pd_text_fit(c, x, y + f + 40, w, line2, PD_LABEL, PD_LABEL_SHADOW);
}

void ui_row(struct pd_canvas* c, int x, int y, int w, const char* label, const char* value, bool selected) {
    ui_row_split(c, x, y, w, label, value, selected, 42);
}

void ui_row_split(struct pd_canvas* c, int x, int y, int w, const char* label, const char* value, bool selected, int valuePct) {
    if (selected) pd_fill(c, x - 4, y, w + 8, ROW_H, PD_ROW_SELECTED);
    // The menus' rows translate what they draw (the app's OptionLine): their literals need no pd_tr of their own.
    label = pd_tr(label);
    value = pd_tr(value);
    int ty = pd_text_y(y, ROW_H);
    pd_text(c, x, ty, label, PD_LABEL, PD_LABEL_SHADOW);
    if (value) {
        // The value column, or past a label too long for it (a Game Boy font's QUICK MENU BUTTON ran under its value).
        int vx = x + w * valuePct / 100, past = x + pd_text_width(label) + 8;
        if (vx < past) vx = past;
        pd_text_fit(c, vx, ty, x + w - vx, value, PD_VALUE, PD_VALUE_SHADOW);
    }
}

#define BALL_RED 0xE84848
#define BALL_DARK 0x404040
#define BALL_SEEN 0xA8A8B0

void ui_ball(struct pd_canvas* c, int x, int y, bool caught) {
    pd_round_rect(c, x, y, 10, 10, 5, caught ? BALL_DARK : BALL_SEEN);
    if (caught) {
        pd_round_rect(c, x + 1, y + 1, 8, 8, 4, 0xFFFFFF);
        pd_fill(c, x + 2, y + 1, 6, 1, BALL_RED);
        pd_fill(c, x + 1, y + 2, 8, 3, BALL_RED);
        pd_fill(c, x + 1, y + 5, 8, 1, BALL_DARK);
        pd_fill(c, x + 4, y + 4, 2, 3, 0xFFFFFF);
    } else {
        pd_round_rect(c, x + 2, y + 2, 6, 6, 3, 0xE0E0E8);
    }
}

int ui_scroll_to(int scroll, int top, int h, int view) {
    if (top + h > scroll + view) scroll = top + h - view;
    if (top < scroll) scroll = top;
    return scroll;
}

int ui_clamp_scroll(int scroll, int total, int view) {
    int max = total - view;
    if (max < 0) max = 0;
    return scroll < 0 ? 0 : scroll > max ? max : scroll;
}

void ui_scroll_bar(struct pd_canvas* c, int x, int y, int h, int scroll, int total, uint32_t color) {
    if (total <= h) return;
    int th = h * h / total;
    if (th < 8) th = 8;
    int ty = y + (h - th) * scroll / (total - h);
    pd_fill(c, x, ty, 2, th, color);
}

void ui_scroll_rail(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, int scroll, int total, int view) {
    int th = h * view / total;
    if (th < 18) th = 18;
    int max = total - view, ty = y + (h - th) * scroll / (max > 0 ? max : 1);
    pd_round_rect(c, x, y, w, h, 3, PD_DIVIDER);
    pd_round_rect(c, x + 1, y + 1, w - 2, h - 2, 2, PD_LIST_FILL);
    pd_round_rect(c, x, ty, w, th, 3, ui->barDragging ? PD_VALUE : PD_LABEL);
    ui->barY = y;
    ui->barH = h;
    ui->barThumb = th;
    ui->barMax = max;
    // A wider target than it looks: a stylus on a 3DS is thin, a finger isn't.
    ui_add_hit(ui, x - 6, y, w + 8, h, HIT_SCROLL_BAR);
}

void ui_list_rail(struct pd_ui* ui, struct pd_canvas* c, int right, int y, int h, int scroll, int total) {
    if (total <= h) return;
    ui_scroll_rail(ui, c, right - 8, y + 1, 6, h - 2, scroll, total, h);
}

void pd_ui_format_money(long v, char* buf, size_t len) {
    if (v < 0) {
        snprintf(buf, len, "-");
    } else if (v >= 1000) {
        snprintf(buf, len, "%ld,%03ld", v / 1000, v % 1000);
    } else {
        snprintf(buf, len, "%ld", v);
    }
}
