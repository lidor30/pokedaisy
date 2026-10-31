package com.pokedaisy.app.companion.data

import java.util.concurrent.ConcurrentHashMap

/*
 * The DEX page's EVOLVE / AREA / MOVES sections, read from the ROM the way the
 * page's INFO is ([PokedexSource]): evolutions (both ways in a family), the
 * maps whose wild tables hold a species, and its learnsets. Everything is read
 * once per ROM into process-wide caches (never per page visit) on Compose's IO
 * dispatcher, through [PokedexSource.reader].
 */

/**
 * Which numbering a game's evolution methods use. Methods 1-15 are vanilla's
 * everywhere; past that every engine went its own way, so each scheme names the
 * ones its games use (checked against the ROMs' own tables: GLIGAR, MANTYKE,
 * ROCKRUFF, TOXEL... in each) and anything else reads as a special condition.
 */
enum class EvoScheme {
    /** pokefirered / pokeemerald: 1-15. */
    VANILLA,
    /** CFRU (Unbound, Amethyst): 16 rain / fog, 17 move type, 18 type in party, 19 map section,
     * 20 / 21 male / female, 22 / 23 night / day, 24 / 25 held item by night / day, 26 move,
     * 27 Pokémon in party, 28 hours, 30 critical hits, 31 / 32 natures, 34 item at a place. */
    CFRU,
    /** Radical Red: CFRU's 16-29, then 30 / 31 the natures. */
    CFRU_RADICAL_RED,
    /** Gaia: 17 rain, 18 Dark type in party, 19 / 20 male / female, 21 move, 22 Pokémon in party,
     * 23 a map, 24 / 25 item on a male / female. */
    GAIA,
    /** pokeemerald-expansion before 1.12 (Seaglass 1.9, Imperium 1.10): EVO_LEVEL_FEMALE 16 ...
     * EVO_OVERWORLD_STEPS 52, {method, param, target, pad} lists from gSpeciesInfo. */
    EXPANSION,
    /** Lazarus (expansion 1.9.4): [EXPANSION]'s list one lower from EVO_LEVEL (3) on. */
    EXPANSION_LAZARUS,
    /** Emerald Rogue: [EXPANSION] to 46, then its own 47-51. */
    ROGUE,
    /** Quetzal: [EXPANSION]'s numbering up to 42 (EVO_LEVEL_FOG). */
    QUETZAL,
    /** R.O.W.E.: [EXPANSION]'s numbering up to 31 (EVO_TRADE_SPECIFIC_MON). */
    ROWE,
    /** pokeemerald-expansion 1.12+ (TMT2, Heart and Soul, SoulGold): EVO_LEVEL / TRADE / ITEM / ...
     * plus a list of {condition, arg1, arg2, arg3} (IF_GENDER, IF_TIME, IF_HOLD_ITEM, ...). */
    EXPANSION_PARAMS,
    /** Recharged Yellow: vanilla's, plus 16 = use item [param] (its LINKING CORD) while holding the
     * row's 4th u16 - the trade-with-an-item evolutions (POLIWHIRL + KING'S ROCK, ONIX + METAL COAT). */
    RECHARGED_YELLOW,
}

/**
 * Where a game keeps its evolutions for the DEX's EVOLVE section, when it isn't
 * [PokedexTables.evolutions]' vanilla table: a species-indexed [table] of
 * [perMon] 8-byte rows {method, param, target, extra}, or (table 0) a list per
 * species from gSpeciesInfo ([SpeciesInfoDex.evolutionsOff]) of [record]-byte
 * entries ending at method 0xFFFF - 12 bytes once a params pointer follows,
 * whose lists end at [conditionsEnd] (the enum grew with releases).
 */
data class EvoLayout(
    val scheme: EvoScheme,
    val table: Long = 0,
    val perMon: Int = 5,
    val record: Int = 8,
    val conditionsEnd: Int = 39,
)

/** expansion's TimeOfDay (and the old day / night / dusk methods). */
enum class EvoTime { MORNING, DAY, DUSK, NIGHT }

/** One requirement of an evolution, as the DEX page words it. */
sealed class EvoReq {
    data class Level(val level: Int) : EvoReq()
    /** A level-up, at any level (friendship, held items, moves...). */
    object LevelUp : EvoReq()
    object Friendship : EvoReq()
    data class Time(val time: EvoTime) : EvoReq()
    object Trade : EvoReq()
    data class TradeFor(val species: Int) : EvoReq()
    data class UseItem(val item: Int) : EvoReq()
    data class Hold(val item: Int) : EvoReq()
    data class Gender(val female: Boolean) : EvoReq()
    /** ATTACK vs DEFENSE: 1 greater, 0 equal, -1 lower. */
    data class Stats(val cmp: Int) : EvoReq()
    /** Decided by the Pokémon's personality value (WURMPLE, TANDEMAUS, DUNSPARCE). */
    object Random : EvoReq()
    data class Beauty(val min: Int) : EvoReq()
    data class KnowsMove(val move: Int) : EvoReq()
    data class KnowsMoveType(val type: Int) : EvoReq()
    data class SpeciesInParty(val species: Int) : EvoReq()
    data class TypeInParty(val type: Int) : EvoReq()
    /** expansion's old EVO_LEVEL_DARK_TYPE_MON_IN_PARTY (the type id differs per game). */
    object DarkInParty : EvoReq()
    /** At a region map section ([mapsec], named like the MAP tab names it). */
    data class AtPlace(val mapsec: Int) : EvoReq()
    /** On one particular map. */
    object SomePlace : EvoReq()
    data class Weather(val kind: Int) : EvoReq()
    /** 1 amped, -1 low-key, 0 one particular nature. */
    data class Nature(val kind: Int) : EvoReq()
    data class Crits(val count: Int) : EvoReq()
    data class HpLost(val hp: Int) : EvoReq()
    data class Recoil(val hp: Int) : EvoReq()
    data class Scroll(val dark: Boolean) : EvoReq()
    data class Steps(val steps: Int) : EvoReq()
    data class BagCount(val item: Int, val count: Int) : EvoReq()
    data class Defeat(val species: Int, val item: Int, val count: Int) : EvoReq()
    data class UsedMove(val move: Int, val times: Int) : EvoReq()
    data class Region(val region: Int, val not: Boolean) : EvoReq()
    data class Hours(val from: Int, val to: Int) : EvoReq()
    object Spin : EvoReq()
    object AfterBattle : EvoReq()
    object InBattle : EvoReq()
    object OverworldTrigger : EvoReq()
    /** SHEDINJA: a free party slot and a spare POKé BALL when NINCADA evolves. */
    object SpareSlot : EvoReq()
    /** expansion's EVO_SPLIT_FROM_EVO: appears when the Pokémon evolves into [species]. */
    data class SplitFrom(val species: Int) : EvoReq()
    /** A method this game's scheme doesn't name (or a story flag). */
    object Special : EvoReq()

