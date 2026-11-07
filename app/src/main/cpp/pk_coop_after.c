// PokeDaisy - co-op joined battles, what happens after a battle. See pk_coop_after.h.
#include "pk_coop_after.h"

#include <mgba/core/core.h>

#include "pk_coop.h"
#include "pk_coop_emerald.h"
#include "pk_coop_field.h"

// Every game-side write goes through the game's own functions (the app's pk_call), and only while the game stands free on the
// field: a text box, a menu, a fade or a battle is -1 with nothing touched. The callee's first 32 bytes are CRC-checked
// against the bus before it runs (the same table install uses), so a hack that moved or rewrote one is a -1 too.

#define AFTER_CALL_FAILED 0xFFFFFFFFu

// pokeemerald.map's gDifferentSaveFile (the build is byte-identical to retail): EWRAM bool8, TRUE until a new game's first save.
#define AFTER_gDifferentSaveFile 0x020322D4u
// gMain.state (include/main.h: after oamBuffer[128]).
#define AFTER_MAIN_STATE 0x438u
// A struct Pokemon's scratch copy for GiveMonToPlayer: EWRAM past the game's last symbol (0x0203CF64), above co-op's own
// RAM (0x0203F000-0x0203F3FF: the result lives there across the battle's end) and below FieldItems' scratch (0x0203FFF0).
#define AFTER_SCRATCH 0x0203F800u
#define AFTER_MON_DATA_IS_EGG 45u
#define AFTER_FLAG_SET_SEEN 2u
#define AFTER_FLAG_SET_CAUGHT 3u
#define AFTER_SPECIES_LAST 411u
#define AFTER_TRAINER_FLAGS_START 0x500u
#define AFTER_SB1_FLAGS 4720u   // offsetof(struct SaveBlock1, flags), as pk_coop_lock_emerald.h
#define AFTER_MAX_TRAINER_ID 854

#define AFTER_MAX_ITEM 376
#define AFTER_MAX_COUNT 99
#define AFTER_MAX_MONEY 999999
#define AFTER_SAVE_NORMAL 0u
#define AFTER_SAVE_OVERWRITE_DIFFERENT_FILE 4u   // include/save.h: NORMAL 0, LINK, EREADER, HALL_OF_FAME, OVERWRITE_DIFFERENT_FILE
#define AFTER_SAVE_STATUS_OK 1u
#define AFTER_GAME_STAT_SAVED_GAME 0u
#define AFTER_EVO_MODE_NORMAL 0u

// TrySavingData runs ~15.6 million CPU steps (the flash chip's write times: measured headless, 15,642,476 on the co-op test
// save): a call's default cap is 2 million, so the save's call gets this many (6x what it was seen to need). A call that hit
// the cap would leave the flash half written, so the cap is generous and a save is never retried on a timeout's account.
#define AFTER_SAVE_STEPS 100000000L

static pk_coop_call_fn sAfterCall;
static pk_coop_cap_fn sAfterCap;

void pk_coop_after_set_call(pk_coop_call_fn call) {
    sAfterCall = call;
}

void pk_coop_after_set_cap(pk_coop_cap_fn cap) {
    sAfterCap = cap;
}

static uint32_t after_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
        }
    }
    return ~c;
}

// Whether the 32 bytes at `addr` are the retail function's.
static int after_fn_ok(struct mCore* core, uint32_t addr, uint32_t crc) {
    uint8_t code[32];
    for (int b = 0; b < 32; b++) {
        code[b] = (uint8_t) core->busRead8(core, addr + (uint32_t) b);
    }
    return after_crc32(code, 32) == crc;
}

