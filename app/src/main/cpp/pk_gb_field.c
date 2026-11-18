// PokeDaisy — the Game Boy games' field actions: PC anywhere, FLY and WILD ENCOUNTERS on Yellow and Crystal
// (GbField.kt has the games' side). Most of it is RAM the game reads itself (Crystal's queued map script, its
// encounter cooldown, Yellow's fly warp and encounter rates), written between frames with pk_gb_write. Yellow's PC has
// no such queue: it's the Pokémon Center PC's own text, which the game shows when the overworld loop calls
// PrintPredefTextID (in the text's ROM bank: through the game's Bankswitch) - pk_gb_call_at makes that call from the loop.

#include "pk_gb_field.h"

#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/sm83/sm83.h>

bool pk_gb_write(struct mCore* core, uint32_t addr, const uint8_t* src, int len) {
    if (!core || core->platform(core) != mPLATFORM_GB || !src || len <= 0) {
        return false;
    }
    if (addr >= 0x10000u) {
        struct GB* gb = core->board;
        uint32_t bank = addr >> 16, off = addr & 0xFFFFu;
        if (bank > 7 || off < 0xD000u || (uint64_t) off + (uint32_t) len > 0xE000u || !gb->memory.wram) {
            return false;
        }
        memcpy(gb->memory.wram + bank * GB_SIZE_WORKING_RAM_BANK0 + (off - 0xD000u), src, (size_t) len);
        return true;
    }
    if (addr < 0xC000u || (uint64_t) addr + (uint32_t) len > 0xE000u) {
        return false;
    }
    for (int i = 0; i < len; i++) {
        core->busWrite8(core, (uint16_t) (addr + (uint32_t) i), src[i]);
    }
    return true;
}

bool pk_gb_call_at(struct mCore* core, uint16_t at, uint16_t fn, uint8_t a, uint8_t b, uint16_t hl, int maxHalts) {
    if (!core || core->platform(core) != mPLATFORM_GB) {
        return false;
    }
    struct SM83Core* cpu = (struct SM83Core*) core->cpu;
    // Between frames the CPU sits in a frame wait (a HALT); the VBlank handler runs, the wait returns, and the loop
    // that called it goes on. Each HALT entered on the way is another frame waited somewhere else.
    bool wasHalted = cpu->halted;
    int halts = 0;
    for (int i = 0; i < 400000; i++) {
        if (cpu->pc == at && !cpu->halted) {
            cpu->sp -= 2;
            core->busWrite8(core, cpu->sp, (uint8_t) (at & 0xFF));
            core->busWrite8(core, (uint16_t) (cpu->sp + 1), (uint8_t) (at >> 8));
            cpu->a = a;
            cpu->b = b;
            cpu->h = (uint8_t) (hl >> 8);
            cpu->l = (uint8_t) hl;
            cpu->pc = fn;
            cpu->memory.setActiveRegion(cpu, cpu->pc);
            return true;
        }
        if (cpu->halted && !wasHalted && ++halts > maxHalts) {
            return false;
        }
        wasHalted = cpu->halted;
        core->step(core);
    }
    return false;
}
