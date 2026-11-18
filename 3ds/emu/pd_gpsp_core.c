// pd_gpsp.h's `struct mCore`: mGBA's own struct (mGBA's headers), its members filled in over gpSP through pd_gpsp.h's
// plain-C half - pd_armgba_core.c's shape. See pd_gpsp.h for what is and isn't there.
#include "pd_gpsp.h"

#include <stdlib.h>
#include <string.h>

#include <mgba/core/cheats.h>
#include <mgba/core/core.h>
#include <mgba/internal/gba/cheats.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>


static struct mCore gpCore;
static struct GBA gpView; // mGBA's shape, pointers into gpSP (never run)
static struct pd_gpsp_memory gpMem;
static color_t* gpVideo;
static size_t gpStride;
static uint32_t gpFrames;
// Cheats: mGBA's GBA cheat device over this view (pd_gpsp_cheats_apply), its sets applied at each frame's end.
static struct mCheatDevice* gpCheats;
static bool gpQuietWrites; // the arrays copied once after the cheats' writes, not after each

static void refresh_view(void) {
    pd_gpsp_memory(&gpMem);
    struct GBA* v = &gpView;
    v->memory.wram = (uint32_t*) gpMem.ewram;
    v->memory.iwram = (uint32_t*) gpMem.iwram;
    v->memory.rom = (uint32_t*) gpMem.rom;
    v->memory.romSize = gpMem.romSize;
    v->video.vram = (uint16_t*) gpMem.vram;
    v->video.renderer = NULL;
    v->video.vcount = 160;
}

// The arrays mGBA's struct holds in place, as the frame left them.
static void copy_arrays(void) {
    struct GBA* v = &gpView;
    if (gpMem.io) memcpy(v->memory.io, gpMem.io, sizeof(v->memory.io) < 0x400 ? sizeof(v->memory.io) : 0x400);
    if (gpMem.palette) memcpy(v->video.palette, gpMem.palette, sizeof(v->video.palette));
    if (gpMem.oam) memcpy(v->video.oam.raw, gpMem.oam, sizeof(v->video.oam.raw));
}

static enum mPlatform c_platform(const struct mCore* core) {
    (void) core;
    return mPLATFORM_GBA;
}

static void c_set_keys(struct mCore* core, uint32_t keys) {
    (void) core;
    pd_gpsp_set_keys(keys);
}

static void c_add_keys(struct mCore* core, uint32_t keys) {
    (void) core;
    pd_gpsp_set_keys(pd_gpsp_keys() | keys);
}

static void c_clear_keys(struct mCore* core, uint32_t keys) {
    (void) core;
    pd_gpsp_set_keys(pd_gpsp_keys() & ~keys);
}

static uint32_t c_get_keys(struct mCore* core) {
    (void) core;
    return pd_gpsp_keys();
}

static void cheats_refresh(void);

static void c_run_frame(struct mCore* core) {
    (void) core;
    pd_gpsp_run_frame();
    gpFrames++;
    cheats_refresh();
    copy_arrays();
}

static void c_reset(struct mCore* core) {
    (void) core;
    pd_gpsp_reset();
    refresh_view();
    copy_arrays();
}

static uint32_t c_frame_counter(const struct mCore* core) {
    (void) core;
    return gpFrames;
}

static void c_dimensions(const struct mCore* core, unsigned* width, unsigned* height) {
    (void) core;
    *width = 240;
    *height = 160;
}

static void c_set_video(struct mCore* core, color_t* buffer, size_t stride) {
    (void) core;
    gpVideo = buffer;
    gpStride = stride;
    pd_gpsp_set_video((uint32_t*) buffer, stride);
}

// gpSP draws the frame on the game's thread: whole when the frame returns.
static void c_get_pixels(struct mCore* core, const void** buffer, size_t* stride) {
    (void) core;
    *buffer = gpVideo;
    *stride = gpStride;
}

static void c_checksum(const struct mCore* core, void* data, enum mCoreChecksumType type) {
    (void) core;
    (void) type;
    *(uint32_t*) data = pd_gpsp_crc();
}

static size_t c_rom_size(const struct mCore* core) {
    (void) core;
    return gpMem.romSize;
}

