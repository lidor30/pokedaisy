package com.pokedaisy.app.companion.data

/**
 * Pokémon Lazarus's guide: NEXT BOSS from its own scripts (trainerbattle / setflag bytecode walked
 * through gMapGroups), HERE from GUIDE_TABLES_LAZARUS. Every boss script checks flag 0x8E6 - the
 * new-game options' DIFFICULTY (NORMAL / HARD) - and battles the next trainer id when it's set. The
 * Muses keep Emerald's badge flags; the 8th badge comes from Team Chimera's KALLIOPE (a former Muse)
 * in the AREIOS HIDEOUT. The RUINS OF AHIYAWA is the league: four Muses in a row (flags 0x296..0x299),
 * then ANYALIOS (0x29E, and the Hall of Fame's FLAG_IS_CHAMPION). Team levels rise in this order.
 */
private const val FLAG_BADGE01_GET = 0x867
private const val FLAG_IS_CHAMPION = 0x87F
private const val FLAG_HARD_MODE = 0x8E6
private const val FLAG_BEAT_RUINS_1 = 0x296 // .. 0x299
private const val FLAG_BEAT_ANYALIOS = 0x29E

private fun pick(normal: Int, hard: Int): (SaveProgress) -> Int = { p -> if (p.flag(FLAG_HARD_MODE)) hard else normal }

private fun gym(n: Int, title: String, where: String, normal: Int, hard: Int) =
    Boss(title, where, FLAG_BADGE01_GET + n, trainer = pick(normal, hard))

private fun ruins(n: Int, title: String, normal: Int, hard: Int) = Boss(
    title, "RUINS OF AHIYAWA", FLAG_BEAT_RUINS_1 + n,
    doneIf = { p -> p.flag(FLAG_BEAT_RUINS_1 + n) || p.flag(FLAG_BEAT_ANYALIOS) || p.flag(FLAG_IS_CHAMPION) },
    trainer = pick(normal, hard),
)

private val BOSSES_LAZARUS = listOf(
    gym(0, "LEADER POLYMNIA", "JUSMAIL TOWN GYM", 8, 15),
    gym(1, "LEADER OURANI", "KALAMI CITY GYM", 29, 30),
    gym(2, "LEADER KLEIO", "PYTHIOS TOWN GYM", 48, 49),
    gym(3, "LEADER TERPSIKORE", "SOFOS CITY GYM", 98, 99),
    gym(4, "LEADER EUTERPE", "MYRRINI ISLAND GYM", 123, 124),
    gym(5, "LEADERS THAL AND MEL", "FRESCO ISLES GYM", 156, 157),
    gym(6, "LEADER RHADINE", "PALATI CITY GYM", 173, 174),
    gym(7, "TEAM CHIMERA KALLIOPE", "AREIOS HIDEOUT", 214, 215),
    ruins(0, "LEADER POLYMNIA", 234, 235),
    ruins(1, "LEADER TERPSIKORE", 237, 238),
    ruins(2, "LEADER EUTERPE", 239, 240),
    ruins(3, "LEADER RHADINE", 241, 242),
    Boss(
        "CHAMPION ANYALIOS", "RUINS OF AHIYAWA", FLAG_BEAT_ANYALIOS,
        doneIf = { p -> p.flag(FLAG_BEAT_ANYALIOS) || p.flag(FLAG_IS_CHAMPION) }, trainer = pick(243, 244),
    ),
)

internal val GUIDE_LAZARUS = GameGuide(verified = false, bosses = BOSSES_LAZARUS, pages = emptyList())
