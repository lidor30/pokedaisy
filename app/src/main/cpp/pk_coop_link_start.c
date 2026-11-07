// PokeDaisy - starting a joined trainer battle as a link battle (plan 6 Task 2; the API is at the end of pk_coop.h).
//
// The host's game is held where a joined battle is offered: Task_BattleStart is running (pk_coop_join_kind: the trainer script has
// set the battle's flags and ids and gMain.savedCallback = CB2_EndTrainerBattle), usually with the transition's first frame drawn.
// When the partner says yes the host does what the cable club's own start does from the field (pk_coop_link_begin) instead of the
// transition: the transition's callback is put back to the field's, the battle flags are cleared for begin and set as the link
// multi's, every task goes (the game's ResetTasks: Task_BattleStart and the transition's own, which would run on into the party
// showcase; the game itself resets them all in CB2_InitBattleInternal), and then the two things the host's script still needs are
// put back - savedCallback (CB2_EndTrainerBattle flags the trainers beaten and goes on with the script; begin's own
// CB2_ReturnToField would leave it waiting forever) and the real trainer ids in gTrainerBattleOpponent_A / _B (begin writes them for
// the tower flow's trainer lookups, which scripts/coop_link_emerald.s answers from gTrainers; the end puts them back as the script
// had them). The partner's game starts from its free field (no task to stop). Plain C over `struct mCore*`:
// native-capture/mgba_dump.c compiles this file in with pk_coop_link.c.
#include <stdint.h>
#include <string.h>

#include <mgba/core/core.h>

#include "pk_cheats.h"
#include "pk_coop.h"
#include "pk_coop_emerald.h"
#include "pk_coop_field.h"

// struct Task (include/task.h): func u32 @0, isActive @4, prev @5, next @6, priority @7, data[16] s16 @8.
#define LSTART_TASK_PREV 5u
#define LSTART_TASK_NEXT 6u
#define LSTART_TASK_PRIORITY 7u
#define LSTART_TASK_DATA 8u
#define LSTART_HEAD_SENTINEL 0xFEu
#define LSTART_TAIL_SENTINEL 0xFFu
#define LSTART_NUM_TASKS 16u
_Static_assert(COOP_EM_OFF_TASK_IS_ACTIVE == 4, "pk_coop_link_start.c: struct Task's isActive");

// What the host's start changed that the end puts back (the host's game only; the partner's start keeps none).
struct lstart_state {
    int active;               // the host's start went through (its savedCallback goes back at the end)
    int have, restored;       // note taken (begin went past its checks) / the end's write done
    int aiPatched;            // the AI flag patches are in (two bits: mask word, TWO_OPPONENTS test)
    uint16_t origA, origB;    // gTrainerBattleOpponent_A / _B before begin (a single trainer's B was not A)
    uint32_t savedCallback;   // gMain.savedCallback before begin (with the Thumb bit)
    uint8_t enemy[6 * COOP_EM_OFF_MON_SIZE];   // gEnemyParty before begin
};
static struct lstart_state sStart;
int pk_coop_link_test_fail;

static uint32_t lstart_task_func(struct mCore* core, uint32_t task) {
    return (uint32_t) core->busRead32(core, task + COOP_EM_OFF_TASK_FUNC) & ~1u;
}

// Whether a Task_BattleStart is running.
static int lstart_battle_start_task(struct mCore* core) {
    for (uint32_t t = 0; t < LSTART_NUM_TASKS; t++) {
        uint32_t task = COOP_EM_gTasks + t * COOP_EM_OFF_TASK_SIZE;
        if (core->busRead8(core, task + COOP_EM_OFF_TASK_IS_ACTIVE) && lstart_task_func(core, task) == COOP_EM_Task_BattleStart) {
            return 1;
        }
    }
    return 0;
}

// The game's ResetTasks (task.c), as it writes the task table.
static void lstart_reset_tasks(struct mCore* core) {
    for (uint32_t t = 0; t < LSTART_NUM_TASKS; t++) {
        uint32_t task = COOP_EM_gTasks + t * COOP_EM_OFF_TASK_SIZE;
        core->busWrite32(core, task + COOP_EM_OFF_TASK_FUNC, COOP_EM_TaskDummy | 1u);
        core->busWrite8(core, task + COOP_EM_OFF_TASK_IS_ACTIVE, 0);
        core->busWrite8(core, task + LSTART_TASK_PREV, (uint8_t) t);
        core->busWrite8(core, task + LSTART_TASK_NEXT, (uint8_t) (t + 1));
        core->busWrite8(core, task + LSTART_TASK_PRIORITY, 0xFF);
        for (uint32_t b = 0; b < 32; b++) core->busWrite8(core, task + LSTART_TASK_DATA + b, 0);
    }
    core->busWrite8(core, COOP_EM_gTasks + LSTART_TASK_PREV, LSTART_HEAD_SENTINEL);
    core->busWrite8(core, COOP_EM_gTasks + (LSTART_NUM_TASKS - 1) * COOP_EM_OFF_TASK_SIZE + LSTART_TASK_NEXT, LSTART_TAIL_SENTINEL);
}

