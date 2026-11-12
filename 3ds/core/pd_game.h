// Which game is running and where its RAM globals live - the C twin of the
// app's NativeConfig (app/.../companion/data/NativeReader.kt). Only the
// fields this port reads so far; the values are generated from gamedata/,
// like the app's (pd_configs_gen.h, from gamedata/targets/3ds.toml).
#ifndef PD_GAME_H
#define PD_GAME_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "pd_tables.h"

enum pd_game_kind {
    PD_GAME_NONE = 0,
    PD_GAME_FIRERED,  // FireRed and LeafGreen (LeafGreen runs as FireRed, like the app)
    PD_GAME_EMERALD,
    PD_GAME_GB,       // Gen 1 / Gen 2 (cfg->gb)
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
    PD_GUIDE_ROCKET_EDITION,
    PD_GUIDE_ASH_GRAY,
    PD_GUIDE_GS_CHRONICLES,
    PD_GUIDE_RADICAL_RED,
    PD_GUIDE_ORANGE_ISLANDS,
    PD_GUIDE_ODYSSEY,
    PD_GUIDE_SWORD_SHIELD,
    PD_GUIDE_UNBOUND,
    PD_GUIDE_GAIA,
    PD_GUIDE_GHOST_GREY,
    PD_GUIDE_CELIA,
    PD_GUIDE_IMPERIUM,
    PD_GUIDE_RECHARGED_YELLOW,
    PD_GUIDE_RECHARGED_EMERALD,
    PD_GUIDE_GB_RED,
    PD_GUIDE_GB_BLUE,
    PD_GUIDE_GB_YELLOW,
    PD_GUIDE_AMETHYST,
    PD_GUIDE_SOULGOLD,
    PD_GUIDE_QUETZAL_V084,
    PD_GUIDE_QUETZAL,
    PD_GUIDE_TMT2,
    PD_GUIDE_SEAGLASS,
    PD_GUIDE_LAZARUS,
    PD_GUIDE_HEART_AND_SOUL,
    PD_GUIDE_GLAZED,
    PD_GUIDE_MARIOMON,
    PD_GUIDE_DREAMSTONE,
    PD_GUIDE_WISHES,
    PD_GUIDE_TEAM_ROCKET,
    PD_GUIDE_ELITE_REDUX,
    PD_GUIDE_ULTRA_VIOLET,
    PD_GUIDE_AMETHYST_V141,
    PD_GUIDE_RECHARGED_YELLOW_V180,
    PD_GUIDE_RECHARGED_YELLOW_V190,
    PD_GUIDE_RECHARGED_YELLOW_V194,
    PD_GUIDE_EMERALD_KAIZO,
    PD_GUIDE_GB_CRYSTAL,
    PD_GUIDE_LETS_GO,
    PD_GUIDE_MONOMYTH,
    PD_GUIDE_GB_POLISHED,
    PD_GUIDE_GB_YELLOW_LEGACY,
    PD_GUIDE_GB_CRYSTAL_LEGACY,
};

