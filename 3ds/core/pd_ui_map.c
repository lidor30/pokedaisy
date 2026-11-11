// MAP: the game's region map rebuilt from the ROM (pd_map.c), filling the tab like the app's, the
// player's head on the tile the game puts it, FireRed's corner cursor, the
// place name in the game's own label (the app's MapScreen.kt), a tap naming
// what's under it, Kanto / Sevii pages, ME and the PLACES list.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "pd_map.h"
#include "pd_games.h"
#include "pd_gb.h"
#include "pd_ui_internal.h"

#define FRAME 5
#define MAX_RECTS 8
// The most MapScreen stretches the map to fill the tab (MAX_MAP_STRETCH); past it the map keeps its shape.
#define PLACE_ROW 18
#define CURSOR 0xFFFFFF
#define CURSOR_ARM 5
#define CURSOR_THICK 2
// FireRed's label: a darkening strip, white text over grey.
#define FR_LABEL_SHADOW 0x636363

static const struct pd_game* lastGame;

// Where the map is drawn this time: (x, y) w x h canvas pixels for its mapW x mapH (240 x 160, a Game Boy's 160 x 144).
static struct { int x, y, w, h; } view = { MARGIN + FRAME, MARGIN + FRAME, PD_MAP_W, PD_MAP_H };
// The page's size (240 x 160, a Game Boy's 160 x 144: its pixels' row stride) and what of it is shown: its map, without
// the border around it (pd_map_art's crop).
static int mapW = PD_MAP_W, mapH = PD_MAP_H;
static struct { int l, t, r, b; } crop = { 0, 0, PD_MAP_W, PD_MAP_H };
static int sx(int mx) { return view.x + (mx - crop.l) * view.w / (crop.r - crop.l); }
static int sy(int my) { return view.y + (my - crop.t) * view.h / (crop.b - crop.t); }
static int lastPlayerPage = -1, lastPlayerTx, lastPlayerTy;

// --- the PLACES list (RegionMapModel.places) ---

struct place {
    int mapsec; // -1 = a group title
    const char* name;
};
#define MAX_PLACES 200
static struct place places[MAX_PLACES];
static int placeCount;
static unsigned placesRom;

static bool starts_route(const char* n) {
    return !strncasecmp(n, "route ", 6);
}

static bool is_town(const char* n) {
    const char* last = strrchr(n, ' ');
    last = last ? last + 1 : n;
    static const char* const WORDS[] = { "town", "city", "village", "island", "plateau" };
    for (size_t i = 0; i < sizeof(WORDS) / sizeof(WORDS[0]); i++) {
        if (!strcasecmp(last, WORDS[i])) return true;
    }
    return false;
}

static int cmp_place(const void* a, const void* b) {
    const struct place *x = a, *y = b;
    if (starts_route(x->name) && starts_route(y->name)) {
        int nx = atoi(x->name + 6), ny = atoi(y->name + 6);
        if (nx != ny) return nx - ny;
    }
    return strcasecmp(x->name, y->name);
}

static void build_places(const struct pd_game* g) {
    if (placesRom == g->loadId && placeCount) return;
    placesRom = g->loadId;
    placeCount = 0;
    static struct place towns[MAX_PLACES], routes[MAX_PLACES], other[MAX_PLACES];
    int nt = 0, nr = 0, no = 0;
    for (int id = 0; id < 256; id++) {
        const char* name = pd_mapsec_name(g, id);
        int page;
        struct pd_tile_rect r;
        if (!*name || pd_map_tiles(g, id, &page, &r, 1) < 1) continue;
        bool dup = false;
        for (int i = 0; i < nt && !dup; i++) dup = !strcasecmp(towns[i].name, name);
        for (int i = 0; i < nr && !dup; i++) dup = !strcasecmp(routes[i].name, name);
        for (int i = 0; i < no && !dup; i++) dup = !strcasecmp(other[i].name, name);
        if (dup) continue;
        struct place p = { id, name };
        if (starts_route(name)) routes[nr++] = p;
        else if (is_town(name)) towns[nt++] = p;
        else other[no++] = p;
    }
    qsort(routes, (size_t) nr, sizeof(struct place), cmp_place);
    qsort(other, (size_t) no, sizeof(struct place), cmp_place);
    struct { const char* title; struct place* list; int n; } groups[] = {
        { "TOWNS & CITIES", towns, nt }, { "ROUTES", routes, nr }, { "OTHER PLACES", other, no },
    };
    for (int gi = 0; gi < 3; gi++) {
        if (!groups[gi].n) continue;
        places[placeCount++] = (struct place) { -1, groups[gi].title };
        for (int i = 0; i < groups[gi].n && placeCount < MAX_PLACES; i++) places[placeCount++] = groups[gi].list[i];
    }
}

