// The GUIDE's data (pd_guide_gen.c, from the app's Guide*.kt by
// 3ds/tools/gen_guide.py) and what's read live for it: the next bosses'
// teams (gTrainers + learnsets), the current map's wild POKéMON
// (gWildMonHeaders) and what its area holds (GuideAreas*Gen.kt) - the app's
// GuideRom.kt / GuideAreas.kt.
#ifndef PD_GUIDE_H
#define PD_GUIDE_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_snapshot.h"

enum pd_have_kind { PD_HAVE_NONE, PD_HAVE_FLAG, PD_HAVE_ITEM, PD_HAVE_CAUGHT };

struct pd_guide_entry {
    const char* title;
    const char* answer; // "" = nothing to reveal
    const char* hint;   // NULL = straight to the answer
    uint8_t haveKind;   // enum pd_have_kind: what marks it done
    uint16_t have[3];   // the flag / item / national numbers (any one)
    const char* areas;  // area keys joined by '|' (areaKey: "SSANNE"), NULL = none
};

struct pd_guide_section {
    const char* heading;
    const char* note;
    const struct pd_guide_entry* entries;
    int count;
};

struct pd_guide_page {
    const char* title;
    const struct pd_guide_section* sections;
    int count;
};

enum pd_boss_kind {
    PD_BOSS_FIXED,    // trainer a
    PD_BOSS_LEAGUE,   // a, or rematch b once the League has its rematch teams
    PD_BOSS_CHAMPION, // the rival: league(a, b) + an offset by the starter
    PD_BOSS_BY_FLAGS, // alts' first {flag, trainer} whose flag is set, else a (Emerald Legacy's BRAWLY)
    PD_BOSS_TEAMVAR,  // Gen 1: alts' {value, team} for the champion's team byte (var 0x4000); no match = every variant
    PD_BOSS_BY_COUNT, // countTeams[how many of alts' flags are set] (SoulGold / Heart and Soul's middle gyms), alts 0-terminated
    PD_BOSS_VAR_ADD,  // a + the var addVar, held to 0..addMax (Too Many Types 2's rival by the starter)
    PD_BOSS_VAR_IS,   // b while the var addVar is addMax, else a (Dreamstone's difficulty)
};

// A team the game picks at run time (difficulty, a double battle's partners): shown under its label. {NULL, 0} ends.
struct pd_variant {
    const char* label;
    uint32_t trainer; // a trainer id; bits 16+ pick an alternate table / the HARD party
};

struct pd_boss {
    const char* title;
    const char* where;
    uint16_t doneFlag;
    uint8_t kind;
    uint32_t a, b; // trainer ids; bits 16+ pick an alternate table (as pd_variant.trainer)
    const uint16_t* alts; // PD_BOSS_BY_FLAGS: {flag, trainer} pairs, 0-terminated
    const struct pd_variant* variants; // when set, one team per entry instead of the kind's trainer
    const uint16_t* doneAny; // more flags that also mean done (an Elite Four member once the game is cleared), 0-terminated
    // Also done once the var doneVar reaches doneVarMin (a League's progress var); doneVar 0 = none.
    uint16_t doneVar, doneVarMin;
    const uint32_t* countTeams; // PD_BOSS_BY_COUNT's teams
    uint16_t addVar;
    uint8_t addMax;
    // The picked trainer's teams in each table, all shown: label i reads id | i << 16 (NORMAL / HARD); NULL-terminated.
    const char* const* pickLabels;
    uint8_t region;  // the campaign it's in (Quetzal's Hoenn 0 / Johto 1 / Kanto 2); see pd_guide.regionOf
    const uint16_t* doneAll; // done once every one of these flags is set too (Crystal's RED), 0-terminated
};

struct pd_guide {
    const struct pd_guide_page* pages;
    int pageCount;
    const struct pd_boss* bosses;
    int bossCount;
    bool verified;
    uint16_t leagueRematchFlag; // FireRed's FLAG_SYS_CAN_LINK_WITH_RS
    uint16_t starterVar;        // FireRed's VAR_STARTER_MON
    // Several campaigns (the app's GameGuide.bossesFor): the region of a map group, and per region the order the
    // regions' bosses come in (the player's own first). NULL = one list in order.
    int (*regionOf)(int mapGroup);
    const uint8_t (*regionOrder)[3];
};

// Whether the boss is beaten on this save (the app's Boss.isDone).
bool pd_boss_done(const struct pd_boss* b, const struct pd_snapshot* s);
// The teams a boss shows (its variants, or pickLabels' tables of the picked trainer); NULL = the one picked team.
const struct pd_variant* pd_boss_variants(const struct pd_guide* gd, const struct pd_boss* b, const struct pd_snapshot* s);
// The bosses in the order NEXT BOSS walks them for this save (regions); returns how many indexes were written.
int pd_boss_order(const struct pd_guide* gd, const struct pd_snapshot* s, int* out, int max);

