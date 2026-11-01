package com.pokedaisy.app.companion.data

/**
 * Pokémon Black's guide (IRBO rev 0), White's too ([GUIDE_WHITE]). HERE and NEXT BOSS come from [BLACK_WORLD] (read
 * from the cart by scripts/gen_gen5_guide.py); the hand-written pages are from the cart's own scripts (a/0/5/7, each zone's script file
 * by its header's +0x06), in our own words:
 * - who hands out what: the scripts that set VAR 0x8000 to the item and call the give-item routine (`2A 00 00 80
 *   <item>`), placed by the zone whose script file it is - CUT in Striaton City, FLY in Driftveil City, SURF in Twist
 *   Mountain, STRENGTH in Nimbasa City, DIVE in Undella Town, the BICYCLE in Nimbasa City, the DOWSING MCHN in Nacrene
 *   City, the SUPER ROD in Nuvema Town; WATERFALL is Route 18's item ball (a/0/5/7 member 864's script 278);
 * - the Striaton leaders' teams: trainers 11-13 (a/0/9/2, a/0/9/3).
 * Item ids are the cart's (a/0/2/4, a/0/0/2 bank 54).
 *
 * Done flags: the gym leaders count by badge ([GEN4_BADGE_FLAG] + the badge's place in the case: the reader reads
 * Black's badges the Gen 4 way). The Elite Four: each room's script sets its flag just before the battle ([BW_FLAG_E4]
 * + 0 SHAUNTAL, 1 GRIMSLEY, 2 MARSHAL, 3 CAITLIN: 0x969-0x96C; the League's entrance script clears them again). N and
 * GHETSIS have no done flag of their own found (the flag N's room sets after the battle, 0x294, reads clear in a save
 * past the ending): they count as done once the National Dex is (Juniper hands it over after the ending,
 * [GEN5_NATIONAL_DEX_FLAG]).
 */
private const val BW_HM01 = 420
private const val BW_BICYCLE = 450
private const val BW_SUPER_ROD = 447
private const val BW_DOWSING_MCHN = 471

/** SHAUNTAL's room flag; GRIMSLEY, MARSHAL and CAITLIN follow (each room's script sets its own before the battle). */
internal const val BW_FLAG_E4 = 0x969

/**
 * The trainers' defeated flags: this + the trainer id. Found from a save (the route trainers it had beaten all read
 * set from here on, the unbeaten ones clear; item balls' flags end just below it, at 1411); the gym leaders, the Elite
 * Four, N and GHETSIS don't set theirs.
 */
internal const val BW_TRAINER_FLAGS = 1420

/**
 * The bosses in [world]: the same in both versions but the eighth gym's leader ([leader8]: Black's DRAYDEN, White's
 * IRIS, the same badge) and N's team (his legend: the generated data's).
 */
private fun bwBosses(world: Gen5World, leader8: String): List<Boss> {
    /** A gym leader: done with the badge (the case's order, TRIO 0 .. LEGEND 7). */
    fun gym(title: String, where: String, key: String, badge: Int) =
        gen5Boss(world, title, where, key, GEN4_BADGE_FLAG + badge)
    fun elite(title: String, key: String, room: Int) =
        gen5Boss(world, title, "POKéMON LEAGUE", key, BW_FLAG_E4 + room)
    return listOf(
        // You face the one whose type beats your first POKéMON's.
        Boss(
            "STRIATON GYM LEADER", "STRIATON CITY GYM", GEN4_BADGE_FLAG + 0,
            variants = listOf("CHILI" to world.trainer("CHILI"), "CRESS" to world.trainer("CRESS"), "CILAN" to world.trainer("CILAN")),
        ) { world.trainer("CHILI") },
        gym("LEADER LENORA", "NACRENE CITY GYM", "LENORA", 1),
        gym("LEADER BURGH", "CASTELIA CITY GYM", "BURGH", 2),
        gym("LEADER ELESA", "NIMBASA CITY GYM", "ELESA", 3),
        gym("LEADER CLAY", "DRIFTVEIL CITY GYM", "CLAY", 4),
        gym("LEADER SKYLA", "MISTRALTON CITY GYM", "SKYLA", 5),
        gym("LEADER BRYCEN", "ICIRRUS CITY GYM", "BRYCEN", 6),
        gym("LEADER $leader8", "OPELUCID CITY GYM", leader8, 7),
        elite("ELITE FOUR SHAUNTAL", "SHAUNTAL", 0),
        elite("ELITE FOUR GRIMSLEY", "GRIMSLEY", 1),
        elite("ELITE FOUR MARSHAL", "MARSHAL", 2),
        elite("ELITE FOUR CAITLIN", "CAITLIN", 3),
        // No done flag known yet (see above): never done.
        gen5Boss(world, "N", "N's CASTLE", "N", 0, doneIf = { it.flag(GEN5_NATIONAL_DEX_FLAG) }),
        gen5Boss(world, "GHETSIS", "N's CASTLE", "GHETSIS", 0, doneIf = { it.flag(GEN5_NATIONAL_DEX_FLAG) }),
    )
}