// Calls `fn` (checked against its CRC); *r0 = what it returned. 0 = it couldn't run (not a safe point, never returned, or
// not retail's code). Callers put the read-only calls first, so a 0 there leaves nothing changed.
static int after_call(struct mCore* core, uint32_t fn, uint32_t crc, uint32_t a, uint32_t b, uint32_t c, uint32_t* r0) {
    if (!sAfterCall || !after_fn_ok(core, fn, crc)) {
        return 0;
    }
    const uint32_t args[4] = { a, b, c, 0 };
    uint32_t r = sAfterCall(core, fn, args);
    if (r0) {
        *r0 = r;
    }
    return r != AFTER_CALL_FAILED;
}

static int after_free(struct mCore* core) {
    return core && core->platform(core) == mPLATFORM_GBA && pk_coop_on_free_field(core);
}

static uint32_t after_money_ptr(struct mCore* core) {
    return (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock1Ptr) + COOP_EM_OFF_SB1_MONEY;
}

int pk_coop_give_mon(struct mCore* core, const uint8_t mon[100]) {
    if (!after_free(core)) {
        return -1;
    }
    uint8_t backup[COOP_EM_OFF_MON_SIZE];
    for (uint32_t i = 0; i < sizeof(backup); i++) backup[i] = (uint8_t) core->busRead8(core, AFTER_SCRATCH + i);
    for (uint32_t i = 0; i < sizeof(backup); i++) core->busWrite8(core, AFTER_SCRATCH + i, mon[i]);
    int result = -1;
    uint32_t species = 0, egg = 0, given = 0;
    // The reads first: nothing changed if the game can't be called now.
    if (after_call(core, COOP_EM_GetMonData, COOP_EM_CRC_GetMonData, AFTER_SCRATCH, COOP_EM_MON_DATA_SPECIES, 0, &species)
        && after_call(core, COOP_EM_GetMonData, COOP_EM_CRC_GetMonData, AFTER_SCRATCH, AFTER_MON_DATA_IS_EGG, 0, &egg)) {
        species &= 0xFFFFu;
        if (species < 1 || species > AFTER_SPECIES_LAST) {
            result = 2;   // nothing a party or a box can hold
        } else if (after_call(core, COOP_EM_GiveMonToPlayer, COOP_EM_CRC_GiveMonToPlayer, AFTER_SCRATCH, 0, 0, &given)) {
            given &= 0xFF;
            if (given > 1) {   // MON_CANT_GIVE: every box full
                result = 2;
            } else {
                result = (int) given;   // 0 MON_GIVEN_TO_PARTY, 1 MON_GIVEN_TO_PC
                if (!(egg & 0xFF)) {   // an egg isn't seen or caught
                    uint32_t nat = 0;
                    if (after_call(core, COOP_EM_SpeciesToNationalPokedexNum, COOP_EM_CRC_SpeciesToNationalPokedexNum, species, 0, 0, &nat)) {
                        nat &= 0xFFFFu;
                        after_call(core, COOP_EM_GetSetPokedexFlag, COOP_EM_CRC_GetSetPokedexFlag, nat, AFTER_FLAG_SET_SEEN, 0, NULL);
                        after_call(core, COOP_EM_GetSetPokedexFlag, COOP_EM_CRC_GetSetPokedexFlag, nat, AFTER_FLAG_SET_CAUGHT, 0, NULL);
                    }
                }
            }
        }
    }
    for (uint32_t i = 0; i < sizeof(backup); i++) core->busWrite8(core, AFTER_SCRATCH + i, backup[i]);
    return result;
}

int pk_coop_remove_item(struct mCore* core, int item, int count) {
    if (!after_free(core)) {
        return -1;
    }
    if (item < 1 || item > AFTER_MAX_ITEM || count < 1 || count > AFTER_MAX_COUNT) {
        return 0;
    }
    uint32_t r = 0;
    if (!after_call(core, COOP_EM_RemoveBagItem, COOP_EM_CRC_RemoveBagItem, (uint32_t) item, (uint32_t) count, 0, &r)) {
        return -1;
    }
    return (r & 0xFF) ? 1 : 0;   // FALSE = not enough of it held, nothing removed
}

