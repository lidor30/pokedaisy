package com.pokedaisy.app.companion.data

/**
 * Pokémon Quetzal's guide: NEXT BOSS for its three regions - each its own eight gyms and
 * League, a campaign of its own - read from the ROM's script bytecode. Every leader battles its
 * NORMAL trainer, or a HARD twin on hard mode (special 0x29E: SaveBlock2+0x2EF & 0x18 == 0x08,
 * which SaveProgress can't see - so both are listed), then sets its region's badge flag.
 * Johto / Kanto trainers are ids 0x1000+ / 0x2000+ in tables of their own (altTrainers), their
 * flags and Johto's League var in SaveBlock1's extra banks. The region the player is in comes
 * first (map groups 34-35 Johto, 36-39 Kanto / Sevii, else Hoenn).
 */
private const val FLAG_HOENN_BADGE01 = 0xA7F        // .. 0xA86
private const val FLAG_DEFEATED_SIDNEY = 0x4FB      // .. DRAKE 0x4FE, cleared after the HALL OF FAME
private const val FLAG_DEFEATED_WALLACE = 0x4BF     // set right after the champion battle, never cleared
private const val FLAG_JOHTO_BADGE01 = 0x11B2       // .. 0x11B9 (CLAIR's badge comes in the DRAGON'S DEN)
private const val FLAG_JOHTO_CHAMPION = 0x11CA      // Johto's HALL OF FAME
private const val VAR_JOHTO_LEAGUE = 0x502A         // 2..6 as WILL .. LANCE fall in a League run
private const val VAR_CLAIR = 0x5010                // 2 once CLAIR is beaten
private const val FLAG_KANTO_BADGE01 = 0x2100       // .. 0x2107
private const val FLAG_KANTO_CHAMPION = 0x2108      // Kanto's HALL OF FAME
private const val FLAG_BEAT_BLUE = 0x2109           // after the champion battle (cleared and set again at the HALL OF FAME)

private fun johto(id: Int) = (1 shl TRAINER_TABLE_SHIFT) or (id - 0x1000)
private fun kanto(id: Int) = (2 shl TRAINER_TABLE_SHIFT) or (id - 0x2000)

private fun boss(title: String, where: String, done: Int, normal: Int, hard: Int, doneIf: ((SaveProgress) -> Boolean)? = null) =
    Boss(title, where, done, variants = listOf("NORMAL" to normal, "HARD" to hard), doneIf = doneIf) { normal }

private val HOENN = listOf(
    boss("LEADER ROXANNE", "RUSTBORO CITY GYM", FLAG_HOENN_BADGE01, 265, 961),
    boss("LEADER BRAWLY", "DEWFORD TOWN GYM", FLAG_HOENN_BADGE01 + 1, 266, 962),
    boss("LEADER WATTSON", "MAUVILLE CITY GYM", FLAG_HOENN_BADGE01 + 2, 267, 963),
    boss("LEADER FLANNERY", "LAVARIDGE TOWN GYM", FLAG_HOENN_BADGE01 + 3, 268, 964),
    boss("LEADER NORMAN", "PETALBURG CITY GYM", FLAG_HOENN_BADGE01 + 4, 269, 965),
    boss("LEADER WINONA", "FORTREE CITY GYM", FLAG_HOENN_BADGE01 + 5, 270, 966),
    boss("LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", FLAG_HOENN_BADGE01 + 6, 271, 967),
    boss("LEADER JUAN", "SOOTOPOLIS CITY GYM", FLAG_HOENN_BADGE01 + 7, 272, 968),
) + listOf("SIDNEY" to 261, "PHOEBE" to 262, "GLACIA" to 263, "DRAKE" to 264).mapIndexed { n, (name, id) ->
    boss(
        "ELITE FOUR $name", "POKéMON LEAGUE", 0, id, 969 + n,
        doneIf = { p -> p.flag(FLAG_DEFEATED_SIDNEY + n) || p.flag(FLAG_DEFEATED_WALLACE) },
    )
} + boss("CHAMPION WALLACE", "POKéMON LEAGUE", FLAG_DEFEATED_WALLACE, 335, 973)

private fun johtoLeague(state: Int): (SaveProgress) -> Boolean = { p -> p.flag(FLAG_JOHTO_CHAMPION) || p.variable(VAR_JOHTO_LEAGUE) >= state }

