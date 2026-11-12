/* The QUICK MENU (the Y button): the app's QuickMenu.kt in RING or GRID, using the HUD look, drawn into
 * a canvas whose see-through pixels carry their alpha (the host lays it over the game). GPL-3.0. */
#ifndef POCKET_QUICK_MENU_H
#define POCKET_QUICK_MENU_H
#include "pd_canvas.h"
#include "pd_game.h"
#include <stdbool.h>

enum quick_action { QUICK_PC, QUICK_FLY, QUICK_REPEL, QUICK_BIKE, QUICK_WILD, QUICK_SAVE_STATE, QUICK_LOAD_STATE, QUICK_FF, QUICK_COMPANION, QUICK_HUD, QUICK_FLASH, QUICK_FLIGHT, QUICK_LAND, QUICK_COUNT };
/* QUICK_HUD: the HUD cursor (the libretro core, which has no hotkey of its own to reach it). */

struct quick_item {
  enum quick_action action;
  bool off;          // WILD ENCOUNTERS switched off: an OFF tag (QuickTile's)
  bool lit;          // FF running
  const char *why;   // why it can't run now (dimmed), or NULL
  int species;       // FLY's icon: the Pokémon that would carry the player (0 = none)
  int item;          // REPEL's / BIKE's icon: the bag's own item (0 = none)
  const char *label; // in place of the action's name (BIKE's GET OFF while riding), or NULL
};

/* What a canvas pixel holds where nothing was drawn (fully see-through). */
#define QUICK_CLEAR 0x00FF00FFu

/* Draws the ring (n tiles, sel highlighted) and the middle's hint over QUICK_CLEAR; returns false if it
 * doesn't fit the canvas. */
bool quick_draw(struct pd_canvas *c, const struct pd_game *g, const struct quick_item *items, int n, int sel, int look);
/* FLY's places / REPEL's kinds (QuickMenu.kt's PickList): a titled list over QUICK_CLEAR, row sel highlighted,
 * scrolled to keep it in view. */
void quick_list_draw(struct pd_canvas *c, const char *title, const char *const *rows, int n, int sel, int look);
/* The tile a D-pad press (dx, dy: -1 / 0 / 1) moves to from sel: the nearest one that way round the ring. */
int quick_step(int n, int sel, int dx, int dy, int w, int h);
/* RING (default) or GRID layout for the next draw / step (settings.quickGrid). */
void quick_set_grid(bool grid);
/* The tile (quick_draw) or list row (quick_list_draw) the last draw put under (x, y), canvas pixels; -1 = none. */
int quick_hit(int x, int y);
int quick_list_hit(int x, int y);
const char *quick_label(enum quick_action a);
/* Pixel art shared with the HUD's buttons, a character a pixel ('.' clear), NULL-terminated. */
extern const char *const quick_icon_pc[], *const quick_icon_grass[], *const quick_icon_companion[];
extern const char *const quick_icon_repel[];
/* After drawing: every pixel opaque but the clear ones and the glass's own alpha. */
void quick_finish(struct pd_canvas *c);
#endif
