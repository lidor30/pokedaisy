#include "pd_game.h"

#include <stdio.h>
#include <string.h>

#include "pd_sha1.h"

// The configs come from gamedata/ (gamedata/targets/3ds.toml picks the fields
// this port reads), generated with the app's: `make gamedata`.
#include "pd_configs_gen.h"

const uint8_t* pd_rom_at(const struct pd_game* g, uint32_t addr, size_t len) {
    if (!g->rom || addr < 0x08000000) return NULL;
    size_t off = addr - 0x08000000;
    if (off > g->romSize || len > g->romSize - off) return NULL;
    return g->rom + off;
}

unsigned pd_rom_id(const struct pd_game* g) {
    return g->rom ? g->loadId : 0;
}

bool pd_rom_read(const struct pd_game* g, uint32_t addr, void* out, size_t len) {
    const uint8_t* p = pd_rom_at(g, addr, len);
    if (!p) return false;
    memcpy(out, p, len);
    return true;
}

void pd_game_detect(struct pd_game* g, const uint8_t* header, size_t romSize) {
    static unsigned loads;
    memset(g, 0, sizeof(*g));
    g->loadId = ++loads ? loads : ++loads;
    memcpy(g->code, header + 0xAC, 4);
    g->code[4] = 0;
    g->revision = header[0xBC];

    if (!strcmp(g->code, "BPRE") || !strcmp(g->code, "BPGE")) {
        g->kind = PD_GAME_FIRERED;
        g->title = g->code[2] == 'R' ? "Pokémon FireRed" : "Pokémon LeafGreen";
        bool rev1 = g->revision == 1;
        g->guide = g->code[2] == 'R' ? PD_GUIDE_FIRERED : PD_GUIDE_LEAFGREEN;
        if (g->code[2] == 'R') g->cfg = rev1 ? &PD_CONFIG_FIRERED_REV1 : &PD_CONFIG_FIRERED_REV0;
        else g->cfg = rev1 ? &PD_CONFIG_LEAFGREEN_REV1 : &PD_CONFIG_LEAFGREEN_REV0;
    } else if (!strcmp(g->code, "AXVE") || !strcmp(g->code, "AXPE")) {
        // Ruby / Sapphire (USA), read like Emerald (Poller.otherRetailConfig): rev 0 has its own addresses.
        bool ruby = g->code[2] == 'V';
        g->kind = PD_GAME_EMERALD;
        g->title = ruby ? "Pokémon Ruby" : "Pokémon Sapphire";
        g->guide = ruby ? PD_GUIDE_RUBY : PD_GUIDE_SAPPHIRE;
        if (g->revision == 0) g->cfg = ruby ? &PD_CONFIG_RUBY_REV0 : &PD_CONFIG_SAPPHIRE_REV0;
        else g->cfg = ruby ? &PD_CONFIG_RUBY : &PD_CONFIG_SAPPHIRE;
        if (g->revision > 2) {
            g->cfg = NULL;
            g->unsupported = "This revision isn't supported yet";
        } else if (romSize > 0x1000000) {
            g->cfg = NULL;
            g->title = ruby ? "Ruby ROM hack" : "Sapphire ROM hack";
            g->unsupported = "Not a version the companion knows";
        }
        return;
    } else if (!strcmp(g->code, "BPEE")) {
        g->kind = PD_GAME_EMERALD;
        g->title = "Pokémon Emerald";
        g->guide = PD_GUIDE_EMERALD;
        g->cfg = &PD_CONFIG_EMERALD;
    } else {
        g->title = "Unknown game";
        g->unsupported = "Not a game the 3DS companion knows yet";
        return;
    }
    if (g->revision > 1) {
        g->cfg = NULL;
        g->unsupported = "This revision isn't supported yet";
    } else if (romSize > 0x1000000) {
        // A >16 MB FireRed / Emerald is a ROM hack: the app tells them apart
        // by SHA1 (Poller.detect) - not ported yet.
        g->cfg = NULL;
        g->title = g->kind == PD_GAME_FIRERED ? "FireRed ROM hack" : "Emerald ROM hack";
        g->unsupported = "ROM hacks aren't supported on 3DS yet";
    }
}

// The retail carts (USA / Europe) detect's configs are for: GameTitles.RETAIL.
static const char* const RETAIL_SHA1S[] = {
    "41cb23d8dccc8ebd7c649cd8fbb58eeace6e2fdc", // FireRed rev 0
    "dd5945db9b930750cb39d00c84da8571feebf417", // FireRed rev 1
    "574fa542ffebb14be69902d1d36f1ec0a4afd71e", // LeafGreen rev 0
    "7862c67bdecbe21d1d69ce082ce34327e1c6ed5e", // LeafGreen rev 1
    "f3ae088181bf583e55daf962a92bb46f4f1d07b7", // Emerald
};