    companion object {
        const val WEATHER_RAIN = 1
        const val WEATHER_FOG = 2
        const val WEATHER_RAIN_OR_FOG = 3
        const val WEATHER_OTHER = 0
    }
}

/** [from] becomes [to] when every one of [reqs] holds. */
data class EvoLink(val from: Int, val to: Int, val reqs: List<EvoReq>)

/** A level-up move. */
data class LevelMove(val level: Int, val move: Int)

/** A TM / HM / tutor move; [label] is "TM06" / "HM01", or "" when the game only lists the moves. */
data class TeachMove(val label: String, val move: Int)

/** How a wild Pokémon is met. */
enum class WildMethod { GRASS, SURF, ROCK_SMASH, OLD_ROD, GOOD_ROD, SUPER_ROD }

/**
 * Where a species can be caught: a region map section, a method, its levels and
 * odds (the best of the section's maps). [times] is a bit per time-of-day set
 * (expansion's MORNING / DAY / EVENING / NIGHT) when it's only met in some of the
 * sets that have encounters there; 0 = any time.
 */
data class CatchSpot(val mapsec: Int, val method: WildMethod, val minLevel: Int, val maxLevel: Int, val percent: Int, val times: Int)

/** What the game's tables give the DEX page, per section (without reading the ROM). */
fun hasEvolutions(t: PokedexTables): Boolean = t.gen4 != null || !t.cartDex && when (val l = t.evoLayout) {
    null -> t.evolutions != 0L
    else -> l.table != 0L || (t.expansion?.evolutionsOff ?: -1) >= 0
}

fun hasLearnsets(t: PokedexTables, g: GuideTables?): Boolean =
    t.gen4 != null || !t.cartDex && ((t.expansion?.levelUpOff ?: -1) >= 0 || t.levelUpLearnsets != 0L || (g?.learnsets ?: 0L) != 0L)

fun hasCatchSpots(g: GuideTables?): Boolean = g != null && g.wildHeaders != 0L

private const val EVO_END = 0xFFFF
private const val MAX_FAMILY_LINKS = 24

/**
 * Reads and caches the DEX page's extra sections. ROM data never changes, so -
 * like [PokedexSource] - this runs on Compose's IO dispatcher, and everything
 * is cached for the process (keyed by the game's tables, dropped when they change).
 */
object DexDetails {
    @Volatile private var evoFor: PokedexTables? = null
    @Volatile private var evoTable: Map<Int, List<EvoLink>>? = null
    @Volatile private var parents: Map<Int, List<EvoLink>>? = null
    private val levelUp = ConcurrentHashMap<Int, List<LevelMove>>()
    private val teach = ConcurrentHashMap<Int, List<TeachMove>>()
    @Volatile private var tmhm: List<Int>? = null
    @Volatile private var movesFor: Pair<PokedexTables, GuideTables?>? = null
    @Volatile private var tmhmOk: Boolean? = null
    @Volatile private var spotsFor: GuideTables? = null
    /** The save's wild swaps the index was built with ([GuideRandomizer]): a new seed rebuilds it. */
    @Volatile private var spotsSeed: Long? = null
    @Volatile private var spots: Map<Int, List<CatchSpot>>? = null

    /** [PokedexSource.romChanged]: every cache here starts over on the next read. */
    fun romChanged() {
        synchronized(this) { evoFor = null; movesFor = null; spotsFor = null }
        MapSections.romChanged()
    }

    // ---- EVOLVE ----------------------------------------------------------------

    /** Already-loaded family only (no ROM read): composables start from it. */
    fun cachedFamily(t: PokedexTables, species: Int): List<EvoLink>? =
        if (evoFor == t) evoTable?.let { familyOf(species, it, parents!!) } else null

    /** Every evolution link in [species]' family - what it comes from and becomes,
     * both ways, in chain order; empty when it doesn't evolve; null = no table. */
    fun family(t: PokedexTables, species: Int): List<EvoLink>? {
        val table = evolutions(t) ?: return null
        return familyOf(species, table, parents!!)
    }

