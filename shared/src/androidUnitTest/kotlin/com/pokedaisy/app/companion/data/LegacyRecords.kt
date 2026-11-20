package com.pokedaisy.app.companion.data

// Phase 2 of gamedata/: the records Pokedex.kt, GuideRom.kt, TrainerCard.kt, NativeReader.kt, Fly.kt, FieldItems.kt and Pc.kt wrote by
// hand before they moved to gamedata/gen3/records/ (GameDataRecordsGen.kt), word for word but renamed, for
// GameDataMigrationTest. Goes with that test.

private fun vanillaDexFlags(seen1: Long, seen2: Long) = DexFlags(DexFlagBlock.SAVE_BLOCK_2, 0x5C, 0x28, 52, listOf(seen1, seen2))

val LEGACY_FIRERED_DEX_FLAGS = vanillaDexFlags(0x5F8, 0x3A18)

val LEGACY_EMERALD_DEX_FLAGS = vanillaDexFlags(0x988, 0x3B24)

val LEGACY_POKEDEX_FIRERED_REV1 = PokedexTables(
    entries = 0x0844E8B0L,
    frontPics = 0x0823511CL,
    palettes = 0x0823737CL,
    speciesInfo = 0x082547F4L,
    speciesToNational = 0x0825205EL,
    abilityNames = 0x0824FCB0L,
    footprints = 0x0843FB20L,
    evolutions = 0x082597C4L,
    tmhmLearnsets = 0x08252C38L,
    tmhmMoves = 0x0845A604L,
)

val LEGACY_POKEDEX_LEAFGREEN_REV0 = PokedexTables(
    entries = 0x0844E270L,
    frontPics = 0x08235088L,
    palettes = 0x082372E8L,
    speciesInfo = 0x08254760L,
    speciesToNational = 0x08251FCAL,
    abilityNames = 0x0824FC1CL,
    footprints = 0x0843F8ECL,
    evolutions = 0x08259734L,
    tmhmLearnsets = 0x08252BA4L,
    tmhmMoves = 0x08459FC4L,
)

val LEGACY_POKEDEX_LEAFGREEN_REV1 = PokedexTables(
    entries = 0x0844E2E0L,
    frontPics = 0x082350F8L,
    palettes = 0x08237358L,
    speciesInfo = 0x082547D0L,
    speciesToNational = 0x0825203AL,
    abilityNames = 0x0824FC8CL,
    footprints = 0x0843F95CL,
    evolutions = 0x082597A4L,
    tmhmLearnsets = 0x08252C14L,
    tmhmMoves = 0x0845A034L,
)

val LEGACY_POKEDEX_EMERALD = PokedexTables(
    entries = 0x0856B5B0L,
    frontPics = 0x0830A18CL,
    palettes = 0x08303678L,
    speciesInfo = 0x083203CCL,
    speciesToNational = 0x0831DC82L,
    abilityNames = 0x0831B6DBL,
    footprints = 0x0856E694L,
    entryStride = 0x20,
    regionalCount = 202, // HOENN_DEX_COUNT (Jirachi and Deoxys included)
    regionName = "HOENN",
    regionalOrder = 0x0831DFB8L,
    flags = LEGACY_EMERALD_DEX_FLAGS,
    nationalMagicOff = 2,
    nationalMagic = 0xDA,
    evolutions = 0x0832531CL,
    tmhmLearnsets = 0x0831E898L,
    tmhmMoves = 0x08615B94L,
)

val LEGACY_GUIDE_TABLES_FIRERED_REV1 = GuideTables(
    trainers = 0x0823EB38L,
    learnsets = 0x0825D824L,
    wildHeaders = 0x083C9D28L,
    mapGroups = 0x08352718L,
)

val LEGACY_GUIDE_TABLES_LEAFGREEN_REV0 = GuideTables(
    trainers = 0x0823EAA4L,
    learnsets = 0x0825D794L,
    wildHeaders = 0x083C9AF4L,
    guide = GuideId.LEAFGREEN,
    mapGroups = 0x08352688L,
)

val LEGACY_GUIDE_TABLES_LEAFGREEN_REV1 = GuideTables(
    trainers = 0x0823EB14L,
    learnsets = 0x0825D804L,
    wildHeaders = 0x083C9B64L,
    guide = GuideId.LEAFGREEN,
    mapGroups = 0x083526F8L,
)

val LEGACY_GUIDE_TABLES_EMERALD = GuideTables(
    trainers = 0x08310030L,
    learnsets = 0x0832937CL,
    wildHeaders = 0x08552D48L,
    sb1FlagsOff = 0x1270,
    sb1VarsOff = 0x139C,
    flagBytes = 0x12C,
    probeTrainer = 265,
    probeName = "ROXANNE",
    guide = GuideId.EMERALD,
    mapGroups = 0x08486578L,
)

