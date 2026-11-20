// PokeDaisy — EXP, the BAG and catching in a joined battle that runs as Emerald's own link multi battle (plan 6 Task 4; pk_coop_link.c is
// the bridge, scripts/coop_link_emerald.s's `task 4` block our Thumb code). The game's link battle refuses all three (no EXP, "items
// can't be used", no balls); for a joined battle each is allowed and made right for two games, one of which only plays the other's
// battle (the master, the host's game, runs the engine; the partner's game runs its own player's controller):
//
//   EXP    the skip of Cmd_getexp is lifted; each side's Pokémon get a single battle's full EXP (the master's wrapper of Cmd_getexp),
//          the partner's Pokémon get theirs in the partner's game (its controller) and in the master's copy of them (so the level-up
//          text and the stats are right); a partner Pokémon's moves are learned in the partner's own game, after the battle;
//   BAG    the refusal is lifted; the item comes out of the bag of the game whose player used it; a partner's medicine / X item is
//          applied to the master's copy of its Pokémon by the master's engine as well;
//   CATCH  a ball goes only at the one wild foe that is left (the game's own refusal for a ball at a trainer's Pokémon stays); the
//          caught Pokémon waits in the rewards' RAM (pk_coop_link_caught), to be given to the thrower's own game after the battle.
//
// Everything is a ROM change through pk_cheats (so the Poller's hash and RetroAchievements' still see the file's bytes) that goes in
// with the bridge's own (begin) and out with them (reset): two literals of the engine's battle-type tests, one instruction, three
// pointers (two script commands and a turn action) and four head patches. Plain C over `struct mCore*`: native-capture/mgba_dump.c
// compiles this very file in.

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <mgba/core/core.h>

#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_emerald.h"

// The code was assembled for this RAM (scripts/coop_link_emerald.s, carried in the generated header).
_Static_assert(COOP_EM_ASM_LINK_RW == PK_COOP_LINK_REWARDS, "coop_link_emerald.s: the rewards' RAM address");
_Static_assert(COOP_EM_ASM_LINK_RW_EXP_HOST == offsetof(struct pk_coop_link_rewards, expHost), "coop_link_emerald.s: expHost");
_Static_assert(COOP_EM_ASM_LINK_RW_EXP_PARTNER == offsetof(struct pk_coop_link_rewards, expPartner), "coop_link_emerald.s: expPartner");
_Static_assert(COOP_EM_ASM_LINK_RW_SHARE_HOST == offsetof(struct pk_coop_link_rewards, shareHost), "coop_link_emerald.s: shareHost");
_Static_assert(COOP_EM_ASM_LINK_RW_SHARE_PARTNER == offsetof(struct pk_coop_link_rewards, sharePartner), "coop_link_emerald.s: sharePartner");
_Static_assert(COOP_EM_ASM_LINK_RW_CAUGHT_BY == offsetof(struct pk_coop_link_rewards, caughtBy), "coop_link_emerald.s: caughtBy");
_Static_assert(COOP_EM_ASM_LINK_RW_CAUGHT == offsetof(struct pk_coop_link_rewards, caught), "coop_link_emerald.s: caught");
_Static_assert(COOP_EM_ASM_LINK_BACKUP_COUNT == offsetof(struct pk_coop_link_ram, backupCount), "coop_link_emerald.s: backupCount");
_Static_assert(COOP_EM_ASM_LINK_RW_SWALLOWED == offsetof(struct pk_coop_link_rewards, swallowed), "coop_link_emerald.s: swallowed");
_Static_assert(COOP_EM_ASM_LINK_RW_BOXFRAMES == offsetof(struct pk_coop_link_rewards, boxFrames), "coop_link_emerald.s: boxFrames");
_Static_assert(COOP_EM_ASM_LINK_RW_THROWER == offsetof(struct pk_coop_link_rewards, thrower), "coop_link_emerald.s: thrower");
_Static_assert(PK_COOP_LINK_REWARDS + sizeof(struct pk_coop_link_rewards) <= PK_COOP_LINK_RAM + PK_COOP_LINK_RAM_SIZE,
               "the rewards' RAM runs past the bridge's");