// A Game Boy boss team, embedded (Gen 1's GuideGen1Gen.kt lines); a table ends in n == 0. var: the champion's team byte
// value that picks it (-1 = always).
struct pd_gb_mon {
    uint16_t species, item; // species = the national Dex number
    uint8_t level, moves[4];
};
struct pd_gb_team {
    int var, n;
    struct pd_gb_mon mons[6];
};
extern const struct pd_gb_team pd_gb_teams_yellow[], pd_gb_teams_red[], pd_gb_teams_blue[];
// Gen 1's wild Pokémon by wCurMap (GuideGen1Gen.kt's WILD tables, the app's Gen1World): grass / cave and surfing slots
// (level, species; WildMonEncounterSlotChances' odds by slot), the super rod's (percent of a cast, species, level); the old
// and good rods are the same everywhere there's water. map -1 ends.
struct pd_gb_wild {
    int16_t map;
    uint8_t grassCount, waterCount, superCount;
    uint8_t grass[10][2], water[10][2], super[10][3];
};
struct pd_gb_fish { int count; uint8_t rows[10][3]; };
extern const struct pd_gb_wild pd_gb_wild_yellow[], pd_gb_wild_red[], pd_gb_wild_blue[];
extern const struct pd_gb_fish pd_gb_old_rod_yellow, pd_gb_good_rod_yellow, pd_gb_old_rod_red, pd_gb_good_rod_red,
    pd_gb_old_rod_blue, pd_gb_good_rod_blue;
// Crystal (GuideCrystalGen.kt, the app's CrystalWorld), by wMapGroup << 8 | wMapNumber: grass / cave slots per time of day
// (morning, day, night: 7 each, rates apiece), surfing (3 slots, levels up to +4), the map's fish group and rock smash
// set; the fish groups' OLD / GOOD / SUPER ROD rows (odds of 256, species, level; species 0 = a time-of-day group whose
// day / night Pokémon pd_gb_time_fish_crystal holds, by the level byte), the rock sets' rows (percent, species, level).
struct pd_gb2_wild {
    int32_t key;
    uint8_t rates[3], grass[21][2];
    uint8_t waterRate, water[3][2];
    int8_t fishGroup, rockSet;
};
struct pd_gb2_fish { int group; uint8_t rows[3][4][3]; uint8_t count[3]; };
struct pd_gb2_rocks { int set, count; uint8_t rows[6][3]; };
extern const struct pd_gb2_wild pd_gb_wild_crystal[];
extern const struct pd_gb2_fish pd_gb_fish_crystal[];
extern const struct pd_gb2_rocks pd_gb_rocks_crystal[];
extern const uint8_t pd_gb_time_fish_crystal[][4];
extern const int pd_gb_time_fish_crystal_count;
extern const uint16_t pd_gb_landmarks_crystal[][2];
extern const int pd_gb_landmarks_crystal_count;
extern const struct pd_gb_team pd_gb_teams_crystal[];

// The app's gen1Area: the first wCurMap with the same town map name (a town's houses count with the town).
extern const uint8_t pd_gb_gen1_area[256];
extern const struct pd_guide pd_guide_gb_yellow, pd_guide_gb_red, pd_guide_gb_blue, pd_guide_gb_crystal;

extern const struct pd_guide pd_guide_firered, pd_guide_leafgreen, pd_guide_emerald, pd_guide_ruby, pd_guide_sapphire,
    pd_guide_emerald_legacy,
    pd_guide_rocket_edition, pd_guide_ash_gray, pd_guide_gs_chronicles, pd_guide_radical_red, pd_guide_orange_islands,
    pd_guide_odyssey, pd_guide_sword_shield, pd_guide_unbound, pd_guide_gaia, pd_guide_ghost_grey, pd_guide_celia, pd_guide_imperium, pd_guide_recharged_yellow, pd_guide_recharged_emerald,
    pd_guide_amethyst, pd_guide_soulgold, pd_guide_quetzal_v084, pd_guide_quetzal, pd_guide_tmt2, pd_guide_seaglass, pd_guide_lazarus, pd_guide_heart_and_soul, pd_guide_glazed, pd_guide_mariomon, pd_guide_dreamstone, pd_guide_wishes, pd_guide_team_rocket, pd_guide_elite_redux, pd_guide_ultra_violet;

enum pd_area_kind { PD_AREA_ITEM, PD_AREA_HIDDEN, PD_AREA_GIFT, PD_AREA_KEY, PD_AREA_MON, PD_AREA_EGG, PD_AREA_TRADE };

