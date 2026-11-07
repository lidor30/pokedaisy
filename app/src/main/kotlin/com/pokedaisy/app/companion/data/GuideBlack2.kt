package com.pokedaisy.app.companion.data

/**
 * Pokémon Black 2's guide (IREO rev 0), White 2's too ([GUIDE_WHITE2]: the same pages and bosses, its own wild
 * Pokémon). HERE and NEXT BOSS come from [BLACK2_WORLD] (read from the cart by scripts/gen_gen5_guide.py); the
 * hand-written pages are from the cart's own scripts (a/0/5/6, each zone's script file by its header's +0x06), in our
 * own words:
 * - who hands out what: the scripts that set VAR 0x8000 to the item before the give-item routine (`2A 00 00 80
 *   <item>`), placed by the zone whose script file it is - CUT in Virbank City, FLY on Route 5, SURF on Route 6,
 *   STRENGTH in the Castelia Sewers, WATERFALL in Victory Road, DIVE in Undella Town; the BICYCLE in Castelia City, the
 *   DOWSING MCHN at Castelia Gate, the SUPER ROD in Nuvema Town, the COLRESS MCHN on Route 22, the DNA SPLICERS in the
 *   Giant Chasm, the REVEAL GLASS at the Abundant Shrine (the same in White 2's cart);
 * - the bosses: trainers in a/0/9/1, a/0/9/2 (names: text bank 382, classes 383), the same in both versions. Each comes
 *   twice: its Normal Mode team and its Challenge Mode one (the League's room scripts battle the Challenge trainer when
 *   that mode is on, else the Normal one; Easy Mode is the Normal team at lower levels), and the Elite Four and IRIS
 *   once more, stronger, after the game is cleared ([B2W2_FLAG_CLEARED]).
 * Item ids are the cart's (a/0/2/4, a/0/0/2 bank 64).
 *
 * Done flags: the gym leaders count by badge ([GEN4_BADGE_FLAG] + n: each leader's script sets badge n after the
 * battle, command 0xD6 - CHEREN 0, ROXIE 1, BURGH 2, ELESA 3, CLAY 4, SKYLA 5, DRAYDEN 6, MARLON 7). GHETSIS: flag
 * 0x375, which the Giant Chasm's script sets right after he loses. The Elite Four: each room's script sets its flag
 * just before the battle ([B2W2_FLAG_E4] + 0 SHAUNTAL, 1 GRIMSLEY, 2 MARSHAL, 3 CAITLIN: 0x967-0x96A; the League's
 * entrance script clears them again). IRIS: [B2W2_FLAG_CLEARED], set by the game's common script after the Hall of Fame
 * (every room script checks it to pick the second round's teams).
 */
private const val B2W2_HM01 = 420
private const val B2W2_BICYCLE = 450
private const val B2W2_SUPER_ROD = 447
private const val B2W2_DOWSING_MCHN = 471
private const val B2W2_DNA_SPLICERS = 628
private const val B2W2_COLRESS_MCHN = 635
private const val B2W2_REVEAL_GLASS = 638

/** SHAUNTAL's room flag; GRIMSLEY, MARSHAL and CAITLIN follow (each room's script sets its own before the battle). */
internal const val B2W2_FLAG_E4 = 0x967

/** The game is cleared (the Hall of Fame entered): the Elite Four and IRIS bring their second round's teams from then on. */
internal const val B2W2_FLAG_CLEARED = 0x960

/** Set when GHETSIS loses in the Giant Chasm. */
internal const val B2W2_FLAG_GHETSIS = 0x375

/** A boss's teams: Normal and Challenge Mode, and with [rematch] (the Elite Four, IRIS) the second round's once the game
 * is cleared. */
private fun sequelTeams(world: Gen5World, key: String, rematch: Boolean): (SaveProgress) -> List<Pair<String, Int>> = { p ->
    val k = if (rematch && p.flag(B2W2_FLAG_CLEARED)) "${key}_2" else key
    listOf("NORMAL" to world.trainer(k), "CHALLENGE" to world.trainer("${k}_C"))
}

private fun sequelBoss(world: Gen5World, title: String, where: String, key: String, done: Int, rematch: Boolean = false) =
    Boss(title, where, done, variantsFor = sequelTeams(world, key, rematch)) { world.trainer(key) }

private fun b2w2Bosses(world: Gen5World): List<Boss> {
    /** A gym leader: done with the badge (the case's order, BASIC 0 .. WAVE 7). */
    fun gym(title: String, where: String, key: String, badge: Int) =
        sequelBoss(world, title, where, key, GEN4_BADGE_FLAG + badge)
    fun elite(title: String, key: String, room: Int) =
        sequelBoss(world, title, "POKéMON LEAGUE", key, B2W2_FLAG_E4 + room, rematch = true)
    return listOf(
        gym("LEADER CHEREN", "ASPERTIA CITY GYM", "CHEREN", 0),
        gym("LEADER ROXIE", "VIRBANK CITY GYM", "ROXIE", 1),
        gym("LEADER BURGH", "CASTELIA CITY GYM", "BURGH", 2),
        gym("LEADER ELESA", "NIMBASA CITY GYM", "ELESA", 3),
        gym("LEADER CLAY", "DRIFTVEIL CITY GYM", "CLAY", 4),
        gym("LEADER SKYLA", "MISTRALTON CITY GYM", "SKYLA", 5),
        gym("LEADER DRAYDEN", "OPELUCID CITY GYM", "DRAYDEN", 6),
        gym("LEADER MARLON", "HUMILAU CITY GYM", "MARLON", 7),
        gen5Boss(world, "GHETSIS", "GIANT CHASM", "GHETSIS", B2W2_FLAG_GHETSIS),
        elite("ELITE FOUR SHAUNTAL", "SHAUNTAL", 0),
        elite("ELITE FOUR GRIMSLEY", "GRIMSLEY", 1),
        elite("ELITE FOUR MARSHAL", "MARSHAL", 2),
        elite("ELITE FOUR CAITLIN", "CAITLIN", 3),
        sequelBoss(world, "CHAMPION IRIS", "POKéMON LEAGUE", "IRIS", B2W2_FLAG_CLEARED, rematch = true),
    )
}

