package com.pokedaisy.app.companion.data

/**
 * Pokémon Odyssey's guide: WHERE IS from its area data (gen_guide_areas_rom.py),
 * NEXT BOSS for the battles whose own script hands out a badge (FireRed's
 * flags, 0x820..). Each script picks its team by flag 0x1512 - set by the
 * game's difficulty menu for HARD MODE - so both are listed (NORMAL is the
 * lower id; Ethan's, Kozuki's and Kitt's are the same team either way). The
 * other three badges come at the end of longer story events, after several
 * battles, so they aren't listed. Places are the region map's names.
 */
private const val FLAG_BADGE01_GET = 0x820

private fun normalHard(normal: Int, hard: Int) = listOf("NORMAL" to normal, "HARD" to hard)

private val BOSSES_ODYSSEY = listOf(
    Boss("CAPTAIN KARIN", "FIRST STRATUM", FLAG_BADGE01_GET, variants = normalHard(33, 34)) { 33 },
    Boss("CAPTAIN ROCKEY", "DES. OF GOLGONDA", FLAG_BADGE01_GET + 1, variants = normalHard(35, 36)) { 35 },
    // One script, two battles in a row: ETHAN, then KOZUKI.
    Boss("ETHAN & KOZUKI", "THIRD STRATUM", FLAG_BADGE01_GET + 2, variants = listOf("ETHAN" to 45, "KOZUKI" to 52)) { 45 },
    Boss("MATHILDA", "FIFTH STRATUM", FLAG_BADGE01_GET + 4, variants = normalHard(66, 67)) { 66 },
    Boss("KITT", "SIXTH STRATUM", FLAG_BADGE01_GET + 5) { 73 },
)

internal val GUIDE_ODYSSEY = GameGuide(
    verified = false,
    bosses = BOSSES_ODYSSEY,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Why are some badges missing from NEXT BOSS?",
                    "Three badges are handed over at the end of longer story events with several battles, so only the five fights that give " +
                        "a badge straight away are listed.",
                ),
                entry(
                    "Which team will a boss use?",
                    "NEXT BOSS lists both where they differ: NORMAL, and HARD for HARD MODE (chosen in the game's difficulty menu).",
                ),
            ),
        ),
    ),
)
