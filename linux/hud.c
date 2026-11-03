/* The HUD (hud.h): FieldHud.kt's CornersLayout - the minimap top right (never top left, where the games slide a new
 * area's name in), the party along the bottom left, REPEL STEPS over the balls bottom right. GPL-3.0. */
#include "hud.h"

#include "pd_icon.h"
#include "pd_map.h"
#include "pd_style.h"
#include "pd_tables.h"
#include "pd_ui.h"
#include "quick_menu.h"
#include <stdio.h>
#include <string.h>

#define GLASS_FILL 0xB8121620u // FieldHud.kt's GLASS_FILL / _EDGE (ARGB)
#define GLASS_EDGE 0x66FFFFFFu
#define LEAD_FILL 0xC8283448u
#define ACCENT 0x00F8C030u
#define INK 0x00F8F8F8u
#define INK_SHADOW 0x00303840u
#define MUTED 0x00A8B0C0u
#define LINE 12

// The sizes: FieldHud's, or a 3DS's (hud_state.compact: its screen shows the game at 1.5x, a Linux handheld's at 3x and
// more, so the same pixels cover twice as much of the picture). A compact tile overlaps the icon's see-through edges.
struct hud_sizes { int pad, edge, tile, gap, mapW, mapH; };
static const struct hud_sizes NORMAL_SIZES = { 4, 6, 30, 3, 104, 64 }, COMPACT_SIZES = { 2, 3, 20, 1, 72, 40 };
static const struct hud_sizes *sizes = &NORMAL_SIZES;
static bool compact;
#define PAD (sizes->pad)
#define EDGE (sizes->edge) // CornersLayout's padding
#define TILE (sizes->tile)
#define TILE_GAP (sizes->gap)
#define MAP_W (sizes->mapW)
#define MAP_H (sizes->mapH)

// Compact: a 3DS shows this canvas pixel for pixel and the icons come no smaller, so they go through Scale2x (edges stay
// clean, no pixel simply dropped) and then down to 7/8 of their size, each pixel what it covers. Dropping pixels broke the
// shapes, and the same at 1x blurred some columns and not others.
#define SHRUNK_CLEAR 0xFE000000u
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

static void panel(struct pd_canvas *c, int x, int y, int w, int h) {
  pd_round_rect(c, x, y, w, h, 4, GLASS_EDGE);
  pd_round_rect(c, x + 1, y + 1, w - 2, h - 2, 3, GLASS_FILL);
}

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

static int party_width(const struct pd_snapshot *s) {
  int n = s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE;
  return n ? 2 * PAD + 2 + n * TILE + (n - 1) * TILE_GAP : 0;
}
#define SMALL_ICON (PD_ICON * 7 / 8)
#define COMPACT_ICON_H 21 // what shows of it: a Pokémon stands at the bottom of its picture
#define PARTY_H (compact ? 2 * PAD + 2 + COMPACT_ICON_H + 5 : 2 * PAD + 2 + 26 + LINE + 6)

static void party_row(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s, int x, int y,
                      void *ctx, hud_mon_draw_fn draw) {
  int n = s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE;
  panel(c, x, y, party_width(s), PARTY_H);
  int tx = x + PAD + 1, ty = y + PAD + 1;
  for (int i = 0; i < n; i++, tx += TILE + TILE_GAP) {
    const struct pd_mon *m = &s->party[i];
    bool egg = m->egg, fainted = !egg && m->hp == 0;
    if (i == 0) { // the lead (the one that goes out first): a lit tile with an accent edge
      pd_round_rect(c, tx - 2, ty - 2, TILE + 4, PARTY_H - 2 * PAD - 2 + 4, 3, ACCENT);
      pd_round_rect(c, tx - 1, ty - 1, TILE + 2, PARTY_H - 2 * PAD - 2 + 2, 3, LEAD_FILL);
    }
    if (draw) {
      draw(c, ctx, m, tx + (TILE - PD_ICON) / 2, ty - 5, fainted);
    } else if (compact) {
      struct pd_canvas small = icon_begin();
      pd_draw_mon_icon(&small, g, m->species, 0, 0, fainted);
      icon_draw(c, tx + (TILE - SMALL_ICON) / 2, ty - 4);
    } else {
      pd_draw_mon_icon(c, g, m->species, tx + (TILE - PD_ICON) / 2, ty - 5, fainted);
    }
    if (egg)
      continue;
    const char *status = fainted ? "FNT" : pd_status_label(m->status);
    if (*status)
      status_chip(c, tx + TILE / 2, ty - 3, status);
    if (compact) { // no level: the party tab has it
      hp_bar(c, tx, ty + COMPACT_ICON_H, TILE, m->hp, m->maxHp);
      continue;
    }
    char lv[8];
    snprintf(lv, sizeof lv, "Lv%d", m->level);
    pd_text(c, tx + (TILE - pd_text_width(lv)) / 2, ty + 24, lv, INK, INK_SHADOW);
    hp_bar(c, tx, ty + 26 + LINE, TILE, m->hp, m->maxHp);
  }
}

