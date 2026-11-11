#include "pd_game.h"
#include "pd_style.h"

#include <stddef.h>
#include <stdio.h>
#include <string.h>

#include "pd_gb.h"
#include "pd_games.h"
#include "pd_randomized.h"
#include "pd_sha1.h"

// The configs come from gamedata/ (gamedata/targets/3ds.toml picks the fields
// this port reads), generated with the app's: `make gamedata`.
const struct pd_party_layout pd_party_layout_vanilla = {
    .size = 100, .status = 0x50, .level = 0x54, .hp = 0x56, .maxHp = 0x58, .flags = 0x13, .eggMask = 0x04,
    .plainBox = 0x20, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = true, .natureModifier = true, .nicknameOff = 0x08, .nicknameLen = 10,
};
// struct BattlePokemon layouts (Gen3Mon.kt): retail's / CFRU's, pokeemerald-expansion's and the hacks' variants of it.
const struct pd_battle_layout pd_battle_layout_vanilla = {
    .size = 88, .species = 0, .moves = 12, .type1 = 33, .type2 = 34, .pp = 36, .hp = 40, .level = 42,
    .maxHp = 44, .status = 76, .speciesMask = 0xFFFF, .personality = 0x48, .otId = 0x54,
    .ability = 0x20, .item = 0x2E, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_expansion = {
    .size = 96, .species = 0, .moves = 12, .type1 = 34, .type2 = 35, .pp = 37, .hp = 42, .level = 44,
    .maxHp = 46, .status = 80, .speciesMask = 0xFFFF, .personality = 0x4C, .otId = 0x58,
    .ability = 0x20, .abilityU16 = true, .item = 0x30, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_tmt2 = {
    .size = 96, .species = 0, .moves = 12, .type1 = 34, .type2 = 35, .pp = 38, .hp = 42, .level = 44,
    .maxHp = 46, .status = 80, .speciesMask = 0xFFFF, .personality = 0x4C, .otId = 0x58,
    .ability = 0x20, .abilityU16 = true, .item = 0x30, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_team_rocket = {
    .size = 92, .species = 0, .moves = 12, .type1 = 34, .type2 = 35, .pp = 37, .hp = 42, .level = 44,
    .maxHp = 46, .status = 80, .speciesMask = 0xFFFF, .personality = 0x4C, .otId = 0x58,
    .ability = 0x20, .abilityU16 = true, .item = 0x30, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_wishes = {
    .size = 136, .species = 0, .moves = 12, .type1 = 34, .type2 = 35, .pp = 37, .hp = 42, .level = 44,
    .maxHp = 46, .status = 80, .speciesMask = 0xFFFF, .personality = 0x4C, .otId = 0x58,
    .ability = 0x20, .abilityU16 = true, .item = 0x30, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_rowe = {
    .size = 96, .species = 0, .moves = 12, .type1 = 34, .type2 = 35, .pp = 37, .hp = 42, .level = 44,
    .maxHp = 46, .status = 80, .speciesMask = 0x7FF, .personality = 0x4C, .otId = 0x58,
    .ability = 0x20, .abilityU16 = true, .item = 0x30, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_soulgold = {
    .size = 152, .species = 0, .moves = 12, .type1 = 34, .type2 = 35, .pp = 37, .hp = 42, .level = 44,
    .maxHp = 46, .status = 84, .speciesMask = 0xFFFF, .personality = 0x50, .otId = 0x88,
    .ability = 0x20, .abilityU16 = true, .item = 0x30, .stats = 0x02, .stages = 0x18, .facts = true,
};
const struct pd_battle_layout pd_battle_layout_elite_redux = {
    .size = 104, .species = 20, .moves = 32, .type1 = 68, .type2 = 69, .pp = 71, .hp = 54, .level = 75,
    .maxHp = 56, .status = 8, .speciesMask = 0xFFFF,
    .ability = 0x28, .abilityU16 = true, .item = 0x3A, .stats = 0x16, .stages = 0x3C, .facts = true,
};
// Monomyth: vanilla's struct, its substructs XORed with a key of its own and its natures its own (MONOMYTH_PARTY_MON).
const struct pd_party_layout pd_party_layout_monomyth = {
    .size = 100, .status = 0x50, .level = 0x54, .hp = 0x56, .maxHp = 0x58, .flags = 0x13, .eggMask = 0x04,
    .plainBox = 0x20, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = true, .monomythKey = true, .nicknameOff = 0x08, .nicknameLen = 10,
};
// Monomyth's rearranged 0x58-byte BattlePokemon (MONOMYTH_BATTLE_MON).
const struct pd_battle_layout pd_battle_layout_monomyth = {
    .size = 0x58, .species = 0x0C, .moves = 0x18, .type1 = 0x0F, .type2 = 0x10, .pp = 0x00, .hp = 0x2C, .level = 0x12,
    .maxHp = 0x2E, .status = 0x30, .speciesMask = 0xFFFF, .personality = 0x40, .otId = 0x38,
    .ability = 0x0E, .item = 0x20, .stats = 0x22, .stages = 0x04, .facts = true,
};
// Pokemon Quetzal: a 0x68-byte plaintext struct (expansion's, bit-packed HP and status), egg flag in the IV word.
const struct pd_party_layout pd_party_layout_quetzal = {
    .size = 0x68, .status = 0x24, .level = 0x58, .hp = 0x23, .maxHp = 0x5A, .flags = 0x13, .eggMask = 0x04,
    .plainBox = 0x28, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .plainExp = true, .hpBits = 10,
    .statusShift = 2, .statusMask = 0xFFF, .eggInIvWord = true, .hasStats = true, .natureModifier = true,
    .nicknameOff = 0x08, .nicknameLen = 10,
};
// SoulGold: 96 bytes, a 12-letter nickname; no stats (the offsets here don't add up) and its species / item are narrower.
const struct pd_party_layout pd_party_layout_soulgold = {
    .size = 96, .status = 0x4C, .level = 0x50, .hp = 0x52, .maxHp = 0x54, .flags = 0x15, .eggMask = 0x04,
    .plainBox = 0x20, .plainSpeciesMask = 0x07FF, .plainItemMask = 0x03FF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = false, .natureModifier = true, .nicknameOff = 0x08, .nicknameLen = 12,
    .shinyBelow = 256, .shinyModifier = 0x1F,
};
// SoulGold v1.2.1: v1.2's struct with its attacks bit-packed (Gen3Mon.kt SOULGOLD_V1_2_1_PARTY_MON).
const struct pd_party_layout pd_party_layout_soulgold_v121 = {
    .size = 96, .status = 0x4C, .level = 0x50, .hp = 0x52, .maxHp = 0x54, .flags = 0x15, .eggMask = 0x04,
    .plainBox = 0x20, .plainSpeciesMask = 0x07FF, .plainItemMask = 0x03FF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = false, .natureModifier = true, .nicknameOff = 0x08, .nicknameLen = 12,
    .shinyBelow = 256, .shinyModifier = 0x1F, .packedAttacks = true,
};
// Team Rocket Edition (Dragonsden): retail's struct, the flags in the language byte, no Mints.
const struct pd_party_layout pd_party_layout_team_rocket = {
    .size = 100, .status = 0x50, .level = 0x54, .hp = 0x56, .maxHp = 0x58, .flags = 0x12, .eggMask = 0x20,
    .plainBox = 0x20, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = true, .natureModifier = false, .nicknameOff = 0x08, .nicknameLen = 10,
};
// Elite Redux 2.65: 0x4C bytes, plaintext and bit-packed in its own order (Gen3Mon.kt decodeEliteReduxMon).
const struct pd_party_layout pd_party_layout_elite_redux = {
    .size = 0x4C, .status = 0x38, .level = 0x3C, .hp = 0x3E, .maxHp = 0x40, .flags = 0x15, .eggMask = 0x80,
    .plainBox = 0x20, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = true, .nicknameOff = 0x26, .nicknameLen = 12, .eliteRedux = true,
    .shinyBelow = 1, .shinyBits = 0x17, .shinyBitsMask = 0x0C,
};
// Emerald Rogue: vanilla's struct grown to 104 bytes (a RoguePartyMon tail).
const struct pd_party_layout pd_party_layout_rogue = {
    .size = 104, .status = 0x50, .level = 0x54, .hp = 0x56, .maxHp = 0x58, .flags = 0x13, .eggMask = 0x04,
    .plainBox = 0x20, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = true, .natureModifier = true, .nicknameOff = 0x08, .nicknameLen = 10,
};
// R.O.W.E. 2.x: 0x4C bytes, plaintext and bit-packed (Gen3Mon.kt decodeRoweMon).
const struct pd_party_layout pd_party_layout_rowe = {
    .size = 0x4C, .status = 0x38, .level = 0x3C, .hp = 0x3E, .maxHp = 0x40, .flags = 0x27, .eggMask = 0x80,
    .plainBox = 0x20, .plainSpeciesMask = 0xFFFF, .plainItemMask = 0xFFFF, .hpBits = 16, .statusMask = 0xFFFFFFFFu,
    .hasStats = true, .nicknameOff = 0x08, .nicknameLen = 12, .rowe = true,
    .shinyBelow = 1, .shinyBits = 0x29, .shinyBitsMask = 0x80,
};
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


// Other-language retail carts (the app's RETAIL_PORTS): by game code and revision.
struct port_rom {
    const char* code;
    int rev;
    const struct pd_config* cfg;
    enum pd_game_kind kind;
    const char* title;
};
// The guide a port reads (its English text over the port's own tables): the base game's, by the code's third letter.
static enum pd_guide_id port_guide(const char* code) {
    if (code[3] == 'J') return PD_GUIDE_NONE; // Japanese tables are laid out apart: no guide yet
    switch (code[2]) {
    case 'R': return PD_GUIDE_FIRERED;
    case 'G': return PD_GUIDE_LEAFGREEN;
    case 'E': return PD_GUIDE_EMERALD;
    case 'V': return PD_GUIDE_RUBY;
    case 'P': return PD_GUIDE_SAPPHIRE;
    }
    return PD_GUIDE_NONE;
}
static const struct port_rom PORT_ROMS[] = {
    {"AXPD", 0, &PD_CONFIG_PORT_AXPD0, PD_GAME_EMERALD, "Pokémon Saphir-Edition (unverified)"},
    {"AXPD", 1, &PD_CONFIG_PORT_AXPD1, PD_GAME_EMERALD, "Pokémon Saphir-Edition (unverified)"},
    {"AXPF", 0, &PD_CONFIG_PORT_AXPF0, PD_GAME_EMERALD, "Pokémon Version Saphir (unverified)"},
    {"AXPF", 1, &PD_CONFIG_PORT_AXPF1, PD_GAME_EMERALD, "Pokémon Version Saphir (unverified)"},
    {"AXPI", 0, &PD_CONFIG_PORT_AXPI0, PD_GAME_EMERALD, "Pokémon Versione Zaffiro (unverified)"},
    {"AXPI", 1, &PD_CONFIG_PORT_AXPI1, PD_GAME_EMERALD, "Pokémon Versione Zaffiro (unverified)"},
    {"AXPS", 0, &PD_CONFIG_PORT_AXPS0, PD_GAME_EMERALD, "Pokémon Edición Zafiro (unverified)"},
    {"AXPS", 1, &PD_CONFIG_PORT_AXPS1, PD_GAME_EMERALD, "Pokémon Edición Zafiro (unverified)"},
    {"AXVD", 0, &PD_CONFIG_PORT_AXVD0, PD_GAME_EMERALD, "Pokémon Rubin-Edition (unverified)"},
    {"AXVD", 1, &PD_CONFIG_PORT_AXVD1, PD_GAME_EMERALD, "Pokémon Rubin-Edition (unverified)"},
    {"AXVF", 0, &PD_CONFIG_PORT_AXVF0, PD_GAME_EMERALD, "Pokémon Version Rubis (unverified)"},
    {"AXVF", 1, &PD_CONFIG_PORT_AXVF1, PD_GAME_EMERALD, "Pokémon Version Rubis (unverified)"},
    {"AXVI", 0, &PD_CONFIG_PORT_AXVI0, PD_GAME_EMERALD, "Pokémon Versione Rubino (unverified)"},
    {"AXVI", 1, &PD_CONFIG_PORT_AXVI1, PD_GAME_EMERALD, "Pokémon Versione Rubino (unverified)"},
    {"AXVS", 0, &PD_CONFIG_PORT_AXVS0, PD_GAME_EMERALD, "Pokémon Edición Rubí (unverified)"},
    {"AXVS", 1, &PD_CONFIG_PORT_AXVS1, PD_GAME_EMERALD, "Pokémon Edición Rubí (unverified)"},
    {"BPGD", 0, &PD_CONFIG_PORT_BPGD0, PD_GAME_FIRERED, "Pokémon Blattgrüne Edition (unverified)"},
    {"BPGF", 0, &PD_CONFIG_PORT_BPGF0, PD_GAME_FIRERED, "Pokémon Version Vert Feuille (unverified)"},
    {"BPGI", 0, &PD_CONFIG_PORT_BPGI0, PD_GAME_FIRERED, "Pokémon Versione Verde Foglia (unverified)"},
    {"BPGS", 0, &PD_CONFIG_PORT_BPGS0, PD_GAME_FIRERED, "Pokémon Edición Verde Hoja (unverified)"},
    {"BPRD", 0, &PD_CONFIG_PORT_BPRD0, PD_GAME_FIRERED, "Pokémon Feuerrote Edition (unverified)"},
    {"BPRF", 0, &PD_CONFIG_PORT_BPRF0, PD_GAME_FIRERED, "Pokémon Version Rouge Feu (unverified)"},
    {"BPRI", 0, &PD_CONFIG_PORT_BPRI0, PD_GAME_FIRERED, "Pokémon Versione Rosso Fuoco (unverified)"},
    {"BPRS", 0, &PD_CONFIG_PORT_BPRS0, PD_GAME_FIRERED, "Pokémon Edición Rojo Fuego (unverified)"},
    // The languages' Emerald (the app's NATIVE_EMERALD_ES / _DE / _FR / _IT / _JA) and the Japanese carts (RETAIL_PORTS).
    {"BPES", 0, &PD_CONFIG_EMERALD_ES, PD_GAME_EMERALD, "Pokémon Edición Esmeralda"},
    {"BPED", 0, &PD_CONFIG_EMERALD_DE, PD_GAME_EMERALD, "Pokémon Smaragd-Edition"},
    {"BPEF", 0, &PD_CONFIG_EMERALD_FR, PD_GAME_EMERALD, "Pokémon Version Émeraude"},
    {"BPEI", 0, &PD_CONFIG_EMERALD_IT, PD_GAME_EMERALD, "Pokémon Versione Smeraldo"},
    {"BPEJ", 0, &PD_CONFIG_EMERALD_JA, PD_GAME_EMERALD, "Pokémon Emerald (Japan)"},
    {"BPRJ", 0, &PD_CONFIG_PORT_BPRJ0, PD_GAME_FIRERED, "Pokémon FireRed (Japan)"},
    {"BPRJ", 1, &PD_CONFIG_PORT_BPRJ1, PD_GAME_FIRERED, "Pokémon FireRed (Japan)"},
    {"BPGJ", 0, &PD_CONFIG_PORT_BPGJ0, PD_GAME_FIRERED, "Pokémon LeafGreen (Japan)"},
    {"BPGJ", 1, &PD_CONFIG_PORT_BPGJ1, PD_GAME_FIRERED, "Pokémon LeafGreen (Japan)"},
    {"AXVJ", 0, &PD_CONFIG_PORT_AXVJ0, PD_GAME_EMERALD, "Pokémon Ruby (Japan)"},
    {"AXVJ", 1, &PD_CONFIG_PORT_AXVJ1, PD_GAME_EMERALD, "Pokémon Ruby (Japan)"},
    {"AXPJ", 0, &PD_CONFIG_PORT_AXPJ0, PD_GAME_EMERALD, "Pokémon Sapphire (Japan)"},
    {"AXPJ", 1, &PD_CONFIG_PORT_AXPJ1, PD_GAME_EMERALD, "Pokémon Sapphire (Japan)"},
    {NULL, 0, NULL, PD_GAME_NONE, NULL},
};

void pd_game_detect(struct pd_game* g, const uint8_t* header, size_t romSize) {
    static unsigned loads;
    memset(g, 0, sizeof(*g));
    g->loadId = ++loads ? loads : ++loads;
    memcpy(g->code, header + 0xAC, 4);
    g->code[4] = 0;
    g->language = (char) header[0xAF];
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
        for (const struct port_rom* r = PORT_ROMS; r->code; r++) {
            if (strcmp(r->code, g->code) || r->rev != g->revision) continue;
            g->kind = r->kind;
            g->title = r->title;
            g->guide = port_guide(r->code);
            g->cfg = romSize > 0x1000000 ? NULL : r->cfg;
            if (!g->cfg) g->unsupported = "Not a version the companion knows";
            return;
        }
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


// Game Boy games: a config with the Gen 3 fields empty, the reader's addresses (pd_gb.c) and the game's own names.
extern const struct pd_text pd_text_gb_yellow, pd_text_gb_yellow_de, pd_text_gb_yellow_fr, pd_text_gb_yellow_it,
    pd_text_gb_yellow_es, pd_text_gb_yellow_legacy, pd_text_gb_pure_rgb, pd_text_gb_crystal, pd_text_gb_gold_silver,
    pd_text_gb_crystal_clear, pd_text_gb_prism, pd_text_gb_polished, pd_text_gb_polished_faithful;
#define PD_GB_BAG { PD_POCKET_ITEMS, PD_POCKET_BALLS, PD_POCKET_TMHM, PD_POCKET_BERRIES, PD_POCKET_KEY }
#define PD_GB_CONFIG(NAME, GB, TEXT) \
    static const struct pd_config PD_GB_CONFIG_##NAME = { .text = &pd_text_##TEXT, .gb = &GB, .bagOrder = PD_GB_BAG, .bagCount = 5 };
PD_GB_CONFIG(RED, pd_gb_red, gb_yellow)
PD_GB_CONFIG(BLUE, pd_gb_blue, gb_yellow)
PD_GB_CONFIG(YELLOW, pd_gb_yellow, gb_yellow)
PD_GB_CONFIG(YELLOW_DE, pd_gb_yellow_de, gb_yellow_de)
PD_GB_CONFIG(YELLOW_FR, pd_gb_yellow_fr, gb_yellow_fr)
PD_GB_CONFIG(YELLOW_IT, pd_gb_yellow_it, gb_yellow_it)
PD_GB_CONFIG(YELLOW_ES, pd_gb_yellow_es, gb_yellow_es)
PD_GB_CONFIG(YELLOW_LEGACY, pd_gb_yellow_legacy, gb_yellow_legacy)
PD_GB_CONFIG(PURE_RED, pd_gb_pure_red, gb_pure_rgb)
PD_GB_CONFIG(PURE_BLUE, pd_gb_pure_red, gb_pure_rgb)
PD_GB_CONFIG(CRYSTAL, pd_gb_crystal, gb_crystal)
PD_GB_CONFIG(GOLD, pd_gb_gold, gb_gold_silver)
PD_GB_CONFIG(SILVER, pd_gb_gold, gb_gold_silver)
PD_GB_CONFIG(CRYSTAL_CLEAR, pd_gb_crystal_clear, gb_crystal_clear)
PD_GB_CONFIG(PRISM, pd_gb_prism, gb_prism)
PD_GB_CONFIG(YUME_BLUE, pd_gb_yume_blue, gb_yellow)
PD_GB_CONFIG(CRYSTAL_LEGACY, pd_gb_crystal_legacy, gb_crystal)
PD_GB_CONFIG(AMBROSIA, pd_gb_ambrosia, gb_crystal)
PD_GB_CONFIG(POLISHED, pd_gb_polished, gb_polished)
PD_GB_CONFIG(POLISHED_FAITHFUL, pd_gb_polished, gb_polished_faithful)

#define PD_NOT_A_GAME "Not a game the companion can read"
#define PD_NO_LAYOUT "Recognised, but its memory layout isn't mapped yet"
// Recognised, but nothing to read: games that aren't party games, and hacks whose memory layout isn't mapped.
struct unsupported_rom {
    const char* sha1;
    const char* title;
    const char* why;
};
static const struct unsupported_rom UNSUPPORTED_ROMS[] = {
    {"9402014d14969432142abfde728c6f1a10ee4dac", "Pokémon Pinball", PD_NOT_A_GAME},
    {"e40d6a7f78b449e5a55baaaea7e227ab19d510e4", "Pokémon Puzzle Challenge", PD_NOT_A_GAME},
    {"bd9227a3f7e4b600990d904e5e5b33021ede77f9", "Pokémon TCG Neo", PD_NOT_A_GAME},
    {"34ca3f54ea2dbec907a4d9af92befe2771f7649c", "Pokémon Trading Card Game 2", PD_NOT_A_GAME},
    {"0f8670a583255cff3e5b7ca71b5d7454d928fc48", "Pokémon Trading Card Game", PD_NOT_A_GAME},
    {"67949d03fd3dbfcec7903857560128ae6438110b", "Pokémon Scorched Silver", PD_NO_LAYOUT},
    {"b8b7881eaa9856b5ada99078ad1053d31cb79827", "Pokémon Pinball: Ruby & Sapphire", PD_NOT_A_GAME},
    {"afee3b060dd5fd4a68afb1b003456aef3a2af073", "Pokémon Mystery Dungeon: Red Rescue Team", PD_NOT_A_GAME},
    {"e596e713051ca40621e028ee839fdcbe8cc8b77d", "Pokémon POKeDUEL", PD_NOT_A_GAME},
    {NULL, NULL, NULL},
};

struct gb_rom {
    const char* sha1;
    const struct pd_config* cfg;
    const char* title;
};
static const struct gb_rom GB_ROMS[] = {
    {"65f409e0677cdb0cfefeb2f981136cc84ba45a1c", &PD_GB_CONFIG_YUME_BLUE, "Pokémon Yume Blue (unverified)"},
    {"74a6727e4406c35bc2d4f81347a5c5fdb8970663", &PD_GB_CONFIG_CRYSTAL_LEGACY, "Pokémon Crystal Legacy (unverified)"},
    {"0d96cd8447788dd72392e4983df2115bf40e9315", &PD_GB_CONFIG_AMBROSIA, "Pokémon Ambrosia (unverified)"},
    {"ea9bcae617fdf159b045185467ae58b2e4a48b9a", &PD_GB_CONFIG_RED, "Pokémon Red"},
    {"d7037c83e1ae5b39bde3c30787637ba1d4c48ce2", &PD_GB_CONFIG_BLUE, "Pokémon Blue"},
    {"cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1", &PD_GB_CONFIG_YELLOW, "Pokémon Yellow"},
    {"42f3714eec6eca25200d42461ff08d57c98f6d1d", &PD_GB_CONFIG_YELLOW_DE, "Pokémon Gelbe Edition"},
    {"0aceec0ef7aa2ca5aa831554598d91f61a925591", &PD_GB_CONFIG_YELLOW_FR, "Pokémon Version Jaune"},
    {"05bb8e99f24d498613930949730afa8024e77d08", &PD_GB_CONFIG_YELLOW_IT, "Pokémon Versione Gialla"},
    {"1dc242039218fba50928d1afb66b70565b6b9daf", &PD_GB_CONFIG_YELLOW_ES, "Pokémon Edición Amarilla"},
    {"2ac87de42afc70ef6016805acb3b206644417f97", &PD_GB_CONFIG_YELLOW_LEGACY, "Pokémon Yellow Legacy"},
    {"2e94d09c1e16a57eb079404c030f26fdeeac949d", &PD_GB_CONFIG_PURE_RED, "Pokémon PureRed"},
    {"d419fe244fa17196f7df46ddab47c840e7573652", &PD_GB_CONFIG_PURE_BLUE, "Pokémon PureBlue"},
    {"f4cd194bdee0d04ca4eac29e09b8e4e9d818c133", &PD_GB_CONFIG_CRYSTAL, "Pokémon Crystal"},
    {"f2f52230b536214ef7c9924f483392993e226cfb", &PD_GB_CONFIG_CRYSTAL, "Pokémon Crystal"},
    {"a0fc810f1d4e124434f7be2c989ab5b5892ddf36", &PD_GB_CONFIG_CRYSTAL, "Pokémon Crystal"},
    {"d8b8a3600a465308c9953dfa04f0081c05bdcb94", &PD_GB_CONFIG_GOLD, "Pokémon Gold"},
    {"49b163f7e57702bc939d642a18f591de55d92dae", &PD_GB_CONFIG_SILVER, "Pokémon Silver"},
    {"a1c4da6b0057c98fe4ebea45d6812a484fc69abd", &PD_GB_CONFIG_CRYSTAL_CLEAR, "Pokémon Crystal Clear"},
    {"6fc677bf7a9db103cb9ee11ac08d26782e69ae41", &PD_GB_CONFIG_CRYSTAL_CLEAR, "Pokémon Crystal Clear"},
    {"84d5c4924c3f1f3fc690669148b7e392948fb23d", &PD_GB_CONFIG_CRYSTAL_CLEAR, "Pokémon Crystal Clear"},
    {"534876e4e4d0d79a82fef3bbe5bea11c40451727", &PD_GB_CONFIG_CRYSTAL_CLEAR, "Pokémon Crystal Clear"},
    {"752076692ae3387cf426ce5f51a98c6b60e8df6a", &PD_GB_CONFIG_PRISM, "Pokémon Prism"},
    {"6930b48af5844d373e3c9130f26d6dd1084cf4ed", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"beb8b38280af59e9ef84be12684a6ecf09021c9b", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"79ebf9984eac8081f488d15a35a6c5d11b4f84e2", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"e55859484c5cda70e05daed59b14662e0a0f8eac", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"457d61dfc28a17388b87792595433ba20bd1c1ed", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"bea9804dc88a4ee370af62ff3139fdf1380277bf", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"9c13585eb34efdcd881c2e68f029b820bddf349d", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"11d461629363402fc15b5005ef51b8f44e726140", &PD_GB_CONFIG_POLISHED, "Pokémon Polished Crystal"},
    {"3ff091a8e94a0c031372b6f0275e12620fe3d766", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"e63b1e52a9f71c3948e3e7e515e9939d728fc679", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"666f8edfb9efd8c301adbc14d38ccfaae33a047d", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"b012f6d55c34c972778f5566e1cc910962dd7ab5", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"b5393eb27fec51970cc9186f36c34e94494bfd39", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"772858dd5a3e66dea4454d5f83b48c83d732ca69", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"c4bd263e58325c6c04c34462c1b4a37fba085e9c", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    {"c0a2c70d73ce68830a891ddd292300c90766acdd", &PD_GB_CONFIG_POLISHED_FAITHFUL, "Pokémon Polished Crystal (Faithful)"},
    // Guessed: builds the app does not list, on the neighbour whose RAM they most plausibly share.
    {"60d445d5a38364cc77e8fd1c043157e50e7df209", &PD_GB_CONFIG_PURE_RED, "Pokémon PureRed 2.7.7 (unverified)"},
    {"b96ee563112c2b17ce1828e48446e1d71d61e8b5", &PD_GB_CONFIG_PURE_BLUE, "Pokémon PureBlue 2.7.7 (unverified)"},
    // Green is Red's sibling (26k bytes differ from PureRed, 42k from PureBlue); no save of it has been read.
    {"258ec7fef3d966cfb2a7e16c15fe1a329cf392ce", &PD_GB_CONFIG_PURE_RED, "Pokémon PureGreen 2.7.7 (unverified)"},
    {NULL, NULL, NULL},
};

// The game's own font (Gen1Art / Gen2Art's font = bank to address, as a ROM offset), for the carts the app builds it for.
// Their party icons (Gen1Art / Gen2Art's YELLOW, RED_BLUE, CRYSTAL, GOLD_SILVER tables, as ROM offsets).
#define GB_OFF(bank, addr) ((uint32_t) ((bank) * 0x4000 + ((addr) - 0x4000)))
static const struct pd_gb_icons GB_ICONS_YELLOW = { .gen = 1, .pointers = GB_OFF(0x1C, 0x584D), .data = GB_OFF(0x1C, 0x59BA),
    .pointerCount = 30, .colors = { 0xFFFFFF, 0xFFFF00, 0x181818 } };
static const struct pd_gb_icons GB_ICONS_RED_BLUE = { .gen = 1, .pointers = GB_OFF(0x1C, 0x57C0), .data = GB_OFF(0x1C, 0x590D),
    .pointerCount = 28, .colors = { 0xFFEFFF, 0xF7B58C, 0x181010 } };
static const struct pd_gb_icons GB_ICONS_CRYSTAL = { .gen = 2, .pointers = GB_OFF(0x23, 0x6BBF), .data = GB_OFF(0x23, 0x6AC4),
    .bank = 0x23, .menuPal = GB_OFF(0x02, 0x7681), .eggIcon = 0x1C };
static const struct pd_gb_icons GB_ICONS_GOLD_SILVER = { .gen = 2, .pointers = GB_OFF(0x23, 0x6A70), .data = GB_OFF(0x23, 0x6975),
    .bank = 0x23, .menuPal = GB_OFF(0x02, 0x7AC6), .eggIcon = 0x1C };

// Gen 1's DEX pages (Gen1Dex.kt's GEN1_DEX_YELLOW / GEN1_DEX_RED, as ROM offsets).
static const struct pd_gb_dex1 GB_DEX_YELLOW = { .baseStats = GB_OFF(0x0E, 0x43DE), .dexEntryPointers = GB_OFF(0x10, 0x450B),
    .monsterPalettes = GB_OFF(0x1C, 0x6921), .cgbPalettes = GB_OFF(0x1C, 0x6AF9), .dexBank = 0x10, .picsBank = 0x09,
    .picsBankBreaks = { 0x1F, 0x4A, 0x74, 0x99 }, .fossilKabutops = 0xB6, .fossilKabutopsBank = 0x0B };
static const struct pd_gb_dex1 GB_DEX_RED = { .baseStats = GB_OFF(0x0E, 0x43DE), .dexEntryPointers = GB_OFF(0x10, 0x447E),
    .monsterPalettes = GB_OFF(0x1C, 0x65C8), .cgbPalettes = GB_OFF(0x1C, 0x6660), .dexBank = 0x10, .picsBank = 0x09,
    .picsBankBreaks = { 0x1F, 0x4A, 0x74, 0x99 }, .fossilKabutops = 0xB6, .fossilKabutopsBank = 0x0B,
    .mewBaseStats = GB_OFF(0x01, 0x425B), .mewPicsBank = 0x01 };

static void gb_font(const struct pd_game* g, const struct pd_config* cfg) {
    pd_gb_dex_use(cfg == &PD_GB_CONFIG_YELLOW ? &GB_DEX_YELLOW : cfg == &PD_GB_CONFIG_RED || cfg == &PD_GB_CONFIG_BLUE ? &GB_DEX_RED : NULL);
    pd_gb_icons_use(cfg == &PD_GB_CONFIG_YELLOW ? &GB_ICONS_YELLOW : cfg == &PD_GB_CONFIG_RED || cfg == &PD_GB_CONFIG_BLUE ? &GB_ICONS_RED_BLUE :
                    cfg == &PD_GB_CONFIG_CRYSTAL ? &GB_ICONS_CRYSTAL : cfg == &PD_GB_CONFIG_GOLD || cfg == &PD_GB_CONFIG_SILVER ? &GB_ICONS_GOLD_SILVER :
                    NULL);
    uint32_t off = 0;
    enum pd_gb_charset cs = PD_GB_CHARS_GEN1;
    if (cfg == &PD_GB_CONFIG_YELLOW) off = 0x04 * 0x4000 + (0x4600 - 0x4000);
    else if (cfg == &PD_GB_CONFIG_RED || cfg == &PD_GB_CONFIG_BLUE) off = 0x04 * 0x4000 + (0x5A80 - 0x4000);
    else if (cfg == &PD_GB_CONFIG_PURE_RED || cfg == &PD_GB_CONFIG_PURE_BLUE) { off = 0x04 * 0x4000 + (0x5F00 - 0x4000); cs = PD_GB_CHARS_PURE; }
    else if (cfg == &PD_GB_CONFIG_CRYSTAL || cfg == &PD_GB_CONFIG_CRYSTAL_CLEAR) { off = 0x3E * 0x4000 + (0x4200 - 0x4000); cs = PD_GB_CHARS_GEN2; }
    else if (cfg == &PD_GB_CONFIG_GOLD || cfg == &PD_GB_CONFIG_SILVER) { off = 0x3E * 0x4000 + (0x42F2 - 0x4000); cs = PD_GB_CHARS_GEN2; }
    else if (cfg == &PD_GB_CONFIG_PRISM) { off = 0x3D * 0x4000 + (0x720D - 0x4000); cs = PD_GB_CHARS_GEN2; }
    else if (cfg == &PD_GB_CONFIG_POLISHED || cfg == &PD_GB_CONFIG_POLISHED_FAITHFUL) {
        cs = PD_GB_CHARS_POLISHED;
        for (const struct pd_gb_font_at* p = pd_gb_polished_fonts; p->sha1; p++)
            if (!strcmp(p->sha1, g->sha1)) off = p->off;
    }
    // The companion keeps the GBA font in a Game Boy game too (the user's call: the cart's 8x8 letters read poorly at the
    // companion's size); the font's address stays mapped for when a page wants the game's own letters.
    (void) off;
    (void) cs;
    pd_gb_font_use(NULL, 0, PD_GB_CHARS_GEN1);
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
    // Guessed from the retail layout: the ROM's code reads the same addresses as often as retail's does (unverified on a save,
    // except Altered Emerald). Scorched Silver, below, reads none of them.
    {"b1d5feccdf9f1b2b025c5ff6062ce97134b1d0e7", &PD_CONFIG_EMERALD, PD_GAME_EMERALD, "Pokémon Altered Emerald (unverified)",
     PD_GUIDE_NONE},
    {"6650b2f7f2b7aa0c880061e0e362bbdb9ab999c6", &PD_CONFIG_EMERALD, PD_GAME_EMERALD, "Pokémon Delta Emerald (unverified)",
     PD_GUIDE_NONE},
    {"186449f6208675af4d8cfe4ace5afbf5186cea6d", &PD_CONFIG_SAPPHIRE, PD_GAME_EMERALD, "Pokémon Royal Sapphire (unverified)",
     PD_GUIDE_NONE},
    {"cbd71af65ee3dcd53280a6a12014bfb11e8f626a", &PD_CONFIG_EMERALD_LEGACY, PD_GAME_EMERALD, "Pokémon Emerald Legacy",
     PD_GUIDE_EMERALD_LEGACY},
    {"a7c4e34fafb53f2d5283eb6d43dad285c0dd40a8", &PD_CONFIG_EMERALD_KAIZO, PD_GAME_EMERALD, "Pokémon Emerald Kaizo",
     PD_GUIDE_EMERALD_KAIZO},
    {"e10105d8544469c6a11ca2cf510289981df3c3b0", &PD_CONFIG_GLAZED, PD_GAME_EMERALD, "Pokémon Glazed",
     PD_GUIDE_GLAZED},
    {"3cbd5a2e72ce60cfae1e0c28d83e01f855c51537", &PD_CONFIG_CELIA, PD_GAME_FIRERED, "Pokémon Celia's Stupid Romhack",
     PD_GUIDE_CELIA},
    {"8bac897de515f88e6ce92d0712ce2c018a394b37", &PD_CONFIG_ORANGE_ISLANDS, PD_GAME_FIRERED, "Pokémon Orange Islands",
     PD_GUIDE_ORANGE_ISLANDS},
    {"d2e3800e69e44c9649c3d9a15dc1cd12c1d90501", &PD_CONFIG_ORANGE_ISLANDS, PD_GAME_FIRERED, "Pokémon Orange Islands",
     PD_GUIDE_ORANGE_ISLANDS},
    {"3b6c51d8fb6b513bdd01b7bda3ed8965bc985d58", &PD_CONFIG_ULTRA_VIOLET, PD_GAME_FIRERED, "Pokémon Ultra Violet",
     PD_GUIDE_ULTRA_VIOLET},
    {"033d05374ae34eb84fae62c693ca8f3da23dd1bd", &PD_CONFIG_ROCKET_EDITION, PD_GAME_FIRERED, "Pokémon FireRed Rocket Edition",
     PD_GUIDE_ROCKET_EDITION},
    {"1ee1557db1e4c2e0e94c6306efcc40506bc40cc9", &PD_CONFIG_ASH_GRAY, PD_GAME_FIRERED, "Pokémon Ash Gray",
     PD_GUIDE_ASH_GRAY},
    {"92ed82145c013069472153391c7aab1cfbf37b75", &PD_CONFIG_ASH_GRAY, PD_GAME_FIRERED, "Pokémon Ash Gray Fork Bug Fix",
     PD_GUIDE_ASH_GRAY},
    {"d5b1e77975fcda831e0e9a7b527906bf3f40ecd0", &PD_CONFIG_GAIA, PD_GAME_FIRERED, "Pokémon Gaia",
     PD_GUIDE_GAIA},
    {"8745ddbdbfadf6abaf66de4e9055923b62eb4668", &PD_CONFIG_ODYSSEY, PD_GAME_FIRERED, "Pokémon Odyssey",
     PD_GUIDE_ODYSSEY},
    {"00e70c0384a5f1698588034201fd5b849d3542e2", &PD_CONFIG_AMETHYST, PD_GAME_FIRERED, "Pokémon Amethyst",
     PD_GUIDE_AMETHYST},
    {"91291aade04b4b111cd03ae7b6e2ff460e1edd8a", &PD_CONFIG_AMETHYST_V1_4_1, PD_GAME_FIRERED, "Pokémon Amethyst",
     PD_GUIDE_AMETHYST_V141},
    {"25f8840ecb58e584edf18ffde35f845e94f3f17c", &PD_CONFIG_GS_CHRONICLES, PD_GAME_FIRERED, "Pokémon GS Chronicles",
     PD_GUIDE_GS_CHRONICLES},
    {"20dfaea1d5317cf95fe0b493208edce7d50b865f", &PD_CONFIG_GHOST_GREY, PD_GAME_FIRERED, "Pokémon Ghost Grey",
     PD_GUIDE_GHOST_GREY},
    {"74337272566a74625d6dedefe47d2d153eb48ec1", &PD_CONFIG_GHOST_GREY, PD_GAME_FIRERED, "Pokémon Ghost Grey",
     PD_GUIDE_GHOST_GREY},
    {"0dafc0116b74406a2df491e26ccb40dd362fb0b7", &PD_CONFIG_GHOST_GREY, PD_GAME_FIRERED, "Pokémon Ghost Grey",
     PD_GUIDE_GHOST_GREY},
    {"afe4c8e0f9c7186860fcea5f25d8318f5d7929b9", &PD_CONFIG_SWORD_SHIELD, PD_GAME_FIRERED, "Pokémon Sword and Shield Ultimate Plus",
     PD_GUIDE_SWORD_SHIELD},
    {"daa2f89934d2c0447d5271dabeba86bfba04e1fd", &PD_CONFIG_SWORD_SHIELD, PD_GAME_FIRERED, "Pokémon Sword and Shield Ultimate Plus",
     PD_GUIDE_SWORD_SHIELD},
    {"4858cc3d7b335bb5eed553861d7a4a17bad9ce2c", &PD_CONFIG_SWORD_SHIELD, PD_GAME_FIRERED, "Pokémon Sword and Shield Ultimate Plus",
     PD_GUIDE_SWORD_SHIELD},
    {"932323d1fa3732d8673d1319fa86ed4df41087b9", &PD_CONFIG_SWORD_SHIELD, PD_GAME_FIRERED, "Pokémon Sword and Shield Ultimate Plus",
     PD_GUIDE_SWORD_SHIELD},
    {"964f951a0fdaf209e4ea1344883ef0d557bb3a80", &PD_CONFIG_RADICAL_RED, PD_GAME_FIRERED, "Pokémon Radical Red",
     PD_GUIDE_RADICAL_RED},
    {"b4776b82a4c7915d0fadeaa27e013523f99dfd94", &PD_CONFIG_UNBOUND, PD_GAME_FIRERED, "Pokémon Unbound",
     PD_GUIDE_UNBOUND},
    {"0ce2a880aa097f1dce4e1db8ee513d0e82d15859", &PD_CONFIG_UNBOUND_FR, PD_GAME_FIRERED, "Pokémon Unbound",
     PD_GUIDE_UNBOUND},
    {"1d20091c4d936f5eb122db8780554dd0829ffb63", &PD_CONFIG_IMPERIUM, PD_GAME_EMERALD, "Pokémon Emerald Imperium",
     PD_GUIDE_IMPERIUM},
    {"3fa8e61ec1727cc51540c54677623f736a446e1b", &PD_CONFIG_TMT2, PD_GAME_EMERALD, "Pokémon Too Many Types 2",
     PD_GUIDE_TMT2},
    {"8a6940ae3fb9a27577ab24833d7c928eb4d8db87", &PD_CONFIG_TMT2, PD_GAME_EMERALD, "Pokémon Too Many Types 2 (unverified)",
     PD_GUIDE_TMT2},
    {"be5033598e91d0e80fa93e551406f02a7876d7c2", &PD_CONFIG_RECHARGED_YELLOW_V1_8_0, PD_GAME_FIRERED, "Pokémon Recharged Yellow",
     PD_GUIDE_RECHARGED_YELLOW_V180},
    {"ac30d1ed80e75104a3fb92f71e2b9a51c26e80c8", &PD_CONFIG_RECHARGED_YELLOW_V1_9_0, PD_GAME_FIRERED, "Pokémon Recharged Yellow",
     PD_GUIDE_RECHARGED_YELLOW_V190},
    {"7d9fae6cf31c07bc7fa5cf7afe137ad5385cefb1", &PD_CONFIG_RECHARGED_YELLOW_V1_9_4, PD_GAME_FIRERED, "Pokémon Recharged Yellow",
     PD_GUIDE_RECHARGED_YELLOW_V194},
    {"ed60654d7525ba005bfc8f799bf0ecba2ef08a2e", &PD_CONFIG_RECHARGED_YELLOW_V1_9_7, PD_GAME_FIRERED, "Pokémon Recharged Yellow",
     PD_GUIDE_RECHARGED_YELLOW},
    {"b9f4d332d30fc88c379f9e037f9eae3b2755ead4", &PD_CONFIG_SEAGLASS, PD_GAME_EMERALD, "Pokémon Emerald Seaglass",
     PD_GUIDE_SEAGLASS},
    {"7dcdc7e280bc4631487e13dd37e6e0cea04adea6", &PD_CONFIG_LAZARUS, PD_GAME_EMERALD, "Pokémon Lazarus",
     PD_GUIDE_LAZARUS},
    {"60182b934d2e8eb3eef226a9bbc25e5df6becd4f", &PD_CONFIG_WISHES, PD_GAME_EMERALD, "Pokémon Wishes of Tomorrow",
     PD_GUIDE_WISHES},
    {"06e79d12afa578db97c23c1f7ba771fe2f8fe905", &PD_CONFIG_MARIOMON_V1_5_2, PD_GAME_EMERALD, "Super Mariomon",
     PD_GUIDE_MARIOMON},
    {"831fe0f97301f17b24a9cd4a64ef0520835c4dde", &PD_CONFIG_MARIOMON_V1_5_1, PD_GAME_EMERALD, "Super Mariomon",
     PD_GUIDE_MARIOMON},
    {"c2ee8cc6b0a80bde0f4fb0d4a2e1fb984bccec77", &PD_CONFIG_MARIOMON_V1_5_1, PD_GAME_EMERALD, "Super Mariomon (unverified)",
     PD_GUIDE_MARIOMON},
    {"043512c2497871c018df606fb90ffbd4c81430f4", &PD_CONFIG_MARIOMON_V1_3, PD_GAME_EMERALD, "Super Mariomon",
     PD_GUIDE_MARIOMON},
    {"08d1c275c0ba4d559b33427292702d61ad49fb5c", &PD_CONFIG_MARIOMON_V1_2_2, PD_GAME_EMERALD, "Super Mariomon",
     PD_GUIDE_MARIOMON},
    {"43dd208b23eade69aa3384301e934ecafbf6c2b3", &PD_CONFIG_MARIOMON_V1_2_1, PD_GAME_EMERALD, "Super Mariomon",
     PD_GUIDE_MARIOMON},
    {"81bd0f4bfa1c04ab2c6faab1bddd10e8a390ea77", &PD_CONFIG_ROWE, PD_GAME_EMERALD, "Pokémon R.O.W.E.",
     PD_GUIDE_NONE},
    {"40780347d0b4df6bf491eaf12603e5422caee677", &PD_CONFIG_TEAM_ROCKET, PD_GAME_EMERALD, "Pokémon Team Rocket Edition",
     PD_GUIDE_TEAM_ROCKET},
    {"20e91aecb3243eea7cd610659846f37062f5d709", &PD_CONFIG_DREAMSTONE, PD_GAME_EMERALD, "Pokémon Dreamstone Mysteries",
     PD_GUIDE_DREAMSTONE},
    {"ea5d369cc8a31cbf1cfacb7c9470ea670f08957b", &PD_CONFIG_SOULGOLD, PD_GAME_EMERALD, "Pokémon SoulGold",
     PD_GUIDE_SOULGOLD},
    {"805d880ee229fb6dc3ce03d7b03baf48f0d759d0", &PD_CONFIG_SOULGOLD_V1_2, PD_GAME_EMERALD, "Pokémon SoulGold",
     PD_GUIDE_SOULGOLD},
    {"5d6a036260fdbde96f85b5c1b92d0256d3aebafb", &PD_CONFIG_SOULGOLD_V1_2B, PD_GAME_EMERALD, "Pokémon SoulGold",
     PD_GUIDE_SOULGOLD},
    {"2be7c99aa473308debe1d03ff2015bab7d9addb5", &PD_CONFIG_SOULGOLD_V1_2C, PD_GAME_EMERALD, "Pokémon SoulGold",
     PD_GUIDE_SOULGOLD},
    {"32988f918ca09923466ae3bc5bbfe5f2a771c728", &PD_CONFIG_SOULGOLD_V1_2_1, PD_GAME_EMERALD, "Pokémon SoulGold",
     PD_GUIDE_SOULGOLD},
    {"dacb6e40e7f572ddefd2dfa10e99c0da6835b062", &PD_CONFIG_SOULGOLD_V1_2_1, PD_GAME_EMERALD, "Pokémon SoulGold",
     PD_GUIDE_SOULGOLD},
    {"62bb900925118796fb963b7df92d02cb548c404e", &PD_CONFIG_LETS_GO_PIKACHU, PD_GAME_FIRERED, "Pokémon Let's Go Pikachu",
     PD_GUIDE_LETS_GO},
    {"11f6ee3890a0aaeb95903471ec73413c846e86fc", &PD_CONFIG_LETS_GO_PIKACHU, PD_GAME_FIRERED,
     "Pokémon Let's Go Pikachu (Translation Only)", PD_GUIDE_LETS_GO},
    {"4a41d539cbf6108cfe3344db5ba997fc5660d77d", &PD_CONFIG_LETS_GO_EEVEE, PD_GAME_FIRERED, "Pokémon Let's Go Eevee",
     PD_GUIDE_LETS_GO},
    {"5b6146adec1a30f88c565e8f555ab7a578825598", &PD_CONFIG_LETS_GO_EEVEE, PD_GAME_FIRERED,
     "Pokémon Let's Go Eevee (Translation Only)", PD_GUIDE_LETS_GO},
    {"ae1c4be9be8d706bb989602bc05731203d42860d", &PD_CONFIG_MONOMYTH, PD_GAME_EMERALD, "Pokémon Monomyth",
     PD_GUIDE_MONOMYTH},
    {"eb660caffbc26e1f09f86714d03af4c3eccd9e4d", &PD_CONFIG_MONOMYTH_V1_1, PD_GAME_EMERALD, "Pokémon Monomyth",
     PD_GUIDE_MONOMYTH},
    {"79ee6df0869c1773c8c6a5f764afc1f8d833d8bb", &PD_CONFIG_HEART_AND_SOUL, PD_GAME_EMERALD, "Pokémon Heart and Soul",
     PD_GUIDE_HEART_AND_SOUL},
    {"d0658315da1e8827f66f15c3d3a000fe747e163e", &PD_CONFIG_QUETZAL_V9_0, PD_GAME_EMERALD, "Pokémon Quetzal",
     PD_GUIDE_QUETZAL},
    {"fe346b5b0eb022e3a103f81e7dcefba6f1dc542f", &PD_CONFIG_QUETZAL_V9_0_ES, PD_GAME_EMERALD, "Pokémon Quetzal",
     PD_GUIDE_QUETZAL},
    {"c108cec750a35775084c0806cc704d8abd1994e7", &PD_CONFIG_QUETZAL_V9_1, PD_GAME_EMERALD, "Pokémon Quetzal",
     PD_GUIDE_QUETZAL},
    {"ff008b70b6f2b330da9ffa8a405fc951a9e54147", &PD_CONFIG_QUETZAL_V9_1_ES, PD_GAME_EMERALD, "Pokémon Quetzal",
     PD_GUIDE_QUETZAL},
    {"6806c7146aff457a4be85ef2030e9ddcdb17b665", &PD_CONFIG_QUETZAL_V0_8_4, PD_GAME_EMERALD, "Pokémon Quetzal",
     PD_GUIDE_QUETZAL_V084},
    {"b6542c7dbb40cf6ea96714a7a0e30cb468102861", &PD_CONFIG_QUETZAL_V0_8_4_ES, PD_GAME_EMERALD, "Pokémon Quetzal",
     PD_GUIDE_QUETZAL_V084},
    {"f1035f615c13c46394a18fa99181c24c3cd15e12", &PD_CONFIG_MOEMON_QUETZAL_V0_8_4, PD_GAME_EMERALD, "Moemon Quetzal",
     PD_GUIDE_QUETZAL_V084},
    {"ae5d88bb823a8f9d0d07e8784da2376ff7c1ae43", &PD_CONFIG_MOEMON_QUETZAL_V0_8_4_ES, PD_GAME_EMERALD, "Moemon Quetzal",
     PD_GUIDE_QUETZAL_V084},
    {"67f44fb4cdbb16c0d1a495ac219f2979b1e3005f", &PD_CONFIG_RECHARGED_EMERALD_V2_2_5, PD_GAME_EMERALD, "Pokémon Recharged Emerald",
     PD_GUIDE_RECHARGED_EMERALD},
    {"56c9777215f5be627f7c3099e68985aefc10f2b7", &PD_CONFIG_RECHARGED_EMERALD_V2_2_8, PD_GAME_EMERALD, "Pokémon Recharged Emerald",
     PD_GUIDE_RECHARGED_EMERALD},
    {"08b9c7903ae4c4e26d80ef945a90c004ae1e0ebb", &PD_CONFIG_RECHARGED_EMERALD_REBALANCED_V2_2_8, PD_GAME_EMERALD, "Pokémon Recharged Emerald Rebalanced",
     PD_GUIDE_RECHARGED_EMERALD},
    {"7600af1fe08444c850c3c1227fd7dfd81336ae8e", &PD_CONFIG_EMERALD_ROGUE, PD_GAME_EMERALD, "Pokémon Emerald Rogue EX",
     PD_GUIDE_NONE},
    {"2f3e17e55c6afe23da97ec0f96a067eeb142ad15", &PD_CONFIG_ELITE_REDUX_V2_65_4_1B, PD_GAME_EMERALD,
     "Pokémon Elite Redux", PD_GUIDE_ELITE_REDUX},
    {"5b38a681b6924ef3e0bb9f6cfbf22426c4c4e523", &PD_CONFIG_ELITE_REDUX_V2_65_3B, PD_GAME_EMERALD,
     "Pokémon Elite Redux", PD_GUIDE_ELITE_REDUX},
    // Guessed: builds the app doesn't list, matched to the neighbour whose addresses they most plausibly share.
    {"411e9023cefae108e6cc28be493e3fd91e9aba6e", &PD_CONFIG_AMETHYST_V1_4_1, PD_GAME_FIRERED,
     "Pokémon Amethyst 1.4.3 (unverified)", PD_GUIDE_AMETHYST_V141},
    {"95a91a0863ed43dd17d6f4a99f70d592725dd6cc", &PD_CONFIG_EMERALD_ROGUE, PD_GAME_EMERALD,
     "Pokémon Emerald Rogue 2.2.1 Vanilla (unverified)", PD_GUIDE_NONE},
    {"5b93702f0e464df37b01500d4716d59f05c0425e", &PD_CONFIG_QUETZAL_V9_1, PD_GAME_EMERALD,
     "Moemon Quetzal 0.9.1 (unverified)", PD_GUIDE_QUETZAL},
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

static void identify(struct pd_game* g) {
    pd_gb_font_use(NULL, 0, PD_GB_CHARS_GEN1);
    pd_gb_icons_use(NULL);
    pd_gb_dex_use(NULL);
    if (!g->rom) return;
    // A Game Boy cart (the Nintendo logo at 0x104, and no GBA header's 0x96 at 0xB2): which one waits for its hash.
    // The logo is past the 0xC0 header pd_game_detect gets, so it is tested here, with the whole ROM.
    if (g->romSize >= 0x150 && g->rom[0xB2] != 0x96 && g->rom[0x104] == 0xCE && g->rom[0x105] == 0xED &&
        g->rom[0x106] == 0x66) {
        g->kind = PD_GAME_GB;
        g->cfg = NULL;
        g->title = "Game Boy ROM";
        g->unsupported = "Not a Game Boy game the companion knows";
    }
    struct pd_sha1 s;
    pd_sha1_init(&s);
    pd_sha1_update(&s, g->rom, g->romSize);
    pd_sha1_hex(&s, g->sha1);
    g->shinyBelow = 0;
    g->retail = false;
    for (const struct pd_shiny_rom* r = pd_shiny_roms; r->sha1; r++)
        if (!strcmp(r->sha1, g->sha1)) g->shinyBelow = r->below;

    for (const struct unsupported_rom* u = UNSUPPORTED_ROMS; u->sha1; u++) {
        if (strcmp(u->sha1, g->sha1)) continue;
        g->cfg = NULL;
        g->guide = PD_GUIDE_NONE;
        g->title = u->title;
        g->unsupported = u->why;
        return;
    }
    if (g->kind == PD_GAME_GB) {
        for (const struct gb_rom* r = GB_ROMS; r->sha1; r++) {
            if (strcmp(r->sha1, g->sha1)) continue;
            g->cfg = r->cfg;
            g->title = r->title;
            g->guide = r->cfg == &PD_GB_CONFIG_RED ? PD_GUIDE_GB_RED : r->cfg == &PD_GB_CONFIG_BLUE ? PD_GUIDE_GB_BLUE :
                       r->cfg == &PD_GB_CONFIG_YELLOW || r->cfg == &PD_GB_CONFIG_YELLOW_DE || r->cfg == &PD_GB_CONFIG_YELLOW_FR ||
                       r->cfg == &PD_GB_CONFIG_YELLOW_IT || r->cfg == &PD_GB_CONFIG_YELLOW_ES ? PD_GUIDE_GB_YELLOW : r->cfg == &PD_GB_CONFIG_CRYSTAL ? PD_GUIDE_GB_CRYSTAL :
                       r->cfg == &PD_GB_CONFIG_POLISHED || r->cfg == &PD_GB_CONFIG_POLISHED_FAITHFUL ? PD_GUIDE_GB_POLISHED :
                       r->cfg == &PD_GB_CONFIG_YELLOW_LEGACY ? PD_GUIDE_GB_YELLOW_LEGACY :
                       r->cfg == &PD_GB_CONFIG_CRYSTAL_LEGACY ? PD_GUIDE_GB_CRYSTAL_LEGACY : PD_GUIDE_NONE;
            g->hackMaps = true;
            g->unsupported = NULL;
            gb_font(g, r->cfg);
            return;
        }
        return;
    }
    for (const struct known_rom* k = KNOWN_ROMS; k->sha1; k++) {
        if (strcmp(k->sha1, g->sha1)) continue;
        g->cfg = k->cfg;
        g->kind = k->kind;
        g->title = k->title;
        g->guide = k->guide;
        g->hackMaps = false; // its names: pd_text.mapsecs / mapsecsRom, else its engine's retail ones (the app's activeMapSecData)
        g->unsupported = NULL;
        return;
    }
    if (!g->kind || !g->cfg) return; // detect's verdict (a revision past 1, a big hack) stands
    // LeafGreen, Ruby and Sapphire are read by code and revision alone, like the app (OTHER_RETAIL_CODES):
    // only FireRed and Emerald, which most hacks build on, need a retail hash.
    if (strcmp(g->code, "BPRE") && strcmp(g->code, "BPEE")) return;
    // English FireRed / Emerald: retail's shiny check once the hash (or a collection's dump of it) checks out (Poller.kt).
    for (size_t i = 0; i < sizeof RETAIL_SHA1S / sizeof *RETAIL_SHA1S; i++)
        if (!strcmp(RETAIL_SHA1S[i], g->sha1)) { g->shinyBelow = 8; g->retail = true; return; }
    if (retail_dump(g)) { g->shinyBelow = 8; g->retail = true; return; }
    // Retail as Universal Pokemon Randomizer ZX left it (the app's RandomizedRetail): retail's config and guide, what the
    // randomizer changed read from the ROM.
    g->randomized = pd_randomized_match(g);
    if (g->randomized) {
        g->shinyBelow = 8;
        g->retail = true;
        g->title = g->kind == PD_GAME_FIRERED ? "Pokémon FireRed (Randomized)" : "Pokémon Emerald (Randomized)";
        return;
    }
    g->cfg = NULL;
    g->guide = PD_GUIDE_NONE;
    g->title = g->kind == PD_GAME_FIRERED ? "FireRed ROM hack" : "Emerald ROM hack";
    g->unsupported = "Not a version the companion knows";
}

// The app's extras for a ROM (its look, its PC's moves), by SHA-1: pd_games_gen.c's entries are sorted by it.
const struct pd_game_entry* pd_game_entry_for(const char* sha1) {
    int lo = 0, hi = pd_game_entry_count - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        int c = strcmp(pd_game_entries[mid].sha1, sha1);
        if (!c) return &pd_game_entries[mid];
        if (c < 0) lo = mid + 1;
        else hi = mid - 1;
    }
    return NULL;
}

// The config with the entry's field items laid over the parts gamedata's leaves empty: FLY (from flyFlagGet to the
// field items), the field items and PC (from fieldVarGet to the end), a bag of its own, USE / GIVE / TAKE.
static struct pd_config merged;

static void overlay(struct pd_game* g, const struct pd_config* add) {
    merged = *g->cfg;
    if (!merged.flyFlagGet && add->flyFlagGet)
        memcpy(&merged.flyFlagGet, &add->flyFlagGet, offsetof(struct pd_config, fieldVarGet) - offsetof(struct pd_config, flyFlagGet));
    if (!merged.fieldVarGet && add->fieldVarGet)
        memcpy(&merged.fieldVarGet, &add->fieldVarGet, sizeof(struct pd_config) - offsetof(struct pd_config, fieldVarGet));
    if (!merged.bag3Off && !merged.itemFlagsOff && (add->bag3Off || add->itemFlagsOff)) {
        merged.bag3Off = add->bag3Off;
        merged.bag3TailOff = add->bag3TailOff;
        merged.bag3SplitAt = add->bag3SplitAt;
        merged.bag3KeyBit = add->bag3KeyBit;
        merged.itemFlagsOff = add->itemFlagsOff;
        merged.itemFlagsCount = add->itemFlagsCount;
        merged.itemPockets = add->itemPockets;
        merged.itemPocketCount = add->itemPocketCount;
    }
    if (!merged.partyFns) merged.partyFns = add->partyFns;
    g->cfg = &merged;
}

void pd_game_identify(struct pd_game* g) {
    g->entry = NULL;
    identify(g);
    if (!g->rom || !g->sha1[0]) {
        pd_look_apply(g, NULL);
        return;
    }
    g->entry = pd_game_entry_for(g->sha1);
    if (g->entry && g->entry->language) g->language = g->entry->language;
    if (g->entry && g->entry->fieldCfg && g->cfg && !g->cfg->gb) overlay(g, g->entry->fieldCfg);
    pd_look_apply(g, NULL); // the game's windows until the host applies its settings
}
