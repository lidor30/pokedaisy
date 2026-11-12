// pd_library_preview - the 3DS library on real files, both screens a PNG per step (the 400x240 top over the 320x240 bottom):
// GAMES as a list and a grid, SETTINGS and a pick-list, SAVE EDITOR (a Pokémon picked up, the money) and TRADE SAVES. ROMs
// and saves are read where they are and never written: a "write" lands in the out dir.
//
//   pd_library_preview <rom dir> <save dir> <out dir> [<fixture dir> <its ROM>]
//
// The fixture (a RAM dump and its ROM) stands in for a session that just ended: its card (pd_game_card_fill) is the one
// the first FireRed in the list shows.
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../core/pd_canvas.h"
#include "../core/pd_game.h"
#include "../core/pd_library.h"
#include "../core/pd_settings.h"
#include "../core/pd_snapshot.h"
#include "../core/pd_style.h"
#include "../core/pd_ui.h"
#include "pd_png.h"

#define TOP_W 400
#define TOP_H 240

static const char* romDir;
static const char* saveDir;
static const char* outDir;

static const char* const FILES[] = {
    "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
    "Pokemon - Emerald Version (USA, Europe).gba",
    "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba",
    "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba",
    "unbound.gba",  // Pokemon Unbound (v2.1.1.1), under a short name as the player's SD card has it
    "monomyth.gba", // Pokemon Monomyth (v1.0)
    "Pokemon Radical Red (v4.1).gba",
};
// What the short names stand for in the ROM dir.
static const char* const REAL[][2] = {
    { "unbound.gba", "Pokemon Unbound (v2.1.1.1).gba" },
    { "monomyth.gba", "Pokemon Monomyth (v1.0).gba" },
};

static void rom_path(const char* file, char* out, size_t len) {
    const char* real = file;
    for (size_t i = 0; i < sizeof REAL / sizeof REAL[0]; i++)
        if (!strcmp(REAL[i][0], file)) real = REAL[i][1];
    snprintf(out, len, "%s/%s", romDir, real);
}

static long read_rom(void* ctx, const char* file, long offset, void* buf, long len) {
    (void) ctx;
    char path[1024];
    rom_path(file, path, sizeof path);
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    fseek(f, offset, SEEK_SET);
    long n = (long) fread(buf, 1, (size_t) len, f);
    fclose(f);
    return n;
}

static long read_save(void* ctx, const char* file, uint8_t* buf, long max) {
    (void) ctx;
    char base[512], path[1024];
    snprintf(base, sizeof base, "%s", file);
    char* dot = strrchr(base, '.');
    if (dot) *dot = 0;
    static const char* const EXT[] = { ".srm", ".sav" };
    for (int i = 0; i < 2; i++) {
        snprintf(path, sizeof path, "%s/%s%s", saveDir, base, EXT[i]);
        FILE* f = fopen(path, "rb");
        if (!f) continue;
        long n = (long) fread(buf, 1, (size_t) max, f);
        fclose(f);
        return n;
    }
    return -1;
}

static int writes;

static bool write_saves(void* ctx, int n, const char* const* files, const uint8_t* const* bytes, const long* lens, char* why, size_t whyLen) {
    (void) ctx;
    (void) why;
    (void) whyLen;
    for (int i = 0; i < n; i++) {
        char path[1024];
        snprintf(path, sizeof path, "%s/written-%d-%s.sav", outDir, ++writes, files[i]);
        FILE* f = fopen(path, "wb");
        if (!f) return false;
        fwrite(bytes[i], 1, (size_t) lens[i], f);
        fclose(f);
    }
    return true;
}

static uint8_t romBuf[32 << 20];

static const uint8_t* load_rom(void* ctx, const char* file, size_t* size) {
    (void) ctx;
    long n = read_rom(NULL, file, 0, romBuf, sizeof romBuf);
    if (n <= 0) return NULL;
    *size = (size_t) n;
    return romBuf;
}

static struct pd_game_card fixtureCard;
static bool hasFixtureCard;

static bool read_card(void* ctx, const char* file, struct pd_game_card* out) {
    (void) ctx;
    if (!hasFixtureCard || strcmp(file, FILES[0])) return false;
    *out = fixtureCard;
    return true;
}

static void settings_changed(void* ctx) { (void) ctx; }
static void cache_changed(void* ctx) { (void) ctx; }

static uint8_t ewram[0x40000], iwram[0x8000];

