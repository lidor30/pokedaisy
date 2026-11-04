// The POKéDEX's ROM side (the app's Pokedex.kt): the species <-> national
// number tables, dex entries, base stats and the front sprite, read from the
// player's ROM; the save side (seen / caught) is in the snapshot.
#ifndef PD_DEX_H
#define PD_DEX_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"

#define PD_SPRITE 64

struct pd_dex_entry {
    int national, species;
    char category[24]; // "SEED"
    int heightDm, weightHg;
    int heightIn, weightLbs10; // a Game Boy cart's own feet / inches and pounds x 10, -1 = none
    char description[320];
    int type1, type2;
    int stats[6];  // HP, ATK, DEF, SP.ATK, SP.DEF, SPEED (the app's order); Gen 1: HP, ATK, DEF, SPEED, SPECIAL
    int statCount;
    char ability1[16], ability2[16];
};

// Whether g's ROM has this port's POKéDEX tables (the app's probe: entry 1
// is SEED, 7 dm, 69 hg; species 1 is national 1).
bool pd_dex_available(const struct pd_game* g);
// The species for a national number (0 = none), and back.
int pd_dex_species(const struct pd_game* g, int national);
int pd_dex_national(const struct pd_game* g, int species);
// The national number at regional position i (1-based); 0 = none.
int pd_dex_regional(const struct pd_game* g, int i);
bool pd_dex_entry(const struct pd_game* g, int national, struct pd_dex_entry* out);
// The front sprite, 64x64 0xRRGGBB with PD_MAP_CLEAR-style see-through
// (0xFF000000); false if it can't be decoded.
bool pd_dex_sprite(const struct pd_game* g, int species, uint32_t* out);
// "2'04\"" and "15.2 lbs." - the app's imperial conversions.
void pd_dex_height(int dm, char* buf, int len);
void pd_dex_weight(int hg, char* buf, int len);

// HT and WT as the game prints them (formatDexHeight / formatDexWeight).
void pd_dex_measures(const struct pd_game* g, const struct pd_dex_entry* e, char* height, int heightLen, char* weight, int weightLen);
// How many entries a Game Boy cart's dex lists (0 on the GBA, whose lists go by its tables), and what follows the category.
int pd_dex_gb_count(const struct pd_game* g);
const char* pd_dex_category_suffix(const struct pd_game* g);

// The species' growth rate (gBaseStats +19: 0 medium fast ... 5 slow), -1 where the ROM's table isn't known.
int pd_growth_rate(const struct pd_game* g, int species);
// EXP at a level by growth rate (the games' gExperienceTables).
long pd_exp_at(int rate, int level);

#endif
