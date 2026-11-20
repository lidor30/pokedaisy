package com.pokedaisy.app.companion.data

/**
 * Pokémon Ash Gray's guide (v4.5.3 and its Fork Bug Fix build): NEXT BOSS from its badge scripts
 * (gen_guide_areas_rom.py --bosses), the badges won in a battle: BROCK 414, LT. SURGE 416, KOGA 418,
 * SABRINA 420 (her gym battles 528 first, then 420), BLAINE 419 (the gym; a script in the volcano,
 * map 2.57, battles 617 for the same badge) and TEAM ROCKET 350 at Viridian City's gym (after its
 * COHORTS, 349). The CASCADE and RAINBOW badges are handed over without a battle (flags 0x821 / 0x823
 * set alone), so they aren't listed. WHERE IS and HERE from its own maps (it moved gMapGroups).
 */
private const val FLAG_BADGE01_GET = 0x820

private val BOSSES_ASH_GRAY = listOf(
    Boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER KOGA", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER SABRINA", "SAFFRON CITY GYM", FLAG_BADGE01_GET + 5) { 420 },
    Boss("LEADER BLAINE", "CINNABAR ISLAND GYM", FLAG_BADGE01_GET + 6) { 419 },
    Boss("TEAM ROCKET", "VIRIDIAN CITY GYM", FLAG_BADGE01_GET + 7) { 350 },
)

internal val GUIDE_ASH_GRAY = GameGuide(
    verified = false,
    bosses = BOSSES_ASH_GRAY,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS follows the game's own gym scripts. Two badges are handed to you without a battle, so " +
                        "only the gyms you battle in are listed. WHERE IS and HERE are read from the game's own maps.",
                ),
            ),
            section(
                "TYPES",
                entry(
                    "Why does my ELECTRIC move hit a GROUND POKéMON?",
                    "In this game ELECTRIC moves hit GROUND for half damage instead of none at all.",
                ),
            ),
        ),
    ),
)
