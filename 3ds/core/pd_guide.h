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
};

struct pd_boss {
    const char* title;
    const char* where;
    uint16_t doneFlag;
    uint8_t kind;
    uint16_t a, b;
    const uint16_t* alts; // PD_BOSS_BY_FLAGS: {flag, trainer} pairs, 0-terminated
};

struct pd_guide {
    const struct pd_guide_page* pages;
    int pageCount;
    const struct pd_boss* bosses;
    int bossCount;
    bool verified;
    uint16_t leagueRematchFlag; // FireRed's FLAG_SYS_CAN_LINK_WITH_RS
    uint16_t starterVar;        // FireRed's VAR_STARTER_MON
};

extern const struct pd_guide pd_guide_firered, pd_guide_leafgreen, pd_guide_emerald, pd_guide_ruby, pd_guide_sapphire,
    pd_guide_emerald_legacy;

enum pd_area_kind { PD_AREA_ITEM, PD_AREA_HIDDEN, PD_AREA_GIFT, PD_AREA_KEY, PD_AREA_MON, PD_AREA_EGG, PD_AREA_TRADE };

struct pd_area_thing {
    uint16_t mapsec;
    uint8_t kind;      // enum pd_area_kind
    uint16_t id;       // an item, or the species you get
    uint16_t flag;     // set once it's done (0 = none known)
    uint8_t qty;
    uint16_t wants;    // TRADE: the species they want
    const char* where; // the sub-map ("" = the area's main map)
};

extern const struct pd_area_thing pd_areas_firered[], pd_areas_leafgreen[], pd_areas_emerald[], pd_areas_ruby_sapphire[],
    pd_areas_emerald_legacy[];
extern const int pd_areas_firered_count, pd_areas_leafgreen_count, pd_areas_emerald_count, pd_areas_ruby_sapphire_count,
    pd_areas_emerald_legacy_count;

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
