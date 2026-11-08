#include "pd_library.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "pd_games.h"
#include "pd_icons_gen.h"
#include "pd_style.h"

#define RAIL_W 48
#define HEADER_H 24
#define PILL_H 16
#define GAP 3
#define LIST_TOP (HEADER_H + PILL_H + 7)
#define TILE_ART 56
#define TILE_H (TILE_ART + 18)

// The website's page, and its soft colours (DaisyBackdrop).
// DARK MODE: the same page at night, its blobs dim glows (NIGHT_PAGE / NIGHT_BLOBS).
#define PAGE (pd_dark ? 0x12141Cu : 0xFDF7F2u)
#define BLOB_PEACH (pd_dark ? 0x6A2F45u : 0xFCE3D6u)
#define BLOB_YELLOW (pd_dark ? 0x6A4418u : 0xFDF2C8u)
#define BLOB_BLUE (pd_dark ? 0x233A7Au : 0xDCEBFAu)
#define TILE_FRAME 0x505868

struct view { const char* label; uint32_t color; };
static const struct view VIEWS[PD_VIEW_COUNT] = {
    { "ALL", 0x505868 }, { "RECENT", 0x3890D8 }, { "GBA", 0x7048C8 }, { "GB", 0xE8A820 }, { "HACKS", 0xE03830 },
};

static bool in_view(const struct pd_library_game* g, int view) {
    switch (view) {
    case PD_VIEW_RECENT: return g->recent;
    case PD_VIEW_GBA: return g->console == PD_CONSOLE_GBA && !g->hack;
    case PD_VIEW_GB: return g->console != PD_CONSOLE_GBA;
    case PD_VIEW_HACKS: return g->hack;
    default: return true;
    }
}

void pd_library_init(struct pd_library* l) {
    memset(l, 0, sizeof(*l));
    l->picked = -1;
    l->pressed = -1;
    l->columns = 3;
}

void pd_library_refresh(struct pd_library* l) {
    l->shownCount = 0;
    for (int i = 0; i < l->count; i++)
        if (in_view(&l->games[i], l->view)) l->shown[l->shownCount++] = i;
    if (l->selected >= l->shownCount) l->selected = l->shownCount ? l->shownCount - 1 : 0;
}

// GameTitles.tidy: the file name minus its dump tags ("(USA, Europe)", "(v1.3.1)", "1636 - ").
static void tidy(const char* file, char* out, int len) {
    const char* dot = strrchr(file, '.');
    int n = dot ? (int) (dot - file) : (int) strlen(file);
    int o = 0;
    int i = 0;
    while (i < n && isdigit((unsigned char) file[i])) i++;
    if (i && i + 2 < n && file[i] == ' ' && file[i + 1] == '-') i += 3;
    else i = 0;
    int depth = 0;
    for (; i < n && o + 1 < len; i++) {
        char c = file[i];
        if (c == '(' || c == '[') depth++;
        else if (c == ')' || c == ']') depth--;
        else if (!depth) out[o++] = c;
    }
    while (o && out[o - 1] == ' ') o--;
    out[o] = 0;
}

void pd_library_title(const char* file, char* out, int len, bool* hack, bool* supported) {
    char base[256];
    snprintf(base, sizeof(base), "%s", file);
    char* dot = strrchr(base, '.');
    if (dot) *dot = 0;
    *hack = false;
    *supported = false;
    for (int i = 0; i < pd_game_entry_count; i++) {
        const struct pd_game_entry* e = &pd_game_entries[i];
        if (strcmp(e->file, base)) continue;
        snprintf(out, (size_t) len, "%s", e->title);
        *hack = strcmp(e->gameKind, "FIRERED") && strcmp(e->gameKind, "EMERALD");
        *supported = true;
        return;
    }
    tidy(file, out, len);
}

static void backdrop(struct pd_canvas* c) {
    pd_fill(c, 0, 0, c->w, c->h, PAGE);
    // The blobs, as soft bands: the page's colours in big steps.
    for (int y = 0; y < c->h; y += 2) {
        int a = y * 100 / c->h;
        for (int x = 0; x < c->w; x += 2) {
            int d1 = (x - c->w / 5) * (x - c->w / 5) + (y - c->h / 4) * (y - c->h / 4);
            int d2 = (x - c->w * 4 / 5) * (x - c->w * 4 / 5) + (y - c->h / 6) * (y - c->h / 6);
            int d3 = (x - c->w / 2) * (x - c->w / 2) + (y - c->h) * (y - c->h);
            uint32_t col = d1 < 9000 ? BLOB_PEACH : d2 < 7000 ? BLOB_YELLOW : d3 < 8000 ? BLOB_BLUE : PAGE;
            if (col != PAGE) pd_fill(c, x, y, 2, 2, col);
        }
        (void) a;
    }
}

