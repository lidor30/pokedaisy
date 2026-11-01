package com.pokedaisy.app.companion.data

// The NativeConfigs as NativeReader.kt wrote them before gamedata/ (phase 1), renamed
// LEGACY_NATIVE_*: GameDataMigrationTest checks the generated NativeConfigsGen.kt equals
// them. Written by scripts/convert_native_configs.py; goes once that gate has passed.

// Retail FireRed 1.0 (BPRE rev 0); CFRU/Unbound keeps these. rev 1 uses the
// IDENTICAL gMain/gSaveBlock1Ptr/gSaveBlock2Ptr — see LEGACY_NATIVE_FIRERED_REV1.
val LEGACY_NATIVE_FIRERED_REV0 = NativeConfig(
    monPalettes = 0x0823730CL,
    // gItems, the struct Item array (RomItemText.kt); every config below has its own.
    itemDescs = vanillaItems(0x083DB028L),
    playerParty = 0x02024284L,
    playerPartyCount = 0x02024029L,
    battleMons = 0x02023BE4L,
    battlerPositions = 0x02023BD6L,
    battlersCount = 0x02023BCCL,
    battleTypeFlags = 0x02022B4CL,
    gMain = 0x030030F0L,
    saveBlock1Ptr = 0x03005008L,
    saveBlock2Ptr = 0x0300500CL,
    objectEvents = 0x02036E38L,
    mapHeader = 0x02036DFCL,
    bagPockets = 0x0203988CL,
    bagPocketCount = 5,
    encryptionKeyOff = 0xF20L,
    moneyOff = 0x290L,
    // From a vanilla `make firered` (rev 0) build (2026-09-18) - NOT
    // cross-checked against a retail rev 0 ROM hash (none on file), unlike
    // rev 1 below. gBattlerControllerFuncs matches rev 1 exactly (same
    // pattern as gMain/saveBlock*Ptr above); HandleInputChooseAction/Move
    // shift slightly between revisions like the rest of ROM code does.
    battlerControllerFuncs = 0x03004fe0L,
    handleInputChooseAction = 0x0802e438L,
    handleInputChooseMove = 0x0802ea10L,
    completeWhenChoseItem = 0x0803073cL,
    waitForMonSelection = 0x08030684L,
    handleInputChooseTarget = 0x0802e674L,
    // gMonIconTable/gMonIconPaletteIndices/gMonIconPaletteTable/sItemIconTable,
    // from the same rev-0 build (arm-none-eabi-nm on the built ELF) - these
    // were missing entirely until now, meaning DecompIconSource never had an
    // address to work with for retail-rev0 native reads (Gaia inherits this
    // config below and was hit by the same gap) since the bundled sprite PNGs
    // this used to fall back to were dropped once every OTHER path could
    // decode live from the ROM (see AssetImages.kt's comment).
    monIconTable = 0x083D37A0L,
    monIconPaletteIndices = 0x083D3E80L,
    monIconPaletteTable = 0x083D4038L,
    itemIconTable = 0x083D4294L, // sItemIconTable (static, but readable)
)

// A prior version of this config guessed rev 1 shifted gMain/saveBlock1Ptr/
// saveBlock2Ptr to 0x03003100/0x03005018/0x0300501C — WRONG, never live-
// verified (see CLAUDE.md). Confirmed 2026-09-15 by building the pinned
// pokefirered commit's `firered_rev1` target unpatched and checking it's
// byte-identical to retail (sha1 dd5945db… matches) before reading its own
// pokefirered_rev1.map: gMain/gSaveBlock1Ptr/gSaveBlock2Ptr — and every other
// field LEGACY_NATIVE_FIRERED_REV0 declares — are IDENTICAL between rev 0 and rev 1.
// (Only the icon-table ROM addresses below are genuinely rev-specific, since
// they were already sourced from that same real rev1 build.) The wrong
// pointers made gSaveBlock2Ptr dereference to a bogus heap address, so every
// bag item quantity came out as raw (still-XOR-obfuscated) noise, and
// gMain+0x439 (inBattle) read garbage too — see the item/battle bugs in
// PokeDaisy's memory notes.
val LEGACY_NATIVE_FIRERED_REV1 = LEGACY_NATIVE_FIRERED_REV0.copy(
    itemDescs = vanillaItems(0x083DB098L),
    monIconTable = 0x083D3810L,
    monIconPaletteIndices = 0x083D3EF0L,
    monIconPaletteTable = 0x083D40A8L,
    itemIconTable = 0x083D4304L, // sItemIconTable (static, but readable)
    // From the same verified-byte-identical-to-retail `firered_rev1` build
    // (sha1 dd5945db… - see CLAUDE.md) as the icon addresses above.
    // gBattlerControllerFuncs is identical to rev 0's; the two function
    // addresses differ slightly (revision-conditional code shifts things).
    handleInputChooseAction = 0x0802e44cL,
    handleInputChooseMove = 0x0802ea24L,
    // Between gBattleStruct and gEnemyParty, as in every build of battle_main.c.
    // Headless on retail: back-to-back presses all land, A right after.
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
    completeWhenChoseItem = 0x08030750L,
    waitForMonSelection = 0x08030698L,
    handleInputChooseTarget = 0x0802e688L,
    pokedex = POKEDEX_FIRERED_REV1,
    guideTables = GUIDE_TABLES_FIRERED_REV1,
    // Right before gPlayerParty (6 x 100 bytes), per the decomp's map. The
    // two below sit between gBattleMons and gEnemyParty, both confirmed
    // unchanged from the decomp's map, as are gBattlersCount / Positions.
    enemyParty = 0x0202402CL,
    battlerPartyIndexes = 0x02023BCEL,
    battleStructPtr = 0x02023FE8L,
    monToSwitchIntoOff = 0x5C, // include/battle.h struct BattleStruct (field_78 lands at 0x78)
    trainerCard = TRAINER_CARD_FIRERED,
)

// CFRU is on rev 0; icons via UnboundIconSource. Tier C (PLAN.md)
// - battle input control - inherits ALL FIVE battle-input fields from rev 0
// unchanged (no override), verified 2026-09-18 by directly comparing raw ROM
// bytes between a genuinely vanilla `make firered` build (sha1 41cb23d8… -
// see CLAUDE.md) and the user's own Unbound v2.1.1.1 ROM (sha1 b4776b82…),
// NOT by guessing:
//   - WaitForMonSelection (0x08030684) and CompleteWhenChoseItem (0x0803073c):
//     the full function bodies are byte-for-byte IDENTICAL at the same
//     address in both ROMs.
//   - HandleInputChooseAction/HandleInputChooseMove themselves DO differ
//     (CFRU visibly redesigns the move-select screen) - but the glue
//     functions that store their addresses into gBattlerControllerFuncs[]
//     (HandleChooseActionAfterDma3 @ 0x08032b94, HandleChooseMoveAfterDma3
//     @ 0x08032c4c) are themselves byte-identical AND store the exact same
//     target addresses (0x0802e439/0x0802ea11, Thumb-bit set) in both ROMs.
//     So CFRU patched these two functions IN PLACE rather than relocating
//     them - the stored pointer value our scan matches against is unchanged
//     even though what runs at that address differs.
//   - gBattlerControllerFuncs's own address (0x03004fe0) is embedded as a
//     literal in that same byte-identical glue-function range, so it's
//     directly confirmed too, not just inferred from the RAM-globals pattern.
// This is why CFRU is documented to keep so many stock rev-0 RAM addresses
// too (see unbound-telemetry memory) - it's an ASM-hook-based patch on the
// retail binary, not a full rebuild-with-insertions, so untouched regions
// stay exactly where they were. Static-analysis-verified, NOT yet confirmed
// live in an actual Unbound battle - unlike Tiers A/B, both of which shipped
// with an equally-reasoned design and still had a real bug only real play
// caught (see gactivebattler-loop-var-gotcha, arm-thumb-bit-nm-gotcha).
val LEGACY_NATIVE_UNBOUND = LEGACY_NATIVE_FIRERED_REV0.copy(
    // The battle menus' cursors are FireRed's (CFRU keeps its EWRAM), checked headless on the user's save
    // (unbound_battle's README): RIGHT / DOWN / LEFT / UP walk the action cursor 0 -> 1 -> 3 -> 2 -> 0, and a cell
    // written before A is what the game takes (2 opens the party menu, a move cell spends that move's PP).
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
)

// Unbound's own POKéDEX tables (SHA1-pinned, see POKEDEX_UNBOUND) - kept off
// LEGACY_NATIVE_UNBOUND itself, which the other CFRU configs reuse as LEGACY_NATIVE_FIRERED_REV0.
val LEGACY_NATIVE_UNBOUND_WITH_DEX = LEGACY_NATIVE_UNBOUND.copy(
    // CFRU repointed gItems (FireRed 1.0's literal pools load it from here).
    itemDescs = vanillaItems(0x08876200L),
    pokedex = POKEDEX_UNBOUND, guideTables = GUIDE_TABLES_UNBOUND, trainerCard = TRAINER_CARD_UNBOUND,
    pokemonStoragePtr = 0x03005010L, pcStorage = UNBOUND_PC_STORAGE,
)

// Pokémon Unbound v2.1.1.1 FR - a French fan translation of the same release (32 MB, sha1
// 0ce2a880…, its header's revision byte left at 0x9E). Only text changed: every literal pool
// English's code loads a RAM global or table from holds the same word, and the headless
// capture of the user's save (`unbound_fr`: LARVITAR / SNORUNT / DELIBIRD on Route 1) decodes
// with English's config. Names in French (UNBOUND_FR_TEXT, gen_unbound_fr_tables.py); gItems,
// the dex entries and the map names were translated in place, so the item descriptions, the
// POKéDEX page and the MAP (RomRegionMap) read French from the ROM by themselves.
val LEGACY_NATIVE_UNBOUND_FR = LEGACY_NATIVE_UNBOUND_WITH_DEX.copy(
    language = 'F',
    gameCode = UNBOUND_FR_TEXT,
    pokedex = POKEDEX_UNBOUND_FR,
    guideTables = GUIDE_TABLES_UNBOUND_FR,
)

// Pokémon Gaia v3.2 - another large (32 MB) BPRE hack, verified 2026-09-20 the
// same way as Unbound above: static byte comparison against the same vanilla
// rev-0 build, against the user's own Gaia v3.2 ROM (sha1 d5b1e779…, see
// GAIA_V3_2_SHA1 in Poller.kt). Ten separate checks, all confirmed identical:
// gPlayerParty (0x02024284, via a byte-identical ZeroPlayerPartyMons),
// gSaveBlock1Ptr/gSaveBlock2Ptr (0x03005008/0x0300500C - SetSaveBlocksPointers'
// own body differs slightly, likely just the ASLR-offset mask, but its
// literal pool stores the exact same six addresses), gMain (0x030030F0, via a
// byte-identical ReadKeys), and the full battle-input chain
// (gBattlerControllerFuncs/HandleInputChooseAction/HandleInputChooseMove/
// WaitForMonSelection/CompleteWhenChoseItem/HandleInputChooseTarget - same
// pattern as Unbound's, in-place-patched functions with unchanged stored
// addresses). The remaining NativeConfig fields (battleMons, bagPockets, etc)
// weren't individually checked, but ten-for-ten on this varied a sample is
// strong evidence Gaia is ASM-patch-based like Unbound/CFRU, not a full
// decomp rebuild (confirmed there's no public Gaia source - the only
// candidate, sphericalice/pokefirered on GitHub, turned out to be an
// unmodified, 228-commits-stale mirror of upstream pret/pokefirered, not
// Gaia's real tree). Known gap: Gaia has a brand-new region, so
// regionMapSectionId will resolve through the shared Kanto mapsec table and
// likely show wrong/"Unknown area" location names outside anything reused
// from Kanto - same class of gap Unbound had before its own Hoenn-equivalent
// table was worth adding, not attempted here yet.
//
// Icons (2026-09-20): sItemIconTable (inherited from rev 0 above, 0x083D4294)
// is STILL AT ITS VANILLA ADDRESS and its entries decode to the correct
// sprites (checked Master Ball/Potion/Ultra Ball) - Gaia never needed to grow
// this table's slot count. gMonIconTable, unlike the item table, DID need to
// grow (~930 species vs vanilla's ~412) and move - the inherited rev-0
// address's entries are all zeroed in Gaia's ROM, and a whole-ROM search for
// Bulbasaur's exact vanilla 1024-byte icon blob found no match, so there was
// no byte-content anchor the way gSpeciesNames' text strings gave one.
//
// Found it anyway, via the CODE instead of the data: pret's GetMonIconTiles
// (0x08097028), GetValidMonIconPalettePtr (0x080971cc) and
// GetValidMonIconPalIndex (0x080971f8) are still at their exact vanilla
// addresses in Gaia's ROM, byte-identical apart from (a) one patched immediate
// (the SPECIES_DEOXYS form-swap check's constant 205->255, since Gaia inserted
// ~100 species before it) and (b) their trailing literal-pool words, which
// naturally now hold Gaia's relocated table addresses instead of vanilla's -
// read those words directly and got monIconTable/monIconPaletteIndices/
// monIconPaletteTable below. Verified by decoding: species 1/4/25/150 (all
// redrawn-or-not Kanto starters+Pikachu+Mewtwo), 412/413-438 (Egg/Unown set,
// same run of shapes as vanilla), and 444 (Chimchar, in Gaia's Sinnoh block
// right after Unown) all render as the correct, recognizable sprite. Bulbasaur
// (species 1) confirmed genuinely redrawn as a regional variant (different
// colors/shape) - not a decode bug, Gaia just gave it new art while reusing
// vanilla's tile data unchanged for most other species (Charmander, Pikachu,
// Mewtwo, the Sinnoh block, etc. all matched vanilla's OWN tile addresses
// exactly). IconTables.monPresent/itemPresent (Telemetry.kt) stay gated
// independently since a future hack could easily have this asymmetry the
// other way around.
val LEGACY_NATIVE_GAIA_V3_2 = LEGACY_NATIVE_FIRERED_REV0.copy(
    itemDescs = vanillaItems(0x083DB028L), // retail's address, its own text
    monIconTable = 0x0872A86CL,
    monIconPaletteIndices = 0x0872B870L,
    monIconPaletteTable = 0x0823C500L,
    pokedex = POKEDEX_GAIA,
    guideTables = GUIDE_TABLES_GAIA,
    // FireRed's battle menu cursors, checked headless like Unbound's (gaia_battle's README).
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
)

// Pokémon Radical Red v4.1 - a 32 MB BPRE hack like Gaia/Unbound above, but
// LESS verified: static byte comparison against the same vanilla rev-0 build
// (2026-09-20) was mixed, unlike Gaia's clean ten-for-ten. Identical:
// ZeroPlayerPartyMons (0x0803da14 - so gPlayerParty stays 0x02024284),
// CompleteWhenChoseItem/WaitForMonSelection/HandleInputChooseTarget (bag/
// party-open detection and the target-select screen). Genuinely DIFFERENT
// (not just a patched constant, real rewritten code from the first byte):
// ReadKeys and SetSaveBlocksPointers - both suggests gMain and/or
// gSaveBlock1Ptr/gSaveBlock2Ptr may have shifted, unlike every other hack
// checked so far. HandleInputChooseAction/HandleInputChooseMove also differ
// (expected - Radical Red is well known for a heavily reworked battle UI,
// same as CFRU/Unbound), so touch battle control (hasBattleInputAddrs) is
// left unverified here even though gBattlerControllerFuncs's own address is
// probably still 0x03004fe0 (three of its five call sites matched byte-for-
// byte). Inheriting rev-0's gMain/saveBlock addresses unchanged for now
// (untested guess) rather than blocking basic party/item display on a full
// gMain/SaveBlock re-discovery - if HP/status/bag quantities come out as
// garbage, THAT'S the next thing to re-derive, the same way LEGACY_NATIVE_GAIA_V3_2
// was (find a still-identical function that loads the address, read its
// literal pool).
//
// Icons: CFRU repoints FireRed's literal pools, so the words retail rev 0 loads
// gMonIconTable / gMonIconPaletteIndices / sItemIconTable from (0x08097050,
// 0x080971F4, 0x0809899C) hold Radical Red's own tables; the palette table is
// still vanilla's. Inheriting rev 0's read species past 411 off the end of
// vanilla's tables - the broken party / bag sprites.
val LEGACY_NATIVE_RADICAL_RED_V4_1 = LEGACY_NATIVE_FIRERED_REV0.copy(
    // Repointed by CFRU; indexed by slot, as the game's ItemId_GetDescription does.
    itemDescs = vanillaItems(0x093C0000L),
    monIconTable = 0x097FE6CCL,
    monIconPaletteIndices = 0x097FE164L,
    itemIconTable = 0x093C8100L,
    pokedex = POKEDEX_RADICAL_RED,
    guideTables = GUIDE_TABLES_RADICAL_RED,
    // FireRed's battle menu cursors (CFRU's EWRAM), checked headless like Unbound's (radical_red_battle's README).
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
)