    /** Every species' outgoing links, read once per ROM; null if [t] has no table or it doesn't check out. */
    fun evolutions(t: PokedexTables): Map<Int, List<EvoLink>>? {
        if (evoFor == t) return evoTable
        synchronized(this) {
            if (evoFor == t) return evoTable
            val table = runCatching { t.gen4?.let { Gen4Dex.evolutions(it) } ?: readEvolutions(t) }
                .onFailure { android.util.Log.w("pokedaisy", "evolutions failed", it) }
                .getOrNull()
                ?.takeIf { it.size >= 10 && it.values.flatten().all { l -> l.to in 1 until t.speciesCount } }
            parents = table?.values?.flatten()?.groupBy { it.to }
            evoTable = table
            // A DS cart's pages may still be on their way (the first run reads them in the background): asked again.
            if (table != null || t.gen4 == null) evoFor = t
            return table
        }
    }

    private fun familyOf(species: Int, table: Map<Int, List<EvoLink>>, up: Map<Int, List<EvoLink>>): List<EvoLink> {
        // The connected family: walk to the roots, then down, so the links come in chain order.
        val members = linkedSetOf(species)
        val queue = ArrayDeque(listOf(species))
        while (queue.isNotEmpty() && members.size < 40) {
            val s = queue.removeFirst()
            (up[s].orEmpty().map { it.from } + table[s].orEmpty().map { it.to }).forEach { if (members.add(it)) queue.add(it) }
        }
        val roots = members.filter { up[it].isNullOrEmpty() }.ifEmpty { listOf(species) }
        val out = ArrayList<EvoLink>()
        val seen = HashSet<Int>()
        val walk = ArrayDeque(roots)
        while (walk.isNotEmpty() && out.size < MAX_FAMILY_LINKS) {
            val s = walk.removeFirst()
            if (!seen.add(s)) continue
            table[s].orEmpty().forEach { link ->
                if (out.size < MAX_FAMILY_LINKS && link !in out) out.add(link)
                walk.add(link.to)
            }
        }
        return out
    }

    private fun readEvolutions(t: PokedexTables): Map<Int, List<EvoLink>>? {
        val layout = t.evoLayout ?: if (t.evolutions != 0L) EvoLayout(EvoScheme.VANILLA, table = t.evolutions) else return null
        val out = HashMap<Int, List<EvoLink>>()
        if (layout.table != 0L) {
            val row = 8
            val raw = rd(layout.table, t.speciesCount * layout.perMon * row)
            for (s in 1 until t.speciesCount) {
                val links = (0 until layout.perMon).mapNotNull { i ->
                    val o = (s * layout.perMon + i) * row
                    val m = u16le(raw, o)
                    if (m == 0) null else link(s, layout.scheme, m, u16le(raw, o + 2), u16le(raw, o + 4), u16le(raw, o + 6), t)
                }.distinct()
                if (links.isNotEmpty()) out[s] = knownFirst(links)
            }
            // A hack of the same size that moved the table must not get wrong data:
            // BULBASAUR evolves at 16 into IVYSAUR (species 1 -> 2) in every table-shaped game here. A randomized
            // retail ROM's table is at retail's place (its hash says so), the randomizer's evolutions in it.
            if (!gameRandomized && out[1]?.firstOrNull()?.let { it.to == 2 && it.reqs == listOf(EvoReq.Level(16)) } != true) return null
            return out
        }
        val x = t.expansion ?: return null
        if (x.evolutionsOff < 0) return null
        val info = rd(t.speciesInfo, t.speciesCount * x.stride)
        for (s in 1 until t.speciesCount) {
            val ptr = u32le(info, s * x.stride + x.evolutionsOff)
            if (!Gfx.inRom(ptr)) continue
            val raw = rd(ptr, layout.record * 16)
            val links = ArrayList<EvoLink>()
            for (i in 0 until 16) {
                val o = i * layout.record
                val m = u16le(raw, o)
                if (m == EVO_END) break
                if (m == 0) continue // EVO_NONE (expansion 1.10+): a breeding-only link
                val p = u16le(raw, o + 2)
                val target = u16le(raw, o + 4)
                val l = if (layout.scheme == EvoScheme.EXPANSION_PARAMS) {
                    paramsLink(s, m, p, target, u32le(raw, o + 8), layout.conditionsEnd)
                } else link(s, layout.scheme, m, p, target, 0, t)
                if (l != null && l !in links) links.add(l)
            }
            if (links.isNotEmpty()) out[s] = knownFirst(links)
        }
        return out
    }

    /** A method the scheme can't name, dropped where the same evolution also has one it can
     * (R.O.W.E. lists a level-up beside each stone): the page shows the named way. */
    private fun knownFirst(links: List<EvoLink>): List<EvoLink> {
        val named = links.filter { EvoReq.Special !in it.reqs }.map { it.to }.toSet()
        return links.filter { EvoReq.Special !in it.reqs || it.to !in named }
    }