// --- drawing ---

static void frame(struct pd_canvas* c, int x, int y, int w, int h) {
    static const struct pd_layer LAYERS[] = { { 0x293131, 2 }, { 0x8C8CCE, 1 }, { 0xFFFFFF, 2 } };
    pd_layered_box(c, x, y, w, h, 3, LAYERS, 3, 0x000000);
}

// The page scaled to the view, each canvas pixel its nearest map pixel.
static void draw_page(struct pd_canvas* c, const uint32_t* px) {
    static int col[1024];
    for (int x = 0; x < view.w && x < 1024; x++) col[x] = crop.l + x * (crop.r - crop.l) / view.w;
    for (int y = 0; y < view.h; y++) {
        int cy = view.y + y;
        if (cy < c->cy0 || cy >= c->cy1) continue;
        const uint32_t* src = px + (crop.t + y * (crop.b - crop.t) / view.h) * mapW;
        uint32_t* dst = c->px + cy * c->w + view.x;
        for (int x = 0; x < view.w && x < 1024; x++) {
            int cx = view.x + x;
            if (cx >= c->cx0 && cx < c->cx1) dst[x] = src[col[x]];
        }
    }
}

static void draw_head(struct pd_canvas* c, const uint32_t* head, int tx, int ty) {
    // The head's 16 x 16 centred on its tile, scaled like the map.
    int x0 = sx(tx * 8 + 4 - 8), y0 = sy(ty * 8 + 4 - 8), x1 = sx(tx * 8 + 4 + 8), y1 = sy(ty * 8 + 4 + 8);
    for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
            uint32_t p = head[((y - y0) * 16 / (y1 - y0)) * 16 + (x - x0) * 16 / (x1 - x0)];
            if (p != PD_MAP_CLEAR) pd_fill(c, x, y, 1, 1, p);
        }
    }
}

// Four white L corners around a tile rect, out by 2 or 3 px (the blink); black on a Game Boy's light town map
// (MapLabelStyle.GEN1's cursorColor).
static void cursor_pixels(struct pd_canvas* c, int x, int y, int w, int h, bool big, uint32_t colour) {
    int out = big ? 3 : 2;
    int l = sx(x) - out, t = sy(y) - out;
    int rr = sx(x + w) + out, b = sy(y + h) + out;
    pd_clip(c, view.x, view.y, view.w, view.h);
    pd_fill(c, l, t, CURSOR_ARM, CURSOR_THICK, colour);
    pd_fill(c, l, t, CURSOR_THICK, CURSOR_ARM, colour);
    pd_fill(c, rr - CURSOR_ARM, t, CURSOR_ARM, CURSOR_THICK, colour);
    pd_fill(c, rr - CURSOR_THICK, t, CURSOR_THICK, CURSOR_ARM, colour);
    pd_fill(c, l, b - CURSOR_THICK, CURSOR_ARM, CURSOR_THICK, colour);
    pd_fill(c, l, b - CURSOR_ARM, CURSOR_THICK, CURSOR_ARM, colour);
    pd_fill(c, rr - CURSOR_ARM, b - CURSOR_THICK, CURSOR_ARM, CURSOR_THICK, colour);
    pd_fill(c, rr - CURSOR_THICK, b - CURSOR_ARM, CURSOR_THICK, CURSOR_ARM, colour);
    pd_unclip(c);
}

