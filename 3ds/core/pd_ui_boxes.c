// BOXES (BoxesScreen.kt): the PC's boxes read live, drawn in our own pieces in the PC's colours (PcColors, BoxWallpapers,
// from FireRed's and Emerald's screens): the PKMN DATA panel on the left, the box on the right, its 30 Pokémon over its
// wallpaper. A box Pokémon's summary has the PC's button: WITHDRAW, or SWAP with six in the party; a party one's DEPOSIT.
#include <stdio.h>
#include <string.h>

#include "pd_boxes.h"
#include "pd_icon.h"
#include "pd_tables.h"
#include "pd_ui_internal.h"
#include "pd_wallpapers_gen.h"

#define PC_FRAME 0x52525A
#define PC_PANEL_TOP 0x73737B
#define PC_PANEL 0x9494AC
#define PC_LIP 0xA4A4B4
#define PC_WELL_EDGE 0x7BB4D5
#define PC_WELL_DARK 0x9CCDEE
#define PC_WELL 0xB4D5F6
#define PC_WELL_LIGHT 0xD5E6FF
#define PC_TITLE_TEXT 0xFFDE94
#define PC_ARROW 0x73737B
#define PC_ARROW_LIGHT 0x9494AC
#define PC_PEACH 0xFFE6D5
#define PC_PEACH_DARK 0xFFDECD
#define PC_PEACH_LIGHT 0xFFEEE6
#define NIGHT 0x12141A

#define PANEL_W 94
#define HEADER_H 22
#define ARROW_W 12

static uint32_t shade(uint32_t c) {
    if (!pd_cur->scrim) return c;
    uint32_t out = 0;
    for (int sh = 0; sh <= 16; sh += 8) {
        int a = (int) (c >> sh & 0xFF), b = NIGHT >> sh & 0xFF;
        out |= (uint32_t) (a + (b - a) * 45 / 100) << sh;
    }
    return out;
}

// One 32 x 32 ring tile of the screen's backdrop, repeated.
static void backdrop(struct pd_canvas* c) {
    uint32_t base = shade(PC_PEACH), dark = shade(PC_PEACH_DARK), light = shade(PC_PEACH_LIGHT);
    pd_fill(c, 0, 0, c->w, c->h, base);
    for (int y = 0; y < 32; y++) {
        for (int x = 0; x < 32; x++) {
            int dx2 = 2 * x - 31, dy2 = 2 * y - 31, d = dx2 * dx2 + dy2 * dy2; // twice the distance, squared
            uint32_t v = d >= 256 && d <= 484 ? (dx2 + dy2 < 0 ? light : dark) : d < 36 ? light : base;
            if (v == base) continue;
            for (int ty = y; ty < c->h; ty += 32)
                for (int tx = x; tx < c->w; tx += 32) pd_fill(c, tx, ty, 1, 1, v);
        }
    }
}

static int wave(int x) {
    static const int W[8] = { 0, 0, 1, 2, 2, 2, 1, 0 };
    return W[x % 8];
}

// BoxWallpapers.ink: 0 the base colour, 1 mark, 2 mark2.
static int ink(int pattern, int x, int y) {
    static const uint8_t BUBBLE[10][2] = { { 3, 2 }, { 2, 3 }, { 4, 3 }, { 3, 4 }, { 11, 9 }, { 10, 10 }, { 12, 10 }, { 11, 11 }, { 13, 3 }, { 6, 12 } };
    switch (pattern) {
    case PD_PAPER_TUFTS: {
        int sx = (x + ((y / 8) % 2 == 0 ? 0 : 4)) % 8;
        return (y % 8 == 3 || y % 8 == 4) && (sx == 2 || sx == 4) ? 1 : 0;
    }
    case PD_PAPER_BRICKS: {
        int off = (y / 4) % 2 == 0 ? 0 : 4;
        return y % 4 == 3 || (x + off) % 8 == 7 ? 1 : y % 4 == 0 ? 2 : 0;
    }
    case PD_PAPER_WAVES: {
        int v = (y + wave(x)) % 8;
        return v == 0 ? 2 : v == 1 ? 1 : 0;
    }
    case PD_PAPER_STRIPES: return x % 8 <= 1 ? 1 : x % 8 == 2 ? 2 : 0;
    case PD_PAPER_CHECKS: return (x / 8 + y / 8) % 2 == 0 ? 1 : x % 8 == 0 || y % 8 == 0 ? 2 : 0;
    case PD_PAPER_DIAGONAL: return (x + y) % 8 <= 1 ? 1 : (x + y) % 8 == 2 ? 2 : 0;
    case PD_PAPER_DOTS:
        return (x % 8 >= 2 && x % 8 <= 3 && y % 8 >= 2 && y % 8 <= 3) || (x % 8 >= 6 && y % 8 >= 6 && (x / 8 + y / 8) % 2 == 0) ? 1 : 0;
    case PD_PAPER_BUBBLES:
        for (int i = 0; i < 10; i++)
            if (x == BUBBLE[i][0] && y == BUBBLE[i][1]) return 1;
        for (int i = 0; i < 10; i++)
            if (x == BUBBLE[i][0] + 1 && y == BUBBLE[i][1] + 1) return 2;
        return 0;
    case PD_PAPER_POLKA: {
        int cx = x % 8, cy = y % 8;
        return cx >= 2 && cx <= 4 && cy >= 2 && cy <= 4 && !(cx != 3 && cy != 3) ? ((x / 8 + y / 8) % 2 == 0 ? 1 : 2) : 0;
    }
    case PD_PAPER_GRID: return x % 8 == 0 || y % 8 == 0 ? 1 : x % 8 == 1 || y % 8 == 1 ? 2 : 0;
    default: return 0;
    }
}

