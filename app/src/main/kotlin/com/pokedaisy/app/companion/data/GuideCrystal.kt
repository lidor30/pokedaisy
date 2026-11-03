package com.pokedaisy.app.companion.data

/**
 * Retail Crystal's guide (rev 0, rev 1 and Australia share it): HERE and NEXT BOSS from GuideCrystalGen.kt, which
 * scripts/gen_crystal_guide.py reads out of all three builds of pret/pokecrystal (byte-identical to the carts) and checks
 * they agree, in Polished Crystal's line format ([Gen2WildTables]). [readCrystalProgress] is the save's side, the same
 * banks as Polished Crystal's: wEventFlags as the flags, the badges and the NPC trades as two more banks, the clock as a var.
 *
 * NEXT BOSS follows the scripts: each leader's `setevent EVENT_BEAT_*` after the battle; the Elite Four's room flags are
 * cleared whenever the INDIGO PLATEAU's Pokémon Center loads, so a room also counts as done once the League is won
 * (EVENT_BEAT_ELITE_FOUR, set by HallOfFame and never cleared).
 */

/** The companion's flag ids past wEventFlags: wJohtoBadges / wKantoBadges bit n, a NPC trade's wTradeFlags bit n, and the
 * clock as a var (wTimeOfDay) - the same numbers as Polished Crystal's. */
internal const val CR_BADGE_FLAG = 0x10000
internal const val CR_TRADE_FLAG = 0x20000
internal const val CR_VAR_TIME_OF_DAY = 0x10000

/** A Crystal RAM address as the readers address it: D000-DFFF in WRAM bank 1 ([wramx]), C000-CFFF on the bus. */
private fun crRam(addr: Int): Long = if (addr >= 0xD000) wramx(1, addr) else addr.toLong()

/** Where a Crystal-engine cart keeps what the guide reads (the generated constants). */
class CrystalGuideRam(val eventFlags: Int, val eventBytes: Int, val timeOfDay: Int, val badges: Int, val tradeFlags: Int, val tradeBytes: Int)

private val CRYSTAL_GUIDE_RAM = CrystalGuideRam(CR_EVENT_FLAGS, CR_EVENT_FLAG_BYTES, CR_TIME_OF_DAY, CR_BADGES, CR_TRADE_FLAGS, CR_TRADE_FLAG_BYTES)
internal val CRYSTAL_LEGACY_GUIDE_RAM = CrystalGuideRam(CL_EVENT_FLAGS, CL_EVENT_FLAG_BYTES, CL_TIME_OF_DAY, CL_BADGES, CL_TRADE_FLAGS, CL_TRADE_FLAG_BYTES)

/** The save's event flags, badges, trade flags and clock, as the guide reads them. */
fun readCrystalProgress(r: MemoryReader, ram: CrystalGuideRam = CRYSTAL_GUIDE_RAM): SaveProgress {
    val tod = r.readCoreMemory(crRam(ram.timeOfDay), 1)
    return SaveProgress(
        r.readCoreMemory(crRam(ram.eventFlags), ram.eventBytes), ByteArray(0),
        flagBanks = listOf(
            SaveBank(CR_BADGE_FLAG, 0, 16) to r.readCoreMemory(crRam(ram.badges), 2),
            SaveBank(CR_TRADE_FLAG, 0, ram.tradeBytes * 8) to r.readCoreMemory(crRam(ram.tradeFlags), ram.tradeBytes),
        ),
        varBanks = listOf(SaveBank(CR_VAR_TIME_OF_DAY, 0, 1) to byteArrayOf(tod[0], 0)),
    )
}

/** wTimeOfDay: [CR_MORN], [CR_DAY] or [CR_NITE]; DAY when unknown. */
fun SaveProgress?.crystalTimeOfDay(): Int = this?.variable(CR_VAR_TIME_OF_DAY)?.takeIf { it in CR_MORN..CR_NITE } ?: CR_DAY

/**
 * The wild Pokémon and the bosses' teams of [CR_WILD_RAW] / [CR_BOSSES_RAW], as the game picks them: grass and caves by
 * the time of day, surfing up to four levels over the slot's (ChooseWildEncounter's boost), the rods' time-of-day slots by
 * day (morning and day) or night, headbutt's common and rare trees.
 */
