package com.pokedaisy.app.companion.data

/**
 * Pokémon Epsilon Emerald's guide: HERE's wild Pokémon, read from its ROM ([GUIDE_TABLES_EPSILON_EMERALD]). No NEXT BOSS:
 * its gym leaders and Elite Four draw their party from a bigger pool (ROXANNE six of twenty), picked when the battle starts,
 * so no one team is the one you'll meet. No area data (its story and maps are the remakes').
 */
internal val GUIDE_EPSILON_EMERALD = GameGuide(
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
                    "In this game every gym leader picks their team from a bigger group of POKéMON when the battle " +
                        "starts, so there's no one team to show.",
                ),
            ),
        ),
    ),
)
