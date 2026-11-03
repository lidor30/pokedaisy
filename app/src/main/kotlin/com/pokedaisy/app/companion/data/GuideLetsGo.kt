package com.pokedaisy.app.companion.data

/**
 * Pokémon Let's Go Pikachu / Eevee's guide (every build): FireRed's NEXT BOSS - its badge scripts are FireRed's
 * (gen_guide_areas_rom.py --bosses on both versions: 414 BROCK .. 420 SABRINA and 350 GIOVANNI give FireRed's
 * badge flags 0x820..0x827, the League starts at 410 / 735), with the hack's own teams read from gTrainers. WHERE IS
 * and HERE from its own maps (GuideAreasLetsGoGen.kt) and each version's own wild Pokémon (GUIDE_TABLES_LETS_GO_*).
 * FireRed's hand-written pages aren't used: the story's Pokémon and gifts changed.
 */
internal val GUIDE_LETS_GO = GameGuide(
    verified = false,
    bosses = BOSSES_FIRERED,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS follows the game's own gym and League trainers, which keep FireRed's order and badges, " +
                        "with this game's own teams. WHERE IS and HERE are read from the game's own maps, and HERE " +
                        "lists your version's own wild POKéMON.",
                ),
            ),
            section(
                "TYPES",
                entry(
                    "Is there a FAIRY type?",
                    "Yes. FAIRY moves hit DRAGON, DARK and FIGHTING hard. DRAGON moves can't touch FAIRY, DARK and " +
                        "FIGHTING moves do half to it, and POISON and STEEL moves hit it hard.",
                ),
            ),
        ),
    ),
)
