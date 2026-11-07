// Randomized retail (the app's RandomizedRetail): English FireRed rev 0 / 1 and Emerald as Universal Pokemon Randomizer ZX
// leaves them. Bytes [0, end) with the species names folded to capitals and every site the randomizer may write zeroed hash
// to sha1: such a ROM is retail's code, RAM and table addresses with the randomizer's data in them, so it runs on retail's
// config and what the randomizer changed is read from the ROM (types, moves, the type chart, the GUIDE's items, trades and
// gifts). The tables are pd_randomized_gen.c (3ds/tools/gen_randomized.py, from RandomizedRetailGen.kt).
#ifndef PD_RANDOMIZED_H
#define PD_RANDOMIZED_H

#include <stdbool.h>
#include <stdint.h>

struct pd_randomized {
    const char* code;
    int rev;
    uint32_t end;
    const char* sha1;
    const char* retailSha1; // the retail cart it is
    uint32_t baseStats, battleMoves, typeChart, trades; // gBaseStats, gBattleMoves, gTypeEffectiveness, sIngameTrades
    const uint32_t (*mask)[2]; // [start, end), merged
    int maskCount;
    const uint32_t (*fold)[2]; // the species names
    int foldCount;
    const uint32_t (*items)[2]; // the GUIDE's flag -> the ROM offset of its item ball's / hidden item's item id
    int itemCount;
    const uint16_t (*trades_)[3]; // retail's given, wanted -> the trade's row
    int tradeCount;
    const uint32_t (*statics)[2]; // retail's species -> the ROM offset of the gift's / static's species
    int staticCount;
};
extern const struct pd_randomized pd_randomized_roms[];
extern const int pd_randomized_rom_count;

struct pd_game;
struct pd_area_thing;
// The randomized retail ROM g's cart is (by code, revision, size and the masked hash), or NULL. Reads the whole ROM.
const struct pd_randomized* pd_randomized_match(const struct pd_game* g);
// What the randomizer changed, read from the running ROM (g->randomized set): a species' types, a move's type and power, one
// attacking type's multiplier on one defending type (100 = neutral). false = not randomized (read retail's).
bool pd_randomized_types(const struct pd_game* g, int species, int* t1, int* t2);
bool pd_randomized_move(const struct pd_game* g, int move, int* type, int* power);
bool pd_randomized_pct(const struct pd_game* g, int atk, int def, int* pct);
// An area thing of the GUIDE as this ROM has it (the app's randomizedAreas): t itself when not randomized, out with the
// ROM's item / species, or NULL to leave it out (a site the randomizer may have moved that isn't known).
const struct pd_area_thing* pd_randomized_area(const struct pd_game* g, const struct pd_area_thing* t, struct pd_area_thing* out);

#endif
