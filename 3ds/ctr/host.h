// The 3DS itself, besides the screens: its log, its model, its memory (host.c).
#ifndef PD_CTR_HOST_H
#define PD_CTR_HOST_H

#include <stdbool.h>
#include <stddef.h>

// sdmc:/pokedaisy/log.txt, the last run's kept as log-prev.txt: what to attach to a bug report.
void host_log_open(const char* path);
void host_log(const char* format, ...) __attribute__((format(printf, 1, 2)));
void host_log_close(void);
// pd_armgba_host.log.
void host_emu_log(void* ctx, const char* line);

// Azahar and Citra (they answer a system info query a console refuses).
bool host_is_emulator(void);
bool host_is_new_3ds(void);
size_t host_heap_free(void);
// The cartridge buffer every game uses, reserved once at start (host_rom_arena_init, before anything else is allocated,
// so a 32 MB game always finds it): mGBA's romBuffer for a Game Boy game, armGBA's offered ROM for a GBA one
// (pd_armgba_host.romBuffer). armGBA frees it when a game closes: that free is skipped (host.c's __wrap_free).
bool host_rom_arena_init(void);
void* host_rom_arena(size_t size);

#endif