_Static_assert(PK_COOP_LINK_REWARDS >= PK_COOP_LINK_RAM + PK_COOP_LINK_BACKUP + 6 * 100, "the rewards' RAM runs into the party backup");

#define RWRAM(field) (PK_COOP_LINK_REWARDS + (uint32_t) offsetof(struct pk_coop_link_rewards, field))

static const char* sRewardsRefused = "";

// The functions our code calls or replaces, CRC-checked before any patch goes in (the bridge's own list is checked at install).
static const struct { const char* name; uint32_t addr, crc; } sRwCrcs[] = {
#define RW_FN(f) { #f, COOP_EM_##f, COOP_EM_CRC_##f }
    RW_FN(Cmd_getexp), RW_FN(Cmd_handlelearnnewmove), RW_FN(HandleAction_UseItem), RW_FN(CompleteWhenChoseItem),
    RW_FN(BattleMainCB2), RW_FN(HandleTurnActionSelectionState), RW_FN(SetMonData), RW_FN(CalculateMonStats),
    RW_FN(PlayerBufferExecCompleted), RW_FN(IsPlayerPartyAndPokemonStorageFull), RW_FN(Task_FadeAndCloseBagMenu),
    RW_FN(ItemUseInBattle_PokeBall), RW_FN(Cmd_trysetcaughtmondexflags), RW_FN(Cmd_givecaughtmon),
    RW_FN(ItemUseInBattle_Medicine), RW_FN(ItemUseInBattle_StatIncrease), RW_FN(ExecuteTableBasedItemEffect),
    RW_FN(GetItemHoldEffect), RW_FN(GetMonData), RW_FN(BtlController_EmitOneReturnValue), RW_FN(BtlController_EmitTwoReturnValues),
    RW_FN(Cmd_handleballthrow), RW_FN(Cmd_drawlvlupbox), RW_FN(ItemUseInBattle_PPRecovery),
    RW_FN(RemoveBagItem), RW_FN(DisplayItemMessage), RW_FN(CloseItemMessage), RW_FN(GetPartyIdFromBattleSlot),
#undef RW_FN
};

// A ROM patch: `value` (len bytes, little-endian) at addr, only where the file holds `expect` there.
struct rw_patch {
    uint32_t addr, expect, value;
    uint8_t len;
};

