package com.pokedaisy.app.companion.data

/**
 * Radical Red's guide: WHERE IS generated from the area data its ROM's maps
 * and scripts give (gen_guide_areas_rom.py), NEXT BOSS for the gyms whose
 * scripts battle one fixed leader: each gym map's script that sets the badge
 * (FireRed's flags, 0x820..) battles trainer 414.. first (a Lv54+ rematch team
 * comes after). The VIRIDIAN CITY leader's party is built at run time, and
 * each ELITE FOUR room picks between two teams by a var no map script sets,
 * so neither is listed.
 */
private const val FLAG_BADGE01_GET = 0x820

private val BOSSES_RADICAL_RED = listOf(
    Boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER MISTY", "CERULEAN CITY GYM", FLAG_BADGE01_GET + 1) { 415 },
    Boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER ERIKA", "CELADON CITY GYM", FLAG_BADGE01_GET + 3) { 417 },
    Boss("LEADER KOGA", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER SABRINA", "SAFFRON CITY GYM", FLAG_BADGE01_GET + 5) { 420 },
    Boss("LEADER BLAINE", "CINNABAR ISLAND GYM", FLAG_BADGE01_GET + 6) { 419 },
)

internal val GUIDE_RADICAL_RED = GameGuide(
    verified = false,
    bosses = BOSSES_RADICAL_RED,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Why does NEXT BOSS stop at the seventh gym?",
                    "VIRIDIAN CITY's leader gets a party put together as the battle starts, and the ELITE FOUR rooms choose between two teams " +
                        "in a way the guide can't read from the game, so they aren't listed.",
                ),
            ),
        ),
    ),
)
