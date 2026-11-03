// The game's own functions, called between frames: pk_call.h. GPL-3.0 (see NOTICE).
#include "pk_call.h"

#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/arm/isa-inlines.h>
#include <mgba/internal/gba/gba.h>

// Where the game's main loop waits for VBlank, as a stack depth: the System-mode stack pointer most frames end with
// (the game in its wait - a spin on gMain.intrCheck in FireRed, VBlankIntrWait in Emerald - with nothing of its own
// in progress). pk_call_safe calls the game's functions only at that depth. Counted per loaded game.
#define PK_REST_SLOTS 8
static uint32_t pkRestSp[PK_REST_SLOTS];
static uint32_t pkRestCount[PK_REST_SLOTS];

static uint32_t pk_sys_sp(struct ARMCore* cpu) {
    return cpu->privilegeMode == MODE_SYSTEM || cpu->privilegeMode == MODE_USER
        ? (uint32_t) cpu->gprs[ARM_SP] : (uint32_t) cpu->bankedRegisters[BANK_NONE][0];
}

void pk_rest_reset(void) {
    memset(pkRestSp, 0, sizeof(pkRestSp));
    memset(pkRestCount, 0, sizeof(pkRestCount));
}

void pk_rest_note(struct mCore* core) {
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    if (cpu->privilegeMode == MODE_IRQ) {
        return;
    }
    uint32_t sp = pk_sys_sp(cpu);
    int low = 0;
    for (int i = 0; i < PK_REST_SLOTS; i++) {
        if (pkRestSp[i] == sp) {
            if (pkRestCount[i] < 100000) pkRestCount[i]++;
            return;
        }
        if (pkRestCount[i] < pkRestCount[low]) low = i;
    }
    pkRestSp[low] = sp;
    pkRestCount[low] = 1;
}

/** The main loop's waiting depth, or 0 until enough frames have ended (about a second's worth). */
static uint32_t pk_rest_sp(void) {
    int best = 0;
    for (int i = 1; i < PK_REST_SLOTS; i++) if (pkRestCount[i] > pkRestCount[best]) best = i;
    return pkRestCount[best] >= 30 ? pkRestSp[best] : 0;
}

// Calls Thumb function `fn` with r0-r3 = `args` and returns once it has, leaving
// the CPU exactly as it was (registers, pipeline, halt). A frame can end
// anywhere - mid-way through the game's own code - and a bare PC hijack
// clobbered its live registers (Unbound then reset its sound engine). So:
// run instruction by instruction with IRQs masked until the call returns to
// PK_CALL_SENTINEL (an address in the ROM header, never executed), then put
// everything back. Tested headless with native-capture/mgba_dump's `call`.
#define PK_CALL_SENTINEL 0x080000C0u
// Whether the game's own code can be called right now (pk_call): parked on a `b .` (the render core), or the game
// waiting for VBlank with nothing of its own in progress - not in an interrupt, its System stack back at the main
// loop's depth (pk_rest_sp). A frame often ends part-way through the game's code instead (its VBlank handler running,
// a lag frame's logic), and a call there lands in the middle of the game's own read-modify-writes: FadeScreen while
// VBlank's handler was part-way through updating the palette fade, the handler then wrote its stale copy back, and the
// FLY warp that followed never finished loading (2026-10-20: a black screen, then a reset, on the Thor; reproduced
// with mgba_dump's `stepinto 3` + FLY's calls). The caller waits for a frame that ends well. Before the depth is
// known (the game's first second) a call goes ahead, as it always did.
int pk_call_safe(struct mCore* core) {
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    uint32_t pc = (uint32_t) cpu->gprs[ARM_PC];
    if (cpu->executionMode == MODE_THUMB && core->rawRead16(core, pc - WORD_SIZE_THUMB * 2, -1) == 0xE7FE) {
        return 1;   // b . - parked
    }
    if (cpu->privilegeMode == MODE_IRQ) {
        return 0;
    }
    uint32_t rest = pk_rest_sp();
    return !rest || pk_sys_sp(cpu) == rest;
}