// Pokemon Odyssey (English) v4.1.1 - a 32 MB BPRE hack like the others above.
// UNTESTED GUESS, same starting point as Radical Red's first pass: inheriting
// rev-0's addresses unchanged rather than blocking basic display on a full
// static-analysis pass up front. Live-bus sha1 (Poller.kt's detect(), NOT a
// plain file hash - see RADICAL_RED_V4_1_SHA1's comment on why those can
// differ) happened to equal a plain `shasum` on the file this time
// (8745ddbdbfadf6abaf66de4e9055923b62eb4668 both ways) - so unlike Radical
// Red, no RTC/GPIO-in-ROM-space quirk here. If party/items/battle come out
// wrong live, re-derive the specific broken field the same way Gaia's icon
// tables were (find a still-byte-identical helper function, read its literal
// pool) rather than guessing again.
val LEGACY_NATIVE_ODYSSEY = LEGACY_NATIVE_FIRERED_REV0.copy(
    pokedex = POKEDEX_ODYSSEY, guideTables = GUIDE_TABLES_ODYSSEY, itemDescs = vanillaItems(0x083DB028L),
    // FireRed's battle menu cursors (CFRU), checked headless like Unbound's (odyssey_battle's README).
    actionCursor = 0x02023FF8L, moveCursor = 0x02023FFCL,
)

// Retail Emerald (BPEE). Same struct layouts as FireRed (gMain +0x439 inBattle,
// +0x24 vblank counter both hold); only the global addresses differ. From
// build/pokeemerald's linker map — see tools/telemetry-viewer/native.go's
// cfgEmerald. NOTE: encryptionKey is at SaveBlock2 + 0xAC in Emerald, not 0xF20.
// Location names still come from the FireRed (Kanto) mapsec table — a known gap.
val LEGACY_NATIVE_EMERALD = NativeConfig(
    itemDescs = vanillaItems(0x085839A0L),
    playerParty = 0x020244ECL,
    playerPartyCount = 0x020244E9L,
    battleMons = 0x02024084L,
    battlerPositions = 0x02024076L,
    battlersCount = 0x0202406CL,
    battleTypeFlags = 0x02022FECL,
    gMain = 0x030022C0L,
    saveBlock1Ptr = 0x03005D8CL,
    saveBlock2Ptr = 0x03005D90L,
    objectEvents = 0x02037350L,
    mapHeader = 0x02037318L,
    bagPockets = 0x02039DD8L,
    bagPocketCount = 5,
    encryptionKeyOff = 0xACL,
    moneyOff = 0x490L,
    bagPocketOrder = EMERALD_BAG_POCKET_ORDER,
    // From a vanilla pokeemerald ELF (ROM sha1 f3ae0881… == retail Emerald).
    monIconTable = 0x0857BCA8L,
    monIconPaletteIndices = 0x0857C388L,
    monIconPaletteTable = 0x0857C540L,
    itemIconTable = 0x08614410L,
    // From the same vanilla pokeemerald ELF (sha1 f3ae0881… == retail Emerald).
    battlerControllerFuncs = 0x03005d60L,
    handleInputChooseAction = 0x08057588L,
    handleInputChooseMove = 0x08057bfcL,
    completeWhenChoseItem = 0x080598e0L,
    waitForMonSelection = 0x08059828L,
    handleInputChooseTarget = 0x08057824L,
)

// Retail Emerald only: the hacks below start from LEGACY_NATIVE_EMERALD, and its
// Pokédex ROM addresses would be wrong for every one of them.
val LEGACY_NATIVE_EMERALD_RETAIL = LEGACY_NATIVE_EMERALD.copy(
    // From the unpatched (byte-identical) build's map; headless on retail: presses land back to back.
    actionCursor = 0x020244ACL,
    moveCursor = 0x020244B0L,
    trainerCard = TRAINER_CARD_EMERALD,
    pokedex = POKEDEX_EMERALD,
    guideTables = GUIDE_TABLES_EMERALD,
    // The FOE TEAM: from the same byte-identical-to-retail pokeemerald ELF;
    // monToSwitchIntoId sits at 0x5C in Emerald's struct BattleStruct too
    // (offsetof, compiled with agbcc).
    enemyParty = 0x02024744L,
    battlerPartyIndexes = 0x0202406EL,
    battleStructPtr = 0x0202449CL,
    monToSwitchIntoOff = 0x5C,
)

// The European Emerald releases (BPES / BPED / BPEF / BPEI): every RAM address
// is English's (literal pools matched in each ROM; English's save loads in all
// four with the same party bytes, money and position - headless, the
// emerald_es / _de / _fr / _it fixtures). The ROM data moved: icon tables, dex,
// guide tables. German and Italian's battle code sits 4 bytes later. The
// TRAINER CARD stays off: their cards are localized art, not matched yet.
val LEGACY_NATIVE_EMERALD_ES = LEGACY_NATIVE_EMERALD_RETAIL.copy(
    itemDescs = vanillaItems(0x0858639CL),
    language = 'S', gameCode = "BPES", trainerCard = null,
    monIconTable = 0x0857E784L, monIconPaletteIndices = 0x0857EE64L, monIconPaletteTable = 0x0857F01CL,
    itemIconTable = 0x08617250L,
    pokedex = POKEDEX_EMERALD_ES, guideTables = GUIDE_TABLES_EMERALD_ES,
)

val LEGACY_NATIVE_EMERALD_DE = LEGACY_NATIVE_EMERALD_RETAIL.copy(
    itemDescs = vanillaItems(0x085946DCL),
    language = 'D', gameCode = "BPED", trainerCard = null,
    monIconTable = 0x0858CAA8L, monIconPaletteIndices = 0x0858D188L, monIconPaletteTable = 0x0858D340L,
    itemIconTable = 0x086258D8L,
    handleInputChooseAction = 0x0805758CL, handleInputChooseMove = 0x08057C00L,
    completeWhenChoseItem = 0x080598E4L, waitForMonSelection = 0x0805982CL, handleInputChooseTarget = 0x08057828L,
    pokedex = POKEDEX_EMERALD_DE, guideTables = GUIDE_TABLES_EMERALD_DE,
)

val LEGACY_NATIVE_EMERALD_FR = LEGACY_NATIVE_EMERALD_RETAIL.copy(
    itemDescs = vanillaItems(0x08587D6CL),
    language = 'F', gameCode = "BPEF", trainerCard = null,
    monIconTable = 0x08580020L, monIconPaletteIndices = 0x08580700L, monIconPaletteTable = 0x085808B8L,
    itemIconTable = 0x08618798L,
    pokedex = POKEDEX_EMERALD_FR, guideTables = GUIDE_TABLES_EMERALD_FR,
)

val LEGACY_NATIVE_EMERALD_IT = LEGACY_NATIVE_EMERALD_RETAIL.copy(
    itemDescs = vanillaItems(0x0858000CL),
    language = 'I', gameCode = "BPEI", trainerCard = null,
    monIconTable = 0x0857838CL, monIconPaletteIndices = 0x08578A6CL, monIconPaletteTable = 0x08578C24L,
    itemIconTable = 0x08610FACL,
    handleInputChooseAction = 0x0805758CL, handleInputChooseMove = 0x08057C00L,
    completeWhenChoseItem = 0x080598E4L, waitForMonSelection = 0x0805982CL, handleInputChooseTarget = 0x08057828L,
    pokedex = POKEDEX_EMERALD_IT, guideTables = GUIDE_TABLES_EMERALD_IT,
)

// Japanese Emerald (BPEJ): its own build - every RAM global and the battle code
// moved (English's literal pools, matched word for word), the structs didn't:
// English's save loads with the same party bytes, money and position, and a
// scripted wild battle reads with English's BattlePokemon / gMain layouts
// (emerald_ja / emerald_ja_battle, headless; the action / move / bag / party
// handlers checked there). Its names are kana (EmeraldText 'J', Gen3Text's
// Japanese table). No TRAINER CARD, and the party slot is the app's own (its
// fonts aren't the Western ones the slot art is drawn with).
val LEGACY_NATIVE_EMERALD_JA = LEGACY_NATIVE_EMERALD_RETAIL.copy(
    itemDescs = japaneseItems(0x0855CEE8L),
    actionCursor = 0, moveCursor = 0, // its RAM moved; not located yet

    language = 'J', gameCode = "BPEJ", trainerCard = null,
    playerParty = 0x02024190L, playerPartyCount = 0x0202418DL,
    battleMons = 0x02023D28L, battlerPositions = 0x02023D1AL, battlersCount = 0x02023D10L,
    battleTypeFlags = 0x02022C90L, battlerPartyIndexes = 0x02023D12L, battleStructPtr = 0x02024140L,
    enemyParty = 0x020243E8L,
    gMain = 0x03002360L, saveBlock1Ptr = 0x03005AECL, saveBlock2Ptr = 0x03005AF0L,
    objectEvents = 0x02036FF0L, mapHeader = 0x02036FB8L, bagPockets = 0x02039A78L,
    battlerControllerFuncs = 0x03005AC0L,
    handleInputChooseAction = 0x08057198L, handleInputChooseMove = 0x0805780CL,
    completeWhenChoseItem = 0x080594F0L, waitForMonSelection = 0x08059438L, handleInputChooseTarget = 0x08057434L,
    monIconTable = 0x08556804L, monIconPaletteIndices = 0x08556EE4L, monIconPaletteTable = 0x0855709CL,
    itemIconTable = 0x085DFCC8L,
    pokedex = POKEDEX_EMERALD_JA, guideTables = GUIDE_TABLES_EMERALD_JA,
)

// LeafGreen (BPGE). pret/pokefirered @ c75f352 builds `leafgreen` and
// `leafgreen_rev1` byte-identical to the user's retail dumps (sha1 574fa542…
// / 7862c67b…), and their .map files put every RAM global and all of the
// battle-controller code at FireRed's own addresses for the same revision -
// only ROM data (icons, dex tables, dex text) moves. sItemIconTable is static
// (not in the map): found by matching FireRed's item-icon data.
val LEGACY_NATIVE_LEAFGREEN_REV0 = LEGACY_NATIVE_FIRERED_REV0.copy(
    itemDescs = vanillaItems(0x083DAE64L),
    monIconTable = 0x083D35DCL,
    monIconPaletteIndices = 0x083D3CBCL,
    monIconPaletteTable = 0x083D3E74L,
    itemIconTable = 0x083D40D0L,
    pokedex = POKEDEX_LEAFGREEN_REV0,
    guideTables = GUIDE_TABLES_LEAFGREEN_REV0,
    trainerCard = TRAINER_CARD_FIRERED,
)

val LEGACY_NATIVE_LEAFGREEN_REV1 = LEGACY_NATIVE_FIRERED_REV1.copy(
    itemDescs = vanillaItems(0x083DAED4L),
    actionCursor = 0, moveCursor = 0, // FireRed's, probably, but not checked on LeafGreen yet
    monIconTable = 0x083D364CL,
    monIconPaletteIndices = 0x083D3D2CL,
    monIconPaletteTable = 0x083D3EE4L,
    itemIconTable = 0x083D4140L,
    pokedex = POKEDEX_LEAFGREEN_REV1,
    // FireRed rev 1's guide tables are ROM addresses - LeafGreen's own here.
    // Its foe-team RAM addresses (gEnemyParty, gBattlerPartyIndexes, ...)
    // match FireRed's in the map, so those carry over unchanged.
    guideTables = GUIDE_TABLES_LEAFGREEN_REV1,
)

// Ruby (AXVE) / Sapphire (AXPE), revs 1 and 2. pret/pokeruby's `ruby_rev1/2`
// and `sapphire_rev1/2` build byte-identical to the user's dumps (sha1
// 610b96a9… / 5b64eacf… / 4722efb8… / 89b45fb1…); all four maps agree on
// every RAM address below. The differences from Emerald's engine: the party
// lives in IWRAM, the save blocks are static (no gSaveBlock*Ptr, no
// per-load shuffle), gBagPockets is a const ROM table pointing into
// SaveBlock1, bag quantities aren't XOR'd (no encryption key), and gMain's
// inBattle bit sits at +0x43D. Same party-mon encryption, same pocket order
// as Emerald. No item icons (the R/S bag never draws one). The battle
// controls' handlers and cursors are the ELFs' (pokeruby's static functions,
// the same in both games and in rev 2's ROMs, byte for byte); the POKéMON
// switch isn't: R/S's party menu keeps its cursor in a sprite, not gPartyMenu.
val LEGACY_NATIVE_RUBY = NativeConfig(
    itemDescs = vanillaItems(0x083C5580L, RUBY_SAPPHIRE_ITEMS),
    playerParty = 0x03004360L,
    playerPartyCount = 0x03004350L,
    battleMons = 0x02024A80L,
    battlerPositions = 0x02024A72L,
    battlersCount = 0x02024A68L,
    battleTypeFlags = 0x020239F8L,
    gMain = 0x03001770L,
    saveBlock1Ptr = 0x02025734L, // gSaveBlock1 itself
    saveBlock2Ptr = 0x02024EA4L, // gSaveBlock2 itself
    staticSaveBlocks = true,
    objectEvents = 0x030048A0L,
    mapHeader = 0x0202E828L,
    bagPockets = 0x083C1634L,
    bagPocketCount = 5,
    encryptionKeyOff = -1,
    moneyOff = 0x490L,
    bagPocketOrder = EMERALD_BAG_POCKET_ORDER,
    monIconTable = 0x083BBD3CL,
    monIconPaletteIndices = 0x083BC41CL,
    monIconPaletteTable = 0x083BC5D4L,
    inBattleOff = 0x43D,
    // Checked headless on Sapphire rev 1 (`sapphire_rev1_battle`): RIGHT / DOWN walk the action cursor 0 -> 1 -> 3, a
    // written 0 + A opens FIGHT, a written move cell 1 + A spends that move's PP; POKéMON / BAG reach their handlers.
    battlerControllerFuncs = 0x03004330L,
    handleInputChooseAction = 0x0802C098L,
    handleInputChooseMove = 0x0802C68CL,
    handleInputChooseTarget = 0x0802C2ECL,
    completeWhenChoseItem = 0x0802E03CL,
    waitForMonSelection = 0x0802DF88L,
    actionCursor = 0x02024E60L,
    moveCursor = 0x02024E64L,
    pokedex = POKEDEX_RUBY,
    guideTables = GUIDE_TABLES_RUBY,
    // FOE TEAM, from the same maps: gEnemyParty is in IWRAM like the party;
    // monToSwitchIntoId is at gSharedMem+0x16068 (include/battle.h, ewram.h).
    // Not battle-tested yet.
    enemyParty = 0x030045C0L,
    battlerPartyIndexes = 0x02024A6AL,
    battleStructPtr = 0x02000000L,
    battleStructStatic = true,
    monToSwitchIntoOff = 0x16068,
)

val LEGACY_NATIVE_SAPPHIRE = LEGACY_NATIVE_RUBY.copy(
    itemDescs = vanillaItems(0x083C55DCL, RUBY_SAPPHIRE_ITEMS),
    bagPockets = 0x083C1690L,
    monIconTable = 0x083BBD98L,
    monIconPaletteIndices = 0x083BC478L,
    monIconPaletteTable = 0x083BC630L,
    pokedex = POKEDEX_SAPPHIRE,
    guideTables = GUIDE_TABLES_SAPPHIRE,
)

