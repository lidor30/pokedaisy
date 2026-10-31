package com.pokedaisy.app.companion.data

/**
 * FireRed's guide, and LeafGreen's - one text,
 * built per version. Every fact here was checked against the pokefirered
 * decomp's map scripts (data/maps/…/scripts.inc) - where an item is given,
 * what a gate checks - rather than taken from memory. The two versions differ
 * only where the scripts' `.ifdef FIRERED` / `.ifdef LEAFGREEN` say so (the
 * GAME CORNER prizes here; the in-game trades in HERE's area data).
 */
// include/constants/flags.h, vars.h, items.h
private const val FLAG_SYS_B_DASH = 0x82F // the RUNNING SHOES aren't a bag item
private const val FLAG_GOT_VS_SEEKER = 0x292
private const val FLAG_GOT_ITEMFINDER = 0x252
private const val FLAG_GOT_EXP_SHARE_FROM_OAKS_AIDE = 0x256
private const val FLAG_BADGE01_GET = 0x820
private const val FLAG_DEFEATED_LORELEI = 0x4B8 // .. _CHAMP 0x4BC, cleared again after the HALL OF FAME
private const val FLAG_SYS_CAN_LINK_WITH_RS = 0x844 // the SEVII story is done: the League has its rematch teams
private const val VAR_STARTER_MON = 0x4031 // 0 BULBASAUR, 1 SQUIRTLE, 2 CHARMANDER
private const val ITEM_OLD_ROD = 262
private const val ITEM_GOOD_ROD = 263
private const val ITEM_SUPER_ROD = 264
private const val ITEM_HM01 = 339 // .. HM07 345
private const val ITEM_BICYCLE = 360

/** include/constants/opponents.h: the first League team, or the rematch one (_2). */
private fun league(first: Int, rematch: Int): (SaveProgress) -> Int = { if (it.flag(FLAG_SYS_CAN_LINK_WITH_RS)) rematch else first }

/**
 * The gyms in the usual order, then the League (PokemonLeague_*Room scripts).
 * The champion is the rival, whose team answers the player's starter:
 * TRAINER_CHAMPION_FIRST_SQUIRTLE (438) when the player took CHARMANDER, etc.
 */
