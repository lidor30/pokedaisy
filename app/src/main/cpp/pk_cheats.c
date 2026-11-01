// PokeDaisy — cheats (GameShark / Action Replay / CodeBreaker / VBA codes) on
// mGBA's own cheat engine. Plain C over `struct mCore*`, no JNI, so
// native-capture/mgba_dump.c compiles this very file in and tests it headless.
//
// Kotlin owns the cheat list (CheatStore: filesDir/cheats/<crc>.cheats, mGBA's
// .cheats format) and hands the core the enabled ones as one mGBA-format text;
// pk_cheats_apply swaps them in. Only enabled cheats ever reach the core, so with
// none on the ROM is byte-for-byte the file again (hooks and ROM patches undone).
//
// Cheats work with USE_DEBUGGERS off: GBA cheats run at frame end
// (GBAFrameEnded) or from a ROM hook, a BKPT that lands on the cheat device's
// CPU component (GBABreakpoint's CPU_COMPONENT_CHEAT_DEVICE case, outside the
// debugger #ifdef) - checked with mgba_dump's `cheat` command.
//
// A hook or a ROM-patch code writes into the ROM mGBA runs from, and the ROM is
// also what identifies the game: the Poller hashes it over the bus (hack SHA1s).
// So the original bytes under every patch are kept here (sFixes): pk_cheats_overlay
// puts them back into a bus read, pk_cheats_swap_rom swaps them in and out (mgba_dump;
// RetroAchievements hashes the ROM file instead).
//
// The app's own game options that change the ROM (REUSABLE TMS: ReusableTms.kt)
// are patches too, kept in a list of their own (sOptions) so loading cheats never
// drops them: pk_cheats_patch / pk_cheats_unpatch, the same overlay and swap.
// PLAYER LOOK (PlayerLook.kt) writes whole blocks instead - another game's sprites
// over the player's, new pictures in the ROM's empty tail, the pointers to them:
// sBlocks, each keeping the file's bytes it covers (pk_cheats_block / _unblock).

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <mgba/core/core.h>
#include <mgba/core/cheats.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/cheats.h>
#include <mgba-util/vfs.h>

#include "pk_cheats.h"

// Code types, = mGBA's enum GBACheatType (and CheatType.native on the Kotlin side).
#define PK_CHEAT_AUTO 0          // GBA_CHEAT_AUTODETECT
#define PK_CHEAT_CODEBREAKER 1   // GBA_CHEAT_CODEBREAKER
#define PK_CHEAT_GAMESHARK 2     // GBA_CHEAT_GAMESHARK (GameShark / Action Replay v1-v2)
#define PK_CHEAT_ACTION_REPLAY 3 // GBA_CHEAT_PRO_ACTION_REPLAY (Action Replay v3)

#define PK_MAX_FIXES 512

// The original ROM bytes under a hook or a ROM patch. `orig` is what the file
// holds; pk_cheats_swap_rom trades it with the live bytes and back.
struct pk_fix {
    uint32_t addr;
    int width;
    uint8_t orig[4];
};

static struct pk_fix sFixes[PK_MAX_FIXES];
static int sFixCount;

#define PK_MAX_OPTIONS 128   // co-op alone puts ~63 patches in (link hooks, rewards, wild) beside the lock's and REUSABLE TMS': 64 refused the wild kind on a device
static struct pk_fix sOptions[PK_MAX_OPTIONS];
static int sOptionCount;

// A block patch: `orig` (malloc'd) holds the file's bytes over [addr, addr + len).
struct pk_block {
    uint32_t addr;
    int len;
    uint8_t* orig;
};

#define PK_MAX_BLOCKS 256
static struct pk_block sBlocks[PK_MAX_BLOCKS];
static int sBlockCount;

static int pk_cheats_is_gba(struct mCore* core) {
    return core && core->platform(core) == mPLATFORM_GBA;
}

static uint8_t* pk_rom_byte(struct mCore* core, uint32_t addr) {
    struct GBA* gba = core->board;
    uint32_t off = addr & (SIZE_CART0 - 1);
    if (!gba->memory.rom || off >= gba->memory.romSize) {
        return NULL;
    }
    return (uint8_t*) gba->memory.rom + off;
}

