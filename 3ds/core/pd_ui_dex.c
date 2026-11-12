// POKéDEX: the list (national or the game's regional dex) with the save's
// seen / caught marks, and an entry page read from the ROM - the app's
// PokedexScreen, fitted to 320x212.
#include <stdio.h>
#include <string.h>

#include "pd_dex.h"
#include "pd_dex_details.h"
#include "pd_gb.h"
#include "pd_icon.h"
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
    bool one = !ui->dexSource && pd_dex_gb_count(g) > 0; // a Game Boy dex has the one list
    int x = MARGIN, w = c->w - 2 * MARGIN - (one ? 0 : TOGGLE_W + 4);
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
    if (one) return;
    char other[24];
    snprintf(other, sizeof(other), "%s", ui->dexSource ? ui->dexSource->filterLabel : national ? g->cfg->regionName : "NATIONAL");
    ui_button(ui, c, x + w + 4, CONTENT_Y, TOGGLE_W, TITLE_H, other, HIT_DEX_TOGGLE);
}

// The list shown (rebuilt each draw), kept for the tap handler, which has no
// game at hand: the toggle flips what shows, up / down step through it.
static int lastList[PD_DEX_BYTES * 8], lastCount;
static bool lastNational;

enum dex_filter { DEX_ALL, DEX_CAUGHT, DEX_SEEN, DEX_UNSEEN, DEX_FILTERS };

static int filter_of(const struct pd_snapshot* s,int national){
    if(pd_dex_caught(s,national))return DEX_CAUGHT;
    return pd_dex_seen(s,national)?DEX_SEEN:DEX_UNSEEN;
}

