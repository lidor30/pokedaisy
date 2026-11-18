// Original diagnostic ROMs only: the public parser, live cheats and reversible ROM overlays.
#include "pk_api.h"
#include "pk_core_access.h"
#include <mgba/core/core.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main(int argc, char** argv) {
    CHECK(argc == 2);
    CHECK(pk_api_cheats_apply(NULL) == 0);
    char* answer = pk_api_cheats_check("32003000 005A\nnot-a-code", 0, "");
    CHECK(answer && !strcmp(answer, "\n10")); free(answer);
    answer = pk_api_cheats_check("32003000 005A", 1, "");
    CHECK(answer && !strcmp(answer, "\n1")); free(answer);
    answer = pk_api_cheats_check(NULL, 0, NULL);
    CHECK(answer && !strcmp(answer, "\n")); free(answer);

    char rom[1024];
    snprintf(rom, sizeof rom, "%s/diagnostic.gba", argv[1]);
    CHECK(pk_api_init(rom, NULL));
    struct mCore* core = pkMainCore(); CHECK(core);
    uint8_t original = core->busRead8(core, 0x080000A0);
    uint8_t option = core->busRead8(core, 0x080000A1);
    uint8_t changed = option ^ 0xFF;
    CHECK(pk_api_rom_patch(0x080000A1, &option, 1, 0x080000A1, &changed, 1));
    CHECK(pk_api_cheats_apply("!reset\n# RAM\n32003000 005A\n!reset\n# ROM\n080000A0:77\n") == 2);
    for (int i = 0; i < 4; ++i) pk_api_run_frame();
    uint8_t value = 0;
    CHECK(pk_api_read(0x02003000, &value, 1) && value == 0x5A);
    CHECK(core->busRead8(core, 0x080000A0) == 0x77);
    CHECK(pk_api_read(0x080000A0, &value, 1) && value == original); // readers see the original ROM
    CHECK(core->busRead8(core, 0x080000A1) == changed); // the separate game option survives

    CHECK(pk_api_cheats_apply("!reset\n# replacement\n32003000 00A5\n") == 1);
    pk_api_run_frame();
    CHECK(pk_api_read(0x02003000, &value, 1) && value == 0xA5);
    CHECK(core->busRead8(core, 0x080000A0) == original);
    CHECK(core->busRead8(core, 0x080000A1) == changed);
    CHECK(pk_api_cheats_apply("") == 0);
    core->busWrite8(core, 0x02003000, 0x33);
    pk_api_run_frame();
    CHECK(pk_api_read(0x02003000, &value, 1) && value == 0x33); // no cheat refresh remains
    pk_api_rom_unpatch();
    CHECK(core->busRead8(core, 0x080000A1) == option);
    pk_api_deinit();

    snprintf(rom, sizeof rom, "%s/diagnostic.gb", argv[1]);
    CHECK(pk_api_init(rom, NULL));
    CHECK(pk_api_cheats_apply("# GBA only\n32003000 005A\n") == 0);
    pk_api_run_frame(); pk_api_deinit();
    puts("pk_cheats_api: all passed");
    return 0;
}
