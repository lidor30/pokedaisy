// POKéDEX: the list (national or the game's regional dex) with the save's
// seen / caught marks, and an entry page read from the ROM - the app's
// PokedexScreen, fitted to 320x212.
#include <stdio.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_dex_details.h"
#include "pd_gb.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define TITLE_H 24
#define LIST_Y (CONTENT_Y + TITLE_H + 4)
#define DEX_ROW 18
#define TOGGLE_W 78

// Whether the National Dex shows: the player's pick, else the save's own.
static bool national_view(const struct pd_ui* ui, const struct pd_snapshot* s) {
    // (A Game Boy dex has one list: the snapshot says national.)
    return ui->dexNational >= 0 ? ui->dexNational : s->dexNational;
}

// The list: national numbers in the order shown; returns how many.
static int dex_list(const struct pd_game* g, bool national, int* out) {
    int n = 0;
    int gb = pd_dex_gb_count(g);
    if (gb) {
        for (int i = 1; i <= gb; i++) {
            if (pd_dex_species(g, i)) out[n++] = i;
        }
    } else if (national) {
        for (int i = 1; i <= PD_NATIONAL_COUNT; i++) {
            if (pd_dex_species(g, i)) out[n++] = i;
        }
    } else {
        for (int i = 1; i <= g->cfg->regionalCount; i++) {
            int nat = pd_dex_regional(g, i);
            if (nat) out[n++] = nat;
        }
    }
    return n;
}

// The number shown for an entry: its regional position in a regional dex.
static int shown_number(const struct pd_game* g, bool national, int nat) {
    if (national) return nat;
    for (int i = 1; i <= g->cfg->regionalCount; i++) {
        if (pd_dex_regional(g, i) == nat) return i;
    }
    return nat;
}

static void title(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                  const int* list, int n, bool national) {
    bool one = pd_dex_gb_count(g) > 0; // a Game Boy dex has the one list
    int x = MARGIN, w = c->w - 2 * MARGIN - (one ? 0 : TOGGLE_W + 4);
    int f = pd_title_box(c, x, CONTENT_Y, w, TITLE_H, PD_TITLE_FILL);
    int ty = pd_text_y(CONTENT_Y, TITLE_H);
    pd_text(c, x + f + 6, ty, "POKéDEX", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int seen = 0, caught = 0;
    for (int i = 0; i < n; i++) {
        seen += pd_dex_seen(s, list[i]);
        caught += pd_dex_caught(s, list[i]);
    }
    char counts[40];
    snprintf(counts, sizeof(counts), "SEEN %d  OWN %d", seen, caught);
    pd_text_right(c, x + w - f - 6, ty, counts, PD_VALUE, PD_VALUE_SHADOW);
    if (one) return;
    // Which dex: tap for the other one.
    char other[24];
    snprintf(other, sizeof(other), "%s", national ? g->cfg->regionName : "NATIONAL");
    ui_button(ui, c, x + w + 4, CONTENT_Y, TOGGLE_W, TITLE_H, other, HIT_DEX_TOGGLE);
}

// The list shown (rebuilt each draw), kept for the tap handler, which has no
// game at hand: the toggle flips what shows, up / down step through it.
static int lastList[PD_NATIONAL_COUNT], lastCount;
static bool lastNational;

static void list_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const int* list = lastList;
    int n = lastCount;
    bool national = lastNational;
    title(ui, c, g, s, list, n, national);

    int x = MARGIN, w = c->w - 2 * MARGIN;
    int f = pd_list_box(c, x, LIST_Y, w, c->h - LIST_Y - MARGIN);
    int ix = x + f, iy = LIST_Y + f + 1, iw = w - 2 * f, ih = c->h - LIST_Y - MARGIN - 2 * f - 2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_DEX_LIST);
    ui->dexScroll = ui_clamp_scroll(ui->dexScroll, n * DEX_ROW, ih);
    pd_clip(c, ix, iy, iw, ih);
    char buf[40], num[16];
    for (int i = ui->dexScroll / DEX_ROW; i < n; i++) {
        int ry = iy + i * DEX_ROW - ui->dexScroll;
        if (ry >= iy + ih) break;
        int nat = list[i];
        bool seen = pd_dex_seen(s, nat), caught = pd_dex_caught(s, nat);
        if (ui->pressedId == HIT_DEX_ROW + nat) pd_fill(c, ix, ry, iw, DEX_ROW, PD_ROW_SELECTED);
        if (seen) ui_ball(c, ix + 6, ry + 4, caught);
        snprintf(num, sizeof(num), "No.%03d", shown_number(g, national, nat));
        int ty = pd_text_y(ry, DEX_ROW);
        pd_text(c, ix + 22, ty, num, PD_MUTED, PD_MUTED_SHADOW);
        const char* name = pd_species_name(pd_dex_species(g, nat), buf, sizeof(buf));
        pd_text(c, ix + 74, ty, name, seen ? PD_LABEL : PD_MUTED, seen ? PD_LABEL_SHADOW : PD_MUTED_SHADOW);
        int top = ry < iy ? iy : ry;
        int bottom = ry + DEX_ROW > iy + ih ? iy + ih : ry + DEX_ROW;
        ui_add_scroll_hit(ui, ix, top, iw, bottom - top, HIT_DEX_ROW + nat);
    }
    pd_unclip(c);
    ui_scroll_bar(c, ix + iw - 4, iy, ih, ui->dexScroll, n * DEX_ROW, PD_MUTED);
}

