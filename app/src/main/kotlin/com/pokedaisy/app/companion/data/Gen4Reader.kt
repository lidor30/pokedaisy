package com.pokedaisy.app.companion.data

// Nintendo DS, Gen 4 (Platinum first): the companion's reads of a DS game's main RAM (nds/pk_nds.cpp's pkReadBytes:
// 0x02000000 + offset). Everything lives in the game's SaveData, a heap block whose pointer is a static word
// (pret/pokeplatinum's sSaveDataPtr, from its .xMAP - the decomp builds the retail ROM byte for byte). Its body is the
// save file's normal block as the game keeps it in RAM, each section at a fixed offset (SaveData's pageInfo table,
// read from a live game: the sections' sizes never change).

/** Where one Gen 4 game keeps what the companion reads. */
data class Gen4Config(
    /** sSaveDataPtr: the SaveData the game is playing. */
    val saveDataPtr: Long,
    /** SaveData.body within it (after its 5 status words). */
    val bodyOff: Int = 0x14,
    /** SAVE_TABLE_ENTRY_PLAYER's TrainerInfo (the entry's own first word is its options): name, id, money, gender. */
    val trainerOff: Int,
    /** SAVE_TABLE_ENTRY_PARTY: Party { capacity, count, Pokemon[6] }. */
    val partyOff: Int,
    /** SAVE_TABLE_ENTRY_BAG: eight pockets of { u16 item, u16 quantity }. */
    val bagOff: Int,
    /** SAVE_TABLE_ENTRY_FIELD_PLAYER_STATE: FieldOverworldState, whose first Location is where the player is. */
    val locationOff: Int,
    /** SpecialEncounter.repelSteps within the body (SAVE_TABLE_ENTRY_ENCOUNTERS + 0x14F): the steps a Repel has left,
     * a byte the field counts down a step at a time (Repel_UpdateSteps). Found as the byte the game's own Repel set to
     * 100, then checked counting down and the game's "wore off" message coming at 0. Null = not mapped (no REPEL steps). */
    val repelStepsOff: Int?,
    /** The pockets' sizes, in the Bag struct's order: items, key items, TMs / HMs, mail, medicine, berries, balls, battle items. */
    val pocketSizes: IntArray = intArrayOf(165, 50, 100, 12, 40, 64, 15, 30),
    /** Each of the save's pockets ([pocketSizes]' order) as the companion's: set, an item's pocket is where it sits
     * (all eight apart); null, it's [itemPockets]' (the five every game has). */
    val savePockets: IntArray? = null,
    /** The save's Pokédex in the body ([readGen4PokedexState]); null = no DEX. */
    val pokedexOff: Int? = null,
    /** Its National Dex byte (Pokedex.nationalDexObtained) and the game's dex tables. */
    val nationalDexOff: Int = 0,
    /** Bytes in each of its flag arrays (caught, seen, the genders'): a bit per species, 0x40 for 493 (Origin HeartGold's
     * 0xC8, for 1600). */
    val dexFlagBytes: Int = 0x40,
    val pokedexTables: PokedexTables? = null,
    /** Each map header's place name (its map label) - the English one: [placeName] reads the cart's own. */
    val mapPlaces: Map<Int, String>,
    /** Each map header's place as a line of the cart's location names ([NdsArt.Names.places]). */
    val mapPlaceIds: Map<Int, Int> = emptyMap(),
    /** The companion's pocket for an item (the game's own; the rest under ITEMS). */
    val itemPockets: Map<Int, Int>,
    /** FLY's spots by spawn id - 1 (the town map's destinations; nds/pk_nds_call.cpp checks and flies to them). */
    val flySpots: List<FlySpot> = emptyList(),
    /** Where a battle keeps its battlers and parties; null = no battle reads. */
    val battle: Gen4Battle? = null,
    /** A hack's own battle engine, read instead of [battle] ([Gen4ManagedBattle]). */
    val managedBattle: Gen4ManagedBattle? = null,
    /** The Town Map ([Gen4TownMap]); null = none. */
    val townMap: Gen4TownMap? = null,
    /** HeartGold / SoulSilver: the save's event flags in the body (VarsFlags' flags, after its vars), for the flypoint
     * flags; and where the player is on the Pokégear map ([gen4PokegearBlock]). -1 / null = none. */
    val flagsOff: Int = -1,
    val pokegear: Gen4Pokegear? = null,
    /** HeartGold / SoulSilver: SavePokegear's second word in the body (skin in bits 18-24, mapUnlockLevel 27-28), or -1. */
    val pokegearWordOff: Int = -1,
    /** VarsFlags in the body: [numVars] vars from VAR 0x4000, then 2912 flag bits - the GUIDE's [SaveProgress]
     * ([readGen4Progress]); -1 = none. */
    val varsFlagsOff: Int = -1,
    val numVars: Int = 0,
    /** HeartGold / SoulSilver: the Kanto badges' byte in TrainerInfo ([trainerOff] + this; the Johto / Sinnoh badges'
     * is +0x1A in both games), or -1. */
    val kantoBadgesOff: Int = -1,
    /** The last species id a party record may hold (a bigger one is a bad read). */
    val maxSpecies: Int = 493,
    /** A record's species and form (block B's byte 0x18, bits 3-7) as the species id the tables use: a hack whose forms
     * have ids of their own (Origin HeartGold); null = the species as stored. */
    val formSpecies: ((species: Int, form: Int) -> Int)? = null,
)

/** The GUIDE's clock: the hour (0-23) the DS's clock says, as a var past the save's own (the DS games keep no time of
 * day in their save; melonDS's clock is the device's, pk_nds.cpp). */
const val GEN4_VAR_HOUR = 0x10000

/** The badges as flags past the save's own: [GEN4_BADGE_FLAG] + badge id (Platinum's BADGE_ID_*: COAL 0 .. BEACON 7;
 * HG / SS's BADGE_*: ZEPHYR 0 .. RISING 7, then BOULDER 8 .. EARTH 15), from TrainerInfo's badge bytes. Gym leaders'
 * own trainer flags are no good for that: HG / SS clears them again for rematches. */
const val GEN4_BADGE_FLAG = 0x20000

private const val GEN4_FLAG_BYTES = 2912 / 8

/** The save's event flags and vars, as the GUIDE reads them ([Gen4Config.varsFlagsOff]), with the hour as
 * [GEN4_VAR_HOUR]; null without them. */
fun readGen4Progress(r: MemoryReader, cfg: Gen4Config, body: Long, hour: Int = java.time.LocalTime.now().hour): SaveProgress? {
    if (cfg.varsFlagsOff < 0) return null
    val vars = r.readCoreMemory(body + cfg.varsFlagsOff, cfg.numVars * 2)
    val flags = r.readCoreMemory(body + cfg.varsFlagsOff + cfg.numVars * 2, GEN4_FLAG_BYTES)
    if (vars.size < cfg.numVars * 2 || flags.size < GEN4_FLAG_BYTES) return null
    val trainer = r.readCoreMemory(body + cfg.trainerOff, 0x20)
    val badges = byteArrayOf(trainer.getOrElse(0x1A) { 0 }, if (cfg.kantoBadgesOff >= 0) trainer.getOrElse(cfg.kantoBadgesOff) { 0 } else 0)
    return SaveProgress(
        flags, vars,
        flagBanks = listOf(SaveBank(GEN4_BADGE_FLAG, 0, 16) to badges),
        varBanks = listOf(SaveBank(GEN4_VAR_HOUR, 0, 1) to byteArrayOf(hour.toByte(), 0)),
    )
}

