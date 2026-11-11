/* The HUD (hud.h): FieldHud.kt's layouts in C. CORNERS - the minimap top right (never top left, where the games slide
 * a new area's name in), the party along the bottom left, REPEL STEPS / the balls / the buttons bottom right;
 * CORNERS 2 the right side the other way up; RAILS two columns at the sides; DOCK one bar along the bottom; the
 * compact HUD (no party, no minimap: a MAP button) where the party doesn't fit beside the buttons. GPL-3.0. */
#include "hud.h"

#include "pd_icon.h"
#include "pd_map.h"
#include "pd_style.h"
#include "pd_tables.h"
#include "quick_menu.h"
#include <stdio.h>
#include <string.h>

// FieldHud.kt's looks: GLASS_INK / WHITE_INK (ARGB where see-through), WINDOW = the OPTION title window's colours.
struct look {
  uint32_t fill, edge, text, shadow, accent, muted, cellOn;
  bool window;
};
static const struct look LOOKS[PD_HUD_LOOKS] = {
    {0xA0121620u, 0x66FFFFFFu, 0xF4F6FA, 0x0C0F15, 0xFFCF5A, 0xB2BACB, 0x59FFFFFFu, false},
    {0xACF6F7FAu, 0x59000000u, 0x262B34, 0xD9DCE3, 0xC8321E, 0x6A7282, 0x4D000000u, false},
    {0xFFFFFF, 0x8C8C94, 0x575656, 0xBCBBB4, 0xCB0707, 0x8C8C94, 0xFFE9A8, true}, // the FireRed window look, whatever the game
};

// The sizes: FieldHud's, or a 3DS's (hud_state.compact: its screen shows the game at 1.5x, a Linux handheld's at 3x and
// more, so the same pixels cover twice as much of the picture). A compact tile overlaps the icon's see-through edges.
struct hud_sizes { int pad, edge, tile, gap, mapW, mapH; };
static const struct hud_sizes NORMAL_SIZES = { 4, 6, 30, 3, 104, 64 }, COMPACT_SIZES = { 2, 3, 20, 1, 72, 40 };
static const struct hud_sizes *sizes = &NORMAL_SIZES;
static bool small; // hud_state.compact (not CompactLayout, the HUD without party or minimap: compact() below)
#define PAD (sizes->pad)
#define EDGE (sizes->edge) // CornersLayout's padding
#define GAP 4  // between a column's windows
#define TILE (sizes->tile)
#define TILE_GAP (sizes->gap)
#define LINE 12
#define MON_ROW_H 30 // RAILS' party rows
#define MON_ROW_W (TILE + 4 + 44)
#define BUTTON_H (3 + 16 + 1 + LINE + 3)
#define RAIL_MIN 70 // a rail's least width (FieldHud's u * 50; COMPANION's button in this font)
// The battle HP box's tail (HUD_SHADOW, the user's pick of five looks): every window's shadow this far down and right.
#define SHADOW (small ? 2 : 3)
// The party screen's orange (HUD_SELECTED): the picked one.
#define HUD_SELECTED 0xF07038

struct hud {
  struct pd_canvas *c;
  int x0, x1;           // the columns it keeps to (hud_state.left / right: the game's picture)
  const struct pd_game *g;
  const struct pd_snapshot *s;
  const struct hud_state *h;
  const struct pd_settings *st;
  const struct look *L;
  struct hud_hits *out;
  hud_mon_draw_fn monDraw;
  hud_item_draw_fn itemDraw;
  int repelItem;
  void *monCtx;
  int partyN;           // party members shown (0: PARTY off)
  struct pd_item ball[3];
  int balls;            // ball kinds shown, most first
  bool repel, map;      // REPEL STEPS showing; MINIMAP on and something to show
  int button[5], buttons; // the buttons shown, in order (enum hud_target)
};

// Compact: a 3DS shows this canvas pixel for pixel and the icons come no smaller, so they go through Scale2x (edges stay
// clean, no pixel simply dropped) and then down to 7/8 of their size, each pixel what it covers. Dropping pixels broke the
// shapes, and the same at 1x blurred some columns and not others.
#define SHRUNK_CLEAR 0xFE000000u
#define SMALL_ICON (PD_ICON * 7 / 8)
static uint32_t iconPx[PD_ICON * PD_ICON], icon2x[4 * PD_ICON * PD_ICON];

static struct pd_canvas icon_begin(void) {
  struct pd_canvas s;
  pd_canvas_init(&s, PD_ICON, PD_ICON, iconPx);
  for (int i = 0; i < PD_ICON * PD_ICON; i++)
    iconPx[i] = SHRUNK_CLEAR;
  return s;
}

static void icon_scale2x(void) {
  const int n = PD_ICON;
  for (int y = 0; y < n; y++)
    for (int x = 0; x < n; x++) {
      uint32_t e = iconPx[y * n + x];
      uint32_t b = iconPx[(y ? y - 1 : y) * n + x], h = iconPx[(y < n - 1 ? y + 1 : y) * n + x];
      uint32_t d = iconPx[y * n + (x ? x - 1 : x)], f = iconPx[y * n + (x < n - 1 ? x + 1 : x)];
      bool edge = b != h && d != f;
      uint32_t *o = &icon2x[2 * y * 2 * n + 2 * x];
      o[0] = edge && d == b ? d : e;
      o[1] = edge && b == f ? f : e;
      o[2 * n] = edge && d == h ? d : e;
      o[2 * n + 1] = edge && h == f ? f : e;
    }
}

// Over what's there: 7/16 of the 2x picture.
static void icon_draw(struct pd_canvas *c, int x, int y) {
  const int n = 2 * PD_ICON, total = 16 * 16;
  icon_scale2x();
  for (int oy = 0; oy < PD_ICON * 7 / 8; oy++)
    for (int ox = 0; ox < PD_ICON * 7 / 8; ox++) {
      int X = x + ox, Y = y + oy;
      if (X < c->cx0 || X >= c->cx1 || Y < c->cy0 || Y >= c->cy1)
        continue;
      // In sevenths of a source pixel, this pixel covers [16 * o, 16 * o + 16) each way.
      int r = 0, g = 0, b = 0, cover = 0;
      for (int sy = oy * 16 / 7; sy <= (oy * 16 + 15) / 7 && sy < n; sy++) {
        int y0 = oy * 16 > sy * 7 ? oy * 16 : sy * 7, y1 = oy * 16 + 16 < sy * 7 + 7 ? oy * 16 + 16 : sy * 7 + 7;
        for (int sx = ox * 16 / 7; sx <= (ox * 16 + 15) / 7 && sx < n; sx++) {
          int x0 = ox * 16 > sx * 7 ? ox * 16 : sx * 7, x1 = ox * 16 + 16 < sx * 7 + 7 ? ox * 16 + 16 : sx * 7 + 7;
          int part = (y1 - y0) * (x1 - x0);
          uint32_t p = icon2x[sy * n + sx];
          if (part <= 0 || p == SHRUNK_CLEAR)
            continue;
          r += part * (int)(p >> 16 & 0xFF);
          g += part * (int)(p >> 8 & 0xFF);
          b += part * (int)(p & 0xFF);
          cover += part;
        }
      }
      if (cover * 4 < total) // a sliver of the outline: left out, or the panel shows a halo round the Pokémon
        continue;
      uint32_t *d = &c->px[Y * c->w + X];
      bool clear = *d == QUICK_CLEAR;
      int rest = total - cover, under = clear ? 0 : *d >> 24 ? (int)(*d >> 24) : 255;
      int dr = clear ? 0 : (int)(*d >> 16 & 0xFF), dg = clear ? 0 : (int)(*d >> 8 & 0xFF), db = clear ? 0 : (int)(*d & 0xFF);
      int wUnder = rest * under / 255, sum = cover + wUnder;
      int alpha = 255 - (255 - under) * rest / total;
      uint32_t rgb = (uint32_t)((r + dr * wUnder) / sum) << 16 | (uint32_t)((g + dg * wUnder) / sum) << 8 | (uint32_t)((b + db * wUnder) / sum);
      *d = (alpha >= 255 ? 0u : (uint32_t)(alpha ? alpha : 1) << 24) | rgb;
    }
}

