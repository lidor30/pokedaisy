// PokeDaisy — the co-op story lock on retail English Emerald (see pk_coop_lock.h). Plain C over `struct mCore*`, no JNI:
// native-capture/mgba_dump.c compiles this very file in (`lockinstall` & co.) and scripts/coop_headless_check.sh runs
// the checks in native-capture/coop/lock_*.txt against the player's own ROM and a save.
//
// Install is all or nothing, like pk_coop.c's: the three functions it relies on are CRC-checked against the bus, the
// code area (0x08FFE000-0x08FFEFFF) must be free of other blocks, then the code + table block and the head patch (two
// 4-byte pk_cheats_patch: the function's first 8 bytes -> `ldr r3, [pc, #0]; bx r3; .word <our code>`) go in.

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <mgba/core/core.h>

#include "pk_cheats.h"
#include "pk_coop_lock.h"
#include "pk_coop_lock_emerald.h"

#define LOCK_CODE_AREA      0x08FFE000u
#define LOCK_CODE_AREA_SIZE 0x1000u
#define LOCK_HEAD           COOP_LK_ScriptContext_SetupScript   // the patch covers 8 bytes from here
#define LOCK_HEAD_SIZE      8u

static const struct { const char* name; uint32_t addr, crc; } sLockCrcs[] = {
#define LOCK_FN(f) { #f, COOP_LK_##f, COOP_LK_CRC_##f }
    LOCK_FN(ScriptContext_SetupScript),
    LOCK_FN(ArePlayerFieldControlsLocked),
    LOCK_FN(ScriptContext_IsEnabled),
#undef LOCK_FN
};

// The code was assembled for this mailbox (scripts/coop_lock_emerald.s' layout, carried in the generated header).
_Static_assert(COOP_LK_ASM_MAILBOX == PK_COOP_LOCK_MAILBOX, "coop_lock_emerald.s: mailbox address");
_Static_assert(COOP_LK_ASM_MB_ACTIVE == offsetof(struct pk_coop_lock_mailbox, active), "coop_lock_emerald.s: active");
_Static_assert(COOP_LK_ASM_MB_KIND == offsetof(struct pk_coop_lock_mailbox, heldKind), "coop_lock_emerald.s: kind");
_Static_assert(COOP_LK_ASM_MB_ID == offsetof(struct pk_coop_lock_mailbox, heldId), "coop_lock_emerald.s: id");
_Static_assert(COOP_LK_ASM_MB_ALLOW == offsetof(struct pk_coop_lock_mailbox, allowId), "coop_lock_emerald.s: allow");
_Static_assert(COOP_LK_ASM_MB_MAP_GROUP == offsetof(struct pk_coop_lock_mailbox, heldMapGroup), "coop_lock_emerald.s: map group");
_Static_assert(COOP_LK_ASM_MB_MAP_NUM == offsetof(struct pk_coop_lock_mailbox, heldMapNum), "coop_lock_emerald.s: map num");
_Static_assert(COOP_LK_ASM_MB_X == offsetof(struct pk_coop_lock_mailbox, heldX), "coop_lock_emerald.s: x");
_Static_assert(COOP_LK_ASM_MB_Y == offsetof(struct pk_coop_lock_mailbox, heldY), "coop_lock_emerald.s: y");
_Static_assert(COOP_LK_ASM_MB_FACING == offsetof(struct pk_coop_lock_mailbox, heldFacing), "coop_lock_emerald.s: facing");
_Static_assert(COOP_LK_ASM_MB_SELECTED == offsetof(struct pk_coop_lock_mailbox, selectedObjectEvent),
               "coop_lock_emerald.s: selected object");
_Static_assert(COOP_LK_ASM_MB_LAST_TALKED == offsetof(struct pk_coop_lock_mailbox, lastTalked), "coop_lock_emerald.s: last talked");
_Static_assert(COOP_LK_ASM_MB_SCRIPT >= sizeof(struct pk_coop_lock_mailbox), "the mailbox runs into the re-entry script");
_Static_assert(COOP_LK_ASM_MB_SCRIPT + 32 <= PK_COOP_LOCK_RAM_SIZE, "the re-entry script is past the lock's RAM");
_Static_assert(sizeof(COOP_LK_CODE) <= LOCK_CODE_AREA_SIZE, "the lock's block is bigger than its code area");
_Static_assert(COOP_LK_CODE_ADDR >= LOCK_CODE_AREA && COOP_LK_CODE_ADDR + sizeof(COOP_LK_CODE) <= LOCK_CODE_AREA + LOCK_CODE_AREA_SIZE,
               "the lock's block is outside its code area");
