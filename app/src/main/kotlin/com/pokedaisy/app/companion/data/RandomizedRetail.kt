package com.pokedaisy.app.companion.data

import java.io.InputStream

/**
 * Retail FireRed (rev 0 / 1) or Emerald (English) as Universal Pokémon Randomizer ZX leaves it ([RANDOMIZED_RETAIL],
 * from `scripts/gen_randomized_retail.py`): bytes [0, [end]) with the species names upper-cased ([fold]) and every
 * site the randomizer may write zeroed ([mask], on top of [RetailDumps]' ranges) hash to [sha1]. Such a ROM is retail's
 * code, RAM and table addresses with the randomizer's data in them, so it's read as retail and what the randomizer
 * changed is read from the ROM ([RandomizedRetail.read]). docs/DEVELOPMENT.md's "Randomized ROMs".
 */
class RandomizedRetailRom(
    val code: String,
    val rev: Int,
    val end: Int,
    val sha1: String,
    /** The retail cart's SHA-1 and CRC32: the game it is, for whatever goes by those ([WideScreen]'s tables). */
    val retailSha1: String,
    val retailCrc: Int,
    /** gBaseStats, gBattleMoves, gTypeEffectiveness and the in-game trade table (sIngameTrades), at retail's places. */
    val baseStats: Long,
    val battleMoves: Long,
    val typeChart: Long,
    val trades: Long,
    /** UPR's sites: "start:len[:count:stride]" (hex), comma-separated ([ranges] expands it; the website reads it as is). */
    val mask: String,
    /** The species names: "start:len" (hex). */
    val fold: String,
    fieldItems: String,
    tradeRows: String,
    statics: String,
) {
    /** Every byte the randomizer may write, merged: "start:len[:count:stride]" (hex) expanded. */
    val ranges: List<IntRange> by lazy { maskRanges(mask) }
    val foldRanges: List<IntRange> by lazy { maskRanges(fold) }
    /** The item id of each item ball / hidden item, by the flag the GUIDE knows it by: a ROM address. */
    val itemSites: Map<Int, Long> by lazy { pairs(fieldItems).associate { (f, o) -> f.toInt(16) to 0x08000000L + o.toLong(16) } }
    /** Each in-game trade's row in [trades], by retail's "given/wanted" species (decimal). */
    val tradeRowOf: Map<String, Int> by lazy { pairs(tradeRows).associate { (k, r) -> k to r.toInt() } }
    /** A gift / static Pokémon's species bytes, by retail's species (when it's the only static of that species). */
    val staticSites: Map<Int, Long> by lazy { pairs(statics).associate { (s, o) -> s.toInt(16) to 0x08000000L + o.toLong(16) } }

    private fun pairs(s: String) = s.split(',').filter { it.isNotEmpty() }.map { it.substringBefore(':') to it.substringAfter(':') }

    companion object {
        /** "start:len[:count:stride]" entries (hex, comma-separated) as merged, sorted ranges. */
        fun maskRanges(s: String): List<IntRange> {
            val raw = ArrayList<IntRange>()
            for (e in s.split(',')) {
                if (e.isEmpty()) continue
                val f = e.split(':').map { it.toInt(16) }
                val count = f.getOrElse(2) { 1 }
                val stride = f.getOrElse(3) { 0 }
                for (i in 0 until count) raw += (f[0] + i * stride).let { it until it + f[1] }
            }
            raw.sortBy { it.first }
            val out = ArrayList<IntRange>()
            for (r in raw) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last)
                else out += r
            }
            return out
        }
    }
}

/**
 * The GUIDE's area data (GuideAreas*Gen.kt) as a randomized ROM has it: each item ball's / hidden item's item by its
 * flag, each in-game trade's (given, wanted) species by retail's "given/wanted", each gift / static Pokémon's species by
 * retail's. HERE lists these instead of retail's ([randomizedAreas]); what isn't here it leaves out.
 */
class RandomizedGuide(
    val items: Map<Int, Int>,
    val trades: Map<String, Pair<Int, Int>>,
    val statics: Map<Int, Int>,
)

object RandomizedRetail {
    /** Species rows gBaseStats has (internal ids 0..411: CHIMECHO) and gBattleMoves' moves (0..354: PSYCHO BOOST). */
    private const val SPECIES = 412
    private const val MOVES = 355
    private const val BASE_STRIDE = 28
    private const val MOVE_STRIDE = 12

    /** The randomized retail ROM a ROM of this [code] / [rev] and [size] is, reading it from [input] (from its start, in
     * order), or null. */
    fun matchStream(code: String?, rev: Int?, size: Long, input: () -> InputStream): RandomizedRetailRom? {
        val rom = candidate(code, rev, size) ?: return null
        return input().use { s -> rom.takeIf { hash(it) { len -> RetailDumps.readFully(s, len) } == it.sha1 } }
    }