// struct Pokemon's party fields (the app's PartyMonLayout): the party-only fields after the
// BoxPokemon, and where a plaintext (unencrypted) box keeps its substructs. A config without
// one (partyLayout NULL) is retail's: pd_party_layout_vanilla.
#define PD_BAG_POCKETS_MAX 12
struct pd_party_layout {
    int size;               // the whole struct (the party's stride)
    int status, level, hp, maxHp; // maxHp is followed by the five other stats
    int flags;              // the box's flags byte (isEgg: eggMask)
    int eggMask;
    int plainBox;           // a plaintext box's growth substruct (attacks +0xC, EVs +0x18, misc +0x28)
    unsigned plainSpeciesMask, plainItemMask;
    bool plainExp;          // a plaintext box's experience (growth +4) is read
    int hpBits;             // HP's bits in the u16 at hp (16 = all)
    int statusShift;
    uint32_t statusMask;
    bool eggInIvWord;       // the egg flag is the IV word's bit 30
    bool hasStats;          // IVs / EVs / nature / stats come out right from these offsets
    bool natureModifier;    // expansion's Mints: +0x12's bits above the language are XORed into the nature
    int nicknameOff, nicknameLen;
    bool rowe;              // R.O.W.E.'s bit-packed struct (no substructs, no IVs, no held item)
    bool eliteRedux;        // Elite Redux's bit-packed struct (no substructs; IVs all 31)
    bool monomythKey;       // Monomyth: the substructs are XORed with a key of its own (MonomythKey), its natures its own
    bool packedAttacks;     // a plaintext box's attacks are bit fields (SoulGold v1.2.1): 11-bit moves at bits 0, 16, 32, 43,
                            // the PP four 7-bit fields of the u32 after them (Gen3Mon.kt plainAttacks)
    // Shininess, for the hacks that mark it (the app's shinyBelow / shinyModifier / shinyBits): shiny when the
    // personality's shiny value is below shinyBelow, flipped by bit 6 of the byte at shinyModifier; or, when
    // shinyBitsMask is set, when that byte at shinyBits has a mask bit. All 0 = not read.
    int shinyBelow, shinyModifier, shinyBits;
    unsigned shinyBitsMask;
};
extern const struct pd_party_layout pd_party_layout_vanilla, pd_party_layout_quetzal, pd_party_layout_soulgold, pd_party_layout_soulgold_v121,
    pd_party_layout_team_rocket, pd_party_layout_rowe, pd_party_layout_rogue, pd_party_layout_elite_redux,
    pd_party_layout_monomyth;

// USE on a Pokémon, GIVE and TAKE (the app's PartyItemFns, gamedata's PARTY_ITEMS_* records).
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

// How a game keeps its PC boxes (the app's PcStorageLayout): format (enum pd_box_format, pd_boxes.h), boxes, a record's
// size, whether pokemonStoragePtr is the storage itself; CFRU's compressed boxes: where they live, the moves table that
// gives their PP, and boxes 20-25 elsewhere in EWRAM.
struct pd_pc_storage {
    int format, boxes, record;
    bool direct;
    uint32_t cfruStorage, cfruMoves;
    uint32_t cfruExtra[6];
};
extern const struct pd_pc_storage pd_pc_vanilla, pd_pc_static, pd_pc_plain, pd_pc_soulgold, pd_pc_unbound, pd_pc_radical_red,
    pd_pc_amethyst, pd_pc_amethyst_v141, pd_pc_sword_shield, pd_pc_gs_chronicles;

// struct BattlePokemon's field offsets (the app's BattleMonLayout); a config without one is retail's.
struct pd_battle_layout {
    int size, species, moves, type1, type2, pp, hp, level, maxHp, status;
    unsigned speciesMask;
    int personality, otId; // u32s for the SHINY marks; 0 = not mapped
    // FOE DETAILS (BattleMonLayout's ability / item / stats / statStages): the ability a byte or a u16, the held item, the
    // five stats (u16: Attack, Defense, Speed, Sp. Atk, Sp. Def), statStages[8] (6 = unchanged); facts false = not mapped.
    int ability, item, stats, stages;
    bool abilityU16, facts;
};
extern const struct pd_battle_layout pd_battle_layout_vanilla, pd_battle_layout_expansion, pd_battle_layout_tmt2,
    pd_battle_layout_team_rocket, pd_battle_layout_wishes, pd_battle_layout_rowe, pd_battle_layout_soulgold,
    pd_battle_layout_elite_redux, pd_battle_layout_monomyth;

struct pd_gb_config;
struct pd_game_entry;
struct pd_randomized;

enum { PD_DEX_SB1 = 1, PD_DEX_SB2, PD_DEX_FIXED };

// gBaseStats / gSpeciesInfo's shape (the app's BaseStatsLayout): what the dex page reads of it.
struct pd_base_stats {
    int stride, abilitiesOff, typesOff;
    bool abilityU16, statsU16; // ability ids are u16; base stats are u16
};