int pk_coop_set_trainer_flag(struct mCore* core, int trainerId) {
    if (!after_free(core)) {
        return -1;
    }
    if (trainerId < 1 || trainerId > AFTER_MAX_TRAINER_ID) {
        return 0;
    }
    // Read before setting: a trainer this game already beat answers 2 and nothing is written (no second prize, no save).
    uint32_t flag = AFTER_TRAINER_FLAGS_START + (uint32_t) trainerId;
    uint32_t sb1 = (uint32_t) core->busRead32(core, COOP_EM_gSaveBlock1Ptr);
    if (sb1 >= 0x02000000u && sb1 < 0x02040000u
        && ((core->busRead8(core, sb1 + AFTER_SB1_FLAGS + flag / 8u) >> (flag % 8u)) & 1u)) {
        return 2;
    }
    if (sb1 < 0x02000000u || sb1 >= 0x02040000u) {
        return 0;   // no save block to look in: refuse, nothing written
    }
    return after_call(core, COOP_EM_FlagSet, COOP_EM_CRC_FlagSet, flag, 0, 0, NULL) ? 1 : -1;
}

// GetTrainerMoneyToGive over ROM reads: the last party member's level x 4 x the class's rate (the table's 0xFF terminator
// entry is the game's default when a class has none). No x2: this is what a two-trainer battle pays. The game's
// moneyMultiplier (the Amulet Coin's) is 1: the partner's lead item isn't looked at. -1 = a trainer id out of range.
int pk_coop_prize(struct mCore* core, int trainerId) {
    if (!core || trainerId < 1 || trainerId > AFTER_MAX_TRAINER_ID) {
        return -1;
    }
    const uint32_t t = COOP_EM_gTrainers + (uint32_t) trainerId * COOP_EM_OFF_TRAINER_SIZE;
    uint32_t flags = core->busRead8(core, t + COOP_EM_OFF_TRAINER_PARTY_FLAGS) & 3u;
    uint32_t cls = core->busRead8(core, t + COOP_EM_OFF_TRAINER_CLASS);
    uint32_t size = core->busRead8(core, t + COOP_EM_OFF_TRAINER_PARTY_SIZE);
    uint32_t party = (uint32_t) core->busRead32(core, t + COOP_EM_OFF_TRAINER_PARTY);
    if (!size || party < 0x08000000u || party >= 0x0A000000u) {
        return 0;
    }
    // A party entry (flags bit 0 = custom moves, bit 1 = held item) is 8 / 16 / 8 / 16 bytes in the ROM (padded to 4: read off
    // all 854 trainers' party pointers); the level is at +2.
    static const uint32_t monSize[4] = { 8, 16, 8, 16 };
    uint32_t level = core->busRead8(core, party + (size - 1) * monSize[flags] + 2u);
    uint32_t rate = 0;
    for (uint32_t i = 0; i < 256; i++) {   // bounded: the table ends at the 0xFF class
        uint32_t entry = COOP_EM_gTrainerMoneyTable + i * COOP_EM_OFF_TRAINER_MONEY_SIZE;
        uint32_t id = core->busRead8(core, entry);
        rate = core->busRead8(core, entry + 1u);
        if (id == 0xFF || id == cls) {
            break;
        }
    }
    return (int) (4u * level * rate);
}

// What the game pays for this trainer ALONE (a one-trainer battle: GetTrainerMoneyToGive without TWO_OPPONENTS): the prize above, x2
// when the trainer's record is a double battle (gTrainers[id].doubleBattle). -1 for an id outside 1..854.
int pk_coop_prize_single(struct mCore* core, int trainerId) {
    int base = pk_coop_prize(core, trainerId);
    if (base <= 0) {
        return base;
    }
    return core->busRead8(core, COOP_EM_gTrainers + (uint32_t) trainerId * COOP_EM_OFF_TRAINER_SIZE + COOP_EM_OFF_TRAINER_DOUBLE_BATTLE) != 0 ? base * 2 : base;
}