int pd_ui_dex_filters(struct pd_ui* ui, struct pd_canvas* c, const int counts[4],
                      int active, int focus, const char* region) {
    static const char* const names[] = {"ALL", "CAUGHT", "SEEN ONLY", "NOT SEEN"};
    const int w = (c->w - 3 * MARGIN) * 36 / 100;
    const int x = MARGIN, y = CONTENT_Y;
    const bool tall = c->h >= 252;
    const int headingH = 46, rowH = tall ? 23 : 18;
    int f = pd_title_box(c, x, y, w, TITLE_H, PD_TITLE_FILL);
    pd_text(c, x + f + 5, pd_text_y(y, TITLE_H), "POKéDEX", PD_TITLE_TEXT, PD_TITLE_SHADOW);
    const int countsY = y + TITLE_H + 3;
    f = pd_list_box(c, x, countsY, w, headingH);
    const int innerX = x + f, innerW = w - 2 * f;
    const int right = innerX + innerW - 4;
    char text[24];
    for (int i = 0; i < 2; i++) {
        const int line = countsY + f + 1 + i * 16;
        pd_text(c, innerX + 3, line, i ? "OWNED" : "SEEN", PD_LABEL, PD_LABEL_SHADOW);
        snprintf(text, sizeof text, "%d", i ? counts[DEX_CAUGHT] : counts[DEX_CAUGHT] + counts[DEX_SEEN]);
        pd_text_right(c, right, line, text, PD_VALUE, PD_VALUE_SHADOW);
    }
    const int filterY = countsY + headingH + 4;
    pd_list_box(c, x, filterY, w, c->h - filterY - MARGIN);
    for (int i = 0; i < DEX_FILTERS; i++) {
        const int line = filterY + f + 1 + i * rowH, ty = pd_text_y(line, rowH);
        if (i == (focus < 0 ? active : focus)) pd_fill(c, innerX, line, innerW, rowH, PD_ROW_SELECTED);
        const uint32_t ink = i == active ? PD_VALUE : PD_LABEL;
        const uint32_t shadow = i == active ? PD_VALUE_SHADOW : PD_LABEL_SHADOW;
        snprintf(text, sizeof text, "%d", counts[i]);
        pd_text_fit(c, innerX + 3, ty, innerW - pd_text_width(text) - 9, names[i], ink, shadow);
        pd_text_right(c, right, ty, text, ink, shadow);
        if (ui) ui_add_hit(ui, innerX, line, innerW, rowH, HIT_DEX_FILTER + i);
    }
    if (tall) pd_text_fit(c, innerX + 3, c->h - 47, innerW - 6, "SELECT  FILTER", PD_MUTED, PD_MUTED_SHADOW);
    if (region) {
        const int by = c->h - MARGIN - f - 25;
        if (ui) ui_button(ui, c, innerX + 1, by, innerW - 2, 22, region, HIT_DEX_TOGGLE);
        else {
            pd_title_box(c, innerX + 1, by, innerW - 2, 22, PD_TITLE_FILL);
            pd_text_fit(c, innerX + 7, pd_text_y(by, 22), innerW - 14, region, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        }
        if (focus == DEX_FILTERS) pd_fill(c, innerX + 5, by + 17, innerW - 10, 2, PD_VALUE);
    } else {
        pd_text_fit(c, innerX + 3, c->h - 27, innerW - 6, "NATIONAL DEX", PD_MUTED, PD_MUTED_SHADOW);
    }
    return x + w + MARGIN;
}

void pd_ui_dex_list_row(struct pd_canvas* c, int x, int y, int w, int number, const char* name,
                        bool seen, uint32_t mark, bool selected, const uint32_t* icon, uint32_t clear) {
    if (selected) pd_fill(c, x, y, w, DEX_ROW, PD_ROW_SELECTED);
    char no[16];
    snprintf(no, sizeof no, "No.%03d", number);
    pd_text(c, x + 4, y, no, PD_MUTED, PD_MUTED_SHADOW);
    pd_text_fit(c, x + 78, y, w - 92, seen ? name : "???", PD_LABEL, PD_LABEL_SHADOW);
    if (!seen) return;
    pd_fill(c, x + w - 10, y + 7, 5, 5, mark);
    if (icon)
        for (int iy = 0; iy < 16; iy++)
            for (int ix = 0; ix < 16; ix++) {
                uint32_t pixel = icon[iy * 64 + ix * 2];
                if (pixel != clear) pd_fill(c, x + 58 + ix, y + 1 + iy, 1, 1, pixel & 0xffffff);
            }
}

static void list_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s,
                      const int counts[DEX_FILTERS]) {
    const int* list = lastList;
    int n = ui->dexSource?ui->dexSource->count:lastCount;
    bool national = ui->dexSource?true:lastNational;
    const bool sidebar=ui->handheldDex && !ui->dexSource;
    int x=MARGIN, y=LIST_Y;
    if (sidebar) {
        const char* region = !g->cfg->gb && g->cfg->regionalCount > 0
            ? (national ? "NATIONAL" : g->cfg->regionName) : NULL;
        x = pd_ui_dex_filters(ui, c, counts, ui->dexFilter, -1, region);
        y = CONTENT_Y;
    }
    else title(ui,c,g,s,list,n,national);
    int w=c->w-x-MARGIN;
    int f=pd_list_box(c,x,y,w,c->h-y-MARGIN);
    int ix=x+f, iy=y+f+1, iw=w-2*f, ih=c->h-y-MARGIN-2*f-2;
    ui_add_scroll_hit(ui, ix, iy, iw, ih, HIT_DEX_LIST);
    // The row a host's D-pad cursor moves onto comes wholly into view (a row cut at the window's edge).
    static int seenFocus = -1;
    for (int i = 0; !ui->dexSource && ui->focusId != seenFocus && i < n; i++)
        if (ui->focusId == HIT_DEX_ROW + list[i] || ui->focusId == HIT_DEX_READ_ONLY + list[i])
            ui->dexScroll = ui_scroll_to(ui->dexScroll, i * DEX_ROW, DEX_ROW, ih);
    int restore = -1;
    if (sidebar && ui->dexReturn) {
        for (int i = 0; i < n; i++) {
            if (ui->dexReturn < 0 || ui->dexReturn == list[i]) {
                restore = i;
                ui->dexScroll = ui_scroll_to(ui->dexScroll, i * DEX_ROW, DEX_ROW, ih);
                break;
            }
        }
        ui->dexReturn = 0;
        ui->startId = HIT_DEX_FILTER + ui->dexFilter;
    }
    seenFocus = ui->focusId;
    ui->dexScroll = ui_clamp_scroll(ui->dexScroll, n * DEX_ROW, ih);
    if((ui->dexSource || sidebar) && n==0)pd_text(c,ix+12,iy+4,"No matching entries.",PD_MUTED,PD_MUTED_SHADOW);
    pd_clip(c, ix, iy, iw, ih);
    char buf[40], num[16];
    for (int i = ui->dexScroll / DEX_ROW; i < n; i++) {
        int ry = iy + i * DEX_ROW - ui->dexScroll;
        if (ry >= iy + ih) break;
        int nat = ui->dexSource?ui->dexSource->national(ui->dexSource->ctx,i):list[i];
        bool seen = ui->dexSource?ui->dexSource->seen(ui->dexSource->ctx,nat):pd_dex_seen(s,nat);
        bool caught = ui->dexSource?ui->dexSource->caught(ui->dexSource->ctx,nat):pd_dex_caught(s,nat);
        if (sidebar && caught) seen = true;
        const int number = shown_number(g, national, nat);
        if (sidebar) {
            const int species = pd_dex_species(g, nat);
            const uint32_t* icon = seen ? pd_mon_icon_pixels(g, species, false) : NULL;
            const bool selected = ui->focusId == HIT_DEX_ROW + nat || ui->focusId == HIT_DEX_READ_ONLY + nat;
            pd_ui_dex_list_row(c, ix, ry, iw - 4, number, pd_species_name(g, species, buf, sizeof buf),
                               seen, caught ? PD_VALUE : PD_MUTED, selected, icon, 0xff000000u);
        } else {
            if (ui->pressedId == HIT_DEX_ROW + nat) pd_fill(c, ix, ry, iw, DEX_ROW, PD_ROW_SELECTED);
            if (seen) ui_ball(c, ix + 6, ry + 4, caught);
            snprintf(num, sizeof num, "No.%03d", number);
            const int ty = pd_text_y(ry, DEX_ROW);
            pd_text(c, ix + 22, ty, num, PD_MUTED, PD_MUTED_SHADOW);
            const char* name = ui->dexSource ? (seen ? ui->dexSource->name(ui->dexSource->ctx, nat) : "???")
                                             : pd_species_name(g, pd_dex_species(g, nat), buf, sizeof buf);
            pd_text(c, ix + 74, ty, name, seen ? PD_LABEL : PD_MUTED, seen ? PD_LABEL_SHADOW : PD_MUTED_SHADOW);
        }
        int top = ry < iy ? iy : ry;
        int bottom = ry + DEX_ROW > iy + ih ? iy + ih : ry + DEX_ROW;
        const bool canOpen = !ui->dexSource && (!g->cfg->gb || pd_gb_dex_pages(g) || sidebar) && (!sidebar || seen);
        if (canOpen || sidebar) {
            const int id = (canOpen ? HIT_DEX_ROW : HIT_DEX_READ_ONLY) + nat;
            ui_add_scroll_hit(ui, ix, top, iw, bottom - top, id);
            if (i == restore) ui->startId = id;
        }
    }
    pd_unclip(c);
    ui_list_rail(ui, c, ix + iw, iy, ih, ui->dexScroll, n * DEX_ROW);
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
    // The abilities (none in Gen 1 / 2).
    if (e->ability1[0] && !e->gen1Stats) {
        char ab[40];
        if (e->ability2[0]) snprintf(ab, sizeof(ab), "%s / %s", e->ability1, e->ability2);
        else snprintf(ab, sizeof(ab), "%s", e->ability1);
        dy += 15;
        lines--;
        int aw = pd_text(c, ix, dy, "ABILITY", PD_MUTED, PD_MUTED_SHADOW);
        pd_text_fit(c, ix + aw + 6, dy, iw - aw - 6, ab, PD_LABEL, PD_LABEL_SHADOW);
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
    // Room for the rail when the page ran past its window last time.
    const bool railed = ui->dexPageMax > 0;
    if (railed) w -= 10;
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
            snprintf(left, sizeof(left), "%s > %s", pd_species_name(g, evos[i].from, a, sizeof(a)), pd_species_name(g, evos[i].to, b, sizeof(b)));
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
            page_line(c, x, &y, top, bottom, w, pd_move_name(g, moves[i].move, left, sizeof(left)), right, false);
        }
        int t = pd_dex_tm_moves(g, e->species, tms, PD_TM_MOVES_MAX);
        if (t) page_line(c, x, &y, top, bottom, w, "TM / HM", NULL, true);
        for (int i = 0; i < t; i++) page_line(c, x, &y, top, bottom, w, pd_move_name(g, tms[i], left, sizeof(left)), NULL, false);
    }
    pd_unclip(c);
    ui->dexPageMax = y + ui->dexPageScroll - top - h;
    if (ui->dexPageMax < 0) ui->dexPageMax = 0;
    ui_list_rail(ui, c, x + w + (railed ? 10 : 0), top, h, ui->dexPageScroll, ui->dexPageMax + h);
}