_Static_assert(COOP_LK_ENTRY_SETUP >= COOP_LK_CODE_ADDR && COOP_LK_ENTRY_SETUP < COOP_LK_TABLE_ADDR, "the entry is outside the code");

static pk_coop_call_fn sLockCall;
static int sLockInstalled;
static int sLockPatchesIn;   // 0, 1 or 2: how many of the two head patches went in
static const char* sLockRefused = "";
static char sLockRefusedBuf[32];

static uint32_t lock_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
        }
    }
    return ~c;
}

#define LOCK_MB(field) (PK_COOP_LOCK_MAILBOX + (uint32_t) offsetof(struct pk_coop_lock_mailbox, field))

static void lock_zero_ram(struct mCore* core) {
    for (uint32_t a = PK_COOP_LOCK_MAILBOX; a < PK_COOP_LOCK_MAILBOX + PK_COOP_LOCK_RAM_SIZE; a += 4) {
        core->busWrite32(core, a, 0);
    }
}

static void lock_put32(uint8_t* p, uint32_t v) {
    for (int b = 0; b < 4; b++) p[b] = (uint8_t) (v >> (8 * b));
}

// The head patch's two 4-byte halves: where the file holds `expect`, put `value`. Another option's patch on these bytes is a
// refusal (pk_cheats_patch would answer 1 without ours going in, and taking ours out by range would take theirs out).
static int lock_patch_in(struct mCore* core, int half) {
    uint8_t expect[4], value[4];
    lock_put32(expect, half ? COOP_LK_HEAD_ORIG1 : COOP_LK_HEAD_ORIG0);
    lock_put32(value, half ? (COOP_LK_ENTRY_SETUP | 1u) : COOP_LK_HEAD_NEW0);
    uint32_t addr = LOCK_HEAD + 4u * (uint32_t) half;
    return !pk_cheats_patch_overlaps(addr, 4) && pk_cheats_patch(core, addr, expect, 4, addr, value, 4);
}

// A script of ours running (the step-back in the ROM block, or go's re-entry script in the mailbox) must not lose its bytes
// under the game: if the global script context's scriptPtr or a saved return address points into either, the context is
// stopped as the game stops one (mode stopped, no pointers, status shut down) and the field controls it locked are freed.
static int lock_ours(uint32_t p) {
    return (p >= LOCK_CODE_AREA && p < LOCK_CODE_AREA + LOCK_CODE_AREA_SIZE)
        || (p >= PK_COOP_LOCK_MAILBOX + COOP_LK_ASM_MB_SCRIPT && p < PK_COOP_LOCK_MAILBOX + COOP_LK_ASM_MB_SCRIPT + 32);
}

static void lock_stop_own_script(struct mCore* core) {
    uint32_t ctx = COOP_LK_sGlobalScriptContext;
    int mode = core->busRead8(core, ctx + 1);
    int depth = core->busRead8(core, ctx);
    int mine = mode != 0 && lock_ours(core->busRead32(core, ctx + 8));
    for (int i = 0; mode != 0 && i < depth && i < 20 && !mine; i++) {
        mine = lock_ours(core->busRead32(core, ctx + 12 + 4u * (uint32_t) i));
    }
    if (!mine) {
        return;
    }
    core->busWrite8(core, ctx, 0);
    core->busWrite8(core, ctx + 1, 0);
    core->busWrite32(core, ctx + 4, 0);
    core->busWrite32(core, ctx + 8, 0);
    core->busWrite8(core, COOP_LK_sGlobalScriptContextStatus, 2);   // CONTEXT_SHUTDOWN
    core->busWrite8(core, COOP_LK_sLockFieldControls, 0);
}

static void lock_take_out(struct mCore* core) {
    lock_stop_own_script(core);
    pk_cheats_unpatch_range(core, LOCK_HEAD, LOCK_HEAD_SIZE);
    sLockPatchesIn = 0;
    pk_cheats_unblock_range(core, LOCK_CODE_AREA, LOCK_CODE_AREA_SIZE);
}

