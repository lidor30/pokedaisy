/* gamedata/ phase 2: the configs generated into pd_configs_gen.h are the ones pd_game.c wrote by hand
 * (copied below, renamed), field for field, over every value those hand-written configs set. The fields
 * added since (a hack's layouts, text tables, dex and guide shapes) are generated only and not compared
 * here. Goes once the gate has passed, with LegacyRecords.kt / GameDataMigrationTest on the app's side. */
#include "../core/pd_configs_gen.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

// NATIVE_FIRERED_REV0 / _REV1 share every RAM address and save offset below,
// and LeafGreen's maps put each RAM global at FireRed's address for the same
// revision (NATIVE_LEAFGREEN_REV0/1).
#define FIRERED_RAM                                                                                     \
    .playerParty = 0x02024284,                                                                          \
    .playerPartyCount = 0x02024029,                                                                     \
    .battleMons = 0x02023BE4,                                                                           \
    .battlerPositions = 0x02023BD6,                                                                     \
    .battlersCount = 0x02023BCC,                                                                        \
    .battleTypeFlags = 0x02022B4C,                                                                      \
    .gMain = 0x030030F0,                                                                                \
    .saveBlock1Ptr = 0x03005008,                                                                        \
    .saveBlock2Ptr = 0x0300500C,                                                                        \
    .mapHeader = 0x02036DFC,                                                                            \
    .encryptionKeyOff = 0xF20,                                                                          \
    .moneyOff = 0x290,                                                                                  \
    /* FireRed's item.c: Items, Key Items, Poke Balls, TM Case, Berry Pouch. */                         \
    .bagPockets = 0x0203988C,                                                                           \
    .bagOrder = { PD_POCKET_ITEMS, PD_POCKET_KEY, PD_POCKET_BALLS, PD_POCKET_TMHM, PD_POCKET_BERRIES }, \
    /* FIRERED_DEX_FLAGS (Pokedex.kt) and GuideTables' SaveBlock1 offsets. */                            \
    .dexSeenCopy1 = 0x5F8,                                                                              \
    .dexSeenCopy2 = 0x3A18,                                                                             \
    .nationalMagicOff = 3,                                                                              \
    .nationalMagic = 0xB9,                                                                              \
    .flagsOff = 0xEE0,                                                                                  \
    .varsOff = 0x1000,                                                                                  \
    .flagBytes = 0x120

// The FOE TEAM, from the rev 1 map (NATIVE_FIRERED_REV1; LeafGreen rev 1's map
// has the same addresses). monToSwitchIntoId sits at 0x5C in struct BattleStruct.
#define FIRERED_REV1_FOES        \
    .enemyParty = 0x0202402C,          \
    .battlerPartyIndexes = 0x02023BCE, \
    .battleStructPtr = 0x02023FE8,     \
    .monToSwitchIntoOff = 0x5C

// TRAINER_CARD_FIRERED: retail FireRed rev 1 and LeafGreen (the app has no
// card for FireRed rev 0).
#define FIRERED_CARD                \
    .cardStyle = PD_CARD_KANTO,      \
    .gameStatsOff = 0x1200,          \
    .badgeFlag = 0x820,              \
    .pokedexFlag = 0x829,            \
    .nationalVar = 0x404E,           \
    .nationalVarValue = 0x6258,      \
    .nationalFlag = 0x840,           \
    .stickerVar = 0x4049

// FireRed rev 1 (sha1 dd5945db..., what pret's firered_rev1 builds): its ROM
// tables too - POKEDEX_FIRERED_REV1 and GUIDE_TABLES_FIRERED_REV1.
static const struct pd_config LEGACY_FIRERED_REV1 = {
    FIRERED_RAM,
    FIRERED_REV1_FOES,
    FIRERED_CARD,
    .monIconTable = 0x083D3810,
    .monIconPaletteIndices = 0x083D3EF0,
    .monIconPaletteTable = 0x083D40A8,
    .dexEntries = 0x0844E8B0,
    .dexEntryStride = 0x24,
    .speciesInfo = 0x082547F4,
    .speciesToNational = 0x0825205E,
    .abilityNames = 0x0824FCB0,
    .frontPics = 0x0823511C,
    .palettes = 0x0823737C,
    .regionalOrder = 0,
    .regionalCount = 151,
    .regionName = "KANTO",
    .trainers = 0x0823EB38,
    .learnsets = 0x0825D824,
    .wildHeaders = 0x083C9D28,
    .probeTrainer = 414,
    .probeName = "BROCK",
};