int pd_ui_dex_machine_row(int levels) {
    return levels + 1;
}

void pd_ui_dex_moves(struct pd_canvas* c, int x, int y, int w, int h,
                     const struct pd_dex_moves_source* src, int selected) {
    const int machineRow = pd_ui_dex_machine_row(src->levels);
    const int count = machineRow + 1 + src->machines;
    const int visible = h / DEX_ROW;
    if (visible < 1) return;
    const int first = ui_clamp_scroll(selected - visible / 2, count, visible);
    struct pd_canvas clip = *c;
    c = &clip;
    pd_clip(c, x, y, w, h);
    for (int i = first; i < count && i < first + visible; i++) {
        const int ry = y + (i - first) * DEX_ROW;
        if (i == selected) pd_fill(c, x, ry, w, DEX_ROW, pd_dark_look ? PD_ROW_SELECTED : PD_LIST_FILL);
        if (i == 0 || i == machineRow) {
            const char* heading = i == 0 ? "LEVEL-UP MOVES" : src->machinesLabel ? src->machinesLabel : "TM / HM";
            const bool available = i == 0 ? src->hasLevels : src->machinesLabel != NULL;
            const int rows = i == 0 ? src->levels : src->machines;
            char label[44];
            snprintf(label, sizeof label, "%s%s", heading, !available ? "  UNAVAILABLE" : !rows ? "  NONE" : "");
            pd_text_fit(c, x + 3, ry, w - 6, label, PD_MUTED, PD_MUTED_SHADOW);
            continue;
        }
        const bool machine = i > machineRow;
        struct pd_dex_move_row row = {0};
        src->row(src->ctx, machine, machine ? i - machineRow - 1 : i - 1, &row);
        char tag[16] = "";
        if (machine && row.number) snprintf(tag, sizeof tag, "%s%02d", row.hm ? "HM" : "TM", row.number);
        else if (row.number) snprintf(tag, sizeof tag, "Lv%d", row.number);
        else if (!machine) snprintf(tag, sizeof tag, "EVO.");
        const int nameX = x + (*tag ? 47 : 3), nameW = x + w - 4 - nameX;
        if (*tag) pd_text(c, x + 3, ry, tag, PD_VALUE, PD_VALUE_SHADOW);
        pd_text_fit(c, nameX, ry, nameW, row.name, PD_LABEL, PD_LABEL_SHADOW);
        if (pd_text_width(row.name) + pd_text_width(row.type) + 7 <= nameW)
            pd_text_right(c, x + w - 4, ry, row.type, PD_MUTED, PD_MUTED_SHADOW);
    }
    ui_scroll_bar(c, x + w - 2, y, visible * DEX_ROW, first * DEX_ROW, count * DEX_ROW, PD_MUTED);
}

