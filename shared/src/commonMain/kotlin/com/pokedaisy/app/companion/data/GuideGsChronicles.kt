package com.pokedaisy.app.companion.data

/**
 * Pokémon GS Chronicles' guide: WHERE IS from its area data (gen_guide_areas_rom.py), NEXT BOSS
 * from the Johto gym scripts that set FireRed's badge flags (0x820..0x827), each battling one
 * leader first (a Lv100 rematch team comes after in the same script): FALKNER 321, BUGSY 53,
 * WHITNEY 74, MORTY 111, CHUCK 151, JASMINE 152, LORELEI 153 (Mahogany's leader here), CLAIR 154.
 * Seven were paired by gen_guide_areas_rom.py's --bosses scan; CLAIR's script (0x08C4A54E) checks
 * FLAG 0x827 before its trainerbattle, the same pairing. The Kanto badges and the League use
 * flags of the hack's own that weren't traced, so they aren't listed.
 */
private const val FLAG_BADGE01_GET = 0x820

private val BOSSES_GS_CHRONICLES = listOf(
    Boss("LEADER FALKNER", "VIOLET CITY", FLAG_BADGE01_GET) { 321 },
    Boss("LEADER BUGSY", "AZALEA TOWN", FLAG_BADGE01_GET + 1) { 53 },
    Boss("LEADER WHITNEY", "GOLDENROD CITY", FLAG_BADGE01_GET + 2) { 74 },
    Boss("LEADER MORTY", "ECRUTEAK CITY", FLAG_BADGE01_GET + 3) { 111 },
    Boss("LEADER CHUCK", "CIANWOOD CITY", FLAG_BADGE01_GET + 4) { 151 },
    Boss("LEADER JASMINE", "OLIVINE CITY", FLAG_BADGE01_GET + 5) { 152 },
    Boss("LEADER LORELEI", "MAHOGANY TOWN", FLAG_BADGE01_GET + 6) { 153 },
    Boss("LEADER CLAIR", "BLACKTHORN CITY", FLAG_BADGE01_GET + 7) { 154 },
)

internal val GUIDE_GS_CHRONICLES = GameGuide(
    verified = false,
    bosses = BOSSES_GS_CHRONICLES,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Why does NEXT BOSS stop at the eighth gym?",
                    "The Kanto gyms and the League keep their progress in places the guide can't read yet, so it lists " +
                        "the eight Johto gyms only.",
                ),
                entry(
                    "Why is the leader of the Mahogany gym not who I expected?",
                    "This game puts its own leader there. The guide shows the team the gym really uses.",
                ),
            ),
        ),
    ),
)
