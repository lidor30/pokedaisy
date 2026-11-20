package com.pokedaisy.app.companion.data

/**
 * Pokémon Wishes of Tomorrow's guide: HERE's wild Pokémon, read from its ROM (GUIDE_TABLES_WISHES) and swapped like the
 * game swaps them when the save plays with its randomizer ([WishesRandomizer]). No NEXT BOSS or area data yet: its story
 * (a region of its own, a wish to chase) isn't a badge order the ROM's flags spell out, and its maps' scripts haven't
 * been read.
 */
internal val GUIDE_WISHES = GameGuide(
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
                    "What if I play with the randomizer on?",
                    "HERE shows the POKéMON your save really meets: the game swaps them the same way every time for one " +
                        "save, so the guide can follow it.",
                ),
                entry(
                    "Why is there no NEXT BOSS page?",
                    "This game's story takes you around its own region in its own order, so there's no one boss to show " +
                        "next yet.",
                ),
            ),
        ),
    ),
)
