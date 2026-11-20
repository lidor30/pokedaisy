package com.pokedaisy.app.companion.data

/**
 * Pokémon Ghost Grey's guide (v1.0.1, v1.0.2, v1.0.4): NEXT BOSS from its badge scripts (gen_guide_areas_rom.py
 * --bosses, then each script read): seven badges, FireRed's flags 0x821..0x827 (0x820 is never given). ASTON at
 * Pewter's gym (414, BADGE 2's flag - the user's save holds it with 414 beaten), MAJ.VOLTAGE at Vermilion's (415),
 * ERIKA at Celidon's (417), the EMPEROR OF SAND in the Desert Palace (416), the DEMON WOMAN ONIBABA at Fuchsia's
 * (418), CAP'N STEVE in Davy Jones' Locker (420), then the player's best friend SUMMER in the Fuji Catacombs, whose
 * team answers the starter (FireRed's VAR_STARTER_MON: 2 LITWICK -> 435, 1 SPIRITLE -> 436, 0 ROWLET -> 437). Listed
 * by their teams' levels, not their flags (the Emperor's Lv32-37 come before Onibaba's Lv40-45). Cinnabar's gym
 * script still sets 0x826 for FireRed's leftover trainer 419, which no one plays. SUMMER's script sets the badge
 * flag before the battle, so her own trainer flag marks her beaten. The League isn't listed. WHERE IS and HERE from
 * its own maps (v1.0.4's; it moved gWildMonHeaders).
 */
private const val FLAG_BADGE01_GET = 0x820
private const val TRAINER_FLAGS_START = 0x500
private const val VAR_STARTER_MON = 0x4031 // 0 ROWLET, 1 SPIRITLE, 2 LITWICK

private val BOSSES_GHOST_GREY = listOf(
    Boss("LEADER ASTON", "PEWTER CITY GYM", FLAG_BADGE01_GET + 1) { 414 },
    Boss("LEADER MAJ.VOLTAGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 2) { 415 },
    Boss("LEADER ERIKA", "CELIDON CITY GYM", FLAG_BADGE01_GET + 3) { 417 },
    Boss("THE EMPEROR OF SAND", "DESERT PALACE", FLAG_BADGE01_GET + 5) { 416 },
    Boss("DEMON WOMAN ONIBABA", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER CAP'N STEVE", "DAVY JONES' LOCKER", FLAG_BADGE01_GET + 6) { 420 },
    Boss(
        "BEST FRIEND SUMMER", "FUJI CATACOMBS", FLAG_BADGE01_GET + 7,
        doneIf = { p -> (435..437).any { p.flag(TRAINER_FLAGS_START + it) } },
    ) { p -> when (p.variable(VAR_STARTER_MON)) { 2 -> 435; 1 -> 436; else -> 437 } },
)

internal val GUIDE_GHOST_GREY = GameGuide(
    verified = false,
    bosses = BOSSES_GHOST_GREY,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS follows the game's own badge scripts: seven badges, from Pewter City's gym to the Fuji " +
                        "Catacombs. WHERE IS and HERE are read from the game's own maps.",
                ),
            ),
            section(
                "TYPES",
                entry(
                    "Why don't my GHOST moves hit GHOST POKéMON as hard as I thought?",
                    "In this game GHOST moves hit GHOST for 1.2x, not 2x, and hit PSYCHIC for normal damage.",
                ),
                entry(
                    "Is there a FAIRY type?",
                    "Yes. FAIRY moves hit DRAGON, DARK and FIGHTING hard. DRAGON, DARK and FIGHTING moves do half to " +
                        "FAIRY, and POISON and STEEL moves hit it hard.",
                ),
            ),
        ),
    ),
)
