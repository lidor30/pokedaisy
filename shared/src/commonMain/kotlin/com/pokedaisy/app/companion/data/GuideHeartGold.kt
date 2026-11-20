package com.pokedaisy.app.companion.data

/**
 * Pokémon HeartGold / SoulSilver's guide (USA). HERE and NEXT BOSS come from [HEARTGOLD_WORLD] / [SOULSILVER_WORLD]
 * (generated from pret/pokeheartgold; the two games' wild Pokémon differ, their trainers don't). NEXT BOSS: the gym
 * leaders by their badges (include/constants/badge.h: the leaders' own trainer flags are cleared again for rematches),
 * the Elite Four by FLAG_DEFEATED_WILL .. KAREN or a cleared game (FLAG_GAME_CLEAR, set when LANCE is beaten), RED by
 * his trainer flag (TRAINER_FLAG_BASE + id).
 * Kanto's gyms can be taken in any order; they're listed in the order the boat and the roads bring you to them.
 *
 * The hand-written pages are from pokeheartgold's scripts (files/fielddata/script/scr_seq), in our own words: the HMs
 * (Ilex Forest's charcoal man once both FARFETCH'D are back; Cianwood after CHUCK; the Dance Theater after its grunt;
 * Route 42's hiker; LANCE in the Rocket HQ after its three ELECTRODE; Route 36's man; an Ice Path 1F item ball; OAK after
 * BLUE), each field move's badge (src/field_move.c), the key items' givers and the blocks: the odd tree (SQUIRTBOTTLE,
 * WHITNEY's badge), Ecruteak's gym (the Burned Tower), JASMINE (the lighthouse, the SECRETPOTION from Cianwood), the Lake
 * of Rage and Rocket HQ (PRYCE's door, Route 43's toll), the Radio Tower (all four of FOG / STORM / MINERAL / GLACIER;
 * BASEMENT KEY, CARD KEY), Mahogany's east road and BLACKTHORN's gym (both after the Radio Tower), the RISING BADGE (the
 * Dragon's Den elder's quiz), the road east of New Bark (the bell, the version's legend), Kanto's Power Plant (MACHINE
 * PART), BLUE (seven Kanto badges, then Cinnabar), Mt. Silver (OAK), the S.S. AQUA (a cleared game, the S.S. TICKET).
 */
private fun hgGym(world: Gen4World, title: String, where: String, key: String, badge: Int) =
    gen4Boss(world, title, where, key, GEN4_BADGE_FLAG + badge)

private fun hgLeague(world: Gen4World, title: String, key: String, flag: Int) =
    gen4Boss(world, title, "POKéMON LEAGUE", key, flag, doneIf = { p -> p.flag(HG_FLAG_GAME_CLEAR) || p.flag(flag) })

private fun hgBosses(w: Gen4World) = listOf(
    hgGym(w, "LEADER FALKNER", "VIOLET CITY GYM", "FALKNER", 0),
    hgGym(w, "LEADER BUGSY", "AZALEA TOWN GYM", "BUGSY", 1),
    hgGym(w, "LEADER WHITNEY", "GOLDENROD CITY GYM", "WHITNEY", 2),
    hgGym(w, "LEADER MORTY", "ECRUTEAK CITY GYM", "MORTY", 3),
    hgGym(w, "LEADER CHUCK", "CIANWOOD CITY GYM", "CHUCK", 4),
    hgGym(w, "LEADER JASMINE", "OLIVINE CITY GYM", "JASMINE", 5),
    hgGym(w, "LEADER PRYCE", "MAHOGANY TOWN GYM", "PRYCE", 6),
    hgGym(w, "LEADER CLAIR", "BLACKTHORN CITY GYM", "CLAIR", 7),
    hgLeague(w, "ELITE FOUR WILL", "WILL", HG_FLAG_DEFEATED_WILL),
    hgLeague(w, "ELITE FOUR KOGA", "KOGA", HG_FLAG_DEFEATED_KOGA),
    hgLeague(w, "ELITE FOUR BRUNO", "BRUNO", HG_FLAG_DEFEATED_BRUNO),
    hgLeague(w, "ELITE FOUR KAREN", "KAREN", HG_FLAG_DEFEATED_KAREN),
    gen4Boss(w, "CHAMPION LANCE", "POKéMON LEAGUE", "LANCE", HG_FLAG_GAME_CLEAR),
    hgGym(w, "LEADER LT. SURGE", "VERMILION CITY GYM", "LT_SURGE", 10),
    hgGym(w, "LEADER SABRINA", "SAFFRON CITY GYM", "SABRINA", 13),
    hgGym(w, "LEADER MISTY", "CERULEAN CITY GYM", "MISTY", 9),
    hgGym(w, "LEADER ERIKA", "CELADON CITY GYM", "ERIKA", 11),
    hgGym(w, "LEADER JANINE", "FUCHSIA CITY GYM", "JANINE", 12),
    hgGym(w, "LEADER BROCK", "PEWTER CITY GYM", "BROCK", 8),
    hgGym(w, "LEADER BLAINE", "SEAFOAM ISLANDS", "BLAINE", 14),
    hgGym(w, "LEADER BLUE", "VIRIDIAN CITY GYM", "BLUE", 15),
    gen4Boss(w, "RED", "MT. SILVER", "RED", HG_TRAINER_FLAGS + w.trainer("RED")),
)