// FireRed rev 0 and LeafGreen: the tabs that read RAM (and the MAP, whose art
// is found by fingerprint) and the party icons; their ROM tables for the
// POKéDEX and the GUIDE's live pages aren't ported yet. The FOE TEAM's
// addresses are known for rev 1 only (the app's NATIVE_FIRERED_REV0 has none).
static const struct pd_config LEGACY_FIRERED_REV0_RAM_ONLY = {
    FIRERED_RAM,
    .monIconTable = 0x083D37A0,
    .monIconPaletteIndices = 0x083D3E80,
    .monIconPaletteTable = 0x083D4038,
};

static const struct pd_config LEGACY_LEAFGREEN_REV0_RAM_ONLY = {
    FIRERED_RAM,
    FIRERED_CARD,
    .monIconTable = 0x083D35DC,
    .monIconPaletteIndices = 0x083D3CBC,
    .monIconPaletteTable = 0x083D3E74,
};

static const struct pd_config LEGACY_LEAFGREEN_REV1_RAM_ONLY = {
    FIRERED_RAM,
    FIRERED_REV1_FOES,
    FIRERED_CARD,
    .monIconTable = 0x083D364C,
    .monIconPaletteIndices = 0x083D3D2C,
    .monIconPaletteTable = 0x083D3EE4,
};

// NATIVE_EMERALD_RETAIL (English, BPEE; sha1 f3ae0881..., what pret's
// pokeemerald builds), with POKEDEX_EMERALD and GUIDE_TABLES_EMERALD.
static const struct pd_config LEGACY_EMERALD = {
    .playerParty = 0x020244EC,
    .playerPartyCount = 0x020244E9,
    .battleMons = 0x02024084,
    .battlerPositions = 0x02024076,
    .battlersCount = 0x0202406C,
    .battleTypeFlags = 0x02022FEC,
    .gMain = 0x030022C0,
    .saveBlock1Ptr = 0x03005D8C,
    .saveBlock2Ptr = 0x03005D90,
    .mapHeader = 0x02037318,
    .encryptionKeyOff = 0xAC,
    .moneyOff = 0x490,
    .enemyParty = 0x02024744,
    .battlerPartyIndexes = 0x0202406E,
    .battleStructPtr = 0x0202449C,
    .monToSwitchIntoOff = 0x5C,
    .monIconTable = 0x0857BCA8,
    .monIconPaletteIndices = 0x0857C388,
    .monIconPaletteTable = 0x0857C540,
    // Emerald's item.c: Items, Poke Balls, TMs & HMs, Berries, Key Items.
    .bagPockets = 0x02039DD8,
    .bagOrder = { PD_POCKET_ITEMS, PD_POCKET_BALLS, PD_POCKET_TMHM, PD_POCKET_BERRIES, PD_POCKET_KEY },
    .dexEntries = 0x0856B5B0,
    .dexEntryStride = 0x20,
    .speciesInfo = 0x083203CC,
    .speciesToNational = 0x0831DC82,
    .abilityNames = 0x0831B6DB,
    .frontPics = 0x0830A18C,
    .palettes = 0x08303678,
    .regionalOrder = 0x0831DFB8,
    .regionalCount = 202,
    .regionName = "HOENN",
    .dexSeenCopy1 = 0x988,
    .dexSeenCopy2 = 0x3B24,
    .nationalMagicOff = 2,
    .nationalMagic = 0xDA,
    .trainers = 0x08310030,
    .learnsets = 0x0832937C,
    .wildHeaders = 0x08552D48,
    .flagsOff = 0x1270,
    .varsOff = 0x139C,
    .flagBytes = 0x12C,
    .probeTrainer = 265,
    .probeName = "ROXANNE",
    // TRAINER_CARD_EMERALD.
    .cardStyle = PD_CARD_HOENN,
    .gameStatsOff = 0x159C,
    .badgeFlag = 0x867,
    .pokedexFlag = 0x861,
    .nationalVar = 0x4046,
    .nationalVarValue = 0x302,
    .nationalFlag = 0x896,
    .frontierSymbolFlag = 0x8C4,
    .museumWinnersOff = 0x2E90 + 8 * 32,
    .frontierBpOff = 0xEBA,
};