private val JOHTO = listOf(
    boss("LEADER FALKNER", "VIOLET CITY GYM", FLAG_JOHTO_BADGE01, johto(0x1013), johto(0x1363)),
    boss("LEADER BUGSY", "AZALEA TOWN GYM", FLAG_JOHTO_BADGE01 + 1, johto(0x1254), johto(0x1364)),
    boss("LEADER WHITNEY", "GOLDENROD CITY GYM", FLAG_JOHTO_BADGE01 + 2, johto(0x125C), johto(0x1365)),
    boss("LEADER MORTY", "ECRUTEAK CITY GYM", FLAG_JOHTO_BADGE01 + 3, johto(0x1260), johto(0x1366)),
    boss("LEADER CHUCK", "CIANWOOD CITY GYM", FLAG_JOHTO_BADGE01 + 4, johto(0x11FE), johto(0x1367)),
    boss("LEADER JASMINE", "OLIVINE CITY GYM", FLAG_JOHTO_BADGE01 + 5, johto(0x128B), johto(0x136B)),
    boss("LEADER PRYCE", "MAHOGANY TOWN GYM", FLAG_JOHTO_BADGE01 + 6, johto(0x12C3), johto(0x136F)),
    boss(
        "LEADER CLAIR", "BLACKTHORN CITY GYM", 0, johto(0x121D), johto(0x1370),
        doneIf = { p -> p.flag(FLAG_JOHTO_BADGE01 + 7) || p.variable(VAR_CLAIR) >= 2 },
    ),
    boss("ELITE FOUR WILL", "INDIGO PLATEAU", 0, johto(0x12E0), johto(0x1371), doneIf = johtoLeague(2)),
    boss("ELITE FOUR KOGA", "INDIGO PLATEAU", 0, johto(0x117F), johto(0x1373), doneIf = johtoLeague(3)),
    boss("ELITE FOUR BRUNO", "INDIGO PLATEAU", 0, johto(0x117B), johto(0x1375), doneIf = johtoLeague(4)),
    boss("ELITE FOUR KAREN", "INDIGO PLATEAU", 0, johto(0x117D), johto(0x1377), doneIf = johtoLeague(5)),
    boss("CHAMPION LANCE", "INDIGO PLATEAU", 0, johto(0x10F9), johto(0x1379), doneIf = johtoLeague(6)),
)

private fun kantoLeague(flag: Int): (SaveProgress) -> Boolean = { p -> p.flag(flag) || p.flag(FLAG_KANTO_CHAMPION) }

private val KANTO = listOf(
    boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_KANTO_BADGE01, kanto(0x219E), kanto(0x2000)),
    boss("LEADER MISTY", "CERULEAN CITY GYM", FLAG_KANTO_BADGE01 + 1, kanto(0x219F), kanto(0x2001)),
    boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_KANTO_BADGE01 + 2, kanto(0x21A0), kanto(0x2002)),
    boss("LEADER ERIKA", "CELADON CITY GYM", FLAG_KANTO_BADGE01 + 3, kanto(0x21A1), kanto(0x2003)),
    boss("LEADER KOGA", "FUCHSIA CITY GYM", FLAG_KANTO_BADGE01 + 4, kanto(0x21A2), kanto(0x2004)),
    boss("LEADER SABRINA", "SAFFRON CITY GYM", FLAG_KANTO_BADGE01 + 5, kanto(0x21A4), kanto(0x2005)),
    boss("LEADER BLAINE", "CINNABAR ISLAND GYM", FLAG_KANTO_BADGE01 + 6, kanto(0x21A3), kanto(0x2006)),
    boss("LEADER GIOVANNI", "VIRIDIAN CITY GYM", FLAG_KANTO_BADGE01 + 7, kanto(0x215E), kanto(0x2007)),
    boss("ELITE FOUR LORELEI", "POKéMON LEAGUE", 0, kanto(0x219A), kanto(0x2008), doneIf = kantoLeague(0x2121)),
    boss("ELITE FOUR BRUNO", "POKéMON LEAGUE", 0, kanto(0x219B), kanto(0x2009), doneIf = kantoLeague(0x211C)),
    boss("ELITE FOUR AGATHA", "POKéMON LEAGUE", 0, kanto(0x219C), kanto(0x200A), doneIf = kantoLeague(0x2119)),
    boss("ELITE FOUR LANCE", "POKéMON LEAGUE", 0, kanto(0x219D), kanto(0x200B), doneIf = kantoLeague(0x211F)),
    boss("CHAMPION BLUE", "POKéMON LEAGUE", 0, kanto(0x21B6), kanto(0x200C), doneIf = kantoLeague(FLAG_BEAT_BLUE)),
)

private fun quetzalBosses(mapGroup: Int) = when (mapGroup) {
    in 34..35 -> JOHTO + KANTO + HOENN
    in 36..39 -> KANTO + JOHTO + HOENN
    else -> HOENN + JOHTO + KANTO
}

internal val GUIDE_QUETZAL = GameGuide(verified = false, bosses = HOENN + JOHTO + KANTO, bossesFor = ::quetzalBosses, pages = emptyList())

/** Quetzal Alpha 0.8.4: Hoenn alone, with Alpha 9's trainers, HARD twins and flags (its ROXANNE is 265 / 961, its
 * continue screen counts the same badge flags). */
internal val GUIDE_QUETZAL_V084 = GameGuide(verified = false, bosses = HOENN, pages = emptyList())
