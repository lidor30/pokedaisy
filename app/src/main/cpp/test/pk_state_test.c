// Shared state API regression tests on the generated diagnostic ROMs (no commercial content).
#include "pk_api.h"
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main(int argc, char** argv) {
    CHECK(argc == 2);
    char gb[1024], gba[1024], save[1024], state[1024], bad[1024];
    snprintf(gb, sizeof gb, "%s/diagnostic.gb", argv[1]);
    snprintf(gba, sizeof gba, "%s/diagnostic.gba", argv[1]);
    snprintf(save, sizeof save, "%s/state-test.sav", argv[1]);
    snprintf(state, sizeof state, "%s/state-test.ss", argv[1]);
    snprintf(bad, sizeof bad, "%s/invalid.ss", argv[1]);
    unlink(save); unlink(state);
    CHECK(!pk_api_save_state(state));
    CHECK(!pk_api_load_state(state));
    CHECK(pk_api_state_matches_save(state) == -1);
    CHECK(pk_api_init(gb, save));
    for (int i = 0; i < 60; i++) pk_api_run_frame();
    uint8_t before, after;
    CHECK(pk_api_read(0xC000, &before, 1));
    CHECK(pk_api_save_state(state));
    CHECK(pk_api_state_matches_save(state) == 1);
    for (int i = 0; i < 7; i++) pk_api_run_frame();
    CHECK(pk_api_read(0xC000, &after, 1) && after != before);
    CHECK(pk_api_load_state(state));
    CHECK(pk_api_read(0xC000, &after, 1) && after == before);
    FILE* f = fopen(bad, "wb"); CHECK(f);
    CHECK(fwrite("invalid", 1, 7, f) == 7); fclose(f);
    CHECK(!pk_api_load_state(bad));
    CHECK(pk_api_state_matches_save(bad) == -1);
    CHECK(pk_api_read(0xC000, &after, 1) && after == before);
    CHECK(!pk_api_save_state(NULL));
    CHECK(!pk_api_load_state(""));
    CHECK(!pk_api_save_state("/no-such-pokedaisy-directory/state"));
    pk_api_deinit();
    // A battery save changed by another emulator must fail the automatic-resume check, without changing it back.
    f = fopen(save, "r+b"); CHECK(f);
    CHECK(fputc(0x6B, f) != EOF); fclose(f);
    CHECK(pk_api_init(gb, save));
    CHECK(pk_api_state_matches_save(state) == 0);
    pk_api_deinit();
    f = fopen(save, "rb"); CHECK(f); CHECK(fgetc(f) == 0x6B); fclose(f);
    // The GBA core also round-trips a state and rejects the other platform's state.
    CHECK(pk_api_init(gba, NULL));
    CHECK(!pk_api_load_state(state));
    CHECK(pk_api_save_state(bad));
    for (int i = 0; i < 4; i++) pk_api_run_frame();
    CHECK(pk_api_load_state(bad));
    pk_api_deinit();
    puts("pk_state: all passed");
    return 0;
}
