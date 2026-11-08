// BAG: one pocket at a time in the game's own pocket order, its items in a
// list a drag scrolls, and the tapped item's description - in each game's bag
// colours (FireRedBag / EmeraldBag in the app's ItemsScreen.kt).
#include <stdio.h>

#include <stdlib.h>
#include <string.h>

#include "pd_games.h"
#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"

#define TAB_H 22
#define BAG_ROW 17
#define DESC_H 44
#define FIRST_HM 339 // ITEM_HM01..ITEM_HM08: no quantity, like the game
#define LAST_HM 346
#define MAX_TABS 9 // room for backend-native eight-pocket bags, plus ALL
#define MAX_ROWS 99 // HIT_BAG_ROW's ids

// What one tab lists: ALL, then each pocket with something in it, in the
// game's own order (the app's ItemsScreen: empty pockets get no tab).
struct bag_row {
    struct pd_item item;
    int pocket;
};

struct bag_view {
    int tabs, pockets[MAX_TABS]; // pockets[0] = -1: ALL
    int count;
    struct bag_row rows[MAX_ROWS];
};

// The game's pockets folded into PD_POCKET_*, each once, in the order the game first lists it.
static int base_pockets(const struct pd_game* g, int out[PD_POCKET_COUNT]) {
    int n = 0;
    int total = g->cfg->bagCount < PD_BAG_POCKETS_MAX ? g->cfg->bagCount : PD_BAG_POCKETS_MAX;
    for (int i = 0; i < total; i++) {
        int pocket = g->cfg->bagOrder[i], seen = 0;
        for (int j = 0; j < n; j++) seen |= out[j] == pocket;
        if (!seen && n < PD_POCKET_COUNT) out[n++] = pocket;
    }
    return n;
}

static void gather(struct pd_ui* ui, const struct pd_game* g, const struct pd_snapshot* s, int tab, struct bag_view* v) {
    if(ui->bagSource){
        v->tabs=ui->bagSource->tabs;
        if(v->tabs<1)v->tabs=1;
        if(v->tabs>MAX_TABS)v->tabs=MAX_TABS;
        if(tab<0 || tab>=v->tabs)tab=0;
        v->count=ui->bagSource->count(ui->bagSource->ctx,tab);return;
    }
    int order[PD_POCKET_COUNT];
    int n = base_pockets(g, order);
    v->tabs = 0;
    v->pockets[v->tabs++] = -1;
    for (int i = 0; i < n; i++) {
        if (s->bag[order[i]].count > 0) v->pockets[v->tabs++] = order[i];
    }
    if (tab < 0 || tab >= v->tabs) tab = 0;
    v->count = 0;
    for (int i = 0; i < n; i++) {
        int pocket = order[i];
        if (v->pockets[tab] >= 0 && pocket != v->pockets[tab]) continue;
        const struct pd_pocket_items* items = &s->bag[pocket];
        for (int j = 0; j < items->count && v->count < MAX_ROWS; j++) {
            v->rows[v->count].item = items->items[j];
            v->rows[v->count].pocket = pocket;
            v->count++;
        }
    }
}

static const struct pd_game* sortGame;

static int by_name(const void* a, const void* b) {
    return strcmp(pd_item_name(sortGame, ((const struct bag_row*) a)->item.id), pd_item_name(sortGame, ((const struct bag_row*) b)->item.id));
}

static int by_count(const void* a, const void* b) {
    int d = ((const struct bag_row*) b)->item.quantity - ((const struct bag_row*) a)->item.quantity;
    return d ? d : by_name(a, b);
}

// The pocket tabs down the left, the open one joined to the list.
static void tabs(struct pd_ui* ui, struct pd_canvas* c, const struct pd_bag_palette* p, const struct bag_view* v,
                 int x, int w, int joinX) {
    int visible=(c->h-CONTENT_Y)/(TAB_H+3);
    int first=ui->bagSource && ui->bagPocket>=visible ? ui->bagPocket-visible+1:0;
    for (int i = first; i < v->tabs; i++) {
        int y = CONTENT_Y + (i-first) * (TAB_H + 3);
        if(ui->bagSource && y+TAB_H>c->h)break;
        bool open = i == ui->bagPocket;
        uint32_t fill = open ? p->fill : p->panel[p->tabLayers].color;
        // The open tab is drawn on into the list, its top and bottom lines meeting the list's frame, and its fill carried
        // through that frame: one shape with the list.
        int f = pd_layered_box(c, x, y, open ? joinX + 4 - x : w, TAB_H, 3, p->panel, p->tabLayers, fill);
        if (open) pd_fill(c, x + w - f - 1, y + f, joinX + 5 - (x + w - f - 1), TAB_H - 2 * f, p->fill);
        const char* name = ui->bagSource ? ui->bagSource->pocketName(ui->bagSource->ctx, i) : v->pockets[i] < 0 ? "ALL" : pd_pocket_name(v->pockets[i]);
        int tx = x + f + 5, ty = pd_text_y(y, TAB_H);
        int tw = pd_text_fit(c, tx, ty, w - 2 * f - 8, name, open ? p->text : p->tabText,
                             open ? p->textShadow : p->tabTextShadow);
        // Under the letters, not through them: the tab's last rows.
        if (open) pd_fill(c, tx, y + TAB_H - f - 2, tw, 2, p->cursor);
        if (ui->pressedId == HIT_BAG_TAB + i) pd_fill(c, x + f, y + f, w - 2 * f, 1, p->cursor);
        ui_add_hit(ui, x, y, w, TAB_H, HIT_BAG_TAB + i);
    }
}

