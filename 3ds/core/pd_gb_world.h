// The Game Boy guides' worlds: the app's Gen1World, CrystalWorld and PolishedCrystalWorld in C. The tables come from the
// app's generated Kotlin (pd_gb_world_gen.c, 3ds/tools/gen_gb_guide.py).
#ifndef PD_GB_WORLD_H
#define PD_GB_WORLD_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_guide.h"
#include "pd_snapshot.h"

struct pd_gbw_slot {
    uint16_t species;
    uint8_t level;
    uint16_t odds; // percent x 256
};

struct pd_gbw_rows {
    const struct pd_gbw_slot* rows;
    int count;
};

struct pd_gbw_wild_map {
    uint16_t key; // Gen 1: wCurMap; Gen 2: group << 8 | number
    uint8_t rates[3]; // grass per time of day, 0 = none
    const struct pd_gbw_slot* grass;
    uint8_t waterRate;
    const struct pd_gbw_slot* water;
    int16_t fish, tree, rock; // indexes, -1 = none
};

struct pd_gbw_fish {
    struct pd_gbw_rows rods[3];
};

struct pd_gbw_trees {
    struct pd_gbw_rows common, rare;
};

// Crystal's time-of-day fish: a rod slot of species 0 names one by its level.
struct pd_gbw_time_fish {
    uint16_t daySpecies;
    uint8_t dayLevel;
    uint16_t nightSpecies;
    uint8_t nightLevel;
};

struct pd_gbw_mon {
    uint16_t species;
    uint8_t level;
    uint16_t item;
    uint16_t moves[4];
};

struct pd_gbw_team {
    const struct pd_gbw_mon* mons;
    int count;
    const struct pd_gbw_mon* faithful; // Polished Crystal's faithful builds' own, NULL = the same
    int faithfulCount;
};

enum pd_gbw_done {
    PD_GBW_DONE_ANY,     // flag a or b
    PD_GBW_DONE_BOTH,    // flags a and b
    PD_GBW_DONE_REMATCH, // flag a, or all 16 badges and flag b
};

struct pd_gbw_boss {
    const char* title;
    const char* where;
    uint8_t rule;
    uint32_t a, b;
    uint16_t team;
    uint8_t teams;      // more than one: a team per value of the champion's var, from [firstValue]
    uint8_t firstValue;
};

struct pd_gbw_bank {
    uint32_t addr;
    int bytes;
};

struct pd_gbw_world {
    uint8_t gen;
    const struct pd_gbw_wild_map* maps;
    int mapsCount;
    const struct pd_gbw_fish* fish;
    int fishCount;
    const struct pd_gbw_trees* trees;
    int treesCount;
    const struct pd_gbw_time_fish* time;
    int timeCount;
    const struct pd_gbw_team* teams;
    int teamsCount;
    const struct pd_gbw_boss* bosses;
    int bossesCount;
    int grassSlots, times, surfBoost;
    uint32_t eventFlags;
    int eventBytes;
    struct pd_gbw_bank banks[4];
    int bankCount;
    uint32_t teamVar, timeOfDay;
    int dayIndex, nightIndex, eveningIndex; // eveningIndex 0 = none
    const uint8_t* badgeLevels;
    int badgeLevelCount, levelFromBadges;
    uint16_t shoreDay, shoreNight;
};

extern const struct pd_gbw_world pd_gbw_polished;
// The running game's world, NULL for every game but Polished Crystal.
const struct pd_gbw_world* pd_gbw_for(const struct pd_game* g);
struct pd_gb_config;

// The flags, badges, clock and place the guide reads (readPolishedProgress), for a config with a world.
void pd_gbw_read(struct pd_snapshot* s, const struct pd_gb_config* c, pd_read_fn read, void* ctx);
// The map's wild POKéMON, as the game picks them for the clock and badges held.
bool pd_gbw_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out);
// The world's bosses in order: whether one's beaten, and its teams.
int pd_gbw_boss_count(const struct pd_game* g);
const struct pd_gbw_boss* pd_gbw_boss_at(const struct pd_game* g, int i);
bool pd_gbw_boss_done(const struct pd_snapshot* s, const struct pd_gbw_boss* b);
// How many teams to show (one, unless the save hasn't settled the champion's); team [k]'s members; "WITH X"'s X.
int pd_gbw_boss_teams(const struct pd_snapshot* s, const struct pd_gbw_boss* b);
int pd_gbw_boss_team(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_gbw_boss* b, int k, struct pd_team_mon* out);
// The clock HERE's lists are for: "MORNING" ... , NULL where the game has none.
const char* pd_gbw_clock(const struct pd_game* g, const struct pd_snapshot* s);

#endif
