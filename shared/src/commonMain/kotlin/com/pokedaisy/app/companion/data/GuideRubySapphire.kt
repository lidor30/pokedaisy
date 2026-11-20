package com.pokedaisy.app.companion.data

/**
 * Ruby and Sapphire's guide - one text, built per version. Every fact was
 * checked against pret/pokeruby (whose ruby_rev1 / sapphire_rev1 builds are
 * byte-identical to the user's ROMs): its map scripts
 * (data/maps/…/scripts.inc) for where things are given and what a gate
 * checks, and src/ for the rules (badge -> HM in pokemon_menu.c, obedience in
 * battle_util.c, rematches in battle_setup.c, the braille puzzles in
 * braille_puzzles.c, the roamer in roamer.c). Where they differ from
 * Emerald: the villain team follows the version (TEAM MAGMA in Ruby, TEAM
 * AQUA in Sapphire - the scripts' VAR_OBJ_GFX_ID_1 grunts), the eighth gym is
 * WALLACE's and the champion STEVEN, the SOOTOPOLIS crisis ends in the CAVE OF
 * ORIGIN, HM07 lies there, STEVEN hands out HM08 on your first visit, the
 * fossils are in the ROUTE 111 desert, the REGI puzzle is different, and
 * there's no MIRAGE TOWER, BATTLE FRONTIER or JOHTO starter gift.
 */
// include/constants/flags.h (SYSTEM_FLAGS is 0x800 here, not Emerald's 0x860); items as in Emerald.
private const val FLAG_RECEIVED_BIKE = 0x5A
private const val FLAG_RECEIVED_HM08 = 0x7B
private const val FLAG_RECEIVED_POKENAV = 0xBC
private const val FLAG_RECEIVED_EXP_SHARE = 0x110
private const val FLAG_SYS_GAME_CLEAR = 0x804
private const val FLAG_BADGE01_GET = 0x807
private const val FLAG_SYS_B_DASH = 0x860
private const val FLAG_DEFEATED_ELITE_4_SIDNEY = 0x4DD // .. _DRAKE 0x4E0
private const val ITEM_COIN_CASE = 260
private const val ITEM_ITEMFINDER = 261
private const val ITEM_OLD_ROD = 262
private const val ITEM_GOOD_ROD = 263
private const val ITEM_SUPER_ROD = 264
private const val ITEM_WAILMER_PAIL = 268
private const val ITEM_SOOT_SACK = 270
private const val ITEM_GO_GOGGLES = 279
private const val ITEM_DEVON_SCOPE = 288
private const val ITEM_HM01 = 339

/** The gyms, then the League. Trainer ids are Emerald's (opponents.h), but the
 * eighth leader is WALLACE (272) and the champion STEVEN (335). */
private val BOSSES_RUBY_SAPPHIRE = listOf(
    Boss("LEADER ROXANNE", "RUSTBORO CITY GYM", FLAG_BADGE01_GET) { 265 },
    Boss("LEADER BRAWLY", "DEWFORD TOWN GYM", FLAG_BADGE01_GET + 1) { 266 },
    Boss("LEADER WATTSON", "MAUVILLE CITY GYM", FLAG_BADGE01_GET + 2) { 267 },
    Boss("LEADER FLANNERY", "LAVARIDGE TOWN GYM", FLAG_BADGE01_GET + 3) { 268 },
    Boss("LEADER NORMAN", "PETALBURG CITY GYM", FLAG_BADGE01_GET + 4) { 269 },
    Boss("LEADER WINONA", "FORTREE CITY GYM", FLAG_BADGE01_GET + 5) { 270 },
    Boss("LEADERS TATE AND LIZA", "MOSSDEEP CITY GYM", FLAG_BADGE01_GET + 6) { 271 },
    Boss("LEADER WALLACE", "SOOTOPOLIS CITY GYM", FLAG_BADGE01_GET + 7) { 272 },
    Boss("ELITE FOUR SIDNEY", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY) { 261 },
    Boss("ELITE FOUR PHOEBE", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + 1) { 262 },
    Boss("ELITE FOUR GLACIA", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + 2) { 263 },
    Boss("ELITE FOUR DRAKE", "POKéMON LEAGUE", FLAG_DEFEATED_ELITE_4_SIDNEY + 3) { 264 },
    Boss("CHAMPION STEVEN", "POKéMON LEAGUE", FLAG_SYS_GAME_CLEAR) { 335 },
)