/** Ultra Violet's too (GuideUltraViolet.kt): the same trainers, badge scripts and League. */
internal val BOSSES_FIRERED = listOf(
    Boss("LEADER BROCK", "PEWTER CITY GYM", FLAG_BADGE01_GET) { 414 },
    Boss("LEADER MISTY", "CERULEAN CITY GYM", FLAG_BADGE01_GET + 1) { 415 },
    Boss("LEADER LT. SURGE", "VERMILION CITY GYM", FLAG_BADGE01_GET + 2) { 416 },
    Boss("LEADER ERIKA", "CELADON CITY GYM", FLAG_BADGE01_GET + 3) { 417 },
    Boss("LEADER KOGA", "FUCHSIA CITY GYM", FLAG_BADGE01_GET + 4) { 418 },
    Boss("LEADER SABRINA", "SAFFRON CITY GYM", FLAG_BADGE01_GET + 5) { 420 },
    Boss("LEADER BLAINE", "CINNABAR ISLAND GYM", FLAG_BADGE01_GET + 6) { 419 },
    Boss("LEADER GIOVANNI", "VIRIDIAN CITY GYM", FLAG_BADGE01_GET + 7) { 350 },
    Boss("ELITE FOUR LORELEI", "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI, trainer = league(410, 735)),
    Boss("ELITE FOUR BRUNO", "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI + 1, trainer = league(411, 736)),
    Boss("ELITE FOUR AGATHA", "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI + 2, trainer = league(412, 737)),
    Boss("ELITE FOUR LANCE", "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI + 3, trainer = league(413, 738)),
    Boss("CHAMPION (YOUR RIVAL)", "POKéMON LEAGUE", FLAG_DEFEATED_LORELEI + 4) { p ->
        val rivalOffset = when (p.variable(VAR_STARTER_MON)) { 2 -> 0; 1 -> 1; else -> 2 }
        league(438, 739)(p) + rivalOffset
    },
)

internal val GUIDE_FIRERED = fireRedGuide(leafGreen = false)
internal val GUIDE_LEAFGREEN = fireRedGuide(leafGreen = true)

// CeladonCity_GameCorner_PrizeRoom/scripts.inc: species, level, coins.
private fun prizeMons(leafGreen: Boolean) = if (leafGreen) {
    "ABRA Lv7 (120 coins), CLEFAIRY Lv12 (750), PINSIR Lv18 (2500), DRATINI Lv24 (4600), PORYGON Lv18 (6500)"
} else {
    "ABRA Lv9 (180 coins), CLEFAIRY Lv8 (500), DRATINI Lv18 (2800), SCYTHER Lv25 (5500), PORYGON Lv26 (9999)"
}

private fun fireRedGuide(leafGreen: Boolean) = GameGuide(
    bosses = BOSSES_FIRERED,
    pages = listOf(
        page(
            "TIPS",
            section(
                "RANDOMIZED",
                Randomized.ONLY,
                entry(
                    "This game is randomized",
                    "HERE, NEXT BOSS, EVOLUTIONS and the DEX read the randomizer's POKéMON, teams, moves and items from the game itself. " +
                        "The gift and legendary POKéMON it moved aren't listed: the guide can't know them.",
                ),
            ),
            section(
                "CONTROLS",
                entry("In-game help", "Press L or R almost anywhere for the game's own HELP menu (OPTION: BUTTON MODE = HELP, the default)."),
                entry("Running", "Hold B to run once you have the RUNNING SHOES."),
                entry("Rematches", "The VS SEEKER finds trainers who want a rematch. It recharges as you walk."),
            ),
            section(
                "BATTLES",
                entry(
                    "Physical or special?",
                    "In this generation it's decided by the move's TYPE, not the move. FIRE, WATER, GRASS, ELECTRIC, ICE, PSYCHIC, " +
                        "DRAGON and DARK moves are special; NORMAL, FIGHTING, FLYING, POISON, GROUND, ROCK, BUG, GHOST and STEEL are physical.",
                ),
                entry("Poison outside battle", "Poison keeps hurting while you walk and can make a POKéMON faint. Cure it or heal at a POKéMON CENTER."),
            ),
            section(
                "HMs",
                entry(
                    "Which badge unlocks which HM?",
                    "FLASH: BOULDER. CUT: CASCADE. FLY: THUNDER. STRENGTH: RAINBOW. SURF: SOUL. ROCK SMASH: MARSH. WATERFALL: VOLCANO. " +
                        "A POKéMON in the party must know the move too.",
                ),
                entry("Getting rid of an HM move", "HM moves can't be forgotten normally. The MOVE DELETER in FUCHSIA CITY removes them."),
                entry("Relearning moves", "The MOVE RELEARNER on TWO ISLAND teaches a forgotten move for a BIG MUSHROOM or two TINY MUSHROOMS."),
            ),
            section(
                "SAFARI ZONE",
                entry("How long do I have?", "600 steps per visit. You can't use your own POKéMON - throw bait or rocks, then SAFARI BALLS."),
            ),
        ),
        page(
            "WHERE IS",
            section(
                "KEY ITEMS",
                entry("RUNNING SHOES", "PROF. OAK's AIDE gives them to you as you leave PEWTER CITY after beating BROCK.", hint = "After the first gym.", have = Have.Flag(FLAG_SYS_B_DASH), areas = listOf("PEWTER CITY")),
                entry("BICYCLE", "Get a BIKE VOUCHER from the chairman of the POKéMON FAN CLUB in VERMILION CITY, then trade it at CERULEAN CITY's bike shop.", hint = "Someone in VERMILION CITY loves to talk about his POKéMON.", have = Have.Item(ITEM_BICYCLE), areas = listOf("VERMILION CITY", "CERULEAN CITY")),
                entry("VS SEEKER", "An AIDE in the VERMILION CITY POKéMON CENTER.", hint = "In a POKéMON CENTER.", have = Have.Flag(FLAG_GOT_VS_SEEKER), areas = listOf("VERMILION CITY")),
                entry("OLD ROD", "The FISHING GURU in a house in VERMILION CITY.", hint = "A port town.", have = Have.Item(ITEM_OLD_ROD), areas = listOf("VERMILION CITY")),
                entry("GOOD ROD", "A fisherman's house in FUCHSIA CITY.", hint = "The city with the SAFARI ZONE.", have = Have.Item(ITEM_GOOD_ROD), areas = listOf("FUCHSIA CITY")),
                entry("SUPER ROD", "The FISHING HOUSE on ROUTE 12.", hint = "On the long pier south of LAVENDER TOWN.", have = Have.Item(ITEM_SUPER_ROD), areas = listOf("ROUTE 12")),
                entry("ITEMFINDER", "PROF. OAK's AIDE upstairs in the ROUTE 11 gate, once you've caught 30 kinds of POKéMON.", hint = "An AIDE east of VERMILION CITY.", have = Have.Flag(FLAG_GOT_ITEMFINDER), areas = listOf("ROUTE 11")),
                entry("EXP. SHARE", "PROF. OAK's AIDE upstairs in the ROUTE 15 gate, once you've caught 50 kinds of POKéMON.", hint = "An AIDE west of FUCHSIA CITY.", have = Have.Flag(FLAG_GOT_EXP_SHARE_FROM_OAKS_AIDE), areas = listOf("ROUTE 15")),
            ),
            section(
                "HMs",
                entry("HM01 CUT", "The captain of the S.S. ANNE in VERMILION CITY - visit him before the ship leaves.", hint = "Someone seasick.", have = Have.Item(ITEM_HM01), areas = listOf("VERMILION CITY", "S.S. ANNE")),
                entry("HM02 FLY", "A house on ROUTE 16, behind a small tree (use CUT).", hint = "West of CELADON CITY.", have = Have.Item(ITEM_HM01 + 1), areas = listOf("ROUTE 16")),
                entry("HM03 SURF", "The SECRET HOUSE deep in the SAFARI ZONE.", hint = "In the SAFARI ZONE.", have = Have.Item(ITEM_HM01 + 2), areas = listOf("KANTO SAFARI ZONE")),
                entry("HM04 STRENGTH", "Find the GOLD TEETH in the SAFARI ZONE's west area and return them to the WARDEN in FUCHSIA CITY.", hint = "Someone in FUCHSIA CITY lost something in the SAFARI ZONE.", have = Have.Item(ITEM_HM01 + 3), areas = listOf("FUCHSIA CITY", "KANTO SAFARI ZONE")),
                entry("HM05 FLASH", "PROF. OAK's AIDE in the building on ROUTE 2, once you've caught 10 kinds of POKéMON.", hint = "An AIDE near DIGLETT's CAVE.", have = Have.Item(ITEM_HM01 + 4), areas = listOf("ROUTE 2")),
                entry("HM06 ROCK SMASH", "The EMBER SPA on KINDLE ROAD (ONE ISLAND).", hint = "A hot spring on the SEVII ISLANDS.", have = Have.Item(ITEM_HM01 + 5), areas = listOf("KINDLE ROAD", "EMBER SPA")),
                entry("HM07 WATERFALL", "Inside ICEFALL CAVE on FOUR ISLAND.", hint = "A frozen cave on the SEVII ISLANDS.", have = Have.Item(ITEM_HM01 + 6), areas = listOf("ICEFALL CAVE", "FOUR ISLAND")),
            ),
            section(
                "SHOPS & SERVICES",
                entry("Evolution stones", "CELADON DEPT. STORE 4F sells FIRE, THUNDER, WATER and LEAF STONES. MOON STONES are found, e.g. in MT. MOON.", hint = "The big store.", areas = listOf("CELADON CITY"), randomized = Randomized.HIDE),
                entry("MOVE DELETER", "A house in FUCHSIA CITY.", areas = listOf("FUCHSIA CITY")),
                entry("MOVE RELEARNER", "A house on TWO ISLAND. Bring a BIG MUSHROOM or two TINY MUSHROOMS.", areas = listOf("TWO ISLAND")),
            ),
            section(
                "GIFT POKéMON",
                Randomized.HIDE,
                entry("EEVEE", "The rooftop room of the CELADON CONDOMINIUMS - reached by the back entrance.", hint = "CELADON CITY.", have = Have.Caught(133), areas = listOf("CELADON CITY")),
                entry("LAPRAS", "An employee on SILPH CO. 7F, after you reach him during the ROCKET takeover.", hint = "SAFFRON CITY.", have = Have.Caught(131), areas = listOf("SILPH CO")),
                entry("HITMONLEE / HITMONCHAN", "Beat the FIGHTING DOJO in SAFFRON CITY and pick one.", hint = "Next to a gym.", have = Have.Caught(106, 107), areas = listOf("SAFFRON CITY")),
                entry("Fossils", "Pick the HELIX or DOME FOSSIL in MT. MOON; the OLD AMBER is in the back of PEWTER's museum. The CINNABAR ISLAND lab revives them.", hint = "A cave, a museum, and a lab.", have = Have.Caught(138, 140, 142), areas = listOf("MT MOON", "PEWTER CITY", "CINNABAR ISLAND")),
                entry(
                    "GAME CORNER prizes",
                    "The prize corner next to the CELADON CITY GAME CORNER trades coins for POKéMON: ${prizeMons(leafGreen)}. " +
                        "A man in the CELADON CITY restaurant gives you the COIN CASE.",
                    hint = "Win coins in CELADON CITY.", areas = listOf("CELADON CITY"),
                ),
            ),
            section(
                "LEGENDARY POKéMON",
                Randomized.HIDE,
                entry("ARTICUNO", "The bottom of the SEAFOAM ISLANDS. Push boulders with STRENGTH to slow the currents.", hint = "Between FUCHSIA CITY and CINNABAR ISLAND.", have = Have.Caught(144), areas = listOf("SEAFOAM ISLANDS")),
                entry("ZAPDOS", "The POWER PLANT - SURF from ROUTE 10.", hint = "An abandoned building.", have = Have.Caught(145), areas = listOf("POWER PLANT", "ROUTE 10")),
                entry("MOLTRES", "The top of MT. EMBER on ONE ISLAND.", hint = "A volcano on the SEVII ISLANDS.", have = Have.Caught(146), areas = listOf("MT EMBER")),
                entry("MEWTWO", "The bottom of CERULEAN CAVE, which opens after the SEVII ISLANDS story is finished.", hint = "Post-game, near CERULEAN CITY.", have = Have.Caught(150), areas = listOf("CERULEAN CAVE")),
                entry("SNORLAX", "Two block ROUTE 12 and ROUTE 16. Wake them with the POKé FLUTE.", hint = "Sleeping in the way.", have = Have.Caught(143), areas = listOf("ROUTE 12", "ROUTE 16")),
            ),
        ),
        page(
            "STUCK?",
            section(
                "EARLY GAME",
                entry(
                    "An old man blocks the road north of VIRIDIAN",
                    "Pick up OAK's PARCEL at the VIRIDIAN CITY POKé MART and deliver it to PROF. OAK in PALLET TOWN. He moves after that.",
                    hint = "PROF. OAK is waiting for something from VIRIDIAN CITY.", areas = listOf("VIRIDIAN CITY", "PALLET TOWN")
                ),
                entry("VIRIDIAN CITY's gym is locked", "It's the last gym - it opens once you have the other seven badges.", hint = "Come back much later.", areas = listOf("VIRIDIAN CITY")),
                entry("Where's the S.S. TICKET?", "BILL gives it to you after you help him in his cottage at the end of ROUTE 25, north of CERULEAN CITY.", hint = "Cross the bridge north of CERULEAN CITY.", areas = listOf("CERULEAN CITY", "ROUTE 25", "VERMILION CITY")),
                entry("ROCK TUNNEL is pitch black", "FLASH lights it up (HM05, see WHERE IS), but you can walk through without it.", hint = "An HM helps, but it's optional.", areas = listOf("ROCK TUNNEL", "ROUTE 10")),
            ),
            section(
                "MID GAME",
                entry(
                    "How do I get into SAFFRON CITY?",
                    "The gate guards are thirsty. Get TEA from the old woman on the ground floor of the CELADON CONDOMINIUMS and any guard lets you through.",
                    hint = "They'd like a drink - ask around CELADON CITY.", areas = listOf("SAFFRON CITY", "CELADON CITY", "ROUTE 5", "ROUTE 6", "ROUTE 7", "ROUTE 8")
                ),
                entry(
                    "I can't identify the ghosts in POKéMON TOWER",
                    "You need the SILPH SCOPE. It's on ROCKET HIDEOUT B4F, under the CELADON GAME CORNER - a switch behind a poster opens the entrance.",
                    hint = "TEAM ROCKET has it, somewhere in CELADON CITY.", areas = listOf("POKEMON TOWER", "LAVENDER TOWN", "CELADON CITY", "ROCKET HIDEOUT")
                ),
                entry("The hideout's elevator won't move", "It needs the LIFT KEY, which a ROCKET on B4F drops. Take the stairs down to reach him.", hint = "Someone downstairs has a key.", areas = listOf("ROCKET HIDEOUT")),
                entry(
                    "A SNORLAX blocks the road",
                    "Rescue MR. FUJI from the top of POKéMON TOWER; he gives you the POKé FLUTE at his house in LAVENDER TOWN.",
                    hint = "Something in LAVENDER TOWN wakes it.", areas = listOf("ROUTE 12", "ROUTE 16", "LAVENDER TOWN"),
                    randomized = Randomized.HIDE,
                ),
                entry("How do I get to FUCHSIA CITY?", "Ride CYCLING ROAD south from CELADON CITY (BICYCLE needed), or go down ROUTES 12-15 from LAVENDER TOWN after waking the SNORLAX.", hint = "There are two ways - one needs wheels.", areas = listOf("CELADON CITY", "LAVENDER TOWN", "ROUTE 12", "ROUTE 16")),
                entry("SILPH CO.'s doors are locked", "Find the CARD KEY on 5F; it opens every locked door in the building.", hint = "Somewhere in the middle floors.", areas = listOf("SILPH CO")),
            ),
            section(
                "LATE GAME",
                entry("CINNABAR ISLAND's gym is locked", "The SECRET KEY is in the basement of the POKéMON MANSION on the same island.", hint = "Search the burned-out building.", areas = listOf("CINNABAR ISLAND", "POKEMON MANSION")),
                entry("VICTORY ROAD's boulders", "Teach STRENGTH and push boulders onto the floor switches to open the way.", hint = "An HM moves them.", areas = listOf("KANTO VICTORY ROAD")),
            ),
            section(
                "POST-GAME",
                entry("How do I get the National Dex?", "Beat the POKéMON LEAGUE, catch 60 kinds of KANTO POKéMON, then talk to PROF. OAK.", hint = "Finish the League and keep catching.", areas = listOf("PALLET TOWN")),
                entry("How do I reach the SEVII ISLANDS?", "After beating BLAINE, BILL is waiting at CINNABAR ISLAND and takes you to ONE ISLAND.", hint = "After the seventh badge, someone on CINNABAR ISLAND.", areas = listOf("CINNABAR ISLAND")),
                entry("CERULEAN CAVE is guarded", "Finish the SEVII ISLANDS story: bring the RUBY (MT. EMBER) and SAPPHIRE to CELIO on ONE ISLAND.", hint = "Two gems for CELIO.", areas = listOf("CERULEAN CITY", "ONE ISLAND")),
            ),
        ),
    ),
)