int pk_coop_link_host_hold(struct mCore* core) {
    if (!core || pk_coop_link_state(core) != PK_COOP_LINK_IDLE || sStart.active) {
        return 0;
    }
    int kind = pk_coop_join_kind(core);   // the offer's own rules: an ordinary wild battle (1), two trainers (2) or one trainer with 2+ Pokemon (3)
    return (kind == 1 || kind == 2 || kind == 3) && lstart_battle_start_task(core);
}

int pk_coop_link_host_go(struct mCore* core, const struct pk_coop_link_setup* partner) {
    if (!partner || !pk_coop_link_host_hold(core)) {
        return 0;
    }
    int ids[2];
    int wild = pk_coop_join_kind(core) == 1;
    pk_coop_join_trainers(core, ids);   // [0] the first trainer, [1] the second (0 for a single trainer; both 0 for a wild battle)
    int single = ids[1] == 0;
    uint32_t flags = (uint32_t) core->busRead32(core, COOP_EM_gBattleTypeFlags);
    uint32_t cb2 = (uint32_t) core->busRead32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2);
    if (!wild && ids[0] <= 0) {
        return 0;
    }
    struct pk_coop_link_setup setup = *partner;
    setup.master = 1;
    if (wild) {   // the host's game rolls the two wild Pokemon itself (begin's WILD kind), nothing of the held encounter is kept
        setup.kind = PK_COOP_LINK_WILD;
        setup.trainerA = 0;
        setup.trainerB = 0;
    } else {
        setup.kind = single ? PK_COOP_LINK_SINGLE : PK_COOP_LINK_TRAINERS;
        setup.trainerA = (uint16_t) ids[0];
        setup.trainerB = (uint16_t) (single ? ids[0] : ids[1]);
    }
    // begin wants the field's own callback (the transition's first frame set CB2_OverworldBasic) and no battle flags; everything
    // it changes (ids, savedCallback, enemy party, the link flags) is put back by begin's own refusal path from the note it took.
    core->busWrite32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2, COOP_EM_CB2_Overworld | 1u);
    core->busWrite32(core, COOP_EM_gBattleTypeFlags, 0);
    if (!pk_coop_link_begin(core, &setup)) {
        core->busWrite32(core, COOP_EM_gBattleTypeFlags, flags);
        core->busWrite32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2, cb2);
        return 0;
    }
    lstart_reset_tasks(core);   // no transition: the link battle's own start has taken over
    core->busWrite32(core, COOP_EM_sTransitionData, 0);   // its block went with the tasks (CB2_InitBattle resets the heap)
    // begin's own savedCallback (CB2_ReturnToField) back to the script's: CB2_EndTrainerBattle flags the trainers and goes on
    core->busWrite32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK, sStart.savedCallback);
    sStart.active = 1;
    return 1;
}

int pk_coop_link_partner_go(struct mCore* core, const struct pk_coop_link_setup* setup) {
    if (!core || !setup || setup->master || !pk_coop_link_installed()) {
        return 0;
    }
    if (!pk_coop_on_free_field(core)) {
        return -1;
    }
    return pk_coop_link_begin(core, setup);
}

void pk_coop_link_end_ids(struct mCore* core) {
    if (!core || !sStart.have || sStart.restored) {
        return;
    }
    sStart.restored = 1;   // once: a later task may change the callback on purpose (a leave)
    // The ids as they were before the battle: for the host's script they are what CB2_EndTrainerBattle flags beaten (a single
    // trainer's B is what its script left), and the host's callback is the script's again.
    core->busWrite16(core, COOP_EM_gTrainerBattleOpponent_A, sStart.origA);
    core->busWrite16(core, COOP_EM_gTrainerBattleOpponent_B, sStart.origB);
    if (sStart.active) {
        core->busWrite32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK, sStart.savedCallback);
    }
}

void pk_coop_link_start_forget(void) {
    memset(&sStart, 0, sizeof(sStart));
}

void pk_coop_link_start_note(struct mCore* core) {
    sStart.origA = (uint16_t) core->busRead16(core, COOP_EM_gTrainerBattleOpponent_A);
    sStart.origB = (uint16_t) core->busRead16(core, COOP_EM_gTrainerBattleOpponent_B);
    sStart.savedCallback = (uint32_t) core->busRead32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK);
    for (uint32_t i = 0; i < sizeof(sStart.enemy); i++) {
        sStart.enemy[i] = (uint8_t) core->busRead8(core, COOP_EM_gEnemyParty + i);
    }
    sStart.have = 1;
    sStart.restored = 0;
}

