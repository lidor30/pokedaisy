// NUZLOCKE: the app's tracker (companion/data/Nuzlocke.kt) in C. A tracker only, it never touches the game or the save.
// The first wild Pokémon met in each map section is that area's one encounter (caught, or missed for good), and a Pokémon
// that faints is out of the run, even revived. Fed from the companion's samples (pd_nuz_feed); kept per game in the app's
// own text format (pd_nuz_encode / pd_nuz_decode).
#ifndef PD_NUZLOCKE_H
#define PD_NUZLOCKE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PD_NUZ_ENCOUNTERS 160
#define PD_NUZ_MONS 160
#define PD_NUZ_NAME 32

enum pd_nuz_outcome { PD_NUZ_NOW, PD_NUZ_CAUGHT, PD_NUZ_MISSED };

struct pd_nuz_encounter {
    int area; // the map section id
    char areaName[PD_NUZ_NAME];
    int species;
    enum pd_nuz_outcome outcome;
};

// A Pokémon of the run, by its personality: [areaName] where it joined ("" = in the party when the run started).
struct pd_nuz_mon {
    uint32_t personality;
    int species, level, diedLevel;
    char name[PD_NUZ_NAME], areaName[PD_NUZ_NAME], diedIn[PD_NUZ_NAME];
    bool dead;
};

struct pd_nuz_run {
    struct pd_nuz_encounter enc[PD_NUZ_ENCOUNTERS];
    int encCount;
    struct pd_nuz_mon mons[PD_NUZ_MONS];
    int monCount;
    bool dupes;    // DUPES CLAUSE: a species already caught doesn't use up an area
    bool warnings; // notices on the game screen
};

// A party Pokémon as the tracker needs it.
struct pd_nuz_party {
    uint32_t personality;
    int species, level, hp, maxHp;
    bool egg;
    char name[PD_NUZ_NAME];
};

// One companion sample, cut down to what the tracker reads (NuzlockeSample).
struct pd_nuz_sample {
    bool inBattle;
    int trainer; // -1 = the battle's type wasn't read (no encounter counted), 0 wild, 1 trainer
    int area;
    char areaName[PD_NUZ_NAME];
    int foeSpecies; // 0 = no foe read yet
    int foeDex;     // its national number (what the caught flags go by)
    char foeName[PD_NUZ_NAME];
    struct pd_nuz_party party[6];
    int partyCount;
    bool menuUp; // the battle's menu is up, so the foe read is this battle's
    // The Pokédex's caught flag for a national number: -1 = not read.
    int (*caught)(void* ctx, int dex);
    void* ctx;
};

#define PD_NUZ_EVENTS 8
struct pd_nuz_events {
    char text[PD_NUZ_EVENTS][64];
    int count;
};

struct pd_nuz_tracker {
    struct pd_nuz_run run;
    bool wasInBattle, battleJudged;
    int pendingArea; // -1 = none
    int pendingSpecies, pendingDex, settleLeft, battleSamples;
    char pendingFoe[PD_NUZ_NAME];
    uint32_t partyBefore[6];
    int partyBeforeCount;
    int caughtBefore; // pendingDex's caught flag before the battle: -1 unknown
    struct { uint32_t personality; int zeroHp, lastHp; } hp[12];
    int hpCount;
};

void pd_nuz_reset(struct pd_nuz_tracker* t, const struct pd_nuz_run* run);
// A sample: what it changed goes into t->run (true when it did), the notices into [events] (even with warnings off:
// the host decides).
bool pd_nuz_feed(struct pd_nuz_tracker* t, const struct pd_nuz_sample* s, struct pd_nuz_events* events);

const struct pd_nuz_encounter* pd_nuz_encounter_at(const struct pd_nuz_run* r, int area);
int pd_nuz_alive(const struct pd_nuz_run* r);
int pd_nuz_dead(const struct pd_nuz_run* r);

// The tab's changes: an encounter's outcome set by hand (-1 clears it), a Pokémon marked dead or alive, NEW RUN.
void pd_nuz_set_outcome(struct pd_nuz_run* r, int area, int outcome);
void pd_nuz_set_dead(struct pd_nuz_run* r, uint32_t personality, bool dead, const char* where);
void pd_nuz_new_run(struct pd_nuz_run* r);

// The app's text: "v1", "rules", "enc" and "mon" lines, tab-separated. Returns the length written (0 = no room).
size_t pd_nuz_encode(const struct pd_nuz_run* r, char* out, size_t len);
void pd_nuz_decode(struct pd_nuz_run* r, const char* text);

#endif