// Darkens a strip of the map (FireRed's label: black at 6/16).
static void darken(struct pd_canvas* c, int x, int y, int w, int h) {
    for (int yy = y < c->cy0 ? c->cy0 : y; yy < y + h && yy < c->cy1; yy++) {
        for (int xx = x < c->cx0 ? c->cx0 : x; xx < x + w && xx < c->cx1; xx++) {
            uint32_t p = c->px[yy * c->w + xx];
            uint32_t r = (p >> 16 & 0xFF) * 10 / 16, g = (p >> 8 & 0xFF) * 10 / 16, b = (p & 0xFF) * 10 / 16;
            c->px[yy * c->w + xx] = r << 16 | g << 8 | b;
        }
    }
}

// Returns how far down the map the label reaches where the buttons must stay clear of it (0: they don't).
static int label(struct pd_canvas* c, const struct pd_game* g, const char* name, const char* dungeon) {
    if (g->kind == PD_GAME_GB) {
        // Gen 1 / Gen 2 (MapLabelStyle.GEN1): the name in black on a white strip over the map's top row, 128 map
        // pixels wide, as tall as a line of the game's font needs; hidden when blank. The strip reaches under the
        // buttons on a small canvas, so they sit below it (blank or not: they don't jump as the label comes and goes).
        int w = sx(128) - view.x, h = sy(8) - view.y;
        if (h < 14) h = 14;
        if (!name || !*name) return h;
        pd_fill(c, view.x, view.y, w, h, PD_GB_PAPER);
        pd_text_fit(c, view.x + 3, view.y + (h - 14) / 2, w - 5, name, PD_GB_INK, PD_NO_SHADOW);
        return h;
    }
    if (g->kind == PD_GAME_EMERALD) {
        // Emerald: a framed window bottom-right, always shown.
        int w = 130, h = 30;
        int x = view.x + view.w - 2 - w, y = view.y + view.h - 2 - h;
        int f = pd_list_box(c, x, y, w, h);
        pd_fill(c, x + f, y + f, w - 2 * f, h - 2 * f, 0xFFFFFF);
        pd_text_fit(c, x + 7, pd_text_y(y, h), w - 10, name, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        return 0;
    }
    // FireRed: a strip top-left per line, hidden when blank.
    const char* lines[2] = { name, dungeon };
    for (int i = 0; i < 2; i++) {
        if (!lines[i] || !*lines[i]) continue;
        int lw = view.w / 2;
        darken(c, view.x, view.y + i * 18, lw, 18);
        pd_text_fit(c, view.x + 5, view.y + i * 18 + 1, lw - 8, lines[i], 0xFFFFFF, FR_LABEL_SHADOW);
    }
    return 0;
}

// The map's buttons, a row over its top right (MapScreen's), right to left.
static int buttonRight, buttonTop;
static void map_button(struct pd_ui* ui, struct pd_canvas* c, const char* text, int id) {
    int w = pd_text_width(text) + 16;
    buttonRight -= w;
    ui_button(ui, c, buttonRight, buttonTop, w, 22, text, id);
    buttonRight -= 4;
}

// The map's view: a w x h page of which [l, r) x [t, b) is shown.
static void layout_box(struct pd_canvas* c, int width, int height, int l, int t, int r, int b) {
    mapW = width;
    mapH = height;
    crop.l = l;
    crop.t = t;
    crop.r = r;
    crop.b = b;
    int mw = crop.r - crop.l, mh = crop.b - crop.t;
    // As big as fits in its own shape, centred: never stretched (the user's call for the 3DS; the app stretches up
    // to MAX_MAP_STRETCH).
    int aw = c->w - 2 * MARGIN - 2 * FRAME, ah = c->h - CONTENT_Y - MARGIN - 2 * FRAME;
    int w = aw, h = (int) ((long) aw * mh / mw);
    if (h > ah) {
        h = ah;
        w = (int) ((long) ah * mw / mh);
    }
    view.w = w > 0 ? w : 1;
    view.h = h > 0 ? h : 1;
    view.x = MARGIN + FRAME + (aw - w) / 2;
    view.y = CONTENT_Y + FRAME + (ah - h) / 2;
}

// A page of the game's map, without the border its crop leaves out.
static void layout(struct pd_canvas* c, const struct pd_map_art* art, int page) {
    const int16_t* k = art->crop[page];
    if (k[2] > k[0] && k[3] > k[1]) layout_box(c, art->w, art->h, k[0], k[1], k[2], k[3]);
    else layout_box(c, art->w, art->h, 0, 0, art->w, art->h);
}

void ui_map_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                const struct pd_host_info* host) {
    lastGame = g;
    const struct pd_map_art* art = pd_map_art(g);
    if (!art) {
        ui_notice(c, "NO MAP", "The region map's art wasn't found", "in this ROM.");
        return;
    }
    int playerPage = -1, ptx = 0, pty = 0;
    bool hasPlayer = pd_map_player_tile(g, s, &playerPage, &ptx, &pty);
    lastPlayerPage = hasPlayer ? playerPage : -1;
    lastPlayerTx = ptx;
    lastPlayerTy = pty;
    int page = ui->mapPage >= 0 ? ui->mapPage : hasPlayer ? playerPage : 0;
    if (page >= art->pages) page = 0;
    layout(c, art, page);
    frame(c, view.x - FRAME, view.y - FRAME, view.w + 2 * FRAME, view.h + 2 * FRAME);
    draw_page(c, art->page[page]);

    // What the cursor and label show: the tapped place, else where the player is.
    struct pd_tile_rect rects[MAX_RECTS];
    int n = 0;
    const char* name = "";
    const char* dungeon = NULL;
    if (ui->mapSel >= 0 || ui->mapDungeon >= 0 || ui->mapPage >= 0) {
        int main = ui->mapSel >= 0 ? ui->mapSel : ui->mapDungeon;
        name = pd_mapsec_name(g, main);
        if (ui->mapDungeon >= 0) {
            rects[n++] = (struct pd_tile_rect) { ui->mapSelTx, ui->mapSelTy, 1, 1 };
            if (ui->mapSel >= 0) dungeon = pd_mapsec_name(g, ui->mapDungeon);
        } else {
            int p;
            n = pd_map_tiles(g, main, &p, rects, MAX_RECTS);
        }
    } else if (hasPlayer && page == playerPage) {
        int p;
        n = pd_map_tiles(g, pd_map_player_section(g, s), &p, rects, MAX_RECTS);
        name = pd_mapsec_name(g, s->mapsec);
    } else if (g->kind == PD_GAME_GB && !hasPlayer && s->valid && s->posOk) {
        name = pd_mapsec_name(g, s->mapsec);  // off the map (FAST SHIP): its name, no cursor
    }
    int gender = s->gender == 1 ? 1 : 0;
    pd_clip(c, view.x, view.y, view.w, view.h);
    if (hasPlayer && page == playerPage && art->hasHead[gender]) draw_head(c, art->head[gender], ptx, pty);
    pd_unclip(c);
    for (int i = 0; i < n; i++) cursor_pixels(c, rects[i].x * 8, rects[i].y * 8, rects[i].w * 8, rects[i].h * 8, ui->mapBlink, g->kind == PD_GAME_GB ? PD_GB_INK : CURSOR);
    int below = label(c, g, name, dungeon);
    ui_add_hit(ui, view.x, view.y, view.w, view.h, HIT_MAP);

    // Over the map's top right: PLACES, ME, the other page(s) (MapScreen's order, left to right).
    buttonRight = view.x + view.w - 4;
    buttonTop = view.y + (below ? below + 2 : 4);
    if (ui->shoulderTabs) {
        if (art->pages > 1) map_button(ui, c, pd_map_page_name(g, (page + 1) % art->pages), HIT_MAP_REGION);
        if (host && host->flyReady) map_button(ui, c, "FLY", HIT_MAP_FLY);
        map_button(ui, c, "ME", HIT_MAP_ME);
        map_button(ui, c, "PLACES", HIT_MAP_PLACES);
    } else {
        map_button(ui, c, "PLACES", HIT_MAP_PLACES);
        if (host && host->flyReady) map_button(ui, c, "FLY", HIT_MAP_FLY);
        if (ui->mapSel >= 0 || ui->mapDungeon >= 0 || (ui->mapPage >= 0 && ui->mapPage != playerPage))
            map_button(ui, c, "ME", HIT_MAP_ME);
        if (art->pages > 1) map_button(ui, c, pd_map_page_name(g, (page + 1) % art->pages), HIT_MAP_REGION);
    }
    // FREE FLIGHT's TAKE OFF (LAND while up) under the row, at its right end (MapScreen's TakeOffButton, under FLY).
    if (host && host->flightReady) {
        buttonRight = view.x + view.w - 4;
        buttonTop += 26;
        map_button(ui, c, host->flying ? "LAND" : "TAKE OFF", HIT_MAP_TAKE_OFF);
    }
}