static int pk_fix_known(uint32_t addr) {
    for (int i = 0; i < sFixCount; i++) {
        if (sFixes[i].addr == addr) {
            return 1;
        }
    }
    return 0;
}

static void pk_fix_add(uint32_t addr, int width, uint32_t orig) {
    // Two hooks on one address: the first holds the real opcode, the second only
    // the first one's BKPT.
    if (sFixCount >= PK_MAX_FIXES || pk_fix_known(addr)) {
        return;
    }
    struct pk_fix* f = &sFixes[sFixCount++];
    f->addr = addr;
    f->width = width;
    for (int i = 0; i < 4; i++) {
        f->orig[i] = (uint8_t) (orig >> (8 * i));
    }
}

// Takes every cheat out of the core, undoing what it did to the ROM: a disabled
// refresh unpatches its ROM patches, removing the set takes its hook's BKPT out.
// mCheatDeviceClear alone would leave both behind. Last first, so two sets on one
// hook put the real opcode back last.
void pk_cheats_clear(struct mCore* core) {
    sFixCount = 0;
    if (!pk_cheats_is_gba(core)) {
        return;
    }
    struct mCheatDevice* device = core->cheatDevice(core);
    if (!device) {
        return;
    }
    while (mCheatSetsSize(&device->cheats)) {
        struct mCheatSet* set = *mCheatSetsGetPointer(&device->cheats, mCheatSetsSize(&device->cheats) - 1);
        set->enabled = false;
        mCheatRefresh(device, set);
        mCheatRemoveSet(device, set);
        mCheatSetDeinit(set);
    }
}

// Replaces the core's cheats with those in `text` (mGBA .cheats format, the
// enabled ones only). Returns how many sets were loaded, -1 if it didn't parse.
int pk_cheats_apply(struct mCore* core, const char* text, size_t len) {
    pk_cheats_clear(core);
    if (!pk_cheats_is_gba(core) || !text || !len) {
        return 0;
    }
    struct mCheatDevice* device = core->cheatDevice(core);
    if (!device) {
        return -1;
    }
    struct VFile* vf = VFileFromConstMemory(text, len);
    if (!vf) {
        return -1;
    }
    bool ok = mCheatParseFile(device, vf);
    vf->close(vf);
    // Hooks are in already (mCheatAddSet), holding the opcode they replaced; ROM
    // patches only go in at the set's first refresh, so the ROM still has the
    // original under them now.
    size_t n = mCheatSetsSize(&device->cheats);
    for (size_t i = 0; i < n; i++) {
        struct GBACheatSet* set = (struct GBACheatSet*) *mCheatSetsGetPointer(&device->cheats, i);
        if (set->hook && set->hook->reentries > 0) {
            pk_fix_add(set->hook->address, set->hook->mode == MODE_ARM ? 4 : 2, set->hook->patchedOpcode);
        }
        for (size_t p = 0; p < mCheatPatchListSize(&set->d.romPatches); p++) {
            struct mCheatPatch* patch = mCheatPatchListGetPointer(&set->d.romPatches, p);
            if (patch->address < BASE_CART0 || patch->address >= BASE_CART_SRAM || pk_fix_known(patch->address)) {
                continue;
            }
            uint32_t orig = 0;
            for (int b = 0; b < patch->width; b++) {
                uint8_t* rom = pk_rom_byte(core, patch->address + b);
                orig |= (uint32_t) (rom ? *rom : 0xFF) << (8 * b);
            }
            pk_fix_add(patch->address, patch->width, orig);
        }
    }
    return ok ? (int) n : -1;
}

static void pk_fixes_overlay(const struct pk_fix* fixes, int count, uint32_t addr, uint8_t* dst, int len) {
    for (int i = 0; i < count; i++) {
        const struct pk_fix* f = &fixes[i];
        for (int b = 0; b < f->width; b++) {
            uint32_t a = f->addr + (uint32_t) b;
            // Mirrors of the cart (wait states 1 and 2) read the same ROM.
            for (uint32_t base = BASE_CART0; base < BASE_CART_SRAM; base += SIZE_CART0) {
                uint32_t m = base | (a & (SIZE_CART0 - 1));
                if (m >= addr && m - addr < (uint32_t) len) {
                    dst[m - addr] = f->orig[b];
                }
            }
        }
    }
}

