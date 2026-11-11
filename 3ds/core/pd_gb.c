#include "pd_gb.h"

#include "pd_dex.h"
#include "pd_font.h"

#include <string.h>

#include "pd_game.h"
#include "pd_games.h"
#include "pd_gb_world.h"
#include "pd_guide.h"
#include "pd_tables.h"

// Gen 1 / Gen 2 structs are plaintext and big-endian (Gen1Reader.kt, Gen2Reader.kt).
#define G1_MON 44
#define G2_MON 48
#define G1_BATTLE 29
#define G2_BATTLE 32
#define POLISHED_BATTLE 35
#define NAME_LEN 11
#define G2_EGG 0xFD
#define G2_TMS 57

extern const struct pd_gb_team pd_gb_teams_crystal[], pd_gb_teams_crystal_legacy[];
extern const uint16_t pd_gb_internal_to_dex_yellow[], pd_gb_internal_to_dex_pure_rgb[], pd_gb_internal_to_dex_yellow_legacy[], pd_gb_gender_crystal[],
    pd_gb_gender_prism[], pd_gb_gender_polished[], pd_gb_tm_crystal[], pd_gb_tm_prism[], pd_gb_tm_polished[];
extern const int pd_gb_internal_to_dex_yellow_count, pd_gb_internal_to_dex_pure_rgb_count, pd_gb_internal_to_dex_yellow_legacy_count, pd_gb_gender_crystal_count,
    pd_gb_gender_prism_count, pd_gb_gender_polished_count, pd_gb_tm_crystal_count, pd_gb_tm_prism_count,
    pd_gb_tm_polished_count;

#define POLISHED_NUM_SPECIES 0x123

const struct pd_gb_config pd_gb_yellow = {
    .gen = 1, .partyCount = 0xD162, .partyMons = 0xD16A, .numItems = 0xD31C, .money = 0xD346, .battleMode = 0xD056,
    .battleMon = 0xD013, .enemyMon = 0xCFE4, .otPartyCount = 0xD89B, .otPartyMons = 0xD8A3, .curOTMon = 0xCFE7,
    .internalToDex = pd_gb_internal_to_dex_yellow, .bagSlots = 20,
    .dexCaught = 0xD2F6, .dexSeen = 0xD309, .dexCount = 151,
    .guideRam = &(const struct pd_gb_guide_ram){ 0xD746, 0xD5A5, 0xD6EF, 0xD736, 0xD355, 0xD714, 0xD35D }, .teams = pd_gb_teams_yellow,
    .tileMap = 0xC3A0, .moveMenuType = 0xCCDB, .outSlot = 0xCC2F, .topMenuItemY = 0xCC24, .battleMenuLeftX = 9, .battleMenuRightX = 15, .battlePartyMenu = 2, .partyMenuType = 0xD07C,
};
// The German, French, Italian and Spanish Yellows (Gen1Reader.kt's GEN1_YELLOW_EUROPE): English's code with their own
// text, everything from wBattleMon up 5 bytes later (the menus and tile map didn't move); the GUIDE's RAM shifted with it.
#define PD_GB_YELLOW_EUROPE \
    .gen = 1, .partyCount = 0xD167, .partyMons = 0xD16F, .numItems = 0xD321, .money = 0xD34B, .battleMode = 0xD05B, \
    .battleMon = 0xD018, .enemyMon = 0xCFE9, .otPartyCount = 0xD8A0, .otPartyMons = 0xD8A8, .curOTMon = 0xCFEC, \
    .internalToDex = pd_gb_internal_to_dex_yellow, .bagSlots = 20, \
    .dexCaught = 0xD2FB, .dexSeen = 0xD30E, .dexCount = 151, \
    .guideRam = &(const struct pd_gb_guide_ram){ 0xD74B, 0xD5AA, 0xD6F4, 0xD73B, 0xD35A, 0xD719, 0xD362 }, .teams = pd_gb_teams_yellow, \
    .tileMap = 0xC3A0, .moveMenuType = 0xCCDB, .outSlot = 0xCC2F, .topMenuItemY = 0xCC24, .battlePartyMenu = 2, .partyMenuType = 0xD081,
const struct pd_gb_config pd_gb_yellow_de = { PD_GB_YELLOW_EUROPE .battleMenuLeftX = 7, .battleMenuRightX = 12 };
const struct pd_gb_config pd_gb_yellow_fr = { PD_GB_YELLOW_EUROPE .battleMenuLeftX = 7, .battleMenuRightX = 13 };
const struct pd_gb_config pd_gb_yellow_it = { PD_GB_YELLOW_EUROPE .battleMenuLeftX = 7, .battleMenuRightX = 14 };
const struct pd_gb_config pd_gb_yellow_es = { PD_GB_YELLOW_EUROPE .battleMenuLeftX = 9, .battleMenuRightX = 15 };
// Yellow Legacy (GEN1_YELLOW_LEGACY): Yellow's but the bag (59 slots) pushed money and the battle mode on; its own
// internal species order. Its GUIDE RAM is GuideGen1Gen.kt's G1L_* (HERE, NEXT BOSS, the map's place).
const struct pd_gb_config pd_gb_yellow_legacy = {
    .gen = 1, .partyCount = 0xD162, .partyMons = 0xD16A, .numItems = 0xD31C, .money = 0xD394, .battleMode = 0xD057,
    .battleMon = 0xD013, .enemyMon = 0xCFE4, .otPartyCount = 0xD89B, .otPartyMons = 0xD8A3, .curOTMon = 0xCFE7,
    .internalToDex = pd_gb_internal_to_dex_yellow_legacy, .bagSlots = 59,
    .dexCaught = 0xD2F6, .dexSeen = 0xD309, .dexCount = 151,
    .guideRam = &(const struct pd_gb_guide_ram){ 0xD746, 0xD5F3, 0xD6EF, 0xD736, 0xD3A3, 0xD714, 0xD3AB }, .teams = pd_gb_teams_yellow_legacy,
    .tileMap = 0xC3A0, .moveMenuType = 0xCCDB, .outSlot = 0xCC2F, .topMenuItemY = 0xCC24, .battleMenuLeftX = 9, .battleMenuRightX = 15, .battlePartyMenu = 2, .partyMenuType = 0xD07C,
};
#define PD_GB_RED_BASE \
    .gen = 1, .partyCount = 0xD163, .partyMons = 0xD16B, .numItems = 0xD31D, .money = 0xD347, .battleMode = 0xD057, \
    .battleMon = 0xD014, .enemyMon = 0xCFE5, .otPartyCount = 0xD89C, .otPartyMons = 0xD8A4, .curOTMon = 0xCFE8, \
    .internalToDex = pd_gb_internal_to_dex_yellow, .bagSlots = 20, \
    .dexCaught = 0xD2F7, .dexSeen = 0xD30A, .dexCount = 151, \
    .guideRam = &(const struct pd_gb_guide_ram){ 0xD747, 0xD5A6, 0xD6F0, 0xD737, 0xD356, 0xD715, 0xD35E }, \
    .tileMap = 0xC3A0, .moveMenuType = 0xCCDB, .outSlot = 0xCC2F, .topMenuItemY = 0xCC24, .battleMenuLeftX = 9, .battleMenuRightX = 15, .battlePartyMenu = 2, .partyMenuType = 0xD07D,
const struct pd_gb_config pd_gb_red = {
    PD_GB_RED_BASE
    .teams = pd_gb_teams_red,
};
const struct pd_gb_config pd_gb_blue = {
    PD_GB_RED_BASE
    .teams = pd_gb_teams_blue,
};
const struct pd_gb_config pd_gb_pure_red = {
    .gen = 1, .partyCount = PD_WRAMX(1, 0xD16B), .partyMons = PD_WRAMX(1, 0xD173), .numItems = PD_WRAMX(1, 0xD542),
    .money = PD_WRAMX(1, 0xD34F), .battleMode = PD_WRAMX(1, 0xD057), .battleMon = PD_WRAMX(1, 0xD014),
    .enemyMon = 0xCFE3, .otPartyCount = PD_WRAMX(1, 0xD8A4), .otPartyMons = PD_WRAMX(1, 0xD8AC), .curOTMon = 0xCFE6,
    .internalToDex = pd_gb_internal_to_dex_pure_rgb, .bagSlots = 30,
    .dexCaught = PD_WRAMX(1, 0xD2FF), .dexSeen = PD_WRAMX(1, 0xD312), .dexCount = 151,
    .tileMap = 0xC3A0, .moveMenuType = 0xCCDB, .outSlot = 0xCC2F, .topMenuItemY = 0xCC24, .battleMenuLeftX = 9, .battleMenuRightX = 15,
    .partyMenuType = PD_WRAMX(1, 0xD07D), .battlePartyMenu = 1,
};
#define PD_GB_CRYSTAL_BASE \
    .gen = 2, .partyCount = PD_WRAMX(1, 0xDCD7), .partyMons = PD_WRAMX(1, 0xDCDF), .numItems = PD_WRAMX(1, 0xD892), \
    .numKeyItems = PD_WRAMX(1, 0xD8BC), .numBalls = PD_WRAMX(1, 0xD8D7), .tmsHms = PD_WRAMX(1, 0xD859), \
    .money = PD_WRAMX(1, 0xD84E), .battleMode = PD_WRAMX(1, 0xD22D), .battleMon = 0xC62C, \
    .enemyMon = PD_WRAMX(1, 0xD206), .otPartyCount = PD_WRAMX(1, 0xD280), .otPartyMons = PD_WRAMX(1, 0xD288), \
    .curOTMon = 0xC663, .genderRatios = pd_gb_gender_crystal, .tmItems = pd_gb_tm_crystal, .speciesCount = 251, \
    .itemsMax = 20, .ballsMax = 12, .keyItemsMax = 25, \
    .tileMap = 0xC4A0, .moveMenuType = PD_WRAMX(1, 0xD235), .outSlot = PD_WRAMX(1, 0xD0D4), .menu2D = 0xCFA1, .menuCursor = 0xCFA9, \
    .cursorTile = 0xED, .partyMenuAction = PD_WRAMX(1, 0xD141),
