/* The QUICK MENU (quick_menu.h): tiles of one size round a circle, the first at the top, clockwise; the
 * highlighted one's name in the middle, with why it's dimmed. GPL-3.0, see ../NOTICE. */
#include "quick_menu.h"

#include "pd_icon.h"
#include "pd_style.h"
#include <math.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#define TILE_W 74
#define TILE_H 42
#define GLASS_FILL 0xB8121620u // FieldHud.kt's GLASS_FILL / _EDGE (ARGB)
#define GLASS_EDGE 0x66FFFFFFu
#define FOCUS 0x00FF7331u      // AppColors.focusRing
#define INK 0x00F8F8F8u
#define INK_SHADOW 0x00303840u
#define MUTED 0x00A8B0C0u
#define ACCENT 0x00E83838u

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
static const char *const REPEL[] = {
    "....KKK.....", "...KLLLK.K..", "...KKKKK..K.", "..KGGGGGK.K.", "..KGLGGGK...", "..KGLGGGK...",
    "..KGGGGGK...", "..KGGGGGK...", "..KGGGGGK...", "..KKKKKKK...", NULL,
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
  default: return "";
  }
}

static uint32_t icon_color(enum quick_action a, char ch) {
  if (a == QUICK_WILD)
    return ch == 'K' ? 0x183818 : ch == 'G' ? 0x389838 : 0x78D058;
  if (a == QUICK_PC)
    return ch == 'K' ? 0x202838 : ch == 'L' ? 0xD8D8E0 : ch == 'D' ? 0x9098A8 : ch == 'S' ? 0x3070C8
         : ch == 'H' ? 0x90C8F8 : 0x40D040;
  if (a == QUICK_REPEL)
    return ch == 'K' ? 0x202838 : ch == 'L' ? 0xE8ECF4 : 0x48A848;
  if (a == QUICK_SAVE_STATE || a == QUICK_LOAD_STATE)
    return ch == 'K' ? 0x202838 : ch == 'D' ? 0x4870C0 : ch == 'L' ? 0xE8ECF4 : 0xF8C030;
  return INK;
}

// An icon centred at (cx, cy), `scale` canvas pixels a pixel; dim: at half strength over the glass.
static void icon(struct pd_canvas *c, enum quick_action a, int cx, int cy, int scale, bool dim) {
  const char *const *rows = a == QUICK_PC ? quick_icon_pc : a == QUICK_WILD ? quick_icon_grass : a == QUICK_SAVE_STATE ? SAVE_STATE
                          : a == QUICK_LOAD_STATE ? LOAD_STATE : a == QUICK_FF ? FF : a == QUICK_REPEL ? REPEL
                          : a == QUICK_BIKE ? BIKE : quick_icon_companion;
  int h = 0, w = (int)strlen(rows[0]);
  while (rows[h])
    h++;
  int x0 = cx - w * scale / 2, y0 = cy - h * scale / 2;
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      if (rows[y][x] == '.')
        continue;
      uint32_t col = icon_color(a, rows[y][x]);
      if (dim) // blend toward the glass: 45% (QuickTile's blocked alpha)
        col = ((((col >> 16) & 255) * 45 + 0x12 * 55) / 100) << 16 | ((((col >> 8) & 255) * 45 + 0x16 * 55) / 100) << 8 |
              (((col & 255) * 45 + 0x20 * 55) / 100);
      pd_fill(c, x0 + x * scale, y0 + y * scale, scale, scale, col);
    }
}

static void glass(struct pd_canvas *c, int x, int y, int w, int h) {
  pd_round_rect(c, x, y, w, h, 4, GLASS_EDGE);
  pd_round_rect(c, x + 1, y + 1, w - 2, h - 2, 3, GLASS_FILL);
}

/* GRID (QuickMenuStyle.GRID; Keegan Whitacre's layout, pokedaisy-brick): the width fitted, rows balanced, the short
 * last row centred, room kept under the tiles for the selected action's name. */
static bool gridMode;
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

bool quick_draw(struct pd_canvas *c, const struct pd_game *g, const struct quick_item *items, int n, int sel) {
  if (c->w < 3 * TILE_W || c->h < 3 * TILE_H)
    return false;
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  for (int i = 0; i < n; i++) {
    int x, y;
    place(n, i, c->w, c->h, &x, &y);
    if (i == sel)
      pd_round_rect(c, x - 2, y - 2, TILE_W + 4, TILE_H + 4, 5, FOCUS);
    glass(c, x, y, TILE_W, TILE_H);
    bool dim = items[i].why != NULL;
    // The game's own art where there is some (the Pokémon that would FLY, the bag's item), pixel art otherwise,
    // as QuickIcon draws them.
    bool art = false;
    if (items[i].action == QUICK_FLY)
      art = pd_draw_mon_icon(c, g, items[i].species, x + (TILE_W - PD_ICON) / 2, y - 3, dim);
    else if (items[i].item)
      art = pd_draw_item_icon(c, g, items[i].item, x + (TILE_W - PD_ITEM_ICON) / 2, y + 2, dim);
    if (!art && items[i].action != QUICK_FLY)
      icon(c, items[i].action, x + TILE_W / 2, y + 14, 1, dim || items[i].off);
    const char *label = items[i].label ? items[i].label : quick_label(items[i].action);
    int lw = pd_text_width(label);
    pd_text(c, x + (TILE_W - lw) / 2, y + TILE_H - 16, label, dim ? MUTED : items[i].lit ? FOCUS : INK,
            INK_SHADOW);
    if (items[i].off) { // WILD switched off: an OFF tag on its corner
      int tw = pd_text_width("OFF") + 6;
      pd_round_rect(c, x + TILE_W - tw - 2, y + 2, tw, 13, 2, ACCENT);
      pd_text(c, x + TILE_W - tw + 1, y, "OFF", INK, PD_NO_SHADOW);
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
    glass(c, x, y, w, h);
    pd_text(c, x + (w - nw) / 2, y + 4, name, INK, INK_SHADOW);
    if (note)
      pd_text(c, x + (w - ow) / 2, y + 20, note, it->why ? MUTED : FOCUS, INK_SHADOW);
  }
  return true;
}

void quick_list_draw(struct pd_canvas *c, const char *title, const char *const *rows, int n, int sel) {
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
  glass(c, x, y, w, h);
  pd_text(c, x + (w - pd_text_width(title)) / 2, y + PAD - 2, title, FOCUS, INK_SHADOW);
  for (int r = 0; r < shown; r++) {
    int i = top + r, ry = y + PAD + ROW + 4 + r * ROW;
    if (i == sel)
      pd_round_rect(c, x + PAD, ry, w - 2 * PAD, ROW, 3, FOCUS);
    pd_text(c, x + 2 * PAD + 3, ry - 1, rows[i], INK, i == sel ? 0x00803010u : INK_SHADOW);
  }
  // More above / below: a small arrow at the edge (Pixel Operator has no arrow glyphs).
  for (int k = 0; k < 3; k++) {
    if (top > 0)
      pd_fill(c, x + w / 2 - k, y + PAD + ROW + 1 - (2 - k), 2 * k + 1, 1, MUTED);
    if (top + shown < n)
      pd_fill(c, x + w / 2 - k, y + h - 3 - k, 2 * k + 1, 1, MUTED);
  }
}

void quick_finish(struct pd_canvas *c) {
  for (int i = 0; i < c->w * c->h; i++) {
    uint32_t p = c->px[i];
    c->px[i] = p == QUICK_CLEAR ? 0 : (p >> 24) ? p : (p | 0xFF000000u);
  }
}
