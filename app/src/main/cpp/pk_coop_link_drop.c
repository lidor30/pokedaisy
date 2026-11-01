// PokeDaisy — a dropped link (plan 6 Task 7; pk_coop.h): ends THIS game's side of a joined (link) battle at once, from any frame.
//
// The game never gives up waiting for the other player's blocks (no timeout anywhere in the battle's path, link.c's errors are only
// raised from serial status the bridge never writes), so a short drop is just a stall and the battle goes on when the blocks
// arrive. After a long one the app calls pk_coop_link_leave: the battle is pointed at the game's own normal ending (the one a
// finished battle takes: a palette fade, then FreeResetData_ReturnToOvOrDoEvolutions -> ReturnFromBattleToOverworld ->
// gMain.savedCallback), everything the link needed is let go, and the bridge is reset (patches out, the set-aside party back).
//
// Everything is written straight into the game's RAM between frames: no game function is called, so it works on any frame, also
// where pk_call has no safe point. What the leave takes care of, in the game's terms:
//  - controllers: all four battlers get BattleControllerDummy, gBattleControllerExecFlags is cleared (no half-sent command is
//    waited for);
//  - gBattleMainFunc = HandleEndTurn_FinishBattle with gCurrentActionFuncId = B_ACTION_FINISHED, callback1 / callback2 = the
//    battle's own (a leave from a party / bag / summary screen or an evolution scene comes back to the battle loop first);
//  - the tasks and the text printers are reset by hand (what ResetTasks / DeactivateAllTextPrinters do): a menu's task would
//    otherwise run on and set the callback2 back to its own exit;
//  - the link flags of gBattleTypeFlags (the game frees the battle's graphics only for a non-link battle) and
//    gReceivedRemoteLinkPlayers go, the outcome is TELEPORTED (the player is not defeated, nothing is won: no prize, no EXP, no
//    trainer flags) or LOST for a blackout, and gMain.savedCallback says where to go: CB2_WhiteOut for a blackout; else the
//    caller's own, except a trainer's end (CB2_EndTrainerBattle sets the trainer flags for any outcome that is not a defeat; the
//    script continue would run the post-battle script of a trainer nobody beat): both become CB2_EndWildBattle, the plain
//    field with the controls released (no flag, no match-call entry, no badge);
//  - pk_coop_link_reset puts the party back (Task 5's merge keeps what the battle earned: EXP, level, HP, moves, catches).
// Evolutions the battle would have run after it are dropped (the game checks gLeveledUpInBattle only for a won battle).

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include <mgba/core/core.h>

#include "pk_coop.h"
#include "pk_coop_emerald.h"

static uint32_t drop_read(struct mCore* core, uint32_t addr, int bytes) {
    uint32_t v = 0;
    for (int b = 0; b < bytes; b++) v |= (uint32_t) core->busRead8(core, addr + (uint32_t) b) << (8 * b);
    return v;
}

static void drop_write(struct mCore* core, uint32_t addr, uint32_t v, int bytes) {
    for (int b = 0; b < bytes; b++) core->busWrite8(core, addr + (uint32_t) b, (uint8_t) (v >> (8 * b)));
}

// ResetTasks, by hand: every task inactive and unlinked, the sentinels in place.
static void drop_reset_tasks(struct mCore* core) {
    for (uint32_t i = 0; i < COOP_EM_NUM_TASKS; i++) {
        uint32_t t = COOP_EM_gTasks + i * COOP_EM_OFF_TASK_SIZE;
        for (uint32_t b = 0; b < COOP_EM_OFF_TASK_SIZE; b++) core->busWrite8(core, t + b, 0);
        drop_write(core, t + COOP_EM_OFF_TASK_FUNC, COOP_EM_TaskDummy | 1u, 4);
        core->busWrite8(core, t + COOP_EM_OFF_TASK_IS_ACTIVE, 0);
        core->busWrite8(core, t + COOP_EM_OFF_TASK_IS_ACTIVE + 1, (uint8_t) i);            // prev
        core->busWrite8(core, t + COOP_EM_OFF_TASK_IS_ACTIVE + 2, (uint8_t) (i + 1));      // next
        core->busWrite8(core, t + COOP_EM_OFF_TASK_IS_ACTIVE + 3, 0xFF);                   // priority
    }
    core->busWrite8(core, COOP_EM_gTasks + COOP_EM_OFF_TASK_IS_ACTIVE + 1, COOP_EM_HEAD_SENTINEL);
    core->busWrite8(core, COOP_EM_gTasks + (COOP_EM_NUM_TASKS - 1) * COOP_EM_OFF_TASK_SIZE + COOP_EM_OFF_TASK_IS_ACTIVE + 2,
                    COOP_EM_TAIL_SENTINEL);
}