static void dex_button(struct pd_ui* ui, struct pd_canvas* c, int x, int y, int w, const char* text,
                       int id, bool active, bool enabled) {
    pd_title_box(c, x, y, w, 23, PD_TITLE_FILL);
    pd_text_fit(c, x + 4, y + 3, w - 8, text, enabled ? PD_TITLE_TEXT : PD_MUTED,
                enabled ? PD_TITLE_SHADOW : PD_MUTED_SHADOW);
    if (active) pd_fill(c, x + 4, y + 19, w - 8, 2, PD_VALUE);
    if (ui && enabled) ui_add_hit(ui, x, y, w, 23, id);
}

struct pd_dex_pane pd_ui_dex_card(struct pd_ui* ui, struct pd_canvas* c, const struct pd_dex_card* v) {
    const int w = c->w - 8, h = c->h - 32, side = w * 42 / 100, tx = side + 15, tw = c->w - tx - 12;
    const int ph = h < 210 ? 67 : 100;
    pd_title_box(c, 4, 4, w, h, PD_TITLE_FILL);
    char label[64];
    snprintf(label, sizeof label, "No.%03d  %s", v->number, v->caught ? "CAUGHT" : "SEEN");
    pd_text_fit(c, 12, 12, side - 16, label, PD_VALUE, PD_VALUE_SHADOW);
    pd_text_fit(c, 12, 31, side - 16, v->name, PD_LABEL, PD_LABEL_SHADOW);
    pd_list_box(c, 11, 46, side - 12, ph);
    if (v->pixels) {
        const int size = v->pictureSize * v->pictureScale;
        const int ox = 5 + (side - size) / 2, oy = 46 + (ph - size) / 2;
        struct pd_canvas clipped = *c;
        pd_clip(&clipped, 14, 49, side - 18, ph - 6);
        for (int y = 0; y < v->pictureSize; y++)
            for (int x = 0; x < v->pictureSize; x++) {
                uint32_t color = v->pixels[y * v->pictureSize + x];
                if (color != v->clear)
                    pd_fill(&clipped, ox + x * v->pictureScale, oy + y * v->pictureScale,
                            v->pictureScale, v->pictureScale, color & 0xffffff);
            }
    }
    pd_text_fit(c, 12, ph + 48, side - 16, v->types, PD_VALUE, PD_VALUE_SHADOW);
    if (v->measurements) pd_text_fit(c, 12, ph + 65, side - 16, v->measurements, PD_MUTED, PD_MUTED_SHADOW);
    if (v->category && ph + 98 <= h)
        pd_text_fit(c, 12, ph + 82, side - 16, v->category, PD_LABEL, PD_LABEL_SHADOW);
    if (v->page == PD_DEX_INFO && v->abilities && *v->abilities && ph + 130 <= h) {
        pd_text(c, 12, ph + 98, "ABILITY", PD_MUTED, PD_MUTED_SHADOW);
        pd_text_fit(c, 12, ph + 114, side - 16, v->abilities, PD_LABEL, PD_LABEL_SHADOW);
    }
    pd_fill(c, side + 4, 12, 1, h - 15, PD_DIVIDER);
    static const char* const tabs[] = {"INFO", "EVOLVE", "AREA", "MOVES"};
    int ideal = 0;
    for (int i = 0; i < PD_DEX_SECTIONS; i++) ideal += pd_text_width(tabs[i]) + 8;
    for (int i = 0, x = tx; i < PD_DEX_SECTIONS; i++) {
        int width = pd_text_width(tabs[i]) + 8;
        if (ideal > tw - 9) width = width * (tw - 9) / ideal;
        dex_button(ui, c, x, 12, width, tabs[i], HIT_DEX_PAGE + i, i == (int)v->page, v->pages & (1u << i));
        x += width + 3;
    }
    const char* help = v->page == PD_DEX_MOVES ? "SELECT  TAB   A  JUMP" :
                       v->pages != (1u << PD_DEX_INFO) ? "SELECT  SWITCH" : "";
    pd_text_fit(c, tx + 4, h - 15, tw - 8, help, PD_MUTED, PD_MUTED_SHADOW);
    const int by = c->h - 25, bw = c->w < 334 ? 72 : 86, ix = 15 + 2 * bw;
    dex_button(ui, c, 4, by, bw, "<  PREV", HIT_DEX_PREV, false, v->count > 1);
    dex_button(ui, c, 8 + bw, by, bw, ">  NEXT", HIT_DEX_NEXT, false, v->count > 1);
    dex_button(ui, c, c->w - bw - 4, by, bw, "B  BACK", HIT_BACK, false, true);
    if (v->count) {
        snprintf(label, sizeof label, "%d / %d", v->row + 1, v->count);
        pd_text_fit(c, ix, by + 3, c->w - bw - ix - 10, label, PD_ON_BACKDROP, PD_ON_BACKDROP_SHADOW);
    }
    if (ui) ui->startId = HIT_DEX_PAGE + v->page;
    return (struct pd_dex_pane){tx + 1, 39, tw - 2, h - 54};
}

