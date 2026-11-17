// GAMEPLAY's game patches: REUSABLE TMS (the app's ReusableTms.kt) and INSTANT TEXT (InstantText.kt), found in the
// game's own code, so retail in every language and the hacks that kept that code get them, and nothing else does.
#ifndef PD_ROM_OPTIONS_H
#define PD_ROM_OPTIONS_H

#include <stddef.h>
#include <stdint.h>

// [value] over the ROM's [expect] at bus address [addr], [len] bytes (2 or 4).
struct pd_rom_write {
    uint32_t addr;
    uint8_t expect[4], value[4];
    int len;
};

#define PD_TM_SITES_MAX 3
#define PD_TEXT_WRITES_MAX 12

// REUSABLE TMS: the `bhi` before each RemoveBagItem of a TM turned into a `b` - all of the engine's sites (three in the
// FireRed engine, one in Emerald's / Ruby and Sapphire's), each calling the same function, or none. A pass over the whole
// ROM: the host keeps the answer per ROM.
int pd_reusable_tm_find(const uint8_t* rom, size_t size, struct pd_rom_write* out);

// The same writes again from the sites' addresses (kept per ROM from an earlier pass); 0 if one doesn't hold its code.
int pd_reusable_tm_at(const uint8_t* rom, size_t size, const uint32_t* sites, int n, struct pd_rom_write* out);

// INSTANT TEXT: RenderFont rewritten in its own 40 bytes (the FireRed / Emerald engines), or Ruby and Sapphire's text
// delay turned into a `b` back to their print. In the ROM's first 64 KB; 0 = not this code.
int pd_instant_text_find(const uint8_t* rom, size_t size, struct pd_rom_write* out);

#endif