/**
 * Where the player is on HeartGold / SoulSilver's Pokégear map (unk_02092BE8.c): on the main matrix their own block
 * (the player object's x / z, in tiles: 32 a block); elsewhere the map header's world-map block, else the special
 * spawn's (LocalFieldData's fifth Location).
 */
data class Gen4Pokegear(
    val fieldSystemPtr: Long,
    val offPlayerAvatar: Int = 0x40,
    val offAvatarMapObject: Int = 0x30,
    val offObjectX: Int = 0x64,
    val offObjectZ: Int = 0x6C,
    val offSpecialSpawn: Int = 0x50,
    val mainMatrixHeaders: Set<Int>,
    val worldMap: Map<Int, Pair<Int, Int>>,
)

/**
 * The Town Map's reads (pret/pokeplatinum's town_map/context.c): the player's block on the main map matrix - their live
 * position (sFieldSystem -> playerAvatar -> its MapObject's x / z, in tiles: 32 a block) while the map they're on is
 * on it, else the save's exit location (the door they went in by) - and the first-arrival flags that colour its towns.
 */
data class Gen4TownMap(
    val fieldSystemPtr: Long,
    /** FieldSystem.location (the live Location) / .playerAvatar; PlayerAvatar.mapObject; MapObject.x / .z. */
    val offLocation: Int = 0x1C,
    val offPlayerAvatar: Int = 0x3C,
    val offAvatarMapObject: Int = 0x30,
    val offObjectX: Int = 0x64,
    val offObjectZ: Int = 0x6C,
    /** FieldOverworldState.exit within the save's FIELD_PLAYER_STATE (its fifth Location). */
    val offExitLocation: Int = 0x50,
    /** VarsFlags in the body (vars from VAR 0x4000, 288 of them, then the flag bits) and the first first-arrival flag. */
    val varsOff: Int = 0xDAC,
    val numVars: Int = 288,
    val firstArrivalFlag: Int = 2480,
    val mainMatrixHeaders: Set<Int>,
    /** Each map header's place (the Town Map's sections). */
    val headerPlaces: Map<Int, Int>,
    /** Each block's place id: 30 x 30 u16, FFFF = none. */
    val grid: ByteArray,
)

/** One of the Town Map's fly locations ([townMapMarksPlatinum]): the block sprite's centre on the top screen ([x], [y]),
 * its cell ([shape]) and visited colour ([palette]: 0 blue, 1 red), and the first-arrival flag that marks it visited. */
data class TownMapMark(val x: Int, val y: Int, val shape: Int, val palette: Int, val firstArrival: Int, val hiddenUntilVisited: Boolean)

/**
 * A battle, reached from the field: sFieldSystem -> processManager -> child, the battle's ApplicationManager (its
 * template's init is gBattleApplicationTemplate's), whose data is the BattleSystem (battle_main.c allocates it there).
 * Offsets from pret/pokeplatinum's structs, checked on a headless battle (the rival's on Route 201: TURTWIG's BattleMon
 * at BattleContext + 0x2D40, field by field, CHIMCHAR's one stride on).
 */
data class Gen4Battle(
    /** sFieldSystem. */
    val fieldSystemPtr: Long,
    /** gBattleApplicationTemplate (its first word, init, is what the battle's ApplicationManager starts with). */
    val battleTemplate: Long,
    /** FieldProcessManager.child, ApplicationManager.data. */
    val offChild: Int = 4,
    val offAppData: Int = 0x1C,
    /** BattleSystem.battleType / .battleCtx / .parties[4]. */
    val offBattleType: Int = 0x2C,
    val offBattleCtx: Int = 0x30,
    val offParties: Int = 0x68,
    /** BattleContext.battleMons[4] and sizeof(BattleMon). */
    val offBattleMons: Int = 0x2D40,
    val battleMonSize: Int = 0xC0,
    /** BattleSystem.battleInput (the touch screen's panels), 0 = its states aren't read ([readGen4BattleInput]). */
    val offBattleInput: Int = 0,
    /** BattleInput's text objects' first pointer: set while a panel is drawn, NULL while the turn plays (headless dumps). */
    val offPanelShown: Int = 0x4D0,
    /** BattleContext.command (CONTROLLER_COMMAND_*: 5 = the player chooses) and battler 0's selection step (u8 at 0). */
    val offCommand: Int = 0x08,
)

/**
 * A battle engine of a hack's own ([GEN4_ORIGIN_HEARTGOLD]'s: no BattleSystem / BattleContext). gOverlayTemplate_Battle runs
 * its overlay 14's init, which makes a manager ([managerPtr]: 0x274 bytes) holding a battle object per Pokémon (0x414 bytes,
 * its battle copy: the party record it came from, HP, item, level, stats, types, stages, moves with PP, ability) in two
 * groups - the player's six from [offPlayerObjs], the foe's from [offFoeObjs] ([offFoeCount] of them) - each in battle
 * order: the Pokémon out first (a switch reorders them: headless, SHIFT to SYLVEON put its object first). [offActive]
 * points at the active slots ({ first, second or 0xFF }: a double has a second). The BattleSetup it got ([offSetup]) starts
 * with the battle type; the parties ([offParties]: the player's, the foe's) are the save's Party struct. Found headless on
 * Origin HeartGold (wild battles against PIDGEY and AZUMARILL, a trainer's BIDOOF): Leer took the foe's Defense stage
 * 6 -> 5, Agility the user's Speed 6 -> 8, a Tackle its HP and PP.
 */
data class Gen4ManagedBattle(
    val fieldSystemPtr: Long,
    val battleTemplate: Long,
    val managerPtr: Long,
    val offSetup: Int = 0x10,
    val offActive: Int = 0x58,
    val offPlayerObjs: Int = 0x60,
    val offFoeObjs: Int = 0x7C,
    val offFoeCount: Int = 0x94,
    val offParties: Int = 0xD0,
    /** A battle object: species, the party record, max HP, HP, item (u16), level (u8). */
    val objSpecies: Int = 0x00,
    val objRecord: Int = 0x10,
    val objMaxHp: Int = 0x1C,
    val objHp: Int = 0x1E,
    val objItem: Int = 0x20,
    val objLevel: Int = 0x24,
    /** Attack, Defense, Sp. Atk, Sp. Def, Speed (u16s), the two types, the seven stages in that order then accuracy and evasion. */
    val objStats: Int = 0x22C,
    val objTypes: Int = 0x236,
    val objStages: Int = 0x240,
    /** Four moves of [objMoveStride] bytes: the move at +2, its PP at +4. */
    val objMoves: Int = 0x250,
    val objMoveStride: Int = 0x12,
    val objAbility: Int = 0x29A,
    val objSize: Int = 0x2A0,
)

/** One of FLY's spots: the town's map header and the tile FLY lands on (its x / z, as GetSpawnIdByMapAndCoords finds it).
 * HeartGold / SoulSilver's also carry their flypoint flag (set = unlocked: the app reads it, Platinum asks the game), the
 * map they're named by and their region (0 Johto, 1 Kanto, 2 either: the Pokégear flies only within the player's). */
data class FlySpot(val mapHeader: Int, val x: Int, val z: Int, val flag: Int = -1, val nameMap: Int = mapHeader, val region: Int = 2)

/** MOVE_FLY: a party Pokémon that knows it can carry the player (Gen 4 keeps Gen 3's move ids). */
const val GEN4_MOVE_FLY = 19

