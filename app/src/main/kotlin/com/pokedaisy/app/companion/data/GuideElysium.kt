package com.pokedaisy.app.companion.data

/**
 * Pokémon Elysium's guide (both parts): HERE's wild Pokémon, read from its ROM (GUIDE_TABLES_ELYSIUM_A / _B). No NEXT
 * BOSS or area data yet: its regions' gym order and its maps' scripts haven't been read.
 */
internal val GUIDE_ELYSIUM = GameGuide(
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
                    "This game's gyms and story are its own, and the guide doesn't know their order yet.",
                ),
            ),
        ),
    ),
)
