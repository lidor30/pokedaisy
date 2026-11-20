package com.pokedaisy.app.companion.data

/**
 * Pokémon Ultra Violet's guide: FireRed's NEXT BOSS (its gTrainers, gym scripts and League
 * are FireRed's - each badge script battles the same trainer, 414.. and 350 - and only one
 * trainer was added), WHERE IS and HERE from its own maps (gen_guide_areas_rom.py: it moved
 * gMapGroups and added maps). FireRed's hand-written pages aren't used: its story changed.
 */
internal val GUIDE_ULTRA_VIOLET = GameGuide(
    verified = false,
    bosses = BOSSES_FIRERED,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS follows the game's own gym and League trainers, which are FireRed's. WHERE IS and HERE " +
                        "are read from Ultra Violet's own maps, so its new places are in them too.",
                ),
            ),
        ),
    ),
)