static void list(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_bag_palette* p,
                 const struct bag_view* v, int x, int y, int w, int h) {
    int f = pd_layered_box(c, x, y, w, h, 3, p->panel, p->panelLayers, p->fill);
    int ix = x + f, iy = y + f + 1, iw = w - 2 * f, ih = h - 2 * f - 2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_BAG_LIST);
    if (v->count == 0) {
        pd_text(c, ix + 14, pd_text_y(iy, BAG_ROW), "The bag is empty.", p->text, p->textShadow);
        return;
    }
    int maxScroll = v->count * BAG_ROW - ih;
    if (maxScroll < 0) maxScroll = 0;
    // The selected row, and the row a host's D-pad cursor is on, come wholly into view when they change (a row cut at
    // the window's edge); after that the list scrolls freely (the D-pad walks on past the window).
    static int seenSel = -1, seenFocus = -1;
    if (ui->bagSelected >= 0 && (ui->bagSource || ui->bagSelected != seenSel))
        ui->bagScroll = ui_scroll_to(ui->bagScroll, ui->bagSelected * BAG_ROW, BAG_ROW, ih);
    if (ui->focusId != seenFocus && ui->focusId >= HIT_BAG_ROW && ui->focusId < HIT_BAG_ROW + v->count)
        ui->bagScroll = ui_scroll_to(ui->bagScroll, (ui->focusId - HIT_BAG_ROW) * BAG_ROW, BAG_ROW, ih);
    seenSel = ui->bagSelected;
    seenFocus = ui->focusId;
    if (ui->bagScroll > maxScroll) ui->bagScroll = maxScroll;
    if (ui->bagScroll < 0) ui->bagScroll = 0;

    pd_clip(c, ix, iy, iw, ih);
    for (int i = ui->bagScroll / BAG_ROW; i < v->count; i++) {
        int ry = iy + i * BAG_ROW - ui->bagScroll;
        if (ry >= iy + ih) break;
        struct pd_item native;
        bool quantity=true;
        const struct pd_item* it = ui->bagSource ? &native : &v->rows[i].item;
        if(ui->bagSource){
            if(!ui->bagSource->item(ui->bagSource->ctx,ui->bagPocket,i,&native,&quantity))continue;
            it=&native;
        }
        bool cursor = i == ui->bagSelected || ui->pressedId == HIT_BAG_ROW + i;
        if (cursor) pd_triangle(c, ix + 4, ry + 3, 6, PD_RIGHT, p->cursor);
        const char* name = ui->bagSource ? ui->bagSource->name(ui->bagSource->ctx,it->id) : pd_item_name(g,it->id);
        char fallback[16];
        if (!*name) {
            snprintf(fallback, sizeof(fallback), "ITEM %d", it->id);
            name = fallback;
        }
        pd_text_fit(c, ix + 14, pd_text_y(ry, BAG_ROW), iw - 64, name, p->text, p->textShadow);
        if (ui->bagSource ? quantity : v->rows[i].pocket != PD_POCKET_KEY && !(it->id >= FIRST_HM && it->id <= LAST_HM)) {
            char qty[12];
            snprintf(qty, sizeof(qty), "× %d", it->quantity);
            pd_text_right(c, ix + iw - 10, pd_text_y(ry, BAG_ROW), qty, p->text, p->textShadow);
        }
        if (p->hasDivider) pd_fill(c, ix + 4, ry + BAG_ROW - 1, iw - 8, 1, p->divider);
        // Rows cut off at the window's edge still take a tap on what shows.
        int top = ry < iy ? iy : ry;
        int bottom = ry + BAG_ROW > iy + ih ? iy + ih : ry + BAG_ROW;
        if(!ui->bagSource)ui_add_scroll_hit(ui, ix, top, iw, bottom - top, HIT_BAG_ROW + i);
    }
    pd_unclip(c);
    // A scroll bar when the list runs past the window.
    if (maxScroll > 0) {
        int total = v->count * BAG_ROW;
        int th = ih * ih / total;
        if (th < 8) th = 8;
        int ty = iy + (ih - th) * ui->bagScroll / maxScroll;
        pd_fill(c, ix + iw - 4, ty, 2, th, p->cursor);
    }
}

