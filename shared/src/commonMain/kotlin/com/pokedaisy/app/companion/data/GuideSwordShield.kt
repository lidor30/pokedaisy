package com.pokedaisy.app.companion.data

/**
 * Pokémon Sword and Shield Ultimate Plus' guide: WHERE IS from its area data
 * (gen_guide_areas_rom.py), NEXT BOSS from the gym scripts that set FireRed's badge flags
 * (0x820..0x827), each battling one leader first (a Lv100 rematch team comes after):
 * MILO 414, NESSA 415, KABU 416, OPAL 418, PIERS 419, RAIHAN 350. Two badges have a second
 * script with another leader - BEA (27) next to ALLISTER (417), GORDIE (49) next to MELONY
 * (420) - so both are listed. The gyms reuse FireRed's gym maps, whose section names are
 * left over from FireRed's towns, so each is named by its badge, not its town. The League
 * rooms still battle FireRed's ELITE FOUR records, so they aren't listed.
 */
private const val FLAG_BADGE01_GET = 0x820

private val BOSSES_SWORD_SHIELD = listOf(
    Boss("LEADER MILO", "1ST GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER NESSA", "2ND GYM", FLAG_BADGE01_GET + 1) { 415 },
    Boss("LEADER KABU", "3RD GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER BEA / ALLISTER", "4TH GYM", FLAG_BADGE01_GET + 3, variants = listOf("BEA" to 27, "ALLISTER" to 417)) { 417 },
    Boss("LEADER OPAL", "5TH GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER GORDIE / MELONY", "6TH GYM", FLAG_BADGE01_GET + 5, variants = listOf("GORDIE" to 49, "MELONY" to 420)) { 420 },
    Boss("LEADER PIERS", "7TH GYM", FLAG_BADGE01_GET + 6) { 419 },
    Boss("LEADER RAIHAN", "8TH GYM", FLAG_BADGE01_GET + 7) { 350 },
)

internal val GUIDE_SWORD_SHIELD = GameGuide(
    verified = false,
    bosses = BOSSES_SWORD_SHIELD,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Why do two gyms list two leaders?",
                    "The game has a second script for the 4th and 6th gyms with another leader, and the guide can't tell " +
                        "from the save which one you'll face, so it shows both teams.",
                ),
                entry(
                    "Why does NEXT BOSS stop at the eighth gym?",
                    "The League rooms still hold FireRed's ELITE FOUR teams, which the guide can't match to what the game " +
                        "really sends out, so they aren't listed.",
                ),
            ),
        ),
    ),
)
