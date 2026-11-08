#include "pd_gb_world.h"

#include <string.h>

#include "pd_gb.h"
#include "pd_game.h"
#include "pd_games.h"

const struct pd_gbw_world* pd_gbw_for(const struct pd_game* g) {
    return g && g->cfg && g->cfg->gb == &pd_gb_polished ? &pd_gbw_polished : NULL;
}

// The faithful builds' teams where they have their own (Polished Crystal's faithful config is its own text set).
extern const struct pd_text pd_text_gb_polished_faithful;
static bool faithful(const struct pd_game* g) { return g->cfg && g->cfg->text == &pd_text_gb_polished_faithful; }

void pd_gbw_read(struct pd_snapshot* s, const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    const struct pd_gbw_world* w = &pd_gbw_polished;
    int bytes = w->eventBytes < PD_FLAG_BYTES ? w->eventBytes : PD_FLAG_BYTES;
    if (!read(ctx, w->eventFlags, s->flags, (size_t) bytes)) return;
    for (int i = 0; i < w->bankCount && i < 4; i++) {
        int n = w->banks[i].bytes < 32 ? w->banks[i].bytes : 32;
        read(ctx, w->banks[i].addr, s->gbBanks[i], (size_t) n);
    }
    uint8_t v = 0;
    if (w->teamVar) read(ctx, w->teamVar, &v, 1);
    s->gbTeam = v;
    v = 0xFF;
    if (w->timeOfDay) read(ctx, w->timeOfDay, &v, 1);
    s->gbTimeOfDay = v;
    s->flagsOk = s->partyCount > 0;
    // Its place (wMapGroup / wMapNumber): HERE's map.
    uint8_t map[2];
    if (c->mapGroup && s->partyCount > 0 && !s->posOk && read(ctx, c->mapGroup, map, 2)) {
        s->posOk = true;
        s->mapGroup = map[0];
        s->mapNum = map[1];
    }
}

// A world's flag: its event flags, or a bank's bit (id 0x10000 * (bank + 1) + bit).
static bool flag_of(const struct pd_snapshot* s, uint32_t flag) {
    if (!s->flagsOk) return false;
    if (flag >= 0x10000) {
        uint32_t bank = (flag >> 16) - 1, bit = flag & 0xFFFF;
        return bank < 4 && bit / 8 < 32 && (s->gbBanks[bank][bit / 8] >> (bit % 8) & 1);
    }
    return flag / 8 < PD_FLAG_BYTES && (s->flags[flag / 8] >> (flag % 8) & 1);
}

static int badges(const struct pd_snapshot* s) {
    int n = 0;
    for (int i = 0; i < 16; i++) n += flag_of(s, 0x10000u + (uint32_t) i);
    return n;
}

// wTimeOfDay; the day when unknown.
static int time_of_day(const struct pd_gbw_world* w, const struct pd_snapshot* s) {
    int max = w->eveningIndex ? w->eveningIndex : w->nightIndex;
    return s->gbTimeOfDay <= max ? s->gbTimeOfDay : w->dayIndex;
}

// AdjustLevelForBadges: a level up to 100 as it is; past it, BadgeBaseLevels[badges] + (level - LEVEL_FROM_BADGES).
static int level_of(const struct pd_gbw_world* w, int raw, int held) {
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

static const struct pd_gbw_wild_map* find_map(const struct pd_gbw_world* w, int key) {
    int lo = 0, hi = w->mapsCount - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (w->maps[mid].key == key) return &w->maps[mid];
        if (w->maps[mid].key < key) lo = mid + 1;
        else hi = mid - 1;
    }
    return NULL;
}

static void trees(const struct pd_gbw_world* w, const struct pd_gbw_rows* r, int held, struct pd_wild_set* out, int method) {
    struct merged m[PD_WILD_MAX * 2];
    int n = 0;
    for (int i = 0; i < r->count; i++) {
        int l = level_of(w, r->rows[i].level, held);
        add(m, &n, r->rows[i].species, l, l, r->rows[i].odds);
    }
    finish(out, method, m, n);
}

