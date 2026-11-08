// The dex save read per game (the app's readPokedexState): each fixture is decoded with its own static
// config and the seen / caught sets are those the app's Kotlin tests assert (HackPokedexTest and the
// *DecodeTest files). Usage: pd_dex_test <fixtures dir> [<dir with the ROMs>] - Emerald Rogue's bits are by species,
// so it needs its ROM and is skipped without one.
#include "../core/pd_game.c" // for the static PD_CONFIG_* tables
#include "../core/pd_snapshot.h"
#include <stdio.h>
#include <stdlib.h>

static uint8_t ram[0x40000], iram[0x8000];
static bool rd(void* c, uint32_t a, void* o, size_t n) {
    (void) c;
    if (a >= 0x02000000 && a < 0x02040000 && n <= 0x40000 - (a - 0x02000000)) { memcpy(o, ram + a - 0x02000000, n); return true; }
    if (a >= 0x03000000 && a < 0x03008000 && n <= 0x8000 - (a - 0x03000000)) { memcpy(o, iram + a - 0x03000000, n); return true; }
    return false;
}

struct want { const char* fixture; const struct pd_config* cfg; const char* name; int seen[8], caught[8]; };
#define W(fx, C, ...) { fx, &PD_CONFIG_##C, #C, __VA_ARGS__ }
static const struct want WANT[] = {
    W("dreamstone", DREAMSTONE, { 25, 152, 255, 501, 906 }, { 25, 152, 255, 501, 906 }),
    W("wishes", WISHES, { 6, 25, 94, 155, 448 }, { 6, 25, 94, 155, 448 }),
    W("rowe_battle", ROWE, { 265, 884 }, { 884 }),
    W("imperium", IMPERIUM, { 4 }, { 4 }),
    W("glazed", GLAZED, { 174, 322 }, { 322 }),
    W("unbound_fr", UNBOUND_FR, { 225, 246, 361, 374, 451, 582, 686, 774 }, { 225, 246, 361 }),
    W("orange_islands", ORANGE_ISLANDS, { 16, 25, 150 }, { 25, 150 }),
    W("odyssey_dex", ODYSSEY, { 137, 263, 285, 303, 311, 312, 316, 352 }, { 311, 312 }),
    W("radical_red_dex", RADICAL_RED, { 4, 7 }, { 4 }),
    W("amethyst_dex", AMETHYST, { 182, 340 }, { 340 }),
    W("gaia_dex", GAIA, { 390 }, { 390 }),
    W("celia_dex", CELIA, { 2 }, { 2 }),
    W("heart_and_soul_dex", HEART_AND_SOUL, { 16, 155 }, { 155 }),
    W("lazarus_dex", LAZARUS, { 653 }, { 653 }),
    W("soulgold", SOULGOLD, { 16, 19, 155, 163, 183, 263 }, { 155 }),
    W("soulgold_v12", SOULGOLD_V1_2, { 16, 19, 155, 163, 183, 263 }, { 155 }),
    W("emerald_seaglass_dex", SEAGLASS, { 4, 12 }, { 4 }),
    W("tmt2_dex", TMT2, { 397 }, { 397 }),
};

// Quetzal (HackPokedexTest): seen / caught counts per save, plus some numbers that must be in the caught set.
struct counts { const char* fixture; const struct pd_config* cfg; int seen, caught; bool national; int has[6]; };
#define Q(fx, C, s, c, ...) { fx, &PD_CONFIG_##C, s, c, true, { __VA_ARGS__ } }
#define R(fx, C, s, c, ...) { fx, &PD_CONFIG_##C, s, c, false, { __VA_ARGS__ } } // National Dex off
static const struct counts QUETZAL[] = {
    Q("quetzal", QUETZAL_V9_1, 6, 1, 4),
    Q("quetzal_es", QUETZAL_V9_1_ES, 468, 349, 6, 130),
    Q("quetzal_johto", QUETZAL_V9_1, -1, -1, 4, 215, 408, 442, 627),
    Q("quetzal_084", QUETZAL_V0_8_4, 466, 347, 6, 130),
    Q("quetzal_es_084", QUETZAL_V0_8_4_ES, 466, 347, 6, 130),
    // Recharged Yellow (HackPokedexTest): seen 58 / owned 31 in every release, its party's among them.
    R("recharged_yellow_v180", RECHARGED_YELLOW_V1_8_0, 58, 31, 1, 8, 25, 27, 37),
    R("recharged_yellow_v190", RECHARGED_YELLOW_V1_9_0, 58, 31, 1, 8, 25, 27, 37),
    R("recharged_yellow_v194", RECHARGED_YELLOW_V1_9_4, 58, 31, 1, 8, 25, 27, 37),
    R("recharged_yellow", RECHARGED_YELLOW_V1_9_7, 58, 31, 1, 8, 25, 27, 37),
};

static int check(const struct pd_snapshot* s, bool caught, const int* want, const char* what) {
    int bad = 0, n = 0;
    for (int i = 0; i < 8 && want[i]; i++) n++;
    for (int k = 1; k <= PD_DEX_BYTES * 8; k++) {
        bool has = caught ? pd_dex_caught(s, k) : pd_dex_seen(s, k), expect = false;
        for (int i = 0; i < n; i++) expect |= want[i] == k;
        if (has != expect) { printf("FAIL %s: No. %d is %d, want %d\n", what, k, has, expect); bad++; }
    }
    return bad;
}

