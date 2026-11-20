// PokeDaisy - co-op joined battles, the field side. See pk_coop_field.h.
#include <stdint.h>

#include <mgba/core/core.h>

#include "pk_coop.h"
#include "pk_coop_emerald.h"
#include "pk_coop_field.h"

// gMain.callback2 is at +4 (include/main.h).
#define PK_COOP_MAIN_CALLBACK2 4u
// gMain.inBattle: bit 0 of the byte at +0x439 (include/main.h).
#define PK_COOP_MAIN_IN_BATTLE 0x439u
// gPaletteFade.active: bit 7 of the byte at +7 (agbcc compiles `gPaletteFade.active` to `ldrb [r0, #7]; lsr #7`).
#define PK_COOP_FADE_ACTIVE_BYTE 7u
#define PK_COOP_FADE_ACTIVE_BIT 0x80u

int pk_coop_on_free_field(struct mCore* core) {
    if (!core) {
        return 0;
    }
    uint32_t cb2 = (uint32_t) core->busRead32(core, COOP_EM_gMain + PK_COOP_MAIN_CALLBACK2) & ~1u;
    if (cb2 != COOP_EM_CB2_Overworld) {
        return 0;
    }
    if (core->busRead8(core, COOP_EM_sLockFieldControls) != 0) {
        return 0;
    }
    if (core->busRead8(core, COOP_EM_gPaletteFade + PK_COOP_FADE_ACTIVE_BYTE) & PK_COOP_FADE_ACTIVE_BIT) {
        return 0;
    }
    if (core->busRead8(core, COOP_EM_gMain + PK_COOP_MAIN_IN_BATTLE) & 1u) {
        return 0;
    }
    return 1;
}

// Task_ShowStartMenu (start_menu.c; the pokeemerald map's 0x0809FA34): the task that runs the START menu. gMenuCallback
// keeps pointing at HandleStartMenuInput after the menu closes, so the callback alone says nothing.
#define PK_COOP_TASK_SHOW_START_MENU 0x0809FA34u
// BATTLE_TYPE_* the join rules read (include/constants/battle.h); the engine header has DOUBLE / TRAINER / MULTI /
// TWO_OPPONENTS / INGAME_PARTNER.
#define PK_COOP_BT_LINK            0x00000002u
#define PK_COOP_BT_FIRST_BATTLE    0x00000010u
#define PK_COOP_BT_SAFARI          0x00000080u
#define PK_COOP_BT_WALLY           0x00000200u
#define PK_COOP_BT_ROAMER          0x00000400u
#define PK_COOP_BT_LEGENDARY       0x00002000u
#define PK_COOP_BT_BIG_MONS        0x70005000u   // KYOGRE_GROUDON | REGI | GROUDON | KYOGRE | RAYQUAZA
#define PK_COOP_BT_FRONTIER        0x003F0100u   // BATTLE_TOWER | DOME | PALACE | ARENA | FACTORY | PIKE | PYRAMID
#define PK_COOP_BT_PYRAMID         0x00200000u
#define PK_COOP_BT_RECORDED        0x03000000u

static uint32_t coop_saved_callback(struct mCore* core) {
    return (uint32_t) core->busRead32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK) & ~1u;
}