static int same(const char* name, const struct pd_config* a, const struct pd_config* b) {
    int bad = 0;
// A field the hand-written config left 0 / NULL may be filled in since (FireRed rev 0's card, LeafGreen's dex and
// guide tables): only what it set must be kept.
#define NUM(f) if (a->f && a->f != b->f) { printf("%s.%s: %lld != %lld\n", name, #f, (long long) a->f, (long long) b->f); bad++; }
#define STR(f) if (a->f && (!b->f || strcmp(a->f, b->f))) { printf("%s.%s differs\n", name, #f); bad++; }
    NUM(playerParty)
    NUM(staticSaveBlocks)
    NUM(flySaveBlock2Static)
    NUM(flyMapHeader)
    NUM(playerPartyCount)
    NUM(battleMons)
    NUM(battlerPositions)
    NUM(battlersCount)
    NUM(battleTypeFlags)
    NUM(gMain)
    NUM(saveBlock1Ptr)
    NUM(saveBlock2Ptr)
    NUM(mapHeader)
    NUM(encryptionKeyOff)
    NUM(moneyOff)
    NUM(enemyParty)
    NUM(battlerPartyIndexes)
    NUM(battleStructPtr)
    NUM(monToSwitchIntoOff)
    NUM(monIconTable)
    NUM(monIconPaletteIndices)
    NUM(monIconPaletteTable)
    NUM(bagPockets)
    NUM(dexEntries)
    NUM(dexEntryStride)
    NUM(speciesInfo)
    NUM(speciesToNational)
    NUM(abilityNames)
    NUM(frontPics)
    NUM(palettes)
    NUM(regionalOrder)
    NUM(regionalCount)
    NUM(dexSeenCopy1)
    NUM(dexSeenCopy2)
    NUM(nationalMagicOff)
    NUM(nationalMagic)
    NUM(trainers)
    NUM(learnsets)
    NUM(wildHeaders)
    NUM(flagsOff)
    NUM(varsOff)
    NUM(flagBytes)
    NUM(probeTrainer)
    NUM(cardStyle)
    NUM(gameStatsOff)
    NUM(badgeFlag)
    NUM(pokedexFlag)
    NUM(nationalVar)
    NUM(nationalVarValue)
    NUM(nationalFlag)
    NUM(stickerVar)
    NUM(frontierSymbolFlag)
    NUM(museumWinnersOff)
    NUM(frontierBpOff)
    STR(regionName)
    STR(probeName)
    if (memcmp(a->bagOrder, b->bagOrder, sizeof(a->bagOrder))) { printf("%s.bagOrder differs\n", name); bad++; }
    return bad;
}