// mGBA's GBA region ids (gba/memory.h).
static void* c_memory_block(struct mCore* core, size_t id, size_t* sizeOut) {
    (void) core;
    *sizeOut = 0;
    switch (id) {
    case REGION_WORKING_RAM: *sizeOut = SIZE_WORKING_RAM; return gpMem.ewram;
    case REGION_WORKING_IRAM: *sizeOut = SIZE_WORKING_IRAM; return gpMem.iwram;
    case REGION_IO: *sizeOut = 0x400; return gpMem.io;
    case REGION_PALETTE_RAM: *sizeOut = SIZE_PALETTE_RAM; return gpMem.palette;
    case REGION_VRAM: *sizeOut = SIZE_VRAM; return gpMem.vram;
    case REGION_OAM: *sizeOut = SIZE_OAM; return gpMem.oam;
    case REGION_CART0: *sizeOut = gpMem.romSize; return gpMem.rom;
    default: return NULL;
    }
}

// RAM and ROM straight from gpSP's memory (most of what PokeDaisy reads), anything else through its memory map.
static inline uint32_t c_read8_at(uint32_t a) {
    uint32_t off = a & 0x00FFFFFF;
    switch (a >> 24) {
    case 0x2: return gpMem.ewram[off & (SIZE_WORKING_RAM - 1)];
    case 0x3: return gpMem.iwram[off & (SIZE_WORKING_IRAM - 1)];
    case 0x8: case 0x9: {
        uint32_t r = a - 0x08000000;
        if (r < gpMem.romSize) return gpMem.rom[r];
        break;
    }
    default: break;
    }
    return pd_gpsp_read8(a);
}

static uint32_t c_read8(struct mCore* core, uint32_t a) {
    (void) core;
    return c_read8_at(a);
}

static uint32_t c_read16(struct mCore* core, uint32_t a) {
    (void) core;
    a &= ~1u;
    return c_read8_at(a) | c_read8_at(a + 1) << 8;
}

static uint32_t c_read32(struct mCore* core, uint32_t a) {
    (void) core;
    a &= ~3u;
    return c_read8_at(a) | c_read8_at(a + 1) << 8 | c_read8_at(a + 2) << 16 | c_read8_at(a + 3) << 24;
}

static uint32_t c_raw8(struct mCore* core, uint32_t a, int segment) {
    (void) segment;
    return c_read8(core, a);
}

static uint32_t c_raw16(struct mCore* core, uint32_t a, int segment) {
    (void) segment;
    return c_read16(core, a);
}

static uint32_t c_raw32(struct mCore* core, uint32_t a, int segment) {
    (void) segment;
    return c_read32(core, a);
}

// Little-endian, as the GBA's memory is (and both hosts are).
static void c_write8(struct mCore* core, uint32_t a, uint8_t v) {
    (void) core;
    pd_gpsp_write(a, &v, 1);
    if (!gpQuietWrites) copy_arrays();
}

static void c_write16(struct mCore* core, uint32_t a, uint16_t v) {
    (void) core;
    pd_gpsp_write(a & ~1u, &v, 2);
    if (!gpQuietWrites) copy_arrays();
}

static void c_write32(struct mCore* core, uint32_t a, uint32_t v) {
    (void) core;
    pd_gpsp_write(a & ~3u, &v, 4);
    if (!gpQuietWrites) copy_arrays();
}

// The cheats' ROM patches go into the cartridge (its recompiled code dropped); RAM as a bus write.
static void c_raw_write(uint32_t a, const void* v, size_t n) {
    if (a >> 24 >= 0x08 && a >> 24 <= 0x0D) pd_gpsp_rom_write(0x08000000u | (a & 0x01FFFFFFu), v, n);
    else pd_gpsp_write(a, v, n);
}

static void c_raw_write8(struct mCore* core, uint32_t a, int segment, uint8_t v) {
    (void) core;
    (void) segment;
    c_raw_write(a, &v, 1);
}

static void c_raw_write16(struct mCore* core, uint32_t a, int segment, uint16_t v) {
    (void) core;
    (void) segment;
    c_raw_write(a & ~1u, &v, 2);
}

static void c_raw_write32(struct mCore* core, uint32_t a, int segment, uint32_t v) {
    (void) core;
    (void) segment;
    c_raw_write(a & ~3u, &v, 4);
}