static void draw_sprite(struct pd_canvas* c, int x, int y, const uint32_t* px) {
    for (int py = 0; py < PD_SPRITE; py++) {
        for (int pxx = 0; pxx < PD_SPRITE; pxx++) {
            uint32_t v = px[py * PD_SPRITE + pxx];
            if (v != 0xFF000000u) pd_fill(c, x + pxx, y + py, 1, 1, v);
        }
    }
}

// The base stats on a line of their own, spread over the page's width, and the entry's text under them.
static void info_page(struct pd_canvas* c, const struct pd_dex_entry* e, int ix, int dy, int iw, int lines) {
    static const char* const STAT[6] = { "HP", "AT", "DF", "SA", "SD", "SP" };
    static const char* const STAT_GEN1[5] = { "HP", "AT", "DF", "SP", "SC" }; // one SPECIAL, in its own order
    char buf[8];
    for (int i = 0; i < e->statCount; i++) {
        int sx = ix + iw * i / e->statCount;
        sx += pd_text(c, sx, dy, e->statCount == 5 ? STAT_GEN1[i] : STAT[i], PD_MUTED, PD_MUTED_SHADOW) + 3;
        snprintf(buf, sizeof(buf), "%d", e->stats[i]);
        pd_text(c, sx, dy, buf, PD_LABEL, PD_LABEL_SHADOW);
    }
    pd_text_wrap(c, ix, dy + 16, iw, 15, lines - 1, e->description, PD_LABEL, PD_LABEL_SHADOW);
}

