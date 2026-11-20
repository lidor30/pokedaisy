package com.pokedaisy.app.companion.data

/**
 * Emerald Legacy's guide. Its story moved things around Emerald's (gift POKéMON, items, its own boss teams), so
 * there's no hand-written WHERE IS: it's generated from its area data ([generatedWhereIs], GuideAreasEmeraldLegacyGen.kt).
 * NEXT BOSS and the TIPS follow its own source (cRz-Shadows/Pokemon_Emerald_Legacy at V1.1.4, the release's): the gyms'
 * and the League's map scripts, include/constants/flags.h and opponents.h, src/data/pokemon/evolution.h. Trainer ids
 * are retail's (its new trainers come after them, from 855); its 960 trainer flags put the system flags at 0x8C0.
 */
private const val FLAG_BADGE01_GET = 0x8C7
private const val FLAG_SYS_GAME_CLEAR = 0x8C4
private const val FLAG_DEFEATED_MAUVILLE_GYM = 0x4F2
private const val FLAG_DEFEATED_LAVARIDGE_GYM = 0x4F3
private const val FLAG_DEFEATED_ELITE_4_SIDNEY = 0x4FB // .. _DRAKE 0x4FE, cleared again after the HALL OF FAME

/**
 * BRAWLY brings a stronger team when you come to him after WATTSON or FLANNERY (DewfordTown_Gym's
 * EventScript_Brawly: FLANNERY beaten -> BRAWLY_1_3, else WATTSON beaten -> BRAWLY_1_2, else BRAWLY_1).
 */
private val brawly: (SaveProgress) -> Int = { p ->
    when {
        p.flag(FLAG_DEFEATED_LAVARIDGE_GYM) -> 861
        p.flag(FLAG_DEFEATED_MAUVILLE_GYM) -> 860
        else -> 266
    }
}

/**
 * The gyms in the usual order, then the League. SOOTOPOLIS CITY's leader is WALLACE until the HALL OF FAME
 * (JUAN stands in after it: hall_of_fame.inc swaps their FLAG_HIDE_SOOTOPOLIS_GYM_* flags), and the champion is
 * STEVEN (EverGrandeCity_ChampionsRoom: STEVEN_1 until FLAG_IS_CHAMPION, WALLACE_2 after).
 */
private val BOSSES_EMERALD_LEGACY = listOf(
    Boss("LEADER ROXANNE", "RUSTBORO CITY GYM", FLAG_BADGE01_GET) { 265 },
    Boss("LEADER BRAWLY", "DEWFORD TOWN GYM", FLAG_BADGE01_GET + 1, trainer = brawly),
    Boss("LEADER WATTSON", "MAUVILLE CITY GYM", FLAG_BADGE01_GET + 2) { 267 },
    Boss("LEADER FLANNERY", "LAVARIDGE TOWN GYM", FLAG_BADGE01_GET + 3) { 268 },
    Boss("LEADER NORMAN", "PETALBURG CITY GYM", FLAG_BADGE01_GET + 4) { 269 },
    Boss("LEADER WINONA", "FORTREE CITY GYM", FLAG_BADGE01_GET + 5) { 270 },
    Boss("LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", FLAG_BADGE01_GET + 6) { 271 },
    Boss("LEADER WALLACE", "SOOTOPOLIS CITY GYM", FLAG_BADGE01_GET + 7) { 335 },
    Boss("ELITE FOUR SIDNEY", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY) { 261 },
    Boss("ELITE FOUR PHOEBE", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + 1) { 262 },
    Boss("ELITE FOUR GLACIA", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + 2) { 263 },
    Boss("ELITE FOUR DRAKE", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + 3) { 264 },
    Boss("CHAMPION STEVEN", "POKéMON LEAGUE", FLAG_SYS_GAME_CLEAR) { 804 },
)

internal val GUIDE_EMERALD_LEGACY = GameGuide(
    // From its source; nobody has walked through it in-game yet.
    verified = false,
    bosses = BOSSES_EMERALD_LEGACY,
    pages = listOf(
        page(
            "TIPS",
            section(
                "BATTLES",
                entry(
                    "Physical or special?",
                    "Still decided by the move's TYPE, as in the original. FIRE, WATER, GRASS, ELECTRIC, ICE, PSYCHIC, " +
                        "DRAGON and DARK moves are special; the other types are physical.",
                ),
                entry(
                    "Why is BRAWLY tougher than I remember?",
                    "He picks his team by how far you've got: a stronger one if you've already beaten WATTSON, stronger again after FLANNERY.",
                    hint = "It depends on your other badges.",
                ),
                entry(
                    "Who leads the last gym?",
                    "WALLACE, in SOOTOPOLIS CITY. JUAN takes over the gym once you're in the HALL OF FAME, and the champion waiting at the end is STEVEN.",
                    hint = "Not the one from the original.",
                ),
            ),
            section(
                "EVOLUTION",
                entry(
                    "No trades needed",
                    "The POKéMON that evolved by trading level up instead (KADABRA and HAUNTER at Lv42, MACHOKE and GRAVELER at Lv38) " +
                        "or use their item like a stone (ONIX and SCYTHER a METAL COAT, SEADRA a DRAGON SCALE, PORYGON an UP-GRADE, " +
                        "CLAMPERL a DEEPSEATOOTH or DEEPSEASCALE, POLIWHIRL and SLOWPOKE a KING'S ROCK).",
                ),
                entry("HITMONTOP", "TYROGUE becomes HITMONTOP with a BRICK PIECE, or at Lv20 when its ATTACK equals its DEFENSE."),
            ),
            section(
                "WILD POKéMON",
                entry(
                    "New POKéMON after the story",
                    "After the HALL OF FAME, PROF. BIRCH's visit to MOSSDEEP CITY changes the wild POKéMON in many areas. HERE always lists the ones out there now.",
                ),
            ),
        ),
    ),
)
