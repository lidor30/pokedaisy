// pd_armgba.h's `struct mCore`: mGBA's own struct (mGBA's headers, which the rest of the 3DS port builds against for its
// Game Boy games), its members filled in over armGBA through pd_armgba_internal.h. See pd_armgba.h for what is and
// isn't there.
#include "pd_armgba_internal.h"

#include <stdlib.h>
#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gba/gba.h>

#include "../../app/src/main/cpp/pk_call.h"

static struct mCore agCore;
static struct GBA agView; // mGBA's shape, pointers into armGBA (never run)
static struct ag_memory agMem;
static color_t* agVideo;
static size_t agStride;
static uint32_t agFrames;

static void refresh_view(void) {
    ag_memory(&agMem);
    struct GBA* v = &agView;
    v->memory.wram = (uint32_t*) agMem.ewram;
    v->memory.iwram = (uint32_t*) agMem.iwram;
    v->memory.rom = (uint32_t*) agMem.rom;
    v->memory.romSize = agMem.romSize;
    v->video.vram = (uint16_t*) agMem.vram;
    v->video.renderer = NULL;
    v->video.vcount = 160;
}

// The arrays mGBA's struct holds in place, as the frame left them.
static void copy_arrays(void) {
    struct GBA* v = &agView;
    if (agMem.io) memcpy(v->memory.io, agMem.io, agMem.ioSize < sizeof(v->memory.io) ? agMem.ioSize : sizeof(v->memory.io));
    if (agMem.palette) memcpy(v->video.palette, agMem.palette, sizeof(v->video.palette));
    if (agMem.oam) memcpy(v->video.oam.raw, agMem.oam, sizeof(v->video.oam.raw));
}

static enum mPlatform c_platform(const struct mCore* core) {
    (void) core;
    return mPLATFORM_GBA;
}

static void c_set_keys(struct mCore* core, uint32_t keys) {
    (void) core;
    ag_set_keys(keys);
}

static void c_add_keys(struct mCore* core, uint32_t keys) {
    (void) core;
    ag_set_keys(ag_keys() | keys);
}

static void c_clear_keys(struct mCore* core, uint32_t keys) {
    (void) core;
    ag_set_keys(ag_keys() & ~keys);
}

static uint32_t c_get_keys(struct mCore* core) {
    (void) core;
    return ag_keys();
}

static void c_run_frame(struct mCore* core) {
    (void) core;
    ag_run_frame();
    agFrames++;
    copy_arrays();
}

static void c_step(struct mCore* core) {
    (void) core;
    ag_step();
}

static void c_reset(struct mCore* core) {
    (void) core;
    ag_reset();
    refresh_view();
    copy_arrays();
}

static uint32_t c_frame_counter(const struct mCore* core) {
    (void) core;
    return agFrames;
}

static void c_dimensions(const struct mCore* core, unsigned* width, unsigned* height) {
    (void) core;
    *width = 240;
    *height = 160;
}

static void c_set_video(struct mCore* core, color_t* buffer, size_t stride) {
    (void) core;
    agVideo = buffer;
    agStride = stride;
    ag_set_video((uint32_t*) buffer, stride);
}

// armGBA's layer hands a frame over whole (its render threads have finished it): nothing to wait for.
static void c_get_pixels(struct mCore* core, const void** buffer, size_t* stride) {
    (void) core;
    *buffer = agVideo;
    *stride = agStride;
}

static void c_checksum(const struct mCore* core, void* data, enum mCoreChecksumType type) {
    (void) core;
    (void) type;
    *(uint32_t*) data = ag_crc();
}

static size_t c_rom_size(const struct mCore* core) {
    (void) core;
    return agMem.romSize;
}

// mGBA's GBA region ids (gba/memory.h).
static void* c_memory_block(struct mCore* core, size_t id, size_t* sizeOut) {
    (void) core;
    *sizeOut = 0;
    switch (id) {
    case REGION_WORKING_RAM: *sizeOut = SIZE_WORKING_RAM; return agMem.ewram;
    case REGION_WORKING_IRAM: *sizeOut = SIZE_WORKING_IRAM; return agMem.iwram;
    case REGION_IO: *sizeOut = agMem.ioSize; return agMem.io;
    case REGION_PALETTE_RAM: *sizeOut = SIZE_PALETTE_RAM; return agMem.palette;
    case REGION_VRAM: *sizeOut = SIZE_VRAM; return agMem.vram;
    case REGION_OAM: *sizeOut = SIZE_OAM; return agMem.oam;
    case REGION_CART0: *sizeOut = agMem.romSize; return agMem.rom;
    default: return NULL;
    }
}

// RAM and ROM straight from armGBA's memory (most of what PokeDaisy reads), anything else through its bus.
static inline uint32_t c_read8_at(uint32_t a) {
    uint32_t off = a & 0x00FFFFFF;
    switch (a >> 24) {
    case 0x2: return agMem.ewram[off & (SIZE_WORKING_RAM - 1)];
    case 0x3: return agMem.iwram[off & (SIZE_WORKING_IRAM - 1)];
    case 0x8: case 0x9: {
        uint32_t r = a - 0x08000000;
        if (r < agMem.romSize) return agMem.rom[r];
        break;
    }
    default: break;
    }
    return ag_read8(a);
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

static void c_write8(struct mCore* core, uint32_t a, uint8_t v) {
    (void) core;
    ag_write8(a, v);
}

static void c_write16(struct mCore* core, uint32_t a, uint16_t v) {
    (void) core;
    ag_write16(a, v);
}

static void c_write32(struct mCore* core, uint32_t a, uint32_t v) {
    (void) core;
    ag_write32(a, v);
}

static void c_deinit(struct mCore* core) {
    (void) core;
    ag_unload();
    memset(&agMem, 0, sizeof(agMem));
    agVideo = NULL;
}

bool pd_armgba_is(const struct mCore* core) {
    return core == &agCore && agMem.ewram;
}

struct mCore* pd_armgba_load(const char* romPath, const char* savePath, const struct pd_armgba_host* host, char* error, size_t errorLen) {
    struct ag_host h = { host->audio, host->log, host->ctx, host->systemDir, host->workDir, host->threaded, host->romBuffer };
    if (!ag_load(romPath, savePath, &h, error, errorLen)) return NULL;
    memset(&agCore, 0, sizeof(agCore));
    memset(&agView, 0, sizeof(agView));
    agFrames = 0;
    struct mCore* c = &agCore;
    c->board = &agView;
    c->platform = c_platform;
    c->deinit = c_deinit;
    c->reset = c_reset;
    c->setKeys = c_set_keys;
    c->addKeys = c_add_keys;
    c->clearKeys = c_clear_keys;
    c->getKeys = c_get_keys;
    c->runFrame = c_run_frame;
    c->step = c_step;
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
    refresh_view();
    copy_arrays();
    return c;
}