static const struct pd_paper* paper_of(const struct pd_game* g, int id) {
    bool emerald = g->kind == PD_GAME_EMERALD;
    if (id < 0 || id >= PD_PAPER_COUNT) id = PD_PAPER_COUNT - 1;
    return emerald ? &PD_PAPERS_EMERALD[id] : &PD_PAPERS_FIRERED[id];
}

// A bevel: light top and left, dark bottom and right.
static void bevel(struct pd_canvas* c, int x, int y, int w, int h, int width, uint32_t top, uint32_t left, uint32_t right, uint32_t bottom) {
    pd_fill(c, x, y, w, width, top);
    pd_fill(c, x, y, width, h, left);
    pd_fill(c, x + w - width, y, width, h, right);
    pd_fill(c, x, y + h - width, w, width, bottom);
}

static void wallpaper(struct pd_canvas* c, const struct pd_paper* p, int x, int y, int w, int h) {
    bevel(c, x, y, w, h, 2, shade(p->frameLight), shade(p->frameLight), shade(p->frameDark), shade(p->frameDark));
    bevel(c, x + 2, y + 2, w - 4, h - 4, 2, shade(p->edgeTop), shade(p->edgeLeft), shade(p->edgeRight), shade(p->edgeBottom));
    uint32_t inks[3] = { shade(p->base), shade(p->mark), shade(p->mark2) };
    for (int yy = 4; yy < h - 4; yy++)
        for (int xx = 4; xx < w - 4; xx++) pd_fill(c, x + xx, y + yy, 1, 1, inks[ink(p->pattern, xx % 16, yy % 16)]);
}

static void header(struct pd_canvas* c, const struct pd_paper* p, int x, int y, int w, int h, const char* name) {
    bevel(c, x, y, w, h, 2, shade(p->frameLight), shade(p->frameLight), shade(p->frameDark), shade(p->frameDark));
    pd_fill(c, x + 2, y + 2, w - 4, h - 4, shade(0xFFFFFF));
    int top = 3, bands = 16, inner = h - 2 * top;
    for (int i = 0; i < bands; i++) {
        int y0 = y + top + inner * i / bands, y1 = y + top + inner * (i + 1) / bands;
        pd_fill(c, x + top, y0, w - 2 * top, y1 - y0, shade(p->header[i]));
    }
    pd_text(c, x + (w - pd_text_width(name)) / 2, pd_text_y(y, h), name, 0xFFFFFF, 0x393939);
}

static void arrow(struct pd_canvas* c, int x, int y, bool right, int id, struct pd_ui* ui) {
    static const char* const A[9] = { "....LD", "...LFD", "..LFFD", ".LFFFD", "LFFFFD", ".DFFFD", "..DFFD", "...DFD", "....DD" };
    for (int r = 0; r < 9; r++) {
        for (int col = 0; col < 6; col++) {
            char k = A[r][right ? 5 - col : col];
            if (k == '.') continue;
            uint32_t v = k == 'L' ? shade(PC_ARROW_LIGHT) : k == 'F' ? shade(PC_ARROW) : shade(PC_FRAME);
            pd_fill(c, x + col, y + r * 2, 1, 2, v);
        }
    }
    ui_add_hit(ui, x - 4, y - 4, ARROW_W + 4, HEADER_H, id);
}