// The place's name: a font of the size it wants, whole pixels, capitals 8 tall (our own drawing; the app's Pixel Operator
// is 9 and a line of 12, too big for the minimap). Anything else in a name falls back to that font.
struct small_glyph {
  unsigned char ch, w;
  unsigned char rows[10]; // top bit first
};
static const struct small_glyph SMALL_FONT[] = {
    { ' ', 3, { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 } },
    { '!', 1, { 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00, 0x80, 0x00, 0x00 } },
    { '&', 5, { 0x60, 0x90, 0x90, 0x60, 0xA8, 0x90, 0x90, 0x68, 0x00, 0x00 } },
    { '\'', 1, { 0x80, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 } },
    { '(', 2, { 0x40, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x40, 0x00, 0x00 } },
    { ')', 2, { 0x80, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x80, 0x00, 0x00 } },
    { ',', 2, { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x40, 0x80 } },
    { '-', 3, { 0x00, 0x00, 0x00, 0x00, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00 } },
    { '.', 1, { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00 } },
    { '/', 4, { 0x10, 0x10, 0x20, 0x20, 0x40, 0x40, 0x80, 0x80, 0x00, 0x00 } },
    { '0', 5, { 0x70, 0x88, 0x98, 0xA8, 0xA8, 0xC8, 0x88, 0x70, 0x00, 0x00 } },
    { '1', 3, { 0x40, 0xC0, 0x40, 0x40, 0x40, 0x40, 0x40, 0xE0, 0x00, 0x00 } },
    { '2', 5, { 0x70, 0x88, 0x08, 0x10, 0x20, 0x40, 0x80, 0xF8, 0x00, 0x00 } },
    { '3', 5, { 0x70, 0x88, 0x08, 0x30, 0x08, 0x08, 0x88, 0x70, 0x00, 0x00 } },
    { '4', 5, { 0x10, 0x30, 0x50, 0x90, 0x90, 0xF8, 0x10, 0x10, 0x00, 0x00 } },
    { '5', 5, { 0xF8, 0x80, 0x80, 0xF0, 0x08, 0x08, 0x88, 0x70, 0x00, 0x00 } },
    { '6', 5, { 0x70, 0x80, 0x80, 0xF0, 0x88, 0x88, 0x88, 0x70, 0x00, 0x00 } },
    { '7', 5, { 0xF8, 0x08, 0x10, 0x10, 0x20, 0x20, 0x20, 0x20, 0x00, 0x00 } },
    { '8', 5, { 0x70, 0x88, 0x88, 0x70, 0x88, 0x88, 0x88, 0x70, 0x00, 0x00 } },
    { '9', 5, { 0x70, 0x88, 0x88, 0x88, 0x78, 0x08, 0x08, 0x70, 0x00, 0x00 } },
    { ':', 1, { 0x00, 0x00, 0x80, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00 } },
    { '?', 5, { 0x70, 0x88, 0x08, 0x10, 0x20, 0x20, 0x00, 0x20, 0x00, 0x00 } },
    { 'A', 5, { 0x70, 0x88, 0x88, 0x88, 0xF8, 0x88, 0x88, 0x88, 0x00, 0x00 } },
    { 'B', 5, { 0xF0, 0x88, 0x88, 0xF0, 0x88, 0x88, 0x88, 0xF0, 0x00, 0x00 } },
    { 'C', 5, { 0x70, 0x88, 0x80, 0x80, 0x80, 0x80, 0x88, 0x70, 0x00, 0x00 } },
    { 'D', 5, { 0xF0, 0x88, 0x88, 0x88, 0x88, 0x88, 0x88, 0xF0, 0x00, 0x00 } },
    { 'E', 5, { 0xF8, 0x80, 0x80, 0xF0, 0x80, 0x80, 0x80, 0xF8, 0x00, 0x00 } },
    { 'F', 5, { 0xF8, 0x80, 0x80, 0xF0, 0x80, 0x80, 0x80, 0x80, 0x00, 0x00 } },
    { 'G', 5, { 0x70, 0x88, 0x80, 0x80, 0xB8, 0x88, 0x88, 0x70, 0x00, 0x00 } },
    { 'H', 5, { 0x88, 0x88, 0x88, 0xF8, 0x88, 0x88, 0x88, 0x88, 0x00, 0x00 } },
    { 'I', 3, { 0xE0, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0xE0, 0x00, 0x00 } },
    { 'J', 5, { 0x38, 0x10, 0x10, 0x10, 0x10, 0x90, 0x90, 0x60, 0x00, 0x00 } },
    { 'K', 5, { 0x88, 0x90, 0xA0, 0xC0, 0xC0, 0xA0, 0x90, 0x88, 0x00, 0x00 } },
    { 'L', 5, { 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0xF8, 0x00, 0x00 } },
    { 'M', 5, { 0x88, 0xD8, 0xA8, 0xA8, 0x88, 0x88, 0x88, 0x88, 0x00, 0x00 } },
    { 'N', 5, { 0x88, 0xC8, 0xC8, 0xA8, 0xA8, 0x98, 0x98, 0x88, 0x00, 0x00 } },
    { 'O', 5, { 0x70, 0x88, 0x88, 0x88, 0x88, 0x88, 0x88, 0x70, 0x00, 0x00 } },
    { 'P', 5, { 0xF0, 0x88, 0x88, 0x88, 0xF0, 0x80, 0x80, 0x80, 0x00, 0x00 } },
    { 'Q', 5, { 0x70, 0x88, 0x88, 0x88, 0x88, 0xA8, 0x90, 0x68, 0x00, 0x00 } },
    { 'R', 5, { 0xF0, 0x88, 0x88, 0x88, 0xF0, 0xA0, 0x90, 0x88, 0x00, 0x00 } },
    { 'S', 5, { 0x70, 0x88, 0x80, 0x70, 0x08, 0x08, 0x88, 0x70, 0x00, 0x00 } },
    { 'T', 5, { 0xF8, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x00, 0x00 } },
    { 'U', 5, { 0x88, 0x88, 0x88, 0x88, 0x88, 0x88, 0x88, 0x70, 0x00, 0x00 } },
    { 'V', 5, { 0x88, 0x88, 0x88, 0x88, 0x88, 0x50, 0x50, 0x20, 0x00, 0x00 } },
    { 'W', 5, { 0x88, 0x88, 0x88, 0x88, 0xA8, 0xA8, 0xD8, 0x88, 0x00, 0x00 } },
    { 'X', 5, { 0x88, 0x88, 0x50, 0x20, 0x20, 0x50, 0x88, 0x88, 0x00, 0x00 } },
    { 'Y', 5, { 0x88, 0x88, 0x50, 0x20, 0x20, 0x20, 0x20, 0x20, 0x00, 0x00 } },
    { 'Z', 5, { 0xF8, 0x08, 0x10, 0x20, 0x40, 0x80, 0x80, 0xF8, 0x00, 0x00 } },
    { 'a', 5, { 0x00, 0x00, 0x70, 0x08, 0x78, 0x88, 0x88, 0x78, 0x00, 0x00 } },
    { 'b', 5, { 0x80, 0x80, 0xF0, 0x88, 0x88, 0x88, 0x88, 0xF0, 0x00, 0x00 } },
    { 'c', 4, { 0x00, 0x00, 0x70, 0x80, 0x80, 0x80, 0x80, 0x70, 0x00, 0x00 } },
    { 'd', 5, { 0x08, 0x08, 0x78, 0x88, 0x88, 0x88, 0x88, 0x78, 0x00, 0x00 } },
    { 'e', 5, { 0x00, 0x00, 0x70, 0x88, 0xF8, 0x80, 0x88, 0x70, 0x00, 0x00 } },
    { 'f', 4, { 0x30, 0x40, 0xE0, 0x40, 0x40, 0x40, 0x40, 0x40, 0x00, 0x00 } },
    { 'g', 5, { 0x00, 0x00, 0x78, 0x88, 0x88, 0x88, 0x78, 0x08, 0x08, 0x70 } },
    { 'h', 5, { 0x80, 0x80, 0xF0, 0x88, 0x88, 0x88, 0x88, 0x88, 0x00, 0x00 } },
    { 'i', 1, { 0x80, 0x00, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00, 0x00 } },
    { 'j', 3, { 0x20, 0x00, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0xA0, 0x40 } },
    { 'k', 4, { 0x80, 0x80, 0x90, 0xA0, 0xC0, 0xC0, 0xA0, 0x90, 0x00, 0x00 } },
    { 'l', 2, { 0xC0, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x00, 0x00 } },
    { 'm', 5, { 0x00, 0x00, 0xF0, 0xA8, 0xA8, 0xA8, 0xA8, 0xA8, 0x00, 0x00 } },
    { 'n', 5, { 0x00, 0x00, 0xF0, 0x88, 0x88, 0x88, 0x88, 0x88, 0x00, 0x00 } },
    { 'o', 5, { 0x00, 0x00, 0x70, 0x88, 0x88, 0x88, 0x88, 0x70, 0x00, 0x00 } },
    { 'p', 5, { 0x00, 0x00, 0xF0, 0x88, 0x88, 0x88, 0x88, 0xF0, 0x80, 0x80 } },
    { 'q', 5, { 0x00, 0x00, 0x78, 0x88, 0x88, 0x88, 0x88, 0x78, 0x08, 0x08 } },
    { 'r', 3, { 0x00, 0x00, 0xA0, 0xC0, 0x80, 0x80, 0x80, 0x80, 0x00, 0x00 } },
    { 's', 4, { 0x00, 0x00, 0x70, 0x80, 0x60, 0x10, 0x10, 0xE0, 0x00, 0x00 } },
    { 't', 4, { 0x40, 0x40, 0xE0, 0x40, 0x40, 0x40, 0x40, 0x30, 0x00, 0x00 } },
    { 'u', 5, { 0x00, 0x00, 0x88, 0x88, 0x88, 0x88, 0x88, 0x78, 0x00, 0x00 } },
    { 'v', 5, { 0x00, 0x00, 0x88, 0x88, 0x88, 0x50, 0x50, 0x20, 0x00, 0x00 } },
    { 'w', 5, { 0x00, 0x00, 0x88, 0x88, 0xA8, 0xA8, 0xA8, 0x50, 0x00, 0x00 } },
    { 'x', 5, { 0x00, 0x00, 0x88, 0x50, 0x20, 0x20, 0x50, 0x88, 0x00, 0x00 } },
    { 'y', 5, { 0x00, 0x00, 0x88, 0x88, 0x88, 0x88, 0x78, 0x08, 0x08, 0x70 } },
    { 'z', 5, { 0x00, 0x00, 0xF8, 0x10, 0x20, 0x40, 0x80, 0xF8, 0x00, 0x00 } },
    { 0xE9, 5, { 0x20, 0x40, 0x70, 0x88, 0xF8, 0x80, 0x88, 0x70, 0x00, 0x00 } },
};

