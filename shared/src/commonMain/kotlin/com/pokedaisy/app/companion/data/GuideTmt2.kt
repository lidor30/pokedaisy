package com.pokedaisy.app.companion.data

/**
 * Too Many Types 2's guide. The game starts in LILYCOVE and runs Hoenn's gyms backwards - the order
 * below follows the teams' levels (WINONA 12-15 ... TATE AND LIZA 52-56). Gym badges are Emerald's;
 * its Elite Four flags moved to 0x49E..0x4A1. The first room (Sidney's map) holds the rival - BRENDAN
 * or MAY by the player's gender, the same team either way, one per starter (VAR_STARTER_MON). The
 * champion is STEVEN, trainer 261 (Sidney's old id), fought in the champion room.
 */
private const val FLAG_BADGE01_GET = 0x867
private const val FLAG_IS_CHAMPION = 0x87F
private const val FLAG_DEFEATED_ELITE_4_1 = 0x49E // .. 0x4A1
private const val VAR_STARTER_MON = 0x4023
private const val TRAINER_RIVAL_E4 = 912 // + the starter (915.. are MAY's, the same teams)

private fun elite(n: Int, title: String, trainer: (SaveProgress) -> Int) = Boss(
    title, "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_1 + n,
    doneIf = { p -> p.flag(FLAG_DEFEATED_ELITE_4_1 + n) || p.flag(FLAG_IS_CHAMPION) }, trainer = trainer,
)

private val BOSSES_TMT2 = listOf(
    Boss("LEADER WINONA", "FORTREE CITY GYM", FLAG_BADGE01_GET + 5) { 270 },
    Boss("LEADER WATTSON", "MAUVILLE CITY GYM", FLAG_BADGE01_GET + 2) { 267 },
    Boss("LEADER FLANNERY", "LAVARIDGE TOWN GYM", FLAG_BADGE01_GET + 3) { 268 },
    Boss("LEADER ROXANNE", "RUSTBORO CITY GYM", FLAG_BADGE01_GET) { 265 },
    Boss("LEADER NORMAN", "PETALBURG CITY GYM", FLAG_BADGE01_GET + 4) { 269 },
    Boss("LEADER BRAWLY", "DEWFORD TOWN GYM", FLAG_BADGE01_GET + 1) { 266 },
    Boss("LEADER JUAN", "SOOTOPOLIS CITY GYM", FLAG_BADGE01_GET + 7) { 272 },
    Boss("LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", FLAG_BADGE01_GET + 6) { 271 },
    elite(0, "ELITE FOUR BRENDAN / MAY") { p -> TRAINER_RIVAL_E4 + p.variable(VAR_STARTER_MON).coerceIn(0, 2) },
    elite(1, "ELITE FOUR MARIO") { 262 },
    elite(2, "ELITE FOUR KIERAN") { 263 },
    elite(3, "ELITE FOUR ZINNIA") { 264 },
    Boss("CHAMPION STEVEN", "POKéMON LEAGUE", FLAG_IS_CHAMPION) { 261 },
)

internal val GUIDE_TMT2 = GameGuide(verified = false, bosses = BOSSES_TMT2, pages = emptyList())
