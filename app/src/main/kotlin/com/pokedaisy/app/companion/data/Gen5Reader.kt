package com.pokedaisy.app.companion.data

// Nintendo DS, Gen 5 (Black first): the companion's reads of the game's main RAM. There is no pret decomp for Gen 5, so
// every address here came from headless captures of the retail cart (native-capture/nds_dump: dumps of the field, a text
// box, the X menu, the party screen and a Battle Subway battle, diffed) and the cart's own files. The game keeps its whole
// save image (the save file's first copy, block for block: Black's 0x24000 bytes, Black 2's 0x26000) in one heap block
// and plays from it: the
// party, the bag, money, the Pokédex and the event flags change there as they change in the game. Its pointer is
// save_control.c's static SaveControl -> its backup system -> the image.

/** Where one Gen 5 game keeps what the companion reads. Offsets into the save image are the save file's own. */
data class Gen5Config(
    /** save_control.c's SaveControl pointer (static); the backup system at [offBackupSystem], the image at [offImage]. */
    val saveControlPtr: Long,
    val offBackupSystem: Int = 0x10,
    val offImage: Int = 0x34,
    /** The save image's size (the save file's first copy): Black / White's 0x24000, Black 2 / White 2's 0x26000. */
    val imageSize: Int = 0x24000,
    /** Party { capacity, count, Pokemon[6] } (0xDC bytes each: Gen 4's struct and encryption). */
    val partyOff: Int = 0x18E00,
    /** The trainer block: name (UTF-16) +4, TID / SID +0x14, language +0x1E, version +0x1F, gender +0x21, play time +0x24. */
    val trainerOff: Int = 0x19400,
    /** The bag: [pocketSizes] slots of { u16 item, u16 quantity }, plain, pocket after pocket. */
    val bagOff: Int = 0x18400,
    val pocketSizes: IntArray = intArrayOf(310, 83, 109, 48, 64),
    /** The companion's pocket for each of the bag's (items, key items, TMs / HMs, medicine, berries). */
    val savePockets: IntArray = intArrayOf(POCKET_ITEMS, POCKET_KEY_ITEMS, POCKET_TM_HM, POCKET_MEDICINE, POCKET_BERRIES),
    /** Money (u32) and the badges (a byte, bit n = badge n) in the misc block. */
    val moneyOff: Int = 0x21200,
    val badgesOff: Int = 0x21204,
    /** The save's Location { u32 type, u16 zone, s16 exit, u16 dir, ..., fx32 x / y / z }: where the last warp left the
     * player (the live zone is the GameData's: [gameSystemPtr]). */
    val locationOff: Int = 0x19500,
    /** The static GameSystem pointer; its GameData at [offGameData], whose player record has the zone (u16) at
     * [offZone] and the position (fx32 x / y / z) after it - live, as the player walks. 0 = the save's Location only. */
    val gameSystemPtr: Long = 0,
    val offGameData: Int = 0x1C,
    val offZone: Int = 0x114,
    /** The Pokédex: magic BEEFCAFE, caught bits at +8, seen in four sets of the same size (male, female, the shiny two). */
    val pokedexOff: Int = 0x21600,
    val pokedexTables: PokedexTables? = null,
    /** The event work: [numVars] u16 vars from VAR 0x4000, then the flags. */
    val varsFlagsOff: Int = 0x20100,
    val numVars: Int = 0x13E,
    val numFlags: Int = 0xB60,
    /** The steps a Repel has left (a byte the field counts down a step at a time), or null when not mapped. */
    val repelStepsOff: Int? = null,
    /** Each zone's place name (the zone header's place-name line) - the English one; [placeName] reads the cart's own. */
    val zonePlaces: Map<Int, String>,
    val zonePlaceIds: Map<Int, Int> = emptyMap(),
    val battle: Gen5Battle? = null,
    val maxSpecies: Int = 649,
    /** The Town Map ([townMapPlacesBlack]) and each zone's place on it ([townMapZonesBlack]); empty = none. */
    val townMapPlaces: List<Gen5TownMapPlace> = emptyList(),
    val townMapZones: IntArray? = null,
    /** The Liberty Pass (its island shows on the Town Map once the bag holds it); 0 = none. */
    val libertyPass: Int = 0,
    /** The Town Map's arrival flags: [Telemetry.townsVisited]'s bit n is flag [firstArrival] + n, for [arrivalCount]
     * bits (Black's 2480 .. 2511; the sequels' run from 2469 to Victory Road's 2530). */
    val firstArrival: Int = GEN5_FIRST_ARRIVAL,
    val arrivalCount: Int = 32,
    /** The regional dex's species (the reader's National Dex check: anything seen outside it); null = Black's Unova
     * Dex, VICTINI .. GENESECT (494-649). */
    val regionalDex: IntArray? = null,
)

