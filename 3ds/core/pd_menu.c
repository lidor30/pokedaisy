#include "pd_menu.h"

#include "pd_style.h"

#include <stddef.h>

#define MARGIN 4
#define TITLE_H 24
#define ROW_H 18
#define LIST_Y (MARGIN + TITLE_H + 4)

// pd_list_box's frame: 1 + 1 + 2 + 1 + 2 px (the list window's layers).
#define LIST_FRAME 7

static int visible_rows(const struct pd_canvas* c) {
    return (c->h - LIST_Y - MARGIN - 2 * LIST_FRAME - 4) / ROW_H;
}

void pd_menu_init(struct pd_menu* m, const char* title, const char* const* items, int count) {
    m->title = title;
    m->items = items;
    m->count = count;
    m->selected = 0;
    m->scroll = 0;
    m->touching = false;
    m->pressed = -1;
}

static void keep_visible(struct pd_menu* m, int rows) {
    if (m->selected < m->scroll) m->scroll = m->selected;
    if (m->selected >= m->scroll + rows) m->scroll = m->selected - rows + 1;
    if (m->scroll < 0) m->scroll = 0;
}

void pd_menu_draw(struct pd_menu* m, struct pd_canvas* c) {
    if (!m->overlay) pd_backdrop(c);
    int w = c->w - 2 * MARGIN;
    int f = pd_title_box(c, MARGIN, MARGIN, w, TITLE_H, PD_TITLE_FILL);
    pd_text(c, MARGIN + f + 6, pd_text_y(MARGIN, TITLE_H), m->title, PD_TITLE_TEXT, PD_TITLE_SHADOW);

    pd_list_box(c, MARGIN, LIST_Y, w, c->h - LIST_Y - MARGIN);
    int rows = visible_rows(c);
    keep_visible(m, rows);
    int x = MARGIN + LIST_FRAME, iw = w - 2 * LIST_FRAME;
    int y = LIST_Y + LIST_FRAME + 2;
    if (m->count == 0) {
        pd_text(c, x + 6, y, "No games found.", PD_LABEL, PD_LABEL_SHADOW);
        pd_text(c, x + 6, y + ROW_H, "Put .gba files in", PD_MUTED, PD_MUTED_SHADOW);
        pd_text(c, x + 6, y + 2 * ROW_H, "sdmc:/pokedaisy/roms/", PD_VALUE, PD_VALUE_SHADOW);
        return;
    }
    for (int r = 0; r < rows && m->scroll + r < m->count; r++) {
        int i = m->scroll + r;
        bool cursor = i == m->selected || i == m->pressed;
        if (cursor) pd_fill(c, x, y + r * ROW_H, iw, ROW_H, PD_ROW_SELECTED);
        pd_text_fit(c, x + 6, pd_text_y(y + r * ROW_H, ROW_H), iw - 12, m->items[i],
                    cursor ? PD_VALUE : PD_LABEL, cursor ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
    }
    // A scroll bar when the list runs past the window.
    if (m->count > rows) {
        int track = rows * ROW_H;
        int th = track * rows / m->count;
        int ty = track * m->scroll / m->count;
        pd_fill(c, x + iw - 3, y + ty, 2, th < 6 ? 6 : th, PD_MUTED);
    }
}

void pd_menu_move(struct pd_menu* m, int delta) {
    if (!m->count) return;
    m->selected = (m->selected + delta + m->count) % m->count;
}

static int row_at(const struct pd_menu* m, int x, int y, int rows, int w) {
    int top = LIST_Y + LIST_FRAME + 2;
    if (x < MARGIN + LIST_FRAME || x >= MARGIN + w - LIST_FRAME || y < top) return -1;
    int r = (y - top) / ROW_H;
    if (r >= rows || m->scroll + r >= m->count) return -1;
    return m->scroll + r;
}

int pd_menu_touch(struct pd_menu* m, int x, int y, bool down) {
    // The bottom screen is the canvas these rows were drawn on: 320x240.
    int rows = (240 - LIST_Y - MARGIN - 2 * LIST_FRAME - 4) / ROW_H;
    int row = row_at(m, x, y, rows, 320 - 2 * MARGIN);
    if (down) {
        if (!m->touching) {
            m->touching = true;
            m->pressed = row;
        } else if (row != m->pressed) {
            m->pressed = -1;
        }
        return -1;
    }
    if (!m->touching) return -1;
    m->touching = false;
    int picked = m->pressed;
    m->pressed = -1;
    if (picked >= 0) m->selected = picked;
    return picked;
}

void pd_splash_draw(struct pd_canvas* c, const char* line1, const char* line2, const char* line3) {
    pd_backdrop(c);
    // Each line wrapped, never cut: an error's detail is what a player's photo of the screen has to tell us. The box grows
    // with the lines (to the screen's height; past that the rows close up, then the last is cut).
    const char* lines[3] = { line1, line2, line3 };
    int w = c->w - 24 < 376 ? c->w - 24 : 376;
    int textW = w - 2 * (8 + 8);   // a title box's frame is under 8 px in every look
    int rows[3] = { 0 }, total = 0;
    for (int i = 0; i < 3; i++) {
        if (!lines[i]) continue;
        rows[i] = pd_text_wrap(NULL, 0, 0, textW, 16, 8, lines[i], PD_LABEL, PD_LABEL_SHADOW);
        total += rows[i];
    }
    int gaps = 0;
    for (int i = 0; i < 3; i++) gaps += lines[i] && rows[i] ? 1 : 0;
    int room = c->h - 16 - 34 - 12;   // the box's frame, its title and the bottom padding
    int lineH = total * 16 + (gaps - 1) * 4 <= room ? 16 : total * 14 + (gaps - 1) * 2 <= room ? 14 : 12;
    int gap = lineH == 16 ? 4 : 2;
    int maxRows = (room - (gaps - 1) * gap) / lineH;
    int h = 34 + total * lineH + (gaps > 1 ? (gaps - 1) * gap : 0) + 16;
    if (h < 120) h = 120;
    if (h > c->h - 8) h = c->h - 8;
    int x = (c->w - w) / 2, y = (c->h - h) / 2;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    const char* name = "POKéDAISY";
    pd_text(c, x + (w - pd_text_width(name)) / 2, y + f + 6, name, PD_VALUE, PD_VALUE_SHADOW);
    int ty = y + f + 30;
    for (int i = 0; i < 3 && maxRows > 0; i++) {
        if (!lines[i] || !rows[i]) continue;
        int n = rows[i] < maxRows ? rows[i] : maxRows;
        pd_text_wrap(c, x + f + 8, ty, textW, lineH, n, lines[i], PD_LABEL, PD_LABEL_SHADOW);
        ty += n * lineH + gap;
        maxRows -= n;
    }
}
