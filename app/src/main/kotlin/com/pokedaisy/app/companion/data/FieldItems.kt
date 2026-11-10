package com.pokedaisy.app.companion.data

/**
 * Using an item from the companion, the way the game does it - by calling the
 * game's own functions between frames (MgbaCore.pkCall), not by steering its
 * menus with button presses. The Repels: what ItemUseOutOfBattle_Repel
 * ends in, without its message box - if VarGet(VAR_REPEL_STEP_COUNT) is 0,
 * VarSet(VAR_REPEL_STEP_COUNT, the item's holdEffectParam) and
 * RemoveBagItem(item, 1). The game's own step counter then counts it down and
 * says "REPEL's effect wore off" by itself. Medicine and held items go to a
 * party Pokémon ([PartyItemFns], below).
 *
 * Only where the player could open the bag: on the field, controls free
 * (ArePlayerFieldControlsLocked: a script, a message or the START menu holds
 * them) and no menu screen up. Each address is retail's (the pinned decomp
 * builds' functions, found byte for byte in the retail ROMs), checked headless
 * with mgba_dump's `call` on the user's saves: the Repel left the bag, the
 * counter read 100 and dropped 7 after 7 steps; FireRed's controls read locked
 * while its quest-log recap was up.
 */
data class FieldItemFns(
    val varGet: Long,
    val varSet: Long,
    val checkBagHasItem: Long,
    val removeBagItem: Long,
    val fieldControlsLocked: Long,
    /** VAR_REPEL_STEP_COUNT (FireRed 0x4020, Emerald 0x4021). */
    val repelVar: Int,
    /** gItems: struct Item, holdEffectParam at +0x13 (a Repel's steps) with [itemNameLen] 14. */
    val items: Long,
    /** CRC32 of each function's first 32 bytes (varGet, varSet, checkBagHasItem, removeBagItem,
     * fieldControlsLocked): a build that moved them is left alone. */
    val codeCrcs: List<Long>,
    /** USE on a party Pokémon and GIVE; null = the Repels only. */
    val party: PartyItemFns? = null,
    /** struct Item's name length: 14 (44-byte records), Japanese 10 (40). Every field after it moves with it. */
    val itemNameLen: Int = 14,
    /** FLY from the MAP tab (Fly.kt); null = none. */
    val fly: FlyFns? = null,
    /** FREE FLIGHT and the scenic FLY (Flight.kt, pk_flight.c); null = none. Needs [fly] too (its gates). */
    val flight: FlightFns? = null,
    /** false = never the game's own FLY warp (Emerald Kaizo's crashed from the field): FLY goes over the map ([flight]), and
     * a town with no way there over the map says so. [fly] still gives the gates and the towns. */
    val classicFly: Boolean = true,
    /** holdEffectParam's offset in struct Item where a build moved it off [itemNameLen] + 5 (Recharged Emerald dropped
     * the itemId field: 0x15 after its name[18]); null = the usual place. */
    val itemParamOffset: Int? = null,
    /** Variables the game's own Task_UseRepel sets to 0 before the steps (Recharged Emerald: its Lure's), in its order. */
    val repelClearVars: List<Int> = emptyList(),
    /** Where Task_UseRepel keeps the Repel just used, for the game's "use another?" when it wears off; 0 = none. */
    val lastRepelVar: Int = 0,
    /** PC anywhere from the BAG tab (Pc.kt); null = none. */
    val pc: PcFns? = null,
    /** USE / GIVE / TAKE from the bag; false = the table is there for FLY and the PC only (a game whose items the app
     * doesn't know the ids / gItems layout of: the expansion hacks) - and the Repels, where [checkBagHasItem],
     * [removeBagItem] and [items] are mapped (SoulGold). */
    val bagActions: Boolean = true,
    /** The Repels' item ids (REPEL, SUPER, MAX: [REPEL_ITEMS] in every Gen 3 game; SoulGold's own 114..116). */
    val repelItems: Set<Int> = REPEL_ITEMS,
    /** The [repelVar] bit a Lure sets (newer expansion: 0x8000, its steps below it); a Repel can go on while a Lure
     * works, as the game's own ItemUseOutOfBattle_Repel allows. 0 = no Lures. */
    val repelLureBit: Int = 0,
) {
    /** The steps of the Repel working now in [repelVar]'s value [v] (0 = none, or a Lure's). */
    fun repelSteps(v: Int): Int = if (repelLureBit != 0 && v and repelLureBit != 0) 0 else v and (if (repelLureBit != 0) repelLureBit - 1 else 0xFFFF)
    /** The functions the code CRCs check, in order; a 0 is one the table doesn't use (the expansion hacks' bag). */
    val functions get() = listOf(varGet, varSet, checkBagHasItem, removeBagItem, fieldControlsLocked).filter { it != 0L }
    val itemSize get() = itemNameLen + 30
    /** Offsets in struct Item: holdEffectParam, importance, pocket, fieldUseFunc. */
    val itemParamOff get() = itemParamOffset ?: (itemNameLen + 5)
    val itemImportanceOff get() = itemNameLen + 10
    val itemPocketOff get() = itemNameLen + 12
    val itemFieldUseOff get() = itemNameLen + 14
}

/**
 * Medicine and held items, as party_menu.c does them (pokefirered: ItemUseCB_Medicine /
 * ItemUseCB_TryRestorePP / ItemUseCB_PPUp, and the GIVE path's GiveItemToMon /
 * Task_HandleSwitchItemsYesNoInput), minus its menus and messages:
 *
 * USE: PokemonItemUseNoEffect(mon, item, slot, move) says which Pokémon (and moves)
 * it would help - the game's own "It won't have any effect." check, plus its
 * HP Up-on-Shedinja rule (NotUsingHPEVItemOnShedinja) - then
 * ExecuteTableBasedItemEffect(mon, item, slot, move) and RemoveBagItem(item, 1).
 * Not Rare Candy or the evolution stones: their effect alone would skip the party
 * menu's move learning and evolution. Not Mail (its letter isn't written here).
 *
 * GIVE: RemoveBagItem(item, 1); a Pokémon already holding something gets its item
 * back into the bag first - AddBagItem(old, 1), and if that finds no room the new
 * one goes back (AddBagItem(item, 1)) and nothing changes, the game's own switch
 * order (taking the new one out first frees its slot for the old one) - then
 * SetMonData(mon, MON_DATA_HELD_ITEM, &item), which re-encrypts the box and fixes its
 * checksum. &item is [scratch]: RAM no build symbol reaches (see the retail table).
 * Eggs can't use or hold anything (IsSelectedMonNotEgg), and a Pokémon holding Mail
 * keeps it (the game asks for it to be taken first).
 *
 * TAKE (the summary's, party_menu.c's TryTakeMonItem): AddBagItem(held, 1) - no room and
 * nothing changes - then SetMonData(mon, MON_DATA_HELD_ITEM, &ITEM_NONE). Not Mail (the game
 * sends that to the PC or loses it, after asking).
 */
data class PartyItemFns(
    /** GetMonData(mon, field, NULL) (GetMonData3: the 2-argument GetMonData is the same code). */
    val getMonData: Long,
    val setMonData: Long,
    /** PokemonItemUseNoEffect(mon, item, partyIndex, moveIndex): TRUE = it wouldn't do anything. FireRed has
     * it; Emerald doesn't (its party menu just runs the effect): null = ask [itemEffect] itself on the Pokémon,
     * then put its 100 bytes back - the same answer, nothing kept. */
    val itemNoEffect: Long?,
    /** ExecuteTableBasedItemEffect(mon, item, partyIndex, moveIndex): FALSE = it did something. */
    val itemEffect: Long,
    val addBagItem: Long,
    /** gPlayerParty and gPlayerPartyCount (a byte). */
    val party: Long,
    val partyCount: Long,
    /** Two bytes of RAM the game never uses, for SetMonData's argument (restored after). */
    val scratch: Long,
    /** gItems' length and struct Item's pocket ids no Pokémon can hold (key items, TMs / HMs). */
    val itemCount: Int,
    val unholdablePockets: Set<Int>,
    /** CRC32 of each function's first 32 bytes (getMonData, setMonData, itemNoEffect where there is one, itemEffect, addBagItem). */
    val codeCrcs: List<Long>,
    /** What USE works on: medicine (one Pokémon), PP items for one move / for all of them. */
    val medicine: Set<Int> = MEDICINE_ITEMS,
    val ppOneMove: Set<Int> = PP_ONE_MOVE_ITEMS,
    val ppAllMoves: Set<Int> = PP_ALL_MOVES_ITEMS,
    /** sizeof(struct Pokemon): 100, SoulGold's 96 (NativeConfig.monStride). */
    val monStride: Int = 100,
    val mail: Set<Int> = MAIL_ITEMS,
    val hpUp: Int = 63,
    val shedinja: Int = 303,
    /** The game's own field-use routines for medicine, PP items and Repels (FieldUseFunc_Medicine / _Ether /
     * _PpUp / _Repel, Emerald's ItemUseOutOfBattle_*): an item is offered only if its gItems entry points at one
     * of them - a hack that turned an id into something else keeps it off. Empty = the id sets alone. */
    val useFuncs: Set<Long> = emptySet(),
    /** How far GetMonData's field ids from MON_DATA_SPECIES on sit past pokeemerald's: Recharged Emerald put 9 fields of
     * its own before MON_DATA_ENCRYPT_SEPARATOR (SPECIES 20, HELD_ITEM 21, MOVE1 22, IS_EGG 54). PERSONALITY stays 0. */
    val monFieldShift: Int = 0,
) {
    val functions get() = listOfNotNull(getMonData, setMonData, itemNoEffect, itemEffect, addBagItem).filter { it != 0L }
    val usable get() = medicine + ppOneMove + ppAllMoves
    val speciesField get() = MON_DATA_SPECIES + monFieldShift
    val heldItemField get() = MON_DATA_HELD_ITEM + monFieldShift
    val move1Field get() = MON_DATA_MOVE1 + monFieldShift
    val isEggField get() = MON_DATA_IS_EGG + monFieldShift
}

/** Whether the running ROM has [fns]' functions (its party ones too) where they're expected. */
fun fieldItemCodeMatches(fns: FieldItemFns, readRom: (addr: Long, len: Int) -> ByteArray): Boolean {
    val party = fns.party
    val all = fns.functions.zip(fns.codeCrcs) + (party?.let { it.functions.zip(it.codeCrcs) }.orEmpty())
    return all.all { (fn, crc) -> java.util.zip.CRC32().apply { update(readRom(fn, 32)) }.value == crc }
}

/** REPEL / SUPER REPEL / MAX REPEL: the same ids in both games. */
val REPEL_ITEMS = setOf(86, 83, 84)

/** SoulGold's REPEL / SUPER REPEL / MAX REPEL (100 / 200 / 250 steps in its gItemsInfo). */
val SOULGOLD_REPEL_ITEMS = setOf(114, 115, 116)

/** USE on one Pokémon (FieldUseFunc_Medicine in pokefirered's items.json, FireRed / Emerald ids): Potion ...
 * Revival Herb, Lava Cookie, Berry Juice, the vitamins, the status / HP Berries. Not the Flutes. */