internal val GUIDE_RUBY = rubySapphireGuide(ruby = true)
internal val GUIDE_SAPPHIRE = rubySapphireGuide(ruby = false)

private fun rubySapphireGuide(ruby: Boolean): GameGuide {
    val team = if (ruby) "TEAM MAGMA" else "TEAM AQUA"
    val legend = if (ruby) "GROUDON" else "KYOGRE"
    val legendNo = if (ruby) 383 else 382
    val roamer = if (ruby) "LATIOS" else "LATIAS"
    val roamerNo = if (ruby) 381 else 380
    return GameGuide(
        // Every fact is from the decomp, but nobody has walked through it in-game yet.
        verified = false,
        bosses = BOSSES_RUBY_SAPPHIRE,
        pages = listOf(
            page(
                "TIPS",
                section(
                    "CONTROLS",
                    entry("Running", "Hold B to run once you have the RUNNING SHOES."),
                    entry("Two bikes", "The MACH BIKE is the fast one - fast enough to cross cracked floors before they give way. The ACRO BIKE can hop and ride narrow rails. RYDEL in MAUVILLE CITY swaps one for the other."),
                    entry(
                        "Rematches",
                        "Trainers you've beaten are listed in the POKéNAV's TRAINER'S EYES. Once you have five badges, " +
                            "walking around makes some of them want a rematch.",
                    ),
                ),
                section(
                    "BATTLES",
                    entry(
                        "Physical or special?",
                        "In this generation it's decided by the move's TYPE, not the move. FIRE, WATER, GRASS, ELECTRIC, ICE, PSYCHIC, " +
                            "DRAGON and DARK moves are special; NORMAL, FIGHTING, FLYING, POISON, GROUND, ROCK, BUG, GHOST and STEEL are physical.",
                    ),
                    entry(
                        "A traded POKéMON won't listen",
                        "Traded POKéMON obey up to Lv10 with no badges, Lv30 with the KNUCKLE BADGE, Lv50 with the HEAT BADGE, Lv70 with the FEATHER BADGE, and always with the RAIN BADGE.",
                        hint = "It's about badges.",
                    ),
                    entry("Poison outside battle", "Poison keeps hurting while you walk and can make a POKéMON faint. Cure it or heal at a POKéMON CENTER."),
                ),
                section(
                    "HMs",
                    entry(
                        "Which badge unlocks which HM?",
                        "CUT: STONE. FLASH: KNUCKLE. ROCK SMASH: DYNAMO. STRENGTH: HEAT. SURF: BALANCE. FLY: FEATHER. DIVE: MIND. WATERFALL: RAIN. " +
                            "A POKéMON in the party must know the move too.",
                    ),
                    entry("Getting rid of an HM move", "HM moves can't be forgotten normally. The MOVE DELETER in LILYCOVE CITY removes them."),
                    entry("Relearning moves", "The MOVE TUTOR in FALLARBOR TOWN teaches a forgotten move for one HEART SCALE."),
                ),
                section(
                    "SAFARI ZONE",
                    entry("How long do I have?", "500 steps per visit. You can't use your own POKéMON - throw POKéBLOCKS, then SAFARI BALLS."),
                ),
            ),
            page(
                "WHERE IS",
                section(
                    "KEY ITEMS",
                    entry("RUNNING SHOES", "MOM gives them to you in LITTLEROOT TOWN, on your way out after PROF. BIRCH hands you the POKéDEX.", hint = "Someone at home.", have = Have.Flag(FLAG_SYS_B_DASH), areas = listOf("LITTLEROOT TOWN")),
                    entry("POKéNAV", "MR. STONE on DEVON CORP. 3F in RUSTBORO CITY, after you bring back the stolen DEVON GOODS.", hint = "The company in RUSTBORO CITY.", have = Have.Flag(FLAG_RECEIVED_POKENAV), areas = listOf("RUSTBORO CITY")),
                    entry("BIKE", "RYDEL's bike shop in MAUVILLE CITY - pick the MACH or the ACRO BIKE.", hint = "A shop in MAUVILLE CITY.", have = Have.Flag(FLAG_RECEIVED_BIKE), areas = listOf("MAUVILLE CITY")),
                    entry("OLD ROD", "A fisherman in DEWFORD TOWN.", hint = "An island town.", have = Have.Item(ITEM_OLD_ROD), areas = listOf("DEWFORD TOWN")),
                    entry("GOOD ROD", "A fisherman on ROUTE 118.", hint = "East of MAUVILLE CITY.", have = Have.Item(ITEM_GOOD_ROD), areas = listOf("ROUTE 118")),
                    entry("SUPER ROD", "A fisherman's house in MOSSDEEP CITY.", hint = "An island city in the east.", have = Have.Item(ITEM_SUPER_ROD), areas = listOf("MOSSDEEP CITY")),
                    entry("ITEMFINDER", "Your rival gives it to you after your battle on ROUTE 110.", hint = "North of SLATEPORT CITY.", have = Have.Item(ITEM_ITEMFINDER), areas = listOf("ROUTE 110")),
                    entry("EXP. SHARE", "MR. STONE at DEVON CORP. 3F, once you've delivered his letter to STEVEN in GRANITE CAVE.", hint = "A reward for a delivery.", have = Have.Flag(FLAG_RECEIVED_EXP_SHARE), areas = listOf("RUSTBORO CITY", "GRANITE CAVE")),
                    entry("GO-GOGGLES", "Your rival gives them to you in LAVARIDGE TOWN after you beat FLANNERY. The ROUTE 111 desert is closed without them.", hint = "After the fourth gym.", have = Have.Item(ITEM_GO_GOGGLES), areas = listOf("LAVARIDGE TOWN", "ROUTE 111")),
                    entry("DEVON SCOPE", "STEVEN on ROUTE 120, after the invisible POKéMON on the bridge.", hint = "East of FORTREE CITY.", have = Have.Item(ITEM_DEVON_SCOPE), areas = listOf("ROUTE 120", "FORTREE CITY")),
                    entry("WAILMER PAIL", "The PRETTY PETAL flower shop on ROUTE 104.", hint = "A shop between PETALBURG CITY and RUSTBORO CITY.", have = Have.Item(ITEM_WAILMER_PAIL), areas = listOf("ROUTE 104")),
                    entry("SOOT SACK", "The GLASS WORKSHOP on ROUTE 113. Walk through the falling ash to collect it.", hint = "Where it rains ash.", have = Have.Item(ITEM_SOOT_SACK), areas = listOf("ROUTE 113")),
                    entry("COIN CASE", "A woman in a MAUVILLE CITY house trades it for a HARBOR MAIL.", hint = "A trade in MAUVILLE CITY.", have = Have.Item(ITEM_COIN_CASE), areas = listOf("MAUVILLE CITY")),
                ),
                section(
                    "HMs",
                    entry("HM01 CUT", "The CUTTER's house in RUSTBORO CITY.", hint = "RUSTBORO CITY.", have = Have.Item(ITEM_HM01), areas = listOf("RUSTBORO CITY")),
                    entry("HM02 FLY", "Your rival, after your battle on ROUTE 119.", hint = "North of FORTREE CITY's route.", have = Have.Item(ITEM_HM01 + 1), areas = listOf("ROUTE 119")),
                    entry("HM03 SURF", "WALLY's father in PETALBURG CITY, after you beat NORMAN.", hint = "After the fifth gym.", have = Have.Item(ITEM_HM01 + 2), areas = listOf("PETALBURG CITY")),
                    entry("HM04 STRENGTH", "WANDA's boyfriend in RUSTURF TUNNEL, once you break the boulder there with ROCK SMASH.", hint = "The tunnel east of RUSTBORO CITY.", have = Have.Item(ITEM_HM01 + 3), areas = listOf("RUSTURF TUNNEL")),
                    entry("HM05 FLASH", "A hiker just inside GRANITE CAVE on DEWFORD's island.", hint = "A dark cave.", have = Have.Item(ITEM_HM01 + 4), areas = listOf("GRANITE CAVE")),
                    entry("HM06 ROCK SMASH", "The ROCK SMASH GUY in a MAUVILLE CITY house.", hint = "MAUVILLE CITY.", have = Have.Item(ITEM_HM01 + 5), areas = listOf("MAUVILLE CITY")),
                    entry("HM07 WATERFALL", "An item ball on CAVE OF ORIGIN B3F, on your way down to $legend.", hint = "Under SOOTOPOLIS CITY, late in the story.", have = Have.Item(ITEM_HM01 + 6), areas = listOf("CAVE OF ORIGIN", "SOOTOPOLIS CITY")),
                    entry("HM08 DIVE", "STEVEN hands it to you the first time you visit his house in MOSSDEEP CITY.", hint = "MOSSDEEP CITY.", have = Have.Flag(FLAG_RECEIVED_HM08), areas = listOf("MOSSDEEP CITY")),
                ),
                section(
                    "SHOPS & SERVICES",
                    entry(
                        "Evolution stones",
                        "The TREASURE HUNTER's house on ROUTE 124 swaps shards found by DIVING: RED for a FIRE STONE, YELLOW for THUNDER, BLUE for WATER, GREEN for LEAF.",
                        hint = "Diving treasure.", areas = listOf("ROUTE 124"),
                    ),
                    entry("MOVE DELETER", "A house in LILYCOVE CITY.", areas = listOf("LILYCOVE CITY")),
                    entry("MOVE TUTOR", "A house in FALLARBOR TOWN. Bring a HEART SCALE.", areas = listOf("FALLARBOR TOWN")),
                ),
                section(
                    "GIFT POKéMON",
                    entry("CASTFORM", "The WEATHER INSTITUTE on ROUTE 119 gives it to you after you drive $team out.", hint = "A research building.", have = Have.Caught(351), areas = listOf("ROUTE 119")),
                    entry("WYNAUT (egg)", "An old woman in LAVARIDGE TOWN.", hint = "A hot-spring town.", have = Have.Caught(360), areas = listOf("LAVARIDGE TOWN")),
                    entry(
                        "LILEEP / ANORITH",
                        "In the ROUTE 111 desert, take the ROOT or the CLAW FOSSIL (the other sinks into the sand); DEVON CORP. 2F in RUSTBORO CITY revives it.",
                        hint = "Buried in the desert.",
                        have = Have.Caught(345, 347), areas = listOf("ROUTE 111", "RUSTBORO CITY"),
                    ),
                    entry("BELDUM", "STEVEN's house in MOSSDEEP CITY, after the HALL OF FAME.", hint = "Post-game.", have = Have.Caught(374), areas = listOf("MOSSDEEP CITY")),
                ),
                section(
                    "LEGENDARY POKéMON",
                    entry(
                        legend,
                        "Lv45, at the bottom of the CAVE OF ORIGIN in SOOTOPOLIS CITY - the story takes you there.",
                        hint = "Its cave is under SOOTOPOLIS CITY.",
                        have = Have.Caught(legendNo), areas = listOf("CAVE OF ORIGIN", "SOOTOPOLIS CITY"),
                    ),
                    entry(
                        "RAYQUAZA",
                        "Lv70, at the top of the SKY PILLAR on ROUTE 131. Some floors crack: ride the MACH BIKE across them.",
                        hint = "A tower in the sea near PACIFIDLOG TOWN.",
                        have = Have.Caught(384), areas = listOf("SKY PILLAR", "ROUTE 131"),
                    ),
                    entry(
                        "REGIROCK / REGICE / REGISTEEL",
                        "First open the doors: in the SEALED CHAMBER (DIVE on ROUTE 134) use DIG on the outer room's braille, then read the inner room's with RELICANTH first and WAILORD last in your party. " +
                            "Then, after reading each ruin's braille: DESERT RUINS (ROUTE 111) - use STRENGTH; ANCIENT TOMB (ROUTE 120) - use FLY; " +
                            "ISLAND CAVE (ROUTE 105) - wait two minutes without pressing anything.",
                        hint = "It starts under the sea, with two particular POKéMON.",
                        have = Have.Caught(377, 378, 379), areas = listOf("SEALED CHAMBER", "ROUTE 134", "DESERT RUINS", "ANCIENT TOMB", "ISLAND CAVE"),
                    ),
                    entry(
                        roamer,
                        "After the HALL OF FAME, go home and watch the TV news: $roamer starts roaming HOENN.",
                        hint = "Post-game, at home.",
                        have = Have.Caught(roamerNo), areas = listOf("LITTLEROOT TOWN"),
                    ),
                    entry("KECLEON", "Invisible ones sit on ROUTES 119 and 120 and in FORTREE CITY. The DEVON SCOPE reveals them.", hint = "You can't see it.", have = Have.Caught(352), areas = listOf("ROUTE 119", "ROUTE 120", "FORTREE CITY")),
                ),
            ),
            page(
                "STUCK?",
                section(
                    "EARLY GAME",
                    entry("How do I get to DEWFORD TOWN?", "MR. BRINEY sails you from his cottage on ROUTE 104 once you've rescued his PEEKO from $team in RUSTURF TUNNEL.", hint = "An old sailor lost his pet.", areas = listOf("ROUTE 104", "RUSTURF TUNNEL")),
                    entry("Where are the stolen DEVON GOODS?", "A $team grunt fled into RUSTURF TUNNEL, east of RUSTBORO CITY. Beat him there.", hint = "Follow the thief east.", areas = listOf("RUSTBORO CITY", "RUSTURF TUNNEL")),
                    entry("PETALBURG CITY's gym won't let me in", "Your father NORMAN battles you once you have four badges.", hint = "Come back with more badges.", areas = listOf("PETALBURG CITY")),
                ),
                section(
                    "MID GAME",
                    entry("$team blocks the cable car on ROUTE 112", "They leave after the scene at METEOR FALLS - go north from MAUVILLE CITY on ROUTE 111, then west on ROUTE 113 to FALLARBOR TOWN and ROUTE 114.", hint = "Go around the mountain.", areas = listOf("ROUTE 112", "METEOR FALLS", "MT. CHIMNEY")),
                    entry("A sandstorm keeps pushing me back on ROUTE 111", "You need the GO-GOGGLES, from your rival in LAVARIDGE TOWN after the fourth badge.", hint = "Protect your eyes.", areas = listOf("ROUTE 111")),
                    entry("Something invisible blocks FORTREE CITY's gym", "It's a KECLEON. Get the DEVON SCOPE from STEVEN on ROUTE 120, then use it on the blocked spot.", hint = "Look east of FORTREE CITY.", areas = listOf("FORTREE CITY", "ROUTE 120")),
                ),
                section(
                    "LATE GAME",
                    entry("How do I get into SOOTOPOLIS CITY?", "It sits inside a crater: DIVE down on ROUTE 126 and surface inside.", hint = "From below.", areas = listOf("SOOTOPOLIS CITY", "ROUTE 126")),
                    entry("SOOTOPOLIS CITY's gym is closed", "End the crisis first: go down through the CAVE OF ORIGIN and face $legend at the bottom. WALLACE opens the gym afterwards.", hint = "The cave in SOOTOPOLIS CITY.", areas = listOf("SOOTOPOLIS CITY", "CAVE OF ORIGIN")),
                    entry("How do I reach EVER GRANDE CITY?", "It's up a waterfall: you need HM07 WATERFALL (in the CAVE OF ORIGIN) and the RAIN BADGE.", hint = "Go up.", areas = listOf("EVER GRANDE CITY", "ROUTE 128")),
                ),
            ),
        ),
    )
}