    private fun link(from: Int, scheme: EvoScheme, m: Int, p: Int, target: Int, extra: Int, t: PokedexTables): EvoLink? {
        if (m >= 0xF0 || target == 0 || target == from || target >= t.speciesCount) return null // Megas, EVO_NONE, ends
        val reqs = when (scheme) {
            EvoScheme.VANILLA -> vanilla(m, p)
            EvoScheme.CFRU -> vanilla(m, p) ?: cfru(m, p, extra) ?: when (m) {
                30 -> listOf(EvoReq.Crits(p))
                31 -> listOf(level(p), EvoReq.Nature(1))
                32 -> listOf(level(p), EvoReq.Nature(-1))
                34 -> listOf(EvoReq.UseItem(p), EvoReq.AtPlace(extra))
                else -> null
            }
            EvoScheme.CFRU_RADICAL_RED -> vanilla(m, p) ?: cfru(m, p, extra) ?: when (m) {
                30 -> listOf(level(p), EvoReq.Nature(1))
                31 -> listOf(level(p), EvoReq.Nature(-1))
                else -> null
            }
            EvoScheme.GAIA -> vanilla(m, p) ?: when (m) {
                17 -> listOf(level(p), EvoReq.Weather(EvoReq.WEATHER_RAIN))
                18 -> listOf(level(p), EvoReq.DarkInParty)
                19 -> listOf(level(p), EvoReq.Gender(female = false))
                20 -> listOf(level(p), EvoReq.Gender(female = true))
                21 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMove(p))
                22 -> listOf(EvoReq.LevelUp, EvoReq.SpeciesInParty(p))
                23 -> listOf(EvoReq.LevelUp, EvoReq.SomePlace)
                24 -> listOf(EvoReq.UseItem(p), EvoReq.Gender(female = false))
                25 -> listOf(EvoReq.UseItem(p), EvoReq.Gender(female = true))
                else -> null
            }
            EvoScheme.EXPANSION -> expansion(m, p)
            EvoScheme.EXPANSION_LAZARUS -> if (m >= 3) expansion(m + 1, p) else null
            EvoScheme.ROGUE -> if (m <= 46) expansion(m, p) else when (m) {
                47, 48 -> listOf(level(p), EvoReq.Random)
                49 -> listOf(EvoReq.Level(30), EvoReq.Nature(0))
                50 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p))
                51 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMoveType(p))
                else -> null
            }
            EvoScheme.QUETZAL -> if (m <= 42) expansion(m, p) else null
            EvoScheme.ROWE -> if (m <= 31) expansion(m, p) else null
            EvoScheme.EXPANSION_PARAMS -> null
            EvoScheme.RECHARGED_YELLOW -> vanilla(m, p) ?: if (m == 16) listOf(EvoReq.UseItem(p), EvoReq.Hold(extra)) else null
        } ?: listOf(EvoReq.Special)
        return EvoLink(from, target, reqs)
    }

    private fun level(p: Int): EvoReq = if (p in 1..100) EvoReq.Level(p) else EvoReq.LevelUp

    /** include/constants/pokemon.h EVO_FRIENDSHIP (1) .. EVO_BEAUTY (15). */
    private fun vanilla(m: Int, p: Int): List<EvoReq>? = when (m) {
        1 -> listOf(EvoReq.LevelUp, EvoReq.Friendship)
        2 -> listOf(EvoReq.LevelUp, EvoReq.Friendship, EvoReq.Time(EvoTime.DAY))
        3 -> listOf(EvoReq.LevelUp, EvoReq.Friendship, EvoReq.Time(EvoTime.NIGHT))
        4 -> listOf(level(p))
        5 -> listOf(EvoReq.Trade)
        6 -> listOf(EvoReq.Trade, EvoReq.Hold(p))
        7 -> listOf(EvoReq.UseItem(p))
        8 -> listOf(level(p), EvoReq.Stats(1))
        9 -> listOf(level(p), EvoReq.Stats(0))
        10 -> listOf(level(p), EvoReq.Stats(-1))
        11, 12 -> listOf(level(p), EvoReq.Random)
        13 -> listOf(level(p))
        14 -> listOf(level(p), EvoReq.SpareSlot)
        15 -> listOf(EvoReq.LevelUp, EvoReq.Beauty(p))
        else -> null
    }

    /** CFRU's 16-29 (include/constants/pokemon.h in Skeli's CFRU; [extra] is the row's 4th u16). */
    private fun cfru(m: Int, p: Int, extra: Int): List<EvoReq>? = when (m) {
        16 -> listOf(level(p), EvoReq.Weather(EvoReq.WEATHER_RAIN_OR_FOG))
        17 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMoveType(p))
        18 -> listOf(level(p), EvoReq.TypeInParty(extra))
        19 -> listOf(EvoReq.LevelUp, EvoReq.AtPlace(p))
        20 -> listOf(level(p), EvoReq.Gender(female = false))
        21 -> listOf(level(p), EvoReq.Gender(female = true))
        22 -> listOf(level(p), EvoReq.Time(EvoTime.NIGHT))
        23 -> listOf(level(p), EvoReq.Time(EvoTime.DAY))
        24 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p), EvoReq.Time(EvoTime.NIGHT))
        25 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p), EvoReq.Time(EvoTime.DAY))
        26 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMove(p))
        27 -> listOf(EvoReq.LevelUp, EvoReq.SpeciesInParty(p))
        28 -> listOf(level(p), EvoReq.Hours(extra shr 8, extra and 0xFF))
        else -> null
    }

    /** pokeemerald-expansion's EVO_* before the 1.12 rework (1-52). */
    private fun expansion(m: Int, p: Int): List<EvoReq>? = vanilla(m, p) ?: when (m) {
        16 -> listOf(level(p), EvoReq.Gender(female = true))
        17 -> listOf(level(p), EvoReq.Gender(female = false))
        18 -> listOf(level(p), EvoReq.Time(EvoTime.NIGHT))
        19 -> listOf(level(p), EvoReq.Time(EvoTime.DAY))
        20 -> listOf(level(p), EvoReq.Time(EvoTime.DUSK))
        21 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p), EvoReq.Time(EvoTime.DAY))
        22 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p), EvoReq.Time(EvoTime.NIGHT))
        23 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMove(p))
        24 -> listOf(EvoReq.LevelUp, EvoReq.Friendship, EvoReq.KnowsMoveType(p))
        25 -> listOf(EvoReq.LevelUp, EvoReq.AtPlace(p))
        26 -> listOf(EvoReq.UseItem(p), EvoReq.Gender(female = false))
        27 -> listOf(EvoReq.UseItem(p), EvoReq.Gender(female = true))
        28 -> listOf(level(p), EvoReq.Weather(EvoReq.WEATHER_RAIN))
        29 -> listOf(EvoReq.LevelUp, EvoReq.SpeciesInParty(p))
        30 -> listOf(level(p), EvoReq.DarkInParty)
        31 -> listOf(EvoReq.Trade, EvoReq.TradeFor(p))
        32 -> listOf(EvoReq.LevelUp, EvoReq.SomePlace)
        33 -> listOf(level(p), EvoReq.Nature(1))
        34 -> listOf(level(p), EvoReq.Nature(-1))
        35 -> listOf(EvoReq.Crits(p))
        36 -> listOf(EvoReq.HpLost(p), EvoReq.OverworldTrigger)
        37 -> listOf(EvoReq.Scroll(dark = true))
        38 -> listOf(EvoReq.Scroll(dark = false))
        39 -> listOf(EvoReq.UseItem(p), EvoReq.Time(EvoTime.NIGHT))
        40 -> listOf(EvoReq.UseItem(p), EvoReq.Time(EvoTime.DAY))
        41 -> listOf(EvoReq.LevelUp, EvoReq.Hold(p))
        42 -> listOf(level(p), EvoReq.Weather(EvoReq.WEATHER_FOG))
        43, 44 -> listOf(EvoReq.LevelUp, EvoReq.KnowsMove(p), EvoReq.Random)
        45, 46 -> listOf(level(p), EvoReq.Random)
        47 -> listOf(EvoReq.UsedMove(p, 20))
        48 -> listOf(EvoReq.Recoil(p), EvoReq.Gender(female = false))
        49 -> listOf(EvoReq.Recoil(p), EvoReq.Gender(female = true))
        50 -> listOf(EvoReq.BagCount(p, 999))
        51 -> listOf(EvoReq.Defeat(0, p, 3))
        52 -> listOf(EvoReq.Steps(p))
        else -> null
    }

    /** expansion 1.12+: a method (1 LEVEL, 2 TRADE, 3 ITEM, 4 SPLIT_FROM_EVO, 5 SCRIPT_TRIGGER,
     * 6 LEVEL_BATTLE_ONLY, 7 BATTLE_END, 8 SPIN) and its EvolutionParam list. */
    private fun paramsLink(from: Int, m: Int, p: Int, target: Int, params: Long, end: Int): EvoLink? {
        if (m == 0 || target == 0 || target == from) return null // EVO_NONE: a breeding-only link
        val reqs = ArrayList<EvoReq>()
        when (m) {
            1 -> reqs.add(level(p))
            2 -> reqs.add(EvoReq.Trade)
            3 -> reqs.add(EvoReq.UseItem(p))
            4 -> reqs.add(EvoReq.SplitFrom(p))
            5 -> reqs.add(EvoReq.OverworldTrigger)
            6 -> { reqs.add(level(p)); reqs.add(EvoReq.InBattle) }
            7 -> reqs.add(EvoReq.AfterBattle)
            8 -> reqs.add(EvoReq.Spin)
            else -> reqs.add(EvoReq.Special)
        }
        if (Gfx.inRom(params)) {
            val raw = rd(params, 8 * 8)
            for (i in 0 until 8) {
                val c = u16le(raw, i * 8)
                if (c >= end) break
                condition(c, u16le(raw, i * 8 + 2), u16le(raw, i * 8 + 4), u16le(raw, i * 8 + 6))?.let { if (it !in reqs) reqs.add(it) }
            }
        }
        return EvoLink(from, target, reqs)
    }

    /** enum EvolutionConditions (IF_GENDER 0 ... IF_NOT_REGION 38). */
    private fun condition(c: Int, a1: Int, a2: Int, a3: Int): EvoReq? = when (c) {
        0 -> EvoReq.Gender(female = a1 == 0xFE)
        1 -> EvoTime.entries.getOrNull(a1)?.let { EvoReq.Time(it) } ?: EvoReq.Special
        // NOT night = by day (morning, day and evening all count); NOT day = at night.
        2 -> when (a1) { EvoTime.NIGHT.ordinal -> EvoReq.Time(EvoTime.DAY); EvoTime.DAY.ordinal -> EvoReq.Time(EvoTime.NIGHT); else -> EvoReq.Special }
        3 -> EvoReq.Friendship
        4 -> EvoReq.Stats(1)
        5 -> EvoReq.Stats(0)
        6 -> EvoReq.Stats(-1)
        7 -> EvoReq.Hold(a1)
        8, 9, 10, 32, 33, 34 -> EvoReq.Random
        11 -> EvoReq.Beauty(a1)
        16 -> EvoReq.SpeciesInParty(a1)
        17 -> EvoReq.SomePlace
        18 -> EvoReq.AtPlace(a1)
        19 -> EvoReq.KnowsMove(a1)
        20 -> EvoReq.TradeFor(a1)
        21 -> EvoReq.TypeInParty(a1)
        22 -> EvoReq.Weather(
            when (a1) {
                3, 5, 13 -> EvoReq.WEATHER_RAIN // RAIN, RAIN_THUNDERSTORM, DOWNPOUR
                6, 9, 22 -> EvoReq.WEATHER_FOG // FOG_HORIZONTAL, FOG_DIAGONAL, FOG
                else -> EvoReq.WEATHER_OTHER
            },
        )
        23 -> EvoReq.KnowsMoveType(a1)
        24 -> EvoReq.Nature(0)
        25 -> EvoReq.Nature(1)
        26 -> EvoReq.Nature(-1)
        27 -> EvoReq.Recoil(a1)
        28 -> EvoReq.HpLost(a1)
        29 -> EvoReq.Crits(a1)
        30 -> EvoReq.UsedMove(a1, a2)
        31 -> EvoReq.Defeat(a1, a2, a3)
        35 -> EvoReq.Steps(a1)
        36 -> EvoReq.BagCount(a1, a2)
        37 -> EvoReq.Region(a1, not = false)
        38 -> EvoReq.Region(a1, not = true)
        else -> EvoReq.Special
    }

    // ---- MOVES -----------------------------------------------------------------

    private fun syncMoves(t: PokedexTables, g: GuideTables?) {
        val key = t to g
        if (movesFor == key) return
        synchronized(this) {
            if (movesFor == key) return
            levelUp.clear(); teach.clear(); tmhmOk = null; tmhm = null
            movesFor = key
        }
    }

    fun cachedLevelUp(t: PokedexTables, g: GuideTables?, species: Int): List<LevelMove>? =
        if (movesFor == (t to g)) levelUp[species] else null

    fun cachedTeachable(t: PokedexTables, g: GuideTables?, species: Int): List<TeachMove>? =
        if (movesFor == (t to g)) teach[species] else null

    /** [species]' level-up moves in learning order; null = this game's learnsets aren't located. */
    fun levelUp(t: PokedexTables, g: GuideTables?, species: Int): List<LevelMove>? {
        if (!hasLearnsets(t, g) || species <= 0) return null
        syncMoves(t, g)
        levelUp[species]?.let { return it }
        return runCatching { readLevelUp(t, g, species) }
            .onFailure { android.util.Log.w("pokedaisy", "learnset $species failed", it) }
            .getOrNull()?.also { levelUp[species] = it }
    }

    private fun readLevelUp(t: PokedexTables, g: GuideTables?, species: Int): List<LevelMove>? {
        t.gen4?.let { return Gen4Dex.levelUp(it, species) }
        val x = t.expansion
        fun words(ptr: Long): List<LevelMove> {
            if (!Gfx.inRom(ptr)) return emptyList()
            val raw = rd(ptr, 4 * 100)
            return (0 until 100).asSequence().map { u16le(raw, it * 4) to u16le(raw, it * 4 + 2) }
                .takeWhile { it.first != 0xFFFF && it.first != 0 }.map { LevelMove(it.second, it.first) }.toList()
        }
        return when {
            x != null && x.levelUpOff >= 0 -> words(u32le(rd(t.speciesInfo + species.toLong() * x.stride + x.levelUpOff, 4), 0))
            t.levelUpLearnsets != 0L -> words(u32le(rd(t.levelUpLearnsets + species * 4L, 4), 0))
            g != null && g.learnsets != 0L -> {
                if (species >= t.speciesCount) return null
                val ptr = u32le(rd(g.learnsets + species.toLong() * g.learnsetStride, 4), 0)
                if (!Gfx.inRom(ptr)) return emptyList()
                if (g.learnsetCfru) { // {u16 move, u8 level}, ending at level 0xFF
                    val raw = rd(ptr, 3 * 100)
                    (0 until 100).asSequence().map { u16le(raw, it * 3) to (raw[it * 3 + 2].toInt() and 0xFF) }
                        .takeWhile { it.second != 0xFF }.map { LevelMove(it.second, it.first) }.toList()
                } else { // u16 level << 9 | move, ending 0xFFFF
                    val raw = rd(ptr, 2 * 64)
                    (0 until 64).asSequence().map { u16le(raw, it * 2) }.takeWhile { it != 0xFFFF }
                        .map { LevelMove(it shr 9, it and 0x1FF) }.toList()
                }
            }
            else -> null
        }?.filter { it.move != 0 && it.level <= 100 }
    }

    /** TMs / HMs (vanilla: "TM06" labels) or expansion's teachable list (TMs, HMs and tutors,
     * unlabelled, by name); null = this game's aren't read. */
    fun teachable(t: PokedexTables, g: GuideTables?, species: Int): List<TeachMove>? {
        if (species <= 0 || t.cartDex && t.gen4 == null) return null
        syncMoves(t, g)
        teach[species]?.let { return it }
        t.gen4?.let { return Gen4Dex.machines(it, species)?.also { m -> teach[species] = m } }
        return runCatching { readTeachable(t, species) }
            .onFailure { android.util.Log.w("pokedaisy", "teachable $species failed", it) }
            .getOrNull()?.also { teach[species] = it }
    }

    /** Whether [t] reads TMs at all (no ROM read): the page says so when it doesn't. */
    fun hasTeachable(t: PokedexTables): Boolean =
        t.gen4 != null || !t.cartDex && ((t.expansion?.teachableOff ?: -1) >= 0 || (t.tmhmLearnsets != 0L && t.tmhmMoves != 0L))

    private fun readTeachable(t: PokedexTables, species: Int): List<TeachMove>? {
        val x = t.expansion
        if (x != null && x.teachableOff >= 0) {
            val ptr = u32le(rd(t.speciesInfo + species.toLong() * x.stride + x.teachableOff, 4), 0)
            if (!Gfx.inRom(ptr)) return emptyList()
            val raw = rd(ptr, 2 * 400)
            return (0 until 400).asSequence().map { u16le(raw, it * 2) }.takeWhile { it != 0xFFFF && it != 0 }
                .distinct().map { TeachMove("", it) }.toList()
        }
        if (t.tmhmLearnsets == 0L || t.tmhmMoves == 0L || species >= t.speciesCount) return null
        if (!tmhmChecks(t)) return null
        val bits = rd(t.tmhmLearnsets + species * 8L, 8)
        val moves = rd(t.tmhmMoves, 58 * 2)
        return (0 until 58).filter { (bits[it / 8].toInt() shr (it % 8)) and 1 != 0 }.map { i ->
            TeachMove(if (i < 50) "TM%02d".format(i + 1) else "HM%02d".format(i - 49), u16le(moves, i * 2))
        }
    }

    /** gTMHMMoves: TM01..TM50 then HM01..HM08's moves; null = not read (or not checked) for [t]. */
    fun tmhmMoves(t: PokedexTables): List<Int>? {
        if (t.cartDex || t.tmhmMoves == 0L || t.tmhmLearnsets == 0L) return null
        // The caches below are [t]'s (keeping the guide tables they were synced with, if [t]'s).
        syncMoves(t, movesFor?.takeIf { it.first == t }?.second)
        tmhm?.let { return it }
        if (!tmhmChecks(t)) return null
        return runCatching { rd(t.tmhmMoves, 58 * 2).let { raw -> List(58) { u16le(raw, it * 2) } } }.getOrNull()?.also { tmhm = it }
    }

    /** The TM tables hold what they should: TM01-04 FOCUS PUNCH, DRAGON CLAW, WATER PULSE, CALM MIND,
     * HM01 CUT, and BULBASAUR's 19 TMs / HMs (TOXIC ... ROCK SMASH). A randomized retail ROM's are at retail's place (its
     * hash says so) with random TMs and compatibility: there, every TM a move and the HMs retail's (no randomizer
     * touches them). */
    private fun tmhmChecks(t: PokedexTables): Boolean = tmhmOk ?: runCatching {
        val m = rd(t.tmhmMoves, 58 * 2)
        if (gameRandomized) return@runCatching (0 until 50).all { u16le(m, it * 2) in 1 until MOVES_GEN3 } &&
            (50 until 58).map { u16le(m, it * 2) } == RETAIL_HM_MOVES
        val bulbasaur = rd(t.tmhmLearnsets + 8, 8)
        var v = 0L
        for (i in 0 until 8) v = v or ((bulbasaur[i].toLong() and 0xFF) shl (8 * i))
        listOf(u16le(m, 0), u16le(m, 2), u16le(m, 4), u16le(m, 6), u16le(m, 100)) == listOf(264, 337, 352, 347, 15) &&
            v == BULBASAUR_TMHM
    }.getOrDefault(false).also { tmhmOk = it }

    private const val MOVES_GEN3 = 355
    /** HM01..HM08: CUT, FLY, SURF, STRENGTH, FLASH, ROCK SMASH, WATERFALL, DIVE. */
    private val RETAIL_HM_MOVES = listOf(15, 19, 57, 70, 148, 249, 127, 291)

    private val BULBASAUR_TMHM: Long = (listOf(6, 9, 10, 11, 17, 19, 21, 22, 27, 32, 36, 42, 43, 44, 45).map { it - 1 } +
        listOf(1, 4, 5, 6).map { 49 + it }).fold(0L) { acc, b -> acc or (1L shl b) }

    // ---- AREA ------------------------------------------------------------------

    fun cachedCatchSpots(g: GuideTables, species: Int): List<CatchSpot>? =
        if (spotsFor == g && spotsSeed == GuideRandomizer.wildSeed) spots?.let { it[species].orEmpty() } else null

    /** Where [species] is met in the wild, by section and method; empty = nowhere; null = no tables. */
    fun catchSpots(g: GuideTables?, species: Int): List<CatchSpot>? {
        if (g == null || !hasCatchSpots(g)) return null
        return index(g)?.let { it[species].orEmpty() }
    }

    /** species -> spots, built once per ROM from every wild header. */
    private fun index(g: GuideTables): Map<Int, List<CatchSpot>>? {
        val seed = GuideRandomizer.wildSeed
        if (spotsFor == g && spotsSeed == seed) return spots
        synchronized(this) {
            if (spotsFor == g && spotsSeed == seed) return spots
            val built = runCatching { buildIndex(g) }
                .onFailure { android.util.Log.w("pokedaisy", "wild index failed", it) }
                .getOrNull()
            spots = built
            spotsFor = g
            spotsSeed = seed
            return built
        }
    }

    private class Acc(var min: Int, var max: Int, var pct: Int, var times: Int)

    private fun buildIndex(g: GuideTables): Map<Int, List<CatchSpot>> {
        val keys = GuideRomSource.wildMapKeys(g)
        // species -> (mapsec, method) -> levels / odds / sets, in header order (roughly the story's).
        val acc = HashMap<Int, LinkedHashMap<Pair<Int, WildMethod>, Acc>>()
        val setsWith = HashMap<Pair<Int, WildMethod>, Int>()
        for (key in keys) {
            val group = key shr 8
            val num = key and 0xFF
            // No map table at all: the map's own id stands in (a negative mapsec, "MAP g.n").
            val mapsec = if (!MapSections.known(g)) -1 - key else MapSections.mapsec(g, group, num) ?: continue
            val sets = GuideRomSource.encounterSets(g, group, num)
            sets.forEachIndexed { set, e ->
                listOf(
                    WildMethod.GRASS to e.grass, WildMethod.SURF to e.water, WildMethod.ROCK_SMASH to e.rockSmash,
                    WildMethod.OLD_ROD to e.oldRod, WildMethod.GOOD_ROD to e.goodRod, WildMethod.SUPER_ROD to e.superRod,
                ).forEach { (method, slots) ->
                    if (slots.isEmpty()) return@forEach
                    val where = mapsec to method
                    setsWith[where] = (setsWith[where] ?: 0) or (1 shl set)
                    for (slot in slots) {
                        val a = acc.getOrPut(slot.species) { LinkedHashMap() }
                        val cur = a[where]
                        if (cur == null) a[where] = Acc(slot.minLevel, slot.maxLevel, slot.percent, 1 shl set)
                        else {
                            cur.min = minOf(cur.min, slot.minLevel); cur.max = maxOf(cur.max, slot.maxLevel)
                            cur.pct = maxOf(cur.pct, slot.percent); cur.times = cur.times or (1 shl set)
                        }
                    }
                }
            }
        }
        val multiSet = g.wildLayout == null && g.wildSets > 1
        return acc.mapValues { (_, m) ->
            m.map { (where, a) ->
                val all = setsWith[where] ?: 0
                CatchSpot(where.first, where.second, a.min, a.max, a.pct, if (multiSet && a.times != all) a.times else 0)
            }
        }
    }

    private fun rd(addr: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val chunk = minOf(4096, n - off)
            PokedexSource.reader.readCoreMemory(addr + off, chunk).copyInto(out, off, 0, chunk)
            off += chunk
        }
        return out
    }
}

