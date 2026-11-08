#include "pd_dex_details.h"

#include <stdio.h>
#include <string.h>

#include "pd_snapshot.h"
#include "pd_tables.h"

#define EVOS_PER_MON 5
#define EVO_SIZE 8
#define WILD_HEADER 20
#define MAP_MAPSEC 0x14

// Each encounter's chance by slot: gWildMonHeaders' four tables (land, water, rock smash, fishing).
static const uint8_t LAND_PCT[12] = { 20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1 };
static const uint8_t WATER_PCT[5] = { 60, 30, 5, 4, 1 };
static const uint8_t FISH_PCT[10] = { 70, 30, 60, 20, 20, 40, 40, 15, 4, 1 };

static uint16_t u16le(const uint8_t* p) {
    return (uint16_t) (p[0] | p[1] << 8);
}

static uint32_t u32le(const uint8_t* p) {
    return (uint32_t) p[0] | (uint32_t) p[1] << 8 | (uint32_t) p[2] << 16 | (uint32_t) p[3] << 24;
}

bool pd_dex_has_details(const struct pd_game* g) {
    const struct pd_config* c = g->cfg;
    return c && c->dexEvolutions && g->rom;
}

static const uint8_t* evos_of(const struct pd_game* g, int species) {
    return pd_rom_at(g, g->cfg->dexEvolutions + (uint32_t) species * EVOS_PER_MON * EVO_SIZE, EVOS_PER_MON * EVO_SIZE);
}

static int species_count(const struct pd_game* g) {
    return g->cfg->dexSpeciesCount ? g->cfg->dexSpeciesCount : 412;
}

static int walk(const struct pd_game* g, int species, struct pd_evo* out, int n, int max) {
    const uint8_t* e = evos_of(g, species);
    if (!e) return n;
    for (int i = 0; i < EVOS_PER_MON && n < max; i++, e += EVO_SIZE) {
        int method = u16le(e), param = u16le(e + 2), to = u16le(e + 4);
        if (!method || !to) continue;
        bool seen = false;
        for (int k = 0; k < n; k++) seen |= out[k].from == species && out[k].to == to && out[k].method == method;
        if (seen) continue;
        out[n++] = (struct pd_evo) { species, to, method, param };
        n = walk(g, to, out, n, max);
    }
    return n;
}

int pd_dex_family(const struct pd_game* g, int species, struct pd_evo* out, int max) {
    if (!pd_dex_has_details(g)) return 0;
    int first = species, count = species_count(g);
    for (int depth = 0; depth < 3; depth++) {
        int parent = 0;
        for (int s = 1; s < count && !parent; s++) {
            const uint8_t* e = evos_of(g, s);
            for (int i = 0; e && i < EVOS_PER_MON; i++)
                if (u16le(e + i * EVO_SIZE) && u16le(e + i * EVO_SIZE + 4) == first) parent = s;
        }
        if (!parent) break;
        first = parent;
    }
    return walk(g, first, out, 0, max);
}

void pd_dex_evo_text(const struct pd_game* g, const struct pd_evo* e, char* out, size_t len) {
    int p = e->param;
    switch (e->method) {
    case 1: snprintf(out, len, "Level up, high friendship"); break;
    case 2: snprintf(out, len, "Level up, high friendship, day"); break;
    case 3: snprintf(out, len, "Level up, high friendship, night"); break;
    case 4: case 13: snprintf(out, len, "LV %d", p); break;
    case 5: snprintf(out, len, "Trade"); break;
    case 6: snprintf(out, len, "Trade, holding %s", pd_item_name(g, p)); break;
    case 7: snprintf(out, len, "Use %s", pd_item_name(g, p)); break;
    case 8: snprintf(out, len, "LV %d, ATK over DEF", p); break;
    case 9: snprintf(out, len, "LV %d, ATK equals DEF", p); break;
    case 10: snprintf(out, len, "LV %d, DEF over ATK", p); break;
    case 11: case 12: snprintf(out, len, "LV %d, by chance (personality)", p); break;
    case 14: snprintf(out, len, "LV %d, a spare party slot", p); break;
    case 15: snprintf(out, len, "Level up, BEAUTY %d+", p); break;
    default: snprintf(out, len, "Special condition"); break;
    }
}

