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
    {0xB8121620u, 0x66FFFFFFu, 0xF4F6FA, 0x0C0F15, 0xFFCF5A, 0xB2BACB, 0x59FFFFFFu, false},
    {0xC4F6F7FAu, 0x59000000u, 0x262B34, 0xD9DCE3, 0xC8321E, 0x6A7282, 0x4D000000u, false},
    {PD_TITLE_FILL, PD_MUTED, PD_LABEL, PD_LABEL_SHADOW, PD_VALUE, PD_MUTED, 0xFFE9A8, true},
};

#define PAD 4
#define EDGE 6 // CornersLayout's padding
#define GAP 4  // between a column's windows
#define TILE 30
#define TILE_GAP 3
#define LINE 12
#define MON_ROW_H 30 // RAILS' party rows
#define MON_ROW_W (TILE + 4 + 44)
#define BUTTON_H (3 + 16 + 1 + LINE + 3)
#define RAIL_MIN 70 // a rail's least width (FieldHud's u * 50; COMPANION's button in this font)

struct hud {
  struct pd_canvas *c;
  const struct pd_game *g;
  const struct pd_snapshot *s;
  const struct hud_state *h;
  const struct pd_settings *st;
  const struct look *L;
  struct hud_hits *out;
  int partyN;           // party members shown (0: PARTY off)
  struct pd_item ball[3];
  int balls;            // ball kinds shown, most first
  bool repel, map;      // REPEL STEPS showing; MINIMAP on and something to show
  int button[5], buttons; // the buttons shown, in order (enum hud_target)
};

static bool shows(const struct hud *u, int part) { return !(u->st->hudHidden >> part & 1); }

static void hit(struct hud *u, int x, int y, int w, int h, int id) {
  if (u->out && u->out->n < HUD_MAX_HITS)
    u->out->at[u->out->n++] = (__typeof__(u->out->at[0])){x, y, w, h, id};
}

static void panel(struct hud *u, int x, int y, int w, int h) {
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

// One member's icon at (x, y) in a TILE-wide slot: faded when fainted, its status chip over the top.
static void mon_icon(struct hud *u, const struct pd_mon *m, int x, int y) {
  bool egg = m->species == PD_SPECIES_EGG, fainted = !egg && m->hp == 0;
  pd_draw_mon_icon(u->c, u->g, m->species, x + (TILE - PD_ICON) / 2, y - 5, fainted);
  const char *status = egg ? "" : fainted ? "FNT" : pd_status_label(m->status);
  if (*status)
    status_chip(u->c, x + TILE / 2, y - 3, status);
}

// The lead (the one that goes out first): a lit cell with an accent edge.
static void lead(struct hud *u, int x, int y, int w, int h) {
  pd_round_rect(u->c, x, y, w, h, 3, u->L->accent);
  pd_round_rect(u->c, x + 1, y + 1, w - 2, h - 2, 3, u->L->cellOn);
}

#define TILE_H (26 + LINE + 6)
static int tiles_width(const struct hud *u) { return u->partyN ? u->partyN * TILE + (u->partyN - 1) * TILE_GAP : 0; }

// The party as tiles from (x, y) (HudMonTile): icon, then Lv and the HP bar under it.
static void party_tiles(struct hud *u, int x, int y) {
  for (int i = 0; i < u->partyN; i++, x += TILE + TILE_GAP) {
    const struct pd_mon *m = &u->s->party[i];
    if (i == 0)
      lead(u, x - 2, y - 2, TILE + 4, TILE_H + 4);
    mon_icon(u, m, x, y);
    hit(u, x - 2, y - 2, TILE + 4, TILE_H + 4, HUD_SLOT + i);
    if (m->species == PD_SPECIES_EGG)
      continue;
    char lv[8];
    snprintf(lv, sizeof lv, "Lv%d", m->level);
    text(u, x + (TILE - pd_text_width(lv)) / 2, y + 24, lv, u->L->text);
    hp_bar(u->c, x, y + 26 + LINE, TILE, m->hp, m->maxHp);
  }
}

#define PARTY_H (2 * PAD + 2 + TILE_H)
static int party_width(const struct hud *u) { return u->partyN ? 2 * PAD + 2 + tiles_width(u) : 0; }

static void party_row(struct hud *u, int x, int y) {
  panel(u, x, y, party_width(u), PARTY_H);
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
    if (i == 0)
      lead(u, rx - 1, ry - 1, w - 2 * PAD, MON_ROW_H + 2);
    mon_icon(u, m, rx, ry + 2);
    hit(u, rx - 1, ry - 1, w - 2 * PAD, MON_ROW_H + 2, HUD_SLOT + i);
    if (m->species == PD_SPECIES_EGG)
      continue;
    char lv[8];
    snprintf(lv, sizeof lv, "Lv%d", m->level);
    int tx = rx + TILE + 3;
    text(u, tx, ry + 2, lv, u->L->text);
    hp_bar(u->c, tx, ry + 4 + LINE + 2, w - 2 * PAD - 2 - TILE - 6, m->hp, m->maxHp);
  }
}

// ---- Minimap ------------------------------------------------------------------------------------

static bool has_minimap(const struct hud *u) {
  const char *name = pd_mapsec_name(u->g, u->s->mapsec);
  return pd_map_art(u->g) || (name && *name);
}

