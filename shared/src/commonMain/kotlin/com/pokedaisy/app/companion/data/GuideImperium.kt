package com.pokedaisy.app.companion.data

/**
 * Emerald Imperium's guide: NEXT BOSS from its gym and League scripts (trainerbattle /
 * setflag bytecode, walked through gMapGroups), HERE from GUIDE_TABLES_IMPERIUM. The gyms
 * keep Emerald's maps and badge flags; TATE and LIZA are two trainers (568 / 569) in one
 * battle. Its Elite Four rooms run `random 2` and battle one of two teams, DRAKE's room holds
 * CYNTHIA (860), and the champion battle is STEVEN (590) and WALLACE (589) together. The HALL
 * OF FAME sets FLAG_IS_CHAMPION; the Elite Four flags are cleared again after it.
 */
private const val FLAG_BADGE01_GET = 0x867
private const val FLAG_IS_CHAMPION = 0x87F
private const val FLAG_DEFEATED_ELITE_4_SIDNEY = 0x4FB // .. 0x4FE

private fun elite(n: Int, title: String, vararg teams: Pair<String, Int>) =
    Boss(
        title, "POKéMON LEAGUE", 0, variants = teams.toList(),
        doneIf = { p -> p.flag(FLAG_DEFEATED_ELITE_4_SIDNEY + n) || p.flag(FLAG_IS_CHAMPION) },
    ) { teams.first().second }

private val BOSSES_IMPERIUM = listOf(
    Boss("LEADER ROXANNE", "RUSTBORO CITY GYM", FLAG_BADGE01_GET) { 265 },
    Boss("LEADER BRAWLY", "DEWFORD TOWN GYM", FLAG_BADGE01_GET + 1) { 266 },
    Boss("LEADER WATTSON", "MAUVILLE CITY GYM", FLAG_BADGE01_GET + 2) { 267 },
    Boss("LEADER FLANNERY", "LAVARIDGE TOWN GYM", FLAG_BADGE01_GET + 3) { 268 },
    Boss("LEADER NORMAN", "PETALBURG CITY GYM", FLAG_BADGE01_GET + 4) { 269 },
    Boss("LEADER WINONA", "FORTREE CITY GYM", FLAG_BADGE01_GET + 5) { 270 },
    Boss("LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", FLAG_BADGE01_GET + 6, variants = listOf("TATE" to 568, "LIZA" to 569)) { 568 },
    Boss("LEADER JUAN", "SOOTOPOLIS CITY GYM", FLAG_BADGE01_GET + 7) { 272 },
    elite(0, "ELITE FOUR SIDNEY", "TEAM 1" to 261, "TEAM 2" to 586),
    elite(1, "ELITE FOUR PHOEBE", "TEAM 1" to 262, "TEAM 2" to 587),
    elite(2, "ELITE FOUR GLACIA", "TEAM 1" to 263, "TEAM 2" to 588),
    elite(3, "ELITE FOUR CYNTHIA", "TEAM" to 860),
    Boss("CHAMPIONS STEVEN AND WALLACE", "POKéMON LEAGUE", FLAG_IS_CHAMPION, variants = listOf("STEVEN" to 590, "WALLACE" to 589)) { 590 },
)

internal val GUIDE_IMPERIUM = GameGuide(verified = false, bosses = BOSSES_IMPERIUM, pages = emptyList())