// Heart and Soul v2.0.6 - a newer pokeemerald-expansion build (RHH's fork).
// A first attempt took addresses from building the source tag
// (PokemonHnS-Development/pokehns-expansion Release-v2.0.6) and reading its
// ELF, but that build doesn't byte-match the release: most of upper EWRAM
// sits 4 bytes lower in the real ROM (party, party count, bag, map header),
// so the party read showed nothing. Every address below was re-checked
// against the RELEASE ROM with headless captures (scripts/
// capture_fixture_headless.sh heart_and_soul) of the user's save: CYNDAQUIL
// Lv5 16/19 HP, 2 Potions, in New Bark Town. Five field captures with
// varied boot timing, plus one wild battle (Hoothoot on Route 29):
//   - playerParty: the "CYNDAQUIL" nickname sits at a fixed 0x0203476C in all
//     5 (mon = -8); the only other copy drifts at gSaveBlock1Ptr+0x23C.
//     Checksum and encryption are vanilla, but BoxPokemon packs
//     species:11|teraType:5 like TMT2 (raw 22683 -> 155), hence the masks.
//   - playerPartyCount: not next to the party. Of the addresses sharing
//     literal pools with gPlayerParty (642 refs), the only one reading 1 in
//     every capture.
//   - gSaveBlock1Ptr/2Ptr: the IWRAM pair pointing at the drifting copy
//     (-0x23C) / at a SaveBlock2 named "LIDOR". SaveBlock1 has 4 extra bytes
//     at its start: party at +0x23C (vanilla +0x238), pos at +4 (reads
//     15,12 = the player's currentCoords - 7), map 0.0 = New Bark (the live
//     map header is gMapGroups[0][0] in the ROM).
//   - encryptionKey: vanilla SaveBlock2+0xAC. Every empty bag slot's quantity
//     equals key & 0xFFFF in all 5 captures.
//   - gMain: the only IWRAM pair counting exact frame deltas (+0x20/+0x24);
//     the inBattle bit at +0x439 flips on in the battle capture.
//   - bagPockets: stable 6-pocket {ptr, cap} table (caps 236/92/39/160/66/
//     60). The Potions are in pocket 1, EXP. SHARE and GB PLAYER in pocket 5,
//     matching HNS_BAG_POCKET_ORDER.
//   - objectEvents: gObjectEvents[0] = the player (localId 0xFF,
//     currentCoords 22,19); the old from-source address was right here.
//   - battle globals: the old from-source values were right too (low EWRAM
//     didn't move). In the battle capture gBattleMons[0] = CYNDAQUIL and [1]
//     = HOOTHOOT, gBattlersCount 2, gBattlerPositions {0, 1}. struct
//     BattlePokemon is 0x88 bytes here: species +0, moves +0x0C, types
//     +0x22/+0x23, pp +0x25, hp +0x2A, level +0x2C, maxHP +0x2E,
//     status1 +0x50 (checked on both mons: Tackle/Leer 33/30 PP, Fire/Fire;
//     Tackle/Growl/Foresight, Normal/Flying, 14/14 HP).
// Icons (ROM; mon and item icons rendered offline and checked by eye):
// gSpeciesInfo 0x10C-byte entries, name-relative +0x40 = iconSprite (raw
// tiles), +0x62 = iconPalIndex (low 3 bits; 193/251 agree with vanilla, the
// rest are HnS's redrawn icons). gMonIconPaletteTable = the unique six
// {ptr, tag 56000..56005}. gItemsInfo 0x2C-byte entries, +0x10 tiles in the
// expansion's "smol" format (see Smol.kt), +0x14 RAW palette.
// Types use newer expansion ids (Fire = 11), so HnS has its own tables
// (*Hns.kt, scripts/gen_expansion_tables.py). Touch battle input: not derived.
val LEGACY_NATIVE_HEART_AND_SOUL = LEGACY_NATIVE_EMERALD.copy(
    // gItemsInfo: 0x2C-byte entries, the description pointer 8 bytes before the name's.
    itemDescs = ItemDescTable(0x0878EFD4L, 0x2C, -8),
    playerParty = 0x02034764L,
    playerPartyCount = 0x020342A4L,
    battleMons = 0x02000420L,
    battlerPositions = 0x02000238L,
    battlersCount = 0x020000B0L,
    battleTypeFlags = 0x020000ACL,
    gMain = 0x03005BD8L,
    saveBlock1Ptr = 0x030041D8L,
    saveBlock2Ptr = 0x030041D4L,
    saveBlock1PosOff = 4,
    moneyOff = 0x494L,
    objectEvents = 0x020017F4L,
    mapHeader = 0x02001D0CL,
    bagPockets = 0x02006DD0L,
    bagPocketCount = 6,
    bagPocketOrder = HNS_BAG_POCKET_ORDER,
    speciesMask = 0x07FF,
    moveMask = 0x07FF,
    ppMask = 0x7F,
    battleMonLayout = BattleMonLayout(
        size = 0x88, moves = 0x0C, type1 = 0x22, type2 = 0x23, pp = 0x25,
        hp = 0x2A, level = 0x2C, maxHp = 0x2E, status1 = 0x50,
        ability = 0x20, abilityU16 = true, item = 0x30,
    ),
    monIconTable = 0x087E65B0L,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0x10C,
    monIconPaletteIndices = 0x087E65D2L,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0x10C,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x08D39CC0L,
    itemIconTable = 0x0878EFE4L,          // gItemsInfo[0].iconPic
    itemIconStride = 0x2C,
    itemPalCompressed = false,
    // Found by diffing headless dumps of a Route 29 battle (the action menu, FIGHT's move menu, BAG, POKéMON): the
    // IWRAM word that holds a different ROM pointer in each. No target handler yet (a single battle never shows the
    // target screen; 0 never matches a live controller function). Checked: a written action cell + A lands on the
    // handler written (1 the bag, 2 the party menu), a written move cell + A spends that move's PP.
    battlerControllerFuncs = 0x03002F54L,
    handleInputChooseAction = 0x0805D1C5L,
    handleInputChooseMove = 0x0805C091L,
    completeWhenChoseItem = 0x0805A95DL,
    waitForMonSelection = 0x0805A9A1L,
    handleInputChooseTarget = 0,
    // gActionSelectionCursor / gMoveSelectionCursor (EWRAM bytes walking 0 -> 1 -> 3 / 0 -> 1 in those dumps).
    actionCursor = 0x020003A4L,
    moveCursor = 0x020003A8L,
    pokedex = POKEDEX_HEART_AND_SOUL,
    guideTables = GUIDE_TABLES_HEART_AND_SOUL,
)

val LEGACY_NATIVE_LAZARUS = LEGACY_NATIVE_EMERALD.copy(
    // gItemsInfo: 0x50-byte entries, inline names, the description pointer 8 bytes before.
    itemDescs = ItemDescTable(0x0886855CL, 0x50, -8),
    guideTables = GUIDE_TABLES_LAZARUS,
    playerParty = 0x0201B960L,
    playerPartyCount = 0x0201B95DL,
    gMain = 0x030014B8L,
    saveBlock1Ptr = 0x03003664L,
    saveBlock2Ptr = 0x03003668L,
    encryptionKeyOff = 0xB0L,
    objectEvents = 0x0200566CL,
    mapHeader = 0x0200B06CL,
    bagPockets = 0x0200B0D8L,
    bagPocketCount = 5,
    bagPocketOrder = LAZARUS_BAG_POCKET_ORDER,
    battleTypeFlags = 0x020002C0L,
    battlersCount = 0x02000344L,
    battlerPartyIndexes = 0x02000348L,
    battlerPositions = 0x02000350L,
    battleMons = 0x02000360L,
    battleMonLayout = EXPANSION_BATTLE_MON,
    battleStructPtr = 0x0200082CL,
    monToSwitchIntoOff = 0x3C,
    // gActionSelectionCursor / gMoveSelectionCursor: +0x10 / +0x14 past the gBattleStruct pointer, as in battle_main.c.
    // Found by dump diffs (0 -> 1 -> 3 on RIGHT / DOWN) and checked headless: a written 2 + A opens the party menu, a
    // written move cell + A spends that move's PP (EXPANSION_BATTLE_MON's pp +0x25).
    actionCursor = 0x0200083CL,
    moveCursor = 0x02000840L,
    enemyParty = 0x0201BBB8L,
    battlerControllerFuncs = 0x03002248L,
    handleInputChooseAction = 0x0805D649L,
    handleInputChooseMove = 0x0805DF0DL,
    completeWhenChoseItem = 0x0805FD79L,
    waitForMonSelection = 0x0805FCA9L,
    handleInputChooseTarget = 0x0805D8A5L,
    eggSpecies = 1561,
    monIconTable = 0x08C7A3B0L,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0xD4,
    monIconPaletteIndices = 0x08C7A3BEL,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0xD4,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x08CCC360L,
    itemIconTable = 0x08868590L,          // gItemsInfo[0].iconPic
    itemIconStride = 0x50,
    pokedex = POKEDEX_LAZARUS,
    trainerCard = TRAINER_CARD_LAZARUS,
)

val LEGACY_NATIVE_SOULGOLD = LEGACY_NATIVE_EMERALD.copy(
    // gItemsInfo: 0x2C-byte entries, the description pointer 8 bytes before the name's.
    itemDescs = ItemDescTable(0x087520A0L, 0x2C, -8),
    playerParty = 0x0203901CL,
    playerPartyCount = 0x02038DD5L,
    monStride = 96,
    partyMonLayout = SOULGOLD_PARTY_MON,
    gMain = 0x030055C0L,
    saveBlock1Ptr = 0x030040C4L,
    saveBlock2Ptr = 0x030040C0L,
    pokemonStoragePtr = 0x030040C8L,
    pcStorage = SOULGOLD_PC_STORAGE,
    encryptionKeyOff = 0xB4L,
    moneyOff = 0x478L,
    objectEvents = 0x02003314L,
    mapHeader = 0x02003828L,
    mapSecWide = true,
    bagPockets = 0x020088D4L,
    bagPocketCount = 8,
    bagPocketOrder = SOULGOLD_BAG_POCKET_ORDER,
    speciesMask = 0x07FF,
    moveMask = 0x07FF,
    ppMask = 0x7F,
    battleTypeFlags = 0x02001AB4L,
    battlersCount = 0x02001AB8L,
    battlerPositions = 0x02001B58L,
    battlerPartyIndexes = 0x02001B5CL,
    battleMons = 0x02001ECCL,
    battleMonLayout = SOULGOLD_BATTLE_MON,
    battleStructPtr = 0x02001ABCL,
    monToSwitchIntoOff = 0x115,
    // gActionSelectionCursor / gMoveSelectionCursor, adjacent as in battle_main.c: found headless by walking them
    // (0 -> 1 -> 3 on RIGHT / DOWN) on v1.1.4 and all three v1.2 builds (same RAM: the v1.2 configs inherit them),
    // and a cell written before A is what the game takes (2 opens the party menu, a move cell spends its own PP).
    actionCursor = 0x02001E50L,
    moveCursor = 0x02001E54L,
    enemyParty = 0x02038DDCL,
    battlerControllerFuncs = 0x03002E44L,
    handleInputChooseAction = 0x08061CD1L,
    handleInputChooseMove = 0x08060E21L,
    completeWhenChoseItem = 0x0805FAC5L,
    waitForMonSelection = 0x0805FB09L,
    handleInputChooseTarget = 0x0805FEA5L,
    partyMenu = 0x02038D24L,
    eggSpecies = 1578,
    monIconTable = 0x087D4514L,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0x118,
    monIconPaletteIndices = 0x087D453AL,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0x118,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x08F15970L,
    monIconPalettes = 0x087D453CL,        // gSpeciesInfo[0]'s own icon palette (+0x94; shiny +0x98)
    itemIconTable = 0x087520B0L,          // gItemsInfo[0].iconPic
    itemIconStride = 0x2C,
    itemPalCompressed = false,
    pokedex = POKEDEX_SOULGOLD,
    guideTables = GUIDE_TABLES_SOULGOLD,
    // AddMoney (0x081B0E5C) caps at 9,999,999, and the card prints 7 digits.
    maxMoney = 9_999_999L,
    trainerCard = TRAINER_CARD_SOULGOLD,
)

// Pokémon SoulGold v1.2 - a rebuild of v1.1.4 (same save format: the user's
// save loads in both). Found by matching v1.1.4's code and data in it (literal
// pools for the RAM addresses, masked byte runs for the ROM), then checked on
// headless captures of that save (the soulgold_v12 / soulgold_v12_battle
// fixtures): gPlayerParty / gPlayerPartyCount / gEnemyParty / gPartyMenu moved
// 8 bytes, the save, map, bag and battle globals didn't. The battle handlers
// moved 0x38-0x3C bytes, ScriptContext_SetupScript is 0x08236D5C, gSpeciesInfo
// grew to 0x120-byte entries (8 bytes added past the fields read here), and the
// ROM tables shifted a few KB - their contents are v1.1.4's but TM75 (Agility,
// was Swords Dance; see ActiveTables' soulGoldV12).
val LEGACY_NATIVE_SOULGOLD_V1_2 = LEGACY_NATIVE_SOULGOLD.copy(
    itemDescs = ItemDescTable(0x08753834L, 0x2C, -8), // its own TM75 text included
    guideTables = GUIDE_TABLES_SOULGOLD_V1_2,
    playerParty = 0x02039024L,
    playerPartyCount = 0x02038DDDL,
    enemyParty = 0x02038DE4L,
    partyMenu = 0x02038D2CL,
    handleInputChooseAction = 0x08061D09L,
    handleInputChooseMove = 0x08060E59L,
    completeWhenChoseItem = 0x0805FB01L,
    waitForMonSelection = 0x0805FB45L,
    handleInputChooseTarget = 0x0805FEE1L,
    monIconTable = 0x087D5D3CL,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0x120,
    monIconPaletteIndices = 0x087D5D62L,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0x120,
    monIconPaletteTable = 0x08F20ABCL,
    monIconPalettes = 0x087D5D64L,
    itemIconTable = 0x08753844L,          // gItemsInfo[0].iconPic
    pokedex = POKEDEX_SOULGOLD_V1_2,
    trainerCard = TRAINER_CARD_SOULGOLD_V1_2,
)

// A second build released as SoulGold v1.2 (sha1 5d6a0362..., its title screen says v1.2 too):
// a few small code changes, so every RAM global and the battle handlers sit where they do in
// LEGACY_NATIVE_SOULGOLD_V1_2 (checked on the same save headlessly: the soulgold_v12b fixtures, and the
// action / move / bag / party handlers in a scripted wild battle) and only the ROM data moved,
// 0x98 bytes (gSpeciesInfo, gItemsInfo) or 0xA4 (the icon palettes). Its tables are byte for byte
// the first v1.2's, TM75 included.
val LEGACY_NATIVE_SOULGOLD_V1_2B = LEGACY_NATIVE_SOULGOLD_V1_2.copy(
    itemDescs = ItemDescTable(0x0875379CL, 0x2C, -8),
    guideTables = GUIDE_TABLES_SOULGOLD_V1_2B,
    monIconTable = 0x087D5CA4L,
    monIconPaletteIndices = 0x087D5CCAL,
    monIconPaletteTable = 0x08F20A18L,
    monIconPalettes = 0x087D5CCCL,
    itemIconTable = 0x087537ACL,
    pokedex = POKEDEX_SOULGOLD_V1_2B,
)

// A third build released as SoulGold v1.2 (sha1 2be7c99a..., "v1.2" on its title screen as well):
// every RAM global and battle handler is still LEGACY_NATIVE_SOULGOLD_V1_2's (checked on the same save
// headlessly: the soulgold_v12c fixtures, and the action / move / bag / party handlers in a scripted
// wild battle - ScriptContext_SetupScript is 0x08236DBC). ROM data moved 0x18C bytes (gSpeciesInfo,
// gItemsInfo, gTrainers), 0x190 (ability names, the Johto list) or 0x198 (icon palettes); ~180 KB
// of new data sits before the wild / map tables (0x2DF30 later). Its tables are byte for byte the
// other v1.2 builds', TM75 included.
val LEGACY_NATIVE_SOULGOLD_V1_2C = LEGACY_NATIVE_SOULGOLD_V1_2.copy(
    itemDescs = ItemDescTable(0x08753928L, 0x2C, -8),
    guideTables = GUIDE_TABLES_SOULGOLD_V1_2C,
    monIconTable = 0x087D5E30L,
    monIconPaletteIndices = 0x087D5E56L,
    monIconPaletteTable = 0x08F20BB0L,
    monIconPalettes = 0x087D5E58L,
    itemIconTable = 0x08753938L,
    pokedex = POKEDEX_SOULGOLD_V1_2C,
)

val LEGACY_NATIVE_ROWE = LEGACY_NATIVE_EMERALD.copy(
    // gItems: 0x38-byte entries, description pointer at +0x1C.
    itemDescs = ItemDescTable(0x08F76D78L, 0x38, 0x1C),
    playerParty = 0x02025128L,
    playerPartyCount = 0x02025125L,
    monStride = 0x4C,
    partyMonLayout = ROWE_PARTY_MON,
    gMain = 0x03002B90L,
    saveBlock1Ptr = 0x03004AFCL,
    saveBlock2Ptr = 0x03004B00L,
    encryptionKeyOff = 0x40L,
    moneyOff = 0x400L,
    objectEvents = 0x02037C2CL,
    mapHeader = 0x02037BF4L,
    bagPockets = 0x0203B3B4L,
    bagPocketCount = 10,
    bagPocketOrder = ROWE_BAG_POCKET_ORDER,
    battleTypeFlags = 0x02024B6CL,
    battlersCount = 0x02024BF8L,
    battlerPartyIndexes = 0x02024BFAL,
    battlerPositions = 0x02024C02L,
    battleMons = 0x02024C10L,
    battleMonLayout = ROWE_BATTLE_MON,
    battleStructPtr = 0x020250A4L,
    // gActionSelectionCursor / gMoveSelectionCursor, +0x10 / +0x14 past the gBattleStruct pointer. Checked
    // headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x020250B4L,
    moveCursor = 0x020250B8L,
    monToSwitchIntoOff = 0x5E,
    enemyParty = 0x020252F0L,
    battlerControllerFuncs = 0x03004AD0L,
    handleInputChooseAction = 0x08075791L,
    handleInputChooseMove = 0x080760B1L,
    completeWhenChoseItem = 0x080780D9L,
    waitForMonSelection = 0x08077F69L,
    handleInputChooseTarget = 0x08075AB1L,
    eggSpecies = 2300,
    monIconTable = 0x08F6F910L,
    monIconPaletteIndices = 0x08F72778L,
    monIconPaletteTable = 0x08F73314L,
    itemIconTable = 0x0904D9A0L,
    pokedex = POKEDEX_ROWE,
)

val LEGACY_NATIVE_EMERALD_ROGUE = LEGACY_NATIVE_EMERALD.copy(
    // gItems (0x28-byte records, description at +0xC), then Rogue's own items from 827 in
    // gRogueItems (0x30-byte records, description at +0x10).
    itemDescs = ItemDescTable(0x08B935ACL, 0x28, 0x0C, count = 827, next = ItemDescTable(0x08B9DD54L, 0x30, 0x10, first = 827, count = 201)),
    playerParty = 0x02036C7CL,
    playerPartyCount = 0x02036C79L,
    monStride = 104,
    hubNameOff = ROGUE_HUB_NAME_OFF,
    battleTypeFlags = 0x0201C2C0L,
    battlersCount = 0x0201C33CL,
    battlerPartyIndexes = 0x0201C340L,
    battlerPositions = 0x0201C348L,
    battleMons = 0x0201C358L,
    battleMonLayout = EXPANSION_BATTLE_MON,
    battleStructPtr = 0x0201C83CL,
    monToSwitchIntoOff = 0x3C,
    enemyParty = 0x02036EECL,
    gMain = 0x030014B4L,
    saveBlock1Ptr = 0x03003614L,
    saveBlock2Ptr = 0x03003618L,
    encryptionKeyOff = 0x4CL,
    moneyOff = 0x4A8L,
    objectEvents = 0x02020A48L,
    mapHeader = 0x020264B4L,
    bagPockets = 0x02026524L,
    bagPocketCount = 9,
    bagPocketOrder = ROGUE_BAG_POCKET_ORDER,
    speciesMask = 0x07FF,
    monIconTable = 0x0905BD04L + 0x48 - 0x98,          // gSpeciesInfo[0].iconSprite
    monIconStride = 0x98,
    monIconPaletteIndices = 0x0905BD04L + 0x5A - 0x98, // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0x98,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x0909713CL,
    itemIconTable = 0x08BA038CL,
    extraItemIconTable = 0x08B9DD54L + 0x28,           // gRogueItems[0].iconImage
    extraItemFirst = 827,
    extraItemCount = 201,
    extraItemStride = 0x30,
    battlerControllerFuncs = 0,
    handleInputChooseAction = 0,
    handleInputChooseMove = 0,
    completeWhenChoseItem = 0,
    waitForMonSelection = 0,
    handleInputChooseTarget = 0,
    pokedex = POKEDEX_EMERALD_ROGUE,
)