val MEDICINE_ITEMS: Set<Int> = (13..33).toSet() + setOf(38, 44, 63, 64, 65, 66, 67, 70, 133, 134, 135, 136, 137, 139, 141, 142)

/** PP items for one move (Ether, Max Ether, Leppa Berry, PP Up, PP Max): a move is picked too. */
val PP_ONE_MOVE_ITEMS = setOf(34, 35, 138, 69, 71)

/** PP Up / PP Max: the move pick's "Boost PP of which move?" (the others restore PP). */
val PP_BOOST_ITEMS = setOf(69, 71)

/** PP items for every move (Elixir, Max Elixir): no move to pick. */
val PP_ALL_MOVES_ITEMS = setOf(36, 37)

/** ORANGE MAIL .. RETRO MAIL (ItemIsMail). */
val MAIL_ITEMS = (121..132).toSet()

// FIELD_ITEMS_FIRERED_REV1 / _EMERALD / _LEAFGREEN_REV0 / _REV1 (and their PARTY_ITEMS_* and PC_*) are records in
// gamedata/gen3/records/ (GameDataRecordsGen.kt), shared with the 3DS / Linux companion.

/**
 * Emerald Legacy v1.1.4 (BPEE, its own build: its source at V1.1.4 built with agbcc = the release): retail's functions
 * and globals at its ELF's addresses. Where its code differs from retail's (the bag functions, GetMonData / SetMonData
 * with its MON_DATA_DEAD appended, the field-use routines) it's pret's later renames and moved calls, not behaviour;
 * the MON_DATA ids, item ids, pockets and VAR_REPEL_STEP_COUNT are retail's. EWRAM's last symbol
 * (sDescriptionSubmenu) ends at 0x0203D28D: the scratch word is free.
 */
val FIELD_ITEMS_EMERALD_LEGACY = FieldItemFns(
    varGet = 0x0809EB00L, varSet = 0x0809EB1CL, checkBagHasItem = 0x080D9EB8L, removeBagItem = 0x080DA238L,
    fieldControlsLocked = 0x0809A208L, repelVar = 0x4021, items = 0x085B6210L,
    codeCrcs = listOf(0x7D0D07B6L, 0x519C39C0L, 0x5FB43DE5L, 0x6591084CL, 0x2532A407L),
    party = FIELD_ITEMS_EMERALD.party!!.copy(
        getMonData = 0x0806B1FCL, setMonData = 0x0806B9B4L, itemEffect = 0x0806CA48L, addBagItem = 0x080DA0BCL,
        codeCrcs = listOf(0xA0496642L, 0xC8B48F7BL, 0xEADAC6C9L, 0x3510B884L),
        useFuncs = setOf(0x08101740L, 0x08101794L, 0x081017B0L, 0x0810195CL),
    ),
    fly = FLY_EMERALD_LEGACY,
    pc = PC_EMERALD_LEGACY,
    flight = FLIGHT_EMERALD_LEGACY,
)

/** Ruby / Sapphire's party side, revs 1 and 2 alike (pret's pokeruby built clean as each, byte for byte retail): the
 * party in IWRAM, 349 items, no PokemonItemUseNoEffect (its party menu runs the effect, like Emerald's). EWRAM's
 * last symbol ends at 0x0203A3D5 and gSharedMem's offsets stop at 0x1FFF0, so the scratch word is free here too. */
private val RUBY_SAPPHIRE_PARTY = PartyItemFns(
    getMonData = 0x0803CB60L, setMonData = 0x0803D1FCL, itemNoEffect = null, itemEffect = 0x0803E18CL,
    addBagItem = 0x080A9444L, party = 0x03004360L, partyCount = 0x03004350L, scratch = 0x0203FFF0L,
    itemCount = 349, unholdablePockets = setOf(5, 3), // POCKET_KEY_ITEMS, POCKET_TM_HM
    codeCrcs = listOf(0x7D459654L, 0x59B8ECA4L, 0xEADAC6C9L, 0x891E35D5L),
    useFuncs = setOf(0x080C9DD0L, 0x080C9E5CL, 0x080C9E78L, 0x080CA034L), // the same in both, revs 1 and 2
)

/** Retail Ruby rev 1 / rev 2 (AXVE; SHA1 610b96a9... / 5b64eacf...). */
val FIELD_ITEMS_RUBY = FieldItemFns(
    varGet = 0x08069274L, varSet = 0x08069290L, checkBagHasItem = 0x080A92F4L, removeBagItem = 0x080A9558L,
    fieldControlsLocked = 0x08065568L, repelVar = 0x4021, items = 0x083C5580L,
    codeCrcs = listOf(0x98EFB2B6L, 0x82AA042BL, 0x87E0CA93L, 0xD7C65886L, 0xA306873DL),
    party = RUBY_SAPPHIRE_PARTY,
    pc = PC_RUBY,
    fly = FLY_RUBY,
    flight = FLIGHT_RUBY,
)

/** Retail Sapphire rev 1 / rev 2 (AXPE; SHA1 4722efb8... / 89b45fb1...): Ruby's code a few bytes on. */
val FIELD_ITEMS_SAPPHIRE = FIELD_ITEMS_RUBY.copy(
    varGet = 0x08069278L, varSet = 0x08069294L, fieldControlsLocked = 0x0806556CL, items = 0x083C55DCL, pc = PC_SAPPHIRE,
    fly = FLY_SAPPHIRE,
    flight = FLIGHT_SAPPHIRE,
)

/** Glazed 9.2.0: retail Emerald edited in place - Emerald's code and RAM, its VarGet / VarSet hooked into its own
 * extended variables (a jump at their entry; still the game's own functions to call). Its edited items are what
 * [PartyItemFns.useFuncs] keeps honest. */
val FIELD_ITEMS_GLAZED = FIELD_ITEMS_EMERALD.copy(
    // FLY as Emerald's: its towns sit in Hoenn's map slots, and its Fly map's exit (CB_ExitFlyMap) and sMapHealLocations
    // are retail's bytes - flown headless (a Tunod city's Pokémon Center). Johto's towns aren't in that table.
    codeCrcs = listOf(0x9D16316AL, 0xAC747C5FL) + FIELD_ITEMS_EMERALD.codeCrcs.drop(2),
    flight = FLIGHT_GLAZED,
)

/**
 * Heart and Soul 2.0.6 (pokeemerald-expansion, gcc - nothing maps from retail): every address from its source
 * (PokemonHnS-Development/pokehns-expansion at the 2.0.6 commit) built plain (-Og) with Debian's arm-none-eabi-gcc 12,
 * each function then found in the release by its code (exact, masked, or through its callers' BLs: FlagGet, FadeScreen,
 * the map warp), RAM and tables through the literal pools (gPartyMenu 0x020341FC = the battle switch's). PC anywhere and
 * FLY only ([FieldItemFns.bagActions] false: the expansion renumbered items and reshaped gItemsInfo). FLY's gate is
 * gFieldMoveInfo[FIELD_MOVE_FLY]'s own functions - IsFieldMoveUnlocked_Fly (badge 5 there, in place of a flag) and
 * SetUpFieldMove_Fly; MON_DATA_MOVE1 / IS_EGG are 20 / 54. Flown headless (New Bark Town three times, two Johto cities),
 * the PC opened and closed.
 */
val FIELD_ITEMS_HEART_AND_SOUL = FieldItemFns(
    varGet = 0x0811E678L, varSet = 0x0811E70CL, checkBagHasItem = 0x081665C0L, removeBagItem = 0x081671CCL,
    fieldControlsLocked = 0x08207FF8L, repelVar = 0x4021, items = 0x0878EFC0L,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x4ACC0486L, 0xE1BB9533L, 0xFB4F8620L),
    party = PartyItemFns(
        getMonData = 0x081BE7F8L, setMonData = 0x081BF848L, itemNoEffect = null, itemEffect = 0,
        addBagItem = 0x08167000L, party = 0x02034764L, partyCount = 0x020342A4L, scratch = 0x0203FFF0L,
        itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0x5AEB875BL, 0x181C95BFL, 0x2616D154L),
    ),
    bagActions = false,
    pc = PcFns(0x08208094L, 63, 0x08294028L, 0x081DA8CDL, listOf(0x9917FF09L, 0x3519E8E6L)),
    fly = FlyFns(
        flagGet = 0x08140258L, badgeFlag = 0, setUpFieldMoveFly = 0x081A6038L, getMapsecType = 0x081F6E58L,
        fadeScreen = 0x0814D340L, lockControls = 0x08207FD8L, unlockControls = 0x08207FECL,
        setFlyWarpDestination = 0, partyMenuSlotId = 0x02034205L, destinations = 0x08D4D1CCL, firstMapsec = 0, count = 0x7D,
        codeCrcs = listOf(0x000D456DL, 0xEACE0EC0L, 0x1634A082L, 0xBEB09F41L, 0x3A5DE8F0L, 0xD7D52C9EL, 0xB5256472L, 0x4C7AD37BL, 0x61E354B6L),
        flyTypes = setOf(2, 4), monMoveField = 20, monEggField = 54,
        healWarp = HealWarp(
            toHealLocation = 0x081986BCL, toMapWarp = 0x08198638L, returnToField = 0x0813CEF0L,
            saveBlock2Ptr = 0x030041D4L, special = emptyMap(), genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_HEART_AND_SOUL,
)

/**
 * Monomyth v1.0 (its own closed pokeemerald build): PC anywhere only. VarGet / VarSet (0x08096068 / 0x080960A0, StandardWildEncounter
 * calls the first with 0x40B1), its ArePlayerFieldControlsLocked (0x080907E4, reading sLockFieldControls 0x0203BF67),
 * ScriptContext_SetupScript (0x08090870: gScriptCmdTable 0x08297D18, whose `special` handler reads gSpecials 0x08298150)
 * and ShowPokemonStorageSystemPC, special 57 there (0x080C62A1: retail's code, its own task and lock calls). Headless on the
 * user's save: the PC menu opened, BOXES showed, three B presses closed it and the field was free again.
 */
val FIELD_ITEMS_MONOMYTH = FieldItemFns(
    varGet = 0x08096068L, varSet = 0x080960A0L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080907E4L, repelVar = 0 /* not mapped: no REPEL STEPS */, items = 0,
    codeCrcs = listOf(0x2D145029L, 0x54901474L, 0x3E311A26L),
    bagActions = false,
    pc = PcFns(0x08090870L, 57, 0x08298150L, 0x080C62A1L, listOf(0xEF3698BAL, 0x3DECE7E0L)),
)

/** Monomyth v1.1: v1.0's functions moved, the same code but ScriptContext_SetupScript's calls; special 57 is still the PC. */
val FIELD_ITEMS_MONOMYTH_V1_1 = FIELD_ITEMS_MONOMYTH.copy(
    varGet = 0x08096234L, varSet = 0x0809626CL, fieldControlsLocked = 0x080909D0L,
    pc = PcFns(0x08090A60L, 57, 0x08298510L, 0x080C6535L, listOf(0xCC9E3CD3L, 0x3DECE7E0L)),
)

/**
 * Emerald Imperium 1.3.1 (pokeemerald-expansion 1.10.0, no source): pokeemerald-expansion's 1.10.0 tag built plain with
 * Debian's arm-none-eabi-gcc 12 as the reference, each function found in Imperium by its first instructions (literal
 * loads / BLs masked); the field-lock trio by its byte (EXPAND's CONTROLS_LOCKED, 0x03000E84), ReturnToFieldFromFlyMapSelect
 * by its callback matching FieldCallback_UseFly, the town table by its first 32 entries, RAM from NATIVE_IMPERIUM / the
 * battle switch. PC anywhere and FLY only; no bag functions (0 = not checked). Emerald's FLY rules (badge 6 = 0x86C,
 * Littleroot by gender, Ever Grande / Battle Frontier); MON_DATA_MOVE1 / IS_EGG 20 / 52. Flown headless (Littleroot,
 * Rustboro, Oldale, Ever Grande, the Battle Frontier), the PC opened and closed.
 */
val FIELD_ITEMS_IMPERIUM = FieldItemFns(
    varGet = 0x08116BFCL, varSet = 0x08116C90L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x08200894L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x75D3C574L),
    party = PartyItemFns(
        getMonData = 0x081BDE00L, setMonData = 0x081BE590L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x020375F8L, partyCount = 0x020375F5L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xC6A5D42AL, 0xF260814BL),
    ),
    bagActions = false,
    pc = PcFns(0x08200930L, 63, 0x0828A4A8L, 0x081CC0CDL, listOf(0x16121208L, 0x09BD3001L)),
    fly = FlyFns(
        flagGet = 0x08116E54L, badgeFlag = 0x86C, setUpFieldMoveFly = 0x08193A48L, getMapsecType = 0x081F26E4L,
        fadeScreen = 0x08145BA8L, lockControls = 0x0820087CL, unlockControls = 0x08200888L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x020370E9L, destinations = 0x08DDAE6CL, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0xF7861D9EL, 0x460F285DL, 0xE44F15FBL, 0x0D60DD4CL, 0x4522C4C5L, 0xFAD9A57BL, 0xED94C77DL, 0x9FA231EBL, 0x0F3C1484L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58), monMoveField = 20, monEggField = 52,
        healWarp = HealWarp(
            toHealLocation = 0x08187BA0L, toMapWarp = 0x08187B28L, returnToField = 0x081313F4L,
            saveBlock2Ptr = 0x0300621CL, special = mapOf(15 to 11, 58 to 22),
            genderMapsec = 0, maleHeal = 12, femaleHeal = 13,
        ),
    ),
    flight = FLIGHT_IMPERIUM,
)

