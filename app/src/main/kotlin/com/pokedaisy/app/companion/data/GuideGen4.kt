package com.pokedaisy.app.companion.data

/**
 * The DS games' GUIDE data: HERE's wild Pokémon by map header and NEXT BOSS's teams, generated from pret's decomps by
 * scripts/gen_gen4_guide.py (GuideGen4PlatinumGen.kt, GuideGen4HeartGoldGen.kt: numbers only), picked for the save's
 * map and the DS clock's hour ([GEN4_VAR_HOUR], [readGen4Progress]). A DS game's map header is its map (the reader's
 * mapGroup is 0, mapNum the header).
 *
 * The time of day is each game's own (rtc.c / gf_rtc.c): morning 4:00-9:59, day 10:00-19:59 (HG / SS's evening counts
 * as day for wild Pokémon), night 20:00-3:59. Platinum's grass lists are the morning's, its slots 3 and 4 (10% each)
 * taking the set's day or night pair (WildEncounters_ReplaceTimedEncounters); HG / SS give every grass slot a species
 * for each time of day, and at night a GOOD ROD's slot 4 and a SUPER ROD's slot 2 catch the set's night fish
 * (EncSlotArray_Update_NightFishing). Swarms, the Poké Radar, the radio's Hoenn / Sinnoh sounds and the GBA cart in
 * Platinum's second slot are left out: HERE lists what the grass gives on a plain step.
 */
