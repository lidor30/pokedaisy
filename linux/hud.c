/* The HUD (hud.h): FieldHud.kt's layouts in C. CORNERS - the minimap top right (never top left, where the games slide
 * a new area's name in), the party along the bottom left, REPEL STEPS / the balls / the buttons bottom right;
 * CORNERS 2 the right side the other way up; RAILS two columns at the sides; DOCK one bar along the bottom; the
 * compact HUD (no party, no minimap: a MAP button) where the party doesn't fit beside the buttons. GPL-3.0. */
#include "hud.h"
#include "hud_style.h"

#include "pd_icon.h"
#include "pd_map.h"
#include "pd_style.h"
#include "pd_tables.h"
#include "quick_menu.h"
#include <stdio.h>
#include <string.h>

// FieldHud.kt's looks (ARGB where see-through). WINDOW uses the current OPTION title window.
static const struct hud_ink LOOKS[PD_HUD_LOOKS] = {
    [PD_HUD_GLASS] = {0xA0121620u, 0x66FFFFFFu, 0xF4F6FA, 0x0C0F15, 0xFFCF5A, 0xB2BACB, 0x80060810u, 0xC8283044u, 0xAA121620u, 0x0C0F15, false},
    [PD_HUD_WHITE_GLASS] = {0xACF6F7FAu, 0x59000000u, 0x262B34, 0xD9DCE3, 0xC8321E, 0x6A7282, 0x4C141820u, 0xD8FFFFFFu, 0xC8E4E7EEu, 0xFFFFFF, false},
    [PD_HUD_WINDOW] = {0xFFFFFF, 0x8C8C94, 0x575656, 0xBCBBB4, 0xCB0707, 0x8C8C94, 0, 0, 0, 0xFFFFFF, true},
};

const struct hud_ink *hud_ink(int look) {
  if (look < 0 || look >= PD_HUD_LOOKS)
    look = PD_HUD_GLASS;
  return &LOOKS[look];
}

void hud_panel(struct pd_canvas *c, const struct hud_ink *ink, int x, int y, int w, int h, int shadow) {
  if (shadow)
    pd_round_rect(c, x + shadow, y + shadow, w, h, 4, ink->window ? pd_cur->title[0].color : ink->boxShadow);
  if (ink->window) {
    pd_title_box(c, x, y, w, h, PD_TITLE_FILL);
  } else {
    const struct pd_layer edge = {ink->edge, 1};
    pd_layered_box(c, x, y, w, h, 4, &edge, 1, ink->fill);
  }
}

// Interpolate the alpha as well as the RGB channels, so each band is drawn only once on the overlay.
static uint32_t mix_color(uint32_t top, uint32_t low, unsigned percent) {
  uint32_t result = 0;
  for (unsigned shift = 0; shift < 32; shift += 8) {
    unsigned a = (top >> shift) & 255, b = (low >> shift) & 255;
    result |= ((a * (100 - percent) + b * percent + 50) / 100) << shift;
  }
  return result;
}

void hud_tile(struct pd_canvas *c, const struct hud_ink *ink, int x, int y, int w, int h, bool selected) {
  uint32_t top = ink->tileTop, low = ink->tileLow;
  const struct pd_layer edge = {ink->edge, 1};
  int radius = ink->window ? 3 : 4;
  pd_round_rect(c, x + 3, y + 3, w, h, 4, ink->window ? pd_cur->title[0].color : ink->boxShadow);
  if (ink->window) {
    pd_button_box(c, x, y, w, h, radius, pd_cur->title, pd_cur->titleCount, PD_TITLE_FILL);
  } else {
    int inset = pd_layered_box(c, x, y, w, h, radius, &edge, 1, top);
    int split = y + h * 47 / 100;
    const int bounds[] = {split, split + 1, split + 2, y + h - inset};
    const uint32_t bands[] = {mix_color(top, low, 35), mix_color(top, low, 70), low};
    for (int i = 0; i < 3; i++) {
      struct pd_canvas clip = *c;
      if (clip.cy0 < bounds[i])
        clip.cy0 = bounds[i];
      if (clip.cy1 > bounds[i + 1])
        clip.cy1 = bounds[i + 1];
      pd_round_rect(&clip, x + inset, y + inset, w - 2 * inset, h - 2 * inset,
                    radius > inset ? radius - inset : 0, bands[i]);
    }
  }
  if (selected) {
    pd_fill(c, x + 4, y + 1, w - 8, 1, HUD_SELECTED);
    pd_fill(c, x + 4, y + h - 2, w - 8, 1, HUD_SELECTED);
    pd_fill(c, x + 1, y + 4, 1, h - 8, HUD_SELECTED);
    pd_fill(c, x + w - 2, y + 4, 1, h - 8, HUD_SELECTED);
    pd_fill(c, x + 2, y + 2, 2, 2, HUD_SELECTED);
    pd_fill(c, x + w - 4, y + 2, 2, 2, HUD_SELECTED);
    pd_fill(c, x + 2, y + h - 4, 2, 2, HUD_SELECTED);
    pd_fill(c, x + w - 4, y + h - 4, 2, 2, HUD_SELECTED);
  }
}

