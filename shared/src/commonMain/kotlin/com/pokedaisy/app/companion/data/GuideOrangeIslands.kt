package com.pokedaisy.app.companion.data

/**
 * Pokémon Orange Islands' guide: WHERE IS from its area data (gen_guide_areas_rom.py),
 * NEXT BOSS from its gym scripts. Its gym leaders are trainers 1-4 of gTrainers, each
 * script battling one and setting every other badge flag (0x820, 0x822, 0x824, 0x826 - the
 * card has four badge slots); the Supreme Gym Leader DRAKE (trainer 5) sets no flag of his
 * own, so his trainer flag (TRAINER_FLAGS_START 0x500 + 5, set by any won trainer battle)
 * marks him beaten. Places are the region map's names for each gym's island. The TIPS come
 * from the ROM's tables (gTypeEffectiveness, gBaseStats).
 */
private const val FLAG_BADGE01_GET = 0x820
private const val TRAINER_FLAGS_START = 0x500

private val BOSSES_ORANGE_ISLANDS = listOf(
    Boss("LEADER CISSY", "MIKAN ISLAND GYM", FLAG_BADGE01_GET) { 1 },
    Boss("LEADER DANNY", "NAVEL ISLAND GYM", FLAG_BADGE01_GET + 2) { 2 },
    Boss("LEADER RUDY", "TROVITA ISLAND GYM", FLAG_BADGE01_GET + 4) { 3 },
    Boss("LEADER LUANA", "KUMQUAT ISLAND GYM", FLAG_BADGE01_GET + 6) { 4 },
    Boss("SUPREME GYM LEADER DRAKE", "PUMMELO ISLAND", TRAINER_FLAGS_START + 5) { 5 },
)

internal val GUIDE_ORANGE_ISLANDS = GameGuide(
    verified = false,
    bosses = BOSSES_ORANGE_ISLANDS,
    pages = listOf(
        page(
            "TIPS",
            section(
                "TYPES",
                entry(
                    "What is the CRYSTL type?",
                    "A type of the game's own (CRYSTAL ONIX is CRYSTAL / GROUND). Its moves hit BUG, FLYING and ICE " +
                        "hard and FIGHTING, GROUND and STEEL weakly. WATER moves don't touch it at all; FIGHTING, GRASS, " +
                        "GROUND, STEEL and FIRE moves hit it hard.",
                ),
            ),
        ),
    ),
)
