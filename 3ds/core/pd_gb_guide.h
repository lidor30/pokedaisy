// The Game Boy guides' worlds: the app's Gen1World, CrystalWorld and PolishedCrystalWorld in C. The tables come from the
// app's generated Kotlin (pd_gb_guide_gen.c, 3ds/tools/gen_gb_guide.py).
#ifndef PD_GB_GUIDE_H
#define PD_GB_GUIDE_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_guide.h"
#include "pd_snapshot.h"

struct pd_gb_slot {
    uint16_t species;
    uint8_t level;
    uint16_t odds; // percent x 256
};

struct pd_gb_rows {
    const struct pd_gb_slot* rows;
    int count;
};

struct pd_gb_wild_map {
    uint16_t key; // Gen 1: wCurMap; Gen 2: group << 8 | number
    uint8_t rates[3]; // grass per time of day, 0 = none
    const struct pd_gb_slot* grass;
    uint8_t waterRate;
    const struct pd_gb_slot* water;
    int16_t fish, tree, rock; // indexes, -1 = none
};

struct pd_gb_fish {
    struct pd_gb_rows rods[3];
};

struct pd_gb_trees {
    struct pd_gb_rows common, rare;
};

// Crystal's time-of-day fish: a rod slot of species 0 names one by its level.
struct pd_gb_time_fish {
    uint16_t daySpecies;
    uint8_t dayLevel;
    uint16_t nightSpecies;
    uint8_t nightLevel;
};

struct pd_gb_mon {
    uint16_t species;
    uint8_t level;
    uint16_t item;
    uint16_t moves[4];
};

struct pd_gb_team {
    const struct pd_gb_mon* mons;
    int count;
    const struct pd_gb_mon* faithful; // Polished Crystal's faithful builds' own, NULL = the same
    int faithfulCount;
};

enum pd_gb_done {
    PD_GB_DONE_ANY,     // flag a or b
    PD_GB_DONE_BOTH,    // flags a and b
    PD_GB_DONE_REMATCH, // flag a, or all 16 badges and flag b
};

struct pd_gb_boss {
    const char* title;
    const char* where;
    uint8_t rule;
    uint32_t a, b;
    uint16_t team;
    uint8_t teams;      // more than one: a team per value of the champion's var, from [firstValue]
    uint8_t firstValue;
};

struct pd_gb_bank {
    uint32_t addr;
    int bytes;
};

struct pd_gb_guide {
    uint8_t gen;
    const struct pd_gb_wild_map* maps;
    int mapsCount;
    const struct pd_gb_fish* fish;
    int fishCount;
    const struct pd_gb_trees* trees;
    int treesCount;
    const struct pd_gb_time_fish* time;
    int timeCount;
    const struct pd_gb_team* teams;
    int teamsCount;
    const struct pd_gb_boss* bosses;
    int bossesCount;
    int grassSlots, times, surfBoost;
    uint32_t eventFlags;
    int eventBytes;
    struct pd_gb_bank banks[PD_FLAG_BANKS];
    int bankCount;
    uint32_t teamVar, timeOfDay;
    int dayIndex, nightIndex, eveningIndex; // eveningIndex 0 = none
    const uint8_t* badgeLevels;
    int badgeLevelCount, levelFromBadges;
    uint16_t shoreDay, shoreNight;
};

extern const struct pd_gb_guide pd_gb_guide_yellow, pd_gb_guide_red, pd_gb_guide_blue, pd_gb_guide_crystal, pd_gb_guide_polished;

// The flags, badges and clock the guide reads (readGen1Progress / readCrystalProgress / readPolishedProgress).
void pd_gb_guide_read(struct pd_snapshot* s, const struct pd_gb_config* c, pd_read_fn read, void* ctx);
// The map's wild POKéMON, as the game picks them for the clock and badges held.
bool pd_gb_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out);
// The world's bosses in order: whether one's beaten, and its teams.
int pd_gb_boss_count(const struct pd_game* g);
const struct pd_gb_boss* pd_gb_boss_at(const struct pd_game* g, int i);
bool pd_gb_boss_done(const struct pd_snapshot* s, const struct pd_gb_boss* b);
// How many teams to show (one, unless the save hasn't settled the champion's); team [k]'s members; "WITH X"'s X.
int pd_gb_boss_teams(const struct pd_snapshot* s, const struct pd_gb_boss* b);
int pd_gb_boss_team(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_gb_boss* b, int k, struct pd_team_mon* out);
// The clock HERE's lists are for: "MORNING" ... , NULL where the game has none.
const char* pd_gb_clock(const struct pd_game* g, const struct pd_snapshot* s);

#endif
