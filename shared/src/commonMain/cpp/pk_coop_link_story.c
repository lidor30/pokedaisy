// PokeDaisy — story battles as link battles (plan 6 Task 6; pk_coop.h, pk_coop_link.c): a trainer battle of the story - a gym leader,
// the rival, a Team Aqua / Magma boss - is joined by a partner standing at the same spot, in every save. Each game goes through its
// own `trainerbattle` script as it always does; at the script's `dotrainerbattle` (BattleSetup_StartTrainerBattle, the one place
// every trainer battle starts) the story hook takes over:
//   1. arm: the app has the partner's yes. BattleSetup_StartTrainerBattle gets a head patch (scripts/coop_link_emerald.s,
//      link_entry_STORYSTART) until go / cancel / disarm.
//   2. The first `dotrainerbattle` runs the game's own team pick instead - `special ChooseHalfPartyForBattle` (the multi battle's
//      "choose three" party screen, from the field) - as a small script in the story RAM whose last command is the original
//      script's own continuation (`goto <what followed dotrainerbattle>`), so after the battle the trainer's own after-battle
//      script (badge, flags, text, TM) runs in each game exactly as it does solo. The picked slots are read at the pick script's own
//      `dotrainerbattle` and the script is held (ScriptContext_Stop): state READY.
//   3. go: the app has both players READY (their own messages): the bridge's begin with the picks as the party (the whole party is
//      still backed up and put back by reset), the real trainer ids, and the callback the story battle ends through
//      (CB2_EndTrainerBattle: the same flow as the solo battle's - flags, blackout or back to the script).
// A pick screen backed out of leaves CANCELLED: `cancel` takes the hook out and lets the game's own battle start (solo).
// No network anything here: plain C over `struct mCore*`, like pk_coop_link.c; native-capture/mgba_dump.c compiles it in.

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <mgba/core/core.h>

#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_emerald.h"

#define STORY_HOOK_FN COOP_EM_BattleSetup_StartTrainerBattle
#define STORY_OLD_FACILITY       0x0Cu   // u16 VAR_FRONTIER_FACILITY before the pick screen's special set it, and whether it is kept
#define STORY_OLD_FACILITY_VALID 0x0Eu
#define story_var(sb1) ((sb1) + COOP_EM_OFF_SB1_VARS + 2u * (COOP_EM_VAR_FRONTIER_FACILITY - COOP_EM_VARS_START))
_Static_assert(COOP_EM_ASM_LINK_STORY_EXPECT == 6, "coop_link_emerald.s: the expected trainer's offset");

_Static_assert(COOP_EM_ASM_LINK_STORY_RAM == PK_COOP_STORY_RAM, "coop_link_emerald.s: story RAM address");
_Static_assert(COOP_EM_ASM_LINK_STORY_STATE == 0 && COOP_EM_ASM_LINK_STORY_RESULT == 1 && COOP_EM_ASM_LINK_STORY_PICKS == 2,
               "coop_link_emerald.s: story RAM layout");
_Static_assert(COOP_EM_ASM_LINK_STORY_SCRIPT == PK_COOP_STORY_SCRIPT, "coop_link_emerald.s: story script offset");
_Static_assert(COOP_EM_ASM_LINK_STORY_OPERAND == PK_COOP_STORY_SCRIPT + 6, "coop_link_emerald.s: the pick script's goto operand");
_Static_assert(PK_COOP_STORY_RAM >= PK_COOP_LINK_RAM + PK_COOP_LINK_RAM_SIZE, "the story RAM is inside the bridge's (begin zeroes that)");
_Static_assert(PK_COOP_STORY_RAM + PK_COOP_STORY_SIZE <= 0x0203FF00u, "the story RAM runs into co-op's scratch window");

struct pk_story_ctx {
    pk_coop_call_fn call;
    int patched;   // head patch halves in (0 or 2)
};
static struct pk_story_ctx sStory;

static uint32_t story_read(struct mCore* core, uint32_t addr, int len) {
    uint32_t v = 0;
    for (int b = 0; b < len; b++) v |= (uint32_t) core->busRead8(core, addr + (uint32_t) b) << (8 * b);
    return v;
}

static void story_write(struct mCore* core, uint32_t addr, uint32_t v, int len) {
    for (int b = 0; b < len; b++) core->busWrite8(core, addr + (uint32_t) b, (uint8_t) (v >> (8 * b)));
}

static void story_put32(uint8_t* p, uint32_t v) {
    for (int b = 0; b < 4; b++) p[b] = (uint8_t) (v >> (8 * b));
}

static uint32_t story_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
    return ~c;
}

static int story_patch_half(struct mCore* core, int half) {
    uint8_t expect[4], value[4];
    story_put32(expect, half ? COOP_EM_LINK_HEAD_BattleSetup_StartTrainerBattle_ORIG1 : COOP_EM_LINK_HEAD_BattleSetup_StartTrainerBattle_ORIG0);
    story_put32(value, half ? COOP_EM_LINK_HEAD_BattleSetup_StartTrainerBattle_NEW1 : COOP_EM_LINK_HEAD_BattleSetup_StartTrainerBattle_NEW0);
    uint32_t addr = STORY_HOOK_FN + 4u * (uint32_t) half;
    return !pk_cheats_patch_overlaps(addr, 4) && pk_cheats_patch(core, addr, expect, 4, addr, value, 4);
}

