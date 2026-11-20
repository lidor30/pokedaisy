package com.pokedaisy.app.companion.data

/**
 * Pokémon Elite Redux's guide: HERE's wild Pokémon, read from its ROM (GUIDE_TABLES_ELITE_REDUX_*). No NEXT BOSS: its
 * gym leaders have several teams each, levelled to the player's level cap and picked when the battle starts, so no one
 * team is the one you'll meet. No area data (its maps' scripts are its own engine's).
 */
internal val GUIDE_ELITE_REDUX = GameGuide(
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
                    "In this game every gym leader has several teams, levelled to your level cap and picked when the " +
                        "battle starts, so there's no one team to show.",
                ),
            ),
        ),
    ),
)
