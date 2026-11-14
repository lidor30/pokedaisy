// pk_api.h off Android: runs linux/make_diagnostic.py's two ROMs (no commercial content) through the plain C API and
// checks what a host sees - the platform, frames, the picture, sound, memory and ROM reads, the save, closing.
// Usage: pk_api_test <dir with diagnostic.gb and diagnostic.gba>. GPL-3.0 (see NOTICE).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "pk_api.h"

static int failures;

#define CHECK(cond, ...) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); failures++; } } while (0)

static void path(char* out, size_t n, const char* dir, const char* name) { snprintf(out, n, "%s/%s", dir, name); }

static void closed(void) {
    uint8_t b = 0x5A;
    char code[5];
    CHECK(pk_api_platform() == PK_API_PLATFORM_NONE, "platform %d with no game", pk_api_platform());
    CHECK(pk_api_video() == NULL, "a picture with no game");
    CHECK(!pk_api_read(0x02000000, &b, 1) && b == 0x5A, "a read with no game");
    CHECK(!pk_api_rom_code(code), "a ROM code with no game");
    CHECK(pk_api_rom_size() == 0, "a ROM size with no game");
}

static void gameBoy(const char* dir) {
    char rom[1024], save[1024];
    path(rom, sizeof rom, dir, "diagnostic.gb");
    path(save, sizeof save, dir, "pk_api_test.sav");
    unlink(save);
    CHECK(pk_api_init(rom, save), "init %s", rom);
    CHECK(pk_api_platform() == PK_API_PLATFORM_GB, "platform %d", pk_api_platform());
    CHECK(pk_api_video_width() == 160 && pk_api_video_height() == 144, "size %dx%d",
          pk_api_video_width(), pk_api_video_height());
    CHECK(pk_api_rom_size() == 32768, "ROM size %lld", (long long) pk_api_rom_size());
    char code[5] = "x";
    CHECK(pk_api_rom_code(code) && code[0] == 0, "a Game Boy game's code is \"%s\"", code);
    uint8_t title[10];
    CHECK(pk_api_rom_read(0x134, title, 10) && !memcmp(title, "PD-TEST-GB", 10), "cart read");
    CHECK(!pk_api_rom_read(32768 - 4, title, 10), "a cart read past the end");

    pk_api_set_audio_rate(PK_API_SAMPLE_RATE);
    pk_api_set_keys(PK_API_KEY_A);
    for (int i = 0; i < 30; i++) pk_api_run_frame();
    uint8_t c0 = 0, c1 = 0;
    CHECK(pk_api_read(0xC000, &c0, 1), "WRAM read");
    int sound = 0, count = 0;
    for (int i = 0; i < 10; i++) {
        pk_api_run_frame();
        const int16_t* s = pk_api_audio(&count);
        for (int j = 0; s && j < count; j++) if (s[j]) sound = 1;
    }
    pk_api_read(0xC000, &c1, 1);
    CHECK((uint8_t) (c1 - c0) == 10, "the frame counter went %u -> %u over 10 frames", c0, c1);
    CHECK(count > 1000 && count < 4000, "%d samples a frame", count);
    CHECK(sound, "no sound from the tone");
    pk_api_finish_save(120);
    pk_api_deinit();
    closed();

    FILE* f = fopen(save, "rb");
    CHECK(f != NULL, "no save written at %s", save);
    if (f) {
        uint8_t first = 0;
        CHECK(fread(&first, 1, 1, f) == 1, "empty save");   // the ROM writes 0x5A to cart RAM 0xA000
        CHECK(first == 0x5A, "save byte 0x%02X", first);
        fclose(f);
    }
}

static void gba(const char* dir) {
    char rom[1024];
    path(rom, sizeof rom, dir, "diagnostic.gba");
    CHECK(pk_api_init(rom, NULL), "init %s", rom);
    CHECK(pk_api_platform() == PK_API_PLATFORM_GBA, "platform %d", pk_api_platform());
    char code[5] = "";
    CHECK(pk_api_rom_code(code) && !strcmp(code, "BPRE"), "game code \"%s\"", code);
    uint8_t header[4];
    CHECK(pk_api_read(0x080000AC, header, 4) && !memcmp(header, "BPRE", 4), "a ROM read on the bus");
    CHECK(!pk_api_rom_read(0, header, 4), "a GBA game has no Game Boy cart read");
    for (int i = 0; i < 10; i++) pk_api_run_frame();
    const uint8_t* px = pk_api_video();
    CHECK(px && pk_api_video_width() == 240 && pk_api_video_height() == 160, "picture %dx%d",
          pk_api_video_width(), pk_api_video_height());
    if (px) CHECK(memcmp(px, px + 4, 3) != 0, "no stripes in the first row");
    pk_api_init(rom, NULL);   // a second init closes the first game
    CHECK(pk_api_platform() == PK_API_PLATFORM_GBA, "reopened");
    pk_api_deinit();
    closed();
}

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <dir with diagnostic.gb / .gba>\n", argv[0]);
        return 2;
    }
    closed();
    gameBoy(argv[1]);
    gba(argv[1]);
    if (failures) fprintf(stderr, "%d failed\n", failures);
    else puts("pk_api: all passed");
    return failures != 0;
}