/** FieldMoves_CheckFly's answer (FIELD_MOVE_ERROR_*) as FLY's. */
fun gen4FlyResult(error: Int): FlyResult = when (error) {
    0 -> FlyResult.OK
    1 -> FlyResult.CANT_FLY_HERE   // LOCATION: indoors, a cave, a link room, the Safari Zone / Pal Park
    2 -> FlyResult.NO_BADGE
    3 -> FlyResult.PARTNER
    else -> FlyResult.NOT_NOW       // STATE
}

/** The fly spot [spawnId]'s place name, or null. */
fun Gen4Config.flySpotName(spawnId: Int): String? = flySpots.getOrNull(spawnId - 1)?.let { placeName(it.nameMap) }

/** Map header [header]'s place name in the cart's own words (a translated cart's: GitHub #230), else the US game's. */
fun Gen4Config.placeName(header: Int): String? =
    mapPlaceIds[header]?.let { NdsArt.names?.place(it) } ?: mapPlaces[header]

/** Pokegear_RegionFromCoords: 0 Johto, 1 Kanto, 2 Indigo Plateau / Victory Road (either). */
fun pokegearRegion(x: Int, y: Int): Int = when {
    x <= 21 -> 0
    x == 25 && y == 8 -> 0   // Mt. Silver
    x == 28 && y == 6 || x == 28 && y in 9..12 -> 2
    else -> 1
}

/** The player's block on the Pokégear map ([Gen4Pokegear]), or null before the field is up. */
fun gen4PokegearBlock(r: (Long, Int) -> ByteArray, cfg: Gen4Config): Pair<Int, Int>? {
    val g = cfg.pokegear ?: return null
    val ptr = u32(r(cfg.saveDataPtr, 4), 0)
    if (ptr !in MAIN_RAM until MAIN_RAM_END - 0x20000) return null
    val body = ptr + cfg.bodyOff
    val header = u32(r(body + cfg.locationOff, 4), 0).toInt()
    if (header in g.mainMatrixHeaders) {
        val fs = u32(r(g.fieldSystemPtr, 4), 0)
        if (!inMain(fs)) return null
        val avatar = u32(r(fs + g.offPlayerAvatar, 4), 0)
        val obj = if (inMain(avatar)) u32(r(avatar + g.offAvatarMapObject, 4), 0) else 0L
        if (!inMain(obj)) return null
        val xz = r(obj + g.offObjectX, g.offObjectZ + 4 - g.offObjectX)
        return u32(xz, 0).toInt() / 32 to u32(xz, g.offObjectZ - g.offObjectX).toInt() / 32
    }
    g.worldMap[header]?.let { return it }
    val spawn = r(body + cfg.locationOff + g.offSpecialSpawn, 0x14)
    return u32(spawn, 8).toInt() / 32 to u32(spawn, 12).toInt() / 32
}

/** HeartGold / SoulSilver's FLY destinations now (spawn ids, 1-based): flypoints whose flag is set, in the player's
 * region (or either), as the Pokégear's fly map offers them. Empty without [Gen4Config.flagsOff]. */
fun gen4OpenFlySpots(r: (Long, Int) -> ByteArray, cfg: Gen4Config): List<Int> {
    if (cfg.flagsOff < 0) return emptyList()
    val ptr = u32(r(cfg.saveDataPtr, 4), 0)
    if (ptr !in MAIN_RAM until MAIN_RAM_END - 0x20000) return emptyList()
    val flags = r(ptr + cfg.bodyOff + cfg.flagsOff, 0x200)
    val here = gen4PokegearBlock(r, cfg)?.let { (x, y) -> pokegearRegion(x, y) }
    return cfg.flySpots.withIndex().filter { (_, s) ->
        s.flag >= 0 && (u8(flags, s.flag / 8) shr (s.flag % 8)) and 1 != 0 && (s.region == 2 || here == null || here == 2 || s.region == here)
    }.map { it.index + 1 }
}

/** Pokémon Platinum (USA, rev 1; the decomp's default build, sha1 0862ec35…). */
val GEN4_PLATINUM_US = Gen4Config(
    saveDataPtr = 0x021C0794,
    trainerOff = 0x68,
    partyOff = 0x98,
    bagOff = 0x630,
    locationOff = 0x1280,
    repelStepsOff = 0x8073,
    // SAVE_TABLE_ENTRY_POKEDEX (found by its magic in the `platinum` fixture's SaveData at +0x133C); pokeplatinum's
    // struct Pokedex keeps nationalDexObtained at 0x31B (its recordedLanguages are MAX_SPECIES + 1 = 496 bytes)
    pokedexOff = 0x1328,
    nationalDexOff = 0x31B,
    pokedexTables = POKEDEX_PLATINUM,
    // SAVE_TABLE_ENTRY_VARS_FLAGS: VAR_PLAYER_STARTER (0x4030) holds the starter in the `platinum` fixture, and the
    // flags after them its new game's (FLAG_TALKED_TO_MOM, FLAG_BAG_ACQUIRED, ...); the Town Map reads the same block.
    varsFlagsOff = 0xDAC,
    numVars = 288,
    mapPlaces = mapPlacesPlatinum,
    mapPlaceIds = mapPlaceIdsPlatinum,
    itemPockets = itemPocketsPlatinum,
    flySpots = flySpotsPlatinum,
    // battleInput: BattleSystem.battleSubscreen, HeartGold's place (its first font OAM at 0x4D0 too: headless, set at the
    // commands and the moves, NULL while the turn plays; battler 0's steps 1 / 4 / 8 / 10 at the commands, moves, bag, party).
    battle = Gen4Battle(fieldSystemPtr = 0x021C07DC, battleTemplate = 0x020EA358, offBattleInput = 0x19C),
    townMap = Gen4TownMap(
        fieldSystemPtr = 0x021C07DC,
        mainMatrixHeaders = mainMatrixHeadersPlatinum,
        headerPlaces = mapPlaceIdsPlatinum,
        grid = hexBytes(TOWN_MAP_GRID_PLATINUM),
    ),
)

/** French Platinum (CPUF rev 0, SHA-1 62f9ec7a…): the US cart's code and save layout, its globals 0x1E0 higher and the
 * battle template 0x88 (scripts/port_nds.py; nds/pk_nds_field.cpp's PLATINUM_FR has the same). */
val GEN4_PLATINUM_FR = GEN4_PLATINUM_US.copy(
    saveDataPtr = 0x021C0974,
    battle = GEN4_PLATINUM_US.battle!!.copy(fieldSystemPtr = 0x021C09BC, battleTemplate = 0x020EA3E0),
    townMap = GEN4_PLATINUM_US.townMap!!.copy(fieldSystemPtr = 0x021C09BC),
)

/**
 * Renegade Platinum ([RenegadePlatinum]): retail Platinum edited in place, its RAM and save unchanged (party, bag, money,
 * place, Pokédex and a wild battle read headless on a new game, also after an in-game save and a reboot). Only its DEX
 * differs: its pages, pictures and machines from its own cart ([POKEDEX_RENEGADE]).
 */
val GEN4_RENEGADE_PLATINUM = GEN4_PLATINUM_US.copy(pokedexTables = POKEDEX_RENEGADE)

/**
 * Pokémon HeartGold / SoulSilver (USA; pret/pokeheartgold builds both byte for byte, sha1 4fcded0e… / f8dc38ea…). The save
 * sections' offsets are the save file's (the body in RAM is that block): party, trainer, bag and location found in the
 * player's own saves, then checked live. Their own items, pockets (101 TM / HM slots, 24 balls), map places and FLY
 * spots (the Pokégear's flypoints); the Pokégear map and battles come later.
 */
