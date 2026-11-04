#include "pd_gb_guide.h"

#include <string.h>

#include "pd_gb.h"

void pd_gb_guide_read(struct pd_snapshot* s, const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    const struct pd_gb_guide* w = c->guide;
    int by = c->guideShift;
    // The European Yellows: English's RAM, [by] bytes later from wBattleMon up.
#define AT(a) ((a) >= 0xD013u && (a) < 0x10000u ? (a) + (uint32_t) by : (a))
    int bytes = w->eventBytes < PD_FLAG_BYTES ? w->eventBytes : PD_FLAG_BYTES;
    read(ctx, AT(w->eventFlags), s->flags, (size_t) bytes);
    for (int i = 0; i < w->bankCount; i++) read(ctx, AT(w->banks[i].addr), s->flagBanks[i], (size_t) w->banks[i].bytes);
    uint8_t v = 0;
    if (w->teamVar) read(ctx, AT(w->teamVar), &v, 1);
    s->gbTeam = v;
    v = 0xFF;
    if (w->timeOfDay) read(ctx, w->timeOfDay, &v, 1);
    s->timeOfDay = v;
    s->flagsOk = true;
#undef AT
}

static int badges(const struct pd_snapshot* s) {
    int n = 0;
    for (int i = 0; i < 16; i++) n += pd_flag(s, 0x10000 + i);
    return n;
}

// wTimeOfDay; the day when unknown.
static int time_of_day(const struct pd_gb_guide* w, const struct pd_snapshot* s) {
    int max = w->eveningIndex ? w->eveningIndex : w->nightIndex;
    return s->timeOfDay <= max ? s->timeOfDay : w->dayIndex;
}

// AdjustLevelForBadges: a level up to 100 as it is; past it, BadgeBaseLevels[badges] + (level - LEVEL_FROM_BADGES).
static int level_of(const struct pd_gb_guide* w, int raw, int held) {
    if (raw <= 100 || !w->badgeLevels) return raw;
    int i = held < w->badgeLevelCount ? held : w->badgeLevelCount - 1;
    int v = w->badgeLevels[i] + (raw - w->levelFromBadges);
    if (v < 2) return 2;
    if (v < 100) return v;
    return v < w->levelFromBadges ? 99 : 2;
}

struct merged {
    int species, minLv, maxLv;
    uint32_t odds;
};

static void add(struct merged* m, int* n, int species, int minLv, int maxLv, int odds) {
    for (int i = 0; i < *n; i++) {
        if (m[i].species != species) continue;
        if (minLv < m[i].minLv) m[i].minLv = minLv;
        if (maxLv > m[i].maxLv) m[i].maxLv = maxLv;
        m[i].odds += (uint32_t) odds;
        return;
    }
    if (*n >= PD_WILD_MAX * 2) return;
    m[(*n)++] = (struct merged) { species, minLv, maxLv, (uint32_t) odds };
}

// Likeliest first, a stable sort of the rounded percents, as the app's list.
static void finish(struct pd_wild_set* out, int method, struct merged* m, int n) {
    int c = 0;
    for (int i = 0; i < n && c < PD_WILD_MAX; i++) {
        int pct = (int) ((m[i].odds + 128) / 256);
        int at = c;
        while (at > 0 && out->mons[method][at - 1].pct < pct) {
            out->mons[method][at] = out->mons[method][at - 1];
            at--;
        }
        out->mons[method][at] = (struct pd_wild) { m[i].species, m[i].minLv, m[i].maxLv, pct };
        c++;
    }
    out->count[method] = c;
}

static const struct pd_gb_wild_map* find_map(const struct pd_gb_guide* w, int key) {
    int lo = 0, hi = w->mapsCount - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (w->maps[mid].key == key) return &w->maps[mid];
        if (w->maps[mid].key < key) lo = mid + 1;
        else hi = mid - 1;
    }
    return NULL;
}

static void trees(const struct pd_gb_guide* w, const struct pd_gb_rows* r, int held, struct pd_wild_set* out, int method) {
    struct merged m[PD_WILD_MAX * 2];
    int n = 0;
    for (int i = 0; i < r->count; i++) {
        int l = level_of(w, r->rows[i].level, held);
        add(m, &n, r->rows[i].species, l, l, r->rows[i].odds);
    }
    finish(out, method, m, n);
}

