// PokeDaisy - co-op joined battles, what happens after a battle (pk_coop_after.c): the host's game is given the
// partner's Pokemon, prize money, trainer flags and so on, each only while pk_coop_on_free_field() (pk_coop_field.h).
// Every one runs through the game's own function (the app's pk_call): -1 = not on a free field, or the game couldn't be
// called right now (no safe point): nothing changed, try again on a later frame.
#pragma once

#include <stdint.h>

#include "pk_coop.h"

struct mCore;

// How these call the game (the app's pk_call / mgba_dump's own); set before any of them is used. Needs no co-op install.
void pk_coop_after_set_call(pk_coop_call_fn call);
// How to raise the step cap of one call (the game's save runs ~16 million steps, a normal call far fewer) and put it back:
// cap(steps), then cap(0). Optional: without it the save is -1 (the call never returns within the default cap).
typedef void (*pk_coop_cap_fn)(long steps);
void pk_coop_after_set_cap(pk_coop_cap_fn cap);

// GiveMonToPlayer (party, else PC; the game puts its own OT on it), then the Pokedex's SEEN and CAUGHT for its species
// (an egg isn't dexed): 0 = in the party, 1 = in a PC box, 2 = no room (or no Pokemon), -1.
int pk_coop_give_mon(struct mCore* core, const uint8_t mon[100]);
// RemoveBagItem: 1 removed, 0 not held in that number or an id / count out of range (item 1..376, count 1..99), -1.
int pk_coop_remove_item(struct mCore* core, int item, int count);
// FlagSet(TRAINER_FLAGS_START + id): 1 set now, 2 already set (nothing written), 0 for an id outside 1..854 or no save block (nothing written), -1.
int pk_coop_set_trainer_flag(struct mCore* core, int trainerId);
// The prize for this trainer in a two-trainer battle (GetTrainerMoneyToGive over ROM reads, no x2, no Amulet Coin), any frame: -1 for an id outside 1..854.
int pk_coop_prize(struct mCore* core, int trainerId);
// The same for a one-trainer battle (kind 3): what the game pays for that trainer alone, x2 when its record is a double battle.
int pk_coop_prize_single(struct mCore* core, int trainerId);
// AddMoney: 1, 0 for an amount outside 1..999,999, -1.
int pk_coop_add_money(struct mCore* core, int amount);
// A returned Pokemon back into party slot `slot` (below gPlayerPartyCount; it must keep that slot's personality and OT
// ID, else 0): 1, 0, -1.
int pk_coop_write_party_mon(struct mCore* core, int slot, const uint8_t mon[100]);
// SetMainCallback2(CB2_WhiteOut) with gMain.state 0: 1 started (the game takes it from there), -1.
int pk_coop_white_out(struct mCore* core);
// The evolution scene for party slot `slot`, back on the field after: 1 started, 0 nothing to evolve into (or a bad slot), -1.
int pk_coop_evolve(struct mCore* core, int slot);
// The START menu's save steps: 1 saved, 0 the game said it failed, -1 not started (nothing changed, retry), -2 started but the call
// never returned: DO NOT retry (the flash may be mid-write); treat the save as failed and keep the backup.
int pk_coop_save_game(struct mCore* core);
// A host's trainer script left waiting for a battle that ended without it (a joined battle lost with Pokemon to spare: no white-out,
// no CB2_EndTrainerBattle to carry the script on): once the game is back on CB2_Overworld the script context is shut down
// (ScriptContext_Init), the objects unfrozen and the field controls released, as ReleaseAll would. 1 = done, 0 = no script was
// waiting (nothing touched), -1 = not on the field yet / the game couldn't be called now: retry.
int pk_coop_release_script(struct mCore* core);