static const struct small_glyph *small_glyph(unsigned char ch) {
  for (size_t i = 0; i < sizeof(SMALL_FONT) / sizeof(SMALL_FONT[0]); i++)
    if (SMALL_FONT[i].ch == ch)
      return &SMALL_FONT[i];
  return NULL;
}

// Latin-1's letters we drew arrive as UTF-8; false if any other character is in the text.
static bool small_text(struct pd_canvas *c, int x, int y, const char *text, uint32_t color, bool measure) {
  for (const unsigned char *p = (const unsigned char *)text; *p; p++) {
    unsigned char ch = *p;
    if (ch == 0xC3 && p[1] == 0xA9) {
      ch = 0xE9;
      p++;
    }
    const struct small_glyph *g = small_glyph(ch);
    if (!g)
      return false;
    if (!measure)
      for (int j = 0; j < 10; j++)
        for (int i = 0; i < g->w; i++)
          if (g->rows[j] & 0x80 >> i)
            pd_fill(c, x + i, y + j, 1, 1, color);
    x += g->w + 1;
  }
  return true;
}

static bool shows(const struct hud *u, int part) { return !(u->st->hudHidden >> part & 1); }

static void hit(struct hud *u, int x, int y, int w, int h, int id) {
  if (u->out && u->out->n < HUD_MAX_HITS)
    u->out->at[u->out->n++] = (__typeof__(u->out->at[0])){x, y, w, h, id};
}

void hud_focus_draw(struct pd_canvas *c, const struct pd_settings *st, const struct hud_hits *out, int focus) {
  if (focus < 0) return;
  // The party screen's orange (HUD_SELECTED), as the HUD's picked one.
  (void) st;
  const uint32_t accent = HUD_SELECTED;
  for (int i = 0; out && i < out->n; i++)
    if (out->at[i].id == focus) {
      int x = out->at[i].x - 3, y = out->at[i].y - 3, w = out->at[i].w + 6, hh = out->at[i].h + 6;
      pd_fill(c, x, y, w, 2, accent);
      pd_fill(c, x, y + hh - 2, w, 2, accent);
      pd_fill(c, x, y, 2, hh, accent);
      pd_fill(c, x + w - 2, y, 2, hh, accent);
    }
}

int hud_first(const struct hud_hits *hh) {
  if (!hh->n) return -1;
  int first = hh->at[0].id;
  for (int i = 0, lowest = PD_PARTY_SIZE; i < hh->n; ++i) {
    const int slot = hh->at[i].id - HUD_SLOT;
    if (slot >= 0 && slot < lowest) {
      lowest = slot;
      first = hh->at[i].id;
    }
  }
  return first;
}

/* The HUD hotkey's cursor: the target to the [direction] of [cur] among where the HUD put them (hud_hits, in HUD
 * canvas pixels) - what lines up on the other axis first, as focus_move does. Keep the current target if there is none in that direction. */
int hud_step(const struct hud_hits *hh, int cur, int horizontal, int vertical) {
  int from = -1, best = -1, score = 1 << 30;
  for (int i = 0; i < hh->n; i++)
    if (hh->at[i].id == cur)
      from = i;
  if (from < 0)
    return hh->n ? hh->at[0].id : -1;
  const typeof(hh->at[0]) *h = &hh->at[from];
  for (int i = 0; i < hh->n; i++) {
    if (i == from)
      continue;
    const typeof(hh->at[0]) *t = &hh->at[i];
    int dx = (t->x + t->w / 2) - (h->x + h->w / 2), dy = (t->y + t->h / 2) - (h->y + h->h / 2);
    int along = horizontal ? horizontal * dx : vertical * dy;
    int across = horizontal
                     ? (t->y + t->h <= h->y ? h->y - (t->y + t->h) : t->y >= h->y + h->h ? t->y - (h->y + h->h) : 0)
                     : (t->x + t->w <= h->x ? h->x - (t->x + t->w) : t->x >= h->x + h->w ? t->x - (h->x + h->w) : 0);
    int cost = along + across * 4 + (across > 0 ? 100000 : 0);
    if (along > 0 && cost < score)
      best = i, score = cost;
  }
  return best >= 0 ? hh->at[best].id : cur;
}