const struct pd_gb_config pd_gb_crystal = {
    PD_GB_CRYSTAL_BASE
    .dexCaught = PD_WRAMX(1, 0xDE99), .dexSeen = PD_WRAMX(1, 0xDEB9), .dexCount = 251,
    .guide2Ram = &(const struct pd_gb_guide2_ram){ PD_WRAMX(1, 0xDA72), PD_WRAMX(1, 0xD269), PD_WRAMX(1, 0xD857),
                                                   PD_WRAMX(1, 0xD960), PD_WRAMX(1, 0xDCB5) },
    .teams = pd_gb_teams_crystal,
    .mapGroup = PD_WRAMX(1, 0xDCB5), .backupMapGroup = PD_WRAMX(1, 0xDCAD), .playerGender = PD_WRAMX(1, 0xD472),
    .landmarks = pd_gb_landmarks_crystal, .landmarkCount = &pd_gb_landmarks_crystal_count,
};
// Crystal Clear v2.6.0: Crystal's RAM with its own wPokedexCaught; the app keeps Crystal's wPokedexSeen (unverified on a save).
const struct pd_gb_config pd_gb_crystal_clear = {
    PD_GB_CRYSTAL_BASE
    .dexCaught = PD_WRAMX(1, 0xDA0E), .dexSeen = PD_WRAMX(1, 0xDEB9), .dexCount = 251,
};
// Hacks whose RAM is Blue's / Crystal's, moved: found by 3ds/tools/gb_addr_map.py (votes of 20-170 code references per
// address, a constant shift per block). Unverified on a save: a wrong address reads as an empty party or bag, not garbage.
// Yume Blue: party +0xEA, battle +0xEC, money / badges +0x1A4, Dex flags +0xEA; no guide (its bosses and flags differ).
const struct pd_gb_config pd_gb_yume_blue = {
    .gen = 1, .partyCount = 0xD24D, .partyMons = 0xD255, .numItems = 0xD407, .money = 0xD4EB, .battleMode = 0xD143,
    .battleMon = 0xD100, .enemyMon = 0xD0D1, .otPartyCount = 0xD8C6, .otPartyMons = 0xD8CE, .curOTMon = 0xD0D4,
    .internalToDex = pd_gb_internal_to_dex_yellow, .bagSlots = 30,
    .dexCaught = 0xD3E1, .dexSeen = 0xD3F4, .dexCount = 151,
};
// Crystal Legacy 1.3.1: party / battle / items +0xB, money +0xB, its bag pockets elsewhere (key items D947, balls D9CC).
const struct pd_gb_config pd_gb_crystal_legacy = {
    .gen = 2, .partyCount = PD_WRAMX(1, 0xDCE2), .partyMons = PD_WRAMX(1, 0xDCEA), .numItems = PD_WRAMX(1, 0xD89D),
    .numKeyItems = PD_WRAMX(1, 0xD947), .numBalls = PD_WRAMX(1, 0xD9CC), .tmsHms = PD_WRAMX(1, 0xD864),
    .money = PD_WRAMX(1, 0xD859), .battleMode = PD_WRAMX(1, 0xD238), .battleMon = 0xC62C,
    .enemyMon = PD_WRAMX(1, 0xD211), .otPartyCount = PD_WRAMX(1, 0xD28B), .otPartyMons = PD_WRAMX(1, 0xD293),
    .curOTMon = 0xC663, .genderRatios = pd_gb_gender_crystal, .tmItems = pd_gb_tm_crystal, .speciesCount = 251,
    .itemsMax = 80, .ballsMax = 25, .keyItemsMax = 50,
    .dexCaught = PD_WRAMX(1, 0xDEA4), .dexSeen = PD_WRAMX(1, 0xDEC4), .dexCount = 251,
    // Its GUIDE (GuideCrystalLegacyGen.kt's CL_*: event flags, clock, badges, trades) and place (GEN2_CRYSTAL_LEGACY), on
    // Crystal's map.
    .guide2Ram = &(const struct pd_gb_guide2_ram){ PD_WRAMX(1, 0xDA7D), PD_WRAMX(1, 0xD274), PD_WRAMX(1, 0xD862),
                                                   PD_WRAMX(1, 0xD96B), PD_WRAMX(1, 0xDCC0) },
    .teams = pd_gb_teams_crystal_legacy,
    .mapGroup = PD_WRAMX(1, 0xDCC0), .backupMapGroup = PD_WRAMX(1, 0xDCB8), .playerGender = PD_WRAMX(1, 0xD47D),
    .landmarks = pd_gb_landmarks_crystal, .landmarkCount = &pd_gb_landmarks_crystal_count,
};
// Ambrosia 1.9.0: party / battle +0xB; bag pockets of 40 items and 30 key items (D884, D8D6, D8F6), TMs D84B, money D840.
const struct pd_gb_config pd_gb_ambrosia = {
    .gen = 2, .partyCount = PD_WRAMX(1, 0xDCE2), .partyMons = PD_WRAMX(1, 0xDCEA), .numItems = PD_WRAMX(1, 0xD884),
    .numKeyItems = PD_WRAMX(1, 0xD8D6), .numBalls = PD_WRAMX(1, 0xD8F6), .tmsHms = PD_WRAMX(1, 0xD84B),
    .money = PD_WRAMX(1, 0xD840), .battleMode = PD_WRAMX(1, 0xD238), .battleMon = 0xC62C,
    .enemyMon = PD_WRAMX(1, 0xD211), .otPartyCount = PD_WRAMX(1, 0xD28B), .otPartyMons = PD_WRAMX(1, 0xD293),
    .curOTMon = 0xC663, .genderRatios = pd_gb_gender_crystal, .tmItems = pd_gb_tm_crystal, .speciesCount = 251,
    .itemsMax = 40, .ballsMax = 25, .keyItemsMax = 30,
    .dexCaught = PD_WRAMX(1, 0xDEA4), .dexSeen = PD_WRAMX(1, 0xDEC4), .dexCount = 251,
};
const struct pd_gb_config pd_gb_gold = {
    .gen = 2, .partyCount = PD_WRAMX(1, 0xDA22), .partyMons = PD_WRAMX(1, 0xDA2A), .numItems = PD_WRAMX(1, 0xD5B7),
    .numKeyItems = PD_WRAMX(1, 0xD5E1), .numBalls = PD_WRAMX(1, 0xD5FC), .tmsHms = PD_WRAMX(1, 0xD57E),
    .money = PD_WRAMX(1, 0xD573), .battleMode = PD_WRAMX(1, 0xD116), .battleMon = 0xCB0C,
    .enemyMon = PD_WRAMX(1, 0xD0EF), .otPartyCount = PD_WRAMX(1, 0xDD55), .otPartyMons = PD_WRAMX(1, 0xDD5D),
    .curOTMon = 0xCB41, .genderRatios = pd_gb_gender_crystal, .tmItems = pd_gb_tm_crystal, .speciesCount = 251,
    .itemsMax = 20, .ballsMax = 12, .keyItemsMax = 25,
    .dexCaught = PD_WRAMX(1, 0xDBE4), .dexSeen = PD_WRAMX(1, 0xDC04), .dexCount = 251,
    .mapGroup = PD_WRAMX(1, 0xDA00), .backupMapGroup = PD_WRAMX(1, 0xD9F6),
    .landmarks = pd_gb_landmarks_gold_silver, .landmarkCount = &pd_gb_landmarks_gold_silver_count,
    .tileMap = 0xC3A0, .moveMenuType = PD_WRAMX(1, 0xD11F), .outSlot = 0xCFC6, .menu2D = 0xCED8, .menuCursor = 0xCEE0,
    .cursorTile = 0xED, .partyMenuAction = PD_WRAMX(1, 0xD03E),
};
// Prism: Crystal's RAM but the bag, and its own 254 species ids.
const struct pd_gb_config pd_gb_prism = {
    .gen = 2, .partyCount = PD_WRAMX(1, 0xDCD7), .partyMons = PD_WRAMX(1, 0xDCDF), .numItems = PD_WRAMX(1, 0xD866),
    .numKeyItems = PD_WRAMX(1, 0xD8B8), .numBalls = PD_WRAMX(1, 0xD8EC), .tmsHms = PD_WRAMX(1, 0xD859),
    .money = PD_WRAMX(1, 0xD84E), .battleMode = PD_WRAMX(1, 0xD22D), .battleMon = 0xC62C,
    .enemyMon = PD_WRAMX(1, 0xD206), .otPartyCount = PD_WRAMX(1, 0xD280), .otPartyMons = PD_WRAMX(1, 0xD288),
    .curOTMon = 0xC663, .genderRatios = pd_gb_gender_prism, .tmItems = pd_gb_tm_prism, .speciesCount = 254,
    .itemsMax = 40, .ballsMax = 25, .keyItemsMax = 50, .tmBits = true,
    .tileMap = 0xC4A0, .moveMenuType = PD_WRAMX(1, 0xD235), .outSlot = PD_WRAMX(1, 0xD0D4), .menu2D = 0xCFA1, .menuCursor = 0xCFA9,
    .cursorTile = 0xED, .partyMenuAction = PD_WRAMX(1, 0xD141),
};
const struct pd_gb_config pd_gb_polished = {
    .gen = 2, .partyCount = PD_WRAMX(1, 0xDCCE), .partyMons = PD_WRAMX(1, 0xDCD6), .numItems = PD_WRAMX(1, 0xD827),
    .numKeyItems = PD_WRAMX(1, 0xD800), .numBalls = PD_WRAMX(1, 0xD90B), .tmsHms = PD_WRAMX(1, 0xD7F5),
    .money = PD_WRAMX(1, 0xD7E5), .battleMode = PD_WRAMX(1, 0xD233), .battleMon = 0xC4A5,
    .enemyMon = PD_WRAMX(1, 0xD209), .otPartyCount = PD_WRAMX(1, 0xD283), .otPartyMons = PD_WRAMX(1, 0xD28B),
    .curOTMon = 0xC4DD, .genderRatios = pd_gb_gender_polished, .tmItems = pd_gb_tm_polished,
    .speciesCount = POLISHED_NUM_SPECIES, .itemsMax = 75, .ballsMax = 25, .keyItemsMax = 38, .tmBits = true,
    .polished = true, .numMedicine = PD_WRAMX(1, 0xD8BF), .numBerries = PD_WRAMX(1, 0xD93F), .medicineMax = 37,
    .berriesMax = 31,
    .dexCaught = PD_WRAMX(1, 0xDE7B), .dexSeen = PD_WRAMX(1, 0xDEAD), .dexCount = POLISHED_NUM_SPECIES,
    .tileMap = 0xC1A0, .moveMenuType = PD_WRAMX(1, 0xD239), .outSlot = PD_WRAMX(1, 0xD0DA), .menu2D = 0xCE7C, .menuCursor = 0xCE84,
    .cursorTile = 0xF0, .partyMenuAction = PD_WRAMX(1, 0xD113),
    .mapGroup = PD_WRAMX(1, 0xDCAC), .backupMapGroup = PD_WRAMX(1, 0xDCA7), .playerGender = PD_WRAMX(1, 0xD47A),
    .landmarks = pd_gb_landmarks_polished, .landmarkCount = &pd_gb_landmarks_polished_count,
};

static int be16(const uint8_t* b) {
    return (b[0] << 8) | b[1];
}

// The cart's text (constants/charmap.asm) for the letters, digits and signs the companion prints, one table per
// charmap as the app has them: Gen1Charset.EN, Gen2Art.CHARS and Gen2Art.POLISHED_CHARS. Polished Crystal ends
// names with 0x53 / 0x52 too.
enum { MAP_GEN1, MAP_GEN2, MAP_POLISHED, MAPS };

static const char* gb_map[MAPS][256];
static char gb_letters[MAPS][52 + 10][2];