internal fun gen4HeartGoldFamily(
    saveDataPtr: Long, fieldSystemPtr: Long = 0x021D4158, battleTemplate: Long = 0x020FA484,
) = Gen4Config(
    saveDataPtr = saveDataPtr,
    bodyOff = 0x10,
    trainerOff = 0x64,
    partyOff = 0x90,
    bagOff = 0x644,
    locationOff = 0x1234,
    // SAVE_ROAMER's chunk (0x68A4: the SaveData's arrayHeaders, read live) + RoamerSave_GetRepelAddr's 0x65
    repelStepsOff = 0x6909,
    pocketSizes = intArrayOf(165, 50, 101, 12, 40, 64, 24, 30),
    // struct Bag: items, key items, TMs / HMs, mail, medicine, berries, balls, battle items
    savePockets = intArrayOf(
        POCKET_ITEMS, POCKET_KEY_ITEMS, POCKET_TM_HM, POCKET_MAIL, POCKET_MEDICINE, POCKET_BERRIES, POCKET_POKE_BALLS, POCKET_BATTLE_ITEMS,
    ),
    mapPlaces = mapPlacesHeartGold,
    mapPlaceIds = mapPlaceIdsHeartGold,
    // SAVE_POKEDEX's chunk (found by its magic in the player's save, checked live)
    pokedexOff = 0x12B8,
    nationalDexOff = 0x337,
    pokedexTables = POKEDEX_HEARTGOLD,
    itemPockets = itemPocketsHeartGold,
    flySpots = flySpotsHeartGold,
    // SAVE_FLAGS' chunk (0xDE4) + its 0x170 vars
    flagsOff = 0xDE4 + 0x170 * 2,
    varsFlagsOff = 0xDE4,
    numVars = 0x170,
    // PlayerProfile: johtoBadges at 0x1A, kantoBadges at 0x1F (include/player_data.h)
    kantoBadgesOff = 0x1F,
    // SAVE_POKEGEAR's chunk (0xBAE0) + 4
    pokegearWordOff = 0xBAE4,
    // gOverlayTemplate_Battle; BattleSystem / BattleContext laid out as Platinum's (battleMons at 0x2D40, 0xC0 each:
    // headless dumps of a wild battle), its touch panels behind BattleSystem.battleInput (0x19C).
    battle = Gen4Battle(fieldSystemPtr = fieldSystemPtr, battleTemplate = battleTemplate, offBattleInput = 0x19C),
    pokegear = Gen4Pokegear(
        fieldSystemPtr = fieldSystemPtr,   // sFieldSysPtr
        mainMatrixHeaders = mainMatrixHeadersHeartGold,
        worldMap = worldMapHeartGold,
    ),
)

/** sSaveDataPtr (save.c, from pokeheartgold's .xMAP; read the same in both games' RAM). */
private const val HEARTGOLD_SAVE_DATA_PTR = 0x021D2228L

val GEN4_HEARTGOLD_US = gen4HeartGoldFamily(saveDataPtr = HEARTGOLD_SAVE_DATA_PTR)
val GEN4_SOULSILVER_US = gen4HeartGoldFamily(saveDataPtr = HEARTGOLD_SAVE_DATA_PTR)

/** German HeartGold rev 0 (IPKD, SHA-1 13b6853a…): SaveData_Get and Field_NewGame_AppInit's literal pools.
 * The save layout is shared with the US carts; its two globals moved by 0x20, gOverlayTemplate_Battle by 0x4C
 * (nds/pk_nds_field.cpp's HEARTGOLD_DE has the same three). */
val GEN4_HEARTGOLD_DE = gen4HeartGoldFamily(saveDataPtr = 0x021D2208, fieldSystemPtr = 0x021D4138, battleTemplate = 0x020FA438)

/**
 * Origin HeartGold ([OriginHeartGold]): HeartGold's save, grown. Its globals were found through the US code that uses them
 * (SaveData_Get keeps the save's pointer 4 bytes into a static block here: `ldr r0, [r0, #4]`) and checked headless on a new
 * game; the save sections' offsets are its SaveData's own table (arrayHeaders, read live): the bag holds 151 TMs / HMs (its
 * 130 TMs), so everything after it moved 0xC8 - the flags (VarsFlags: 0x170 vars, then a longer flag array) 0xEAC, the
 * Location 0x1324, the Pokédex 0x13A8 (a bit per species to 1600: 0xC8 bytes an array), the roamers 0x6A48 (REPEL's steps
 * +0x65), the Pokégear 0xBC84. Species to 1440 (forms past 1025, [OriginHeartGold.Data.formSpecies]); its DEX is the
 * National Dex's 1025 from its cart ([POKEDEX_ORIGIN]). No GUIDE; its battles are its own engine's ([Gen4ManagedBattle]).
 */
val GEN4_ORIGIN_HEARTGOLD = gen4HeartGoldFamily(saveDataPtr = 0x021D11B4, fieldSystemPtr = 0x021D31F4).copy(
    pocketSizes = intArrayOf(165, 50, 151, 12, 40, 64, 24, 30),
    locationOff = 0x1324,
    repelStepsOff = 0x6A48 + 0x65,
    // Its Pokedex: HeartGold's, the four flag arrays 0xC8 bytes each and no caughtLanguages; dexEnabled 0x366 (GivePokedex
    // set it headless), nationalDex 0x367.
    pokedexOff = 0x13A8,
    nationalDexOff = 0x367,
    dexFlagBytes = 0xC8,
    pokedexTables = POKEDEX_ORIGIN,
    flagsOff = 0xEAC + 0x170 * 2,
    varsFlagsOff = 0xEAC,
    pokegearWordOff = 0xBC84 + 4,
    battle = null,
    managedBattle = Gen4ManagedBattle(fieldSystemPtr = 0x021D31F4, battleTemplate = 0x020F70B8, managerPtr = 0x02255BAC),
    maxSpecies = OriginHeartGold.LAST_SPECIES,
    formSpecies = { species, form -> OriginHeartGold.data?.formSpecies(species, form) ?: species },
)

/** German SoulSilver rev 0 shares HeartGold's globals and save layout (IPGD, SHA-1 576cdb7e…). */
val GEN4_SOULSILVER_DE = gen4HeartGoldFamily(saveDataPtr = 0x021D2208, fieldSystemPtr = 0x021D4138, battleTemplate = 0x020FA438)

/** French HeartGold / SoulSilver rev 0 (IPKF / IPGF, SHA-1 dba64620… / 6ed98d44…): the US carts' save layout, the two
 * globals 0x20 higher, the battle template 0x1C lower (scripts/port_nds.py; pk_nds_field.cpp's HEARTGOLD_FR). */
val GEN4_HEARTGOLD_FR = gen4HeartGoldFamily(saveDataPtr = 0x021D2248, fieldSystemPtr = 0x021D4178, battleTemplate = 0x020FA468)
val GEN4_SOULSILVER_FR = gen4HeartGoldFamily(saveDataPtr = 0x021D2248, fieldSystemPtr = 0x021D4178, battleTemplate = 0x020FA468)

/** A DS game ([readGen4Telemetry], [readGen5Telemetry]): two screens, its own look and tables. */
val GameKind?.isDsGame: Boolean get() = this == GameKind.PLATINUM || isHgss || isGen5

/** Gen 5 (Black, White, Black 2, White 2): [readGen5Telemetry], their own tables (Black's for the species and moves of
 * all four; the sequels' places, items and Town Map their own: [isBw2]). */
val GameKind?.isGen5: Boolean get() = this == GameKind.BLACK || this == GameKind.WHITE || isBw2

