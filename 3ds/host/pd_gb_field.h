// PC anywhere, FLY and WILD ENCOUNTERS on the Game Boy games: GbField.kt. Each game's own way in: Crystal queues a map script
// and holds its encounter cooldown; Gen 1's PC is the PC tile's routine called from the overworld loop, its FLY the town map's
// warp flags, its encounters the map's rates held at 0.
#ifndef PD_GB_FIELD_H
#define PD_GB_FIELD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "../../linux/fly.h"
#include "../core/pd_gb.h"

#define PD_GB_TAKE_FRAMES 24

struct pd_session;

// Whether the cart has the code each step relies on.
bool pd_gbf_ok(const struct pd_gb_field* f, const uint8_t* rom, size_t size);
bool pd_gbf_open_pc(struct pd_session* s, const struct pd_gb_field* f);
void pd_gbf_fly_targets(struct pd_session* s, const struct pd_gb_field* f, struct fly_targets* out);
enum fly_result pd_gbf_fly(struct pd_session* s, const struct pd_gb_field* f, int mapsec);
// A few frames after: whether the game took it; if not, it's withdrawn.
bool pd_gbf_taken(struct pd_session* s, const struct pd_gb_field* f);
// Every frame while WILD ENCOUNTERS is OFF; once back ON, false = not now, try again.
void pd_gbf_hold_wild(struct pd_session* s, const struct pd_gb_field* f);
bool pd_gbf_release_wild(struct pd_session* s, const struct pd_gb_field* f);

#endif