/**
 * Lazarus 2.0 (pokeemerald-expansion, no source): mapped like Imperium from expansion 1.10.0's build - FlagGet,
 * GetMapsecType and FadeScreen through their callers' BLs, the field-lock trio by its byte (0x03003B38), RAM from
 * NATIVE_LAZARUS / the battle switch. Its sMapHealLocations is retail's 50 Hoenn rows grown to 122: its own towns are
 * sections 90-121 (Acrisia City first). MON_DATA as 1.10 (probed on the save: moves at 20, level 62). Badge 6 (0x86C, its
 * party menu's FLAG_BADGE01_GET + 5). Flown headless (Acrisia, Sofos, Kalami City), the PC opened and closed.
 */
val FIELD_ITEMS_LAZARUS = FieldItemFns(
    varGet = 0x08114644L, varSet = 0x081146D8L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x0820B218L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x75F73111L),
    party = PartyItemFns(
        getMonData = 0x081C2F54L, setMonData = 0x081C36D0L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x0201B960L, partyCount = 0x0201B95DL, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xC6A5D42AL, 0xF260814BL),
    ),
    bagActions = false,
    pc = PcFns(0x0820B2B0L, 63, 0x0828CBF4L, 0x081D54D9L, listOf(0x16121208L, 0xDC62C107L)),
    fly = FlyFns(
        flagGet = 0x081148A0L, badgeFlag = 0x86C, setUpFieldMoveFly = 0x0819C0C0L, getMapsecType = 0x081FD308L,
        fadeScreen = 0x081450C0L, lockControls = 0x0820B200L, unlockControls = 0x0820B20CL, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0201B685L, destinations = 0x08CE479CL, firstMapsec = 0, count = 122,
        codeCrcs = listOf(0x6DE8DF3FL, 0x75E3EFAEL, 0xCA58413CL, 0x4F62D78CL, 0x824C20A1L, 0x3DB7411FL, 0xBC4A086AL, 0x06BC9D2AL, 0xE8CC9227L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 20, monEggField = 52,
        healWarp = HealWarp(
            toHealLocation = 0x0818F0B8L, toMapWarp = 0x0818F040L, returnToField = 0x0812E4D0L,
            saveBlock2Ptr = 0x03003668L, special = mapOf(),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_LAZARUS,
)

/**
 * Emerald Seaglass v3.0 (pokeemerald-expansion, no source, Lazarus's build of it: its RAM a few bytes off Lazarus's): every
 * function ported from Lazarus's verified table by masked code, else through its callers' BLs (votes), the 1.10 reference
 * agreeing; RAM and ROM data by the matched functions' pool words. Hoenn's 50-row sMapHealLocations, so Emerald's FLY rules
 * (badge 6 = 0x86C, Littleroot by gender, Ever Grande / Battle Frontier). gSpecials[63] = the PC. Flown headless on its save
 * from Littleroot (free flight, scenic routes with EXPAND matched every frame).
 */
val FIELD_ITEMS_SEAGLASS = FieldItemFns(
    varGet = 0x0810D104L, varSet = 0x0810D198L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x081EF4FCL, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x702BF616L),
    party = PartyItemFns(
        getMonData = 0x081A9448L, setMonData = 0x081A9BC0L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02019C20L, partyCount = 0x02019C1DL, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xC6A5D42AL, 0xF260814BL),
    ),
    bagActions = false,
    pc = PcFns(0x081EF598L, 63, 0x0826DD68L, 0x081BB3E1L, listOf(0x16121208L, 0xB1948204L)),
    fly = FlyFns(
        flagGet = 0x0810D35CL, badgeFlag = 0x86c, setUpFieldMoveFly = 0x08184904L, getMapsecType = 0x081E0C2CL,
        fadeScreen = 0x08138A70L, lockControls = 0x081EF4E4L, unlockControls = 0x081EF4F0L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0201996DL, destinations = 0x08955848L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0xB1C97055L, 0x306BED37L, 0xE6691796L, 0x9D56C50CL, 0xB89FF331L, 0x0764928FL, 0x60ED675EL, 0x2DA8EDBBL, 0x902950E2L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58), monMoveField = 20, monEggField = 52,
        healWarp = HealWarp(
            toHealLocation = 0x08178580L, toMapWarp = 0x08178508L, returnToField = 0x08125BD0L,
            saveBlock2Ptr = 0x030051BCL, special = mapOf(15 to 11, 58 to 22),
            genderMapsec = 0, maleHeal = 12, femaleHeal = 13,
        ),
    ),
    flight = FLIGHT_SEAGLASS,
)

/**
 * Too Many Types 2 v1.5.2 (pokeemerald-expansion 1.12.3 by its RHHEXP block, no source; its code differs from a plain build of
 * that tag): each function matched against 1.12.3's build by its instruction sequence, then checked - by its calls on known
 * tiles (the grid reads), its pool words (CreateSprite's gSprites, BeginNormalPaletteFade's gPaletteFade), or its place
 * beside a matched one (the field-lock trio); CB_ExitFlyMap read for the town table and its specials: Emerald's, plus map
 * section 0x49 to heal location 21. MON_DATA as 1.12 (moves at 20, IS_EGG 52; probed on the save). FLY only: no PC (its
 * special table isn't mapped). No following Pokemon in it.
 */
val FIELD_ITEMS_TMT2 = FieldItemFns(
    varGet = 0x0810C680L, varSet = 0x0810C70CL, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x081DC2D4L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x6CBAC3D0L, 0x71D660ADL, 0x33740AA2L),
    party = PartyItemFns(
        getMonData = 0x081984CCL, setMonData = 0x08199254L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02032C94L, partyCount = 0x02032715L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0x606845D5L, 0xC2290DFDL),
    ),
    bagActions = false,
    fly = FlyFns(
        flagGet = 0x0810C8D4L, badgeFlag = 0x86c, setUpFieldMoveFly = 0x08178FC4L, getMapsecType = 0x081CD118L,
        fadeScreen = 0x08137F70L, lockControls = 0x081DC2BCL, unlockControls = 0x081DC2C8L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x02032435L, destinations = 0x08E10EF8L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0x8DCF82E1L, 0xBDED4F98L, 0x3FFC12DBL, 0xA7DFDA63L, 0xCC1230F1L, 0x42EC4B88L, 0xB9A17CB3L, 0x98E0E358L, 0x9F5B5B34L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58, 73), monMoveField = 20, monEggField = 52,
        healWarp = HealWarp(
            toHealLocation = 0x08173930L, toMapWarp = 0x081738A0L, returnToField = 0x08129A90L,
            saveBlock2Ptr = 0x030057D4L, special = mapOf(15 to 11, 58 to 22, 73 to 21),
            genderMapsec = 0, maleHeal = 12, femaleHeal = 13,
        ),
    ),
    flight = FLIGHT_TMT2,
)

/**
 * R.O.W.E. v2.1.9.1 (BelialClover's own pokeemerald fork, no expansion): retail Emerald's verified table ported by masked
 * code, then callers' BLs, then pool words - its RAM landing exactly on the ROM's own symbol table (gObjectEvents, gSprites,
 * gMain, the save block pointers, the party, gPartyMenu). Its CB_ExitFlyMap is a switch over map sections, decoded into
 * [HealWarp.special]: Kanto and Sevii towns sit past retail's 50-row table, with their own heal locations (76 in all).
 * FLAG_BADGE01_GET is 0xCD7 there (its trainer card's badge check), so badge 6 is 0xCDC. Vanilla MON_DATA (moves at 13,
 * IS_EGG 45). gSpecials[63] = the PC. Flown headless on its save from Littleroot.
 */
val FIELD_ITEMS_ROWE = FieldItemFns(
    varGet = 0x080E0F3CL, varSet = 0x080E0F74L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080D9434L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x2D145029L, 0x54901474L, 0xCE9220C5L),
    party = PartyItemFns(
        getMonData = 0x0809AF5CL, setMonData = 0x0809B57CL, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02025128L, partyCount = 0x02025125L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xD0B6F369L, 0xA898ABB0L),
    ),
    bagActions = false,
    pc = PcFns(0x080D9540L, 63, 0x082545E0L, 0x08116A01L, listOf(0x13B39B1CL, 0x53FBCB0DL)),
    fly = FlyFns(
        flagGet = 0x080E10BCL, badgeFlag = 0xcdc, setUpFieldMoveFly = 0x08218F38L, getMapsecType = 0x0817B3CCL,
        fadeScreen = 0x080F25D8L, lockControls = 0x080D941CL, unlockControls = 0x080D9428L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203E77DL, destinations = 0x08FD3DE0L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0x78731671L, 0x48CC197BL, 0xD503322AL, 0x5C7A97CFL, 0xDD6560B2L, 0xD4A92DBAL, 0x2D6C39E2L, 0xA4535461L, 0x5FC7015AL),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(56, 58, 73, 88, 89, 90, 91, 92, 93, 94, 95, 96, 98, 137, 143, 144, 145, 146, 147, 148, 149, 157), monMoveField = 13, monEggField = 45,
        healWarp = HealWarp(
            toHealLocation = 0x080C2CF8L, toMapWarp = 0x080C2B3CL, returnToField = 0x080FE58CL,
            saveBlock2Ptr = 0x03004B00L, special = mapOf(15 to 11, 56 to 57, 58 to 22, 73 to 21, 88 to 58, 89 to 59, 90 to 60, 91 to 61, 92 to 64, 93 to 63, 94 to 65, 95 to 66, 96 to 67, 98 to 62, 137 to 12, 143 to 69, 144 to 70, 145 to 71, 146 to 72, 147 to 73, 148 to 75, 149 to 74, 157 to 76),
            genderMapsec = 0, maleHeal = 12, femaleHeal = 13,
        ),
    ),
    flight = FLIGHT_ROWE,
)