/**
 * One of a Gen 5 Town Map's places (a/0/8/6's records): [zone], its [place] (the place names' line), its point on the
 * map ([x], [y]: the touch screen's pixels, where the game centres its sprites), its [kind] (0 a town, 1 a route, 2 a
 * place, 3 Victory Road, 4 Liberty Garden, 5 Unity Tower) and its arrival flag ([flag], -1 none).
 */
data class Gen5TownMapPlace(
    val zone: Int, val place: Int, val x: Int, val y: Int, val kind: Int, val flag: Int,
    /** Black 2 / White 2: what shows the place (its +0x20: a flag, 0xF00n one of the game's own checks); -1 always. */
    val shownBy: Int = -1,
)

/** The Town Map's first arrival flag: [Telemetry.townsVisited]'s bit n is flag 2480 + n, as on Platinum. */
const val GEN5_FIRST_ARRIVAL = 2480

/** Black 2 / White 2's first arrival flag (Pokéstar Studios'; Nuvema Town's is 2472, Victory Road's 2530). */
const val GEN5_BW2_FIRST_ARRIVAL = 2469

/** [Telemetry.townsVisited]'s bits past the sequels' arrival flags (62 of them): the flags that show a Town Map place the
 * story reveals (`Gen5TownMapPlace.shownBy` under 0xF000: N's Castle's 0x99F, the Abyssal Ruins' 0x9A0), in that order. */
const val GEN5_BW2_SHOWN_BIT = 62

/** The flags [GEN5_BW2_SHOWN_BIT] and on stand for, from [places]. */
fun gen5ShownFlags(places: List<Gen5TownMapPlace>): List<Int> =
    places.map { it.shownBy }.filter { it in 0 until 0xF000 }.distinct().sorted()

/** [Telemetry.townsVisited]'s bit for the Liberty Pass in the bag (Liberty Garden's island on the Town Map). */
const val GEN5_LIBERTY_PASS_BIT = 40

/** The Town Map's grid: [GEN5_TOWN_MAP_TILE] pixels a cell, the map 256 x 168 pixels. */
const val GEN5_TOWN_MAP_TILE = 8

/**
 * A battle, from the game system: GameSystem -> its proc system -> the running proc, the battle's when its overlay is
 * [battleOverlay]; the proc's work is the battle's main module.
 */
data class Gen5Battle(
    /** The static pointer to the GameSystem (procsys.c's work, which also holds the GameData and the field map). */
    val gameSystemPtr: Long,
    val offProcSys: Int = 0x08,
    val offCurrentProc: Int = 0x08,
    /** The proc's overlay id; [battleOverlay] the battle's (21 is the field's, 91 the party list's). */
    val offProcOverlay: Int = 0x04,
    val battleOverlay: Int = 93,
    /** The proc's param (the battle setup) and work (the main module). */
    val offProcParam: Int = 0x10,
    val offProcWork: Int = 0x14,
    /** BATTLE_SETUP_PARAM: competitor (0 wild, 1 trainer, 2 the Battle Subway, ...), rule (0 single, 1 double, ...),
     * then the parties: the player's and the foe's (the save's Party struct). */
    val offCompetitor: Int = 0x00,
    val offRule: Int = 0x04,
    val offPlayerParty: Int = 0x24,
    val offFoeParty: Int = 0x28,
    /** The main module's battle parties: [partyStride] bytes a client, { BTL_POKEPARAM* member[6], u32 count },
     * member 0 (and 1 in a double) the ones out. */
    val offParties: Int = 0xCC,
    val partyStride: Int = 0x1C,
)

