#include <stdio.h>
#include <string.h>

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

void ui_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, int h, const char* label, int id) {
    bool pressed = ui->pressedId == id;
    pd_title_box(c, x, y, w, h, pressed ? PD_LIST_FILL : PD_TITLE_FILL);
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
    int f = pd_list_box(c, MARGIN, y, c->w - 2 * MARGIN, 76);
    int x = MARGIN + f + 6, w = c->w - 2 * (MARGIN + f + 6);
    pd_text(c, x, y + f + 2, title, PD_VALUE, PD_VALUE_SHADOW);
    if (line1) pd_text_fit(c, x, y + f + 22, w, line1, PD_LABEL, PD_LABEL_SHADOW);
    if (line2) pd_text_fit(c, x, y + f + 40, w, line2, PD_LABEL, PD_LABEL_SHADOW);
}

void ui_row(struct pd_canvas* c, int x, int y, int w, const char* label, const char* value, bool selected) {
    if (selected) pd_fill(c, x - 4, y, w + 8, ROW_H, PD_ROW_SELECTED);
    int ty = pd_text_y(y, ROW_H);
    pd_text(c, x, ty, label, PD_LABEL, PD_LABEL_SHADOW);
    if (value) {
        // The value column, or past a label too long for it (a Game Boy font's QUICK MENU BUTTON ran under its value).
        int vx = x + w * 42 / 100, past = x + pd_text_width(label) + 8;
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

void ui_format_money(long v, char* buf, size_t len) {
    if (v < 0) {
        snprintf(buf, len, "-");
    } else if (v >= 1000) {
        snprintf(buf, len, "%ld,%03ld", v / 1000, v % 1000);
    } else {
        snprintf(buf, len, "%ld", v);
    }
}