// The picked item's description, a window along the bottom (ItemsScreen's), USE / GIVE at its right where they work.
static void description(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_bag_palette* p,
                        const struct pd_host_info* host, int id, int y) {
    int x = MARGIN, w = c->w - 2 * MARGIN, h = c->h - y - MARGIN;
    int f = pd_layered_box(c, x, y, w, h, 3, p->desc, p->descLayers, p->descFill);
    bool use = host && host->itemCanUse && host->itemCanUse(id), give = host && host->itemCanGive && host->itemCanGive(id);
    int bw = 44, room = use || give ? bw + 6 : 0;
    const char* text = ui->bagSource ? ui->bagSource->name(ui->bagSource->ctx, id) : pd_item_description(g, id);
    if (!*text) text = pd_item_name(g, id);
    int tx = x + f + 6, tw = w - 2 * (f + 6) - room;
    if (pd_draw_item_icon(c, g, id, x + f + 3, y + (h - PD_ITEM_ICON) / 2, false)) {
        tx += PD_ITEM_ICON + 2;
        tw -= PD_ITEM_ICON + 2;
    }
    pd_text_wrap(c, tx, y + f + 1, tw, 16, (h - 2 * f) / 16, text, p->descText, p->descTextShadow);
    int bh = (h - 2 * f - 2) / 2, by = y + f + 1, bx = x + w - f - bw - 2;
    if (use) ui_button(ui, c, bx, by, bw, bh, "USE", HIT_BAG_USE);
    if (give) ui_button(ui, c, bx, use ? by + bh + 2 : by, bw, bh, "GIVE", HIT_BAG_GIVE);
}

