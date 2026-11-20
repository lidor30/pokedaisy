// PokeDaisy - co-op joined battles, the field side (pk_coop_field.c): what the overworld is doing, for the helpers that
// only act when the host (or the partner's own game) is standing free on the field. Plain C over `struct mCore*`.
#pragma once

#include <stdint.h>

struct mCore;

// 1 = gMain.callback2 is CB2_Overworld, the field controls aren't locked, no palette fade is active and the game isn't
// in a battle; 0 otherwise (also on a core that isn't retail English Emerald's, which the caller has installed first).
int pk_coop_on_free_field(struct mCore* core);

// What a joined battle needs to know. 0 = not joinable, 1 = an ordinary wild battle, 2 = two trainers' battle, 3 = one trainer with
// two or more Pokemon (never a one-Pokemon trainer: no co-op); always 0
// unless pk_coop_battle_starting().
int pk_coop_join_kind(struct mCore* core);
// The opponent ids [A, B] of a kind-2 battle, [A, 0] of a kind-3 one (both 0 otherwise).
void pk_coop_join_trainers(struct mCore* core, int out[2]);
// 1 on the free field (pk_coop_on_free_field) or on the START menu's top level.
int pk_coop_partner_free(struct mCore* core);
