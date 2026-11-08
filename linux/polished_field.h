/* GPL-3.0. Version-verified Polished Crystal field-script bridge. */
#ifndef POCKET_POLISHED_FIELD_H
#define POCKET_POLISHED_FIELD_H
#include "pd_field_input.h"
struct mCore;
bool polished_field_supported(const char *sha);
/* NULL means ready; otherwise a player-facing reason. Does not write. */
const char *polished_pc_reason(const char *sha, pd_read_fn, void *ctx);
/* Queue the game's existing PC script through the shared GB adapter.
 * No CPU injection, box edits, automatic confirmations or save writes. */
bool polished_pc_open(struct mCore *, const char *sha, pd_read_fn, void *ctx);
/* Once before every emulated frame while WILD is OFF. Stops random walking
 * encounters through the game's cooldown; does not block scripted battles.
 * Stop calling to restore encounters; the short cooldown expires naturally. */
bool polished_wild_hold(struct mCore *, const char *sha, pd_read_fn, void *ctx);
#endif
