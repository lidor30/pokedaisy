// pk_api.h off Android: runs linux/make_diagnostic.py's two ROMs (no commercial content) through the plain C API and
// checks what a host sees - the platform, frames, the picture, sound, memory and ROM reads, the save, closing.
// Usage: pk_api_test <dir with diagnostic.gb and diagnostic.gba>. GPL-3.0 (see NOTICE).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "pk_api.h"
#include "pk_coop_api.h"
#include "pk_core_access.h"
#include "pk_wide.h"
#include <mgba/core/core.h>

static int failures;

#define CHECK(cond, ...) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); failures++; } } while (0)

static void path(char* out, size_t n, const char* dir, const char* name) { snprintf(out, n, "%s/%s", dir, name); }

static void closed(void) {
    pk_api_set_tint(0, 0, 0);  // safe before init and after deinit
    uint8_t b = 0x5A;
    char code[5];
    CHECK(pk_api_platform() == PK_API_PLATFORM_NONE, "platform %d with no game", pk_api_platform());
    CHECK(pk_api_video() == NULL, "a picture with no game");
    CHECK(!pk_api_read(0x02000000, &b, 1) && b == 0x5A, "a read with no game");
    CHECK(!pk_api_rom_code(code), "a ROM code with no game");
    CHECK(pk_api_rom_size() == 0, "a ROM size with no game");
    uint8_t zero = 0;
    CHECK(!pk_api_rom_patch(0x08000000, &zero, 1, 0x08000000, &zero, 1), "patch with no game");
    pk_api_rom_unpatch();
    CHECK(!pk_api_field_configure(0xDD88761C) && pk_api_field_state() == 0, "field observation without a game");
    CHECK(!pk_api_field_wild(0x02000020, false) && pk_api_field_step(true, 0) == 0, "field options without a game");
}