// --- pk_cheats.h on gpSP: mGBA's own parser (GameShark, Action Replay, CodeBreaker, as on the other cores) ---
// A hook code (a master code's "run the cheats from here") would put a BKPT in through mGBA's struct GBA, which this
// view only borrows the shape of: the sets are parsed with no core attached (so no hook goes in) and their hooks
// dropped, every set then applied at the frame's end, as mGBA does with a set that has none.

static void cheats_refresh(void) {
    if (!gpCheats || !mCheatSetsSize(&gpCheats->cheats)) return;
    gpQuietWrites = true;
    for (size_t i = 0; i < mCheatSetsSize(&gpCheats->cheats); i++) mCheatRefresh(gpCheats, *mCheatSetsGetPointer(&gpCheats->cheats, i));
    gpQuietWrites = false;
}

void pd_gpsp_cheats_clear(void) {
    if (!gpCheats) return;
    // Last first, each switched off and refreshed so its ROM patches come out (mCheatRefresh's unpatch).
    while (mCheatSetsSize(&gpCheats->cheats)) {
        struct mCheatSet* set = *mCheatSetsGetPointer(&gpCheats->cheats, mCheatSetsSize(&gpCheats->cheats) - 1);
        set->enabled = false;
        mCheatRefresh(gpCheats, set);
        mCheatRemoveSet(gpCheats, set);
        mCheatSetDeinit(set);
    }
    copy_arrays();
}

int pd_gpsp_cheats_apply(const char* text, size_t len) {
    pd_gpsp_cheats_clear();
    if (!gpMem.ewram || !text || !len) return 0;
    if (!gpCheats) gpCheats = GBACheatDeviceCreate();
    if (!gpCheats) return -1;
    struct VFile* vf = VFileFromConstMemory(text, len);
    if (!vf) return -1;
    gpCheats->p = NULL;
    bool ok = mCheatParseFile(gpCheats, vf);
    vf->close(vf);
    size_t n = mCheatSetsSize(&gpCheats->cheats);
    for (size_t i = 0; i < n; i++) {
        struct GBACheatSet* set = (struct GBACheatSet*) *mCheatSetsGetPointer(&gpCheats->cheats, i);
        if (!set->hook) continue;
        if (--set->hook->refs == 0) free(set->hook);
        set->hook = NULL;
    }
    gpCheats->p = &gpCore;
    return ok ? (int) n : -1;
}

static void c_deinit(struct mCore* core) {
    (void) core;
    pd_gpsp_cheats_clear();
    if (gpCheats) {
        mCheatDeviceDestroy(gpCheats);
        gpCheats = NULL;
    }
    pd_gpsp_close();
    memset(&gpMem, 0, sizeof(gpMem));
    gpVideo = NULL;
}

bool pd_gpsp_is(const struct mCore* core) {
    return core == &gpCore && gpMem.ewram;
}

struct mCore* pd_gpsp_load(const char* romPath, const char* savePath, const struct pd_gpsp_host* host, char* error, size_t errorLen) {
    if (!pd_gpsp_open(romPath, savePath, host, error, errorLen)) return NULL;
    memset(&gpCore, 0, sizeof(gpCore));
    memset(&gpView, 0, sizeof(gpView));
    gpFrames = 0;
    struct mCore* c = &gpCore;
    c->board = &gpView;
    c->platform = c_platform;
    c->deinit = c_deinit;
    c->reset = c_reset;
    c->setKeys = c_set_keys;
    c->addKeys = c_add_keys;
    c->clearKeys = c_clear_keys;
    c->getKeys = c_get_keys;
    c->runFrame = c_run_frame;
    c->frameCounter = c_frame_counter;
    c->desiredVideoDimensions = c_dimensions;
    c->setVideoBuffer = c_set_video;
    c->getPixels = c_get_pixels;
    c->checksum = c_checksum;
    c->romSize = c_rom_size;
    c->getMemoryBlock = c_memory_block;
    c->busRead8 = c_read8;
    c->busRead16 = c_read16;
    c->busRead32 = c_read32;
    c->busWrite8 = c_write8;
    c->busWrite16 = c_write16;
    c->busWrite32 = c_write32;
    c->rawRead8 = c_raw8;
    c->rawRead16 = c_raw16;
    c->rawRead32 = c_raw32;
    c->rawWrite8 = c_raw_write8;
    c->rawWrite16 = c_raw_write16;
    c->rawWrite32 = c_raw_write32;
    refresh_view();
    copy_arrays();
    return c;
}