// A line of a section's page, kept to the page.
static void page_line(struct pd_canvas* c, int x, int* y, int top, int bottom, int w, const char* left, const char* right, bool value) {
    if (*y >= top - 2 && *y + 15 <= bottom + 4) {
        pd_text_fit(c, x, *y, right ? w * 62 / 100 : w, left, value ? PD_VALUE : PD_LABEL, value ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        if (right) pd_text_right(c, x + w, *y, right, PD_MUTED, PD_MUTED_SHADOW);
    }
    *y += 15;
}

static void section_page(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_dex_entry* e, int x, int top, int w, int h) {
    char left[64], right[32];
    if (ui->dexPageScroll > ui->dexPageMax) ui->dexPageScroll = ui->dexPageMax;
    if (ui->dexPageScroll < 0) ui->dexPageScroll = 0;
    int bottom = top + h, y = top - ui->dexPageScroll;
    pd_clip(c, x, top, w, h);
    ui_add_hit(ui, x, top, w, h, HIT_DEX_PAGE);
    if (ui->dexSection == 1) {
        struct pd_evo evos[PD_EVO_MAX];
        int n = pd_dex_family(g, e->species, evos, PD_EVO_MAX);
        if (!n) page_line(c, x, &y, top, bottom, w, "Doesn't evolve.", NULL, false);
        for (int i = 0; i < n; i++) {
            char a[24], b[24], how[56];
            snprintf(left, sizeof(left), "%s > %s", pd_species_name(evos[i].from, a, sizeof(a)), pd_species_name(evos[i].to, b, sizeof(b)));
            page_line(c, x, &y, top, bottom, w, left, NULL, evos[i].from == e->species || evos[i].to == e->species);
            pd_dex_evo_text(g, &evos[i], how, sizeof(how));
            if (y >= top - 2 && y + 15 <= bottom + 4) pd_text_fit(c, x + 8, y, w - 8, how, PD_MUTED, PD_MUTED_SHADOW);
            y += 15;
        }
    } else if (ui->dexSection == 2) {
        static const char* const HOW[4] = { "GRASS", "SURF", "ROCK", "FISH" };
        struct pd_spot spots[PD_SPOTS_MAX];
        int n = pd_dex_spots(g, e->species, spots, PD_SPOTS_MAX);
        if (!n) page_line(c, x, &y, top, bottom, w, "Not found in the wild.", NULL, false);
        for (int i = 0; i < n; i++) {
            snprintf(right, sizeof(right), "%s LV%d-%d", HOW[spots[i].method], spots[i].minLevel, spots[i].maxLevel);
            page_line(c, x, &y, top, bottom, w, pd_mapsec_name(g, spots[i].mapsec), right, false);
        }
    } else {
        struct pd_level_move moves[PD_LEVEL_MOVES_MAX];
        uint16_t tms[PD_TM_MOVES_MAX];
        int n = pd_dex_level_moves(g, e->species, moves, PD_LEVEL_MOVES_MAX);
        for (int i = 0; i < n; i++) {
            snprintf(right, sizeof(right), "LV %d", moves[i].level);
            page_line(c, x, &y, top, bottom, w, pd_move_name(moves[i].move, left, sizeof(left)), right, false);
        }
        int t = pd_dex_tm_moves(g, e->species, tms, PD_TM_MOVES_MAX);
        if (t) page_line(c, x, &y, top, bottom, w, "TM / HM", NULL, true);
        for (int i = 0; i < t; i++) page_line(c, x, &y, top, bottom, w, pd_move_name(tms[i], left, sizeof(left)), NULL, false);
    }
    pd_unclip(c);
    ui->dexPageMax = y + ui->dexPageScroll - top - h;
    if (ui->dexPageMax < 0) ui->dexPageMax = 0;
}

static void entry_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    static struct pd_dex_entry e;
    static uint32_t sprite[PD_SPRITE * PD_SPRITE];
    static bool spriteOk;
    static int loaded;
    static unsigned loadedRom;
    if (loaded != ui->dexOpen || loadedRom != pd_rom_id(g)) {
        pd_dex_entry(g, ui->dexOpen, &e);
        spriteOk = pd_dex_sprite(g, e.species, sprite);
        loaded = ui->dexOpen;
        loadedRom = pd_rom_id(g);
    }
    bool national = lastNational;
    char buf[64], buf2[40];
    int x = MARGIN, y = CONTENT_Y, w = c->w - 2 * MARGIN, h = c->h - CONTENT_Y - MARGIN;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    int ix = x + f + 4, iw = w - 2 * (f + 4);
    int top = y + f + 2;

    // The sprite on the list window's grey, framed.
    int bx = ix, by = top;
    pd_list_box(c, bx, by, PD_SPRITE + 16, PD_SPRITE + 16);
    // Dark mode on Gen 1: the picture on its own white card, or its outline vanishes (spritePaper).
    if (spriteOk && pd_dark && g->gb && g->gb->gen == 1) pd_fill(c, bx + 8, by + 8, PD_SPRITE, PD_SPRITE, 0xFFFFFF);
    if (spriteOk) draw_sprite(c, bx + 8, by + 8, sprite);

    int tx = bx + PD_SPRITE + 24, tw = ix + iw - tx;
    snprintf(buf, sizeof(buf), "No.%03d", shown_number(g, national, e.national));
    pd_text(c, tx, top, buf, PD_MUTED, PD_MUTED_SHADOW);
    int nw = pd_text(c, tx + 50, top, pd_species_name(e.species, buf2, sizeof(buf2)), PD_LABEL, PD_LABEL_SHADOW);
    if (pd_dex_seen(s, e.national)) ui_ball(c, tx + 50 + nw + 6, top + 4, pd_dex_caught(s, e.national));
    snprintf(buf, sizeof(buf), "%s %s", e.category, pd_dex_category_suffix(g));
    pd_text_fit(c, tx, top + 18, tw, buf, PD_MUTED, PD_MUTED_SHADOW);
    int t1 = e.type1, t2 = e.type2;
    if (g->gb) pd_species_type_pair(e.species, &t1, &t2); // the cart's ids, as the party's
    if (t2 != t1 && t2 != PD_TYPE_NONE) {
        snprintf(buf, sizeof(buf), "%s / %s", pd_type_name(t1), pd_type_name(t2));
    } else {
        snprintf(buf, sizeof(buf), "%s", pd_type_name(t1));
    }
    pd_text(c, tx, top + 36, buf, PD_VALUE, PD_VALUE_SHADOW);
    char ht[16], wt[20];
    pd_dex_measures(g, &e, ht, sizeof(ht), wt, sizeof(wt));
    snprintf(buf, sizeof(buf), "HT %s   WT %s", ht, wt);
    pd_text_fit(c, tx, top + 52, tw, buf, PD_LABEL, PD_LABEL_SHADOW);

    int dy = by + PD_SPRITE + 20;
    pd_fill(c, ix, dy - 2, iw, 1, PD_DIVIDER);
    int sectionCount = pd_dex_has_details(g) ? 4 : 1;
    if (sectionCount > 1) {
        // Chips switch what fills the rest of the entry (DexSection).
        static const char* const SECTIONS[4] = { "INFO", "EVOLVE", "AREA", "MOVES" };
        int cw = (iw - 3 * 2) / 4;
        for (int i = 0; i < 4; i++) {
            int cx = ix + i * (cw + 2);
            pd_title_box(c, cx, dy, cw, 18, i == ui->dexSection ? PD_TITLE_FILL : PD_LIST_FILL);
            pd_text(c, cx + (cw - pd_text_width(SECTIONS[i])) / 2, pd_text_y(dy, 18), SECTIONS[i],
                    i == ui->dexSection ? PD_VALUE : PD_LABEL, i == ui->dexSection ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
            ui_add_hit(ui, cx, dy, cw, 18, HIT_DEX_SECTION + i);
        }
        dy += 20;
    }
    int pageBottom = y + h - f - 28;
    if (ui->dexSection == 0 || sectionCount == 1) info_page(c, &e, ix, dy, iw, (y + h - f - 28 - dy) / 15);
    else section_page(ui, c, g, &e, ix, dy, iw, pageBottom - dy);

    // Step through the list (wrapping) or go back to it.
    int byBtn = y + h - f - 26;
    ui_button(ui, c, x + w - f - 70 - 2 * 40, byBtn, 36, 22, "", HIT_DEX_PREV);
    pd_triangle(c, x + w - f - 70 - 2 * 40 + 13, byBtn + 7, 6, PD_UP, PD_TITLE_TEXT);
    ui_button(ui, c, x + w - f - 70 - 40, byBtn, 36, 22, "", HIT_DEX_NEXT);
    pd_triangle(c, x + w - f - 70 - 40 + 13, byBtn + 8, 6, PD_DOWN, PD_TITLE_TEXT);
    ui_button(ui, c, x + w - f - 66, byBtn, 62, 22, "BACK", HIT_BACK);
}

void ui_dex_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    if (!pd_dex_available(g)) {
        ui_notice(c, "NO POKéDEX YET", "This version's POKéDEX tables", "aren't in the 3DS companion yet.");
        return;
    }
    if (!s->dexOk) {
        ui_notice(c, "NO POKéDEX YET", "The POKéDEX shows once the game", "has loaded a save.");
        return;
    }
    lastNational = national_view(ui, s);
    lastCount = dex_list(g, lastNational, lastList);
    if (ui->dexOpen) {
        entry_view(ui, c, g, s);
    } else {
        list_view(ui, c, g, s);
    }
}

bool ui_dex_act(struct pd_ui* ui, int id) {
    if (id >= HIT_DEX_SECTION && id < HIT_DEX_SECTION + 4) {
        ui->dexSection = id - HIT_DEX_SECTION;
        ui->dexPageScroll = 0;
        return true;
    }
    if (ui->tab != PD_TAB_DEX || ui->overlay != PD_OVERLAY_NONE) return false;
    if (id == HIT_DEX_TOGGLE) {
        // Flip from what shows now (the save's default until something was picked).
        ui->dexNational = !lastNational;
        ui->dexScroll = 0;
        return true;
    }
    if (id >= HIT_DEX_ROW && id <= HIT_DEX_ROW + PD_NATIONAL_COUNT) {
        ui->dexOpen = id - HIT_DEX_ROW;
        return true;
    }
    if ((id == HIT_DEX_PREV || id == HIT_DEX_NEXT) && lastCount > 0) {
        int at = 0;
        for (int i = 0; i < lastCount; i++) {
            if (lastList[i] == ui->dexOpen) at = i;
        }
        at = (at + lastCount + (id == HIT_DEX_NEXT ? 1 : -1)) % lastCount;
        ui->dexOpen = lastList[at];
        return true;
    }
    return false;
}