// Emerald Seaglass v3.0 - BPEE, exactly 16 MB (so Poller.detect()'s >16 MB
// hack-hash branch never saw it - see its own 16 MB branch). No source
// access; a pokeemerald-expansion-style recompile (mixed-case names, National
// Dex species numbering, expansion item order) with its own link layout -
// none of LEGACY_NATIVE_EMERALD's RAM addresses hold. Every field overridden below
// was found from HEADLESS captures (scripts/capture_fixture_headless.sh,
// no device) of the user's real save - 1 Torchic Lv5 + a Shiny Charm - and
// confirmed stable across 5 independent captures with different boot timing
// (so different RNG seeds, so a different SaveBlock offset each time -
// Emerald's per-load save-block relocation):
//   - playerParty: checksum-valid mon at 0x02019C20 in all 5; the only other
//     hit (0x0200CBxx) drifted every capture and sits at exactly
//     gSaveBlock1Ptr+0x238 (vanilla's SaveBlock1.playerParty) - it's the
//     save-block copy, not the global. Count byte at party-3, like vanilla.
//   - saveBlock1Ptr/saveBlock2Ptr: the IWRAM pair that always points at that
//     drifting copy (-0x238) / at a SaveBlock2 whose name reads "LIDOR".
//     encryptionKey stays at vanilla's +0xAC: every empty bag slot's
//     quantity is exactly key&0xFFFF in all 5 captures.
//   - gMain: the only IWRAM pair counting exactly the captured frame deltas
//     (vblankCounter1/2 at +0x20/+0x24, vanilla offsets); callback1/2 are
//     stable Thumb ROM pointers.
//   - bagPockets: stable {ptr,cap} x5 table whose pointers track SaveBlock1
//     (+0x560 cap 80, +0x740 cap 20, +0x790 cap 70, +0x8A8 cap 60, +0x6A0
//     cap 40) - vanilla Emerald's pocket order; the Shiny Charm (a key item,
//     id 691 in ItemNamesSeaglass.kt) sits in pocket 4.
//   - mapHeader: stable, mapsec 0x00 (Littleroot) + MAP_TYPE_INDOOR while
//     SaveBlock1 says map 1.4 (Birch's Lab) - vanilla Hoenn mapsec ids.
//   - objectEvents: gObjectEvents[0] = the player (currentCoords = SaveBlock1
//     pos + 7), stable; the drifting 0x0200D4xx hit is SaveBlock1's copy.
// NOT found (no battle in any capture): battleMons/battlerPositions/
// battlersCount/battleTypeFlags are still LEGACY_NATIVE_EMERALD's - known wrong,
// same gap as Lazarus/Emerald Rogue - and expansion's struct BattlePokemon
// likely isn't vanilla's layout either, so decodeBattleMon would need a
// variant regardless. Touch-battle-input addresses zeroed so that stays
// cleanly disabled, same as Heart and Soul.
//
// Icons (ROM data, found by static analysis of the ROM file - no RAM
// involved): expansion has no gMonIconTable/gMonIconPaletteIndices/
// gItemIconTable arrays; the same fields live inside each fixed-stride
// gSpeciesInfo / gItemsInfo entry, so the bases below point at the field
// within entry 0 and the strides are the whole structs' sizes:
//   - gSpeciesInfo: 0xD0-byte entries (inline species names are exactly
//     0xD0 apart - "Bulbasaur"@0x088F087C, "Ivysaur" +0xD0, "Torchic" at
//     species 255). Relative to the name: +0x4C = iconSprite (raw 1024-byte
//     4bpp, the only per-species pointer whose target isn't LZ77 - the
//     neighbours are front/back pics and palettes), +0x5A = iconPalIndex in
//     the low 3 bits (upper bits: iconPalIndexFemale).
//   - gMonIconPaletteTable: still a separate array (0x0893F0B8 - the only
//     place in ROM with six {ptr, tag 56000..56005} entries).
//   - gItemsInfo: 0x54-byte entries, name-relative +0x34/+0x38 = iconPic/
//     iconPalette (every item's pair points at LZ77 data decompressing to
//     exactly 288/32 bytes = a 24x24 icon + palette).
// Verified by decoding offline with these exact rules: species 1-60,
// 252-311, Mewtwo, Rayquaza, Pecharunt all render as the right Pokémon in
// the right colours; balls, Potion, Mint, Gengarite and the user's Shiny
// Charm all render correctly as items. Species Seaglass doesn't include
// (e.g. #445) and TMs (expansion draws TM discs per move type in code) have
// NULL pointers there, which DecompIconSource already treats as "no icon".
// Battle (2026-10-07, headless - the emerald_seaglass_battle fixture: a scripted
// wild battle run through ScriptContext_SetupScript 0x081EF598): the same
// expansion layout as Lazarus - gBattleTypeFlags 0x020002C0, gBattlersCount
// 0x02000344, gBattlerPartyIndexes 0x02000348 (1 after switching to slot 2),
// gBattlerPositions 0x02000350, gBattleMons 0x02000360 (0x60-byte entries) - but
// the gBattleStruct pointer is 0x02000824 (monToSwitchIntoId at +0x3C, 06 06 06 06
// then 01 23 45); gEnemyParty 0x02019E78, right after the party. Before this the
// config inherited vanilla Emerald's battle addresses: a blank INFO page.
// Touch battle input: gBattlerControllerFuncs 0x03002244 (IWRAM), the action /
// move / bag / party handlers 0x0805CD35 / 0x0805D5CD / 0x0805F41D / 0x0805F34D,
// HandleInputChooseTarget 0x0805CF85 by code (the move handler's pool; it returns
// to the move handler on B). gPartyMenu 0x02019964, Emerald's list (DOWN walks it).
val LEGACY_NATIVE_EMERALD_SEAGLASS = LEGACY_NATIVE_EMERALD.copy(
    // gItemsInfo: 0x54-byte entries, inline names, the description pointer 8 bytes before.
    itemDescs = ItemDescTable(0x0867E77CL, 0x54, -8),
    guideTables = GUIDE_TABLES_SEAGLASS,
    playerParty = 0x02019C20L,
    playerPartyCount = 0x02019C1DL,
    gMain = 0x030014B4L,
    saveBlock1Ptr = 0x030051B8L,
    saveBlock2Ptr = 0x030051BCL,
    objectEvents = 0x0200564CL,
    mapHeader = 0x0200B04CL,
    bagPockets = 0x0200B0B8L,
    monIconTable = 0x088F07F8L,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0xD0,
    monIconPaletteIndices = 0x088F0806L,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0xD0,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x0893F0B8L,
    itemIconTable = 0x0867E7B0L,          // gItemsInfo[0].iconPic
    itemIconStride = 0x54,
    battleTypeFlags = 0x020002C0L,
    battlersCount = 0x02000344L,
    battlerPartyIndexes = 0x02000348L,
    battlerPositions = 0x02000350L,
    battleMons = 0x02000360L,
    battleMonLayout = EXPANSION_BATTLE_MON,
    battleStructPtr = 0x02000824L,
    // gActionSelectionCursor / gMoveSelectionCursor: +0x10 / +0x14 past the gBattleStruct pointer (Lazarus's layout,
    // 8 bytes earlier). Checked headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a
    // written move 1 + A spends its PP.
    actionCursor = 0x02000834L,
    moveCursor = 0x02000838L,
    monToSwitchIntoOff = 0x3C,
    enemyParty = 0x02019E78L,
    battlerControllerFuncs = 0x03002244L,
    handleInputChooseAction = 0x0805CD35L,
    handleInputChooseMove = 0x0805D5CDL,
    completeWhenChoseItem = 0x0805F41DL,
    waitForMonSelection = 0x0805F34DL,
    handleInputChooseTarget = 0x0805CF85L,
    pokedex = POKEDEX_EMERALD_SEAGLASS,
)

// Pokemon Celia's Stupid Romhack v1.1.4 - BPRE rev 0, ~28.7 MB, no source.
// A full pokefirered RECOMPILE (unlike Unbound/Gaia's in-place ASM patches),
// so nothing sits at its vanilla address. Found headlessly (no device):
//   - RAM: 5 captures with varied boot timing, same method as
//     LEGACY_NATIVE_EMERALD_SEAGLASS. playerParty is the only checksum-valid copy
//     that never moved (the other sits at gSaveBlock1Ptr+0x38 and drifts);
//     gSaveBlock1Ptr/2Ptr are the IWRAM pair pointing at it; gMain's
//     callbacks are ROM ptrs and +0x24 counts frames exactly; bagPockets/
//     mapHeader/objectEvents found by the same structural scans (stable
//     across captures). Pocket order is vanilla FireRed's but Items/Key
//     Items are enlarged (52/40 slots vs 42/30) - the {ptr,cap} table
//     carries its own capacities, so nothing else to change.
//   - encryptionKey moved to SaveBlock2+0xB18 (an identical copy also sits
//     at +0xB40): the only offsets whose value tracks the per-load key in
//     every capture; SaveBlock1 money decrypts to 2999 with it.
//   - playerPartyCount and all four battle globals come from the ROM's CODE,
//     not RAM (a field capture has no battle in it, and a story script at
//     Pallet's north exit blocks reaching grass on this save): count
//     0x020240E1 shares 35 literal pools with gPlayerParty (and is vanilla's
//     own party-0x25B). The battle block moved +0x14 as a unit - vanilla's
//     most-referenced global there, gActiveBattler (955 refs), lines up with
//     Celia's 0x02023BD8 (1001), and battleTypeFlags/battlersCount/
//     battlerPositions/battleMons each match at the same +0x14 with similar
//     ref counts (305->374, 154->180, 30->31, 380->513). NOT live-verified in
//     a battle, and struct BattlePokemon's layout is assumed vanilla.
//   - Icons (ROM): vanilla icon bytes for Bulbasaur/Mewtwo found in Celia's
//     ROM, then the pointers to them - both give gMonIconTable 0x08C12A48
//     once you use THIS hack's species ids (Bulbasaur=2, Mewtwo=151; an
//     off-by-one here was caught by rendering). Palette table = the unique
//     six {ptr, tag 56000..56005}; palette indices = the u8<=5 run right
//     after the icon table; item table = the {tiles,pal} pairs whose entry 1
//     is vanilla's Master Ball tiles. All checked by decoding offline and
//     looking: species 0-23 + a spread up to 410, and 12 items incl. the
//     user's ETHER, render as the right Pokemon/items.
// Touch battle control disabled (addresses not derived).
val LEGACY_NATIVE_CELIA = LEGACY_NATIVE_FIRERED_REV0.copy(
    itemDescs = vanillaItems(0x08C1D0BCL), // relocated (15 references)
    playerParty = 0x0202433CL,
    playerPartyCount = 0x020240E1L,
    battleMons = 0x02023BF8L,
    battlerPositions = 0x02023BEAL,
    battlersCount = 0x02023BE0L,
    battleTypeFlags = 0x02022B60L,
    gMain = 0x03003490L,
    saveBlock1Ptr = 0x030053A8L,
    saveBlock2Ptr = 0x030053ACL,
    objectEvents = 0x02037034L,
    mapHeader = 0x02036FF8L,
    bagPockets = 0x02039B44L,
    encryptionKeyOff = 0xB18L,
    monIconTable = 0x08C12A48L,
    monIconPaletteIndices = 0x08C13F0CL,
    monIconPaletteTable = 0x08C14440L,
    itemIconTable = 0x08C1469CL,
    // Found by diffing headless dumps of a scripted battle (celia_battle's README): the IWRAM word holding a different
    // ROM pointer at the action menu, the move menu and the party menu; the bag's from a written BAG cell + A. No
    // target handler yet. Its battle block is FireRed's moved 0x14 up (gBattleMons 0x02023BF8), gBattlerPartyIndexes
    // with it (0x02023BE2: 0 -> 1 after a SHIFT to slot 1), the cursors 0x020240AC / 0x020240B0 (0 -> 1 -> 3), and a
    // written move 1 + A spent one PP byte (0x02023C1E).
    battlerControllerFuncs = 0x03005380L,
    handleInputChooseAction = 0x08037AFDL,
    handleInputChooseMove = 0x08038185L,
    completeWhenChoseItem = 0x0803A005L,
    waitForMonSelection = 0x08039E99L,
    actionCursor = 0x020240ACL,
    moveCursor = 0x020240B0L,
    battlerPartyIndexes = 0x02023BE2L,
    handleInputChooseTarget = 0,
    pokedex = POKEDEX_CELIA,
    guideTables = GUIDE_TABLES_CELIA,
)

val LEGACY_NATIVE_TMT2 = LEGACY_NATIVE_EMERALD.copy(
    // gItemsInfo: 0x54-byte entries, inline names ("Poké Ball" 0x086B9A04), the description 8 bytes before.
    itemDescs = ItemDescTable(0x086B99B0L, 0x54, -8),
    guideTables = GUIDE_TABLES_TMT2,
    playerParty = 0x02032C94L,
    playerPartyCount = 0x02032715L,
    gMain = 0x03004084L,
    saveBlock1Ptr = 0x030057D8L,
    saveBlock2Ptr = 0x030057D4L,
    encryptionKeyOff = 0x44L,
    objectEvents = 0x020016ACL,
    mapHeader = 0x02001BBCL,
    bagPockets = 0x02006C28L,
    bagPocketCount = 7,
    bagPocketOrder = TMT2_BAG_POCKET_ORDER,
    speciesMask = 0x07FF,
    moveMask = 0x07FF,
    ppMask = 0x7F,
    monIconTable = 0x08712AB0L,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0x104,
    monIconPaletteIndices = 0x08712AD2L,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0x104,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x08DF976CL,
    itemIconTable = 0x086B99E4L,          // gItemsInfo[0].iconPic
    itemIconStride = 0x54,
    itemPalCompressed = false,
    battleTypeFlags = 0x020000A8L,
    battlersCount = 0x020000ACL,
    battlerPartyIndexes = 0x02000298L,
    battlerPositions = 0x020002A8L,
    battleMons = 0x020005ACL,
    battleMonLayout = TMT2_BATTLE_MON,
    battleStructPtr = 0x020000B0L,
    // gActionSelectionCursor / gMoveSelectionCursor, found by dump diffs (0 -> 1 -> 3 on RIGHT / DOWN) and checked
    // headless: a written 2 + A opens the party menu, a written move 1 + A spends its PP (TMT2_BATTLE_MON's pp +0x26).
    actionCursor = 0x02000414L,
    moveCursor = 0x02000418L,
    monToSwitchIntoOff = 0x8B,
    enemyParty = 0x02032A3CL,
    battlerControllerFuncs = 0x03004590L,
    handleInputChooseAction = 0x080599FDL,
    handleInputChooseMove = 0x08058A01L,
    completeWhenChoseItem = 0x08057BF5L,
    waitForMonSelection = 0x08057B49L,
    handleInputChooseTarget = 0x08057641L,
    pokedex = POKEDEX_TMT2,
)

// Pokémon Glazed 9.2.0 - BPEE, 32 MB, an in-place binary edit of retail
// Emerald (no source). Its RAM is retail's: every literal pool that loads
// gPlayerParty / gSaveBlock1Ptr / gMain / gMapHeader / gObjectEvents /
// gBagPockets / the battle globals / gBattlerControllerFuncs / gPartyMenu holds
// retail's address (compared word for word against retail Emerald), and the
// headless captures agree (`glazed`: CHIMCHAR Lv6 at 0x020244EC, Forest Pass;
// `glazed_battle`: a wild SENTRET). The battle handlers are retail's code byte
// for byte, at retail's addresses (FIGHT / POKéMON read 0x08057BFD / 0x08059829
// in the battle). Its own tables (species in retail's 412 slots, renamed moves
// and items, FAIRY as type 9, the Tunod / Johto map sections and cursor grid)
// come from scripts/gen_glazed_tables.py; the icon tables are retail's addresses
// holding its own icons, and RomArt rebuilds its region map (GZ_REGION_*). Its
// POKéDEX is retail's tables rewritten in place (its own 1..386 numbering).
// Pokémon Emerald Kaizo v2.0 - BPEE, 16 MB, an in-place edit of retail Emerald (95% of the ROM byte for byte): retail's
// code, RAM and save, so everything retail reads; its own moves (KAIZO_TEXT) and wild table (GUIDE_TABLES_EMERALD_KAIZO).
val LEGACY_NATIVE_EMERALD_KAIZO = LEGACY_NATIVE_EMERALD_RETAIL.copy(gameCode = KAIZO_TEXT, guideTables = GUIDE_TABLES_EMERALD_KAIZO)