static const char* shown_name(const struct pd_mon* m, char* buf, size_t len) {
    if (m->egg) snprintf(buf, len, "EGG");
    else if (*m->nickname) snprintf(buf, len, "%s", m->nickname);
    else return pd_species_name(ui_game, m->species, buf, len);
    return buf;
}

static void panel(struct pd_canvas* c, const struct pd_game* g, const struct pd_mon* m, int x, int y, int w, int h) {
    pd_fill(c, x, y, w, h, shade(PC_FRAME));
    pd_fill(c, x + 2, y + 2, w - 4, 18, shade(PC_PANEL_TOP));
    pd_text(c, x + (w - pd_text_width("PKMN DATA")) / 2, pd_text_y(y + 2, 18), "PKMN DATA", shade(PC_TITLE_TEXT), 0);
    pd_fill(c, x + 2, y + 20, w - 4, h - 22, shade(PC_PANEL));
    pd_fill(c, x + 2, y + 20, w - 4, 1, shade(PC_LIP));
    int wx = x + 8, wy = y + 25, ww = w - 16, wh = 70;
    pd_round_rect(c, wx, wy, ww, wh, 3, shade(PC_WELL_EDGE));
    for (int yy = 2; yy < wh - 2; yy++)
        for (int xx = 2; xx < ww - 2; xx++)
            pd_fill(c, wx + xx, wy + yy, 1, 1, shade((xx / 2 + yy / 2) % 2 ? PC_WELL_DARK : (xx + yy) % 4 == 0 ? PC_WELL_LIGHT : PC_WELL));
    if (!m) return;
    pd_draw_mon_icon_scaled(c, g, m->species, wx + (ww - 2 * PD_ICON) / 2, wy + (wh - 2 * PD_ICON) / 2, 2, false);
    char buf[40];
    int ty = wy + wh + 3;
    pd_text_fit(c, x + 6, ty, w - 12, shown_name(m, buf, sizeof(buf)), 0xFFFFFF, 0);
    ty += 15;
    if (m->egg) return;
    char species[24];
    snprintf(buf, sizeof(buf), "/%s", pd_species_name(ui_game, m->species, species, sizeof(species)));
    pd_text_fit(c, x + 6, ty, w - 12, buf, 0xFFFFFF, 0);
    ty += 15;
    snprintf(buf, sizeof(buf), "Lv%d", m->level);
    pd_text(c, x + 6, ty, buf, 0xFFFFFF, 0);
    ty += 15;
    if (m->heldItem) pd_text_fit(c, x + 6, ty, w - 12, pd_item_name(g, m->heldItem), 0xFFFFFF, 0);
}

static const struct pd_mon* column[PD_PARTY_SIZE];
static int columnSlots[PD_PARTY_SIZE];

// The summary's column: six of the box's Pokémon, the shown one among them.
static int fill_column(const struct pd_boxes* b, int shown, int* at) {
    int all[PD_BOX_SLOTS], n = 0, here = 0;
    for (int i = 0; i < PD_BOX_SLOTS; i++) {
        if (!b->has[i]) continue;
        if (i == shown) here = n;
        all[n++] = i;
    }
    int first = here / PD_PARTY_SIZE * PD_PARTY_SIZE, count = n - first < PD_PARTY_SIZE ? n - first : PD_PARTY_SIZE;
    for (int i = 0; i < count; i++) {
        columnSlots[i] = all[first + i];
        column[i] = &b->mons[all[first + i]];
    }
    *at = here - first;
    return count;
}

void ui_box_message(struct pd_ui* ui, struct pd_canvas* c) {
    int w = c->w - 2 * MARGIN, h = 52, x = MARGIN, y = (c->h - h) / 2 - 16;
    int f = pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
    pd_text_wrap(c, x + f + 6, y + f + 2, w - 2 * f - 52, 15, 3, ui->boxMessage, PD_TITLE_TEXT, PD_TITLE_SHADOW);
    ui_button(ui, c, x + w - f - 40, y + (h - 22) / 2, 36, 22, "OK", HIT_BOX_MESSAGE);
}