// The sizes: FieldHud's, or a 3DS's (hud_state.compact: its screen shows the game at 1.5x, a Linux handheld's at 3x and
// more, so the same pixels cover twice as much of the picture). A compact tile overlaps the icon's see-through edges.
struct hud_sizes { int pad, edge, tile, gap, mapW, mapH; };
static const struct hud_sizes NORMAL_SIZES = { 4, 6, 30, 3, 104, 64 }, COMPACT_SIZES = { 2, 3, 20, 1, 72, 40 },
                               MINI_SIZES = { 2, 3, 15, 1, 72, 40 }; // the cores' mini HUD: half-size party icons
static const struct hud_sizes *sizes = &NORMAL_SIZES;
static bool small; // hud_state.compact (not CompactLayout, the HUD without party or minimap: compact() below)
#define PAD (sizes->pad)
#define EDGE (sizes->edge) // CornersLayout's padding
#define GAP (mini ? 2 : 4) // between a column's windows
#define TILE (sizes->tile)
#define TILE_GAP (sizes->gap)
#define LINE 12
#define MON_ROW_H 30 // RAILS' party rows
#define MON_ROW_W (TILE + 4 + 44)
#define BUTTON_H (3 + 16 + 1 + LINE + 3)
#define RAIL_MIN 70 // a rail's least width (FieldHud's u * 50; COMPANION's button in this font)

