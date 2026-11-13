// armGBA's libretro layer with no Game Boy side: the entry points gb.h declares, for Gambatte (GPL-2.0-only, which can't
// be distributed in one program with PokeDaisy's GPL-3.0 code). The 3DS port runs Game Boy games on mGBA instead, so
// none of these is reached with a game loaded; a Game Boy file given to armGBA fails to load.
#include "gb.h"

#include <stdio.h>

bool libretroGbLoad(const void* rom, size_t size, const char* systemDirectory, enum LibretroGbModel model, bool colorCorrection, unsigned audioRate, char* error, size_t errorSize) {
    (void) rom; (void) size; (void) systemDirectory; (void) colorCorrection; (void) audioRate;
    if (error && errorSize) snprintf(error, errorSize, "Game Boy games run on mGBA in this build");
    return false;
}

void libretroGbUnload(void) {
    
}

void libretroGbSetBootRom(enum LibretroGbBoot boot) {
    
}

void libretroGbReset(enum LibretroGbModel model) {
    
}

void libretroGbRestart(void) {
    
}

void libretroGbSetColorCorrection(bool a0) {
    
}

void libretroGbSetColorPanel(bool color) {
    
}

void libretroGbSetScreen(int a0) {
    
}

void libretroGbSetContrast(int a0) {
    
}

uint32_t libretroGbShade(int shade) {
    return 0;
}

uint32_t libretroGbOffColor(void) {
    return 0;
}

uint32_t libretroGbDarkestColor(void) {
    return 0;
}

bool libretroGbOnAdvance(void) {
    return false;
}

uint32_t libretroGbBootCrc(void) {
    return 0;
}

const uint32_t* libretroGbRunFrame(uint16_t keys, const int16_t** samples, size_t* frames) {
    return NULL;
}

void* libretroGbSaveRam(size_t* size) {
    return NULL;
}

void* libretroGbRtc(size_t* size) {
    return NULL;
}

enum LibretroGbClock libretroGbClock(void) {
    return LIBRETRO_GB_NO_CLOCK;
}

void libretroGbSetClock(bool gameTime, unsigned percent, long long offset) {
    
}

void libretroGbDeviceTime(bool on) {
    
}

bool libretroGbResetClock(void) {
    return false;
}

void libretroGbSetTilt(int x, int y) {
    
}

void libretroGbFlick(void) {
    
}

void* libretroGbSystemRam(size_t* size) {
    return NULL;
}

size_t libretroGbMemoryMap(struct retro_memory_descriptor* a0) {
    return 0;
}

bool libretroGbIsColor(void) {
    return false;
}

bool libretroGbSetCheats(const char* const* codes, size_t count) {
    return false;
}

void libretroGbSetCpuClock(unsigned quarters) {
    
}

bool libretroGbBooting(void) {
    return false;
}

size_t libretroGbStateSize(void) {
    return 0;
}

void libretroGbExactStates(bool exact) {
    
}

bool libretroGbSaveState(void* data, size_t size) {
    return false;
}

bool libretroGbLoadState(const void* data, size_t size) {
    return false;
}

int libretroGbLinkOut(void) {
    return 0;
}

void libretroGbLinkIn(int byte) {
    
}

int libretroGbLinkControl(void) {
    return 0;
}

void libretroGbSetPrinter(bool on, void (*output)(const uint8_t* pixels, int rows, bool cut, uint32_t number)) {
    
}

void libretroGbSelect(int which) {
    
}

void libretroGbSetPlayers(int count, bool adapter) {
    
}

void libretroGbRunPlayers(const uint16_t keys[], const uint32_t* pictures[], const int16_t* samples[], size_t frames[]) {
    
}