static void gb_map_init(void) {
    static bool done;
    if (done) return;
    done = true;
    for (int m = 0; m < MAPS; m++) {
        for (int i = 0; i < 26; i++) {
            gb_letters[m][i][0] = (char) ('A' + i);
            gb_letters[m][26 + i][0] = (char) ('a' + i);
            gb_map[m][0x80 + i] = gb_letters[m][i];
            gb_map[m][0xA0 + i] = gb_letters[m][26 + i];
        }
        for (int i = 0; i < 10; i++) {
            gb_letters[m][52 + i][0] = (char) ('0' + i);
            gb_map[m][(m == MAP_POLISHED ? 0xE0 : 0xF6) + i] = gb_letters[m][52 + i];
        }
    }
    const char** g1 = gb_map[MAP_GEN1];
    g1[0x7F] = " "; g1[0x4E] = " "; g1[0x4F] = " "; g1[0x49] = " "; g1[0x51] = " ";
    g1[0x9A] = "("; g1[0x9B] = ")"; g1[0x9C] = ":"; g1[0x9D] = ";"; g1[0x9E] = "["; g1[0x9F] = "]";
    g1[0xBA] = "é"; g1[0xBB] = "'d"; g1[0xBC] = "'l"; g1[0xBD] = "'s"; g1[0xBE] = "'t"; g1[0xBF] = "'v";
    g1[0xE0] = "'"; g1[0xE3] = "-"; g1[0xE4] = "'r"; g1[0xE5] = "'m"; g1[0xE6] = "?"; g1[0xE7] = "!"; g1[0xE8] = ".";
    g1[0xEC] = "▷"; g1[0xED] = "▶"; g1[0xEE] = "▼"; g1[0xEF] = "♂"; g1[0xF0] = "¥"; g1[0xF1] = "×"; g1[0xF2] = ".";
    g1[0xF3] = "/"; g1[0xF4] = ","; g1[0xF5] = "♀"; g1[0x54] = "POKé"; g1[0x75] = "…";

    const char** g2 = gb_map[MAP_GEN2];
    g2[0x7F] = " ";
    g2[0x9A] = "("; g2[0x9B] = ")"; g2[0x9C] = ":"; g2[0x9D] = ";"; g2[0x9E] = "["; g2[0x9F] = "]";
    g2[0xC0] = "Ä"; g2[0xC1] = "Ö"; g2[0xC2] = "Ü"; g2[0xC3] = "ä"; g2[0xC4] = "ö"; g2[0xC5] = "ü";
    g2[0xE0] = "'"; g2[0xE3] = "-"; g2[0xE6] = "?"; g2[0xE7] = "!"; g2[0xE8] = "."; g2[0xE9] = "&"; g2[0xEA] = "é";
    g2[0xEB] = "→"; g2[0xEC] = "▷"; g2[0xED] = "▶"; g2[0xEE] = "▼"; g2[0xEF] = "♂"; g2[0xF0] = "¥"; g2[0xF1] = "×";
    g2[0xF3] = "/"; g2[0xF4] = ","; g2[0xF5] = "♀";

    const char** pc = gb_map[MAP_POLISHED];
    pc[0x7F] = " ";
    pc[0x9A] = "("; pc[0x9B] = ")"; pc[0x9C] = "."; pc[0x9D] = ","; pc[0x9E] = "?"; pc[0x9F] = "!";
    pc[0xBA] = "“"; pc[0xBB] = "”"; pc[0xBC] = "-"; pc[0xBD] = ":"; pc[0xBE] = "♂"; pc[0xBF] = "♀"; pc[0xC0] = "'";
    pc[0xC8] = "é"; pc[0xC9] = "É"; pc[0xCA] = "á"; pc[0xCB] = "ê"; pc[0xCC] = "í"; pc[0xCD] = "ó"; pc[0xCE] = "¿"; pc[0xCF] = "¡";
    pc[0xD5] = "№"; pc[0xD8] = "&"; pc[0xD9] = "♪"; pc[0xDA] = "♥"; pc[0xDB] = "×"; pc[0xDC] = "/"; pc[0xDD] = "%";
    pc[0xDE] = "+"; pc[0xEA] = "¥"; pc[0xEB] = "…"; pc[0xEC] = "★"; pc[0xED] = "▼"; pc[0xEE] = "▲"; pc[0xEF] = "◀";
    pc[0xF0] = "▶";
}

static void gb_name(const uint8_t* src, int gen, bool polished, char* out, size_t outLen) {
    gb_map_init();
    const char** map = gb_map[polished ? MAP_POLISHED : gen == 1 ? MAP_GEN1 : MAP_GEN2];
    size_t n = 0;
    for (int i = 0; i < NAME_LEN; i++) {
        int c = src[i];
        if (c == 0x00 || c == 0x50 || (polished && (c == 0x52 || c == 0x53))) break;
        const char* s = map[c];
        if (!s) continue;
        size_t l = strlen(s);
        if (n + l >= outLen) break;
        memcpy(out + n, s, l);
        n += l;
    }
    out[n] = 0;
    // Trailing spaces (the app trims both ends).
    while (n && out[n - 1] == ' ') out[--n] = 0;
    size_t lead = 0;
    while (out[lead] == ' ') lead++;
    if (lead) memmove(out, out + lead, n - lead + 1);
}

static bool rd(pd_read_fn read, void* ctx, uint32_t a, void* out, size_t n) {
    return read(ctx, a, out, n);
}

// Hidden Power from Gen 2's DVs (retail Gen 3's type numbering: Fighting 1 ... Dark 17, no 9).
static void gen2_hidden_power(struct pd_mon_stats* st, int atk, int def, int spe, int spc) {
    int k = (atk & 3) * 4 + (def & 3); // 0..15: Fighting ... Dark
    st->hiddenPowerType = k < 8 ? k + 1 : k + 2;
    int v = ((atk >> 3) << 3) | ((def >> 3) << 2) | ((spe >> 3) << 1) | (spc >> 3);
    st->hiddenPowerPower = (5 * v + (spc & 3)) / 2 + 31;
    st->hasHiddenPower = true;
}

static void gb_stats(struct pd_mon* m, const uint8_t* raw, const struct pd_gb_config* g) {
    if (g->polished) {
        // v3.2.3 macros/ram.asm: six EV bytes, six packed DVs, then nature in the personality byte.
        struct pd_mon_stats* st = &m->stats;
        for (int i = 0; i < 6; i++) {
            st->evs[i] = raw[11 + i];
            st->ivs[i] = (raw[17 + i / 2] >> (i % 2 ? 0 : 4)) & 15;
            st->values[i] = be16(raw + 36 + 2 * i);
        }
        st->nature = raw[20] & 31;
        st->valid = st->hasIvs = st->hasEvs = true;
        st->hasNature = st->nature < 25;
        return;
    }
    int atk, def, spe, spc, base;
    const uint8_t* dv;
    if (g->gen == 1) {
        dv = raw + 27;
        base = 34;
    } else {
        dv = raw + 21;
        base = 36;
    }
    atk = dv[0] >> 4;
    def = dv[0] & 15;
    spe = dv[1] >> 4;
    spc = dv[1] & 15;
    int hp = ((atk & 1) << 3) | ((def & 1) << 2) | ((spe & 1) << 1) | (spc & 1);
    struct pd_mon_stats* st = &m->stats;
    int ivs[6] = {hp, atk, def, spe, spc, spc};
    for (int i = 0; i < 6; i++) st->ivs[i] = (uint8_t) ivs[i];
    st->values[0] = be16(raw + base);
    st->values[1] = be16(raw + base + 2);
    st->values[2] = be16(raw + base + 4);
    st->values[3] = be16(raw + base + 6);
    st->values[4] = be16(raw + base + 8);
    st->values[5] = g->gen == 1 ? st->values[4] : be16(raw + base + 10);
    st->valid = st->hasIvs = true;
    if (g->gen == 2) gen2_hidden_power(st, atk, def, spe, spc);
}

static int polished_species(int species, int form) {
    int s9 = species | ((form & 0x20) ? 0x100 : 0);
    int key = (s9 << 8) | (form & 0x1F);
    for (int k = 0; k < pd_gb_polished_variants_count; k++)
        if ((int) pd_gb_polished_variants[k] == key) return POLISHED_NUM_SPECIES + 1 + k;
    return s9;
}

static int gen2_gender(const struct pd_gb_config* g, int species, const uint8_t* dv, bool formBit, bool polished) {
    if (species < 0 || species >= g->genderCount) return PD_GENDER_NONE;
    int r = g->genderRatios[species];
    if (r == 255) return PD_GENDER_NONE;
    if (r == 0) return PD_GENDER_MALE;
    if (r == 254) return PD_GENDER_FEMALE;
    if (polished) return formBit ? PD_GENDER_FEMALE : PD_GENDER_MALE;
    return r < ((dv[0] & 0xF0) | (dv[1] >> 4)) ? PD_GENDER_MALE : PD_GENDER_FEMALE;
}

// A wrong guess at a game's addresses reads bytes that are not a monster: a hit-point count above its maximum, or a
// maximum no Game Boy monster has (999 at most), shows nothing rather than garbage.
static bool hp_sane(int hp, int maxHp) { return maxHp >= 1 && maxHp <= 999 && hp <= maxHp; }

static bool decode_mon(const struct pd_gb_config* g, const uint8_t* raw, bool egg, struct pd_mon* m) {
    memset(m, 0, sizeof *m);
    if (g->gen == 1) {
        int internal = raw[0];
        int dex = internal < g->internalCount ? g->internalToDex[internal] : 0;
        int level = raw[33];
        if (!dex || level < 1 || level > 100) return false;
        m->species = dex;
        m->level = level;
        m->hp = be16(raw + 1);
        m->maxHp = be16(raw + 34);
        if (!hp_sane(m->hp, m->maxHp)) return false;
        m->status = raw[4];
        for (int i = 0; i < 4; i++) {
            m->moves[i] = raw[8 + i];
            m->pp[i] = raw[29 + i] & 0x3F;
        }
        m->experience = ((uint32_t) raw[14] << 16) | (raw[15] << 8) | raw[16];
        m->hasExperience = true;
        m->gender = PD_GENDER_NONE;
        m->personality = ((uint32_t) raw[12] << 24) | (raw[13] << 16) | (raw[27] << 8) | (raw[28] ^ (uint32_t) dex);
        gb_stats(m, raw, g);
        return true;
    }
    int species = raw[0];
    if (g->polished) {
        if (species == 0 || species == 0xFF) return false;
        int form = raw[21];
        egg = (form & 0x40) != 0;
        int id = polished_species(species, form);
        if (id < 1 || id >= g->genderCount) return false;
        species = id;
        m->gender = egg ? PD_GENDER_NONE : gen2_gender(g, id, raw + 17, (form & 0x80) != 0, true);
        for (int i = 0; i < 4; i++) m->pp[i] = raw[22 + i] & 0x3F;
        m->personality = ((uint32_t) raw[6] << 24) | (raw[7] << 16) | (raw[17] << 8) | raw[18];
    } else {
        if (species < 1 || species > g->speciesCount) return false;
        m->gender = egg ? PD_GENDER_NONE : gen2_gender(g, species, raw + 21, false, false);
        for (int i = 0; i < 4; i++) m->pp[i] = raw[23 + i] & 0x3F;
        m->personality = ((uint32_t) raw[6] << 24) | (raw[7] << 16) | (raw[21] << 8) | raw[22];
    }
    int level = raw[31];
    if (level < 1 || level > 100) return false;
    m->species = egg ? PD_SPECIES_EGG : species;
    m->level = level;
    m->hp = be16(raw + 34);
    m->maxHp = be16(raw + 36);
    if (!hp_sane(m->hp, m->maxHp)) return false;
    m->status = raw[32];
    for (int i = 0; i < 4; i++) m->moves[i] = raw[2 + i];
    m->experience = ((uint32_t) raw[8] << 16) | (raw[9] << 8) | raw[10];
    m->hasExperience = true;
    m->heldItem = raw[1];
    m->hasHeldItem = true;
    m->friendship = raw[g->polished ? 26 : 27];
    m->hasFriendship = !egg;
    if (!egg) gb_stats(m, raw, g);
    return true;
}