// Pokémon Emerald Legacy v1.1.4 - BPEE, 16 MB: pret's pokeemerald rebuilt from its own source (cRz-Shadows/
// Pokemon_Emerald_Legacy at V1.1.4, built with agbcc = the release byte for byte), so every address here is a symbol of
// that build's ELF. The battle globals kept retail's EWRAM; gMain, the save blocks, gObjectEvents, the bag and the code
// moved. Its structs are retail's but for SaveBlock1 past the ITEMS pocket (120 slots: the bag goes through gBagPockets,
// the flags / vars / dex / card offsets are on their tables) and ObjectEvent's u16 graphicsId. Eggs are SPECIES_EGG 415:
// species 412-414 are Deoxys forms, so an egg is flagged (eggSpecies) rather than read as vanilla's 412.
val LEGACY_NATIVE_EMERALD_LEGACY = LEGACY_NATIVE_EMERALD_RETAIL.copy(
    gameCode = LEGACY_TEXT,
    itemDescs = vanillaItems(0x085B6210L),
    gMain = 0x030024A0L,
    saveBlock1Ptr = 0x03005F6CL,
    saveBlock2Ptr = 0x03005F70L,
    objectEvents = 0x020374DCL,
    mapHeader = 0x020374A4L,
    bagPockets = 0x0203A100L,
    monIconTable = 0x085AE4DCL,
    monIconPaletteIndices = 0x085AEBC8L,
    monIconPaletteTable = 0x085AED84L,
    itemIconTable = 0x086486E0L,
    battlerControllerFuncs = 0x03005F40L,
    handleInputChooseAction = 0x08057E1CL,
    handleInputChooseMove = 0x08058490L,
    completeWhenChoseItem = 0x0805A214L,
    waitForMonSelection = 0x0805A15CL,
    handleInputChooseTarget = 0x080580B8L,
    partyMenu = 0x0203D1F0L,
    eggSpecies = 415,
    trainerCard = TRAINER_CARD_EMERALD_LEGACY,
    pokedex = POKEDEX_EMERALD_LEGACY,
    guideTables = GUIDE_TABLES_EMERALD_LEGACY,
)

val LEGACY_NATIVE_GLAZED = LEGACY_NATIVE_EMERALD.copy(
    itemDescs = vanillaItems(0x085839A0L), // retail's, rewritten in place
    guideTables = GUIDE_TABLES_GLAZED,
    // Retail's dex tables and flags, rewritten in place in its own 1..386 numbering (No.322 is
    // CHIMCHAR); its species 252-276 map past 386, where the game's dex doesn't go either.
    pokedex = POKEDEX_EMERALD,
    trainerCard = TRAINER_CARD_GLAZED,
    maxMoney = 9_999_999L,
    enemyParty = LEGACY_NATIVE_EMERALD_RETAIL.enemyParty,
    battlerPartyIndexes = LEGACY_NATIVE_EMERALD_RETAIL.battlerPartyIndexes,
    battleStructPtr = LEGACY_NATIVE_EMERALD_RETAIL.battleStructPtr,
    monToSwitchIntoOff = LEGACY_NATIVE_EMERALD_RETAIL.monToSwitchIntoOff,
    // Retail's battle menu cursors too: checked headless on a scripted wild battle (RIGHT / DOWN walk 0 -> 1 -> 3, a
    // written 2 + A opens the party menu, a written move + A spends its PP - against a Magikarp: its Pikachu's Sweet
    // Kiss confused the lead on the first try, and a self-hit spends none).
    actionCursor = LEGACY_NATIVE_EMERALD_RETAIL.actionCursor,
    moveCursor = LEGACY_NATIVE_EMERALD_RETAIL.moveCursor,
)

val LEGACY_NATIVE_IMPERIUM = LEGACY_NATIVE_EMERALD.copy(
    // gItemsInfo: 0x50-byte entries, inline names, the description pointer 8 bytes before.
    itemDescs = ItemDescTable(0x086C7A78L, 0x50, -8),
    playerParty = 0x020375F8L,
    playerPartyCount = 0x020375F5L,
    gMain = 0x03004218L,
    saveBlock1Ptr = 0x03006218L,
    saveBlock2Ptr = 0x0300621CL,
    encryptionKeyOff = 0x44L,
    moneyOff = 0x490L,
    objectEvents = 0x0200A6C4L,
    mapHeader = 0x0200A68CL,
    bagPockets = 0x0200AC44L,
    bagPocketCount = 6,
    bagPocketOrder = IMPERIUM_BAG_POCKET_ORDER,
    speciesMask = 0x07FF,
    moveMask = 0x07FF,
    ppMask = 0x7F,
    battleTypeFlags = 0x020002C0L,
    battlersCount = 0x02000344L,
    battlerPartyIndexes = 0x02000348L,
    battlerPositions = 0x02000350L,
    battleMons = 0x02000360L,
    battleMonLayout = EXPANSION_BATTLE_MON,
    battleStructPtr = 0x02000830L,
    // gActionSelectionCursor / gMoveSelectionCursor, +0x10 / +0x14 past the gBattleStruct pointer. Checked
    // headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x02000840L,
    moveCursor = 0x02000844L,
    monToSwitchIntoOff = 0x3C,
    enemyParty = 0x02037850L,
    battlerControllerFuncs = 0x03004F5CL,
    handleInputChooseAction = 0x0805648DL,
    handleInputChooseMove = 0x08056EA1L,
    completeWhenChoseItem = 0x08058CE9L,
    waitForMonSelection = 0x08058C19L,
    handleInputChooseTarget = 0x0805683DL,
    eggSpecies = 1536,
    monIconTable = 0x08D5DA40L,           // gSpeciesInfo[0].iconSprite
    monIconStride = 0x104,
    monIconPaletteIndices = 0x08D5DA62L,  // gSpeciesInfo[0].iconPalIndex
    monPalIdxStride = 0x104,
    monPalIdxMask = 0x07,
    monIconPaletteTable = 0x08DC0350L,
    itemIconTable = 0x086C7AACL,          // gItemsInfo[0].iconPic
    itemIconStride = 0x50,
    pokedex = POKEDEX_IMPERIUM,
    trainerCard = TRAINER_CARD_IMPERIUM,
    guideTables = GUIDE_TABLES_IMPERIUM,
)

// Pokémon Quetzal English Alpha 9 v0 - BPEE, 32 MB ("PKM QUETZAL"), TenmaRH's own
// engine on pokeemerald (vanilla's table shapes, expansion-sized contents), no
// source. From headless captures of the user's save (`quetzal`: Charmander Lv5,
// 10/19 HP, on Kanto's Route 1; `quetzal_battle`: a scripted wild Pidgey), each
// address also a literal-pool word (counts in brackets) and each field poked to see
// the game show it:
//   - gPlayerParty 0x020235CC (1033) / gPlayerPartyCount 0x020235C9 (55); its own
//     104-byte plaintext struct (QUETZAL_PARTY_MON). SaveBlock1+0x6A8 is the save's copy.
//   - gSaveBlock1Ptr / 2Ptr 0x030055BC / 0x030055C0 (the blocks move on every load);
//     SaveBlock1 is its own: pos / map at +0x470 (walking LEFT moved it, the player's
//     object at pos + 7), money at +0x918 XOR SB2+0x2C (₽7450, as START shows, in 5
//     boots with 5 keys). gMain 0x03002660 (inBattle at the usual +0x439).
//   - gMapHeader 0x02038CB0 (a byte mapsec, Emerald's ids: 0x65 = the Town Map's
//     ROUTE 1), gObjectEvents 0x02038D10 (488).
//   - The bag: Bag3Layout (no gBagPockets anywhere): Poké Ball x6, Premier Ball,
//     Potion, Burn Heal, Paralyze Heal, Repel, Escape Rope, Poké Doll; key items
//     Town Map, Mega Ring, Z-Power Ring, Exp. Share - its bag screen's. TMs aren't in it.
//   - Battle: gBattleTypeFlags 0x02022FBC (583), gBattlersCount 0x02023044,
//     gBattlerPartyIndexes 0x02023046 (1 after a switch), gBattlerPositions 0x0202304E,
//     gBattleMons 0x0202305C (EXPANSION_BATTLE_MON: a hit took HP 10 -> 7 at +0x2A, PP
//     56 -> 55 at +0x25; poison poked into status1 hurt the foe), gBattleStruct
//     0x0202352C (monToSwitchIntoId at +0x3C), gEnemyParty 0x02023AAC (on the field it
//     holds the overworld's visible wild Pokémon, with a count of 0).
//     gBattlerControllerFuncs 0x03005580: action / move / bag / party 0x0808BD45 /
//     0x0808C9B9 / 0x0808EC05 / 0x0808EB4D; HandleInputChooseTarget 0x0808C18D by code.
//     gPartyMenu 0x0203E9FC (Emerald's list; a switch to slot 2 worked).
//   - Icons: vanilla-shaped tables of its own (gMonIconTable 0x091CFB80, palette
//     indices 0x091D1DDC, palettes 0x091D2674; item icons 0x092BD4B8); the egg is 1529.
//     Tables: scripts/gen_quetzal_tables.py.
val LEGACY_NATIVE_QUETZAL = LEGACY_NATIVE_EMERALD.copy(
    // gItems: 0x1C-byte entries, description pointer at +0xC (names are a table of their own).
    itemDescs = ItemDescTable(0x091E0594L, 0x1C, 0x0C),
    playerParty = 0x020235CCL,
    playerPartyCount = 0x020235C9L,
    monStride = 0x68,
    partyMonLayout = QUETZAL_PARTY_MON,
    gMain = 0x03002660L,
    saveBlock1Ptr = 0x030055BCL,
    saveBlock2Ptr = 0x030055C0L,
    encryptionKeyOff = 0x2CL,
    moneyOff = 0x918L,
    saveBlock1PosOff = 0x470L,
    objectEvents = 0x02038D10L,
    mapHeader = 0x02038CB0L,
    bagPockets = 0L,
    bagPocketCount = 0,
    bag3 = Bag3Layout(off = 0x940L, splitAt = 0xC08, tailOff = 0x1DD0L, keyItemsBit = 0x6480, pockets = itemPocketsQuetzal),
    battleTypeFlags = 0x02022FBCL,
    battlersCount = 0x02023044L,
    battlerPartyIndexes = 0x02023046L,
    battlerPositions = 0x0202304EL,
    battleMons = 0x0202305CL,
    battleMonLayout = EXPANSION_BATTLE_MON,
    battleStructPtr = 0x0202352CL,
    // gActionSelectionCursor / gMoveSelectionCursor, +0x10 / +0x14 past the gBattleStruct pointer (English and Spanish). Checked
    // headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x0202353CL,
    moveCursor = 0x02023540L,
    monToSwitchIntoOff = 0x3C,
    enemyParty = 0x02023AACL,
    battlerControllerFuncs = 0x03005580L,
    handleInputChooseAction = 0x0808BD45L,
    handleInputChooseMove = 0x0808C9B9L,
    completeWhenChoseItem = 0x0808EC05L,
    waitForMonSelection = 0x0808EB4DL,
    handleInputChooseTarget = 0x0808C18DL,
    partyMenu = 0x0203E9FCL,
    eggSpecies = 1529,
    monIconTable = 0x091CFB80L,
    monIconPaletteIndices = 0x091D1DDCL,
    monIconPaletteTable = 0x091D2674L,
    itemIconTable = 0x092BD4B8L,
    pokedex = POKEDEX_QUETZAL,
    guideTables = GUIDE_TABLES_QUETZAL,
    // Johto's maps (groups 34-35) name their sections from Johto's own table (ROM 0x0922A7E8).
    altMapSecGroups = 34..35,
    // Its money goes past Gen 3's 999,999 (a save at 1,048,458, which START shows as such).
    maxMoney = 9_999_999L,
    quetzalPlacesDefault = 0,
)

// Pokémon Quetzal Spanish Alpha 9 v0 (sha1 fe346b5b…): the same engine rebuilt with Spanish
// dialogue, item descriptions and dex text (its header's GF language word is 7, English's 2).
// Its RAM is English's - every RAM literal pool maps to the same word (scripts/port_retail.py's
// literal matching, run English -> Spanish), and the user's save (`quetzal_es`: six Lv42s on
// Jagged Pass, ₽1,048,458, 3403 Master Balls - its own bag screen shows the same) decodes with
// it. The ROM side moved: tables by those literal pools, the battle handlers by their code
// (all 0x24 earlier), gBattleMoves / gSpeciesNames by the GF header at 0x08000100. Its names
// tables are English's too - the game picks Spanish ones per its IDIOMA options, like English
// (QuetzalNames); only LUGARES defaults to Spanish here.
val LEGACY_NATIVE_QUETZAL_ES = LEGACY_NATIVE_QUETZAL.copy(
    itemDescs = ItemDescTable(0x091E359CL, 0x1C, 0x0C),
    handleInputChooseAction = 0x0808BD21L,
    handleInputChooseMove = 0x0808C995L,
    completeWhenChoseItem = 0x0808EBE1L,
    waitForMonSelection = 0x0808EB29L,
    handleInputChooseTarget = 0x0808C169L,
    monIconTable = 0x091D315CL,
    monIconPaletteIndices = 0x091D53B8L,
    monIconPaletteTable = 0x091D5C50L,
    itemIconTable = 0x092C1A2CL,
    pokedex = POKEDEX_QUETZAL_ES,
    guideTables = GUIDE_TABLES_QUETZAL_ES,
    language = 'S',
    quetzalPlacesDefault = 1,
)

// Pokémon Quetzal English / Spanish Alpha 9 v1 (sha1 c108cec7… / ff008b70…): Alpha 9 v0 rebuilt. Its RAM is v0's - every
// RAM literal pool of v0's code maps to the same word in v1's, and the v0 saves load in it unchanged (`quetzal_v1`,
// `quetzal_es_v1`; the party struct sets three more bits of byte 0x1B, which nothing here reads). Every ROM table moved, each
// checked equal to v0's entry for entry (scripts/gen_quetzal_tables.py --v1: names, species data, pockets, chart, moves'
// power / type, map sections). Its bag is a new record, "BAG4" ([Bag4Layout]), which v1 writes over a v0 save's BAG3 as it
// loads (the same items, quantities and key items); the battle handlers were read in gBattlerControllerFuncs in each menu of a scripted wild
// battle (`quetzal_v1_battle`, `quetzal_es_v1_battle`: Spanish's 0x24 earlier, as in v0), the target handler (doubles
// only) is v0's function, one local more, where v0's literal pool points in v1. One-press picks: v0's cursors, unmoved.
val LEGACY_NATIVE_QUETZAL_V1 = LEGACY_NATIVE_QUETZAL.copy(
    itemDescs = ItemDescTable(0x091E9C6CL, 0x1C, 0x0C),
    handleInputChooseAction = 0x0808DC89L,
    handleInputChooseMove = 0x0808E911L,
    completeWhenChoseItem = 0x08090B21L,
    waitForMonSelection = 0x08090A31L,
    handleInputChooseTarget = 0x0808E071L,
    monIconTable = 0x091D9258L,
    monIconPaletteIndices = 0x091DB4B4L,
    monIconPaletteTable = 0x091DBD4CL,
    itemIconTable = 0x092C7AC0L,
    bag4 = Bag4Layout(off = 0x940L, midOff = 0x29D8L, tailOff = 0x3535L, lists = 0x09201A00L, counts = 0x09201A2CL, pockets = itemPocketsQuetzal),
    pokedex = POKEDEX_QUETZAL_V1,
    guideTables = GUIDE_TABLES_QUETZAL_V1,
    quetzalV1 = true,
)

val LEGACY_NATIVE_QUETZAL_ES_V1 = LEGACY_NATIVE_QUETZAL_ES.copy(
    itemDescs = ItemDescTable(0x091ECCB0L, 0x1C, 0x0C),
    handleInputChooseAction = 0x0808DC65L,
    handleInputChooseMove = 0x0808E8EDL,
    completeWhenChoseItem = 0x08090AFDL,
    waitForMonSelection = 0x08090A0DL,
    handleInputChooseTarget = 0x0808E04DL,
    monIconTable = 0x091DC870L,
    monIconPaletteIndices = 0x091DEACCL,
    monIconPaletteTable = 0x091DF364L,
    itemIconTable = 0x092CC07CL,
    bag4 = Bag4Layout(off = 0x940L, midOff = 0x29D8L, tailOff = 0x3535L, lists = 0x09204A44L, counts = 0x09204A70L, pockets = itemPocketsQuetzal),
    pokedex = POKEDEX_QUETZAL_ES_V1,
    guideTables = GUIDE_TABLES_QUETZAL_ES_V1,
    quetzalV1 = true,
)