int pd_dex_tm_moves(const struct pd_game* g, int species, uint16_t* out, int max) {
    const struct pd_config* c = g->cfg;
    if (!pd_dex_has_details(g) || !c->dexTmhm || !c->dexTmhmMoves) return 0;
    const uint8_t* bits = pd_rom_at(g, c->dexTmhm + (uint32_t) species * 8, 8);
    const uint8_t* moves = pd_rom_at(g, c->dexTmhmMoves, 58 * 2);
    if (!bits || !moves) return 0;
    int n = 0;
    for (int i = 0; i < 58 && n < max; i++)
        if (bits[i / 8] >> (i % 8) & 1) out[n++] = u16le(moves + 2 * i);
    return n;
}

static int mapsec_of(const struct pd_game* g, int group, int num) {
    const struct pd_config* c = g->cfg;
    const uint8_t* table = pd_rom_at(g, c->dexMapGroups + (uint32_t) group * 4, 4);
    if (!table) return -1;
    const uint8_t* entry = pd_rom_at(g, u32le(table) + (uint32_t) num * 4, 4);
    if (!entry) return -1;
    const uint8_t* map = pd_rom_at(g, u32le(entry), MAP_MAPSEC + 1);
    return map ? map[MAP_MAPSEC] : -1;
}

static int add_spot(struct pd_spot* out, int n, int max, int mapsec, int method, int minLv, int maxLv, int pct) {
    for (int i = 0; i < n; i++) {
        struct pd_spot* s = &out[i];
        if (s->mapsec != mapsec || s->method != method) continue;
        if (minLv < s->minLevel) s->minLevel = minLv;
        if (maxLv > s->maxLevel) s->maxLevel = maxLv;
        s->pct += pct;
        return n;
    }
    if (n < max) out[n++] = (struct pd_spot) { mapsec, method, minLv, maxLv, pct };
    return n;
}

static int scan(const struct pd_game* g, const uint8_t* info, int species, int slots, const uint8_t* pct, int method, int mapsec,
                struct pd_spot* out, int n, int max) {
    uint32_t mons = u32le(info);
    if (!mons) return n;
    const uint8_t* list = pd_rom_at(g, u32le(info) ? u32le(info) : 0, 8);
    if (!list) return n;
    const uint8_t* m = pd_rom_at(g, u32le(list + 4), (size_t) slots * 4);
    if (!m) return n;
    for (int i = 0; i < slots; i++, m += 4)
        if (u16le(m + 2) == species) n = add_spot(out, n, max, mapsec, method, m[0], m[1], pct[i]);
    return n;
}

int pd_dex_spots(const struct pd_game* g, int species, struct pd_spot* out, int max) {
    const struct pd_config* c = g->cfg;
    if (!pd_dex_has_details(g) || !c->wildHeaders || !c->dexMapGroups) return 0;
    int n = 0;
    for (int h = 0; h < 200; h++) {
        const uint8_t* hd = pd_rom_at(g, c->wildHeaders + (uint32_t) h * WILD_HEADER, WILD_HEADER);
        if (!hd || hd[0] == 0xFF) break;
        int mapsec = mapsec_of(g, hd[0], hd[1]);
        if (mapsec < 0) continue;
        n = scan(g, hd + 4, species, 12, LAND_PCT, PD_SPOT_GRASS, mapsec, out, n, max);
        n = scan(g, hd + 8, species, 5, WATER_PCT, PD_SPOT_SURF, mapsec, out, n, max);
        n = scan(g, hd + 12, species, 5, WATER_PCT, PD_SPOT_ROCK, mapsec, out, n, max);
        n = scan(g, hd + 16, species, 10, FISH_PCT, PD_SPOT_FISH, mapsec, out, n, max);
    }
    return n;
}