static void decode_battle(const struct pd_gb_config* g, const uint8_t* raw, struct pd_battle_mon* b) {
    memset(b, 0, sizeof *b);
    int species, level, t1, t2, hp, maxHp, status, moves, pp;
    if (g->gen == 1) {
        int internal = raw[0];
        species = internal < g->internalCount ? g->internalToDex[internal] : 0;
        level = raw[14];
        t1 = raw[5];
        t2 = raw[6];
        hp = be16(raw + 1);
        maxHp = be16(raw + 15);
        status = raw[4];
        moves = 8;
        pp = 25;
    } else if (g->polished) {
        if (raw[0] == 0 || raw[0] == 0xFF) return;
        species = polished_species(raw[0], raw[10]);
        if (species < 1 || species >= g->genderCount) return;
        level = raw[16];
        t1 = raw[33];
        t2 = raw[34];
        hp = be16(raw + 19);
        maxHp = be16(raw + 21);
        status = raw[17];
        moves = 2;
        pp = 11;
        b->heldItem = raw[1];
    } else {
        species = raw[0];
        if (species < 1 || species > g->speciesCount) return;
        level = raw[13];
        t1 = raw[30];
        t2 = raw[31];
        hp = be16(raw + 16);
        maxHp = be16(raw + 18);
        status = raw[14];
        moves = 2;
        pp = 8;
        b->heldItem = raw[1];
    }
    if (!species || level < 1 || level > 100 || !hp_sane(hp, maxHp)) return;
    b->species = species;
    b->level = level;
    b->type1 = t1;
    b->type2 = t2;
    b->hp = hp;
    b->maxHp = maxHp;
    b->status = (uint32_t) status;
    for (int i = 0; i < 4; i++) {
        b->moves[i] = raw[moves + i];
        b->pp[i] = raw[pp + i] & 0x3F;
    }
}

static void add_item(struct pd_snapshot* s, int pocket, int id, int qty) {
    struct pd_pocket_items* p = &s->bag[pocket];
    if (p->count >= PD_POCKET_SLOTS) return;
    p->items[p->count].id = (uint16_t) id;
    p->items[p->count].quantity = (uint16_t) qty;
    p->count++;
}

// A list of {item, quantity} pairs after a count, ending in 0xFF; false when it isn't well formed (WRAM before a save).
static bool read_pairs(pd_read_fn read, void* ctx, uint32_t addr, int max, int pocket, struct pd_snapshot* s) {
    uint8_t raw[2 + 2 * 80];
    if (max > 80) max = 80;
    if (!rd(read, ctx, addr, raw, (size_t) (2 + 2 * max))) return false;
    int n = raw[0];
    if (n > max || raw[1 + 2 * n] != 0xFF) return false;
    for (int i = 0; i < n; i++) add_item(s, pocket, raw[1 + 2 * i], raw[2 + 2 * i]);
    return true;
}

static bool read_gen2_bag(const struct pd_gb_config* g, struct pd_snapshot* s, pd_read_fn read, void* ctx) {
    if (!read_pairs(read, ctx, g->numItems, g->itemsMax, PD_POCKET_ITEMS, s)) return false;
    if (!read_pairs(read, ctx, g->numBalls, g->ballsMax, PD_POCKET_BALLS, s)) return false;
    if (g->polished) {
        if (!read_pairs(read, ctx, g->numMedicine, g->medicineMax, PD_POCKET_ITEMS, s)) return false;
        if (!read_pairs(read, ctx, g->numBerries, g->berriesMax, PD_POCKET_BERRIES, s)) return false;
        uint8_t key[40];
        if (!rd(read, ctx, g->numKeyItems, key, (size_t) g->keyItemsMax + 1)) return false;
        int n = 0;
        while (n <= g->keyItemsMax && key[n]) n++;
        if (n > g->keyItemsMax) return false;
        for (int i = 0; i < n; i++) add_item(s, PD_POCKET_KEY, 0x200 + key[i], 1);
    } else {
        uint8_t key[2 + 50];
        if (!rd(read, ctx, g->numKeyItems, key, (size_t) (2 + g->keyItemsMax))) return false;
        int n = key[0];
        if (n > g->keyItemsMax || key[1 + n] != 0xFF) return false;
        for (int i = 0; i < n; i++) add_item(s, PD_POCKET_KEY, key[1 + i], 1);
    }
    if (g->tmBits) {
        uint8_t bits[16];
        int bytes = (g->tmCount + 7) / 8;
        if (bytes > 16 || !rd(read, ctx, g->tmsHms, bits, (size_t) bytes)) return false;
        for (int i = 0; i < g->tmCount; i++)
            if ((bits[i / 8] >> (i % 8)) & 1) add_item(s, PD_POCKET_TMHM, g->tmItems[i], 1);
    } else {
        uint8_t tm[G2_TMS];
        if (!rd(read, ctx, g->tmsHms, tm, sizeof tm)) return false;
        for (int i = 0; i < G2_TMS; i++)
            if (tm[i] > 99) return false;
        for (int i = 0; i < G2_TMS && i < g->tmCount; i++)
            if (tm[i]) add_item(s, PD_POCKET_TMHM, g->tmItems[i], tm[i]);
    }
    return true;
}

// The generated tables' lengths, which a static initializer can't name.
static void fill_counts(struct pd_gb_config* c) {
    if (c->internalToDex == pd_gb_internal_to_dex_yellow) c->internalCount = pd_gb_internal_to_dex_yellow_count;
    if (c->internalToDex == pd_gb_internal_to_dex_pure_rgb) c->internalCount = pd_gb_internal_to_dex_pure_rgb_count;
    if (c->internalToDex == pd_gb_internal_to_dex_yellow_legacy) c->internalCount = pd_gb_internal_to_dex_yellow_legacy_count;
    if (c->genderRatios == pd_gb_gender_crystal) c->genderCount = pd_gb_gender_crystal_count;
    if (c->genderRatios == pd_gb_gender_prism) c->genderCount = pd_gb_gender_prism_count;
    if (c->genderRatios == pd_gb_gender_polished) c->genderCount = pd_gb_gender_polished_count;
    if (c->tmItems == pd_gb_tm_crystal) c->tmCount = pd_gb_tm_crystal_count;
    if (c->tmItems == pd_gb_tm_prism) c->tmCount = pd_gb_tm_prism_count;
    if (c->tmItems == pd_gb_tm_polished) c->tmCount = pd_gb_tm_polished_count;
}

// Map group << 8 | number -> its landmark (0 = SPECIAL, or a map the table doesn't list).
static int gb_landmark(const struct pd_gb_config* g, int key) {
    for (int i = 0; i < *g->landmarkCount; i++)
        if (g->landmarks[i][0] == key) return g->landmarks[i][1];
    return 0;
}

void pd_gb_snapshot_read(struct pd_snapshot* s, const struct pd_config* cfg, pd_read_fn read, void* ctx) {
    struct pd_gb_config copy = *cfg->gb;
    fill_counts(&copy);
    const struct pd_gb_config* g = &copy;
    s->valid = true;
    s->partyActive = -1;
    s->money = -1;
    s->foeActive = s->foeNext = -1;
    int monSize = g->gen == 1 ? G1_MON : G2_MON;

    // --- party: the count, then the species list (Gen 2 ends it in 0xFF; Polished Crystal keeps none) ---
    uint8_t head[1 + PD_PARTY_SIZE + 1] = {0};
    if (!rd(read, ctx, g->partyCount, head, sizeof head)) return;
    int count = head[0];
    if (count > PD_PARTY_SIZE || (g->gen == 2 && !g->polished && count != PD_PARTY_SIZE && head[1 + count] != 0xFF) ||
        (g->gen == 1 && head[1 + count] != 0xFF))
        count = 0;
    uint8_t raw[PD_PARTY_SIZE * G2_MON + PD_PARTY_SIZE * NAME_LEN * 2];
    size_t span = (size_t) PD_PARTY_SIZE * monSize + 2 * (size_t) PD_PARTY_SIZE * NAME_LEN;
    if (count && rd(read, ctx, g->partyMons, raw, span)) {
        const uint8_t* nick = raw + PD_PARTY_SIZE * monSize + PD_PARTY_SIZE * NAME_LEN;
        for (int i = 0; i < count; i++) {
            struct pd_mon* m = &s->party[s->partyCount];
            if (!decode_mon(g, raw + i * monSize, g->gen == 2 && !g->polished && head[1 + i] == G2_EGG, m)) continue;
            gb_name(nick + i * NAME_LEN, g->gen, g->polished, m->nickname, sizeof m->nickname);
            s->partyCount++;
        }
    }

    // --- bag ---
    if (g->gen == 1) {
        uint8_t bag[1 + 2 * 30];
        int slots = g->bagSlots > 30 ? 30 : g->bagSlots;
        if (rd(read, ctx, g->numItems, bag, (size_t) (1 + 2 * slots))) {
            int n = bag[0] > slots ? slots : bag[0];
            s->bagOk = true;
            for (int i = 0; i < n && bag[1 + 2 * i] != 0xFF; i++) add_item(s, PD_POCKET_ITEMS, bag[1 + 2 * i], bag[2 + 2 * i]);
        }
    } else {
        s->bagOk = read_gen2_bag(g, s, read, ctx);
        if (!s->bagOk) memset(s->bag, 0, sizeof s->bag);
    }

    // --- money ---
    uint8_t m3[3];
    if (rd(read, ctx, g->money, m3, 3)) {
        if (g->gen == 1) {
            long v = 0;
            for (int i = 0; i < 3; i++) v = v * 100 + (m3[i] >> 4) * 10 + (m3[i] & 15);
            s->money = v;
        } else {
            s->money = ((long) m3[0] << 16) | (m3[1] << 8) | m3[2];
        }
    }

    // --- GUIDE (Gen 1): the event flags and the other banks packed into flags[] (gen_guide.py's GB_BANK_BIT) ---
    if (g->guideRam) {
        const struct pd_gb_guide_ram* r = g->guideRam;
        uint8_t* f = s->flags;
        uint8_t team = 0;
        if (rd(read, ctx, r->event, f, 320) && rd(read, ctx, r->missable, f + 320, 32) &&
            rd(read, ctx, r->hidden, f + 352, 14) && rd(read, ctx, r->trades, f + 366, 2) &&
            rd(read, ctx, r->badges, f + 368, 1) && rd(read, ctx, r->teamVar, &team, 1)) {
            s->vars[0] = team;
            s->flagsOk = s->partyCount > 0;
        }
        uint8_t map;
        if (s->partyCount > 0 && rd(read, ctx, r->curMap, &map, 1)) {
            s->posOk = true;
            s->mapGroup = 0;
            s->mapNum = map;
            s->mapsec = pd_gb_gen1_area[map];
        }
    }

    // --- GUIDE (Crystal): wEventFlags, then the badge and trade banks packed after them (gen_guide.py's CR_BANK_BIT); the
    // clock's time of day in hour (0 morning, 1 day, 2 night); the map and its landmark (HERE's area) ---
    if (g->guide2Ram && s->partyCount > 0) {
        const struct pd_gb_guide2_ram* r = g->guide2Ram;
        uint8_t tod, map[2];
        if (rd(read, ctx, r->event, s->flags, 256) && rd(read, ctx, r->badges, s->flags + 256, 2) &&
            rd(read, ctx, r->trades, s->flags + 258, 1)) s->flagsOk = true;
        if (rd(read, ctx, r->timeOfDay, &tod, 1)) s->hour = tod;
        if (rd(read, ctx, r->mapGroup, map, 2)) {
            s->posOk = true;
            s->mapGroup = map[0];
            s->mapNum = map[1];
        }
    }

    // --- Gen 2's place: the map's landmark, a SPECIAL one (0) named by the backup map (gen2Landmark) ---
    if (g->landmarks && s->partyCount > 0) {
        uint8_t map[2], backup[2];
        if (rd(read, ctx, g->mapGroup, map, 2) && rd(read, ctx, g->backupMapGroup, backup, 2)) {
            s->posOk = true;
            s->mapGroup = map[0];
            s->mapNum = map[1];
            s->mapsec = gb_landmark(g, map[0] << 8 | map[1]);
            if (s->mapsec == 0) s->mapsec = gb_landmark(g, backup[0] << 8 | backup[1]);
        }
        uint8_t gender;
        if (g->playerGender && rd(read, ctx, g->playerGender, &gender, 1)) s->gender = gender & 1;
    }

    // --- POKéDEX: seen / owned bit arrays by Dex number; owned counts as seen (readGen1PokedexState) ---
    if (g->dexCount) {
        uint8_t owned[40], seen[40];
        size_t nb = (size_t) (g->dexCount + 7) / 8;
        if (nb <= sizeof owned && rd(read, ctx, g->dexCaught, owned, nb) && rd(read, ctx, g->dexSeen, seen, nb) &&
            s->partyCount > 0) {
            for (size_t i = 0; i < nb; i++) {
                s->dexCaught[i] = owned[i];
                s->dexSeen[i] = seen[i] | owned[i];
            }
            s->dexOk = true;
            s->dexNational = true;
            s->dexCount = g->dexCount;
        }
    }

    // --- Polished Crystal's GUIDE world (pd_gb_world.c): its flags, badges, clock, champion's var and place ---
    if (cfg->gb == &pd_gb_polished) pd_gbw_read(s, g, read, ctx);

    // --- battle ---
    uint8_t mode = 0;
    rd(read, ctx, g->battleMode, &mode, 1);
    s->inBattle = mode == 1 || mode == 2;
    if (!s->inBattle) return;
    int cursor;
    s->battleState = g->tileMap ? pd_gb_battle_menu(g, read, ctx, &cursor) : PD_BATTLE_BUSY;
    s->isTrainer = mode == 2;
    uint8_t bm[G2_BATTLE + 8];
    int bsize = g->gen == 1 ? G1_BATTLE : g->polished ? POLISHED_BATTLE : G2_BATTLE;
    if (rd(read, ctx, g->battleMon, bm, (size_t) bsize)) decode_battle(g, bm, &s->battlers[PD_POS_PLAYER_LEFT]);
    if (rd(read, ctx, g->enemyMon, bm, (size_t) bsize)) decode_battle(g, bm, &s->battlers[PD_POS_OPPONENT_LEFT]);
    if (mode == 2) {
        uint8_t n = 0;
        rd(read, ctx, g->otPartyCount, &n, 1);
        if (n > PD_PARTY_SIZE) n = PD_PARTY_SIZE;
        uint8_t ot[PD_PARTY_SIZE * G2_MON];
        if (n && rd(read, ctx, g->otPartyMons, ot, (size_t) PD_PARTY_SIZE * monSize)) {
            for (int i = 0; i < n; i++)
                if (decode_mon(g, ot + i * monSize, false, &s->foes[s->foeCount])) s->foeCount++;
            uint8_t cur = 0;
            rd(read, ctx, g->curOTMon, &cur, 1);
            if (cur < s->foeCount) s->foeActive = cur;
        }
    }
}

