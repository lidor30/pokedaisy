package com.pokedaisy.app.companion.data

/**
 * Pokémon Gaia's guide: WHERE IS from its area data (gen_guide_areas_rom.py),
 * NEXT BOSS from its gym and League scripts. The eight leaders are FireRed's
 * trainer slots 414..421 with FireRed's badge flags (each gym script battles
 * one and sets the next badge); the Elite Four rooms keep FireRed's scripts at
 * their old addresses but set their own flags (0x123..0x126) and battle
 * 410..413 (735..738 once flag 0x844 is set), the CHAMPION 438 (739). Places
 * are the region map's names for each gym's map.
 */
private const val FLAG_BADGE01_GET = 0x820
private const val FLAG_SYS_GAME_CLEAR = 0x82C
private const val FLAG_DEFEATED_E4_1 = 0x123

private fun elite(n: Int, title: String, trainer: Int) =
    Boss(title, "POKéMON LEAGUE", 0, doneIf = { p -> p.flag(FLAG_DEFEATED_E4_1 + n) || p.flag(FLAG_SYS_GAME_CLEAR) }) { trainer }

private val BOSSES_GAIA = listOf(
    Boss("LEADER FERNANDO", "SEROS VILLAGE GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER ARIA", "NESTPINE TOWN GYM", FLAG_BADGE01_GET + 1) { 415 },
    Boss("LEADER NINA", "WINDMIST CITY GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER VERNON", "VALOON TOWN GYM", FLAG_BADGE01_GET + 3) { 417 },
    Boss("LEADER SID", "TELMURK CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER WILL", "PRECIMOS ISLAND GYM", FLAG_BADGE01_GET + 5) { 419 },
    Boss("LEADER MARINA", "ATSAIL CITY GYM", FLAG_BADGE01_GET + 6) { 420 },
    Boss("LEADER RICHTER", "LOAMAS TOWN GYM", FLAG_BADGE01_GET + 7) { 421 },
    elite(0, "ELITE FOUR NICOLA", 410),
    elite(1, "ELITE FOUR KNIGHT", 411),
    elite(2, "ELITE FOUR KARA", 412),
    elite(3, "ELITE FOUR LEIF", 413),
    Boss("CHAMPION HERSCHEL", "POKéMON LEAGUE", FLAG_SYS_GAME_CLEAR) { 438 },
)

internal val GUIDE_GAIA = GameGuide(
    verified = false,
    bosses = BOSSES_GAIA,
    pages = emptyList(),
)