class Gen4World(
    wildRaw: List<String>,
    bossesRaw: List<String>,
    private val heartGold: Boolean,
) : GuideWorld {
    private class Slot(val min: Int, val max: Int, val species: IntArray)

    private class EncSet(
        val grassRate: Int, val grass: List<Slot>,
        /** Platinum: slots 3 / 4's day and night species. */
        val day: IntArray, val night: IntArray,
        val surfRate: Int, val surf: List<Slot>,
        val rockRate: Int, val rock: List<Slot>,
        val rods: List<Pair<Int, List<Slot>>>,
        val nightFish: Int,
    )

    private class Wild(val maps: Map<Int, Int>, val sets: Map<Int, EncSet>)

    private val wildRows = wildRaw
    private val wild: Wild by lazy {
        val maps = HashMap<Int, Int>()
        val sets = HashMap<Int, EncSet>()
        fun ints(s: String) = s.split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        // "level:species" (Platinum's grass), "level:morn,day,nite" (HG / SS's grass), "min-max:species" (the rest).
        fun slots(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { p ->
            val (lv, sp) = p.split(':')
            val range = lv.split('-').map { it.toInt() }
            Slot(range.first(), range.last(), ints(sp))
        }
        wildRows.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('|')
            when (f[0]) {
                "M" -> maps[f[1].toInt()] = f[2].toInt()
                "E" -> sets[f[1].toInt()] = if (heartGold) EncSet(
                    f[2].toInt(), slots(f[3]), IntArray(0), IntArray(0),
                    f[4].toInt(), slots(f[5]), f[6].toInt(), slots(f[7]),
                    listOf(f[8].toInt() to slots(f[9]), f[10].toInt() to slots(f[11]), f[12].toInt() to slots(f[13])),
                    f[14].toInt(),
                ) else EncSet(
                    f[2].toInt(), slots(f[3]), ints(f[4]), ints(f[5]),
                    f[6].toInt(), slots(f[7]), 0, emptyList(),
                    listOf(f[8].toInt() to slots(f[9]), f[10].toInt() to slots(f[11]), f[12].toInt() to slots(f[13])),
                    0,
                )
            }
        }
        Wild(maps, sets)
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

    /** Every map's grass and surf rates as the cart has them, [map header id, grass, surf] triples: WILD ENCOUNTERS back on
     *  puts a map's back from here (MgbaCore.pkNdsWildRates). */
    fun wildRates(): IntArray = wild.maps.flatMap { (map, set) ->
        val s = wild.sets[set] ?: return@flatMap emptyList()
        listOf(map, s.grassRate, s.surfRate)
    }.toIntArray()

    /** NEXT BOSS's trainer id for boss [key] (the generator's: ROARK, WILL, ...). */
    fun trainer(key: String): Int = bosses.second[key] ?: error("no boss $key")

    override fun encounters(group: Int, num: Int, progress: SaveProgress?): MapEncounters {
        val set = wild.maps[num]?.let { wild.sets[it] } ?: return MapEncounters(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val time = timeOfDay(progress)
        fun merge(rows: List<Triple<Int, IntRange, Int>>): List<EncounterSlot> =
            rows.filter { it.first != 0 }.groupBy { it.first }.map { (species, g) ->
                EncounterSlot(species, g.minOf { it.second.first }, g.maxOf { it.second.last }, g.sumOf { it.third })
            }.sortedByDescending { it.percent }
        val grass = if (set.grassRate == 0) emptyList() else merge(set.grass.mapIndexed { i, s ->
            val species = when {
                heartGold -> s.species.getOrElse(time) { s.species.first() }
                i == 2 || i == 3 -> when (time) {
                    DAY -> set.day.getOrElse(i - 2) { s.species[0] }
                    NIGHT -> set.night.getOrElse(i - 2) { s.species[0] }
                    else -> s.species[0]
                }
                else -> s.species[0]
            }
            Triple(species, s.min..s.max, LAND_ODDS[i])
        })
        fun water(rate: Int, slots: List<Slot>, odds: List<Int>, night: Int = -1) =
            if (rate == 0) emptyList() else merge(slots.mapIndexed { i, s ->
                val species = if (i == night && set.nightFish != 0 && time == NIGHT) set.nightFish else s.species[0]
                Triple(species, s.min..s.max, odds.getOrElse(i) { 0 })
            })
        val (old, good, superRod) = set.rods
        return MapEncounters(
            grass = grass,
            water = water(set.surfRate, set.surf, WATER_ODDS),
            rockSmash = water(set.rockRate, set.rock, ROCK_SMASH_ODDS),
            oldRod = water(old.first, old.second, if (heartGold) HG_ROD_ODDS else PT_OLD_ROD_ODDS),
            goodRod = water(good.first, good.second, if (heartGold) HG_ROD_ODDS else PT_ROD_ODDS, night = if (heartGold) 3 else -1),
            superRod = water(superRod.first, superRod.second, if (heartGold) HG_ROD_ODDS else PT_ROD_ODDS, night = if (heartGold) 1 else -1),
        )
    }

    override fun party(trainer: Int, progress: SaveProgress?): List<TrainerMon> = bosses.first[trainer].orEmpty()

    override fun clockNote(progress: SaveProgress?): String = when (timeOfDay(progress)) {
        MORNING -> "MORNING"
        NIGHT -> "NIGHT"
        else -> "DAY"
    }

    private fun timeOfDay(progress: SaveProgress?): Int {
        val hour = progress?.variable(GEN4_VAR_HOUR) ?: localNow().hour
        return when (hour) {
            in 4..9 -> MORNING
            in 10..19 -> DAY
            else -> NIGHT
        }
    }

    private companion object {
        // HG / SS's grass species order (morn, day, nite).
        const val MORNING = 0
        const val DAY = 1
        const val NIGHT = 2
        // Each game's slot rolls (wild_encounters.c / encounter_check.c): the same land and surf odds in both.
        val LAND_ODDS = listOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
        val WATER_ODDS = listOf(60, 30, 5, 4, 1)
        val PT_OLD_ROD_ODDS = listOf(60, 30, 5, 4, 1)
        val PT_ROD_ODDS = listOf(40, 40, 15, 4, 1)
        val HG_ROD_ODDS = listOf(40, 30, 15, 10, 5)
        val ROCK_SMASH_ODDS = listOf(80, 20)
    }
}

val PLATINUM_WORLD = Gen4World(PT_WILD_RAW, PT_BOSSES_RAW, heartGold = false)
val HEARTGOLD_WORLD = Gen4World(HG_WILD_RAW, HG_BOSSES_RAW, heartGold = true)
val SOULSILVER_WORLD = Gen4World(SS_WILD_RAW, HG_BOSSES_RAW, heartGold = true)

/** A boss NEXT BOSS shows: done once [done] is set, or by [doneIf]; its team is [world]'s for boss [key]. */
internal fun gen4Boss(world: Gen4World, title: String, where: String, key: String, done: Int, doneIf: ((SaveProgress) -> Boolean)? = null): Boss =
    Boss(title, where, done, doneIf = doneIf) { world.trainer(key) }
