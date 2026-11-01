package com.pokedaisy.app.companion.data

/**
 * The month (1-12) for Black's seasons, as a var past the save's own (like [GEN4_VAR_HOUR]): 0 / missing = use the
 * device's clock. The DS clock is the device's, so the two agree unless a reader has the game's own.
 */
const val GEN5_VAR_MONTH = 0x10001

/**
 * Pokémon Black's, White's, Black 2's and White 2's GUIDE data: HERE's wild Pokémon by zone and NEXT BOSS's teams, read
 * from each cart by scripts/gen_gen5_guide.py (GuideGen5BlackGen.kt, GuideGen5WhiteGen.kt, GuideGen5Black2Gen.kt,
 * GuideGen5White2Gen.kt: numbers only). A Gen 5 save's map is a zone
 * (the reader's mapGroup 0, mapNum the zone id).
 *
 * The seasons are the month's: January, May and September are spring, then summer, autumn and winter in turn
 * (season = (month - 1) % 4). A place whose Pokémon change with them has four wild sets (spring first; the Moor of
 * Icirrus's grass, for one, is empty in the fourth), everywhere else one for the whole year.
 *
 * Gen 5's ways to meet a wild Pokémon map onto [MapEncounters] as: grass (and caves' floors) = [MapEncounters.grass],
 * surfing = water, fishing = superRod (Black has one rod, the SUPER ROD). Dark grass, rustling grass, rippling water and
 * rippling fishing spots have no place there: [extras] gives them. The slot odds are the land / water ones Gen 3 and 4
 * use, which Gen 5 kept (12 grass slots 20 / 20 / 10 / 10 / 10 / 10 / 5 / 5 / 4 / 4 / 1 / 1, 5 water slots 60 / 30 / 5
 * / 4 / 1 - fishing too).
 */
class Gen5World(wildRaw: List<String>, bossesRaw: List<String>) : GuideWorld {
    private class Slot(val min: Int, val max: Int, val species: Int)

    /** One season's block: each way's rate (0 = none) and slots. */
    private class EncSet(val rates: IntArray, val slots: List<List<Slot>>)

    private class Wild(val zones: Map<Int, Int>, val sets: Map<Int, Array<EncSet?>>)

    /** Dark grass, rustling grass, rippling water and rippling fishing spots: the ways [MapEncounters] has no place for. */
    data class Extras(
        val darkGrass: List<EncounterSlot>,
        val rustlingGrass: List<EncounterSlot>,
        val ripplingWater: List<EncounterSlot>,
        val ripplingFishing: List<EncounterSlot>,
    ) {
        val isEmpty get() = darkGrass.isEmpty() && rustlingGrass.isEmpty() && ripplingWater.isEmpty() && ripplingFishing.isEmpty()
    }

    private val wildRows = wildRaw
    private val wild: Wild by lazy {
        val zones = HashMap<Int, Int>()
        val sets = HashMap<Int, Array<EncSet?>>()
        fun slots(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { p ->
            val (lv, sp) = p.split(':')
            val (lo, hi) = lv.split('-').map { it.toInt() }
            Slot(lo, hi, sp.toInt())
        }
        wildRows.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('|')
            when (f[0]) {
                "M" -> zones[f[1].toInt()] = f[2].toInt()
                "E" -> {
                    val set = EncSet(IntArray(METHODS) { f[3 + 2 * it].toInt() }, List(METHODS) { slots(f[4 + 2 * it]) })
                    val seasons = sets.getOrPut(f[1].toInt()) { arrayOfNulls(4) }
                    when (val season = f[2].toInt()) {
                        -1 -> seasons.fill(set)
                        else -> seasons[season] = set
                    }
                }
            }
        }
        Wild(zones, sets)
    }

