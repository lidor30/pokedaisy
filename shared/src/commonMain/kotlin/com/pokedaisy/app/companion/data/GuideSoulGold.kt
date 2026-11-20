package com.pokedaisy.app.companion.data

/**
 * SoulGold's guide (v1.1.4, v1.2 and v1.2b share every id and flag). Johto's badges are flags
 * 0x993.. (CLAIR's 0x99A is handed over later, in the DRAGON'S DEN, so her gym's 0x4F7 marks her beaten). CHUCK, JASMINE and PRYCE can be
 * fought in any order and each brings a stronger team the more of the other two are beaten (their
 * gym scripts check 0x4F4 / 0x4F5 / 0x4F6). The League counts up VAR 0x4082 (WILL sets 2 .. KAREN 5,
 * LANCE 6); the Hall of Fame sets 0xA19, after which the Elite Four use rematch teams. Kanto's gyms
 * set FLAG_DEFEATED_* 0x26D..0x274; the order below follows their levels. Mt. Silver's GOLD /
 * CRYSTAL (by the player's gender) have the same team; 0x4F8 once beaten. HARD mode is a SaveBlock2
 * option bit SaveProgress can't see, so the teams with a HARD record show both.
 */
private const val FLAG_BADGE01_GET = 0x993 // .. 0x99A
private const val FLAG_SYS_GAME_CLEAR = 0x990
private const val FLAG_DEFEATED_CIANWOOD_GYM = 0x4F4
private const val FLAG_DEFEATED_OLIVINE_GYM = 0x4F5
private const val FLAG_DEFEATED_MAHOGANY_GYM = 0x4F6
private const val FLAG_DEFEATED_BLACKTHORN_GYM = 0x4F7
private const val FLAG_DEFEATED_PEWTER_GYM = 0x26D // Brock, Misty, Surge, Erika, Sabrina, Janine, Blaine, Blue
private const val FLAG_DEFEATED_MT_SILVER = 0x4F8
private const val FLAG_HALL_OF_FAME = 0xA19
private const val VAR_LEAGUE_STATE = 0x4082
private const val HARD = 1 shl TRAINER_TABLE_SHIFT // altTrainers[0]: gTrainers[DIFFICULTY_HARD]

private fun nh(id: Int) = listOf("NORMAL" to id, "HARD" to (id or HARD))

private fun middleGym(first: Int, second: Int, third: Int, otherA: Int, otherB: Int): (SaveProgress) -> Int = { p ->
    when ((if (p.flag(otherA)) 1 else 0) + (if (p.flag(otherB)) 1 else 0)) {
        0 -> first
        1 -> second
        else -> third
    }
}

private val CHUCK = middleGym(510, 442, 538, FLAG_DEFEATED_OLIVINE_GYM, FLAG_DEFEATED_MAHOGANY_GYM)
private val JASMINE = middleGym(513, 651, 180, FLAG_DEFEATED_CIANWOOD_GYM, FLAG_DEFEATED_MAHOGANY_GYM)
private val PRYCE = middleGym(546, 578, 707, FLAG_DEFEATED_CIANWOOD_GYM, FLAG_DEFEATED_OLIVINE_GYM)

private fun league(state: Int): (SaveProgress) -> Boolean =
    { p -> p.flag(FLAG_HALL_OF_FAME) || p.flag(FLAG_SYS_GAME_CLEAR) || p.variable(VAR_LEAGUE_STATE) >= state }

private fun johto(n: Int, title: String, where: String, id: Int) = Boss(title, where, FLAG_BADGE01_GET + n, variants = nh(id)) { id }
private fun middle(n: Int, title: String, where: String, pick: (SaveProgress) -> Int) =
    Boss(title, where, FLAG_BADGE01_GET + n, variantsFor = { p -> nh(pick(p)) }, trainer = pick)
private fun elite(state: Int, title: String, id: Int) = Boss(title, "POKéMON LEAGUE", 0, variants = nh(id), doneIf = league(state)) { id }
private fun kanto(n: Int, title: String, where: String, id: Int) = Boss(title, where, FLAG_DEFEATED_PEWTER_GYM + n) { id }

private val BOSSES_SOULGOLD = listOf(
    johto(0, "LEADER FALKNER", "VIOLET CITY GYM", 19),
    johto(1, "LEADER BUGSY", "AZALEA TOWN GYM", 596),
    johto(2, "LEADER WHITNEY", "GOLDENROD CITY GYM", 604),
    johto(3, "LEADER MORTY", "ECRUTEAK CITY GYM", 608),
    middle(4, "LEADER CHUCK", "CIANWOOD CITY GYM", CHUCK),
    middle(5, "LEADER JASMINE", "OLIVINE CITY GYM", JASMINE),
    middle(6, "LEADER PRYCE", "MAHOGANY TOWN GYM", PRYCE),
    Boss(
        "LEADER CLAIR", "BLACKTHORN CITY GYM", FLAG_DEFEATED_BLACKTHORN_GYM, variants = nh(541),
        doneIf = { it.flag(FLAG_DEFEATED_BLACKTHORN_GYM) || it.flag(FLAG_BADGE01_GET + 7) },
    ) { 541 },
    elite(2, "ELITE FOUR WILL", 736),
    elite(3, "ELITE FOUR KOGA", 383),
    elite(4, "ELITE FOUR BRUNO", 379),
    elite(5, "ELITE FOUR KAREN", 381),
    Boss(
        "CHAMPION LANCE", "POKéMON LEAGUE", FLAG_HALL_OF_FAME, variants = nh(249),
        doneIf = { p -> p.flag(FLAG_HALL_OF_FAME) || p.flag(FLAG_SYS_GAME_CLEAR) },
    ) { 249 },
    kanto(2, "LEADER LT. SURGE", "VERMILION CITY GYM", 302),
    kanto(3, "LEADER ERIKA", "CELADON CITY GYM", 303),
    kanto(1, "LEADER MISTY", "CERULEAN CITY GYM", 544),
    kanto(5, "LEADER JANINE", "FUCHSIA CITY GYM", 305),
    kanto(4, "LEADER SABRINA", "SAFFRON CITY GYM", 304),
    kanto(0, "LEADER BROCK", "PEWTER CITY GYM", 543),
    kanto(6, "LEADER BLAINE", "SEAFOAM ISLANDS", 306),
    kanto(7, "LEADER BLUE", "VIRIDIAN CITY GYM", 595),
    Boss("GOLD / CRYSTAL", "MT. SILVER", FLAG_DEFEATED_MT_SILVER, variants = nh(882)) { 882 },
)

internal val GUIDE_SOULGOLD = GameGuide(verified = false, bosses = BOSSES_SOULGOLD, pages = emptyList())