// A HUD window shaped like the games' battle HP box (hudPanel): its shadow down and right, then the look's window - the
// OPTION title window, or a see-through panel with a pixel edge.
static void panel(struct hud *u, int x, int y, int w, int h) {
  uint32_t shade = u->L->window ? pd_cur->title[0].color : u->L == &LOOKS[0] ? 0x80060810u : 0x4C141820u;
  pd_round_rect(u->c, x + SHADOW, y + SHADOW, w, h, 4, shade);
  if (u->L->window) {
    pd_title_box(u->c, x, y, w, h, PD_TITLE_FILL);
    return;
  }
  pd_round_rect(u->c, x, y, w, h, 4, u->L->edge);
  pd_round_rect(u->c, x + 1, y + 1, w - 2, h - 2, 3, u->L->fill);
}

static int text(struct hud *u, int x, int y, const char *t, uint32_t color) {
  return pd_text(u->c, x, y, t, color, u->L->shadow);
}

// Pixel art, a character a pixel ('.' clear): keys[i] is drawn in cols[i], anything else in [other].
static void art(struct pd_canvas *c, const char *const *rows, int x, int y, const char *keys, const uint32_t *cols,
                uint32_t other) {
  for (int yy = 0; rows[yy]; yy++)
    for (int xx = 0; rows[yy][xx]; xx++) {
      char ch = rows[yy][xx];
      if (ch == '.')
        continue;
      const char *k = strchr(keys, ch);
      pd_fill(c, x + xx, y + yy, 1, 1, k ? cols[k - keys] : other);
    }
}

// ---- Party --------------------------------------------------------------------------------------

// The party menu's HP bar without its "HP" tag (HudHpBar): a dark frame, the two-tone fill by how much is left.
static void hp_bar(struct pd_canvas *c, int x, int y, int w, int hp, int maxHp) {
  pd_fill(c, x, y, w, 5, 0x404848);
  pd_fill(c, x + 1, y + 1, w - 2, 3, 0x506858);
  int inner = w - 2, f = maxHp > 0 ? hp * inner / maxHp : 0;
  if (f == 0 && hp > 0)
    f = 1;
  if (f > inner)
    f = inner;
  uint32_t top = 0x70F8A8, main = 0x18C020;
  if (maxHp > 0 && hp * 2 <= maxHp)
    top = hp * 5 > maxHp ? 0xF8E038 : 0xF85838, main = hp * 5 > maxHp ? 0xC8A808 : 0xA84048;
  pd_fill(c, x + 1, y + 1, f, 1, top);
  pd_fill(c, x + 1, y + 2, f, 2, main);
}

// The status chip over an icon's top (HudStatusChip): the party tab's colours.
static void status_chip(struct pd_canvas *c, int cx, int y, const char *label) {
  uint32_t fill = !strcmp(label, "FNT") ? 0x6B6B6B : !strcmp(label, "PSN") || !strcmp(label, "TOX") ? 0xA040A0
                : !strcmp(label, "PAR") ? 0xB8A038 : !strcmp(label, "BRN") ? 0xE07030
                : !strcmp(label, "FRZ") ? 0x58A8C8 : 0x8C8C8C;
  int w = pd_text_width(label) + 6;
  pd_round_rect(c, cx - w / 2, y, w, 13, 2, fill);
  pd_text(c, cx - w / 2 + 3, y - 2, label, 0xFFFFFF, PD_NO_SHADOW);
}

// One member's status chip (HudMonTag), centred on cx: FNT when fainted, nothing for an egg or a healthy one.
static void mon_tag(struct hud *u, const struct pd_mon *m, int cx, int y) {
  bool egg = m->species == PD_SPECIES_EGG;
  const char *status = egg ? "" : m->hp == 0 ? "FNT" : pd_status_label(m->status);
  if (*status)
    status_chip(u->c, cx, y, status);
}

// Someone has a status tag (or fainted): the party's tiles get a row for the tags over their icons, and its window is that
// much taller; with none it's shorter (the user's call).
static bool tag_row(const struct hud *u) {
  for (int i = 0; i < u->partyN; i++) {
    const struct pd_mon *m = &u->s->party[i];
    if (m->species != PD_SPECIES_EGG && (m->hp == 0 || *pd_status_label(m->status)))
      return true;
  }
  return false;
}
#define TAG_H 15 // a tag and the room under it

// One member's icon at (x, y) in a TILE-wide slot, faded when fainted.
static void mon_icon(struct hud *u, const struct pd_mon *m, int x, int y) {
  bool egg = m->species == PD_SPECIES_EGG, fainted = !egg && m->hp == 0;
  int ix = x + (TILE - PD_ICON) / 2;
  if (u->monDraw) {
    u->monDraw(u->c, u->monCtx, m, ix, y - 5, fainted);
  } else if (small) {
    struct pd_canvas shrunk = icon_begin();
    pd_draw_mon_icon_shiny(&shrunk, u->g, m->species, m->shiny, 0, 0, 1, fainted);
    icon_draw(u->c, x + (TILE - SMALL_ICON) / 2, y - 4);
  } else {
    pd_draw_mon_icon_shiny(u->c, u->g, m->species, m->shiny, ix, y - 5, 1, fainted);
  }
}

#define COMPACT_ICON_H 21 // what shows of it: a Pokémon stands at the bottom of its picture
// The sprite, room under it, the HP bar (no level and no lit lead: the party at a glance, HudMonTile).
#define TILE_H (small ? COMPACT_ICON_H + 5 : 26 + 3 + 5)
static int tiles_width(const struct hud *u) { return u->partyN ? u->partyN * TILE + (u->partyN - 1) * TILE_GAP : 0; }

// The party as tiles from (x, y) (HudMonTile): the tags' row when anyone has one, the icon, the HP bar under it.
static void party_tiles(struct hud *u, int x, int y) {
  int tags = tag_row(u) ? TAG_H : 0;
  for (int i = 0; i < u->partyN; i++, x += TILE + TILE_GAP) {
    const struct pd_mon *m = &u->s->party[i];
    if (tags)
      mon_tag(u, m, x + TILE / 2, y);
    mon_icon(u, m, x, y + tags);
    hit(u, x - 2, y - 2, TILE + 4, TILE_H + tags + 4, HUD_SLOT + i);
    if (m->species == PD_SPECIES_EGG)
      continue;
    hp_bar(u->c, x, y + tags + (small ? COMPACT_ICON_H : 29), TILE, m->hp, m->maxHp);
  }
}

static int party_h(const struct hud *u) { return 2 * PAD + 2 + TILE_H + (tag_row(u) ? TAG_H : 0); }
static int party_width(const struct hud *u) { return u->partyN ? 2 * PAD + 2 + tiles_width(u) : 0; }

static void party_row(struct hud *u, int x, int y) {
  panel(u, x, y, party_width(u), party_h(u));
  party_tiles(u, x + PAD + 1, y + PAD + 1);
}

// RAILS' party: a row each (HudMonRow), the icon then Lv over the HP bar.
static int party_column_h(const struct hud *u) {
  return u->partyN ? 2 * PAD + 2 + u->partyN * MON_ROW_H + (u->partyN - 1) * GAP : 0;
}

static void party_column(struct hud *u, int x, int y, int w) {
  panel(u, x, y, w, party_column_h(u));
  int rx = x + PAD + 1, ry = y + PAD + 1;
  for (int i = 0; i < u->partyN; i++, ry += MON_ROW_H + GAP) {
    const struct pd_mon *m = &u->s->party[i];
    mon_icon(u, m, rx, ry + 2);
    hit(u, rx - 1, ry - 1, w - 2 * PAD, MON_ROW_H + 2, HUD_SLOT + i);
    if (m->species == PD_SPECIES_EGG)
      continue;
    // HudMonRow: the icon, then its status tag (if any) over the HP bar.
    int tx = rx + TILE + 3, bw = w - 2 * PAD - 2 - TILE - 6;
    if (m->hp == 0 || *pd_status_label(m->status))
      mon_tag(u, m, tx + 14, ry + 3);
    hp_bar(u->c, tx, ry + MON_ROW_H - 10, bw, m->hp, m->maxHp);
  }
}