// Whether the lock's block and head patch are still in the ROM overlay (the app's all-out ROM calls take them out).
static int lock_in_place(void) {
    return sLockPatchesIn == 2 && pk_cheats_block_overlaps(COOP_LK_CODE_ADDR, (uint32_t) sizeof(COOP_LK_CODE))
        && pk_cheats_patch_overlaps(LOCK_HEAD, LOCK_HEAD_SIZE);
}

int pk_coop_lock_install(struct mCore* core, pk_coop_call_fn call) {
    if (sLockInstalled) {
        if (core && lock_in_place()) {
            sLockCall = call;
            return 1;
        }
        if (core) {
            lock_take_out(core);
        }
        sLockInstalled = 0;
        sLockCall = NULL;
    }
    if (!core || core->platform(core) != mPLATFORM_GBA) {
        sLockRefused = "not a GBA game";
        return 0;
    }
    for (size_t i = 0; i < sizeof(sLockCrcs) / sizeof(sLockCrcs[0]); i++) {
        uint8_t code[32];
        for (int b = 0; b < 32; b++) code[b] = (uint8_t) core->busRead8(core, sLockCrcs[i].addr + (uint32_t) b);
        if (lock_crc32(code, 32) != sLockCrcs[i].crc) {
            sLockRefused = sLockCrcs[i].name;
            return 0;
        }
    }
    if (pk_cheats_block_overlaps(LOCK_CODE_AREA, LOCK_CODE_AREA_SIZE)) {
        sLockRefused = "block overlap";
        return 0;
    }
    {
        static uint8_t blank[sizeof(COOP_LK_CODE)];
        memset(blank, 0xFF, sizeof(blank));
        if (!pk_cheats_block(core, COOP_LK_CODE_ADDR, COOP_LK_CODE, (int) sizeof(COOP_LK_CODE),
                             lock_crc32(blank, (int) sizeof(blank)))) {
            sLockRefused = "code block";
            return 0;
        }
    }
    sLockPatchesIn = 0;
    for (int half = 0; half < 2; half++) {
        if (!lock_patch_in(core, half)) {
            lock_take_out(core);
            snprintf(sLockRefusedBuf, sizeof(sLockRefusedBuf), "patch %08X", (unsigned) (LOCK_HEAD + 4u * (uint32_t) half));
            sLockRefused = sLockRefusedBuf;
            return 0;
        }
        sLockPatchesIn = half + 1;
    }
    lock_zero_ram(core);
    sLockCall = call;
    sLockInstalled = 1;
    sLockRefused = "";
    return 1;
}

void pk_coop_lock_uninstall(struct mCore* core) {
    if (!sLockInstalled || !core) {
        return;
    }
    lock_take_out(core);
    lock_zero_ram(core);
    sLockInstalled = 0;
    sLockCall = NULL;
}

const char* pk_coop_lock_refused(void) {
    return sLockRefused;
}

int pk_coop_lock_installed(void) {
    return sLockInstalled;
}

void pk_coop_lock_reset(void) {
    sLockInstalled = 0;
    sLockPatchesIn = 0;
    sLockCall = NULL;
}

void pk_coop_lock_original_bytes(uint32_t addr, uint8_t* dst, int len) {
    if (!sLockInstalled || sLockPatchesIn == 0) {
        return;
    }
    uint8_t orig[8];
    lock_put32(orig, COOP_LK_HEAD_ORIG0);
    lock_put32(orig + 4, COOP_LK_HEAD_ORIG1);
    for (uint32_t b = 0; b < LOCK_HEAD_SIZE; b++) {
        uint32_t a = LOCK_HEAD + b;
        if (a >= addr && a - addr < (uint32_t) len) {
            dst[a - addr] = orig[b];
        }
    }
}

void pk_coop_lock_cancel(struct mCore* core) {
    if (!sLockInstalled || !core) {
        return;
    }
    core->busWrite16(core, LOCK_MB(heldId), 0);
    core->busWrite8(core, LOCK_MB(heldKind), 0);
}

void pk_coop_lock_set_active(struct mCore* core, int on) {
    if (!sLockInstalled || !core) {
        return;
    }
    core->busWrite8(core, LOCK_MB(active), on ? 1 : 0);
    if (!on) {
        pk_coop_lock_cancel(core);
        core->busWrite16(core, LOCK_MB(allowId), 0);
    }
}

