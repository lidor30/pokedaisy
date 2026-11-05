// POKéDEX: the list (national or the game's regional dex) with the save's
// seen / caught marks, and an entry page read from the ROM - the app's
// PokedexScreen, fitted to 320x212.
#include <stdio.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_gb.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define TITLE_H 24
#define LIST_Y (CONTENT_Y + TITLE_H + 4)
#define DEX_ROW 18
#define TOGGLE_W 78

// Whether the National Dex shows: the player's pick, else the save's own.
static bool national_view(const struct pd_ui* ui, const struct pd_snapshot* s) {
    return ui->dexNational >= 0 ? ui->dexNational : s->dexNational;
}

// The list: national numbers in the order shown; returns how many.
static int dex_list(const struct pd_game* g, bool national, int* out) {
    int n = 0;
    if (g->cfg->gb) {  // a Game Boy game: its one Dex, in order
        for (int i = 1; i <= g->cfg->gb->dexCount; i++) out[n++] = i;
    } else if (national) {
        for (int i = 1; i <= g->cfg->nationalCount; i++) {
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
    int x = MARGIN, w = c->w - 2 * MARGIN - (!ui->dexSource && g->cfg->gb ? 0 : TOGGLE_W + 4);
    int f = pd_title_box(c, x, CONTENT_Y, w, TITLE_H, PD_TITLE_FILL);
    int ty = pd_text_y(CONTENT_Y, TITLE_H);
    pd_text(c, x + f + 6, ty, "POKéDEX", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    int seen = ui->dexSource?ui->dexSource->seenCount:0, caught = ui->dexSource?ui->dexSource->caughtCount:0;
    for (int i = 0; !ui->dexSource && i < n; i++) {
        seen += pd_dex_seen(s, list[i]);
        caught += pd_dex_caught(s, list[i]);
    }
    char counts[40];
    snprintf(counts, sizeof(counts), "SEEN %d  OWN %d", seen, caught);
    pd_text_right(c, x + w - f - 6, ty, counts, PD_VALUE, PD_VALUE_SHADOW);
    // Which dex: tap for the other one (a Game Boy game has just the one).
    if (!ui->dexSource && g->cfg->gb) return;
    char other[24];
    snprintf(other, sizeof(other), "%s", ui->dexSource ? ui->dexSource->filterLabel : national ? g->cfg->regionName : "NATIONAL");
    ui_button(ui, c, x + w + 4, CONTENT_Y, TOGGLE_W, TITLE_H, other, HIT_DEX_TOGGLE);
}

// The list shown (rebuilt each draw), kept for the tap handler, which has no
// game at hand: the toggle flips what shows, up / down step through it.
static int lastList[PD_DEX_BYTES * 8], lastCount;
static bool lastNational;

static void list_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const int* list = lastList;
    int n = ui->dexSource?ui->dexSource->count:lastCount;
    bool national = ui->dexSource?true:lastNational;
    title(ui, c, g, s, list, n, national);

    int x = MARGIN, w = c->w - 2 * MARGIN;
    int f = pd_list_box(c, x, LIST_Y, w, c->h - LIST_Y - MARGIN);
    int ix = x + f, iy = LIST_Y + f + 1, iw = w - 2 * f, ih = c->h - LIST_Y - MARGIN - 2 * f - 2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_DEX_LIST);
    // The row a host's D-pad cursor moves onto comes wholly into view (a row cut at the window's edge).
    static int seenFocus = -1;
    for (int i = 0; !ui->dexSource && ui->focusId != seenFocus && i < n; i++)
        if (ui->focusId == HIT_DEX_ROW + list[i]) ui->dexScroll = ui_scroll_to(ui->dexScroll, i * DEX_ROW, DEX_ROW, ih);
    seenFocus = ui->focusId;
    ui->dexScroll = ui_clamp_scroll(ui->dexScroll, n * DEX_ROW, ih);
    if(ui->dexSource && n==0)pd_text(c,ix+12,iy+4,"No matching entries.",PD_MUTED,PD_MUTED_SHADOW);
    pd_clip(c, ix, iy, iw, ih);
    char buf[40], num[16];
    for (int i = ui->dexScroll / DEX_ROW; i < n; i++) {
        int ry = iy + i * DEX_ROW - ui->dexScroll;
        if (ry >= iy + ih) break;
        int nat = ui->dexSource?ui->dexSource->national(ui->dexSource->ctx,i):list[i];
        bool seen = ui->dexSource?ui->dexSource->seen(ui->dexSource->ctx,nat):pd_dex_seen(s,nat);
        bool caught = ui->dexSource?ui->dexSource->caught(ui->dexSource->ctx,nat):pd_dex_caught(s,nat);
        if (ui->pressedId == HIT_DEX_ROW + nat) pd_fill(c, ix, ry, iw, DEX_ROW, PD_ROW_SELECTED);
        if (seen) ui_ball(c, ix + 6, ry + 4, caught);
        snprintf(num, sizeof(num), "No.%03d", shown_number(g, national, nat));
        int ty = pd_text_y(ry, DEX_ROW);
        pd_text(c, ix + 22, ty, num, PD_MUTED, PD_MUTED_SHADOW);
        const char* name = ui->dexSource ? (seen?ui->dexSource->name(ui->dexSource->ctx,nat):"???") : pd_species_name(g, pd_dex_species(g, nat), buf, sizeof(buf));
        pd_text(c, ix + 74, ty, name, seen ? PD_LABEL : PD_MUTED, seen ? PD_LABEL_SHADOW : PD_MUTED_SHADOW);
        int top = ry < iy ? iy : ry;
        int bottom = ry + DEX_ROW > iy + ih ? iy + ih : ry + DEX_ROW;
        if (!ui->dexSource && (!g->cfg->gb || pd_gb_dex_pages(g)))
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
    if (spriteOk) draw_sprite(c, bx + 8, by + 8, sprite);

    int tx = bx + PD_SPRITE + 24, tw = ix + iw - tx;
    snprintf(buf, sizeof(buf), "No.%03d", shown_number(g, national, e.national));
    pd_text(c, tx, top, buf, PD_MUTED, PD_MUTED_SHADOW);
    int nw = pd_text(c, tx + 50, top, pd_species_name(g, e.species, buf2, sizeof(buf2)), PD_LABEL, PD_LABEL_SHADOW);
    if (pd_dex_seen(s, e.national)) ui_ball(c, tx + 50 + nw + 6, top + 4, pd_dex_caught(s, e.national));
    snprintf(buf, sizeof(buf), "%s POKéMON", e.category);
    pd_text_fit(c, tx, top + 18, tw, buf, PD_MUTED, PD_MUTED_SHADOW);
    if (e.type2 != e.type1 && e.type2 != PD_TYPE_NONE) {
        snprintf(buf, sizeof(buf), "%s / %s", pd_type_name(g, e.type1), pd_type_name(g, e.type2));
    } else {
        snprintf(buf, sizeof(buf), "%s", pd_type_name(g, e.type1));
    }
    pd_text(c, tx, top + 36, buf, PD_VALUE, PD_VALUE_SHADOW);
    char ht[16], wt[20];
    pd_dex_height(e.heightDm, ht, sizeof(ht));
    pd_dex_weight(e.weightHg, wt, sizeof(wt));
    if (e.heightIn) snprintf(ht, sizeof(ht), "%d'%02d\"", e.heightIn / 12, e.heightIn % 12);  // the cart's own numbers
    if (e.weightLbs10) snprintf(wt, sizeof(wt), "%d.%d lbs.", e.weightLbs10 / 10, e.weightLbs10 % 10);
    snprintf(buf, sizeof(buf), "HT %s   WT %s", ht, wt);
    pd_text_fit(c, tx, top + 54, tw, buf, PD_LABEL, PD_LABEL_SHADOW);

    int dy = by + PD_SPRITE + 20;
    pd_fill(c, ix, dy - 2, iw, 1, PD_DIVIDER);
    pd_text_wrap(c, ix, dy, iw, 16, 3, e.description, PD_LABEL, PD_LABEL_SHADOW);
    dy += 3 * 16 + 2;
    static const char* const STAT[6] = { "HP", "ATK", "DEF", "SPA", "SPD", "SPE" };
    static const char* const STAT1[5] = { "HP", "ATK", "DEF", "SPE", "SPC" };  // Gen 1's own five, its order
    int sx = ix;
    for (int i = 0; i < (e.gen1Stats ? 5 : 6); i++) {
        if (e.gen1Stats) {
            sx += pd_text(c, sx, dy, STAT1[i], PD_MUTED, PD_MUTED_SHADOW) + 3;
            snprintf(buf, sizeof(buf), "%d", e.stats[i]);
            sx += pd_text(c, sx, dy, buf, PD_LABEL, PD_LABEL_SHADOW) + 9;
            continue;
        }
        sx += pd_text(c, sx, dy, STAT[i], PD_MUTED, PD_MUTED_SHADOW) + 3;
        snprintf(buf, sizeof(buf), "%d", e.stats[i]);
        sx += pd_text(c, sx, dy, buf, PD_LABEL, PD_LABEL_SHADOW) + 9;
    }
    dy += 18;
    if (e.gen1Stats) goto buttons;  // no abilities in Gen 1
    if (e.ability2[0]) {
        snprintf(buf, sizeof(buf), "%s / %s", e.ability1, e.ability2);
    } else {
        snprintf(buf, sizeof(buf), "%s", e.ability1);
    }
    int aw = pd_text(c, ix, dy, "ABILITY", PD_MUTED, PD_MUTED_SHADOW);
    pd_text_fit(c, ix + aw + 6, dy, iw - aw - 150, buf, PD_LABEL, PD_LABEL_SHADOW);

buttons:;
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
    if (ui->tab != PD_TAB_DEX || ui->overlay != PD_OVERLAY_NONE) return false;
    if (id == HIT_DEX_TOGGLE) {
        // Flip from what shows now (the save's default until something was picked).
        ui->dexNational = !lastNational;
        ui->dexScroll = 0;
        return true;
    }
    if (id >= HIT_DEX_ROW && id <= HIT_DEX_ROW + PD_DEX_BYTES * 8) {
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

void pd_ui_dex_view(struct pd_ui* ui,struct pd_canvas* c,const struct pd_game* g,const struct pd_snapshot* s,int selected){
    ui->hitCount=0;pd_backdrop(c);
    if(!ui->dexSource){ui_dex_tab(ui,c,g,s);return;}
    int n=ui->dexSource->count;
    if(selected<0)selected=0;
    if(n>0 && selected>=n)selected=n-1;
    ui->dexScroll=selected*DEX_ROW;ui->pressedId=n>0?HIT_DEX_ROW+ui->dexSource->national(ui->dexSource->ctx,selected):-1;
    list_view(ui,c,g,s);
}