void hud_party_draw(struct pd_canvas *c, const struct pd_snapshot *s, void *ctx, hud_mon_draw_fn draw) {
  compact = false;
  sizes = &NORMAL_SIZES;
  if (s && s->valid && s->partyCount > 0 && draw && c->w >= party_width(s) + 2 * EDGE && c->h >= PARTY_H + 2 * EDGE)
    party_row(c, NULL, s, EDGE, c->h - EDGE - PARTY_H, ctx, draw);
}

#define MINIMAP_W (MAP_W + 2 * PAD)
#define NAME_H (compact ? 10 : LINE)
#define MINIMAP_H (MAP_H + 2 * PAD + 3 + NAME_H)

// The region map around the player's head (HudMiniMap): the map at 1x, its name under it.
static bool minimap(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s, int x, int y) {
  const struct pd_map_art *art = pd_map_art(g);
  int page = -1, tx = 0, ty = 0;
  const char *name = pd_mapsec_name(g, s->mapsec);
  bool has = art && pd_map_player_tile(g, s, &page, &tx, &ty) && page >= 0 && page < art->pages;
  if (!has && !(name && *name))
    return false;
  panel(c, x, y, MINIMAP_W, MINIMAP_H);
  int mx = x + PAD, my = y + PAD;
  if (has) {
    int cx = tx * 8 + 4, cy = ty * 8 + 4;
    int left = cx - MAP_W / 2, top = cy - MAP_H / 2;
    if (left > PD_MAP_W - MAP_W)
      left = PD_MAP_W - MAP_W;
    if (top > PD_MAP_H - MAP_H)
      top = PD_MAP_H - MAP_H;
    if (left < 0)
      left = 0;
    if (top < 0)
      top = 0;
    const uint32_t *px = art->page[page];
    for (int yy = 0; yy < MAP_H; yy++)
      for (int xx = 0; xx < MAP_W; xx++) {
        uint32_t p = px[(top + yy) * PD_MAP_W + left + xx];
        if (p != PD_MAP_CLEAR)
          pd_fill(c, mx + xx, my + yy, 1, 1, p & 0xFFFFFF);
      }
    int gender = s->gender == 1 ? 1 : 0;
    if (art->hasHead[gender])
      for (int yy = 0; yy < 16; yy++)
        for (int xx = 0; xx < 16; xx++) {
          uint32_t p = art->head[gender][yy * 16 + xx];
          int dx = cx - 8 - left + xx, dy = cy - 8 - top + yy;
          if (p != PD_MAP_CLEAR && dx >= 0 && dx < MAP_W && dy >= 0 && dy < MAP_H)
            pd_fill(c, mx + dx, my + dy, 1, 1, p & 0xFFFFFF);
        }
  }
  if (name && *name) {
    pd_clip(c, mx, my + MAP_H + 1, MAP_W, NAME_H + 4);
    if (compact && small_text(c, 0, 0, name, 0, true)) {
      small_text(c, mx + 1, my + MAP_H + 2, name, INK_SHADOW, false);
      small_text(c, mx, my + MAP_H + 1, name, INK, false);
    } else {
      pd_text(c, mx, my + MAP_H + 1, name, INK, INK_SHADOW);
    }
    pd_unclip(c);
  }
  return true;
}

// The bag's Poké Balls, most first, three at most (HudBalls): each one's icon and how many.
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

static int balls_width(const struct pd_item *b, int n) {
  int w = 2 * PAD + 4;
  for (int i = 0; i < n; i++) {
    char t[12];
    snprintf(t, sizeof t, "%d", b[i].quantity);
    w += PD_ITEM_ICON + 1 + pd_text_width(t) + (i ? 4 : 0);
  }
  return w;
}
#define BALLS_H (2 * PAD + PD_ITEM_ICON)

static void balls_panel(struct pd_canvas *c, const struct pd_game *g, const struct pd_item *b, int n, int x, int y) {
  panel(c, x, y, balls_width(b, n), BALLS_H);
  int bx = x + PAD + 2;
  for (int i = 0; i < n; i++) {
    char t[12];
    snprintf(t, sizeof t, "%d", b[i].quantity);
    pd_draw_item_icon(c, g, b[i].id, bx, y + PAD, false);
    bx += PD_ITEM_ICON + 1;
    bx += pd_text(c, bx, pd_text_y(y, BALLS_H), t, INK, INK_SHADOW) + 4;
  }
}

