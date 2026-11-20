package com.pokedaisy.app.companion.data

/**
 * Pokémon Unbound's guide: WHERE IS is generated from its area data (read
 * from the ROM's own maps and scripts by gen_guide_areas_rom.py), NEXT BOSS
 * lists each gym leader's teams. Unbound picks the team at run time - its gym
 * scripts set VAR_0x8000 to the gym and `callasm` a routine (0x09ECA98D) that
 * reads the difficulty (var 0x50DF) and the party's best level. That routine
 * was run headlessly for every gym and difficulty; the ids below are what it
 * returned. Difficulty names and order are the game's own menu list
 * (0x09FB54CC: DIFFICULT, VANILLA, EXPERT) plus INSANE. Gyms by badge flag
 * (FireRed's 0x820..), each gym map's badge script giving the town.
 */
private const val FLAG_BADGE01_GET = 0x820

private fun gym(n: Int, title: String, where: String, difficult: Int, vanilla: Int, expert: Int, insane: Int) =
    Boss(
        title, where, FLAG_BADGE01_GET + n - 1,
        variants = listOf("VANILLA" to vanilla, "DIFFICULT" to difficult, "EXPERT" to expert, "INSANE" to insane),
    ) { difficult }

private val BOSSES_UNBOUND = listOf(
    // EXPERT MIRSKLE brings the INSANE team (721) once your best POKéMON is over Lv39.
    gym(1, "LEADER MIRSKLE", "DRESCO TOWN GYM", 6, 719, 720, 721),
    gym(2, "LEADER VÉGA", "CRATER TOWN GYM", 415, 722, 723, 724),
    gym(3, "LEADER ALICE", "BLIZZARD CITY GYM", 20, 725, 726, 727),
    gym(4, "LEADER MEL", "FALLSHORE CITY GYM", 416, 728, 729, 730),
    gym(5, "LEADER GALAVAN", "DEHARA CITY GYM", 418, 731, 732, 733),
    gym(6, "LEADER BIG MO", "ANTISIS CITY GYM", 419, 737, 738, 739),
    gym(7, "LEADER TESSY", "POLDER TOWN GYM", 417, 734, 735, 736),
    gym(8, "LEADER BENJAMIN", "REDWOOD VILLAGE GYM", 420, 740, 741, 742),
)

internal val GUIDE_UNBOUND = GameGuide(
    verified = false,
    bosses = BOSSES_UNBOUND,
    pages = listOf(
        page(
            "TIPS",
            section(
                "BATTLES",
                entry(
                    "Why does NEXT BOSS show four teams?",
                    "Unbound gives each gym leader a different team for each difficulty (VANILLA, DIFFICULT, EXPERT, INSANE). Look at the one you're playing on.",
                ),
            ),
        ),
    ),
)
