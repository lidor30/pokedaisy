package com.pokedaisy.app.companion.data

/**
 * Polished Crystal 3.2.x's guide, the first Game Boy one. Its live pages don't read the GBA tables [GuideTables] knows:
 * the wild Pokémon, the bosses' teams and the area data are generated from every release build by
 * scripts/gen_polished_crystal_guide.py (GuidePolishedCrystalGen.kt: each build's ROM read through its own .sym, all 16
 * checked to agree, the faithful builds with a few boss moves of their own), and [PolishedCrystalWorld] picks from them
 * what the save would meet - the time of day on its clock, the levels its badges set. [readPolishedProgress] is the
 * save's side: wEventFlags as the flags, the badges and the NPC trades as two more banks, the clock as a var.
 *
 * NEXT BOSS follows the scripts (pret-style maps in the source): each leader's `loadtrainer` and the `setevent
 * EVENT_BEAT_*` after it; the Elite Four and LANCE bring their second team once you hold all 16 badges (WillsRoom.asm's
 * VAR_BADGES check), and a League won sets EVENT_BEAT_ELITE_FOUR (with 16 badges, EVENT_BEAT_ELITE_FOUR_AGAIN too:
 * HallOfFame.asm) - the rooms' own flags are cleared again at the next visit (IndigoPlateauPokecenter1F.asm). RED's
 * battle ends with the MYSTICTICKET, the only flag his script sets.
 */

/** The companion's flag ids for the banks past wEventFlags: wJohtoBadges / wKantoBadges bit n, a NPC trade's
 * wTradeFlags bit n (the generator writes the trades' flags so), and the clock as a var (wTimeOfDay). */
internal const val PC_BADGE_FLAG = 0x10000
internal const val PC_TRADE_FLAG = 0x20000
internal const val PC_VAR_TIME_OF_DAY = 0x10000

/** The save's event flags, badges, trade flags and clock (WRAM bank 1), as the guide reads them. */
fun readPolishedProgress(r: MemoryReader): SaveProgress {
    val tod = r.readCoreMemory(wramx(1, PC_TIME_OF_DAY), 1)
    return SaveProgress(
        r.readCoreMemory(wramx(1, PC_EVENT_FLAGS), PC_EVENT_FLAG_BYTES), ByteArray(0),
        flagBanks = listOf(
            SaveBank(PC_BADGE_FLAG, 0, 16) to r.readCoreMemory(wramx(1, PC_BADGES), 2),
            SaveBank(PC_TRADE_FLAG, 0, PC_TRADE_FLAG_BYTES * 8) to r.readCoreMemory(wramx(1, PC_TRADE_FLAGS), PC_TRADE_FLAG_BYTES),
        ),
        varBanks = listOf(SaveBank(PC_VAR_TIME_OF_DAY, 0, 1) to byteArrayOf(tod[0], 0)),
    )
}

/** The species HERE's seen / caught check goes by: a Polished Crystal variant form counts as its species, as the dex
 * flags do ([readGen2PokedexState] reads one bit per species); every other species is itself. */
fun guideDexSpecies(species: Int): Int =
    if (activeGame != GameKind.POLISHED_CRYSTAL || species <= POLISHED_NUM_SPECIES) species
    else polishedVariantForms.getOrNull(species - POLISHED_NUM_SPECIES - 1)?.let { it shr 8 } ?: species

/** Badges held, Johto's and Kanto's (CountSetBits over wBadges). */
fun SaveProgress.polishedBadges(): Int = (0 until 16).count { flag(PC_BADGE_FLAG + it) }

/** wTimeOfDay: [PC_MORN], [PC_DAY], [PC_NITE] or [PC_EVE]; DAY when unknown. */
fun SaveProgress?.polishedTimeOfDay(): Int = this?.variable(PC_VAR_TIME_OF_DAY)?.takeIf { it in 0..3 } ?: PC_DAY

