package com.pokedaisy.app.companion.data

/**
 * Celia's Stupid Romhack's guide: WHERE IS from its area data
 * (gen_guide_areas_rom.py), NEXT BOSS from each gym script's battle and badge
 * flag (FireRed's 0x820..). The gyms keep FireRed's map slots (Pewter's is
 * still map 6.2), so they're named by town. Its CINNABAR leader is eight
 * one-Pokémon battles in a row (trainers 503..510).
 */
private const val FLAG_BADGE01_GET = 0x820

private val BOSSES_CELIA = listOf(
    Boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER MISTY", "CERULEAN CITY GYM", FLAG_BADGE01_GET + 1) { 415 },
    Boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER GIOVANNI", "CELADON CITY GYM", FLAG_BADGE01_GET + 3) { 348 },
    Boss("LEADER DAD", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER BRUNO", "SAFFRON CITY GYM", FLAG_BADGE01_GET + 5) { 420 },
    Boss("LEADER BLAINE", "CINNABAR ISLAND GYM", FLAG_BADGE01_GET + 6, variants = (1..8).map { "BATTLE $it" to 502 + it }) { 503 },
    Boss("LEADER GIOVANNI", "VIRIDIAN CITY GYM", FLAG_BADGE01_GET + 7) { 350 },
)

internal val GUIDE_CELIA = GameGuide(
    verified = false,
    bosses = BOSSES_CELIA,
    pages = listOf(
        page(
            "TIPS",
            section(
                "NEXT BOSS",
                entry("Why does BLAINE have eight teams?", "His gym is eight one-on-one battles in a row, each against a single POKéMON."),
            ),
        ),
    ),
)
