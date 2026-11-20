// PokeDaisy - the end of a joined link battle (plan 6 Task 5; see pk_coop.h's task 5 block). The game ends a link battle its own way (a
// VS screen, a record prompt, a link reconnect); here one patched halfword (pk_coop_link.c) sends it down the normal-end branch to
// gMain.savedCallback, so each game is back on its own field like after any battle. What no script paid for, because a link battle
// has none, is this file: it merges the party back before the game looks at it, then on each game's own free field pays the prize,
// sets the trainers' flags, starts the evolutions and, when nothing in the whole party can fight after a win, the white-out.
// Plain C over `struct mCore*`, no JNI: native-capture/mgba_dump.c compiles this very file in.

#include <stdint.h>
#include <string.h>

#include <mgba/core/core.h>

#include "pk_coop.h"
#include "pk_coop_after.h"
#include "pk_coop_emerald.h"
#include "pk_coop_field.h"

#define LEND_OUTCOME_WON    1
#define LEND_OUTCOME_LOST   2
#define LEND_OUTCOME_DREW   3
#define LEND_OUTCOME_RAN    4
#define LEND_OUTCOME_MASK   0x7F   // B_OUTCOME_LINK_BATTLE_RAN (1 << 7) is a flag on top

struct lend_ctx {
    int stage;                // 0 = waiting for the end, 1 = steps running, 2 = result ready
    uint8_t outcome;
    uint8_t twoTrainers;      // the TRAINERS kind
    uint8_t leveledAll;       // as the merge found them (the result's)
    uint8_t leveledLeft;      // slots whose evolution check is still to run
    uint8_t blackoutWanted;   // the game won't make it (a win with nothing able), the app starts it
    uint8_t blackoutNative;   // a LOST battle with nothing able: the game's own CB2_WhiteOut
    uint8_t prizeDone, blackoutDone;
    uint8_t releaseScript;    // a lost battle walked away from: the host's waiting script is let go once on the field
    uint8_t flagDone[2];
    uint8_t flagsByGame;      // the host's savedCallback is the script's CB2_EndTrainerBattle: the game sets the trainers' flags itself
    uint16_t trainer[2];      // to flag (0 = none)
    uint32_t prize;           // to pay (0 = none)
    uint32_t paid;
    uint8_t result[PK_COOP_LINK_RESULT_SIZE];
};
static struct lend_ctx sEnd;

static void lend_put16(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t) v;
    p[1] = (uint8_t) (v >> 8);
}

static void lend_put32(uint8_t* p, uint32_t v) {
    lend_put16(p, v);
    lend_put16(p + 2, v >> 16);
}

// The first tick the bridge says ENDED: before the game's own IsPlayerDefeated / CB2_WhiteOut (about three frames after the link
// closes) look at the party, the party is merged back and the battle's outcome noted.
static void lend_snapshot(struct mCore* core, const struct pk_coop_link_setup* su) {
    struct pk_coop_link_merge m;
    memset(&sEnd, 0, sizeof(sEnd));
    sEnd.stage = 1;
    // The Amulet Coin: the game doubles the prize when a Pokemon of the player's side holding one is switched in (the two first sent out
    // here: the battle party's slots 0 and 3, read before the merge).
    const int mult = (pk_coop_link_mon_item(core, COOP_EM_gPlayerParty) == COOP_EM_ITEM_AMULET_COIN
                      || pk_coop_link_mon_item(core, COOP_EM_gPlayerParty + 3u * COOP_EM_OFF_MON_SIZE) == COOP_EM_ITEM_AMULET_COIN) ? 2 : 1;
    sEnd.outcome = (uint8_t) (core->busRead8(core, COOP_EM_gBattleOutcome) & LEND_OUTCOME_MASK);
    pk_coop_link_restore(core, &m);
    sEnd.leveledAll = sEnd.leveledLeft = m.leveled;
    const int won = sEnd.outcome == LEND_OUTCOME_WON;
    const int lost = sEnd.outcome == LEND_OUTCOME_LOST || sEnd.outcome == LEND_OUTCOME_DREW;
    sEnd.twoTrainers = su->kind == PK_COOP_LINK_TRAINERS;
    sEnd.flagsByGame = (uint32_t) core->busRead32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK) == (COOP_EM_CB2_EndTrainerBattle | 1u);
    if (won && (su->kind == PK_COOP_LINK_TRAINERS || su->kind == PK_COOP_LINK_SINGLE)) {
        // A two-trainer battle pays both trainers' prizes (no x2); a single trainer pays its own. The trainers are beaten.
        sEnd.trainer[0] = su->trainerA;
        sEnd.trainer[1] = su->kind == PK_COOP_LINK_TRAINERS ? su->trainerB : 0;
        int a = su->trainerA ? (su->kind == PK_COOP_LINK_SINGLE ? pk_coop_prize_single(core, su->trainerA) : pk_coop_prize(core, su->trainerA)) : 0;
        int b = sEnd.trainer[1] ? pk_coop_prize(core, sEnd.trainer[1]) : 0;
        uint32_t total = (uint32_t) ((a > 0 ? a : 0) + (b > 0 ? b : 0)) * (uint32_t) mult;
        sEnd.prize = total > 999999u ? 999999u : total;   // AddMoney itself stops at the cap: a bigger prize is paid up to it
    }
    if (lost) {
        if (m.able) {
            // The game's CB2_EndWildBattle would white out on LOST / DREW; with an able Pokemon anywhere in the whole party the player
            // doesn't black out, so the game is told the battle was a run: no white-out, the field again.
            core->busWrite8(core, COOP_EM_gBattleOutcome, LEND_OUTCOME_RAN);
            // ...and a trainer's CB2_EndTrainerBattle (a later task's savedCallback for the host) would take RAN for a win: register the
            // trainer in Match Call, set its flags and carry the host's script on. The no-side-effect way back is the wild battle's, and
            // the host's script (held by its trainerbattle) is let go on the field (pk_coop_release_script), so the trainer can be fought again.
            core->busWrite32(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK, COOP_EM_CB2_EndWildBattle | 1u);
            sEnd.releaseScript = 1;
        } else {
            sEnd.blackoutNative = 1;
        }
    } else if (sEnd.outcome && !m.able) {
        sEnd.blackoutWanted = 1;   // a win (or a run / a catch) with nothing able left: the game would walk on
    }
}