#define PK_CALL_CAP 2000000
static __thread long pkCallCap = PK_CALL_CAP; // per thread: the render core calls on another one

void pk_call_set_cap(long steps) {
    pkCallCap = steps > 0 ? steps : PK_CALL_CAP;
}

int pk_call(struct mCore* core, uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    if (core->platform(core) != mPLATFORM_GBA) {
        return 0;   // an ARM-only trick: the GB core's CPU is an SM83
    }
    if (!pk_call_safe(core)) {
        return 0;
    }
    struct ARMCore* cpu = (struct ARMCore*) core->cpu;
    struct ARMRegisterFile saved = cpu->regs;
    uint32_t prefetch0 = cpu->prefetch[0], prefetch1 = cpu->prefetch[1];
    // The memory region the CPU fetches from, and the idle-loop detector that region switches feed: the call
    // moves both to the ROM. A frame often ends just inside the interrupt handler (IWRAM, or the BIOS), and
    // with only the registers put back the CPU went on fetching that address from the ROM - garbage: the game
    // froze or reset (a TAKE on the Thor, reproduced with mgba_dump's `stepinto 3` + `call`).
    struct ARMMemory mem = cpu->memory;
    struct GBA* gba = (struct GBA*) core->board;
    int region = gba->memory.activeRegion;
    uint32_t biosPrefetch = gba->memory.biosPrefetch;
    uint32_t idleLoop = gba->idleLoop, lastJump = gba->lastJump;
    bool haltPending = gba->haltPending;
    int idleStep = gba->idleDetectionStep, idleFailures = gba->idleDetectionFailures;
    int32_t cached[16];
    memcpy(cached, gba->cachedRegisters, sizeof(cached));
    enum ExecutionMode exec = cpu->executionMode;
    int halted = cpu->halted;
    enum PrivilegeMode priv = cpu->privilegeMode;
    cpu->halted = 0;
    cpu->cpsr.i = 1;
    // On the main stack, in System mode: inside an interrupt the IRQ stack is 160 bytes with the main one right
    // under it (and the BIOS's SVC stack is smaller still), while below the main stack's SP nothing is in use.
    if (priv != MODE_SYSTEM && priv != MODE_USER) {
        ARMSetPrivilegeMode(cpu, MODE_SYSTEM);
        cpu->cpsr.priv = MODE_SYSTEM;
    }
    for (int i = 0; i < 4; i++) {
        cpu->gprs[i] = (int32_t) args[i];
    }
    cpu->gprs[ARM_LR] = (int32_t) (PK_CALL_SENTINEL | 1);
    cpu->gprs[ARM_PC] = (int32_t) (fn & ~1u);
    _ARMSetMode(cpu, MODE_THUMB);
    ThumbWritePC(cpu);
    int ok = 0;
    for (long i = 0; i < pkCallCap; i++) {
        if (cpu->executionMode == MODE_THUMB && (uint32_t) cpu->gprs[ARM_PC] - WORD_SIZE_THUMB == PK_CALL_SENTINEL) {
            ok = 1;
            break;
        }
        core->step(core);
    }
    if (r0) *r0 = (uint32_t) cpu->gprs[0];
    cpu->regs = saved; // the banked registers too: the mode's own SP / LR are back as they were
    cpu->privilegeMode = priv;
    cpu->executionMode = exec == MODE_ARM ? MODE_THUMB : MODE_ARM; // so _ARMSetMode applies
    _ARMSetMode(cpu, exec);
    cpu->prefetch[0] = prefetch0;
    cpu->prefetch[1] = prefetch1;
    cpu->memory = mem;
    gba->memory.activeRegion = region;
    gba->memory.biosPrefetch = biosPrefetch;
    gba->idleLoop = idleLoop;
    gba->lastJump = lastJump;
    gba->haltPending = haltPending;
    gba->idleDetectionStep = idleStep;
    gba->idleDetectionFailures = idleFailures;
    memcpy(gba->cachedRegisters, cached, sizeof(cached));
    cpu->halted = halted;
    return ok;
}