    /** [matchStream] over a reader that gives [len] bytes at an offset (the game's bus), in order. */
    fun match(code: String?, rev: Int?, size: Long, read: (offset: Long, len: Int) -> ByteArray?): RandomizedRetailRom? {
        val rom = candidate(code, rev, size) ?: return null
        var off = 0L
        return rom.takeIf { hash(it) { len -> read(off, len)?.takeIf { b -> b.size == len }.also { off += len } } == it.sha1 }
    }

    private fun candidate(code: String?, rev: Int?, size: Long): RandomizedRetailRom? =
        RANDOMIZED_RETAIL.firstOrNull { it.code == code && it.rev == rev }?.takeIf { size >= it.end && size <= 0x1000000L }

    private fun hash(rom: RandomizedRetailRom, next: (Int) -> ByteArray?): String? =
        RetailDumps.maskedSha1(rom.end, rom.ranges, rom.foldRanges, next)

    /**
     * What the randomizer changed that the app otherwise takes from its own retail tables, read from the running ROM:
     * species types and growth rates (gBaseStats), each move's type and power (with retail's names) and its effect,
     * accuracy, PP, chance and priority (gBattleMoves), and the type chart (gTypeEffectiveness, which a UPR tweak
     * updates). Laid over retail's like a hack's own tables ([HackText], [randomizedRetail]).
     */
    fun read(c: MemoryReader, rom: RandomizedRetailRom): HackText {
        val base = readAll(c, rom.baseStats, SPECIES * BASE_STRIDE)
        val types = (1 until SPECIES).associateWith { s ->
            SpeciesTypes(base[s * BASE_STRIDE + 6].toInt() and 0xFF, base[s * BASE_STRIDE + 7].toInt() and 0xFF)
        }
        val growth = ByteArray(SPECIES) { s -> base[s * BASE_STRIDE + 19] }
        val raw = readAll(c, rom.battleMoves, MOVES * MOVE_STRIDE)
        val moves = HashMap<Int, MoveInfo>()
        val stats = ArrayList<MoveStats>(MOVES)
        for (m in 0 until MOVES) {
            val o = m * MOVE_STRIDE
            fun b(i: Int) = raw[o + i].toInt() and 0xFF
            stats += MoveStats(vanillaMoveEffectNames.getOrElse(b(0)) { "UNKNOWN" }, b(3), b(4), b(5), raw[o + 7].toInt())
            if (m > 0) moveData[m]?.let { moves[m] = MoveInfo(it.name, b(2), b(1)) }
        }
        return HackText(
            species = emptyMap(), types = types, moves = moves, items = emptyMap(), mapSecs = emptyMap(),
            typeChart = typeChart(readAll(c, rom.typeChart, 0x200)),
            moveStats = stats, growthRates = growth, randomized = rom, randomizedGuide = readGuide(c, rom),
        )
    }

    /** What the GUIDE's area data names that the randomizer changes, as this ROM has it ([RandomizedGuide]). */
    private fun readGuide(c: MemoryReader, rom: RandomizedRetailRom): RandomizedGuide {
        fun u16(at: Long) = c.readCoreMemory(at, 2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
        return RandomizedGuide(
            items = rom.itemSites.mapValues { (_, at) -> u16(at) },
            trades = rom.tradeRowOf.mapValues { (_, row) -> rom.trades + row * TRADE_STRIDE.toLong() }
                .mapValues { (_, at) -> u16(at + TRADE_GIVEN) to u16(at + TRADE_WANTED) },
            statics = rom.staticSites.mapValues { (_, at) -> u16(at) },
        )
    }

    private const val TRADE_STRIDE = 60    // struct InGameTrade
    private const val TRADE_GIVEN = 12     // its species
    private const val TRADE_WANTED = 56    // requestedSpecies

    /** gTypeEffectiveness' (attacker, defender, x10) triples to the app's atk*100+def -> percent, up to its 0xFF end
     * (the 0xFE marker - Foresight's own ghost entries follow it - skipped). */
    fun typeChart(raw: ByteArray): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        var i = 0
        while (i + 2 < raw.size) {
            val atk = raw[i].toInt() and 0xFF
            if (atk == 0xFF) break
            if (atk != 0xFE) out[atk * 100 + (raw[i + 1].toInt() and 0xFF)] = (raw[i + 2].toInt() and 0xFF) * 10
            i += 3
        }
        return out
    }

    private fun readAll(c: MemoryReader, at: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = minOf(4096, len - off)
            c.readCoreMemory(at + off, n).copyInto(out, off)
            off += n
        }
        return out
    }
}
