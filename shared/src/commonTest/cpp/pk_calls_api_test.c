// Existing game-call machinery through the portable host interface, original diagnostics only.
#include "pk_api.h"
#include "pk_core_access.h"
#include <mgba/core/core.h>
#include <mgba/internal/arm/arm.h>
#include <mgba/internal/sm83/sm83.h>
#include <stdio.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main(int argc, char** argv) {
    CHECK(argc == 2);
    const uint8_t value[] = {0x12, 0x34};
    CHECK(!pk_api_write(0x02000000, value, 2));
    CHECK(!pk_api_call_safe());
    CHECK(pk_api_call(0x03004001, 1, 2, 3, 4) == -1);
    CHECK(!pk_api_gb_call_at(0x150, 0xC100, 1, 2, 3));
    char path[1024];
    snprintf(path, sizeof path, "%s/diagnostic.gba", argv[1]);
    CHECK(pk_api_init(path, NULL));
    for (int i = 0; i < 60; ++i) pk_api_run_frame();
    uint8_t read[2];
    CHECK(pk_api_write(0x0203FFFE, value, 2));
    CHECK(pk_api_read(0x0203FFFE, read, 2) && !memcmp(value, read, 2));
    CHECK(pk_api_write(0x03007FFE, value, 2));
    CHECK(!pk_api_write(0x0203FFFF, value, 2));
    CHECK(!pk_api_write(0x03007FFF, value, 2));
    CHECK(!pk_api_write(0xFFFFFFFE, value, 2));
    CHECK(!pk_api_write(0x08000000, value, 2));
    CHECK(!pk_api_write(0x04000000, value, 2));
    CHECK(!pk_api_write(0x02000000, NULL, 2));
    CHECK(!pk_api_write(0x02000000, value, 0));
    CHECK(!pk_api_write(0x02000000, value, -1));
    CHECK(!pk_api_gb_call_at(0x150, 0xC100, 1, 2, 3));
    // Thumb: add r0,r0,r1; add r0,r0,r2; add r0,r0,r3; bx lr.
    const uint8_t sum[] = {0x40,0x18,0x80,0x18,0xC0,0x18,0x70,0x47};
    CHECK(pk_api_write(0x03004000, sum, sizeof sum));
    struct ARMCore* arm = pkMainCore()->cpu;
    int32_t regs[16]; memcpy(regs, arm->gprs, sizeof regs);
    CHECK(pk_api_call_safe());
    CHECK(pk_api_call(0x03004001, 1, 2, 3, 4) == 10);
    CHECK(!memcmp(regs, arm->gprs, sizeof regs));
    // Unsigned r0 is distinct from the failure sentinel -1.
    const uint8_t maximum[] = {0,0x20,0xC0,0x43,0x70,0x47};
    CHECK(pk_api_write(0x03004000, maximum, sizeof maximum));
    CHECK(pk_api_call(0x03004001, 0, 0, 0, 0) == 4294967295LL);
    const uint8_t loop[] = {0xFE,0xE7};
    CHECK(pk_api_write(0x03004000, loop, sizeof loop));
    CHECK(pk_api_call(0x03004001, 0, 0, 0, 0) == -1);
    CHECK(!memcmp(regs, arm->gprs, sizeof regs));
    pk_api_run_frame(); // timed-out call restored the player's CPU
    pk_api_deinit();

    snprintf(path, sizeof path, "%s/diagnostic.gb", argv[1]);
    CHECK(pk_api_init(path, NULL));
    for (int i = 0; i < 60; ++i) pk_api_run_frame();
    CHECK(!pk_api_call_safe());
    CHECK(pk_api_call(0xC101, 0, 0, 0, 0) == -1);
    CHECK(pk_api_write(0xC100, value, 2));
    CHECK(pk_api_read(0xC100, read, 2) && !memcmp(value, read, 2));
    CHECK(pk_api_write(0x7D000, value, 2));
    CHECK(pk_api_read(0x7D000, read, 2) && !memcmp(value, read, 2));
    CHECK(!pk_api_write(0x8D000, value, 2));
    CHECK(!pk_api_write(0x7DFFF, value, 2));
    CHECK(!pk_api_write(0xDFFF, value, 2));
    CHECK(!pk_api_write(0xA000, value, 2)); // battery save excluded
    CHECK(!pk_api_write(0xC100, value, 0x7FFFFFFF)); // reject before touching src
    // GB: store A, B and H/L in scratch RAM, then return to the original loop.
    const uint8_t store[] = {0xEA,0xC1,0xC1,0x78,0xEA,0xC2,0xC1,0x7C,0xEA,0xC3,0xC1,0x7D,0xEA,0xC4,0xC1,0xC9};
    CHECK(pk_api_write(0xC100, store, sizeof store));
    struct SM83Core* gb = pkMainCore()->cpu;
    CHECK(pk_api_gb_call_at(gb->pc, 0xC100, 0x12, 0x34, 0x5678));
    pk_api_run_frame();
    uint8_t args[4];
    CHECK(pk_api_read(0xC1C1, args, 4));
    CHECK(args[0] == 0x12 && args[1] == 0x34 && args[2] == 0x56 && args[3] == 0x78);
    pk_api_deinit();
    CHECK(!pk_api_call_safe());
    puts("pk_calls_api: all passed");
    return 0;
}