/** Black 2 / White 2: Black's engine rebuilt - Black's species and moves, their own zones, places, Town Map and art. */
val GameKind?.isBw2: Boolean get() = this == GameKind.BLACK2 || this == GameKind.WHITE2

/** The Gen 4 config for [kind], or null. */
fun gen4ConfigFor(kind: GameKind?, code: String = romGameCode): Gen4Config? = when (kind) {
    GameKind.PLATINUM -> when {
        renegadePlatinum -> GEN4_RENEGADE_PLATINUM
        code == "CPUF" -> GEN4_PLATINUM_FR
        else -> DsPorts.byCode(code)?.let(DsPorts::gen4Config) ?: GEN4_PLATINUM_US
    }
    GameKind.HEARTGOLD -> when {
        originHeartGold -> GEN4_ORIGIN_HEARTGOLD
        code == "IPKD" -> GEN4_HEARTGOLD_DE
        code == "IPKF" -> GEN4_HEARTGOLD_FR
        else -> DsPorts.byCode(code)?.let(DsPorts::gen4Config) ?: GEN4_HEARTGOLD_US
    }
    GameKind.SOULSILVER -> when (code) {
        "IPGD" -> GEN4_SOULSILVER_DE
        "IPGF" -> GEN4_SOULSILVER_FR
        else -> DsPorts.byCode(code)?.let(DsPorts::gen4Config) ?: GEN4_SOULSILVER_US
    }
    else -> null
}

/** HeartGold or SoulSilver: one engine, one set of tables. */
val GameKind?.isHgss: Boolean get() = this == GameKind.HEARTGOLD || this == GameKind.SOULSILVER

private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
private fun u16(b: ByteArray, o: Int) = u16le(b, o)
private fun u32(b: ByteArray, o: Int) = u32le(b, o)
private val EMPTY_BATTLE_MON = BattleMon(0, 0, 0, 0, 0, 0, 0, IntArray(4), IntArray(4))

private const val GEN4_MON_SIZE = 0xEC
private const val GEN4_BOX_SIZE = 0x88
private const val MAIN_RAM = 0x02000000L
private const val MAIN_RAM_END = 0x02400000L

/** The shuffled order of the four 32-byte blocks (A growth, B moves, C contest, D misc) for (personality >> 13) % 24. */
private val GEN4_BLOCK_ORDERS = arrayOf(
    "ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
    "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA",
)

/** XORs [len] bytes from [off] with the games' LCG stream from [seed] (one u16 per step), into a new array. */
private fun gen4Crypt(raw: ByteArray, off: Int, len: Int, seed: Long): ByteArray {
    val out = ByteArray(len)
    var s = seed and 0xFFFFFFFFL
    var i = 0
    while (i < len) {
        s = (s * 0x41C64E6DL + 0x6073L) and 0xFFFFFFFFL
        val w = (u16(raw, off + i) xor (s ushr 16).toInt()) and 0xFFFF
        out[i] = w.toByte()
        out[i + 1] = (w shr 8).toByte()
        i += 2
    }
    return out
}

/** A party Pokémon's 236 bytes (encrypted, as in RAM and the save), or null for an empty / unreadable slot. The game
 * decrypts a Pokémon in place while it reads or changes it (Pokemon_EnterDecryptionContext), saying so in the flags
 * after the personality - bit 0 the party fields, bit 1 the four blocks - so those are taken as they are. (Reading them
 * as encrypted dropped the lead every few seconds: the game looks at it that often on the field.) HeartGold / SoulSilver
 * also hold one party Pokémon's blocks in plaintext between frames with the flags clear (slot 1 on both players' saves,
 * on the field), so whichever way the checksum adds up is taken, and likewise the party fields that make sense.
 * Gen 5 keeps the same struct with shorter party fields (Black: [maxSpecies] 649, [size] 220: read as 236, a full
 * party's sixth slot ran past the read and threw, and the companion showed nothing). */
fun decodeGen4PartyMon(
    raw: ByteArray, off: Int, maxSpecies: Int = 493, size: Int = GEN4_MON_SIZE, formSpecies: ((Int, Int) -> Int)? = null,
): Mon? {
    if (off < 0 || off + size > raw.size) return null
    val personality = u32(raw, off)
    val decrypted = u16(raw, off + 4)
    val checksum = u16(raw, off + 6)
    fun sums(b: ByteArray): Boolean {
        var sum = 0
        for (i in 0 until 0x80 step 2) sum += u16(b, i)
        return sum and 0xFFFF == checksum
    }
    val plain = raw.copyOfRange(off + 8, off + 8 + 0x80)
    val blocks = if (decrypted and 2 != 0) plain.takeIf(::sums)
        else gen4Crypt(raw, off + 8, 0x80, checksum.toLong()).takeIf(::sums) ?: plain.takeIf(::sums)
    blocks ?: return null
    val order = GEN4_BLOCK_ORDERS[(((personality ushr 13) and 0x1F) % 24).toInt()]
    fun block(c: Char) = order.indexOf(c) * 32
    val a = block('A')
    val b = block('B')
    val species = u16(blocks, a)
    if (species == 0 || species > maxSpecies) return null
    val ivWord = u32(blocks, b + 0x10)
    val flags = u8(blocks, b + 0x18)
    // The nickname: block C's first 11 characters - the game's charmap (Gen 4), UTF-16 (Gen 5's shorter record).
    val c = block('C')
    val nickname = if (size == GEN4_MON_SIZE) Gen4Text.name(blocks, c, NICKNAME_CHARS)
        else buildString {
            for (i in 0 until NICKNAME_CHARS) {
                val ch = u16(blocks, c + i * 2)
                if (ch == 0xFFFF || ch == 0) break
                append(ch.toChar())
            }
        }
    // The party fields carry no checksum: a level or HP no Pokémon has is a read gone wrong.
    fun sane(p: ByteArray) = u8(p, 4) in 1..100 && u16(p, 8) in 1..999 && u16(p, 6) <= u16(p, 8)
    val plainParty = raw.copyOfRange(off + GEN4_BOX_SIZE, off + size)
    val party = (if (decrypted and 1 != 0) plainParty.takeIf(::sane)
        else gen4Crypt(raw, off + GEN4_BOX_SIZE, size - GEN4_BOX_SIZE, personality).takeIf(::sane)
            ?: plainParty.takeIf(::sane)) ?: return null
    return Mon(
        species = formSpecies?.invoke(species, flags ushr 3) ?: species,
        level = u8(party, 4),
        hp = u16(party, 6),
        maxHp = u16(party, 8),
        status = u32(party, 0),
        moves = IntArray(4) { u16(blocks, b + it * 2) },
        pp = IntArray(4) { u8(blocks, b + 8 + it) },
        genderSymbol = when {
            flags and 4 != 0 -> GENDER_SYMBOL_NONE
            flags and 2 != 0 -> GENDER_SYMBOL_FEMALE
            else -> GENDER_SYMBOL_MALE
        },
        exp = u32(blocks, a + 8),
        personality = personality,
        isEgg = (ivWord ushr 30) and 1L != 0L,
        heldItem = u16(blocks, a + 2),
        nickname = nickname.takeIf { it.isNotBlank() },
    )
}

/** A nickname's characters in the record (POKEMON_NAME_LENGTH + 1). */
private const val NICKNAME_CHARS = 11

/** Each party slot's last read that decoded ([readGen4Telemetry]). */
private val lastGoodParty = arrayOfNulls<Mon>(PARTY_SIZE)

private fun inMain(p: Long) = p in MAIN_RAM until MAIN_RAM_END

