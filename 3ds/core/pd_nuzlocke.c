// NUZLOCKE (pd_nuzlocke.h): companion/data/Nuzlocke.kt's NuzlockeRun and NuzlockeTracker, rule for rule.
#include "pd_nuzlocke.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FAINT_SAMPLES 2  // samples in a row at 0 HP before a faint counts (one torn read mustn't end a run)
#define SETTLE_SAMPLES 4 // samples after a battle before its encounter is settled
#define FOE_SAMPLES 4    // a battle's samples before its foe is trusted with no menu read

static void copy_name(char* to, const char* from) {
    snprintf(to, PD_NUZ_NAME, "%s", from ? from : "");
}

static void event(struct pd_nuz_events* e, const char* text) {
    if (e && e->count < PD_NUZ_EVENTS) snprintf(e->text[e->count++], sizeof(e->text[0]), "%.*s", (int)sizeof(e->text[0]) - 1, text);
}

const struct pd_nuz_encounter* pd_nuz_encounter_at(const struct pd_nuz_run* r, int area) {
    for (int i = 0; i < r->encCount; i++)
        if (r->enc[i].area == area) return &r->enc[i];
    return NULL;
}

static struct pd_nuz_mon* mon_of(struct pd_nuz_run* r, uint32_t personality) {
    if (!personality) return NULL;
    for (int i = 0; i < r->monCount; i++)
        if (r->mons[i].personality == personality) return &r->mons[i];
    return NULL;
}

int pd_nuz_alive(const struct pd_nuz_run* r) {
    int n = 0;
    for (int i = 0; i < r->monCount; i++) n += !r->mons[i].dead;
    return n;
}

int pd_nuz_dead(const struct pd_nuz_run* r) {
    return r->monCount - pd_nuz_alive(r);
}

void pd_nuz_reset(struct pd_nuz_tracker* t, const struct pd_nuz_run* run) {
    memset(t, 0, sizeof(*t));
    if (run) t->run = *run;
    else t->run.warnings = true;
    t->pendingArea = -1;
    t->caughtBefore = -1;
}

static int* hp_slot(struct pd_nuz_tracker* t, uint32_t personality, bool last) {
    for (int i = 0; i < t->hpCount; i++)
        if (t->hp[i].personality == personality) return last ? &t->hp[i].lastHp : &t->hp[i].zeroHp;
    if (t->hpCount == 12) memmove(&t->hp[0], &t->hp[1], sizeof(t->hp[0]) * 11), t->hpCount--;
    t->hp[t->hpCount].personality = personality;
    t->hp[t->hpCount].zeroHp = 0;
    t->hp[t->hpCount].lastHp = -1;
    t->hpCount++;
    return last ? &t->hp[t->hpCount - 1].lastHp : &t->hp[t->hpCount - 1].zeroHp;
}

static void settle(struct pd_nuz_tracker* t, int area, const struct pd_nuz_sample* s, struct pd_nuz_events* events, bool* changed) {
    const struct pd_nuz_party* joined = NULL;
    for (int i = 0; i < s->partyCount && !joined; i++) {
        const struct pd_nuz_party* p = &s->party[i];
        bool before = false;
        for (int k = 0; k < t->partyBeforeCount; k++) before |= t->partyBefore[k] == p->personality;
        if (p->personality && !before && p->species == t->pendingSpecies) joined = p;
    }
    int now = s->caught ? s->caught(s->ctx, t->pendingDex) : -1;
    bool toPc = t->caughtBefore == 0 && now == 1;
    enum pd_nuz_outcome outcome;
    if (joined || toPc) outcome = PD_NUZ_CAUGHT;
    else if (s->inBattle) return;
    else if (--t->settleLeft > 0) return;
    else outcome = PD_NUZ_MISSED;
    t->pendingArea = -1;
    // Changed on the tab meanwhile (cleared or set by hand): the player's word stands.
    struct pd_nuz_encounter* e = (struct pd_nuz_encounter*) pd_nuz_encounter_at(&t->run, area);
    if (!e || e->outcome != PD_NUZ_NOW) return;
    e->outcome = outcome;
    *changed = true;
    if (outcome == PD_NUZ_CAUGHT) {
        char text[96];
        snprintf(text, sizeof text, "%s joins the run", joined ? joined->name : t->pendingFoe);
        event(events, text);
        // The catch joined where it was met.
        struct pd_nuz_mon* m = joined ? mon_of(&t->run, joined->personality) : NULL;
        if (m) copy_name(m->areaName, e->areaName);
    }
}

