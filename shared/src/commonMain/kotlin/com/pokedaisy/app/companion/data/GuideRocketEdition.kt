package com.pokedaisy.app.companion.data

/**
 * Pokémon FireRed Rocket Edition's guide: NEXT BOSS from its badge scripts (gen_guide_areas_rom.py
 * --bosses): each gym battles FireRed's leader (414..420, the same badge flags), but Viridian City's
 * battles ARIANA (trainer 50) for the last badge. Its League isn't listed (its trainers changed and
 * no script was followed there). WHERE IS and HERE from its own maps. FireRed's hand-written pages
 * aren't used: you play for TEAM ROCKET.
 */
private const val FLAG_BADGE01_GET = 0x820

private val BOSSES_ROCKET_EDITION = listOf(
    Boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER MISTY", "CERULEAN CITY GYM", FLAG_BADGE01_GET + 1) { 415 },
    Boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER ERIKA", "CELADON CITY GYM", FLAG_BADGE01_GET + 3) { 417 },
    Boss("LEADER KOGA", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER SABRINA", "SAFFRON CITY GYM", FLAG_BADGE01_GET + 5) { 420 },
    Boss("LEADER BLAINE", "CINNABAR ISLAND GYM", FLAG_BADGE01_GET + 6) { 419 },
    Boss("LEADER ARIANA", "VIRIDIAN CITY GYM", FLAG_BADGE01_GET + 7) { 50 },
)

internal val GUIDE_ROCKET_EDITION = GameGuide(
    verified = false,
    bosses = BOSSES_ROCKET_EDITION,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS follows the game's own gym scripts: the leaders are FireRed's, but Viridian City's gym " +
                        "is ARIANA's. WHERE IS and HERE are read from the game's own maps.",
                ),
            ),
            section(
                "TYPES",
                entry(
                    "Is there a FAIRY type?",
                    "Yes. CLEFAIRY and a few others are FAIRY now. FAIRY moves hit DRAGON, DARK and FIGHTING hard, and " +
                        "DRAGON moves don't touch FAIRY at all; POISON and STEEL moves hit it hard.",
                ),
            ),
        ),
    ),
)