// An expansion game's gSpeciesInfo page (the app's SpeciesInfoDex): the offsets inside one entry; -1 = none.
struct pd_dex_x {
    int stride, catchRateOff, genderOff, eggGroupsOff, abilitiesOff, categoryOff, natDexOff, descriptionOff, frontPicOff,
        paletteOff, footprintOff, levelUpOff, teachableOff, evolutionsOff;
};

// Item id's description pointer: base + (id - first) * stride + descOff, for ids first..first+count-1 (count 0 = no
// end); idOff = the record's u16 item id where it has one (checked on item 1), -1 = none; next = a second range.
struct pd_item_descs {
    uint32_t base;
    int stride, descOff, idOff, first, count;
    const struct pd_item_descs* next;
};
struct pd_wild_layout { int stride, land, water, rockSmash, fishing; };
struct pd_night_wild { uint32_t hourAddr; int dayFrom, dayTo, skipGroup, skipNum; };

struct pd_config {
    const struct pd_text* text; // the hack's own species / item / move names; NULL = retail's
    const struct pd_party_layout* partyLayout; // NULL = pd_party_layout_vanilla
    const struct pd_gb_config* gb; // a Game Boy game (pd_gb.c): the Gen 3 fields below are unused
    const struct pd_battle_layout* battleLayout; // NULL = pd_battle_layout_vanilla
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
    uint32_t maxMoney;         // the most the game lets a player hold (retail 999999)
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
    // An expansion game's icons live inside gSpeciesInfo / gItemsInfo entries: the strides of the mon icon pointers
    // and palette indices (masked), and whether the item palette is LZ77 (else raw).
    int monIconStride, monPalIdxStride, monPalIdxMask;
    bool itemPalCompressed;
    // Icon palettes per species (SoulGold's gSpeciesInfo: the shiny one 4 bytes on); 0 = the shared six. A game that marks
    // shiny Pokemon (its party layout's shinyBelow) recolours the shared ones from its battle palettes: the dex's
    // gMonPaletteTable (dexPalettes), else monPalettes (the app's IconTables.battlePalettes).
    uint32_t monIconPalettes, dexPalettes, monPalettes;
    // Where the ROM keeps the item descriptions (the app's ItemDescTable, RomItemText.kt); NULL = none in this game.
    const struct pd_item_descs* itemDescs;
    // The game's own egg species (0 = retail's 412) and how many species its tables hold (with the egg).
    int eggSpecies, speciesCount;
    // Bits an expansion game packs above the species / move / PP words (0xFFFF / 0xFFFF / 0xFF = none).
    int speciesMask, moveMask, ppMask;
    // gBagPockets: five {slots pointer, capacity} pairs, 8 bytes apart;
    // bagOrder[i] is the pocket (PD_POCKET_*) the game keeps at index i.
    uint32_t bagPockets;
    uint8_t bagOrder[PD_BAG_POCKETS_MAX];
    int bagCount; // how many pockets gBagPockets holds (a hack's: up to PD_BAG_POCKETS_MAX); folded into PD_POCKET_*
    // Bags of their own (0 / NULL = gBagPockets): Quetzal's BAG3 bit-stream (bag3Off / bag3TailOff in SaveBlock1, split at
    // bag3SplitAt, key items from bag3KeyBit) and Elite Redux's owned bits (itemFlagsCount bits at itemFlagsOff); each item's
    // pocket by id (itemPockets, PD_POCKET_*, itemPocketCount ids).
    uint32_t bag3Off, bag3TailOff, itemFlagsOff;
    int bag3SplitAt, bag3KeyBit, itemFlagsCount, itemPocketCount;
    const uint8_t* itemPockets;


