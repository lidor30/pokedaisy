// A tiny GBA homebrew ROM for testing the 3DS build without a Pokémon ROM: it
// copies one of the repo's RAM fixtures (test-fixtures/resources/fixtures/<key>/
// ewram.bin + iwram.bin, linked in by fixture_data.s) into the GBA's RAM, fills
// the screen and idles. gbafix gives it FireRed's or Emerald's game code, so
// the companion detects that game and reads the fixture's party, battle and
// money exactly as it would read the real game's. No game code or data from
// any ROM is in it - only the save state the fixtures already hold.
//
// Built by `make fixture-roms` (devkitARM, in Docker).
#include <stdint.h>

extern const uint8_t fixture_ewram[], fixture_ewram_end[];
extern const uint8_t fixture_iwram[], fixture_iwram_end[];

#define REG_DISPCNT (*(volatile uint16_t*) 0x04000000)
#define VRAM ((volatile uint16_t*) 0x06000000)

// IWRAM's last 0x200 bytes hold this program's own stack: left alone.
#define IWRAM_KEEP 0x7E00

int main(void) {
    // Mode 3 (a 240x160 bitmap), BG2 on: a dark teal screen with a light band,
    // so it's clear the game ran.
    REG_DISPCNT = 3 | (1 << 10);
    for (int y = 0; y < 160; y++) {
        uint16_t c = (y >= 72 && y < 88) ? 0x7FFF : (uint16_t) ((12 << 10) | (10 << 5) | 4);
        for (int x = 0; x < 240; x++) VRAM[y * 240 + x] = c;
    }

    volatile uint32_t* ew = (volatile uint32_t*) 0x02000000;
    const uint32_t* src = (const uint32_t*) fixture_ewram;
    uint32_t n = (uint32_t) (fixture_ewram_end - fixture_ewram) / 4;
    for (uint32_t i = 0; i < n; i++) ew[i] = src[i];

    volatile uint32_t* iw = (volatile uint32_t*) 0x03000000;
    src = (const uint32_t*) fixture_iwram;
    n = (uint32_t) (fixture_iwram_end - fixture_iwram);
    if (n > IWRAM_KEEP) n = IWRAM_KEEP;
    for (uint32_t i = 0; i < n / 4; i++) iw[i] = src[i];

    // This program's globals were just overwritten too; it never returns.
    for (;;) {
    }
}
