// REUSABLE TMS and INSTANT TEXT (pd_rom_options.h): the app's ReusableTms.kt and InstantText.kt, the same searches.
#include "pd_rom_options.h"

#include <stdbool.h>
#include <string.h>

#define ROM_BASE 0x08000000u

static unsigned u16(const uint8_t* rom, size_t o) {
    return (unsigned) rom[o] | (unsigned) rom[o + 1] << 8;
}

// Where the `bl` at ROM offset [at] goes (a ROM offset).
static long bl_target(const uint8_t* rom, long at) {
    int hi = (int) (u16(rom, (size_t) at) & 0x7FF);
    if (hi & 0x400) hi -= 0x800;
    return at + 4 + ((long) hi << 12) + ((long) (u16(rom, (size_t) at + 2) & 0x7FF) << 1);
}

// --- REUSABLE TMS ---

#define TM_SITE_LEN 16
#define TM_BRANCH 6 // the `bhi` after movs, lsls, cmp

// How many sites the engine behind the header's game code has: the FireRed engine (FireRed, LeafGreen, Ultra Violet's
// PUVV) three, Emerald / Ruby / Sapphire one.
static int expected_sites(const uint8_t* rom) {
    const char* code = (const char*) rom + 0xAC;
    if (!strncmp(code, "BPR", 3) || !strncmp(code, "BPG", 3) || !strncmp(code, "PUV", 3)) return 3;
    if (!strncmp(code, "BPE", 3) || !strncmp(code, "AXV", 3) || !strncmp(code, "AXP", 3)) return 1;
    return 0;
}

// `movs r0, #0xA9; lsls r0, r0, #1; cmp rN, r0; bhi past the call; adds r0, rN, #0; movs r1, #1; bl` at [o].
static bool tm_site(const uint8_t* rom, size_t size, size_t o) {
    if (o + TM_SITE_LEN > size || rom[o] != 0xA9 || rom[o + 1] != 0x20 || u16(rom, o + 2) != 0x0040) return false;
    unsigned cmp = u16(rom, o + 4), n = cmp & 7;
    return cmp == (0x4280 | n) && u16(rom, o + 6) == 0xD803 && u16(rom, o + 8) == (0x1C00 | (n << 3)) && u16(rom, o + 10) == 0x2101 &&
           (u16(rom, o + 12) & 0xF800) == 0xF000 && (u16(rom, o + 14) & 0xF800) == 0xF800;
}

static void tm_write(const uint8_t* rom, size_t o, struct pd_rom_write* w) {
    memset(w, 0, sizeof(*w));
    w->addr = ROM_BASE + (uint32_t) (o + TM_BRANCH);
    w->len = 2;
    memcpy(w->expect, rom + o + TM_BRANCH, 2);
    w->value[0] = 0x03; // `b` to the same place as the `bhi`
    w->value[1] = 0xE0;
}

int pd_reusable_tm_at(const uint8_t* rom, size_t size, const uint32_t* sites, int n, struct pd_rom_write* out) {
    if (size < 0xC0 || n <= 0 || n > PD_TM_SITES_MAX || n != expected_sites(rom)) return 0;
    long calls = -1;
    for (int i = 0; i < n; i++) {
        size_t o = sites[i] - ROM_BASE;
        if (sites[i] < ROM_BASE || !tm_site(rom, size, o)) return 0;
        long t = bl_target(rom, (long) o + 12);
        if (calls >= 0 && t != calls) return 0;
        calls = t;
        tm_write(rom, o, &out[i]);
    }
    return n;
}

int pd_reusable_tm_find(const uint8_t* rom, size_t size, struct pd_rom_write* out) {
    if (size < 0xC0) return 0;
    int want = expected_sites(rom);
    if (!want) return 0;
    uint32_t sites[PD_TM_SITES_MAX];
    int n = 0;
    for (size_t o = 0; o + TM_SITE_LEN <= size; o += 2) {
        if (rom[o] != 0xA9 || !tm_site(rom, size, o)) continue;
        if (n == want) return 0; // more than the engine has: a hack changed the code, so none (TMs used up elsewhere)
        sites[n++] = ROM_BASE + (uint32_t) o;
    }
    return n == want ? pd_reusable_tm_at(rom, size, sites, n, out) : 0;
}

// --- INSTANT TEXT ---

#define SEARCH 0x10000
#define ANY -1
#define ANY_BL_HIGH -2 // the first half of a `bl`: 0xF0-0xF7
#define ANY_BL_LOW -3  // the second half: 0xF8-0xFF

// RenderFont as agbcc built it, through its `bx r1`; 40 bytes, the gFonts literal straight after.
static const int FONT[] = {
    0x10, 0xB5, 0x04, 0x1C, 0x61, 0x79, 0x08, 0x48, 0x02, 0x68, 0x48, 0x00, 0x40, 0x18, 0x80, 0x00, 0x80, 0x18, 0x01, 0x68,
    0x20, 0x1C, ANY, ANY_BL_HIGH, ANY, ANY_BL_LOW, 0x00, 0x04, 0x00, 0x0C, 0x02, 0x28, 0xF0, 0xD0, 0x10, 0xBC, 0x02, 0xBC,
    0x08, 0x47,
};
#define FONT_CALL 0x16 // RenderFont's call to _call_via_r1
#define NEW_CALL 0x12  // ... and where FONT_CODE has it

