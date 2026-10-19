package com.pokedaisy.app.companion.data

/**
 * SoulGold's guide (v1.1.4, v1.2 and v1.2b share every id and flag). Johto's badges are flags
 * 0x993.. (CLAIR's 0x99A is handed over later, in the DRAGON'S DEN). CHUCK, JASMINE and PRYCE can be
 * fought in any order and each brings a stronger team the more of the other two are beaten (their
 * gym scripts check 0x4F4 / 0x4F5 / 0x4F6). The League counts up VAR 0x4082 (WILL sets 2 .. KAREN 5,
 * LANCE 6); the Hall of Fame sets 0xA19, after which the Elite Four use rematch teams. Kanto's gyms
 * set FLAG_DEFEATED_* 0x26D..0x274; the order below follows their levels. Mt. Silver's GOLD /
 * CRYSTAL (by the player's gender) have the same team; 0x4F8 once beaten. HARD mode is a SaveBlock2
 * option bit SaveProgress can't see, so the teams with a HARD record show both.
 * Hand-written reference pages (TIPS, LEGENDARIES, GROTTOS, MEGA EVOLUTIONS, ACHIEVEMENTS, GIFTS & TRADES)
 * are adapted from official SoulGold game documentation.
 */
private const val FLAG_BADGE01_GET = 0x993 // .. 0x99A
private const val FLAG_SYS_GAME_CLEAR = 0x990
private const val FLAG_DEFEATED_CIANWOOD_GYM = 0x4F4
private const val FLAG_DEFEATED_OLIVINE_GYM = 0x4F5
private const val FLAG_DEFEATED_MAHOGANY_GYM = 0x4F6
private const val FLAG_DEFEATED_BLACKTHORN_GYM = 0x4F7
private const val FLAG_DEFEATED_PEWTER_GYM = 0x26D // Brock, Misty, Surge, Erika, Sabrina, Janine, Blaine, Blue
private const val FLAG_DEFEATED_MT_SILVER = 0x4F8
private const val FLAG_HALL_OF_FAME = 0xA19
private const val VAR_LEAGUE_STATE = 0x4082
private const val HARD = 1 shl TRAINER_TABLE_SHIFT // altTrainers[0]: gTrainers[DIFFICULTY_HARD]

private fun nh(id: Int) = listOf("NORMAL" to id, "HARD" to (id or HARD))

private fun middleGym(first: Int, second: Int, third: Int, otherA: Int, otherB: Int): (SaveProgress) -> Int = { p ->
    when ((if (p.flag(otherA)) 1 else 0) + (if (p.flag(otherB)) 1 else 0)) {
        0 -> first
        1 -> second
        else -> third
    }
}

private val CHUCK = middleGym(510, 442, 538, FLAG_DEFEATED_OLIVINE_GYM, FLAG_DEFEATED_MAHOGANY_GYM)
private val JASMINE = middleGym(513, 651, 180, FLAG_DEFEATED_CIANWOOD_GYM, FLAG_DEFEATED_MAHOGANY_GYM)
private val PRYCE = middleGym(546, 578, 707, FLAG_DEFEATED_CIANWOOD_GYM, FLAG_DEFEATED_OLIVINE_GYM)

private fun league(state: Int): (SaveProgress) -> Boolean =
    { p -> p.flag(FLAG_HALL_OF_FAME) || p.flag(FLAG_SYS_GAME_CLEAR) || p.variable(VAR_LEAGUE_STATE) >= state }

private fun johto(n: Int, title: String, where: String, id: Int) = Boss(title, where, FLAG_BADGE01_GET + n, variants = nh(id)) { id }
private fun middle(n: Int, title: String, where: String, pick: (SaveProgress) -> Int) =
    Boss(title, where, FLAG_BADGE01_GET + n, variantsFor = { p -> nh(pick(p)) }, trainer = pick)
private fun elite(state: Int, title: String, id: Int) = Boss(title, "POKéMON LEAGUE", 0, variants = nh(id), doneIf = league(state)) { id }
private fun kanto(n: Int, title: String, where: String, id: Int) = Boss(title, where, FLAG_DEFEATED_PEWTER_GYM + n) { id }