void pk_coop_link_start_unwind(struct mCore* core) {
    if (!sStart.have) {
        return;
    }
    core->busWrite16(core, COOP_EM_gTrainerBattleOpponent_A, sStart.origA);
    core->busWrite16(core, COOP_EM_gTrainerBattleOpponent_B, sStart.origB);
    core->busWrite32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK, sStart.savedCallback);
    for (uint32_t i = 0; i < sizeof(sStart.enemy); i++) {
        core->busWrite8(core, COOP_EM_gEnemyParty + i, sStart.enemy[i]);
    }
    sStart.have = 0;
}

// The AI flag choice (BattleAI_SetupAIData), while a link battle runs: see gen_coop_emerald.py's AI_ block.
int pk_coop_link_start_patches_in(struct mCore* core) {
    uint8_t expect[4], value[4];
    for (int b = 0; b < 4; b++) {
        expect[b] = (uint8_t) (COOP_EM_AI_MASK_WORD >> (8 * b));
        value[b] = (uint8_t) (COOP_EM_AI_MASK_NEW >> (8 * b));
    }
    if (pk_cheats_patch_overlaps(COOP_EM_AI_MASK_ADDR, 4) || pk_cheats_patch_overlaps(COOP_EM_AI_TWO_ADDR, 2)
        || !pk_cheats_patch(core, COOP_EM_AI_MASK_ADDR, expect, 4, COOP_EM_AI_MASK_ADDR, value, 4)) {
        return 0;
    }
    expect[0] = (uint8_t) COOP_EM_AI_TWO_INSN;
    expect[1] = (uint8_t) (COOP_EM_AI_TWO_INSN >> 8);
    value[0] = (uint8_t) COOP_EM_AI_TWO_NEW;
    value[1] = (uint8_t) (COOP_EM_AI_TWO_NEW >> 8);
    if (!pk_cheats_patch(core, COOP_EM_AI_TWO_ADDR, expect, 2, COOP_EM_AI_TWO_ADDR, value, 2)) {
        pk_cheats_unpatch_range(core, COOP_EM_AI_MASK_ADDR, 4);
        return 0;
    }
    sStart.aiPatched = 1;
    return 1;
}

void pk_coop_link_start_patches_out(struct mCore* core) {
    if (sStart.aiPatched) {
        pk_cheats_unpatch_range(core, COOP_EM_AI_TWO_ADDR, 2);
        pk_cheats_unpatch_range(core, COOP_EM_AI_MASK_ADDR, 4);
        sStart.aiPatched = 0;
    }
}

void pk_coop_link_start_ids(struct mCore* core, const struct pk_coop_link_setup* setup) {
    // The real ids: the tower flow's name / picture / class lookups are answered from gTrainers while the bridge's hooks are in.
    core->busWrite16(core, COOP_EM_gTrainerBattleOpponent_A, setup->trainerA);
    core->busWrite16(core, COOP_EM_gTrainerBattleOpponent_B, setup->kind == PK_COOP_LINK_SINGLE ? setup->trainerA : setup->trainerB);
}

int pk_coop_link_start_single_party(struct mCore* core, pk_coop_call_fn call, uint16_t trainer) {
    if (!call) {
        return 0;
    }
    const uint32_t args[4] = { COOP_EM_gEnemyParty, trainer, 1, 0 };   // ZeroEnemyPartyMons, then up to 6 Pokemon
    if (call(core, COOP_EM_CreateNPCTrainerParty, args) == 0xFFFFFFFFu) {
        return 0;
    }
    uint8_t mons[COOP_EM_PARTY_SIZE][COOP_EM_OFF_MON_SIZE];
    int n = 0;
    for (int s = 0; s < COOP_EM_PARTY_SIZE; s++) {
        for (uint32_t i = 0; i < COOP_EM_OFF_MON_SIZE; i++) {
            mons[s][i] = (uint8_t) core->busRead8(core, COOP_EM_gEnemyParty + (uint32_t) s * COOP_EM_OFF_MON_SIZE + i);
        }
    }
    while (n < COOP_EM_PARTY_SIZE && mons[n][COOP_EM_OFF_MON_LEVEL] != 0) n++;
    if (n < 2) {
        return 0;   // a one-Pokemon trainer is not joined
    }
    // 0 2 4 to the left foe (slots 0-2, battler 1), 1 3 5 to the right foe (slots 3-5, battler 3): the leads are the trainer's
    // first two, as in a double battle.
    int left = 0, right = 0;
    uint8_t out[COOP_EM_PARTY_SIZE][COOP_EM_OFF_MON_SIZE];
    memset(out, 0, sizeof(out));
    for (int i = 0; i < n; i++) {
        memcpy(out[(i & 1) ? 3 + right++ : left++], mons[i], COOP_EM_OFF_MON_SIZE);
    }
    for (int s = 0; s < COOP_EM_PARTY_SIZE; s++) {
        for (uint32_t i = 0; i < COOP_EM_OFF_MON_SIZE; i++) {
            core->busWrite8(core, COOP_EM_gEnemyParty + (uint32_t) s * COOP_EM_OFF_MON_SIZE + i, out[s][i]);
        }
    }
    return 1;
}
