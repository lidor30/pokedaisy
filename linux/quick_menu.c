/* The QUICK MENU (quick_menu.h): tiles of one size round a circle, the first at the top, clockwise; the
 * highlighted one's name in the middle, with why it's dimmed. GPL-3.0, see ../NOTICE. */
#include "quick_menu.h"
#include "hud_style.h"

#include "pd_icon.h"
#include "pd_style.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#define TILE_W 74 // GRID's tiles (and the most a ring tile may be)
#define TILE_H 46 // QuickMenu.kt: 8u above and under the icon and its name
#define TILE_GAP 10
#define RING_GAP 8       // between two ring tiles' facing edges (QuickMenu.kt's 10u, a little closer on 240 rows)
#define LABEL_LINE 11    // a label line: the capitals, their shadow and a row
#define ONE_LINE_MAX 50  // a ring label wider than this (with its 5 px each side) goes on two lines, at its space
// Windows, tiles and their colours are the HUD's (hud_style.h): its LOOK, GLASS by default.

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
  case QUICK_FLASH: return "FLASH";
  case QUICK_FLIGHT: return "TAKE OFF";
  case QUICK_LAND: return "LAND";
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

// FLASH (QuickMenu.kt's FLASH_ICON): a burst of light, K outline, Y its glow, W the white middle.
static const char *const FLASH[] = {
    ".....KK.....", ".K...KK...K.", "..K.KYYK.K..", "...KYWWYK...", "..KYWWWWYK..", "KKYWWWWWWYKK",
    "KKYWWWWWWYKK", "..KYWWWWYK..", "...KYWWYK...", "..K.KYYK.K..", ".K...KK...K.", ".....KK.....", NULL,
};

// FREE FLIGHT (QuickMenu.kt's TAKE_OFF_ICON / LAND_ICON): a pair of wings, the arrow over them going up or under them
// going down; K outline, B the wings, W their light, G the arrow.
static const char *const TAKE_OFF[] = {
    ".....GG.....", "....GGGG....", "...G.GG.G...", ".....GG.....", "K....KK....K", "KB..KBBK..BK",
    "KBBKBBBBKBBK", "KBWBBBBBBWBK", ".KBWBBBBWBK.", "..KBBKKBBK..", "...KK..KK...", "............", NULL,
};
static const char *const LAND[] = {
    "K....KK....K", "KB..KBBK..BK", "KBBKBBBBKBBK", "KBWBBBBBBWBK", ".KBWBBBBWBK.", "..KBBKKBBK..",
    "...KK..KK...", "............", ".....GG.....", "...G.GG.G...", "....GGGG....", ".....GG.....", NULL,
};