// --- the heap -------------------------------------------------------------------------------------------------------------------
// malloc.c's heap: blocks (16-byte header: flag u16, magic u16, size u32, prev, next) in a ring from sHeapStart. A battle's screens
// (the party menu's sPartyMenuInternal + box array, the bag, the summary screen, an evolution scene, the animation buffers) hold
// blocks that only their own exit frees, so a leave from one would leak them. The field's blocks are noted at begin (arm); once a
// leave has brought the game back to its own end callback - the battle's own frees have run by then - every block in use that
// isn't one of those is freed (the game's FreeInternal, in C).
#define HEAP_HEADER 16u
#define HEAP_MAX_BLOCKS 512
#define DROP_BASE_MAX 160

static struct {
    struct mCore* core;
    int armed, pending, count, leaks;
    uint32_t base[DROP_BASE_MAX];
} sDrop[2];

static int drop_slot(struct mCore* core, int create) {
    for (int i = 0; i < 2; i++) {
        if (sDrop[i].core == core) return i;
    }
    if (create) {
        for (int i = 0; i < 2; i++) {
            if (!sDrop[i].core) {
                sDrop[i].core = core;
                return i;
            }
        }
    }
    return -1;
}

// The blocks in use, by address; -1 = the ring isn't a heap (nothing is touched then).
static int drop_heap_used(struct mCore* core, uint32_t* out, int cap) {
    uint32_t start = drop_read(core, COOP_EM_sHeapStart, 4);
    if (start < 0x02000000u || start >= 0x02040000u) return -1;
    uint32_t pos = start;
    int n = 0;
    for (int i = 0; i < HEAP_MAX_BLOCKS; i++) {
        if (drop_read(core, pos + 2, 2) != 0xA3A3u) return -1;
        if (drop_read(core, pos, 2)) {
            if (n < cap) out[n] = pos;
            n++;
        }
        pos = drop_read(core, pos + 12, 4);
        if (pos == start) return n;
        if (pos < 0x02000000u || pos >= 0x02040000u) return -1;
    }
    return -1;
}

// malloc.c's FreeInternal on the block at `block`.
static void drop_heap_free(struct mCore* core, uint32_t block) {
    uint32_t head = drop_read(core, COOP_EM_sHeapStart, 4);
    drop_write(core, block, 0, 2);
    uint32_t next = drop_read(core, block + 12, 4);
    if (next != head && !drop_read(core, next, 2)) {
        drop_write(core, block + 4, drop_read(core, block + 4, 4) + HEAP_HEADER + drop_read(core, next + 4, 4), 4);
        drop_write(core, next + 2, 0, 2);
        next = drop_read(core, next + 12, 4);
        drop_write(core, block + 12, next, 4);
        if (next != head) drop_write(core, next + 8, block, 4);
    }
    if (block != head) {
        uint32_t prev = drop_read(core, block + 8, 4);
        if (!drop_read(core, prev, 2)) {
            drop_write(core, prev + 12, next, 4);
            if (next != head) drop_write(core, next + 8, prev, 4);
            drop_write(core, block + 2, 0, 2);
            drop_write(core, prev + 4, drop_read(core, prev + 4, 4) + HEAP_HEADER + drop_read(core, block + 4, 4), 4);
        }
    }
}

