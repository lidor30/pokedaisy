// BAG: one pocket at a time in the game's own pocket order, its items in a
// list a drag scrolls, and the tapped item's description - in each game's bag
// colours (FireRedBag / EmeraldBag in the app's ItemsScreen.kt).
#include <stdio.h>

#include "pd_ui_internal.h"

struct bag_palette {
    struct pd_layer panel[4];
    int panelLayers;
    int tabLayers; // the header reuses this many panel layers, filled with the next one's colour
    uint32_t fill, divider; // divider 0 = none
    uint32_t text, textShadow;
    uint32_t tabText, tabTextShadow;
    uint32_t cursor;
    struct pd_layer desc[2];
    int descLayers;
    uint32_t descFill, descText, descTextShadow;
    uint32_t backdropA, backdropB; // 0 = the companion's own backdrop
};

static const struct bag_palette FIRERED_BAG = {
    .panel = { { 0x686868, 2 }, { 0xE8E0A8, 1 }, { 0xF0C870, 2 }, { 0xD0B050, 2 } },
    .panelLayers = 4,
    .tabLayers = 2,
    .fill = 0xF8F8C8, .divider = 0xE8E0A8,
    .text = 0x606060, .textShadow = 0xD0D0C8,
    .tabText = 0xF8F8F8, .tabTextShadow = 0x606060,
    .cursor = 0xD88848,
    .desc = { { 0x005070, 2 }, { 0x10A8D8, 1 } },
    .descLayers = 2,
    .descFill = 0x0078C0, .descText = 0xF8F8F8, .descTextShadow = 0x606060,
};

static const struct bag_palette EMERALD_BAG = {
    .panel = { { 0x335170, 2 }, { 0x2C3941, 1 }, { 0x636372, 1 }, { 0xFBE898, 2 } },
    .panelLayers = 4,
    .tabLayers = 3,
    .fill = 0xFFFFD3, .divider = 0,
    .text = 0x000000, .textShadow = 0xD6D6CF,
    .tabText = 0x000000, .tabTextShadow = 0xB89D63,
    .cursor = 0xEC5F2A,
    .desc = { { 0x7B7B7B, 1 } },
    .descLayers = 1,
    .descFill = 0xFFFFFF, .descText = 0x000000, .descTextShadow = 0xD6D6CF,
    .backdropA = 0xC871F7, .backdropB = 0x5882F7,
};

// Game Boy: black on white, the games' double-line frame; the cursor is an ink bar like the menus' arrow.
static const struct bag_palette GB_BAG = {
    .panel = { { 0x181818, 1 }, { 0xFFFFFF, 1 }, { 0x181818, 1 } },
    .panelLayers = 3,
    .tabLayers = 2,
    .fill = 0xFFFFFF, .divider = 0xC8C8C8,
    .text = 0x181818, .textShadow = 0xFFFFFF,
    .tabText = 0xFFFFFF, .tabTextShadow = 0x181818, // the closed tabs are ink chips: white on them
    .cursor = 0x181818,
    .desc = { { 0x181818, 1 } },
    .descLayers = 1,
    .descFill = 0xFFFFFF, .descText = 0x181818, .descTextShadow = 0xFFFFFF,
};

#define TAB_H 22
#define BAG_ROW 17
#define DESC_H 44
#define FIRST_HM 339 // ITEM_HM01..ITEM_HM08: no quantity, like the game
#define LAST_HM 346
#define MAX_TABS 9 // room for backend-native eight-pocket bags, plus ALL
#define MAX_ROWS 99 // HIT_BAG_ROW's ids

