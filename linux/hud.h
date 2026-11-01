/* The HUD over the overworld: the app's FieldHud.kt - its four layouts (CORNERS, CORNERS 2, RAILS, DOCK, and the
 * compact one when the party doesn't fit beside the buttons), three looks (GLASS, WHITE GLASS, WINDOW) and every part
 * (PARTY, MINIMAP, POKé BALLS, REPEL STEPS and the PC / BAG / WILD / COMPANION buttons). SIZE is the host's: the
 * canvas scale one step smaller or bigger. Without a touch screen the buttons, party and minimap are reached with
 * the HUD hotkey: hud_draw lists where each one is (struct hud_hits) and rings the focused one. Drawn into a canvas
 * whose see-through pixels carry their alpha, like the quick menu's. GPL-3.0, see ../NOTICE. */
#ifndef POCKET_HUD_H
#define POCKET_HUD_H
#include "pd_canvas.h"
#include "pd_game.h"
#include "pd_snapshot.h"
#include "pd_ui.h"
#include <stdbool.h>
#include <stdint.h>

struct hud_state {
  int repel;     // the Repel's steps left (0 = none working)
  int repelFull; // the steps it started from (the smallest of 100 / 200 / 250 covering the first count seen)
  bool pc;       // the game's PC can open from here (pc_supported): the PC button
  int wild;      // this map's wild species not caught yet (WILD's badge); -1 = no wild data, no WILD button
  int focus;     // the focused target (enum hud_target), -1 = none: the HUD hotkey's cursor
};

/* What a press on the HUD can reach: a party slot, the minimap, the balls, REPEL STEPS, a button. */
enum hud_target {
  HUD_SLOT = 0, // + party slot (0-5)
  HUD_MAP = 6,
  HUD_BALLS,
  HUD_REPEL,
  HUD_PC,
  HUD_BAG,
  HUD_WILD,
  HUD_MAP_BUTTON, // the compact HUD's MAP (no minimap there)
  HUD_COMPANION,
  HUD_TARGETS
};
#define HUD_MAX_HITS 16
struct hud_hits {
  int n;
  struct { int x, y, w, h, id; } at[HUD_MAX_HITS];
};

/* Draws the HUD's shown parts in the settings' layout and look over QUICK_CLEAR; false if nothing shows. [out]
 * (nullable): where each target landed. */
bool hud_draw(struct pd_canvas *c, const struct pd_game *g, const struct pd_snapshot *s, const struct hud_state *h,
              const struct pd_settings *st, struct hud_hits *out);
/* A number that changes whenever what the HUD shows does (so the host redraws it only then). */
uint32_t hud_key(const struct pd_snapshot *s, const struct hud_state *h, const struct pd_settings *st);
/* REPEL STEPS' full bar for a count first seen as [steps] (FieldHud's repelFull). */
int hud_repel_full(int steps);

#endif