bool pd_gbw_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out) {
    memset(out, 0, sizeof(*out));
    const struct pd_gbw_world* w = pd_gbw_for(g);
    if (!w || !s->posOk) return false;
    const struct pd_gbw_wild_map* map = find_map(w, w->gen == 1 ? s->mapNum : s->mapGroup << 8 | s->mapNum);
    if (!map) return true;
    int held = w->gen == 2 ? badges(s) : 0;
    int tod = w->times > 1 ? time_of_day(w, s) : 0;
    struct merged m[PD_WILD_MAX * 2];
    int n = 0;
    // The evening uses the night's list (GetTimeOfDayNotEve).
    int grassTime = w->eveningIndex && tod == w->eveningIndex ? w->nightIndex : tod;
    if (map->grass && map->rates[grassTime]) {
        const struct pd_gbw_slot* r = map->grass + grassTime * w->grassSlots;
        for (int i = 0; i < w->grassSlots; i++) {
            int l = level_of(w, r[i].level, held);
            add(m, &n, r[i].species, l, l, r[i].odds);
        }
        finish(out, 0, m, n);
    }
    n = 0;
    if (map->water && map->waterRate) {
        for (int i = 0; i < (w->gen == 1 ? w->grassSlots : 3); i++) {
            const struct pd_gbw_slot* r = &map->water[i];
            add(m, &n, r->species, level_of(w, r->level, held), level_of(w, r->level + w->surfBoost, held), r->odds);
        }
        finish(out, 1, m, n);
    }
    if (map->rock >= 0) trees(w, &w->trees[map->rock].common, held, out, 2);
    if (map->fish >= 0) {
        for (int rod = 0; rod < 3; rod++) {
            const struct pd_gbw_rows* r = &w->fish[map->fish].rods[rod];
            n = 0;
            for (int i = 0; i < r->count; i++) {
                const struct pd_gbw_slot* t = &r->rows[i];
                int species = t->species, level = t->level;
                if (!species && w->time) {
                    // Morning counts as day.
                    if (level >= w->timeCount) continue;
                    const struct pd_gbw_time_fish* f = &w->time[level];
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

int pd_gbw_boss_count(const struct pd_game* g) {
    const struct pd_gbw_world* w = pd_gbw_for(g);
    return w ? w->bossesCount : 0;
}

const struct pd_gbw_boss* pd_gbw_boss_at(const struct pd_game* g, int i) {
    return &pd_gbw_for(g)->bosses[i];
}

bool pd_gbw_boss_done(const struct pd_snapshot* s, const struct pd_gbw_boss* b) {
    switch (b->rule) {
    case PD_GBW_DONE_BOTH: return flag_of(s, b->a) && flag_of(s, b->b);
    case PD_GBW_DONE_REMATCH: return flag_of(s, b->a) || (badges(s) == 16 && flag_of(s, b->b));
    default: return (b->a && flag_of(s, b->a)) || (b->b && flag_of(s, b->b));
    }
}

// The champion's var holds a team's value: that one alone. Before it's set, every team.
static int settled(const struct pd_snapshot* s, const struct pd_gbw_boss* b) {
    int i = s->gbTeam - b->firstValue;
    return b->teams > 1 && i >= 0 && i < b->teams ? i : -1;
}

int pd_gbw_boss_teams(const struct pd_snapshot* s, const struct pd_gbw_boss* b) {
    return b->teams <= 1 || settled(s, b) >= 0 ? 1 : b->teams;
}

int pd_gbw_boss_team(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_gbw_boss* b, int k, struct pd_team_mon* out) {
    const struct pd_gbw_world* w = pd_gbw_for(g);
    int pick = settled(s, b);
    const struct pd_gbw_team* t = &w->teams[b->team + (pick >= 0 ? pick : k)];
    const struct pd_gbw_mon* mons = faithful(g) && t->faithful ? t->faithful : t->mons;
    int count = faithful(g) && t->faithful ? t->faithfulCount : t->count;
    int held = w->gen == 2 ? badges(s) : 0;
    if (count > PD_TEAM_MAX) count = PD_TEAM_MAX;
    for (int i = 0; i < count; i++) {
        out[i] = (struct pd_team_mon) { mons[i].species, level_of(w, mons[i].level, held), mons[i].item, { 0, 0, 0, 0 } };
        for (int m = 0; m < 4; m++) out[i].moves[m] = mons[i].moves[m];
    }
    return count;
}

const char* pd_gbw_clock(const struct pd_game* g, const struct pd_snapshot* s) {
    const struct pd_gbw_world* w = pd_gbw_for(g);
    if (!w || w->times < 2) return NULL;
    int tod = time_of_day(w, s);
    if (w->eveningIndex && tod == w->eveningIndex) return "EVENING";
    return tod == w->nightIndex ? "NIGHT" : tod == w->dayIndex ? "DAY" : "MORNING";
}