    // The DEX's sections (DexDetails.kt), on vanilla's tables: gEvolutionTable, the TM bitfields, their moves, gMapGroups.
    uint32_t dexEvolutions, dexTmhm, dexTmhmMoves, dexMapGroups;
    int dexSpeciesCount;
    // The PC (NativeConfig.pcStorage, pokemonStoragePtr): NULL / 0 = not read.
    uint32_t pokemonStoragePtr;
    const struct pd_pc_storage* pcStorage;
    // USE on a Pokémon, GIVE and TAKE (the app's PartyItemFns); NULL = none.
    const struct pd_party_fns* partyFns;
    // Where things sit that a few games moved (NativeConfig): a party mon's stride (0 = the layout's size), SaveBlock1's
    // pos and SaveBlock2's playerGender, gMain's frame counter; mapsec ids as u16 (SoulGold).
    int monStride;
    uint32_t saveBlock1PosOff, playerGenderOff, frameCounterOff;
    bool mapSecWide;
    bool monIconPacked;       // Monomyth's own tile coder
    bool monIconRecord;       // Unbound's: an entry points at the tiles with their palette behind them (UnboundIconSource)
    // Emerald Rogue's own items: {tiles, palette} per entry from extraItemFirst on, extraItemCount of them.
    uint32_t extraItemIconTable;
    int extraItemFirst, extraItemCount, extraItemStride;

    // POKéDEX (the app's PokedexTables): ROM tables ...
    uint32_t dexEntries;       // gPokedexEntries, by national number
    uint32_t dexEntryStride;
    uint32_t speciesInfo;      // gBaseStats, 28 bytes per species
    uint32_t speciesToNational; // u16 per species from species 1
    uint32_t abilityNames;     // 13 bytes each
    uint32_t frontPics;        // {LZ77 pointer, size, tag} per species
    uint32_t palettes;         // {LZ77 pointer, tag} per species
    uint32_t regionalOrder;    // u16 national numbers; 0 = national 1..regionalCount
    int regionalOrderStride;   // 2, or 4 (u32 per entry)
    bool regionalOrderIsSpecies; // the entries are species ids, not national numbers
    // The dex page's layout where a game differs from retail's (the app's PokedexTables).
    uint32_t nationalToSpecies; // u16 species per national number from 0 (Celia); 0 = invert speciesToNational
    bool entriesBySpecies;     // gPokedexEntries is indexed by species, not national number
    int entryCategoryLen;      // bytes of category text at the start of an entry
    bool entryCategoryPtr;     // the entry starts with a pointer to its category text
    int entryHeightOff, entryDescOff, descriptionPage2Off; // -1 = one page
    int abilityNameLength, abilityNameStride; // 0 stride = the length
    bool abilityNamePtr;       // each ability record starts with a pointer to the name
    const struct pd_base_stats* baseStats;
    const struct pd_dex_x* expansion; // NULL = the vanilla-shaped tables above
    const char *probeCategory; // entry 1's category in this game's own language, and its height / weight
    int probeHeight, probeWeight, probeSpecies;
    int regionalCount;
    const char* regionName;
    // ... and the save: SaveBlock2's owned / seen flags, SaveBlock1's two
    // copies of seen (all must agree), the National Dex byte.
    uint32_t dexSeenCopy1, dexSeenCopy2; // in SaveBlock1
    // The flag arrays (the app's DexFlags): No. n is bit n - dexFirstBit of two dexBytes-long arrays.
    // dexBlock 0 = not read here (the schema's required field, so 0 only for a bare config).
    uint8_t dexBlock; // PD_DEX_SB1 / PD_DEX_SB2 / PD_DEX_FIXED (offsets then absolute)
    uint8_t dexFirstBit;
    bool dexSeenLevels, dexBySpecies, dexSeenOrCaught; // Quetzal's 3-bit levels / Emerald Rogue's species-indexed bits
    uint16_t dexBytes, nationalCount;
    uint32_t dexSeenOff, dexCaughtOff;
    uint32_t dexCaughtRegion1, dexCaughtRegion2, dexCaughtRegion3; // Quetzal: caught is one bit array per region (dexBytes each), any of them counts
    bool dexCaughtFromLevel;     // Quetzal Alpha 0.8.4: no caught arrays - a seen level of 2 or more is caught
    int8_t nationalMagicOff; // SaveBlock2 + 0x18 + off == nationalMagic; -1 = the National Dex is always there
    uint8_t nationalMagic;

