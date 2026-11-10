/* The QUICK MENU (quick_menu.h): tiles of one size round a circle, the first at the top, clockwise; the
 * highlighted one's name in the middle, with why it's dimmed. GPL-3.0, see ../NOTICE. */
#include "quick_menu.h"
#include "hud_style.h"

#include "pd_icon.h"
#include "pd_style.h"
#include <math.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#define TILE_W 74
#define TILE_H 42
// QuickMenu.kt's icons: one character a pixel, '.' clear.
const char *const quick_icon_grass[] = {
    "................", "...K......K.....", "..KGK....KGK..K.", "..KGK...KGGK.KGK", ".KGLGK..KGLGKKGK",
    ".KGLGK.KGLLGKGLK", "KGLLGKKGLLGKGLLK", "KGLLGGKGLLGGGLLK", "KGLLLGGGLLLGGLLK", "KGLLLGGGLLLLGLLK",
    "KGGLLLGGLLLLGLGK", "KGGLLLLGLLLLLLGK", "KGGGLLLLLLLLLGGK", "KGGGGGLLLLLGGGGK", "KKKKKKKKKKKKKKKK",
    "................", NULL,
};
static const char *const SAVE_STATE[] = {
    "....AAAA....", "....AAAA....", "..AAAAAAAA..", "...AAAAAA...", "....AAAA....", ".....AA.....",
    "KKKKKKKKKKKK", "KDDLLLLLLDDK", "KDDLLLLLLDDK", "KDDDDDDDDDDK", "KDDDDDDDDDDK", "KKKKKKKKKKKK", NULL,
};
static const char *const LOAD_STATE[] = {
    ".....AA.....", "....AAAA....", "...AAAAAA...", "..AAAAAAAA..", "....AAAA....", "....AAAA....",
    "KKKKKKKKKKKK", "KDDLLLLLLDDK", "KDDLLLLLLDDK", "KDDDDDDDDDDK", "KDDDDDDDDDDK", "KKKKKKKKKKKK", NULL,
};
static const char *const FF[] = {
    "1.....1.....", "11....11....", "111...111...", "1111..1111..", "11111.11111.", "111111111111",
    "11111.11111.", "1111..1111..", "111...111...", "11....11....", "1.....1.....", NULL,
};
// FieldHud.kt's PC_ICON.
const char *const quick_icon_pc[] = {
    "................", ".KKKKKKKKKKKKKK.", ".KLLLLLLLLLLLLK.", ".KLKKKKKKKKKKLK.", ".KLKSSSSSSHHKLK.",
    ".KLKSSSSSHHSKLK.", ".KLKSSSSHHSSKLK.", ".KLKSSSSSSSSKLK.", ".KLKKKKKKKKKKLK.", ".KLLLLLLLLLLGLK.",
    ".KDDDDDDDDDDDDK.", ".KKKKKKKKKKKKKK.", "......KDDK......", "....KKKKKKKK....", "...KLLLLLLLLK...",
    "...KKKKKKKKKK...", NULL,
};
// REPEL and BIKE where the game has no item icons to draw (Ruby / Sapphire; the app has its bundled item sprites).
const char *const quick_icon_repel[] = {
    "....KKK.....", "...KLLLK.K..", "...KKKKK..K.", "..KGGGGGK.K.", "..KGLGGGK...", "..KGLGGGK...",
    "..KGGGGGK...", "..KGGGGGK...", "..KGGGGGK...", "..KKKKKKK...", NULL,
};
static const char *const WING[] = {
    "............1...", "..........111...", "........11111...", "......1111111...",
    "....11111111....", "..111111111.....", ".111111111......", ".11111111.......",
    "..111111........", "...1111.........", "....11..........", NULL,
};
static const char *const BIKE[] = {
    "...........KK...", "...KKK.....K....", "....K.....K.....", "....KKKKKKK.....", "..KKK.K..K.KKK..",
    ".K...KK.K.K...K.", "K...K.KK.K.K...K", "K...K..KK..K...K", "K.....K..K.....K", ".K...K....K...K.",
    "..KKK......KKK..", NULL,
};
const char *const quick_icon_companion[] = {
    "......11", ".....11.", "....11..", "...11...", "..11....", ".11.....", "11......",
    ".11.....", "..11....", "...11...", "....11..", ".....11.", "......11", NULL,
};