private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
private fun u16(b: ByteArray, o: Int) = u16le(b, o)
private fun u32(b: ByteArray, o: Int) = u32le(b, o)
private const val MAIN_RAM = 0x02000000L
private const val MAIN_RAM_END = 0x02400000L
private fun inMain(p: Long) = p in MAIN_RAM until MAIN_RAM_END
private const val GEN5_MON_SIZE = 0xDC
private val EMPTY_BATTLE_MON = BattleMon(0, 0, 0, 0, 0, 0, 0, IntArray(4), IntArray(4))

/** Gen 5's type ids drop Gen 4's ??? (9): FIRE is 9 .. DARK 16. The app keeps Gen 3's ids. */
fun gen5Type(t: Int): Int = if (t >= 9) t + 1 else t

/** The save image in main RAM, or -1 before the game has loaded one (boot, the title screen). */
fun gen5Image(r: (Long, Int) -> ByteArray, cfg: Gen5Config): Long {
    val sc = u32(r(cfg.saveControlPtr, 4), 0)
    if (!inMain(sc)) return -1
    val bs = u32(r(sc + cfg.offBackupSystem, 4), 0)
    if (!inMain(bs)) return -1
    val img = u32(r(bs + cfg.offImage, 4), 0)
    return if (img in MAIN_RAM until MAIN_RAM_END - cfg.imageSize) img else -1
}

/** Zone [zone]'s place name in the cart's own words, else the US game's. */
fun Gen5Config.placeName(zone: Int): String? = zonePlaceIds[zone]?.let { NdsArt.names?.place(it) } ?: zonePlaces[zone]

/** Each party slot's last read that decoded ([readGen5Telemetry]). */
private val lastGoodParty = arrayOfNulls<Mon>(PARTY_SIZE)

fun readGen5Bag(r: MemoryReader, cfg: Gen5Config, img: Long): List<Item> {
    val raw = r.readCoreMemory(img + cfg.bagOff, cfg.pocketSizes.sum() * 4)
    val out = ArrayList<Item>()
    var slot = 0
    for ((p, size) in cfg.pocketSizes.withIndex()) {
        for (i in 0 until size) {
            val id = u16(raw, (slot + i) * 4)
            val qty = u16(raw, (slot + i) * 4 + 2)
            if (id != 0 && qty != 0) out += Item(id, qty, cfg.savePockets[p])
        }
        slot += size
    }
    return out
}

/** A flag past the save's own: the National Dex is in ([readGen5PokedexState]'s national) - Black hands it over after
 * the ending, so it says N and GHETSIS are beaten. */
const val GEN5_NATIONAL_DEX_FLAG = 0x30000

/** The event flags and vars (the GUIDE's [SaveProgress]), with the badges as [GEN4_BADGE_FLAG] + n, the DS clock's
 * hour as [GEN4_VAR_HOUR], like the Gen 4 games', and [GEN5_NATIONAL_DEX_FLAG]. */
fun readGen5Progress(
    r: MemoryReader, cfg: Gen5Config, img: Long, national: Boolean = false, hour: Int = java.time.LocalTime.now().hour,
): SaveProgress? {
    val flagBytes = cfg.numFlags / 8
    val vars = r.readCoreMemory(img + cfg.varsFlagsOff, cfg.numVars * 2)
    val flags = r.readCoreMemory(img + cfg.varsFlagsOff + cfg.numVars * 2, flagBytes)
    if (vars.size < cfg.numVars * 2 || flags.size < flagBytes) return null
    val badges = r.readCoreMemory(img + cfg.badgesOff, 1)
    return SaveProgress(
        flags, vars,
        flagBanks = listOf(
            SaveBank(GEN4_BADGE_FLAG, 0, 8) to byteArrayOf(badges[0]),
            SaveBank(GEN5_NATIONAL_DEX_FLAG, 0, 1) to byteArrayOf(if (national) 1 else 0),
        ),
        varBanks = listOf(SaveBank(GEN4_VAR_HOUR, 0, 1) to byteArrayOf(hour.toByte(), 0)),
    )
}