// Puts the file's own bytes back into `dst`, a bus read of [addr, addr + len)
// from the cheated core, wherever a cheat or a game option patched the ROM.
void pk_cheats_overlay(uint32_t addr, uint8_t* dst, int len) {
    pk_fixes_overlay(sFixes, sFixCount, addr, dst, len);
    pk_fixes_overlay(sOptions, sOptionCount, addr, dst, len);
    for (int i = 0; i < sBlockCount; i++) {
        const struct pk_block* k = &sBlocks[i];
        for (uint32_t base = BASE_CART0; base < BASE_CART_SRAM; base += SIZE_CART0) {
            // The block as seen through this mirror, clipped to the read.
            uint32_t from = base | (k->addr & (SIZE_CART0 - 1));
            uint32_t lo = from > addr ? from : addr;
            uint32_t hi = from + (uint32_t) k->len;
            if (addr + (uint32_t) len < hi) {
                hi = addr + (uint32_t) len;
            }
            if (lo < hi) {
                memcpy(dst + (lo - addr), k->orig + (lo - from), hi - lo);
            }
        }
    }
}

static void pk_fixes_swap(struct mCore* core, struct pk_fix* fixes, int count) {
    for (int i = 0; i < count; i++) {
        struct pk_fix* f = &fixes[i];
        for (int b = 0; b < f->width; b++) {
            uint8_t* rom = pk_rom_byte(core, f->addr + b);
            if (rom) {
                uint8_t live = *rom;
                *rom = f->orig[b];
                f->orig[b] = live;
            }
        }
    }
}

// Swaps the patched ROM bytes with the originals; twice is a no-op. For reading
// the ROM the file holds while cheats are in, on the emu thread so no frame runs
// in between.
void pk_cheats_swap_rom(struct mCore* core) {
    if (!pk_cheats_is_gba(core)) {
        return;
    }
    pk_fixes_swap(core, sFixes, sFixCount);
    pk_fixes_swap(core, sOptions, sOptionCount);
    for (int i = 0; i < sBlockCount; i++) {
        struct pk_block* k = &sBlocks[i];
        uint8_t* rom = pk_rom_byte(core, k->addr);
        if (rom && pk_rom_byte(core, k->addr + (uint32_t) k->len - 1)) {
            for (int b = 0; b < k->len; b++) {
                uint8_t live = rom[b];
                rom[b] = k->orig[b];
                k->orig[b] = live;
            }
        }
    }
}

// A game option's ROM patch: `value` over [addr, addr + len) (len <= 4), only if
// the ROM holds `expect` over [expectAddr, expectAddr + expectLen) - the code
// around it, so a ROM built differently is never patched. Returns 1 if it went in
// (or was in already), 0 if the code didn't match.
int pk_cheats_patch(struct mCore* core, uint32_t expectAddr, const uint8_t* expect, int expectLen,
                    uint32_t addr, const uint8_t* value, int len) {
    if (!pk_cheats_is_gba(core) || len < 1 || len > 4 || addr < BASE_CART0 || addr >= BASE_CART_SRAM) {
        return 0;
    }
    for (int i = 0; i < sOptionCount; i++) {
        if (sOptions[i].addr == addr) {
            return 1;
        }
    }
    if (sOptionCount >= PK_MAX_OPTIONS) {
        return 0;
    }
    // The file's bytes: a cheat may sit on them (read through the overlay).
    uint8_t live[64];
    if (expectLen < 1 || expectLen > (int) sizeof(live)) {
        return 0;
    }
    for (int i = 0; i < expectLen; i++) {
        uint8_t* rom = pk_rom_byte(core, expectAddr + i);
        if (!rom) {
            return 0;
        }
        live[i] = *rom;
    }
    pk_fixes_overlay(sFixes, sFixCount, expectAddr, live, expectLen);
    if (memcmp(live, expect, (size_t) expectLen)) {
        return 0;
    }
    struct pk_fix* f = &sOptions[sOptionCount++];
    f->addr = addr;
    f->width = len;
    struct GBA* gba = core->board;
    for (int b = 0; b < len; b++) {
        int8_t old = 0;
        // mGBA's own patch: copies a still-mapped ROM file first (_pristineCow).
        GBAPatch8(gba->cpu, addr + b, (int8_t) value[b], &old);
        f->orig[b] = (uint8_t) old;
    }
    return 1;
}