open class CrystalEngineWorld(
    wildRaw: List<String>,
    bossRaw: List<String>,
    private val grassOdds: IntArray,
    private val waterOdds: IntArray,
) : GuideWorld {
    private val wild by lazy { Gen2WildTables(wildRaw) }
    private val bosses by lazy { Gen2BossTables(bossRaw) }

    /** The trainer key NEXT BOSS uses for boss [name]. */
    fun trainer(name: String): Int = bosses.trainer(name)

    override fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters {
        val key = group shl 8 or num
        val tod = progress.crystalTimeOfDay()
        fun merge(rows: List<Triple<Int, IntRange, Double>>) = wild.merge(rows)
        fun one(s: Gen2WildTables.Slot, odds: Double) = Triple(s.species, s.level..s.level, odds)
        val grass = wild.grass[key]?.takeIf { it.first[tod] > 0 }?.second?.drop(tod * grassOdds.size)?.take(grassOdds.size)
            ?.mapIndexed { i, s -> one(s, grassOdds[i].toDouble()) }?.let(::merge).orEmpty()
        val water = wild.water[key]?.takeIf { it.first > 0 }?.second
            ?.mapIndexed { i, s -> Triple(s.species, s.level..s.level + 4, waterOdds[i].toDouble()) }?.let(::merge).orEmpty()
        val rods = wild.fishMaps[key]?.let { wild.fish[it] }.orEmpty().map { rod ->
            merge(rod.mapNotNull { s ->
                val mon = if (s.species != 0) s
                    else wild.timeFish[s.level]?.let { if (tod == CR_NITE) it.second else it.first } ?: return@mapNotNull null
                one(mon, s.odds * 100.0 / 256)
            })
        }
        fun trees(rows: List<Gen2WildTables.Slot>) = merge(rows.map { one(it, it.odds.toDouble()) })
        val tree = wild.treeMaps[key]?.let { wild.trees[it] }
        return MapEncounters(
            grass = grass,
            water = water,
            rockSmash = wild.rockMaps[key]?.let { wild.trees[it] }?.first?.let(::trees).orEmpty(),
            oldRod = rods.getOrElse(0) { emptyList() },
            goodRod = rods.getOrElse(1) { emptyList() },
            superRod = rods.getOrElse(2) { emptyList() },
            headbutt = tree?.first?.let(::trees).orEmpty(),
            headbuttRare = tree?.second?.let(::trees).orEmpty(),
        )
    }

    override fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon> =
        bosses.teams[trainer].orEmpty().map { TrainerMon(it.species, it.level, it.item, it.moves) }

    override fun clockNote(progress: SaveProgress?): String = when (progress.crystalTimeOfDay()) {
        CR_MORN -> "MORNING"
        CR_NITE -> "NIGHT"
        else -> "DAY"
    }
}

object CrystalWorld : CrystalEngineWorld(CR_WILD_RAW, CR_BOSSES_RAW, CR_GRASS_ODDS, CR_WATER_ODDS)

/** Crystal Legacy v1.3.1's: GuideCrystalLegacyGen.kt, from its own build, in the same format. */
object CrystalLegacyWorld : CrystalEngineWorld(CL_WILD_RAW, CL_BOSSES_RAW, CL_GRASS_ODDS, CL_WATER_ODDS)

private fun crLeague(own: Int): (SaveProgress) -> Boolean = { p -> p.flag(CR_EVENT_BEAT_ELITE_FOUR) || p.flag(own) }

private fun crBoss(title: String, where: String, done: Int, key: String, doneIf: ((SaveProgress) -> Boolean)? = null) =
    Boss(title, where, done, doneIf = doneIf) { CrystalWorld.trainer(key) }