// MAP's FLY: the towns the game would fly to.
void ui_fly_overlay(struct pd_ui* ui, struct pd_canvas* c) {
    pd_dim(c, 0xE6);
    ui_add_hit(ui, 0, 0, c->w, c->h, HIT_SCRIM);
    int x = MARGIN, w = c->w - 2 * MARGIN, y = CONTENT_Y;
    int f = pd_title_box(c, x, y, w, 24, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(y, 24), "FLY", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    y += 28;
    int lf = pd_list_box(c, x, y, w, c->h - y - MARGIN);
    int ix = x + lf, iy = y + lf + 1, iw = w - 2 * lf, ih = c->h - y - MARGIN - 2 * lf - 2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_SCRIM);
    ui->placesScroll = ui_clamp_scroll(ui->placesScroll, ui->flyCount * PLACE_ROW, ih);
    pd_clip(c, ix, iy, iw, ih);
    for (int i = ui->placesScroll / PLACE_ROW; i < ui->flyCount; i++) {
        int ry = iy + i * PLACE_ROW - ui->placesScroll;
        if (ry >= iy + ih) break;
        if (ui->pressedId == HIT_FLY_TOWN + i) pd_fill(c, ix, ry, iw, PLACE_ROW, PD_ROW_SELECTED);
        pd_text_fit(c, ix + 14, pd_text_y(ry, PLACE_ROW), iw - 28, ui->flyNames[i], PD_LABEL, PD_LABEL_SHADOW);
        int top = ry < iy ? iy : ry;
        int bottom = ry + PLACE_ROW > iy + ih ? iy + ih : ry + PLACE_ROW;
        ui_add_scroll_hit(ui, ix, top, iw, bottom - top, HIT_FLY_TOWN + i);
    }
    pd_unclip(c);
    ui_list_rail(ui, c, ix + iw, iy, ih, ui->placesScroll, ui->flyCount * PLACE_ROW);
}

