// Which game is running and where its RAM globals live - the C twin of the
// app's NativeConfig (app/.../companion/data/NativeReader.kt). Only the
// fields this port reads so far; the values are generated from gamedata/,
// like the app's (pd_configs_gen.h, from gamedata/targets/3ds.toml).
#ifndef PD_GAME_H
#define PD_GAME_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum pd_game_kind {
    PD_GAME_NONE = 0,
    PD_GAME_FIRERED,  // FireRed and LeafGreen (LeafGreen runs as FireRed, like the app)
    PD_GAME_EMERALD,
};

// Which GUIDE a game gets (pd_guide_for): its own, or none until it's ported.
enum pd_guide_id {
    PD_GUIDE_NONE = 0,
    PD_GUIDE_FIRERED,
    PD_GUIDE_LEAFGREEN,
    PD_GUIDE_EMERALD,
    PD_GUIDE_RUBY,
    PD_GUIDE_SAPPHIRE,
    PD_GUIDE_EMERALD_LEGACY,
};

struct pd_config {
    uint32_t playerParty;
    uint32_t playerPartyCount;
    uint32_t battleMons;
    uint32_t battlerPositions;
    uint32_t battlersCount;
    uint32_t battleTypeFlags;
    uint32_t gMain;
    uint32_t saveBlock1Ptr;
    uint32_t saveBlock2Ptr;
    uint32_t mapHeader;
    int32_t encryptionKeyOff;  // in SaveBlock2; -1 = no key (Ruby / Sapphire: money and bag counts plain)
    // Ruby / Sapphire: saveBlock1Ptr / saveBlock2Ptr are the blocks themselves, gBattleStruct a fixed block.
    bool staticSaveBlocks, battleStructStatic;
    uint32_t moneyOff;         // in SaveBlock1
    // The battle's FOE TEAM: gEnemyParty (6 struct Pokemon), gBattlerPartyIndexes
    // (u16 per battler: which one is out) and gBattleStruct (a pointer) ->
    // monToSwitchIntoId[battler] (the trainer's next pick). 0 = unknown.
    uint32_t enemyParty;
    uint32_t battlerPartyIndexes;
    uint32_t battleStructPtr;
    uint32_t monToSwitchIntoOff;
    // The party menu's icons in the ROM: gMonIconTable (a tiles pointer per
    // species), gMonIconPaletteIndices (u8) and gMonIconPaletteTable
    // ({data, tag}, 8 bytes). 0 = none.
    uint32_t monIconTable, monIconPaletteIndices, monIconPaletteTable;
    // The bag's icons: sItemIconTable, {tiles, palette} (both LZ77) every itemIconStride bytes. 0 = none.
    uint32_t itemIconTable;
    int itemIconStride;
    // The game's own egg species (0 = retail's 412) and how many species its tables hold (with the egg).
    int eggSpecies, speciesCount;
    // gBagPockets: five {slots pointer, capacity} pairs, 8 bytes apart;
    // bagOrder[i] is the pocket (PD_POCKET_*) the game keeps at index i.
    uint32_t bagPockets;
    uint8_t bagOrder[5];

    // POKéDEX (the app's PokedexTables): ROM tables ...
    uint32_t dexEntries;       // gPokedexEntries, by national number
    uint32_t dexEntryStride;
    uint32_t speciesInfo;      // gBaseStats, 28 bytes per species
    uint32_t speciesToNational; // u16 per species from species 1
    uint32_t abilityNames;     // 13 bytes each
    uint32_t frontPics;        // {LZ77 pointer, size, tag} per species
    uint32_t palettes;         // {LZ77 pointer, tag} per species
    uint32_t regionalOrder;    // u16 national numbers; 0 = national 1..regionalCount
    int regionalCount;
    const char* regionName;
    // ... and the save: SaveBlock2's owned / seen flags, SaveBlock1's two
    // copies of seen (all must agree), the National Dex byte.
    uint32_t dexSeenCopy1, dexSeenCopy2; // in SaveBlock1
    uint8_t nationalMagicOff, nationalMagic; // SaveBlock2 + 0x18 + off == magic

    // GUIDE (the app's GuideTables): ROM tables and SaveBlock1 offsets.
    uint32_t trainers;    // gTrainers, 0x28 bytes each
    uint32_t learnsets;   // gLevelUpLearnsets, a pointer per species
    uint32_t wildHeaders; // gWildMonHeaders, 20 bytes each
    uint32_t flagsOff, varsOff, flagBytes; // in SaveBlock1
    // A hack's own struct TrainerMon (GuideTables.trainerMon), every field at a fixed offset; stride 0 = retail's.
    int trainerMonStride, trainerMonMovesOff, trainerMonSpeciesOff, trainerMonItemOff, trainerMonLevelOff;
    int probeTrainer;     // checks the tables match the ROM ...
    const char* probeName; // ... by this trainer's name

    // TRAINER CARD (the app's TrainerCardSave): which card (PD_CARD_*, 0 =
    // none), SaveBlock1.gameStats, the badge / POKéDEX flags, what makes the
    // National Dex count (with nationalMagic above), FireRed's sticker vars and
    // Emerald's extra stars (Battle Frontier symbols, the museum's paintings)
    // and battle points. 0 = not in this game.
    int cardStyle;
    uint32_t gameStatsOff;
    int badgeFlag, pokedexFlag;
    int nationalVar, nationalVarValue, nationalFlag;
    int stickerVar;
    int frontierSymbolFlag;
    int extraStarFlag; // a flag worth one more star (Legacy's FLAG_CAUGHT_MEW); 0 = none
    uint32_t museumWinnersOff, frontierBpOff; // in SaveBlock1 / SaveBlock2