static void story_unpatch(struct mCore* core) {
    for (int half = sStory.patched - 1; half >= 0; half--) pk_cheats_unpatch_range(core, STORY_HOOK_FN + 4u * (uint32_t) half, 4);
    sStory.patched = 0;
}

static void story_restore_var(struct mCore* core);

int pk_coop_story_state(struct mCore* core) {
    if (!core || !sStory.patched) return PK_COOP_STORY_IDLE;
    int st = core->busRead8(core, PK_COOP_STORY_RAM + COOP_EM_ASM_LINK_STORY_STATE);
    if (st == PK_COOP_STORY_MISMATCH) {   // another trainer's battle went by the hook (the game ran it as always): out it goes
        pk_coop_story_disarm(core);
        return PK_COOP_STORY_IDLE;
    }
    return st;
}

int pk_coop_story_arm(struct mCore* core, pk_coop_call_fn call, uint16_t trainerId) {
    if (!core || !call || sStory.patched || !pk_coop_link_installed() || core->platform(core) != mPLATFORM_GBA) return 0;
    uint8_t code[32];
    for (int b = 0; b < 32; b++) code[b] = (uint8_t) core->busRead8(core, STORY_HOOK_FN + (uint32_t) b);
    if (story_crc32(code, 32) != COOP_EM_CRC_BattleSetup_StartTrainerBattle) return 0;
    // On the field with no battle yet: the hook is for the `dotrainerbattle` still to come.
    if (story_read(core, COOP_EM_gBattleTypeFlags, 4) != 0 || story_read(core, COOP_EM_gReceivedRemoteLinkPlayers, 1) != 0) return 0;
    // The stub finishes the function's first 8 bytes itself for another trainer: they must be the ones it was written for (the
    // literal its `ldr r0, [pc, #12]` loads is gNoOfApproachingTrainers).
    if (!trainerId || story_read(core, STORY_HOOK_FN + 16, 4) != COOP_EM_gNoOfApproachingTrainers) return 0;
    for (uint32_t a = PK_COOP_STORY_RAM; a < PK_COOP_STORY_RAM + PK_COOP_STORY_SIZE; a++) core->busWrite8(core, a, 0);
    story_write(core, PK_COOP_STORY_RAM + COOP_EM_ASM_LINK_STORY_EXPECT, trainerId, 2);
    {   // the pick screen's special sets VAR_FRONTIER_FACILITY: its value now, put back by go / cancel / disarm
        uint32_t sb1 = story_read(core, COOP_EM_gSaveBlock1Ptr, 4);
        if (sb1 >= 0x02000000u && sb1 < 0x02040000u) {
            story_write(core, PK_COOP_STORY_RAM + STORY_OLD_FACILITY, story_read(core, story_var(sb1), 2), 2);
            core->busWrite8(core, PK_COOP_STORY_RAM + STORY_OLD_FACILITY_VALID, 1);
        }
    }
    // The pick script: special ChooseHalfPartyForBattle; waitstate; dotrainerbattle; goto <operand: the stub fills it in>.
    uint32_t s = PK_COOP_STORY_RAM + PK_COOP_STORY_SCRIPT;
    core->busWrite8(core, s, COOP_EM_SCR_OP_SPECIAL);
    story_write(core, s + 1, COOP_EM_SPECIAL_CHOOSE_HALF_PARTY, 2);
    core->busWrite8(core, s + 3, COOP_EM_SCR_OP_WAITSTATE);
    core->busWrite8(core, s + 4, COOP_EM_SCR_OP_DOTRAINERBATTLE);
    core->busWrite8(core, s + 5, COOP_EM_SCR_OP_GOTO);
    core->busWrite8(core, PK_COOP_STORY_RAM + COOP_EM_ASM_LINK_STORY_STATE, PK_COOP_STORY_ARMED);
    for (int half = 0; half < 2; half++) {
        if (!story_patch_half(core, half)) {
            story_unpatch(core);
            core->busWrite8(core, PK_COOP_STORY_RAM + COOP_EM_ASM_LINK_STORY_STATE, 0);
            return 0;
        }
        sStory.patched = half + 1;
    }
    sStory.call = call;
    return 1;
}

static void story_restore_var(struct mCore* core) {
    if (!core->busRead8(core, PK_COOP_STORY_RAM + STORY_OLD_FACILITY_VALID)) return;
    uint32_t sb1 = story_read(core, COOP_EM_gSaveBlock1Ptr, 4);
    if (sb1 >= 0x02000000u && sb1 < 0x02040000u) {
        story_write(core, story_var(sb1), story_read(core, PK_COOP_STORY_RAM + STORY_OLD_FACILITY, 2), 2);
    }
    core->busWrite8(core, PK_COOP_STORY_RAM + STORY_OLD_FACILITY_VALID, 0);
}