private const val HG_HM01 = 420
private fun hm(n: Int, move: String, answer: String, hint: String, areas: List<String>) =
    entry("HM${n.pad(2)} $move", answer, hint, Have.Item(HG_HM01 + n - 1), areas)

private fun hgPages(soulSilver: Boolean): List<GuidePage> {
    val wing = if (soulSilver) "SILVER WING" else "RAINBOW WING"
    val wingId = if (soulSilver) 482 else 483
    val otherWing = if (soulSilver) "RAINBOW WING" else "SILVER WING"
    val bell = if (soulSilver) "TIDAL BELL" else "CLEAR BELL"
    val bellId = if (soulSilver) 503 else 474
    val legend = if (soulSilver) "LUGIA at the bottom of the WHIRL ISLANDS" else "HO-OH on the roof of the BELL TOWER"
    return listOf(
        page(
            "TIPS",
            section(
                "WILD POKéMON",
                entry(
                    "Why did the POKéMON on this route change?",
                    "The grass has a list for the morning (4 AM to 10 AM), the day (until 8 PM) and the night. At night the " +
                        "GOOD and SUPER ROD can also hook a night fish. HERE shows the list for the time on your DS clock, " +
                        "which is your device's.",
                    hint = "Look at the clock.",
                ),
            ),
            section(
                "FIELD MOVES",
                entry(
                    "Which badge lets me use each HM outside battle?",
                    "ROCK SMASH needs the ZEPHYR BADGE (FALKNER), CUT the HIVE BADGE (BUGSY), STRENGTH the PLAIN BADGE " +
                        "(WHITNEY), SURF the FOG BADGE (MORTY), FLY the STORM BADGE (CHUCK), WHIRLPOOL the GLACIER BADGE " +
                        "(PRYCE), WATERFALL the RISING BADGE (CLAIR) and ROCK CLIMB the EARTH BADGE (BLUE).",
                    hint = "Each one has its own badge.",
                ),
                entry(
                    "CLAIR didn't give me a badge",
                    "Beating her only lets you into the DRAGON'S DEN behind her gym. The elder in its shrine gives you the " +
                        "RISING BADGE once you've answered his questions; a wrong answer just gets the question again.",
                    hint = "Behind her gym.",
                ),
            ),
        ),
        page(
            "WHERE IS",
            section(
                "HMs",
                hm(1, "CUT", "The charcoal man in ILEX FOREST, once you've herded both FARFETCH'D back to his apprentice. " +
                    "The FARFETCH'D only wander off after you've cleared SLOWPOKE WELL.", "ILEX FOREST.", listOf("ILEX FOREST")),
                hm(2, "FLY", "A woman outside the gym in CIANWOOD CITY, as you leave after beating CHUCK.", "CIANWOOD CITY.", listOf("CIANWOOD CITY")),
                hm(3, "SURF", "A gentleman in ECRUTEAK's DANCE THEATER, once you've beaten the grunt bothering the dancers there.",
                    "ECRUTEAK CITY.", listOf("ECRUTEAK CITY")),
                hm(4, "STRENGTH", "A hiker runs into you the first time you walk onto ROUTE 42 from ECRUTEAK CITY.", "ROUTE 42.", listOf("ROUTE 42")),
                hm(5, "WHIRLPOOL", "LANCE gives it to you in TEAM ROCKET's hideout in MAHOGANY TOWN, after the three ELECTRODE on B2F.",
                    "MAHOGANY TOWN.", listOf("MAHOGANY TOWN")),
                hm(6, "ROCK SMASH", "A man on ROUTE 36, just east of the odd tree.", "ROUTE 36.", listOf("ROUTE 36")),
                hm(7, "WATERFALL", "An item ball on the ICE PATH's first floor.", "ICE PATH.", listOf("ICE PATH")),
                hm(8, "ROCK CLIMB", "PROF. OAK in PALLET TOWN, after you've beaten BLUE in VIRIDIAN CITY and he's called you.",
                    "PALLET TOWN.", listOf("PALLET TOWN")),
            ),
            section(
                "KEY ITEMS",
                entry("BICYCLE", "The bike shop in GOLDENROD CITY lends you one: just say yes.", "GOLDENROD CITY.", Have.Item(450), listOf("GOLDENROD CITY")),
                entry("OLD ROD", "A man in ROUTE 32's POKéMON CENTER.", "ROUTE 32.", Have.Item(445), listOf("ROUTE 32")),
                entry("GOOD ROD", "A house in OLIVINE CITY's north-west.", "OLIVINE CITY.", Have.Item(446), listOf("OLIVINE CITY")),
                entry("SUPER ROD", "The fishing brother's house on ROUTE 12, in KANTO.", "ROUTE 12.", Have.Item(447), listOf("ROUTE 12")),
                entry("SQUIRTBOTTLE", "The flower shop in GOLDENROD CITY, once you have the PLAIN BADGE.", "GOLDENROD CITY.",
                    Have.Item(477), listOf("GOLDENROD CITY")),
                entry("DOWSING MCHN", "A house in ECRUTEAK CITY: say yes.", "ECRUTEAK CITY.", Have.Item(471), listOf("ECRUTEAK CITY")),
                entry("COIN CASE", "Someone in the GOLDENROD or CELADON game corner.", "A game corner.", Have.Item(444),
                    listOf("GOLDENROD CITY", "CELADON CITY")),
                entry("RADIO CARD", "Answer the quiz on the RADIO TOWER's first floor in GOLDENROD CITY.", "GOLDENROD CITY.",
                    Have.Flag(0x8A), listOf("GOLDENROD CITY")),
                entry("EXPN CARD", "The director of LAVENDER TOWN's radio station, once the power's back on.", "LAVENDER TOWN.",
                    Have.Flag(0x11F), listOf("LAVENDER TOWN")),
                entry(wing, "The director on the RADIO TOWER's top floor, once TEAM ROCKET is gone from it. The $otherWing comes " +
                    "later, from an old man in PEWTER CITY.", "GOLDENROD CITY.", Have.Item(wingId), listOf("GOLDENROD CITY")),
                entry(bell, "Beat the five dancers in a row in ECRUTEAK's DANCE THEATER.", "ECRUTEAK CITY.", Have.Item(bellId), listOf("ECRUTEAK CITY")),
                entry("S.S. TICKET", "PROF. ELM in NEW BARK TOWN, once you've entered the HALL OF FAME.", "NEW BARK TOWN.",
                    Have.Item(456), listOf("NEW BARK TOWN")),
                entry("PASS", "COPYCAT in SAFFRON CITY trades it for the LOST ITEM, which VERMILION's fan club has once you've " +
                    "talked to her after the power's back.", "SAFFRON CITY.", Have.Item(480), listOf("SAFFRON CITY", "VERMILION CITY")),
            ),
        ),
        page(
            "STUCK?",
            section(
                "JOHTO",
                entry("An odd tree blocks ROUTE 36", "Water it with the SQUIRTBOTTLE, from GOLDENROD's flower shop once you have " +
                    "WHITNEY's badge. It's a POKéMON, and the battle starts.", "It wants water.", Have.Item(477), listOf("ROUTE 36")),
                entry("ECRUTEAK's gym won't let me in", "Go down into the BURNED TOWER first: the gym opens after what happens " +
                    "on its lowest floor.", "The tower in town.", areas = listOf("ECRUTEAK CITY")),
                entry("JASMINE isn't in OLIVINE's gym", "She's at the top of the lighthouse with a sick AMPHAROS. Talk to her, " +
                    "get the SECRETPOTION from CIANWOOD's pharmacy, and bring it back to her.", "The lighthouse.",
                    Have.Item(464), listOf("OLIVINE CITY", "CIANWOOD CITY")),
                entry("How do I get to CIANWOOD CITY?", "Over the sea west of OLIVINE CITY: you need SURF.", "Across the water.",
                    areas = listOf("OLIVINE CITY")),
                entry("Someone blocks PRYCE's gym in MAHOGANY TOWN", "After the red GYARADOS at the LAKE OF RAGE, talk to LANCE " +
                    "there, then go into MAHOGANY's souvenir shop. Clearing TEAM ROCKET's hideout behind it moves him " +
                    "(and ends ROUTE 43's toll).", "The LAKE OF RAGE first.", Have.Flag(0xCA), listOf("MAHOGANY TOWN", "LAKE OF RAGE", "ROUTE 43")),
                entry("TEAM ROCKET took the RADIO TOWER", "It starts once you hold the FOG, STORM, MINERAL and GLACIER BADGES. " +
                    "The fake director on the top floor drops the BASEMENT KEY; it opens a door in the GOLDENROD TUNNEL, where " +
                    "the real director gives you the CARD KEY for the tower's third floor.", "Two keys.", Have.Flag(0xC6),
                    listOf("GOLDENROD CITY")),
                entry("The road east of MAHOGANY TOWN, or BLACKTHORN's gym, is blocked", "Both clear once TEAM ROCKET is out " +
                    "of the RADIO TOWER.", "GOLDENROD first.", Have.Flag(0xC6), listOf("MAHOGANY TOWN", "BLACKTHORN CITY")),
                entry("I'm stopped at NEW BARK TOWN's east exit", "After the RISING BADGE, see PROF. ELM, then go to ECRUTEAK, get the " +
                    "$bell in the DANCE THEATER and face $legend. The road to the LEAGUE opens after that.", "A legendary POKéMON.",
                    areas = listOf("NEW BARK TOWN")),
            ),
            section(
                "KANTO",
                entry("The power is out", "Talk to the POWER PLANT's manager, then go into CERULEAN's gym, follow MISTY to ROUTE 25, " +
                    "and beat the grunt on ROUTE 24. The MACHINE PART then turns up in CERULEAN's gym: bring it back to the manager.",
                    "CERULEAN CITY.", Have.Flag(0x118), listOf("ROUTE 10", "CERULEAN CITY", "ROUTE 24", "ROUTE 25")),
                entry("VIRIDIAN's gym is locked", "Win seven of KANTO's badges, then find BLUE on CINNABAR ISLAND and talk to him.",
                    "CINNABAR ISLAND.", areas = listOf("VIRIDIAN CITY", "CINNABAR ISLAND")),
                entry("A big POKéMON sleeps by DIGLETT'S CAVE", "Play the POKé FLUTE station on your POKéGEAR's radio (it needs " +
                    "the EXPN CARD) and talk to it.", "Music wakes it.", Have.Flag(0x11F), listOf("ROUTE 11")),
                entry("How do I get to MT. SILVER?", "Beat BLUE in VIRIDIAN CITY, then see PROF. OAK in PALLET TOWN: he gives you " +
                    "ROCK CLIMB and has the guard at the LEAGUE's gate let you through to ROUTE 28.", "PROF. OAK.", Have.Flag(0x12B), listOf("PALLET TOWN", "ROUTE 28")),
                entry("When does the S.S. AQUA sail?", "Once you're in the HALL OF FAME and have the S.S. TICKET. After the first " +
                    "trip it leaves OLIVINE on Monday and Friday, VERMILION on Sunday and Wednesday.", "Weekdays.",
                    Have.Item(456), listOf("OLIVINE CITY", "VERMILION CITY")),
            ),
        ),
    )
}

internal val GUIDE_HEARTGOLD = GameGuide(verified = false, bosses = hgBosses(HEARTGOLD_WORLD), world = HEARTGOLD_WORLD, pages = hgPages(soulSilver = false))
internal val GUIDE_SOULSILVER = GameGuide(verified = false, bosses = hgBosses(SOULSILVER_WORLD), world = SOULSILVER_WORLD, pages = hgPages(soulSilver = true))
