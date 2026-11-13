// pd_docs_shots - the 3DS screens the manual's install guide shows (docs/manual/3ds.md), both screens in one PNG (the
// 400x240 top over the 320x240 bottom) at 3x: the library on its first launch, with games, and its SETTINGS.
//
//   pd_docs_shots <out dir>
//
// No ROM or save is read: the games are file names with a made-up header (the game code and nothing else), so the shots
// carry no game's art, pictures or data, only the library's own look and the games' titles.
#include <stdio.h>
#include <string.h>

#include "../core/pd_canvas.h"
#include "../core/pd_library.h"
#include "../core/pd_settings.h"
#include "pd_png.h"

#define TOP_W 400
#define TOP_H 240
#define SCALE 3

static const char* outDir;

// The games on the SD card in the shots, and the header code each one's made-up ROM carries (NULL: a Game Boy cart,
// whose header the library doesn't read).
static const char* const FILES[][2] = {
    { "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba", "BPRE" },
    { "Pokemon - Emerald Version (USA, Europe).gba", "BPEE" },
    { "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba", "AXVE" },
    { "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba", "BPGE" },
    { "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc", NULL },
    { "Pokemon Unbound (v2.1.1.1).gba", "BPRE" },
};

static const char* code_of(const char* file) {
    for (size_t i = 0; i < sizeof FILES / sizeof FILES[0]; i++)
        if (!strcmp(FILES[i][0], file)) return FILES[i][1];
    return NULL;
}

// A 1 KB "ROM": zeros but the header's title and game code.
static long read_rom(void* ctx, const char* file, long offset, void* buf, long len) {
    (void) ctx;
    static uint8_t rom[1024];
    const char* code = code_of(file);
    if (!code) return -1;
    memset(rom, 0, sizeof rom);
    memcpy(rom + 0xA0, "POKEMON", 7);
    memcpy(rom + 0xAC, code, 4);
    memcpy(rom + 0xB0, "01", 2);
    rom[0xB2] = 0x96;
    if (offset >= (long) sizeof rom) return 0;
    if (len > (long) sizeof rom - offset) len = (long) sizeof rom - offset;
    memcpy(buf, rom + offset, (size_t) len);
    return len;
}

static long read_save(void* ctx, const char* file, uint8_t* buf, long max) {
    (void) ctx;
    (void) file;
    (void) buf;
    (void) max;
    return -1;
}

static bool write_saves(void* ctx, int n, const char* const* files, const uint8_t* const* bytes, const long* lens, char* why, size_t whyLen) {
    (void) ctx;
    (void) n;
    (void) files;
    (void) bytes;
    (void) lens;
    snprintf(why, whyLen, "not in the docs shots");
    return false;
}

static const uint8_t* load_rom(void* ctx, const char* file, size_t* size) {
    (void) ctx;
    (void) file;
    (void) size;
    return NULL;
}

static bool read_card(void* ctx, const char* file, struct pd_game_card* out) {
    (void) ctx;
    (void) file;
    (void) out;
    return false;
}

static void nothing(void* ctx) { (void) ctx; }

static uint32_t topPx[TOP_W * TOP_H], bottomPx[320 * 240];
static uint32_t all[TOP_W * SCALE * TOP_H * 2 * SCALE];
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
    const int w = TOP_W * SCALE;
    for (int y = 0; y < TOP_H * 2 * SCALE; y++) {
        int sy = y / SCALE;
        for (int x = 0; x < w; x++) {
            int sx = x / SCALE;
            uint32_t px = 0;
            if (sy < TOP_H) px = topPx[sy * TOP_W + sx];
            else if (sx >= 40 && sx < 360) px = bottomPx[(sy - TOP_H) * 320 + sx - 40];
            all[y * w + x] = px & 0xFFFFFF;
        }
    }
    char path[1024];
    snprintf(path, sizeof path, "%s/3ds-%s.png", outDir, name);
    pd_png_write(path, w, TOP_H * 2 * SCALE, all);
    printf("%s\n", path);
}

static void keys(unsigned k) {
    pd_library_keys(&lib, k);
    for (int i = 0; i < 4; i++) pd_library_tick(&lib);
}

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: pd_docs_shots <out dir>\n");
        return 2;
    }
    outDir = argv[1];
    static struct pd_settings settings;
    pd_settings_defaults(&settings);
    static const struct pd_library_host host = {
        NULL, read_rom, read_save, write_saves, load_rom, read_card, nothing, nothing, "3DS", NULL,
    };

    // The first launch: an empty roms folder.
    pd_library_init(&lib, &settings, &host);
    pd_library_refresh(&lib);
    shot("library-empty");

    // The games copied in.
    pd_library_init(&lib, &settings, &host);
    for (size_t i = 0; i < sizeof FILES / sizeof FILES[0]; i++) lib.games[lib.count++].file = FILES[i][0];
    pd_library_refresh(&lib);
    for (int i = 0; i < 100; i++) pd_library_tick(&lib);
    keys(PD_LIB_DOWN); // FireRed (the list is by name: Crystal, Emerald, FireRed, ...)
    keys(PD_LIB_DOWN);
    shot("library");

    // SETTINGS: SELECT steps GAMES, SAVE EDITOR, TRADE SAVES, SETTINGS.
    keys(PD_LIB_SELECT);
    keys(PD_LIB_SELECT);
    keys(PD_LIB_SELECT);
    shot("settings");
    return 0;
}
