package com.pokedaisy.app.companion.data

/**
 * Pokémon Recharged Emerald's guide (every build): NEXT BOSS and HERE, read from each ROM (GUIDE_TABLES_RECHARGED_EMERALD_*;
 * no area data - the ROM reader only knows the FireRed engine's maps). It keeps Emerald's trainer ids, gym order, badge
 * flags and FLAG_DEFEATED_ELITE_4_* / FLAG_IS_CHAMPION (their code still loads them), but moved the game-clear flag to
 * 0x863 (its GF ROM header's gameClearFlag; retail's 0x864 isn't loaded any more). Every gym leader, Elite Four member
 * and the champion has a second, HARD party in the same record (its difficulty option), so both teams are listed.
 */
private const val FLAG_BADGE01_GET = 0x867
private const val FLAG_SYS_GAME_CLEAR = 0x863
private const val FLAG_IS_CHAMPION = 0x87F
private const val FLAG_DEFEATED_ELITE_4_SIDNEY = 0x4FB // .. 0x4FE
private const val HARD = 1 shl TRAINER_TABLE_SHIFT // GuideTables.trainerHardPartyOff: the record's HARD party

private fun nh(id: Int) = listOf("NORMAL" to id, "HARD" to (id or HARD))

private fun gym(n: Int, title: String, where: String, id: Int) = Boss(title, where, FLAG_BADGE01_GET + n, variants = nh(id)) { id }

private fun elite(n: Int, title: String, id: Int) = Boss(
    title, "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + n, variants = nh(id),
    doneIf = { p -> p.flag(FLAG_DEFEATED_ELITE_4_SIDNEY + n) || p.flag(FLAG_IS_CHAMPION) },
) { id }

private val BOSSES_RECHARGED_EMERALD = listOf(
    gym(0, "LEADER ROXANNE", "RUSTBORO CITY GYM", 265),
    gym(1, "LEADER BRAWLY", "DEWFORD TOWN GYM", 266),
    gym(2, "LEADER WATTSON", "MAUVILLE CITY GYM", 267),
    gym(3, "LEADER FLANNERY", "LAVARIDGE TOWN GYM", 268),
    gym(4, "LEADER NORMAN", "PETALBURG CITY GYM", 269),
    gym(5, "LEADER WINONA", "FORTREE CITY GYM", 270),
    gym(6, "LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", 271),
    gym(7, "LEADER JUAN", "SOOTOPOLIS CITY GYM", 272),
    elite(0, "ELITE FOUR SIDNEY", 261),
    elite(1, "ELITE FOUR PHOEBE", 262),
    elite(2, "ELITE FOUR GLACIA", 263),
    elite(3, "ELITE FOUR DRAKE", 264),
    Boss(
        "CHAMPION WALLACE", "POKéMON LEAGUE", FLAG_IS_CHAMPION, variants = nh(335),
        doneIf = { p -> p.flag(FLAG_IS_CHAMPION) || p.flag(FLAG_SYS_GAME_CLEAR) },
    ) { 335 },
)

internal val GUIDE_RECHARGED_EMERALD = GameGuide(
    verified = false,
    bosses = BOSSES_RECHARGED_EMERALD,
    pages = listOf(
        page(
            "TIPS",
            section(
                "EVOLUTION",
                entry(
                    "How do Pokémon that evolve by trading evolve here?",
                    "With the LINKING CORD, used like an evolution stone: KADABRA, MACHOKE, GRAVELER and HAUNTER evolve with it alone. " +
                        "The ones that trade holding an item (POLIWHIRL and SLOWPOKE with a KING'S ROCK, ONIX and SCYTHER with a METAL COAT, " +
                        "SEADRA with a DRAGON SCALE, PORYGON with an UP-GRADE, CLAMPERL with a DEEPSEATOOTH or DEEPSEASCALE) need to hold " +
                        "that item when the cord is used. Trading still works too.",
                    hint = "Look for a key item.",
                ),
            ),
            section(
                "TRAINERS",
                entry(
                    "Why does NEXT BOSS show two teams?",
                    "Every gym leader, the Elite Four and the champion have a second team for the game's HARD difficulty. NORMAL is " +
                        "the one you'll meet unless you picked HARD.",
                ),
            ),
        ),
    ),
)