static void pill(struct pd_canvas* c, int x, int y, int w, const char* text, uint32_t color, bool on) {
    pd_round_rect(c, x, y, w, PILL_H, 3, color);
    pd_round_rect(c, x + 1, y + 1, w - 2, PILL_H - 2, 2, on ? color : PD_TITLE_FILL);
    pd_text(c, x + (w - pd_text_width(text)) / 2, y - 1, text, on || pd_dark ? 0xFFFFFF : color, PD_NO_SHADOW);
}

static void tile(struct pd_canvas* c, const struct pd_library_game* g, int x, int y, int w, bool selected) {
    uint32_t fill = g->hack ? 0xD83030 : g->console == PD_CONSOLE_GBA ? 0x6840B8 : g->console == PD_CONSOLE_GBC ? 0xF0C020 : 0x38A890;
    const char* word = g->hack ? "HACK" : g->console == PD_CONSOLE_GBA ? "GBA" : g->console == PD_CONSOLE_GBC ? "GBC" : "GB";
    pd_round_rect(c, x, y, w, TILE_H, 3, selected ? 0xF08030 : TILE_FRAME);
    pd_round_rect(c, x + 2, y + 2, w - 4, TILE_H - 4, 2, PD_TITLE_FILL);
    pd_round_rect(c, x + 4, y + 4, w - 8, TILE_ART - 4, 2, fill);
    int s = 2;
    int tw = pd_text_width(word) * s;
    pd_text_scaled(c, x + (w - tw) / 2, y + 4 + (TILE_ART - 4 - 16 * s) / 2 + 2, word, 0xFFFFFF, 0x202020, s);
    pd_text_fit(c, x + 4, y + TILE_ART, w - 8, g->title, g->supported ? PD_LABEL : PD_MUTED, PD_LABEL_SHADOW);
}

static void rail(struct pd_canvas* c) {
    pd_fill(c, 0, 0, RAIL_W, c->h, pd_dark ? PD_LIST_FILL : 0xE8E8EC);
    pd_fill(c, RAIL_W - 1, 0, 1, c->h, TILE_FRAME);
    pd_round_rect(c, 2, 4, RAIL_W - 6, 42, 3, PD_TITLE_FILL);
    ui_art_at(c, &PD_TAB_ART[0], (RAIL_W - 18) / 2, 8, 1);
    pd_text(c, (RAIL_W - 2 - pd_text_width("GAMES")) / 2, 26, "GAMES", PD_VALUE, PD_VALUE_SHADOW);
}

