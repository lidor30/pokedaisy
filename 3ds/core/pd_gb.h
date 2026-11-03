// Game Boy games (Gen 1 and Gen 2): where each keeps its party, bag and battle in WRAM, and the reader that turns it
// into a struct pd_snapshot (the app's Gen1Reader.kt / Gen2Reader.kt). Addresses: a bus address (C000-CFFF), or
// WRAM bank b's D000-DFFF as (b << 16) | address (the host reads that bank whatever SVBK says).
#ifndef PD_GB_H
#define PD_GB_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_snapshot.h"

#define PD_WRAMX(bank, addr) (((uint32_t) (bank) << 16) | (addr))

// Where a Gen 1 game keeps what its GUIDE reads: event flags (320 bytes), the missable-object (32), hidden-item (14) and
// trade (2) banks, the badge byte, the champion's team byte. The snapshot packs them into flags[] (gen_guide.py's GB_BANK_BIT).
struct pd_gb_guide_ram {
    uint32_t event, missable, hidden, trades, badges, teamVar;
    uint32_t curMap; // wCurMap: HERE's map
};
struct pd_gb_team;

struct pd_gb_config {
    int gen; // 1 or 2
    uint32_t partyCount, partyMons;
    // Gen 1: wNumBagItems (then {item, quantity} pairs). Gen 2: wNumItems, wNumKeyItems, wNumBalls, wTMsHMs.
    uint32_t numItems, numKeyItems, numBalls, tmsHms;
    uint32_t money;
    uint32_t battleMode; // Gen 1's wIsInBattle: 0 none, 1 wild, 2 trainer
    uint32_t battleMon, enemyMon, otPartyCount, otPartyMons, curOTMon;
    const uint16_t* internalToDex; // Gen 1: the internal species index -> Dex number
    int internalCount;
    const uint16_t* genderRatios; // Gen 2, by species id
    int genderCount;
    const uint16_t* tmItems; // Gen 2: the item id of each TM / HM
    int tmCount;
    int speciesCount;        // Gen 2: the highest species id
    int itemsMax, ballsMax, keyItemsMax, bagSlots; // pocket sizes (Gen 1: bagSlots)
    bool tmBits;             // wTMsHMs is a bit per TM, not a quantity
    bool polished;           // Polished Crystal's own structs, pockets and charmap
    uint32_t numMedicine, numBerries;
    int medicineMax, berriesMax;
    // POKéDEX flags: wPokedexOwned / wPokedexSeen, a bit per Dex number from 1 (Gen 2: by species id); dexCount 0 = no DEX.
    uint32_t dexCaught, dexSeen;
    int dexCount;
    const struct pd_gb_guide_ram* guideRam; // Gen 1: the GUIDE's RAM (NULL = no guide)
    // Crystal's GUIDE RAM (GuideCrystalGen.kt: wEventFlags, wTimeOfDay, wJohtoBadges, wTradeFlags) and wMapGroup / wMapNumber
    const struct pd_gb_guide2_ram { uint32_t event, timeOfDay, badges, trades, mapGroup; } * guide2Ram;
    const struct pd_gb_team* teams;         // the GUIDE's boss teams, by index
};

extern const uint32_t pd_gb_polished_variants[];
extern const int pd_gb_polished_variants_count;

extern const struct pd_gb_config pd_gb_yellow, pd_gb_red, pd_gb_pure_red, pd_gb_crystal, pd_gb_gold, pd_gb_prism,
    pd_gb_polished, pd_gb_crystal_clear, pd_gb_blue, pd_gb_yume_blue, pd_gb_crystal_legacy, pd_gb_ambrosia;

// The game's own font (the app's Gen1Art / Gen2Art font): FontGraphics' 1bpp 8x8 tiles 0x80-0xFF from the ROM, mapped to
// characters by the game's charmap and drawn 9/7 as tall as the standard font's letters, near its width (the app's
// LocalGameTextScale 9/7, LocalGameFontWidth 0.75). Installed with pd_font_set; pd_gb_font_use(NULL, ...) removes it.
enum pd_gb_charset { PD_GB_CHARS_GEN1, PD_GB_CHARS_PURE, PD_GB_CHARS_GEN2, PD_GB_CHARS_POLISHED };
struct pd_gb_font_at { const char* sha1; uint32_t off; };
extern const struct pd_gb_font_at pd_gb_polished_fonts[];
struct pd_game;
void pd_gb_font_use(const struct pd_game* g, uint32_t off, enum pd_gb_charset charset);

// The game's party icon (the app's Gen1Art / Gen2Art icons), frame 1 as 16x16 ARGB (0 = clear), for a Dex number or
// PD_SPECIES_EGG. Set per cart by pd_gb_icons_use (pd_game.c); false where the cart's icons aren't read here.
struct pd_gb_icons {
    int gen;                       // 1: MonPartySpritePointers into VRAM, mirrored halves; 2: an icon table + menu palette
    uint32_t pointers, data;       // Gen 1: the pointer table (ROM offset), MonPartyData; Gen 2: Icons' pointers, MonMenuIcons
    int pointerCount, bank;        // Gen 1: entries; Gen 2: Icons' bank
    uint32_t colors[3];            // Gen 1: pixel values 1-3
    uint32_t menuPal;              // Gen 2: PartyMenuOBPals (ROM offset)
    int eggIcon;
};
void pd_gb_icons_use(const struct pd_gb_icons* icons);
bool pd_gb_icon(const struct pd_game* g, int species, uint32_t* out);

// Gen 1's DEX pages from the cart (the app's Gen1DexTables / Gen1Dex): ROM offsets of BaseStats, PokedexEntryPointers
// (same bank as the entries), MonsterPalettes and the colour palettes; the "Pics" banks; MEW's own base stats (Red / Blue).
struct pd_gb_dex1 {
    uint32_t baseStats, dexEntryPointers, monsterPalettes, cgbPalettes;
    int dexBank;
    int picsBank, picsBankBreaks[4], fossilKabutops, fossilKabutopsBank;
    uint32_t mewBaseStats;
    int mewPicsBank;
};
struct pd_dex_entry;
void pd_gb_dex_use(const struct pd_gb_dex1* t);
bool pd_gb_dex_pages(const struct pd_game* g); // this cart's pages are read
bool pd_gb_dex_entry(const struct pd_game* g, int dex, struct pd_dex_entry* out);
bool pd_gb_dex_sprite(const struct pd_game* g, int dex, uint32_t* out); // PD_SPRITE square, 0xFF000000 = clear

struct pd_config;
void pd_gb_snapshot_read(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx);

#endif
