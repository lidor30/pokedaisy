package com.pokedaisy.app.companion.data

import kotlin.math.roundToInt

/**
 * The generated wild and boss lines the Gen 2 guides share (Polished Crystal's GuidePolishedCrystalGen.kt, Crystal's
 * GuideCrystalGen.kt: one format, so one reader). Wild lines, `|`-separated:
 *  - `G|group|num|morn,day,nite rates|lv:sp x 7 per time of day` grass / caves,
 *  - `W|group|num|rate|lv:sp x 3` surfing,
 *  - `F|g|n|fishgroup`, `T|g|n|treeset`, `R|g|n|rockset` which set a map uses,
 *  - `FG|fishgroup|old|good|super`, rows of `odds:sp:lv` (odds in counts of 256; species 0 = a time-of-day slot whose
 *    level field names its `TG` group, or Polished Crystal's CORSOLA / STARYU slot),
 *  - `TG|group|daySp:dayLv|nightSp:nightLv` Crystal's time fish groups,
 *  - `TS|set|common|rare` headbutt / rock smash rows of `odds:sp:lv` (odds in percent).
 * Boss lines: `KEY|class|party|Name|lv sp item m,m,m,m;...`, keyed class << 8 | party.
 */
internal class Gen2WildTables(raw: List<String>) {
    class Slot(val level: Int, val species: Int, val odds: Int = 0)

    val grass = HashMap<Int, Pair<IntArray, List<Slot>>>()
    val water = HashMap<Int, Pair<Int, List<Slot>>>()
    val fishMaps = HashMap<Int, Int>()
    val treeMaps = HashMap<Int, Int>()
    val rockMaps = HashMap<Int, Int>()
    /** Each fish group's OLD / GOOD / SUPER ROD rows. */
    val fish = HashMap<Int, List<List<Slot>>>()
    /** Each headbutt / rock set's common and rare rows. */
    val trees = HashMap<Int, Pair<List<Slot>, List<Slot>>>()
    /** A time fish group's day and night Pokémon. */
    val timeFish = HashMap<Int, Pair<Slot, Slot>>()

    init {
        fun pairs(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { p -> p.split(':').let { Slot(it[0].toInt(), it[1].toInt()) } }
        fun rows(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { p ->
            p.split(':').let { Slot(level = it[2].toInt(), species = it[1].toInt(), odds = it[0].toInt()) }
        }
        fun mon(s: String) = s.split(':').let { Slot(level = it[1].toInt(), species = it[0].toInt()) }
        raw.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('|')
            val key by lazy { f[1].toInt() shl 8 or f[2].toInt() }
            when (f[0]) {
                "G" -> grass[key] = f[3].split(',').map { it.toInt() }.toIntArray() to pairs(f[4])
                "W" -> water[key] = f[3].toInt() to pairs(f[4])
                "F" -> fishMaps[key] = f[3].toInt()
                "T" -> treeMaps[key] = f[3].toInt()
                "R" -> rockMaps[key] = f[3].toInt()
                "FG" -> fish[f[1].toInt()] = f.drop(2).map { rows(it) }
                "TS" -> trees[f[1].toInt()] = rows(f[2]) to rows(f.getOrElse(3) { "" })
                "TG" -> timeFish[f[1].toInt()] = mon(f[2]) to mon(f[3])
            }
        }
    }

    /** Rows of (species, level range, percent) merged by species, likeliest first. */
    fun merge(rows: List<Triple<Int, IntRange, Double>>): List<EncounterSlot> =
        rows.groupBy { it.first }.map { (species, g) ->
            EncounterSlot(species, g.minOf { it.second.first }, g.maxOf { it.second.last }, (g.sumOf { it.third }).roundToInt())
        }.sortedByDescending { it.percent }
}

internal class Gen2BossTables(raw: List<String>) {
    class Mon(val level: Int, val species: Int, val item: Int, val moves: List<Int>)

    private val lines = raw.asSequence().flatMap { it.lineSequence() }.filter { it.isNotBlank() }.map { it.split('|') }.toList()

    /** Trainer key (class << 8 | party) -> its team. */
    val teams: Map<Int, List<Mon>> = lines.associate { f ->
        (f[1].toInt() shl 8 or f[2].toInt()) to f[4].split(';').map { m ->
            val p = m.split(' ')
            Mon(p[0].toInt(), p[1].toInt(), p[2].toInt(), p.getOrElse(3) { "" }.split(',').filter { it.isNotEmpty() }.map { it.toInt() })
        }
    }

    private val keys: Map<String, Int> = lines.associate { f -> f[0] to (f[1].toInt() shl 8 or f[2].toInt()) }

    /** The trainer key for boss [name] (the generator's key: FALKNER, WILL_2, ...). */
    fun trainer(name: String): Int = keys[name] ?: error("no boss $name")
}