void ui_places_overlay(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g) {
    build_places(g);
    pd_dim(c, 0xE6);
    ui_add_hit(ui, 0, 0, c->w, c->h, HIT_SCRIM);
    int x = MARGIN, w = c->w - 2 * MARGIN, y = CONTENT_Y;
    int f = pd_title_box(c, x, y, w, 24, PD_TITLE_FILL);
    pd_text(c, x + f + 6, pd_text_y(y, 24), "PLACES", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    y += 28;
    int lf = pd_list_box(c, x, y, w, c->h - y - MARGIN);
    int ix = x + lf, iy = y + lf + 1, iw = w - 2 * lf, ih = c->h - y - MARGIN - 2 * lf - 2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_SCRIM);
    // The row a host's D-pad cursor moves onto comes wholly into view (a row cut at the window's edge).
    static int seenFocus = -1;
    if (ui->focusId != seenFocus && ui->focusId >= HIT_PLACE && ui->focusId < HIT_PLACE + placeCount)
        ui->placesScroll = ui_scroll_to(ui->placesScroll, (ui->focusId - HIT_PLACE) * PLACE_ROW, PLACE_ROW, ih);
    seenFocus = ui->focusId;
    ui->placesScroll = ui_clamp_scroll(ui->placesScroll, placeCount * PLACE_ROW, ih);
    pd_clip(c, ix, iy, iw, ih);
    for (int i = ui->placesScroll / PLACE_ROW; i < placeCount; i++) {
        int ry = iy + i * PLACE_ROW - ui->placesScroll;
        if (ry >= iy + ih) break;
        int ty = pd_text_y(ry, PLACE_ROW);
        if (places[i].mapsec < 0) {
            pd_text(c, ix + 6, ty, places[i].name, PD_VALUE, PD_VALUE_SHADOW);
            continue;
        }
        if (ui->pressedId == HIT_PLACE + i) pd_fill(c, ix, ry, iw, PLACE_ROW, PD_ROW_SELECTED);
        pd_text_fit(c, ix + 14, ty, iw - 100, places[i].name, PD_LABEL, PD_LABEL_SHADOW);
        int page;
        struct pd_tile_rect r;
        if (pd_map_tiles(g, places[i].mapsec, &page, &r, 1) && page > 0) {
            pd_text_right(c, ix + iw - 10, ty, pd_map_page_name(g, page), PD_MUTED, PD_MUTED_SHADOW);
        }
        int top = ry < iy ? iy : ry;
        int bottom = ry + PLACE_ROW > iy + ih ? iy + ih : ry + PLACE_ROW;
        ui_add_scroll_hit(ui, ix, top, iw, bottom - top, HIT_PLACE + i);
    }
    pd_unclip(c);
    ui_list_rail(ui, c, ix + iw, iy, ih, ui->placesScroll, placeCount * PLACE_ROW);
}

