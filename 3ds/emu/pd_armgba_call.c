// pk_call.h for armGBA: the game's own Thumb functions called between frames, the way the mGBA hosts' pk_call.c does it
// (followed line for line where the two cores agree): the CPU run from the function to its return on armGBA's exact
// interpreter, IRQs masked, then put back as it was. From ratherDashing's pokedaisy-3ds fork (3ds/emu/pd_emu_call.c).
#include "pd_armgba_internal.h"

#include <string.h>

#include "cpu/jit.h"
#include "gba.h"

extern struct GBA* gba;

// Where the game's main loop waits for VBlank, as a stack depth (see pk_call.c).
#define PK_REST_SLOTS 8
static uint32_t pkRestSp[PK_REST_SLOTS];
static uint32_t pkRestCount[PK_REST_SLOTS];

// armGBA keeps User / System's r13 in bank 0 while another mode is in.
static uint32_t pk_sys_sp(const struct CPU* cpu) {
    return cpu->mode == MODE_SYSTEM || cpu->mode == MODE_USER ? cpu->r[13] : cpu->bank[0][0];
}

void pd_armgba_rest_reset(void) {
    memset(pkRestSp, 0, sizeof(pkRestSp));
    memset(pkRestCount, 0, sizeof(pkRestCount));
}

void pd_armgba_rest_note(void) {
    if (!gba) return;
    const struct CPU* cpu = &gba->cpu;
    if (cpu->mode == MODE_IRQ) return;
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

static uint32_t pk_rest_sp(void) {
    int best = 0;
    for (int i = 1; i < PK_REST_SLOTS; i++) if (pkRestCount[i] > pkRestCount[best]) best = i;
    return pkRestCount[best] >= 30 ? pkRestSp[best] : 0;
}

#define PK_CALL_SENTINEL 0x080000C0u

int pd_armgba_call_safe(void) {
    if (!gba) return 0;
    const struct CPU* cpu = &gba->cpu;
    // Between instructions r15 is the next one's address plus one instruction, as in mGBA.
    if ((cpu->cpsr & PSR_T) && (ag_read8(cpu->r[15] - 4) | ag_read8(cpu->r[15] - 3) << 8) == 0xE7FE) return 1; // b . - parked
    if (cpu->mode == MODE_IRQ) return 0;
    uint32_t rest = pk_rest_sp();
    return !rest || pk_sys_sp(cpu) == rest;
}

#define PK_CALL_CAP 2000000
static long pkCallCap = PK_CALL_CAP;

void pd_armgba_call_set_cap(long steps) {
    pkCallCap = steps > 0 ? steps : PK_CALL_CAP;
}

void pd_armgba_step(struct GBA* g) {
    struct CPU* cpu = &g->cpu;
    if (cpu->halted) {
        if (cpu->cycles < cpu->nextEvent) cpu->cycles = cpu->nextEvent;
        processEvents(g);
        return;
    }
    stepInstruction(g);
}

int pd_armgba_call(uint32_t fn, const uint32_t args[4], uint32_t* r0) {
    if (!gba || !pd_armgba_call_safe()) return 0;
    struct GBA* g = gba;
    struct CPU* cpu = &g->cpu;
    // Everything the CPU and its fetches hold. The clock isn't put back: the call takes the game's time, as on mGBA.
    struct CPU saved = *cpu;
    int32_t activeRegion = g->activeRegion, fetchRegion = g->fetchRegion;
    uint32_t lastPrefetchedPc = g->lastPrefetchedPc, biosPrefetch = g->biosPrefetch, biosPrefetchNext = g->biosPrefetchNext;
    uint32_t romLatch = g->romLatch, romNext = g->romNext;
    struct Prefetcher prefetcher = g->prefetcher;
    bool prefetchDisabled = g->prefetchDisabled;

    cpu->halted = 0;
    // On the main stack, in System mode (see pk_call.c: the IRQ stack is 160 bytes), IRQs masked.
    if (cpu->mode != MODE_SYSTEM && cpu->mode != MODE_USER) {
        cpuSetPrivilegeMode(g, MODE_SYSTEM);
        cpu->cpsr = (cpu->cpsr & ~0x1Fu) | MODE_SYSTEM;
    }
    cpu->cpsr |= PSR_I | PSR_T;
    cpu->irqMasked = true;
    for (int i = 0; i < 4; i++) cpu->r[i] = args[i];
    cpu->r[14] = PK_CALL_SENTINEL | 1;
    cpu->r[15] = fn & ~1u;
    stepReload(g);
    int ok = 0;
    for (long i = 0; i < pkCallCap; i++) {
        if ((cpu->cpsr & PSR_T) && cpu->r[15] - 2 == PK_CALL_SENTINEL) {
            ok = 1;
            break;
        }
        pd_armgba_step(g);
    }
    if (r0) *r0 = cpu->r[0];

    int32_t cycles = cpu->cycles, nextEvent = cpu->nextEvent;
    *cpu = saved; // every mode's banked registers too
    cpu->cycles = cycles;
    cpu->nextEvent = nextEvent;
    g->activeRegion = activeRegion;
    g->fetchRegion = fetchRegion;
    g->lastPrefetchedPc = lastPrefetchedPc;
    g->biosPrefetch = biosPrefetch;
    g->biosPrefetchNext = biosPrefetchNext;
    g->romLatch = romLatch;
    g->romNext = romNext;
    g->prefetcher = prefetcher;
    g->prefetchDisabled = prefetchDisabled;
    // The recompiler's run loop starts afresh from the CPU as it now is, as after a loaded state (state.c).
    g->siteValid = false;
    g->jitDeferred = false;
    g->jitLeave = false;
    g->idleMark = 0;
    g->earlyExit = false;
    g->returnRequested = false;
    jitStateLoaded(g, false);
    return ok;
}

// --- what the native m4a mixer (host/pd_mixer.c) needs of the CPU: the BKPT it hooks, the registers, a jump ---

extern bool (*gbaBkptHook)(struct GBA* gba, uint32_t address, unsigned immediate); // armgba-pokedaisy.patch
static bool (*bkptHandler)(uint32_t address, unsigned immediate);

static bool bkpt(struct GBA* g, uint32_t address, unsigned immediate) {
    (void) g;
    return bkptHandler && bkptHandler(address, immediate);
}

void pd_armgba_set_bkpt(bool (*handler)(uint32_t address, unsigned immediate)) {
    bkptHandler = handler;
    gbaBkptHook = handler ? bkpt : NULL;
}

uint32_t pd_armgba_reg(int n) {
    return gba ? gba->cpu.r[n] : 0;
}

void pd_armgba_set_reg(int n, uint32_t value) {
    if (gba && n >= 0 && n < 15) gba->cpu.r[n] = value;
}

void pd_armgba_jump(uint32_t to) {
    if (!gba) return;
    struct CPU* cpu = &gba->cpu;
    cpu->cpsr = to & 1 ? cpu->cpsr | PSR_T : cpu->cpsr & ~PSR_T;
    cpu->r[15] = to & ~1u;
    stepReload(gba);
}

int pd_armgba_vcount(void) {
    if (!gba) return 0;
    videoSync(gba); // the line is worked out when something asks
    return gba->video.vcount;
}
