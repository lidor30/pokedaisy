// Game Boy games (Gen 1 and Gen 2): where each keeps its party, bag and battle in WRAM, and the reader that turns it
// into a struct pd_snapshot (the app's Gen1Reader.kt / Gen2Reader.kt). Addresses: a bus address (C000-CFFF), or
// WRAM bank b's D000-DFFF as (b << 16) | address (the host reads that bank whatever SVBK says).
#ifndef PD_GB_H
#define PD_GB_H

#include <stdbool.h>
#include <stddef.h>
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
    // Gen 2's place (the app's gen2Landmark): wMapGroup / wMapNumber and wBackupMapGroup / wBackupMapNumber, each map's
    // landmark ({group << 8 | number, landmark}); a SPECIAL one (0) is named by the backup map, as the town map does.
    // playerGender: wPlayerGender (bit 0 = the girl), 0 = none (Gold / Silver: always the boy).
    uint32_t mapGroup, backupMapGroup, playerGender;
    const uint16_t (*landmarks)[2];
    const int* landmarkCount;
    // The battle's controls (the app's GbPartySwitch / readGen1BattleInput / readGen2BattleInput): wTileMap (a menu waits
    // while its cursor tile is drawn), the move menu's type byte, the party slot that's out; Gen 1's menu (wTopMenuItemY ...,
    // the battle menu's two cursor columns, wPartyMenuTypeOrMessageID and its value after a faint); Gen 2's _2DMenu and
    // cursor, its cursor tile and wPartyMenuActionText. 0 = no battle controls on this cart.
    uint32_t tileMap, moveMenuType, outSlot;
    uint32_t topMenuItemY, partyMenuType;
    uint8_t battleMenuLeftX, battleMenuRightX, battlePartyMenu;
    uint32_t menu2D, menuCursor, partyMenuAction;
    uint8_t cursorTile;
};
extern const uint16_t pd_gb_landmarks_gold_silver[][2];
extern const int pd_gb_landmarks_gold_silver_count;
extern const uint16_t pd_gb_landmarks_polished[][2];
extern const int pd_gb_landmarks_polished_count;

extern const uint32_t pd_gb_polished_variants[];
extern const int pd_gb_polished_variants_count;

extern const struct pd_gb_config pd_gb_yellow, pd_gb_red, pd_gb_pure_red, pd_gb_crystal, pd_gb_gold, pd_gb_prism,
    pd_gb_polished, pd_gb_crystal_clear, pd_gb_blue, pd_gb_yume_blue, pd_gb_crystal_legacy, pd_gb_ambrosia,
    pd_gb_yellow_de, pd_gb_yellow_fr, pd_gb_yellow_it, pd_gb_yellow_es, pd_gb_yellow_legacy;

// The game's own font (the app's Gen1Art / Gen2Art font): FontGraphics' 1bpp 8x8 tiles 0x80-0xFF from the ROM, mapped to
// characters by the game's charmap and drawn 9/7 as tall as the standard font's letters, near its width (the app's
// LocalGameTextScale 9/7, LocalGameFontWidth 0.75). Installed with pd_font_set; pd_gb_font_use(NULL, ...) removes it.
enum pd_gb_charset { PD_GB_CHARS_GEN1, PD_GB_CHARS_PURE, PD_GB_CHARS_GEN2, PD_GB_CHARS_POLISHED };
struct pd_gb_font_at { const char* sha1; uint32_t off; };
extern const struct pd_gb_font_at pd_gb_polished_fonts[];
struct pd_game;
void pd_gb_font_use(const struct pd_game* g, uint32_t off, enum pd_gb_charset charset);

// The game's party icon (the app's Gen1Art / Gen2Art icons), frame 1 as 16x16 ARGB (0 = clear), for a Dex number or
// Where the cart keeps its art, as file offsets (Gen1ArtTables / Gen2ArtTables, its pd_games_gen.c entry's gbArt). 0 = not on this cart.
struct pd_gb_art {
    // Gen 1.
    uint32_t iconPointers, monPartyData, iconSheet;
    int iconPointerCount, dexCount;
    uint32_t iconColors[3];
    // Gen 2.
    uint32_t icons, monMenuIcons, partyMenuObPals, iconPalettes, iconFarPointers, iconPalIndex, iconObjPals, minis, monPalettes;
    uint32_t unownIcons;
    int iconsBank, eggIcon, bankLow, bankHigh, unownBank, cosmeticForms;
    // The town map: Gen 1's RLE over its 16 tiles; Gen 2's tile ids over the LZ tiles, each with a palette.
    uint32_t townMap, townMapTiles, townMapColors[4];
    uint32_t playerSprite, playerColors[4];
    uint32_t townMapGfx, townMaps[6], townMapPalMap, pokegearPals, objectPals, playerSprites[3];
    int townMapCount, townMapPalCount, townMapPal0From, playerPalFrom;
    bool townMapsLz, townMapFlips, playerLz;
};

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

