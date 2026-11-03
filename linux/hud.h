/* The HUD over the overworld: the app's FieldHud.kt in its default CORNERS layout and GLASS look, the parts a
 * player reads (PARTY, MINIMAP, POKé BALLS, REPEL STEPS). Its buttons (BAG, PC, WILD, COMPANION) are left out:
 * without a touch screen they're the QUICK MENU's. Drawn into a canvas whose see-through pixels carry their alpha,
 * like the quick menu's. GPL-3.0, see ../NOTICE. */
#ifndef POCKET_HUD_H
#define POCKET_HUD_H
#include "pd_canvas.h"
#include "pd_game.h"
#include "pd_snapshot.h"
#include <stdbool.h>
#include <stdint.h>

struct hud_state {
  int repel;     // the Repel's steps left (0 = none working)
  int repelFull; // the steps it started from (the smallest of 100 / 200 / 250 covering the first count seen)
};

/* Draws the HUD's shown parts ([hidden]: a bit per enum pd_hud_part) over QUICK_CLEAR; false if nothing shows. */
bool hud_draw(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s,
              const struct hud_state *h, int hidden);
/* Draw the same party HUD for another backend. Species art is supplied by that
 * backend; no GBA ROM tables are consulted. Does not clear the canvas. */
typedef void (*hud_mon_draw_fn)(struct pd_canvas*, void*, const struct pd_mon*, int x, int y, bool faded);
void hud_party_draw(struct pd_canvas *c, const struct pd_snapshot *s, void *ctx, hud_mon_draw_fn draw);
/* A number that changes whenever what the HUD shows does (so the host redraws it only then). */
uint32_t hud_key(const struct pd_snapshot *s, const struct hud_state *h, int hidden);
/* REPEL STEPS' full bar for a count first seen as [steps] (FieldHud's repelFull). */
int hud_repel_full(int steps);

#endif