val LEGACY_TRAINER_CARD_FIRERED = TrainerCardSave(
    style = CardStyle.KANTO,
    gameStatsOff = 0x1200, flagsOff = 0xEE0, varsOff = 0x1000,
    badgeFlag = 0x820, pokedexFlag = 0x829,
    nationalMagicOff = 0x18 + 3, nationalMagic = 0xB9, nationalVar = 0x404E, nationalVarValue = 0x6258, nationalFlag = 0x840,
    dexFlags = LEGACY_FIRERED_DEX_FLAGS,
    regionalDex = IntArray(151) { it + 1 },
    stickerVar = 0x4049,
)

val LEGACY_TRAINER_CARD_EMERALD = TrainerCardSave(
    style = CardStyle.HOENN,
    gameStatsOff = 0x159C, flagsOff = 0x1270, varsOff = 0x139C,
    badgeFlag = 0x867, pokedexFlag = 0x861,
    nationalMagicOff = 0x18 + 2, nationalMagic = 0xDA, nationalVar = 0x4046, nationalVarValue = 0x302, nationalFlag = 0x896,
    dexFlags = LEGACY_EMERALD_DEX_FLAGS,
    regionalDex = HOENN_TO_NATIONAL,
    frontierSymbolFlag = 0x8C4,
    museumWinnersOff = 0x2E90 + 8 * 32,
    frontierBpOff = 0xEBA,
)

val LEGACY_FIRERED_BAG_POCKET_ORDER = listOf(POCKET_ITEMS, POCKET_KEY_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES)

val LEGACY_EMERALD_BAG_POCKET_ORDER = listOf(POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_KEY_ITEMS)

// Fly.kt's FLY tables, before they moved to the records.

/** Retail FireRed rev 1: pret's pokefirered built clean as firered_rev1 (byte for byte retail) and its ELF. */
val LEGACY_FLY_FIRERED_REV1 = FlyFns(
    flagGet = 0x0806E6E4L, badgeFlag = 0x822, setUpFieldMoveFly = 0x08124B04L, getMapsecType = 0x080C35F0L,
    fadeScreen = 0x0807A82CL, lockControls = 0x08069954L, unlockControls = 0x08069960L, setFlyWarpDestination = 0x080C5290L,
    partyMenuSlotId = 0x0203B0A9L, destinations = 0x083F2F50L, firstMapsec = 0x58, count = 108,
    codeCrcs = listOf(0x07BEBC3AL, 0xCD8961DFL, 0x348C5B9FL, 0xF9959006L, 0x0111A86EL, 0xBAB654BAL, 0x70EA1DFCL),
)

/** Retail LeafGreen rev 0 / rev 1: built clean as leafgreen / leafgreen_rev1. */
val LEGACY_FLY_LEAFGREEN_REV0 = LEGACY_FLY_FIRERED_REV1.copy(
    flagGet = 0x0806E6D0L, setUpFieldMoveFly = 0x08124A64L, getMapsecType = 0x080C35B0L, fadeScreen = 0x0807A7ECL,
    lockControls = 0x08069940L, unlockControls = 0x0806994CL, setFlyWarpDestination = 0x080C5250L, destinations = 0x083F2D1CL,
    codeCrcs = listOf(0x07BEBC3AL, 0x5B68C93AL, 0xE4D65E08L, 0xF9959006L, 0x0111A86EL, 0xBAB654BAL, 0x059815BFL),
)
val LEGACY_FLY_LEAFGREEN_REV1 = LEGACY_FLY_FIRERED_REV1.copy(
    flagGet = 0x0806E6E4L, setUpFieldMoveFly = 0x08124ADCL, getMapsecType = 0x080C35C4L, fadeScreen = 0x0807A800L,
    lockControls = 0x08069954L, unlockControls = 0x08069960L, setFlyWarpDestination = 0x080C5264L, destinations = 0x083F2D8CL,
    codeCrcs = listOf(0x07BEBC3AL, 0xDBE480BDL, 0x50C0C136L, 0xF9959006L, 0x0111A86EL, 0xBAB654BAL, 0x059815BFL),
)

/** Retail Emerald: pret's pokeemerald built clean (byte for byte retail) and its ELF. sMapHealLocations covers map
 * sections 0..49; Ever Grande flies to the city (the game's League spot needs the cursor's top tile). */