int pk_coop_lock_held(struct mCore* core, int* kind) {
    if (kind) *kind = 0;
    if (!sLockInstalled || !core) {
        return 0;
    }
    int id = (int) core->busRead16(core, LOCK_MB(heldId));
    if (id && kind) *kind = (int) core->busRead8(core, LOCK_MB(heldKind));
    return id;
}

// The player's object event: its tile and facing (the game's own numbers).
static int lock_player(struct mCore* core, int* x, int* y, int* facing) {
    uint32_t id = core->busRead8(core, COOP_LK_gPlayerAvatar + COOP_LK_AVATAR_OBJ_ID);
    uint32_t obj = COOP_LK_gObjectEvents + id * COOP_LK_OBJ_SIZE;
    if (obj < 0x02000000u || obj >= 0x02040000u) {
        return 0;
    }
    *x = (int16_t) core->busRead16(core, obj + COOP_LK_OBJ_CURRENT_X);
    *y = (int16_t) core->busRead16(core, obj + COOP_LK_OBJ_CURRENT_Y);
    *facing = (int) (core->busRead8(core, obj + COOP_LK_OBJ_FACING) & 0xF);
    return 1;
}

void pk_coop_lock_tick(struct mCore* core) {
    if (!sLockInstalled || !core || !core->busRead16(core, LOCK_MB(heldId))) {
        return;
    }
    uint32_t sb1 = core->busRead32(core, COOP_LK_gSaveBlock1Ptr);
    if (sb1 >= 0x02000000u && sb1 < 0x02040000u
        && (core->busRead8(core, sb1 + COOP_LK_SB1_LOCATION_GROUP) != core->busRead8(core, LOCK_MB(heldMapGroup))
            || core->busRead8(core, sb1 + COOP_LK_SB1_LOCATION_NUM) != core->busRead8(core, LOCK_MB(heldMapNum)))) {
        pk_coop_lock_cancel(core);
        return;
    }
    int kind = core->busRead8(core, LOCK_MB(heldKind));
    if (kind == PK_COOP_LOCK_FRAME) {
        return;
    }
    int x, y, facing;
    if (!lock_player(core, &x, &y, &facing)) {
        return;
    }
    int hx = (int16_t) core->busRead16(core, LOCK_MB(heldX)), hy = (int16_t) core->busRead16(core, LOCK_MB(heldY));
    if (kind == PK_COOP_LOCK_COORD) {
        int dx = x > hx ? x - hx : hx - x, dy = y > hy ? y - hy : hy - y;
        if (dx > 1 || dy > 1) {
            pk_coop_lock_cancel(core);
        }
    } else if (x != hx || y != hy || facing != (int) core->busRead8(core, LOCK_MB(heldFacing))) {
        pk_coop_lock_cancel(core);
    }
}

// A free field: no script running (ScriptContext_IsEnabled 0) and the field controls not locked. 1 yes, 0 no,
// -1 the game couldn't be called now.
static int lock_field_free(struct mCore* core) {
    const uint32_t args[4] = {0, 0, 0, 0};
    uint32_t enabled = sLockCall(core, COOP_LK_ScriptContext_IsEnabled, args);
    if (enabled == 0xFFFFFFFFu) return -1;
    uint32_t locked = sLockCall(core, COOP_LK_ArePlayerFieldControlsLocked, args);
    if (locked == 0xFFFFFFFFu) return -1;
    return !(enabled & 0xFF) && !(locked & 0xFF);
}

static uint32_t lock_script_of(int id) {
    for (int i = 0; i < COOP_LK_STORY_COUNT; i++) {
        if (COOP_LK_STORY[i].id == id) return COOP_LK_STORY[i].script;
    }
    return 0;
}

// The movement byte and tile step for walking in a facing (1 south, 2 north, 3 west, 4 east); 0 = not a 4-way facing.
static int lock_walk(int facing, int* dx, int* dy) {
    switch (facing) {
        case 1: *dx = 0; *dy = 1; return COOP_LK_MOVE_walk_down;
        case 2: *dx = 0; *dy = -1; return COOP_LK_MOVE_walk_up;
        case 3: *dx = -1; *dy = 0; return COOP_LK_MOVE_walk_left;
        case 4: *dx = 1; *dy = 0; return COOP_LK_MOVE_walk_right;
        default: return 0;
    }
}

