#include "pd_gb.h"
#include "pd_guide.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"

#define TRAINER_STRIDE 0x28
#define MAX_HEADERS 512

static uint16_t u16(const uint8_t* p) {
    return (uint16_t) (p[0] | p[1] << 8);
}

static uint32_t u32(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

const struct pd_guide* pd_guide_for(const struct pd_game* g) {
    switch (g->guide) {
    case PD_GUIDE_FIRERED: return &pd_guide_firered;
    case PD_GUIDE_LEAFGREEN: return &pd_guide_leafgreen;
    case PD_GUIDE_EMERALD: return &pd_guide_emerald;
    case PD_GUIDE_RUBY: return &pd_guide_ruby;
    case PD_GUIDE_SAPPHIRE: return &pd_guide_sapphire;
    case PD_GUIDE_EMERALD_LEGACY: return &pd_guide_emerald_legacy;
    case PD_GUIDE_ROCKET_EDITION: return &pd_guide_rocket_edition;
    case PD_GUIDE_ASH_GRAY: return &pd_guide_ash_gray;
    case PD_GUIDE_GS_CHRONICLES: return &pd_guide_gs_chronicles;
    case PD_GUIDE_RADICAL_RED: return &pd_guide_radical_red;
    case PD_GUIDE_ORANGE_ISLANDS: return &pd_guide_orange_islands;
    case PD_GUIDE_ODYSSEY: return &pd_guide_odyssey;
    case PD_GUIDE_SWORD_SHIELD: return &pd_guide_sword_shield;
    case PD_GUIDE_UNBOUND: return &pd_guide_unbound;
    case PD_GUIDE_GAIA: return &pd_guide_gaia;
    case PD_GUIDE_GHOST_GREY: return &pd_guide_ghost_grey;
    case PD_GUIDE_CELIA: return &pd_guide_celia;
    case PD_GUIDE_IMPERIUM: return &pd_guide_imperium;
    case PD_GUIDE_GB_RED: return &pd_guide_gb_red;
    case PD_GUIDE_GB_BLUE: return &pd_guide_gb_blue;
    case PD_GUIDE_GB_YELLOW: return &pd_guide_gb_yellow;
    case PD_GUIDE_GB_CRYSTAL: return &pd_guide_gb_crystal;
    case PD_GUIDE_RECHARGED_YELLOW: return &pd_guide_recharged_yellow;
    case PD_GUIDE_RECHARGED_EMERALD: return &pd_guide_recharged_emerald;
    case PD_GUIDE_AMETHYST: return &pd_guide_amethyst;
    case PD_GUIDE_SOULGOLD: return &pd_guide_soulgold;
    case PD_GUIDE_QUETZAL_V084: return &pd_guide_quetzal_v084;
    case PD_GUIDE_QUETZAL: return &pd_guide_quetzal;
    case PD_GUIDE_TMT2: return &pd_guide_tmt2;
    case PD_GUIDE_SEAGLASS: return &pd_guide_seaglass;
    case PD_GUIDE_LAZARUS: return &pd_guide_lazarus;
    case PD_GUIDE_HEART_AND_SOUL: return &pd_guide_heart_and_soul;
    case PD_GUIDE_GLAZED: return &pd_guide_glazed;
    case PD_GUIDE_MARIOMON: return &pd_guide_mariomon;
    case PD_GUIDE_DREAMSTONE: return &pd_guide_dreamstone;
    case PD_GUIDE_WISHES: return &pd_guide_wishes;
    case PD_GUIDE_TEAM_ROCKET: return &pd_guide_team_rocket;
    case PD_GUIDE_ELITE_REDUX: return &pd_guide_elite_redux;
    case PD_GUIDE_ULTRA_VIOLET: return &pd_guide_ultra_violet;
    case PD_GUIDE_AMETHYST_V141: return &pd_guide_amethyst;
    case PD_GUIDE_RECHARGED_YELLOW_V180:
    case PD_GUIDE_RECHARGED_YELLOW_V190:
    case PD_GUIDE_RECHARGED_YELLOW_V194: return &pd_guide_recharged_yellow;
    case PD_GUIDE_EMERALD_KAIZO: return &pd_guide_emerald; // the app's EMERALD guide, Kaizo's own wild table
    default: return NULL;
    }
}

int pd_quetzal_region(int mapGroup) {
    return mapGroup >= 34 && mapGroup <= 35 ? 1 : mapGroup >= 36 && mapGroup <= 39 ? 2 : 0;
}

// The app's quetzalBosses: Hoenn + Johto + Kanto, Johto + Kanto + Hoenn, Kanto + Johto + Hoenn.
const uint8_t pd_quetzal_order[3][3] = {{0, 1, 2}, {1, 2, 0}, {2, 1, 0}};

const struct pd_area_thing* pd_areas_for(const struct pd_game* g, int* count) {
    switch (g->guide) {
    case PD_GUIDE_FIRERED: *count = pd_areas_firered_count; return pd_areas_firered;
    case PD_GUIDE_LEAFGREEN: *count = pd_areas_leafgreen_count; return pd_areas_leafgreen;
    case PD_GUIDE_EMERALD: *count = pd_areas_emerald_count; return pd_areas_emerald;
    case PD_GUIDE_RUBY:
    case PD_GUIDE_SAPPHIRE: *count = pd_areas_ruby_sapphire_count; return pd_areas_ruby_sapphire;
    case PD_GUIDE_EMERALD_LEGACY: *count = pd_areas_emerald_legacy_count; return pd_areas_emerald_legacy;
    case PD_GUIDE_ROCKET_EDITION: *count = pd_areas_rocket_edition_count; return pd_areas_rocket_edition;
    case PD_GUIDE_ASH_GRAY: *count = pd_areas_ash_gray_count; return pd_areas_ash_gray;
    case PD_GUIDE_GS_CHRONICLES: *count = pd_areas_gs_chronicles_count; return pd_areas_gs_chronicles;
    case PD_GUIDE_RADICAL_RED: *count = pd_areas_radical_red_count; return pd_areas_radical_red;
    case PD_GUIDE_ORANGE_ISLANDS: *count = pd_areas_orange_islands_count; return pd_areas_orange_islands;
    case PD_GUIDE_ODYSSEY: *count = pd_areas_odyssey_count; return pd_areas_odyssey;
    case PD_GUIDE_SWORD_SHIELD: *count = pd_areas_sword_shield_count; return pd_areas_sword_shield;
    case PD_GUIDE_UNBOUND: *count = pd_areas_unbound_count; return pd_areas_unbound;
    case PD_GUIDE_GAIA: *count = pd_areas_gaia_count; return pd_areas_gaia;
    case PD_GUIDE_GHOST_GREY: *count = pd_areas_ghost_grey_count; return pd_areas_ghost_grey;
    case PD_GUIDE_CELIA: *count = pd_areas_celia_count; return pd_areas_celia;
    case PD_GUIDE_AMETHYST: *count = pd_areas_amethyst_count; return pd_areas_amethyst;
    case PD_GUIDE_RECHARGED_YELLOW: *count = pd_areas_recharged_yellow_count; return pd_areas_recharged_yellow;
    case PD_GUIDE_HEART_AND_SOUL: *count = pd_areas_heart_and_soul_count; return pd_areas_heart_and_soul;
    case PD_GUIDE_ULTRA_VIOLET: *count = pd_areas_ultra_violet_count; return pd_areas_ultra_violet;
    case PD_GUIDE_AMETHYST_V141: *count = pd_areas_amethyst_v141_count; return pd_areas_amethyst_v141;
    case PD_GUIDE_RECHARGED_YELLOW_V180: *count = pd_areas_recharged_yellow_v180_count; return pd_areas_recharged_yellow_v180;
    case PD_GUIDE_RECHARGED_YELLOW_V190: *count = pd_areas_recharged_yellow_v190_count; return pd_areas_recharged_yellow_v190;
    case PD_GUIDE_RECHARGED_YELLOW_V194: *count = pd_areas_recharged_yellow_v194_count; return pd_areas_recharged_yellow_v194;
    case PD_GUIDE_EMERALD_KAIZO: *count = pd_areas_emerald_count; return pd_areas_emerald;
    case PD_GUIDE_GB_YELLOW: *count = pd_areas_gb_yellow_count; return pd_areas_gb_yellow;
    case PD_GUIDE_GB_RED: *count = pd_areas_gb_red_count; return pd_areas_gb_red;
    case PD_GUIDE_GB_BLUE: *count = pd_areas_gb_blue_count; return pd_areas_gb_blue;
    case PD_GUIDE_GB_CRYSTAL: *count = pd_areas_gb_crystal_count; return pd_areas_gb_crystal;
    default: *count = 0; return NULL;
    }
}

static bool caught_species(const struct pd_game* g, const struct pd_snapshot* s, int species) {
    int nat = pd_dex_national(g, species);
    return nat && pd_dex_caught(s, nat);
}

bool pd_guide_has(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_guide_entry* e) {
    (void) g;
    switch (e->haveKind) {
    case PD_HAVE_FLAG: return pd_flag(s, e->have[0]);
    case PD_HAVE_ITEM: return pd_bag_has(s, e->have[0]);
    case PD_HAVE_CAUGHT:
        for (int i = 0; i < 3; i++) {
            if (e->have[i] && pd_dex_caught(s, e->have[i])) return true;
        }
        return false;
    default: return false;
    }
}

void pd_area_key(const char* name, char* out, int len) {
    int n = 0;
    for (const unsigned char* p = (const unsigned char*) name; *p && n + 1 < len; p++) {
        if (p[0] == 0xC3 && (p[1] == 0xA9 || p[1] == 0x89)) { // é / É
            out[n++] = 'E';
            p++;
        } else if (isalnum(*p)) {
            out[n++] = (char) toupper(*p);
        }
    }
    out[n] = 0;
}

bool pd_entry_in_area(const struct pd_guide_entry* e, const char* key) {
    if (!e->areas || !*key) return false;
    size_t kl = strlen(key);
    for (const char* p = e->areas; *p;) {
        const char* end = strchr(p, '|');
        size_t l = end ? (size_t) (end - p) : strlen(p);
        if (l == kl && !strncmp(p, key, l)) return true;
        if (!end) break;
        p = end + 1;
    }
    return false;
}

bool pd_area_done(const struct pd_game* g, const struct pd_snapshot* s, const struct pd_area_thing* t) {
    if (t->flag) return pd_flag(s, t->flag);
    if (t->kind == PD_AREA_GIFT && t->id >= 259) return pd_bag_has(s, t->id);
    if (t->kind == PD_AREA_KEY) return pd_bag_has(s, t->id);
    if (t->kind == PD_AREA_MON) return caught_species(g, s, t->id);
    return false;
}

// The trainer's record (GuideRom.party): its id's top bits pick an alternate table, an empty record there falls back
// to the main one. A config without the layout fields reads retail's struct Trainer.
static int tr_stride(const struct pd_config* c) { return c->trainerStride ? c->trainerStride : TRAINER_STRIDE; }
static int tr_size_off(const struct pd_config* c) { return c->trainerStride ? c->trainerSizeOff : 0x20; }
static int tr_party_off(const struct pd_config* c) { return c->trainerStride ? c->trainerPartyOff : 0x24; }

static const uint8_t* trainer_record(const struct pd_game* g, int id) {
    const struct pd_config* c = g->cfg;
    int stride = tr_stride(c), local = id & 0xFFFF, table = id >> 16;
    if (table >= 1 && table <= 3 && c->altTrainers[table - 1]) {
        const uint8_t* r = pd_rom_at(g, c->altTrainers[table - 1] + (uint32_t) local * stride, (size_t) stride);
        if (r && (r[0] || u32(r + tr_party_off(c)))) return r;
    }
    return pd_rom_at(g, c->trainers + (uint32_t) local * stride, (size_t) stride);
}

bool pd_guide_rom_ok(const struct pd_game* g) {
    const struct pd_config* cfg = g->cfg;
    if (cfg && cfg->gb) return cfg->gb->teams != NULL;  // a Game Boy guide's teams are embedded, nothing to probe
    if (!cfg || !cfg->trainers || !cfg->probeName) return false;
    size_t len = (size_t) (cfg->trainerStride ? cfg->trainerNameLen : 12);
    uint32_t at = cfg->trainers + (uint32_t) cfg->probeTrainer * tr_stride(cfg) + (cfg->trainerStride ? cfg->trainerNameOff : 4);
    if (cfg->trainerNamePtr) {
        const uint8_t* p = pd_rom_at(g, at, 4);
        if (!p) return false;
        at = u32(p);
    }
    const uint8_t* name = pd_rom_at(g, at, len);
    const uint8_t* wild = pd_rom_at(g, cfg->wildHeaders, 1);
    if (!name || !wild || *wild == 0xFF) return false;
    char decoded[64];
    pd_gen3_text(name, len, decoded, sizeof(decoded));
    return !strcmp(decoded, cfg->probeName);
}

bool pd_boss_done(const struct pd_boss* b, const struct pd_snapshot* s) {
    if (b->doneFlag && pd_flag(s, b->doneFlag)) return true;
    for (const uint16_t* f = b->doneAny; f && *f; f++)
        if (pd_flag(s, *f)) return true;
    if (b->doneAll && b->doneAll[0]) {
        bool all = true;
        for (const uint16_t* f = b->doneAll; *f && all; f++) all = pd_flag(s, *f);
        if (all) return true;
    }
    return b->doneVar && s->flagsOk && pd_var(s, b->doneVar) >= b->doneVarMin;
}

const struct pd_variant* pd_boss_variants(const struct pd_guide* gd, const struct pd_boss* b, const struct pd_snapshot* s) {
    if (!b->pickLabels) return b->variants;
    static struct pd_variant picked[9];
    uint32_t id = (uint32_t) pd_boss_trainer(gd, b, s) & 0xFFFF;
    int n = 0;
    for (; b->pickLabels[n] && n < 8; n++) picked[n] = (struct pd_variant) {b->pickLabels[n], id | (uint32_t) n << 16};
    picked[n] = (struct pd_variant) {NULL, 0};
    return picked;
}

int pd_boss_order(const struct pd_guide* gd, const struct pd_snapshot* s, int* out, int max) {
    int n = 0;
    if (!gd->regionOf) {
        for (int b = 0; b < gd->bossCount && n < max; b++) out[n++] = b;
        return n;
    }
    int here = gd->regionOf(s->posOk ? s->mapGroup : -1);
    for (int r = 0; r < 3; r++)
        for (int b = 0; b < gd->bossCount && n < max; b++)
            if (gd->bosses[b].region == gd->regionOrder[here][r]) out[n++] = b;
    return n;
}

int pd_boss_trainer(const struct pd_guide* gd, const struct pd_boss* b, const struct pd_snapshot* s) {
    if (b->kind == PD_BOSS_TEAMVAR) {  // the champion's team byte picks the team; none matching = the first
        int v = pd_var(s, 0x4000);
        for (const uint16_t* p = b->alts; p && p[0]; p += 2)
            if (p[0] == v) return p[1];
        return b->a;
    }
    if (b->kind == PD_BOSS_BY_FLAGS) {
        for (const uint16_t* p = b->alts; p && p[0]; p += 2)
            if (pd_flag(s, p[0])) return p[1];
        return b->a;
    }
    if (b->kind == PD_BOSS_BY_COUNT) {
        int n = 0;
        for (const uint16_t* p = b->alts; p && p[0]; p++) n += pd_flag(s, p[0]);
        return (int) b->countTeams[n];
    }
    if (b->kind == PD_BOSS_VAR_IS) return pd_var(s, b->addVar) == b->addMax ? b->b : b->a;
    if (b->kind == PD_BOSS_VAR_ADD) {
        int v = pd_var(s, b->addVar);
        return b->a + (v < 0 ? 0 : v > b->addMax ? b->addMax : v);
    }
    int id = b->kind == PD_BOSS_FIXED ? b->a : pd_flag(s, gd->leagueRematchFlag) ? b->b : b->a;
    if (b->kind == PD_BOSS_CHAMPION) {
        // The rival answers the starter: CHARMANDER (2) -> +0, SQUIRTLE (1) -> +1, else +2.
        int starter = pd_var(s, gd->starterVar);
        id += starter == 2 ? 0 : starter == 1 ? 1 : 2;
    }
    return id;
}

// The last four distinct moves the species learns up to level (the game's
// default moveset for a trainer mon without its own).
static void default_moves(const struct pd_game* g, int species, int level, int* moves) {
    memset(moves, 0, 4 * sizeof(int));
    const uint8_t* ptr = pd_rom_at(g, g->cfg->learnsets + (uint32_t) species * 4, 4);
    if (!ptr) return;
    uint32_t at = u32(ptr);
    int n = 0;
    const struct pd_config* cfg = g->cfg;
    int stride = cfg->learnsetPairs ? 4 : cfg->learnsetCfru ? 3 : 2;
    for (int i = 0; i < 64; i++) {
        const uint8_t* e = pd_rom_at(g, at + (uint32_t) i * stride, (size_t) stride);
        if (!e) return;
        int move, lv;
        if (cfg->learnsetPairs) {  // {u16 move, u16 level}, ending at move 0xFFFF
            move = u16(e);
            lv = u16(e + 2);
            if (move == 0xFFFF || lv > level) return;
        } else if (cfg->learnsetCfru) {  // {u16 move, u8 level}, ending at level 0xFF
            move = u16(e);
            lv = e[2];
            if (lv == 0xFF || lv > level) return;
        } else {
            uint16_t v = u16(e);
            if (v == 0xFFFF || (v >> 9) > level) return;
            move = v & 0x1FF;
        }
        bool dup = false;
        for (int k = 0; k < n; k++) dup |= moves[k] == move;
        if (dup) continue;
        if (n == 4) {
            memmove(moves, moves + 1, 3 * sizeof(int));
            moves[3] = move;
        } else {
            moves[n++] = move;
        }
    }
}

int pd_trainer_team(const struct pd_game* g, int trainer, struct pd_team_mon* out) {
    if (g->cfg->gb) {  // a Game Boy guide's embedded teams, by index
        const struct pd_gb_team* t = g->cfg->gb->teams;
        for (int i = 0; t && t[i].n && i <= trainer; i++) {
            if (i != trainer) continue;
            for (int m = 0; m < t[i].n; m++) {
                const struct pd_gb_mon* src = &t[i].mons[m];
                out[m] = (struct pd_team_mon) { src->species, src->level, src->item, { src->moves[0], src->moves[1], src->moves[2], src->moves[3] } };
            }
            return t[i].n;
        }
        return 0;
    }
    const uint8_t* t = trainer_record(g, trainer);
    if (!t) return 0;
    const struct pd_config* cfg = g->cfg;
    bool hard = cfg->trainerStride && cfg->trainerHardPartyOff >= 0 && (trainer >> 16) == 1;
    int flags = t[0], size = t[tr_size_off(cfg)];
    if (cfg->trainerStride && cfg->trainerHardPartyOff >= 0) size = hard ? size >> 4 : size & 0x0F;
    if (size < 1 || size > PD_TEAM_MAX) return 0;
    uint32_t partyPtr = u32(t + (hard ? cfg->trainerHardPartyOff : tr_party_off(cfg)));
    if (cfg->trainerMonStride) {  // a hack's own TrainerMon (Emerald Legacy's 0x2C), whatever the flags
        const uint8_t* party = pd_rom_at(g, partyPtr, (size_t) (size * cfg->trainerMonStride));
        if (!party) return 0;
        for (int i = 0; i < size; i++) {
            const uint8_t* m = party + i * cfg->trainerMonStride;
            struct pd_team_mon* o = &out[i];
            memset(o, 0, sizeof(*o));
            o->level = m[cfg->trainerMonLevelOff];
            o->species = u16(m + cfg->trainerMonSpeciesOff);
            o->item = u16(m + cfg->trainerMonItemOff);
            bool any = false;
            for (int k = 0; k < 4; k++) any |= (o->moves[k] = u16(m + cfg->trainerMonMovesOff + k * 2)) != 0;
            if (!any) default_moves(g, o->species, o->level, o->moves);  // none = the level-up moves
        }
        return size;
    }
    bool customMoves = flags & 1, heldItem = flags & 2;
    int stride = customMoves ? 16 : 8;
    const uint8_t* party = pd_rom_at(g, partyPtr, (size_t) (size * stride));
    if (!party) return 0;
    for (int i = 0; i < size; i++) {
        const uint8_t* m = party + i * stride;
        struct pd_team_mon* o = &out[i];
        memset(o, 0, sizeof(*o));
        o->level = m[2];
        o->species = u16(m + 4);
        if (heldItem) o->item = u16(m + 6);
        if (customMoves) {
            int off = heldItem ? 8 : 6;
            for (int k = 0; k < 4; k++) o->moves[k] = u16(m + off + k * 2);
        } else {
            default_moves(g, o->species, o->level, o->moves);
        }
    }
    return size;
}

void pd_species_types(const struct pd_game* g, int species, int* t1, int* t2) {
    *t1 = *t2 = 255;
    const uint8_t* b = g->cfg ? pd_rom_at(g, g->cfg->speciesInfo + (uint32_t) species * 28, 28) : NULL;
    if (!b) return;
    *t1 = b[6];
    *t2 = b[7];
}

// --- wild encounters ---

static const uint8_t LAND_ODDS[12] = { 20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1 };
static const uint8_t WATER_ODDS[5] = { 60, 30, 5, 4, 1 };
static const uint8_t FISH_ODDS[10] = { 70, 30, 60, 20, 20, 40, 40, 15, 4, 1 };

static void add_slot(struct pd_wild_set* w, int method, int species, int minLv, int maxLv, int pct) {
    // A slot that can't be one (levels out of order or past 100) is dropped, as the app's merge(): TMT2 lists 11 land
    // slots, so its 12th is the next table's pointer.
    if (!species || minLv < 1 || minLv > 100 || maxLv < minLv || maxLv > 100) return;
    for (int i = 0; i < w->count[method]; i++) {
        struct pd_wild* m = &w->mons[method][i];
        if (m->species != species) continue;
        if (minLv < m->minLv) m->minLv = minLv;
        if (maxLv > m->maxLv) m->maxLv = maxLv;
        m->pct += pct;
        return;
    }
    if (w->count[method] < PD_WILD_MAX) {
        w->mons[method][w->count[method]++] = (struct pd_wild) { species, minLv, maxLv, pct };
    }
}

// The slots of one table (WildPokemonInfo -> mons), slots first..first+n-1.
static void read_table(const struct pd_game* g, uint32_t info, int first, int n, const uint8_t* odds,
                       struct pd_wild_set* w, int method) {
    const uint8_t* i = info ? pd_rom_at(g, info, 8) : NULL;
    if (!i) return;
    const uint8_t* mons = pd_rom_at(g, u32(i + 4), (size_t) (first + n) * 4);
    if (!mons) return;
    for (int s = 0; s < n; s++) {
        const uint8_t* m = mons + (first + s) * 4;
        add_slot(w, method, u16(m + 2), m[0], m[1], odds[s]);
    }
}

static int cmp_wild(const void* a, const void* b) {
    return ((const struct pd_wild*) b)->pct - ((const struct pd_wild*) a)->pct;
}

// The app's GuideRomSource: a header is 4 + wildSets * a set (4 pointers, + a word when there are several), or the
// game's own shape.
#define WILD_SET_SIZE 16
static int set_stride(const struct pd_config* c) { return c->wildSets > 1 ? WILD_SET_SIZE + 4 : WILD_SET_SIZE; }
static int header_stride(const struct pd_config* c) {
    return c->wildLayout ? c->wildLayout->stride : 4 + (c->wildSets > 1 ? c->wildSets : 1) * set_stride(c);
}

// Which of the map's back-to-back headers the game reads now (the app's wildSetOf): a var's value, or the clock's night.
static int wild_set_of(const struct pd_config* c, const struct pd_snapshot* s, int group, int num) {
    for (const uint32_t* p = c->wildSetVars; p && p[0] != 0xFFFFFFFFu; p += 2)
        if (p[0] == (uint32_t) (group << 8 | num)) return pd_var(s, (int) p[1]);
    const struct pd_night_wild* n = c->nightWild;
    if (!n || group == n->skipGroup || num == n->skipNum) return 0;
    return s->hour >= 0 && s->hour <= 23 && (s->hour < n->dayFrom || s->hour > n->dayTo) ? 1 : 0;
}

// Gen 1 (Gen1World.encounters): the map's grass and surfing slots, its super rod and, where it has water, the old and good
// rods; slots of one species merged, odds summed and rounded.
static void gb_add(struct pd_wild_set* w, int method, int species, int level, double pct, double* sums) {
    if (!species) return;
    for (int i = 0; i < w->count[method]; i++) {
        struct pd_wild* m = &w->mons[method][i];
        if (m->species != species) continue;
        if (level < m->minLv) m->minLv = level;
        if (level > m->maxLv) m->maxLv = level;
        sums[method * PD_WILD_MAX + i] += pct;
        return;
    }
    if (w->count[method] >= PD_WILD_MAX) return;
    sums[method * PD_WILD_MAX + w->count[method]] = pct;
    w->mons[method][w->count[method]++] = (struct pd_wild) { species, level, level, 0 };
}

// Crystal (CrystalWorld.encounters): the time of day's grass slots, surfing (levels up to +4), the map's rods (a
// time-of-day slot by the clock) and rock smash; headbutt trees aren't listed here.
static bool crystal_wild_here(const struct pd_snapshot* s, struct pd_wild_set* out) {
    static const uint8_t GRASS[7] = { 30, 30, 20, 10, 5, 4, 1 }, WATER[3] = { 60, 30, 10 };
    if (!s->posOk) return false;
    int key = s->mapGroup << 8 | s->mapNum, tod = s->hour >= 0 && s->hour <= 2 ? s->hour : 1;
    const struct pd_gb2_wild* t = pd_gb_wild_crystal;
    for (; t->key >= 0 && t->key != key; t++) {}
    if (t->key < 0) return true;
    double sums[PD_WILD_METHODS * PD_WILD_MAX] = { 0 };
    if (t->rates[tod])
        for (int i = 0; i < 7; i++) gb_add(out, 0, t->grass[tod * 7 + i][1], t->grass[tod * 7 + i][0], GRASS[i], sums);
    if (t->waterRate)
        for (int i = 0; i < 3; i++) {
            gb_add(out, 1, t->water[i][1], t->water[i][0], WATER[i], sums);
            gb_add(out, 1, t->water[i][1], t->water[i][0] + 4, 0, sums);
        }
    for (const struct pd_gb2_fish* f = pd_gb_fish_crystal; t->fishGroup >= 0 && f->group >= 0; f++) {
        if (f->group != t->fishGroup) continue;
        for (int r = 0; r < 3; r++)
            for (int i = 0; i < f->count[r]; i++) {
                int sp = f->rows[r][i][1], lv = f->rows[r][i][2];
                if (!sp) {  // a time-of-day slot: its group's day or night Pokémon
                    if (lv >= pd_gb_time_fish_crystal_count) continue;
                    const uint8_t* tf = pd_gb_time_fish_crystal[lv];
                    sp = tod == 2 ? tf[2] : tf[0];
                    lv = tod == 2 ? tf[3] : tf[1];
                }
                gb_add(out, 3 + r, sp, lv, f->rows[r][i][0] * 100.0 / 256, sums);
            }
    }
    for (const struct pd_gb2_rocks* r = pd_gb_rocks_crystal; t->rockSet >= 0 && r->set >= 0; r++)
        if (r->set == t->rockSet)
            for (int i = 0; i < r->count; i++) gb_add(out, 2, r->rows[i][1], r->rows[i][2], r->rows[i][0], sums);
    for (int m = 0; m < PD_WILD_METHODS; m++) {
        for (int i = 0; i < out->count[m]; i++) out->mons[m][i].pct = (int) (sums[m * PD_WILD_MAX + i] + 0.5);
        qsort(out->mons[m], (size_t) out->count[m], sizeof(struct pd_wild), cmp_wild);
    }
    return true;
}

static bool gb_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out) {
    if (g->guide == PD_GUIDE_GB_CRYSTAL) return crystal_wild_here(s, out);
    static const uint8_t SLOT_ODDS[10] = { 51, 51, 39, 25, 25, 25, 13, 13, 11, 3 };
    const struct pd_gb_wild* t = g->guide == PD_GUIDE_GB_YELLOW ? pd_gb_wild_yellow : g->guide == PD_GUIDE_GB_RED ? pd_gb_wild_red :
                                 g->guide == PD_GUIDE_GB_BLUE ? pd_gb_wild_blue : NULL;
    const struct pd_gb_fish* old = g->guide == PD_GUIDE_GB_YELLOW ? &pd_gb_old_rod_yellow : g->guide == PD_GUIDE_GB_RED ? &pd_gb_old_rod_red : &pd_gb_old_rod_blue;
    const struct pd_gb_fish* good = g->guide == PD_GUIDE_GB_YELLOW ? &pd_gb_good_rod_yellow : g->guide == PD_GUIDE_GB_RED ? &pd_gb_good_rod_red : &pd_gb_good_rod_blue;
    if (!t || !s->posOk) return false;
    double sums[PD_WILD_METHODS * PD_WILD_MAX] = { 0 };
    for (; t->map >= 0 && t->map != s->mapNum; t++) {}
    if (t->map >= 0) {
        for (int i = 0; i < t->grassCount; i++) gb_add(out, 0, t->grass[i][1], t->grass[i][0], SLOT_ODDS[i] * 100.0 / 256, sums);
        for (int i = 0; i < t->waterCount; i++) gb_add(out, 1, t->water[i][1], t->water[i][0], SLOT_ODDS[i] * 100.0 / 256, sums);
        for (int i = 0; i < t->superCount; i++) gb_add(out, 5, t->super[i][1], t->super[i][2], t->super[i][0], sums);
        if (t->waterCount || t->superCount) {
            for (int i = 0; i < old->count; i++) gb_add(out, 3, old->rows[i][1], old->rows[i][2], old->rows[i][0], sums);
            for (int i = 0; i < good->count; i++) gb_add(out, 4, good->rows[i][1], good->rows[i][2], good->rows[i][0], sums);
        }
    }
    for (int m = 0; m < PD_WILD_METHODS; m++) {
        for (int i = 0; i < out->count[m]; i++) out->mons[m][i].pct = (int) (sums[m * PD_WILD_MAX + i] + 0.5);
        qsort(out->mons[m], (size_t) out->count[m], sizeof(struct pd_wild), cmp_wild);
    }
    return true;
}