val LEGACY_FLY_EMERALD = FlyFns(
    flagGet = 0x0809D790L, badgeFlag = 0x86C, setUpFieldMoveFly = 0x081B5884L, getMapsecType = 0x08123D58L,
    fadeScreen = 0x080ABCD0L, lockControls = 0x08098E54L, unlockControls = 0x08098E60L, setFlyWarpDestination = 0,
    partyMenuSlotId = 0x0203CED1L, destinations = 0x085A1E3CL, firstMapsec = 0, count = 50,
    codeCrcs = listOf(0xBCF97BF3L, 0x13BE1A7AL, 0x4B625BC4L, 0xF9959006L, 0xB9FB85A7L, 0xB037C8AFL, 0x74DBCE3AL, 0x2F1D18CAL, 0xA284286DL),
    flyTypes = setOf(2, 4), extraMapsecs = listOf(58),
    healWarp = HealWarp(
        toHealLocation = 0x08084CCCL, toMapWarp = 0x08084C28L, returnToField = 0x080B69DCL, saveBlock2Ptr = 0x03005D90L,
        // MAPSEC_EVER_GRANDE_CITY, MAPSEC_BATTLE_FRONTIER (HEAL_LOCATION_EVER_GRANDE_CITY, _BATTLE_FRONTIER_OUTSIDE_EAST)
        special = mapOf(15 to 11, 58 to 22), genderMapsec = 0, maleHeal = 12, femaleHeal = 13,
    ),
)


// FieldItems.kt's and Pc.kt's retail English tables, before they moved to the records.

/** Retail FireRed rev 1 (its gSpecials and ScriptContext_SetupScript traced from the ROM's script command table). */
val LEGACY_PC_FIRERED_REV1 = PcFns(0x08069AF8L, 60, 0x0815FDD8L, 0x0808C6BDL, listOf(0xAAC00946L, 0x9662F022L))
val LEGACY_PC_LEAFGREEN_REV0 = PcFns(0x08069AE4L, 60, 0x0815FD3CL, 0x0808C67DL, listOf(0xAAC00946L, 0x5333E53EL))
val LEGACY_PC_LEAFGREEN_REV1 = PcFns(0x08069AF8L, 60, 0x0815FDB4L, 0x0808C691L, listOf(0xAAC00946L, 0x5333E53EL))
val LEGACY_PC_EMERALD = PcFns(0x08098EF8L, 63, 0x081DBA64L, 0x080C7579L, listOf(0xEF3698BAL, 0x81188148L))

/** Retail FireRed rev 1 (BPRE). Every address from the pret pokefirered build at the pinned
 * commit, built as firered_rev1 (byte for byte the retail ROM, SHA1 dd5945db...) and its ELF. */
val LEGACY_FIELD_ITEMS_FIRERED_REV1 = FieldItemFns(
    varGet = 0x0806E57CL, varSet = 0x0806E598L, checkBagHasItem = 0x08099F54L, removeBagItem = 0x0809A1ECL,
    fieldControlsLocked = 0x0806996CL, repelVar = 0x4020, items = 0x083DB098L,
    codeCrcs = listOf(0xE6DE47BAL, 0x6E7CC2BEL, 0xD30D656EL, 0xC40BF208L, 0x9C26CCA4L),
    party = PartyItemFns(
        getMonData = 0x0803FBFCL, setMonData = 0x08040390L, itemNoEffect = 0x08042428L, itemEffect = 0x080413D4L,
        addBagItem = 0x0809A098L, party = 0x02024284L, partyCount = 0x02024029L,
        // EWRAM's last symbol (__malloc_current_mallinfo) ends at 0x0203FBB0 and nothing writes
        // past it (the only EWRAM_END user is the title screen's Colosseum multiboot copy):
        // the last 16 bytes read 0 on the field and through a save.
        scratch = 0x0203FFF0L,
        itemCount = 375, unholdablePockets = setOf(2, 4), // POCKET_KEY_ITEMS, POCKET_TM_CASE
        codeCrcs = listOf(0xA73A32CDL, 0x71F5A020L, 0x788AB547L, 0xEADAC6C9L, 0xC316CD20L),
        useFuncs = setOf(0x080A16F4L, 0x080A1710L, 0x080A172CL, 0x080A19ACL), // FieldUseFunc_Medicine, _Ether, _PpUp, _Repel
    ),
    fly = LEGACY_FLY_FIRERED_REV1,
    // FREE FLIGHT came after the move to gamedata (2026-11-10): no hand-written one, the record's own.
    flight = FLIGHT_FIRERED_REV1,
    pc = LEGACY_PC_FIRERED_REV1,
)