    // GUIDE (the app's GuideTables): ROM tables and SaveBlock1 offsets.
    uint32_t trainers;    // gTrainers, 0x28 bytes each
    uint32_t learnsets;   // gLevelUpLearnsets, a pointer per species
    bool learnsetCfru;    // ... as {u16 move, u8 level} entries ending at level 0xFF (CFRU: Radical Red, Unbound, Amethyst)
    bool learnsetPairs;   // ... as {u16 move, u16 level} entries ending at move 0xFFFF
    uint32_t wildHeaders; // gWildMonHeaders, 20 bytes each
    uint32_t flagsOff, varsOff, flagBytes; // in SaveBlock1
    // A hack's own struct TrainerMon (GuideTables.trainerMon), every field at a fixed offset; stride 0 = retail's.
    int trainerMonStride, trainerMonMovesOff, trainerMonSpeciesOff, trainerMonItemOff, trainerMonLevelOff;
    // struct Trainer's shape (GuideTables.trainerStride ...): size, where the name / party size byte / party pointer sit.
    // trainerHardPartyOff >= 0: a second party pointer in the record (size in the size byte's high nibble).
    // altTrainers: more tables a trainer id picks with (k + 1) << 16 (0 = none); an empty record falls back to `trainers`.
    int trainerStride, trainerNameOff, trainerNameLen, trainerSizeOff, trainerPartyOff, trainerHardPartyOff;
    uint32_t altTrainers[3];
    int probeTrainer;     // checks the tables match the ROM ...
    const char* probeName; // ... by this trainer's name
    bool trainerNamePtr;   // the record holds a pointer to the name, not the name (Elite Redux)
    // HERE's wild headers when they aren't vanilla's 20 bytes (the app's GuideTables): wildSets time-of-day sets of 4
    // pointers + a word each (wildSet = the one read first), or a header of its own shape (wildLayout); a map's
    // back-to-back headers picked by a var (wildSetVars: {group << 8 | num, var} pairs, ~0u-terminated) or by the clock
    // (nightWild).
    int wildSets, wildSet;
    const struct pd_wild_layout* wildLayout;
    const uint32_t* wildSetVars;
    const struct pd_night_wild* nightWild;
    // Flags / vars past the main arrays, elsewhere in SaveBlock1 (Quetzal's 0x1000+ flags, 0x5000+ vars); count 0 = none.
    struct pd_save_bank { uint32_t firstId, sb1Off, count; } flagBank, varBank;