const char *const quick_icon_hud[] = {
    "11111..11111", "1..........1", "1..........1", "1....11....1", "1....11....1", "1..........1", "1..........1",
    "11111..11111", NULL,
};

const char *quick_label(enum quick_action a) {
  switch (a) {
  case QUICK_PC: return "PC";
  case QUICK_FLY: return "FLY";
  case QUICK_REPEL: return "REPEL";
  case QUICK_BIKE: return "BIKE";
  case QUICK_WILD: return "WILD";
  case QUICK_SAVE_STATE: return "SAVE STATE";
  case QUICK_LOAD_STATE: return "LOAD STATE";
  case QUICK_FF: return "FF";
  case QUICK_COMPANION: return "COMPANION";
  case QUICK_HUD: return "HUD";
  default: return "";
  }
}

static uint32_t icon_color(enum quick_action a, char ch, uint32_t text) {
  if (a == QUICK_WILD)
    return ch == 'K' ? 0x183818 : ch == 'G' ? 0x389838 : 0x78D058;
  if (a == QUICK_PC)
    return ch == 'K' ? 0x202838 : ch == 'L' ? 0xD8D8E0 : ch == 'D' ? 0x9098A8 : ch == 'S' ? 0x3070C8
         : ch == 'H' ? 0x90C8F8 : 0x40D040;
  if (a == QUICK_REPEL)
    return ch == 'K' ? 0x202838 : ch == 'L' ? 0xE8ECF4 : 0x48A848;
  if (a == QUICK_SAVE_STATE || a == QUICK_LOAD_STATE)
    return ch == 'K' ? 0x202838 : ch == 'D' ? 0x4870C0 : ch == 'L' ? 0xE8ECF4 : 0xF8C030;
  return text;
}

// An icon centred at (cx, cy), `scale` canvas pixels a pixel; dim: at half strength over the glass.
static void icon(struct pd_canvas *c, enum quick_action a, int cx, int cy, int scale, bool dim, const struct hud_ink *ink) {
  const char *const *rows = a == QUICK_PC ? quick_icon_pc : a == QUICK_WILD ? quick_icon_grass : a == QUICK_SAVE_STATE ? SAVE_STATE
                          : a == QUICK_LOAD_STATE ? LOAD_STATE : a == QUICK_FF ? FF : a == QUICK_REPEL ? quick_icon_repel
                          : a == QUICK_BIKE ? BIKE : a == QUICK_FLY ? WING
                          : a == QUICK_HUD ? quick_icon_hud : quick_icon_companion;
  int h = 0, w = (int)strlen(rows[0]);
  while (rows[h])
    h++;
  int x0 = cx - w * scale / 2, y0 = cy - h * scale / 2;
  const uint32_t text = ink->window ? PD_TITLE_TEXT : ink->text;
  const uint32_t face = ink->window ? PD_TITLE_FILL : ink->tileTop;
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      if (rows[y][x] == '.')
        continue;
      uint32_t col = icon_color(a, rows[y][x], text);
      if (dim) { // QuickTile's 45% icon opacity over the upper band.
        uint32_t blended = 0;
        for (unsigned shift = 0; shift < 24; shift += 8)
          blended |= (((col >> shift & 255) * 45 + (face >> shift & 255) * 55) / 100) << shift;
        col = blended;
      }
      pd_fill(c, x0 + x * scale, y0 + y * scale, scale, scale, col);
    }
}

/* GRID (QuickMenuStyle.GRID; Keegan Whitacre's layout, pokedaisy-brick): the width fitted, rows balanced, the short
 * last row centred, room kept under the tiles for the selected action's name. */
