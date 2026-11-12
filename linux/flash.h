/* FLASH from the QUICK MENU: the app's Flash.kt in C, over the config's FLASH fields (pd_config's flash*, generated from
 * gamedata/'s FLASH_* records) and the game's own functions (pk_call). GPL-3.0, see ../NOTICE. */
#ifndef POCKET_FLASH_H
#define POCKET_FLASH_H
#include "pd_game.h"
#include "pd_snapshot.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

enum flash_result {
  FLASH_OK,            // the game's FLASH is on its way (the cut-in, then the light)
  FLASH_NOT_NOW,       // the field's controls held (a text box, a menu), or not on the field
  FLASH_IN_BATTLE,
  FLASH_NO_BADGE,
  FLASH_NO_FLASHER,    // nobody in the party knows the move
  FLASH_CANT_USE_HERE, // not a dark place (or not Emerald's tomb wall)
  FLASH_ALREADY_LIT,   // this cave is lit already
  FLASH_FAILED,        // the game's code isn't where the config says, or a call didn't return
};

/* Whether this ROM ([rom], the bytes from 0x08000000) has the config's FLASH functions where it says, its party menu's
 * badge check retail's own (a hack that changed it gets no FLASH here: the app reads those rules, flashBadgeRule). */
bool flash_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len);
/* The first party Pokémon (not an egg) that knows FLASH, or -1. */
int flash_flasher(const struct pd_snapshot *snap);
/* Whether FLASH would light this place up, from RAM (the quick menu's dimming): 1 a dark cave, 0 not, -1 can't tell. */
int flash_dark(struct mCore *core, const struct pd_config *cfg, const struct pd_snapshot *snap);
/* FLASH, the party menu's checks in its order and then the field move. [busy]: not on the plain field; [battle]: in a
 * battle. Calls the game: only between frames, when pk_call_safe says it can. */
enum flash_result flash_use(struct mCore *core, const struct pd_config *cfg, const uint8_t *rom, size_t len,
                            const struct pd_snapshot *snap, bool busy, bool battle);
/* Why FLASH can't go, in the app's words; NULL for FLASH_OK. */
const char *flash_reason(enum flash_result r);

#endif
