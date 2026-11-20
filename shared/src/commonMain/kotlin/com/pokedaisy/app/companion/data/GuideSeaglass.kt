package com.pokedaisy.app.companion.data

/**
 * Emerald Seaglass's guide: its gym, Elite Four and champion scripts are Emerald's (the same maps,
 * trainer ids, badge and FLAG_DEFEATED_ELITE_4_* flags; the teams are its own). HERE from
 * GUIDE_TABLES_SEAGLASS.
 */
private const val FLAG_BADGE01_GET = 0x867
private const val FLAG_SYS_GAME_CLEAR = 0x864
private const val FLAG_IS_CHAMPION = 0x87F
private const val FLAG_DEFEATED_ELITE_4_SIDNEY = 0x4FB // .. 0x4FE

private fun elite(n: Int, title: String, trainer: Int) = Boss(
    title, "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + n,
    doneIf = { p -> p.flag(FLAG_DEFEATED_ELITE_4_SIDNEY + n) || p.flag(FLAG_IS_CHAMPION) },
) { trainer }

private val BOSSES_SEAGLASS = listOf(
    Boss("LEADER ROXANNE", "RUSTBORO CITY GYM", FLAG_BADGE01_GET) { 265 },
    Boss("LEADER BRAWLY", "DEWFORD TOWN GYM", FLAG_BADGE01_GET + 1) { 266 },
    Boss("LEADER WATTSON", "MAUVILLE CITY GYM", FLAG_BADGE01_GET + 2) { 267 },
    Boss("LEADER FLANNERY", "LAVARIDGE TOWN GYM", FLAG_BADGE01_GET + 3) { 268 },
    Boss("LEADER NORMAN", "PETALBURG CITY GYM", FLAG_BADGE01_GET + 4) { 269 },
    Boss("LEADER WINONA", "FORTREE CITY GYM", FLAG_BADGE01_GET + 5) { 270 },
    Boss("LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", FLAG_BADGE01_GET + 6) { 271 },
    Boss("LEADER JUAN", "SOOTOPOLIS CITY GYM", FLAG_BADGE01_GET + 7) { 272 },
    elite(0, "ELITE FOUR SIDNEY", 261),
    elite(1, "ELITE FOUR PHOEBE", 262),
    elite(2, "ELITE FOUR GLACIA", 263),
    elite(3, "ELITE FOUR DRAKE", 264),
    Boss(
        "CHAMPION WALLACE", "POKéMON LEAGUE", FLAG_IS_CHAMPION,
        doneIf = { p -> p.flag(FLAG_IS_CHAMPION) || p.flag(FLAG_SYS_GAME_CLEAR) },
    ) { 335 },
)

internal val GUIDE_SEAGLASS = GameGuide(verified = false, bosses = BOSSES_SEAGLASS, pages = emptyList())