// Takes every game option's patch out again (the file's bytes back).
void pk_cheats_unpatch(struct mCore* core) {
    if (pk_cheats_is_gba(core)) {
        for (int i = sOptionCount - 1; i >= 0; i--) {
            for (int b = 0; b < sOptions[i].width; b++) {
                uint8_t* rom = pk_rom_byte(core, sOptions[i].addr + b);
                if (rom) {
                    *rom = sOptions[i].orig[b];
                }
            }
        }
    }
    sOptionCount = 0;
}

// Takes out only the game options' patches that touch [addr, addr + len) (the file's bytes back) and keeps the
// others: co-op's patches go while REUSABLE TMS stays. Returns how many went.
int pk_cheats_unpatch_range(struct mCore* core, uint32_t addr, uint32_t len) {
    uint32_t lo = addr & (SIZE_CART0 - 1), hi = lo + len;
    int removed = 0, kept = 0;
    for (int i = sOptionCount - 1; i >= 0; i--) {
        uint32_t o = sOptions[i].addr & (SIZE_CART0 - 1);
        if (o < hi && lo < o + (uint32_t) sOptions[i].width) {
            for (int b = 0; pk_cheats_is_gba(core) && b < sOptions[i].width; b++) {
                uint8_t* rom = pk_rom_byte(core, sOptions[i].addr + b);
                if (rom) {
                    *rom = sOptions[i].orig[b];
                }
            }
            sOptions[i].width = 0;   // dropped below
            removed++;
        }
    }
    for (int i = 0; i < sOptionCount; i++) {
        if (sOptions[i].width) {
            sOptions[kept++] = sOptions[i];
        }
    }
    sOptionCount = kept;
    return removed;
}

// Whether a game option's patch already in touches [addr, addr + len): pk_cheats_patch answers 1 for an address
// another option holds without putting anything in, so a caller that takes its own patches out by range (co-op) asks
// first.
int pk_cheats_patch_overlaps(uint32_t addr, uint32_t len) {
    uint32_t lo = addr & (SIZE_CART0 - 1), hi = lo + len;
    for (int i = 0; i < sOptionCount; i++) {
        uint32_t o = sOptions[i].addr & (SIZE_CART0 - 1);
        if (o < hi && lo < o + (uint32_t) sOptions[i].width) {
            return 1;
        }
    }
    return 0;
}

static uint32_t pk_crc32(const uint8_t* p, int len) {
    uint32_t c = 0xFFFFFFFFu;
    for (int i = 0; i < len; i++) {
        c ^= p[i];
        for (int k = 0; k < 8; k++) {
            c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
        }
    }
    return ~c;
}

// A PLAYER LOOK block: `data` over [addr, addr + len), only if the ROM there is still the
// file's (its CRC32 = `expectCrc`, no cheat or other patch on it) and the block doesn't
// overlap one already in. Returns 1 if it went in, 0 if not.
int pk_cheats_block(struct mCore* core, uint32_t addr, const uint8_t* data, int len, uint32_t expectCrc) {
    if (!pk_cheats_is_gba(core) || len < 1 || sBlockCount >= PK_MAX_BLOCKS || addr < BASE_CART0 || addr >= BASE_CART_SRAM) {
        return 0;
    }
    uint32_t off = addr & (SIZE_CART0 - 1);
    uint8_t* rom = pk_rom_byte(core, addr);
    if (!rom || !pk_rom_byte(core, addr + (uint32_t) len - 1)) {
        return 0;
    }
    for (int i = 0; i < sBlockCount; i++) {
        uint32_t o = sBlocks[i].addr & (SIZE_CART0 - 1);
        if (off < o + (uint32_t) sBlocks[i].len && o < off + (uint32_t) len) {
            return 0;
        }
    }
    if (pk_crc32(rom, len) != expectCrc) {
        return 0;
    }
    uint8_t* orig = malloc((size_t) len);
    if (!orig) {
        return 0;
    }
    memcpy(orig, rom, (size_t) len);
    // mGBA's own patch for the first byte: copies a still-mapped ROM file first
    // (_pristineCow), so the rest goes into that copy, never the file.
    int8_t old = 0;
    GBAPatch8(((struct GBA*) core->board)->cpu, addr, (int8_t) data[0], &old);
    rom = pk_rom_byte(core, addr);
    memcpy(rom, data, (size_t) len);
    struct pk_block* k = &sBlocks[sBlockCount++];
    k->addr = BASE_CART0 | off;
    k->len = len;
    k->orig = orig;
    return 1;
}

