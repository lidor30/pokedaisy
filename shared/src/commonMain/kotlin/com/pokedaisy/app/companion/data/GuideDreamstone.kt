package com.pokedaisy.app.companion.data

/**
 * Pokémon Dreamstone Mysteries' guide: NEXT BOSS from its scripts and HERE from GUIDE_TABLES_DREAMSTONE. Each badge
 * holder's script runs its trainerbattle and then sets one of vanilla's badge flags (0x867 + n), found by walking the
 * bytecode after each battle: INGER (ROUTE 3 UNDERPASS), ARIANA (CERAM BASE CAMP), KOHLA (GALECREST CITY: the rival, an
 * ACE ROOKIE, after three trainers in a row), GLORIA (SILVERSUN CITY), CARONA (MIRROH BASE CAMP), VINIEL (WINTERLILY
 * HOLLOW), JANIA (PELLUCA CITY), RAAZI (S.S. ELEGANT); the places are the map sections of the maps whose people run
 * those scripts. The last battles at VICTORY CAPE are four CONTENDERS and KOHLA again (trainers 197-201), done by their
 * trainer flags (0x500 + id: beating BACKPACKER RONNIE, 7, set 0x507 headless).
 *
 * The game's difficulty (var 0x40A8) picks each trainer's team: 0 reads its first table (0x085837E4), where a trainer
 * has a team there, and anything else the second (0x085837E4 + 0x8598, GuideTables.trainers); a third is empty.
 */
private const val FLAG_BADGE01_GET = 0x867
private const val TRAINER_FLAGS_START = 0x500
private const val VAR_DIFFICULTY = 0x40A8

/** The team the save's difficulty fights: the first table's (altTrainers[0]) on 0, the second's otherwise. */
private fun team(id: Int): (SaveProgress) -> Int = { p -> if (p.variable(VAR_DIFFICULTY) == 0) id or (1 shl TRAINER_TABLE_SHIFT) else id }

private fun badge(title: String, where: String, n: Int, id: Int) = Boss(title, where, FLAG_BADGE01_GET + n, trainer = team(id))

private fun cape(title: String, id: Int) = Boss(title, "VICTORY CAPE", TRAINER_FLAGS_START + id, trainer = team(id))

private val BOSSES_DREAMSTONE = listOf(
    badge("LEADER INGER", "ROUTE 3 UNDERPASS", 0, 12),
    badge("LEADER ARIANA", "CERAM BASE CAMP", 1, 30),
    badge("ACE ROOKIE KOHLA", "GALECREST CITY", 2, 45),
    badge("LEADER GLORIA", "SILVERSUN CITY", 3, 70),
    badge("LEADER CARONA", "MIRROH BASE CAMP", 4, 99),
    badge("LEADER VINIEL", "WINTERLILY HOLLOW", 5, 133),
    badge("LEADER JANIA", "PELLUCA CITY", 6, 143),
    badge("LEADER RAAZI", "S.S. ELEGANT", 7, 167),
    cape("CONTENDER CHARA", 197),
    cape("CONTENDER STANJA", 198),
    cape("CONTENDER BERGER", 199),
    cape("CONTENDER GABRIELLE", 200),
    cape("ACE ROOKIE KOHLA", 201),
)

internal val GUIDE_DREAMSTONE = GameGuide(
    verified = false,
    bosses = BOSSES_DREAMSTONE,
    pages = listOf(
        page(
            "TIPS",
            section(
                "THIS GUIDE",
                entry(
                    "Where does this guide come from?",
                    "NEXT BOSS and HERE are read from the game itself: who gives each badge, their teams for the " +
                        "difficulty you play on, and the wild POKéMON of the place you're in.",
                ),
            ),
        ),
    ),
)
