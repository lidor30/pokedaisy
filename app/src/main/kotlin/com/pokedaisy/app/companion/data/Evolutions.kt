package com.pokedaisy.app.companion.data

import com.pokedaisy.app.companion.i18n.tr

/** One row of the ROM's evolution table: [method] (EVO_*), its [param], the [target] species. */
data class Evolution(val method: Int, val param: Int, val target: Int)

// include/constants/pokemon.h (identical in pokefirered and pokeemerald).
private const val EVO_FRIENDSHIP = 1
private const val EVO_FRIENDSHIP_DAY = 2
private const val EVO_FRIENDSHIP_NIGHT = 3
private const val EVO_LEVEL = 4
private const val EVO_TRADE = 5
private const val EVO_TRADE_ITEM = 6
private const val EVO_ITEM = 7
private const val EVO_LEVEL_ATK_GT_DEF = 8
private const val EVO_LEVEL_ATK_EQ_DEF = 9
private const val EVO_LEVEL_ATK_LT_DEF = 10
private const val EVO_LEVEL_SILCOON = 11
private const val EVO_LEVEL_CASCOON = 12
private const val EVO_LEVEL_NINJASK = 13
private const val EVO_LEVEL_SHEDINJA = 14
private const val EVO_BEAUTY = 15
private const val EVO_MAX = EVO_BEAUTY

private const val EVOS_PER_MON = 5
private const val EVO_STRIDE = 8

/** The spoiler-free half of an evolution: how, not into what (in the language on screen). */
fun evolutionKind(e: Evolution): String = when (e.method) {
    EVO_LEVEL, EVO_LEVEL_NINJASK -> tr("LEVEL UP")
    EVO_LEVEL_ATK_GT_DEF, EVO_LEVEL_ATK_EQ_DEF, EVO_LEVEL_ATK_LT_DEF -> tr("LEVEL UP (STATS)")
    EVO_LEVEL_SILCOON, EVO_LEVEL_CASCOON -> tr("LEVEL UP (RANDOM)")
    EVO_LEVEL_SHEDINJA -> tr("LEVEL UP (BONUS)")
    EVO_FRIENDSHIP, EVO_FRIENDSHIP_DAY, EVO_FRIENDSHIP_NIGHT -> tr("FRIENDSHIP")
    EVO_TRADE, EVO_TRADE_ITEM -> tr("TRADE")
    EVO_ITEM -> tr("USE AN ITEM")
    EVO_BEAUTY -> tr("BEAUTY")
    else -> tr("SPECIAL")
}

/** The whole story, e.g. "LV 16 → IVYSAUR", "FIRE STONE → ARCANINE" (names from the game, words translated). */
fun evolutionText(e: Evolution): String {
    val how = when (e.method) {
        EVO_LEVEL -> tr("LV {0}", e.param)
        EVO_LEVEL_ATK_GT_DEF -> tr("LV {0}, ATTACK > DEFENSE", e.param)
        EVO_LEVEL_ATK_EQ_DEF -> tr("LV {0}, ATTACK = DEFENSE", e.param)
        EVO_LEVEL_ATK_LT_DEF -> tr("LV {0}, ATTACK < DEFENSE", e.param)
        EVO_LEVEL_SILCOON, EVO_LEVEL_CASCOON -> tr("LV {0} (depends on the POKéMON)", e.param)
        EVO_LEVEL_NINJASK -> tr("LV {0}", e.param)
        EVO_LEVEL_SHEDINJA -> tr("LV {0}, with a free party slot and a spare POKé BALL", e.param)
        EVO_FRIENDSHIP -> tr("Level up with high friendship")
        EVO_FRIENDSHIP_DAY -> tr("Level up with high friendship, by day")
        EVO_FRIENDSHIP_NIGHT -> tr("Level up with high friendship, at night")
        EVO_TRADE -> tr("Trade")
        EVO_TRADE_ITEM -> tr("Trade holding {0}", itemName(e.param))
        EVO_ITEM -> itemName(e.param)
        EVO_BEAUTY -> tr("Level up with BEAUTY {0}+", e.param)
        else -> "?"
    }
    return "$how → ${speciesName(e.target)}"
}

/** A plain level-up - the evolutions that need no explaining. */
fun Evolution.isPlainLevel(): Boolean = method == EVO_LEVEL

/**
 * The ROM's evolution table ([PokedexTables.evolutions]), read once and
 * cached - like [PokedexSource], whose [PokedexSource.reader] it shares.
 */
object EvolutionSource {
    @Volatile private var cached: Pair<PokedexTables, List<List<Evolution>>>? = null

    /** Already-loaded table only (no ROM read). */
    fun cached(t: PokedexTables): List<List<Evolution>>? = cached?.takeIf { it.first == t }?.second

    /** Another ROM: a randomized retail one shares retail's [PokedexTables] but not its evolutions. */
    fun romChanged() { cached = null }

    /** Evolutions by species id, or null if [t] has no table or it doesn't check out. */
    fun table(t: PokedexTables): List<List<Evolution>>? {
        if (t.evolutions == 0L) return null
        cached(t)?.let { return it }
        val table = runCatching { read(t) }
            .onFailure { android.util.Log.w("pokedaisy", "evolution table failed", it) }
            .getOrNull() ?: return null
        cached = t to table
        return table
    }

    private fun read(t: PokedexTables): List<List<Evolution>>? {
        val raw = ByteArray(t.speciesCount * EVOS_PER_MON * EVO_STRIDE)
        var off = 0
        while (off < raw.size) {
            val n = minOf(4096, raw.size - off)
            PokedexSource.reader.readCoreMemory(t.evolutions + off, n).copyInto(raw, off)
            off += n
        }
        val table = List(t.speciesCount) { s ->
            (0 until EVOS_PER_MON).mapNotNull { i ->
                val o = (s * EVOS_PER_MON + i) * EVO_STRIDE
                val method = u16le(raw, o)
                val target = u16le(raw, o + 4)
                if (method in 1..EVO_MAX && target in 1 until t.speciesCount) Evolution(method, u16le(raw, o + 2), target) else null
            }
        }
        // A hack of the same size that moved the table must not get wrong data:
        // BULBASAUR evolves at 16 into species 2. A randomized retail ROM's is at retail's place (its hash says so)
        // with the randomizer's evolutions in it.
        return table.takeIf { gameRandomized || it.getOrNull(1)?.firstOrNull() == Evolution(EVO_LEVEL, 16, 2) }
    }
}
