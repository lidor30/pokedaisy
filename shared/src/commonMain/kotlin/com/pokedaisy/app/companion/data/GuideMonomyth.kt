package com.pokedaisy.app.companion.data

/**
 * Pokémon Monomyth's guide: NEXT BOSS from its scripts and HERE from GUIDE_TABLES_MONOMYTH. Each leader's script runs its
 * trainerbattle and then sets one of its badge flags (0x887 + n, vanilla's moved up by 0x20 like its other system flags):
 * BASTION (CATSEAR CITY), SANTANA (HOLLOW CANYON), AUGUSTUS (MANDRAKE CITY), FABRIZIA (NORTH EDELWEISS), RINRI (WOOD
 * AVENS), ARIEL (CASCADE CITY), PIPER (BLAZING PATH), HEM and LOCKE together (FORT ELDERFLOWER); the places are the map
 * sections of the maps whose people (or step triggers) run those scripts. Then the Elite Four at IRONBARK PLATEAU and
 * CHAMPION STENVARD at IRONBARK PEAK, done by their trainer flags (0x500 + id: the user's save has BUG CATCHER TIM's
 * 0x501 and BASTION's 0x524 set, with badge 0x887).
 *
 * Heroic mode (flag 0x8A3: each leader's script checks it and battles another trainer) fights other teams: the leaders'
 * and the Elite Four's are 551..564, in the same order; the champion has one team.
 */
private const val FLAG_BADGE01_GET = 0x887
private const val TRAINER_FLAGS_START = 0x500
private const val FLAG_HEROIC = 0x8A3

/** The team the save's difficulty fights: [heroic] in Heroic mode. */
private fun team(id: Int, heroic: Int): (SaveProgress) -> Int = { p -> if (p.flag(FLAG_HEROIC)) heroic else id }

private fun badge(title: String, where: String, n: Int, id: Int, heroic: Int) =
    Boss(title, where, FLAG_BADGE01_GET + n, trainer = team(id, heroic))

/** Beaten on either difficulty: the trainer flag of whichever team the save fought. */
private fun league(title: String, where: String, id: Int, heroic: Int = id) = Boss(
    title, where, TRAINER_FLAGS_START + id, trainer = team(id, heroic),
    doneIf = { p -> p.flag(TRAINER_FLAGS_START + id) || p.flag(TRAINER_FLAGS_START + heroic) },
)

private val BOSSES_MONOMYTH = listOf(
    badge("LEADER BASTION", "CATSEAR CITY", 0, 36, 551),
    badge("LEADER SANTANA", "HOLLOW CANYON", 1, 67, 552),
    badge("LEADER AUGUSTUS", "MANDRAKE CITY", 2, 91, 553),
    badge("LEADER FABRIZIA", "NORTH EDELWEISS", 3, 114, 554),
    badge("LEADER RINRI", "WOOD AVENS", 4, 237, 555),
    badge("LEADER ARIEL", "CASCADE CITY", 5, 248, 556),
    badge("LEADER PIPER", "BLAZING PATH", 6, 309, 557),
    // HEM and LOCKE fight together (a two-trainer battle): both teams shown.
    Boss(
        "LEADERS HEM AND LOCKE", "FORT ELDERFLOWER", FLAG_BADGE01_GET + 7, trainer = team(493, 558),
        variantsFor = { p -> if (p.flag(FLAG_HEROIC)) listOf("HEM" to 558, "LOCKE" to 559) else listOf("HEM" to 493, "LOCKE" to 498) },
    ),
    league("ELITE FOUR DOUGLAS", "IRONBARK PLATEAU", 499, 561),
    league("ELITE FOUR ZARIZA", "IRONBARK PLATEAU", 500, 562),
    league("ELITE FOUR LUDOVIC", "IRONBARK PLATEAU", 501, 563),
    league("ELITE FOUR FABIAN", "IRONBARK PLATEAU", 502, 564),
    league("CHAMPION STENVARD", "IRONBARK PEAK", 763),
)

internal val GUIDE_MONOMYTH = GameGuide(
    verified = false,
    bosses = BOSSES_MONOMYTH,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS and HERE are read from the game itself: who gives each badge, their teams for the " +
                        "difficulty you play on, and the wild POKéMON of the place you're in.",
                ),
            ),
        ),
    ),
)