// Who it goes to: the party's slots, dimmed where it can't (ItemPartyPicker), over the question and CANCEL.
static void item_picker(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    int barH = 26, y = c->h - barH - 2;
    struct pd_canvas top = *c;
    top.h = y;
    if (top.cy1 > y) top.cy1 = y;
    ui_item_grid(ui, &top, g, s);
    int x = MARGIN, w = c->w - 2 * MARGIN;
    char text[64];
    snprintf(text, sizeof(text), ui->itemGive ? "Give %s to which POKéMON?" : "Use %s on which POKéMON?", pd_item_name(g, ui->itemId));
    int f = pd_title_box(c, x, y, w, barH, PD_TITLE_FILL);
    pd_text_fit(c, x + f + 5, pd_text_y(y, barH), w - 84, text, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    ui_button(ui, c, x + w - 68, y + 3, 62, barH - 6, "CANCEL", HIT_ITEM_CANCEL);
    if (ui->itemMoveSlot < 0) return;
    // A one-move PP item: which move, a list over the rest.
    pd_dim(c, 0xA0);
    const struct pd_mon* m = &s->party[ui->itemMoveSlot];
    int ly = 40, rowH = 26, lh = 4 * rowH + 8;
    int lf = pd_list_box(c, x + 30, ly, w - 60, lh);
    for (int i = 0; i < PD_NUM_MOVES; i++) {
        int ry = ly + lf + 2 + i * rowH;
        char buf[40];
        if (!m->moves[i]) continue;
        bool ok = ui->itemSlots[ui->itemMoveSlot].moves[i];
        pd_text(c, x + 40, pd_text_y(ry, rowH), pd_move_name(g, m->moves[i], buf, sizeof(buf)), ok ? PD_LABEL : PD_MUTED,
                ok ? PD_LABEL_SHADOW : PD_MUTED_SHADOW);
        snprintf(buf, sizeof(buf), "PP %d", m->pp[i]);
        pd_text_right(c, x + w - 40, pd_text_y(ry, rowH), buf, PD_VALUE, PD_VALUE_SHADOW);
        if (ok) ui_add_hit(ui, x + 30, ry, w - 60, rowH, HIT_ITEM_MOVE + i);
    }
}

bool ui_bag_act(struct pd_ui* ui, int id, const struct pd_snapshot* s) {
    if (id == HIT_BAG_SORT) {
        ui->bagSort = (ui->bagSort + 1) % 3;
        ui->bagSelected = -1;
        return true;
    }
    if (id == HIT_BAG_USE || id == HIT_BAG_GIVE) {
        ui->itemGive = id == HIT_BAG_GIVE;
        ui->itemId = ui->bagItemShown;
        ui->action = PD_ACTION_ITEM_PICK;
        return true;
    }
    if (id == HIT_ITEM_CANCEL) {
        if (ui->itemMoveSlot >= 0) ui->itemMoveSlot = -1;
        else ui->itemPicking = false;
        return true;
    }
    if (id >= HIT_ITEM_SLOT && id < HIT_ITEM_SLOT + PD_PARTY_SIZE) {
        int slot = id - HIT_ITEM_SLOT;
        if (!ui->itemSlots[slot].ok) return true;
        bool anyMove = false;
        for (int i = 0; i < 4; i++) anyMove |= ui->itemSlots[slot].moves[i];
        if (anyMove) {
            ui->itemMoveSlot = slot;
            return true;
        }
        ui->itemSlot = slot;
        ui->itemMove = 0;
        ui->itemPicking = false;
        ui->action = PD_ACTION_ITEM_APPLY;
        return true;
    }
    if (id >= HIT_ITEM_MOVE && id < HIT_ITEM_MOVE + 4) {
        ui->itemSlot = ui->itemMoveSlot;
        ui->itemMove = id - HIT_ITEM_MOVE;
        ui->itemMoveSlot = -1;
        ui->itemPicking = false;
        ui->action = PD_ACTION_ITEM_APPLY;
        return true;
    }
    (void) s;
    return false;
}

void ui_bag_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                const struct pd_host_info* host) {
    if (ui->itemPicking) {
        item_picker(ui, c, g, s);
        return;
    }
    if (!s->bagOk) {
        ui_notice(c, "NO BAG YET", "The bag shows once the game", "has loaded a save.");
        return;
    }
    const struct pd_bag_palette* p = ui->bagPalette ? ui->bagPalette : pd_bag_cur;
    if (p->hasBackdrop) {
        for (int y = 0; y < c->h; y += 8) pd_fill(c, 0, y, c->w, 8, (y / 8) % 2 ? p->backdropB : p->backdropA);
    }
    static struct bag_view v;
    gather(ui, g, s, ui->bagPocket, &v);
    sortGame = g;
    if (ui->bagSort == 1) qsort(v.rows, (size_t) v.count, sizeof(v.rows[0]), by_name);
    else if (ui->bagSort == 2) qsort(v.rows, (size_t) v.count, sizeof(v.rows[0]), by_count);
    if (ui->bagPocket < 0 || ui->bagPocket >= v.tabs) ui->bagPocket = 0;
    if (ui->bagSelected >= v.count) ui->bagSelected = -1;
    int tabW = c->w * 27 / 100, panelX = MARGIN + tabW - 3;
    bool details = ui->bagSelected >= 0 && (!ui->bagSource || ui->bagDetails);
    int bottom = details ? c->h - MARGIN - DESC_H - 4 : c->h - MARGIN;
    list(ui, c, g, p, &v, panelX, CONTENT_Y, c->w - MARGIN - panelX, bottom - CONTENT_Y);
    int frame = 0;
    for (int i = 0; i < p->panelLayers; i++) frame += p->panel[i].width;
    tabs(ui, c, p, &v, MARGIN, tabW - 4, panelX + frame);
    if (ui->bagSelected < 0 && !ui->bagSource) {
        // SORT: under the tabs, like the description bar's corner.
        static const char* const SORTS[3] = { "DEFAULT", "NAME", "COUNT" };
        int sy = c->h - MARGIN - 22, sw = tabW - 4;
        pd_layered_box(c, MARGIN, sy, sw, 22, 3, p->desc, p->descLayers, p->descFill);
        pd_text(c, MARGIN + (sw - pd_text_width(SORTS[ui->bagSort])) / 2, pd_text_y(sy, 22), SORTS[ui->bagSort], p->descText, p->descTextShadow);
        ui_add_hit(ui, MARGIN, sy, sw, 22, HIT_BAG_SORT);
    }
    if (details) {
        if (ui->bagSource) {
            // A backend's bag (libretro's DS companion): its own item and words, no USE / GIVE.
            struct pd_item item;
            bool quantity;
            if (ui->bagSource->item(ui->bagSource->ctx, ui->bagPocket, ui->bagSelected, &item, &quantity))
                description(ui, c, g, p, NULL, item.id, bottom + 4);
        } else {
            ui->bagItemShown = v.rows[ui->bagSelected].item.id;
            description(ui, c, g, p, host, ui->bagItemShown, bottom + 4);
        }
    }
}

void pd_ui_bag_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    ui->hitCount = 0;
    if (!ui->keepBackdrop) pd_backdrop(c);
    ui_bag_tab(ui, c, g, s, NULL);
}
