/* pk_call.h over armGBA: the game's own functions called between frames (FLY, PC, REPEL, BIKE, field input), the same
 * contract as app/src/main/cpp/pk_call.c's, which is written against mGBA's ARMCore and cannot be shared. GPL-3.0, see
 * ../NOTICE.
 *
 * The CPU is armGBA's exact one (src/cpu/step.c): between frames its register file is in struct CPU, laid out as mGBA's
 * (r15 holds the next instruction's address plus one instruction width), so the logic is pk_call.c's. A frame can end
 * anywhere, so a call runs instruction by instruction with IRQs masked until the function returns to a sentinel in the
 * ROM header, then puts the registers, pipeline and halt back exactly as they were. What it does NOT put back is the
 * clock (cpu.cycles, nextEvent) and the events: time moved on while the function ran, as it does under mGBA, and the
 * timers, video and DMA were served by the bus ticks of its instructions. The compiled code is told the CPU changed
 * behind its back the way a loaded state tells it (jitStateLoaded). */
#include "core.h" /* armGBA's libretro glue: extern struct GBA *gba */
#include "gba.h"
#include "cpu/jit.h"
#include "pk_call.h"

#include <stdint.h>
#include <string.h>

enum { BANK_SYSTEM_USER = 0 }; /* cpu/arm.c's BANK_NONE: the bank User and System mode share */

#define PK_REST_SLOTS 8
static uint32_t pkRestSp[PK_REST_SLOTS];
static uint32_t pkRestCount[PK_REST_SLOTS];

/* The System-mode stack pointer, whatever mode the CPU is in. */
static uint32_t pk_sys_sp(const struct CPU *cpu) {
  return cpu->mode == MODE_SYSTEM || cpu->mode == MODE_USER ? cpu->r[13] : cpu->bank[BANK_SYSTEM_USER][0];
}

void pk_rest_reset(void) {
  memset(pkRestSp, 0, sizeof pkRestSp);
  memset(pkRestCount, 0, sizeof pkRestCount);
}

void pk_rest_note(struct mCore *core) {
  (void)core;
  if (!gba || gba->cpu.mode == MODE_IRQ)
    return;
  uint32_t sp = pk_sys_sp(&gba->cpu);
  int low = 0;
  for (int i = 0; i < PK_REST_SLOTS; i++) {
    if (pkRestSp[i] == sp) {
      if (pkRestCount[i] < 100000)
        pkRestCount[i]++;
      return;
    }
    if (pkRestCount[i] < pkRestCount[low])
      low = i;
  }
  pkRestSp[low] = sp;
  pkRestCount[low] = 1;
}

/* The main loop's waiting depth, or 0 until enough frames have ended (about a second's worth). */
static uint32_t pk_rest_sp(void) {
  int best = 0;
  for (int i = 1; i < PK_REST_SLOTS; i++)
    if (pkRestCount[i] > pkRestCount[best])
      best = i;
  return pkRestCount[best] >= 30 ? pkRestSp[best] : 0;
}

#define PK_CALL_SENTINEL 0x080000C0u /* an address in the ROM header, never executed */
#define PK_CALL_CAP 2000000

static long pkCallCap = PK_CALL_CAP;
static long lastSteps; /* the steps the last call took (the test build reports it) */
long pk_call_last_steps(void) { return lastSteps; }
void pk_call_set_cap(long steps) { pkCallCap = steps > 0 ? steps : PK_CALL_CAP; }

int pk_call_safe(struct mCore *core) {
  (void)core;
  if (!gba)
    return 0;
  const struct CPU *cpu = &gba->cpu;
  if ((cpu->cpsr & PSR_T) && memoryLoad16(gba, cpu->r[15] - 4, NULL) == 0xE7FE)
    return 1; /* b . - parked */
  if (cpu->mode == MODE_IRQ)
    return 0;
  uint32_t rest = pk_rest_sp();
  return !rest || pk_sys_sp(cpu) == rest;
}

/* What a call puts back: the register file and pipeline, not the clock. */
struct Saved {
  uint32_t r[16], cpsr, spsr, bank[6][7], bankSpsr[6], prefetch[2], mode, shifterCarry;
  int32_t halted;
  bool irqMasked, fetchSeq, prefetching;
};

static void save(const struct CPU *c, struct Saved *s) {
  memcpy(s->r, c->r, sizeof s->r);
  s->cpsr = c->cpsr;
  s->spsr = c->spsr;
  memcpy(s->bank, c->bank, sizeof s->bank);
  memcpy(s->bankSpsr, c->bankSpsr, sizeof s->bankSpsr);
  memcpy(s->prefetch, c->prefetch, sizeof s->prefetch);
  s->mode = c->mode;
  s->shifterCarry = c->shifterCarry;
  s->halted = c->halted;
  s->irqMasked = c->irqMasked;
  s->fetchSeq = c->fetchSeq;
  s->prefetching = c->prefetching;
}

static void restore(struct CPU *c, const struct Saved *s) {
  memcpy(c->r, s->r, sizeof s->r);
  c->cpsr = s->cpsr;
  c->spsr = s->spsr;
  memcpy(c->bank, s->bank, sizeof s->bank);
  memcpy(c->bankSpsr, s->bankSpsr, sizeof s->bankSpsr);
  memcpy(c->prefetch, s->prefetch, sizeof s->prefetch);
  c->mode = s->mode;
  c->shifterCarry = s->shifterCarry;
  c->halted = s->halted;
  c->irqMasked = s->irqMasked;
  c->fetchSeq = s->fetchSeq;
  c->prefetching = s->prefetching;
}

int pk_call(struct mCore *core, uint32_t fn, const uint32_t args[4], uint32_t *r0) {
  if (!gba || !pk_call_safe(core))
    return 0;
  struct CPU *cpu = &gba->cpu;
  struct Saved saved;
  save(cpu, &saved);
  cpu->halted = 0;
  cpu->cpsr |= PSR_I;
  /* On the main stack, in System mode: inside an interrupt the IRQ stack is small with the main one right under it, while
   * below the main stack's SP nothing is in use. */
  if (cpu->mode != MODE_SYSTEM && cpu->mode != MODE_USER) {
    cpuSetPrivilegeMode(gba, MODE_SYSTEM);
    cpu->cpsr = (cpu->cpsr & ~0x1Fu) | MODE_SYSTEM;
  }
  for (int i = 0; i < 4; i++)
    cpu->r[i] = args[i];
  cpu->r[14] = PK_CALL_SENTINEL | 1;
  cpu->r[15] = fn & ~1u;
  cpu->cpsr |= PSR_T;
  stepReload(gba);
  int ok = 0;
  long i;
  for (i = 0; i < pkCallCap; i++) {
    if ((cpu->cpsr & PSR_T) && cpu->r[15] - 2 == PK_CALL_SENTINEL) {
      ok = 1;
      break;
    }
    stepInstruction(gba);
  }
  lastSteps = i;
  if (r0)
    *r0 = cpu->r[0];
  restore(cpu, &saved);
  memorySetActiveRegion(gba, cpu->r[15]); /* the region the next fetch comes from, as the registers left it */
  jitStateLoaded(gba, false);
  return ok;
}