    // TRAINER CARD (the app's TrainerCardSave): which card (PD_CARD_*, 0 =
    // none), SaveBlock1.gameStats, the badge / POKéDEX flags, what makes the
    // National Dex count (with nationalMagic above), FireRed's sticker vars and
    // Emerald's extra stars (Battle Frontier symbols, the museum's paintings)
    // and battle points. 0 = not in this game.
    int cardStyle;
    uint32_t gameStatsOff;
    bool nationalFlagOnly, linkLines; // the National Dex on with nationalFlag alone; FireRed's UNION ROOM / BERRY CRUSH lines
    int allRegionalDrop;              // regional entries the "all caught" star leaves out at the end
    int iconVar, tintVar;             // Recharged Yellow's back: six icons from vars iconVar.., tinted by tintVar
    uint32_t cardIconTable, cardPaletteTable;
    uint32_t achievementBitsOff, achievementMagicOff; // in SaveBlock1; 0 = none
    int achievementsNeeded;           // achievements 0..n-1 set for the star (Recharged Yellow: all 37)
    uint32_t cardFlagsOff, cardVarsOff; // where the card reads its flags and vars (the guide's can differ)
    int cardFlagBytes;
    uint32_t cardMagicOff; // SaveBlock2 + this == nationalMagic, as the card reads it (readTrainerCard)
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
    // FLASH from the QUICK MENU (Flash.kt's FlashFns, the config's FLASH record): FlagGet, the field move's badge flag and the
    // cave's lit flag, SetUpFieldMove_Flash, the two callbacks it sets (put back after), the post-menu callbacks it may set,
    // gPartyMenu.slotId, gMapHeader (its cave byte at flashCaveOff), CRC32 of FlagGet / SetUp / each callback's first 32
    // bytes, and the party menu's badge check with its 16 bytes' CRC32 (0 = not read). flashFlagGet 0 = no FLASH.
    uint32_t flashFlagGet, flashSetUp, flashMenuCb, flashPostCb, flashSlot, flashMapHeader, flashBadgeCheck, flashBadgeCheckCrc;
    int flashBadgeFlag, flashLitFlag, flashCaveOff;
    uint32_t flashCallbacks[2], flashCrcs[4];
    // FREE FLIGHT and the scenic FLY (Flight.kt's FlightFns, the field-item record's flight): pk_flight.h's words, plus
    // what FLIGHT AREA and the scenic route read (gMapGroups with each group's size, sHealLocations, SaveBlock1's pos).
    // flightCrcs: CRC32 of each called function's first 32 bytes (FlightFns.functions' order). flightObjectEvents 0 = none.
    // flightClassicFly false: never the game's own FLY warp (Emerald Kaizo), the scenic FLY only.
    uint32_t flightObjectEvents, flightPlayerAvatar, flightSprites, flightVmap, flightConnectionFlags, flightSaveBlock1Ptr;
    uint32_t flightSaveBlock2Ptr, flightMain, flightCb2Overworld, flightLockByte, flightPaletteFade, flightLockControls;
    uint32_t flightUnlockControls, flightCreateSprite, flightDestroySprite, flightStartSpriteAnim, flightBirdTemplate;
    uint32_t flightBehaviorAt, flightCollisionAt, flightElevationAt, flightObjectAt, flightPlaySe, flightSpriteOffset;
    uint32_t flightMapHeader, flightDrawMetatileAt, flightMapGroups, flightHealLocations, flightRiderTemplate, flightRiderGfx;
    uint32_t flightSpriteDummy, flightScratch, flightDrawView, flightSpriteOffsetY, flightMapGroupsXor, flightBirdPalette;
    uint32_t flightFollowerRemove, flightFollowerUpdate;
    int flightSeFly, flightSeFailure, flightWalkFast, flightWalkFaster, flightWalkCurrent, flightWalkSlide;
    int flightHealLocationSize, flightHealLocationCount, flightRiderAnim, flightBirdPaletteTag, flightObjSpriteId;
    int flightSaveBlock1PosOff, flightMapGridUndefined;
    int flightLandOk[32], flightMapGroupSizes[64], flightNoFly[4];
    uint32_t flightCrcs[17];
    bool flightSaveBlockStatic, flightClassicFly;