void pd_ui_dex_info(struct pd_canvas* c, struct pd_dex_pane body, const char* text, const int* stats, bool gen1) {
    struct pd_canvas clipped = *c;
    c = &clipped;
    pd_clip(c, body.x, body.y, body.w, body.h);
    const int x = body.x + 3, w = body.w - 6, step = 13;
    int y = body.y + 1, lines = (body.h - (stats ? 63 : 4)) / step;
    if (lines < 1) lines = 1;
    if (lines > 6) lines = 6;
    if (text && *text) y += pd_text_wrap(c, x, y, w, step, lines, text, PD_LABEL, PD_LABEL_SHADOW) * step + 4;
    else if (!stats) pd_text_wrap(c, x, y, w, step, 3, "Species details unavailable for this cartridge.", PD_MUTED, PD_MUTED_SHADOW);
    if (!stats) return;
    pd_fill(c, x, y, w, 1, PD_DIVIDER);
    pd_text(c, x, y + 3, "BASE STATS", PD_MUTED, PD_MUTED_SHADOW);
    static const char* const labels[] = {"HP","ATK","DEF","SP. ATK","SP. DEF","SPEED"};
    static const char* const labels1[] = {"HP","ATK","DEF","SPEED","SPECIAL"};
    for (int i = 0; i < (gen1 ? 5 : 6); i++) {
        const int col = w / 2, cx = x + i / 3 * col, cy = y + 17 + i % 3 * 14;
        char value[12];
        snprintf(value, sizeof value, "%d", stats[i]);
        pd_text_fit(c, cx, cy, col - 26, gen1 ? labels1[i] : labels[i], PD_LABEL, PD_LABEL_SHADOW);
        pd_text_right(c, cx + col - 5, cy, value, PD_VALUE, PD_VALUE_SHADOW);
    }
}

void pd_ui_dex_links(struct pd_canvas* c, struct pd_dex_pane pane, const struct pd_dex_links* links, int selected) {
    if (!links->count) {
        pd_text_fit(c, pane.x + 3, pane.y + 18, pane.w - 6, "No evolution links found.", PD_LABEL, PD_LABEL_SHADOW);
        return;
    }
    const int height = 33, rows = pane.h / height;
    if (rows < 1) return;
    const int first = ui_clamp_scroll(selected - rows / 2, links->count, rows);
    for (int i = first; i < first + rows && i < links->count; i++) {
        const int y = pane.y + (i - first) * height;
        struct pd_dex_link_row row = {0};
        links->row(links->ctx, i, &row);
        if (i == selected) pd_fill(c, pane.x, y, pane.w, height - 1, pd_dark_look ? PD_ROW_SELECTED : PD_LIST_FILL);
        pd_text_fit(c, pane.x + 3, y + 1, pane.w - 6, row.name, PD_LABEL, PD_LABEL_SHADOW);
        pd_text_fit(c, pane.x + 3, y + 15, pane.w - 6, row.requirement, PD_VALUE, PD_VALUE_SHADOW);
    }
}

struct cartridge_links {
    const struct pd_game* game;
    int species;
    struct pd_dex_evolution links[24];
};