/**
 * The wild Pokémon and the bosses' teams of [PC_WILD_RAW] / [PC_BOSSES_RAW], as the game picks them: grass by the time
 * of day (the evening uses the night's list, GetTimeOfDayNotEve), surfing one to four levels over the slot's at times
 * (ChooseWildEncounter's boost), rods with CORSOLA by day and STARYU at night in species 0's slot, and a level past
 * MAX_LEVEL counted from BadgeBaseLevels (AdjustLevelForBadges).
 */
object PolishedCrystalWorld : GuideWorld {
    private val wild by lazy { Gen2WildTables(PC_WILD_RAW) }
    private val normalBosses by lazy { Gen2BossTables(PC_BOSSES_RAW) }
    private val faithfulBosses by lazy { normalBosses.teams + Gen2BossTables(PC_BOSSES_FAITHFUL_RAW).teams }

    /** The trainer key NEXT BOSS uses for boss [name] (the generator's key: FALKNER, WILL_2, ...). */
    fun trainer(name: String): Int = normalBosses.trainer(name)

    /** AdjustLevelForBadges: a level up to MAX_LEVEL as it is; past it, BadgeBaseLevels[badges] + (level - LEVEL_FROM_BADGES),
     * kept within 2..99. */
    fun level(raw: Int, badges: Int): Int {
        if (raw <= 100) return raw
        val v = PC_BADGE_BASE_LEVELS[badges.coerceIn(0, PC_BADGE_BASE_LEVELS.size - 1)] + (raw - PC_LEVEL_FROM_BADGES)
        return when {
            v < 2 -> 2
            v < 100 -> v
            v < PC_LEVEL_FROM_BADGES -> 99
            else -> 2
        }
    }