    // USE on a TM / HM (TmTeach.kt's TmFns, the config's TM record): gSpecialVar_ItemId, gItemUseCB and ItemUseCB_TMHM,
    // CB2_ShowPartyMenuForItemUse, gPartyMenu (its cursor at tmPartyMenuSlotOff), CB2_InitPartyMenu, gMain.callback2,
    // SetMainCallback2, CB2_ReturnToField, CleanupOverworldWindowsAndTilemaps, FadeScreen, Lock / UnlockPlayerFieldControls,
    // CRC32 of the functions' first 32 bytes (ItemUseCB_TMHM ... UnlockPlayerFieldControls) and the TM / HM item ids.
    // tmShowParty 0 = none.
    uint32_t tmItemId, tmItemUseCb, tmItemUseCbTmhm, tmShowParty, tmPartyMenu, tmInitPartyMenu, tmMainCallback2;
    uint32_t tmSetMainCallback2, tmReturnToField, tmCleanup, tmFade, tmLock, tmUnlock;
    int tmPartyMenuSlotOff, tmFirst, tmLast;
    uint32_t tmCrcs[9];

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

// GLAZED: Emerald's card with its own badges, pics, font and regional dex.
// SOULGOLD: Emerald's card code with Johto art (smol-compressed) and its own stars.
// RECHARGED_YELLOW: FireRed's card under code of its own, icons on the back; _V1_9 has a lighter red.
// LAZARUS: Emerald's card code with its own art, dex (430 regional) and star rules.
// UNBOUND: FireRed's card in Unbound's purple, with its own player pics.
// LEGACY: Emerald Legacy's - HOENN's card, a fifth star (extraStarFlag) on a black one, Hoenn's dex and eight more.
enum pd_card_style { PD_CARD_NONE, PD_CARD_KANTO, PD_CARD_HOENN, PD_CARD_LEGACY, PD_CARD_UNBOUND, PD_CARD_LAZARUS,
                      PD_CARD_RECHARGED_YELLOW, PD_CARD_RECHARGED_YELLOW_V1_9, PD_CARD_SOULGOLD, PD_CARD_GLAZED };


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
    enum pd_guide_id guide;
    const struct pd_config* cfg; // NULL = detected but not supported
    const char* unsupported;     // why, when cfg is NULL
    // The ROM's bytes (0x08000000 on the bus), for the tabs that read its
    // tables and art; the host sets them once the ROM is loaded (NULL = none).
    const uint8_t* rom;
    size_t romSize;
    unsigned loadId; // new on every pd_game_detect (see pd_rom_id)
    char sha1[41];   // the whole ROM's, once pd_game_identify ran ("" before)
    // The game's shiny check (the app's NativeConfig.shinyBelow, set by hash): shiny when the shiny value is below it;
    // 0 = the party layout's own rule, if any.
    int shinyBelow;
    // English FireRed / Emerald proven retail: its hash, a collection's dump of it or a randomizer's copy (the app's
    // retailRom, which FREE FLIGHT goes by).
    bool retail;
    // A Game Boy game: no map-section names yet (the GBA hacks' come from pd_text.mapsecs / the ROM).
    bool hackMaps;
    // What the app shows of this ROM beyond its config (pd_games_gen.c, by SHA-1): its look, its PC's moves; NULL = none.
    const struct pd_game_entry* entry;
    const struct pd_randomized* randomized; // retail as a randomizer left it (pd_randomized.c), NULL = not
    char language; // the ROM header's language letter ('E', 'J', ...)
};

// The ROMs whose shiny check is known (pd_shiny_gen.c, from ShinyOdds.kt), NULL-terminated.
struct pd_shiny_rom { const char* sha1; int below; };
extern const struct pd_shiny_rom pd_shiny_roms[];

// Which ROM the tabs' caches were built from: 0 = none, else new for every
// game loaded. Not the ROM pointer - mGBA's 3DS build loads every game into
// the same fixed buffer.
unsigned pd_rom_id(const struct pd_game* g);

// Reads from the ROM at a bus address (0x08xxxxxx); false if out of range.
bool pd_rom_read(const struct pd_game* g, uint32_t addr, void* out, size_t len);
// A pointer into the ROM at a bus address, with at least len bytes after it.
const uint8_t* pd_rom_at(const struct pd_game* g, uint32_t addr, size_t len);

// Reads the ROM header (the first 0xC0 bytes of the ROM, no more) and its size. A Game Boy cart is told apart in
// pd_game_identify, which has the whole ROM.
void pd_game_detect(struct pd_game* g, const uint8_t* header, size_t romSize);

// Once g->rom is set: which FireRed / LeafGreen / Emerald file this is, by its SHA-1, as the app's Poller.detect
// tells them apart - a hack the companion knows gets its own config, a retail cart (or a collection's dump of one,
// the app's RetailDumps) keeps detect's, and anything else is an unknown hack: unsupported rather than read with
// retail's addresses.
void pd_game_identify(struct pd_game* g);

#endif