bool pd_gb_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out) {
    memset(out, 0, sizeof(*out));
    const struct pd_gb_guide* w = g->gb->guide;
    if (!w || !s->posOk) return false;
    const struct pd_gb_wild_map* map = find_map(w, w->gen == 1 ? s->mapNum : s->mapGroup << 8 | s->mapNum);
    if (!map) return true;
    int held = w->gen == 2 ? badges(s) : 0;
    int tod = w->times > 1 ? time_of_day(w, s) : 0;
    struct merged m[PD_WILD_MAX * 2];
    int n = 0;
    // The evening uses the night's list (GetTimeOfDayNotEve).
    int grassTime = w->eveningIndex && tod == w->eveningIndex ? w->nightIndex : tod;
    if (map->grass && map->rates[grassTime]) {
        const struct pd_gb_slot* r = map->grass + grassTime * w->grassSlots;
        for (int i = 0; i < w->grassSlots; i++) {
            int l = level_of(w, r[i].level, held);
            add(m, &n, r[i].species, l, l, r[i].odds);
        }
        finish(out, 0, m, n);
    }
    n = 0;
    if (map->water && map->waterRate) {
        for (int i = 0; i < (w->gen == 1 ? w->grassSlots : 3); i++) {
            const struct pd_gb_slot* r = &map->water[i];
            add(m, &n, r->species, level_of(w, r->level, held), level_of(w, r->level + w->surfBoost, held), r->odds);
        }
        finish(out, 1, m, n);
    }
    if (map->rock >= 0) trees(w, &w->trees[map->rock].common, held, out, 2);
    if (map->fish >= 0) {
        for (int rod = 0; rod < 3; rod++) {
            const struct pd_gb_rows* r = &w->fish[map->fish].rods[rod];
            n = 0;
            for (int i = 0; i < r->count; i++) {
                const struct pd_gb_slot* t = &r->rows[i];
                int species = t->species, level = t->level;
                if (!species && w->time) {
                    // Morning counts as day.
                    if (level >= w->timeCount) continue;
                    const struct pd_gb_time_fish* f = &w->time[level];
                    bool night = tod == w->nightIndex;
                    species = night ? f->nightSpecies : f->daySpecies;
                    level = night ? f->nightLevel : f->dayLevel;
                } else if (!species) {
                    // The shore's slot: CORSOLA in the morning and day, STARYU in the evening and at night.
                    species = tod <= w->dayIndex ? w->shoreDay : w->shoreNight;
                }
                int l = level_of(w, level, held);
                add(m, &n, species, l, l, t->odds);
            }
            finish(out, 3 + rod, m, n);
        }
    }
    if (map->tree >= 0) {
        trees(w, &w->trees[map->tree].common, held, out, 6);
        trees(w, &w->trees[map->tree].rare, held, out, 7);
    }
    return true;
}

int pd_gb_boss_count(const struct pd_game* g) {
    return g->gb && g->gb->guide ? g->gb->guide->bossesCount : 0;
}

const struct pd_gb_boss* pd_gb_boss_at(const struct pd_game* g, int i) {
    return &g->gb->guide->bosses[i];
}

bool pd_gb_boss_done(const struct pd_snapshot* s, const struct pd_gb_boss* b) {
    switch (b->rule) {
    case PD_GB_DONE_BOTH: return pd_flag(s, (int) b->a) && pd_flag(s, (int) b->b);
    case PD_GB_DONE_REMATCH: return pd_flag(s, (int) b->a) || (badges(s) == 16 && pd_flag(s, (int) b->b));
    default: return (b->a && pd_flag(s, (int) b->a)) || (b->b && pd_flag(s, (int) b->b));
    }
}

// The champion's var holds a team's value: that one alone. Before it's set, every team.
static int settled(const struct pd_snapshot* s, const struct pd_gb_boss* b) {
    int i = s->gbTeam - b->firstValue;
    return b->teams > 1 && i >= 0 && i < b->teams ? i : -1;
}

int pd_gb_boss_teams(const struct pd_snapshot* s, const struct pd_gb_boss* b) {
    return b->teams <= 1 || settled(s, b) >= 0 ? 1 : b->teams;
}

int pd_gb_boss_team(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_gb_boss* b, int k, struct pd_team_mon* out) {
    const struct pd_gb_guide* w = g->gb->guide;
    int pick = settled(s, b);
    const struct pd_gb_team* t = &w->teams[b->team + (pick >= 0 ? pick : k)];
    const struct pd_gb_mon* mons = g->gb->polishedFaithful && t->faithful ? t->faithful : t->mons;
    int count = g->gb->polishedFaithful && t->faithful ? t->faithfulCount : t->count;
    int held = w->gen == 2 ? badges(s) : 0;
    if (count > PD_TEAM_MAX) count = PD_TEAM_MAX;
    for (int i = 0; i < count; i++) {
        out[i] = (struct pd_team_mon) { mons[i].species, level_of(w, mons[i].level, held), mons[i].item, { 0, 0, 0, 0 } };
        for (int m = 0; m < 4; m++) out[i].moves[m] = mons[i].moves[m];
    }
    return count;
}

const char* pd_gb_clock(const struct pd_game* g, const struct pd_snapshot* s) {
    const struct pd_gb_guide* w = g->gb ? g->gb->guide : NULL;
    if (!w || w->times < 2) return NULL;
    int tod = time_of_day(w, s);
    if (w->eveningIndex && tod == w->eveningIndex) return "EVENING";
    return tod == w->nightIndex ? "NIGHT" : tod == w->dayIndex ? "DAY" : "MORNING";
}