static void lend_finish(struct mCore* core) {
    if (!pk_coop_link_drained(core)) {
        return;   // the game's last block is still in the outbox: a reset now would lose it
    }
    memset(sEnd.result, 0, sizeof(sEnd.result));
    sEnd.result[0] = 1;
    sEnd.result[1] = sEnd.outcome;
    sEnd.result[2] = (uint8_t) ((sEnd.blackoutWanted || sEnd.blackoutNative ? 1 : 0) | (sEnd.twoTrainers ? 2 : 0));
    sEnd.result[3] = sEnd.leveledAll;
    lend_put16(sEnd.result + 4, sEnd.trainer[0]);
    lend_put16(sEnd.result + 6, sEnd.trainer[1]);
    lend_put32(sEnd.result + 8, sEnd.paid);
    sEnd.stage = 2;
}

void pk_coop_link_tick(struct mCore* core) {
    if (!core) {
        return;
    }
    const struct pk_coop_link_setup* su = pk_coop_link_setup_get();
    const int ended = su && pk_coop_link_state(core) == PK_COOP_LINK_ENDED;
    if (sEnd.stage == 2 && su && !ended) {
        memset(&sEnd, 0, sizeof(sEnd));   // a new battle began: the last one's result is stale
    }
    if (sEnd.stage == 0) {
        if (ended) {
            lend_snapshot(core, su);
        }
        return;
    }
    if (sEnd.stage == 2) {
        return;
    }
    if (!su) {
        memset(&sEnd, 0, sizeof(sEnd));   // reset before the end ran: the battle was cut short, nothing more to pay
        return;
    }
    // Each step answers 1 / 0 when it is through and -1 when the game isn't free (or can't be called now): tried again next frame.
    if (sEnd.releaseScript) {
        if (pk_coop_release_script(core) >= 0) {
            sEnd.releaseScript = 0;
        }
        return;
    }
    if (sEnd.prize && !sEnd.prizeDone) {
        int r = pk_coop_add_money(core, (int) sEnd.prize);
        if (r >= 0) {
            sEnd.prizeDone = 1;
            sEnd.paid = r == 1 ? sEnd.prize : 0;
        }
        return;
    }
    for (int i = 0; i < 2; i++) {
        if (sEnd.trainer[i] && !sEnd.flagDone[i] && !sEnd.flagsByGame) {   /* merge: not twice, the game's own end does it for the host */
            if (pk_coop_set_trainer_flag(core, sEnd.trainer[i]) >= 0) {
                sEnd.flagDone[i] = 1;
            }
            return;
        }
    }
    while (sEnd.leveledLeft) {
        int slot = 0;
        while (!((sEnd.leveledLeft >> slot) & 1)) slot++;
        int r = pk_coop_evolve(core, slot);
        if (r < 0) {
            return;
        }
        sEnd.leveledLeft &= (uint8_t) ~(1u << slot);
        if (r == 1) {
            return;   // the evolution scene runs; the next one waits for the field again
        }
    }
    if (sEnd.blackoutWanted && !sEnd.blackoutDone) {
        if (pk_coop_white_out(core) == 1) {
            sEnd.blackoutDone = 1;
        }
        return;
    }
    if (pk_coop_on_free_field(core)) {   // a white-out (the game's own or ours) isn't over until the game is on the field again
        lend_finish(core);
    }
}

int pk_coop_link_result(struct mCore* core, uint8_t* out, int cap) {
    (void) core;
    if (sEnd.stage != 2 || !out || cap < PK_COOP_LINK_RESULT_SIZE) {
        return 0;
    }
    memcpy(out, sEnd.result, PK_COOP_LINK_RESULT_SIZE);
    return PK_COOP_LINK_RESULT_SIZE;
}

void pk_coop_link_result_clear(struct mCore* core) {
    (void) core;
    memset(&sEnd, 0, sizeof(sEnd));
}