private val BOSSES_SOULGOLD = listOf(
    johto(0, "LEADER FALKNER", "VIOLET CITY GYM", 19),
    johto(1, "LEADER BUGSY", "AZALEA TOWN GYM", 596),
    johto(2, "LEADER WHITNEY", "GOLDENROD CITY GYM", 604),
    johto(3, "LEADER MORTY", "ECRUTEAK CITY GYM", 608),
    middle(4, "LEADER CHUCK", "CIANWOOD CITY GYM", CHUCK),
    middle(5, "LEADER JASMINE", "OLIVINE CITY GYM", JASMINE),
    middle(6, "LEADER PRYCE", "MAHOGANY TOWN GYM", PRYCE),
    Boss(
        "LEADER CLAIR", "BLACKTHORN CITY GYM", FLAG_DEFEATED_BLACKTHORN_GYM, variants = nh(541),
        doneIf = { it.flag(FLAG_DEFEATED_BLACKTHORN_GYM) || it.flag(FLAG_BADGE01_GET + 7) || it.variable(0x405F) >= 2 },
    ) { 541 },
    elite(2, "ELITE FOUR WILL", 736),
    elite(3, "ELITE FOUR KOGA", 383),
    elite(4, "ELITE FOUR BRUNO", 379),
    elite(5, "ELITE FOUR KAREN", 381),
    Boss(
        "CHAMPION LANCE", "POKéMON LEAGUE", FLAG_HALL_OF_FAME, variants = nh(249),
        doneIf = { p -> p.flag(FLAG_HALL_OF_FAME) || p.flag(FLAG_SYS_GAME_CLEAR) },
    ) { 249 },
    kanto(2, "LEADER LT. SURGE", "VERMILION CITY GYM", 302),
    kanto(3, "LEADER ERIKA", "CELADON CITY GYM", 303),
    kanto(1, "LEADER MISTY", "CERULEAN CITY GYM", 544),
    kanto(5, "LEADER JANINE", "FUCHSIA CITY GYM", 305),
    kanto(4, "LEADER SABRINA", "SAFFRON CITY GYM", 304),
    kanto(0, "LEADER BROCK", "PEWTER CITY GYM", 543),
    kanto(6, "LEADER BLAINE", "SEAFOAM ISLANDS", 306),
    kanto(7, "LEADER BLUE", "VIRIDIAN CITY GYM", 595),
    Boss("GOLD / CRYSTAL", "MT. SILVER", FLAG_DEFEATED_MT_SILVER, variants = nh(882)) { 882 },
)