private fun hm(n: Int, move: String, answer: String, hint: String, areas: List<String>) =
    entry("HM%02d %s".format(n, move), answer, hint, Have.Item(B2W2_HM01 + n - 1), areas)

internal val GUIDE_BLACK2 = b2w2Guide(BLACK2_WORLD)

/** White 2's: Black 2's pages and bosses (its cart's scripts and trainers are Black 2's), its own wild Pokémon
 * ([WHITE2_WORLD]). */
internal val GUIDE_WHITE2 = b2w2Guide(WHITE2_WORLD)

private fun b2w2Guide(world: Gen5World) = GameGuide(
    verified = false,
    bosses = b2w2Bosses(world),
    world = world,
    pages = listOf(
        page(
            "TIPS",
            section(
                "WILD POKéMON",
                entry(
                    "Why did the POKéMON here change?",
                    "Some places change their POKéMON with the seasons, and the seasons follow the month: January, May " +
                        "and September are spring, February, June and October summer, March, July and November autumn, " +
                        "April, August and December winter. HERE shows this month's list, by your DS clock (your device's).",
                    hint = "Look at the calendar.",
                ),
                entry(
                    "What about the dark grass and the grass that shakes?",
                    "Dark grass, grass that shakes and water that ripples each hold POKéMON of their own, often stronger " +
                        "or rarer than the plain grass around them.",
                    hint = "They're not the plain grass.",
                ),
            ),
            section(
                "BOSSES",
                entry(
                    "Why does NEXT BOSS show two teams?",
                    "Every leader, the ELITE FOUR and the CHAMPION bring one team in the normal game and a bigger one " +
                        "with CHALLENGE MODE on. Easy mode uses the normal team at lower levels.",
                    hint = "It depends on the game's mode.",
                ),
                entry(
                    "Do the ELITE FOUR change after the ending?",
                    "Yes. Once you have entered the HALL OF FAME, the ELITE FOUR and the CHAMPION come back with " +
                        "stronger teams, and NEXT BOSS shows those.",
                    hint = "Think about the HALL OF FAME.",
                ),
            ),
        ),
        page(
            "WHERE IS",
            section(
                "HMs",
                hm(1, "CUT", "Someone hands it to you in VIRBANK CITY.", "VIRBANK CITY.", listOf("VIRBANK CITY")),
                hm(2, "FLY", "Someone hands it to you on ROUTE 5.", "ROUTE 5.", listOf("ROUTE 5")),
                hm(3, "SURF", "Someone hands it to you on ROUTE 6.", "ROUTE 6.", listOf("ROUTE 6")),
                hm(4, "STRENGTH", "Someone in the CASTELIA SEWERS gives it to you.", "CASTELIA SEWERS.", listOf("CASTELIA SEWERS")),
                hm(5, "WATERFALL", "Someone in VICTORY ROAD gives it to you.", "VICTORY ROAD.", listOf("VICTORY ROAD")),
                hm(6, "DIVE", "Someone in UNDELLA TOWN gives it to you.", "UNDELLA TOWN.", listOf("UNDELLA TOWN")),
            ),
            section(
                "KEY ITEMS",
                entry("BICYCLE", "Someone hands it to you in CASTELIA CITY.", "CASTELIA CITY.", Have.Item(B2W2_BICYCLE), listOf("CASTELIA CITY")),
                entry("DOWSING MCHN", "Someone hands it to you at CASTELIA GATE.", "CASTELIA GATE.", Have.Item(B2W2_DOWSING_MCHN), listOf("CASTELIA GATE")),
                entry("SUPER ROD", "Someone in NUVEMA TOWN gives it to you.", "NUVEMA TOWN.", Have.Item(B2W2_SUPER_ROD), listOf("NUVEMA TOWN")),
                entry("COLRESS MCHN", "Someone hands it to you on ROUTE 22.", "ROUTE 22.", Have.Item(B2W2_COLRESS_MCHN), listOf("ROUTE 22")),
                entry("DNA SPLICERS", "You get them in the GIANT CHASM.", "GIANT CHASM.", Have.Item(B2W2_DNA_SPLICERS), listOf("GIANT CHASM")),
                entry("REVEAL GLASS", "You get it at the ABUNDANT SHRINE.", "ABUNDANT SHRINE.", Have.Item(B2W2_REVEAL_GLASS), listOf("ABUNDANT SHRINE")),
            ),
        ),
    ),
)