static void cartridge_link_row(const void* data, int index, struct pd_dex_link_row* row) {
    const struct cartridge_links* source = data;
    const struct pd_dex_evolution link = source->links[index];
    const bool incoming = link.to == source->species;
    char scratch[48];
    snprintf(row->name, sizeof row->name, "%s  %s", incoming ? "FROM" : "TO",
             pd_species_name(source->game, incoming ? link.from : link.to, scratch, sizeof scratch));
    static const char* const special[] = {
        [1]="Level up with friendship", [2]="Friendship by day", [3]="Friendship by night",
        [5]="Trade", [15]="Level up with beauty",
    };
    static const char* const levelNote[] = {
        [8]=" / ATK > DEF", [9]=" / ATK = DEF", [10]=" / ATK < DEF",
        [11]=" / personality", [12]=" / personality", [14]=" / spare party slot",
    };
    const int m = link.method;
    if (m == 6 || m == 7)
        snprintf(row->requirement, sizeof row->requirement, "%s%s", m == 6 ? "Trade holding " : "Use ",
                 pd_item_name(source->game, link.parameter));
    else if (m < (int)(sizeof special / sizeof *special) && special[m])
        snprintf(row->requirement, sizeof row->requirement, "%s", special[m]);
    else {
        const char* note = m < (int)(sizeof levelNote / sizeof *levelNote) ? levelNote[m] : NULL;
        snprintf(row->requirement, sizeof row->requirement, "Lv%d%s", link.parameter, note ? note : "");
    }
}

struct cartridge_moves {
    const struct pd_game* game;
    struct pd_level_move levels[100];
    struct pd_machine_move machines[400];
};
static void cartridge_move_row(const void* context, bool machine, int index, struct pd_dex_move_row* out) {
    const struct cartridge_moves* source = context;
    int move;
    if (machine) {
        const struct pd_machine_move m = source->machines[index];
        move = m.move;
        out->number = m.number;
        out->hm = m.hm;
    } else {
        const struct pd_level_move m = source->levels[index];
        move = m.move;
        out->number = m.level;
    }
    char scratch[48];
    snprintf(out->name, sizeof out->name, "%s", pd_move_name(source->game, move, scratch, sizeof scratch));
    snprintf(out->type, sizeof out->type, "%s", pd_type_name(source->game, pd_move_type(source->game, move)));
}

