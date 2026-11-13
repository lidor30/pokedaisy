/* FLY from the QUICK MENU: the app's Fly.kt in C, over the config's FLY fields (pd_config's fly*, generated from
 * gamedata/'s FLY_* records) and the game's own functions (pk_call). GPL-3.0, see ../NOTICE. */
#ifndef POCKET_FLY_H
#define POCKET_FLY_H
#include "pd_game.h"
#include "pd_snapshot.h"
#include "pd_tables.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

enum fly_result {
  FLY_OK,
  FLY_NOT_NOW,       // the field's controls held (a text box, a menu), or not on the field
  FLY_IN_BATTLE,
  FLY_NO_BADGE,
  FLY_CANT_FLY_HERE, // indoors, a cave
  FLY_NO_FLYER,      // nobody in the party knows the move
  FLY_FAILED,        // the game's code isn't where the config says, or a call didn't return
};

#define FLY_MAX_TOWNS 64
/* Frames FadeScreen takes to black before fly_finish: delay 0, with room to spare (Fly.kt's FLY_FADE_FRAMES). */
#define FLY_FADE_FRAMES 30

struct fly_targets {
  enum fly_result result;
  int towns[FLY_MAX_TOWNS]; // map sections, in the game's table order
  int count;
  int flyerSlot;            // the party slot that carries the player
  uint32_t flyerPersonality;
};

/* Whether this ROM ([rom], the bytes from 0x08000000) has the config's FLY functions where it says. */
bool fly_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len);
/* The first party Pokémon (not an egg) that knows the move, or -1. */
int fly_flyer(const struct pd_snapshot *snap);
/* Where FLY can go now, or why it can't: the party menu's own checks in its order. [busy]: not on the plain field
 * (a menu screen, a fade, a text box); [battle]: in a battle. Calls the game: only between frames, when
 * pk_call_safe says it can (else FLY_FAILED; ask again next frame). */
void fly_targets(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                 const struct pd_snapshot *snap, bool busy, bool battle, struct fly_targets *out);
/* FLY's first step to [mapsec]: the checks again, then what closing the Fly map leaves (controls locked, the
 * screen fading to black). fly_finish follows FLY_FADE_FRAMES frames later. */
enum fly_result fly_gate(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                         bool busy, bool battle);
enum fly_result fly_start(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                          const struct pd_snapshot *snap, int mapsec, const struct fly_targets *t, bool busy,
                          bool battle);
/* The second: the cut-in's Pokémon under the party cursor, then the Fly map's choice, and the game flies. With
 * [leftField] (the game's main callback moved on during the fade: a script's screen, a battle) nothing at all - the
 * game brings the field back itself. Else, if it can't go on, the field comes back as it was. */
bool fly_finish(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len, int mapsec,
                const struct fly_targets *t, bool leftField);
/* gMain.callback2, to tell whether the field is still the game's screen. */
uint32_t fly_main_callback(struct mCore *core, const struct pd_config *cfg);
/* Why FLY can't go, in the app's words (MapScreen.kt's flyMessage). */
const char *fly_reason(enum fly_result r);

#endif