/** BATTLE_TYPE_TRAINER / BATTLE_TYPE_DOUBLES (constants/battle.h). */
private const val GEN4_BATTLE_TRAINER = 1L
private const val GEN4_BATTLE_DOUBLES = 2L

/** One BattleMon (battle/battle_mon.h, plaintext): what the battle screens show, with its stats and stages. */
private fun decodeGen4BattleMon(b: ByteArray, o: Int): BattleMon {
    val species = u16(b, o)
    if (species !in 1..493) return EMPTY_BATTLE_MON
    return BattleMon(
        species = species,
        level = u8(b, o + 0x34),
        type1 = u8(b, o + 0x24),
        type2 = u8(b, o + 0x25),
        hp = u32(b, o + 0x4C).toInt().coerceAtLeast(0),
        maxHp = u32(b, o + 0x50).toInt(),
        status1 = u32(b, o + 0x6C),
        moves = IntArray(4) { u16(b, o + 0x0C + it * 2) },
        pp = IntArray(4) { u8(b, o + 0x2C + it) },
        ability = u8(b, o + 0x27),
        item = u16(b, o + 0x78),
        // attack, defense, speed, sp. attack, sp. defense: Gen 3's order
        stats = IntArray(5) { u16(b, o + 2 + it * 2) },
        // statBoosts[8] starts with HP's (never used): the seven the companion shows, 6 = unchanged
        statStages = IntArray(7) { u8(b, o + 0x19 + it) },
    )
}

/** The battle running now ([Gen4Battle]): its battlers (and their personalities), the foe's party, whether it's a
 * trainer's / a double. */
private class Gen4BattleRead(
    val mons: List<BattleMon>, val personalities: List<Long>, val foes: List<Mon>, val trainer: Boolean, val doubles: Boolean,
)

/**
 * The battle's input state for BATTLE BUTTONS ([BATTLE_INPUT_*]), where [Gen4Battle.offBattleInput] is known: while the
 * controller waits for the player (command 5, SELECTION_SCREEN_INPUT), battler 0's selection step says which panel -
 * battle_controller_player.c's SSI states: 1 / 2 the commands, 3 / 4 the moves, 7 / 8 the bag, 9 / 10 the party
 * (headless on HeartGold: 1, 4, 8, 10) - and the touch screen's panel has to be drawn for a pick to land. Anything
 * else in a battle is BUSY; no battle is NONE.
 */
fun readGen4BattleInput(r: MemoryReader, b: Gen4Battle): Int {
    if (b.offBattleInput == 0) return BATTLE_INPUT_NONE
    val bs = gen4BattleSystem(r, b) ?: return BATTLE_INPUT_NONE
    val ctx = u32(r.readCoreMemory(bs + b.offBattleCtx, 4), 0)
    if (!inMain(ctx)) return BATTLE_INPUT_NONE
    val head = r.readCoreMemory(ctx, b.offCommand + 4)
    if (u32(head, b.offCommand) != GEN4_COMMAND_SELECTING) return BATTLE_INPUT_BUSY
    val input = u32(r.readCoreMemory(bs + b.offBattleInput, 4), 0)
    val shown = inMain(input) && inMain(u32(r.readCoreMemory(input + b.offPanelShown, 4), 0))
    return when (u8(head, 0)) {
        1, 2 -> if (shown) BATTLE_INPUT_ACTION_SELECT else BATTLE_INPUT_BUSY
        3, 4 -> if (shown) BATTLE_INPUT_MOVE_SELECT else BATTLE_INPUT_BUSY
        7, 8 -> BATTLE_INPUT_BAG_OPEN
        9, 10 -> BATTLE_INPUT_PARTY_OPEN
        else -> BATTLE_INPUT_BUSY
    }
}

/** CONTROLLER_COMMAND_SELECTION_SCREEN_INPUT. */
private const val GEN4_COMMAND_SELECTING = 5L

/** The running battle's BattleSystem (the field's child process's data), or null. */
private fun gen4BattleSystem(r: MemoryReader, b: Gen4Battle): Long? {
    val fs = u32(r.readCoreMemory(b.fieldSystemPtr, 4), 0)
    if (!inMain(fs)) return null
    val pm = u32(r.readCoreMemory(fs, 4), 0)
    if (!inMain(pm)) return null
    val child = u32(r.readCoreMemory(pm + b.offChild, 4), 0)
    if (!inMain(child)) return null
    if (u32(r.readCoreMemory(child, 4), 0) != u32(r.readCoreMemory(b.battleTemplate, 4), 0)) return null
    return u32(r.readCoreMemory(child + b.offAppData, 4), 0).takeIf { inMain(it) }
}

/** Null when there's no battle (the field, a menu, the moments before its BattleSystem exists). */
private fun readGen4Battle(r: MemoryReader, b: Gen4Battle): Gen4BattleRead? {
    val bs = gen4BattleSystem(r, b) ?: return null
    val head = r.readCoreMemory(bs + b.offBattleType, b.offParties + 16 - b.offBattleType)
    val type = u32(head, 0)
    val ctx = u32(head, b.offBattleCtx - b.offBattleType)
    if (!inMain(ctx)) return null
    val raw = r.readCoreMemory(ctx + b.offBattleMons, 4 * b.battleMonSize)
    val mons = List(4) { decodeGen4BattleMon(raw, it * b.battleMonSize) }
    val personalities = List(4) { u32(raw, it * b.battleMonSize + 0x68) }
    // parties[1]: the foe's side (a wild Pokémon's too), the same Party struct as the save's
    val foeParty = u32(head, b.offParties + 4 - b.offBattleType)
    val foes = if (inMain(foeParty)) {
        val count = u32(r.readCoreMemory(foeParty + 4, 4), 0).toInt().coerceIn(0, PARTY_SIZE)
        val foeRaw = r.readCoreMemory(foeParty + 8, PARTY_SIZE * GEN4_MON_SIZE)
        (0 until count).mapNotNull { decodeGen4PartyMon(foeRaw, it * GEN4_MON_SIZE) }
    } else emptyList()
    return Gen4BattleRead(mons, personalities, foes, type and GEN4_BATTLE_TRAINER != 0L, type and GEN4_BATTLE_DOUBLES != 0L)
}