void pk_coop_link_drop_arm(struct mCore* core) {
    int s = drop_slot(core, 1);
    if (s < 0) return;
    int n = drop_heap_used(core, sDrop[s].base, DROP_BASE_MAX);
    sDrop[s].count = n > DROP_BASE_MAX ? DROP_BASE_MAX : n;
    sDrop[s].armed = n >= 0 && n <= DROP_BASE_MAX;   // a field with more blocks than the list holds is left alone
    sDrop[s].pending = 0;
}

void pk_coop_link_drop_tick(struct mCore* core) {
    int s = drop_slot(core, 0);
    if (s < 0 || !sDrop[s].pending) {
        return;
    }
    // The battle loop (BattleMainCB2) is callback2 until ReturnFromBattleToOverworld hands over to savedCallback.
    uint32_t cb2 = drop_read(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2, 4);
    if (cb2 == (COOP_EM_BattleMainCB2 | 1u)) {
        return;
    }
    sDrop[s].pending = 0;
    drop_write(core, COOP_EM_gBattleTypeFlags, 0, 4);   // nothing reads the tower multi's bits after this
    // Free only inside the window where the field cannot have allocated yet: callback2 is still one of the ends a leave sets (or the
    // first hop after CB2_EndWildBattle, CB2_ReturnToField) with gMain.state 0. Anything else means the app pumped late (the field
    // has run, its blocks are live): leave the heap alone and count the leak.
    int inWindow = (cb2 == (COOP_EM_CB2_EndWildBattle | 1u) || cb2 == (COOP_EM_CB2_EndScriptedWildBattle | 1u)
                    || cb2 == (COOP_EM_CB2_ReturnToField | 1u) || cb2 == (COOP_EM_CB2_ReturnToFieldLocal | 1u)
                    || cb2 == (COOP_EM_CB2_WhiteOut | 1u))
                   && core->busRead8(core, COOP_EM_gMain + 0x438) == 0;
    if (!inWindow) {
        sDrop[s].leaks++;
        return;
    }
    if (!sDrop[s].armed) {
        return;
    }
    uint32_t freed[DROP_BASE_MAX];
    int nfreed = 0;
    for (int round = 0; round < DROP_BASE_MAX; round++) {
        uint32_t used[HEAP_MAX_BLOCKS];
        int n = drop_heap_used(core, used, HEAP_MAX_BLOCKS);
        if (n < 0 || n > HEAP_MAX_BLOCKS) break;
        uint32_t extra = 0;
        for (int i = 0; i < n && !extra; i++) {
            int known = 0;
            for (int b2 = 0; b2 < sDrop[s].count; b2++) known |= sDrop[s].base[b2] == used[i];
            if (!known) extra = used[i];
        }
        if (!extra) break;
        freed[nfreed++] = extra;
        drop_heap_free(core, extra);
    }
    // The link battle's own buffers are freed above (its end, CB2_EndLinkBattle, never ran): no dangling pointers to them.
    static const uint32_t ptrs[2] = { COOP_EM_gLinkBattleSendBuffer, COOP_EM_gLinkBattleRecvBuffer };
    for (int k = 0; k < 2; k++) {
        uint32_t ptr = drop_read(core, ptrs[k], 4);
        for (int i = 0; i < nfreed; i++) {
            if (ptr == freed[i] + HEAP_HEADER) drop_write(core, ptrs[k], 0, 4);
        }
    }
}

int pk_coop_link_drop_leaks(struct mCore* core) {
    int s = drop_slot(core, 0);
    return s < 0 ? 0 : sDrop[s].leaks;
}