    // FLY (the app's FlyFns / HealWarp, Fly.kt): the game's own functions, called between frames (the Linux host's
    // quick menu). FlagGet, SetUpFieldMove_Fly, GetMapsecType, FadeScreen, Lock- / UnlockPlayerFieldControls and
    // SetFlyWarpDestination - or, Emerald's way (flyHealTo set), SetWarpDestinationToHealLocation /
    // SetWarpDestinationToMapWarp + ReturnToFieldFromFlyMapSelect; flyCrcs = CRC32 of each one's first 32 bytes, in
    // that order. gPartyMenu.slotId (the cut-in's Pokémon), sMapFlyDestinations ({group, num, heal} per map section
    // from flyFirstMapsec), the GetMapsecType answers that fly and the sections past the table that do (0 = none).
    // flyFlagGet 0 = no FLY.
    uint32_t flyFlagGet, flySetUp, flyGetMapsecType, flyFade, flyLock, flyUnlock, flyWarp;
    int flyBadgeFlag;
    uint32_t flyPartySlot, flyDestinations;
    int flyFirstMapsec, flyCount;
    uint32_t flyCrcs[9];
    int flyTypes[4], flyExtra[4];
    uint32_t flyHealTo, flyMapWarp, flyReturn, flySaveBlock2Ptr; // gSaveBlock2Ptr: playerGender at +8
    int flySpecial[4][2]; // {map section, heal location} taken before the table's
    int flyGenderMapsec, flyMaleHeal, flyFemaleHeal; // the home town: by the player's gender
    bool flySaveBlock2Static; // flySaveBlock2Ptr is gSaveBlock2 itself (Ruby / Sapphire)
    uint32_t flyMapHeader; // set: SetUpFieldMove_Fly takes gMapHeader.mapType (+0x17) as its argument (Ruby / Sapphire)

    // REPEL and PC anywhere (the app's FieldItemFns / PcFns, FieldItems.kt / Pc.kt): VarGet, VarSet,
    // CheckBagHasItem, RemoveBagItem and ArePlayerFieldControlsLocked (fieldCrcs, in that order), VAR_REPEL_STEP_COUNT
    // and the Repels' ids, gItems (holdEffectParam = a Repel's steps, itemNameLen + 5 into struct Item unless
    // itemParamOffset); ScriptContext_SetupScript, ShowPokemonStorageSystemPC's index in gSpecials (whose entry is
    // pcFn), their CRCs and where the PC's script goes. fieldVarGet 0 = none.
    uint32_t fieldVarGet, fieldVarSet, fieldCheckBag, fieldRemoveBag, fieldLocked;
    uint32_t fieldCrcs[5];
    int repelVar, repelItems[4];
    uint32_t gItems;
    int itemNameLen, itemParamOffset;
    uint32_t pcSetupScript;
    int pcSpecial;
    uint32_t pcSpecials, pcFn, pcCrcs[2], pcScript;
};

// LEGACY: Emerald Legacy's - HOENN's card, a fifth star (extraStarFlag) on a black one, Hoenn's dex and eight more.
enum pd_card_style { PD_CARD_NONE, PD_CARD_KANTO, PD_CARD_HOENN, PD_CARD_LEGACY };

// The bag's pockets, in a fixed order of our own (the app's POCKET_* ids).
enum pd_pocket {
    PD_POCKET_ITEMS,
    PD_POCKET_BALLS,
    PD_POCKET_TMHM,
    PD_POCKET_BERRIES,
    PD_POCKET_KEY,
    PD_POCKET_COUNT,
};

struct pd_game {
    enum pd_game_kind kind;
    char code[5];       // ROM header game code, e.g. "BPRE"
    int revision;       // ROM header 0xBC
    const char* title;  // "Pokémon FireRed"
    enum pd_guide_id guide;
    const struct pd_config* cfg; // NULL = detected but not supported
    const char* unsupported;     // why, when cfg is NULL
    // The ROM's bytes (0x08000000 on the bus), for the tabs that read its
    // tables and art; the host sets them once the ROM is loaded (NULL = none).
    const uint8_t* rom;
    size_t romSize;
    unsigned loadId; // new on every pd_game_detect (see pd_rom_id)
    char sha1[41];   // the whole ROM's, once pd_game_identify ran ("" before)
};

// Which ROM the tabs' caches were built from: 0 = none, else new for every
// game loaded. Not the ROM pointer - mGBA's 3DS build loads every game into
// the same fixed buffer.
unsigned pd_rom_id(const struct pd_game* g);

// Reads from the ROM at a bus address (0x08xxxxxx); false if out of range.
bool pd_rom_read(const struct pd_game* g, uint32_t addr, void* out, size_t len);
// A pointer into the ROM at a bus address, with at least len bytes after it.
const uint8_t* pd_rom_at(const struct pd_game* g, uint32_t addr, size_t len);

// Reads the ROM header (the first 0xC0 bytes of the ROM) and its size.
void pd_game_detect(struct pd_game* g, const uint8_t* header, size_t romSize);

// Once g->rom is set: which FireRed / LeafGreen / Emerald file this is, by its SHA-1, as the app's Poller.detect
// tells them apart - a hack the companion knows gets its own config, a retail cart (or a collection's dump of one,
// the app's RetailDumps) keeps detect's, and anything else is an unknown hack: unsupported rather than read with
// retail's addresses.
void pd_game_identify(struct pd_game* g);

#endif
