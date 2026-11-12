// Between pd_armgba.c / pd_armgba_call.c (armGBA's headers) and pd_armgba_core.c (mGBA's): plain C types only, since
// both have a `struct GBA` of their own.
#ifndef PD_ARMGBA_INTERNAL_H
#define PD_ARMGBA_INTERNAL_H

#include "pd_armgba.h"

struct ag_host {
    void (*audio)(void* ctx, const int16_t* stereo, size_t frames);
    void (*log)(void* ctx, const char* line);
    void* ctx;
    const char* systemDir;
    const char* workDir;
    bool threaded;
    void* (*romBuffer)(size_t size);
};

// Where armGBA keeps the machine's memory (stays put while the game runs).
struct ag_memory {
    uint8_t* ewram;
    uint8_t* iwram;
    uint8_t* rom;
    size_t romSize;
    uint16_t* io;
    size_t ioSize;
    uint8_t* palette;
    uint8_t* vram;
    uint8_t* oam;
};

bool ag_load(const char* romPath, const char* savePath, const struct ag_host* host, char* error, size_t errorLen);
void ag_unload(void);
void ag_memory(struct ag_memory* m);
void ag_set_keys(uint32_t keys);
uint32_t ag_keys(void);
void ag_run_frame(void);
void ag_step(void);
void ag_reset(void);
void ag_set_video(uint32_t* buffer, size_t stride);
bool ag_skipping_video(void);
uint32_t ag_crc(void);
uint32_t ag_read8(uint32_t address);
void ag_write8(uint32_t address, uint8_t value);
void ag_write16(uint32_t address, uint16_t value);
void ag_write32(uint32_t address, uint32_t value);

struct GBA; // armGBA's, in the two files that see it
// One instruction on armGBA's exact CPU (its interpreter), or the wait for the next event while it's halted.
void pd_armgba_step(struct GBA* gba);

#endif