bool pd_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out) {
    memset(out, 0, sizeof(*out));
    if (g->cfg && g->cfg->gb) return gb_wild_here(g, s, out);
    if (!s->posOk || !pd_guide_rom_ok(g)) return false;
    const struct pd_config* c = g->cfg;
    int stride = header_stride(c);
    for (int h = 0; h < MAX_HEADERS; h++) {
        uint32_t at = c->wildHeaders + (uint32_t) (h * stride);
        const uint8_t* hd = pd_rom_at(g, at, (size_t) stride);
        if (!hd || hd[0] == 0xFF) return false;
        if (hd[0] != s->mapGroup || hd[1] != s->mapNum) continue;
        // the set'th header after this one, while it's still this map's (else the first)
        int set = wild_set_of(c, s, s->mapGroup, s->mapNum);
        if (set > 0) {
            const uint8_t* alt = pd_rom_at(g, at + (uint32_t) (set * stride), (size_t) stride);
            if (alt && alt[0] == hd[0] && alt[1] == hd[1]) hd = alt;
        }
        uint32_t ptr[4];
        for (int m = 0; m < 4; m++) {
            if (c->wildLayout) {
                const int slot[4] = { c->wildLayout->land, c->wildLayout->water, c->wildLayout->rockSmash, c->wildLayout->fishing };
                ptr[m] = u32(hd + 4 + 4 * slot[m]);
                continue;
            }
            // each method from the chosen time of day, or the first set that has it
            ptr[m] = 0;
            int sets = c->wildSets > 1 ? c->wildSets : 1;
            for (int k = -1; k < sets && !ptr[m]; k++) {
                int set = k < 0 ? c->wildSet : k;
                if (k >= 0 && k == c->wildSet) continue;
                ptr[m] = u32(hd + 4 + set * set_stride(c) + 4 * m);
            }
        }
        read_table(g, ptr[0], 0, 12, LAND_ODDS, out, 0);
        read_table(g, ptr[1], 0, 5, WATER_ODDS, out, 1);
        read_table(g, ptr[2], 0, 5, WATER_ODDS, out, 2);
        read_table(g, ptr[3], 0, 2, FISH_ODDS, out, 3);
        read_table(g, ptr[3], 2, 3, FISH_ODDS + 2, out, 4);
        read_table(g, ptr[3], 5, 5, FISH_ODDS + 5, out, 5);
        for (int m = 0; m < PD_WILD_METHODS; m++) {
            qsort(out->mons[m], (size_t) out->count[m], sizeof(struct pd_wild), cmp_wild);
        }
        return true;
    }
    return false;
}

const char* pd_wild_method_name(int method) {
    static const char* const NAMES[PD_WILD_METHODS] = {
        "GRASS / CAVE", "SURFING", "ROCK SMASH", "OLD ROD", "GOOD ROD", "SUPER ROD",
    };
    return method >= 0 && method < PD_WILD_METHODS ? NAMES[method] : "";
}