// Pokémon Quetzal English Alpha 0.8.4 (sha1 6806c714…) - the release before Alpha 9: the same engine with Hoenn alone
// (34 map groups to Alpha 9's 40), so Emerald's region map and vanilla's gRegionMapEntries (one language a build), and
// vanilla's bag: gBagPockets' 11 pockets of {itemId, quantity ^ key} in SaveBlock1 (no BAG3 stream). Its species and
// items are Alpha 9's first 1520 / 879 (1520 is its egg), its names tables (every language) Alpha 9's, 16 moves have
// other powers (scripts/gen_quetzal_tables.py --v084). No 0.8.4 save: Spanish Alpha 9's loads in it (the English
// saves stand in Kanto / Johto and fade to black) - with its party, money, map and dex as they were, its bag read as
// 0.8.4's slots (the game's own bag screen shows the same: Great Ball x9999 x3 ...). From headless captures of it
// (`quetzal_084`, a walk, `quetzal_084_battle`), every address also English Alpha 9's literal pools mapped into its
// code (scripts/port_retail.py's matching) and stable across the three:
//   - gPlayerParty / Count Alpha 9's (0x020235CC / 0x020235C9); gMain 0x030023C0 (its vblank counter moving);
//     gSaveBlock1Ptr / 2Ptr 0x0300531C / 0x03005320 (moving on every load; SaveBlock1 Alpha 9's, money +0x918, pos
//     +0x470); gMapHeader 0x02038CAC, gObjectEvents 0x02038CE4 (the player's at pos + 7).
//   - gBagPockets 0x0203B904 (11 x {slots*, capacity}: 130 / 60 / 40 / 70 / 70 / 120 / 120 / 160 / 40 / 30 / 80).
//   - Battle: Alpha 9's EWRAM (gBattleTypeFlags .. gEnemyParty, the cursors); gBattlerControllerFuncs 0x030052E0,
//     action / move / bag / party 0x08087FED / 0x08088C61 / 0x0808AE95 / 0x0808ADDD (each seen there), the target
//     handler by its code. Cursors checked headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party
//     menu, 1 + A the bag, a written move 1 + A spent its PP; gPartyMenu 0x0203EA08 (a list: DOWN walks slotId 0 -> 1,
//     A, SHIFT sent slot 1 in).
//   - ROM: its GF header's tables (species / move names, gSpeciesInfo, gItems, gBattleMoves, pics, icons), the rest
//     through the literal pools, each checked against Alpha 9's bytes.
val LEGACY_NATIVE_QUETZAL_V084 = LEGACY_NATIVE_QUETZAL.copy(
    itemDescs = ItemDescTable(0x08F95224L, 0x1C, 0x0C),
    gMain = 0x030023C0L,
    saveBlock1Ptr = 0x0300531CL,
    saveBlock2Ptr = 0x03005320L,
    objectEvents = 0x02038CE4L,
    mapHeader = 0x02038CACL,
    bagPockets = 0x0203B904L,
    bagPocketCount = 11,
    bagPocketOrder = QUETZAL_V084_BAG_POCKET_ORDER,
    bag3 = null,
    battlerControllerFuncs = 0x030052E0L,
    handleInputChooseAction = 0x08087FEDL,
    handleInputChooseMove = 0x08088C61L,
    completeWhenChoseItem = 0x0808AE95L,
    waitForMonSelection = 0x0808ADDDL,
    handleInputChooseTarget = 0x08088435L,
    partyMenu = 0x0203EA08L,
    eggSpecies = 1520,
    monIconTable = 0x08F84C54L,
    monIconPaletteIndices = 0x08F86E8CL,
    monIconPaletteTable = 0x08F8771CL,
    itemIconTable = 0x09060C70L,
    pokedex = POKEDEX_QUETZAL_V084,
    guideTables = GUIDE_TABLES_QUETZAL_V084,
    altMapSecGroups = null,
    quetzalV084 = true,
)

// Pokémon Quetzal Spanish Alpha 0.8.4 (sha1 b6542c7d…): English 0.8.4's RAM (the same literal pools, the same three
// captures' checks: `quetzal_es_084`, `quetzal_es_084_battle`), its handlers 0x24 earlier (as Alpha 9 Spanish's are),
// its ROM tables at its own addresses; its map names are Spanish.
val LEGACY_NATIVE_QUETZAL_V084_ES = LEGACY_NATIVE_QUETZAL_V084.copy(
    itemDescs = ItemDescTable(0x08F94944L, 0x1C, 0x0C),
    handleInputChooseAction = 0x08087FC9L,
    handleInputChooseMove = 0x08088C3DL,
    completeWhenChoseItem = 0x0808AE71L,
    waitForMonSelection = 0x0808ADB9L,
    handleInputChooseTarget = 0x08088411L,
    monIconTable = 0x08F84988L,
    monIconPaletteIndices = 0x08F86BC0L,
    monIconPaletteTable = 0x08F87450L,
    itemIconTable = 0x09061844L,
    pokedex = POKEDEX_QUETZAL_V084_ES,
    guideTables = GUIDE_TABLES_QUETZAL_V084_ES,
    language = 'S',
    quetzalPlacesDefault = 1,
)

// Moemon Quetzal English / Spanish Alpha 0.8.4 Hotfix (sha1 f1035f61… / ae5d88bb…): the 0.8.4 builds with other
// Pokémon pictures - the same code (its battle handlers at the same addresses, each seen in its own captures:
// `quetzal_moemon_084`, `quetzal_moemon_es_084` and their `_battle`s), RAM and names tables; only where the ROM keeps
// its tables moved. Its pictures are read from the player's ROM like every game's, never bundled.
val LEGACY_NATIVE_QUETZAL_MOEMON_V084 = LEGACY_NATIVE_QUETZAL_V084.copy(
    itemDescs = ItemDescTable(0x08F95208L, 0x1C, 0x0C),
    monIconTable = 0x08F84C38L,
    monIconPaletteIndices = 0x08F86E70L,
    monIconPaletteTable = 0x08F87700L,
    itemIconTable = 0x09060C54L,
    pokedex = POKEDEX_QUETZAL_MOEMON_V084,
    guideTables = GUIDE_TABLES_QUETZAL_MOEMON_V084,
)

val LEGACY_NATIVE_QUETZAL_MOEMON_V084_ES = LEGACY_NATIVE_QUETZAL_V084_ES.copy(
    itemDescs = ItemDescTable(0x08F94928L, 0x1C, 0x0C),
    monIconTable = 0x08F8496CL,
    monIconPaletteIndices = 0x08F86BA4L,
    monIconPaletteTable = 0x08F87434L,
    itemIconTable = 0x09061828L,
    pokedex = POKEDEX_QUETZAL_MOEMON_V084_ES,
    guideTables = GUIDE_TABLES_QUETZAL_MOEMON_V084_ES,
)

// Pokemon Amethyst v1.3.0 - FireRed-based (BPRE), no source access. Kept
// vanilla FireRed rev 0's RAM layout entirely unchanged (party/bag/location
// all confirmed live with zero address changes - see the README's Supported
// ROMs table), so LEGACY_NATIVE_FIRERED_REV0's RAM fields all apply as-is. Only the
// ROM-embedded icon-graphics tables needed re-deriving, the same
// literal-pool technique used for Gaia:
//
// - monIconPaletteTable: UNCHANGED at vanilla's own 0x083D4038 - found
//   as-is (no re-deriving needed) as a literal constant repeated across
//   several palette-lookup functions near GetMonIconTiles.
// - monIconPaletteIndices: MOVED, to 0x09C01408 - the literal every vanilla
//   reader of gMonIconPaletteIndices now holds (0x08096EC0, 0x080970D8, ...).
//   Vanilla's 0x083D3E80 was used at first and looked right on the one icon
//   checked (Tepig's index is 0 in both), but 757 of the 1268 species differ.
// - monIconTable: MOVED. GetMonIconTiles's own vanilla rev-0 address
//   (0x08097028) still holds byte-identical CODE (only the per-species
//   tile-graphics DATA relocated, unsurprising given ~1267 species now vs.
//   vanilla's 411 means the old table's space was long outgrown) - its
//   trailing literal pool now holds 0x09C018FC instead of vanilla's own
//   table address. Decoded species 551 (the user's real Tepig) through all
//   three of these addresses end-to-end and it rendered as a real,
//   recognizable two-frame Pokémon icon - not a guess, visually confirmed.
// - itemIconTable: MOVED, to 0x083DB028. Vanilla rev 0's GetItemIconGfxPtr
//   (0x08098974, address obtained by building the pinned pokefirered commit
//   completely unpatched in the project's own Docker image - confirmed
//   byte-identical to real retail rev 0, sha1 41cb23d8… - and reading
//   arm-none-eabi-nm's own symbol table, not guessed) is ALSO still
//   byte-identical code, same story as GetMonIconTiles. Decoded item id 13
//   (the user's real Potion, LZ77 tiles+palette) and it rendered as a real,
//   recognizable potion-bottle icon.
val LEGACY_NATIVE_AMETHYST = LEGACY_NATIVE_FIRERED_REV0.copy(
    itemDescs = vanillaItems(0x0872DB0CL), // repointed; v1.4.1 keeps the address
    monIconTable = 0x09C018FCL,
    monIconPaletteIndices = 0x09C01408L,
    itemIconTable = 0x083DB028L,
    pokedex = POKEDEX_AMETHYST,
    guideTables = GUIDE_TABLES_AMETHYST,
    // Battle (headless, a wild FLABEBE on Route 17: `amethyst_battle`): rev 0's battle RAM and
    // BattlePokemon hold; the three below are rev 1's (missing from rev 0's config), each checked
    // there - gEnemyParty holds the FLABEBE, gBattlerPartyIndexes[0] and monToSwitchIntoId moved
    // 0 -> 1 / 06 -> 01 -> 06 through a SHIFT. The action / move handlers' slots hold 4-byte stubs
    // jumping into the hack's own; target select is its own too (0x088E8245, from the move
    // handler's literal pool - no single battle reaches it, so not seen live).
    enemyParty = 0x0202402CL,
    battlerPartyIndexes = 0x02023BCEL,
    battleStructPtr = 0x02023FE8L,
    monToSwitchIntoOff = 0x5C,
    handleInputChooseTarget = 0x088E8245L,
    // FireRed's battle menu cursors (CFRU), checked headless on v1.3.0 and v1.4.1 like Unbound's: RIGHT / DOWN walk
    // 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
)

// Pokemon Amethyst v1.4.1: the same RAM (a v1.3.0 save loads; its EWRAM after
// boot differs from v1.3.0's by 41 bytes) and vanilla code; the hack's own data
// moved, found through the literal pools of the code that reads it. It has 26
// more species (Hisuian forms at 1234, the Gigantamax forms after them), so its
// names / gender ratios are its own (ActiveTables' amethystV141).
val LEGACY_NATIVE_AMETHYST_V1_4_1 = LEGACY_NATIVE_AMETHYST.copy(
    monIconTable = 0x09AD8B50L,
    monIconPaletteIndices = 0x09AD8642L,
    pokedex = POKEDEX_AMETHYST_V1_4_1,
    guideTables = GUIDE_TABLES_AMETHYST_V1_4_1,
    handleInputChooseTarget = 0x0890BE01L, // its moved target-select handler, found the same way
)

// Pokémon Orange Islands (BPRE rev 0, 16 MB, sha1 8bac897d…; Beta 5.7 d2e3800e… differs from it
// in 80 bytes of map text / data and shares this): FireRed 1.0 edited in place, no source. Its
// code is retail rev 0's byte for byte where the config points (the five battle handlers and
// their glue, ScriptContext_SetupScript), and so is its RAM - the headless capture of the
// user's save (`orange_islands`: PIKACHU Lv10 on VALENCIA ISLAND, ₽2000, POTION / ORANGE PASS /
// OLD ROD) and a scripted wild battle (`orange_islands_battle`) decode with rev 0's addresses
// plus rev 1's three battle fields (the same RAM in both revisions). Its tables are retail
// rev 0's edited in place, but for the type names / chart (a 24th type, CRYSTL) and
// gWildMonHeaders (0x08A1B1B0), which it repointed: scripts/gen_orange_islands_tables.py. Its
// region map is its own Orange Archipelago on FireRed's region_map.c (RomRegionMap).
val LEGACY_NATIVE_ORANGE_ISLANDS = LEGACY_NATIVE_FIRERED_REV0.copy(
    // FireRed's battle menu cursors (retail rev 0's EWRAM), checked headless like Unbound's: RIGHT / DOWN walk
    // 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = LEGACY_NATIVE_FIRERED_REV1.actionCursor,
    moveCursor = LEGACY_NATIVE_FIRERED_REV1.moveCursor,
    pokedex = POKEDEX_ORANGE_ISLANDS,
    guideTables = GUIDE_TABLES_ORANGE_ISLANDS,
    enemyParty = LEGACY_NATIVE_FIRERED_REV1.enemyParty,
    battlerPartyIndexes = LEGACY_NATIVE_FIRERED_REV1.battlerPartyIndexes,
    battleStructPtr = LEGACY_NATIVE_FIRERED_REV1.battleStructPtr,
    monToSwitchIntoOff = LEGACY_NATIVE_FIRERED_REV1.monToSwitchIntoOff,
)

// Pokémon Ultra Violet v1.22 (16 MB, sha1 3b6c51d8…): FireRed 1.0 edited in place, under a
// game code of its own (PUVV, revision byte 0). Every literal pool the config points through
// still names rev 0's address - the party / save / battle RAM, gItems, the icon and dex
// tables, gTrainers, gLevelUpLearnsets - and the headless capture of the user's save
// (`ultra_violet`: CHARMANDER Lv6 10/21, SCRATCH / GROWL, POTION, ₽3080 in PALLET TOWN)
// decodes with rev 0's addresses. It moved gWildMonHeaders (0x087339C0) and gMapGroups
// (0x0872AF04) for its new maps, and renamed a few things (GameKind.FIRERED with
// ActiveTables' fireRedHack: UltraVioletGen.kt). Its region map is FireRed's byte for byte.
val LEGACY_NATIVE_ULTRA_VIOLET = LEGACY_NATIVE_FIRERED_REV0.copy(
    // FireRed's battle menu cursors (retail rev 0's EWRAM), checked headless like Unbound's: RIGHT / DOWN walk
    // 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = LEGACY_NATIVE_FIRERED_REV1.actionCursor,
    moveCursor = LEGACY_NATIVE_FIRERED_REV1.moveCursor,
    pokedex = POKEDEX_ULTRA_VIOLET,
    guideTables = GUIDE_TABLES_ULTRA_VIOLET,
    enemyParty = LEGACY_NATIVE_FIRERED_REV1.enemyParty,
    battlerPartyIndexes = LEGACY_NATIVE_FIRERED_REV1.battlerPartyIndexes,
    battleStructPtr = LEGACY_NATIVE_FIRERED_REV1.battleStructPtr,
    monToSwitchIntoOff = LEGACY_NATIVE_FIRERED_REV1.monToSwitchIntoOff,
)

// Pokémon FireRed Rocket Edition (16 MB, BPRE rev 0, sha1 033d0537…): FireRed 1.0 edited in place, no
// source. Every literal pool the config points through still names rev 0's address but for the moves
// (gMoveNames 0x08901800 / gBattleMoves 0x08900000: 132 added), gTypeNames / gTypeEffectiveness (a FAIRY
// type, 23) and its ability names (0x08950000) - and retail FireRed's save loads in it (it keeps the
// SaveBlocks): the headless capture (`rocket_edition`) decodes with rev 0's addresses. GameKind.FIRERED
// with ActiveTables' fireRedHack (RocketEditionGen.kt, gen_firered_hack_tables.py); FireRed's region map.
val LEGACY_NATIVE_ROCKET_EDITION = LEGACY_NATIVE_ULTRA_VIOLET.copy(
    pokedex = POKEDEX_ROCKET_EDITION,
    guideTables = GUIDE_TABLES_ROCKET_EDITION,
)

// Pokémon Ash Gray v4.5.3 (16 MB, BPRE rev 0, sha1 1ee1557d…; its Fork Bug Fix v1.0 build, 92ed8214…,
// differs in 493 bytes of scripts and shares this): FireRed 1.0 edited in place, no source. Its tables
// sit at rev 0's addresses, edited in place, but for gWildMonHeaders (0x088CD1F8), gMapGroups (0x0873E2F4)
// and its region map's art (its own Kanto and Orange Islands: RomRegionMap); retail FireRed's save loads
// in both builds (`ash_gray`, `ash_gray_fork`). GameKind.FIRERED with ActiveTables' fireRedHack
// (AshGrayGen.kt, gen_firered_hack_tables.py).
val LEGACY_NATIVE_ASH_GRAY = LEGACY_NATIVE_ULTRA_VIOLET.copy(
    pokedex = POKEDEX_ULTRA_VIOLET,
    guideTables = GUIDE_TABLES_ASH_GRAY,
)

// Pokémon Ghost Grey (JoeyZeed; 32 MB, BPRE rev 0): v1.0.2 (sha1 74337272…) and v1.0.4 (0dafc011…) share every
// table; v1.0.1 (20dfaea1…) differs in one move's type, so it is its own config (ActiveTables' fireRedHack picks
// the text by config). FireRed 1.0 edited in place with HexManiacAdvance, not CFRU: Rocket Edition's repointed
// moves / types / ability names, its ghosts in FireRed's species slots, gWildMonHeaders moved to 0x09300100, and
// every other literal pool the config points through still naming rev 0's address. Its save (a 1.0.3 one, from
// its Discord) loads in all three builds: the `ghost_grey*` fixtures decode with rev 0's RAM. Its region map's art
// is its own grey (RomRegionMap).
val LEGACY_NATIVE_GHOST_GREY = LEGACY_NATIVE_ULTRA_VIOLET.copy(
    pokedex = POKEDEX_GHOST_GREY,
    guideTables = GUIDE_TABLES_GHOST_GREY,
)

val LEGACY_NATIVE_GHOST_GREY_V1_0_1 = LEGACY_NATIVE_GHOST_GREY.copy()