int pk_coop_add_money(struct mCore* core, int amount) {
    if (!after_free(core)) {
        return -1;
    }
    if (amount < 1 || amount > AFTER_MAX_MONEY) {
        return 0;
    }
    return after_call(core, COOP_EM_AddMoney, COOP_EM_CRC_AddMoney, after_money_ptr(core), (uint32_t) amount, 0, NULL) ? 1 : -1;
}

static uint32_t after_le32(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

// The partner's own Pokemon coming back into its own party slot: the slot must hold the same Pokemon (personality, OT ID).
int pk_coop_write_party_mon(struct mCore* core, int slot, const uint8_t mon[100]) {
    if (!after_free(core)) {
        return -1;
    }
    uint32_t count = core->busRead8(core, COOP_EM_gPlayerPartyCount);
    if (slot < 0 || (uint32_t) slot >= count || slot >= (int) COOP_EM_PARTY_SIZE) {
        return 0;
    }
    const uint32_t at = COOP_EM_gPlayerParty + (uint32_t) slot * COOP_EM_OFF_MON_SIZE;
    if ((uint32_t) core->busRead32(core, at) != after_le32(mon)
        || (uint32_t) core->busRead32(core, at + COOP_EM_OFF_BOX_MON_OT_ID) != after_le32(mon + COOP_EM_OFF_BOX_MON_OT_ID)) {
        return 0;
    }
    for (uint32_t i = 0; i < COOP_EM_OFF_MON_SIZE; i++) {
        core->busWrite8(core, at + i, mon[i]);
    }
    return 1;
}

// SetMainCallback2(CB2_WhiteOut): the game's own white-out (halves the money, heals, warps to the last heal spot); it
// counts gMain.state up from 0 and does the work at 120. SetMainCallback2 itself zeroes the state; written again anyway.
int pk_coop_white_out(struct mCore* core) {
    if (!after_free(core)) {
        return -1;
    }
    if (!after_fn_ok(core, COOP_EM_CB2_WhiteOut, COOP_EM_CRC_CB2_WhiteOut)) {
        return -1;
    }
    if (!after_call(core, COOP_EM_SetMainCallback2, COOP_EM_CRC_SetMainCallback2, COOP_EM_CB2_WhiteOut | 1u, 0, 0, NULL)) {
        return -1;
    }
    core->busWrite8(core, COOP_EM_gMain + AFTER_MAIN_STATE, 0);
    return 1;
}

// gCB2_AfterEvolution = CB2_ReturnToField, then BeginEvolutionScene(mon, GetEvolutionTargetSpecies(mon, EVO_MODE_NORMAL, 0),
// TRUE, slot): the game's own scene, back on the field when it ends. 0 = nothing to evolve into.
int pk_coop_evolve(struct mCore* core, int slot) {
    if (!after_free(core)) {
        return -1;
    }
    uint32_t count = core->busRead8(core, COOP_EM_gPlayerPartyCount);
    if (slot < 0 || (uint32_t) slot >= count || slot >= (int) COOP_EM_PARTY_SIZE) {
        return 0;
    }
    const uint32_t mon = COOP_EM_gPlayerParty + (uint32_t) slot * COOP_EM_OFF_MON_SIZE;
    uint32_t target = 0;
    if (!after_call(core, COOP_EM_GetEvolutionTargetSpecies, COOP_EM_CRC_GetEvolutionTargetSpecies, mon, AFTER_EVO_MODE_NORMAL, 0, &target)) {
        return -1;
    }
    target &= 0xFFFFu;
    if (target == 0) {
        return 0;
    }
    if (!after_fn_ok(core, COOP_EM_CB2_ReturnToField, COOP_EM_CRC_CB2_ReturnToField)) {
        return -1;
    }
    const uint32_t before = (uint32_t) core->busRead32(core, COOP_EM_gCB2_AfterEvolution);
    core->busWrite32(core, COOP_EM_gCB2_AfterEvolution, COOP_EM_CB2_ReturnToField | 1u);
    uint32_t args[4] = { mon, target, 1u, (uint32_t) slot };
    if (!sAfterCall || !after_fn_ok(core, COOP_EM_BeginEvolutionScene, COOP_EM_CRC_BeginEvolutionScene)
        || sAfterCall(core, COOP_EM_BeginEvolutionScene, args) == AFTER_CALL_FAILED) {
        core->busWrite32(core, COOP_EM_gCB2_AfterEvolution, before);
        return -1;
    }
    return 1;
}

// task 5: sGlobalScriptContextStatus is CONTEXT_WAITING (1) while ScriptContext_Stop holds a script for a battle (trainerbattle).
int pk_coop_release_script(struct mCore* core) {
    if (!core || core->platform(core) != mPLATFORM_GBA) {
        return -1;
    }
    if (((uint32_t) core->busRead32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2) & ~1u) != COOP_EM_CB2_Overworld) {
        return -1;
    }
    if (core->busRead8(core, COOP_EM_sGlobalScriptContextStatus) != 1) {
        return 0;
    }
    if (!after_call(core, COOP_EM_ScriptContext_Init, COOP_EM_CRC_ScriptContext_Init, 0, 0, 0, NULL)
        || !after_call(core, COOP_EM_ScriptUnfreezeObjectEvents, COOP_EM_CRC_ScriptUnfreezeObjectEvents, 0, 0, 0, NULL)
        || !after_call(core, COOP_EM_UnlockPlayerFieldControls, COOP_EM_CRC_UnlockPlayerFieldControls, 0, 0, 0, NULL)) {
        return -1;
    }
    return 1;
}

