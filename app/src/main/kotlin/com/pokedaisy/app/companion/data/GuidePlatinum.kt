package com.pokedaisy.app.companion.data

/**
 * Pokémon Platinum's guide (USA rev 1). HERE and NEXT BOSS come from [PLATINUM_WORLD] (generated from
 * pret/pokeplatinum); the hand-written pages are from its scripts (res/field/scripts) in our own words:
 * - the HMs: Eterna City (CYNTHIA, HM01), the Galactic Warehouse's item ball (HM02), Celestic Town's cave (the elder,
 *   HM03), Iron Island (RILEY, HM04), Solaceon Ruins' item ball (HM05), Oreburgh Gate 1F's hiker (HM06), Sunyshore City
 *   (JASMINE, HM07), Route 217's item ball (HM08);
 * - each field move's badge: field_move_tasks.c's FieldMoves_Check* (PlayerHasRequiredBadge), and which gym gives
 *   which: the gym scripts' GiveBadge;
 * - the blocks: Route 210's PSYDUCK want the SECRETPOTION (CYNTHIA gives it at Valor Lakefront), the Valley Windworks'
 *   WORKS KEY (Floaroma Meadow, once its grunts are beaten), the Veilstone warehouse's STORAGE KEY (a grunt drops it in
 *   Veilstone City once Lake Acuity's events are done: lake_acuity.s shows him), the GALACTIC KEY (an item ball on
 *   Galactic HQ B2F);
 * - key items: the BICYCLE (the Eterna cycle shop's owner, once rescued), the rods (OLD: the gate from Route 218 to
 *   Jubilife City; GOOD: Route 209; SUPER: the Fight Area), the VS SEEKER (Rowan's assistant on Route 207), the
 *   EXPLORER KIT (the Underground Man's house in Eterna City), the POKé RADAR (Prof. Rowan, for a complete Sinnoh Dex).
 * Item ids are pokeplatinum's (generated/items.txt).
 */
private const val PT_HM01 = 420
private const val PT_BICYCLE = 450
private const val PT_OLD_ROD = 445
private const val PT_GOOD_ROD = 446
private const val PT_SUPER_ROD = 447
private const val PT_VS_SEEKER = 443
private const val PT_EXPLORER_KIT = 428
private const val PT_POKE_RADAR = 431
private const val PT_SECRETPOTION = 464
private const val PT_WORKS_KEY = 438
private const val PT_STORAGE_KEY = 463
private const val PT_GALACTIC_KEY = 440

/** A gym leader: done with the badge (generated/badges.txt's BADGE_ID_* order). */
private fun ptGym(title: String, where: String, key: String, badge: Int) =
    gen4Boss(PLATINUM_WORLD, title, where, key, GEN4_BADGE_FLAG + badge)

/** The League: each room's trainer flag (TRAINER_DEFEATED_FLAGS_START + id), or the game completed - a new run through
 * the League clears the rooms' flags again. */
private fun ptLeague(title: String, key: String): Boss {
    val flag = PT_TRAINER_FLAGS + PLATINUM_WORLD.trainer(key)
    return gen4Boss(PLATINUM_WORLD, title, "POKéMON LEAGUE", key, flag, doneIf = { p -> p.flag(PT_FLAG_GAME_COMPLETED) || p.flag(flag) })
}

private val BOSSES_PLATINUM = listOf(
    ptGym("LEADER ROARK", "OREBURGH CITY GYM", "ROARK", 0),
    ptGym("LEADER GARDENIA", "ETERNA CITY GYM", "GARDENIA", 1),
    ptGym("LEADER FANTINA", "HEARTHOME CITY GYM", "FANTINA", 4),
    ptGym("LEADER MAYLENE", "VEILSTONE CITY GYM", "MAYLENE", 2),
    ptGym("LEADER CRASHER WAKE", "PASTORIA CITY GYM", "WAKE", 3),
    ptGym("LEADER BYRON", "CANALAVE CITY GYM", "BYRON", 5),
    ptGym("LEADER CANDICE", "SNOWPOINT CITY GYM", "CANDICE", 6),
    ptGym("LEADER VOLKNER", "SUNYSHORE CITY GYM", "VOLKNER", 7),
    ptLeague("ELITE FOUR AARON", "AARON"),
    ptLeague("ELITE FOUR BERTHA", "BERTHA"),
    ptLeague("ELITE FOUR FLINT", "FLINT"),
    ptLeague("ELITE FOUR LUCIAN", "LUCIAN"),
    gen4Boss(PLATINUM_WORLD, "CHAMPION CYNTHIA", "POKéMON LEAGUE", "CYNTHIA", PT_FLAG_GAME_COMPLETED),
)

private fun hm(n: Int, move: String, answer: String, hint: String, areas: List<String>) =
    entry("HM${n.pad(2)} $move", answer, hint, Have.Item(PT_HM01 + n - 1), areas)