static void gameBoy(const char* dir) {
    char rom[1024], save[1024];
    path(rom, sizeof rom, dir, "diagnostic.gb");
    path(save, sizeof save, dir, "pk_api_test.sav");
    unlink(save);
    CHECK(pk_api_init(rom, save), "init %s", rom);
    CHECK(pk_api_platform() == PK_API_PLATFORM_GB, "platform %d", pk_api_platform());
    uint8_t zero = 0;
    CHECK(!pk_api_rom_patch(0x08000000, &zero, 1, 0x08000000, &zero, 1), "GBA option patch applied to Game Boy");
    CHECK(!pk_api_field_configure(0xDD88761C) && pk_api_field_state() == 0, "GBA field table applied to Game Boy");
    CHECK(!pk_api_field_wild(0x02000020, false) && pk_api_field_step(true, 0) == 0, "GBA field options applied to Game Boy");
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
    uint8_t rideFrames[PK_API_RIDE_FRAME_BYTES] = {0}, ridePalette[32] = {0};
    char rideInfo[160];
    pk_api_ride_set(rideFrames, sizeof rideFrames, ridePalette, sizeof ridePalette, 64);
    pk_api_ride_show(true);
    pk_ride_info(rideInfo, sizeof rideInfo);
    CHECK(strstr(rideInfo, "on 1 have 1 w 64"), "valid ride assets were rejected: %s", rideInfo);
    pk_api_ride_set(rideFrames, sizeof rideFrames - 1, ridePalette, sizeof ridePalette, 64);
    pk_ride_info(rideInfo, sizeof rideInfo);
    CHECK(strstr(rideInfo, "have 0"), "short ride frame data did not clear assets: %s", rideInfo);
    pk_api_ride_show(false);
    struct mCore* core = pkMainCore();
    uint8_t original = core->busRead8(core, 0x080000A0), replacement = original ^ 0xFF, visible = 0;
    const int32_t addresses[] = {0x080000A0, 0x080000A1};
    const int32_t lengths[] = {1, 1};
    const int32_t crcs[] = {(int32_t) 0xB969BE79u, 0};  // CRC of "P", then an intentional mismatch
    const uint8_t bytes[] = {replacement, replacement};
    CHECK(pk_api_look_blocks(addresses, lengths, crcs, 1, bytes, 1) == 1, "look block refused");
    CHECK(core->busRead8(core, addresses[0]) == replacement, "look did not change live ROM");
    CHECK(pk_api_look_blocks(NULL, NULL, NULL, 0, NULL, 0) == 0, "look clear refused");
    CHECK(core->busRead8(core, addresses[0]) == original, "look clear did not restore ROM");
    CHECK(pk_api_look_blocks(addresses, lengths, crcs, 2, bytes, 2) == -1, "look mismatch accepted");
    CHECK(core->busRead8(core, addresses[0]) == original, "failed look was partially applied");
    CHECK(pk_api_look_blocks(addresses, lengths, crcs, 1, bytes, 2) == -1, "trailing bytes accepted");
    CHECK(core->busRead8(core, addresses[0]) == original, "malformed look changed live ROM");
    CHECK(!pk_api_look_refresh(NULL, 0), "unknown field refreshed");
    CHECK(pk_api_look_repalette(bytes, sizeof bytes, bytes, sizeof bytes) == 0, "short palette accepted");
    CHECK(!pk_api_rom_patch(0x080000A0, NULL, 1, 0x080000A0, &replacement, 1), "null expected bytes accepted");
    CHECK(!pk_api_rom_patch(0x080000A0, &original, 1, 0x080000A0, NULL, 1), "null replacement accepted");
    CHECK(!pk_api_rom_patch(0x080000A0, &original, 65, 0x080000A0, &replacement, 1), "oversized expected bytes accepted");
    CHECK(!pk_api_rom_patch(0x080000A0, &original, 1, 0x080000A0, &replacement, 5), "oversized replacement accepted");
    CHECK(!pk_api_rom_patch(0x080000A0, &replacement, 1, 0x080000A0, &replacement, 1), "mismatched bytes accepted");
    CHECK(!pk_api_rom_patch(0x080000A0, &original, 1, 0x02000000, &replacement, 1), "RAM accepted as ROM patch");
    CHECK(!pk_api_rom_patch(0x080000A0, &original, 1, 0x0DFFFFFF, &replacement, 4), "patch crossing ROM boundary accepted");
    CHECK(!pk_api_rom_patch(0xFFFFFFFF, &original, 1, 0x080000A0, &replacement, 1), "overflowing expected address accepted");
    CHECK(core->busRead8(core, 0x080000A0) == original, "failed patch changed the live ROM");
    CHECK(pk_api_rom_patch(0x080000A0, &original, 1, 0x080000A0, &replacement, 1), "valid option patch refused");
    CHECK(core->busRead8(core, 0x080000A0) == replacement, "live ROM not patched");
    CHECK(pk_api_read(0x080000A0, &visible, 1) && visible == original, "identity read exposed patch bytes");
    pk_api_rom_unpatch();
    CHECK(core->busRead8(core, 0x080000A0) == original, "unpatch did not restore live ROM");
    CHECK(pk_api_field_configure(0xDD88761C), "known GBA field table");
    CHECK(pk_api_field_state() & PK_API_FIELD_KNOWN, "field observer missing its known bit");
    CHECK(!pk_api_field_wild(0x04000130, false), "I/O accepted as encounter switch");
    core->busWrite8(core, 0x02000020, 0);
    CHECK(pk_api_field_wild(0x02000020, false), "verified EWRAM switch refused");
    CHECK(pk_api_field_step(false, 0) & PK_API_FIELD_WILD_HELD, "OFF did not take encounter ownership");
    CHECK(core->busRead8(core, 0x02000020) == 1, "OFF did not hold the switch");
    CHECK(pk_api_field_state() & PK_API_FIELD_WILD_HELD, "observer lost encounter ownership");
    CHECK(core->busRead8(core, 0x02000020) == 1, "observation changed the encounter switch");
    pk_api_field_step(false, 100);
    CHECK(core->busRead8(core, 0x02000020) == 0, "ON did not restore encounters");
    core->busWrite8(core, 0x02000020, 1);
    CHECK(pk_api_field_wild(0x02000020, true), "resumed OFF switch refused");
    pk_api_field_step(false, 100);
    CHECK(core->busRead8(core, 0x02000020) == 0, "resumed OFF ownership was not restored");
    CHECK(pk_api_field_wild(0, false), "disabling switch refused");
    CHECK(!pk_api_field_configure(0x12345678) && pk_api_field_state() == 0, "unknown CRC retained a field table");
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

static void tint(const char* dir) {
    char rom[1024];
    path(rom, sizeof rom, dir, "diagnostic.gba");
    CHECK(pk_api_init(rom, NULL), "tint diagnostic init");
    for (int i = 0; i < 10; i++) pk_api_run_frame(); // original diagnostic reaches its idle loop
    struct mCore* core = pkMainCore();
    // One synthetic tile, alternating map/text palettes. No commercial code or art.
    core->busWrite16(core, 0x04000000, 0x0100); // mode 0, BG0
    core->busWrite16(core, 0x04000008, 31 << 8); // map in screen block 31
    for (int i = 0; i < 16; i++) core->busWrite16(core, 0x06000000 + i * 2, 0x1111);
    for (int i = 0; i < 1024; i++) core->busWrite16(core, 0x0600F800 + i * 2, i % 2 ? 13 << 12 : 0);
    core->busWrite16(core, 0x05000002, 0x7FFF);
    core->busWrite16(core, 0x050001A2, 0x7FFF);
    pk_api_run_frame(); pk_api_run_frame();
    const uint8_t* px = pk_api_video();
    uint8_t plain[3]; memcpy(plain, px + 4, 3);
    CHECK(plain[0] > 240 && !memcmp(plain, px + 9 * 4, 3), "synthetic palettes not visible");
    pk_api_set_tint(135, 150, 225);
    pk_api_run_frame(); pk_api_run_frame();
    CHECK(px[4] < px[5] && px[5] < px[6] && px[6] < plain[2], "field palette did not become blue at night");
    CHECK(!memcmp(plain, px + 9 * 4, 3), "text-window palette was tinted");
    CHECK(core->busRead16(core, 0x05000002) == 0x7FFF && core->busRead16(core, 0x050001A2) == 0x7FFF,
          "tint changed emulated palette memory");
    pk_api_set_tint(-1, 500, 256);
    pk_api_run_frame(); pk_api_run_frame();
    CHECK(px[4] == 0 && px[5] == plain[1] && px[6] == plain[2], "tint channel bounds not clamped");
    pk_api_set_tint(256, 256, 256);
    pk_api_run_frame(); pk_api_run_frame();
    CHECK(!memcmp(plain, px + 4, 3), "OFF did not restore field colors exactly");
    pk_api_deinit();
    path(rom, sizeof rom, dir, "diagnostic.gb");
    CHECK(pk_api_init(rom, NULL), "GB tint diagnostic init");
    for (int i = 0; i < 30; i++) pk_api_run_frame();
    uint8_t row[160 * 4]; memcpy(row, pk_api_video(), sizeof row);
    pk_api_set_tint(0, 0, 0);
    pk_api_run_frame(); pk_api_run_frame();
    CHECK(!memcmp(row, pk_api_video(), sizeof row), "GBA tint changed the Game Boy picture");
    pk_api_deinit();
    closed();
}

static void soundIsolation(const char* dir) {
    char gbPath[1024], soundPath[1024], gbaPath[1024];
    path(gbPath, sizeof gbPath, dir, "diagnostic.gb");
    path(soundPath, sizeof soundPath, dir, "diagnostic-sound.gb");
    path(gbaPath, sizeof gbaPath, dir, "diagnostic.gba");
    CHECK(pk_api_sound_open(NULL) == NULL, "renderer opened a null cartridge");
    if (!pk_api_init(gbPath, NULL)) {
        CHECK(0, "player's GB cartridge could not open");
        return;
    }
    for (int i = 0; i < 30; i++) pk_api_run_frame();
    uint8_t before = 0, after = 0;
    pk_api_read(0xC000, &before, 1);
    struct pk_sound_renderer* gb = pk_api_sound_open(soundPath);
    struct pk_sound_renderer* gba = pk_api_sound_open(gbaPath);
    CHECK(gb && gba, "renderers could not open both cartridges");
    uint8_t code[4] = {0};
    CHECK(pk_api_sound_read(gba, 0x080000AC, code, sizeof code) && !memcmp(code, "BPRE", 4),
          "GBA renderer read the wrong cartridge");
    CHECK(!pk_api_sound_park_gba(gb, 0x08000000) && !pk_api_sound_song(gb, 0x08000001, 0, 0),
          "GBA music operations accepted a GB cartridge");
    CHECK(!pk_api_sound_park_gb(gba, 0xC100) && !pk_api_sound_call_gb(gba, 0x150, 0, 0, 0xC100),
          "GB music operations accepted a GBA cartridge");

    bool heardSound = false;
    for (int i = 0; i < 30; i++) {
        pk_api_sound_frame(gb);
        pk_api_sound_frame(gba);
        int16_t samples[4096];
        int n = pk_api_sound_audio(gb, samples, 4096);
        for (int k = 0; k < n; k++) heardSound |= samples[k] != 0;
    }
    CHECK(heardSound, "GB renderer delivered no audio from the diagnostic tone");
    CHECK(pk_api_sound_park_gb(gb, 0xC100), "GB renderer didn't enter the park loop");
    uint8_t spin[2] = {0};
    CHECK(pk_api_sound_read(gb, 0xC100, spin, 2) && spin[0] == 0x18 && spin[1] == 0xFE,
          "the GB renderer's private park loop wasn't installed");
    uint8_t soundValue = 0;
    CHECK(pk_api_sound_call_gb(gb, 0x310, 0x56, 0, 0xC100) && pk_api_sound_read(gb, 0xC102, &soundValue, 1) &&
          soundValue == 0x56, "GB render call didn't write the expected value to its private WRAM");
    pk_api_read(0xC100, spin, 2);
    CHECK(spin[0] != 0x18 || spin[1] != 0xFE, "renderer changed the player's RAM");
    pk_api_sound_close(gb);
    pk_api_sound_close(gba);
    pk_api_read(0xC000, &after, 1);
    CHECK(after == before, "renderer advanced the player's game");
    pk_api_run_frame();
    pk_api_read(0xC000, &after, 1);
    CHECK((uint8_t) (after - before) == 1, "the player's game didn't continue after closing the renderers");
    pk_api_deinit();
    closed();
}

static void achievementMemory(const char* dir) {
    char rom[1024];
    uint8_t bytes[4] = {0};
    CHECK(pk_api_ra_read_memory(0, bytes, sizeof bytes) == 0, "no game read");
    path(rom, sizeof rom, dir, "diagnostic.gba");
    CHECK(pk_api_init(rom, NULL), "GBA achievement memory init");
    struct mCore* core = pkMainCore();
    core->busWrite8(core, 0x03007FFF, 0x21);
    core->busWrite8(core, 0x02000000, 0x43);
    CHECK(pk_api_ra_read_memory(0x7FFF, bytes, 2) == 2 && bytes[0] == 0x21 && bytes[1] == 0x43,
          "GBA achievement memory map did not cross IWRAM to EWRAM");
    CHECK(pk_api_ra_read_memory(0x58000, bytes, sizeof bytes) == 0, "GBA mapped past SRAM");
    CHECK(pk_api_ra_read_memory(0, NULL, 1) == 0, "null GBA output accepted");
    pk_api_deinit();

    path(rom, sizeof rom, dir, "diagnostic.gb");
    CHECK(pk_api_init(rom, NULL), "GB achievement memory init");
    core = pkMainCore();
    const uint8_t first = 0x56, second = 0x78, last = 0xAB;
    core->busWrite8(core, 0xC020, 0x12);
    CHECK(pk_api_write(0x1D000, &first, 1) && pk_api_write(0x2D000, &second, 1) &&
          pk_api_write(0x7DFFF, &last, 1), "GB WRAM setup");
    CHECK(pk_api_ra_read_memory(0xC020, bytes, 1) == 1 && bytes[0] == 0x12, "GB CPU bus");
    CHECK(pk_api_ra_read_memory(0xD000, bytes, 1) == 1 && bytes[0] == first, "GB fixed WRAM bank 1");
    CHECK(pk_api_ra_read_memory(0x10000, bytes, 1) == 1 && bytes[0] == second, "GBC extended WRAM bank 2");
    CHECK(pk_api_ra_read_memory(0x15FFF, bytes, 2) == 1 && bytes[0] == last,
          "GBC bank 7 stops at the last mapped byte");
    CHECK(pk_api_ra_read_memory(UINT32_MAX, bytes, sizeof bytes) == 0, "GB address wrapped");
    pk_api_deinit();
}

static void coopControl(const char* dir) {
    char rom[1024];
    char reason[80];
    int trainers[] = {-1, -1};
    CHECK(!pk_api_coop_enable() && !pk_api_coop_link_enable(), "co-op opened without a game");
    uint8_t mon[PK_API_COOP_MON_SIZE] = { 0 };
    CHECK(pk_api_coop_give_mon(mon, sizeof mon - 1) == 2, "coop catch accepted a short Pokémon");
    CHECK(pk_api_coop_write_party_mon(0, NULL, sizeof mon) == 0, "coop update accepted a null Pokémon");
    CHECK(pk_api_coop_give_mon(mon, sizeof mon) == -1, "coop catch ran without a core");
    CHECK(pk_api_coop_save_game() == -1, "coop save ran without a core");

    uint8_t data[PK_API_COOP_LINK_PACKET_SIZE] = { 0 };
    CHECK(pk_api_coop_link_take(data, sizeof data - 1) == 0, "link take ignored small buffer");
    CHECK(pk_api_coop_link_result(data, sizeof data) == 0, "link reported a result without a core");
    CHECK(!pk_api_coop_link_host_go(data, PK_API_COOP_NAME_SIZE - 1, 0, 1), "link accepted a short trainer name");
    CHECK(!pk_api_coop_link_give(data, sizeof data + 1), "link accepted an oversized block");
    CHECK(!pk_api_coop_story_arm(-1), "link armed an invalid trainer");
    pk_api_coop_join_trainers(trainers);
    CHECK(trainers[0] == 0 && trainers[1] == 0, "co-op returned trainers without a game");

    path(rom, sizeof rom, dir, "diagnostic.gb");
    CHECK(pk_api_init(rom, NULL), "co-op Game Boy fixture");
    CHECK(!pk_api_coop_enable() && !pk_api_coop_link_enable(), "co-op accepted Game Boy");
    CHECK(pk_api_coop_add_money(300) == -1 && pk_api_coop_set_trainer_flag(19) == -1 &&
          pk_api_coop_write_party_mon(0, mon, sizeof mon) == -1 && pk_api_coop_release_script() == -1,
          "coop rewards ran on Game Boy");
    CHECK(!pk_api_coop_battle_starting() && !pk_api_coop_partner_free() && !pk_api_coop_on_free_field(),
          "co-op state present for Game Boy");
    pk_api_coop_disable();
    pk_api_deinit();

    path(rom, sizeof rom, dir, "diagnostic.gba");
    CHECK(pk_api_init(rom, NULL), "co-op GBA fixture");
    CHECK(!pk_api_coop_enable() && !pk_api_coop_link_enable(), "co-op accepted an unrecognized GBA ROM");
    pk_api_coop_refusal(false, reason, sizeof reason);
    CHECK(reason[0], "co-op did not diagnose the unsupported ROM");
    char bounded[3] = {'?', '?', '!'};
    pk_api_coop_refusal(true, bounded, 2);
    CHECK(bounded[0] != '?' && bounded[1] == '\0' && bounded[2] == '!', "co-op refusal exceeded the requested buffer");
    CHECK(pk_api_coop_join_kind() == 0, "unrecognized GBA ROM exposed a join");
    pk_api_coop_disable();
    pk_api_deinit();
}

int main(int argc, char** argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <dir with diagnostic.gb / .gba>\n", argv[0]);
        return 2;
    }
    closed();
    gameBoy(argv[1]);
    gba(argv[1]);
    tint(argv[1]);
    soundIsolation(argv[1]);
    achievementMemory(argv[1]);
    coopControl(argv[1]);
    if (failures) fprintf(stderr, "%d failed\n", failures);
    else puts("pk_api: all passed");
    return failures != 0;
}