/** A [Gen4ManagedBattle]'s battle now, or null outside one. */
private fun readManagedBattle(r: MemoryReader, b: Gen4ManagedBattle, cfg: Gen4Config): Gen4BattleRead? {
    val fs = u32(r.readCoreMemory(b.fieldSystemPtr, 4), 0)
    if (!inMain(fs)) return null
    val child = u32(r.readCoreMemory(u32(r.readCoreMemory(fs, 4), 0) + 4, 4), 0)
    if (!inMain(child) || u32(r.readCoreMemory(child, 4), 0) != u32(r.readCoreMemory(b.battleTemplate, 4), 0)) return null
    val mgr = u32(r.readCoreMemory(b.managerPtr, 4), 0)
    if (!inMain(mgr)) return null
    val head = r.readCoreMemory(mgr, b.offParties + 8)
    val setup = u32(head, b.offSetup)
    val type = if (inMain(setup)) u32(r.readCoreMemory(setup, 4), 0) else 0L
    val active = u32(head, b.offActive).let { if (inMain(it)) r.readCoreMemory(it, 2) else byteArrayOf(0, -1) }
    fun obj(base: Int, slot: Int): Pair<BattleMon, Long>? {
        if (slot !in 0 until PARTY_SIZE) return null
        val p = u32(head, base + slot * 4)
        if (!inMain(p)) return null
        val o = r.readCoreMemory(p, b.objSize)
        val species = u16(o, b.objSpecies)
        if (species == 0 || species > cfg.maxSpecies) return null
        val record = u32(o, b.objRecord)
        val mon = if (inMain(record)) decodeGen4PartyMon(r.readCoreMemory(record, GEN4_MON_SIZE), 0, cfg.maxSpecies, formSpecies = cfg.formSpecies) else null
        // Its order: Attack, Defense, Sp. Atk, Sp. Def, Speed - the companion's: Attack, Defense, Speed, Sp. Atk, Sp. Def.
        val st = IntArray(5) { u16(o, b.objStats + it * 2) }
        val sg = IntArray(7) { u8(o, b.objStages + it) }
        return BattleMon(
            species = species,
            level = u8(o, b.objLevel),
            type1 = u8(o, b.objTypes),
            type2 = u8(o, b.objTypes + 1),
            hp = u16(o, b.objHp),
            maxHp = u16(o, b.objMaxHp),
            status1 = mon?.status ?: 0L,
            moves = IntArray(4) { u16(o, b.objMoves + it * b.objMoveStride + 2) },
            pp = IntArray(4) { u8(o, b.objMoves + it * b.objMoveStride + 4) },
            ability = u16(o, b.objAbility),
            item = u16(o, b.objItem),
            stats = intArrayOf(st[0], st[1], st[4], st[2], st[3]),
            statStages = intArrayOf(sg[0], sg[1], sg[4], sg[2], sg[3], sg[5], sg[6]),
        ) to (mon?.personality ?: 0L)
    }
    val second = u8(active, 1).takeIf { it != 0xFF }
    val foes = u8(head, b.offFoeCount).coerceIn(0, PARTY_SIZE)
    val battlers = listOf(
        obj(b.offPlayerObjs, u8(active, 0)),
        obj(b.offFoeObjs, 0),
        second?.let { obj(b.offPlayerObjs, it) },
        if (second != null && foes > 1) obj(b.offFoeObjs, 1) else null,
    )
    val foeParty = u32(head, b.offParties + 4)
    val foeMons = if (inMain(foeParty)) {
        val count = u32(r.readCoreMemory(foeParty + 4, 4), 0).toInt().coerceIn(0, PARTY_SIZE)
        val raw = r.readCoreMemory(foeParty + 8, PARTY_SIZE * GEN4_MON_SIZE)
        (0 until count).mapNotNull { decodeGen4PartyMon(raw, it * GEN4_MON_SIZE, cfg.maxSpecies, formSpecies = cfg.formSpecies) }
    } else emptyList()
    return Gen4BattleRead(
        battlers.map { it?.first ?: EMPTY_BATTLE_MON }, battlers.map { it?.second ?: 0L }, foeMons,
        type and GEN4_BATTLE_TRAINER != 0L, second != null,
    )
}

/** Where the player is on the Town Map: their block, its place and the first-arrival flags (bit n = first arrival n). */
private class Gen4MapRead(val block: Pair<Int, Int>?, val place: Int, val live: Triple<Int, Int, Int>?, val firstArrivals: Long)

private fun readGen4TownMap(r: MemoryReader, t: Gen4TownMap, body: Long, locationOff: Int): Gen4MapRead {
    // The live map and position, while the field is up.
    var live: Triple<Int, Int, Int>? = null
    val fs = u32(r.readCoreMemory(t.fieldSystemPtr, 4), 0)
    if (inMain(fs)) {
        val loc = u32(r.readCoreMemory(fs + t.offLocation, 4), 0)
        val avatar = u32(r.readCoreMemory(fs + t.offPlayerAvatar, 4), 0)
        val obj = if (inMain(avatar)) u32(r.readCoreMemory(avatar + t.offAvatarMapObject, 4), 0) else 0L
        if (inMain(loc) && inMain(obj)) {
            val xz = r.readCoreMemory(obj + t.offObjectX, t.offObjectZ + 4 - t.offObjectX)
            live = Triple(u32(r.readCoreMemory(loc, 4), 0).toInt(), u32(xz, 0).toInt(), u32(xz, t.offObjectZ - t.offObjectX).toInt())
        }
    }
    val (header, x, z) = live ?: run {
        val saved = r.readCoreMemory(body + locationOff, 0x14)
        Triple(u32(saved, 0).toInt(), u32(saved, 8).toInt(), u32(saved, 12).toInt())
    }
    // On the overworld their own block; anywhere else (a house, a cave) the door they went in by, as the game's map does.
    val (bx, bz) = if (header in t.mainMatrixHeaders) x / 32 to z / 32 else {
        val exit = r.readCoreMemory(body + locationOff + t.offExitLocation, 0x14)
        u32(exit, 8).toInt() / 32 to u32(exit, 12).toInt() / 32
    }
    val block = (bx to bz).takeIf { bx in 0 until 30 && bz in 0 until 30 }
    val place = block?.let { (cx, cz) -> u16(t.grid, (cz * 30 + cx) * 2) }?.takeIf { it != 0xFFFF } ?: (t.headerPlaces[header] ?: -1)
    val flagsAt = body + t.varsOff + t.numVars * 2 + t.firstArrivalFlag / 8
    val bits = r.readCoreMemory(flagsAt, 8)
    val shift = t.firstArrivalFlag % 8
    var arrivals = 0L
    for (i in 0 until 56) {
        val bit = shift + i
        if ((u8(bits, bit / 8) shr (bit % 8)) and 1 != 0) arrivals = arrivals or (1L shl i)
    }
    return Gen4MapRead(block, place, live, arrivals)
}

/** The SaveData's body in main RAM, or -1 before the game has one (boot, title screen). */
private fun gen4Body(r: MemoryReader, cfg: Gen4Config): Long {
    val ptr = u32(r.readCoreMemory(cfg.saveDataPtr, 4), 0)
    return if (ptr in MAIN_RAM until MAIN_RAM_END - 0x20000) ptr + cfg.bodyOff else -1
}

fun readGen4Bag(r: MemoryReader, cfg: Gen4Config, body: Long): List<Item> {
    val raw = r.readCoreMemory(body + cfg.bagOff, cfg.pocketSizes.sum() * 4)
    val out = ArrayList<Item>()
    var slot = 0
    for ((p, size) in cfg.pocketSizes.withIndex()) {
        for (i in 0 until size) {
            val id = u16(raw, (slot + i) * 4)
            val qty = u16(raw, (slot + i) * 4 + 2)
            if (id != 0 && qty != 0) out += Item(id, qty, cfg.savePockets?.getOrNull(p) ?: cfg.itemPockets[id] ?: POCKET_ITEMS)
        }
        slot += size
    }
    return out
}

