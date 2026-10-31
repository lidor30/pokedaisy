package com.pokedaisy.app.companion.data

/**
 * A Pokémon's IVs, EVs and nature - the summary's STATS page. Read from the
 * decrypted BoxPokemon (EVs substruct, Misc substruct's IV word), so only the
 * native RAM paths have it.
 *
 * Every list is in the game's own stat order ([STAT_HP] .. [STAT_SPDEF]):
 * HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF. [stats] are the party struct's
 * computed stats (max HP first) - the game only recomputes them on a level-up
 * (or a Rare Candy / evolution), so EVs gained since then don't show there yet.
 */
data class MonStats(
    val ivs: List<Int>,
    val evs: List<Int>,
    /** 0..24, Hardy..Quirky: the nature the stats use (an expansion Mint's included); Monomyth's own, which change no stat,
     * from [MONOMYTH_NATURE_BASE] on. */
    val nature: Int,
    val stats: List<Int>,
)

const val STAT_HP = 0
const val STAT_ATK = 1
const val STAT_DEF = 2
const val STAT_SPEED = 3
const val STAT_SPATK = 4
const val STAT_SPDEF = 5

const val MAX_IV = 31
const val MAX_TOTAL_EVS = 510

/** The summary screen's order (HP, ATTACK, DEFENSE, SP. ATK, SP. DEF, SPEED) - not the struct's. */
val SUMMARY_STAT_ORDER = listOf(STAT_HP, STAT_ATK, STAT_DEF, STAT_SPATK, STAT_SPDEF, STAT_SPEED)

private val natureNames = listOf(
    "Hardy", "Lonely", "Brave", "Adamant", "Naughty",
    "Bold", "Docile", "Relaxed", "Impish", "Lax",
    "Timid", "Hasty", "Serious", "Jolly", "Naive",
    "Modest", "Mild", "Quiet", "Bashful", "Rash",
    "Calm", "Gentle", "Sassy", "Careful", "Quirky",
)

fun natureName(nature: Int): String =
    // Monomyth's own (flavour-only) natures, in its own case.
    if (nature >= MONOMYTH_NATURE_BASE) natureNamesMonomyth.getOrElse(nature - MONOMYTH_NATURE_BASE) { "?" }
    else localText?.natures?.getOrNull(nature) ?: gameCase(natureNames.getOrElse(nature) { "?" })

// gNatureStatTable: nature / 5 is the raised stat, nature % 5 the lowered one,
// counting ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF; equal = neutral.
/** The stat ([STAT_ATK]..[STAT_SPDEF]) [nature] raises; null = a neutral nature. */
fun natureRaises(nature: Int): Int? = (1 + nature / 5).takeIf { nature in 0..24 && nature / 5 != nature % 5 }

/** The stat [nature] lowers; null = a neutral nature. */
fun natureLowers(nature: Int): Int? = (1 + nature % 5).takeIf { nature in 0..24 && nature / 5 != nature % 5 }

// Hidden Power's type index -> type, Fighting first (Normal and ??? are skipped).
private val hiddenPowerTypes = listOf(
    "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel",
    "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
)

private fun ivBits(ivs: List<Int>, bit: Int): Int =
    ivs.withIndex().sumOf { (i, iv) -> ((iv shr bit) and 1) shl i }

/** Hidden Power's type, as the running game's type table names it (hacks number types differently), from the IVs' low bits. */
fun hiddenPowerType(ivs: List<Int>): String {
    val name = hiddenPowerTypes[ivBits(ivs, 0) * 15 / 63]
    return activeTypeNames.values.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
}

/**
 * Hidden Power's power (30..70) from the IVs' second bits - the Gen 3 rule,
 * which only the retail games are known to keep (the
 * expansion hacks use Gen 6's flat 60); null where it isn't known.
 */
fun hiddenPowerPower(ivs: List<Int>): Int? =
    if (activeGame == GameKind.FIRERED || activeGame == GameKind.EMERALD || activeGame == GameKind.ORANGE_ISLANDS) ivBits(ivs, 1) * 40 / 63 + 30 else null