// What one tab lists: ALL, then each pocket with something in it, in the
// game's own order (the app's ItemsScreen: empty pockets get no tab).
struct bag_view {
    int tabs, pockets[MAX_TABS]; // pockets[0] = -1: ALL
    int count;
    struct {
        struct pd_item item;
        int pocket;
    } rows[MAX_ROWS];
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

// The pocket tabs down the left, the open one joined to the list.
static void tabs(struct pd_ui* ui, struct pd_canvas* c, const struct bag_palette* p, const struct bag_view* v,
                 int x, int w, int joinX) {
    int visible=(c->h-CONTENT_Y)/(TAB_H+3);
    int first=ui->bagSource && ui->bagPocket>=visible ? ui->bagPocket-visible+1:0;
    for (int i = first; i < v->tabs; i++) {
        int y = CONTENT_Y + (i-first) * (TAB_H + 3);
        if(ui->bagSource && y+TAB_H>c->h)break;
        bool open = i == ui->bagPocket;
        uint32_t fill = open ? p->fill : p->panel[p->tabLayers].color;
        int f = pd_layered_box(c, x, y, w, TAB_H, 3, p->panel, p->tabLayers, fill);
        // The open tab runs into the list: its fill across both borders.
        if (open) pd_fill(c, x + w - f, y + f, joinX - (x + w - f), TAB_H - 2 * f, p->fill);
        const char* name = ui->bagSource ? ui->bagSource->pocketName(ui->bagSource->ctx,i) : v->pockets[i] < 0 ? "ALL" : pd_pocket_name(v->pockets[i]);
        int tx = x + f + 5, ty = pd_text_y(y, TAB_H);
        int tw = pd_text_fit(c, tx, ty, w - 2 * f - 8, name, open ? p->text : p->tabText,
                             open ? p->textShadow : p->tabTextShadow);
        if (open) { // the open tab's underline: under the text's shadow (caps rows 4-12, shadow 13), inside the border
            int uy = ty + 14, uh = y + TAB_H - f - uy;
            if (uh > 2) uh = 2;
            if (uh > 0) pd_fill(c, tx, uy, tw, uh, p->cursor);
        }
        if (ui->pressedId == HIT_BAG_TAB + i) pd_fill(c, x + f, y + f, w - 2 * f, 1, p->cursor);
        ui_add_hit(ui, x, y, w, TAB_H, HIT_BAG_TAB + i);
    }
}

static void list(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct bag_palette* p,
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
        if (p->divider) pd_fill(c, ix + 4, ry + BAG_ROW - 1, iw - 8, 1, p->divider);
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

// The picked item's description, a window along the bottom (ItemsScreen's).
static void description(struct pd_ui* ui,struct pd_canvas* c, const struct pd_game* g, const struct bag_palette* p, int id, int y) {
    int x = MARGIN, w = c->w - 2 * MARGIN, h = c->h - y - MARGIN;
    int f = pd_layered_box(c, x, y, w, h, 3, p->desc, p->descLayers, p->descFill);
    const char* text = ui->bagSource ? ui->bagSource->name(ui->bagSource->ctx,id) : pd_item_description(g,id);
    if (!*text) text = pd_item_name(g, id);
    pd_text_wrap(c, x + f + 6, y + f + 1, w - 2 * (f + 6), 16, (h - 2 * f) / 16, text, p->descText,
                 p->descTextShadow);
}

void ui_bag_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    if (!s->bagOk) {
        ui_notice(c, "NO BAG YET", "The bag shows once the game", "has loaded a save.");
        return;
    }
    const struct bag_palette* p = g->kind == PD_GAME_GB ? &GB_BAG : g->kind == PD_GAME_EMERALD ? &EMERALD_BAG : &FIRERED_BAG;
    if (p->backdropA) {
        for (int y = 0; y < c->h; y += 8) pd_fill(c, 0, y, c->w, 8, (y / 8) % 2 ? p->backdropB : p->backdropA);
    }
    static struct bag_view v;
    gather(ui,g, s, ui->bagPocket, &v);
    if (ui->bagPocket < 0 || ui->bagPocket >= v.tabs) ui->bagPocket = 0;
    if (ui->bagSelected >= v.count) ui->bagSelected = -1;
    int tabW = c->w * 27 / 100, panelX = MARGIN + tabW - 3;
    bool details=ui->bagSelected>=0 && (!ui->bagSource || ui->bagDetails);
    int bottom = details ? c->h - MARGIN - DESC_H - 4 : c->h - MARGIN;
    list(ui, c, g, p, &v, panelX, CONTENT_Y, c->w - MARGIN - panelX, bottom - CONTENT_Y);
    int frame = 0;
    for (int i = 0; i < p->panelLayers; i++) frame += p->panel[i].width;
    tabs(ui, c, p, &v, MARGIN, tabW - 4, panelX + frame);
    if(details){
        struct pd_item item;bool quantity;
        if(ui->bagSource){if(ui->bagSource->item(ui->bagSource->ctx,ui->bagPocket,ui->bagSelected,&item,&quantity))description(ui,c,g,p,item.id,bottom+4);}
        else description(ui,c,g,p,v.rows[ui->bagSelected].item.id,bottom+4);
    }
}

void pd_ui_bag_view(struct pd_ui* ui,struct pd_canvas* c,const struct pd_game* g,const struct pd_snapshot* s){
    ui->hitCount=0;pd_backdrop(c);ui_bag_tab(ui,c,g,s);
}