// ---- Minimap ------------------------------------------------------------------------------------

static bool has_minimap(const struct hud *u) {
  const char *name = pd_mapsec_name(u->g, u->s->mapsec);
  return pd_map_art(u->g) || (name && *name);
}

static int minimap_w(int mw) { return mw + 2 * PAD; }
#define NAME_H (small ? 10 : LINE)
static int minimap_h(int mh, int lines) { return mh + 2 * PAD + 3 + lines * NAME_H; }

// The region map around the player's head (HudMiniMap), mw x mh at 1x, the place's name under it ([lines]: RAILS'
// narrow one wraps it onto two).
static void minimap(struct hud *u, int x, int y, int mw, int mh, int lines) {
  const struct pd_map_art *a = pd_map_art(u->g);
  int page = -1, tx = 0, ty = 0;
  const char *name = pd_mapsec_name(u->g, u->s->mapsec);
  bool has = a && pd_map_player_tile(u->g, u->s, &page, &tx, &ty) && page >= 0 && page < a->pages;
  panel(u, x, y, minimap_w(mw), minimap_h(mh, lines));
  hit(u, x, y, minimap_w(mw), minimap_h(mh, lines), HUD_MAP);
  int mx = x + PAD, my = y + PAD;
  if (has) {
    int cx = tx * 8 + 4, cy = ty * 8 + 4;
    int left = cx - mw / 2, top = cy - mh / 2;
    if (left > a->w - mw)
      left = a->w - mw;
    if (top > a->h - mh)
      top = a->h - mh;
    if (left < 0)
      left = 0;
    if (top < 0)
      top = 0;
    const uint32_t *px = a->page[page];
    for (int yy = 0; yy < mh && top + yy < a->h; yy++)
      for (int xx = 0; xx < mw && left + xx < a->w; xx++) {
        uint32_t p = px[(top + yy) * a->w + left + xx];
        if (p != PD_MAP_CLEAR)
          pd_fill(u->c, mx + xx, my + yy, 1, 1, p & 0xFFFFFF);
      }
    int gender = u->s->gender == 1 ? 1 : 0;
    if (a->hasHead[gender])
      for (int yy = 0; yy < 16; yy++)
        for (int xx = 0; xx < 16; xx++) {
          uint32_t p = a->head[gender][yy * 16 + xx];
          int dx = cx - 8 - left + xx, dy = cy - 8 - top + yy;
          if (p != PD_MAP_CLEAR && dx >= 0 && dx < mw && dy >= 0 && dy < mh)
            pd_fill(u->c, mx + dx, my + dy, 1, 1, p & 0xFFFFFF);
        }
  }
  if (name && *name) {
    pd_clip(u->c, mx, my + mh + 1, mw, lines * NAME_H + 4);
    if (small && lines == 1 && small_text(u->c, 0, 0, name, 0, true)) {
      // The capitals (the small font's rows 0-7) centred between the map and the panel's edge: 3 rows each side.
      small_text(u->c, mx + 1, my + mh + 4, name, u->L->shadow, false);
      small_text(u->c, mx, my + mh + 3, name, u->L->text, false);
    } else if (lines > 1)
      pd_text_wrap(u->c, mx, my + mh + 1, mw, LINE, lines, name, u->L->text, u->L->shadow);
    else
      text(u, mx, my + mh + 1, name, u->L->text);
    pd_unclip(u->c);
  }
}

// ---- Balls, REPEL STEPS -------------------------------------------------------------------------

// The bag's Poké Balls, most first, three at most (HudBalls).
static int balls(const struct pd_snapshot *s, struct pd_item out[3]) {
  int n = 0;
  if (!s->bagOk)
    return 0;
  const struct pd_pocket_items *p = &s->bag[PD_POCKET_BALLS];
  for (int i = 0; i < p->count && i < PD_POCKET_SLOTS; i++) {
    struct pd_item it = p->items[i];
    if (!it.id || !it.quantity)
      continue;
    int at = n < 3 ? n++ : 3;
    while (at > 0 && out[at - 1].quantity < it.quantity) {
      if (at < 3)
        out[at] = out[at - 1];
      at--;
    }
    if (at < 3)
      out[at] = it;
  }
  return n;
}

static int count_w(const struct pd_item *b) {
  char t[12];
  snprintf(t, sizeof t, "x%d", b->quantity);
  return pd_text_width(t);
}

// One ball and its count (BallCount); Ruby / Sapphire keep no item icons: a drawn ball in its place.
static int ball_count(struct hud *u, const struct pd_item *b, int x, int y) {
  bool drawn = u->itemDraw ? u->itemDraw(u->c, b->id, x, y) : pd_draw_item_icon(u->c, u->g, b->id, x, y, false);
  if (!drawn)
    pd_draw_party_ball(u->c, x + (PD_ITEM_ICON - 20) / 2, y + (PD_ITEM_ICON - 20) / 2);
  char t[12];
  snprintf(t, sizeof t, "x%d", b->quantity);
  return PD_ITEM_ICON + 1 + text(u, x + PD_ITEM_ICON + 1, pd_text_y(y, PD_ITEM_ICON), t, u->L->text);
}

static int balls_inline_w(const struct hud *u) {
  int w = 0;
  for (int i = 0; i < u->balls; i++)
    w += PD_ITEM_ICON + 1 + count_w(&u->ball[i]) + (i ? 4 : 0);
  return w;
}

static void balls_inline(struct hud *u, int x, int y) {
  hit(u, x - 2, y - 2, balls_inline_w(u) + 4, PD_ITEM_ICON + 4, HUD_BALLS);
  for (int i = 0; i < u->balls; i++)
    x += ball_count(u, &u->ball[i], x, y) + 4;
}

static int balls_w(const struct hud *u, bool vertical) {
  if (!vertical)
    return 2 * PAD + 4 + balls_inline_w(u);
  int w = 0;
  for (int i = 0; i < u->balls; i++)
    if (count_w(&u->ball[i]) > w)
      w = count_w(&u->ball[i]);
  return 2 * PAD + 2 + PD_ITEM_ICON + 1 + w;
}
static int balls_h(const struct hud *u, bool vertical) { return 2 * PAD + (vertical ? u->balls : 1) * PD_ITEM_ICON; }

static void balls_panel(struct hud *u, int x, int y, bool vertical) {
  panel(u, x, y, balls_w(u, vertical), balls_h(u, vertical));
  hit(u, x, y, balls_w(u, vertical), balls_h(u, vertical), HUD_BALLS);
  int bx = x + PAD + 2, by = y + PAD;
  for (int i = 0; i < u->balls; i++) {
    int w = ball_count(u, &u->ball[i], bx, by);
    if (vertical)
      by += PD_ITEM_ICON;
    else
      bx += w + 4;
  }
}

// RAILS' narrow one: the count over a shorter bar, no "steps".
static int repel_inner_w(const struct hud *u, bool narrow) {
  char t[12];
  snprintf(t, sizeof t, "%d", u->h->repel);
  int words = pd_text_width(t) + (narrow ? 0 : 3 + pd_text_width("steps")), bar = narrow ? 30 : 46;
  return PD_ITEM_ICON + 3 + (words > bar ? words : bar);
}
#define REPEL_INNER_H (PD_ITEM_ICON + 2)
static int repel_w(const struct hud *u, bool narrow) { return 2 * PAD + repel_inner_w(u, narrow) + 2; }
#define REPEL_H (2 * PAD + REPEL_INNER_H)