    override fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters {
        val key = group shl 8 or num
        val badges = progress?.polishedBadges() ?: 0
        val tod = progress.polishedTimeOfDay()
        val grassTime = if (tod == PC_EVE) PC_NITE else tod
        fun merge(rows: List<Triple<Int, IntRange, Double>>) = wild.merge(rows)
        val grass = wild.grass[key]?.takeIf { it.first[grassTime] > 0 }?.second?.drop(grassTime * GRASS_ODDS.size)?.take(GRASS_ODDS.size)
            ?.mapIndexed { i, s -> Triple(s.species, level(s.level, badges).let { it..it }, GRASS_ODDS[i].toDouble()) }
            ?.let(::merge).orEmpty()
        val water = wild.water[key]?.takeIf { it.first > 0 }?.second
            ?.mapIndexed { i, s -> Triple(s.species, level(s.level, badges)..level(s.level + 4, badges), WATER_ODDS[i].toDouble()) }
            ?.let(::merge).orEmpty()
        val rods = wild.fishMaps[key]?.let { wild.fish[it] }.orEmpty().map { rod ->
            merge(rod.map { s ->
                val species = if (s.species != 0) s.species else if (tod == PC_MORN || tod == PC_DAY) PC_SPECIES_CORSOLA else PC_SPECIES_STARYU
                Triple(species, level(s.level, badges).let { it..it }, s.odds * 100.0 / 256)
            })
        }
        fun trees(rows: List<Gen2WildTables.Slot>) = merge(rows.map { Triple(it.species, level(it.level, badges).let { l -> l..l }, it.odds.toDouble()) })
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

    override fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon> {
        val badges = progress?.polishedBadges() ?: 0
        val table = if (activeGen2.polishedFaithful) faithfulBosses else normalBosses.teams
        return table[trainer].orEmpty().map { TrainerMon(it.species, level(it.level, badges), it.item, it.moves) }
    }

    override fun clockNote(progress: SaveProgress?): String = when (progress.polishedTimeOfDay()) {
        PC_MORN -> "MORNING"
        PC_NITE -> "NIGHT"
        PC_EVE -> "EVENING"
        else -> "DAY"
    }

    // GrassMonProbTable / WaterMonProbTable (data/wild/probabilities.asm).
    private val GRASS_ODDS = listOf(30, 30, 20, 10, 5, 3, 2)
    private val WATER_ODDS = listOf(60, 30, 10)
}

private fun pcLeague(own: Int): (SaveProgress) -> Boolean = { p -> p.flag(PC_EVENT_BEAT_ELITE_FOUR) || p.flag(own) }

/** The rematch: with all 16 badges the rooms send their second teams, until a League run is won that way. */
private fun pcRematch(own: Int): (SaveProgress) -> Boolean =
    { p -> p.flag(PC_EVENT_BEAT_ELITE_FOUR_AGAIN) || (p.polishedBadges() == 16 && p.flag(own)) }

private fun pcBoss(title: String, where: String, done: Int, key: String, doneIf: ((SaveProgress) -> Boolean)? = null) =
    Boss(title, where, done, doneIf = doneIf) { PolishedCrystalWorld.trainer(key) }

private val BOSSES_POLISHED_CRYSTAL = listOf(
    pcBoss("LEADER FALKNER", "VIOLET CITY GYM", PC_EVENT_BEAT_FALKNER, "FALKNER"),
    pcBoss("LEADER BUGSY", "AZALEA TOWN GYM", PC_EVENT_BEAT_BUGSY, "BUGSY"),
    pcBoss("LEADER WHITNEY", "GOLDENROD CITY GYM", PC_EVENT_BEAT_WHITNEY, "WHITNEY"),
    pcBoss("LEADER MORTY", "ECRUTEAK CITY GYM", PC_EVENT_BEAT_MORTY, "MORTY"),
    pcBoss("LEADER CHUCK", "CIANWOOD CITY GYM", PC_EVENT_BEAT_CHUCK, "CHUCK"),
    pcBoss("LEADER JASMINE", "OLIVINE CITY GYM", PC_EVENT_BEAT_JASMINE, "JASMINE"),
    pcBoss("LEADER PRYCE", "MAHOGANY TOWN GYM", PC_EVENT_BEAT_PRYCE, "PRYCE"),
    pcBoss("LEADER CLAIR", "BLACKTHORN CITY GYM", PC_EVENT_BEAT_CLAIR, "CLAIR"),
    pcBoss("ELITE FOUR WILL", "POKéMON LEAGUE", 0, "WILL", pcLeague(PC_EVENT_BEAT_ELITE_4_WILL)),
    pcBoss("ELITE FOUR KOGA", "POKéMON LEAGUE", 0, "KOGA", pcLeague(PC_EVENT_BEAT_ELITE_4_KOGA)),
    pcBoss("ELITE FOUR BRUNO", "POKéMON LEAGUE", 0, "BRUNO", pcLeague(PC_EVENT_BEAT_ELITE_4_BRUNO)),
    pcBoss("ELITE FOUR KAREN", "POKéMON LEAGUE", 0, "KAREN", pcLeague(PC_EVENT_BEAT_ELITE_4_KAREN)),
    pcBoss("CHAMPION LANCE", "POKéMON LEAGUE", PC_EVENT_BEAT_ELITE_FOUR, "LANCE"),
    pcBoss("LEADER BROCK", "PEWTER CITY GYM", PC_EVENT_BEAT_BROCK, "BROCK"),
    pcBoss("LEADER MISTY", "CERULEAN CITY GYM", PC_EVENT_BEAT_MISTY, "MISTY"),
    pcBoss("LEADER LT. SURGE", "VERMILION CITY GYM", PC_EVENT_BEAT_LTSURGE, "LT_SURGE"),
    pcBoss("LEADER ERIKA", "CELADON CITY GYM", PC_EVENT_BEAT_ERIKA, "ERIKA"),
    pcBoss("LEADER JANINE", "FUCHSIA CITY GYM", PC_EVENT_BEAT_JANINE, "JANINE"),
    pcBoss("LEADER SABRINA", "SAFFRON CITY GYM", PC_EVENT_BEAT_SABRINA, "SABRINA"),
    pcBoss("LEADER BLAINE", "SEAFOAM ISLANDS GYM", PC_EVENT_BEAT_BLAINE, "BLAINE"),
    pcBoss("LEADER BLUE", "VIRIDIAN CITY GYM", PC_EVENT_BEAT_BLUE, "BLUE"),
    pcBoss("ELITE FOUR WILL, REMATCH", "POKéMON LEAGUE, all 16 badges", 0, "WILL_2", pcRematch(PC_EVENT_BEAT_ELITE_4_WILL)),
    pcBoss("ELITE FOUR KOGA, REMATCH", "POKéMON LEAGUE, all 16 badges", 0, "KOGA_2", pcRematch(PC_EVENT_BEAT_ELITE_4_KOGA)),
    pcBoss("ELITE FOUR BRUNO, REMATCH", "POKéMON LEAGUE, all 16 badges", 0, "BRUNO_2", pcRematch(PC_EVENT_BEAT_ELITE_4_BRUNO)),
    pcBoss("ELITE FOUR KAREN, REMATCH", "POKéMON LEAGUE, all 16 badges", 0, "KAREN_2", pcRematch(PC_EVENT_BEAT_ELITE_4_KAREN)),
    pcBoss("CHAMPION LANCE, REMATCH", "POKéMON LEAGUE, all 16 badges", PC_EVENT_BEAT_ELITE_FOUR_AGAIN, "LANCE_2"),
    pcBoss("RED", "SILVER CAVE", PC_EVENT_GOT_MYSTICTICKET_FROM_RED, "RED"),
)

internal val GUIDE_POLISHED_CRYSTAL = GameGuide(
    verified = false,
    bosses = BOSSES_POLISHED_CRYSTAL,
    world = PolishedCrystalWorld,
    pages = listOf(
        page(
            "TIPS",
            section(
                "WILD POKéMON",
                entry(
                    "Why did the POKéMON on this route change?",
                    "The game has four times of day on its clock: morning from 5 AM, day from 9 AM, evening from 5 PM and " +
                        "night from 9 PM. Grass and caves have a list for the morning, the day and the night, and the " +
                        "evening uses the night's. HERE shows the list for the time your game's clock says.",
                    hint = "Look at the clock.",
                ),
                entry(
                    "Why are the wild POKéMON here close to my level?",
                    "Some side areas set their wild POKéMON's levels by how many badges you have, among them the coasts " +
                        "off ROUTE 32 to 35, QUIET CAVE, YELLOW FOREST and the ORANGE ISLANDS. HERE already shows the " +
                        "levels for the badges you hold.",
                    hint = "It depends on your badges.",
                ),
                entry(
                    "Why does surfing find higher levels than HERE's slot says?",
                    "While surfing, a wild POKéMON is often one to four levels over its slot's level. HERE's levels for " +
                        "SURFING include that.",
                ),
                entry(
                    "Why do some trees never have the rare POKéMON?",
                    "Each tree you HEADBUTT is common, rare or almost empty, picked from where the tree stands and your " +
                        "trainer ID. HERE lists both tables; try every tree on a route to find the rare ones.",
                    hint = "It has to do with your trainer ID.",
                ),
                entry(
                    "Which rod catches what at the shore?",
                    "On the shore's GOOD ROD and SUPER ROD lists one slot changes with the clock: CORSOLA in the morning " +
                        "and day, STARYU in the evening and at night. HERE shows the one for now.",
                ),
            ),
            section(
                "AFTER THE LEAGUE",
                entry(
                    "Is the POKéMON LEAGUE harder the second time?",
                    "With all 16 badges, the ELITE FOUR and LANCE use much stronger teams. NEXT BOSS lists them after the " +
                        "Kanto gyms.",
                    hint = "Come back with every badge.",
                ),
                entry(
                    "What does RED give me?",
                    "After the battle in SILVER CAVE, RED hands you the MYSTICTICKET. Show it at the ferry gate in " +
                        "VERMILION CITY for a trip to an island with one more trainer waiting at its top.",
                    hint = "A key item.",
                    have = Have.Item(0x200 + PC_KEY_MYSTICTICKET),
                ),
            ),
        ),
    ),
)
