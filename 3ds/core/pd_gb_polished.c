// Polished Crystal level-up tables from app-owned release metadata.
#include <string.h>

#include "pd_dex.h"
#include "pd_gb.h"

#define POLISHED_SPECIES 291
extern const struct pd_gb_polished_learn pd_gb_polished_learn_tables[];
extern const int pd_gb_polished_variants_count;

static const struct pd_gb_polished_learn* polished_learn(const struct pd_game* g) {
    if (!g->rom || !g->cfg || !g->cfg->gb || !g->cfg->gb->polished) return NULL;
    for (const struct pd_gb_polished_learn* art = pd_gb_polished_learn_tables; art->sha1; art++)
        if (!strcmp(art->sha1, g->sha1)) return art;
    return NULL;
}

static int byte_at(const struct pd_game* g, uint32_t off) {
    return off < g->romSize ? g->rom[off] : -1;
}

static int word_at(const struct pd_game* g, uint32_t off) {
    const int hi = byte_at(g, off + 1);
    return hi < 0 ? -1 : byte_at(g, off) | hi << 8;
}

static uint32_t bank_address(int bank, int address) {
    if (bank < 0 || address < (bank ? 0x4000 : 1) || address >= (bank ? 0x8000 : 0x4000)) return 0;
    return (uint32_t)(bank ? bank * 0x4000 + address - 0x4000 : address);
}

int pd_polished_growth(const struct pd_game* g, int species) {
    const struct pd_gb_polished_learn* art = polished_learn(g);
    if (!art || species < 1 || species > POLISHED_SPECIES + pd_gb_polished_variants_count) return -1;
    // Polished BaseData + 0x10: Medium Fast, Medium Slow, Fast, Slow.
    static const int normalized[] = {0,3,4,5};
    const int code = byte_at(g, art->base + 34u * (species - 1) + 0x10);
    return code >= 0 && code < 4 ? normalized[code] : -1;
}

int pd_polished_level_moves(const struct pd_game* g, int species, struct pd_level_move* out, int capacity) {
    const struct pd_gb_polished_learn* art = polished_learn(g);
    if (!art || species < 1 || species > POLISHED_SPECIES + pd_gb_polished_variants_count ||
        species == 255 || species == 256) return -1;
    // GetSpeciesAndFormIndex already produced our species ID minus one. Like LearnLevelMoves,
    // skip the evolution block through $ff and read level/move bytes through a second $ff.
    const int bank = art->learnData / 0x4000;
    uint32_t at = bank_address(bank, word_at(g, art->learnPointers + 2u * (species - 1)));
    if (at < art->learnData || at / 0x4000 != (uint32_t)bank) return -1;
    const uint32_t end = (uint32_t)(bank + 1) * 0x4000;
    while (at < end && byte_at(g, at) >= 0 && byte_at(g, at) != 0xff) at++;
    if (at >= end || byte_at(g, at++) != 0xff) return -1;
    for (int n = 0; n < capacity; n++, at += 2) {
        if (at >= end) return -1;
        const int level = byte_at(g, at);
        if (level == 0xff) return n;
        const int move = byte_at(g, at + 1);
        if (level < 0 || level > 100 || move <= 0 || at + 1 >= end) return -1;
        out[n] = (struct pd_level_move){move,level};
    }
    return capacity;
}