// Emerald Rogue v2.2.1-EX: seen {546, 744}, caught {744} (HackPokedexTest).
static int rogue(const char* fixtures, const char* roms) {
    char p[1024];
    snprintf(p, sizeof p, "%s/Pokemon Emerald Rogue (v2.2.1-EX).gba", roms);
    FILE* f = fopen(p, "rb");
    if (!f) { printf("skip Rogue (no ROM)\n"); return 0; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t* rom = malloc((size_t) n);
    if (!rom || fread(rom, 1, (size_t) n, f) != (size_t) n) { fclose(f); return 1; }
    fclose(f);
    memset(ram, 0, sizeof ram); memset(iram, 0, sizeof iram);
    snprintf(p, sizeof p, "%s/emerald_rogue/ewram.bin", fixtures);
    f = fopen(p, "rb"); if (!f) return 1;
    if (fread(ram, 1, sizeof ram, f) == 0) return 1;
    fclose(f);
    snprintf(p, sizeof p, "%s/emerald_rogue/iwram.bin", fixtures);
    f = fopen(p, "rb"); if (f) { if (fread(iram, 1, sizeof iram, f) == 0) return 1; fclose(f); }
    struct pd_game g; memset(&g, 0, sizeof g);
    pd_game_detect(&g, rom, (size_t) n); g.rom = rom; g.romSize = (size_t) n;
    g.cfg = &PD_CONFIG_EMERALD_ROGUE; g.kind = PD_GAME_EMERALD;
    static struct pd_snapshot s; pd_snapshot_read(&s, &g, rd, NULL);
    int bad = !s.dexOk;
    if (bad) printf("FAIL Rogue: no dex\n");
    else bad = check(&s, false, (const int[8]) { 546, 744 }, "Rogue") + check(&s, true, (const int[8]) { 744 }, "Rogue");
    free(rom);
    return bad;
}

int main(int argc, char** argv) {
    int bad = 0;
    if (argc < 2) return 2;
    for (size_t w = 0; w < sizeof WANT / sizeof WANT[0]; w++) {
        char p[1024]; FILE* f;
        memset(ram, 0, sizeof ram); memset(iram, 0, sizeof iram);
        snprintf(p, sizeof p, "%s/%s/ewram.bin", argv[1], WANT[w].fixture);
        f = fopen(p, "rb"); if (!f) { printf("FAIL no %s\n", p); bad++; continue; }
        if (fread(ram, 1, sizeof ram, f) == 0) bad++;
        fclose(f);
        snprintf(p, sizeof p, "%s/%s/iwram.bin", argv[1], WANT[w].fixture);
        f = fopen(p, "rb"); if (f) { if (fread(iram, 1, sizeof iram, f) == 0) bad++; fclose(f); }
        struct pd_game g; memset(&g, 0, sizeof g); g.cfg = WANT[w].cfg; g.kind = PD_GAME_EMERALD;
        static struct pd_snapshot s; pd_snapshot_read(&s, &g, rd, NULL);
        char what[96]; snprintf(what, sizeof what, "%s/%s", WANT[w].fixture, WANT[w].name);
        if (!s.dexOk) { printf("FAIL %s: no dex\n", what); bad++; continue; }
        bad += check(&s, false, WANT[w].seen, what) + check(&s, true, WANT[w].caught, what);
    }
    for (size_t w = 0; w < sizeof QUETZAL / sizeof QUETZAL[0]; w++) {
        const struct counts* q = &QUETZAL[w];
        char p[1024]; FILE* f;
        memset(ram, 0, sizeof ram); memset(iram, 0, sizeof iram);
        snprintf(p, sizeof p, "%s/%s/ewram.bin", argv[1], q->fixture);
        f = fopen(p, "rb"); if (!f) { printf("FAIL no %s\n", p); bad++; continue; }
        if (fread(ram, 1, sizeof ram, f) == 0) bad++;
        fclose(f);
        snprintf(p, sizeof p, "%s/%s/iwram.bin", argv[1], q->fixture);
        f = fopen(p, "rb"); if (f) { if (fread(iram, 1, sizeof iram, f) == 0) bad++; fclose(f); }
        struct pd_game g; memset(&g, 0, sizeof g); g.cfg = q->cfg; g.kind = PD_GAME_EMERALD;
        static struct pd_snapshot s; pd_snapshot_read(&s, &g, rd, NULL);
        if (!s.dexOk) { printf("FAIL %s: no dex\n", q->fixture); bad++; continue; }
        int seen = 0, caught = 0;
        for (int k = 1; k <= PD_DEX_BYTES * 8; k++) { seen += pd_dex_seen(&s, k); caught += pd_dex_caught(&s, k); }
        if ((q->seen >= 0 && seen != q->seen) || (q->caught >= 0 && caught != q->caught)) {
            printf("FAIL %s: seen %d caught %d, want %d / %d\n", q->fixture, seen, caught, q->seen, q->caught); bad++;
        }
        for (int i = 0; i < 6 && q->has[i]; i++) {
            if (!pd_dex_caught(&s, q->has[i])) { printf("FAIL %s: No. %d not caught\n", q->fixture, q->has[i]); bad++; }
        }
        if (s.dexNational != q->national) { printf("FAIL %s: National Dex %d, want %d\n", q->fixture, s.dexNational, q->national); bad++; }
    }
    if (argc > 2) bad += rogue(argv[1], argv[2]);
    printf("%s (%zu games)\n", bad ? "FAILED" : "PASS dex", sizeof WANT / sizeof WANT[0]);
    return bad != 0;
}
