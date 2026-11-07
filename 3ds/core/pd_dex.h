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
    char description[240];
    int type1, type2;
    int stats[6]; // HP, ATK, DEF, SP.ATK, SP.DEF, SPEED (the app's order)
    char ability1[16], ability2[16];
    // A Game Boy page (Gen 1): the cart's own feet / inches and pounds x 10 (0 = from dm / hg), five stats (HP, ATTACK,
    // DEFENSE, SPEED, SPECIAL in stats[0..4]) and no abilities.
    int heightIn, weightLbs10;
    bool gen1Stats;
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

#endif