int main(void) {
    // Every field above, by size: a new one in struct pd_config needs a line in same().
    struct field { size_t size; } fields[] = {
        { sizeof(((struct pd_config*) 0)->playerParty) },
        { sizeof(((struct pd_config*) 0)->playerPartyCount) },
        { sizeof(((struct pd_config*) 0)->battleMons) },
        { sizeof(((struct pd_config*) 0)->battlerPositions) },
        { sizeof(((struct pd_config*) 0)->battlersCount) },
        { sizeof(((struct pd_config*) 0)->battleTypeFlags) },
        { sizeof(((struct pd_config*) 0)->gMain) },
        { sizeof(((struct pd_config*) 0)->saveBlock1Ptr) },
        { sizeof(((struct pd_config*) 0)->saveBlock2Ptr) },
        { sizeof(((struct pd_config*) 0)->mapHeader) },
        { sizeof(((struct pd_config*) 0)->encryptionKeyOff) },
        { sizeof(((struct pd_config*) 0)->moneyOff) },
        { sizeof(((struct pd_config*) 0)->enemyParty) },
        { sizeof(((struct pd_config*) 0)->battlerPartyIndexes) },
        { sizeof(((struct pd_config*) 0)->battleStructPtr) },
        { sizeof(((struct pd_config*) 0)->monToSwitchIntoOff) },
        { sizeof(((struct pd_config*) 0)->monIconTable) },
        { sizeof(((struct pd_config*) 0)->monIconPaletteIndices) },
        { sizeof(((struct pd_config*) 0)->monIconPaletteTable) },
        { sizeof(((struct pd_config*) 0)->bagPockets) },
        { sizeof(((struct pd_config*) 0)->dexEntries) },
        { sizeof(((struct pd_config*) 0)->dexEntryStride) },
        { sizeof(((struct pd_config*) 0)->speciesInfo) },
        { sizeof(((struct pd_config*) 0)->speciesToNational) },
        { sizeof(((struct pd_config*) 0)->abilityNames) },
        { sizeof(((struct pd_config*) 0)->frontPics) },
        { sizeof(((struct pd_config*) 0)->palettes) },
        { sizeof(((struct pd_config*) 0)->regionalOrder) },
        { sizeof(((struct pd_config*) 0)->regionalCount) },
        { sizeof(((struct pd_config*) 0)->dexSeenCopy1) },
        { sizeof(((struct pd_config*) 0)->dexSeenCopy2) },
        { sizeof(((struct pd_config*) 0)->nationalMagicOff) },
        { sizeof(((struct pd_config*) 0)->nationalMagic) },
        { sizeof(((struct pd_config*) 0)->trainers) },
        { sizeof(((struct pd_config*) 0)->learnsets) },
        { sizeof(((struct pd_config*) 0)->wildHeaders) },
        { sizeof(((struct pd_config*) 0)->flagsOff) },
        { sizeof(((struct pd_config*) 0)->varsOff) },
        { sizeof(((struct pd_config*) 0)->flagBytes) },
        { sizeof(((struct pd_config*) 0)->probeTrainer) },
        { sizeof(((struct pd_config*) 0)->cardStyle) },
        { sizeof(((struct pd_config*) 0)->gameStatsOff) },
        { sizeof(((struct pd_config*) 0)->badgeFlag) },
        { sizeof(((struct pd_config*) 0)->pokedexFlag) },
        { sizeof(((struct pd_config*) 0)->nationalVar) },
        { sizeof(((struct pd_config*) 0)->nationalVarValue) },
        { sizeof(((struct pd_config*) 0)->nationalFlag) },
        { sizeof(((struct pd_config*) 0)->stickerVar) },
        { sizeof(((struct pd_config*) 0)->frontierSymbolFlag) },
        { sizeof(((struct pd_config*) 0)->museumWinnersOff) },
        { sizeof(((struct pd_config*) 0)->frontierBpOff) },
        { sizeof(((struct pd_config*) 0)->regionName) },
        { sizeof(((struct pd_config*) 0)->probeName) },
        { sizeof(((struct pd_config*) 0)->bagOrder) },
        // FLY's: new to the port (no hand-written copy to compare), the app's Fly.kt records. The Linux host's
        // test flies with them on the real games.
        { sizeof(((struct pd_config*) 0)->flyFlagGet) },
        { sizeof(((struct pd_config*) 0)->flySetUp) },
        { sizeof(((struct pd_config*) 0)->flyGetMapsecType) },
        { sizeof(((struct pd_config*) 0)->flyFade) },
        { sizeof(((struct pd_config*) 0)->flyLock) },
        { sizeof(((struct pd_config*) 0)->flyUnlock) },
        { sizeof(((struct pd_config*) 0)->flyWarp) },
        { sizeof(((struct pd_config*) 0)->flyBadgeFlag) },
        { sizeof(((struct pd_config*) 0)->flyPartySlot) },
        { sizeof(((struct pd_config*) 0)->flyDestinations) },
        { sizeof(((struct pd_config*) 0)->flyFirstMapsec) },
        { sizeof(((struct pd_config*) 0)->flyCount) },
        { sizeof(((struct pd_config*) 0)->flyCrcs) },
        { sizeof(((struct pd_config*) 0)->flyTypes) },
        { sizeof(((struct pd_config*) 0)->flyExtra) },
        { sizeof(((struct pd_config*) 0)->flyHealTo) },
        { sizeof(((struct pd_config*) 0)->flyMapWarp) },
        { sizeof(((struct pd_config*) 0)->flyReturn) },
        { sizeof(((struct pd_config*) 0)->flySaveBlock2Ptr) },
        { sizeof(((struct pd_config*) 0)->flySpecial) },
        { sizeof(((struct pd_config*) 0)->flyGenderMapsec) },
        { sizeof(((struct pd_config*) 0)->flyMaleHeal) },
        { sizeof(((struct pd_config*) 0)->flyFemaleHeal) },
        // The bag's icons and REPEL / PC anywhere: new to the port too (NativeConfig's, FieldItems.kt / Pc.kt records).
        { sizeof(((struct pd_config*) 0)->itemIconTable) },
        { sizeof(((struct pd_config*) 0)->itemIconStride) },
        { sizeof(((struct pd_config*) 0)->fieldVarGet) },
        { sizeof(((struct pd_config*) 0)->fieldVarSet) },
        { sizeof(((struct pd_config*) 0)->fieldCheckBag) },
        { sizeof(((struct pd_config*) 0)->fieldRemoveBag) },
        { sizeof(((struct pd_config*) 0)->fieldLocked) },
        { sizeof(((struct pd_config*) 0)->fieldCrcs) },
        { sizeof(((struct pd_config*) 0)->repelVar) },
        { sizeof(((struct pd_config*) 0)->repelItems) },
        { sizeof(((struct pd_config*) 0)->gItems) },
        { sizeof(((struct pd_config*) 0)->itemNameLen) },
        { sizeof(((struct pd_config*) 0)->itemParamOffset) },
        { sizeof(((struct pd_config*) 0)->pcSetupScript) },
        { sizeof(((struct pd_config*) 0)->pcSpecial) },
        { sizeof(((struct pd_config*) 0)->pcSpecials) },
        { sizeof(((struct pd_config*) 0)->pcFn) },
        { sizeof(((struct pd_config*) 0)->pcCrcs) },
        { sizeof(((struct pd_config*) 0)->pcScript) },
        { sizeof(((struct pd_config*) 0)->staticSaveBlocks) },
        { sizeof(((struct pd_config*) 0)->flySaveBlock2Static) },
        { sizeof(((struct pd_config*) 0)->flyMapHeader) },
        // The 3DS's fields from 3ds.toml's battle / engine / boxes / dexsec parts and pd_games_gen.c (not in the old tables).
        { sizeof(((struct pd_config*) 0)->pcStorage) },
        { sizeof(((struct pd_config*) 0)->monIconRecord) },
        { sizeof(((struct pd_config*) 0)->extraItemIconTable) },
        { sizeof(((struct pd_config*) 0)->extraItemFirst) },
        { sizeof(((struct pd_config*) 0)->extraItemCount) },
        { sizeof(((struct pd_config*) 0)->extraItemStride) },
    };
    size_t sum = 0;
    for (size_t i = 0; i < sizeof(fields) / sizeof(*fields); i++) sum += fields[i].size;
    assert(sum <= sizeof(struct pd_config));
    int bad = same("FIRERED_REV1", &LEGACY_FIRERED_REV1, &PD_CONFIG_FIRERED_REV1)
        + same("FIRERED_REV0", &LEGACY_FIRERED_REV0_RAM_ONLY, &PD_CONFIG_FIRERED_REV0)
        + same("LEAFGREEN_REV0", &LEGACY_LEAFGREEN_REV0_RAM_ONLY, &PD_CONFIG_LEAFGREEN_REV0)
        + same("LEAFGREEN_REV1", &LEGACY_LEAFGREEN_REV1_RAM_ONLY, &PD_CONFIG_LEAFGREEN_REV1)
        + same("EMERALD", &LEGACY_EMERALD, &PD_CONFIG_EMERALD);
    assert(!bad);
    puts("PASS the 5 configs generated from gamedata/ keep every value the hand-written ones set");
}