static uint32_t icon_color(enum quick_action a, char ch, uint32_t text) {
  if (a == QUICK_FLIGHT || a == QUICK_LAND)
    return ch == 'K' ? 0x202850 : ch == 'B' ? 0x5068C8 : ch == 'W' ? 0xB8C8F8 : 0x58C858;
  if (a == QUICK_FLASH)
    return ch == 'K' ? 0x584818 : ch == 'Y' ? 0xF8D030 : 0xFFFFF0;
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

// An icon centred at (cx, cy), `scale` canvas pixels a pixel; dim: at 45% over the tile's face (QuickTile's blocked alpha).
static void icon(struct pd_canvas *c, enum quick_action a, int cx, int cy, int scale, bool dim, const struct hud_ink *ink) {
  const char *const *rows = a == QUICK_PC ? quick_icon_pc : a == QUICK_WILD ? quick_icon_grass : a == QUICK_SAVE_STATE ? SAVE_STATE
                          : a == QUICK_LOAD_STATE ? LOAD_STATE : a == QUICK_FF ? FF : a == QUICK_REPEL ? quick_icon_repel
                          : a == QUICK_BIKE ? BIKE : a == QUICK_FLY ? WING : a == QUICK_FLASH ? FLASH
                          : a == QUICK_FLIGHT ? TAKE_OFF : a == QUICK_LAND ? LAND
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
      if (dim) {
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
  int fit = (w - 24 + TILE_GAP) / (TILE_W + TILE_GAP);
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
  *x = (w - (count * TILE_W + (count - 1) * TILE_GAP)) / 2 + (i % cols) * (TILE_W + TILE_GAP);
  *y = (h - (rows * TILE_H + (rows - 1) * TILE_GAP + 52)) / 2 + row * (TILE_H + TILE_GAP);
}

// The ring's tiles, all one size (UniformTiles): the widest label line's, set by quick_draw.
static int ringW = TILE_W, ringH = TILE_H, ringLines = 1;

// QuickMenu.kt's ringPlaces: the tiles round a circle, the first at the top, clockwise, each the same gap from the last
// (rectangles at equal angles meet corner to corner closer than side to side); too tall for the canvas, an oval.
struct ring {
  int n, w, h, limW, limH;
  double px[QUICK_COUNT], py[QUICK_COUNT]; // each tile's centre from the ring's
};
static struct ring ring = {.n = -1};
static double rw, rh; // the tiles' size, for the helpers below

static double between(double rx, double ry, double a, double b) {
  double dx = fabs(rx * sin(b) - rx * sin(a)) - rw, dy = fabs(-ry * cos(b) + ry * cos(a)) - rh;
  return dx > dy ? dx : dy;
}
// The right half's angles, one gap [g] after another from the top.
static void walk(int n, double rx, double ry, double g, double *t) {
  int half = n / 2;
  t[0] = 0;
  for (int i = 1; i <= half; i++) {
    double lo = t[i - 1], hi = t[i - 1] + M_PI;
    for (int k = 0; k < 40; k++) {
      double mid = (lo + hi) / 2;
      if (between(rx, ry, t[i - 1], mid) < g)
        lo = mid;
      else
        hi = mid;
    }
    t[i] = hi;
  }
}
// The halves run past each other (true = a bigger ring, or a smaller gap).
static bool overrun(int n, double rx, double ry, double g) {
  double t[QUICK_COUNT / 2 + 2];
  walk(n, rx, ry, g, t);
  int half = n / 2;
  return n % 2 == 0 ? t[half] > M_PI : t[half] >= M_PI || between(rx, ry, t[half], -t[half]) < g;
}
enum grow { GROW_RADIUS, GROW_GAP, GROW_WIDTH };
static double bisect(double lo, double hi, enum grow what, int n, double a, double b) {
  for (int k = 0; k < 40; k++) {
    double mid = (lo + hi) / 2;
    bool grow = what == GROW_RADIUS ? overrun(n, mid, mid, a) : what == GROW_GAP ? !overrun(n, a, a, mid)
                                                                : overrun(n, b * mid, b, a);
    if (grow)
      lo = mid;
    else
      hi = mid;
  }
  return hi;
}
static void ring_places(int n, int w, int h, int limW, int limH) {
  if (ring.n == n && ring.w == w && ring.h == h && ring.limW == limW && ring.limH == limH)
    return;
  ring = (struct ring){.n = n, .w = w, .h = h, .limW = limW, .limH = limH};
  if (n <= 0)
    return;
  rw = w;
  rh = h;
  double big = w > h ? w : h, minR = big * 1.6, g = RING_GAP;
  double ry = n == 1 ? minR : bisect(big / 2, (w + h + RING_GAP) * (double)n, GROW_RADIUS, n, RING_GAP, 0);
  if (ry < minR) {
    // Held at the size the hint needs: the gaps grow (all alike) until the halves meet.
    ry = minR;
    if (n > 1)
      g = bisect(RING_GAP, 2 * M_PI * minR, GROW_GAP, n, minR, 0);
  }
  double rx = ry;
  if (2 * ry + h > limH) {
    // Too tall: as tall as the canvas allows, the oval as wide as it then needs.
    ry = (limH - h) / 2.0 > h ? (limH - h) / 2.0 : h;
    double wide = ry * bisect(1, 8, GROW_WIDTH, n, g, ry), most = (limW - w) / 2.0 > ry ? (limW - w) / 2.0 : ry;
    rx = wide < most ? wide : most;
  }
  double t[QUICK_COUNT / 2 + 2];
  walk(n, rx, ry, g, t);
  int half = n / 2;
  double squeeze = n % 2 == 0 && t[half] > M_PI ? M_PI / t[half] : 1;
  for (int i = 0; i < n; i++) {
    double turn = i <= half ? t[i] * squeeze : 2 * M_PI - t[n - i] * squeeze;
    ring.px[i] = rx * sin(turn);
    ring.py[i] = -ry * cos(turn);
  }
}

// Where tile i sits (its top left).
static void place(int n, int i, int w, int h, int *x, int *y) {
  if (gridMode) {
    grid_place(n, i, w, h, x, y);
    return;
  }
  ring_places(n, ringW, ringH, w * 96 / 100, h * 96 / 100);
  *x = (int)lround(w / 2.0 + ring.px[i] - ringW / 2.0);
  *y = (int)lround(h / 2.0 + ring.py[i] - ringH / 2.0);
}

// A ring label's lines: on two at its space when it's wider than ONE_LINE_MAX; their count.
static int label_lines(const char *label, char a[32], char b[32]) {
  snprintf(a, 32, "%s", label);
  b[0] = 0;
  const char *space = strchr(label, ' ');
  if (gridMode || !space || pd_text_width(label) + 10 <= ONE_LINE_MAX)
    return 1;
  // At the space nearest the middle.
  const char *best = space;
  int len = (int)strlen(label);
  for (const char *p = space; p; p = strchr(p + 1, ' '))
    if (abs((int)(p - label) * 2 - len) < abs((int)(best - label) * 2 - len))
      best = p;
  snprintf(a, 32, "%.*s", (int)(best - label), label);
  snprintf(b, 32, "%s", best + 1);
  return 2;
}

// The ring's tile size for these items: the widest label line with 5 px each side, at least 38 (QuickTile's).
static void measure(const struct quick_item *items, int n) {
  int w = 38, lines = 1;
  for (int i = 0; i < n; i++) {
    const char *label = items[i].label ? items[i].label : quick_label(items[i].action);
    char a[32], b[32];
    int k = label_lines(label, a, b);
    if (k > lines)
      lines = k;
    int lw = pd_text_width(a) > pd_text_width(b) ? pd_text_width(a) : pd_text_width(b);
    if (lw + 10 > w)
      w = lw + 10;
  }
  ringW = gridMode ? TILE_W : w > TILE_W ? TILE_W : w;
  ringH = gridMode ? TILE_H : 24 + lines * LABEL_LINE + 3;
  ringLines = gridMode ? 1 : lines;
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
  measure(items, n);
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  lastHits = 0;
  const int TW = ringW, TH = ringH;
  const struct hud_ink *ink = hud_ink(look);
  const uint32_t text = ink->window ? PD_TITLE_TEXT : ink->text;
  const uint32_t shadow = ink->window ? PD_TITLE_SHADOW : ink->shadow;
  for (int i = 0; i < n; i++) {
    int x, y;
    place(n, i, c->w, c->h, &x, &y);
    hit_add(x, y, TW, TH, i);
    hud_tile(c, ink, x, y, TW, TH, i == sel);
    bool dim = items[i].why != NULL;
    // The game's own art where there is some (the Pokémon that would FLY, the bag's item), pixel art otherwise,
    // as QuickIcon draws them.
    bool art = false;
    // The icon's middle: GRID's tiles as before; a ring tile's 8 px down from its top edge's padding.
    int iconY = gridMode ? y + 16 : y + 14;
    if (items[i].action == QUICK_FLY)
      art = pd_draw_mon_icon(c, g, items[i].species, x + (TW - PD_ICON) / 2, iconY - 17, dim);
    else if (items[i].item)
      art = pd_draw_item_icon(c, g, items[i].item, x + (TW - PD_ITEM_ICON) / 2, iconY - 12, dim);
    if (!art)
      icon(c, items[i].action, x + TW / 2, iconY, 1, dim || items[i].off, ink);
    const char *label = items[i].label ? items[i].label : quick_label(items[i].action);
    char la[32], lb[32];
    int lines = label_lines(label, la, lb);
    uint32_t col = dim ? ink->muted : items[i].lit ? ink->accent : text;
    // The label's lines at the tile's bottom (a one-line label on a two-line ring sits on the lower line's place).
    int ly = gridMode ? y + TH - 16 : y + TH - 3 - lines * LABEL_LINE - 4;
    pd_text(c, x + (TW - pd_text_width(la)) / 2, ly, la, col, shadow);
    if (lines > 1)
      pd_text(c, x + (TW - pd_text_width(lb)) / 2, ly + LABEL_LINE, lb, col, shadow);
    if (items[i].off) { // WILD switched off: an OFF tag on its corner
      int tw = pd_text_width("OFF") + 6;
      pd_round_rect(c, x + TW - tw - 2, y + 2, tw, 13, 2, ink->accent);
      pd_text(c, x + TW - tw + 1, y, "OFF", ink->onAccent, PD_NO_SHADOW);
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
      y = (c->h - (rows * TILE_H + (rows - 1) * TILE_GAP + 52)) / 2 + rows * (TILE_H + TILE_GAP) + 6;
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