// Takes every PLAYER LOOK block out again (the file's bytes back).
void pk_cheats_unblock(struct mCore* core) {
    for (int i = sBlockCount - 1; i >= 0; i--) {
        struct pk_block* k = &sBlocks[i];
        uint8_t* rom = pk_cheats_is_gba(core) ? pk_rom_byte(core, k->addr) : NULL;
        if (rom) {
            memcpy(rom, k->orig, (size_t) k->len);
        }
        free(k->orig);
        k->orig = NULL;
    }
    sBlockCount = 0;
}

// Whether a block already in overlaps [addr, addr + len).
int pk_cheats_block_overlaps(uint32_t addr, uint32_t len) {
    uint32_t lo = addr & (SIZE_CART0 - 1), hi = lo + len;
    for (int i = 0; i < sBlockCount; i++) {
        uint32_t o = sBlocks[i].addr & (SIZE_CART0 - 1);
        if (o < hi && lo < o + (uint32_t) sBlocks[i].len) {
            return 1;
        }
    }
    return 0;
}

// Takes out only the blocks that lie inside [addr, addr + len) (the file's bytes back) and keeps the others: co-op's
// code goes while PLAYER LOOK's stay. Returns how many went.
int pk_cheats_unblock_range(struct mCore* core, uint32_t addr, uint32_t len) {
    uint32_t lo = addr & (SIZE_CART0 - 1), hi = lo + len;
    int removed = 0, kept = 0;
    for (int i = sBlockCount - 1; i >= 0; i--) {
        struct pk_block* k = &sBlocks[i];
        uint32_t o = k->addr & (SIZE_CART0 - 1);
        if (o >= lo && o + (uint32_t) k->len <= hi) {
            uint8_t* rom = pk_cheats_is_gba(core) ? pk_rom_byte(core, k->addr) : NULL;
            if (rom) {
                memcpy(rom, k->orig, (size_t) k->len);
            }
            free(k->orig);
            k->orig = NULL;
            removed++;
        }
    }
    for (int i = 0; i < sBlockCount; i++) {
        if (sBlocks[i].orig) {
            sBlocks[kept++] = sBlocks[i];
        }
    }
    sBlockCount = kept;
    return removed;
}

// Swaps 32-byte palettes already loaded: wherever one of `from` (n of them back to back)
// sits in EWRAM (the game's palette buffers, gPlttBufferUnfaded / Faded) or in the OBJ half
// of palette RAM, `to` goes in its place - so a look put in or taken out mid-game recolours
// the player now, not on the next map. Returns how many were swapped. A `from` of fewer than
// three colours is skipped: a black or blank palette's bytes are every empty run of EWRAM.
static bool pk_palette_real(const uint8_t* pal) {
    uint16_t seen[3];
    int distinct = 0;
    for (int i = 0; i < 16 && distinct < 3; i++) {
        uint16_t c = (uint16_t) (pal[i * 2] | (pal[i * 2 + 1] << 8));
        bool dup = false;
        for (int j = 0; j < distinct; j++) dup |= seen[j] == c;
        if (!dup) seen[distinct++] = c;
    }
    return distinct >= 3;
}