// REPEL's steps left while one works (RepelSteps): the spray, the count (gold for the last 20) over a bar
// draining green / yellow / red against the steps it started from.
static void repel_inner(struct hud *u, int x, int y, bool narrow) {
  const struct hud_state *h = u->h;
  // The game tracks steps, so use the plain Repel icon.
  if (u->itemDraw)
    u->itemDraw(u->c, u->repelItem, x, y + 1);
  else if (!pd_draw_item_icon(u->c, u->g, h->repelItem ? h->repelItem : 86, x, y + 1, false))
    art(u->c, quick_icon_repel, x + 1, y + 2, "KLG", (const uint32_t[]){0x182820, 0xA4F0B8, 0x48B66C}, 0);
  int tx = x + PD_ITEM_ICON + 3, bar = narrow ? 30 : 46;
  char t[12];
  snprintf(t, sizeof t, "%d", h->repel);
  int w = text(u, tx, y - 2, t, h->repel <= 20 ? u->L->accent : u->L->text);
  if (!narrow)
    text(u, tx + w + 3, y - 2, "steps", u->L->muted);
  int full = h->repelFull > 0 ? h->repelFull : 100;
  int left = h->repel * (bar - 2) / full;
  if (left > bar - 2)
    left = bar - 2;
  uint32_t fill = h->repel * 2 > full ? 0x58D080 : h->repel * 5 > full ? 0xF8C030 : 0xE84838;
  pd_round_rect(u->c, tx, y + REPEL_INNER_H - 6, bar, 5, 1, 0x00405060);
  pd_fill(u->c, tx + 1, y + REPEL_INNER_H - 5, left, 3, fill);
}

static void repel_panel(struct hud *u, int x, int y, bool narrow) {
  panel(u, x, y, repel_w(u, narrow), REPEL_H);
  hit(u, x, y, repel_w(u, narrow), REPEL_H, HUD_REPEL);
  repel_inner(u, x + PAD, y + PAD, narrow);
}

// ---- Buttons ------------------------------------------------------------------------------------

/* A satchel / a region map (FieldHud.kt's BAG_ICON / MAP_ICON). */
static const char *const BAG_ICON[] = {
    ".....KKKKKK.....", "....KK....KK....", "....K......K....", "..KKKKKKKKKKKK..", ".KBLLLLLLLLLLBK.",
    ".KBBBBBBBBBBBBK.", ".KBBBBBBBBBBBBK.", ".KKKKKKYYKKKKKK.", ".KBBBBKYYKBBBBK.", ".KBBBBBKKBBBBBK.",
    ".KBBBBBBBBBBBBK.", ".KBBBBBBBBBBBBK.", ".KLBBBBBBBBBBLK.", ".KLLBBBBBBBBLLK.", "..KKKKKKKKKKKK..",
    "................", NULL,
};
static const char *const MAP_ICON[] = {
    "................", ".KKKKKKKKKKKKKK.", ".KBBBBBGGGGGGGK.", ".KBBBBGGGGRRGGK.", ".KBBBGGGGRWRRGK.",
    ".KBBGGGGGRRRRGK.", ".KBGGGGGGGRRGGK.", ".KGGGGYYYYYRGGK.", ".KGGGYGGGGGGGGK.", ".KGGYGGGGGGGGGK.",
    ".KGGYGGGGGBBBBK.", ".KYYGGGGGBBBBBK.", ".KGGGGGGBBBBBBK.", ".KGGGGGBBBBBBBK.", ".KKKKKKKKKKKKKK.",
    "................", NULL,
};

static const char *button_label(int id) {
  switch (id) {
  case HUD_PC: return "PC";
  case HUD_BAG: return "BAG";
  case HUD_WILD: return "WILD";
  case HUD_MAP_BUTTON: return "MAP";
  default: return "COMPANION";
  }
}

static int button_w(int id) {
  int w = pd_text_width(button_label(id)) + 6;
  return w > 34 ? w : 34;
}

static int buttons_w(const struct hud *u, bool vertical) {
  int w = 0;
  for (int i = 0; i < u->buttons; i++) {
    int b = button_w(u->button[i]);
    w = vertical ? (b > w ? b : w) : w + b + (i ? 3 : 0);
  }
  return w;
}
static int buttons_h(const struct hud *u, bool vertical) {
  return u->buttons ? (vertical ? u->buttons * BUTTON_H + (u->buttons - 1) * 3 : BUTTON_H) : 0;
}

// A square button (HudButton): pixel art over a small label, WILD's red count on its corner.
static void button(struct hud *u, int id, int x, int y, int w, bool framed) {
  if (framed)
    panel(u, x, y, w, BUTTON_H);
  hit(u, x, y, w, BUTTON_H, id);
  int ax = x + (w - 16) / 2, ay = y + 3;
  if (id == HUD_PC)
    art(u->c, quick_icon_pc, ax, ay, "KLDSHG", (const uint32_t[]){0x202838, 0xD8D8E0, 0x9098A8, 0x3070C8, 0x90C8F8, 0x40D040}, 0);
  else if (id == HUD_BAG)
    art(u->c, BAG_ICON, ax, ay, "KBLY", (const uint32_t[]){0x3A2414, 0xC0783A, 0xE8A868, 0xF8D030}, 0);
  else if (id == HUD_WILD)
    art(u->c, quick_icon_grass, ax, ay, "KGL", (const uint32_t[]){0x183818, 0x389838, 0x78D058}, 0);
  else if (id == HUD_MAP_BUTTON)
    art(u->c, MAP_ICON, ax, ay, "KGBYRW", (const uint32_t[]){0x202838, 0x58B048, 0x4890E0, 0xF0D060, 0xE83028, 0xFFFFFF}, 0);
  else // the companion's open arrow, 8 x 13
    art(u->c, quick_icon_companion, ax + 4, ay + 1, "", NULL, u->L->text);
  const char *label = button_label(id);
  text(u, x + (w - pd_text_width(label)) / 2, ay + 16 + 1 - 2, label, u->L->text);
  if (id == HUD_WILD && u->h->wild > 0) {
    char t[8];
    snprintf(t, sizeof t, "%d", u->h->wild);
    int bw = pd_text_width(t) + 6;
    pd_round_rect(u->c, x + w - bw, y - 3, bw, 13, 3, 0xE83028);
    pd_text(u->c, x + w - bw + 3, y - 5, t, 0xFFFFFF, PD_NO_SHADOW);
  }
}

static void buttons(struct hud *u, int x, int y, bool vertical, bool framed) {
  int w = vertical ? buttons_w(u, true) : 0;
  for (int i = 0; i < u->buttons; i++) {
    int bw = vertical ? w : button_w(u->button[i]);
    button(u, u->button[i], x, y, bw, framed);
    if (vertical)
      y += BUTTON_H + 3;
    else
      x += bw + 3;
  }
}

// ---- A column of windows: REPEL STEPS, the balls, the buttons -----------------------------------

enum piece { P_REPEL, P_BALLS, P_BUTTONS };

static bool piece_on(const struct hud *u, enum piece p) {
  return p == P_REPEL ? u->repel : p == P_BALLS ? u->balls > 0 : u->buttons > 0;
}
static int piece_w(const struct hud *u, enum piece p, bool vertical) {
  return !piece_on(u, p) ? 0 : p == P_REPEL ? repel_w(u, vertical) : p == P_BALLS ? balls_w(u, vertical)
                                                                    : buttons_w(u, vertical);
}
static int piece_h(const struct hud *u, enum piece p, bool vertical) {
  return !piece_on(u, p) ? 0 : p == P_REPEL ? REPEL_H : p == P_BALLS ? balls_h(u, vertical) : buttons_h(u, vertical);
}