void pd_library_draw(struct pd_library* l, struct pd_canvas* c) {
    backdrop(c);
    int x0 = RAIL_W + GAP, w = c->w - x0 - GAP;
    // The list scrolls under the header.
    int colW = (w - GAP * (l->columns - 1)) / l->columns;
    int rows = (l->shownCount + l->columns - 1) / l->columns;
    int total = rows * (TILE_H + GAP), view = c->h - LIST_TOP;
    int maxScroll = total > view ? total - view : 0;
    if (l->scroll > maxScroll) l->scroll = maxScroll;
    if (l->scroll < 0) l->scroll = 0;
    pd_clip(c, RAIL_W, LIST_TOP, c->w - RAIL_W, view);
    for (int i = 0; i < l->shownCount; i++) {
        int y = LIST_TOP + (i / l->columns) * (TILE_H + GAP) - l->scroll;
        if (y + TILE_H < LIST_TOP || y >= c->h) continue;
        tile(c, &l->games[l->shown[i]], x0 + (i % l->columns) * (colW + GAP), y, colW, i == l->selected);
    }
    pd_unclip(c);
    if (!l->shownCount) {
        const char* a = l->count ? "No games here." : "No games yet.";
        pd_text(c, x0 + 12, LIST_TOP + 20, a, PD_LABEL, PD_LABEL_SHADOW);
        if (!l->count) {
            pd_text(c, x0 + 12, LIST_TOP + 40, "Put your games in", PD_MUTED, PD_MUTED_SHADOW);
            pd_text(c, x0 + 12, LIST_TOP + 58, "sdmc:/pokedaisy/roms/", PD_VALUE, PD_VALUE_SHADOW);
        }
    }
    int f = pd_title_box(c, x0, 2, w, HEADER_H, PD_TITLE_FILL);
    ui_art_at(c, &PD_LOGO, x0 + f + 2, 2 + (HEADER_H - 32 / 2) / 2, 0);
    pd_text(c, x0 + f + 22, pd_text_y(2, HEADER_H), "POKéDAISY", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int px = x0;
    for (int i = 0; i < PD_VIEW_COUNT; i++) {
        char text[20];
        if (i == PD_VIEW_ALL) snprintf(text, sizeof(text), "%s %d", VIEWS[i].label, l->count);
        else snprintf(text, sizeof(text), "%s", VIEWS[i].label);
        int pw = pd_text_width(text) + 8;
        pill(c, px, HEADER_H + 5, pw, text, VIEWS[i].color, l->view == i);
        px += pw + 2;
    }
    rail(c);
}

void pd_library_draw_top(struct pd_library* l, struct pd_canvas* c, const char* line) {
    backdrop(c);
    int w = 340, h = 140, x = (c->w - w) / 2, y = (c->h - h) / 2;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    ui_art_at(c, &PD_LOGO, x + f + 8, y + f + 6, 1);
    pd_text_scaled(c, x + f + 50, y + f + 6, "POKéDAISY", PD_VALUE, PD_VALUE_SHADOW, 2);
    if (l->shownCount) {
        const struct pd_library_game* g = &l->games[l->shown[l->selected]];
        pd_text_fit(c, x + f + 10, y + f + 50, w - 2 * f - 20, g->title, PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, x + f + 10, y + f + 68, w - 2 * f - 20, g->file, PD_MUTED, PD_MUTED_SHADOW);
        pd_text(c, x + f + 10, y + f + 86, g->supported ? "A: play" : "A: play, no companion for this one yet", PD_LABEL, PD_LABEL_SHADOW);
    }
    if (line) pd_text_fit(c, x + f + 10, y + f + 104, w - 2 * f - 20, line, PD_MUTED, PD_MUTED_SHADOW);
}

void pd_library_move(struct pd_library* l, int dx, int dy) {
    int n = l->shownCount;
    if (!n) return;
    int at = l->selected + dx + dy * l->columns;
    if (at < 0) at = 0;
    if (at >= n) at = n - 1;
    l->selected = at;
    int top = (at / l->columns) * (TILE_H + GAP), view = 240 - LIST_TOP;
    if (top < l->scroll) l->scroll = top;
    if (top + TILE_H > l->scroll + view) l->scroll = top + TILE_H - view;
}

void pd_library_touch(struct pd_library* l, int x, int y, bool down) {
    int x0 = RAIL_W + GAP, w = 320 - x0 - GAP, colW = (w - GAP * (l->columns - 1)) / l->columns;
    if (down) {
        if (!l->touching) {
            l->touching = true;
            l->dragged = false;
            l->touchX = x;
            l->touchY = l->lastY = y;
        } else {
            if (abs(y - l->touchY) > 6) l->dragged = true;
            if (l->dragged) {
                l->scroll += l->lastY - y;
                l->lastY = y;
            }
        }
        return;
    }
    if (!l->touching) return;
    l->touching = false;
    if (l->dragged) return;
    int tx = l->touchX, ty = l->touchY;
    if (ty >= HEADER_H + 4 && ty < LIST_TOP) {
        int px = x0;
        for (int i = 0; i < PD_VIEW_COUNT; i++) {
            char text[20];
            if (i == PD_VIEW_ALL) snprintf(text, sizeof(text), "%s %d", VIEWS[i].label, l->count);
            else snprintf(text, sizeof(text), "%s", VIEWS[i].label);
            int pw = pd_text_width(text) + 8;
            if (tx >= px && tx < px + pw) {
                l->view = i;
                l->selected = 0;
                l->scroll = 0;
                pd_library_refresh(l);
                return;
            }
            px += pw + 2;
        }
        return;
    }
    if (ty < LIST_TOP || tx < x0) return;
    int col = (tx - x0) / (colW + GAP), row = (ty - LIST_TOP + l->scroll) / (TILE_H + GAP);
    int i = row * l->columns + col;
    if (col >= l->columns || i >= l->shownCount) return;
    if (i == l->selected) l->picked = l->shown[i];
    l->selected = i;
}
