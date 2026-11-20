package com.pokedaisy.app.companion.data

/**
 * Heart and Soul's guide. There's no hand-written text: WHERE IS is generated
 * from its area data ([generatedWhereIs]); NEXT BOSS follows its own scripts
 * (pokehns-expansion @ 2.0.6, the release's source - its `_hns` maps): the
 * badge order of include/constants/flags_hns.h, each gym leader's
 * trainerbattle, and the League's own progress var. Trainer ids are checked
 * against the release ROM's gTrainers (every leader's name at its id).
 */
private const val SYS_FLAGS = 0x860
private const val FLAG_BADGE01_GET = SYS_FLAGS + 0x20 // .. BADGE16 + 0x2F
private const val FLAG_IS_CHAMPION = SYS_FLAGS + 0x3F
private const val FLAG_DEFEATED_CIANWOOD_GYM = 0x22B
private const val FLAG_DEFEATED_OLIVINE_CITY_GYM = 0x22C
private const val FLAG_DEFEATED_MAHOGANY_TOWN_GYM = 0x22D
private const val FLAG_DEFEATED_BLACKTHORN_GYM = 0x22E // the RISING BADGE comes later, in the DRAGON'S DEN
private const val FLAG_DEFEATED_RED = 0x23B
private const val VAR_LEAGUE_STATE = 0x4070 // 2.. once WILL .. KAREN are beaten in a League run

/**
 * CHUCK, JASMINE and PRYCE can be fought in any order, and each brings a
 * stronger team the more of the other two you've beaten (their gym scripts
 * pick _1, _1_2 or _1_3).
 */
private fun middleGym(first: Int, second: Int, third: Int, otherA: Int, otherB: Int): (SaveProgress) -> Int = { p ->
    when ((if (p.flag(otherA)) 1 else 0) + (if (p.flag(otherB)) 1 else 0)) {
        0 -> first
        1 -> second
        else -> third
    }
}

private fun league(state: Int): (SaveProgress) -> Boolean = { p -> p.flag(FLAG_IS_CHAMPION) || p.variable(VAR_LEAGUE_STATE) >= state }

private val BOSSES_HEART_AND_SOUL = listOf(
    Boss("LEADER FALKNER", "VIOLET CITY GYM", FLAG_BADGE01_GET) { 402 },
    Boss("LEADER BUGSY", "AZALEA TOWN GYM", FLAG_BADGE01_GET + 1) { 404 },
    Boss("LEADER WHITNEY", "GOLDENROD CITY GYM", FLAG_BADGE01_GET + 2) { 406 },
    Boss("LEADER MORTY", "ECRUTEAK CITY GYM", FLAG_BADGE01_GET + 3) { 408 },
    Boss("LEADER CHUCK", "CIANWOOD CITY GYM", FLAG_BADGE01_GET + 4,
        trainer = middleGym(418, 420, 421, FLAG_DEFEATED_OLIVINE_CITY_GYM, FLAG_DEFEATED_MAHOGANY_TOWN_GYM)),
    Boss("LEADER JASMINE", "OLIVINE CITY GYM", FLAG_BADGE01_GET + 5,
        trainer = middleGym(414, 416, 417, FLAG_DEFEATED_CIANWOOD_GYM, FLAG_DEFEATED_MAHOGANY_TOWN_GYM)),
    Boss("LEADER PRYCE", "MAHOGANY TOWN GYM", FLAG_BADGE01_GET + 6,
        trainer = middleGym(410, 412, 413, FLAG_DEFEATED_OLIVINE_CITY_GYM, FLAG_DEFEATED_CIANWOOD_GYM)),
    Boss("LEADER CLAIR", "BLACKTHORN CITY GYM", FLAG_DEFEATED_BLACKTHORN_GYM) { 422 },
    Boss("ELITE FOUR WILL", "POKéMON LEAGUE", 0, doneIf = league(2)) { 433 },
    Boss("ELITE FOUR KOGA", "POKéMON LEAGUE", 0, doneIf = league(3)) { 439 },
    Boss("ELITE FOUR BRUNO", "POKéMON LEAGUE", 0, doneIf = league(4)) { 435 },
    Boss("ELITE FOUR KAREN", "POKéMON LEAGUE", 0, doneIf = league(5)) { 437 },
    Boss("CHAMPION LANCE", "POKéMON LEAGUE", FLAG_IS_CHAMPION) { 441 },
    Boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_BADGE01_GET + 8) { 424 },
    Boss("LEADER MISTY", "CERULEAN CITY GYM", FLAG_BADGE01_GET + 9) { 425 },
    Boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 10) { 426 },
    Boss("LEADER ERIKA", "CELADON CITY GYM", FLAG_BADGE01_GET + 11) { 427 },
    Boss("LEADER SABRINA", "SAFFRON CITY GYM", FLAG_BADGE01_GET + 12) { 428 },
    Boss("LEADER JANINE", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 13) { 429 },
    Boss("LEADER BLAINE", "SEAFOAM ISLANDS", FLAG_BADGE01_GET + 14) { 430 },
    Boss("LEADER BLUE", "VIRIDIAN CITY GYM", FLAG_BADGE01_GET + 15) { 431 },
    Boss("RED", "MT. SILVER SUMMIT", FLAG_DEFEATED_RED) { 464 },
)

// TIPS: only what its config says (include/config/battle.h, overworld.h - GEN_LATEST is GEN_9).
internal val GUIDE_HEART_AND_SOUL = GameGuide(
    verified = false,
    bosses = BOSSES_HEART_AND_SOUL,
    pages = listOf(
        page(
            "TIPS",
            section(
                "BATTLES",
                entry("Physical or special?", "Each move is physical or special on its own, as in newer games - not by its type."),
                entry("EXP from catching", "Catching a wild POKéMON gives your POKéMON EXP., like beating it would."),
            ),
            section(
                "WILD POKéMON",
                entry(
                    "Different POKéMON at night?",
                    "Yes - each area has its own morning, day, evening and night encounters. HERE lists the daytime ones.",
                ),
            ),
        ),
    ),
)
