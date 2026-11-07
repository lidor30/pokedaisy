// The TRAINER CARD per game: each fixture's card is read and drawn from its ROM, and the CRC32 of the ARGB pixels must
// be the one the app's Kotlin renderer records in TrainerCardTest (each matched a headless screenshot, or was recorded
// once it looked right). Usage: pd_card_test <fixtures dir> <dir with the ROMs>; a case without its ROM is skipped.
#include "../core/pd_game.c" // for the static PD_CONFIG_* tables
#include "../core/pd_card.h"
#include "../core/pd_romart.h"
#include <stdio.h>
#include <stdlib.h>

static uint8_t ram[0x40000], iram[0x8000];
static bool rd(void* c, uint32_t a, void* o, size_t n) {
    (void) c;
    if (a >= 0x02000000 && a < 0x02040000 && n <= 0x40000 - (a - 0x02000000)) { memcpy(o, ram + a - 0x02000000, n); return true; }
    if (a >= 0x03000000 && a < 0x03008000 && n <= 0x8000 - (a - 0x03000000)) { memcpy(o, iram + a - 0x03000000, n); return true; }
    return false;
}

// One rendered card: back, colon, minutes override (-1 = the save's) and the CRC TrainerCardTest records.
struct shot { bool back, colon; int minutes; const char* crc; bool female; };
struct want {
    const char* fixture; const struct pd_config* cfg; int kind; const char* rom; struct shot shots[6];
};
#define W(fx, C, K, rom, ...) { fx, &PD_CONFIG_##C, K, rom, { __VA_ARGS__ } }
static const struct want WANT[] = {
    W("firered_vanilla", FIRERED_REV1, PD_GAME_FIRERED, "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
      { false, true, -1, "d62339db", false }, { true, true, -1, "045996a2", false }),
    W("emerald_vanilla", EMERALD, PD_GAME_EMERALD, "Pokemon - Emerald Version (USA, Europe).gba",
      { false, true, 27, "f3e14960", false }, { true, true, -1, "74484c21", false }),
    W("unbound", UNBOUND, PD_GAME_FIRERED, "Pokemon Unbound (v2.1.1.1).gba",
      { false, true, -1, "be68c6aa", false }, { true, true, -1, "5155c5ba", false }),
    W("emerald_legacy", EMERALD_LEGACY, PD_GAME_EMERALD, "Pokemon Emerald Legacy (v1.1.4).gba",
      { false, true, -1, "6da94bfe", false }, { true, true, -1, "74484c21", false }),
    W("emerald_legacy_stars", EMERALD_LEGACY, PD_GAME_EMERALD, "Pokemon Emerald Legacy (v1.1.4).gba",
      { false, false, -1, "19efdadd", false }, { true, true, -1, "90074f39", false }),
    W("recharged_yellow_card", RECHARGED_YELLOW_V1_8_0, PD_GAME_FIRERED, "Pokemon Recharged Yellow (v1.8.0).gba",
      { false, true, -1, "1ff30636", false }, { true, true, -1, "f18cd53c", false }),
    W("recharged_yellow_card_max", RECHARGED_YELLOW_V1_8_0, PD_GAME_FIRERED, "Pokemon Recharged Yellow (v1.8.0).gba",
      { false, true, -1, "a8c33291", false }, { true, true, -1, "3a1532af", false }),
    W("recharged_yellow_card_tint", RECHARGED_YELLOW_V1_8_0, PD_GAME_FIRERED, "Pokemon Recharged Yellow (v1.8.0).gba",
      { true, true, -1, "f2c5a87a", false }),
    W("soulgold_card", SOULGOLD, PD_GAME_EMERALD, "Pokemon SoulGold (v1.2.1).gba",
      { false, true, -1, "da0265f8", false }, { true, true, -1, "03fb2d2e", false }),
    W("soulgold_card_1star", SOULGOLD, PD_GAME_EMERALD, "Pokemon SoulGold (v1.2.1).gba",
      { false, true, -1, "aceca0ed", false }),
    W("soulgold_card_3star", SOULGOLD, PD_GAME_EMERALD, "Pokemon SoulGold (v1.2.1).gba",
      { false, true, -1, "2496c107", false }),
    W("soulgold_card_max", SOULGOLD, PD_GAME_EMERALD, "Pokemon SoulGold (v1.2.1).gba",
      { false, true, -1, "da68241d", false }, { true, true, -1, "fb5a508c", false }, { false, true, -1, "dc9b54be", true }),
    W("soulgold_v12_card_max", SOULGOLD_V1_2, PD_GAME_EMERALD, "Pokemon SoulGold (v1.2.1).gba",
      { false, true, -1, "da68241d", false }),
    W("recharged_yellow_v197_card_max", RECHARGED_YELLOW_V1_9_7, PD_GAME_FIRERED, "Pokemon Recharged Yellow (v1.9.7).gba",
      { false, true, -1, "a8c33291", false }, { true, true, -1, "797f2e18", false }),
    W("lazarus_card", LAZARUS, PD_GAME_EMERALD, "Pokemon Lazarus (v2.0).gba",
      { false, true, -1, "4daa6af1", false }, { true, true, -1, "4c5a1651", false }, { false, true, -1, "cfef77a8", true }),
    W("lazarus_card_max", LAZARUS, PD_GAME_EMERALD, "Pokemon Lazarus (v2.0).gba",
      { false, true, -1, "f6224f73", false }, { true, true, -1, "f2eda466", false }),
};

