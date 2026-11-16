// The 3DS itself, besides the screens: its log, its model, its memory (host.c).
#ifndef PD_CTR_HOST_H
#define PD_CTR_HOST_H

#include <stdbool.h>
#include <stddef.h>

// sdmc:/pokedaisy/log.txt, the last run's kept as log-prev.txt: what to attach to a bug report.
void host_log_open(const char* path);
void host_log(const char* format, ...) __attribute__((format(printf, 1, 2)));
void host_log_close(void);
// While a game runs, the lines wait in memory (no SD card writes mid-game) until hold(false), HOME, sleep or a full buffer.
void host_log_hold(bool hold);
void host_log_flush(void);
// pd_armgba_host.log.
void host_emu_log(void* ctx, const char* line);

// Azahar and Citra (they answer a system info query a console refuses).
bool host_is_emulator(void);
bool host_is_new_3ds(void);
// The dev build on a New 3DS told to play as an Old one (sdmc:/pokedaisy-dev/old3ds): host_is_new_3ds says no.
bool host_old_3ds_stand_in(void);
// The Old 3DS's system core (core 1) lent to the app for [percent] of its time, kept at the most asked for and given back
// to the system (30%) while the app is in the background. False when the system refused.
#define HOST_CORE1_PERCENT 80
bool host_core1_take(unsigned percent);
size_t host_heap_free(void);
// The cartridge buffer every game uses, reserved once at start (host_rom_arena_init, before anything else is allocated,
// so a 32 MB game always finds it): mGBA's romBuffer for a Game Boy game, armGBA's offered ROM for a GBA one
// (pd_armgba_host.romBuffer). armGBA frees it when a game closes: that free is skipped (host.c's __wrap_free).
bool host_rom_arena_init(void);
void* host_rom_arena(size_t size);

#endif