bool ui_map_act(struct pd_ui* ui, int id) {
    if (ui->overlay == PD_OVERLAY_FLY) {
        if (id >= HIT_FLY_TOWN && id < HIT_FLY_TOWN + ui->flyCount) {
            ui->flyPick = id - HIT_FLY_TOWN;
            ui->overlay = PD_OVERLAY_NONE;
            ui->action = PD_ACTION_FLY_TO;
            return true;
        }
        if (id == HIT_SCRIM) {
            ui->overlay = PD_OVERLAY_NONE;
            return true;
        }
        return false;
    }
    if (ui->overlay == PD_OVERLAY_PLACES) {
        if (id >= HIT_PLACE && id < HIT_PLACE + placeCount && places[id - HIT_PLACE].mapsec >= 0 && lastGame) {
            int mapsec = places[id - HIT_PLACE].mapsec, page;
            struct pd_tile_rect r;
            if (pd_map_tiles(lastGame, mapsec, &page, &r, 1)) {
                ui->mapPage = page;
                ui->mapSel = mapsec;
                ui->mapDungeon = -1;
            }
            ui->overlay = PD_OVERLAY_NONE;
            return true;
        }
        if (id == HIT_SCRIM) {
            ui->overlay = PD_OVERLAY_NONE;
            return true;
        }
        return false;
    }
    if (ui->tab != PD_TAB_MAP || ui->overlay != PD_OVERLAY_NONE || !lastGame) return false;
    const struct pd_map_art* art = pd_map_art(lastGame);
    int pages = art ? art->pages : 1;
    int shown = ui->mapPage >= 0 ? ui->mapPage : lastPlayerPage >= 0 ? lastPlayerPage : 0;
    switch (id) {
    case HIT_MAP: {
        int tx = (crop.l + (ui->touchX0 - view.x) * (crop.r - crop.l) / view.w) / 8;
        int ty = (crop.t + (ui->touchY0 - view.y) * (crop.b - crop.t) / view.h) / 8;
        // The player's own tile: back to following the player.
        if (shown == lastPlayerPage && tx == lastPlayerTx && ty == lastPlayerTy) {
            ui->mapSel = ui->mapDungeon = -1;
            ui->mapPage = -1;
            return true;
        }
        int mapsec, dungeon;
        pd_map_pick(lastGame, shown, tx, ty, &mapsec, &dungeon);
        ui->mapPage = shown;
        ui->mapSel = mapsec;
        ui->mapDungeon = dungeon;
        ui->mapSelTx = tx;
        ui->mapSelTy = ty;
        return true;
    }
    case HIT_MAP_REGION:
        ui->mapPage = (shown + 1) % pages;
        ui->mapSel = ui->mapDungeon = -1;
        return true;
    case HIT_MAP_ME:
        ui->mapPage = ui->mapSel = ui->mapDungeon = -1;
        return true;
    case HIT_MAP_PLACES:
        ui->overlay = PD_OVERLAY_PLACES;
        ui->placesScroll = 0;
        return true;
    case HIT_MAP_FLY:
        ui->action = PD_ACTION_FLY_ASK;
        return true;
    case HIT_MAP_TAKE_OFF:
        ui->action = PD_ACTION_FLIGHT;
        return true;
    default:
        return false;
    }
}