// --- the game's font ---

// The characters a font tile stands for (single characters only; ligatures and words left out): Gen1Charset.EN / PURE,
// Gen2Art.CHARS / POLISHED_CHARS. {tile, codepoint}, 0-terminated.
struct gb_char { uint8_t tile; uint16_t cp; };
static const struct gb_char GEN1_EXTRA[] = {
    {0x9A, '('}, {0x9B, ')'}, {0x9C, ':'}, {0x9D, ';'}, {0x9E, '['}, {0x9F, ']'}, {0xE0, '\''}, {0xE3, '-'}, {0xE6, '?'},
    {0xE7, '!'}, {0xE8, '.'}, {0xEC, 0x25B7}, {0xED, 0x25B6}, {0xEE, 0x25BC}, {0xEF, 0x2642}, {0xF0, 0xA5}, {0xF1, 0xD7},
    {0xF3, '/'}, {0xF4, ','}, {0xF5, 0x2640}, {0xBA, 0xE9}, {0, 0}};
static const struct gb_char PURE_EXTRA[] = {{0x9E, 0x201C}, {0x9F, 0x201D}, {0xE9, 0x2192}, {0xEA, '+'}, {0xEB, '%'}, {0, 0}};
static const struct gb_char GEN2_EXTRA[] = {
    {0x9A, '('}, {0x9B, ')'}, {0x9C, ':'}, {0x9D, ';'}, {0x9E, '['}, {0x9F, ']'}, {0xC0, 0xC4}, {0xC1, 0xD6}, {0xC2, 0xDC},
    {0xC3, 0xE4}, {0xC4, 0xF6}, {0xC5, 0xFC}, {0xE0, '\''}, {0xE3, '-'}, {0xE6, '?'}, {0xE7, '!'}, {0xE8, '.'}, {0xE9, '&'},
    {0xEA, 0xE9}, {0xEB, 0x2192}, {0xEC, 0x25B7}, {0xED, 0x25B6}, {0xEE, 0x25BC}, {0xEF, 0x2642}, {0xF0, 0xA5}, {0xF1, 0xD7},
    {0xF3, '/'}, {0xF4, ','}, {0xF5, 0x2640}, {0, 0}};
static const struct gb_char POLISHED_EXTRA[] = {
    {0x9A, '('}, {0x9B, ')'}, {0x9C, '.'}, {0x9D, ','}, {0x9E, '?'}, {0x9F, '!'}, {0xBA, 0x201C}, {0xBB, 0x201D}, {0xBC, '-'},
    {0xBD, ':'}, {0xBE, 0x2642}, {0xBF, 0x2640}, {0xC0, '\''}, {0xC8, 0xE9}, {0xC9, 0xC9}, {0xCA, 0xE1}, {0xCB, 0xEA},
    {0xCC, 0xED}, {0xCD, 0xF3}, {0xCE, 0xBF}, {0xCF, 0xA1}, {0xD5, 0x2116}, {0xD8, '&'}, {0xD9, 0x266A}, {0xDA, 0x2665},
    {0xDB, 0xD7}, {0xDC, '/'}, {0xDD, '%'}, {0xDE, '+'}, {0xEA, 0xA5}, {0xEB, 0x2026}, {0xEC, 0x2605}, {0xED, 0x25BC},
    {0xEE, 0x25B2}, {0xEF, 0x25C0}, {0xF0, 0x25B6}, {0, 0}};

#define GB_GLYPHS 160
static struct pd_glyph gbGlyphs[GB_GLYPHS];
static uint8_t gbAlpha[GB_GLYPHS][16 * 16];

// A tile's pixel, outside it blank.
static int font_px(const uint8_t* tile, int x, int y) {
    return tile && x >= 0 && x < 8 && y >= 0 && y < 8 ? tile[y] >> (7 - x) & 1 : 0;
}

// Scale3x (AdvMAME3x) of a 1-bit tile: sub-pixel (sx, sy) of (x, y).
static int tile3x(const uint8_t* tile, int x, int y, int sx, int sy) {
    int A = font_px(tile, x - 1, y - 1), B = font_px(tile, x, y - 1), C = font_px(tile, x + 1, y - 1), D = font_px(tile, x - 1, y),
        E = font_px(tile, x, y), F = font_px(tile, x + 1, y), G = font_px(tile, x - 1, y + 1), H = font_px(tile, x, y + 1),
        I = font_px(tile, x + 1, y + 1);
    if (B == H || D == F) return E;
    int e[9] = {
        D == B ? D : E, (D == B && E != C) || (B == F && E != A) ? B : E, B == F ? F : E,
        (D == B && E != G) || (D == H && E != A) ? D : E, E, (B == F && E != I) || (H == F && E != C) ? F : E,
        D == H ? D : E, (D == H && E != I) || (H == F && E != G) ? H : E, H == F ? F : E,
    };
    return e[sy * 3 + sx];
}

// One 8x8 1bpp tile (top row first, the left pixel the high bit) as a glyph 10 rows tall (9/7 the standard font's letters),
// its top at the standard font's cap line (row 4), 8 wide. Drawn smooth: the tile at 3x (Scale3x), each glyph pixel the
// share of its 3 x 2.4 block the letter covers - repeating two rows of 8 made the letters uneven.
static void put_glyph(int* n, uint16_t cp, const uint8_t* tile) {
    if (*n >= GB_GLYPHS) return;
    int i = (*n)++;
    struct pd_glyph* gl = &gbGlyphs[i];
    uint8_t* alpha = gbAlpha[i];
    memset(gl, 0, sizeof(*gl));
    memset(alpha, 0, 16 * 16);
    gl->codepoint = cp;
    gl->advance = 8;
    gl->alpha = alpha;
    for (int r = 0; r < 10; r++) {
        uint16_t row = 0;
        for (int x = 0; x < 8; x++) {
            // Rows [r * 2.4, (r + 1) * 2.4) of the 24-row picture, in fifths of a row.
            int from = r * 12, to = (r + 1) * 12, covered = 0;
            for (int f = from; f < to; f++) {
                int by = f / 5;
                for (int bx = x * 3; bx < x * 3 + 3; bx++) covered += tile3x(tile, bx / 3, by / 3, bx % 3, by % 3);
            }
            int a = covered * 255 / (12 * 3);
            alpha[(4 + r) * 16 + x] = (uint8_t) a;
            if (a >= 128) row |= (uint16_t) (1u << x);
        }
        gl->rows[4 + r] = row;
    }
}

void pd_gb_font_use(const struct pd_game* g, uint32_t off, enum pd_gb_charset charset) {
    if (!g || !g->rom || !off || off + 0x80 * 8 > g->romSize) { pd_font_set(NULL, 0); return; }
    const uint8_t* font = g->rom + off;
    int n = 0;
    put_glyph(&n, ' ', NULL);
    int digits = charset == PD_GB_CHARS_POLISHED ? 0xE0 : 0xF6;
    for (int i = 0; i < 26; i++) put_glyph(&n, (uint16_t) ('A' + i), font + (0x80 + i - 0x80) * 8);
    for (int i = 0; i < 26; i++) put_glyph(&n, (uint16_t) ('a' + i), font + (0xA0 + i - 0x80) * 8);
    for (int i = 0; i < 10; i++) put_glyph(&n, (uint16_t) ('0' + i), font + (digits + i - 0x80) * 8);
    const struct gb_char* extra = charset == PD_GB_CHARS_POLISHED ? POLISHED_EXTRA : charset == PD_GB_CHARS_GEN2 ? GEN2_EXTRA : GEN1_EXTRA;
    for (const struct gb_char* c = extra; c->tile; c++) put_glyph(&n, c->cp, font + (c->tile - 0x80) * 8);
    if (charset == PD_GB_CHARS_PURE)
        for (const struct gb_char* c = PURE_EXTRA; c->tile; c++) put_glyph(&n, c->cp, font + (c->tile - 0x80) * 8);
    pd_font_set(gbGlyphs, n);
}

// --- the game's party icons ---

static const struct pd_gb_icons* gbIcons;

// A Game Boy cart is read by file offset (bank * 0x4000 + address - 0x4000).
static const uint8_t* pd_rom_at_off(const struct pd_game* g, uint32_t off, size_t len) {
    return g->rom && off <= g->romSize && len <= g->romSize - off ? g->rom + off : NULL;
}

void pd_gb_icons_use(const struct pd_gb_icons* icons) { gbIcons = icons; }

static int tile_px(const uint8_t* tile, int x, int y) {
    return (tile[2 * y] >> (7 - x) & 1) | (tile[2 * y + 1] >> (7 - x) & 1) << 1;
}

// --- the cart's own art (its entry's gbArt), where the core's tables don't cover the cart ---
static const struct pd_gb_art* art_of(const struct pd_game* g) {
    return g->entry && g->cfg && g->cfg->gb ? g->entry->gbArt : NULL;
}

static uint32_t art_off(int bank, int addr) {
    return bank ? (uint32_t) (bank * 0x4000 + (addr - 0x4000)) : (uint32_t) addr;
}

static void put2x(uint32_t* out, int x, int y, uint32_t colour) {
    out[y * 16 + x] = 0xFF000000u | colour; // the core's 16x16 icon (pd_gb_icon)
}