private val BOSSES_CRYSTAL = listOf(
    crBoss("LEADER FALKNER", "VIOLET CITY GYM", CR_EVENT_BEAT_FALKNER, "FALKNER"),
    crBoss("LEADER BUGSY", "AZALEA TOWN GYM", CR_EVENT_BEAT_BUGSY, "BUGSY"),
    crBoss("LEADER WHITNEY", "GOLDENROD CITY GYM", CR_EVENT_BEAT_WHITNEY, "WHITNEY"),
    crBoss("LEADER MORTY", "ECRUTEAK CITY GYM", CR_EVENT_BEAT_MORTY, "MORTY"),
    crBoss("LEADER CHUCK", "CIANWOOD CITY GYM", CR_EVENT_BEAT_CHUCK, "CHUCK"),
    crBoss("LEADER JASMINE", "OLIVINE CITY GYM", CR_EVENT_BEAT_JASMINE, "JASMINE"),
    crBoss("LEADER PRYCE", "MAHOGANY TOWN GYM", CR_EVENT_BEAT_PRYCE, "PRYCE"),
    crBoss("LEADER CLAIR", "BLACKTHORN CITY GYM", CR_EVENT_BEAT_CLAIR, "CLAIR"),
    crBoss("ELITE FOUR WILL", "POKéMON LEAGUE", 0, "WILL", crLeague(CR_EVENT_BEAT_ELITE_4_WILL)),
    crBoss("ELITE FOUR KOGA", "POKéMON LEAGUE", 0, "KOGA", crLeague(CR_EVENT_BEAT_ELITE_4_KOGA)),
    crBoss("ELITE FOUR BRUNO", "POKéMON LEAGUE", 0, "BRUNO", crLeague(CR_EVENT_BEAT_ELITE_4_BRUNO)),
    crBoss("ELITE FOUR KAREN", "POKéMON LEAGUE", 0, "KAREN", crLeague(CR_EVENT_BEAT_ELITE_4_KAREN)),
    crBoss("CHAMPION LANCE", "POKéMON LEAGUE", CR_EVENT_BEAT_ELITE_FOUR, "LANCE"),
    crBoss("LEADER BROCK", "PEWTER CITY GYM", CR_EVENT_BEAT_BROCK, "BROCK"),
    crBoss("LEADER MISTY", "CERULEAN CITY GYM", CR_EVENT_BEAT_MISTY, "MISTY"),
    crBoss("LEADER LT. SURGE", "VERMILION CITY GYM", CR_EVENT_BEAT_LTSURGE, "LT_SURGE"),
    crBoss("LEADER ERIKA", "CELADON CITY GYM", CR_EVENT_BEAT_ERIKA, "ERIKA"),
    crBoss("LEADER JANINE", "FUCHSIA CITY GYM", CR_EVENT_BEAT_JANINE, "JANINE"),
    crBoss("LEADER SABRINA", "SAFFRON CITY GYM", CR_EVENT_BEAT_SABRINA, "SABRINA"),
    crBoss("LEADER BLAINE", "SEAFOAM ISLANDS GYM", CR_EVENT_BEAT_BLAINE, "BLAINE"),
    crBoss("LEADER BLUE", "VIRIDIAN CITY GYM", CR_EVENT_BEAT_BLUE, "BLUE"),
    // His script sets no flag of its own: he's hidden from a new game until the Hall of Fame shows him, and gone again
    // (EVENT_RED_IN_MT_SILVER set) once beaten.
    crBoss("RED", "MT. SILVER", 0, "RED") { p -> p.flag(CR_EVENT_BEAT_ELITE_FOUR) && p.flag(CR_EVENT_RED_IN_MT_SILVER) },
)

private val CRYSTAL_TIPS = page(
    "TIPS",
    section(
        "WILD POKéMON",
        entry(
            "Why did the POKéMON on this route change?",
            "The game's clock has three times of day: morning from 4 AM, day from 10 AM and night from 6 PM. " +
                "Grass and caves have a list for each, and HERE shows the one for the time your game's clock says.",
            hint = "Look at the clock.",
        ),
        entry(
            "Why do some trees never have the rare POKéMON?",
            "Each tree you HEADBUTT is common, rare or almost empty, picked from where the tree stands and your " +
                "trainer ID. HERE lists both tables; try every tree on a route to find the rare ones.",
            hint = "It has to do with your trainer ID.",
        ),
    ),
)

internal val GUIDE_CRYSTAL = GameGuide(
    verified = false,
    bosses = BOSSES_CRYSTAL,
    world = CrystalWorld,
    pages = listOf(CRYSTAL_TIPS),
)

private fun clLeague(own: Int): (SaveProgress) -> Boolean = { p -> p.flag(CL_EVENT_BEAT_ELITE_FOUR) || p.flag(own) }

private fun clBoss(title: String, where: String, done: Int, key: String, doneIf: ((SaveProgress) -> Boolean)? = null) =
    Boss(title, where, done, doneIf = doneIf) { CrystalLegacyWorld.trainer(key) }

/**
 * A gym Crystal Legacy lets you take in any order, so it scales by badge count (VAR_BADGES, Johto and Kanto's): 5 owned brings
 * the team generated as `<key>_6`, 6 `<key>_7`, any other count [key]'s.
 */
