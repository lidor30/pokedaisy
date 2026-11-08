// The DEX entry's sections (DexDetails.kt) on the games that keep vanilla's tables: the family's evolutions, what it
// learns, where it's found. Read from the ROM.
#ifndef PD_DEX_DETAILS_H
#define PD_DEX_DETAILS_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_dex.h"

#define PD_EVO_MAX 12
#define PD_LEVEL_MOVES_MAX 40
#define PD_TM_MOVES_MAX 64
#define PD_SPOTS_MAX 40

struct pd_evo {
    int from, to, method, param;
};

enum pd_spot_method { PD_SPOT_GRASS, PD_SPOT_SURF, PD_SPOT_ROCK, PD_SPOT_FISH };

struct pd_spot {
    int mapsec, method, minLevel, maxLevel, pct;
};

// [species]' whole family, both ways, starting at its first stage.
int pd_dex_family(const struct pd_game* g, int species, struct pd_evo* out, int max);
void pd_dex_evo_text(const struct pd_game* g, const struct pd_evo* e, char* out, size_t len);
int pd_dex_level_moves(const struct pd_game* g, int species, struct pd_level_move* out, int max);
int pd_dex_tm_moves(const struct pd_game* g, int species, uint16_t* out, int max);
int pd_dex_spots(const struct pd_game* g, int species, struct pd_spot* out, int max);
bool pd_dex_has_details(const struct pd_game* g);

#endif