internal val GUIDE_PLATINUM = GameGuide(
    verified = false,
    bosses = BOSSES_PLATINUM,
    world = PLATINUM_WORLD,
    pages = listOf(
        page(
            "TIPS",
            section(
                "WILD POKéMON",
                entry(
                    "Why did the POKéMON in this grass change?",
                    "Two of the grass's slots follow the clock: one pair of POKéMON in the morning (4 AM to 10 AM), " +
                        "another by day (until 8 PM) and another at night. HERE shows the list for the time on your " +
                        "DS clock, which is your device's.",
                    hint = "Look at the clock.",
                ),
            ),
            section(
                "FIELD MOVES",
                entry(
                    "Which badge lets me use each HM outside battle?",
                    "ROCK SMASH needs the COAL BADGE (ROARK), CUT the FOREST BADGE (GARDENIA), DEFOG the RELIC BADGE " +
                        "(FANTINA), FLY the COBBLE BADGE (MAYLENE), SURF the FEN BADGE (CRASHER WAKE), STRENGTH the " +
                        "MINE BADGE (BYRON), ROCK CLIMB the ICICLE BADGE (CANDICE) and WATERFALL the BEACON BADGE (VOLKNER).",
                    hint = "Each one has its own badge.",
                ),
            ),
        ),
        page(
            "WHERE IS",
            section(
                "HMs",
                hm(1, "CUT", "CYNTHIA hands it to you in ETERNA CITY.", "ETERNA CITY.", listOf("ETERNA CITY")),
                hm(2, "FLY", "It's an item ball inside the GALACTIC WAREHOUSE in VEILSTONE CITY.", "VEILSTONE CITY.", listOf("VEILSTONE CITY")),
                hm(3, "SURF", "The elder gives it to you in the cave in CELESTIC TOWN.", "CELESTIC TOWN.", listOf("CELESTIC TOWN")),
                hm(4, "STRENGTH", "RILEY gives it to you on IRON ISLAND.", "IRON ISLAND.", listOf("IRON ISLAND")),
                hm(5, "DEFOG", "It's an item ball deep in the SOLACEON RUINS.", "SOLACEON RUINS.", listOf("SOLACEON RUINS", "SOLACEON TOWN")),
                hm(6, "ROCK SMASH", "A hiker gives it to you in OREBURGH GATE.", "OREBURGH GATE.", listOf("OREBURGH GATE")),
                hm(7, "WATERFALL", "JASMINE gives it to you in SUNYSHORE CITY.", "SUNYSHORE CITY.", listOf("SUNYSHORE CITY")),
                hm(8, "ROCK CLIMB", "It's an item ball on ROUTE 217, in the snow.", "ROUTE 217.", listOf("ROUTE 217")),
            ),
            section(
                "KEY ITEMS",
                entry("BICYCLE", "The cycle shop's owner in ETERNA CITY gives it to you once you've freed him from the top floor of TEAM GALACTIC's building there.",
                    "ETERNA CITY.", Have.Item(PT_BICYCLE), listOf("ETERNA CITY")),
                entry("OLD ROD", "A fisherman in the gate between ROUTE 218 and JUBILIFE CITY.", "Near JUBILIFE CITY.",
                    Have.Item(PT_OLD_ROD), listOf("ROUTE 218", "JUBILIFE CITY")),
                entry("GOOD ROD", "A fisherman on ROUTE 209.", "ROUTE 209.", Have.Item(PT_GOOD_ROD), listOf("ROUTE 209")),
                entry("SUPER ROD", "A fisherman in the FIGHT AREA, after the LEAGUE.", "The FIGHT AREA.", Have.Item(PT_SUPER_ROD), listOf("FIGHT AREA")),
                entry("VS SEEKER", "PROF. ROWAN's assistant gives it to you on ROUTE 207.", "ROUTE 207.",
                    Have.Item(PT_VS_SEEKER), listOf("ROUTE 207")),
                entry("EXPLORER KIT", "The UNDERGROUND MAN gives it to you in his house in ETERNA CITY.", "ETERNA CITY.",
                    Have.Item(PT_EXPLORER_KIT), listOf("ETERNA CITY")),
                entry("POKé RADAR", "PROF. ROWAN gives it to you in SANDGEM TOWN once you've seen every POKéMON in the SINNOH DEX.",
                    "SANDGEM TOWN.", Have.Item(PT_POKE_RADAR), listOf("SANDGEM TOWN")),
            ),
        ),
        page(
            "STUCK?",
            section(
                "BLOCKED PATHS",
                entry("PSYDUCK block ROUTE 210", "They need the SECRETPOTION, which CYNTHIA gives you at VALOR LAKEFRONT. Talk to them with it in your bag.",
                    "A medicine.", Have.Item(PT_SECRETPOTION), listOf("ROUTE 210")),
                entry("Thick fog on ROUTE 210", "Use DEFOG (HM05, from the SOLACEON RUINS; it needs the RELIC BADGE).", "A field move clears it.",
                    Have.Item(PT_HM01 + 4), listOf("ROUTE 210")),
                entry("The VALLEY WINDWORKS is locked", "Beat the grunts in FLOAROMA MEADOW: one of them drops the WORKS KEY there.",
                    "Somebody has the key.", Have.Item(PT_WORKS_KEY), listOf("VALLEY WINDWORKS", "FLOAROMA MEADOW")),
                entry("Can't get into the GALACTIC WAREHOUSE", "It opens with the STORAGE KEY. After what happens at LAKE ACUITY, a grunt " +
                    "turns up in VEILSTONE CITY: talk to him and he drops it.", "A key in VEILSTONE CITY.", Have.Item(PT_STORAGE_KEY), listOf("VEILSTONE CITY")),
                entry("Locked doors in the GALACTIC HQ", "The GALACTIC KEY is an item ball on the HQ's B2F.", "Look downstairs.",
                    Have.Item(PT_GALACTIC_KEY), listOf("VEILSTONE CITY")),
                entry("Rocks in the way", "Small cracked rocks break with ROCK SMASH, big round ones move with STRENGTH, rocky walls are climbed " +
                    "with ROCK CLIMB. WHERE IS has each HM.", "A field move."),
            ),
        ),
    ),
)