void ui_boxes_tab(struct pd_ui* ui, struct pd_canvas* c, const struct pd_game* g, const struct pd_snapshot* s) {
    const struct pd_boxes* b = ui->boxes;
    if (!b || !b->ok || b->boxCount == 0) {
        ui_notice(c, "NO BOXES YET", "The boxes show once the game", "has loaded a save.");
        return;
    }
    if (ui->boxSwapPick) {
        char buf[80], name[32];
        struct pd_canvas grid = *c;
        grid.h -= 34;
        if (grid.cy1 > grid.h) grid.cy1 = grid.h;
        ui_party_grid(ui, &grid, g, s, -1, 0);
        const struct pd_mon* m = ui->boxSlot >= 0 ? &b->mons[ui->boxSlot] : NULL;
        snprintf(buf, sizeof(buf), s->partyCount >= PD_PARTY_SIZE ? "Party full: swap %s with which POKéMON?" : "Swap %s with which POKéMON?",
                 m ? shown_name(m, name, sizeof(name)) : "");
        int y = c->h - 32, w = c->w - 2 * MARGIN;
        int f = pd_title_box(c, MARGIN, y, w, 30, PD_TITLE_FILL);
        pd_text_wrap(c, MARGIN + f + 4, y + f, w - 2 * f - 64, 13, 2, buf, PD_TITLE_TEXT, PD_TITLE_SHADOW);
        ui_button(ui, c, MARGIN + w - f - 54, y + 5, 50, 20, "CANCEL", HIT_BOX_CANCEL);
        return;
    }
    if (ui->boxSummary >= 0 && b->has[ui->boxSummary]) {
        int shown = 0, n = fill_column(b, ui->boxSummary, &shown);
        ui_mon_summary(ui, c, g, s, &b->mons[ui->boxSummary], column, n, shown, false, ui->boxMoves ? "WITHDRAW" : NULL);
        if (*ui->boxMessage) ui_box_message(ui, c);
        return;
    }
    ui->boxSummary = -1;
    backdrop(c);
    int shownBox = b->shown;
    int px = MARGIN, py = 3, ph = c->h - 5;
    int focus = -1;
    for (int i = 0; i < PD_BOX_SLOTS; i++)
        if (ui->focusId == HIT_BOX_SLOT + i || ui->pressedId == HIT_BOX_SLOT + i) focus = i;
    if (focus < 0) focus = ui->boxSlot;
    panel(c, g, focus >= 0 && b->has[focus] ? &b->mons[focus] : NULL, px, py, PANEL_W, ph);

    int bx = px + PANEL_W + 4, bw = c->w - MARGIN - bx;
    const struct pd_paper* p = paper_of(g, b->wallpaper[shownBox]);
    arrow(c, bx + 1, py + 2, false, HIT_BOX_PREV, ui);
    arrow(c, bx + bw - 7, py + 2, true, HIT_BOX_NEXT, ui);
    header(c, p, bx + ARROW_W - 2, py, bw - 2 * (ARROW_W - 2), HEADER_H, b->names[shownBox]);
    ui_add_hit(ui, bx + ARROW_W, py, bw - 2 * ARROW_W, HEADER_H, HIT_BOX_NAME);
    int gy = py + HEADER_H + 2, gh = ph - HEADER_H - 2;
    wallpaper(c, p, bx, gy, bw, gh);
    int cw = (bw - 8) / PD_BOX_COLUMNS, ch = (gh - 8) / 5;
    for (int i = 0; i < PD_BOX_SLOTS; i++) {
        int cx = bx + 4 + (i % PD_BOX_COLUMNS) * cw, cy = gy + 4 + (i / PD_BOX_COLUMNS) * ch;
        if (i == focus) {
            bevel(c, cx, cy, cw, ch, 1, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF);
        }
        if (b->has[i]) pd_draw_mon_icon(c, g, b->mons[i].species, cx + (cw - PD_ICON) / 2, cy + (ch - PD_ICON) / 2 - 1, false);
        ui_add_hit(ui, cx, cy, cw, ch, HIT_BOX_SLOT + i);
    }
    if (*ui->boxMessage) ui_box_message(ui, c);
}