/**
 * Map (group, num) -> its region map section, from the ROM's gMapGroups
 * ([GuideTables.mapGroups], checked against every wild map; found by shape in
 * the first 16 MB when it isn't set or doesn't check out - another language's
 * port that copied English's address).
 */
object MapSections {
    @Volatile private var cachedFor: GuideTables? = null
    @Volatile private var groups: Long = 0

    /** Whether [g]'s gMapGroups was found (else [mapsec] is always null). */
    fun known(g: GuideTables): Boolean = groupsFor(g) != 0L

    fun mapsec(g: GuideTables, group: Int, num: Int): Int? {
        val base = groupsFor(g)
        if (base == 0L) return null
        return runCatching {
            val header = header(g, base, group, num) ?: return null
            val b = rd(header + 0x14, 2)
            val id = if (g.mapSecWide) u16le(b, 0) else b[0].toInt() and 0xFF
            if (g.altMapSecGroups?.contains(group) == true) 0x100 + id else id
        }.getOrNull()
    }

    private fun header(g: GuideTables, base: Long, group: Int, num: Int): Long? {
        val list = u32le(rd(base + 4L * group, 4), 0) xor g.mapGroupsXor
        if (!Gfx.inRom(list)) return null
        val h = u32le(rd(list + 4L * num, 4), 0)
        if (!Gfx.inRom(h)) return null
        val hdr = rd(h, 8)
        return if (Gfx.inRom(u32le(hdr, 0)) && Gfx.inRom(u32le(hdr, 4))) h else null
    }