void pk_coop_link_leave(struct mCore* core, int blackout) {
    if (!core) {
        return;
    }
    int state = pk_coop_link_state(core);
    if (state != PK_COOP_LINK_SETUP && state != PK_COOP_LINK_BATTLE && state != PK_COOP_LINK_ERROR) {
        // Nothing to do when never begun, already left, or already ENDED: the game has left the link battle by itself and runs
        // its own end (the post-battle callback, an evolution scene it started) - cutting that short would drop what it is doing.
        return;
    }
    // The link flags go (the game's cleanup frees the battle's graphics only for a battle that isn't a link one).
    uint32_t flags = drop_read(core, COOP_EM_gBattleTypeFlags, 4);
    flags &= ~(COOP_EM_BATTLE_TYPE_LINK | COOP_EM_BATTLE_TYPE_LINK_IN_BATTLE | COOP_EM_BATTLE_TYPE_RECORDED_LINK
               | COOP_EM_BATTLE_TYPE_IS_MASTER);
    drop_write(core, COOP_EM_gBattleTypeFlags, flags, 4);
    core->busWrite8(core, COOP_EM_gReceivedRemoteLinkPlayers, 0);

    // No controller waits for anything any more.
    drop_write(core, COOP_EM_gBattleControllerExecFlags, 0, 4);
    for (uint32_t i = 0; i < 4; i++) {
        drop_write(core, COOP_EM_gBattlerControllerFuncs + 4 * i, COOP_EM_BattleControllerDummy | 1u, 4);
    }

    // Menus, text and animations of the battle are let go.
    drop_reset_tasks(core);
    for (uint32_t i = 0; i < COOP_EM_WINDOWS_MAX; i++) {
        core->busWrite8(core, COOP_EM_sTextPrinters + i * COOP_EM_TEXT_PRINTER_SIZE + COOP_EM_OFF_TEXT_PRINTER_ACTIVE, 0);
    }

    // The battle's own ending: fade out, free, return to savedCallback.
    drop_write(core, COOP_EM_gBattleMainFunc, COOP_EM_HandleEndTurn_FinishBattle | 1u, 4);
    core->busWrite8(core, COOP_EM_gCurrentActionFuncId, COOP_EM_B_ACTION_FINISHED);
    core->busWrite8(core, COOP_EM_gBattleOutcome, blackout ? COOP_EM_B_OUTCOME_LOST : COOP_EM_B_OUTCOME_PLAYER_TELEPORTED);
    drop_write(core, COOP_EM_gMain, COOP_EM_BattleMainCB1 | 1u, 4);                                     // callback1
    drop_write(core, COOP_EM_gMain + COOP_EM_OFF_MAIN_CALLBACK2, COOP_EM_BattleMainCB2 | 1u, 4);
    uint32_t saved = COOP_EM_gMain + COOP_EM_OFF_MAIN_SAVED_CALLBACK;
    if (blackout) {
        drop_write(core, saved, COOP_EM_CB2_WhiteOut | 1u, 4);
    } else {
        // savedCallback is the caller's own end (the game's wild / trainer end, a script's continue, the plain field) - unless a
        // screen of the battle's start took the slot for itself (the party showcase leaves CB2_PreInitMultiBattle there): then
        // the plain field, never a battle's start again.
        // A trainer's script (story or everyday) is never continued after a leave: its post-battle part can hand out a badge, a
        // gift or a flag for a trainer who was not beaten. CB2_EndTrainerBattle (flags + the continue) and the continue itself
        // become CB2_EndWildBattle (outcome TELEPORTED: the field again, controls released, the stopped script just ends, the
        // trainer can be fought again).
        uint32_t s = drop_read(core, saved, 4);
        if (s == (COOP_EM_CB2_EndTrainerBattle | 1u) || s == (COOP_EM_CB2_ReturnToFieldContinueScriptPlayMapMusic | 1u)) {
            s = COOP_EM_CB2_EndWildBattle | 1u;
        } else if (s != (COOP_EM_CB2_ReturnToField | 1u) && s != (COOP_EM_CB2_EndWildBattle | 1u)
                   && s != (COOP_EM_CB2_EndScriptedWildBattle | 1u)) {
            s = COOP_EM_CB2_ReturnToField | 1u;
        }
        drop_write(core, saved, s, 4);
    }

    // The bridge: patches out, the party back (a merge that keeps the battle's gains since Task 5). Then, once the game is
    // back at its own end callback, pk_coop_link_drop_tick frees what the leaving screen still held and zeroes gBattleTypeFlags.
    int slot = drop_slot(core, 0);
    if (slot >= 0) sDrop[slot].pending = 1;
    pk_coop_link_reset(core);
}