    private val bossRows = bossesRaw
    /** Trainer id -> its team; boss key -> trainer id. */
    private val bosses: Pair<Map<Int, List<TrainerMon>>, Map<String, Int>> by lazy {
        val teams = HashMap<Int, List<TrainerMon>>()
        val keys = HashMap<String, Int>()
        bossRows.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('|')
            val id = f[1].toInt()
            keys[f[0]] = id
            teams[id] = f[2].split(';').map { m ->
                val p = m.split(' ')
                TrainerMon(p[1].toInt(), p[0].toInt(), p[2].toInt(), p.getOrElse(3) { "" }.split(',').filter { it.isNotEmpty() }.map { it.toInt() })
            }
        }
        teams to keys
    }

    /** NEXT BOSS's trainer id for boss [key] (the generator's: CHILI, LENORA, SHAUNTAL, N, ...). */
    fun trainer(key: String): Int = bosses.second[key] ?: error("no boss $key")

    /** Every zone's grass and surf rates for [season] as the cart has them, [zone, grass, surf] triples (as
     *  [Gen4World.wildRates] gives Gen 4's). */
    fun wildRates(season: Int = season(null)): IntArray = wild.zones.flatMap { (zone, set) ->
        val s = wild.sets[set]?.getOrNull(season) ?: return@flatMap emptyList()
        listOf(zone, s.rates[GRASS], s.rates[SURF])
    }.toIntArray()

    /** Every zone's seven rates for [season] as the cart has them, as WILD ENCOUNTERS back on writes them into the zone's
     *  encounter table (nds/pk_nds_field.cpp's Gen 5 hold): [zone, rates 0-3, rates 4-6] triples, a byte a rate. */
    fun wildRateWords(season: Int = season(null)): IntArray = wild.zones.flatMap { (zone, set) ->
        val r = wild.sets[set]?.getOrNull(season)?.rates ?: return@flatMap emptyList()
        fun b(i: Int) = r.getOrElse(i) { 0 } and 0xFF
        listOf(zone, b(0) or (b(1) shl 8) or (b(2) shl 16) or (b(3) shl 24), b(4) or (b(5) shl 8) or (b(6) shl 16))
    }.toIntArray()

    private fun set(zone: Int, progress: SaveProgress?): EncSet? = wild.zones[zone]?.let { wild.sets[it]?.getOrNull(season(progress)) }

    private fun merge(set: EncSet, method: Int, odds: List<Int>): List<EncounterSlot> =
        if (set.rates[method] == 0) emptyList()
        else set.slots[method].mapIndexed { i, s -> Triple(s, odds.getOrElse(i) { 0 }, i) }
            .filter { it.first.species != 0 }
            .groupBy { it.first.species }
            .map { (species, g) -> EncounterSlot(species, g.minOf { it.first.min }, g.maxOf { it.first.max }, g.sumOf { it.second }) }
            .sortedByDescending { it.percent }

    override fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters {
        val set = set(num, progress) ?: return MapEncounters(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        return MapEncounters(
            grass = merge(set, GRASS, LAND_ODDS),
            water = merge(set, SURF, WATER_ODDS),
            rockSmash = emptyList(),
            oldRod = emptyList(),
            goodRod = emptyList(),
            superRod = merge(set, FISH, WATER_ODDS),
        )
    }

    /** Zone [zone]'s dark grass, rustling grass and rippling spots now. */
    fun extras(zone: Int, progress: SaveProgress?): Extras {
        val set = set(zone, progress) ?: return Extras(emptyList(), emptyList(), emptyList(), emptyList())
        return Extras(
            darkGrass = merge(set, DARK_GRASS, LAND_ODDS),
            rustlingGrass = merge(set, RUSTLING_GRASS, LAND_ODDS),
            ripplingWater = merge(set, RIPPLING_WATER, WATER_ODDS),
            ripplingFishing = merge(set, RIPPLING_FISH, WATER_ODDS),
        )
    }

    /** Whether zone [zone]'s wild Pokémon change with the seasons. */
    fun seasonal(zone: Int): Boolean = wild.zones[zone]?.let { wild.sets[it] }?.let { s -> s.distinct().size > 1 } == true

    override fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon> = bosses.first[trainer].orEmpty()

    override fun clockNote(progress: SaveProgress?): String = SEASONS[season(progress)]

    /** The season for [progress]'s month ([GEN5_VAR_MONTH]), else the device's: 0 spring, 1 summer, 2 autumn, 3 winter. */
    fun season(progress: SaveProgress?): Int {
        val month = progress?.variable(GEN5_VAR_MONTH)?.takeIf { it in 1..12 } ?: java.time.LocalDate.now().monthValue
        return (month - 1) % 4
    }

    private companion object {
        // The block's ways, in the cart's order.
        const val GRASS = 0
        const val DARK_GRASS = 1
        const val RUSTLING_GRASS = 2
        const val SURF = 3
        const val RIPPLING_WATER = 4
        const val FISH = 5
        const val RIPPLING_FISH = 6
        const val METHODS = 7
        val SEASONS = listOf("SPRING", "SUMMER", "AUTUMN", "WINTER")
        val LAND_ODDS = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
        val WATER_ODDS = listOf(60, 30, 5, 4, 1)
    }
}

val BLACK_WORLD = Gen5World(BLACK_WILD_RAW, BLACK_BOSSES_RAW)
val WHITE_WORLD = Gen5World(WHITE_WILD_RAW, WHITE_BOSSES_RAW)
val BLACK2_WORLD = Gen5World(BLACK2_WILD_RAW, BLACK2_BOSSES_RAW)
val WHITE2_WORLD = Gen5World(WHITE2_WILD_RAW, WHITE2_BOSSES_RAW)

/** A boss NEXT BOSS shows: done once [done] is set, or by [doneIf]; its team is [world]'s for boss [key]. */
internal fun gen5Boss(world: Gen5World, title: String, where: String, key: String, done: Int, doneIf: ((SaveProgress) -> Boolean)? = null): Boss =
    Boss(title, where, done, doneIf = doneIf) { world.trainer(key) }