    fun romChanged() {
        synchronized(this) { cachedFor = null }
    }

    private fun groupsFor(g: GuideTables): Long {
        if (cachedFor == g) return groups
        synchronized(this) {
            if (cachedFor == g) return groups
            val keys = GuideRomSource.wildMapKeys(g)
            // Every wild map (SoulGold keeps a few headers for maps it dropped: 9 in 10 will do).
            fun fits(base: Long) = keys.isNotEmpty() &&
                keys.count { runCatching { header(g, base, it shr 8, it and 0xFF) }.getOrNull() != null } * 10 >= keys.size * 9
            groups = g.mapGroups.takeIf { it != 0L && runCatching { fits(it) }.getOrDefault(false) }
                ?: runCatching { scan(g, ::fits) }.onFailure { android.util.Log.w("pokedaisy", "gMapGroups scan failed", it) }.getOrNull()
                ?: 0L
            cachedFor = g
            return groups
        }
    }

    /** gMapGroups by shape: the longest run of pointers to lists of MapHeader pointers that fits every wild map. */
    private fun scan(g: GuideTables, fits: (Long) -> Boolean): Long? {
        val size = 0x1000000
        val rom = ByteArray(size)
        var off = 0
        while (off < size) {
            val chunk = runCatching { PokedexSource.reader.readCoreMemory(0x08000000L + off, 0x10000) }.getOrNull() ?: break
            chunk.copyInto(rom, off)
            off += chunk.size
        }
        fun word(a: Int) = if (a in 0..off - 4) u32le(rom, a) else 0L
        fun romPtr(p: Long) = p in 0x08000000L until 0x08000000L + off && p % 4 == 0L
        fun headerOk(p: Long): Boolean {
            if (!romPtr(p)) return false
            val o = (p - 0x08000000L).toInt()
            return romPtr(word(o)) && romPtr(word(o + 4))
        }
        var best: Long? = null
        var bestLen = 0
        var a = 0
        while (a < off - 4) {
            var n = 0
            while (n < 60) {
                val list = word(a + 4 * n)
                if (!romPtr(list) || !headerOk(word((list - 0x08000000L).toInt()))) break
                n++
            }
            if (n > bestLen && n >= 20 && fits(0x08000000L + a)) { best = 0x08000000L + a; bestLen = n }
            a += 4
        }
        return best
    }

    private fun rd(addr: Long, n: Int): ByteArray = PokedexSource.reader.readCoreMemory(addr, n)
}