// The START menu's save, SaveDoSaveCallback's own steps without its text box: IncrementGameStat(GAME_STAT_SAVED_GAME),
// PausePyramidChallenge, TrySavingData(SAVE_NORMAL, or SAVE_OVERWRITE_DIFFERENT_FILE for a new game's first save). 1 = saved,
// 0 = the game said the save failed.
int pk_coop_save_game(struct mCore* core) {
    if (!after_free(core)) {
        return -1;
    }
    if (!sAfterCap) {
        return -1;   // the save's call would hit the default step cap half way: not started
    }
    // The gate first: all three callees must be retail's, so a refusal changes nothing (-1, retry).
    if (!after_fn_ok(core, COOP_EM_IncrementGameStat, COOP_EM_CRC_IncrementGameStat)
        || !after_fn_ok(core, COOP_EM_PausePyramidChallenge, COOP_EM_CRC_PausePyramidChallenge)
        || !after_fn_ok(core, COOP_EM_TrySavingData, COOP_EM_CRC_TrySavingData) || !sAfterCall) {
        return -1;
    }
    uint32_t status = 0;
    int different = core->busRead8(core, AFTER_gDifferentSaveFile) == 1;
    if (!after_call(core, COOP_EM_IncrementGameStat, COOP_EM_CRC_IncrementGameStat, AFTER_GAME_STAT_SAVED_GAME, 0, 0, NULL)
        || !after_call(core, COOP_EM_PausePyramidChallenge, COOP_EM_CRC_PausePyramidChallenge, 0, 0, 0, NULL)) {
        return -1;
    }
    sAfterCap(AFTER_SAVE_STEPS);
    int saved = after_call(core, COOP_EM_TrySavingData, COOP_EM_CRC_TrySavingData,
                           different ? AFTER_SAVE_OVERWRITE_DIFFERENT_FILE : AFTER_SAVE_NORMAL, 0, 0, &status);
    sAfterCap(0);
    if (!saved) {
        return -2;   // started but never returned: the flash may be mid-write, never retried
    }
    if (different) {
        core->busWrite8(core, AFTER_gDifferentSaveFile, 0);
    }
    return (status & 0xFF) == AFTER_SAVE_STATUS_OK ? 1 : 0;
}
