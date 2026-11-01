#include "pd_guide.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include "pd_dex.h"

#define TRAINER_STRIDE 0x28
#define WILD_HEADER 20
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
    default: return NULL;
    }
}

const struct pd_area_thing* pd_areas_for(const struct pd_game* g, int* count) {
    switch (g->guide) {
    case PD_GUIDE_FIRERED: *count = pd_areas_firered_count; return pd_areas_firered;
    case PD_GUIDE_LEAFGREEN: *count = pd_areas_leafgreen_count; return pd_areas_leafgreen;
    case PD_GUIDE_EMERALD: *count = pd_areas_emerald_count; return pd_areas_emerald;
    case PD_GUIDE_RUBY:
    case PD_GUIDE_SAPPHIRE: *count = pd_areas_ruby_sapphire_count; return pd_areas_ruby_sapphire;
    case PD_GUIDE_EMERALD_LEGACY: *count = pd_areas_emerald_legacy_count; return pd_areas_emerald_legacy;
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

bool pd_guide_rom_ok(const struct pd_game* g) {
    const struct pd_config* cfg = g->cfg;
    if (!cfg || !cfg->trainers || !cfg->probeName) return false;
    const uint8_t* name = pd_rom_at(g, cfg->trainers + (uint32_t) cfg->probeTrainer * TRAINER_STRIDE + 4, 12);
    const uint8_t* wild = pd_rom_at(g, cfg->wildHeaders, 1);
    if (!name || !wild || *wild == 0xFF) return false;
    char decoded[32];
    pd_gen3_text(name, 12, decoded, sizeof(decoded));
    return !strcmp(decoded, cfg->probeName);
}

int pd_boss_trainer(const struct pd_guide* gd, const struct pd_boss* b, const struct pd_snapshot* s) {
    if (b->kind == PD_BOSS_BY_FLAGS) {
        for (const uint16_t* p = b->alts; p && p[0]; p += 2)
            if (pd_flag(s, p[0])) return p[1];
        return b->a;
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
    for (int i = 0; i < 64; i++) {
        const uint8_t* e = pd_rom_at(g, at + (uint32_t) i * 2, 2);
        if (!e) return;
        uint16_t v = u16(e);
        if (v == 0xFFFF || (v >> 9) > level) return;
        int move = v & 0x1FF;
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
    const uint8_t* t = pd_rom_at(g, g->cfg->trainers + (uint32_t) trainer * TRAINER_STRIDE, TRAINER_STRIDE);
    if (!t) return 0;
    int flags = t[0], size = t[0x20];
    if (size < 1 || size > PD_TEAM_MAX) return 0;
    const struct pd_config* cfg = g->cfg;
    if (cfg->trainerMonStride) {  // a hack's own TrainerMon (Emerald Legacy's 0x2C), whatever the flags
        const uint8_t* party = pd_rom_at(g, u32(t + 0x24), (size_t) (size * cfg->trainerMonStride));
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
    const uint8_t* party = pd_rom_at(g, u32(t + 0x24), (size_t) (size * stride));
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
    if (!species) return;
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

bool pd_wild_here(const struct pd_game* g, const struct pd_snapshot* s, struct pd_wild_set* out) {
    memset(out, 0, sizeof(*out));
    if (!s->posOk || !pd_guide_rom_ok(g)) return false;
    for (int h = 0; h < MAX_HEADERS; h++) {
        const uint8_t* hd = pd_rom_at(g, g->cfg->wildHeaders + (uint32_t) h * WILD_HEADER, WILD_HEADER);
        if (!hd || hd[0] == 0xFF) return false;
        if (hd[0] != s->mapGroup || hd[1] != s->mapNum) continue;
        read_table(g, u32(hd + 4), 0, 12, LAND_ODDS, out, 0);
        read_table(g, u32(hd + 8), 0, 5, WATER_ODDS, out, 1);
        read_table(g, u32(hd + 12), 0, 5, WATER_ODDS, out, 2);
        read_table(g, u32(hd + 16), 0, 2, FISH_ODDS, out, 3);
        read_table(g, u32(hd + 16), 2, 3, FISH_ODDS + 2, out, 4);
        read_table(g, u32(hd + 16), 5, 5, FISH_ODDS + 5, out, 5);
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