struct pd_area_thing {
    uint16_t mapsec;
    uint8_t kind;      // enum pd_area_kind
    uint16_t id;       // an item, or the species you get
    uint16_t flag;     // set once it's done (0 = none known)
    uint16_t qty;
    uint16_t wants;    // TRADE: the species they want
    const char* where; // the sub-map ("" = the area's main map)
};

extern const struct pd_area_thing pd_areas_firered[], pd_areas_leafgreen[], pd_areas_emerald[], pd_areas_ruby_sapphire[],
    pd_areas_emerald_legacy[], pd_areas_rocket_edition[], pd_areas_ash_gray[], pd_areas_gs_chronicles[], pd_areas_radical_red[], pd_areas_orange_islands[],
    pd_areas_odyssey[], pd_areas_sword_shield[], pd_areas_unbound[], pd_areas_gaia[], pd_areas_ghost_grey[], pd_areas_celia[],
    pd_areas_amethyst[], pd_areas_recharged_yellow[], pd_areas_heart_and_soul[], pd_areas_ultra_violet[], pd_areas_amethyst_v141[], pd_areas_recharged_yellow_v180[], pd_areas_recharged_yellow_v190[], pd_areas_recharged_yellow_v194[];
extern const int pd_areas_firered_count, pd_areas_leafgreen_count, pd_areas_emerald_count, pd_areas_ruby_sapphire_count,
    pd_areas_emerald_legacy_count, pd_areas_rocket_edition_count, pd_areas_ash_gray_count, pd_areas_gs_chronicles_count, pd_areas_radical_red_count, pd_areas_orange_islands_count,
    pd_areas_odyssey_count, pd_areas_sword_shield_count, pd_areas_unbound_count, pd_areas_gaia_count, pd_areas_ghost_grey_count, pd_areas_celia_count,
    pd_areas_amethyst_count, pd_areas_recharged_yellow_count, pd_areas_heart_and_soul_count, pd_areas_ultra_violet_count, pd_areas_amethyst_v141_count, pd_areas_recharged_yellow_v180_count, pd_areas_recharged_yellow_v190_count, pd_areas_recharged_yellow_v194_count;

// Quetzal's campaigns: a map group's region (34-35 Johto, 36-39 Kanto / Sevii, else Hoenn) and each region's order.
int pd_quetzal_region(int mapGroup);
extern const uint8_t pd_quetzal_order[3][3];

extern const struct pd_area_thing pd_areas_gb_yellow[], pd_areas_gb_red[], pd_areas_gb_blue[];
extern const int pd_areas_gb_yellow_count, pd_areas_gb_red_count, pd_areas_gb_blue_count;
extern const struct pd_area_thing pd_areas_gb_crystal[];
extern const int pd_areas_gb_crystal_count;

// The guide and area data for g (NULL / 0 if none).
const struct pd_guide* pd_guide_for(const struct pd_game* g);
const struct pd_area_thing* pd_areas_for(const struct pd_game* g, int* count);

// Whether a Have check holds for the save.
bool pd_guide_has(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide_entry* e);
// The app's areaKey(): upper case, É -> E, letters and digits only.
void pd_area_key(const char* name, char* out, int len);
bool pd_entry_in_area(const struct pd_guide_entry* e, const char* key);
// Whether an area thing is done (the app's areaDone).
bool pd_area_done(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_area_thing* t);

// Whether g's ROM has the GUIDE's live tables (the probe trainer's name).
bool pd_guide_rom_ok(const struct pd_game* g);

#define PD_TEAM_MAX 6
struct pd_team_mon {
    int species, level, item;
    int moves[4];
};
// The boss's trainer id for this save, and its team from the ROM.
int pd_boss_trainer(const struct pd_guide* gd, const struct pd_boss* b, const struct pd_snapshot* s);
int pd_trainer_team(const struct pd_game* g, int trainer, struct pd_team_mon* out);
// The species' types from the ROM's base stats.
void pd_species_types(const struct pd_game* g, int species, int* t1, int* t2);

// The wild POKéMON of the player's map, merged per species (the app's
// merge(): min of mins, max of maxes, odds summed), highest odds first.
#define PD_WILD_METHODS 6 // grass / cave, surfing, rock smash, old / good / super rod
#define PD_WILD_MAX 12
struct pd_wild {
    int species, minLv, maxLv, pct;
};
struct pd_wild_set {
    int count[PD_WILD_METHODS];
    struct pd_wild mons[PD_WILD_METHODS][PD_WILD_MAX];
};
bool pd_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out);
const char* pd_wild_method_name(int method);

#endif
