#include "pk_link.h"
#include <mgba/internal/gba/io.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REQUIRE(test) do { if (!(test)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #test); exit(1); } } while (0)
#define IO(reg) (0x04000000 + (reg))

static void cable_words(int n) {
    for (int i = 0; i < n; i++) {
        struct mCore* core = pk_link_core(i);
        core->busWrite16(core, IO(REG_RCNT), 0);
        core->busWrite16(core, IO(REG_SIOCNT), 0x2003);
        core->busWrite16(core, IO(REG_SIOMLT_SEND), (uint16_t) (0x4320 + i));
    }
    for (int frame = 0; frame < 2; frame++) REQUIRE(pk_link_run_frame());
    struct mCore* parent = pk_link_core(0);
    parent->busWrite16(parent, IO(REG_SIOCNT), 0x2083);
    for (int frame = 0; frame < 4; frame++) REQUIRE(pk_link_run_frame());

    for (int i = 0; i < n; i++) {
        struct mCore* core = pk_link_core(i);
        for (int peer = 0; peer < n; peer++) {
            REQUIRE(core->busRead16(core, IO(REG_SIOMULTI0 + peer * 2)) == (uint32_t) (0x4320 + peer));
        }
    }
}

int main(int argc, char** argv) {
    REQUIRE(argc == 2);
    char rom[1024], gb[1024], battery[PK_LINK_MAX][1024];
    const char* roms[PK_LINK_MAX];
    const char* saves[PK_LINK_MAX];
    snprintf(rom, sizeof(rom), "%s/diagnostic.gba", argv[1]);
    snprintf(gb, sizeof(gb), "%s/diagnostic.gb", argv[1]);
    for (int i = 0; i < PK_LINK_MAX; i++) {
        roms[i] = rom;
        snprintf(battery[i], sizeof(battery[i]), "%s/link-test-%d.sav", argv[1], i);
        remove(battery[i]);
        saves[i] = battery[i];
    }

    // A failed later cart closes an already opened peer and leaves the link reusable.
    roms[1] = gb;
    REQUIRE(!pk_link_open_with_rtc(2, roms, saves));
    REQUIRE(pk_link_count() == 0 && !pk_link_run_frame());
    roms[1] = rom;

    uint8_t frame[240 * 160 * 4];
    for (int n = 2; n <= PK_LINK_MAX; n++) {
        REQUIRE(pk_link_open_with_rtc(n, roms, saves));
        REQUIRE(pk_link_count() == n);
        cable_words(n);
        for (int i = 0; i < n; i++) {
            REQUIRE(pk_link_video_w(i) == 240 && pk_link_video_h(i) == 160);
            memset(frame, 0, sizeof(frame));
            REQUIRE(!pk_link_copy_video(i, frame, sizeof(frame) - 1));
            REQUIRE(frame[3] == 0);
            REQUIRE(pk_link_copy_video(i, frame, sizeof(frame)));
            for (size_t p = 3; p < sizeof(frame); p += 4) REQUIRE(frame[p] == 0xFF);
            int16_t audio[4096 * 2];
            REQUIRE(pk_link_read_audio(i, audio, -1) == 0);
            REQUIRE(pk_link_read_audio(i, audio, 4096) > 0);
        }
        pk_link_close(3);
        REQUIRE(pk_link_count() == 0);
        REQUIRE(!pk_link_copy_video(0, frame, sizeof(frame)));
    }
    for (int i = 0; i < PK_LINK_MAX; i++) remove(battery[i]);
    return 0;
}