static uint8_t* slurp(const char* path, long* n) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END); *n = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t* b = malloc((size_t) *n);
    if (!b || fread(b, 1, (size_t) *n, f) != (size_t) *n) { fclose(f); free(b); return NULL; }
    fclose(f);
    return b;
}

static void crc_of(const uint32_t* img, char* out) {
    static uint8_t bytes[PD_CARD_W * PD_CARD_H * 4];
    for (int i = 0; i < PD_CARD_W * PD_CARD_H; i++) {
        bytes[i * 4] = (uint8_t) (img[i] >> 24); bytes[i * 4 + 1] = (uint8_t) (img[i] >> 16);
        bytes[i * 4 + 2] = (uint8_t) (img[i] >> 8); bytes[i * 4 + 3] = (uint8_t) img[i];
    }
    snprintf(out, 9, "%08x", pd_crc32(bytes, sizeof bytes));
}

int main(int argc, char** argv) {
    int bad = 0, ran = 0;
    if (argc < 3) return 2;
    for (size_t w = 0; w < sizeof WANT / sizeof WANT[0]; w++) {
        const struct want* t = &WANT[w];
        char p[1024]; long n = 0, m = 0;
        snprintf(p, sizeof p, "%s/%s", argv[2], t->rom);
        uint8_t* rom = slurp(p, &n);
        if (!rom) { printf("skip %s (no ROM)\n", t->fixture); continue; }
        memset(ram, 0, sizeof ram); memset(iram, 0, sizeof iram);
        snprintf(p, sizeof p, "%s/%s/ewram.bin", argv[1], t->fixture);
        uint8_t* e = slurp(p, &m);
        if (!e) { printf("FAIL no %s\n", p); bad++; free(rom); continue; }
        memcpy(ram, e, (size_t) m < sizeof ram ? (size_t) m : sizeof ram); free(e);
        snprintf(p, sizeof p, "%s/%s/iwram.bin", argv[1], t->fixture);
        e = slurp(p, &m);
        if (e) { memcpy(iram, e, (size_t) m < sizeof iram ? (size_t) m : sizeof iram); free(e); }
        struct pd_game g; memset(&g, 0, sizeof g);
        g.rom = rom; g.romSize = (size_t) n; g.cfg = t->cfg; g.kind = t->kind; g.loadId = (unsigned) (w + 1);
        static struct pd_snapshot s; pd_snapshot_read(&s, &g, rd, NULL);
        struct pd_card_info c;
        if (!pd_card_info(&g, &s, &c)) { printf("FAIL %s: no card info\n", t->fixture); bad++; free(rom); continue; }
        if (getenv("CARD_DEBUG")) printf("%s stars %d drawn %d badges %x hof %x dex %d money %ld nat %d cnt %d magic %x/%x var %x/%x flag %d\n", t->fixture, c.stars, c.drawnStars, c.badges, c.hofDebut, c.dexCaught, c.money, s.dexNational, s.dexCount, s.cardMagic, t->cfg->nationalMagic, pd_var(&s, t->cfg->nationalVar), t->cfg->nationalVarValue, pd_flag(&s, t->cfg->nationalFlag));
        for (int i = 0; i < 6 && t->shots[i].crc; i++) {
            struct pd_card_info k = c;
            if (t->shots[i].minutes >= 0) k.minutes = t->shots[i].minutes;
            if (t->shots[i].female) k.female = true;
            static uint32_t img[PD_CARD_W * PD_CARD_H];
            char got[9] = "";
            if (!pd_card_render(&g, &k, t->shots[i].back, t->shots[i].colon, true, img)) strcpy(got, "(no art)");
            else crc_of(img, got);
            ran++;
            if (strcmp(got, t->shots[i].crc)) { printf("FAIL %s %s: %s, want %s\n", t->fixture, t->shots[i].back ? "back" : "front", got, t->shots[i].crc); bad++; }
        }
        free(rom);
    }
    // Glazed: a card read off the user's screenshot of the game's own (Sakkkuu, 48679, 45452, 22 caught, 21:24, eight
    // badges, the colon blinked off); the render matched that screenshot but for the colon.
    {
        char p[1024]; long n = 0;
        snprintf(p, sizeof p, "%s/Pokemon Glazed (v9.2.0).gba", argv[2]);
        uint8_t* rom = slurp(p, &n);
        if (!rom) printf("skip Glazed (no ROM)\n");
        else {
            struct pd_game g; memset(&g, 0, sizeof g);
            g.rom = rom; g.romSize = (size_t) n; g.cfg = &PD_CONFIG_GLAZED; g.kind = PD_GAME_EMERALD; g.loadId = 99;
            struct pd_card_info c; memset(&c, 0, sizeof c);
            c.style = PD_CARD_GLAZED;
            const uint8_t nm[7] = { 0xCD, 0xD5, 0xDF, 0xDF, 0xDF, 0xE9, 0xE9 };
            memcpy(c.name, nm, 7); c.nameLen = 7;
            c.trainerId = 48679; c.hours = 21; c.minutes = 24; c.money = 45452; c.dexCaught = 22; c.badges = 0xFF;
            static uint32_t img[PD_CARD_W * PD_CARD_H];
            char got[9] = "(no art)";
            if (pd_card_render(&g, &c, false, false, true, img)) crc_of(img, got);
            ran++;
            if (strcmp(got, "d18d3a7f")) { printf("FAIL Glazed screenshot card: %s\n", got); bad++; }
            free(rom);
        }
    }
    printf("%s (%d cards)\n", bad ? "FAILED" : "PASS card", ran);
    return bad != 0;
}