bool pd_nuz_feed(struct pd_nuz_tracker* t, const struct pd_nuz_sample* s, struct pd_nuz_events* events) {
    // The title screen, a save not loaded yet: nothing to follow.
    if (s->partyCount <= 0) return false;
    bool changed = false;
    struct pd_nuz_run* r = &t->run;
    char text[96];
    // New faces join the run where they're first seen; the living keep their name and level up to date. The party the
    // run starts with has been along from the start (no area).
    const bool first = r->monCount == 0;
    for (int i = 0; i < s->partyCount; i++) {
        const struct pd_nuz_party* p = &s->party[i];
        if (!p->personality || !p->species || p->egg) continue;
        struct pd_nuz_mon* m = mon_of(r, p->personality);
        if (!m) {
            if (r->monCount == PD_NUZ_MONS) continue;
            m = &r->mons[r->monCount++];
            memset(m, 0, sizeof(*m));
            m->personality = p->personality;
            m->species = p->species;
            m->level = p->level;
            copy_name(m->name, p->name);
            copy_name(m->areaName, first ? "" : s->areaName);
            changed = true;
        } else if (!m->dead && (strcmp(m->name, p->name) || m->level != p->level || m->species != p->species)) {
            copy_name(m->name, p->name);
            m->level = p->level;
            m->species = p->species;
            changed = true;
        }
    }

    if (s->inBattle && !t->wasInBattle) {
        t->battleJudged = false;
        t->battleSamples = 0;
    }
    if (s->inBattle) t->battleSamples++;
    // A wild battle's first foe: the area's encounter, if it hasn't had one - at the battle's menu, or after a few samples
    // without one (it opens with the last battle's foe still in memory).
    bool foeIsThisBattles = s->menuUp || t->battleSamples > FOE_SAMPLES;
    if (s->inBattle && !t->battleJudged && foeIsThisBattles && s->foeSpecies && s->trainer >= 0) {
        t->battleJudged = true;
        for (int i = 0; i < s->partyCount; i++) {
            struct pd_nuz_mon* m = mon_of(r, s->party[i].personality);
            if (m && m->dead && s->party[i].hp > 0) {
                snprintf(text, sizeof text, "%s is out of the run: leave it in the PC", m->name);
                event(events, text);
            }
        }
        if (s->trainer == 0) {
            int foeCaught = s->caught ? s->caught(s->ctx, s->foeDex) : -1;
            if (pd_nuz_encounter_at(r, s->area)) {
                snprintf(text, sizeof text, "%s is used up: no catching here", s->areaName);
                event(events, text);
            } else if (r->dupes && foeCaught == 1) {
                snprintf(text, sizeof text, "%s is caught already: this one doesn't count", s->foeName);
                event(events, text);
            } else if (r->encCount < PD_NUZ_ENCOUNTERS) {
                struct pd_nuz_encounter* e = &r->enc[r->encCount++];
                e->area = s->area;
                copy_name(e->areaName, s->areaName);
                e->species = s->foeSpecies;
                e->outcome = PD_NUZ_NOW;
                t->pendingArea = s->area;
                t->pendingSpecies = s->foeSpecies;
                t->pendingDex = s->foeDex;
                copy_name(t->pendingFoe, s->foeName);
                t->partyBeforeCount = 0;
                for (int i = 0; i < s->partyCount; i++) t->partyBefore[t->partyBeforeCount++] = s->party[i].personality;
                t->caughtBefore = foeCaught;
                t->settleLeft = SETTLE_SAMPLES;
                snprintf(text, sizeof text, "First encounter in %s: %s", s->areaName, s->foeName);
                event(events, text);
                changed = true;
            }
        }
    }

    if (t->pendingArea >= 0) settle(t, t->pendingArea, s, events, &changed);

    // Fainting: out of the run for good. A dead Pokémon healed again gets a reminder, once each time.
    for (int i = 0; i < s->partyCount; i++) {
        const struct pd_nuz_party* p = &s->party[i];
        if (!p->personality || p->egg) continue;
        struct pd_nuz_mon* m = mon_of(r, p->personality);
        if (!m) continue;
        int* zero = hp_slot(t, p->personality, false);
        int* last = hp_slot(t, p->personality, true);
        if (p->hp == 0 && p->maxHp > 0) {
            ++*zero;
            if (!m->dead && *zero >= FAINT_SAMPLES) {
                m->dead = true;
                copy_name(m->diedIn, s->areaName);
                m->diedLevel = p->level;
                snprintf(text, sizeof text, "%s fainted: it's out of the run", m->name);
                event(events, text);
                changed = true;
            }
        } else {
            *zero = 0;
            if (m->dead && p->hp > 0 && *last == 0 && !s->inBattle) {
                snprintf(text, sizeof text, "%s is out of the run: leave it in the PC", m->name);
                event(events, text);
            }
        }
        *last = p->hp;
    }
    t->wasInBattle = s->inBattle;
    return changed;
}

void pd_nuz_set_outcome(struct pd_nuz_run* r, int area, int outcome) {
    for (int i = 0; i < r->encCount; i++) {
        if (r->enc[i].area != area) continue;
        if (outcome < 0) {
            memmove(&r->enc[i], &r->enc[i + 1], sizeof(r->enc[0]) * (size_t) (r->encCount - i - 1));
            r->encCount--;
        } else {
            r->enc[i].outcome = (enum pd_nuz_outcome) outcome;
        }
        return;
    }
}