// The head patch of game function <f>: its first 8 bytes become `ldr r3, [pc, #0]; bx r3; .word <our routine | 1>` (the generator's
// COOP_EM_LINK_HEAD_<f>_*), two 4-byte halves.
#define RW_HEAD(f) \
    { COOP_EM_##f, COOP_EM_LINK_HEAD_##f##_ORIG0, COOP_EM_LINK_HEAD_##f##_NEW0, 4 }, \
    { COOP_EM_##f + 4u, COOP_EM_LINK_HEAD_##f##_ORIG1, COOP_EM_LINK_HEAD_##f##_NEW1, 4 }
// A pointer of a ROM table (a script command, a turn action) from the game's routine to ours.
#define RW_PTR(table, op, fn, entry) \
    { COOP_EM_##table + 4u * (uint32_t) COOP_EM_##op, COOP_EM_##fn | 1u, COOP_EM_LINK_ENTRY_##entry | 1u, 4 }

// An item whose battle use is the PP recovery: its gItems battleUseFunc pointer to our wrapper (the partner's bag refuses them).
#define RW_PP(n) \
    { COOP_EM_gItems + (uint32_t) COOP_EM_PP_ITEM_##n * COOP_EM_OFF_ITEM_SIZE + COOP_EM_OFF_ITEM_BATTLE_USE_FUNC, \
      COOP_EM_ItemUseInBattle_PPRecovery | 1u, COOP_EM_LINK_ENTRY_PPITEM | 1u, 4 }
_Static_assert(COOP_EM_PP_ITEM_COUNT == 5, "sRwPatches lists five PP items");
static const struct rw_patch sRwPatches[] = {
    // The engine's battle-type tests: no EXP / "items can't be used" for a link battle or a Frontier one; a Pokémon of slots 3-5 is
    // never boosted as a traded one (it is not traded in its own game)
    { COOP_EM_RW_GETEXP_SKIP_MASK, COOP_EM_RW_GETEXP_SKIP_MASK_ORIG, COOP_EM_RW_GETEXP_SKIP_MASK_NEW, 4 },
    { COOP_EM_RW_BAG_MASK, COOP_EM_RW_BAG_MASK_ORIG, COOP_EM_RW_BAG_MASK_NEW, 4 },
    { COOP_EM_RW_TRADED_BEQ, COOP_EM_RW_TRADED_BEQ_INSN, COOP_EM_RW_TRADED_BEQ_NEW, 2 },
    // The script commands and the turn action our code wraps
    RW_PTR(gBattleScriptingCommandsTable, B_SCR_OP_GETEXP, Cmd_getexp, GETEXP),
    RW_PTR(gBattleScriptingCommandsTable, B_SCR_OP_HANDLELEARNNEWMOVE, Cmd_handlelearnnewmove, LEARN),
    RW_PTR(sTurnActionsFuncsTable, B_ACTION_USE_ITEM, HandleAction_UseItem, USEITEM),
    RW_PTR(gBattleScriptingCommandsTable, B_SCR_OP_HANDLEBALLTHROW, Cmd_handleballthrow, BALLTHROW),
    RW_PTR(gBattleScriptingCommandsTable, B_SCR_OP_DRAWLVLUPBOX, Cmd_drawlvlupbox, LVLBOX),
    RW_PP(0), RW_PP(1), RW_PP(2), RW_PP(3), RW_PP(4),
    // The game functions our code replaces whole
    RW_HEAD(ItemUseInBattle_PokeBall),
    RW_HEAD(CompleteWhenChoseItem),
    RW_HEAD(Cmd_trysetcaughtmondexflags),
    RW_HEAD(Cmd_givecaughtmon),
};
#define RW_PATCH_COUNT ((int) (sizeof(sRwPatches) / sizeof(sRwPatches[0])))
#define RW_HEAD_FIRST 13   // the first head patch's index in sRwPatches (the last 8 entries: four functions of two halves)
_Static_assert(RW_PATCH_COUNT == RW_HEAD_FIRST + 8, "sRwPatches: the head patches are the last four functions");

static uint32_t rw_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
        }
    }
    return ~c;
}

static void rw_put(uint8_t* p, uint32_t v, int len) {
    for (int b = 0; b < len; b++) p[b] = (uint8_t) (v >> (8 * b));
}

static int rw_patch_in(struct mCore* core, const struct rw_patch* p) {
    uint8_t expect[4], value[4];
    rw_put(expect, p->expect, p->len);
    rw_put(value, p->value, p->len);
    // Another option's patch on these bytes is a refusal (pk_cheats_patch would answer 1 without ours going in, and taking ours out
    // by range would take theirs out).
    return !pk_cheats_patch_overlaps(p->addr, p->len) && pk_cheats_patch(core, p->addr, expect, p->len, p->addr, value, p->len);
}

void pk_coop_link_rewards_out(struct mCore* core) {
    if (!core) {
        return;
    }
    int in = core->busRead8(core, RWRAM(patchesIn));
    for (int i = in - 1; i >= 0; i--) {
        pk_cheats_unpatch_range(core, sRwPatches[i].addr, sRwPatches[i].len);
    }
    core->busWrite8(core, RWRAM(patchesIn), 0);
}