static int minimap_w(int mw) { return mw + 2 * PAD; }
static int minimap_h(int mh, int lines) { return mh + 2 * PAD + 3 + lines * LINE; }

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
    if (left > PD_MAP_W - mw)
      left = PD_MAP_W - mw;
    if (top > PD_MAP_H - mh)
      top = PD_MAP_H - mh;
    if (left < 0)
      left = 0;
    if (top < 0)
      top = 0;
    const uint32_t *px = a->page[page];
    for (int yy = 0; yy < mh && top + yy < PD_MAP_H; yy++)
      for (int xx = 0; xx < mw && left + xx < PD_MAP_W; xx++) {
        uint32_t p = px[(top + yy) * PD_MAP_W + left + xx];
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
    pd_clip(u->c, mx, my + mh + 1, mw, lines * LINE + 4);
    if (lines > 1)
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
  if (!pd_draw_item_icon(u->c, u->g, b->id, x, y, false))
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
  pd_draw_item_icon(u->c, u->g, 86, x, y + 1, false); // the plain REPEL's (the game doesn't keep which)
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
  column(u, BOTTOM_UP, 3, false, EDGE, column_w(u, BOTTOM_UP, 3, false), -1, u->c->h - EDGE, true);
}

static bool tight(const struct hud *u) {
  return u->partyN && party_width(u) + column_w(u, BOTTOM_UP, 3, false) + 3 * EDGE > u->c->w;
}

// CORNERS / CORNERS 2 ([swapped]: the minimap bottom right, the buttons top right).
static void corners(struct hud *u, bool swapped) {
  if (tight(u)) {
    compact(u);
    return;
  }
  int W = u->c->w, H = u->c->h;
  if (u->map)
    minimap(u, W - EDGE - minimap_w(104), swapped ? H - EDGE - minimap_h(64, 1) : EDGE, 104, 64, 1);
  if (u->partyN)
    party_row(u, EDGE, H - EDGE - PARTY_H);
  int cw = column_w(u, BOTTOM_UP, 3, false);
  if (swapped)
    column(u, TOP_DOWN, 3, false, W - EDGE - cw, cw, 1, EDGE, false);
  else
    column(u, BOTTOM_UP, 3, false, W - EDGE - cw, cw, 1, H - EDGE, true);
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
  int barW = 2 * 5 + inner, barH = 2 * PAD + 2 + (u->partyN ? TILE_H : BUTTON_H);
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
      pd_fill(u->c, x + 3, mid - 15, 1, 30, u->L->window ? PD_MUTED : u->L->edge);
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
  struct hud u = {.c = c, .g = g, .s = s, .h = h, .st = st, .out = out,
                  .L = &LOOKS[st->hudLook >= 0 && st->hudLook < PD_HUD_LOOKS ? st->hudLook : 0]};
  u.partyN = shows(&u, PD_HUD_PARTY) ? (s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE) : 0;
  u.balls = shows(&u, PD_HUD_BALLS) ? balls(s, u.ball) : 0;
  u.repel = shows(&u, PD_HUD_REPEL) && h->repel > 0;
  u.map = shows(&u, PD_HUD_MAP) && has_minimap(&u);
  bool bag = false;
  for (int p = 0; s->bagOk && p < PD_POCKET_COUNT; p++)
    bag |= s->bag[p].count > 0;
  // HudButtons' order: PC, BAG, WILD, then COMPANION (one screen: the side panel's button).
  if (h->pc && shows(&u, PD_HUD_PC))
    u.button[u.buttons++] = HUD_PC;
  if (bag && shows(&u, PD_HUD_BAG))
    u.button[u.buttons++] = HUD_BAG;
  if (h->wild >= 0 && shows(&u, PD_HUD_WILD))
    u.button[u.buttons++] = HUD_WILD;
  u.button[u.buttons++] = HUD_COMPANION;
  switch (st->hudLayout) {
  case PD_HUD_CORNERS_2: corners(&u, true); break;
  case PD_HUD_RAILS: rails(&u); break;
  case PD_HUD_DOCK: dock(&u); break;
  default: corners(&u, false); break;
  }
  // The HUD hotkey's cursor: the quick menu's focus ring round the focused target.
  for (int i = 0; out && i < out->n; i++)
    if (out->at[i].id == h->focus) {
      int x = out->at[i].x - 3, y = out->at[i].y - 3, w = out->at[i].w + 6, hh = out->at[i].h + 6;
      pd_fill(c, x, y, w, 2, u.L->accent);
      pd_fill(c, x, y + hh - 2, w, 2, u.L->accent);
      pd_fill(c, x, y, 2, hh, u.L->accent);
      pd_fill(c, x + w - 2, y, 2, hh, u.L->accent);
    }
  return true;
}

uint32_t hud_key(const struct pd_snapshot *s, const struct hud_state *h, const struct pd_settings *st) {
  uint32_t k = 2166136261u;
#define MIX(v) (k = (k ^ (uint32_t)(v)) * 16777619u)
  MIX(st->hudHidden);
  MIX(st->hudLayout);
  MIX(st->hudLook);
  MIX(h->repel);
  MIX(h->repelFull);
  MIX(h->pc);
  MIX(h->wild);
  MIX(h->focus);
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
    MIX(s->posOk ? s->x : 0);
    MIX(s->posOk ? s->y : 0);
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
