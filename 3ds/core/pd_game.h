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
    PD_GAME_GB,       // Gen 1 / Gen 2: read through g->gb (pd_gb.c)
};

// Where a game's struct Pokemon keeps things (the app's PartyMonLayout). NULL on a config = vanilla's.
struct pd_party_layout {
    int size, status, level, hp, maxHp, flags, plainBox, hpBits, statusShift;
    uint32_t statusMask;
    int eggMask;
    bool eggInIvWord;
    int nicknameOff, nicknameLen;
    int plainSpeciesMask, plainItemMask;
    bool hasStats, rowe, eliteRedux, monomythKey;
};

// ... and its struct BattlePokemon (BattleMonLayout).
struct pd_battle_layout {
    int size, species, speciesMask, moves, pp, type1, type2, hp, level, maxHp, status1;
};

// USE on a Pokémon, GIVE and TAKE (the app's PartyItemFns).
struct pd_party_fns {
    uint32_t getMonData, setMonData, itemNoEffect, itemEffect, addBagItem, party, partyCount, scratch;
    int itemCount;
    uint32_t unholdablePockets; // a bit per struct Item pocket id
    uint32_t codeCrcs[5];
    const uint16_t* medicine;
    int medicineCount;
    const uint16_t* ppOne;
    int ppOneCount;
    const uint16_t* ppAll;
    int ppAllCount;
    const uint16_t* mail;
    int mailCount;
    const uint32_t* useFuncs;
    int useFuncCount;
    int hpUp, shedinja, monFieldShift;
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
    uint32_t encryptionKeyOff; // in SaveBlock2
    uint32_t moneyOff;         // in SaveBlock1
    // The battle's FOE TEAM: gEnemyParty (6 struct Pokemon), gBattlerPartyIndexes
    // (u16 per battler: which one is out) and gBattleStruct (a pointer) ->
    // monToSwitchIntoId[battler] (the trainer's next pick). 0 = unknown.
    uint32_t enemyParty;
    uint32_t battlerPartyIndexes;
    uint32_t battleStructPtr;
    uint32_t monToSwitchIntoOff;
    // The battle's touch controls (pd_binput.h; the app's BattleInputController): gMain's inBattle byte,
    // gBattlerControllerFuncs and the handlers each battler's function can be; the battle menus' cursors (a byte per
    // battler; 0 = walked blind); gPartyMenu (0 = no POKéMON switch) and whether its slots sit in two columns.
    uint32_t inBattleOff;
    uint32_t battlerControllerFuncs;
    uint32_t handleInputChooseAction, handleInputChooseMove, handleInputChooseTarget;
    uint32_t completeWhenChoseItem, waitForMonSelection;
    uint32_t actionCursor, moveCursor;
    uint32_t partyMenu;
    bool partyMenuGrid;
    // The party menu's icons in the ROM: gMonIconTable (a tiles pointer per
    // species), gMonIconPaletteIndices (u8) and gMonIconPaletteTable
    // ({data, tag}, 8 bytes). 0 = none.
    uint32_t monIconTable, monIconPaletteIndices, monIconPaletteTable;
    // The bag's icons: sItemIconTable, {tiles, palette} (both LZ77) every itemIconStride bytes. 0 = none.
    uint32_t itemIconTable;
    int itemIconStride;
    // gBagPockets: five {slots pointer, capacity} pairs, 8 bytes apart;
    // bagOrder[i] is the pocket (PD_POCKET_*) the game keeps at index i.
    uint32_t bagPockets;
    uint8_t bagOrder[12];
    int bagPocketCount; // 0 = five

    // What a game's engine changed (0 / NULL = vanilla's).
    // Bags of their own: Quetzal's BAG3 and Elite Redux's owned bits, each item's pocket by id.
    uint32_t bag3Off, bag3TailOff, itemFlagsOff;
    int bag3SplitAt, bag3KeyBit, itemFlagsCount, itemPocketCount;
    const uint8_t* itemPockets;
    // The DEX's sections (DexDetails.kt), on vanilla's tables: gEvolutionTable, the TM bitfields, their moves, gMapGroups.
    uint32_t dexEvolutions, dexTmhm, dexTmhmMoves, dexMapGroups;
    int dexSpeciesCount;
    // The PC (NativeConfig.pcStorage, pokemonStoragePtr): 0 boxes = not read. pcFormat: enum pd_box_format.
    uint32_t pokemonStoragePtr, cfruStorage, cfruMoves;
    uint32_t cfruExtra[6];
    int pcBoxes, pcRecord, pcFormat;
    bool pcDirect;
    // The PC's moves (BoxSwapFns): 0 = no WITHDRAW / DEPOSIT / SWAP here.
    uint32_t boxToMon, boxRestorePP, boxGetData, boxStoragePtr;
    uint32_t boxCrcs[3];
    const struct pd_party_layout* party;
    const struct pd_battle_layout* battle;
    const struct pd_party_fns* partyFns; // NULL = no USE / GIVE / TAKE
    int monStride;
    int speciesMask, moveMask, ppMask;
    uint32_t saveBlock1PosOff, playerGenderOff, frameCounterOff;
    int eggSpecies;
    long maxMoney;
    bool staticSaveBlocks, battleStructStatic, mapSecWide;
    int monIconStride, monPalIdxStride, monPalIdxMask; // 0 = 4, 1, 0xFF
    uint32_t monIconPalettes; // a palette pointer per species at monIconStride, not the shared six
    bool monIconPacked;       // Monomyth's own tile coder
    bool monIconRecord;       // Unbound's: an entry points at the tiles with their palette behind them (UnboundIconSource)
    bool itemPalRaw;          // the bag's palettes aren't LZ77 (itemPalCompressed false)
    // Emerald Rogue's own items: {tiles, palette} per entry from extraItemFirst on, extraItemCount of them.
    uint32_t extraItemIconTable;
    int extraItemFirst, extraItemCount, extraItemStride;
    uint32_t itemDescBase;
    int itemDescStride, itemDescOff, itemDescFirst, itemDescCount;

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

enum pd_card_style { PD_CARD_NONE, PD_CARD_KANTO, PD_CARD_HOENN };

struct pd_table_set;
struct pd_game_entry;
struct pd_gb_config;

// The bag's pockets, in a fixed order of our own (the app's POCKET_* ids).
enum pd_pocket {
    PD_POCKET_ITEMS,
    PD_POCKET_BALLS,
    PD_POCKET_TMHM,
    PD_POCKET_BERRIES,
    PD_POCKET_KEY,
    PD_POCKET_MEDICINE,
    PD_POCKET_MAIL,
    PD_POCKET_BATTLE_ITEMS,
    PD_POCKET_MEGA_STONES,
    PD_POCKET_COUNT,
};

struct pd_game {
    enum pd_game_kind kind;
    char code[5];       // ROM header game code, e.g. "BPRE"
    int revision;       // ROM header 0xBC
    const char* title;  // "Pokémon FireRed"
    const struct pd_config* cfg; // NULL = detected but not supported
    const char* unsupported;     // why, when cfg is NULL
    // The ROM's bytes (0x08000000 on the bus), for the tabs that read its
    // tables and art; the host sets them once the ROM is loaded (NULL = none).
    const uint8_t* rom;
    size_t romSize;
    unsigned loadId; // new on every pd_game_detect (see pd_rom_id)
    const struct pd_table_set* tables; // the game's own names; NULL = the retail English ones
    const struct pd_game_entry* entry; // what the CRC found; NULL = the ROM header's guess
    const struct pd_gb_config* gb;     // a Game Boy game's addresses; NULL on the GBA
    char language;
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

#endif