void pd_nuz_set_dead(struct pd_nuz_run* r, uint32_t personality, bool dead, const char* where) {
    struct pd_nuz_mon* m = mon_of(r, personality);
    if (!m) return;
    m->dead = dead;
    copy_name(m->diedIn, dead ? where : "");
    m->diedLevel = dead ? m->level : 0;
}

void pd_nuz_new_run(struct pd_nuz_run* r) {
    bool dupes = r->dupes, warnings = r->warnings;
    memset(r, 0, sizeof(*r));
    r->dupes = dupes;
    r->warnings = warnings;
}

// Names lose their tabs and line ends.
static void clean(char* to, size_t len, const char* from) {
    snprintf(to, len, "%s", from);
    for (char* p = to; *p; p++)
        if (*p == '\t' || *p == '\n' || *p == '\r') *p = ' ';
}

static const char* const OUTCOMES[] = { "NOW", "CAUGHT", "MISSED" };

size_t pd_nuz_encode(const struct pd_nuz_run* r, char* out, size_t len) {
    size_t at = 0;
#define PUT(...) do { int n = snprintf(out + at, len - at, __VA_ARGS__); if (n < 0 || (size_t) n >= len - at) return 0; at += (size_t) n; } while (0)
    PUT("v1\nrules\t%d\t%d\n", r->dupes ? 1 : 0, r->warnings ? 1 : 0);
    char a[PD_NUZ_NAME], b[PD_NUZ_NAME], c[PD_NUZ_NAME];
    for (int i = 0; i < r->encCount; i++) {
        const struct pd_nuz_encounter* e = &r->enc[i];
        clean(a, sizeof a, e->areaName);
        PUT("enc\t%d\t%d\t%s\t%s\n", e->area, e->species, OUTCOMES[e->outcome], a);
    }
    for (int i = 0; i < r->monCount; i++) {
        const struct pd_nuz_mon* m = &r->mons[i];
        clean(a, sizeof a, m->name);
        clean(b, sizeof b, m->areaName);
        clean(c, sizeof c, m->diedIn);
        PUT("mon\t%lu\t%d\t%d\t%d\t%d\t%s\t%s\t%s\n", (unsigned long) m->personality, m->species, m->level, m->dead ? 1 : 0,
            m->diedLevel, a, b, c);
    }
#undef PUT
    return at;
}

// A line's tab-separated fields (in place).
static int fields(char* line, char** f, int max) {
    int n = 0;
    f[n++] = line;
    for (char* p = line; *p && n < max; p++)
        if (*p == '\t') {
            *p = 0;
            f[n++] = p + 1;
        }
    return n;
}

void pd_nuz_decode(struct pd_nuz_run* r, const char* text) {
    memset(r, 0, sizeof(*r));
    r->warnings = true;
    char line[512];
    while (text && *text) {
        const char* end = strchr(text, '\n');
        size_t n = end ? (size_t) (end - text) : strlen(text);
        if (n < sizeof line) {
            memcpy(line, text, n);
            line[n] = 0;
            char* f[10];
            int k = fields(line, f, 10);
            if (!strcmp(f[0], "rules") && k >= 3) {
                r->dupes = !strcmp(f[1], "1");
                r->warnings = !strcmp(f[2], "1");
            } else if (!strcmp(f[0], "enc") && k >= 4 && r->encCount < PD_NUZ_ENCOUNTERS) {
                struct pd_nuz_encounter* e = &r->enc[r->encCount];
                e->area = atoi(f[1]);
                e->species = atoi(f[2]);
                // A battle cut short by closing the game: missed, as it would have been.
                e->outcome = !strcmp(f[3], "CAUGHT") ? PD_NUZ_CAUGHT : PD_NUZ_MISSED;
                copy_name(e->areaName, k >= 5 ? f[4] : "");
                if (!strcmp(f[3], "NOW") || !strcmp(f[3], "CAUGHT") || !strcmp(f[3], "MISSED")) r->encCount++;
            } else if (!strcmp(f[0], "mon") && k >= 7 && r->monCount < PD_NUZ_MONS) {
                struct pd_nuz_mon* m = &r->mons[r->monCount++];
                memset(m, 0, sizeof(*m));
                m->personality = (uint32_t) strtoul(f[1], NULL, 10);
                m->species = atoi(f[2]);
                m->level = atoi(f[3]);
                m->dead = !strcmp(f[4], "1");
                m->diedLevel = atoi(f[5]);
                copy_name(m->name, f[6]);
                copy_name(m->areaName, k >= 8 ? f[7] : "");
                copy_name(m->diedIn, k >= 9 ? f[8] : "");
            }
        }
        text = end ? end + 1 : NULL;
    }
}