struct hud {
  struct pd_canvas *c;
  int x0, x1;           // the columns it keeps to (hud_state.left / right: the game's picture)
  const struct pd_game *g;
  const struct pd_snapshot *s;
  const struct hud_state *h;
  const struct pd_settings *st;
  const struct hud_ink *L;
  struct hud_hits *out;
  hud_mon_draw_fn monDraw;
  hud_item_draw_fn itemDraw;
  int repelItem;
  const struct pd_party_source_palette *partyPalette;
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

// ---- Mini (hud_state.mini): the libretro cores' HUD -------------------------------------------------
// A core's canvas is the game's own pixels, shown at the screen's whole scale (5x on a Steam Deck), so the HUD's
// art and text are drawn at about half their size: icons halved, counts in 3x5 digits, buttons without labels.
static bool mini;
static const unsigned char TINY_DIGITS[10][5] = {
    {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
    {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 2, 2, 2}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7}};
#define TINY_H 5
// Digits (anything else is skipped), each 3 wide and a pixel apart, with the look's shadow down-right; the width.
static int tiny_text(struct pd_canvas *c, int x, int y, const char *t, uint32_t color, uint32_t shadow) {
  int w = 0;
  for (const char *p = t; *p; p++) {
    if (*p < '0' || *p > '9')
      continue;
    const unsigned char *g = TINY_DIGITS[*p - '0'];
    for (int pass = shadow != PD_NO_SHADOW ? 0 : 1; pass < 2; pass++)
      for (int j = 0; j < TINY_H; j++)
        for (int i = 0; i < 3; i++)
          if (g[j] & 4 >> i)
            pd_fill(c, x + w + i + (pass ? 0 : 1), y + j + (pass ? 0 : 1), 1, 1, pass ? color : shadow);
    w += 4;
  }
  return w ? w - 1 : 0;
}
static int tiny_width(const char *t) {
  int w = 0;
  for (const char *p = t; *p; p++)
    w += *p >= '0' && *p <= '9' ? 4 : 0;
  return w ? w - 1 : 0;
}

// An item icon at two thirds: drawn into its own canvas, through Scale2x (edges kept, as the compact party icons), then
// each 3x3 block of that is one pixel - its solid pixels' average, where most of the block is solid.
#define MINI_ITEM (PD_ITEM_ICON * 2 / 3)
static uint32_t itemPx[PD_ITEM_ICON * PD_ITEM_ICON], item2x[4 * PD_ITEM_ICON * PD_ITEM_ICON];
static void art(struct pd_canvas *c, const char *const *rows, int x, int y, const char *keys, const uint32_t *cols,
                uint32_t other);
/* An item's picture at about two thirds (the mini HUD); [art] (nullable): drawn when the game has no picture for it,
 * else a Poké Ball (Ruby / Sapphire draw no item icons: the REPEL row then shows the spray, as the full-size HUD). */
static void mini_item(struct hud *u, int id, int x, int y, const char *const *fallback) {
  struct pd_canvas s;
  pd_canvas_init(&s, PD_ITEM_ICON, PD_ITEM_ICON, itemPx);
  for (int i = 0; i < PD_ITEM_ICON * PD_ITEM_ICON; i++)
    itemPx[i] = SHRUNK_CLEAR;
  bool drawn = u->itemDraw ? u->itemDraw(&s, id, 0, 0) : pd_draw_item_icon(&s, u->g, id, 0, 0, false);
  if (!drawn && fallback)
    art(&s, fallback, 1, 2, "KLG", (const uint32_t[]){0x182820, 0xA4F0B8, 0x48B66C}, 0);
  else if (!drawn)
    pd_draw_party_ball(&s, (PD_ITEM_ICON - 20) / 2, (PD_ITEM_ICON - 20) / 2);
  const int n2 = 2 * PD_ITEM_ICON;
  for (int y = 0; y < PD_ITEM_ICON; y++)
    for (int x2 = 0; x2 < PD_ITEM_ICON; x2++) {
      const int m = PD_ITEM_ICON;
      uint32_t e = itemPx[y * m + x2];
      uint32_t bb = itemPx[(y ? y - 1 : y) * m + x2], hh = itemPx[(y < m - 1 ? y + 1 : y) * m + x2];
      uint32_t dd = itemPx[y * m + (x2 ? x2 - 1 : x2)], ff = itemPx[y * m + (x2 < m - 1 ? x2 + 1 : x2)];
      bool edge = bb != hh && dd != ff;
      uint32_t *o = &item2x[2 * y * n2 + 2 * x2];
      o[0] = edge && dd == bb ? dd : e;
      o[1] = edge && bb == ff ? ff : e;
      o[n2] = edge && dd == hh ? dd : e;
      o[n2 + 1] = edge && hh == ff ? ff : e;
    }
  for (int oy = 0; oy < MINI_ITEM; oy++)
    for (int ox = 0; ox < MINI_ITEM; ox++) {
      int r = 0, g = 0, b = 0, n = 0;
      for (int k = 0; k < 9; k++) {
        uint32_t p = item2x[(3 * oy + k / 3) * n2 + 3 * ox + k % 3];
        if (p == SHRUNK_CLEAR || p >> 24)
          continue;
        r += (int)(p >> 16 & 0xFF), g += (int)(p >> 8 & 0xFF), b += (int)(p & 0xFF), n++;
      }
      if (n >= 5)
        pd_fill(u->c, x + ox, y + oy, 1, 1, (uint32_t)(r / n) << 16 | (uint32_t)(g / n) << 8 | (uint32_t)(b / n));
    }
}

static void hit(struct hud *u, int x, int y, int w, int h, int id) {
  if (u->out && u->out->n < HUD_MAX_HITS)
    u->out->at[u->out->n++] = (__typeof__(u->out->at[0])){x, y, w, h, id};
}

void hud_focus_draw(struct pd_canvas *c, const struct hud_hits *out, int focus) {
  if (focus < 0) return;
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

static void panel(struct hud *u, int x, int y, int w, int h) {
  hud_panel(u->c, u->L, x, y, w, h, small ? 2 : 3);
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
static void hp_bar(struct hud *u, int x, int y, int w, int hp, int maxHp) {
  struct pd_canvas *c = u->c;
  const struct pd_party_source_palette *p = u->partyPalette;
  if (p) {
    pd_round_rect(c, x, y, w, 5, 1, p->hpFrame);
    pd_fill(c, x + 1, y + 1, w - 2, 1, p->hpEmpty.top);
    pd_fill(c, x + 1, y + 2, w - 2, 2, p->hpEmpty.main);
  } else {
    pd_fill(c, x, y, w, 5, 0x404848);
    pd_fill(c, x + 1, y + 1, w - 2, 3, 0x506858);
  }
  int inner = w - 2, f = maxHp > 0 ? hp * inner / maxHp : 0;
  if (f == 0 && hp > 0)
    f = 1;
  if (f > inner)
    f = inner;
  uint32_t top = 0x70F8A8, main = 0x18C020;
  if (p) {
    struct pd_hp_band band = maxHp <= 0 || hp * 2 > maxHp ? p->hpGreen : hp * 5 > maxHp ? p->hpYellow : p->hpRed;
    top = band.top;
    main = band.main;
  } else if (maxHp > 0 && hp * 2 <= maxHp) {
    top = hp * 5 > maxHp ? 0xF8E038 : 0xF85838;
    main = hp * 5 > maxHp ? 0xC8A808 : 0xA84048;
  }
  pd_fill(c, x + 1, y + 1, f, 1, top);
  pd_fill(c, x + 1, y + 2, f, 2, main);
}

// The status chip over an icon's top (HudStatusChip): the party tab's colours.
#define TAG_H (mini ? 5 : 15) // the party's status row: chips, or the mini HUD's marks
static void status_chip(struct hud *u, int cx, int y, const char *label) {
  struct pd_canvas *c = u->c;
  uint32_t fill = u->partyPalette ? pd_ui_status_color(label)
                : !strcmp(label, "FNT") ? 0x6B6B6B : !strcmp(label, "PSN") || !strcmp(label, "TOX") ? 0xA040A0
                : !strcmp(label, "PAR") ? 0xB8A038 : !strcmp(label, "BRN") ? 0xE07030
                : !strcmp(label, "FRZ") ? 0x58A8C8 : 0x8C8C8C;
  if (mini) { // a tile is 15 pixels and the tiny font has digits only: the status is its colour, inside the tile
    pd_round_rect(c, cx - (TILE - 3) / 2, y, TILE - 3, TAG_H - 2, 1, fill);
    return;
  }
  int w = pd_text_width(label) + 6;
  pd_round_rect(c, cx - w / 2, y, w, 13, 2, fill);
  pd_text(c, cx - w / 2 + 3, y - 2, label, 0xFFFFFF, PD_NO_SHADOW);
}

static const char *mon_status(const struct pd_mon *m) {
  return m->species == PD_SPECIES_EGG ? "" : m->hp == 0 ? "FNT" : pd_status_label(m->status);
}

// A party icon at half size (the mini HUD): Scale2x, then each 4x4 block of that is one pixel - its solid pixels'
// average, where at least half the block is solid.
#define MINI_ICON (PD_ICON / 2)
static void icon_half_draw(struct pd_canvas *c, int x, int y) {
  const int n = 2 * PD_ICON;
  icon_scale2x();
  for (int oy = 0; oy < MINI_ICON; oy++)
    for (int ox = 0; ox < MINI_ICON; ox++) {
      int r = 0, g = 0, b = 0, k = 0;
      for (int j = 0; j < 16; j++) {
        uint32_t p = icon2x[(4 * oy + j / 4) * n + 4 * ox + j % 4];
        if (p == SHRUNK_CLEAR || p >> 24)
          continue;
        r += (int)(p >> 16 & 0xFF), g += (int)(p >> 8 & 0xFF), b += (int)(p & 0xFF), k++;
      }
      if (k >= 8)
        pd_fill(c, x + ox, y + oy, 1, 1, (uint32_t)(r / k) << 16 | (uint32_t)(g / k) << 8 | (uint32_t)(b / k));
    }
}

// One member's icon at (x, y) in a TILE-wide slot, faded when fainted.
static void mon_icon(struct hud *u, const struct pd_mon *m, int x, int y) {
  bool egg = m->species == PD_SPECIES_EGG, fainted = !egg && m->hp == 0;
  int ix = x + (TILE - PD_ICON) / 2;
  if (mini) {
    struct pd_canvas shrunk = icon_begin();
    if (u->monDraw)
      u->monDraw(&shrunk, u->monCtx, m, 0, 0, fainted);
    else
      pd_draw_mon_icon_shiny(&shrunk, u->g, m->species, m->shiny, 0, 0, 1, fainted);
    icon_half_draw(u->c, x + (TILE - MINI_ICON) / 2, y - 3);
  } else if (u->monDraw) {
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
#define SHOWN_ICON_H (mini ? 12 : COMPACT_ICON_H)
#define TILE_H (small ? SHOWN_ICON_H + 5 : 26 + 5)

static int party_tag_height(const struct hud *u) {
  for (int i = 0; i < u->partyN; i++)
    if (*mon_status(&u->s->party[i]))
      return TAG_H;
  return 0;
}

static int tiles_width(const struct hud *u) { return u->partyN ? u->partyN * TILE + (u->partyN - 1) * TILE_GAP : 0; }

// The party as tiles (HudMonTile): one status row when needed, then the sprite and HP bar.
static void party_tiles(struct hud *u, int x, int y) {
  const int tag = party_tag_height(u);
  const int iconH = small ? SHOWN_ICON_H : 26;
  for (int i = 0; i < u->partyN; i++, x += TILE + TILE_GAP) {
    const struct pd_mon *m = &u->s->party[i];
    const char *status = mon_status(m);
    mon_icon(u, m, x, y + tag);
    if (*status)
      status_chip(u, x + TILE / 2, y, status);
    hit(u, x, y, TILE, tag + TILE_H, HUD_SLOT + i);
    if (m->species != PD_SPECIES_EGG)
      hp_bar(u, x, y + tag + iconH, TILE, m->hp, m->maxHp);
  }
}

static int party_height(const struct hud *u) {
  return 2 * PAD + 2 + TILE_H + party_tag_height(u);
}
static int party_width(const struct hud *u) { return u->partyN ? 2 * PAD + 2 + tiles_width(u) : 0; }

static void party_row(struct hud *u, int x, int y) {
  panel(u, x, y, party_width(u), party_height(u));
  party_tiles(u, x + PAD + 1, y + PAD + 1);
}

// RAILS' party: the icon beside the status tag and HP bar (HudMonRow).
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
    int tx = rx + TILE + 3, hpW = w - 2 * PAD - 2 - TILE - 6;
    const char *status = mon_status(m);
    if (*status)
      status_chip(u, tx + hpW / 2, ry + 4, status);
    hp_bar(u, tx, ry + (*status ? 20 : (MON_ROW_H - 5) / 2), hpW, m->hp, m->maxHp);
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

#define ITEM_SZ (mini ? MINI_ITEM : PD_ITEM_ICON)
static int count_w(const struct pd_item *b) {
  char t[12];
  snprintf(t, sizeof t, "x%d", b->quantity);
  return mini ? tiny_width(t) : pd_text_width(t);
}

// One ball and its count (BallCount); Ruby / Sapphire keep no item icons: a drawn ball in its place.
static int ball_count(struct hud *u, const struct pd_item *b, int x, int y) {
  if (mini) {
    char t[12];
    snprintf(t, sizeof t, "%d", b->quantity);
    mini_item(u, b->id, x, y, NULL);
    return MINI_ITEM + 1 + tiny_text(u->c, x + MINI_ITEM + 1, y + (MINI_ITEM - TINY_H) / 2, t, u->L->text, u->L->shadow);
  }
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
    w += ITEM_SZ + 1 + count_w(&u->ball[i]) + (i ? (mini ? 3 : 4) : 0);
  return w;
}

static void balls_inline(struct hud *u, int x, int y) {
  hit(u, x - 2, y - 2, balls_inline_w(u) + 4, ITEM_SZ + 4, HUD_BALLS);
  for (int i = 0; i < u->balls; i++)
    x += ball_count(u, &u->ball[i], x, y) + (mini ? 3 : 4);
}

static int balls_w(const struct hud *u, bool vertical) {
  if (!vertical)
    return 2 * PAD + (mini ? 2 : 4) + balls_inline_w(u);
  int w = 0;
  for (int i = 0; i < u->balls; i++)
    if (count_w(&u->ball[i]) > w)
      w = count_w(&u->ball[i]);
  return 2 * PAD + 2 + ITEM_SZ + 1 + w;
}
static int balls_h(const struct hud *u, bool vertical) { return 2 * PAD + (vertical ? u->balls : 1) * ITEM_SZ; }

static void balls_panel(struct hud *u, int x, int y, bool vertical) {
  panel(u, x, y, balls_w(u, vertical), balls_h(u, vertical));
  hit(u, x, y, balls_w(u, vertical), balls_h(u, vertical), HUD_BALLS);
  int bx = x + PAD + (mini ? 1 : 2), by = y + PAD;
  for (int i = 0; i < u->balls; i++) {
    int w = ball_count(u, &u->ball[i], bx, by);
    if (vertical)
      by += ITEM_SZ;
    else
      bx += w + (mini ? 3 : 4);
  }
}

// RAILS' narrow one: the count over a shorter bar, no "steps".
#define MINI_BAR 20
static int repel_inner_w(const struct hud *u, bool narrow) {
  char t[12];
  snprintf(t, sizeof t, "%d", u->h->repel);
  if (mini)
    return MINI_ITEM + 2 + (tiny_width(t) > MINI_BAR ? tiny_width(t) : MINI_BAR);
  int words = pd_text_width(t) + (narrow ? 0 : 3 + pd_text_width("steps")), bar = narrow ? 30 : 46;
  return PD_ITEM_ICON + 3 + (words > bar ? words : bar);
}
#define REPEL_INNER_H (mini ? MINI_ITEM : PD_ITEM_ICON + 2)
static int repel_w(const struct hud *u, bool narrow) { return 2 * PAD + repel_inner_w(u, narrow) + 2; }
#define REPEL_H (2 * PAD + REPEL_INNER_H)

// REPEL's steps left while one works (RepelSteps): the spray, the count (gold for the last 20) over a bar
// draining green / yellow / red against the steps it started from.
static void repel_inner(struct hud *u, int x, int y, bool narrow) {
  const struct hud_state *h = u->h;
  if (mini) { // the spray, the count over a thin bar
    mini_item(u, u->itemDraw ? u->repelItem : h->repelItem ? h->repelItem : 86, x, y, quick_icon_repel);
    char t[12];
    snprintf(t, sizeof t, "%d", h->repel);
    int tx = x + MINI_ITEM + 2, full = h->repelFull > 0 ? h->repelFull : 100;
    tiny_text(u->c, tx, y + 1, t, h->repel <= 20 ? u->L->accent : u->L->text, u->L->shadow);
    int left = h->repel * (MINI_BAR - 2) / full;
    if (left > MINI_BAR - 2)
      left = MINI_BAR - 2;
    uint32_t fill = h->repel * 2 > full ? 0x58D080 : h->repel * 5 > full ? 0xF8C030 : 0xE84838;
    pd_fill(u->c, tx, y + MINI_ITEM - 4, MINI_BAR, 3, 0x00405060);
    pd_fill(u->c, tx + 1, y + MINI_ITEM - 3, left, 1, fill);
    return;
  }
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

// The mini HUD's buttons: the same art, no label.
#define MINI_BUTTON 20 // square, the 16 x 16 art in the middle

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
  if (mini)
    return MINI_BUTTON;
  int w = pd_text_width(button_label(id)) + 6;
  return w > 34 ? w : 34;
}

static int buttons_w(const struct hud *u, bool vertical) {
  int w = 0;
  for (int i = 0; i < u->buttons; i++) {
    int b = button_w(u->button[i]);
    w = vertical ? (b > w ? b : w) : w + b + (i ? (mini ? 2 : 3) : 0);
  }
  return w;
}
static int buttons_h(const struct hud *u, bool vertical) {
  int bh = mini ? MINI_BUTTON : BUTTON_H, gap = mini ? 2 : 3;
  return u->buttons ? (vertical ? u->buttons * bh + (u->buttons - 1) * gap : bh) : 0;
}

// A square button (HudButton): pixel art over a small label, WILD's red count on its corner.
static void button(struct hud *u, int id, int x, int y, int w, bool framed) {
  if (mini) {
    if (framed)
      panel(u, x, y, w, MINI_BUTTON);
    hit(u, x, y, w, MINI_BUTTON, id);
    int ax = x + (w - 16) / 2, ay = y + (MINI_BUTTON - 16) / 2;
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
    if (id == HUD_WILD && u->h->wild > 0) {
      char t[12];
      snprintf(t, sizeof t, "%d", u->h->wild);
      int bw = tiny_width(t) + 4;
      pd_fill(u->c, x + w - bw, y - 2, bw, TINY_H + 2, 0xE83028);
      tiny_text(u->c, x + w - bw + 2, y - 1, t, 0xFFFFFF, PD_NO_SHADOW);
    }
    return;
  }
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
    char t[12];
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
      y += (mini ? MINI_BUTTON : BUTTON_H) + (mini ? 2 : 3);
    else
      x += bw + (mini ? 2 : 3);
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
    party_row(u, u->x0 + EDGE, H - EDGE - party_height(u));
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
  int contentH = u->partyN ? TILE_H + party_tag_height(u) : 0;
  if ((u->buttons || u->balls || u->repel) && contentH < BUTTON_H)
    contentH = BUTTON_H;
  int barW = 2 * 5 + inner, barH = 2 * PAD + 2 + contentH;
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
      party_tiles(u, x, mid - (TILE_H + party_tag_height(u)) / 2);
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
  mini = h->mini;
  small = h->compact || mini; // the mini HUD's party and minimap are the compact ones, its party icons halved
  sizes = mini ? &MINI_SIZES : small ? &COMPACT_SIZES : &NORMAL_SIZES;
  struct hud u = {.c = c, .g = g, .s = s, .h = h, .st = st, .out = out, .x0 = h->left, .x1 = h->right ? h->right : c->w,
                  .L = hud_ink(st->hudLook)};
  // WINDOW on a Game Boy game: the game's own black-on-white window (OptionColors' Gen 1 look), not FireRed's.
  static struct hud_ink gbWindow;
  if (u.L->window && pd_gb_look) {
    gbWindow = *u.L;
    gbWindow.fill = PD_GB_PAPER;
    gbWindow.edge = gbWindow.text = gbWindow.muted = PD_GB_INK;
    gbWindow.shadow = PD_GB_PAPER;
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
  hud_focus_draw(c, out, h->focus);
  return true;
}

/* The party row alone, for the Linux host's DSperate backend (dsperate/frontend.cpp): the default look, its art from
 * [draw], the canvas not cleared. */
void hud_party_draw(struct pd_canvas *c, const struct pd_snapshot *s, void *ctx, hud_mon_draw_fn draw) {
  static const struct pd_settings defaults;
  static const struct hud_state none = {.wild = -1, .focus = -1};
  if (!s || !s->valid || s->partyCount <= 0 || !draw)
    return;
  small = mini = false;
  sizes = &NORMAL_SIZES;
  struct hud u = {.c = c, .s = s, .h = &none, .st = &defaults, .monDraw = draw, .monCtx = ctx, .L = &LOOKS[0], .x1 = c->w};
  u.partyN = s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE;
  if (c->w >= party_width(&u) + 2 * EDGE && c->h >= party_height(&u) + 2 * EDGE)
    party_row(&u, EDGE, c->h - EDGE - party_height(&u));
}

void hud_ds_draw(struct pd_canvas *c, const struct pd_snapshot *s, const struct hud_state *h,
                 const struct pd_settings *st, void *ctx, hud_mon_draw_fn monDraw,
                 hud_item_draw_fn itemDraw, int repelItem, bool minimal, int right, struct hud_hits *out,
                 const struct pd_party_source_palette *partyPalette) {
  if (out) out->n = 0;
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  if (!s->valid)
    return;
  mini = h->mini;
  small = mini || right < c->w;
  sizes = mini ? &MINI_SIZES : small ? &COMPACT_SIZES : &NORMAL_SIZES;
  struct hud u = {.c = c, .s = s, .h = h, .st = st, .monDraw = monDraw, .monCtx = ctx, .itemDraw = itemDraw, .x1 = right,
                  .repelItem = repelItem, .out = out, .partyPalette = partyPalette,
                  .L = hud_ink(st->hudLook)};
  u.partyN = !minimal && shows(&u, PD_HUD_PARTY) ? s->partyCount : 0;
  u.balls = shows(&u, PD_HUD_BALLS) ? balls(s, u.ball) : 0;
  u.repel = shows(&u, PD_HUD_REPEL) && h->repel > 0;
  if (!h->noButtons) {
    if (h->pc && shows(&u, PD_HUD_PC))
      u.button[u.buttons++] = HUD_PC;
    if (shows(&u, PD_HUD_BAG))
      u.button[u.buttons++] = HUD_BAG;
    if (h->wild >= 0 && shows(&u, PD_HUD_WILD))
      u.button[u.buttons++] = HUD_WILD;
    u.button[u.buttons++] = HUD_COMPANION;
  }
  const int available = right - 2 * EDGE;
  while (u.buttons > 1 && buttons_w(&u, false) > available) {
    if (u.buttons == 2 && u.button[1] == HUD_BAG)
      u.button[0] = HUD_BAG;
    u.buttons--;
  }
  while (u.balls > 1 && balls_w(&u, false) > available)
    u.balls--;
  if (minimal) {
    if (!u.balls && !u.repel && !u.buttons && shows(&u, PD_HUD_PARTY) && s->partyCount) {
      u.partyN = 1;
      party_row(&u, EDGE, c->h - EDGE - party_height(&u));
    } else compact(&u);
    return;
  }
  if (u.partyN)
    party_row(&u, EDGE, c->h - EDGE - party_height(&u));
  int width = column_w(&u, BOTTOM_UP, 3, false);
  int bottom = c->h - EDGE - (tight(&u) ? party_height(&u) + GAP : 0);
  column(&u, BOTTOM_UP, 3, false, right - EDGE - width, width, 1, bottom, true);
}

void hud_ds_minimap(struct pd_canvas *c, const struct pd_settings *st, const char *name, hud_ds_map_fn draw, struct hud_hits *out) {
  small = false;
  mini = st->hudSize != PD_HUD_LARGE;
  sizes = mini ? &MINI_SIZES : &NORMAL_SIZES;
  const int w = sizes->mapW, h = sizes->mapH;
  struct hud u = {.c=c, .x1=c->w, .out=out, .L=hud_ink(st->hudLook)};
  const int x=c->w-EDGE-minimap_w(w), y=EDGE;
  panel(&u,x,y,minimap_w(w),minimap_h(h,1));
  hit(&u,x,y,minimap_w(w),minimap_h(h,1),HUD_MAP);
  draw(c,x+PAD,y+PAD,w,h);
  pd_text_fit(c,x+PAD,y+PAD+h+1,w,name,u.L->text,u.L->shadow);
}

int hud_compact_map_width(void) { return COMPACT_SIZES.mapW + 2 * COMPACT_SIZES.pad; }

uint32_t hud_key(const struct pd_game *g, const struct pd_snapshot *s, const struct hud_state *h, const struct pd_settings *st) {
  uint32_t k = 2166136261u;
#define MIX(v) (k = (k ^ (uint32_t)(v)) * 16777619u)
  MIX(st->hudHidden);
  MIX(st->hudLayout);
  MIX(st->hudLook);
  MIX(h->mini);
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