static bool fixture_read(void* ctx, uint32_t addr, void* out, size_t len) {
    const struct pd_game* g = ctx;
    if (addr >= 0x02000000 && addr + len <= 0x02040000) memcpy(out, ewram + (addr - 0x02000000), len);
    else if (addr >= 0x03000000 && addr + len <= 0x03008000) memcpy(out, iwram + (addr - 0x03000000), len);
    else return pd_rom_read(g, addr, out, len);
    return true;
}

static bool load(const char* path, uint8_t* buf, size_t len) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    size_t n = fread(buf, 1, len, f);
    fclose(f);
    return n == len;
}

// A session that just ended: the fixture's RAM read with its ROM, made into the card the library keeps.
static void fixture_card(const char* dir, const char* romFile) {
    char path[1024];
    snprintf(path, sizeof path, "%s/ewram.bin", dir);
    if (!load(path, ewram, sizeof ewram)) return;
    snprintf(path, sizeof path, "%s/iwram.bin", dir);
    if (!load(path, iwram, sizeof iwram)) return;
    static uint8_t rom[32 << 20];
    FILE* f = fopen(romFile, "rb");
    size_t n = f ? fread(rom, 1, sizeof rom, f) : 0;
    if (f) fclose(f);
    if (n < 0xC0) return;
    static struct pd_game g;
    pd_game_detect(&g, rom, n);
    g.rom = rom;
    g.romSize = n;
    pd_game_identify(&g);
    static struct pd_snapshot snap;
    pd_snapshot_read(&snap, &g, fixture_read, &g);
    hasFixtureCard = pd_game_card_fill(&fixtureCard, &g, &snap, 1);
}

static uint32_t topPx[TOP_W * TOP_H], bottomPx[320 * 240], all[TOP_W * TOP_H * 2];
static struct pd_library lib;

static void shot(const char* name) {
    struct pd_canvas b, t;
    pd_canvas_init(&b, 320, 240, bottomPx);
    pd_canvas_init(&t, TOP_W, TOP_H, topPx);
    // The top screen waits for the cursor to rest: let it.
    for (int i = 0; i < 8; i++) {
        lib.frame++;
        pd_library_draw_top(&lib, &t);
    }
    pd_library_draw(&lib, &b);
    pd_library_draw_top(&lib, &t);
    for (int y = 0; y < TOP_H; y++) {
        for (int x = 0; x < TOP_W; x++) all[y * TOP_W + x] = topPx[y * TOP_W + x] & 0xFFFFFF;
        for (int x = 0; x < TOP_W; x++) all[(TOP_H + y) * TOP_W + x] = 0;
        for (int x = 0; x < 320; x++) all[(TOP_H + y) * TOP_W + 40 + x] = bottomPx[y * 320 + x] & 0xFFFFFF;
    }
    char path[1024];
    snprintf(path, sizeof path, "%s/library-%s.png", outDir, name);
    pd_png_write(path, TOP_W, TOP_H * 2, all);
    printf("%s\n", path);
}

static void keys(unsigned k) {
    pd_library_keys(&lib, k);
    for (int i = 0; i < 4; i++) pd_library_tick(&lib); // LOADING's frames
}

// A tap where the last drawing put [x, y] (bottom screen coordinates).
static void tap_at(int x, int y) {
    struct pd_canvas b;
    pd_canvas_init(&b, 320, 240, bottomPx);
    pd_library_draw(&lib, &b); // the hits as the screen shows them now
    pd_library_touch(&lib, x, y, true);
    pd_library_touch(&lib, 0, 0, false);
}