static int column_w(const struct hud *u, const enum piece *p, int n, bool vertical) {
  int w = 0;
  for (int i = 0; i < n; i++)
    if (piece_w(u, p[i], vertical) > w)
      w = piece_w(u, p[i], vertical);
  return w;
}

/* The pieces top to bottom from y (or ending at y when [up]), each aligned in [x, x + w): -1 left, 0 centre,
 * 1 right. */
static void column(struct hud *u, const enum piece *p, int n, bool vertical, int x, int w, int align, int y, bool up) {
  if (up) {
    for (int i = 0; i < n; i++)
      if (piece_on(u, p[i]))
        y -= piece_h(u, p[i], vertical) + GAP;
    y += GAP;
  }
  for (int i = 0; i < n; i++) {
    if (!piece_on(u, p[i]))
      continue;
    int pw = piece_w(u, p[i], vertical);
    int px = align < 0 ? x : align > 0 ? x + w - pw : x + (w - pw) / 2;
    if (p[i] == P_REPEL)
      repel_panel(u, px, y, vertical);
    else if (p[i] == P_BALLS)
      balls_panel(u, px, y, vertical);
    else
      buttons(u, px, y, vertical, true);
    y += piece_h(u, p[i], vertical) + GAP;
  }
}

// ---- Layouts ------------------------------------------------------------------------------------

static const enum piece BOTTOM_UP[] = {P_REPEL, P_BALLS, P_BUTTONS}; // buttons toward the screen's edge
static const enum piece TOP_DOWN[] = {P_BUTTONS, P_BALLS, P_REPEL};

// The compact HUD (CompactLayout): no party or minimap - the balls over the buttons and a MAP button, bottom left.
static void compact(struct hud *u) {
  int mapBtn = u->map ? 1 : 0;
  if (mapBtn) { // MAP right of WILD, before COMPANION
    int at = u->buttons && u->button[u->buttons - 1] == HUD_COMPANION ? u->buttons - 1 : u->buttons;
    for (int i = u->buttons; i > at; i--)
      u->button[i] = u->button[i - 1];
    u->button[at] = HUD_MAP_BUTTON;
    u->buttons++;
  }
  // A picture narrower than the rows (a Game Boy game's 160 in ORIGINAL): the last buttons go first (COMPANION: X
  // opens it anyway), then the last ball kinds, until each row fits.
  int avail = u->x1 - u->x0 - 2 * EDGE;
  while (u->buttons > 1 && buttons_w(u, false) > avail)
    u->buttons--;
  while (u->balls > 1 && balls_w(u, false) > avail)
    u->balls--;
  column(u, BOTTOM_UP, 3, false, u->x0 + EDGE, column_w(u, BOTTOM_UP, 3, false), -1, u->c->h - EDGE, true);
}

static bool tight(const struct hud *u) {
  return u->partyN && party_width(u) + column_w(u, BOTTOM_UP, 3, false) + 3 * EDGE > u->x1 - u->x0;
}

// CORNERS / CORNERS 2 ([swapped]: the minimap bottom right, the buttons top right).
static void corners(struct hud *u, bool swapped) {
  if (tight(u)) {
    compact(u);
    return;
  }
  int R = u->x1, H = u->c->h, mw = sizes->mapW, mh = sizes->mapH;
  if (u->map)
    minimap(u, R - EDGE - minimap_w(mw), swapped ? H - EDGE - minimap_h(mh, 1) : EDGE, mw, mh, 1);
  if (u->partyN)
    party_row(u, u->x0 + EDGE, H - EDGE - party_h(u));
  int cw = column_w(u, BOTTOM_UP, 3, false);
  if (swapped)
    column(u, TOP_DOWN, 3, false, R - EDGE - cw, cw, 1, EDGE, false);
  else
    column(u, BOTTOM_UP, 3, false, R - EDGE - cw, cw, 1, H - EDGE, true);
}

// RAILS: the party down the left, the minimap and the rest down the right, beside the GBA's 3:2.
static void rails(struct hud *u) {
  int W = u->c->w, H = u->c->h;
  int middle = W < H * 3 / 2 ? W : H * 3 / 2, side = (W - middle) / 2;
  if (side < RAIL_MIN)
    side = RAIL_MIN;
  int x = W - side + 2, w = side - 4, inner = w - 2 * PAD, colH = 0;
  for (int i = 0; i < 3; i++)
    colH += piece_on(u, BOTTOM_UP[i]) ? piece_h(u, BOTTOM_UP[i], true) + GAP : 0;
  if (party_column_h(u) + 4 > H || (u->map ? minimap_h(inner, 2) + GAP : 0) + colH + 4 > H) {
    compact(u); // a picture too small for the rails (LARGE)
    return;
  }
  if (u->partyN) {
    int pw = 2 * PAD + 2 + MON_ROW_W, want = side - 4;
    pw = pw > want ? pw : want;
    party_column(u, 2, (H - party_column_h(u)) / 2, pw);
  }
  if (u->map)
    minimap(u, x, 2, inner, inner, 2);
  column(u, BOTTOM_UP, 3, true, x, w, 0, H - 2, true);
}

// DOCK: the party, balls, REPEL and buttons in one bar along the bottom, the minimap top right; CORNERS where
// the bar is wider than the screen.
static void dock(struct hud *u) {
  int W = u->c->w, H = u->c->h;
  int parts[4] = {tiles_width(u), u->balls ? balls_inline_w(u) : 0, u->repel ? repel_inner_w(u, false) : 0,
                  buttons_w(u, false)};
  int inner = 0, shown = 0;
  for (int i = 0; i < 4; i++)
    if (parts[i])
      inner += parts[i] + (shown++ ? 2 * 3 + 1 : 0); // a divider between, 3 each side
  if (!shown) {
    if (u->map)
      minimap(u, W - EDGE - minimap_w(80), EDGE, 80, 52, 1);
    return;
  }
  int barW = 2 * 5 + inner, barH = 2 * PAD + 2 + (u->partyN ? TILE_H + (tag_row(u) ? TAG_H : 0) : BUTTON_H);
  if (barW + 2 * EDGE > W) {
    corners(u, false);
    return;
  }
  if (u->map)
    minimap(u, W - EDGE - minimap_w(80), EDGE, 80, 52, 1);
  int x = (W - barW) / 2, y = H - EDGE - barH, mid = y + barH / 2;
  panel(u, x, y, barW, barH);
  x += 5;
  shown = 0;
  for (int i = 0; i < 4; i++) {
    if (!parts[i])
      continue;
    if (shown++) { // DockDivider
      pd_fill(u->c, x + 3, mid - 15, 1, 30, u->L->window ? 0x8C8C94u : u->L->edge);
      x += 7;
    }
    if (i == 0)
      party_tiles(u, x, y + PAD + 1);
    else if (i == 1)
      balls_inline(u, x, mid - PD_ITEM_ICON / 2);
    else if (i == 2) {
      hit(u, x - 2, mid - REPEL_INNER_H / 2 - 2, parts[i] + 4, REPEL_INNER_H + 4, HUD_REPEL);
      repel_inner(u, x, mid - REPEL_INNER_H / 2, false);
    } else
      buttons(u, x, mid - BUTTON_H / 2, false, false);
    x += parts[i];
  }
}

