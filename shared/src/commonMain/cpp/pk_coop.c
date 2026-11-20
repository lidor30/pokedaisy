// PokeDaisy - co-op joined battles on retail English Emerald: the gate (see pk_coop.h). Plain C over `struct mCore*`, no JNI:
// native-capture/mgba_dump.c compiles this very file in (`coopinstall` & co.) and scripts/coop_headless_check.sh runs the
// checks in native-capture/coop/ against the player's own ROM and a save.
//
// A joined battle is the game's own link battle (pk_coop_link.c and its parts); what is left here is the retail check the
// join rules and the after-battle steps stand on: pk_coop_install changes NOTHING - it compares the first 32 bytes of every
// function co-op reads or calls with retail Emerald's (a hack, another language or a cheat on that code refuses it) and says
// whether this is a game co-op can run in. The link bridge puts its own code and patches in (pk_coop_link_install), only for
// the length of a battle.

#include <stddef.h>
#include <stdint.h>

#include <mgba/core/core.h>

#include "pk_coop.h"
#include "pk_coop_emerald.h"
#include "pk_coop_lock.h"   // the story lock's head patch sits on ScriptContext_SetupScript

static const struct { const char* name; uint32_t addr, crc; } sCoopCrcs[] = {
#define COOP_FN(f) { #f, COOP_EM_##f, COOP_EM_CRC_##f }
    COOP_FN(Task_BattleStart),            // the join rules: a battle that is starting
    COOP_FN(CB2_Overworld),               // the field the after-battle steps and the free-field test stand on
    COOP_FN(CB2_WhiteOut),
    COOP_FN(CB2_EvolutionSceneUpdate),
    COOP_FN(CB2_InitBattle),
    COOP_FN(ScriptContext_SetupScript),   // the story lock's and the story join's hook
    COOP_FN(GetMonData),                  // a joined wild battle's second Pokemon is rolled by the game's own functions
    COOP_FN(TryGenerateWildMon),
    COOP_FN(GetCurrentMapWildMonHeaderId),
#undef COOP_FN
};

static int sCoopInstalled;
static const char* sCoopRefused = "";

static uint32_t coop_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
        }
    }
    return ~c;
}

int pk_coop_install(struct mCore* core) {
    sCoopInstalled = 0;
    if (!core || core->platform(core) != mPLATFORM_GBA) {
        sCoopRefused = "not a GBA game";
        return 0;
    }
    for (size_t i = 0; i < sizeof(sCoopCrcs) / sizeof(sCoopCrcs[0]); i++) {
        uint8_t code[32];
        for (int b = 0; b < 32; b++) code[b] = (uint8_t) core->busRead8(core, sCoopCrcs[i].addr + (uint32_t) b);
        pk_coop_lock_original_bytes(sCoopCrcs[i].addr, code, 32);   // the lock's own head patch is not a refusal
        pk_coop_link_original_bytes(sCoopCrcs[i].addr, code, 32);   // nor are the link bridge's head patches (while a link battle runs)
        if (coop_crc32(code, 32) != sCoopCrcs[i].crc) {
            sCoopRefused = sCoopCrcs[i].name;
            return 0;
        }
    }
    sCoopInstalled = 1;
    sCoopRefused = "";
    return 1;
}

void pk_coop_uninstall(struct mCore* core) {
    (void) core;
    sCoopInstalled = 0;
}

int pk_coop_battle_starting(struct mCore* core) {
    if (!sCoopInstalled || !core) {
        return 0;
    }
    for (uint32_t t = 0; t < 16; t++) {   // NUM_TASKS
        uint32_t task = COOP_EM_gTasks + t * COOP_EM_OFF_TASK_SIZE;
        if (core->busRead8(core, task + COOP_EM_OFF_TASK_IS_ACTIVE)
            && ((uint32_t) core->busRead32(core, task + COOP_EM_OFF_TASK_FUNC) & ~1u) == COOP_EM_Task_BattleStart) {
            return 1;
        }
    }
    return 0;
}

void pk_coop_reset(void) {
    sCoopInstalled = 0;
}

const char* pk_coop_refused(void) {
    return sCoopRefused;
}

int pk_coop_installed(void) {
    return sCoopInstalled;
}
