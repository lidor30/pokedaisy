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
#define PAD 4
#define EDGE 6 // CornersLayout's padding
#define TILE 30
#define TILE_GAP 3
#define MAP_W 104
#define MAP_H 64
#define LINE 12

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
#define PARTY_H (2 * PAD + 2 + 26 + LINE + 6)

static void party_row(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s, int x, int y,
                      void *ctx, hud_mon_draw_fn draw) {
  int n = s->partyCount < PD_PARTY_SIZE ? s->partyCount : PD_PARTY_SIZE;
  panel(c, x, y, party_width(s), PARTY_H);
  int tx = x + PAD + 1, ty = y + PAD + 1;
  for (int i = 0; i < n; i++, tx += TILE + TILE_GAP) {
    const struct pd_mon *m = &s->party[i];
    bool egg = m->species == PD_SPECIES_EGG, fainted = !egg && m->hp == 0;
    if (i == 0) { // the lead (the one that goes out first): a lit tile with an accent edge
      pd_round_rect(c, tx - 2, ty - 2, TILE + 4, PARTY_H - 2 * PAD - 2 + 4, 3, ACCENT);
      pd_round_rect(c, tx - 1, ty - 1, TILE + 2, PARTY_H - 2 * PAD - 2 + 2, 3, LEAD_FILL);
    }
    if (draw)
      draw(c, ctx, m, tx + (TILE - PD_ICON) / 2, ty - 5, fainted);
    else
      pd_draw_mon_icon(c, g, m->species, tx + (TILE - PD_ICON) / 2, ty - 5, fainted);
    if (egg)
      continue;
    const char *status = fainted ? "FNT" : pd_status_label(m->status);
    if (*status)
      status_chip(c, tx + TILE / 2, ty - 3, status);
    char lv[8];
    snprintf(lv, sizeof lv, "Lv%d", m->level);
    pd_text(c, tx + (TILE - pd_text_width(lv)) / 2, ty + 24, lv, INK, INK_SHADOW);
    hp_bar(c, tx, ty + 26 + LINE, TILE, m->hp, m->maxHp);
  }
}

void hud_party_draw(struct pd_canvas *c, const struct pd_snapshot *s, void *ctx, hud_mon_draw_fn draw) {
  if (s && s->valid && s->partyCount > 0 && draw && c->w >= party_width(s) + 2 * EDGE && c->h >= PARTY_H + 2 * EDGE)
    party_row(c, NULL, s, EDGE, c->h - EDGE - PARTY_H, ctx, draw);
}

#define MINIMAP_W (MAP_W + 2 * PAD)
#define MINIMAP_H (MAP_H + 2 * PAD + 3 + LINE)

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
    pd_clip(c, mx, my + MAP_H + 1, MAP_W, LINE + 4);
    pd_text(c, mx, my + MAP_H + 1, name, INK, INK_SHADOW);
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

bool hud_draw(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s,
              const struct hud_state *h, int hidden) {
  for (int i = 0; i < c->w * c->h; i++)
    c->px[i] = QUICK_CLEAR;
  if (!s || !s->valid)
    return false;
  bool any = false;
  int mapBottom = EDGE;
  if (!(hidden & 1 << PD_HUD_MAP) && minimap(c, g, s, c->w - EDGE - MINIMAP_W, EDGE)) {
    any = true;
    mapBottom = EDGE + MINIMAP_H + 4;
  }
  int partyRight = 0;
  if (!(hidden & 1 << PD_HUD_PARTY) && s->partyCount > 0) {
    party_row(c, g, s, EDGE, c->h - EDGE - PARTY_H, NULL, NULL);
    partyRight = EDGE + party_width(s);
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
    bool top = partyRight && c->w - EDGE - colW < partyRight + 4;
    int y = top ? mapBottom : c->h - EDGE - colH;
    if (repel) {
      repel_panel(c, g, h, c->w - EDGE - repel_width(h->repel), y);
      y += REPEL_H + 4;
    }
    if (nb)
      balls_panel(c, g, b, nb, c->w - EDGE - balls_width(b, nb), y);
    any = true;
  }
  return any;
}

uint32_t hud_key(const struct pd_snapshot *s, const struct hud_state *h, int hidden) {
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
    MIX(s->posOk ? s->x : 0);
    MIX(s->posOk ? s->y : 0);
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