// Pokémon Elite Redux 2.65.4.1b (32 MB, BPEE, sha1 2f3e17e5…; 2.65.3b below): its own pokeemerald rebuild (gcc, from
// Inclement Emerald), nothing at a retail address. Mapped headless from a save off its Discord ("v2.65 Beta2.1", Mia,
// six Lv20s), which loads in both builds: every RAM global from dumps (the field, a scripted wild battle's menus, its
// party menu) and checked against the game's own screens - the party menu, LUCINEON's summary, the bag, the title's
// POKéDEX 29. Its party mon is a packed plaintext 0x4C bytes ([ELITE_REDUX_PARTY_MON]), its battle mon 0x68
// ([ELITE_REDUX_BATTLE_MON]); the bag one "owned" bit per item ([ItemFlagsBag]); money plain at SaveBlock1 + 0x400 (the
// game zeroes the encryption key on every load); its battle menu has no BAG and other cursor values
// (BattleInputController.MenuCells.ELITE_REDUX). Tables from its ROM: gen_elite_redux_tables.py. A scripted trainer battle
// (`elite_redux_trainer*`: ROXANNE) checked the FOE TEAM (gEnemyParty, the same packed mons), the foe out / next
// (gBattlerPartyIndexes, monToSwitchIntoId +0x5E) and FOE DETAILS; HandleInputChooseTarget was read live in a forced double
// battle (a single battle never opens the target screen in this game). The two builds share the
// save layout, IWRAM and the battle globals up to gBattlerPartyIndexes; the rest of EWRAM sits 8 higher in 2.65.4.1b.
val LEGACY_NATIVE_ELITE_REDUX_V2_65_4_1B = NativeConfig(
    playerParty = 0x02039910L,
    playerPartyCount = 0x0203990DL,
    enemyParty = 0x02039AD8L,
    monStride = 0x4C,
    partyMonLayout = ELITE_REDUX_PARTY_MON,
    battleMons = 0x0201C514L,
    battleMonLayout = ELITE_REDUX_BATTLE_MON,
    battlerPositions = 0x0201C490L,
    battlersCount = 0x0201C484L,
    battleTypeFlags = 0x0201C3F4L,
    battlerPartyIndexes = 0x0201C488L,
    battleStructPtr = 0x0201CB10L,
    monToSwitchIntoOff = 0x5E,
    actionCursor = 0x0201CB20L,
    moveCursor = 0x0201CB24L,
    battlerControllerFuncs = 0x03002080L,
    handleInputChooseAction = 0x0805BE8DL,
    handleInputChooseMove = 0x0805CA01L,
    handleInputChooseTarget = 0x0805C305L,
    waitForMonSelection = 0x0805E415L,
    partyMenu = 0x0203960CL,
    gMain = 0x03003420L,
    saveBlock1Ptr = 0x0300340CL,
    saveBlock2Ptr = 0x03003410L,
    objectEvents = 0x02021DC4L,
    mapHeader = 0x020277C0L,
    bagPockets = 0,
    bagPocketCount = 0,
    encryptionKeyOff = -1,
    moneyOff = 0x400L,
    itemFlagsBag = ItemFlagsBag(0x40AL, 440, itemPocketsEliteRedux),
    monIconTable = 0x08E31FA0L,
    monIconPaletteIndices = 0x08E37A50L,
    monIconPaletteTable = 0x08E38848L,
    itemIconTable = 0x08AF1FF0L,
    itemDescs = ItemDescTable(0x08AC6B24L, 40, 0x10, idOff = 4, count = 440),
    eggSpecies = 2901,
    pokedex = POKEDEX_ELITE_REDUX_V2_65_4_1B,
    guideTables = GUIDE_TABLES_ELITE_REDUX_V2_65_4_1B,
)

// Pokémon Elite Redux 2.65.3b (sha1 5b38a681…): 2.65.4.1b's layout, its EWRAM past the battle globals 8 lower, every ROM
// table and handler at its own address (the same save and screens checked).
val LEGACY_NATIVE_ELITE_REDUX_V2_65_3B = LEGACY_NATIVE_ELITE_REDUX_V2_65_4_1B.copy(
    playerParty = 0x02039908L,
    playerPartyCount = 0x02039905L,
    enemyParty = 0x02039AD0L,
    battleMons = 0x0201C50CL,
    battleStructPtr = 0x0201CB08L,
    actionCursor = 0x0201CB18L,
    moveCursor = 0x0201CB1CL,
    handleInputChooseAction = 0x0805C2D5L,
    handleInputChooseMove = 0x0805CE89L,
    handleInputChooseTarget = 0x0805C751L,
    waitForMonSelection = 0x0805E7E1L,
    partyMenu = 0x02039604L,
    objectEvents = 0x02021DBCL,
    mapHeader = 0x020277B8L,
    itemFlagsBag = ItemFlagsBag(0x40AL, 440, itemPocketsEliteRedux265_3),
    monIconTable = 0x08E1FB28L,
    monIconPaletteIndices = 0x08E255D8L,
    monIconPaletteTable = 0x08E263D0L,
    itemIconTable = 0x08AE4B0CL,
    itemDescs = ItemDescTable(0x08AB9640L, 40, 0x10, idOff = 4, count = 440),
    pokedex = POKEDEX_ELITE_REDUX_V2_65_3B,
    guideTables = GUIDE_TABLES_ELITE_REDUX_V2_65_3B,
)

// Pokémon Team Rocket Edition: Dragonsden Version (32 MB, BPEE, sha1 40780347…, unversioned; DragonsdenTV): its own closed
// pokeemerald build (gcc) on an older pokeemerald-expansion - vanilla's struct shapes with expansion's ids (National Dex
// species to 1395, 755 moves, expansion's items). Mapped headless from a save off its Discord ("Pokemon - Team Rocket
// Edition.srm", Jason in CELADON CITY, six Pokémon Lv77-79), every address checked on the game's own screens:
//   - gPlayerParty 0x02025170 / gPlayerPartyCount 0x0202516D, gEnemyParty 0x020253C8 (+0x258, as in vanilla): 100-byte
//     encrypted mons with the flags folded into +0x12 ([TEAM_ROCKET_PARTY_MON]); the egg is species 1395.
//   - gSaveBlock1Ptr / 2Ptr 0x0300524C / 0x03005250 (gPokemonStoragePtr after them); vanilla Emerald's SaveBlock1 up to
//     money (+0x490 XOR SaveBlock2 +0xAC: ₽3,000,000, its AddMoney caps at 99,999,999).
//   - gMain 0x030032E0 (vblankCounter1 +0x20 counted 16 in 16 frames), gMapHeader 0x020382D8 (mapsec 0x5E CELADON CITY;
//     mapType 8 in its Pokémon Center), gObjectEvents 0x02038314 (the player's coords = pos + 7).
//   - gBagPockets 0x0203ADDC: eight pockets ([TEAM_ROCKET_BAG_POCKET_ORDER]), quantities XOR the key; the battle's bag
//     screen showed the Items pocket's five.
//   - Battle (a scripted wild RATTATA: its setwildbattle takes a second mon's fields): gBattleTypeFlags 0x02024BB8,
//     gBattlersCount 0x02024C38, gBattlerPartyIndexes 0x02024C3A, gBattlerPositions 0x02024C42, gBattleMons 0x02024C50
//     ([TEAM_ROCKET_BATTLE_MON]), gBattleStruct 0x020250EC with the menus' cursors +0x10 / +0x14 (RIGHT walked the action
//     cursor 0 -> 1, DOWN 1 -> 3 ran). gBattlerControllerFuncs 0x03005220: action / move / bag / party read live
//     (0x08082291 / 0x08082CE1 / 0x08084D71 / 0x08084CB5), HandleInputChooseTarget 0x08082569 (HandleInputChooseMove's
//     two stores). gPartyMenu 0x0203DE64 (Emerald's list, slotId +9 walked 0 -> 1 on DOWN).
//   - Icons: vanilla-shaped tables from its GF ROM header (0x08000138..), sItemIconTable 0x08D3B894; gItems 0x08C833B0.
//     Names / types / map sections: scripts/gen_team_rocket_tables.py.
val LEGACY_NATIVE_TEAM_ROCKET = LEGACY_NATIVE_EMERALD.copy(
    itemDescs = vanillaItems(0x08C833B0L, 923),
    playerParty = 0x02025170L,
    playerPartyCount = 0x0202516DL,
    enemyParty = 0x020253C8L,
    partyMonLayout = TEAM_ROCKET_PARTY_MON,
    battleMons = 0x02024C50L,
    battleMonLayout = TEAM_ROCKET_BATTLE_MON,
    battlerPositions = 0x02024C42L,
    battlersCount = 0x02024C38L,
    battleTypeFlags = 0x02024BB8L,
    battlerPartyIndexes = 0x02024C3AL,
    battleStructPtr = 0x020250ECL,
    monToSwitchIntoOff = 0x62,
    actionCursor = 0x020250FCL,
    moveCursor = 0x02025100L,
    battlerControllerFuncs = 0x03005220L,
    handleInputChooseAction = 0x08082291L,
    handleInputChooseMove = 0x08082CE1L,
    completeWhenChoseItem = 0x08084D71L,
    waitForMonSelection = 0x08084CB5L,
    handleInputChooseTarget = 0x08082569L,
    partyMenu = 0x0203DE64L,
    gMain = 0x030032E0L,
    saveBlock1Ptr = 0x0300524CL,
    saveBlock2Ptr = 0x03005250L,
    objectEvents = 0x02038314L,
    mapHeader = 0x020382D8L,
    bagPockets = 0x0203ADDCL,
    bagPocketCount = 8,
    bagPocketOrder = TEAM_ROCKET_BAG_POCKET_ORDER,
    encryptionKeyOff = 0xACL,
    moneyOff = 0x490L,
    maxMoney = 99_999_999L,
    monIconTable = 0x08C722E8L,
    monIconPaletteIndices = 0x08C74754L,
    monIconPaletteTable = 0x08C75070L,
    itemIconTable = 0x08D3B894L,
    eggSpecies = 1395,
    guideTables = GUIDE_TABLES_TEAM_ROCKET,
)

// Pokémon Sword and Shield Ultimate Plus v1.2.1.2 (32 MB, sha1 afe4c8e0…): Galar on CFRU, so
// FireRed 1.0's code and RAM with its data repointed. The words FireRed loads its tables from
// hold the hack's: gItems 0x093C0000 (Radical Red's address too), gMonIconTable 0x099F1EB8,
// gMonIconPaletteIndices 0x099F1980 (the palettes stay vanilla's), sItemIconTable 0x093C8100.
// The headless capture of the user's save (`sword_shield_up`: SCORBUNNY Lv5 21/21, GROWL /
// TACKLE, ₽5000, POTION x2 / SOOTHE BELL / OLD ROD) decodes with rev 0's RAM. Its Casual / Performance /
// Casual and Performance patches keep every table and the RAM: the same save and battle decode in each.
val LEGACY_NATIVE_SWORD_SHIELD = LEGACY_NATIVE_FIRERED_REV0.copy(
    // FireRed's battle menu cursors (CFRU), checked headless like Unbound's (a scripted WOOLOO battle on the base patch).
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
    itemDescs = vanillaItems(0x093C0000L),
    monIconTable = 0x099F1EB8L,
    monIconPaletteIndices = 0x099F1980L,
    itemIconTable = 0x093C8100L,
    pokedex = POKEDEX_SWORD_SHIELD,
    guideTables = GUIDE_TABLES_SWORD_SHIELD,
    // Battle (headless, a scripted wild WOOLOO: `sword_shield_up_battle`): rev 0's battle RAM and
    // BattlePokemon hold, gEnemyParty holds the WOOLOO, and rev 0's action handler is what
    // gBattlerControllerFuncs points at while the action menu waits (CFRU patched it in place).
    enemyParty = LEGACY_NATIVE_FIRERED_REV1.enemyParty,
    battlerPartyIndexes = LEGACY_NATIVE_FIRERED_REV1.battlerPartyIndexes,
    battleStructPtr = LEGACY_NATIVE_FIRERED_REV1.battleStructPtr,
    monToSwitchIntoOff = LEGACY_NATIVE_FIRERED_REV1.monToSwitchIntoOff,
)

// Pokémon GS Chronicles v2.7.6 (Ruki Studios; 32 MB, sha1 25f8840e…): Johto and Kanto on CFRU,
// FireRed 1.0's code and RAM with its data repointed. Every RAM global's literal-pool word is rev
// 0's (gPlayerParty 745 of 745, gSaveBlock1Ptr 444 of 452, gMain 585 of 586, gBagPockets 15 of 15,
// the battle globals all of theirs); the tables' words hold the hack's: gItems 0x08F70000 (indexed
// by slot), gMonIconTable 0x097DA810, gMonIconPaletteIndices 0x097DA2EE (the palettes stay
// vanilla's), sItemIconTable 0x08F78000. No save of the user's: the `gs_chronicles*` fixtures are
// a new game played headless (CYNDAQUIL / TOTODILE / CHIKORITA in NEW BARK TOWN; the READMEs).
val LEGACY_NATIVE_GS_CHRONICLES = LEGACY_NATIVE_FIRERED_REV0.copy(
    actionCursor = 0x02023FF8L,
    moveCursor = 0x02023FFCL,
    itemDescs = vanillaItems(0x08F70000L),
    monIconTable = 0x097DA810L,
    monIconPaletteIndices = 0x097DA2EEL,
    itemIconTable = 0x08F78000L,
    pokedex = POKEDEX_GS_CHRONICLES,
    guideTables = GUIDE_TABLES_GS_CHRONICLES,
    enemyParty = LEGACY_NATIVE_FIRERED_REV1.enemyParty,
    battlerPartyIndexes = LEGACY_NATIVE_FIRERED_REV1.battlerPartyIndexes,
    battleStructPtr = LEGACY_NATIVE_FIRERED_REV1.battleStructPtr,
    monToSwitchIntoOff = LEGACY_NATIVE_FIRERED_REV1.monToSwitchIntoOff,
)

