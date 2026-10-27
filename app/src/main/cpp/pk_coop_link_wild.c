// PokeDaisy — a joined wild battle as an Emerald link battle (plan 6 Task 3; see pk_coop.h, pk_coop_link.c).
//
// Emerald's own tower link multi is the engine (pk_coop_link.c); a wild battle is that battle with BATTLE_TYPE_TRAINER left out
// of the flags on both games (the game itself decides the intro text "Wild X and Y appeared!", the foes' sprites instead of
// trainer pictures, no foe AI picks beyond the wild random one - nothing to patch for those). What's left is patched here, for
// the wild kind only, while the battle runs (taken out by pk_coop_link_reset with the bridge's head patches):
//   - the VS screen (two names and ball rows before the battle) is replaced by a task that ends at once;
//   - the foes' trainer picture at OPPONENT_RIGHT in a MULTI battle (it would stand on the second wild Pokémon): COOP_EM_INTRO_FOE_RIGHT_CMP;
//   - RUN: a link battle's RUN is a forfeit (IsRunningFromBattleImpossible says SUCCESS for any link, HandleAction_Run ends the
//     battle as lost); both tests are skipped so Shadow Tag & co. can refuse at the prompt and the escape roll of TryRunFromBattle
//     runs, which COOP_EM_RUN_DOUBLE_BNE lets roll in a double battle;
//   - (the Frontier's level 50 for the enemies: task 2's always-on TrySetLinkBattleTowerEnemyPartyLevel hook leaves every kind's foes at their level)
// The host's game rolls the two wild Pokémon from the map's table (the game's own TryGenerateWildMon) into gEnemyParty slots 0 and 3
// (battler 1 reads 0, battler 3 reads 3); the partner's game receives them as the link battle's own enemy-party blocks.
//
// Plain C over `struct mCore*`, no JNI; mgba_dump compiles this file in beside pk_coop_link.c.

#include <stddef.h>
#include <stdint.h>

#include <mgba/core/core.h>

#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_emerald.h"

#define LW_WILD_AREA_LAND  0u   // enum in src/wild_encounter.c (private): WILD_AREA_LAND / WATER
#define LW_WILD_AREA_WATER 1u
#define LW_HEADER_NONE     0xFFFFu   // GetCurrentMapWildMonHeaderId's HEADER_NONE

// The stub's "the end was sent" byte (scripts/coop_link_emerald.s, link_ransent) sits in the bridge's header, past struct pk_coop_link_ram.
_Static_assert(COOP_EM_ASM_LINK_RANSENT == 0x3F, "coop_link_emerald.s: ransent");
_Static_assert(sizeof(struct pk_coop_link_ram) <= 0x3F, "the bridge's header runs into task 3's byte");

static const struct { uint32_t addr, expect, value; int len; } sLwPatches[] = {
    // the VS screen's task is our no-op (the wild battle has no "COOP VS NED / BRADY, CONNER" screen)
    { COOP_EM_LINK_WILD_VS_TASK_PTR, COOP_EM_LINK_WILD_VS_TASK_PTR_PTR, COOP_EM_LINK_ENTRY_WILD_NOVS | 1u, 4 },
    // the escape's (and a foe's flight's) end goes to the other player too (scripts/coop_link_emerald.s, link_entry_RAN_FINISH)
    { COOP_EM_LINK_WILD_RAN_FINISH_PTR, COOP_EM_LINK_WILD_RAN_FINISH_PTR_PTR, COOP_EM_LINK_ENTRY_RAN_FINISH | 1u, 4 },
    { COOP_EM_LINK_WILD_FLED_FINISH_PTR, COOP_EM_LINK_WILD_FLED_FINISH_PTR_PTR, COOP_EM_LINK_ENTRY_RAN_FINISH | 1u, 4 },
    { COOP_EM_LINK_WILD_END_TELEPORTED_PTR, COOP_EM_LINK_WILD_END_TELEPORTED_PTR_PTR, COOP_EM_LINK_ENTRY_RAN_FINISH | 1u, 4 },
    { COOP_EM_LINK_WILD_END_MON_TELEPORTED_PTR, COOP_EM_LINK_WILD_END_MON_TELEPORTED_PTR_PTR, COOP_EM_LINK_ENTRY_RAN_FINISH | 1u, 4 },
    { COOP_EM_INTRO_FOE_RIGHT_CMP, COOP_EM_INTRO_FOE_RIGHT_CMP_INSN, COOP_EM_INTRO_FOE_RIGHT_CMP_NEW, 2 },
    { COOP_EM_RUN_DOUBLE_BNE, COOP_EM_RUN_DOUBLE_BNE_INSN, COOP_EM_RUN_DOUBLE_BNE_NEW, 2 },
    { COOP_EM_LINK_WILD_RUN_IMPOSSIBLE_BEQ, COOP_EM_LINK_WILD_RUN_IMPOSSIBLE_BEQ_INSN, COOP_EM_LINK_WILD_RUN_IMPOSSIBLE_BEQ_NEW, 2 },
    { COOP_EM_LINK_WILD_RUN_ACTION_BEQ, COOP_EM_LINK_WILD_RUN_ACTION_BEQ_INSN, COOP_EM_LINK_WILD_RUN_ACTION_BEQ_NEW, 2 },
    // the intro slide's link task moves the VS letters off by their sprite ids (gBattleStruct + 0x7D / 0x7E, set by the VS screen we skip): 0 = the
    // healthbox at sprite 0 (the host's own, battler 0) slid off the screen
    { COOP_EM_LINK_WILD_INTRO_VS_SPRITES, COOP_EM_LINK_WILD_INTRO_VS_SPRITES_INSN, COOP_EM_LINK_WILD_INTRO_VS_SPRITES_NEW, 2 },
};
#define LW_PATCH_COUNT ((int) (sizeof(sLwPatches) / sizeof(sLwPatches[0])))