static bool icon_gen1(const struct pd_game* g, const struct pd_gb_art* a, int dex, uint32_t* out) {
    const uint8_t* rom = g->rom;
    if (dex < 1 || dex > a->dexCount) return false;
    if (a->iconSheet) {
        // PureRGB: an icon's 8 tiles as they are, OAM drawing tiles 0, 1 over 4, 5.
        uint32_t icon = a->iconSheet + 0x80u * rom[a->monPartyData + (uint32_t) dex];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int v = tile_px(rom + icon + 16 * ((y / 8) * 4 + x / 8), x % 8, y % 8);
                if (v) put2x(out, x, y, a->iconColors[v - 1]);
            }
        return true;
    }
    // MonPartySpritePointers loads the shapes' tiles into VRAM; a 16 x 16 is a tile column and its mirror image.
    int b = rom[a->monPartyData + (uint32_t) (dex - 1) / 2];
    int type = (dex - 1) % 2 == 0 ? b >> 4 : b & 0xF;
    int base = type << 2;
    bool asymmetric = type == 2; // the helix: 4 tiles, no mirroring
    static const uint8_t* vram[0x100];
    memset(vram, 0, sizeof(vram));
    for (int e = 0; e < a->iconPointerCount; e++) {
        const uint8_t* o = rom + a->iconPointers + 6 * e;
        int ptr = o[0] | o[1] << 8, count = o[2], bank = o[3];
        int dest = ((o[4] | o[5] << 8) - 0x8000) / 16;
        for (int k = 0; k < count && dest + k >= 0 && dest + k < 0x100; k++) vram[dest + k] = rom + art_off(bank, ptr) + 16 * k;
    }
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
            int slot = asymmetric ? base + (y / 8) * 2 + x / 8 : base + (y / 8) * 2;
            int tx = asymmetric ? x % 8 : (x < 8 ? x : 15 - x);
            const uint8_t* tile = vram[slot];
            int v = tile ? tile_px(tile, tx, y % 8) : 0;
            if (v) put2x(out, x, y, a->iconColors[v - 1]);
        }
    return true;
}

static uint32_t gbc_rgb(const uint8_t* rom, uint32_t o) {
    int c = rom[o] | rom[o + 1] << 8;
    uint32_t r = c & 31, g = c >> 5 & 31, b = c >> 10 & 31;
    return (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
}

// Frame 1's four tiles (TL, TR, BL, BR) in [pal].
static void draw_gen2(const uint8_t* tiles, const uint32_t* pal, uint32_t* out) {
    for (int t = 0; t < 4; t++)
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = tile_px(tiles + 16 * t, x, y);
                if (v) put2x(out, (t % 2) * 8 + x, (t / 2) * 8 + y, pal[v]);
            }
}

static bool icon_gen2(const struct pd_game* g, const struct pd_gb_art* a, int species, uint32_t* out) {
    const uint8_t* rom = g->rom;
    bool polished = g->cfg->gb->polished;
    uint8_t tiles[128];
    uint32_t pal[4] = { 0, 0, 0, 0 };
    if (a->minis) {
        int mini = species > POLISHED_NUM_SPECIES ? POLISHED_NUM_SPECIES + a->cosmeticForms + (species - POLISHED_NUM_SPECIES - 1) : species;
        const uint8_t* e = rom + a->minis + 7 * mini;
        if (pd_gb_lz(rom, g->romSize, art_off(e[0], e[1] | e[2] << 8), tiles, 128, polished) < 64) return false;
        uint32_t p = a->monPalettes + 8u * (uint32_t) species;
        pal[1] = gbc_rgb(rom, p);
        pal[2] = gbc_rgb(rom, p + 2);
        draw_gen2(tiles, pal, out);
        return true;
    }
    if (a->iconFarPointers) {
        const uint8_t* e = rom + a->iconFarPointers + 3 * (species - 1);
        if (pd_gb_lz(rom, g->romSize, art_off(e[0], e[1] | e[2] << 8), tiles, 128, false) < 64) return false;
        uint32_t p = a->iconObjPals + 8u * (rom[a->iconPalIndex + (uint32_t) species - 1] >> 4 & 7);
        for (int i = 1; i < 4; i++) pal[i] = gbc_rgb(rom, p + 2u * (uint32_t) i);
        draw_gen2(tiles, pal, out);
        return true;
    }
    if (a->bankLow) {
        // Crystal Clear: by Dex number, LZ, each in its own colours (UNOWN's by letter).
        int bank = species < 0x80 ? a->bankLow : a->bankHigh;
        uint32_t table = a->icons + 2u * (uint32_t) species;
        if (species == 201 && a->unownIcons) {
            table = a->unownIcons;
            bank = a->unownBank;
        }
        if (pd_gb_lz(rom, g->romSize, art_off(bank, rom[table] | rom[table + 1] << 8), tiles, 128, false) < 64) return false;
        uint32_t p = a->iconPalettes + 8u * (uint32_t) species;
        pal[1] = gbc_rgb(rom, p);
        pal[2] = gbc_rgb(rom, p + 2);
        draw_gen2(tiles, pal, out);
        return true;
    }
    int id = species == PD_SPECIES_EGG ? a->eggIcon : rom[a->monMenuIcons + (uint32_t) species - 1];
    uint32_t t = a->icons + 2u * (uint32_t) id;
    uint32_t at = art_off(a->iconsBank, rom[t] | rom[t + 1] << 8);
    for (int i = 0; i < 4; i++) pal[i] = gbc_rgb(rom, a->partyMenuObPals + 2u * (uint32_t) i);
    draw_gen2(rom + at, pal, out);
    return true;
}

// --- the town map ---

static bool gen1_map(const struct pd_game* g, const struct pd_gb_art* a, uint32_t* out) {
    const uint8_t* rom = g->rom;
    if (!a->townMap) return false;
    const uint8_t* tiles = rom + a->townMapTiles;
    uint32_t o = a->townMap;
    int cell = 0;
    while (rom[o] && cell < 20 * 18) {
        int b = rom[o++];
        for (int k = 0; k < (b & 0xF) && cell < 20 * 18; k++, cell++) {
            const uint8_t* tile = tiles + 16 * (b >> 4);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    out[((cell / 20) * 8 + y) * 160 + (cell % 20) * 8 + x] = a->townMapColors[tile_px(tile, x, y)];
        }
    }
    return true;
}

static bool gen2_map(const struct pd_game* g, const struct pd_gb_art* a, int region, uint32_t* out) {
    const uint8_t* rom = g->rom;
    if (!a->townMapGfx || region < 0 || region >= a->townMapCount) return false;
    static uint8_t tiles[0x1000], lz[20 * 18];
    int size = pd_gb_lz(rom, g->romSize, a->townMapGfx, tiles, sizeof(tiles), g->cfg->gb->polished);
    if (size <= 0) return false;
    int first = 0;
    const uint8_t* map = rom + a->townMaps[region];
    if (a->townMapsLz) {
        if (pd_gb_lz(rom, g->romSize, a->townMaps[region], lz, 20 * 17, false) != 20 * 17) return false;
        map = lz;
        first = 20;
        for (int i = 0; i < 160 * 8; i++) out[i] = gbc_rgb(rom, a->pokegearPals);
    }
    for (int cell = first; cell < 20 * 18; cell++) {
        int byte = map[cell - first];
        if (byte == 0xFF) return false;
        int tile = a->townMapFlips ? byte & 0x3F : byte;
        bool xf = a->townMapFlips && (byte & 0x40), yf = a->townMapFlips && (byte & 0x80);
        if (tile * 16 + 16 > size) return false;
        int pal = tile >= a->townMapPal0From ? 0 : (rom[a->townMapPalMap + (uint32_t) tile / 2] >> (tile % 2 == 0 ? 0 : 4)) & 7;
        if (pal >= a->townMapPalCount) return false;
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = tile_px(tiles + tile * 16, xf ? 7 - x : x, yf ? 7 - y : y);
                out[((cell / 20) * 8 + y) * 160 + (cell % 20) * 8 + x] = gbc_rgb(rom, a->pokegearPals + 8u * (uint32_t) pal + 2u * (uint32_t) v);
            }
    }
    return true;
}

bool pd_gb_art_town_map(const struct pd_game* g, int region, uint32_t* out, int stride) {
    const struct pd_gb_art* a = art_of(g);
    if (!a || !g->rom) return false;
    static uint32_t page[160 * 144];
    if (!(g->cfg->gb->gen == 1 ? gen1_map(g, a, page) : gen2_map(g, a, region, page))) return false;
    for (int y = 0; y < 144; y++) memcpy(out + y * stride, page + y * 160, 160 * sizeof(uint32_t));
    return true;
}

int pd_gb_art_maps(const struct pd_game* g) {
    const struct pd_gb_art* a = art_of(g);
    if (!a) return 0;
    return g->cfg->gb->gen == 1 ? (a->townMap ? 1 : 0) : (a->townMapGfx ? a->townMapCount : 0);
}

bool pd_gb_art_player(const struct pd_game* g, int gender, uint32_t* out, uint32_t clear) {
    const struct pd_gb_art* a = art_of(g);
    if (!a || !g->rom) return false;
    const struct pd_gb_config* c = g->cfg->gb;
    const uint8_t* tiles;
    static uint8_t buffer[128];
    uint32_t pal[4];
    if (c->gen == 1) {
        if (!a->playerSprite) return false;
        tiles = g->rom + a->playerSprite;
        for (int i = 0; i < 4; i++) pal[i] = a->playerColors[i];
    } else {
        if (gender < 0 || !a->playerSprites[gender]) return false;
        tiles = g->rom + a->playerSprites[gender];
        if (a->playerLz) {
            if (pd_gb_lz(g->rom, g->romSize, a->playerSprites[gender], buffer, sizeof(buffer), true) < 64) return false;
            tiles = buffer;
        }
        for (int i = 0; i < 4; i++) pal[i] = gbc_rgb(g->rom, a->objectPals + 8u * (uint32_t) (a->playerPalFrom + gender) + 2u * (uint32_t) i);
    }
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
            int v = tile_px(tiles + 16 * ((y / 8) * 2 + x / 8), x % 8, y % 8);
            out[y * 16 + x] = v ? pal[v] : clear;
        }
    return true;
}

static bool art_icon(const struct pd_game* g, int species, uint32_t* out) {
    const struct pd_gb_art* a = art_of(g);
    if (!a || !g->rom || species <= 0) return false;
    memset(out, 0, 16 * 16 * sizeof(uint32_t));
    return g->cfg->gb->gen == 1 ? icon_gen1(g, a, species, out) : icon_gen2(g, a, species, out);
}