fun readGen4Telemetry(r: MemoryReader, cfg: Gen4Config): Telemetry {
    val body = gen4Body(r, cfg)
    if (body < 0) return emptyTelemetry()
    val partyHead = r.readCoreMemory(body + cfg.partyOff, 8)
    val count = u32(partyHead, 4).toInt().takeIf { it in 0..PARTY_SIZE } ?: 0
    // The game runs while this reads, and it decrypts a Pokémon in place, field by field, whenever it looks at one: a
    // read can catch it half done (the flags saying one thing, the bytes another; the party fields have no checksum, so
    // a lead read as Lv 48 asleep). So the party is read twice, back to back, and a slot counts only when both agree
    // and it decodes to something real; else it keeps its last good read of the same Pokémon.
    val partyRaw = r.readCoreMemory(body + cfg.partyOff + 8, PARTY_SIZE * GEN4_MON_SIZE)
    val again = r.readCoreMemory(body + cfg.partyOff + 8, PARTY_SIZE * GEN4_MON_SIZE)
    val party = (0 until count).mapNotNull { slot ->
        val off = slot * GEN4_MON_SIZE
        val personality = u32(partyRaw, off)
        val steady = (off until off + GEN4_MON_SIZE).all { partyRaw[it] == again[it] }
        (if (steady) decodeGen4PartyMon(partyRaw, off, cfg.maxSpecies, formSpecies = cfg.formSpecies) else null)?.also { lastGoodParty[slot] = it }
            ?: lastGoodParty[slot]?.takeIf { it.personality == personality }
    }
    val items = readGen4Bag(r, cfg, body)
    val trainer = r.readCoreMemory(body + cfg.trainerOff, 0x1C)
    val location = r.readCoreMemory(body + cfg.locationOff, 0x14)
    val map = cfg.townMap?.let { readGen4TownMap(r, it, body, cfg.locationOff) }
    // The live map and position where the field has them (the save's Location only moves on a warp or a save).
    // (FieldSystem.location is the save's own Location, so its map id is live too; its x / z move only on a warp.)
    val mapHeader = map?.live?.first ?: u32(location, 0).toInt()
    val battle = cfg.battle?.let { readGen4Battle(r, it) } ?: cfg.managedBattle?.let { readManagedBattle(r, it, cfg) }
    // HeartGold / SoulSilver: the Pokégear map - the player's cell (their block two rows down), its place, the flypoint
    // flags and the Pokégear's skin / unlock level ([PokegearMap]).
    val pgCell = if (cfg.pokegear != null) gen4PokegearBlock({ a, n -> r.readCoreMemory(a, n) }, cfg)?.let { (x, y) -> x to y + 2 } else null
    val flypoints = if (cfg.flagsOff >= 0) {
        val bits = r.readCoreMemory(body + cfg.flagsOff + PokegearMap.FIRST_FLYPOINT_FLAG / 8, 8)
        var v = 0L
        for (i in 0 until 38) {
            val bit = PokegearMap.FIRST_FLYPOINT_FLAG % 8 + i
            if ((u8(bits, bit / 8) shr (bit % 8)) and 1 != 0) v = v or (1L shl i)
        }
        v
    } else null
    val pokegearState = if (cfg.pokegearWordOff >= 0) {
        val w = u32(r.readCoreMemory(body + cfg.pokegearWordOff, 4), 0)
        (((w ushr 18) and 0x7F).toInt() shl 2) or ((w ushr 27) and 3).toInt()
    } else null
    // The foe that's out, by its personality among the foe's party (no slot index to read).
    val foePid = battle?.personalities?.getOrNull(BATTLE_POS_OPPONENT_LEFT)
    val enemyActive = battle?.foes?.indexOfFirst { it.personality == foePid } ?: -1
    return Telemetry(
        frameCounter = 0,
        inBattle = battle != null,
        isDoubleBattle = battle?.doubles == true,
        battleInputState = if (battle != null) cfg.battle?.let { readGen4BattleInput(r, it) } ?: BATTLE_INPUT_NONE else BATTLE_INPUT_NONE,
        mapGroup = 0,
        mapNum = mapHeader,
        regionMapSectionId = map?.place ?: pgCell?.let { (x, y) -> PokegearMap.placeAt(x, y) } ?: -1,
        x = map?.live?.second ?: u32(location, 8).toInt(),
        y = map?.live?.third ?: u32(location, 12).toInt(),
        facingDirection = u32(location, 16).toInt(),
        partyCount = party.size,
        party = party,
        battleMons = battle?.mons ?: List(4) { EMPTY_BATTLE_MON },
        itemCount = items.size,
        items = items,
        money = u32(trainer, 0x14).takeIf { it <= 999_999 },
        playerGender = u8(trainer, 0x18).takeIf { it <= 1 } ?: -1,
        mapSecName = cfg.placeName(mapHeader),
        repelSteps = cfg.repelStepsOff?.let { u8(r.readCoreMemory(body + it, 1), 0) },
        enemyParty = if (battle?.trainer == true) battle.foes else emptyList(),
        battleFoes = battle?.foes ?: emptyList(),
        enemyActive = if (battle?.trainer == true) enemyActive else -1,
        regionMapTile = map?.block ?: pgCell,
        townsVisited = map?.firstArrivals ?: flypoints,
        pokegearState = pokegearState,
        pokedex = readGen4PokedexState(r, cfg, body),
        progress = readGen4Progress(r, cfg, body),
    )
}

/** REPEL's kinds on a Gen 4 game, weakest first: item id and steps (Platinum's REPEL / SUPER REPEL / MAX REPEL). */
val GEN4_REPEL_KINDS = listOf(79 to 100, 76 to 200, 77 to 250)

/**
 * Uses one Repel ([itemId], [steps]) the way the game's bag does (applications/bag: SetRepelSteps, then
 * Bag_TryRemoveItem): one off its slot in the ITEMS pocket - an emptied slot moves to the pocket's end, the rest keep
 * their order (Pocket_SortEmpty) - and the step count into [Gen4Config.repelStepsOff]. Plain RAM writes, no game code:
 * the field counts the steps down and says when it wore off by itself. Only with the field free ([fieldFree]) and no
 * Repel still working. Emu thread, between frames.
 */
fun useGen4Repel(cfg: Gen4Config, calls: GameCalls, itemId: Int, steps: Int, fieldFree: Boolean): ItemUseOutcome {
    if (!fieldFree) return ItemUseOutcome(ItemUseResult.NOT_NOW)
    val repelStepsOff = cfg.repelStepsOff ?: return ItemUseOutcome(ItemUseResult.FAILED)
    val ptr = u32(calls.read(cfg.saveDataPtr, 4), 0)
    if (ptr !in MAIN_RAM until MAIN_RAM_END - 0x20000) return ItemUseOutcome(ItemUseResult.FAILED)
    val body = ptr + cfg.bodyOff
    if (u8(calls.read(body + repelStepsOff, 1), 0) > 0) return ItemUseOutcome(ItemUseResult.STILL_ACTIVE)
    val pocket = body + cfg.bagOff   // ITEMS is the bag's first pocket
    val size = cfg.pocketSizes[0]
    val raw = calls.read(pocket, size * 4)
    val slot = (0 until size).firstOrNull { u16(raw, it * 4) == itemId && u16(raw, it * 4 + 2) > 0 }
        ?: return ItemUseOutcome(ItemUseResult.NONE_LEFT)
    val out = raw.copyOf()
    val left = u16(raw, slot * 4 + 2) - 1
    if (left > 0) {
        out[slot * 4 + 2] = left.toByte()
        out[slot * 4 + 3] = (left shr 8).toByte()
    } else {
        // The empty slot to the end, the others moved up in order.
        System.arraycopy(raw, (slot + 1) * 4, out, slot * 4, (size - slot - 1) * 4)
        for (i in (size - 1) * 4 until size * 4) out[i] = 0
    }
    if (!calls.write(pocket, out)) return ItemUseOutcome(ItemUseResult.FAILED)
    if (!calls.write(body + repelStepsOff, byteArrayOf(steps.toByte()))) return ItemUseOutcome(ItemUseResult.FAILED)
    return ItemUseOutcome(ItemUseResult.USED, steps = steps)
}

private fun emptyTelemetry() = Telemetry(
    frameCounter = 0, inBattle = false, isDoubleBattle = false, mapGroup = 0, mapNum = 0, regionMapSectionId = -1,
    x = 0, y = 0, facingDirection = 0, partyCount = 0, party = emptyList(), battleMons = List(4) { EMPTY_BATTLE_MON },
    itemCount = 0, items = emptyList(),
)
