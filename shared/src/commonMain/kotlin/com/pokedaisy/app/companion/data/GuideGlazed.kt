package com.pokedaisy.app.companion.data

/**
 * Pokémon Glazed's guide: NEXT BOSS for Tunod's eight gyms, HERE from its wild
 * tables (GUIDE_TABLES_GLAZED). Its leaders sit in retail Emerald's gym
 * leader slots (trainers 265..272: SPARKY, TERRY, FLO, LIEF, IRENE, ERNEST,
 * NICOLE, TYSON) and each one's gym script sets retail's next badge flag
 * (0x867..0x86E) after the battle - read from the scripts' trainerbattle /
 * setflag bytecode. Its gyms' maps reuse retail's map slots with stale
 * section ids, so they're named by badge order rather than by town. Johto's
 * gyms (FALKNER 258, BUGSY 259, ...) aren't listed: their badges aren't plain
 * flags the scripts set.
 */
private const val FLAG_BADGE01_GET = 0x867

private val BOSSES_GLAZED = listOf("SPARKY", "TERRY", "FLO", "LIEF", "IRENE", "ERNEST", "NICOLE", "TYSON")
    .mapIndexed { i, name -> Boss("LEADER $name", "TUNOD GYM ${i + 1}", FLAG_BADGE01_GET + i) { 265 + i } }

internal val GUIDE_GLAZED = GameGuide(
    verified = false,
    bosses = BOSSES_GLAZED,
    pages = emptyList(),
)