/** Retail Emerald (BPEE): its functions sit where the pinned pokeemerald build has them (Emerald
 * Kaizo's too, retail edited in place). Every address from pret's pokeemerald built clean (byte for byte retail,
 * SHA1 f3ae0881...) and its ELF. Emerald has no PokemonItemUseNoEffect: the no-effect check is its party menu's own,
 * ExecuteTableBasedItemEffect, on the Pokémon and then undone ([PartyItemFns.itemNoEffect]). */
val LEGACY_FIELD_ITEMS_EMERALD = FieldItemFns(
    varGet = 0x0809D694L, varSet = 0x0809D6B0L, checkBagHasItem = 0x080D6724L, removeBagItem = 0x080D6AA4L,
    fieldControlsLocked = 0x08098E6CL, repelVar = 0x4021, items = 0x085839A0L,
    codeCrcs = listOf(0x7D0D07B6L, 0x519C39C0L, 0x312F6576L, 0x379EF742L, 0x2532A407L),
    party = PartyItemFns(
        getMonData = 0x0806A518L, setMonData = 0x0806ACACL, itemNoEffect = null, itemEffect = 0x0806BD04L,
        addBagItem = 0x080D6928L, party = 0x020244ECL, partyCount = 0x020244E9L,
        // EWRAM's last symbol (sRayScene) ends at 0x0203CF64; nothing writes past it (EWRAM_END is defined, never used).
        scratch = 0x0203FFF0L,
        itemCount = 377, unholdablePockets = setOf(5, 3), // POCKET_KEY_ITEMS, POCKET_TM_HM
        codeCrcs = listOf(0x9670EBDEL, 0x8D555FF8L, 0xEADAC6C9L, 0x75BC5970L),
        // ItemUseOutOfBattle_Medicine, _PPRecovery, _PPUp, _Repel
        useFuncs = setOf(0x080FDEA0L, 0x080FDEF4L, 0x080FDF10L, 0x080FE0BCL),
    ),
    fly = LEGACY_FLY_EMERALD,
    pc = LEGACY_PC_EMERALD,
    flight = FLIGHT_EMERALD,
)

/** Retail LeafGreen rev 0 (BPGE): pret's pokefirered built clean as leafgreen (byte for byte retail, SHA1 574fa542...). */
val LEGACY_FIELD_ITEMS_LEAFGREEN_REV0 = FieldItemFns(
    varGet = 0x0806E568L, varSet = 0x0806E584L, checkBagHasItem = 0x08099F14L, removeBagItem = 0x0809A1ACL,
    fieldControlsLocked = 0x08069958L, repelVar = 0x4020, items = 0x083DAE64L,
    codeCrcs = listOf(0xE6DE47BAL, 0x6E7CC2BEL, 0xD30D656EL, 0xC40BF208L, 0x9C26CCA4L),
    party = LEGACY_FIELD_ITEMS_FIRERED_REV1.party!!.copy(
        getMonData = 0x0803FBE8L, setMonData = 0x0804037CL, itemNoEffect = 0x08042414L, itemEffect = 0x080413C0L,
        addBagItem = 0x0809A058L,
        codeCrcs = listOf(0x7841F205L, 0x9B11B0A6L, 0x788AB547L, 0xEADAC6C9L, 0xC316CD20L),
        useFuncs = setOf(0x080A16B4L, 0x080A16D0L, 0x080A16ECL, 0x080A196CL),
    ),
    fly = LEGACY_FLY_LEAFGREEN_REV0,
    flight = FLIGHT_LEAFGREEN_REV0,
    pc = LEGACY_PC_LEAFGREEN_REV0,
)

/** Retail LeafGreen rev 1 (BPGE): built clean as leafgreen_rev1 (SHA1 7862c67b...). FireRed rev 1's party code and RAM. */
val LEGACY_FIELD_ITEMS_LEAFGREEN_REV1 = FieldItemFns(
    varGet = 0x0806E57CL, varSet = 0x0806E598L, checkBagHasItem = 0x08099F28L, removeBagItem = 0x0809A1C0L,
    fieldControlsLocked = 0x0806996CL, repelVar = 0x4020, items = 0x083DAED4L,
    codeCrcs = listOf(0xE6DE47BAL, 0x6E7CC2BEL, 0xD30D656EL, 0xC40BF208L, 0x9C26CCA4L),
    party = LEGACY_FIELD_ITEMS_FIRERED_REV1.party!!.copy(
        addBagItem = 0x0809A06CL, useFuncs = setOf(0x080A16C8L, 0x080A16E4L, 0x080A1700L, 0x080A1980L),
    ),
    fly = LEGACY_FLY_LEAFGREEN_REV1,
    flight = FLIGHT_LEAFGREEN_REV1,
    pc = LEGACY_PC_LEAFGREEN_REV1,
)

