package com.pokedaisy.app.companion.data

/**
 * Pokémon Recharged Yellow's guide (every release; each has its own area data, generated from its
 * ROM by gen_guide_areas_rom.py): WHERE IS from that data, NEXT BOSS from its gym and League
 * scripts. It keeps FireRed's trainer ids and gym order, but moved the system flags: the badges are
 * flags 0x880.. (the three on the user's save are set, as its trainer card shows) and the game-clear
 * flag is 0x864 (its GF ROM header's gameClearFlag). Each gym leader's record holds a second, HARD
 * party (its difficulty option, kept in SaveBlock2, which SaveProgress can't see), so both teams are
 * listed. The ELITE FOUR rooms check 0x864 and use their rematch teams (735-738) once it's set, and
 * mark each win with FireRed's FLAG_DEFEATED_LORELEI.. (0x4B8 on). The CHAMPION is the rival's
 * Champion-class record, 438 (739, its stronger twin, after the game is cleared); his room's script
 * runs through a command the ROM reader can't follow, so his flag is FireRed's 0x4BC, unchecked.
 * The TIPS come from the ROM's own tables (gEvolutionTable, gTrainers, gItems).
 */
private const val FLAG_BADGE01_GET = 0x880
private const val FLAG_DEFEATED_LORELEI = 0x4B8 // .. the champion 0x4BC
private const val FLAG_SYS_GAME_CLEAR = 0x864
private const val HARD = 1 shl TRAINER_TABLE_SHIFT // GuideTables.trainerHardPartyOff: the record's HARD party

private fun nh(id: Int) = listOf("NORMAL" to id, "HARD" to (id or HARD))

private fun gym(n: Int, title: String, where: String, id: Int) = Boss(title, where, FLAG_BADGE01_GET + n, variants = nh(id)) { id }

private fun league(n: Int, title: String, first: Int, rematch: Int) =
    Boss(title, "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI + n) { p -> if (p.flag(FLAG_SYS_GAME_CLEAR)) rematch else first }

private val BOSSES_RECHARGED_YELLOW = listOf(
    gym(0, "LEADER BROCK", "PEWTER CITY GYM", 414),
    gym(1, "LEADER MISTY", "CERULEAN CITY GYM", 415),
    gym(2, "LEADER LT. SURGE", "VERMILION CITY GYM", 416),
    gym(3, "LEADER ERIKA", "CELADON CITY GYM", 417),
    gym(4, "LEADER KOGA", "FUCHSIA CITY GYM", 418),
    gym(5, "LEADER SABRINA", "SAFFRON CITY GYM", 420),
    gym(6, "LEADER BLAINE", "CINNABAR ISLAND GYM", 419),
    gym(7, "LEADER GIOVANNI", "VIRIDIAN CITY GYM", 350),
    league(0, "ELITE FOUR LORELEI", 410, 735),
    league(1, "ELITE FOUR BRUNO", 411, 736),
    league(2, "ELITE FOUR AGATHA", 412, 737),
    league(3, "ELITE FOUR LANCE", 413, 738),
    league(4, "CHAMPION (YOUR RIVAL)", 438, 739),
)

internal val GUIDE_RECHARGED_YELLOW = GameGuide(
    verified = false,
    bosses = BOSSES_RECHARGED_YELLOW,
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
                    "Every gym leader has a second team for the game's HARD difficulty - usually one Pokémon more, sometimes a stronger " +
                        "one. NORMAL is the one you'll meet unless you picked HARD.",
                ),
            ),
        ),
    ),
)