void pk_coop_story_disarm(struct mCore* core) {
    if (!core) return;
    if (sStory.patched) story_restore_var(core);
    story_unpatch(core);
    core->busWrite8(core, PK_COOP_STORY_RAM + COOP_EM_ASM_LINK_STORY_STATE, 0);
}

void pk_coop_story_forget(void) {
    memset(&sStory, 0, sizeof(sStory));
}

// A story trainer with one party (BattleSetup_ConfigureTrainerBattle gave opponent B none) fights as two: the party split over the two
// foe slots as the tower's multi lays them out (gEnemyParty 0-2 for the first foe, 3-5 for the second): the first half, rounded up,
// stays; the rest moves to slot 3.
static void story_split_foes(struct mCore* core) {
    uint8_t mons[3 * 100];
    int n = 0;
    for (int i = 0; i < 3; i++) {
        uint32_t at = COOP_EM_gEnemyParty + (uint32_t) i * COOP_EM_OFF_MON_SIZE;
        if (!(core->busRead8(core, at + COOP_EM_OFF_BOX_MON_FLAGS) & 0x02)) break;
        for (int b = 0; b < 100; b++) mons[n * 100 + b] = (uint8_t) core->busRead8(core, at + (uint32_t) b);
        n++;
    }
    if (n < 2) return;
    int a = (n + 1) / 2;
    for (uint32_t b = 0; b < 6u * COOP_EM_OFF_MON_SIZE; b++) core->busWrite8(core, COOP_EM_gEnemyParty + b, 0);
    for (int i = 0; i < n; i++) {
        uint32_t at = COOP_EM_gEnemyParty + (uint32_t) (i < a ? i : 3 + i - a) * COOP_EM_OFF_MON_SIZE;
        for (int b = 0; b < 100; b++) core->busWrite8(core, at + (uint32_t) b, mons[i * 100 + b]);
    }
}

int pk_coop_story_go(struct mCore* core, struct pk_coop_link_setup* setup) {
    if (!core || !setup || !sStory.patched || pk_coop_story_state(core) != PK_COOP_STORY_READY) return 0;
    uint32_t r = PK_COOP_STORY_RAM;
    uint16_t a = (uint16_t) story_read(core, COOP_EM_gTrainerBattleOpponent_A, 2), b = (uint16_t) story_read(core, COOP_EM_gTrainerBattleOpponent_B, 2);
    int approaching = core->busRead8(core, COOP_EM_gNoOfApproachingTrainers);
    setup->kind = PK_COOP_LINK_STORY;
    setup->trainerA = a;
    setup->trainerB = approaching == 2 ? b : 0;
    for (int i = 0; i < 3; i++) setup->picks[i] = core->busRead8(core, r + COOP_EM_ASM_LINK_STORY_PICKS + (uint32_t) i);
    if (!pk_coop_link_begin(core, setup)) return 0;
    // The link battle has begun: the hook has done its job. What the original function did before it started the battle, the
    // trainer script's own after-battle code reads (the retained scripts, the trainer to face), then the story battle ends through
    // the solo battle's callback.
    story_restore_var(core);
    story_unpatch(core);
    core->busWrite8(core, r + COOP_EM_ASM_LINK_STORY_STATE, 0);
    {   // what DoTrainerBattle counts for a trainer battle
        const uint32_t stat1[4] = { COOP_EM_GAME_STAT_TOTAL_BATTLES, 0, 0, 0 }, stat2[4] = { COOP_EM_GAME_STAT_TRAINER_BATTLES, 0, 0, 0 };
        sStory.call(core, COOP_EM_IncrementGameStat, stat1);
        sStory.call(core, COOP_EM_IncrementGameStat, stat2);
    }
    core->busWrite8(core, COOP_EM_sNoOfPossibleTrainerRetScripts, (uint8_t) approaching);
    core->busWrite8(core, COOP_EM_gNoOfApproachingTrainers, 0);
    core->busWrite8(core, COOP_EM_sShouldCheckTrainerBScript, 0);
    core->busWrite8(core, COOP_EM_gWhichTrainerToFaceAfterBattle, 0);
    story_write(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK, COOP_EM_CB2_EndTrainerBattle | 1u, 4);
    if (setup->master && !setup->trainerB) story_split_foes(core);
    return 1;
}

int pk_coop_story_cancel(struct mCore* core) {
    if (!core || !sStory.patched || !sStory.call) return 0;
    int st = pk_coop_story_state(core);
    if (st != PK_COOP_STORY_CANCELLED && st != PK_COOP_STORY_READY) return 0;
    const uint32_t args[4] = { 0, 0, 0, 0 };
    // The game's own trainer battle, solo, from the field the held script left: only with the hook out.
    story_restore_var(core);
    story_unpatch(core);
    core->busWrite8(core, PK_COOP_STORY_RAM + COOP_EM_ASM_LINK_STORY_STATE, 0);
    return sStory.call(core, STORY_HOOK_FN, args) != 0xFFFFFFFFu;
}