private fun hm(n: Int, move: String, answer: String, hint: String, areas: List<String>) =
    entry("HM%02d %s".format(n, move), answer, hint, Have.Item(BW_HM01 + n - 1), areas)

internal val GUIDE_BLACK = bwGuide(BLACK_WORLD, "DRAYDEN")

/** White's: Black's pages (its cart's scripts, trainers and zone events are Black's, file for file), its own wild
 * Pokémon, IRIS in Opelucid City's gym and N's team ([WHITE_WORLD]). */
internal val GUIDE_WHITE = bwGuide(WHITE_WORLD, "IRIS")

private fun bwGuide(world: Gen5World, leader8: String) = GameGuide(
    verified = false,
    bosses = bwBosses(world, leader8),
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
                "GYMS",
                entry(
                    "Which STRIATON CITY leader will I battle?",
                    "The one whose type beats your first POKéMON's: CHILI (fire) if you picked SNIVY, CRESS (water) if you " +
                        "picked TEPIG, CILAN (grass) if you picked OSHAWOTT. NEXT BOSS shows all three teams.",
                    hint = "It depends on your first POKéMON.",
                ),
            ),
        ),
        page(
            "WHERE IS",
            section(
                "HMs",
                hm(1, "CUT", "Someone hands it to you in STRIATON CITY.", "STRIATON CITY.", listOf("STRIATON CITY")),
                hm(2, "FLY", "Someone hands it to you in DRIFTVEIL CITY.", "DRIFTVEIL CITY.", listOf("DRIFTVEIL CITY")),
                hm(3, "SURF", "Someone hands it to you in TWIST MOUNTAIN.", "TWIST MOUNTAIN.", listOf("TWIST MOUNTAIN")),
                hm(4, "STRENGTH", "Someone in NIMBASA CITY gives it to you.", "NIMBASA CITY.", listOf("NIMBASA CITY")),
                hm(5, "WATERFALL", "It's an item ball on ROUTE 18.", "ROUTE 18.", listOf("ROUTE 18")),
                hm(6, "DIVE", "Someone in UNDELLA TOWN gives it to you.", "UNDELLA TOWN.", listOf("UNDELLA TOWN")),
            ),
            section(
                "KEY ITEMS",
                entry("BICYCLE", "Someone hands it to you in NIMBASA CITY.", "NIMBASA CITY.", Have.Item(BW_BICYCLE), listOf("NIMBASA CITY")),
                entry("DOWSING MCHN", "Someone hands it to you in NACRENE CITY.", "NACRENE CITY.", Have.Item(BW_DOWSING_MCHN), listOf("NACRENE CITY")),
                entry("SUPER ROD", "Someone in NUVEMA TOWN gives it to you.", "NUVEMA TOWN.", Have.Item(BW_SUPER_ROD), listOf("NUVEMA TOWN")),
            ),
        ),
    ),
)
