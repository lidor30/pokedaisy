// Original diagnostic ROMs only. Shared rewind controls, timeline resets and battery-save preservation.
#include "pk_api.h"
#include "pk_core_access.h"
#include <mgba/core/core.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static int game(const char* dir, const char* filename, bool gb) {
    char rom[1024], save[1024], state[1024];
    snprintf(rom, sizeof rom, "%s/%s", dir, filename);
    snprintf(save, sizeof save, "%s/rewind-%s.sav", dir, filename);
    snprintf(state, sizeof state, "%s/rewind-%s.ss", dir, filename);
    unlink(save); unlink(state);
    CHECK(pk_api_init(rom, gb ? save : NULL));
    struct mCore* core = pkMainCore();
    CHECK(core);
    for (int i = 0; i < 60; ++i) pk_api_run_frame();
    pk_api_set_rewind(16);
    for (int i = 0; i < 40; ++i) pk_api_run_frame(); // wrap the ring
    if (gb) {
        core->busWrite8(core, 0xA000, 0x77); // newer battery data than any of the stored snapshots
        pk_api_finish_save(600);
        uint8_t value = 0;
        CHECK(pk_api_read(0xA000, &value, 1) && value == 0x77);
        FILE* f = fopen(save, "rb"); CHECK(f); CHECK(fgetc(f) == 0x77); fclose(f);
    }
    uint32_t before = core->frameCounter(core);
    pk_api_set_rewinding(true);
    for (int i = 0; i < 6; ++i) pk_api_run_frame();
    CHECK(core->frameCounter(core) < before);
    if (gb) {
        uint8_t value = 0;
        CHECK(pk_api_read(0xA000, &value, 1) && value == 0x77);
        FILE* f = fopen(save, "rb"); CHECK(f); CHECK(fgetc(f) == 0x77); fclose(f);
    }
    for (int i = 0; i < 40; ++i) pk_api_run_frame(); // exhausted history stands still and yields no audio
    before = core->frameCounter(core);
    pk_api_run_frame(); CHECK(core->frameCounter(core) == before);
    int samples = -1; pk_api_audio(&samples); CHECK(samples == 0);
    pk_api_set_rewinding(false);
    pk_api_run_frame(); CHECK(core->frameCounter(core) == before + 1);

    // A successful state load starts a new timeline but retains the configured rewind capacity.
    CHECK(pk_api_save_state(state));
    before = core->frameCounter(core);
    for (int i = 0; i < 20; ++i) pk_api_run_frame();
    CHECK(pk_api_load_state(state));
    CHECK(core->frameCounter(core) == before);
    pk_api_set_rewinding(true); pk_api_run_frame();
    CHECK(core->frameCounter(core) == before); // empty history, not a jump to the old future
    pk_api_set_rewinding(false);
    for (int i = 0; i < 8; ++i) pk_api_run_frame();
    uint32_t advanced = core->frameCounter(core);
    CHECK(!pk_api_load_state("/no-such-pokedaisy-rewind-state"));
    pk_api_set_rewinding(true); pk_api_run_frame();
    CHECK(core->frameCounter(core) < advanced && core->frameCounter(core) >= before);

    // Reconfiguring while held releases rewind; zero and negative values free it.
    pk_api_set_rewind(8);
    before = core->frameCounter(core);
    pk_api_run_frame(); CHECK(core->frameCounter(core) == before + 1);
    pk_api_set_rewinding(true); pk_api_set_rewind(-1);
    before = core->frameCounter(core);
    pk_api_run_frame(); CHECK(core->frameCounter(core) == before + 1);
    pk_api_set_rewind(0);
    pk_api_deinit();
    if (gb) {
        FILE* f = fopen(save, "rb"); CHECK(f); CHECK(fgetc(f) == 0x77); fclose(f);
    }
    return 0;
}

int main(int argc, char** argv) {
    CHECK(argc == 2);
    pk_api_set_rewind(16); pk_api_set_rewinding(true); pk_api_run_frame(); pk_api_deinit();
    CHECK(!game(argv[1], "diagnostic.gb", true));
    CHECK(!game(argv[1], "diagnostic.gba", false));
    pk_api_set_rewinding(true); pk_api_set_rewind(0); pk_api_deinit();
    puts("pk_rewind_api: all passed");
    return 0;
}
