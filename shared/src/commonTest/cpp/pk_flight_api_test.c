// Portable flight transport without verified game tables: no flight, input, or picture changes.
#include "pk_api.h"
#include "pk_flight.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

_Static_assert(PK_API_FLIGHT_IDLE == PK_FLIGHT_IDLE && PK_API_FLIGHT_DISMOUNT == PK_FLIGHT_DISMOUNT, "flight states");
_Static_assert(PK_API_FLIGHT_OK == PK_FLIGHT_OK && PK_API_FLIGHT_NOT_CONFIGURED == PK_FLIGHT_NOT_CONFIGURED &&
    PK_API_FLIGHT_BUSY == PK_FLIGHT_BUSY && PK_API_FLIGHT_NOT_NOW == PK_FLIGHT_NOT_NOW &&
    PK_API_FLIGHT_NOT_ON_FOOT == PK_FLIGHT_NOT_ON_FOOT, "flight results");
static int failures;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #c); failures++; } } while (0)

static void idle(void) {
    uint32_t shortWords[] = { 1, 2, 3 };
    uint8_t route[] = { 1, 4 };
    pk_api_flight_configure(shortWords, 3);
    CHECK(pk_api_flight_state() == PK_API_FLIGHT_IDLE);
    CHECK(pk_api_flight_take_off() == PK_API_FLIGHT_NOT_CONFIGURED);
    CHECK(pk_api_flight_route(route, 2) == PK_API_FLIGHT_NOT_NOW);
    CHECK(pk_api_flight_route(NULL, 1) == PK_API_FLIGHT_NOT_NOW);
    CHECK(pk_api_flight_route(NULL, 0) == PK_API_FLIGHT_NOT_NOW);
    CHECK(pk_api_flight_route_done() == -1);
    CHECK(!pk_api_flight_area_turned());
    pk_api_flight_area(route, 2);
    pk_api_flight_area(NULL, 0);
    pk_api_flight_land();
    pk_api_flight_configure(NULL, 0);
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    idle();
    const char* carts[] = { "diagnostic.gb", "diagnostic.gba" };
    for (unsigned i = 0; i < 2; i++) {
        char rom[1024]; snprintf(rom, sizeof rom, "%s/%s", argv[1], carts[i]);
        CHECK(pk_api_init(rom, NULL));
        for (int f = 0; f < 30; f++) pk_api_run_frame();
        size_t n = (size_t) pk_api_video_width() * pk_api_video_height() * 4;
        void* before = malloc(n); CHECK(before != NULL);
        if (!before) return 2;
        memcpy(before, pk_api_video(), n);
        idle();
        for (int f = 0; f < 3; f++) pk_api_run_frame();
        CHECK(!memcmp(before, pk_api_video(), n));
        CHECK(pk_api_flight_state() == PK_API_FLIGHT_IDLE);
        free(before); pk_api_deinit(); idle();
    }
    return failures != 0;
}