// The Gen 2 carts' POKéDEX pages (Gen2Dex.kt / Polished Crystal's / Prism's, pd_gb_dex.c), from a cart's tables in its
// pd_games_gen.c entry (gbDex): Gen 1's pages stay pd_gb_dex1's above.
// Where a cart keeps its POKéDEX (Gen1DexTables / Gen2DexTables), as file offsets; 0 = not on this cart.
struct pd_gb_dex {
    int count;  // the entries it lists
    bool metric, unitSpace;
    char decimal;
    const char* suffix;       // after the category ("POKéMON", Prism's "Pokémon")
    const char* const* chars; // the page text's characters, by byte
    uint32_t baseData, dexEntryPointers, palettes;
    int baseDataSize;
    // Gen 1.
    uint32_t colors, mewBaseStats, dexEntryData, categoryMapping, categoryStrings;
    int picsBank, picsBankBreaks[4], fossilKabutops, fossilKabutopsBank, mewPicsBank, picBankOffset, textFar;
    // Gen 2.
    uint32_t dexEntryBanks, picPointers, unownPicPointers, picsBanks, textDict, picBankFixes;
    uint32_t packedTree, packedRareChars, packedRareRanges, packedControlChars; // Prism's text engine (0 = plain text)
    const uint16_t* dexOrder; // the species by place, where its numbers aren't the species ids
    int dexOrderCount, unown, dexEntryFirstBank;
    bool picBankDirect;
    bool dexEntryFar;          // a 3-byte far pointer per species (Crystal Legacy), not a bank table + addresses
    const int32_t* polished;  // Polished Crystal: this build's tables, bank << 16 | address
    const char* const* polishedChars;
    int polishedSpecies;      // its last species: past it, a variant form
};

struct pd_game;
int pd_gb2_dex_species(const struct pd_game* g, int dex);
int pd_gb2_dex_number(const struct pd_game* g, int species);
bool pd_gb2_dex_entry(const struct pd_game* g, int dex, struct pd_dex_entry* out);
bool pd_gb2_dex_sprite(const struct pd_game* g, int species, uint32_t* out);
// The cart's Gen 2 dex tables (its pd_games_gen.c entry's), NULL where there are none.
const struct pd_gb_dex* pd_gb2_dex(const struct pd_game* g);
// The cart's own art where the core's tables don't cover it (its entry's gbArt): Gen 2 Polished / Prism / Crystal Clear,
// PureRGB, the European Yellows. A town map page (160x144, 0xRRGGBB) and the player's 16x16 head (clear where it's clear).
bool pd_gb_art_town_map(const struct pd_game* g, int region, uint32_t* out, int stride);
bool pd_gb_art_player(const struct pd_game* g, int gender, uint32_t* out, uint32_t clear);
int pd_gb_art_maps(const struct pd_game* g); // its pages, 0 = no town map in the art
// How many internal species ids its internalToDex lists.
int pd_gb_internal_count(const struct pd_gb_config* gb);
// pret's LZ (Gen2Lz); [polished]: its variant's counts. Stops once [max] bytes are out, returns how many are.
int pd_gb_lz(const uint8_t* rom, size_t romSize, uint32_t start, uint8_t* out, int max, bool polished);
struct pd_gb_polished_learn {
    const char* sha1;
    uint32_t base, learnPointers, learnData;
};
struct pd_level_move;
int pd_polished_level_moves(const struct pd_game*, int, struct pd_level_move*, int capacity);
int pd_polished_growth(const struct pd_game*, int species); // normalized to the shared EXP table

struct pd_config;
void pd_gb_snapshot_read(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx);

// The battle's controls (the app's BattleInputController.gen1 / GbPartySwitch): which battle menu waits and its cursor
// (FIGHT 0, PkMn 1, ITEM 2, RUN 3 / the move's index); a waiting battle party menu, the list (cursor = the slot, or
// PD_GB_PARTY_CANCEL) or SWITCH / STATS / CANCEL over it; the party's keys (a pd_mon's personality) and HP; the slot out.
#define PD_GB_PARTY_CANCEL 7
struct pd_gb_party_menu {
    bool open, submenu, forced;
    int cursor;
};
struct pd_gb_party_mon {
    uint32_t key;
    int hp;
};
enum pd_battle_state pd_gb_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor);
struct pd_gb_party_menu pd_gb_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx);
int pd_gb_party(const struct pd_gb_config* c, pd_read_fn read, void* ctx, struct pd_gb_party_mon* out);
int pd_gb_out_slot(const struct pd_gb_config* c, pd_read_fn read, void* ctx);

#endif