bool pd_gb_icon(const struct pd_game* g, int species, uint32_t* out) {
    const struct pd_gb_icons* t = gbIcons;
    if (!t) return art_icon(g, species, out); // the cart's own art (pd_games_gen.c), where the core has no table
    if (!g->rom) return false;
    memset(out, 0, 16 * 16 * sizeof(uint32_t));
    if (t->gen == 1) {
        if (species == PD_SPECIES_EGG || species < 1 || species > 151) return false;
        // VRAM as the party menu loads it: each entry copies count tiles from bank:ptr to its tile slot.
        const uint8_t* vram[0x80] = { 0 };
        for (int e = 0; e < t->pointerCount; e++) {
            const uint8_t* p = pd_rom_at_off(g, t->pointers + 6u * (uint32_t) e, 6);
            if (!p) return false;
            int ptr = p[0] | p[1] << 8, count = p[2], bank = p[3], dest = (p[4] | p[5] << 8) - 0x8000;
            for (int k = 0; k < count; k++) {
                int slot = dest / 16 + k;
                uint32_t src = (uint32_t) (bank ? bank * 0x4000 + (ptr - 0x4000) : ptr) + 16u * (uint32_t) k;
                if (slot >= 0 && slot < 0x80) vram[slot] = pd_rom_at_off(g, src, 16);
            }
        }
        const uint8_t* types = pd_rom_at_off(g, t->data + (uint32_t) (species - 1) / 2, 1);
        if (!types) return false;
        int type = (species - 1) % 2 == 0 ? types[0] >> 4 : types[0] & 0xF;
        bool helix = type == 2; // the one asymmetric icon: 4 tiles, no mirroring
        int base = type << 2;
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                const uint8_t* tile = helix ? vram[base + (y / 8) * 2 + x / 8] : vram[base + (y / 8) * 2];
                if (!tile) continue;
                int v = tile_px(tile, helix ? x % 8 : x < 8 ? x : 15 - x, y % 8);
                if (v) out[y * 16 + x] = 0xFF000000u | t->colors[v - 1];
            }
        return true;
    }
    // Gen 2 (Crystal, Gold / Silver): MonMenuIcons[dex - 1] picks an icon, Icons[icon] its 2bpp tiles (TL TR BL BR).
    int icon;
    if (species == PD_SPECIES_EGG) icon = t->eggIcon;
    else if (species >= 1 && species <= 251) {
        const uint8_t* m = pd_rom_at_off(g, t->data + (uint32_t) species - 1, 1);
        if (!m) return false;
        icon = m[0];
    } else return false;
    const uint8_t* ip = pd_rom_at_off(g, t->pointers + 2u * (uint32_t) icon, 2);
    const uint8_t* pal = pd_rom_at_off(g, t->menuPal, 8);
    if (!ip || !pal) return false;
    const uint8_t* tiles = pd_rom_at_off(g, (uint32_t) (t->bank * 0x4000 + ((ip[0] | ip[1] << 8) - 0x4000)), 4 * 16);
    if (!tiles) return false;
    uint32_t colors[4];
    for (int i = 0; i < 4; i++) {
        unsigned c = pal[2 * i] | pal[2 * i + 1] << 8, r = c & 31, gg = c >> 5 & 31, b = c >> 10 & 31;
        colors[i] = 0xFF000000u | (r << 3 | r >> 2) << 16 | (gg << 3 | gg >> 2) << 8 | (b << 3 | b >> 2);
    }
    for (int tile = 0; tile < 4; tile++)
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int v = tile_px(tiles + 16 * tile, x, y);
                if (v) out[((tile / 2) * 8 + y) * 16 + (tile % 2) * 8 + x] = colors[v];
            }
    return true;
}

// --- Gen 1 DEX pages ---

static const struct pd_gb_dex1* gbDex1;
void pd_gb_dex_use(const struct pd_gb_dex1* t) { gbDex1 = t; }
bool pd_gb_dex_pages(const struct pd_game* g) { return (gbDex1 || pd_gb2_dex(g)) && g->rom; }

static int gb_u8(const struct pd_game* g, uint32_t o) { const uint8_t* p = pd_rom_at_off(g, o, 1); return p ? p[0] : -1; }
static int gb_u16(const struct pd_game* g, uint32_t o) { const uint8_t* p = pd_rom_at_off(g, o, 2); return p ? p[0] | p[1] << 8 : -1; }
static uint32_t gb_off(int bank, int addr) { return (uint32_t) (bank ? bank * 0x4000 + (addr - 0x4000) : addr); }

// Gen 1 text until its end (@, or DEXEND 0x5F's full stop), in the game's characters (Gen1Charset.EN), spaces collapsed.
static void gen1_text(const struct pd_game* g, uint32_t o, char* out, size_t len) {
    static const char* const PUNCT[256] = {
        [0x7F] = " ", [0x9A] = "(", [0x9B] = ")", [0x9C] = ":", [0x9D] = ";", [0x9E] = "[", [0x9F] = "]", [0xBA] = "é",
        [0xBB] = "'d", [0xBC] = "'l", [0xBD] = "'s", [0xBE] = "'t", [0xBF] = "'v", [0xE0] = "'", [0xE3] = "-", [0xE4] = "'r",
        [0xE5] = "'m", [0xE6] = "?", [0xE7] = "!", [0xE8] = ".", [0xEF] = "♂", [0xF0] = "¥", [0xF1] = "×", [0xF2] = ".",
        [0xF3] = "/", [0xF4] = ",", [0xF5] = "♀", [0x54] = "POKé", [0x75] = "…", [0x4E] = " ", [0x4F] = " ", [0x49] = " ",
        [0x51] = " "};
    size_t n = 0;
    out[0] = 0;
    for (int i = 0; i < 400 && n + 8 < len; i++) {
        int c = gb_u8(g, o + (uint32_t) i);
        if (c < 0 || c == 0x50) break;
        char one[2] = {0, 0};
        const char* s = NULL;
        if (c == 0x5F) { s = "."; }
        else if (c == 0x00 && n == 0) continue;  // the text command that opens a far text
        else if (c >= 0x80 && c < 0x80 + 26) { one[0] = (char) ('A' + c - 0x80); s = one; }
        else if (c >= 0xA0 && c < 0xA0 + 26) { one[0] = (char) ('a' + c - 0xA0); s = one; }
        else if (c >= 0xF6) { one[0] = (char) ('0' + c - 0xF6); s = one; }
        else s = PUNCT[c];
        if (!s) s = "";
        if (*s == ' ' && (n == 0 || out[n - 1] == ' ')) s++;
        size_t l = strlen(s);
        if (n + l + 1 >= len) break;
        memcpy(out + n, s, l);
        n += l;
        out[n] = 0;
        if (c == 0x5F) break;
    }
    while (n && out[n - 1] == ' ') out[--n] = 0;
}

int pd_gb_internal_count(const struct pd_gb_config* gb) {
    return gb->internalCount ? gb->internalCount : gb->internalToDex == pd_gb_internal_to_dex_yellow ?
        pd_gb_internal_to_dex_yellow_count : gb->internalToDex == pd_gb_internal_to_dex_pure_rgb ? pd_gb_internal_to_dex_pure_rgb_count :
        gb->internalToDex == pd_gb_internal_to_dex_yellow_legacy ? pd_gb_internal_to_dex_yellow_legacy_count : 0;
}

static int internal_of(const struct pd_game* g, int dex) {
    const struct pd_gb_config* gb = g->cfg->gb;
    int count = gb->internalCount ? gb->internalCount : gb->internalToDex == pd_gb_internal_to_dex_yellow ?
        pd_gb_internal_to_dex_yellow_count : gb->internalToDex == pd_gb_internal_to_dex_pure_rgb ? pd_gb_internal_to_dex_pure_rgb_count :
        gb->internalToDex == pd_gb_internal_to_dex_yellow_legacy ? pd_gb_internal_to_dex_yellow_legacy_count : 0;
    for (int i = 1; i < count; i++)
        if (gb->internalToDex[i] == dex) return i;
    return 0;
}

static uint32_t base_stats_of(const struct pd_gb_dex1* t, int dex) {
    return dex == 151 && t->mewBaseStats ? t->mewBaseStats : t->baseStats + 28u * (uint32_t) (dex - 1);
}

bool pd_gb_dex_entry(const struct pd_game* g, int dex, struct pd_dex_entry* out) {
    if (pd_gb2_dex(g)) return pd_gb2_dex_entry(g, dex, out); // pd_gb_dex.c's pages, where the cart has its tables (either gen)
    const struct pd_gb_dex1* t = gbDex1;
    memset(out, 0, sizeof(*out));
    if (!t || !g->rom || dex < 1 || dex > 151) return false;
    int internal = internal_of(g, dex);
    if (!internal) return false;
    int ptr = gb_u16(g, t->dexEntryPointers + 2u * (uint32_t) (internal - 1));
    if (ptr < 0) return false;
    uint32_t e = gb_off(t->dexBank, ptr), o = e;
    while (gb_u8(g, o) != 0x50) { if (gb_u8(g, o) < 0 || o - e > 20) return false; o++; }
    gen1_text(g, e, out->category, sizeof(out->category));
    o++;
    int feet = gb_u8(g, o), inches = gb_u8(g, o + 1), lbs10 = gb_u16(g, o + 2);
    out->heightIn = feet * 12 + inches;
    out->weightLbs10 = lbs10;
    out->heightDm = (int) ((out->heightIn * 2.54) / 10 + 0.5);
    out->weightHg = (int) (lbs10 * 0.45359237 + 0.5);
    uint32_t far = o + 4;
    if (gb_u8(g, far) == 0x17) gen1_text(g, gb_off(gb_u8(g, far + 3), gb_u16(g, far + 1)), out->description, sizeof(out->description));
    uint32_t s = base_stats_of(t, dex);
    out->national = out->species = dex;
    out->type1 = gb_u8(g, s + 6);
    out->type2 = gb_u8(g, s + 7);
    for (int i = 0; i < 5; i++) out->stats[i] = gb_u8(g, s + 1 + (uint32_t) i);
    out->gen1Stats = true;
    return true;
}

// Gen 1's sprite compression (home/uncompress.asm, the app's Gen1Pic): two 1bpp chunks, RLE-packed bit pairs, delta-coded.
struct bits { const struct pd_game* g; uint32_t pos; int cur, left; bool bad; };
static int bit(struct bits* b) {
    if (b->left == 0) { b->cur = gb_u8(b->g, b->pos++); if (b->cur < 0) { b->bad = true; b->cur = 0; } b->left = 8; }
    b->left--;
    return b->cur >> b->left & 1;
}
static void chunk(struct bits* b, uint8_t* buf, int wT, int height) {
    int col = 0, y = 0, shift = 6;
    bool done = false, rle = bit(b) == 0;
    while (!done && !b->bad) {
        int pairs[1], n = 0, pair = 0;
        (void) pairs;
        if (rle) {
            int c = 0;
            while (bit(b) == 1 && c < 16) c++;
            int v = 0;
            for (int i = 0; i <= c; i++) v = v << 1 | bit(b);
            n = v + (1 << (c + 1)) - 1;
            rle = false;
        } else {
            pair = bit(b) << 1;
            pair |= bit(b);
            if (pair == 0) { rle = true; continue; }
            n = 1;
        }
        for (int i = 0; i < n && !done; i++) {
            buf[col * height + y] |= (uint8_t) (pair << shift);
            if (++y == height) {
                y = 0;
                if (shift > 0) shift -= 2;
                else { shift = 6; if (++col == wT) done = true; }
            }
        }
    }
}
static void delta(uint8_t* buf, int wT, int height) {
    static const uint8_t D0[8] = {0x01, 0x32, 0x76, 0x45, 0xFE, 0xCD, 0x89, 0xBA};
    static const uint8_t D1[8] = {0xFE, 0xCD, 0x89, 0xBA, 0x01, 0x32, 0x76, 0x45};
    for (int y = 0; y < height; y++) {
        int last = 0;
        for (int col = 0; col < wT; col++) {
            int v = buf[col * height + y], r[2];
            for (int h = 0; h < 2; h++) {
                int nyb = h ? v & 0xF : v >> 4;
                int e = (last & 1 ? D1 : D0)[nyb >> 1];
                r[h] = nyb & 1 ? e & 0xF : e >> 4;
                last = r[h];
            }
            buf[col * height + y] = (uint8_t) (r[0] << 4 | r[1]);
        }
    }
}