int pk_coop_join_kind(struct mCore* core) {
    if (!core || !pk_coop_battle_starting(core)) {
        return 0;
    }
    uint32_t flags = (uint32_t) core->busRead32(core, COOP_EM_gBattleTypeFlags);
    uint32_t saved = coop_saved_callback(core);
    if (!(flags & COOP_EM_BATTLE_TYPE_TRAINER)) {
        // DoStandardWildBattle sets CB2_EndWildBattle; a scripted wild battle (and the first one) has the scripted one.
        uint32_t never = PK_COOP_BT_FIRST_BATTLE | PK_COOP_BT_LEGENDARY | PK_COOP_BT_SAFARI | PK_COOP_BT_PYRAMID
                       | PK_COOP_BT_ROAMER | PK_COOP_BT_LINK | PK_COOP_BT_BIG_MONS | PK_COOP_BT_WALLY | PK_COOP_BT_RECORDED
                       | PK_COOP_BT_FRONTIER | COOP_EM_BATTLE_TYPE_MULTI | COOP_EM_BATTLE_TYPE_INGAME_PARTNER;
        return saved == COOP_EM_CB2_EndWildBattle && !(flags & never) ? 1 : 0;
    }
    // One trainer (TRAINER, or TRAINER | DOUBLE for a trainer whose record is a double battle) with two or more Pokemon:
    // the link start gives it both foe halves (pk_coop_link_start_single_party). A one-Pokemon trainer has no
    // co-op (the user's call).
    if (flags == COOP_EM_BATTLE_TYPE_TRAINER || flags == (COOP_EM_BATTLE_TYPE_TRAINER | COOP_EM_BATTLE_TYPE_DOUBLE)) {
        uint32_t id = core->busRead16(core, COOP_EM_gTrainerBattleOpponent_A);
        if (saved != COOP_EM_CB2_EndTrainerBattle || id == 0 || id >= COOP_EM_TRAINERS_COUNT) {
            return 0;
        }
        uint32_t size = core->busRead8(core, COOP_EM_gTrainers + id * COOP_EM_OFF_TRAINER_SIZE + COOP_EM_OFF_TRAINER_PARTY_SIZE);
        return size >= 2 && size <= COOP_EM_PARTY_SIZE ? 3 : 0;
    }
    uint32_t both = COOP_EM_BATTLE_TYPE_TRAINER | COOP_EM_BATTLE_TYPE_TWO_OPPONENTS;
    uint32_t never = COOP_EM_BATTLE_TYPE_INGAME_PARTNER | COOP_EM_BATTLE_TYPE_MULTI | PK_COOP_BT_FRONTIER | PK_COOP_BT_LINK
                   | PK_COOP_BT_RECORDED;
    if ((flags & both) != both || (flags & never) || saved != COOP_EM_CB2_EndTrainerBattle) {
        return 0;
    }
    if (core->busRead16(core, COOP_EM_gTrainerBattleOpponent_A) == 0 || core->busRead16(core, COOP_EM_gTrainerBattleOpponent_B) == 0) {
        return 0;
    }
    return 2;
}

void pk_coop_join_trainers(struct mCore* core, int out[2]) {
    out[0] = out[1] = 0;
    int kind = pk_coop_join_kind(core);
    if (kind != 2 && kind != 3) {
        return;
    }
    out[0] = core->busRead16(core, COOP_EM_gTrainerBattleOpponent_A);
    out[1] = kind == 2 ? core->busRead16(core, COOP_EM_gTrainerBattleOpponent_B) : 0;
}

int pk_coop_partner_free(struct mCore* core) {
    if (!core) {
        return 0;
    }
    if (pk_coop_on_free_field(core)) {
        return 1;
    }
    // The START menu's top level: still the field's callback, no fade, no battle, the menu's task running and its input
    // handler the current callback (a submenu - SAVE, a screen - has another).
    if (((uint32_t) core->busRead32(core, COOP_EM_gMain + PK_COOP_MAIN_CALLBACK2) & ~1u) != COOP_EM_CB2_Overworld
        || (core->busRead8(core, COOP_EM_gPaletteFade + PK_COOP_FADE_ACTIVE_BYTE) & PK_COOP_FADE_ACTIVE_BIT)
        || (core->busRead8(core, COOP_EM_gMain + PK_COOP_MAIN_IN_BATTLE) & 1u)
        || ((uint32_t) core->busRead32(core, COOP_EM_gMenuCallback) & ~1u) != COOP_EM_HandleStartMenuInput) {
        return 0;
    }
    for (uint32_t t = 0; t < 16; t++) {
        uint32_t task = COOP_EM_gTasks + t * COOP_EM_OFF_TASK_SIZE;
        if (core->busRead8(core, task + COOP_EM_OFF_TASK_IS_ACTIVE)
            && ((uint32_t) core->busRead32(core, task + COOP_EM_OFF_TASK_FUNC) & ~1u) == PK_COOP_TASK_SHOW_START_MENU) {
            return 1;
        }
    }
    return 0;
}