/**
 * Super Mariomon v1.5.1 / v1.5.2 Anniversary (an old pokeemerald-expansion built with agbcc: retail Emerald's code where the
 * expansion left it): retail Emerald's verified table ported by masked code, callers and pool words, v1.5.2 from v1.5.1 the
 * same way. Its CB_ExitFlyMap read: its own 168-row town table and four special towns (no gender case: the player is Mario).
 * Its CreateFlyBirdSprite loads the bird's palette first (index 0x1A), as R.O.W.E.'s does. GetMonData's numbers two past
 * vanilla's (moves at 15, IS_EGG 47; probed on the save). gSpecials[63] = the PC. [fieldItemsCandidatesForGame] lets the
 * code CRCs pick the build; v1.2.1 / v1.2.2 / v1.3 (the other RAM layout) aren't mapped.
 */
val FIELD_ITEMS_MARIOMON_V1_5 = FieldItemFns(
    varGet = 0x080C402CL, varSet = 0x080C4064L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080BED04L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x2D145029L, 0x54901474L, 0x538293F4L),
    party = PartyItemFns(
        getMonData = 0x0808A2C0L, setMonData = 0x0808AA60L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02025158L, partyCount = 0x02025155L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0x512B36B2L, 0xF711C2C4L),
    ),
    bagActions = false,
    pc = PcFns(0x080BED90L, 63, 0x0821F76CL, 0x080F0249L, listOf(0xEF3698BAL, 0xB4419B33L)),
    fly = FlyFns(
        flagGet = 0x080C416CL, badgeFlag = 0x86c, setUpFieldMoveFly = 0x081B8C5CL, getMapsecType = 0x081558E4L,
        fadeScreen = 0x080D3094L, lockControls = 0x080BECECL, unlockControls = 0x080BECF8L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203E9B9L, destinations = 0x08837564L, firstMapsec = 0, count = 168,
        codeCrcs = listOf(0x78731671L, 0xAD5B9839L, 0xB6660C16L, 0x038209E9L, 0xF387E86EL, 0xFA4BA566L, 0x9E87E6D6L, 0x2F1D18CAL, 0x85770D80L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 15, monEggField = 47,
        healWarp = HealWarp(
            toHealLocation = 0x080A8CB8L, toMapWarp = 0x080A8C14L, returnToField = 0x080DF4A8L,
            saveBlock2Ptr = 0x03004BD0L, special = mapOf(15 to 35, 73 to 21, 88 to 32, 94 to 27),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_MARIOMON_V1_5,
)
val FIELD_ITEMS_MARIOMON_V1_5_2 = FieldItemFns(
    varGet = 0x080C410CL, varSet = 0x080C4144L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080BEDE4L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x2D145029L, 0x54901474L, 0x538293F4L),
    party = PartyItemFns(
        getMonData = 0x0808A384L, setMonData = 0x0808AB24L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02025158L, partyCount = 0x02025155L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xD82E48D9L, 0x7BE99C24L),
    ),
    bagActions = false,
    pc = PcFns(0x080BEE70L, 63, 0x0821F93CL, 0x080F0361L, listOf(0xEF3698BAL, 0x2391FB16L)),
    fly = FlyFns(
        flagGet = 0x080C424CL, badgeFlag = 0x86c, setUpFieldMoveFly = 0x081B8DDCL, getMapsecType = 0x081559FCL,
        fadeScreen = 0x080D3174L, lockControls = 0x080BEDCCL, unlockControls = 0x080BEDD8L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203E9B9L, destinations = 0x08837A18L, firstMapsec = 0, count = 168,
        codeCrcs = listOf(0x78731671L, 0x36027784L, 0x4C94D1F3L, 0x038209E9L, 0xF387E86EL, 0xFA4BA566L, 0x17E42B52L, 0x2F1D18CAL, 0x7D2D3809L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 15, monEggField = 47,
        healWarp = HealWarp(
            toHealLocation = 0x080A8D8CL, toMapWarp = 0x080A8CE8L, returnToField = 0x080DF5B4L,
            saveBlock2Ptr = 0x03004BD0L, special = mapOf(15 to 35, 73 to 21, 88 to 32, 94 to 27),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_MARIOMON_V1_5_2,
)

/**
 * Quetzal Alpha 9.0 (English): agbcc pokeemerald grown into its own engine, so retail Emerald's verified table ported by
 * masked code, callers and pool words (its RAM landing on NATIVE_QUETZAL's). FLY's gate is the game's own: its party menu
 * checks the current region's badge for a field move through its own function (0x08293CAC, FLY's entry 0xD9 in its table at
 * 0x092C2430), used here as [FlyFns.flagGet] / [FlyFns.badgeFlag]; SetUpFieldMove_Fly from its field-move callback table.
 * Its Fly warp replays Hoenn's 50-row table and Emerald's specials (Littleroot fixed at heal location 12); its other regions'
 * towns have handlers of their own, not offered here. Vanilla MON_DATA. gSpecials[63] = the PC. The bird's palette loaded
 * first (index 0x1A). Flown headless on the Spanish save from Jagged Pass.
 */
val FIELD_ITEMS_QUETZAL = FieldItemFns(
    varGet = 0x080E1A54L, varSet = 0x080E1A8CL, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080DB940L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x5E1955EEL, 0x974222FCL, 0xE9BE9306L),
    party = PartyItemFns(
        getMonData = 0x0809C9D4L, setMonData = 0x0809D164L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x020235CCL, partyCount = 0x020235C9L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0x580B6010L, 0xD3637F5FL),
    ),
    bagActions = false,
    pc = PcFns(0x080DB9CCL, 63, 0x082AC36CL, 0x081046C1L, listOf(0xEF3698BAL, 0x16040B9DL)),
    fly = FlyFns(
        flagGet = 0x08293CACL, badgeFlag = 0xd9, setUpFieldMoveFly = 0x0822A68CL, getMapsecType = 0x0818B970L,
        fadeScreen = 0x080FC36CL, lockControls = 0x080DB928L, unlockControls = 0x080DB934L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203EA05L, destinations = 0x0922B1C8L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0x033909CDL, 0x835A5257L, 0xB58C94D7L, 0x5C7A97CFL, 0x17AEE52BL, 0x1E62A823L, 0xFC0F5150L, 0x2F1D18CAL, 0x21B04BE8L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58, 73), monMoveField = 13, monEggField = 45,
        healWarp = HealWarp(
            toHealLocation = 0x080BEDECL, toMapWarp = 0x080BED28L, returnToField = 0x08110210L,
            saveBlock2Ptr = 0x030055C0L, special = mapOf(0 to 12, 15 to 11, 58 to 22, 73 to 21),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_QUETZAL,
)

/** Quetzal Alpha 9.0 (Spanish) and Alpha 9v1 (English / Spanish): Alpha 9.0 English's table ported (masked code, callers, pool
 * words); 9v1 creates the bird through a field-effect helper of its own, so its template is read from that helper's table. */
val FIELD_ITEMS_QUETZAL_ES = FieldItemFns(
    varGet = 0x080E1A6CL, varSet = 0x080E1AA4L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080DB958L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x5E1955EEL, 0x974222FCL, 0xE9BE9306L),
    party = PartyItemFns(
        getMonData = 0x0809C9B0L, setMonData = 0x0809D140L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x020235CCL, partyCount = 0x020235C9L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xA5391E4BL, 0x68350C8AL),
    ),
    bagActions = false,
    pc = PcFns(0x080DB9E4L, 63, 0x082AC1CCL, 0x081046D9L, listOf(0xEF3698BAL, 0xE364C232L)),
    fly = FlyFns(
        flagGet = 0x08293AF0L, badgeFlag = 0xd9, setUpFieldMoveFly = 0x0822A5A4L, getMapsecType = 0x0818B858L,
        fadeScreen = 0x080FC384L, lockControls = 0x080DB940L, unlockControls = 0x080DB94CL, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203EA05L, destinations = 0x0922E444L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0x033909CDL, 0xEF35D490L, 0xB58C94D7L, 0x5C7A97CFL, 0x17AEE52BL, 0x1E62A823L, 0x24F1FF1FL, 0x2F1D18CAL, 0x8522F30DL),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58, 73), monMoveField = 13, monEggField = 45,
        healWarp = HealWarp(
            toHealLocation = 0x080BEDE0L, toMapWarp = 0x080BED1CL, returnToField = 0x08110228L,
            saveBlock2Ptr = 0x030055C0L, special = mapOf(0 to 12, 15 to 11, 58 to 22, 73 to 21),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_QUETZAL_ES,
)
val FIELD_ITEMS_QUETZAL_V1 = FieldItemFns(
    varGet = 0x080E4558L, varSet = 0x080E4590L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080DE2B4L, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x5E1955EEL, 0x974222FCL, 0xE9BE9306L),
    party = PartyItemFns(
        getMonData = 0x0809EAE8L, setMonData = 0x0809F278L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x020235CCL, partyCount = 0x020235C9L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0x8E37D7DDL, 0x30B75BFEL),
    ),
    bagActions = false,
    pc = PcFns(0x080DE340L, 63, 0x082BF49CL, 0x0810750DL, listOf(0xEF3698BAL, 0x89BF90F8L)),
    fly = FlyFns(
        flagGet = 0x082A169CL, badgeFlag = 0xd9, setUpFieldMoveFly = 0x08236558L, getMapsecType = 0x08196A40L,
        fadeScreen = 0x080FF0BCL, lockControls = 0x080DE29CL, unlockControls = 0x080DE2A8L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203EA05L, destinations = 0x09235750L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0x6F3F5BC8L, 0xE2D711E9L, 0x159A48E5L, 0x5C7A97CFL, 0x17AEE52BL, 0x1E62A823L, 0x0F85E929L, 0x2F1D18CAL, 0x9052E6DDL),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58, 73), monMoveField = 13, monEggField = 45,
        healWarp = HealWarp(
            toHealLocation = 0x080C132CL, toMapWarp = 0x080C1268L, returnToField = 0x08113308L,
            saveBlock2Ptr = 0x030055C0L, special = mapOf(0 to 12, 15 to 11, 58 to 22, 73 to 21),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_QUETZAL_V1,
)
val FIELD_ITEMS_QUETZAL_ES_V1 = FieldItemFns(
    varGet = 0x080E4570L, varSet = 0x080E45A8L, checkBagHasItem = 0, removeBagItem = 0,
    fieldControlsLocked = 0x080DE2CCL, repelVar = 0x4021, items = 0,
    codeCrcs = listOf(0x5E1955EEL, 0x974222FCL, 0xE9BE9306L),
    party = PartyItemFns(
        getMonData = 0x0809EAC4L, setMonData = 0x0809F254L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x020235CCL, partyCount = 0x020235C9L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(),
        codeCrcs = listOf(0xB3730972L, 0x187D480CL),
    ),
    bagActions = false,
    pc = PcFns(0x080DE358L, 63, 0x082BF354L, 0x08107525L, listOf(0xEF3698BAL, 0x757E410DL)),
    fly = FlyFns(
        flagGet = 0x082A1548L, badgeFlag = 0xd9, setUpFieldMoveFly = 0x082364D8L, getMapsecType = 0x08196990L,
        fadeScreen = 0x080FF0D4L, lockControls = 0x080DE2B4L, unlockControls = 0x080DE2C0L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x0203EA05L, destinations = 0x09238A14L, firstMapsec = 0, count = 50,
        codeCrcs = listOf(0x6F3F5BC8L, 0x7E186EC3L, 0x159A48E5L, 0x5C7A97CFL, 0x17AEE52BL, 0x1E62A823L, 0x034546A5L, 0x2F1D18CAL, 0x3DE8C9E7L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(58, 73), monMoveField = 13, monEggField = 45,
        healWarp = HealWarp(
            toHealLocation = 0x080C1320L, toMapWarp = 0x080C125CL, returnToField = 0x08113320L,
            saveBlock2Ptr = 0x030055C0L, special = mapOf(0 to 12, 15 to 11, 58 to 22, 73 to 21),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_QUETZAL_ES_V1,
)

/**
 * SoulGold (pokeemerald-expansion, no source), its four builds (v1.2.1 below): v1.1.4 mapped from pokeemerald-expansion 1.13.0's build
 * (FlagGet, VarGet / VarSet, FadeScreen, the PC by code; GetMonData / SetMonData through their callers), the rest from its
 * own code - gFieldMoveInfo's FLY entry (SetUpFieldMove_Fly, IsFieldMoveUnlocked_Fly), SetFlyDestination's literal and
 * calls (sMapHealLocations 313 rows by u16 section, Johto from 196; the two warps; gSaveBlock2Ptr), the call after it in
 * CB_ExitFlyMap (ReturnToFieldFromFlyMapSelect), GetMapsecType by its switch (it takes the section minus 0xC3), the
 * field-lock trio by its byte (0x03001F74), the PC as special 71. MON_DATA_MOVE1 / IS_EGG 21 / 55 (probed: one field more
 * than Heart and Soul's from 20). The v1.2 builds from v1.1.4 by code, SetUpFieldMove_Fly from their own gFieldMoveInfo
 * (the masked match took a neighbour), checked against their own SetFlyDestination. Flown headless on all four (New Bark,
 * Cherrygrove; v1.1.4 Goldenrod too), the PC opened on each. [fieldItemsCandidatesForGame] lets the CRCs pick the build.
 */
val FIELD_ITEMS_SOULGOLD = FieldItemFns(
    varGet = 0x08133F38L, varSet = 0x08133FCCL, checkBagHasItem = 0x08189F88L, removeBagItem = 0x0818AADCL,
    fieldControlsLocked = 0x082363D8L, repelVar = 0x4021, items = 0x087520A0L,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x3DAF3E13L, 0xF947826AL, 0x41C9CB9EL),
    repelItems = SOULGOLD_REPEL_ITEMS, repelLureBit = 0x8000, itemParamOffset = 9, lastRepelVar = 0x40BD,
    party = PartyItemFns(
        getMonData = 0x081ED710L, setMonData = 0x081EDD68L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x0203901CL, partyCount = 0x02038DD5L, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(), monStride = 96,
        codeCrcs = listOf(0xDA6FC37DL, 0x7D68264BL),
    ),
    bagActions = false,
    pc = PcFns(0x0823647CL, 71, 0x082D1804L, 0x08206835L, listOf(0xB41D4149L, 0x6EA471A2L)),
    fly = FlyFns(
        flagGet = 0x08155B88L, badgeFlag = 0x0, setUpFieldMoveFly = 0x081D2384L, getMapsecType = 0x08223064L,
        fadeScreen = 0x0816187CL, lockControls = 0x082363B8L, unlockControls = 0x082363CCL, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x02038D2DL, destinations = 0x08F3738CL, firstMapsec = 0, count = 313,
        codeCrcs = listOf(0xF2387223L, 0x26AB0FBDL, 0x1595030BL, 0x194580CBL, 0x49C48542L, 0x10E7E49AL, 0xC0BE06FBL, 0x99B2D9C6L, 0x6A74490DL),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 21, monEggField = 55,
        healWarp = HealWarp(
            toHealLocation = 0x081BD184L, toMapWarp = 0x081BD100L, returnToField = 0x081521ECL,
            saveBlock2Ptr = 0x030040C0L, special = mapOf(),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_SOULGOLD,
)
val FIELD_ITEMS_SOULGOLD_V1_2 = FieldItemFns(
    varGet = 0x0813466CL, varSet = 0x08134700L, checkBagHasItem = 0x0818A694L, removeBagItem = 0x0818B1E8L,
    fieldControlsLocked = 0x08236CB8L, repelVar = 0x4021, items = 0x08753834L,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x3DAF3E13L, 0xF947826AL, 0x41C9CB9EL),
    repelItems = SOULGOLD_REPEL_ITEMS, repelLureBit = 0x8000, itemParamOffset = 9, lastRepelVar = 0x40BD,
    party = PartyItemFns(
        getMonData = 0x081EE340L, setMonData = 0x081EE998L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02039024L, partyCount = 0x02038DDDL, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(), monStride = 96,
        codeCrcs = listOf(0x9A0CA5AEL, 0x7D68264BL),
    ),
    bagActions = false,
    pc = PcFns(0x08236D5CL, 71, 0x082D20C4L, 0x08207135L, listOf(0xB41D4149L, 0xCED42BCBL)),
    fly = FlyFns(
        flagGet = 0x08156238L, badgeFlag = 0x0, setUpFieldMoveFly = 0x081D302CL, getMapsecType = 0x0822393CL,
        fadeScreen = 0x08161F28L, lockControls = 0x08236C98L, unlockControls = 0x08236CACL, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x02038D35L, destinations = 0x08F424D8L, firstMapsec = 0, count = 313,
        codeCrcs = listOf(0x6BE6A214L, 0x26AB0FBDL, 0x7288EAB0L, 0x194580CBL, 0xCCA8CCA7L, 0x10E7E49AL, 0x7A5F4BEBL, 0x5C06F129L, 0x215DCE2AL),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 21, monEggField = 55,
        healWarp = HealWarp(
            toHealLocation = 0x081BDB78L, toMapWarp = 0x081BDAF4L, returnToField = 0x08152718L,
            saveBlock2Ptr = 0x030040C0L, special = mapOf(),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_SOULGOLD_V1_2,
)
val FIELD_ITEMS_SOULGOLD_V1_2B = FieldItemFns(
    varGet = 0x0813465CL, varSet = 0x081346F0L, checkBagHasItem = 0x0818A684L, removeBagItem = 0x0818B1D8L,
    fieldControlsLocked = 0x08236C20L, repelVar = 0x4021, items = 0x0875379CL,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x3DAF3E13L, 0xF947826AL, 0x41C9CB9EL),
    repelItems = SOULGOLD_REPEL_ITEMS, repelLureBit = 0x8000, itemParamOffset = 9, lastRepelVar = 0x40BD,
    party = PartyItemFns(
        getMonData = 0x081EE2D4L, setMonData = 0x081EE92CL, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02039024L, partyCount = 0x02038DDDL, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(), monStride = 96,
        codeCrcs = listOf(0x9A0CA5AEL, 0x7D68264BL),
    ),
    bagActions = false,
    pc = PcFns(0x08236CC4L, 71, 0x082D202CL, 0x0820709DL, listOf(0xB41D4149L, 0xCED42BCBL)),
    fly = FlyFns(
        flagGet = 0x08156228L, badgeFlag = 0x0, setUpFieldMoveFly = 0x081D2FC0L, getMapsecType = 0x082238A4L,
        fadeScreen = 0x08161F18L, lockControls = 0x08236C00L, unlockControls = 0x08236C14L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x02038D35L, destinations = 0x08F42434L, firstMapsec = 0, count = 313,
        codeCrcs = listOf(0x6BE6A214L, 0x26AB0FBDL, 0x2E44EAB9L, 0x194580CBL, 0x5A477C60L, 0x10E7E49AL, 0x20BA9723L, 0x5C06F129L, 0xE4CC882DL),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 21, monEggField = 55,
        healWarp = HealWarp(
            toHealLocation = 0x081BDB0CL, toMapWarp = 0x081BDA88L, returnToField = 0x08152708L,
            saveBlock2Ptr = 0x030040C0L, special = mapOf(),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_SOULGOLD_V1_2B,
)
val FIELD_ITEMS_SOULGOLD_V1_2C = FieldItemFns(
    varGet = 0x081346C8L, varSet = 0x0813475CL, checkBagHasItem = 0x0818A6F0L, removeBagItem = 0x0818B244L,
    fieldControlsLocked = 0x08236D18L, repelVar = 0x4021, items = 0x08753928L,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x3DAF3E13L, 0xF947826AL, 0x41C9CB9EL),
    repelItems = SOULGOLD_REPEL_ITEMS, repelLureBit = 0x8000, itemParamOffset = 9, lastRepelVar = 0x40BD,
    party = PartyItemFns(
        getMonData = 0x081EE3A0L, setMonData = 0x081EE9F8L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02039024L, partyCount = 0x02038DDDL, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(), monStride = 96,
        codeCrcs = listOf(0x9A0CA5AEL, 0x7D68264BL),
    ),
    bagActions = false,
    pc = PcFns(0x08236DBCL, 71, 0x082D2124L, 0x08207195L, listOf(0xB41D4149L, 0xCED42BCBL)),
    fly = FlyFns(
        flagGet = 0x08156294L, badgeFlag = 0x0, setUpFieldMoveFly = 0x081D308CL, getMapsecType = 0x0822399CL,
        fadeScreen = 0x08161F84L, lockControls = 0x08236CF8L, unlockControls = 0x08236D0CL, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x02038D35L, destinations = 0x08F425CCL, firstMapsec = 0, count = 313,
        codeCrcs = listOf(0x6BE6A214L, 0x26AB0FBDL, 0x40BE8832L, 0x194580CBL, 0xE2F80527L, 0x10E7E49AL, 0xCA6219BBL, 0x5C06F129L, 0x9A609E57L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 21, monEggField = 55,
        healWarp = HealWarp(
            toHealLocation = 0x081BDBD8L, toMapWarp = 0x081BDB54L, returnToField = 0x08152774L,
            saveBlock2Ptr = 0x030040C0L, special = mapOf(),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_SOULGOLD_V1_2C,
)
/** SoulGold v1.2.1 (both builds): the third v1.2 build's functions, matched in its code (masked bytes, else through their
 * callers; SetUpFieldMoveFly from its gFieldMoveInfo, as a masked match takes its twin neighbour again), the Repel's
 * by find_field_items_soulgold.py; the party globals and gPartyMenu moved with its EWRAM. Headless on the v1.2 save:
 * the PC opened, a flight from Cherrygrove landed in New Bark. */
val FIELD_ITEMS_SOULGOLD_V1_2_1 = FieldItemFns(
    varGet = 0x08136B90L, varSet = 0x08136C24L, checkBagHasItem = 0x0818D158L, removeBagItem = 0x0818DCACL,
    fieldControlsLocked = 0x0823BB60L, repelVar = 0x4021, items = 0x0875B360L,
    codeCrcs = listOf(0xAEDB0050L, 0xC6FC58DDL, 0x3DAF3E13L, 0xF947826AL, 0x41C9CB9EL),
    repelItems = SOULGOLD_REPEL_ITEMS, repelLureBit = 0x8000, itemParamOffset = 9, lastRepelVar = 0x40BD,
    party = PartyItemFns(
        getMonData = 0x081F1748L, setMonData = 0x081F1D28L, itemNoEffect = null, itemEffect = 0, addBagItem = 0,
        party = 0x02038E68L, partyCount = 0x02038C1DL, scratch = 0x0203FFF0L, itemCount = 0, unholdablePockets = emptySet(), monStride = 96,
        codeCrcs = listOf(0x99E9AFF8L, 0x2E734A9CL),
    ),
    bagActions = false,
    pc = PcFns(0x0823BC04L, 71, 0x082D77C4L, 0x0820F339L, listOf(0xB41D4149L, 0x9225F1BFL)),
    fly = FlyFns(
        flagGet = 0x08158790L, badgeFlag = 0x0, setUpFieldMoveFly = 0x081D5ECCL, getMapsecType = 0x0822807CL,
        fadeScreen = 0x081643B4L, lockControls = 0x0823BB40L, unlockControls = 0x0823BB54L, setFlyWarpDestination = 0,
        partyMenuSlotId = 0x02038B75L, destinations = 0x08F4DE4CL, firstMapsec = 0, count = 313,
        codeCrcs = listOf(0x737934D0L, 0x26AB0FBDL, 0x6108C555L, 0x194580CBL, 0x6FE2BF05L, 0x10E7E49AL, 0xAF17E189L, 0xC39FD5A6L, 0x31398813L),
        flyTypes = setOf(2, 4), extraMapsecs = listOf(), monMoveField = 21, monEggField = 55,
        healWarp = HealWarp(
            toHealLocation = 0x081C0A44L, toMapWarp = 0x081C09C0L, returnToField = 0x08154C70L,
            saveBlock2Ptr = 0x030040C0L, special = mapOf(),
            genderMapsec = -1, maleHeal = 0, femaleHeal = 0,
        ),
    ),
    flight = FLIGHT_SOULGOLD_V1_2_1,
)

/** The CFRU hacks keep every function the tables use where FireRed rev 0 has it (each checked by CRC), with their
 * own item table: repointed and grown ([items]; ids past the real ones never reach the bag, so a generous
 * [itemCount] is harmless). Sword and Shield moved Berries and Mail into pocket 2 beside the key items, so only the
 * TM case's pocket keeps items out by pocket there - its key items by their importance flag. */
private fun cfru(items: Long, unholdablePockets: Set<Int> = setOf(2, 4)): FieldItemFns? = FIELD_ITEMS_PORTS["BPRE0"]?.let { t ->
    t.copy(items = items, party = t.party!!.copy(itemCount = 0x300, unholdablePockets = unholdablePockets)).u16Mapsecs()
}

/** FREE FLIGHT for a FireRed 1.0 hack edited in place, by its config (the Poller's, from its hash). */
private fun fireRedHackFlight(gameCode: String, cfg: NativeConfig?): FlightFns? = when {
    gameCode == "PUVV" -> FLIGHT_ULTRA_VIOLET
    cfg == null -> null
    cfg === NATIVE_GHOST_GREY || cfg === NATIVE_GHOST_GREY_V1_0_1 -> FLIGHT_GHOST_GREY
    cfg === NATIVE_LETS_GO_PIKACHU || cfg === NATIVE_LETS_GO_EEVEE -> FLIGHT_LETS_GO
    cfg === NATIVE_ROCKET_EDITION -> FLIGHT_ROCKET_EDITION
    cfg === NATIVE_ASH_GRAY -> FLIGHT_ASH_GRAY
    else -> null
}

/** FLY on a FireRed hack that widened map sections to 16 bits: its patched GetMapsecType counts as the game's. */
private fun FieldItemFns.u16Mapsecs() = copy(fly = fly?.copy(getMapsecTypeAltCrc = GET_MAPSEC_TYPE_U16))

/**
 * The tables for the running game: its config's own ([configured]: NativeConfig.fieldItems, a rebuild like Recharged
 * Emerald's, which shares retail's game code but none of its code), else by [kind] for the hacks built on retail code
 * (Glazed on Emerald; Orange Islands, Ultra Violet and the CFRU hacks on FireRed rev 0), else by header code and
 * revision for the retail games, which run as FIRERED / EMERALD. The code CRCs still decide ([fieldItemSupport]).
 */
fun fieldItemsForGame(kind: GameKind?, gameCode: String, revision: Int, configured: FieldItemFns? = null): FieldItemFns? =
    configured ?: fieldItemsByKind(kind, gameCode, revision)

private fun fieldItemsByKind(kind: GameKind?, gameCode: String, revision: Int): FieldItemFns? = when (kind) {
    GameKind.GLAZED -> FIELD_ITEMS_GLAZED.takeIf { gameCode == "BPEE" }
    GameKind.ORANGE_ISLANDS -> FIELD_ITEMS_PORTS["BPRE0"]?.u16Mapsecs()?.copy(flight = FLIGHT_ORANGE_ISLANDS)
        .takeIf { gameCode == "BPRE" && revision == 0 }
    GameKind.HEART_AND_SOUL -> FIELD_ITEMS_HEART_AND_SOUL.takeIf { gameCode == "BPEE" }
    GameKind.IMPERIUM -> FIELD_ITEMS_IMPERIUM.takeIf { gameCode == "BPEE" }
    GameKind.LAZARUS -> FIELD_ITEMS_LAZARUS.takeIf { gameCode == "BPEE" }
    GameKind.EMERALD_SEAGLASS -> FIELD_ITEMS_SEAGLASS.takeIf { gameCode == "BPEE" }
    GameKind.TMT2 -> FIELD_ITEMS_TMT2.takeIf { gameCode == "BPEE" }
    GameKind.ROWE -> FIELD_ITEMS_ROWE.takeIf { gameCode == "BPEE" }
    GameKind.UNBOUND -> cfru(0x08876200L)?.let { t -> t.copy(fly = t.fly?.let(::flyUnbound), flight = FLIGHT_UNBOUND) }
    GameKind.RADICAL_RED -> cfru(0x093C0000L)?.copy(flight = FLIGHT_RADICAL_RED)
    GameKind.ODYSSEY -> cfru(0x083DB028L)?.copy(flight = FLIGHT_ODYSSEY)
    GameKind.GAIA -> cfru(0x083DB028L)?.copy(flight = FLIGHT_GAIA)
    GameKind.AMETHYST -> cfru(0x0872DB0CL)?.copy(flight = if (amethystV141) FLIGHT_AMETHYST_V141 else FLIGHT_AMETHYST)
    GameKind.SWORD_SHIELD -> cfru(0x093C0000L, unholdablePockets = setOf(4))?.copy(flight = FLIGHT_SWORD_SHIELD)
    GameKind.GS_CHRONICLES -> cfru(0x08F70000L)?.copy(flight = FLIGHT_GS_CHRONICLES)
    // The FireRed 1.0 hacks edited in place run as FIRERED: their own flight record by their config (else rev 0's,
    // which flightAllowed keeps to the retail ROM).
    GameKind.FIRERED -> (if (gameCode == "PUVV") FIELD_ITEMS_PORTS["BPRE0"] else fieldItemsFor(gameCode, revision))
        ?.let { t -> fireRedHackFlight(gameCode, activeNativeConfig)?.let { t.copy(flight = it) } ?: t }
    // Emerald Kaizo: retail's FLY code, but its map reload crashed when the game's ReturnToField came straight from the
    // field (headless, on retail's save: the first flight, or the fourth once the overworld's windows were freed first),
    // where the game's own Fly map flies fine. So no classic FLY there: FLY goes over the map, which never warps.
    GameKind.EMERALD -> if (emeraldLegacy) FIELD_ITEMS_EMERALD_LEGACY.takeIf { gameCode == "BPEE" }
        else fieldItemsFor(gameCode, revision)?.let {
            if (romGameCode == KAIZO_TEXT) it.copy(classicFly = false, flight = FLIGHT_EMERALD_KAIZO) else it
        }
    else -> null
}

/** Every table a game may need, for the games whose builds share a [GameKind] and game code (SoulGold's five): the
 * code CRCs pick the build ([fieldItemSupport] returns null for the others). Otherwise [fieldItemsForGame]'s one. */
fun fieldItemsCandidatesForGame(kind: GameKind?, gameCode: String, revision: Int, configured: FieldItemFns? = null): List<FieldItemFns> = when (kind) {
    GameKind.MARIOMON -> if (gameCode == "BPEE") listOf(FIELD_ITEMS_MARIOMON_V1_5, FIELD_ITEMS_MARIOMON_V1_5_2) else emptyList()
    GameKind.QUETZAL -> if (gameCode == "BPEE") listOf(FIELD_ITEMS_QUETZAL, FIELD_ITEMS_QUETZAL_ES, FIELD_ITEMS_QUETZAL_V1, FIELD_ITEMS_QUETZAL_ES_V1) else emptyList()
    GameKind.SOULGOLD -> if (gameCode == "BPEE") listOf(
        FIELD_ITEMS_SOULGOLD, FIELD_ITEMS_SOULGOLD_V1_2, FIELD_ITEMS_SOULGOLD_V1_2B, FIELD_ITEMS_SOULGOLD_V1_2C,
        FIELD_ITEMS_SOULGOLD_V1_2_1,
    ) else emptyList()
    else -> listOfNotNull(fieldItemsForGame(kind, gameCode, revision, configured))
}

/** The tables for a ROM by its header's game code and revision byte; the code CRCs still decide ([fieldItemSupport]). */
fun fieldItemsFor(gameCode: String, revision: Int): FieldItemFns? = when (gameCode) {
    "BPRE" -> FIELD_ITEMS_FIRERED_REV1.takeIf { revision == 1 }
    "BPGE" -> when (revision) { 0 -> FIELD_ITEMS_LEAFGREEN_REV0; 1 -> FIELD_ITEMS_LEAFGREEN_REV1; else -> null }
    "BPEE" -> FIELD_ITEMS_EMERALD
    "AXVE" -> FIELD_ITEMS_RUBY.takeIf { revision == 1 || revision == 2 }
    "AXPE" -> FIELD_ITEMS_SAPPHIRE.takeIf { revision == 1 || revision == 2 }
    else -> null
} ?: FIELD_ITEMS_PORTS["$gameCode$revision"] // the other languages, English FireRed / Ruby / Sapphire rev 0

enum class ItemUseResult {
    USED, STILL_ACTIVE, NOT_NOW, NONE_LEFT, FAILED,
    /** NOT_NOW because a battle is on (the app's answer, from the snapshot: the game code only says NOT_NOW). */
    IN_BATTLE,
    /** The game's "It won't have any effect." */
    NO_EFFECT,
    /** GIVE: the Pokémon holds it now ([ItemUseOutcome.swapped] = what it held before, 0 = nothing). */
    GIVEN,
    /** GIVE: no room in the bag for the item the Pokémon held; nothing changed. */
    BAG_FULL,
    /** The party isn't what the pick showed any more (a switch, a trade): nothing changed. */
    PARTY_CHANGED,
    /** TAKE: the held item ([ItemUseOutcome.swapped]) is in the bag now. */
    TAKEN,
    /** A TM / HM: the game's own TM screen is opening (TmTeach.kt); the rest happens there. */
    IN_GAME,
}

/** What using [itemId] did and, for a Repel, how many steps it lasts; a GIVE's item held before ([swapped]). */
data class ItemUseOutcome(val result: ItemUseResult, val steps: Int = 0, val swapped: Int = 0)

/** What the game's functions are reached through: [call] runs one (r0-r3) and gives back r0, -1 if it
 * never returned; [read] reads memory (ROM or RAM); [write] writes RAM. The emu thread, between frames. */
interface GameCalls {
    fun call(fn: Long, a0: Int = 0, a1: Int = 0, a2: Int = 0, a3: Int = 0): Long
    fun read(addr: Long, len: Int): ByteArray
    fun write(addr: Long, bytes: ByteArray): Boolean

    /** A Game Boy cart's own bytes at [off] (it's bank-switched: not all on the bus); null on the GBA. */
    fun rom(off: Long, len: Int): ByteArray? = null

    /** A Game Boy game calls [fn] from its main loop at [at] (GbField.kt, MgbaCore.pkGbCallAt). */
    fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean = false
}

/**
 * What the companion can do with each item on this ROM: built once per ROM ([fieldItemSupport]) and
 * read while drawing the bag, so it never changes with the moment (a battle, a menu) - those get NOT
 * NOW when tapped. The row mark ([marked]) shows only where the party actions are there too.
 */
class FieldItemSupport(
    val fns: FieldItemFns,
    private val holdable: Set<Int>,
    /** What USE works on here: [PartyItemFns.usable] as the ROM's gItems has it ([PartyItemFns.useFuncs]), plus the Repels. */
    private val usable: Set<Int> = fns.party?.usable.orEmpty() + fns.repelItems,
    /** FLY's code is where [FieldItemFns.fly] says. */
    val canFly: Boolean = false,
    /** FREE FLIGHT (Flight.kt): FLY's code and the flight's both where expected. */
    val canFlight: Boolean = false,
    /** PC anywhere's code is where [FieldItemFns.pc] says. */
    val canPc: Boolean = false,
) {
    private val party = fns.party
    /** USE: a Repel on the spot, or medicine / a PP item on a picked Pokémon. */
    fun canUse(itemId: Int): Boolean = itemId in usable && (itemId in fns.repelItems || party != null)
    fun canGive(itemId: Int): Boolean = party != null && itemId in holdable
    /** USE opens the party pick (everything but the Repels). */
    fun picksMon(itemId: Int): Boolean = party != null && itemId in usable && itemId !in fns.repelItems
    /** ... and then one of the Pokémon's moves. */
    fun picksMove(itemId: Int): Boolean = party != null && itemId in party.ppOneMove
    /** The bag row's mark: something to do with it here. */
    fun marked(itemId: Int): Boolean = party != null && (canUse(itemId) || canGive(itemId))
    /** TAKE on the summary. */
    val canTake: Boolean get() = party != null && fns.bagActions
}

/** [fns]' support on the running ROM, or null when its code isn't where [fns] says (a hack
 * that moved it). The holdable items come from the ROM's gItems ([holdableItems]). */
fun fieldItemSupport(fns: FieldItemFns, read: (addr: Long, len: Int) -> ByteArray): FieldItemSupport? {
    if (!fieldItemCodeMatches(fns, read)) return null
    // No party functions: the Repels where the bag's functions are mapped, and PC anywhere (Monomyth's table is the PC alone).
    val party = fns.party ?: return FieldItemSupport(
        fns, emptySet(), if (fns.bagActions) fns.repelItems else emptySet(), canPc = fns.pc?.let { pcCodeMatches(it, read) } == true,
    )
    val fly = fns.fly?.let { flyCodeMatches(it, read) } == true
    val pc = fns.pc?.let { pcCodeMatches(it, read) } == true
    // FREE FLIGHT needs FLY's checks (the badge, the move, SetUpFieldMove_Fly) and its own code where the record says.
    val flight = fly && fns.flight?.let { flightCodeMatches(it, read) } == true
    if (!fns.bagActions) {
        // FLY and the PC; the Repels too where the table names its own (the expansion renumbered items) and their
        // functions are mapped (SoulGold, GitHub #242).
        val repels = if (fns.repelItems != REPEL_ITEMS && fns.checkBagHasItem != 0L && fns.removeBagItem != 0L && fns.items != 0L) fns.repelItems else emptySet()
        return FieldItemSupport(fns, emptySet(), repels, canFly = fly, canPc = pc, canFlight = flight)
    }
    val items = read(fns.items, party.itemCount * fns.itemSize)
    return FieldItemSupport(fns, holdableItems(fns, items), usableItems(fns, items), canFly = fly, canPc = pc, canFlight = flight)
}

/** What the bag's GIVE takes (item_menu.c's Task_ItemContext_FieldGive): importance 0 and not a key
 * item, minus the TM case (left out here) and Mail. [items] is gItems. */
fun holdableItems(fns: FieldItemFns, items: ByteArray): Set<Int> {
    val p = fns.party ?: return emptySet()
    return (1 until p.itemCount).filter { id ->
        val o = id * fns.itemSize
        o + fns.itemSize <= items.size && items[o + fns.itemImportanceOff].toInt() == 0 &&
            (items[o + fns.itemPocketOff].toInt() and 0xFF) !in p.unholdablePockets && id !in p.mail
    }.toSet()
}

/** USE's items (the party's and the Repels) whose gItems entry runs one of [PartyItemFns.useFuncs]; all of them
 * when the table lists none. */
fun usableItems(fns: FieldItemFns, items: ByteArray): Set<Int> {
    val p = fns.party ?: return fns.repelItems
    val all = p.usable + fns.repelItems
    if (p.useFuncs.isEmpty()) return all
    return all.filter { id ->
        val o = id * fns.itemSize + fns.itemFieldUseOff
        o + 4 <= items.size && (java.nio.ByteBuffer.wrap(items, o, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFEL) in p.useFuncs
    }.toSet()
}

/** Why a Pokémon can't be picked; [OK] = it can. */
/** BAG_FULL: TAKE's - what it holds has no room in the bag ([partyHeldItems]). */
enum class SlotVerdict { OK, NO_EFFECT, EGG, HOLDS_MAIL, HOLDS_IT, BAG_FULL }

/** One party slot as USE / GIVE sees it: the Pokémon ([personality], checked again before acting),
 * what it holds, and for a one-move item which moves the item would help. */
data class PartyTarget(
    val slot: Int,
    val personality: Long,
    val heldItem: Int,
    val verdict: SlotVerdict,
    val moves: List<Boolean> = emptyList(),
)

/** The party pick: [result] USED = pick from [targets]; otherwise why not (NOT_NOW, NONE_LEFT, FAILED). */
data class PartyTargets(val result: ItemUseResult, val targets: List<PartyTarget> = emptyList())

private const val MON_DATA_PERSONALITY = 0
private const val MON_DATA_SPECIES = 11
private const val MON_DATA_HELD_ITEM = 12
private const val MON_DATA_MOVE1 = 13
private const val MON_DATA_IS_EGG = 45

/** Uses [itemId] on the field - a Repel. [busy] is whether a battle or a menu screen is up. Emu thread. */
fun useFieldItem(fns: FieldItemFns, itemId: Int, busy: Boolean, g: GameCalls): ItemUseOutcome {
    if (itemId !in fns.repelItems || !fieldItemCodeMatches(fns, g::read)) return ItemUseOutcome(ItemUseResult.FAILED)
    if (busy || g.call(fns.fieldControlsLocked) != 0L) return ItemUseOutcome(ItemUseResult.NOT_NOW)
    if (fns.repelSteps(g.call(fns.varGet, fns.repelVar).toInt()) != 0) return ItemUseOutcome(ItemUseResult.STILL_ACTIVE)
    if (g.call(fns.checkBagHasItem, itemId, 1) != 1L) return ItemUseOutcome(ItemUseResult.NONE_LEFT)
    val steps = g.read(fns.items + itemId.toLong() * fns.itemSize + fns.itemParamOff, 1)[0].toInt() and 0xFF
    if (steps == 0) return ItemUseOutcome(ItemUseResult.FAILED)
    // Task_UseRepel's order: its other variables cleared, the steps, the Repel used, then one out of the bag.
    val sets = fns.repelClearVars.map { it to 0 } + (fns.repelVar to steps) +
        listOfNotNull(fns.lastRepelVar.takeIf { it != 0 }?.let { it to itemId })
    if (sets.any { (v, value) -> g.call(fns.varSet, v, value) < 0 } || g.call(fns.removeBagItem, itemId, 1) != 1L) {
        return ItemUseOutcome(ItemUseResult.FAILED)
    }
    return ItemUseOutcome(ItemUseResult.USED, steps)
}

/** Why [itemId] can't go to the party right now, or null if it can: the common checks of USE and GIVE. */
private fun partyGate(fns: FieldItemFns, itemId: Int, allowed: Boolean, busy: Boolean, g: GameCalls): ItemUseResult? = when {
    fns.party == null || !allowed || !fieldItemCodeMatches(fns, g::read) -> ItemUseResult.FAILED
    busy || g.call(fns.fieldControlsLocked) != 0L -> ItemUseResult.NOT_NOW
    g.call(fns.checkBagHasItem, itemId, 1) != 1L -> ItemUseResult.NONE_LEFT
    else -> null
}

private fun PartyItemFns.mon(slot: Int) = (party + slot * monStride).toInt()

/** GetMonData, failing loudly: a call that never returned would read as a 0 field otherwise. */
private fun PartyItemFns.get(g: GameCalls, slot: Int, field: Int): Long =
    g.call(getMonData, mon(slot), field).also { if (it < 0) throw IllegalStateException("GetMonData didn't return") }

/** Whether USE of [itemId] on [slot] would do something (move [move] for a PP item). */
private fun PartyItemFns.helps(g: GameCalls, itemId: Int, slot: Int, move: Int): Boolean {
    if (itemId == hpUp && get(g, slot, speciesField) == shedinja.toLong()) return false
    itemNoEffect?.let { return g.call(it, mon(slot), itemId, slot, move) == 0L }
    // The effect itself, undone: the Pokémon's struct is all it touches on the field (HP, status, PP, EVs and
    // stats, friendship). Whatever happens, its bytes go back.
    val saved = g.read(mon(slot).toLong() and 0xFFFFFFFFL, monStride)
    return try {
        g.call(itemEffect, mon(slot), itemId, slot, move) == 0L
    } finally {
        if (!g.write(mon(slot).toLong() and 0xFFFFFFFFL, saved)) throw IllegalStateException("couldn't put the Pokémon back")
    }
}

/**
 * Who [itemId] could go to: every party slot with its verdict - for USE ([give] false) the game's
 * no-effect check (per move for a one-move PP item, a move slot with no move left out), for GIVE eggs
 * and Mail holders out. [support] says what the ROM allows. Emu thread.
 */
fun partyTargets(support: FieldItemSupport, itemId: Int, give: Boolean, busy: Boolean, g: GameCalls): PartyTargets {
    val fns = support.fns
    partyGate(fns, itemId, if (give) support.canGive(itemId) else support.picksMon(itemId), busy, g)?.let { return PartyTargets(it) }
    val p = fns.party!!
    return runCatching {
        val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
        PartyTargets(ItemUseResult.USED, (0 until count).map { slot ->
            val personality = p.get(g, slot, MON_DATA_PERSONALITY)
            val held = p.get(g, slot, p.heldItemField).toInt()
            val egg = p.get(g, slot, p.isEggField) != 0L
            when {
                egg -> PartyTarget(slot, personality, held, SlotVerdict.EGG)
                give && held in p.mail -> PartyTarget(slot, personality, held, SlotVerdict.HOLDS_MAIL)
                give && held == itemId -> PartyTarget(slot, personality, held, SlotVerdict.HOLDS_IT)
                give -> PartyTarget(slot, personality, held, SlotVerdict.OK)
                itemId in p.ppOneMove -> {
                    val moves = (0 until 4).map { m -> p.get(g, slot, p.move1Field + m) != 0L && p.helps(g, itemId, slot, m) }
                    PartyTarget(slot, personality, held, if (moves.any { it }) SlotVerdict.OK else SlotVerdict.NO_EFFECT, moves)
                }
                else -> PartyTarget(slot, personality, held, if (p.helps(g, itemId, slot, 0)) SlotVerdict.OK else SlotVerdict.NO_EFFECT)
            }
        })
    }.getOrElse { PartyTargets(ItemUseResult.FAILED) }
}

/** Whether [slot] still holds the Pokémon the pick showed (and isn't an egg). */
private fun PartyItemFns.stillThere(g: GameCalls, slot: Int, personality: Long): Boolean =
    slot in 0 until (g.read(partyCount, 1)[0].toInt() and 0xFF) &&
        get(g, slot, MON_DATA_PERSONALITY) == personality && get(g, slot, isEggField) == 0L

/**
 * USE of medicine / a PP item on the Pokémon in [target] ([move] for a one-move PP item): the game's
 * no-effect check again, its effect, then one out of the bag - checked to be there first, so an
 * effect is never had for free. Emu thread.
 */
fun useOnMon(support: FieldItemSupport, itemId: Int, target: PartyTarget, move: Int, busy: Boolean, g: GameCalls): ItemUseOutcome {
    val fns = support.fns
    partyGate(fns, itemId, support.picksMon(itemId), busy, g)?.let { return ItemUseOutcome(it) }
    val p = fns.party!!
    return runCatching {
        val slot = target.slot
        val m = if (itemId in p.ppOneMove) move.coerceIn(0, 3) else 0
        when {
            !p.stillThere(g, slot, target.personality) -> ItemUseOutcome(ItemUseResult.PARTY_CHANGED)
            !p.helps(g, itemId, slot, m) -> ItemUseOutcome(ItemUseResult.NO_EFFECT)
            g.call(p.itemEffect, p.mon(slot), itemId, slot, m) != 0L -> ItemUseOutcome(ItemUseResult.NO_EFFECT)
            g.call(fns.removeBagItem, itemId, 1) != 1L -> ItemUseOutcome(ItemUseResult.FAILED)
            else -> ItemUseOutcome(ItemUseResult.USED)
        }
    }.getOrElse { ItemUseOutcome(ItemUseResult.FAILED) }
}

/**
 * GIVE [itemId] to the Pokémon in [target], as the party menu does (see [PartyItemFns]): never loses
 * an item - whatever fails after the bag changed puts the bag and the Pokémon back. Emu thread.
 */
fun giveToMon(support: FieldItemSupport, itemId: Int, target: PartyTarget, busy: Boolean, g: GameCalls): ItemUseOutcome {
    val fns = support.fns
    partyGate(fns, itemId, support.canGive(itemId), busy, g)?.let { return ItemUseOutcome(it) }
    val p = fns.party!!
    val slot = target.slot
    val old = runCatching {
        if (!p.stillThere(g, slot, target.personality)) return ItemUseOutcome(ItemUseResult.PARTY_CHANGED)
        p.get(g, slot, p.heldItemField).toInt()
    }.getOrElse { return ItemUseOutcome(ItemUseResult.FAILED) }
    if (old in p.mail) return ItemUseOutcome(ItemUseResult.FAILED)
    if (old == itemId) return ItemUseOutcome(ItemUseResult.NO_EFFECT)
    if (g.call(fns.removeBagItem, itemId, 1) != 1L) return ItemUseOutcome(ItemUseResult.NONE_LEFT)
    if (old != 0 && g.call(p.addBagItem, old, 1) != 1L) {
        g.call(p.addBagItem, itemId, 1)
        return ItemUseOutcome(ItemUseResult.BAG_FULL)
    }
    if (setHeldItem(p, g, slot, itemId)) return ItemUseOutcome(ItemUseResult.GIVEN, swapped = old)
    // Back as it was: the old item out of the bag and onto the Pokémon, the new one back in.
    if (old != 0 && g.call(fns.removeBagItem, old, 1) == 1L) setHeldItem(p, g, slot, old)
    g.call(p.addBagItem, itemId, 1)
    return ItemUseOutcome(ItemUseResult.FAILED)
}

/** SetMonData(mon, MON_DATA_HELD_ITEM, &item) through [PartyItemFns.scratch], then read back. */
private fun setHeldItem(p: PartyItemFns, g: GameCalls, slot: Int, item: Int): Boolean = runCatching {
    val before = g.read(p.scratch, 2)
    if (!g.write(p.scratch, byteArrayOf(item.toByte(), (item shr 8).toByte()))) return false
    val called = g.call(p.setMonData, p.mon(slot), p.heldItemField, p.scratch.toInt())
    g.write(p.scratch, before)
    called >= 0 && p.get(g, slot, p.heldItemField) == item.toLong()
}.getOrDefault(false)

/**
 * Whether the bag has room for one more [item]: the game's own AddBagItem, then EWRAM put back byte for byte -
 * the bag (SaveBlock1) and whatever else the call touches (Emerald's heap) live there, so nothing it did stays.
 * Null = couldn't tell.
 */
private fun PartyItemFns.bagHasRoom(g: GameCalls, item: Int): Boolean? {
    val before = g.read(EWRAM_START, EWRAM_SIZE).takeIf { it.size == EWRAM_SIZE } ?: return null
    val added = g.call(addBagItem, item, 1)
    val after = g.read(EWRAM_START, EWRAM_SIZE)
    fun restore(from: Int, to: Int) {
        if (!g.write(EWRAM_START + from, before.copyOfRange(from, to))) throw IllegalStateException("couldn't put the bag back")
    }
    if (after.size != EWRAM_SIZE) restore(0, EWRAM_SIZE)
    else {
        var i = 0
        while (i < EWRAM_SIZE) {
            if (after[i] == before[i]) { i++; continue }
            var j = i
            while (j < EWRAM_SIZE && after[j] != before[j]) j++
            restore(i, j)
            i = j
        }
    }
    return if (added < 0) null else added == 1L
}

private const val EWRAM_START = 0x02000000L
private const val EWRAM_SIZE = 0x40000

/**
 * What each party Pokémon holds, for TAKE: [PartyTarget.heldItem] with OK where it can be taken,
 * BAG_FULL where the bag has no room for it (the game's own check, undone: [bagHasRoom]), NO_EFFECT
 * where there's nothing, HOLDS_MAIL / EGG. Changes nothing, so not gated on the field. Emu thread.
 */
fun partyHeldItems(support: FieldItemSupport, g: GameCalls): PartyTargets {
    val p = support.fns.party ?: return PartyTargets(ItemUseResult.FAILED)
    if (!fieldItemCodeMatches(support.fns, g::read)) return PartyTargets(ItemUseResult.FAILED)
    return runCatching {
        val count = (g.read(p.partyCount, 1)[0].toInt() and 0xFF).coerceAtMost(6)
        PartyTargets(ItemUseResult.USED, (0 until count).map { slot ->
            val held = p.get(g, slot, p.heldItemField).toInt()
            val verdict = when {
                p.get(g, slot, p.isEggField) != 0L -> SlotVerdict.EGG
                held == 0 -> SlotVerdict.NO_EFFECT
                held in p.mail -> SlotVerdict.HOLDS_MAIL
                p.bagHasRoom(g, held) == false -> SlotVerdict.BAG_FULL
                else -> SlotVerdict.OK
            }
            PartyTarget(slot, p.get(g, slot, MON_DATA_PERSONALITY), held, verdict)
        })
    }.getOrElse { PartyTargets(ItemUseResult.FAILED) }
}

/** TAKE the item [target]'s Pokémon holds into the bag (see [PartyItemFns]); [ItemUseOutcome.swapped] is the
 * item. Never loses it: no room leaves it held, a SetMonData that didn't take puts the bag back. Emu thread. */
fun takeFromMon(support: FieldItemSupport, target: PartyTarget, busy: Boolean, g: GameCalls): ItemUseOutcome {
    val fns = support.fns
    val p = fns.party ?: return ItemUseOutcome(ItemUseResult.FAILED)
    if (!fieldItemCodeMatches(fns, g::read)) return ItemUseOutcome(ItemUseResult.FAILED)
    if (busy || g.call(fns.fieldControlsLocked) != 0L) return ItemUseOutcome(ItemUseResult.NOT_NOW)
    val held = runCatching {
        if (!p.stillThere(g, target.slot, target.personality)) return ItemUseOutcome(ItemUseResult.PARTY_CHANGED)
        p.get(g, target.slot, p.heldItemField).toInt()
    }.getOrElse { return ItemUseOutcome(ItemUseResult.FAILED) }
    if (held == 0) return ItemUseOutcome(ItemUseResult.NO_EFFECT)
    if (held in p.mail) return ItemUseOutcome(ItemUseResult.FAILED)
    if (g.call(p.addBagItem, held, 1) != 1L) return ItemUseOutcome(ItemUseResult.BAG_FULL, swapped = held)
    if (setHeldItem(p, g, target.slot, 0)) return ItemUseOutcome(ItemUseResult.TAKEN, swapped = held)
    g.call(fns.removeBagItem, held, 1)
    return ItemUseOutcome(ItemUseResult.FAILED)
}