// The boxes as a grid: where BOXES jumps to, or DEPOSIT's where to.
void ui_boxes_overlay(struct pd_ui* ui, struct pd_canvas* c) {
    const struct pd_boxes* b = ui->boxes;
    if (!b) return;
    int cols = 3, rows = (b->boxCount + cols - 1) / cols, x = MARGIN, w = c->w - 2 * MARGIN;
    int rowH = 20, top = 22, y = (c->h - rows * rowH - top - 16) / 2;
    if (y < 2) y = 2;
    int f = pd_list_box(c, x, y, w, rows * rowH + top + 12);
    pd_text(c, x + f + 6, y + f, ui->boxDepositing ? "Deposit in which BOX?" : "BOXES", PD_LABEL, PD_LABEL_SHADOW);
    int gx = x + f + 4, gw = (w - 2 * f - 8) / cols, gy = y + f + top - 4;
    for (int i = 0; i < b->boxCount; i++) {
        int cx = gx + (i % cols) * gw, cy = gy + (i / cols) * rowH;
        bool on = i == b->shown;
        if (on || ui->pressedId == HIT_BOX_PICK + i) pd_fill(c, cx, cy, gw - 2, rowH - 1, PD_ROW_SELECTED);
        char text[40];
        snprintf(text, sizeof(text), "%s %d/30", b->names[i], b->count[i]);
        pd_text_fit(c, cx + 3, pd_text_y(cy, rowH), gw - 6, text, on ? PD_VALUE : PD_LABEL, on ? PD_VALUE_SHADOW : PD_LABEL_SHADOW);
        ui_add_hit(ui, cx, cy, gw - 2, rowH - 1, HIT_BOX_PICK + i);
    }
}

bool ui_boxes_act(struct pd_ui* ui, int id, const struct pd_snapshot* s) {
    const struct pd_boxes* b = ui->boxes;
    if (!b) return false;
    if (ui->overlay == PD_OVERLAY_PICK_BOX) {
        if (id < HIT_BOX_PICK || id >= HIT_BOX_PICK + PD_MAX_BOXES) return false;
        int box = id - HIT_BOX_PICK;
        ui->overlay = PD_OVERLAY_NONE;
        if (ui->boxDepositing) {
            ui->boxDepositing = false;
            ui->boxTarget = box;
            ui->boxPartySlot = ui->summarySlot;
            ui->action = PD_ACTION_BOX_DEPOSIT;
        } else {
            ui->boxShown = box;
            ui->boxSlot = -1;
        }
        return true;
    }
    // A party Pokémon's DEPOSIT, from the PARTY tab's summary.
    if (ui->tab == PD_TAB_PARTY) {
        if (id == HIT_SUMMARY_PC) {
            ui->boxDepositing = true;
            ui->overlay = PD_OVERLAY_PICK_BOX;
            return true;
        }
        if (id == HIT_BOX_MESSAGE) {
            *ui->boxMessage = 0;
            return true;
        }
        return false;
    }
    if (ui->tab != PD_TAB_BOXES) return false;
    if (id == HIT_BOX_MESSAGE) {
        *ui->boxMessage = 0;
        return true;
    }
    if (ui->boxSwapPick) {
        if (id == HIT_BOX_CANCEL) {
            ui->boxSwapPick = false;
            return true;
        }
        if (id >= HIT_SLOT && id < HIT_SLOT + PD_PARTY_SIZE && s && id - HIT_SLOT < s->partyCount) {
            ui->boxPartySlot = id - HIT_SLOT;
            ui->boxSwapPick = false;
            ui->action = PD_ACTION_BOX_SWAP;
            return true;
        }
        return false;
    }
    if (ui->boxSummary >= 0) {
        if (id == HIT_BACK) {
            ui->boxSummary = -1;
            return true;
        }
        if (id >= HIT_SUMMARY_MON && id < HIT_SUMMARY_MON + PD_PARTY_SIZE) {
            ui->boxSummary = columnSlots[id - HIT_SUMMARY_MON];
            ui->boxSlot = ui->boxSummary;
            return true;
        }
        if (id == HIT_SUMMARY_PC) {
            ui->boxSlot = ui->boxSummary;
            if (s && s->partyCount >= PD_PARTY_SIZE) ui->boxSwapPick = true;
            else ui->action = PD_ACTION_BOX_WITHDRAW;
            return true;
        }
        return false;
    }
    if (id >= HIT_BOX_SLOT && id < HIT_BOX_SLOT + PD_BOX_SLOTS) {
        int slot = id - HIT_BOX_SLOT;
        ui->boxSlot = slot;
        if (b->has[slot]) ui->boxSummary = slot;
        return true;
    }
    if (id == HIT_BOX_PREV || id == HIT_BOX_NEXT) {
        int n = b->boxCount ? b->boxCount : 1;
        ui->boxShown = (ui->boxShown + (id == HIT_BOX_NEXT ? 1 : n - 1)) % n;
        ui->boxSlot = -1;
        return true;
    }
    if (id == HIT_BOX_NAME) {
        ui->overlay = PD_OVERLAY_PICK_BOX;
        return true;
    }
    return false;
}
