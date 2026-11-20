package com.pokedaisy.app.companion.data

/**
 * Pokémon Team Rocket Edition: Dragonsden Version's guide: HERE's wild Pokémon, read from its ROM
 * (GUIDE_TABLES_TEAM_ROCKET). No NEXT BOSS or area data yet: its story is its own (you play a Team Rocket recruit), so
 * which leader comes next isn't the badge order, and its maps' scripts haven't been read.
 */
internal val GUIDE_TEAM_ROCKET = GameGuide(
    verified = false,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "HERE lists the wild POKéMON of the place you're in, read from the game itself.",
                ),
                entry(
                    "Why is there no NEXT BOSS page?",
                    "In this game you play for Team Rocket and its story takes you around in its own order, so there's " +
                        "no one boss to show next yet.",
                ),
            ),
        ),
    ),
)