// As wide as the count and "steps" need, at least the bar's 46.
static int repel_width(int steps) {
  char t[12];
  snprintf(t, sizeof t, "%d", steps);
  int text = pd_text_width(t) + 3 + pd_text_width("steps");
  return 2 * PAD + PD_ITEM_ICON + 3 + (text > 46 ? text : 46) + 2;
}
#define REPEL_H (2 * PAD + PD_ITEM_ICON + 2)

// REPEL's steps left while one works (RepelSteps): the spray, the count (gold for the last 20) over a bar
// draining green / yellow / red against the steps it started from.
static void repel_panel(struct pd_canvas *c, const struct pd_game *g, const struct hud_state *h, int x, int y) {
  panel(c, x, y, repel_width(h->repel), REPEL_H);
  pd_draw_item_icon(c, g, 86, x + PAD, y + PAD + 1, false); // the plain REPEL's (the game doesn't keep which)
  int tx = x + PAD + PD_ITEM_ICON + 3;
  char t[12];
  snprintf(t, sizeof t, "%d", h->repel);
  bool low = h->repel <= 20;
  int w = pd_text(c, tx, y + PAD - 2, t, low ? ACCENT : INK, INK_SHADOW);
  pd_text(c, tx + w + 3, y + PAD - 2, "steps", MUTED, INK_SHADOW);
  int full = h->repelFull > 0 ? h->repelFull : 100;
  int left = h->repel * 44 / full;
  if (left > 44)
    left = 44;
  uint32_t fill = h->repel * 2 > full ? 0x58D080 : h->repel * 5 > full ? 0xF8C030 : 0xE84838;
  pd_round_rect(c, tx, y + REPEL_H - PAD - 6, 46, 5, 1, 0x00405060);
  pd_fill(c, tx + 1, y + REPEL_H - PAD - 5, left, 3, fill);
}

int hud_compact_map_width(void) { return COMPACT_SIZES.mapW + 2 * COMPACT_SIZES.pad; }

bool hud_draw(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s,
              const struct hud_state *h, int hidden) {
  compact = h->compact;
  sizes = compact ? &COMPACT_SIZES : &NORMAL_SIZES;
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  if (!s || !s->valid)
    return false;
  bool any = false;
  int mapBottom = EDGE;
  int left = h->left + EDGE, right = (h->right ? h->right : c->w) - EDGE;
  if (!(hidden & 1 << PD_HUD_MAP) && minimap(c, g, s, right - MINIMAP_W, EDGE)) {
    any = true;
    mapBottom = EDGE + MINIMAP_H + 4;
  }
  int partyRight = 0;
  if (!(hidden & 1 << PD_HUD_PARTY) && s->partyCount > 0) {
    party_row(c, g, s, left, c->h - EDGE - PARTY_H, NULL, NULL);
    partyRight = left + party_width(s);
    any = true;
  }
  // The right column, from the bottom up: the balls, then REPEL STEPS over them. Where the party row reaches
  // under it, it goes under the minimap instead.
  struct pd_item b[3];
  int nb = hidden & 1 << PD_HUD_BALLS ? 0 : balls(s, b);
  bool repel = !(hidden & 1 << PD_HUD_REPEL) && h->repel > 0;
  int colW = 0, colH = 0;
  if (nb) {
    colW = balls_width(b, nb);
    colH += BALLS_H;
  }
  if (repel) {
    colW = colW > repel_width(h->repel) ? colW : repel_width(h->repel);
    colH += (colH ? 4 : 0) + REPEL_H;
  }
  if (colH) {
    bool top = partyRight && right - colW < partyRight + 4;
    int y = top ? mapBottom : c->h - EDGE - colH;
    if (repel) {
      repel_panel(c, g, h, right - repel_width(h->repel), y);
      y += REPEL_H + 4;
    }
    if (nb)
      balls_panel(c, g, b, nb, right - balls_width(b, nb), y);
    any = true;
  }
  return any;
}

uint32_t hud_key(const struct pd_game *g, const struct pd_snapshot *s, const struct hud_state *h, int hidden) {
  uint32_t k = 2166136261u;
#define MIX(v) (k = (k ^ (uint32_t)(v)) * 16777619u)
  MIX(hidden);
  MIX(h->repel);
  MIX(h->repelFull);
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
    if (pd_map_player_tile(g, s, &page, &tx, &ty)) {
      MIX(page);
      MIX(tx);
      MIX(ty);
    }
    MIX(s->gender);
    MIX(s->bagOk);
    for (int i = 0; s->bagOk && i < s->bag[PD_POCKET_BALLS].count && i < PD_POCKET_SLOTS; i++) {
      MIX(s->bag[PD_POCKET_BALLS].items[i].id);
      MIX(s->bag[PD_POCKET_BALLS].items[i].quantity);
    }
  }
#undef MIX
  return k;
}

int hud_repel_full(int steps) { return steps <= 100 ? 100 : steps <= 200 ? 200 : 250; }
