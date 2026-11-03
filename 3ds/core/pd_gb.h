// The Game Boy games: the app's Gen1Reader.kt, Gen2Reader.kt and GbPartySwitch.kt in C. Their configs come from the
// app's own (pd_games_gen.c). Addresses are the Game Boy's bus; 0x1Dxxx is D000-DFFF in WRAM bank 1 whatever the game
// has switched in at D000 (wramx), and the host's read resolves that.
#ifndef PD_GB_H
#define PD_GB_H

#include <stdbool.h>
#include <stdint.h>

#include "pd_game.h"
#include "pd_snapshot.h"

struct pd_gb_landmark {
    uint16_t map; // group * 256 + number
    uint16_t landmark;
};

// Where the cart keeps its art, as file offsets (Gen1ArtTables / Gen2ArtTables). 0 = not on this cart.
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

// GbField: the cart's field actions (host/pd_gb_field.c). Crystal's addresses live there.
struct pd_gb_sig {
    uint32_t off;
    const char* hex;
};

struct pd_gb_field {
    bool crystal;
    uint16_t loopAfterWait, bankswitch, pcRoutine, wildDataPointers;
    uint32_t walkCounter, flags5, inBattle, badges, tileset, townsVisited, destination, flags6, partyCount, partyMoves, curMap;
    uint32_t grassRate, waterRate;
    int32_t pikachuFlags, pikachuImage;
    uint8_t pcBank, wildBank;
    const struct pd_gb_sig* sigs;
    int sigCount;
};

struct pd_gb_config {
    uint8_t gen; // 1 or 2
    const struct pd_gb_art* art;
    const struct pd_gb_field* field;
    uint32_t partyCount, partyMons, money, battleMode, battleMon, enemyMon, otPartyCount, otPartyMons, otActive;
    uint32_t tileMap, moveMenuType, gameTime, outSlot, dexCaught, dexSeen;

    // Gen 1.
    uint32_t bagCount, curMap, yCoord, xCoord, topMenuItemY, partyMenuType;
    uint8_t battleMenuLeftX, battleMenuRightX, bagSlots, battlePartyMenu;
    const uint16_t* internalToDex;
    int internalCount;

    // Gen 2.
    uint32_t numItems, numKeyItems, numBalls, tmsHms, mapGroup, coords, backupMapGroup, playerGender;
    uint32_t menu2D, menuCursor, partyMenuAction, numMedicine, numBerries;
    const struct pd_gb_landmark* landmarks;
    int landmarkCount;
    const uint8_t* genderRatios;
    int genderCount;
    const uint16_t* tmItems;
    int tmCount;
    int speciesCount, itemsMax, ballsMax, keyItemsMax, medicineMax, berriesMax;
    bool tmBits, polished;
    uint8_t cursorTile;
    const uint32_t* forms; // Polished Crystal's polishedVariantForms
    int formCount;

    const char* const* chars; // the nickname charset, by byte
};

// A waiting battle party menu: the list (cursor = the slot, or PD_GB_PARTY_CANCEL) or SWITCH / STATS / CANCEL over it.
#define PD_GB_PARTY_CANCEL 7
struct pd_gb_party_menu {
    bool open, submenu, forced;
    int cursor;
};

struct pd_gb_party_mon {
    uint32_t key;
    int hp;
};

// A blank Gen 3 config: a Game Boy game's g->cfg, so what asks "is this game known?" says yes and every address is 0.
extern const struct pd_config pd_gb_blank_config;

void pd_gb_snapshot_read(struct pd_snapshot* s, const struct pd_game* g, pd_read_fn read, void* ctx);
// Which battle menu waits, and its cursor (FIGHT 0, PkMn 1, ITEM 2, RUN 3 / the move's index).
enum pd_battle_state pd_gb_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor);
struct pd_gb_party_menu pd_gb_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx);
int pd_gb_party(const struct pd_gb_config* c, pd_read_fn read, void* ctx, struct pd_gb_party_mon* out);
int pd_gb_out_slot(const struct pd_gb_config* c, pd_read_fn read, void* ctx);
// The cart's town map [region] (160 x 144, 0xRRGGBB) and the player's 16 x 16 sprite (CLEAR = [clear]): false where it isn't known.
bool pd_gb_town_map(const struct pd_game* g, int region, uint32_t* out);
bool pd_gb_player(const struct pd_game* g, int gender, uint32_t* out, uint32_t clear);
// A species' party icon from the cart, its first frame doubled into [out] (32 x 32, 0xRRGGBB, CLEAR = [clear]).
bool pd_gb_icon(const struct pd_game* g, int species, uint32_t* out, uint32_t clear);

#endif