internal val GUIDE_SOULGOLD = GameGuide(
    verified = true,
    bosses = BOSSES_SOULGOLD,
    pages = listOf(
        page(
            "TIPS",
            section(
                "GAMEPLAY & PROGRESSION",
                entry("Day & Night Schedule", "Morning: 06:00-10:00, Day: 10:00-19:00, Evening: 19:00-20:00, Night: 20:00-06:00 in-game time.", "Times of day cycle automatically by RTC."),
                entry("Level Caps", "Hard caps: 12 (Badge 1), 19 (Badge 2), 26 (Badge 3), 34 (Badge 4), 42 (Badge 5), 45 (Badge 6), 48 (Badge 7), 55 (Rocket Takeover), 58 (Badge 8), 62 (Pre-League), 70 (Champion).", "Caps prevent over-leveling during story."),
                entry("DexNav Access", "Obtained right after receiving your first set of Poké Balls.", "Allows chaining, finding hidden abilities, and egg moves."),
                entry("Exp. Share & Candy Jar", "Exp. Share is given right before arriving in Violet City. Elm's aide hands you the Candy Jar with the Mystery Egg.", "The Candy Jar accumulates extra battle Exp. and produces Exp Candy on use."),
                entry("HMs in Overworld", "You only need to own the HM and possess the corresponding Gym Badge to use field moves. Flying is also accessible directly via the Pokégear Map.", "No need to teach HMs to your battle team."),
            ),
            section(
                "MECHANICS & SHINIES",
                entry("Physical / Special Split", "Each move is categorized as Physical or Special independently based on its individual properties, as in modern generations.", "Not decided by move type."),
                entry("Shiny Hunting Odds", "Base shiny rate is 1/256. There are NO shiny locks (even gift Pokémon and NPC trades can be shiny).", "Shiny eggs display with distinct blue dots!"),
                entry("Mega Evolution", "Press START when selecting a move while holding the matching Mega Stone / Bondstone and possessing the Mega Ring (obtained after Rocket Takeover).", "Requires Mega Ring + Mega Stone."),
            ),
        ),
        page(
            "LEGENDARIES",
            section(
                "JOHTO BIRDS & BEASTS",
                entry("Raikou, Entei, Suicune", "Roam throughout Johto after the Burned Tower event in Ecruteak City.", "Track them on the Pokégear map."),
                entry("Ho-Oh", "Choose the Rainbow Wing after Rocket Takeover, then climb Tin Tower. (If Silver Wing was chosen, defeat the Director in postgame).", "Found at Bell Tower summit."),
                entry("Lugia", "Choose the Silver Wing after Rocket Takeover, then explore Whirl Islands. (If Rainbow Wing was chosen, defeat the Director in postgame).", "Found deep within Whirl Islands."),
                entry("Celebi", "Solve all 8 Ruins of Alph puzzles (4 slider, 4 wall) to find the GS Ball in B1F. Take it to Kurt in Azalea, then visit the Ilex Forest shrine.", "Inspect the shrine in Ilex Forest."),
            ),
            section(
                "KANTO & EXTRA LEGENDS",
                entry("Articuno", "Found in Snowtop Mountain. Requires HM Rock Climb.", "High mountain peaks."),
                entry("Zapdos", "Found at the top of Olivine Lighthouse after earning all 8 Johto badges.", "Lighthouse roof."),
                entry("Moltres", "Found inside Victory Road.", "Cave interior."),
                entry("Mewtwo", "Located in Nameless Dungeon at Kitakami Border after defeating the Elite Four. Requires Rock Climb.", "Postgame dungeon."),
                entry("Mew", "After obtaining 8 badges, speak to Blaine at Olivine Port. Defeat him to sail to Faraway Island.", "Faraway Island."),
                entry("Jirachi", "Complete the Rival storyline at the summit of Mt. Silver.", "Mt. Silver summit reward."),
            ),
        ),
        page(
            "GROTTOS",
            section(
                "HIDDEN GROTTOS",
                entry("How Grottos Work", "Hidden Grottos repopulate once per day or instantly when using a Beckoning Bell. Pokémon have 2 guaranteed 31 IVs and Hidden Abilities.", "Look for narrow paths behind trees."),
                entry("Route 32 (Lv. 10)", "Applin, Paldean Wooper, Mareep, Misdreavus. Rare Item: Everstone.", "Early game route."),
                entry("Route 33 (Lv. 14)", "Pachirisu, Mienfoo, Darumaka, Cottonee. Rare Item: Sun Stone.", "Near Union Cave entrance."),
                entry("Ilex Forest (Lv. 15)", "Oddish, Ferroseed, Roselia, Exeggcute. Rare Item: Leaf Stone.", "Inside deep forest."),
                entry("Route 35 (Lv. 18)", "Rookidee, Pikachu, Poltchageist, Eevee. Rare Item: Unremarkable Teacup.", "North of Goldenrod."),
                entry("Goldenrod Shore (Lv. 20)", "Minccino, Shroomish, Own Tempo Rockruff, Heracross. Rare Item: Light Clay.", "Coastal path."),
                entry("Route 38 (Lv. 30)", "Munchlax, Meowstic (M/F), Espathra. Rare Item: Stardust.", "Farm route."),
                entry("Vajra Desert (Lv. 30)", "Baltoy, Vullaby, Larvitar, Gible. Rare Item: Smooth Rock.", "Desert sands."),
                entry("Lake of Rage (Lv. 35)", "Cyclizar, Oricorio-Sensu, Falinks, Drampa. Rare Item: Eviolite.", "North lake perimeter."),
                entry("Route 44 (Lv. 40)", "Arctibax, Bergmite, Lopunny, Hisuian Zorua. Rare Item: Ice Stone.", "Path to Ice Path."),
                entry("Route 47 (Lv. 40)", "Chansey, Larvesta, Ditto, Zorua. Rare Item: Lucky Egg.", "Cliffside route."),
            ),
        ),
        page(
            "MEGA EVOLUTIONS",
            section(
                "STARTERS & BONDSTONE",
                entry("Bondstone", "Meganium, Typhlosion, Feraligatr, Raichu (Mega Y), Blaziken, Primarina, Chesnaught, Delphox, Greninja, Meowscarada, Ogerpon (Teal).", "Given by Prof. Elm during story."),
                entry("Bugtite", "Butterfree, Beedrill, Scizor, Pinsir, Heracross, Golisopod.", "Bug Mega Evolutions."),
                entry("Dragotite", "Charizard (Mega X), Dragonite, Salamence, Latias, Latios, Garchomp (Mega Z), Tatsugiri, Baxcalibur.", "Dragon Mega Evolutions."),
                entry("Firetite", "Charizard (Mega Y), Camerupt, Heatran, Emboar, Pyroar, Cinderace, Centiskorch.", "Fire Mega Evolutions."),
                entry("Watertite", "Blastoise, Slowbro, Starmie, Gyarados, Swampert, Sharpedo, Inteleon.", "Water Mega Evolutions."),
                entry("Grasstite", "Venusaur, Sceptile, Rillaboom, Scovillain.", "Grass Mega Evolutions."),
                entry("Steeltite", "Steelix, Skarmory, Aggron, Metagross, Lucario (Mega Z), Magearna.", "Steel Mega Evolutions."),
            ),
        ),
        page(
            "ACHIEVEMENTS",
            section(
                "TROPHY MILESTONES",
                entry("Trophy System & Location", "Over 100 trophies can be earned. Talk to the gentleman in the house on Route 40 to check milestone progress and claim rewards. All gift Pokémon can be shiny (visible in preview)!", "House on Route 40."),
                entry("15 Trophies", "Shiny Genome (consumable item that turns any Pokémon you own shiny).", "Shiny Genome consumable."),
                entry("30 Achievements", "Ash-Greninja (possesses the Battle Bond ability, transforming into Ash-Greninja upon knocking out an opponent).", "Battle Bond Greninja."),
                entry("45 Achievements", "Poipole (Ultra Beast; evolves into Naganadel upon learning Dragon Pulse).", "Poipole Ultra Beast."),
                entry("60 Achievements", "Eternal Floette (special high-stat Floette capable of Mega Evolution).", "Eternal Flower Floette."),
                entry("75 Achievements", "Zarude (Mythical Pokémon from the jungle).", "Mythical Zarude."),
                entry("100 Achievements", "Original Color Magearna (special Poké Ball-patterned mythical form).", "Original Color Magearna."),
            ),
        ),
        page(
            "GIFTS & TRADES",
            section(
                "STORY & NPC GIFTS",
                entry("Cherrygrove City", "Choice between Alolan Rattata and Galarian Zigzagoon after obtaining your first set of Poké Balls.", "Right after getting Poké Balls."),
                entry("Ruins of Alph (Fossils)", "Smash rocks in the Ruins of Alph for fossils, then bring them to the research lab nearby to revive them.", "Rock Smash in ruins."),
                entry("Odd Egg (Rival)", "Hatches one of: Bagon, Gible, Feebas, Riolu, Jangmo-o, Hisuian Zorua, or Goomy.", "Hatched from the Odd Egg."),
                entry("Goldenrod City (Bill)", "Bill offers a choice between Let's Go Eevee and Let's Go Pikachu after speaking to him in the Ecruteak Pokémon Center.", "Visit Bill's house after Ecruteak."),
                entry("Cianwood City", "Gimmighoul inside one of the coastal houses.", "Inside a resident's house."),
                entry("Mt. Mortar", "Tyrogue gifted by the Karate King (Black Belt Kiyo) in the depths of Mt. Mortar.", "Defeat the Karate King."),
                entry("Dragon's Den (Dratini)", "Pass the elder's quiz in the shrine and speak to Clair. Re-enter the shrine to receive Dratini. Correct answers grant unique move Spacial Rend!", "Dragon's Den shrine reward."),
                entry("Kitakami Border", "Steven Stone gifts Beldum.", "Gift from Steven."),
                entry("Goldenrod Casino (Starters)", "Gachapon machine behind the prize counter offers starters from all generations (minimum 3,000 coins; higher bets reduce duplicate odds).", "Gachapon machine."),
            ),
            section(
                "IN-GAME TRADES",
                entry("Violet City", "Receive Pawmi (offers Cottonee).", "Trade Cottonee."),
                entry("Azalea Town", "Receive Galarian Slowpoke (offers regular Slowpoke).", "Trade Slowpoke."),
                entry("Goldenrod Dept. Store", "Receive Honedge (offers Clefairy).", "Trade Clefairy."),
                entry("Route 39-49 Gatehouse", "Receive Rotom (offers regular Zorua). Form-changing appliances in Goldenrod Apartments basement.", "Trade regular Zorua."),
                entry("Olivine City", "Receive Hisuian Voltorb (offers Mareanie).", "Trade Mareanie."),
                entry("Blackthorn City", "Receive Gabite (offers Dragonair).", "Trade Dragonair."),
                entry("Rinto Village", "Receive Meltan (offers Tinkaton).", "Trade Tinkaton."),
            ),
        ),
    ),
)