bool hud_draw(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s, const struct hud_state *h,
              const struct pd_settings *st, struct hud_hits *out) {
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  if (out)
    out->n = 0;
  if (!s || !s->valid)
    return false;
  small = h->compact;
  sizes = small ? &COMPACT_SIZES : &NORMAL_SIZES;
  struct hud u = {.c = c, .g = g, .s = s, .h = h, .st = st, .out = out, .x0 = h->left, .x1 = h->right ? h->right : c->w,
                  .L = &LOOKS[st->hudLook >= 0 && st->hudLook < PD_HUD_LOOKS ? st->hudLook : 0]};
  // WINDOW on a Game Boy game: the game's own black-on-white window (OptionColors' Gen 1 look), not FireRed's.
  static struct look gbWindow;
  if (u.L->window && pd_gb_look) {
    gbWindow = *u.L;
    gbWindow.fill = PD_GB_PAPER;
    gbWindow.edge = gbWindow.text = gbWindow.muted = PD_GB_INK;
    gbWindow.shadow = PD_GB_PAPER;
    gbWindow.cellOn = 0xD8D8D8;
    u.L = &gbWindow;
  }
  u.partyN = shows(&u, PD_HUD_PARTY) ? (s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE) : 0;
  u.balls = shows(&u, PD_HUD_BALLS) ? balls(s, u.ball) : 0;
  u.repel = shows(&u, PD_HUD_REPEL) && h->repel > 0;
  u.map = shows(&u, PD_HUD_MAP) && has_minimap(&u);
  bool bag = false;
  for (int p = 0; s->bagOk && p < PD_POCKET_COUNT; p++)
    bag |= s->bag[p].count > 0;
  // HudButtons' order: PC, BAG, WILD, then COMPANION (one screen: the side panel's button).
  if (h->noButtons)
    ;
  else if (h->pc && shows(&u, PD_HUD_PC))
    u.button[u.buttons++] = HUD_PC;
  if (!h->noButtons) {
    if (bag && shows(&u, PD_HUD_BAG))
      u.button[u.buttons++] = HUD_BAG;
    if (h->wild >= 0 && shows(&u, PD_HUD_WILD))
      u.button[u.buttons++] = HUD_WILD;
    u.button[u.buttons++] = HUD_COMPANION;
  }
  switch (st->hudLayout) {
  case PD_HUD_CORNERS_2: corners(&u, true); break;
  case PD_HUD_RAILS: rails(&u); break;
  case PD_HUD_DOCK: dock(&u); break;
  default: corners(&u, false); break;
  }
  hud_focus_draw(c, st, out, h->focus);
  return true;
}

/* The party row alone, for the Linux host's DSperate backend (dsperate/frontend.cpp): the default look, its art from
 * [draw], the canvas not cleared. */
void hud_party_draw(struct pd_canvas *c, const struct pd_snapshot *s, void *ctx, hud_mon_draw_fn draw) {
  static const struct pd_settings defaults;
  static const struct hud_state none = {.wild = -1, .focus = -1};
  if (!s || !s->valid || s->partyCount <= 0 || !draw)
    return;
  small = false;
  sizes = &NORMAL_SIZES;
  struct hud u = {.c = c, .s = s, .h = &none, .st = &defaults, .monDraw = draw, .monCtx = ctx, .L = &LOOKS[0], .x1 = c->w};
  u.partyN = s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE;
  if (c->w >= party_width(&u) + 2 * EDGE && c->h >= party_h(&u) + 2 * EDGE)
    party_row(&u, EDGE, c->h - EDGE - party_h(&u));
}

void hud_ds_draw(struct pd_canvas *c, const struct pd_snapshot *s, const struct hud_state *h,
                 const struct pd_settings *st, void *ctx, hud_mon_draw_fn monDraw,
                 hud_item_draw_fn itemDraw, int repelItem, bool minimal, int right, struct hud_hits *out) {
  if (out) out->n = 0;
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  if (!s->valid)
    return;
  small = right < c->w;
  sizes = small ? &COMPACT_SIZES : &NORMAL_SIZES;
  struct hud u = {.c = c, .s = s, .h = h, .st = st, .monDraw = monDraw, .monCtx = ctx, .itemDraw = itemDraw, .x1 = right,
                  .repelItem = repelItem, .out = out,
                  .L = &LOOKS[st->hudLook >= 0 && st->hudLook < PD_HUD_LOOKS ? st->hudLook : 0]};
  u.partyN = !minimal && shows(&u, PD_HUD_PARTY) ? s->partyCount : 0;
  u.balls = shows(&u, PD_HUD_BALLS) ? balls(s, u.ball) : 0;
  u.repel = shows(&u, PD_HUD_REPEL) && h->repel > 0;
  if (minimal) {
    if (!u.balls && !u.repel && shows(&u, PD_HUD_PARTY) && s->partyCount) {
      u.partyN = 1;
      party_row(&u, EDGE, c->h - EDGE - party_h(&u));
    } else compact(&u);
    return;
  }
  if (u.partyN)
    party_row(&u, EDGE, c->h - EDGE - party_h(&u));
  int width = column_w(&u, BOTTOM_UP, 3, false);
  int bottom = c->h - EDGE - (tight(&u) ? party_h(&u) + GAP : 0);
  column(&u, BOTTOM_UP, 3, false, right - EDGE - width, width, 1, bottom, true);
}

void hud_ds_minimap(struct pd_canvas *c, const struct pd_settings *st, const char *name, hud_ds_map_fn draw, struct hud_hits *out) {
  enum { W=104, H=64 };
  small = false;
  sizes = &NORMAL_SIZES;
  struct hud u = {.c=c, .x1=c->w, .out=out, .L=&LOOKS[st->hudLook >= 0 && st->hudLook < PD_HUD_LOOKS ? st->hudLook : 0]};
  const int x=c->w-EDGE-minimap_w(W), y=EDGE;
  panel(&u,x,y,minimap_w(W),minimap_h(H,1));
  hit(&u,x,y,minimap_w(W),minimap_h(H,1),HUD_MAP);
  draw(c,x+PAD,y+PAD,W,H);
  pd_text_fit(c,x+PAD,y+PAD+H+1,W,name,u.L->text,u.L->shadow);
}

int hud_compact_map_width(void) { return COMPACT_SIZES.mapW + 2 * COMPACT_SIZES.pad; }

uint32_t hud_key(const struct pd_game *g, const struct pd_snapshot *s, const struct hud_state *h, const struct pd_settings *st) {
  uint32_t k = 2166136261u;
#define MIX(v) (k = (k ^ (uint32_t)(v)) * 16777619u)
  MIX(st->hudHidden);
  MIX(st->hudLayout);
  MIX(st->hudLook);
  MIX(h->repel);
  MIX(h->repelFull);
  MIX(h->repelItem);
  MIX(h->pc);
  MIX(h->wild);
  MIX(h->focus);
  MIX(h->compact);
  MIX(h->noButtons);
  MIX(h->left);
  MIX(h->right);
  if (s && s->valid) {
    MIX(s->partyCount);
    for (int i = 0; i < s->partyCount && i < PD_PARTY_SIZE; i++) {
      MIX(s->party[i].species);
      MIX(s->party[i].level);
      MIX(s->party[i].hp);
      MIX(s->party[i].maxHp);
      MIX(s->party[i].status);
    }
    MIX(s->mapsec);
    // The tile the minimap shows, not every step: a redraw is several ms on a 3DS.
    int page = -1, tx = 0, ty = 0;
    if (g && pd_map_player_tile(g, s, &page, &tx, &ty)) {
      MIX(page);
      MIX(tx);
      MIX(ty);
    } else {
      MIX(s->posOk ? s->x : 0);
      MIX(s->posOk ? s->y : 0);
    }
    MIX(s->gender);
    MIX(s->bagOk);
    for (int p = 0; s->bagOk && p < PD_POCKET_COUNT; p++)
      MIX(s->bag[p].count);
    for (int i = 0; s->bagOk && i < s->bag[PD_POCKET_BALLS].count && i < PD_POCKET_SLOTS; i++) {
      MIX(s->bag[PD_POCKET_BALLS].items[i].id);
      MIX(s->bag[PD_POCKET_BALLS].items[i].quantity);
    }
  }
#undef MIX
  return k;
}

int hud_repel_full(int steps) { return steps <= 100 ? 100 : steps <= 200 ? 200 : 250; }