// The new RenderFont (InstantText.kt's FONT_CODE: it goes on after RENDER_PRINT up to the next control code, textSpeed 0).
static const uint8_t FONT_CODE[40] = {
    0x10, 0xB5, 0x04, 0x1C, 0x61, 0x79, 0x0C, 0x20, 0x41, 0x43, 0x07, 0x48, 0x00, 0x68, 0x41, 0x58, 0x20, 0x1C, 0, 0, 0, 0,
    0x41, 0x08, 0x05, 0xD2, 0xF3, 0xD1, 0x60, 0x77, 0x21, 0x68, 0x09, 0x78, 0xF7, 0x29, 0xEE, 0xD3, 0x10, 0xBD,
};

// Ruby / Sapphire's UpdateWindowText: its `PrintNextChar(win)` and the switch on the state after it ...
static const int RS_PRINT[] = { 0x20, 0x1C, ANY, ANY_BL_HIGH, ANY, ANY_BL_LOW, 0xE0, 0x8A, 0x0A, 0x28, 0x25, 0xD8, 0x80, 0x00 };
// ... and its `state = WIN_STATE_CHAR_DELAY; delayCounter = GetTextDelay(win)` with the `return 0` after it.
static const int RS_DELAY[] = {
    0x03, 0x20, 0xE0, 0x82, 0x20, 0x1C, 0x00, 0xF0, ANY, ANY_BL_LOW, 0x60, 0x73, 0x00, 0x20, 0x10, 0xBC, 0x02, 0xBC, 0x08, 0x47,
};
#define RS_GAP 0x58 // RS_DELAY past RS_PRINT (the jump table between them)

// Where [sig] matches in the first 64 KB: up to [max] offsets in [at], how many there were.
static int matches(const uint8_t* rom, size_t size, const int* sig, int len, long* at, int max) {
    long end = (long) (size < SEARCH ? size : SEARCH) - len - 4;
    int n = 0;
    for (long o = 0; o <= end; o += 2) {
        bool ok = true;
        for (int i = 0; ok && i < len; i++) {
            int b = rom[o + i];
            ok = sig[i] == ANY ? true : sig[i] == ANY_BL_HIGH ? (b >= 0xF0 && b <= 0xF7) : sig[i] == ANY_BL_LOW ? b >= 0xF8 : b == sig[i];
        }
        if (!ok) continue;
        if (n < max) at[n] = o;
        n++;
    }
    return n;
}

int pd_instant_text_find(const uint8_t* rom, size_t size, struct pd_rom_write* out) {
    long font[2], print[2], delay[2];
    int nf = matches(rom, size, FONT, (int) (sizeof(FONT) / sizeof(*FONT)), font, 2);
    int np = matches(rom, size, RS_PRINT, (int) (sizeof(RS_PRINT) / sizeof(*RS_PRINT)), print, 2);
    int nd = matches(rom, size, RS_DELAY, (int) (sizeof(RS_DELAY) / sizeof(*RS_DELAY)), delay, 2);
    if (nf == 1 && np == 0) {
        long at = font[0];
        if (at % 4) return 0; // FONT_CODE's `ldr` counts on it
        uint8_t code[40];
        memcpy(code, FONT_CODE, sizeof(code));
        long target = bl_target(rom, at + FONT_CALL), off = (target - (at + NEW_CALL + 4)) >> 1;
        unsigned h1 = 0xF000 | ((unsigned long) (off >> 11) & 0x7FF), h2 = 0xF800 | ((unsigned long) off & 0x7FF);
        code[NEW_CALL] = (uint8_t) h1;
        code[NEW_CALL + 1] = (uint8_t) (h1 >> 8);
        code[NEW_CALL + 2] = (uint8_t) h2;
        code[NEW_CALL + 3] = (uint8_t) (h2 >> 8);
        int n = 0;
        for (int i = 0; i < 40 && n < PD_TEXT_WRITES_MAX; i += 4) {
            if (!memcmp(rom + at + i, code + i, 4)) continue;
            struct pd_rom_write* w = &out[n++];
            memset(w, 0, sizeof(*w));
            w->addr = ROM_BASE + (uint32_t) (at + i);
            w->len = 4;
            memcpy(w->expect, rom + at + i, 4);
            memcpy(w->value, code + i, 4);
        }
        return n;
    }
    if (nf == 0 && np == 1 && nd == 1 && delay[0] == print[0] + RS_GAP) {
        unsigned branch = 0xE000 | ((unsigned long) ((print[0] - (delay[0] + 4)) >> 1) & 0x7FF);
        struct pd_rom_write* w = &out[0];
        memset(w, 0, sizeof(*w));
        w->addr = ROM_BASE + (uint32_t) delay[0];
        w->len = 2;
        memcpy(w->expect, rom + delay[0], 2);
        w->value[0] = (uint8_t) branch;
        w->value[1] = (uint8_t) (branch >> 8);
        return 1;
    }
    return 0;
}