/** The save's Pokédex: caught, and seen in any of its four sets (caught counts as seen). */
fun readGen5PokedexState(r: MemoryReader, cfg: Gen5Config, img: Long): PokedexState? {
    val tables = cfg.pokedexTables ?: return null
    val setBytes = (cfg.maxSpecies + 7) / 8 + 3 and 3.inv()   // 0x54 for 649
    val d = r.readCoreMemory(img + cfg.pokedexOff, 8 + setBytes * 5)
    if (d.size < 8 + setBytes * 5 || u32(d, 0) != 0xBEEFCAFEL) return null
    fun bit(base: Int, n: Int) = (d[base + (n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 != 0
    val caught = (1..cfg.maxSpecies).filter { bit(8, it) }.toSet()
    val seen = (1..cfg.maxSpecies).filter { n -> n in caught || (1..4).any { bit(8 + setBytes * it, n) } }.toSet()
    // Black's Unova Dex holds only Unova's own (VICTINI .. GENESECT, 494-649); the sequels' has 301, older ones among them:
    // anything seen outside it means the National Dex.
    val regional = cfg.regionalDex?.toHashSet()
    val national = seen.any { if (regional != null) it !in regional else it < 494 }
    return PokedexState(tables, seen, caught, national)
}

/** One BTL_POKEPARAM (0x224 bytes): what the battle screens show, with its stats and stages, in the app's order. */
private fun decodeGen5BattleMon(b: ByteArray, o: Int): BattleMon {
    val species = u16(b, o + 0x0C)
    if (species !in 1..649) return EMPTY_BATTLE_MON
    fun stat(i: Int) = u16(b, o + 0xEE + i * 2)
    // Stats: attack, defense, sp. atk, sp. def, speed; stages the same order then accuracy, evasion (6 = unchanged).
    val stage = IntArray(7) { u8(b, o + 0xFC + it) }
    return BattleMon(
        species = species,
        level = u8(b, o + 0x18),
        type1 = gen5Type(u8(b, o + 0xF8)),
        type2 = gen5Type(u8(b, o + 0xF9)),
        hp = u16(b, o + 0x0E),
        maxHp = u16(b, o + 0x10),
        status1 = 0,
        moves = IntArray(4) { u16(b, o + 0x104 + it * 14) },
        pp = IntArray(4) { u8(b, o + 0x104 + it * 14 + 2) },
        ability = u16(b, o + 0x16),
        item = u16(b, o + 0x12),
        // the app's order: attack, defense, speed, sp. atk, sp. def (Gen 3's)
        stats = intArrayOf(stat(0), stat(1), stat(4), stat(2), stat(3)),
        statStages = intArrayOf(stage[0], stage[1], stage[4], stage[2], stage[3], stage[5], stage[6]),
    )
}

private class Gen5BattleRead(val mons: List<BattleMon>, val foes: List<Mon>, val foeActive: Int, val trainer: Boolean, val doubles: Boolean)

/** BTL_POKEPARAM's id: its place in the party it came from, plus 6 for each step to its side (the player's 0-5, the foe's
 * 12-17 in the captures). The battle's own array reorders on a switch; this doesn't. */
private const val GEN5_POKE_ID = 0x19

/** Null outside a battle (the field, a menu, the moments before the battle's proc runs). */
private fun readGen5Battle(r: MemoryReader, b: Gen5Battle): Gen5BattleRead? {
    fun p(a: Long) = u32(r.readCoreMemory(a, 4), 0)
    val gs = p(b.gameSystemPtr)
    if (!inMain(gs)) return null
    val ps = p(gs + b.offProcSys)
    if (!inMain(ps)) return null
    val proc = p(ps + b.offCurrentProc)
    if (!inMain(proc) || p(proc + b.offProcOverlay) != b.battleOverlay.toLong()) return null
    val param = p(proc + b.offProcParam)
    val main = p(proc + b.offProcWork)
    if (!inMain(param) || !inMain(main)) return null
    val head = r.readCoreMemory(param, b.offFoeParty + 4)
    val competitor = u32(head, b.offCompetitor).toInt()
    val rule = u32(head, b.offRule).toInt()
    val parties = r.readCoreMemory(main + b.offParties, b.partyStride * 2)
    fun member(client: Int, i: Int) = u32(parties, client * b.partyStride + i * 4).takeIf { inMain(it) }
    fun mon(addr: Long?) = addr?.let { decodeGen5BattleMon(r.readCoreMemory(it, 0x140), 0) } ?: EMPTY_BATTLE_MON
    val foeOut = member(1, 0)?.let { u8(r.readCoreMemory(it + GEN5_POKE_ID, 1), 0) % 6 } ?: -1
    val doubles = rule == 1
    val mons = listOf(mon(member(0, 0)), mon(member(1, 0)), if (doubles) mon(member(0, 1)) else EMPTY_BATTLE_MON,
        if (doubles) mon(member(1, 1)) else EMPTY_BATTLE_MON)
    val foeParty = u32(head, b.offFoeParty)
    val foes = if (inMain(foeParty)) {
        val count = u32(r.readCoreMemory(foeParty + 4, 4), 0).toInt().coerceIn(0, PARTY_SIZE)
        val raw = r.readCoreMemory(foeParty + 8, PARTY_SIZE * GEN5_MON_SIZE)
        (0 until count).mapNotNull { decodeGen4PartyMon(raw, it * GEN5_MON_SIZE, 649, GEN5_MON_SIZE) }
    } else emptyList()
    return Gen5BattleRead(mons, foes, foeOut.takeIf { it in foes.indices } ?: -1, competitor != 0, doubles)
}

fun readGen5Telemetry(r: MemoryReader, cfg: Gen5Config): Telemetry {
    val img = gen5Image({ a, n -> r.readCoreMemory(a, n) }, cfg)
    if (img < 0) return emptyGen5Telemetry()
    val partyHead = r.readCoreMemory(img + cfg.partyOff, 8)
    val count = u32(partyHead, 4).toInt().takeIf { it in 0..PARTY_SIZE } ?: 0
    // Read twice, a slot counting only when both agree (the game decrypts a Pokémon in place while it looks at it).
    val partyRaw = r.readCoreMemory(img + cfg.partyOff + 8, PARTY_SIZE * GEN5_MON_SIZE)
    val again = r.readCoreMemory(img + cfg.partyOff + 8, PARTY_SIZE * GEN5_MON_SIZE)
    val party = (0 until count).mapNotNull { slot ->
        val off = slot * GEN5_MON_SIZE
        val personality = u32(partyRaw, off)
        val steady = (off until off + GEN5_MON_SIZE).all { partyRaw[it] == again[it] }
        (if (steady) decodeGen4PartyMon(partyRaw, off, cfg.maxSpecies, GEN5_MON_SIZE) else null)?.also { lastGoodParty[slot] = it }
            ?: lastGoodParty[slot]?.takeIf { it.personality == personality }
    }
    val items = readGen5Bag(r, cfg, img)
    val trainer = r.readCoreMemory(img + cfg.trainerOff, 0x28)
    val saved = r.readCoreMemory(img + cfg.locationOff, 0x1C)
    // The live zone and position where the game has them (the save's Location only moves on a warp).
    val gd = if (cfg.gameSystemPtr != 0L) u32(r.readCoreMemory(cfg.gameSystemPtr, 4), 0).takeIf { inMain(it) }
        ?.let { u32(r.readCoreMemory(it + cfg.offGameData, 4), 0) }?.takeIf { inMain(it) } else null
    val live = gd?.let { r.readCoreMemory(it + cfg.offZone, 0x10) }
    val zone = live?.let { u16(it, 0) } ?: u16(saved, 4)
    val battle = cfg.battle?.let { readGen5Battle(r, it) }
    val dex = readGen5PokedexState(r, cfg, img)
    val progress = readGen5Progress(r, cfg, img, national = dex?.national == true)
    // The Town Map: the zone's place on it (a house or a cave's floor: its parent zone's), and which of its towns the
    // player has arrived at (the arrival flags from 2480; the game colours those).
    val town = cfg.townMapZones?.getOrNull(zone)?.let { cfg.townMapPlaces.getOrNull(it) }
    val arrivals = progress?.let { p ->
        var bits = 0L
        for (n in 0 until cfg.arrivalCount) if (p.flag(cfg.firstArrival + n)) bits = bits or (1L shl n)
        if (cfg.firstArrival == GEN5_BW2_FIRST_ARRIVAL) for ((k, flag) in gen5ShownFlags(cfg.townMapPlaces).withIndex()) {
            if (GEN5_BW2_SHOWN_BIT + k < 64 && p.flag(flag)) bits = bits or (1L shl (GEN5_BW2_SHOWN_BIT + k))
        }
        if (cfg.libertyPass != 0 && items.any { it.itemId == cfg.libertyPass }) bits = bits or (1L shl GEN5_LIBERTY_PASS_BIT)
        bits
    }
    return Telemetry(
        frameCounter = 0,
        inBattle = battle != null,
        isDoubleBattle = battle?.doubles == true,
        mapGroup = 0,
        mapNum = zone,
        regionMapSectionId = cfg.zonePlaceIds[zone] ?: -1,
        x = (live?.let { u32(it, 4) } ?: u32(saved, 0x10)).toInt() shr 16,
        y = (live?.let { u32(it, 0xC) } ?: u32(saved, 0x18)).toInt() shr 16,
        facingDirection = u16(saved, 8),
        partyCount = party.size,
        party = party,
        battleMons = battle?.mons ?: List(4) { EMPTY_BATTLE_MON },
        itemCount = items.size,
        items = items,
        money = u32(r.readCoreMemory(img + cfg.moneyOff, 4), 0).takeIf { it <= 9_999_999 },
        playerGender = u8(trainer, 0x21).takeIf { it <= 1 } ?: -1,
        mapSecName = cfg.placeName(zone),
        repelSteps = cfg.repelStepsOff?.let { u8(r.readCoreMemory(img + it, 1), 0) },
        enemyParty = if (battle?.trainer == true) battle.foes else emptyList(),
        battleFoes = battle?.foes ?: emptyList(),
        isTrainerBattle = battle?.trainer,
        enemyActive = if (battle?.trainer == true) battle.foeActive else -1,
        pokedex = dex,
        progress = progress,
        regionMapTile = town?.let { it.x / GEN5_TOWN_MAP_TILE to it.y / GEN5_TOWN_MAP_TILE },
        townsVisited = if (cfg.townMapPlaces.isEmpty()) null else arrivals,
    )
}

private fun emptyGen5Telemetry() = Telemetry(
    frameCounter = 0, inBattle = false, isDoubleBattle = false, mapGroup = 0, mapNum = 0, regionMapSectionId = -1,
    x = 0, y = 0, facingDirection = 0, partyCount = 0, party = emptyList(), battleMons = List(4) { EMPTY_BATTLE_MON },
    itemCount = 0, items = emptyList(),
)

/** Pokémon Black (USA / Europe, IRBO rev 0, sha1 26ad0b99…): every address from headless captures of the user's save
 * (the field, a text box, the X menu, the party screen, a Battle Subway battle). */
val GEN5_BLACK = Gen5Config(
    saveControlPtr = 0x020A7298,
    pokedexTables = POKEDEX_BLACK,
    gameSystemPtr = 0x02146248,
    // the encounter block's Repel counter: 250 after a Max Repel, one less a step
    repelStepsOff = 0x21B31,
    zonePlaces = mapPlacesBlack,
    zonePlaceIds = mapPlaceIdsBlack,
    battle = Gen5Battle(gameSystemPtr = 0x02146248),
    townMapPlaces = townMapPlacesBlack,
    townMapZones = townMapZonesBlack,
    libertyPass = 574,
)

/** Pokémon White (USA / Europe, IRAO rev 0): Black's code with its statics 0x20 higher (the SaveControl and GameSystem
 * pointers, found through the code that uses them in both carts and checked on White's headless captures of Black's
 * save, which it loads), Black's save layout, its own DEX text and Town Map. */
val GEN5_WHITE = GEN5_BLACK.copy(
    saveControlPtr = 0x020A72B8,
    pokedexTables = POKEDEX_WHITE,
    gameSystemPtr = 0x02146268,
    battle = Gen5Battle(gameSystemPtr = 0x02146268),
    townMapPlaces = townMapPlacesWhite,
    townMapZones = townMapZonesWhite,
)

/**
 * Pokémon Black 2 (USA / Europe, IREO rev 0, sha1 e51e6dfb…): a new build of Black's engine, every static moved but the
 * structs kept (each found again through the code that uses it in Black and Black 2, then checked in headless captures of
 * the player's saves: the field, the X menu, the party screen, the Pokédex, the bag, a wild battle on Route 21's water).
 * Its save is bigger and its blocks moved: event work 0x1FF00 (431 vars, 3064 flags), money / badges 0x21100 / 0x21104,
 * the Pokédex 0x21400, the Repel's steps 0x2192D (250 after a Max Repel); the party, bag, trainer and Location kept
 * Black's places.
 */
val GEN5_BLACK2 = GEN5_BLACK.copy(
    saveControlPtr = 0x0209A338,
    imageSize = 0x26000,
    pokedexTables = POKEDEX_BLACK2,
    gameSystemPtr = 0x021410F4,
    moneyOff = 0x21100,
    badgesOff = 0x21104,
    pokedexOff = 0x21400,
    varsFlagsOff = 0x1FF00,
    numVars = 0x1AF,
    numFlags = 0xBF8,
    repelStepsOff = 0x2192D,
    zonePlaces = mapPlacesBlack2,
    zonePlaceIds = mapPlaceIdsBlack2,
    battle = Gen5Battle(gameSystemPtr = 0x021410F4, battleOverlay = 167),
    townMapPlaces = townMapPlacesBlack2,
    townMapZones = townMapZonesBlack2,
    libertyPass = 0,
    firstArrival = GEN5_BW2_FIRST_ARRIVAL,
    arrivalCount = 62,
    regionalDex = unovaDexBlack2,
)

/** Pokémon White 2 (USA / Europe, IRDO rev 0): Black 2's code with its statics 0x40 higher (found through the code that
 * uses them in both carts, checked on White 2's headless captures of the player's save), Black 2's save layout, its own
 * DEX text and Town Map (White Forest where Black City is). */
val GEN5_WHITE2 = GEN5_BLACK2.copy(
    saveControlPtr = 0x0209A378,
    pokedexTables = POKEDEX_WHITE2,
    gameSystemPtr = 0x02141134,
    battle = Gen5Battle(gameSystemPtr = 0x02141134, battleOverlay = 167),
    townMapPlaces = townMapPlacesWhite2,
    townMapZones = townMapZonesWhite2,
)

/** The Gen 5 config for [kind], or null. */
fun gen5ConfigFor(kind: GameKind?): Gen5Config? = when (kind) {
    GameKind.BLACK -> GEN5_BLACK
    GameKind.WHITE -> GEN5_WHITE
    GameKind.BLACK2 -> GEN5_BLACK2
    GameKind.WHITE2 -> GEN5_WHITE2
    else -> null
}

/**
 * Uses one Repel ([itemId], [steps]) the way [useGen4Repel] does on Gen 4: one off its slot in the bag's first pocket
 * (an emptied slot to the pocket's end, the rest in order) and the steps into [Gen5Config.repelStepsOff], which the
 * field counts down by itself. Only with the field free ([fieldFree]) and no Repel still working. Emu thread.
 */
fun useGen5Repel(cfg: Gen5Config, calls: GameCalls, itemId: Int, steps: Int, fieldFree: Boolean): ItemUseOutcome {
    if (!fieldFree) return ItemUseOutcome(ItemUseResult.NOT_NOW)
    val repelOff = cfg.repelStepsOff ?: return ItemUseOutcome(ItemUseResult.FAILED)
    val img = gen5Image({ a, n -> calls.read(a, n) }, cfg)
    if (img < 0) return ItemUseOutcome(ItemUseResult.FAILED)
    if (u8(calls.read(img + repelOff, 1), 0) > 0) return ItemUseOutcome(ItemUseResult.STILL_ACTIVE)
    val pocket = img + cfg.bagOff   // the items pocket is the bag's first
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
        System.arraycopy(raw, (slot + 1) * 4, out, slot * 4, (size - slot - 1) * 4)
        for (i in (size - 1) * 4 until size * 4) out[i] = 0
    }
    if (!calls.write(pocket, out)) return ItemUseOutcome(ItemUseResult.FAILED)
    if (!calls.write(img + repelOff, byteArrayOf(steps.toByte()))) return ItemUseOutcome(ItemUseResult.FAILED)
    return ItemUseOutcome(ItemUseResult.USED, steps = steps)
}
