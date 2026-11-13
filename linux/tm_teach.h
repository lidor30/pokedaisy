/* USE on a TM / HM: the app's TmTeach.kt in C - the game's own TM party menu ("Teach which POKéMON?", its forget-a-move
 * summary, its fanfare, its rules for a TM used up), started the way the game's bag starts it, going back to the field
 * when it closes. Over the config's TM fields (pd_config's tm*, from gamedata's TM_* records) and the field items' bag
 * check / field lock (field*). GPL-3.0, see ../NOTICE. */
#ifndef POCKET_TM_TEACH_H
#define POCKET_TM_TEACH_H
#include "pd_game.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;

enum tm_result {
  TM_STARTED,   // the fade is on its way: tm_finish follows TM_FADE_FRAMES frames later
  TM_NOT_NOW,   // the field's controls held, a menu, a battle
  TM_NONE_LEFT, // not in the bag any more
  TM_FAILED,    // the game's code isn't where the config says, or a call didn't return
};

/* Frames tm_start's fade takes to reach black before tm_finish: FadeScreen at delay 0, with room to spare. */
#define TM_FADE_FRAMES 30

/* Whether this ROM ([rom], the bytes from 0x08000000) has the config's TM screen calls and the field items' where they
 * say. */
bool tm_supported(const struct pd_config *cfg, const uint8_t *rom, size_t len);
/* Whether [item] is a TM or an HM. */
bool tm_teaches(const struct pd_config *cfg, int item);
/* The first step for [item]: the bag's checks (the free field, the TM in the bag, a party), then what the START menu
 * leaves before a screen of its own - the controls locked, the screen fading to black. Between frames only. */
enum tm_result tm_start(struct mCore *core, const struct pd_config *cfg, int item, bool busy);
/* The second, the screen black: the game's TM party menu for [item], back to the field when it closes. If it can't
 * start, the field comes back as it was. [leftField]: something else took the game off the field during the fade - then
 * nothing at all. */
bool tm_finish(struct mCore *core, const struct pd_config *cfg, int item, bool leftField);
/* gMain.callback2, to tell whether the field is still the game's screen. */
uint32_t tm_main_callback(struct mCore *core, const struct pd_config *cfg);
/* Why it can't, in the app's words; NULL for TM_STARTED. */
const char *tm_reason(enum tm_result r);

#endif