int main(int argc, char** argv) {
    if (argc != 4 && argc != 6) {
        fprintf(stderr, "usage: pd_library_preview <rom dir> <save dir> <out dir> [<fixture dir> <its ROM>]\n");
        return 2;
    }
    romDir = argv[1];
    saveDir = argv[2];
    outDir = argv[3];
    if (argc == 6) fixture_card(argv[4], argv[5]);
    static struct pd_settings settings;
    pd_settings_defaults(&settings);
    static const struct pd_library_host host = {
        NULL, read_rom, read_save, write_saves, load_rom, read_card, settings_changed, cache_changed, "1.6.0 (3DS)", NULL,
    };
    pd_library_init(&lib, &settings, &host);
    lib.batteryPct = 82;   // the 3DS host reads the console's
    for (size_t i = 0; i < sizeof FILES / sizeof FILES[0]; i++) {
        char path[1024];
        rom_path(FILES[i], path, sizeof path);
        FILE* f = fopen(path, "rb");
        if (!f) {
            printf("skip %s (not in %s)\n", FILES[i], romDir);
            continue;
        }
        fclose(f);
        lib.games[lib.count++].file = FILES[i];
    }
    pd_library_refresh(&lib);
    pd_library_played(&lib, 0, 100);
    pd_library_played(&lib, 4, 50);
    // Name every game by its contents first (the console spreads it over frames).
    for (int i = 0; i < 20000; i++) {
        pd_library_tick(&lib);
        bool done = lib.hashing < 0;
        for (int k = 0; k < lib.count; k++) done &= lib.games[k].named || lib.games[k].console != PD_CONSOLE_GBA;
        if (done) break;
    }
    for (int i = 0; i < lib.count; i++)
        printf("%-60s %-4s %s%s %s\n", lib.games[i].file, lib.games[i].code, lib.games[i].hack ? "HACK " : "", lib.games[i].title,
               lib.games[i].sha1);

    shot("games");
    keys(PD_LIB_DOWN);
    keys(PD_LIB_DOWN);
    shot("games-down");
    keys(PD_LIB_R);
    keys(PD_LIB_R);
    keys(PD_LIB_R);
    keys(PD_LIB_R);
    shot("games-hacks");
    keys(PD_LIB_L);
    keys(PD_LIB_L);
    keys(PD_LIB_L);
    keys(PD_LIB_L);
    settings.libraryGrid = true;
    shot("games-grid");
    settings.libraryGrid = false;

    keys(PD_LIB_SELECT);
    keys(PD_LIB_SELECT);
    keys(PD_LIB_SELECT); // SETTINGS
    shot("settings");
    keys(PD_LIB_DOWN);
    keys(PD_LIB_DOWN); // THEME
    keys(PD_LIB_A);
    shot("settings-pick");
    keys(PD_LIB_B);
    settings.darkMode = true;
    keys(PD_LIB_SELECT); // GAMES, dark
    shot("games-dark");
    settings.darkMode = false;

    // A game picked: LOADING over both screens until it starts.
    {
        struct pd_canvas b, t;
        pd_canvas_init(&b, 320, 240, bottomPx);
        pd_canvas_init(&t, TOP_W, TOP_H, topPx);
        lib.picked = lib.list.items[1];
        pd_library_draw_loading(&lib, &b, &t);
        for (int y = 0; y < TOP_H; y++) {
            for (int x = 0; x < TOP_W; x++) all[y * TOP_W + x] = topPx[y * TOP_W + x] & 0xFFFFFF;
            for (int x = 0; x < TOP_W; x++) all[(TOP_H + y) * TOP_W + x] = 0;
            for (int x = 0; x < 320; x++) all[(TOP_H + y) * TOP_W + 40 + x] = bottomPx[y * 320 + x] & 0xFFFFFF;
        }
        char path[1024];
        snprintf(path, sizeof path, "%s/library-loading.png", outDir);
        pd_png_write(path, TOP_W, TOP_H * 2, all);
        lib.picked = -1;
    }

    // SAVE EDITOR on the first game.
    tap_at(20, 3 + 45 + 20);
    shot("editor-pick");
    keys(PD_LIB_A);
    shot("editor");
    keys(PD_LIB_A); // pick up the first Pokémon
    keys(PD_LIB_RIGHT);
    keys(PD_LIB_RIGHT);
    shot("editor-moving");
    keys(PD_LIB_A);
    keys(PD_LIB_DOWN);
    keys(PD_LIB_DOWN);
    keys(PD_LIB_DOWN);
    keys(PD_LIB_DOWN);
    keys(PD_LIB_DOWN); // the buttons
    keys(PD_LIB_LEFT);
    keys(PD_LIB_A); // MONEY
    keys(PD_LIB_UP);
    shot("editor-money");
    keys(PD_LIB_A);
    keys(PD_LIB_X); // SAVE
    shot("editor-save");
    keys(PD_LIB_A);
    shot("editor-saved");
    keys(PD_LIB_A);
    keys(PD_LIB_B);

    // TRADE SAVES: Emerald then Ruby.
    tap_at(20, 3 + 2 * 45 + 20);
    keys(PD_LIB_DOWN);
    keys(PD_LIB_A);
    shot("trade-pick-second");
    keys(PD_LIB_DOWN);
    keys(PD_LIB_A);
    keys(PD_LIB_A); // the first Pokémon of the first save
    keys(PD_LIB_X); // the other side
    for (int i = 0; i < 29; i++) keys(PD_LIB_RIGHT); // the box's last place (empty)
    keys(PD_LIB_A);
    shot("trade");
    keys(PD_LIB_Y);
    shot("trade-confirm");
    keys(PD_LIB_A);
    shot("trade-done");
    printf("%d saves written to %s\n", writes, outDir);
    return 0;
}