private fun clScaledBoss(title: String, where: String, done: Int, key: String) = Boss(title, where, done) { p ->
    CrystalLegacyWorld.trainer(when ((0 until 16).count { p.flag(CR_BADGE_FLAG + it) }) {
        5 -> "${key}_6"
        6 -> "${key}_7"
        else -> key
    })
}

/** Crystal's order; each boss's second team in its data is a rematch, once beaten (NEXT BOSS has moved on by then). */
private val BOSSES_CRYSTAL_LEGACY = listOf(
    clBoss("LEADER FALKNER", "VIOLET CITY GYM", CL_EVENT_BEAT_FALKNER, "FALKNER"),
    clBoss("LEADER BUGSY", "AZALEA TOWN GYM", CL_EVENT_BEAT_BUGSY, "BUGSY"),
    clBoss("LEADER WHITNEY", "GOLDENROD CITY GYM", CL_EVENT_BEAT_WHITNEY, "WHITNEY"),
    clBoss("LEADER MORTY", "ECRUTEAK CITY GYM", CL_EVENT_BEAT_MORTY, "MORTY"),
    clScaledBoss("LEADER CHUCK", "CIANWOOD CITY GYM", CL_EVENT_BEAT_CHUCK, "CHUCK"),
    clScaledBoss("LEADER JASMINE", "OLIVINE CITY GYM", CL_EVENT_BEAT_JASMINE, "JASMINE"),
    clScaledBoss("LEADER PRYCE", "MAHOGANY TOWN GYM", CL_EVENT_BEAT_PRYCE, "PRYCE"),
    clBoss("LEADER CLAIR", "BLACKTHORN CITY GYM", CL_EVENT_BEAT_CLAIR, "CLAIR"),
    clBoss("ELITE FOUR WILL", "POKéMON LEAGUE", 0, "WILL", clLeague(CL_EVENT_BEAT_ELITE_4_WILL)),
    clBoss("ELITE FOUR KOGA", "POKéMON LEAGUE", 0, "KOGA", clLeague(CL_EVENT_BEAT_ELITE_4_KOGA)),
    clBoss("ELITE FOUR BRUNO", "POKéMON LEAGUE", 0, "BRUNO", clLeague(CL_EVENT_BEAT_ELITE_4_BRUNO)),
    clBoss("ELITE FOUR KAREN", "POKéMON LEAGUE", 0, "KAREN", clLeague(CL_EVENT_BEAT_ELITE_4_KAREN)),
    clBoss("CHAMPION LANCE", "POKéMON LEAGUE", CL_EVENT_BEAT_ELITE_FOUR, "LANCE"),
    clBoss("LEADER BROCK", "PEWTER CITY GYM", CL_EVENT_BEAT_BROCK, "BROCK"),
    clBoss("LEADER MISTY", "CERULEAN CITY GYM", CL_EVENT_BEAT_MISTY, "MISTY"),
    clBoss("LEADER LT. SURGE", "VERMILION CITY GYM", CL_EVENT_BEAT_LTSURGE, "LT_SURGE"),
    clBoss("LEADER ERIKA", "CELADON CITY GYM", CL_EVENT_BEAT_ERIKA, "ERIKA"),
    clBoss("LEADER JANINE", "FUCHSIA CITY GYM", CL_EVENT_BEAT_JANINE, "JANINE"),
    clBoss("LEADER SABRINA", "SAFFRON CITY GYM", CL_EVENT_BEAT_SABRINA, "SABRINA"),
    clBoss("LEADER BLAINE", "SEAFOAM ISLANDS GYM", CL_EVENT_BEAT_BLAINE, "BLAINE"),
    clBoss("LEADER BLUE", "VIRIDIAN CITY GYM", CL_EVENT_BEAT_BLUE, "BLUE"),
    // Its own flag: Legacy clears EVENT_RED_IN_MT_SILVER when he's beaten, the opposite of Crystal.
    clBoss("RED", "MT. SILVER", CL_EVENT_DEFEATED_RED, "RED"),
)

internal val GUIDE_CRYSTAL_LEGACY = GameGuide(
    verified = false,
    bosses = BOSSES_CRYSTAL_LEGACY,
    world = CrystalLegacyWorld,
    pages = listOf(CRYSTAL_TIPS),
)