static bool gridMode;
/* Where the last draw put each tile / list row (canvas pixels), for a touch to find (quick_hit / quick_list_hit). */
#define QUICK_MAX_HITS 32
static struct { int x, y, w, h, i; } lastHit[QUICK_MAX_HITS];
static int lastHits;
static void hit_add(int x, int y, int w, int h, int i) {
  if (lastHits < QUICK_MAX_HITS)
    lastHit[lastHits++] = (__typeof__(lastHit[0])){x, y, w, h, i};
}
static int hit_find(int px, int py) {
  for (int k = 0; k < lastHits; k++)
    if (px >= lastHit[k].x && px < lastHit[k].x + lastHit[k].w && py >= lastHit[k].y && py < lastHit[k].y + lastHit[k].h)
      return lastHit[k].i;
  return -1;
}
int quick_hit(int x, int y) { return hit_find(x, y); }
int quick_list_hit(int x, int y) { return hit_find(x, y); }
void quick_set_grid(bool grid) { gridMode = grid; }
static void grid_shape(int n, int w, int *cols, int *rows) {
  int fit = (w - 24 + 6) / (TILE_W + 6);
  if (fit < 1)
    fit = 1;
  if (fit > n)
    fit = n;
  *rows = (n + fit - 1) / fit;
  *cols = (n + *rows - 1) / *rows;
}
static void grid_place(int n, int i, int w, int h, int *x, int *y) {
  int cols, rows;
  grid_shape(n, w, &cols, &rows);
  int row = i / cols, count = n - row * cols;
  if (count > cols)
    count = cols;
  *x = (w - (count * TILE_W + (count - 1) * 6)) / 2 + (i % cols) * (TILE_W + 6);
  *y = (h - (rows * TILE_H + (rows - 1) * 6 + 52)) / 2 + row * (TILE_H + 6);
}

// Where tile i sits: round an ellipse that fits the canvas (a circle where it can be), the first at the top.
static void place(int n, int i, int w, int h, int *x, int *y) {
  if (gridMode) {
    grid_place(n, i, w, h, x, y);
    return;
  }
  double rx = (w - TILE_W) / 2.0 - 4, ry = (h - TILE_H) / 2.0 - 4;
  // Close round the middle's hint, not out at the screen's edges (QuickMenu.kt's ring); wide enough that
  // nine tiles don't touch.
  if (ry > 90)
    ry = 90;
  if (rx > ry * 1.6)
    rx = ry * 1.6;
  double a = -M_PI / 2 + 2 * M_PI * i / (n > 0 ? n : 1);
  *x = (int)lround(w / 2.0 + rx * cos(a)) - TILE_W / 2;
  *y = (int)lround(h / 2.0 + ry * sin(a)) - TILE_H / 2;
}

int quick_step(int n, int sel, int dx, int dy, int w, int h) {
  if (n <= 0)
    return 0;
  int sx, sy, best = sel, score = 1 << 30, cols = 1, rows;
  place(n, sel, w, h, &sx, &sy);
  if (gridMode)
    grid_shape(n, w, &cols, &rows);
  for (int i = 0; i < n; i++) {
    if (i == sel)
      continue;
    // GRID: left / right stay in the row, up / down go to the next one.
    if (gridMode && (dx ? i / cols != sel / cols : i / cols != sel / cols + (dy > 0 ? 1 : -1)))
      continue;
    int x, y;
    place(n, i, w, h, &x, &y);
    int along = (x - sx) * dx + (y - sy) * dy, across = dx ? abs(y - sy) : abs(x - sx);
    if (along > 0 && along + 2 * across < score)
      best = i, score = along + 2 * across;
  }
  return best;
}