int pk_cheats_repalette(struct mCore* core, const uint8_t* from, int n, const uint8_t* to) {
    if (!pk_cheats_is_gba(core) || n < 1) {
        return 0;
    }
    struct GBA* gba = core->board;
    int swapped = 0;
    uint8_t* ewram = (uint8_t*) gba->memory.wram;
    for (int o = 0; o + 32 <= SIZE_WORKING_RAM; o += 2) {
        for (int i = 0; i < n; i++) {
            if (!pk_palette_real(from + i * 32)) continue;
            if (!memcmp(ewram + o, from + i * 32, 32)) {
                memcpy(ewram + o, to, 32);
                swapped++;
                o += 30;
                break;
            }
        }
    }
    for (int slot = 16; slot < 32; slot++) {
        const uint8_t* live = (const uint8_t*) &gba->video.palette[slot * 16];
        for (int i = 0; i < n; i++) {
            if (!pk_palette_real(from + i * 32)) continue;
            if (!memcmp(live, from + i * 32, 32)) {
                for (int c = 0; c < 16; c++) {
                    uint16_t v = (uint16_t) (to[c * 2] | (to[c * 2 + 1] << 8));
                    core->busWrite16(core, BASE_PALETTE_RAM + (uint32_t) (slot * 32 + c * 2), v);
                }
                swapped++;
                break;
            }
        }
    }
    return swapped;
}

int pk_cheats_rom_patched(void) {
    return sFixCount > 0 || sOptionCount > 0 || sBlockCount > 0;
}

// Checks one cheat's code lines ('\n'-separated) on a scratch cheat device - no
// core needed, so the top-screen Settings can check a code with no game running.
// `directive` (a .cheats file's "GSAv1", "PARv3 raw", ...; may be empty) goes in
// first, as mCheatParseFile would.
// Returns a malloc'd "<directive>\n<one '1' or '0' per line>": the directive is
// what mGBA detected the code as ("GSAv1", "PARv3", ... or empty for
// CodeBreaker / VBA codes, which don't need one), stored with the cheat so it
// loads the same way every time; a '0' line is one mGBA can't read.
char* pk_cheats_check(const char* code, int type, const char* directive) {
    struct mCheatDevice* device = GBACheatDeviceCreate();
    if (!device) {
        return NULL;
    }
    struct mCheatSet* set = device->createSet(device, NULL);
    size_t codeLen = code ? strlen(code) : 0;
    char* out = malloc(codeLen + 64);
    char* lines = malloc(codeLen + 1);
    if (!set || !out || !lines) {
        free(out);
        free(lines);
        if (set) {
            mCheatSetDeinit(set);
        }
        mCheatDeviceDestroy(device);
        return NULL;
    }
    if (directive && directive[0]) {
        struct StringList given;
        StringListInit(&given, 1);
        *StringListAppend(&given) = strdup(directive);
        set->parseDirectives(set, &given);
        free(*StringListGetPointer(&given, 0));
        StringListDeinit(&given);
    }
    char* results = lines;
    const char* cur = code ? code : "";
    char line[128];
    while (*cur) {
        const char* nl = strchr(cur, '\n');
        size_t n = nl ? (size_t) (nl - cur) : strlen(cur);
        size_t copy = n < sizeof(line) - 1 ? n : sizeof(line) - 1;
        memcpy(line, cur, copy);
        line[copy] = '\0';
        *results++ = (n < sizeof(line) && mCheatAddLine(set, line, type)) ? '1' : '0';
        cur += n;
        if (*cur == '\n') {
            cur++;
        }
    }
    *results = '\0';

    struct StringList directives;
    StringListInit(&directives, 2);
    set->dumpDirectives(set, &directives);
    const char* detected = StringListSize(&directives) ? *StringListGetPointer(&directives, 0) : "";
    snprintf(out, codeLen + 64, "%s\n%s", detected, lines);
    for (size_t d = 0; d < StringListSize(&directives); d++) {
        free(*StringListGetPointer(&directives, d));
    }
    StringListDeinit(&directives);
    free(lines);
    mCheatSetDeinit(set);
    mCheatDeviceDestroy(device);
    return out;
}