// Writes the COORD re-entry script at mailbox + 0x20: `applymovement OBJ_EVENT_ID_PLAYER, <one step the way the player
// faced>; waitmovement 0; goto <script>` (its movement bytes 16 bytes on), or just `goto <script>` when the player is
// already on the trigger tile (the step back was blocked). 0 = the hold's facing is no 4-way one.
static int lock_write_reentry(struct mCore* core, uint32_t script) {
    int dx = 0, dy = 0;
    int move = lock_walk(core->busRead8(core, LOCK_MB(heldFacing)), &dx, &dy);
    int px, py, pf;
    if (!move || !lock_player(core, &px, &py, &pf)) {
        return 0;
    }
    int hx = (int16_t) core->busRead16(core, LOCK_MB(heldX)), hy = (int16_t) core->busRead16(core, LOCK_MB(heldY));
    uint32_t at = PK_COOP_LOCK_MAILBOX + COOP_LK_ASM_MB_SCRIPT;
    uint8_t bytes[16];
    int n = 0;
    if (!(px == hx + dx && py == hy + dy)) {
        bytes[n++] = COOP_LK_OP_applymovement;
        bytes[n++] = COOP_LK_LOCALID_PLAYER;
        bytes[n++] = 0;
        lock_put32(bytes + n, at + 16);
        n += 4;
        bytes[n++] = COOP_LK_OP_waitmovement;
        bytes[n++] = 0;
        bytes[n++] = 0;
        core->busWrite8(core, at + 16, (uint8_t) move);
        core->busWrite8(core, at + 17, COOP_LK_MOVE_step_end);
    }
    bytes[n++] = COOP_LK_OP_goto;
    lock_put32(bytes + n, script);
    n += 4;
    for (int i = 0; i < n; i++) {
        core->busWrite8(core, at + (uint32_t) i, bytes[i]);
    }
    return 1;
}

int pk_coop_lock_go(struct mCore* core, int id) {
    if (!sLockInstalled || !core || !sLockCall || id <= 0) {
        return 0;
    }
    int kind;
    if (pk_coop_lock_held(core, &kind) != id) {
        return 0;
    }
    uint32_t script = lock_script_of(id);
    if (!script) {
        return 0;
    }
    if (kind == PK_COOP_LOCK_FRAME) {
        // The game's own ON_FRAME check runs the script on the next frame it is free; the hook lets it through once.
        core->busWrite16(core, LOCK_MB(allowId), (uint16_t) id);
        pk_coop_lock_cancel(core);
        return 1;
    }
    if (lock_field_free(core) != 1) {
        return -1;
    }
    uint32_t arg[4] = {script, 0, 0, 0};
    uint16_t oldTalked = 0;
    uint8_t oldSelected = 0;
    if (kind == PK_COOP_LOCK_OBJECT) {
        oldTalked = core->busRead16(core, COOP_LK_gSpecialVar_LastTalked);
        oldSelected = core->busRead8(core, COOP_LK_gSelectedObjectEvent);
        core->busWrite16(core, COOP_LK_gSpecialVar_LastTalked, core->busRead16(core, LOCK_MB(lastTalked)));
        core->busWrite8(core, COOP_LK_gSelectedObjectEvent, core->busRead8(core, LOCK_MB(selectedObjectEvent)));
        core->busWrite16(core, LOCK_MB(allowId), (uint16_t) id);
    } else {
        if (!lock_write_reentry(core, script)) {
            return 0;
        }
        arg[0] = PK_COOP_LOCK_MAILBOX + COOP_LK_ASM_MB_SCRIPT;   // not a story script's pointer: the hook lets it through
    }
    if (sLockCall(core, COOP_LK_ScriptContext_SetupScript, arg) == 0xFFFFFFFFu) {
        core->busWrite16(core, LOCK_MB(allowId), 0);
        if (kind == PK_COOP_LOCK_OBJECT) {   // -1 changes nothing
            core->busWrite16(core, COOP_LK_gSpecialVar_LastTalked, oldTalked);
            core->busWrite8(core, COOP_LK_gSelectedObjectEvent, oldSelected);
        }
        return -1;
    }
    pk_coop_lock_cancel(core);
    return 1;
}