bool quick_draw(struct pd_canvas *c, const struct pd_game *g, const struct quick_item *items, int n, int sel, int look) {
  if (c->w < 3 * TILE_W || c->h < 3 * TILE_H)
    return false;
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  const struct hud_ink *ink = hud_ink(look);
  const uint32_t text = ink->window ? PD_TITLE_TEXT : ink->text;
  const uint32_t shadow = ink->window ? PD_TITLE_SHADOW : ink->shadow;
  lastHits = 0;
  for (int i = 0; i < n; i++) {
    int x, y;
    place(n, i, c->w, c->h, &x, &y);
    hit_add(x, y, TILE_W, TILE_H, i);
    hud_tile(c, ink, x, y, TILE_W, TILE_H, i == sel);
    bool dim = items[i].why != NULL;
    // The game's own art where there is some (the Pokémon that would FLY, the bag's item), pixel art otherwise,
    // as QuickIcon draws them.
    bool art = false;
    if (items[i].action == QUICK_FLY)
      art = pd_draw_mon_icon(c, g, items[i].species, x + (TILE_W - PD_ICON) / 2, y - 3, dim);
    else if (items[i].item)
      art = pd_draw_item_icon(c, g, items[i].item, x + (TILE_W - PD_ITEM_ICON) / 2, y + 2, dim);
    if (!art)
      icon(c, items[i].action, x + TILE_W / 2, y + 14, 1, dim || items[i].off, ink);
    const char *label = items[i].label ? items[i].label : quick_label(items[i].action);
    int lw = pd_text_width(label);
    pd_text(c, x + (TILE_W - lw) / 2, y + TILE_H - 16, label, dim ? ink->muted : items[i].lit ? ink->accent : text, shadow);
    if (items[i].off) { // WILD switched off: an OFF tag on its corner
      int tw = pd_text_width("OFF") + 6;
      pd_round_rect(c, x + TILE_W - tw - 2, y + 2, tw, 13, 2, ink->accent);
      pd_text(c, x + TILE_W - tw + 1, y, "OFF", ink->onAccent, PD_NO_SHADOW);
    }
  }
  // The middle: the highlighted one's name, and why it's dimmed (or WILD's state).
  if (sel >= 0 && sel < n) {
    const struct quick_item *it = &items[sel];
    const char *name = it->action == QUICK_WILD ? "WILD ENCOUNTERS" : it->label ? it->label : quick_label(it->action);
    const char *note = it->why ? it->why : it->action == QUICK_WILD ? (it->off ? "OFF" : "ON")
                     : it->action == QUICK_FF ? (it->lit ? "ON" : "OFF") : NULL;
    int nw = pd_text_width(name), ow = note ? pd_text_width(note) : 0;
    int w = (nw > ow ? nw : ow) + 16, h = note ? 40 : 24;
    int x = (c->w - w) / 2, y = (c->h - h) / 2;
    if (gridMode) { // under the tiles
      int cols, rows;
      grid_shape(n, c->w, &cols, &rows);
      y = (c->h - (rows * TILE_H + (rows - 1) * 6 + 52)) / 2 + rows * (TILE_H + 6) + 6;
    }
    hud_panel(c, ink, x, y, w, h, 3);
    pd_text(c, x + (w - nw) / 2, y + 4, name, text, shadow);
    if (note)
      pd_text(c, x + (w - ow) / 2, y + 20, note, it->why ? ink->muted : ink->accent, shadow);
  }
  return true;
}

void quick_list_draw(struct pd_canvas *c, const char *title, const char *const *rows, int n, int sel, int look) {
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  enum { ROW = 16, PAD = 6 };
  int widest = pd_text_width(title);
  for (int i = 0; i < n; i++)
    if (pd_text_width(rows[i]) > widest)
      widest = pd_text_width(rows[i]);
  int w = widest + 4 * PAD + 6, maxRows = (c->h - 2 * PAD - ROW - 16) / ROW;
  if (w > c->w - 8)
    w = c->w - 8;
  int shown = n < maxRows ? n : maxRows;
  int top = sel - shown / 2;
  if (top > n - shown)
    top = n - shown;
  if (top < 0)
    top = 0;
  int h = PAD + ROW + 4 + shown * ROW + PAD, x = (c->w - w) / 2, y = (c->h - h) / 2;
  const struct hud_ink *ink = hud_ink(look);
  const uint32_t text = ink->window ? PD_TITLE_TEXT : ink->text;
  const uint32_t shadow = ink->window ? PD_TITLE_SHADOW : ink->shadow;
  hud_panel(c, ink, x, y, w, h, 3);
  pd_text(c, x + (w - pd_text_width(title)) / 2, y + PAD - 2, title, ink->accent, shadow);
  lastHits = 0;
  for (int r = 0; r < shown; r++) {
    int i = top + r, ry = y + PAD + ROW + 4 + r * ROW;
    hit_add(x + PAD, ry, w - 2 * PAD, ROW, i);
    if (i == sel)
      pd_round_rect(c, x + PAD, ry, w - 2 * PAD, ROW, 3, HUD_SELECTED);
    pd_text(c, x + 2 * PAD + 3, ry - 1, rows[i], i == sel ? 0xFFFFFFu : text, i == sel ? 0x00803010u : shadow);
  }
  // More above / below: a small arrow at the edge (Pixel Operator has no arrow glyphs).
  for (int k = 0; k < 3; k++) {
    if (top > 0)
      pd_fill(c, x + w / 2 - k, y + PAD + ROW + 1 - (2 - k), 2 * k + 1, 1, ink->muted);
    if (top + shown < n)
      pd_fill(c, x + w / 2 - k, y + h - 3 - k, 2 * k + 1, 1, ink->muted);
  }
}

void quick_finish(struct pd_canvas *c) {
  for (int i = 0; i < c->w * c->h; i++) {
    uint32_t p = c->px[i];
    c->px[i] = p == QUICK_CLEAR ? 0 : (p >> 24) ? p : (p | 0xFF000000u);
  }
}
