/* PokeDaisy - PC anywhere, FLY and WILD ENCOUNTERS on the Game Boy games: a C port of the app's GbField.kt (Red / Blue,
 * Yellow in five languages, PureRed / PureBlue 2.7.6, Crystal rev 0 / rev 1 / AU), each the game's own way in:
 *
 * - Gen 1: the PC is the Pokemon Center PC tile's routine, called through the game's Bankswitch from its overworld loop
 *   right after the frame wait (pk_gb_call_at); FLY is what the town map's A leaves for the loop (the destination, its
 *   fly-warp bits, Yellow's Pikachu hidden); encounters roll against the map's grass / water rates, held at 0 and put
 *   back from the map's wild data in ROM.
 * - Crystal queues a map script the overworld runs once the player stands free (RunMemScript): the standard PC script,
 *   or the Fly field move's script after wDefaultSpawnpoint; encounters wait while wWildEncounterCooldown is held.
 *
 * The code each step relies on is checked in the ROM first (gb_field_find). Calls go between frames only. */
#ifndef PD_GB_FIELD_H
#define PD_GB_FIELD_H

#include "field_items.h"
#include "fly.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;
struct gb_field;

/* Frames gb_field_taken waits for: Yellow's loop and Crystal's PlayerEvents take theirs within one step. */
#define GB_FIELD_TAKE_FRAMES 24

/* The config whose code this cart ([rom]: the cart's bytes from file offset 0) has, or NULL. */
const struct gb_field *gb_field_find(const uint8_t *rom, size_t len);
/* The config's short name ("Yellow", "Crystal", ...), for logs and tests. */
const char *gb_field_name(const struct gb_field *f);
/* The game's PC: ITEM_USED = started (Gen 1) or queued (Crystal: gb_field_taken says), ITEM_NOT_NOW where the player
 * isn't free. [busy]: the host's own "not on the plain field" (a scene that isn't the field). */
enum item_result gb_field_pc(struct mCore *core, const struct gb_field *f, bool busy);
/* Where FLY can go now (map sections: Gen 1's town map ids, Crystal's landmarks), or why it can't. */
void gb_field_fly_targets(struct mCore *core, const struct gb_field *f, const uint8_t *rom, size_t len, bool busy,
                          struct fly_targets *out);
/* Flies to [mapsec], one of the targets: FLY_OK = the game has it (or has it queued: gb_field_taken). */
enum fly_result gb_field_fly(struct mCore *core, const struct gb_field *f, const uint8_t *rom, size_t len, int mapsec,
                             bool busy);
/* GB_FIELD_TAKE_FRAMES running frames after gb_field_pc / gb_field_fly: whether the game took it; if not, it's withdrawn. */
bool gb_field_taken(struct mCore *core, const struct gb_field *f);
/* Every frame while WILD ENCOUNTERS is OFF. */
void gb_field_hold_wild(struct mCore *core, const struct gb_field *f);
/* Once WILD ENCOUNTERS is back ON, on the field: false = not now, try again. */
bool gb_field_release_wild(struct mCore *core, const struct gb_field *f, const uint8_t *rom, size_t len);

#endif