// Puts the wild kind's patches in, each one checked against the file's bytes; *in = how many went in (undone by _out). 1 = all in.
// Another option's patch on the same bytes is a refusal (taking ours out by range would take theirs out).
uint32_t pk_coop_link_wild_failed_at;   // the patch address the last wild_patches_in stopped at (0 = none)

int pk_coop_link_wild_patches_in(struct mCore* core, int* in) {
    pk_coop_link_wild_failed_at = 0;
    *in = 0;
    for (int i = 0; i < LW_PATCH_COUNT; i++) {
        uint8_t expect[4], value[4];
        for (int b = 0; b < sLwPatches[i].len; b++) {
            expect[b] = (uint8_t) (sLwPatches[i].expect >> (8 * b));
            value[b] = (uint8_t) (sLwPatches[i].value >> (8 * b));
        }
        pk_coop_link_wild_failed_at = sLwPatches[i].addr;
        if (pk_cheats_patch_overlaps(sLwPatches[i].addr, (uint32_t) sLwPatches[i].len)
            || !pk_cheats_patch(core, sLwPatches[i].addr, expect, sLwPatches[i].len, sLwPatches[i].addr, value, sLwPatches[i].len)) {
            pk_coop_link_wild_patches_out(core, in);
            return 0;
        }
        *in = i + 1;
    }
    return 1;
}

void pk_coop_link_wild_patches_out(struct mCore* core, int* in) {
    for (int i = *in - 1; i >= 0; i--) {
        pk_cheats_unpatch_range(core, sLwPatches[i].addr, (uint32_t) sLwPatches[i].len);
    }
    *in = 0;
}

// Rolls the two wild Pokémon of the map the player stands on (the land table, or the water's while surfing) with the game's own
// TryGenerateWildMon, and leaves them in gEnemyParty slots 0 and 3, everything else zero (the game zeroes all six when it
// rolls). 1 = rolled; 0 = no table here, a failed roll, or no pk_call-safe frame (gEnemyParty is zeroed then).
int pk_coop_link_wild_roll(struct mCore* core, pk_coop_call_fn call) {
    if (!core || !call) {
        return 0;
    }
    uint8_t mons[2][COOP_EM_OFF_MON_SIZE];
    int ok = 0;
    uint32_t none[4] = {0, 0, 0, 0};
    uint32_t headerId = call(core, COOP_EM_GetCurrentMapWildMonHeaderId, none);
    if (headerId != 0xFFFFFFFFu && (headerId &= 0xFFFFu) != LW_HEADER_NONE) {
        uint32_t header = COOP_EM_gWildMonHeaders + headerId * COOP_EM_OFF_WILD_HEADER_SIZE;
        // Land or water only (fishing encounters aren't joinable: the join rule offers ordinary wild battles), the water table while surfing.
        int surfing = core->busRead8(core, COOP_EM_gPlayerAvatar + COOP_EM_OFF_PLAYER_AVATAR_FLAGS)
                      & COOP_EM_PLAYER_AVATAR_FLAG_SURFING;
        uint32_t info = (uint32_t) core->busRead32(core, header + (surfing ? COOP_EM_OFF_WILD_HEADER_WATER
                                                                           : COOP_EM_OFF_WILD_HEADER_LAND));
        uint32_t area = surfing ? LW_WILD_AREA_WATER : LW_WILD_AREA_LAND;
        if (info >= 0x08000000u && info < 0x0A000000u) {
            ok = 1;
            for (int n = 0; n < 2 && ok; n++) {
                uint32_t roll[4] = {info, area, 0, 0};
                uint32_t got = call(core, COOP_EM_TryGenerateWildMon, roll);
                ok = got != 0xFFFFFFFFu && (got & 0xFFu) == 1;
                for (uint32_t i = 0; i < COOP_EM_OFF_MON_SIZE; i++) {
                    mons[n][i] = (uint8_t) core->busRead8(core, COOP_EM_gEnemyParty + i);
                }
            }
        }
    }
    for (uint32_t i = 0; i < 6u * COOP_EM_OFF_MON_SIZE; i++) {
        core->busWrite8(core, COOP_EM_gEnemyParty + i, 0);
    }
    if (ok) {
        for (uint32_t i = 0; i < COOP_EM_OFF_MON_SIZE; i++) {
            core->busWrite8(core, COOP_EM_gEnemyParty + i, mons[0][i]);
            core->busWrite8(core, COOP_EM_gEnemyParty + 3u * COOP_EM_OFF_MON_SIZE + i, mons[1][i]);
        }
    }
    return ok;
}
