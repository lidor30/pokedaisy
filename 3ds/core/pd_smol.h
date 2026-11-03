// pokeemerald-expansion's "smol" graphics compression (the app's Smol.kt): tANS entropy coding plus LZ-style
// instructions over u16 symbols. Newer expansion hacks store their dex pictures this way instead of LZ77.
#ifndef PD_SMOL_H
#define PD_SMOL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// Whether src starts with a smol tile-data header (modes 1..6; mode 0 is LZ77, whose id byte is 0x10).
bool pd_is_smol(const uint8_t* src, size_t avail);
// Decode into out; returns the decoded byte count, or 0 if it isn't valid smol data or doesn't fit outCap.
size_t pd_smol(const uint8_t* src, size_t avail, uint8_t* out, size_t outCap);

// Mode 8 (a tilemap: running sums of the decoded u16s) into out; returns the byte count or 0.
size_t pd_smol_tilemap(const uint8_t* src, size_t avail, uint8_t* out, size_t outCap);

#endif