// The same map frame, bounded stretch, nearest-neighbour scaling, label and cursor
// for another backend's already-composed art. The normal GBA path still builds its
// own page, head and selection above.
enum pd_map_input pd_ui_map_control(int* action, int count, int dx, int dy, bool accept, bool back, bool places) {
    if (*action >= count) *action = -1;
    if (back) {
        if (*action >= 0 || count == 0) return PD_MAP_CLOSE;
        *action = 0;
        return PD_MAP_IDLE;
    }
    if (*action >= 0) {
        *action = pd_ui_list_step(*action, count, dx);
        if (accept) return PD_MAP_CHOOSE;
        if (dy < 0) *action = -1;
        return PD_MAP_IDLE;
    }
    if (places && count) return PD_MAP_OPEN_PLACES;
    if (accept) return PD_MAP_CENTER;
    return dx || dy ? PD_MAP_MOVE : PD_MAP_IDLE;
}

struct pd_map_viewport pd_ui_map_view(struct pd_canvas* c, const struct pd_map_source* source) {
    if (!source || !source->pixels || source->width < 1 || source->height < 1 ||
        source->width > 4096 || source->height > 4096 || c->w < 32 || c->h < 32 || c->w > 1024) {
        ui_notice(c, "NO MAP", "The region map's art wasn't found", "in this ROM.");
        return (struct pd_map_viewport){0};
    }
    layout_box(c, source->width, source->height, 0, 0, source->width, source->height);
    frame(c, view.x - FRAME, view.y - FRAME, view.w + 2 * FRAME, view.h + 2 * FRAME);
    draw_page(c, source->pixels);
    if (source->cursorX >= 0 && source->cursorY >= 0 && source->cursorW > 0 && source->cursorH > 0 &&
        source->cursorX <= mapW - source->cursorW && source->cursorY <= mapH - source->cursorH)
        cursor_pixels(c, source->cursorX, source->cursorY, source->cursorW, source->cursorH, false, CURSOR);
    const struct pd_game game = {0};
    label(c, &game, source->name, NULL);
    return (struct pd_map_viewport){view.x, view.y, view.w, view.h};
}
