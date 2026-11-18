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
     * from [MONOMYTH_NATURE_BASE] on; [NO_NATURE] in Gen 1 / 2. */
    val nature: Int,
    val stats: List<Int>,
    /** What [ivs] / [evs] are: Gen 3's IVs and EVs, or a Game Boy game's DVs and stat experience. */
    val kind: StatKind = StatKind.GBA,
    /** Gen 3's aptitude bit (the IV word's top bit): 0 = the species' first ability, 1 = its second; null = not read. */
    val abilityNum: Int? = null,
)

/**
 * How a game keeps a Pokémon's hidden stats. The Game Boy games have DVs (0-15) where Gen 3 has IVs: Gen 1 / 2 four of
 * them (Attack, Defense, Speed, Special; HP's made of their low bits) and stat experience (0-65535, Special one value) for
 * EVs, so their lists carry Special in both [STAT_SPATK] and [STAT_SPDEF] - Gen 1's [MonStats.stats] too, it has one
 * Special stat. Polished Crystal keeps six DVs, Gen 3-style EVs and a nature. [GBA] is Gen 3's IVs and EVs.
 */
enum class StatKind(val ivMax: Int) {
    GBA(MAX_IV),
    GEN1(MAX_DV),
    GEN2(MAX_DV),
    POLISHED(MAX_DV);

    /** DVs rather than IVs. */
    val dvs: Boolean get() = this != GBA
    /** Stat experience rather than EVs (no 510 cap, Special one value). */
    val statExp: Boolean get() = this == GEN1 || this == GEN2
    /** The stats a total counts once: Gen 1 / 2's Special is one value shown twice. */
    val distinctStats: List<Int> get() = if (statExp) listOf(STAT_HP, STAT_ATK, STAT_DEF, STAT_SPEED, STAT_SPATK) else SUMMARY_STAT_ORDER
}

const val NO_NATURE = -1
const val MAX_DV = 15

/**
 * Gen 1 / 2's DVs from their two bytes (Attack | Defense, Speed | Special, high nibble first) in [MonStats.ivs]' order;
 * HP's DV is the four's low bits, Attack's the highest.
 */
fun gbDvs(b0: Int, b1: Int): List<Int> {
    val atk = (b0 shr 4) and 0xF
    val def = b0 and 0xF
    val spd = (b1 shr 4) and 0xF
    val spc = b1 and 0xF
    val hp = ((atk and 1) shl 3) or ((def and 1) shl 2) or ((spd and 1) shl 1) or (spc and 1)
    return listOf(hp, atk, def, spd, spc, spc)
}

/** Polished Crystal's three DV bytes (HP | Attack, Defense | Speed, Sp. Atk | Sp. Def), in [MonStats.ivs]' order. */
fun polishedDvs(b0: Int, b1: Int, b2: Int): List<Int> =
    listOf((b0 shr 4) and 0xF, b0 and 0xF, (b1 shr 4) and 0xF, b1 and 0xF, (b2 shr 4) and 0xF, b2 and 0xF)

/** Polished Crystal's nature: the personality byte's low 5 bits, numbered as Gen 3's; [NO_NATURE] past Quirky. */
fun polishedNature(personality: Int): Int = (personality and 0x1F).takeIf { it < 25 } ?: NO_NATURE

/**
 * Whether [MonStats.abilityNum] names the ability in this game: only where the engine is the original games' (one bit,
 * two abilities a species, nothing else). Games with hidden abilities keep them elsewhere (CFRU's own bit, the expansion's
 * two-bit slot), so there the bit alone would name the wrong one: no row rather than a guess.
 */
fun abilitySlotReadable(tables: PokedexTables, game: GameKind = activeGame): Boolean =
    (game == GameKind.FIRERED || game == GameKind.EMERALD) && tables.hiddenAbilityOff < 0 && tables.expansion == null

/** The species' ability for [slot] (the game's rule: slot 1 with no second ability is the first). */
fun abilityForSlot(abilities: List<String>, slot: Int): String? {
    val names = abilities.filter { it.isNotBlank() }
    return names.getOrNull(slot) ?: names.firstOrNull()
}

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

/**
 * Whether the summary can name [stats]' Hidden Power: Gen 3 always; Gen 2 by its DVs only in the games that kept its rule
 * and type order (Gold / Silver, Crystal and Crystal Clear - Prism added types, Polished Crystal's rule isn't mapped);
 * Gen 1 has no Hidden Power.
 */
fun hasHiddenPower(stats: MonStats): Boolean = when (stats.kind) {
    StatKind.GBA -> true
    StatKind.GEN2 -> activeGame.isGoldSilver || activeGame == GameKind.CRYSTAL || activeGame == GameKind.CRYSTAL_CLEAR
    else -> false
}

/** Gen 2's Hidden Power type (pokecrystal's HiddenPowerDamage): Attack's and Defense's DVs' low 2 bits, Fighting first. */
fun gen2HiddenPowerType(dvs: List<Int>): String {
    val name = hiddenPowerTypes[((dvs[STAT_ATK] and 3) shl 2) or (dvs[STAT_DEF] and 3)]
    return activeTypeNames.values.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
}

/** Gen 2's Hidden Power power (31..70): the DVs' top bits, Attack's the highest, x5, plus Special's low 2 bits, halved. */
fun gen2HiddenPowerPower(dvs: List<Int>): Int {
    fun top(s: Int) = (dvs[s] shr 3) and 1
    val bits = 8 * top(STAT_ATK) + 4 * top(STAT_DEF) + 2 * top(STAT_SPEED) + top(STAT_SPATK)
    return (5 * bits + (dvs[STAT_SPATK] and 3)) / 2 + 31
}

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
    if (activeGame == GameKind.FIRERED || activeGame == GameKind.EMERALD || activeGame == GameKind.ORANGE_ISLANDS || retailDsGame)
        ivBits(ivs, 1) * 40 / 63 + 30 else null