// A collection's dump of a retail cart (the app's RETAIL_DUMPS, RetailDumpsGen.kt): bytes [0, end) with the
// ranges zeroed hash to sha1 - the end's padding trimmed, the save code patched, the wait states changed.
struct retail_dump {
    const char* code;
    int rev;
    uint32_t end;
    uint32_t ranges[6][2]; // [from, to)
    const char* sha1;
};
static const struct retail_dump RETAIL_DUMPS[] = {
    {"BPRE", 0, 0xEB0B1E,
     {{0xC4, 0xCA}, {0x460, 0x464}, {0x1DE824, 0x1DF128}, {0x415BFF, 0x415C8F}, {0x6FBEB8, 0x6FC03C}, {0x71A23C, 0xD00000}},
     "7c5ed3b3ce796ef2ef05878b31836b090dc7b7b5"},
    {"BPRE", 1, 0xEB0B1E,
     {{0xC4, 0xCA}, {0x470, 0x474}, {0x1DE894, 0x1DF198}, {0x415C6F, 0x415CFF}, {0x6FBF18, 0x6FC09C}, {0x71A29C, 0xD00000}},
     "4b1c9554caf64c58055e7e7202fa5bf99f86bea1"},
    {"BPEE", 0, 0xE3CF51,
     {{0xC4, 0xCA}, {0x470, 0x474}, {0x2E1860, 0x2E2A2C}, {0x5E83B6, 0x5E84D3}, {0x9A3064, 0x9A3234}},
     "09d7e7cbb3cfbf842c7663c63d3c8fb8eb6423f8"},
};

// The hacks (and other versions) the companion reads, by SHA-1: Poller.detect's *_SHA1 cases, one per config the
// 3DS / Linux target generates (gamedata/targets/3ds.toml).
struct known_rom {
    const char* sha1;
    const struct pd_config* cfg;
    enum pd_game_kind kind;
    const char* title;
    enum pd_guide_id guide;
};
static const struct known_rom KNOWN_ROMS[] = {
    {"cbd71af65ee3dcd53280a6a12014bfb11e8f626a", &PD_CONFIG_EMERALD_LEGACY, PD_GAME_EMERALD, "Pokémon Emerald Legacy",
     PD_GUIDE_EMERALD_LEGACY},
    {NULL, NULL, PD_GAME_NONE, NULL, PD_GUIDE_NONE},
};

static bool retail_dump(const struct pd_game* g) {
    for (size_t i = 0; i < sizeof RETAIL_DUMPS / sizeof *RETAIL_DUMPS; i++) {
        const struct retail_dump* d = &RETAIL_DUMPS[i];
        if (strcmp(d->code, g->code) || d->rev != g->revision || g->romSize < d->end || g->romSize > 0x1000000) continue;
        static uint8_t chunk[1 << 16];
        struct pd_sha1 s;
        char hex[41];
        pd_sha1_init(&s);
        for (uint32_t off = 0; off < d->end; off += sizeof chunk) {
            uint32_t len = d->end - off < sizeof chunk ? d->end - off : sizeof chunk;
            memcpy(chunk, g->rom + off, len);
            for (int r = 0; r < 6 && d->ranges[r][1]; r++) {
                uint32_t a = d->ranges[r][0] > off ? d->ranges[r][0] : off;
                uint32_t z = d->ranges[r][1] < off + len ? d->ranges[r][1] : off + len;
                if (a < z) memset(chunk + (a - off), 0, z - a);
            }
            pd_sha1_update(&s, chunk, len);
        }
        pd_sha1_hex(&s, hex);
        if (!strcmp(hex, d->sha1)) return true;
    }
    return false;
}

void pd_game_identify(struct pd_game* g) {
    if (!g->rom || !g->kind) return;
    struct pd_sha1 s;
    pd_sha1_init(&s);
    pd_sha1_update(&s, g->rom, g->romSize);
    pd_sha1_hex(&s, g->sha1);

    for (const struct known_rom* k = KNOWN_ROMS; k->sha1; k++) {
        if (strcmp(k->sha1, g->sha1)) continue;
        g->cfg = k->cfg;
        g->kind = k->kind;
        g->title = k->title;
        g->guide = k->guide;
        g->unsupported = NULL;
        return;
    }
    if (!g->cfg) return; // detect's verdict (a revision past 1, a big hack) stands
    // LeafGreen, Ruby and Sapphire are read by code and revision alone, like the app (OTHER_RETAIL_CODES):
    // only FireRed and Emerald, which most hacks build on, need a retail hash.
    if (strcmp(g->code, "BPRE") && strcmp(g->code, "BPEE")) return;
    for (size_t i = 0; i < sizeof RETAIL_SHA1S / sizeof *RETAIL_SHA1S; i++)
        if (!strcmp(RETAIL_SHA1S[i], g->sha1)) return;
    if (retail_dump(g)) return;
    g->cfg = NULL;
    g->guide = PD_GUIDE_NONE;
    g->title = g->kind == PD_GAME_FIRERED ? "FireRed ROM hack" : "Emerald ROM hack";
    g->unsupported = "Not a version the companion knows";
}