val LEGACY_NATIVE_RECHARGED_YELLOW_V1_8_0 = NativeConfig(
    itemDescs = ItemDescTable(0x08807A38L, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    playerParty = 0x02038554L,
    playerPartyCount = 0x02038551L,
    battleMons = 0x02001460L,
    battlerPositions = 0x02001450L,
    battlersCount = 0x02001444L,
    battleTypeFlags = 0x020003C0L,
    gMain = 0x03003B64L,
    saveBlock1Ptr = 0x03005AB4L,
    saveBlock2Ptr = 0x03005AB8L,
    objectEvents = 0x02005CCCL,
    mapHeader = 0x0200B6CCL,
    bagPockets = 0x0200B768L,
    bagPocketCount = 6,
    bagPocketOrder = RECHARGED_YELLOW_BAG_POCKET_ORDER,
    encryptionKeyOff = -1,
    moneyOff = 0x29CL,
    monIconTable = 0x0886B7F0L,
    monIconPaletteIndices = 0x0886BED0L,
    monIconPaletteTable = 0x0886C088L,
    itemIconTable = 0x0880C6ECL,
    battlerControllerFuncs = 0x030048F8L,
    handleInputChooseAction = 0x08045F89L,
    handleInputChooseMove = 0x080464EDL,
    completeWhenChoseItem = 0x080482A1L,
    waitForMonSelection = 0x080481C9L,
    handleInputChooseTarget = 0x08046201L,
    enemyParty = 0x020387ACL,
    battlerPartyIndexes = 0x02001448L,
    battleStructPtr = 0x02001880L,
    // gActionSelectionCursor / gMoveSelectionCursor, +0x10 / +0x14 past the gBattleStruct pointer (every release: the battle globals sit below its 1.9 EWRAM shift). Checked
    // headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x02001890L,
    moveCursor = 0x02001894L,
    monToSwitchIntoOff = 0x5C,
    partyMenu = 0x020384CCL,
    pokedex = POKEDEX_RECHARGED_YELLOW_V1_8_0,
    guideTables = GUIDE_TABLES_RECHARGED_YELLOW_V1_8_0,
    // AddMoney (0x081391D8) caps at 9,999,999, and the card prints 7 digits.
    maxMoney = 9_999_999L,
    trainerCard = TRAINER_CARD_RECHARGED_YELLOW_V1_8_0,
)

val LEGACY_NATIVE_RECHARGED_YELLOW_V1_9_0 = LEGACY_NATIVE_RECHARGED_YELLOW_V1_8_0.copy(
    itemDescs = ItemDescTable(0x088074ACL, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    playerParty = 0x0203855CL,
    playerPartyCount = 0x02038559L,
    objectEvents = 0x02005CD4L,
    mapHeader = 0x0200B6D4L,
    bagPockets = 0x0200B770L,
    monIconTable = 0x0886B2B0L,
    monIconPaletteIndices = 0x0886B990L,
    monIconPaletteTable = 0x0886BB48L,
    itemIconTable = 0x0880C160L,
    handleInputChooseAction = 0x08045F5DL,
    handleInputChooseMove = 0x080464C1L,
    completeWhenChoseItem = 0x08048275L,
    waitForMonSelection = 0x0804819DL,
    handleInputChooseTarget = 0x080461D5L,
    enemyParty = 0x020387B4L,
    partyMenu = 0x020384D4L,
    pokedex = POKEDEX_RECHARGED_YELLOW_V1_9_0,
    guideTables = GUIDE_TABLES_RECHARGED_YELLOW_V1_9_0,
    trainerCard = TRAINER_CARD_RECHARGED_YELLOW_V1_9_0,
)

val LEGACY_NATIVE_RECHARGED_YELLOW_V1_9_4 = LEGACY_NATIVE_RECHARGED_YELLOW_V1_9_0.copy(
    itemDescs = ItemDescTable(0x088E74B0L, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    monIconTable = 0x0894B2B4L,
    monIconPaletteIndices = 0x0894B994L,
    monIconPaletteTable = 0x0894BB4CL,
    itemIconTable = 0x088EC164L,
    handleInputChooseAction = 0x08045F55L,
    handleInputChooseMove = 0x080464B9L,
    completeWhenChoseItem = 0x0804826DL,
    waitForMonSelection = 0x08048195L,
    handleInputChooseTarget = 0x080461CDL,
    pokedex = POKEDEX_RECHARGED_YELLOW_V1_9_4,
    guideTables = GUIDE_TABLES_RECHARGED_YELLOW_V1_9_4,
    trainerCard = TRAINER_CARD_RECHARGED_YELLOW_V1_9_4,
)

val LEGACY_NATIVE_RECHARGED_YELLOW_V1_9_7 = LEGACY_NATIVE_RECHARGED_YELLOW_V1_9_4.copy(
    itemDescs = ItemDescTable(0x0890F960L, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    saveBlock1Ptr = 0x03005AD0L,
    saveBlock2Ptr = 0x03005AD4L,
    monIconTable = 0x089740C4L,
    monIconPaletteIndices = 0x089747A4L,
    monIconPaletteTable = 0x0897495CL,
    itemIconTable = 0x08914614L,
    pokedex = POKEDEX_RECHARGED_YELLOW_V1_9_7,
    guideTables = GUIDE_TABLES_RECHARGED_YELLOW_V1_9_7,
    trainerCard = TRAINER_CARD_RECHARGED_YELLOW_V1_9_7,
)

// Pokémon Recharged Emerald (Jaizu; BPEE, 16 MB, sha1 67f44fb4… v2.2.5 / 56c97772… v2.2.8 / 08b9c790… Rebalanced
// v2.2.8): Recharged Yellow's treatment of Emerald - a pokeemerald rebuild with modern gcc, no source, nothing at a
// retail address - but Emerald's ids, gTypeEffectiveness and party / bag / region map art, so it runs as EMERALD with
// its own names laid over (ActiveTables' emeraldHack, gen_recharged_emerald_tables.py). The user has no save of it:
// retail Emerald's loads, but SaveBlock1 differs past the bag, so the fixtures boot it rewritten by
// scripts/convert_save_recharged_emerald.py (`recharged_emerald*`: KADABRA Lv36 first of six on ROUTE 114).
//   - SaveBlock1 (its GF ROM header + SetBagItemsPointers): Items 60 slots at +0x560, Key Items 50 at +0x650, Poké
//     Balls 32 at +0x718, TMs at +0x798, Berries at +0x898, a Medicine pocket (50) at +0x366C, flags +0x1344, vars
//     +0x1470; party +0x238, money +0x490 and SaveBlock2's key +0xAC are retail's (the money decrypts as in retail).
//   - gSaveBlock1Ptr / 2Ptr (v2.2.5 0x030071F8, v2.2.8 0x03007214, Rebalanced 0x03007220; 922 / 760 references),
//     gPlayerParty 0x020373C8 (Rebalanced +0x14) with its count 3 bytes before and gEnemyParty after it, gMain
//     0x030036D4 (+0x24 counted 16 between dumps 16 frames apart), gMapHeader 0x0200C1AC (mapsec 0x1D ROUTE 114),
//     gObjectEvents 0x020067AC (the player at pos + 7), gBagPockets 0x0200C218: six {ptr, capacity} pockets in each
//     item's own pocket order (gItems +0x1E, 48-byte records as in Recharged Yellow) - Items, Medicine, Poké Balls,
//     TMs, Berries, Key Items.
//   - Battle (a scripted wild ZIGZAGOON through ScriptContext_SetupScript - v2.2.5 0x081AF031, v2.2.8 0x081B09D5,
//     Rebalanced 0x081B0AF1, the function that loads gScriptCmdTable and sets the script pointer): Recharged
//     Yellow's order 8 bytes lower - gBattleTypeFlags 0x020003B8, gBattlersCount 0x0200143C, gBattlerPartyIndexes
//     0x02001440, gBattlerPositions 0x02001448, gBattleMons 0x02001458 (vanilla's 0x58 bytes), the gBattleStruct
//     pointer 0x02001878 with the cursors +0x10 / +0x14 (RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the
//     party menu, a written move 1 + A spent CONFUSION's PP). gBattlerControllerFuncs 0x03004468: action / move /
//     bag / party read live in each menu; the target handler is the one the move handler's literal pool names.
//     gPartyMenu (slotId +9 walked 0 -> 1 -> 2 with DOWN: Emerald's list); POKéMON, DOWN, A, SHIFT put party slot 1
//     into gBattlerPartyIndexes[0].
//   - ROM: the GF ROM header gives the icon tables, gSpeciesNames, gBaseStats and gItems; sItemIconTable is the pair
//     table whose entry 1 points at retail's MASTER BALL icon bytes. The Pokédex / guide tables are retail's shapes,
//     found by their bytes (Pokedex.kt, GuideRom.kt).
//   - USE / GIVE / TAKE (`fieldItems`): FieldItemsRechargedEmeraldGen.kt, found through the game's own callers by
//     scripts/find_field_items_recharged_emerald.py and checked headless on each build.
// Rebalanced puts 4 bytes in front of both save blocks (its header's offsets are all 4 higher), so its SaveBlock1 /
// SaveBlock2 fields sit 4 later; its RAM globals are v2.2.8's but for the party (+0x14) and the save-block pointers.
private val LEGACY_NATIVE_RECHARGED_EMERALD = NativeConfig(
    itemDescs = null,
    playerParty = 0x020373C8L,
    playerPartyCount = 0x020373C5L,
    battleMons = 0x02001458L,
    battlerPositions = 0x02001448L,
    battlersCount = 0x0200143CL,
    battleTypeFlags = 0x020003B8L,
    gMain = 0x030036D4L,
    saveBlock1Ptr = 0,
    saveBlock2Ptr = 0,
    objectEvents = 0x020067ACL,
    mapHeader = 0x0200C1ACL,
    bagPockets = 0x0200C218L,
    bagPocketCount = 6,
    // Recharged Yellow's order, Medicine folded into Items.
    bagPocketOrder = RECHARGED_YELLOW_BAG_POCKET_ORDER,
    encryptionKeyOff = 0xACL,
    moneyOff = 0x490L,
    battlerControllerFuncs = 0x03004468L,
    enemyParty = 0x02037620L,
    battlerPartyIndexes = 0x02001440L,
    battleStructPtr = 0x02001878L,
    actionCursor = 0x02001888L,
    moveCursor = 0x0200188CL,
    monToSwitchIntoOff = 0x5C,
    partyMenu = 0x02037338L,
)

val LEGACY_NATIVE_RECHARGED_EMERALD_V2_2_5 = LEGACY_NATIVE_RECHARGED_EMERALD.copy(
    itemDescs = ItemDescTable(0x088AB850L, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    saveBlock1Ptr = 0x030071F8L,
    saveBlock2Ptr = 0x030071FCL,
    monIconTable = 0x088DADD4L,
    monIconPaletteIndices = 0x088DB4B4L,
    monIconPaletteTable = 0x088DB66CL,
    itemIconTable = 0x088B04D4L,
    handleInputChooseAction = 0x080446D9L,
    handleInputChooseMove = 0x08044C49L,
    completeWhenChoseItem = 0x08046AE5L,
    waitForMonSelection = 0x08046A0DL,
    handleInputChooseTarget = 0x08044939L,
    pokedex = POKEDEX_RECHARGED_EMERALD_V2_2_5,
    guideTables = GUIDE_TABLES_RECHARGED_EMERALD_V2_2_5,
    fieldItems = FIELD_ITEMS_RECHARGED_EMERALD_V2_2_5,
)

val LEGACY_NATIVE_RECHARGED_EMERALD_V2_2_8 = LEGACY_NATIVE_RECHARGED_EMERALD.copy(
    itemDescs = ItemDescTable(0x088AD6E8L, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    saveBlock1Ptr = 0x03007214L,
    saveBlock2Ptr = 0x03007218L,
    monIconTable = 0x088DCC6CL,
    monIconPaletteIndices = 0x088DD34CL,
    monIconPaletteTable = 0x088DD504L,
    itemIconTable = 0x088B236CL,
    handleInputChooseAction = 0x080447C9L,
    handleInputChooseMove = 0x08044D39L,
    completeWhenChoseItem = 0x08046B49L,
    waitForMonSelection = 0x08046A71L,
    handleInputChooseTarget = 0x08044A21L,
    pokedex = POKEDEX_RECHARGED_EMERALD_V2_2_8,
    guideTables = GUIDE_TABLES_RECHARGED_EMERALD_V2_2_8,
    fieldItems = FIELD_ITEMS_RECHARGED_EMERALD_V2_2_8,
)

val LEGACY_NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8 = LEGACY_NATIVE_RECHARGED_EMERALD.copy(
    itemDescs = ItemDescTable(0x088ADE28L, 48, 0x18, count = RECHARGED_YELLOW_ITEMS),
    playerParty = 0x020373DCL,
    playerPartyCount = 0x020373D9L,
    enemyParty = 0x02037634L,
    partyMenu = 0x0203734CL,
    saveBlock1Ptr = 0x03007220L,
    saveBlock2Ptr = 0x03007224L,
    saveBlock1PosOff = 4,
    playerGenderOff = 0xC,
    encryptionKeyOff = 0xB0L,
    moneyOff = 0x494L,
    monIconTable = 0x088DD3F0L,
    monIconPaletteIndices = 0x088DDAD0L,
    monIconPaletteTable = 0x088DDC88L,
    itemIconTable = 0x088B2AACL,
    handleInputChooseAction = 0x080447C5L,
    handleInputChooseMove = 0x08044D35L,
    completeWhenChoseItem = 0x08046B45L,
    waitForMonSelection = 0x08046A6DL,
    handleInputChooseTarget = 0x08044A1DL,
    pokedex = POKEDEX_RECHARGED_EMERALD_REBALANCED_V2_2_8,
    guideTables = GUIDE_TABLES_RECHARGED_EMERALD_REBALANCED_V2_2_8,
    fieldItems = FIELD_ITEMS_RECHARGED_EMERALD_REBALANCED_V2_2_8,
)

val LEGACY_NATIVE_MARIOMON_V1_5_1 = LEGACY_NATIVE_EMERALD.copy(
    // gItems: 0x28-byte struct Item (name[14], price +0xE, description +0x14), ITEMS_COUNT 824.
    itemDescs = ItemDescTable(0x0880C8DCL, 0x28, 0x14, count = 824),
    playerParty = 0x02025158L,
    playerPartyCount = 0x02025155L,
    gMain = 0x03002C40L,
    saveBlock1Ptr = 0x03004BCCL,
    saveBlock2Ptr = 0x03004BD0L,
    objectEvents = 0x02038DD4L,
    mapHeader = 0x02038D9CL,
    bagPockets = 0x0203B8BCL,
    bagPocketCount = 6,
    bagPocketOrder = MARIOMON_BAG_POCKET_ORDER,
    battleTypeFlags = 0x02024B5CL,
    battlersCount = 0x02024BD8L,
    battlerPartyIndexes = 0x02024BDAL,
    battlerPositions = 0x02024BE2L,
    battleMons = 0x02024BF0L,
    battleMonLayout = EXPANSION_BATTLE_MON,
    battleStructPtr = 0x020250BCL,
    // gActionSelectionCursor / gMoveSelectionCursor, +0x10 / +0x14 past the gBattleStruct pointer (v1.5.1 / v1.5.2). Checked
    // headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x020250CCL,
    moveCursor = 0x020250D0L,
    monToSwitchIntoOff = 0x5C,
    enemyParty = 0x02025860L,
    battlerControllerFuncs = 0x03004B90L,
    handleInputChooseAction = 0x0807C259L,
    handleInputChooseMove = 0x0807CD81L,
    completeWhenChoseItem = 0x0807E9A5L,
    waitForMonSelection = 0x0807E8EDL,
    handleInputChooseTarget = 0x0807C60DL,
    partyMenu = 0x0203E9B0L,
    eggSpecies = MARIOMON_EGG,
    monIconTable = 0x087FC70CL,
    monIconPaletteIndices = 0x087FF2E4L,
    monIconPaletteTable = 0x087FFC4CL,
    itemIconTable = 0x088C9420L,
    pokedex = POKEDEX_MARIOMON_V1_5_1,
    guideTables = GUIDE_TABLES_MARIOMON_V1_5_1,
)

// v1.5.2-Anniversary: v1.5.1's RAM; its code and data sit a little later (+0xC4 for the battle
// handlers, +0x490..+0x4B4 for the tables).
val LEGACY_NATIVE_MARIOMON_V1_5_2 = LEGACY_NATIVE_MARIOMON_V1_5_1.copy(
    itemDescs = ItemDescTable(0x0880CD90L, 0x28, 0x14, count = 824),
    handleInputChooseAction = 0x0807C31DL,
    handleInputChooseMove = 0x0807CE45L,
    completeWhenChoseItem = 0x0807EA69L,
    waitForMonSelection = 0x0807E9B1L,
    handleInputChooseTarget = 0x0807C6D1L,
    monIconTable = 0x087FCBC0L,
    monIconPaletteIndices = 0x087FF798L,
    monIconPaletteTable = 0x08800100L,
    itemIconTable = 0x088C98C8L,
    pokedex = POKEDEX_MARIOMON_V1_5_2,
    guideTables = GUIDE_TABLES_MARIOMON_V1_5_2,
)

// v1.3: the older RAM layout (no Medicine pocket: Emerald's 5 pockets, ITEMS_COUNT 821).
val LEGACY_NATIVE_MARIOMON_V1_3 = LEGACY_NATIVE_MARIOMON_V1_5_1.copy(
    eggSpecies = MARIOMON_EGG - 1,
    itemDescs = ItemDescTable(0x087EE1C8L, 0x28, 0x14, count = 821),
    playerParty = 0x02025038L,
    playerPartyCount = 0x02025035L,
    objectEvents = 0x020383F0L,
    mapHeader = 0x020383B8L,
    bagPockets = 0x0203AEC0L,
    bagPocketCount = 5,
    bagPocketOrder = EMERALD_BAG_POCKET_ORDER,
    battleTypeFlags = 0x02024A5CL,
    battlersCount = 0x02024AD8L,
    battlerPartyIndexes = 0x02024ADAL,
    battlerPositions = 0x02024AE2L,
    battleMons = 0x02024AF0L,
    battleStructPtr = 0x02024F9CL,
    // gActionSelectionCursor / gMoveSelectionCursor, +0x10 / +0x14 past the gBattleStruct pointer (v1.3 / v1.2.2 / v1.2.1). Checked
    // headless: RIGHT / DOWN walk 0 -> 1 -> 3, a written 2 + A opens the party menu, a written move 1 + A spends its PP.
    actionCursor = 0x02024FACL,
    moveCursor = 0x02024FB0L,
    monToSwitchIntoOff = 0x3C,
    enemyParty = 0x020254E8L,
    handleInputChooseAction = 0x0807AA19L,
    handleInputChooseMove = 0x0807B541L,
    completeWhenChoseItem = 0x0807D165L,
    waitForMonSelection = 0x0807D0ADL,
    handleInputChooseTarget = 0x0807ADCDL,
    partyMenu = 0x0203DFB0L,
    monIconTable = 0x087DE064L,
    monIconPaletteIndices = 0x087E0C34L,
    monIconPaletteTable = 0x087E159CL,
    itemIconTable = 0x088A85A0L,
    pokedex = POKEDEX_MARIOMON_V1_3,
    guideTables = GUIDE_TABLES_MARIOMON_V1_3,
)

// v1.2.2 and v1.2.1: v1.3's RAM; the battle handlers 0x9C later than v1.3's, the same in both.
val LEGACY_NATIVE_MARIOMON_V1_2_2 = LEGACY_NATIVE_MARIOMON_V1_3.copy(
    itemDescs = ItemDescTable(0x087EE1E4L, 0x28, 0x14, count = 821),
    handleInputChooseAction = 0x0807AAB5L,
    handleInputChooseMove = 0x0807B5DDL,
    completeWhenChoseItem = 0x0807D201L,
    waitForMonSelection = 0x0807D149L,
    handleInputChooseTarget = 0x0807AE69L,
    monIconTable = 0x087DE080L,
    monIconPaletteIndices = 0x087E0C50L,
    monIconPaletteTable = 0x087E15B8L,
    itemIconTable = 0x088A8598L,
    pokedex = POKEDEX_MARIOMON_V1_2_2,
    guideTables = GUIDE_TABLES_MARIOMON_V1_2_2,
)

val LEGACY_NATIVE_MARIOMON_V1_2_1 = LEGACY_NATIVE_MARIOMON_V1_2_2.copy(
    itemDescs = ItemDescTable(0x087EE200L, 0x28, 0x14, count = 821),
    monIconTable = 0x087DE09CL,
    monIconPaletteIndices = 0x087E0C6CL,
    monIconPaletteTable = 0x087E15D4L,
    itemIconTable = 0x088A85B8L,
    pokedex = POKEDEX_MARIOMON_V1_2_1,
    guideTables = GUIDE_TABLES_MARIOMON_V1_2_1,
)