static void entry_view(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    static struct pd_dex_entry e;
    static uint32_t sprite[PD_SPRITE * PD_SPRITE];
    static bool spriteOk, entryOk;
    static int loaded;
    static unsigned loadedRom;
    static bool loadedHandheld;
    static struct cartridge_moves moves;
    static int moveCount, machineCount, linkCount, spotCount;
    static struct pd_spot spots[PD_SPOTS_MAX];
    static struct cartridge_links links;
    if (loaded != ui->dexOpen || loadedRom != pd_rom_id(g) || loadedHandheld != ui->handheldDex) {
        entryOk = pd_dex_entry(g, ui->dexOpen, &e);
        if (!entryOk && ui->handheldDex) {
            e.national = ui->dexOpen;
            e.species = pd_dex_species(g, e.national);
            pd_species_type_pair(g, e.species, &e.type1, &e.type2);
        }
        spriteOk = pd_dex_sprite(g, e.species, sprite);
        moveCount = ui->handheldDex ? pd_dex_level_moves(g, e.species, moves.levels, 100) : -1;
        machineCount = ui->handheldDex ? pd_machine_moves(g, e.species, moves.machines, 400) : -1;
        links.species = e.species;
        linkCount = ui->handheldDex ? pd_dex_evolutions(g, e.species, links.links, 24) : -1;
        spotCount = ui->handheldDex && g->cfg->wildHeaders && g->cfg->dexMapGroups && pd_dex_has_details(g)
            ? pd_dex_spots(g, e.species, spots, PD_SPOTS_MAX) : -1;
        loadedHandheld = ui->handheldDex;
        loaded = ui->dexOpen;
        loadedRom = pd_rom_id(g);
    }
    moves.game = g;
    links.game = g;
    bool national = lastNational;
    char buf[64], buf2[40];
    if (ui->handheldDex) {
        const bool hasLevels = moveCount >= 0;
        const struct pd_dex_x* expansion = g->cfg->expansion;
        const char* machines = machineCount < 0 ? NULL : expansion && expansion->teachableOff >= 0 ? "TEACHABLE MOVES" : "TM / HM";
        ui->dexPages = (1u << PD_DEX_INFO) | ((hasLevels || machines) ? 1u << PD_DEX_MOVES : 0) |
                       (linkCount >= 0 ? 1u << PD_DEX_EVOLVE : 0) | (spotCount >= 0 ? 1u << PD_DEX_AREA : 0);
        ui->dexSpotCount = spotCount < 0 ? 0 : spotCount;
        ui->dexLinkCount = linkCount < 0 ? 0 : linkCount;
        if (!(ui->dexPages & (1u << ui->dexPage))) ui->dexPage = PD_DEX_INFO;
        ui->dexLevelCount = hasLevels ? moveCount : 0;
        ui->dexMachineCount = machineCount < 0 ? 0 : machineCount;
        char size[48] = "", category[48] = "", abilities[48];
        const bool dual = e.type1 != e.type2 && e.type2 != PD_TYPE_NONE;
        snprintf(buf, sizeof buf, "%s%s%s", pd_type_name(g, e.type1), dual ? " / " : "", dual ? pd_type_name(g, e.type2) : "");
        if (e.heightIn > 0 || e.weightLbs10 > 0 || e.heightDm || e.weightHg) {
            // The game's own units, as the 3DS page: a metric cart's heightIn / weightLbs10 are -1.
            char ht[16], wt[20];
            pd_dex_measures(g, &e, ht, sizeof ht, wt, sizeof wt);
            snprintf(size, sizeof size, "%s  %s", ht, wt);
        }
        if (*e.category) snprintf(category, sizeof category, "%s Pokémon", e.category);
        snprintf(abilities, sizeof abilities, "%s%s%s", e.ability1, *e.ability2 ? " / " : "", e.ability2);
        int row = 0;
        for (int i = 0; i < lastCount; i++) if (lastList[i] == e.national) row = i;
        const struct pd_dex_card view = {
            .name = pd_species_name(g, e.species, buf2, sizeof buf2), .types = buf,
            .measurements = size, .category = category, .abilities = abilities,
            .pixels = spriteOk ? sprite : pd_mon_icon_pixels(g, e.species, false),
            .pictureSize = spriteOk ? PD_SPRITE : PD_ICON, .pictureScale = spriteOk ? 1 : 2, .clear = 0xff000000u,
            .number = shown_number(g, national, e.national), .row = row, .count = lastCount,
            .caught = pd_dex_caught(s, e.national), .pages = ui->dexPages, .page = ui->dexPage,
        };
        const struct pd_dex_pane pane = pd_ui_dex_card(ui, c, &view);
        if (ui->dexPage == PD_DEX_MOVES) {
            const struct pd_dex_moves_source list = {&moves, ui->dexLevelCount, ui->dexMachineCount, hasLevels, machines, cartridge_move_row};
            pd_ui_dex_moves(c, pane.x, pane.y, pane.w, pane.h, &list, ui->dexDetailRow);
        } else if (ui->dexPage == PD_DEX_EVOLVE) {
            const struct pd_dex_links list = {&links, cartridge_link_row, ui->dexLinkCount};
            pd_ui_dex_links(c, pane, &list, ui->dexDetailRow);
        } else if (ui->dexPage == PD_DEX_AREA) {
            static const char* const methods[] = {"GRASS", "SURF", "ROCK", "FISH"};
            const int rowH = 30, visible = pane.h / rowH;
            const int first = ui_clamp_scroll(ui->dexDetailRow - visible / 2, spotCount, visible);
            if (!spotCount) pd_text_fit(c, pane.x + 3, pane.y + 12, pane.w - 6, "Not found in the wild.", PD_MUTED, PD_MUTED_SHADOW);
            for (int i = first; i < spotCount && i < first + visible; ++i) {
                const struct pd_spot* spot = &spots[i];
                const int y = pane.y + (i - first) * rowH;
                if (i == ui->dexDetailRow) pd_fill(c, pane.x, y, pane.w, rowH - 1, PD_ROW_SELECTED);
                pd_text_fit(c, pane.x + 3, y, pane.w - 6, pd_mapsec_name(g, spot->mapsec), PD_LABEL, PD_LABEL_SHADOW);
                snprintf(buf, sizeof buf, "%s  LV%d-%d", methods[spot->method], spot->minLevel, spot->maxLevel);
                pd_text_fit(c, pane.x + 3, y + 14, pane.w - 6, buf, PD_MUTED, PD_MUTED_SHADOW);
            }
        } else pd_ui_dex_info(c, pane, e.description, entryOk ? e.stats : NULL, e.gen1Stats);
        return;
    }
    int x = MARGIN, y = CONTENT_Y, w = c->w - 2 * MARGIN, h = c->h - CONTENT_Y - MARGIN;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    int ix = x + f + 4, iw = w - 2 * (f + 4);
    int top = y + f + 2;

    // The sprite on the list window's grey, framed.
    int bx = ix, by = top;
    pd_list_box(c, bx, by, PD_SPRITE + 16, PD_SPRITE + 16);
    // Dark mode on Gen 1: the picture on its own white card, or its outline vanishes (spritePaper).
    if (spriteOk && pd_dark && g->cfg && g->cfg->gb && g->cfg->gb->gen == 1) pd_fill(c, bx + 8, by + 8, PD_SPRITE, PD_SPRITE, 0xFFFFFF);
    if (spriteOk) draw_sprite(c, bx + 8, by + 8, sprite);

    int tx = bx + PD_SPRITE + 24, tw = ix + iw - tx;
    snprintf(buf, sizeof(buf), "No.%03d", shown_number(g, national, e.national));
    pd_text(c, tx, top, buf, PD_MUTED, PD_MUTED_SHADOW);
    int nw = pd_text(c, tx + 50, top, pd_species_name(g, e.species, buf2, sizeof(buf2)), PD_LABEL, PD_LABEL_SHADOW);
    if (pd_dex_seen(s, e.national)) ui_ball(c, tx + 50 + nw + 6, top + 4, pd_dex_caught(s, e.national));
    snprintf(buf, sizeof(buf), "%s %s", e.category, pd_dex_category_suffix(g));
    pd_text_fit(c, tx, top + 18, tw, buf, PD_MUTED, PD_MUTED_SHADOW);
    if (e.type2 != e.type1 && e.type2 != PD_TYPE_NONE) {
        snprintf(buf, sizeof(buf), "%s / %s", pd_type_name(g, e.type1), pd_type_name(g, e.type2));
    } else {
        snprintf(buf, sizeof(buf), "%s", pd_type_name(g, e.type1));
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
            // The tab bar's chips (two-tone), the open section white.
            pd_layered_two_tone(c, cx, dy, cw, 18, 3, pd_cur->chip, pd_cur->chipCount,
                                i == ui->dexSection ? pd_cur->tabSelectedFill : pd_cur->tabIdleFill, 0);
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
    int counts[DEX_FILTERS]={lastCount};
    if(ui->handheldDex){
        int matching=0;
        for(int i=0;i<lastCount;i++){
            const int id=lastList[i],filter=filter_of(s,id);
            counts[filter]++;
            if((ui->dexOpen && filter!=DEX_UNSEEN && (ui->dexFilter==DEX_ALL || filter==ui->dexFilter)) ||
               (!ui->dexOpen && (ui->dexFilter==DEX_ALL || filter==ui->dexFilter)))
                lastList[matching++]=id;
        }
        lastCount=matching;
    }
    if (ui->dexOpen) {
        entry_view(ui, c, g, s);
    } else {
        list_view(ui,c,g,s,counts);
    }
}

void ui_dex_next_filter(struct pd_ui* ui) {
    if (ui_dex_act(ui, HIT_DEX_FILTER + (ui->dexFilter + 1) % DEX_FILTERS)) ui->dexReturn = -1;
}

bool ui_dex_act(struct pd_ui* ui, int id) {
    if (id >= HIT_DEX_SECTION && id < HIT_DEX_SECTION + 4) {
        ui->dexSection = id - HIT_DEX_SECTION;
        ui->dexPageScroll = 0;
        return true;
    }
    if (ui->tab != PD_TAB_DEX || ui->overlay != PD_OVERLAY_NONE) return false;
    if (ui->handheldDex && id >= HIT_DEX_FILTER && id < HIT_DEX_FILTER+DEX_FILTERS) {
        ui->dexFilter=id-HIT_DEX_FILTER;
        ui->dexScroll=0;
        return true;
    }
    if (ui->handheldDex && ui->dexOpen && id >= HIT_DEX_PAGE && id <= HIT_DEX_CYCLE) {
        int page = id - HIT_DEX_PAGE;
        if (id == HIT_DEX_CYCLE) {
            page = (ui->dexPage + 1) % PD_DEX_SECTIONS;
            while (!(ui->dexPages & (1u << page)) && page != ui->dexPage) page = (page + 1) % PD_DEX_SECTIONS;
        }
        if (ui->dexPages & (1u << page)) {
            ui->dexPage = page;
            ui->dexDetailRow = 0;
        }
        return true;
    }
    if (id == HIT_DEX_TOGGLE) {
        // Flip from what shows now (the save's default until something was picked).
        ui->dexNational = !lastNational;
        ui->dexScroll = 0;
        return true;
    }
    if (id >= HIT_DEX_ROW && id <= HIT_DEX_ROW + PD_DEX_BYTES * 8) {
        ui->dexOpen = id - HIT_DEX_ROW;
        ui->dexPage=ui->dexDetailRow=0;
        return true;
    }
    if ((id == HIT_DEX_PREV || id == HIT_DEX_NEXT) && lastCount > 0) {
        int at = 0;
        for (int i = 0; i < lastCount; i++) {
            if (lastList[i] == ui->dexOpen) at = i;
        }
        at = (at + lastCount + (id == HIT_DEX_NEXT ? 1 : -1)) % lastCount;
        ui->dexOpen = lastList[at];
        ui->dexDetailRow=0;
        return true;
    }
    return false;
}

void pd_ui_dex_view(struct pd_ui* ui,struct pd_canvas* c,const struct pd_game* g,const struct pd_snapshot* s,int selected){
    ui->hitCount=0;if(!ui->keepBackdrop)pd_backdrop(c);
    if(!ui->dexSource){ui_dex_tab(ui,c,g,s);return;}
    int n=ui->dexSource->count;
    if(selected<0)selected=0;
    if(n>0 && selected>=n)selected=n-1;
    ui->dexScroll=selected*DEX_ROW;ui->pressedId=n>0?HIT_DEX_ROW+ui->dexSource->national(ui->dexSource->ctx,selected):-1;
    list_view(ui,c,g,s,NULL);
}