bool pd_gb_dex_sprite(const struct pd_game* g, int dex, uint32_t* out) {
    if (pd_gb2_dex(g)) return pd_gb2_dex_sprite(g, dex, out);
    const struct pd_gb_dex1* t = gbDex1;
    if (!t || !g->rom || dex < 1 || dex > 151) return false;
    uint32_t s = base_stats_of(t, dex);
    int ptr = gb_u16(g, s + 11), internal = internal_of(g, dex), bank;
    if (ptr < 0 || !internal) return false;
    if (internal == t->fossilKabutops) bank = t->fossilKabutopsBank;
    else if (dex == 151 && t->mewBaseStats) bank = t->mewPicsBank;
    else { bank = t->picsBank; for (int i = 0; i < 4; i++) bank += internal >= t->picsBankBreaks[i]; }
    struct bits b = { g, gb_off(bank, ptr), 0, 0, false };
    int dims = gb_u8(g, b.pos++);
    int hT = dims & 0xF, wT = dims >> 4;
    if (dims < 0 || !wT || !hT || wT > 8 || hT > 8) return false;
    int height = hT * 8, size = wT * height;
    static uint8_t buf[2][8 * 64];
    memset(buf, 0, sizeof buf);
    bool firstIn2 = bit(&b) == 1;
    uint8_t* first = firstIn2 ? buf[1] : buf[0];
    uint8_t* second = firstIn2 ? buf[0] : buf[1];
    chunk(&b, first, wT, height);
    int mode = bit(&b) == 0 ? 0 : 1 + bit(&b);
    chunk(&b, second, wT, height);
    if (b.bad) return false;
    if (mode == 0) { delta(buf[0], wT, height); delta(buf[1], wT, height); }
    else if (mode == 1) { delta(first, wT, height); for (int i = 0; i < size; i++) second[i] ^= first[i]; }
    else { delta(second, wT, height); delta(first, wT, height); for (int i = 0; i < size; i++) second[i] ^= first[i]; }
    // the mon's palette (MonsterPalettes by Dex number, 4 RGB555 colours each; colour 0 clear)
    int palId = gb_u8(g, t->monsterPalettes + (uint32_t) dex);
    uint32_t colors[4] = {0};
    for (int i = 1; i < 4; i++) {
        int c = gb_u16(g, t->cgbPalettes + 8u * (uint32_t) palId + 2u * (uint32_t) i);
        if (c < 0) return false;
        colors[i] = (uint32_t) ((c & 31) * 255 / 31) << 16 | (uint32_t) ((c >> 5 & 31) * 255 / 31) << 8 | (uint32_t) ((c >> 10 & 31) * 255 / 31);
    }
    for (int i = 0; i < PD_SPRITE * PD_SPRITE; i++) out[i] = 0xFF000000u;
    // 56x56 box (LoadUncompressedSpriteData: centred across, bottom-aligned), in the middle of the 64x64 sprite
    int ox = ((8 - wT) / 2) * 8 + 4, oy = (7 - hT) * 8 + 4;
    for (int col = 0; col < wT; col++)
        for (int y = 0; y < height; y++)
            for (int bx = 0; bx < 8; bx++) {
                int v = (buf[0][col * height + y] >> (7 - bx) & 1) | (buf[1][col * height + y] >> (7 - bx) & 1) << 1;
                int px = ox + col * 8 + bx, py = oy + y;
                if (v && px >= 0 && px < PD_SPRITE && py >= 0 && py < PD_SPRITE) out[py * PD_SPRITE + px] = colors[v];
            }
    return true;
}

// The battle menus (pret's pokeyellow / pokecrystal): where each waits and its cursor.
#define TILE_CURSOR 0xED // ▶, a menu waiting for input
#define GEN1_BATTLE_MENU_TOP 0x0E
#define GEN1_MOVE_MENU_TOP 0x0C
#define GEN1_MOVE_MENU_X 0x05
#define GEN1_SUBMENU_TOP 0x0C
#define GEN2_BATTLE_MENU_TOP 0x0E
#define GEN2_BATTLE_MENU_LEFT 0x09
#define GEN2_MOVE_MENU_TOP 0x0D
#define GEN2_MOVE_MENU_LEFT 0x05
#define GEN2_CHOOSE_POKEMON 0
#define GEN2_SWITCH 2
#define GEN2_SUBMENU_TOP 0x0C
#define POLISHED_SUBMENU_LEFT 0x0B

static int read8(pd_read_fn read, void* ctx, uint32_t addr) {
    uint8_t v = 0;
    read(ctx, addr, &v, 1);
    return v;
}

static bool cursor_shown(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int row, int x, int tile) {
    return row >= 0 && row < 18 && x >= 0 && x < 20 && read8(read, ctx, c->tileMap + (uint32_t) (row * 20 + x)) == tile;
}

static enum pd_battle_state gen1_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor) {
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return PD_BATTLE_NONE;
    struct pd_gb_party_menu p = pd_gb_party_menu(c, read, ctx);
    if (p.open) {
        *cursor = p.cursor;
        return PD_BATTLE_PARTY_OPEN;
    }
    uint8_t m[5];
    read(ctx, c->topMenuItemY, m, 5);
    int top = m[0], x = m[1], item = m[2], max = m[4];
    if (top == GEN1_BATTLE_MENU_TOP && max == 1 && item <= 1 && (x == c->battleMenuLeftX || x == c->battleMenuRightX) &&
        cursor_shown(c, read, ctx, top + 2 * item, x, TILE_CURSOR)) {
        *cursor = (x == c->battleMenuRightX ? 1 : 0) + 2 * item;
        return PD_BATTLE_ACTION;
    }
    if (top == GEN1_MOVE_MENU_TOP && x == GEN1_MOVE_MENU_X && item >= 1 && item <= 4 && read8(read, ctx, c->moveMenuType) == 0 &&
        cursor_shown(c, read, ctx, top + item, x, TILE_CURSOR)) {
        *cursor = item - 1;
        return PD_BATTLE_MOVE;
    }
    return PD_BATTLE_BUSY;
}

static enum pd_battle_state gen2_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor) {
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return PD_BATTLE_NONE;
    struct pd_gb_party_menu p = pd_gb_party_menu(c, read, ctx);
    if (p.open) {
        *cursor = p.cursor;
        return PD_BATTLE_PARTY_OPEN;
    }
    uint8_t m[7], cur[2];
    read(ctx, c->menu2D, m, 7);
    read(ctx, c->menuCursor, cur, 2);
    int top = m[0], left = m[1], rows = m[2], cols = m[3], spacing = m[6], y = cur[0], x = cur[1];
    if (y < 1 || y > rows || x < 1 || x > cols) return PD_BATTLE_BUSY;
    int tx = left + (x - 1) * (spacing & 0xF), ty = top + (y - 1) * (spacing >> 4);
    if (!cursor_shown(c, read, ctx, ty, tx, c->cursorTile)) return PD_BATTLE_BUSY;
    if (top == GEN2_BATTLE_MENU_TOP && left == GEN2_BATTLE_MENU_LEFT && rows == 2 && cols == 2) {
        *cursor = (x - 1) + 2 * (y - 1);
        return PD_BATTLE_ACTION;
    }
    if (top == GEN2_MOVE_MENU_TOP && left == GEN2_MOVE_MENU_LEFT && cols == 1 && rows >= 1 && rows <= 4 &&
        read8(read, ctx, c->moveMenuType) == 0) {
        *cursor = y - 1;
        return PD_BATTLE_MOVE;
    }
    return PD_BATTLE_BUSY;
}

enum pd_battle_state pd_gb_battle_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx, int* cursor) {
    *cursor = -1;
    return c->gen == 1 ? gen1_battle_menu(c, read, ctx, cursor) : gen2_battle_menu(c, read, ctx, cursor);
}

static struct pd_gb_party_menu gen1_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    struct pd_gb_party_menu r = { 0 };
    if (!c->topMenuItemY || !c->partyMenuType) return r;
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return r;
    uint8_t m[5];
    read(ctx, c->topMenuItemY, m, 5);
    int top = m[0], x = m[1], item = m[2], max = m[4];
    if (top == GEN1_SUBMENU_TOP && x == GEN1_SUBMENU_TOP && max == 2 && item <= 2 && cursor_shown(c, read, ctx, top + 2 * item, x, TILE_CURSOR)) {
        r.open = r.submenu = true;
        r.cursor = item;
        return r;
    }
    if (top != 1 || x != 0 || item > max) return r;
    int count = read8(read, ctx, c->partyCount);
    int type = read8(read, ctx, c->partyMenuType);
    if (count < 1 || count > PD_PARTY_SIZE || max != count - 1) return r;
    if (type != 0 && type != c->battlePartyMenu) return r; // a Potion's "use on which?"
    if (!cursor_shown(c, read, ctx, top + 2 * item, x, TILE_CURSOR)) return r;
    r.open = true;
    r.cursor = item;
    r.forced = type == c->battlePartyMenu;
    return r;
}

static struct pd_gb_party_menu gen2_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    struct pd_gb_party_menu r = { 0 };
    int battle = read8(read, ctx, c->battleMode);
    if (battle != 1 && battle != 2) return r;
    uint8_t m[7];
    read(ctx, c->menu2D, m, 7);
    int top = m[0], left = m[1], rows = m[2], cols = m[3], spacing = m[6];
    int y = read8(read, ctx, c->menuCursor);
    if (cols != 1 || spacing != 0x20 || y < 1 || y > rows) return r;
    int ty = top + 2 * (y - 1);
    if (ty >= 18 || left >= 20 || !cursor_shown(c, read, ctx, ty, left, c->cursorTile)) return r;
    if (top == GEN2_SUBMENU_TOP && left == (c->polished ? POLISHED_SUBMENU_LEFT : GEN2_SUBMENU_TOP) && rows == 3) {
        r.open = r.submenu = true;
        r.cursor = y - 1;
        return r;
    }
    if (top != 1 || left != 0) return r;
    int count = read8(read, ctx, c->partyCount);
    int action = read8(read, ctx, c->partyMenuAction);
    if (count < 1 || count > PD_PARTY_SIZE || rows != count + 1) return r;
    if (action != GEN2_CHOOSE_POKEMON && action != GEN2_SWITCH) return r;
    r.open = true;
    r.cursor = y == rows ? PD_GB_PARTY_CANCEL : y - 1;
    r.forced = action == GEN2_SWITCH;
    return r;
}

struct pd_gb_party_menu pd_gb_party_menu(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    return c->gen == 1 ? gen1_party_menu(c, read, ctx) : gen2_party_menu(c, read, ctx);
}

int pd_gb_party(const struct pd_gb_config* cfg, pd_read_fn read, void* ctx, struct pd_gb_party_mon* out) {
    // The keys are the snapshot's personalities (decode_mon: OT ID and DVs), so a slot is found by the party read's key.
    struct pd_gb_config copy = *cfg;
    fill_counts(&copy);
    const struct pd_gb_config* c = &copy;
    static uint8_t raw[PD_PARTY_SIZE * G2_MON];
    int n = read8(read, ctx, c->partyCount);
    if (n > PD_PARTY_SIZE) n = PD_PARTY_SIZE;
    int stride = c->gen == 1 ? G1_MON : G2_MON;
    rd(read, ctx, c->partyMons, raw, (size_t) (PD_PARTY_SIZE * stride));
    for (int i = 0; i < n; i++) {
        struct pd_mon mon;
        out[i].key = decode_mon(c, raw + i * stride, false, &mon) ? mon.personality : 0;
        out[i].hp = be16(raw + i * stride + (c->gen == 1 ? 1 : 34));
    }
    return n;
}

int pd_gb_out_slot(const struct pd_gb_config* c, pd_read_fn read, void* ctx) {
    return read8(read, ctx, c->outSlot);
}