int pk_coop_link_rewards_in(struct mCore* core) {
    static char refusedBuf[40];
    if (!core) {
        return 0;
    }
    // Every function we replace or call must be the retail one (a patch of another option over the first 32 bytes is a refusal; the
    // bridge's own head patches are not in these functions)
    for (size_t i = 0; i < sizeof(sRwCrcs) / sizeof(sRwCrcs[0]); i++) {
        uint8_t code[32];
        for (int b = 0; b < 32; b++) code[b] = (uint8_t) core->busRead8(core, sRwCrcs[i].addr + (uint32_t) b);
        if (rw_crc32(code, 32) != sRwCrcs[i].crc) {
            snprintf(refusedBuf, sizeof(refusedBuf), "rewards %s", sRwCrcs[i].name);
            sRewardsRefused = refusedBuf;
            return 0;
        }
    }
    core->busWrite8(core, RWRAM(patchesIn), 0);
    for (int i = 0; i < RW_PATCH_COUNT; i++) {
        if (!rw_patch_in(core, &sRwPatches[i])) {
            pk_coop_link_rewards_out(core);
            snprintf(refusedBuf, sizeof(refusedBuf), "rewards patch %08X", (unsigned) sRwPatches[i].addr);
            sRewardsRefused = refusedBuf;
            return 0;
        }
        core->busWrite8(core, RWRAM(patchesIn), (uint8_t) (i + 1));
    }
    sRewardsRefused = "";
    return 1;
}

const char* pk_coop_link_rewards_refused(void) {
    return sRewardsRefused;
}

void pk_coop_link_rewards_original_bytes(uint32_t addr, uint8_t* dst, int len) {
    // (the callers hold a core only through the bus they read `dst` from: the patch count lives in the game's RAM, which they can't
    // see from here, so every head patch of ours answers with the file's bytes: for a function not patched that is what the bus has)
    for (int i = RW_HEAD_FIRST; i < RW_PATCH_COUNT; i++) {
        for (int b = 0; b < sRwPatches[i].len; b++) {
            uint32_t a = sRwPatches[i].addr + (uint32_t) b;
            if (a >= addr && a - addr < (uint32_t) len) {
                dst[a - addr] = (uint8_t) (sRwPatches[i].expect >> (8 * b));
            }
        }
    }
}

void pk_coop_link_rewards_players(struct mCore* core, int master, uint32_t ownOtId, uint32_t partnerOtId) {
    core->busWrite32(core, RWRAM(otId), master ? ownOtId : partnerOtId);
    core->busWrite32(core, RWRAM(otId) + 4u, master ? partnerOtId : ownOtId);
}

int pk_coop_link_caught(struct mCore* core, uint8_t mon[100]) {
    if (!core || !mon) {
        return 0;
    }
    int who = core->busRead8(core, RWRAM(caughtBy));
    if (who != 1 && who != 2) {
        return 0;
    }
    for (uint32_t b = 0; b < 100; b++) mon[b] = (uint8_t) core->busRead8(core, RWRAM(caught) + b);
    // (The foe is a stranger's Pokémon) (its OT ID keys its encrypted substructures: personality ^ OT ID). The game that gives it to
    // itself (GiveMonToPlayer) writes its own trainer's ID, name and gender into it - which only keeps it readable when that ID is
    // already the one the substructures are keyed by - so it is made the catcher's first: OT ID changed, every substructure word
    // XORed with old ^ new (the checksum, the sum of the decrypted words, holds). The trainer IDs are the ones begin was given (the
    // link players' own copy keeps only 16 bits).
    uint32_t otId = 0, old = 0;
    for (int b = 0; b < 4; b++) {
        otId |= (uint32_t) core->busRead8(core, RWRAM(otId) + 4u * (who == 1 ? 0u : 1u) + (uint32_t) b) << (8 * b);
        old |= (uint32_t) mon[4 + b] << (8 * b);
    }
    for (int i = 0; i < 48; i++) mon[0x20 + i] ^= (uint8_t) ((old ^ otId) >> (8 * (i & 3)));
    for (int b = 0; b < 4; b++) mon[4 + b] = (uint8_t) (otId >> (8 * b));
    return who;
}
