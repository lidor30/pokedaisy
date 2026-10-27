// MAP: the game's region map rebuilt from the ROM (pd_map.c), filling the tab like the app's, the
// player's head on the tile the game puts it, FireRed's corner cursor, the
// place name in the game's own label (the app's MapScreen.kt), a tap naming
// what's under it, Kanto / Sevii pages, ME and the PLACES list.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "pd_map.h"
#include "pd_ui_internal.h"

#define FRAME 5
#define MAX_RECTS 8
// The most MapScreen stretches the map to fill the tab (MAX_MAP_STRETCH); past it the map keeps its shape.
#define MAX_STRETCH_NUM 6
#define MAX_STRETCH_DEN 5
#define PLACE_ROW 18
#define CURSOR 0xFFFFFF
#define CURSOR_ARM 5
#define CURSOR_THICK 2
// FireRed's label: a darkening strip, white text over grey.
#define FR_LABEL_SHADOW 0x636363

static const struct pd_game* lastGame;

// Where the map is drawn this time: (x, y) w x h canvas pixels for its 240 x 160.
static struct { int x, y, w, h; } view = { MARGIN + FRAME, MARGIN + FRAME, PD_MAP_W, PD_MAP_H };
static int sx(int mx) { return view.x + mx * view.w / PD_MAP_W; }
static int sy(int my) { return view.y + my * view.h / PD_MAP_H; }
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
    for (int x = 0; x < view.w && x < 1024; x++) col[x] = x * PD_MAP_W / view.w;
    for (int y = 0; y < view.h; y++) {
        int cy = view.y + y;
        if (cy < c->cy0 || cy >= c->cy1) continue;
        const uint32_t* src = px + (y * PD_MAP_H / view.h) * PD_MAP_W;
        uint32_t* dst = c->px + cy * c->w + view.x;
        for (int x = 0; x < view.w && x < 1024; x++) dst[x] = src[col[x]];
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

// Four white L corners around a tile rect, out by 2 or 3 px (the blink).
static void cursor(struct pd_canvas* c, const struct pd_tile_rect* r, bool big) {
    int out = big ? 3 : 2;
    int l = sx(r->x * 8) - out, t = sy(r->y * 8) - out;
    int rr = sx((r->x + r->w) * 8) + out, b = sy((r->y + r->h) * 8) + out;
    pd_clip(c, view.x, view.y, view.w, view.h);
    pd_fill(c, l, t, CURSOR_ARM, CURSOR_THICK, CURSOR);
    pd_fill(c, l, t, CURSOR_THICK, CURSOR_ARM, CURSOR);
    pd_fill(c, rr - CURSOR_ARM, t, CURSOR_ARM, CURSOR_THICK, CURSOR);
    pd_fill(c, rr - CURSOR_THICK, t, CURSOR_THICK, CURSOR_ARM, CURSOR);
    pd_fill(c, l, b - CURSOR_THICK, CURSOR_ARM, CURSOR_THICK, CURSOR);
    pd_fill(c, l, b - CURSOR_ARM, CURSOR_THICK, CURSOR_ARM, CURSOR);
    pd_fill(c, rr - CURSOR_ARM, b - CURSOR_THICK, CURSOR_ARM, CURSOR_THICK, CURSOR);
    pd_fill(c, rr - CURSOR_THICK, b - CURSOR_ARM, CURSOR_THICK, CURSOR_ARM, CURSOR);
    pd_unclip(c);
}

// Darkens a strip of the map (FireRed's label: black at 6/16).
static void darken(struct pd_canvas* c, int x, int y, int w, int h) {
    for (int yy = y; yy < y + h; yy++) {
        for (int xx = x; xx < x + w; xx++) {
            uint32_t p = c->px[yy * c->w + xx];
            uint32_t r = (p >> 16 & 0xFF) * 10 / 16, g = (p >> 8 & 0xFF) * 10 / 16, b = (p & 0xFF) * 10 / 16;
            c->px[yy * c->w + xx] = r << 16 | g << 8 | b;
        }
    }
}

static void label(struct pd_canvas* c, const struct pd_game* g, const char* name, const char* dungeon) {
    if (g->kind == PD_GAME_EMERALD) {
        // Emerald: a framed window bottom-right, always shown.
        int w = 130, h = 30;
        int x = view.x + view.w - 2 - w, y = view.y + view.h - 2 - h;
        int f = pd_list_box(c, x, y, w, h);
        pd_fill(c, x + f, y + f, w - 2 * f, h - 2 * f, 0xFFFFFF);
        pd_text_fit(c, x + 7, pd_text_y(y, h), w - 10, name, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        return;
    }
    // FireRed: a strip top-left per line, hidden when blank.
    const char* lines[2] = { name, dungeon };
    for (int i = 0; i < 2; i++) {
        if (!lines[i] || !*lines[i]) continue;
        int lw = view.w / 2;
        darken(c, view.x, view.y + i * 18, lw, 18);
        pd_text_fit(c, view.x + 5, view.y + i * 18 + 1, lw - 8, lines[i], 0xFFFFFF, FR_LABEL_SHADOW);
    }
}

// The map's buttons, a row over its top right (MapScreen's), right to left.
static int buttonRight;
static void map_button(struct pd_ui* ui, struct pd_canvas* c, const char* text, int id) {
    int w = pd_text_width(text) + 16;
    buttonRight -= w;
    ui_button(ui, c, buttonRight, view.y + 4, w, 22, text, id);
    buttonRight -= 4;
}

void ui_map_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
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

    // The map fills the tab: stretched to it while that's within 1.2x of its shape, else as big as fits,
    // centred (MapScreen's MAX_MAP_STRETCH).
    int aw = c->w - 2 * MARGIN - 2 * FRAME, ah = c->h - CONTENT_Y - MARGIN - 2 * FRAME;
    int w = aw, h = ah;
    if ((long) aw * PD_MAP_H * MAX_STRETCH_DEN > (long) ah * PD_MAP_W * MAX_STRETCH_NUM) w = ah * PD_MAP_W / PD_MAP_H;
    else if ((long) ah * PD_MAP_W * MAX_STRETCH_DEN > (long) aw * PD_MAP_H * MAX_STRETCH_NUM) h = aw * PD_MAP_H / PD_MAP_W;
    view.w = w;
    view.h = h;
    view.x = MARGIN + FRAME + (aw - w) / 2;
    view.y = CONTENT_Y + FRAME + (ah - h) / 2;
    frame(c, view.x - FRAME, view.y - FRAME, w + 2 * FRAME, h + 2 * FRAME);
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
        n = pd_map_tiles(g, s->mapsec, &p, rects, MAX_RECTS);
        name = pd_mapsec_name(g, s->mapsec);
    }
    int gender = s->gender == 1 ? 1 : 0;
    if (hasPlayer && page == playerPage && art->hasHead[gender]) draw_head(c, art->head[gender], ptx, pty);
    for (int i = 0; i < n; i++) cursor(c, &rects[i], ui->mapBlink);
    label(c, g, name, dungeon);
    ui_add_hit(ui, view.x, view.y, view.w, view.h, HIT_MAP);

    // Over the map's top right: PLACES, ME, the other page(s) (MapScreen's order, left to right).
    buttonRight = view.x + view.w - 4;
    map_button(ui, c, "PLACES", HIT_MAP_PLACES);
    if (ui->mapSel >= 0 || ui->mapDungeon >= 0 || ui->mapPage >= 0) {
        map_button(ui, c, "ME", HIT_MAP_ME);
    }
    if (art->pages > 1) map_button(ui, c, pd_map_page_name(g, (page + 1) % art->pages), HIT_MAP_REGION);
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
    ui_scroll_bar(c, ix + iw - 4, iy, ih, ui->placesScroll, placeCount * PLACE_ROW, PD_MUTED);
}

bool ui_map_act(struct pd_ui* ui, int id) {
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
        int tx = (ui->touchX0 - view.x) * PD_MAP_W / view.w / 8, ty = (ui->touchY0 - view.y) * PD_MAP_H / view.h / 8;
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
    default:
        return false;
    }
}
