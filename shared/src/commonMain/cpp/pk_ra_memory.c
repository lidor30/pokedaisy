// RetroAchievements' GB/GBC and GBA address spaces over mGBA (rcheevos consoleinfo.c defines the layout).
#include "pk_ra_memory.h"

#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gba/memory.h>

// $0000-$FFFF is the CPU bus with WRAM bank 1 fixed at $D000. $10000-$15FFF holds the Color's WRAM banks 2–7.
static uint32_t read_gb(struct mCore* core, uint32_t address, uint8_t* out, uint32_t bytes) {
    struct GB* gb = core->board;
    uint32_t done = 0;
    for (; done < bytes; done++) {
        uint32_t a = address + done;
        if (a >= 0xD000 && a < 0xE000) {
            out[done] = gb->memory.wram[0x1000 + (a - 0xD000)];
        } else if (a < 0x10000) {
            out[done] = (uint8_t) core->busRead8(core, a);
        } else if (a < 0x16000) {
            out[done] = gb->memory.wram[0x2000 + (a - 0x10000)];
        } else {
            break;
        }
    }
    return done;
}

// GBA's IWRAM, EWRAM and first 64 KiB of cartridge SRAM form consecutive ranges in RetroAchievements.
static uint32_t read_gba(struct mCore* core, uint32_t address, uint8_t* out, uint32_t bytes) {
    uint32_t done = 0;
    while (done < bytes) {
        uint32_t a = address + done;
        size_t id, base, end;
        if (a < 0x8000) {
            id = REGION_WORKING_IRAM;
            base = 0;
            end = 0x8000;
        } else if (a < 0x48000) {
            id = REGION_WORKING_RAM;
            base = 0x8000;
            end = 0x48000;
        } else if (a < 0x58000) {
            id = REGION_CART_SRAM;
            base = 0x48000;
            end = 0x58000;
        } else {
            break;
        }
        size_t size = 0;
        const uint8_t* block = core->getMemoryBlock(core, id, &size);
        size_t off = a - base;
        if (!block || off >= size) break;
        size_t remaining = end - a;
        size_t n = size - off;
        if (n > remaining) n = remaining;
        if (n > bytes - done) n = bytes - done;
        memcpy(out + done, block + off, n);
        done += (uint32_t) n;
    }
    return done;
}

uint32_t pk_ra_read_memory(struct mCore* core, uint32_t address, uint8_t* out, uint32_t bytes) {
    if (!core || !out || !bytes) return 0;
    switch (core->platform(core)) {
        case mPLATFORM_GB: return read_gb(core, address, out, bytes);
        case mPLATFORM_GBA: return read_gba(core, address, out, bytes);
        default: return 0;
    }
}
