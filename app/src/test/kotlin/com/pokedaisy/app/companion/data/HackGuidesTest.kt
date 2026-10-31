package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The GUIDEs of Odyssey, Gaia, Amethyst and Celia's hack: each game's tables in
 * the user's ROM (skipped when it isn't on this machine), every boss team the
 * NEXT BOSS page lists, a wild table, NEXT BOSS from the real save and the
 * generated WHERE IS.
 */
class HackGuidesTest {
    private class Game(val rom: String, val kind: GameKind, val cfg: NativeConfig, val fixture: String) {
        val t get() = cfg.guideTables!!
    }

    private val odyssey = Game("Pokemon Odyssey (v4.1.1).gba", GameKind.ODYSSEY, NATIVE_ODYSSEY, "odyssey")
    private val gaia = Game("Pokemon Gaia (v3.2).gba", GameKind.GAIA, NATIVE_GAIA_V3_2, "gaia")
    private val amethyst = Game("Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST, NATIVE_AMETHYST, "amethyst")
    private val amethyst141 = Game("Pokemon Amethyst (v1.4.1).gba", GameKind.AMETHYST, NATIVE_AMETHYST_V1_4_1, "amethyst_v141")
    private val celia = Game("Pokemon Celia's Stupid Romhack (v1.1.4).gba", GameKind.CELIA, NATIVE_CELIA, "celia")
    private val uvGame = Game("Pokemon Ultra Violet (v1.22).gba", GameKind.FIRERED, NATIVE_ULTRA_VIOLET, "ultra_violet")
    private val swordShield = Game("Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba", GameKind.SWORD_SHIELD, NATIVE_SWORD_SHIELD, "sword_shield_up")
    private val rocket = Game("Pokemon FireRed Rocket Edition.gba", GameKind.FIRERED, NATIVE_ROCKET_EDITION, "rocket_edition")
    private val ashGray = Game("Pokemon Ash Gray (v4.5.3).gba", GameKind.FIRERED, NATIVE_ASH_GRAY, "ash_gray")
    private val ashGrayFork = Game("Pokemon Ash Gray Fork Bug Fix (v1.0).gba", GameKind.FIRERED, NATIVE_ASH_GRAY, "ash_gray_fork")
    private val ghostGrey = Game("Pokemon Ghost Grey (v1.0.4).gba", GameKind.FIRERED, NATIVE_GHOST_GREY, "ghost_grey")
    private val ghostGreyV101 = Game("Pokemon Ghost Grey (v1.0.1).gba", GameKind.FIRERED, NATIVE_GHOST_GREY_V1_0_1, "ghost_grey_v101")
    private val gsChronicles = Game("Pokemon GS Chronicles (v2.7.6).gba", GameKind.GS_CHRONICLES, NATIVE_GS_CHRONICLES, "gs_chronicles")
    private val all = listOf(odyssey, gaia, amethyst, amethyst141, celia, uvGame, swordShield, gsChronicles, rocket, ashGray, ashGrayFork, ghostGrey, ghostGreyV101)

    private fun load(g: Game): RomFileReader? = RomFileReader.load(RETAIL_ROM_DIR + g.rom)?.also {
        select(g)
        PokedexSource.reader = it
    }

    private fun select(g: Game) {
        activeGame = g.kind
        amethystV141 = g.cfg === NATIVE_AMETHYST_V1_4_1
        fireRedHack = fireRedHackFor(g.cfg)
        emeraldHack = emeraldHackFor(g.cfg)
    }

    private val noProgress = SaveProgress(ByteArray(0x120), ByteArray(0x200))

    @Test fun `tables are where the configs say`() = all.forEach { g ->
        val rom = load(g) ?: return@forEach
        assertTrue(g.kind.name, guideTablesMatchRom(rom, g.t))
    }

    /** Every listed team: 1-6 real Pokémon with levels and moves. */
    @Test fun `boss teams`() = all.forEach { g ->
        load(g) ?: return@forEach
        val guide = gameGuide(g.t.guide)!!
        for (boss in guide.bosses) for ((label, id) in boss.teams(noProgress)) {
            val team = GuideRomSource.party(g.t, id)
            val what = "${g.kind} ${boss.title} $label ($id)"
            assertTrue(what, team != null && team.size in 1..6)
            assertTrue(what, team!!.all { it.species > 0 && it.level in 1..100 && it.moves.isNotEmpty() })
        }
    }

    /** Odyssey's KARIN: 4 Pokémon on NORMAL (Lv16-17), 5 on HARD (Lv18). */
    @Test fun `odyssey normal and hard`() {
        assumeTrue(load(odyssey) != null)
        assertEquals(listOf(16, 16, 17, 17), GuideRomSource.party(odyssey.t, 33)!!.map { it.level })
        assertEquals(listOf(18, 18, 18, 18, 18), GuideRomSource.party(odyssey.t, 34)!!.map { it.level })
    }

    /** Amethyst's TERRENCE: one more Pokémon on HARD, a different second one on DIVERGENT. */
    @Test fun `amethyst tables`() {
        assumeTrue(load(amethyst) != null)
        val terrence = GUIDE_AMETHYST.bosses.first().teams(noProgress).map { GuideRomSource.party(amethyst.t, it.second)!! }
        assertEquals(listOf(1102 to 11, 780 to 13), terrence[0].map { it.species to it.level })
        assertEquals(3, terrence[1].size)
        assertEquals(638, terrence[2][1].species)
        assertEquals(3, terrence[3].size)
    }

    /** v1.4.1 moved the four tables; TERRENCE's teams are the same. */
    @Test fun `amethyst v1_4_1 tables`() {
        assumeTrue(load(amethyst141) != null)
        val terrence = GUIDE_AMETHYST.bosses.first().teams(noProgress).map { GuideRomSource.party(amethyst141.t, it.second)!! }
        assertEquals(listOf(1102 to 11, 780 to 13), terrence[0].map { it.species to it.level })
        assertEquals(3, terrence[1].size)
        assertEquals(638, terrence[2][1].species)
        assertEquals(3, terrence[3].size)
        try {
            assertEquals("Tepig", speciesName(551))
            assertEquals("Growlithe", speciesName(1234)) // a Hisuian form, where v1.3.0 had Gigantamax VENUSAUR
            assertEquals("Venusaur", speciesName(1260))
        } finally {
            amethystV141 = false
        }
    }

    /** RAINE's and CHANCE's teams follow the badges held. */
    @Test fun `amethyst middle gyms by badges`() {
        fun save(badges: Int) = SaveProgress(ByteArray(0x120).also { b -> repeat(badges) { b[(0x820 + it) / 8] = (b[(0x820 + it) / 8].toInt() or (1 shl ((0x820 + it) % 8))).toByte() } }, ByteArray(0x200))
        val raine = GUIDE_AMETHYST.bosses.single { it.title == "LEADER RAINE" }
        val chance = GUIDE_AMETHYST.bosses.single { it.title == "LEADER CHANCE" }
        assertEquals(listOf(105, 256, 257), listOf(2, 3, 4).map { raine.trainer(save(it)) })
        assertEquals(listOf(154, 154, 258), listOf(2, 3, 4).map { chance.trainer(save(it)) })
        assertEquals(257 or (1 shl TRAINER_TABLE_SHIFT), raine.teams(save(4))[1].second)
    }

    /** A wild table that HERE would show: the first map with grass encounters. */
    @Test fun `wild encounters`() = all.forEach { g ->
        load(g) ?: return@forEach
        val e = (0 until 60).asSequence().flatMap { n -> (0 until 4).asSequence().map { it to n } }
            .mapNotNull { (grp, n) -> GuideRomSource.encounters(g.t, grp, n)?.takeIf { it.grass.isNotEmpty() } }.firstOrNull()
        assertNotNull(g.kind.name, e)
        assertEquals(g.kind.name, 100, e!!.grass.sumOf { it.percent })
    }

    /** Retail FireRed's finished save, booted in these: every badge is won, no boss is next. */
    private val finishedSaves = setOf(rocket, ashGray, ashGrayFork)

    @Test fun `next boss from the real saves`() = (all - finishedSaves).forEach { g ->
        val progress = readSaveProgress(FixtureMemoryReader.load(g.fixture), g.cfg, g.t)
        assertNotNull(g.kind.name, progress)
        val next = gameGuide(guideId(g.kind, g.t))!!.bosses.firstOrNull { !it.isDone(progress!!) }
        assertNotNull(g.kind.name, next)
    }

    /** Glazed: no area data yet, so its own checks - the tables, Tunod's leaders, the save's map. */
    private val glazed = Game("Pokemon Glazed (v9.2.0).gba", GameKind.GLAZED, NATIVE_GLAZED, "glazed")

    @Test fun `glazed tables, leaders and HERE`() {
        val rom = load(glazed) ?: return
        assertTrue(guideTablesMatchRom(rom, glazed.t))
        for (boss in GUIDE_GLAZED.bosses) {
            val team = GuideRomSource.party(glazed.t, boss.trainer(noProgress))!!
            assertTrue(boss.title, team.size in 1..6 && team.all { it.species in 1..411 && it.level in 1..100 })
        }
        // Forest Pass (0.17), where the save stands: SENTRET is in its grass.
        val here = GuideRomSource.encounters(glazed.t, 0, 17)!!
        assertEquals(100, here.grass.sumOf { it.percent })
        assertTrue(here.grass.any { speciesNamesGlazed[it.species] == "SENTRET" })
        val progress = readSaveProgress(FixtureMemoryReader.load(glazed.fixture), glazed.cfg, glazed.t)!!
        assertEquals("LEADER SPARKY", GUIDE_GLAZED.bosses.first { !it.isDone(progress) }.title)
    }

    /** Super Mariomon: the eight STARS' leaders in every release, WORLD 1-1's grass (a GOOMBA Lv2 met there
     * headlessly, walking it), and the save's next boss. */
    private val mariomon = listOf(
        Game("Super Mariomon (v1.5.2 Anniversary).gba", GameKind.MARIOMON, NATIVE_MARIOMON_V1_5_2, "mariomon_v1_5_2"),
        Game("Super Mariomon (v1.5.1 Anniversary).gba", GameKind.MARIOMON, NATIVE_MARIOMON_V1_5_1, "mariomon_v1_5_1"),
        Game("Super Mariomon (v1.3).gba", GameKind.MARIOMON, NATIVE_MARIOMON_V1_3, "mariomon_v1_3"),
        Game("Super Mariomon (v1.2.2).gba", GameKind.MARIOMON, NATIVE_MARIOMON_V1_2_2, "mariomon_v1_2_2"),
        Game("Super Mariomon (v1.2.1).gba", GameKind.MARIOMON, NATIVE_MARIOMON_V1_2_1, "mariomon_v1_2_1"),
    )

    @Test fun `mariomon stars, WORLD 1-1 and the next star`() = mariomon.forEach { g ->
        val progress = readSaveProgress(FixtureMemoryReader.load(g.fixture), g.cfg, g.t)!!
        assertEquals("STAR 1 PEACH", GUIDE_MARIOMON.bosses.first { !it.isDone(progress) }.title)
        val rom = load(g) ?: return@forEach
        mariomonV13 = g.cfg.guideTables !== GUIDE_TABLES_MARIOMON_V1_5_1 && g.cfg.guideTables !== GUIDE_TABLES_MARIOMON_V1_5_2
        assertTrue(g.rom, guideTablesMatchRom(rom, g.t))
        val levels = GUIDE_MARIOMON.bosses.map { boss ->
            val team = GuideRomSource.party(g.t, boss.trainer(noProgress))!!
            assertTrue(boss.title, team.size in 1..6 && team.all { it.species in 906..1100 && it.level in 1..100 && it.moves.isNotEmpty() })
            team.maxOf { it.level }
        }
        assertEquals(g.rom, listOf(11, 17, 24, 24, 40, 45, 47, 56), levels) // its level caps: the leaders' strongest
        // PEACH: BIDDYBUD / CRAZEE D. Lv10, BABY YOSHI Lv11; no moves of their own, so the level-up ones.
        val peach = GuideRomSource.party(g.t, 102)!!
        assertEquals(listOf("BIDDYBUD", "CRAZEE D.", "BABY YOSHI"), peach.map { activeSpeciesNames[it.species] })
        val here = GuideRomSource.encounters(g.t, 2, 4)!! // WORLD 1-1
        assertEquals(100, here.grass.sumOf { it.percent })
        assertEquals(setOf("GOOMBA", "KOOPA T.", "TWIRLIP", "ANTROOPER"), here.grass.map { activeSpeciesNames[it.species] }.toSet())
        assertEquals(2 to 4, here.grass.minOf { it.minLevel } to here.grass.maxOf { it.maxLevel })
    }

    private val orangeIslands = Game("Pokemon Orange Islands.gba", GameKind.ORANGE_ISLANDS, NATIVE_ORANGE_ISLANDS, "orange_islands")
    private val quetzalEs = Game("Pokemon Quetzal (Alpha 9.0) (Spanish).gba", GameKind.QUETZAL, NATIVE_QUETZAL_ES, "quetzal_es")
    private val unboundFr = Game("Pokemon Unbound (v2.1.1.1) (French).gba", GameKind.UNBOUND, NATIVE_UNBOUND_FR, "unbound_fr")

    /** Orange Islands: its four gym leaders and DRAKE (trainers 1-5), the next one on the save, HERE and WHERE IS. */
    @Test fun `orange islands`() {
        val rom = load(orangeIslands) ?: return
        assertTrue(guideTablesMatchRom(rom, orangeIslands.t))
        for (boss in GUIDE_ORANGE_ISLANDS.bosses) {
            val team = GuideRomSource.party(orangeIslands.t, boss.trainer(noProgress))!!
            assertTrue(boss.title, team.size in 1..6 && team.all { it.species in 1..411 && it.level in 1..100 && it.moves.isNotEmpty() })
        }
        assertEquals(listOf(20, 22), GuideRomSource.party(orangeIslands.t, 1)!!.map { it.level }) // CISSY
        val p = readSaveProgress(FixtureMemoryReader.load("orange_islands"), NATIVE_ORANGE_ISLANDS, orangeIslands.t)!!
        assertEquals("LEADER CISSY", GUIDE_ORANGE_ISLANDS.bosses.first { !it.isDone(p) }.title)
        // Its repointed wild headers: some map has grass.
        assertTrue(GuideRomSource.wildMapKeys(orangeIslands.t).isNotEmpty())
        val whereIs = generatedWhereIs(GuideId.ORANGE_ISLANDS, { "AREA $it" }) { false }
        assertNotNull(whereIs)
    }

    /** Ultra Violet: FireRed's gyms and League (BROCK's ONIX / GEODUDE), BROCK next on the save; its own wild headers and areas. */
    @Test fun `ultra violet`() {
        load(uvGame) ?: return
        assertEquals(listOf(12, 14), GuideRomSource.party(uvGame.t, 414)!!.map { it.level })
        val p = readSaveProgress(FixtureMemoryReader.load("ultra_violet"), NATIVE_ULTRA_VIOLET, uvGame.t)!!
        assertEquals("LEADER BROCK", GUIDE_ULTRA_VIOLET.bosses.first { !it.isDone(p) }.title)
        assertTrue(GuideRomSource.wildMapKeys(uvGame.t).isNotEmpty())
        assertNotNull(generatedWhereIs(GuideId.ULTRA_VIOLET, { "AREA $it" }) { false })
        fireRedHack = null
    }

    /** Rocket Edition: FireRed's leaders but ARIANA at Viridian; its own maps' areas. Retail FireRed's save has every badge. */
    @Test fun `rocket edition`() {
        load(rocket) ?: return
        assertEquals(5, GuideRomSource.party(rocket.t, 50)!!.size) // ARIANA
        assertTrue(GuideRomSource.wildMapKeys(rocket.t).isNotEmpty())
        assertNotNull(generatedWhereIs(GuideId.ROCKET_EDITION, { "AREA $it" }) { false })
        val p = readSaveProgress(FixtureMemoryReader.load("rocket_edition"), NATIVE_ROCKET_EDITION, rocket.t)!!
        assertTrue(GUIDE_ROCKET_EDITION.bosses.all { it.isDone(p) })
        fireRedHack = null
    }

    /** Ash Gray (both builds): the badges won in battle, TEAM ROCKET last; its own wild headers and areas. */
    @Test fun `ash gray`() {
        for (g in listOf(ashGray, ashGrayFork)) {
            load(g) ?: continue
            assertEquals(3, GuideRomSource.party(g.t, 350)!!.size) // TEAM ROCKET
            assertTrue(GuideRomSource.wildMapKeys(g.t).isNotEmpty())
            assertNotNull(generatedWhereIs(GuideId.ASH_GRAY, { "AREA $it" }) { false })
        }
        fireRedHack = null
    }

    /** Ghost Grey: seven badges, ERIKA next on the save (ASTON and MAJ.VOLTAGE beaten); SUMMER's team by the starter. */
    @Test fun `ghost grey`() {
        for (g in listOf(ghostGrey, ghostGreyV101)) {
            load(g) ?: continue
            assertEquals(listOf("Kabuto", "Nosegast"), GuideRomSource.party(g.t, 414)!!.map { speciesName(it.species) })
            val p = readSaveProgress(FixtureMemoryReader.load(g.fixture), g.cfg, g.t)!!
            assertEquals("LEADER ERIKA", GUIDE_GHOST_GREY.bosses.first { !it.isDone(p) }.title)
            assertTrue(GuideRomSource.wildMapKeys(g.t).isNotEmpty())
            assertNotNull(generatedWhereIs(GuideId.GHOST_GREY, { "AREA $it" }) { false })
        }
        val summer = GUIDE_GHOST_GREY.bosses.last()
        val starter = { v: Int -> SaveProgress(ByteArray(0x120), ByteArray(0x200).also { it[0x31 * 2] = v.toByte() }) }
        assertEquals(listOf(437, 436, 435), (0..2).map { summer.trainer(starter(it)) })
        fireRedHack = null
    }

    /** Sword and Shield Ultimate Plus: its eight leaders by badge (BEA / ALLISTER and GORDIE / MELONY both listed), MILO next. */
    @Test fun `sword and shield`() {
        load(swordShield) ?: return
        val leaders = GUIDE_SWORD_SHIELD.bosses.flatMap { b -> b.teams(noProgress).map { (_, id) -> id } }
        assertEquals(listOf(414, 415, 416, 27, 417, 418, 49, 420, 419, 350), leaders)
        assertEquals(listOf("Gossifleur", "Eldegoss"), GuideRomSource.party(swordShield.t, 414)!!.map { speciesName(it.species) })
        assertEquals(listOf(46, 47, 46, 48), GuideRomSource.party(swordShield.t, 350)!!.map { it.level }) // RAIHAN
        val p = readSaveProgress(FixtureMemoryReader.load("sword_shield_up"), NATIVE_SWORD_SHIELD, swordShield.t)!!
        assertEquals("LEADER MILO", GUIDE_SWORD_SHIELD.bosses.first { !it.isDone(p) }.title)
        assertTrue(GuideRomSource.wildMapKeys(swordShield.t).isNotEmpty())
        assertNotNull(generatedWhereIs(GuideId.SWORD_SHIELD, { "AREA $it" }) { false })
    }

    /** GS Chronicles: the eight Johto leaders by badge, FALKNER next on the new game's save. */
    @Test fun `gs chronicles`() {
        load(gsChronicles) ?: return
        val leaders = GUIDE_GS_CHRONICLES.bosses.flatMap { b -> b.teams(noProgress).map { (_, id) -> id } }
        assertEquals(listOf(321, 53, 74, 111, 151, 152, 153, 154), leaders)
        assertEquals(listOf("Pidgey", "Hoothoot", "Murkrow"), GuideRomSource.party(gsChronicles.t, 321)!!.map { speciesName(it.species) })
        assertEquals(listOf(49, 51, 50, 53, 53), GuideRomSource.party(gsChronicles.t, 154)!!.map { it.level }) // CLAIR
        val p = readSaveProgress(FixtureMemoryReader.load("gs_chronicles"), NATIVE_GS_CHRONICLES, gsChronicles.t)!!
        assertEquals("LEADER FALKNER", GUIDE_GS_CHRONICLES.bosses.first { !it.isDone(p) }.title)
        assertTrue(GuideRomSource.wildMapKeys(gsChronicles.t).isNotEmpty())
        assertNotNull(generatedWhereIs(GuideId.GS_CHRONICLES, { "AREA $it" }) { false })
    }

    /** Recharged Yellow, every release: the tables, each boss's NORMAL and HARD teams (BROCK 2 / 3 Pokémon - the
     * NORMAL pair is the one a scripted battle sent out), the next boss on the three-badge save, Route 1's grass and
     * the generated WHERE IS. */
    @Test fun `recharged yellow`() {
        for ((v, cfg, fixture) in listOf(
            Triple("1.8.0", NATIVE_RECHARGED_YELLOW_V1_8_0, "recharged_yellow_v180"), Triple("1.9.0", NATIVE_RECHARGED_YELLOW_V1_9_0, "recharged_yellow_v190"),
            Triple("1.9.4", NATIVE_RECHARGED_YELLOW_V1_9_4, "recharged_yellow_v194"), Triple("1.9.7", NATIVE_RECHARGED_YELLOW_V1_9_7, "recharged_yellow"),
        )) {
            val g = Game("Pokemon Recharged Yellow (v$v).gba", GameKind.RECHARGED_YELLOW, cfg, fixture)
            val rom = load(g) ?: continue
            assertTrue(v, guideTablesMatchRom(rom, g.t))
            for (boss in GUIDE_RECHARGED_YELLOW.bosses) for ((label, id) in boss.teams(noProgress)) {
                val team = GuideRomSource.party(g.t, id)
                val what = "$v ${boss.title} $label ($id)"
                assertTrue(what, team != null && team.size in 1..6 && team.all { it.species in 1..411 && it.level in 1..100 && it.moves.isNotEmpty() })
            }
            assertEquals(listOf(74 to 12, 95 to 14), GuideRomSource.party(g.t, 414)!!.map { it.species to it.level })
            assertEquals(listOf(74, 138, 95), GuideRomSource.party(g.t, 414 or (1 shl TRAINER_TABLE_SHIFT))!!.map { it.species })
            val p = readSaveProgress(FixtureMemoryReader.load(fixture), cfg, g.t)!!
            assertEquals(v, "LEADER ERIKA", GUIDE_RECHARGED_YELLOW.bosses.first { !it.isDone(p) }.title)
            // Route 1 (3.19): PIDGEY, RATTATA, ODDISH and BELLSPROUT in its grass.
            val route1 = GuideRomSource.encounters(g.t, 3, 19)!!
            assertEquals(100, route1.grass.sumOf { it.percent })
            assertEquals(setOf(16, 19, 43, 69), route1.grass.map { it.species }.toSet())
            val whereIs = generatedWhereIs(g.t.guide, { "AREA $it" }) { false }!!
            assertTrue(v, whereIs.sections.flatMap { it.entries }.any { it.title == "Linking Cord" })
        }
    }

    /** Recharged Emerald, every build: the tables, each boss's NORMAL and HARD teams (ROXANNE 3 / 4 Pokémon), the next
     * boss on retail Emerald's save (converted) and Route 114's grass. */
    @Test fun `recharged emerald`() {
        try {
            for ((file, cfg, fixture) in listOf(
                Triple("Pokemon Recharged Emerald (v2.2.5).gba", NATIVE_RECHARGED_EMERALD_V2_2_5, "recharged_emerald_v225"),
                Triple("Pokemon Recharged Emerald (v2.2.8).gba", NATIVE_RECHARGED_EMERALD_V2_2_8, "recharged_emerald"),
                Triple("Pokemon Recharged Emerald Rebalanced (v2.2.8).gba", NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8, "recharged_emerald_rebalanced"),
            )) {
                val g = Game(file, GameKind.EMERALD, cfg, fixture)
                val rom = load(g) ?: continue
                assertTrue(file, guideTablesMatchRom(rom, g.t))
                for (boss in GUIDE_RECHARGED_EMERALD.bosses) for ((label, id) in boss.teams(noProgress)) {
                    val team = GuideRomSource.party(g.t, id)
                    val what = "$file ${boss.title} $label ($id)"
                    assertTrue(what, team != null && team.size in 1..6 && team.all { it.species in 1..411 && it.level in 1..100 && it.moves.isNotEmpty() })
                }
                assertEquals(file, listOf(74 to 12, 74 to 12, 320 to 15), GuideRomSource.party(g.t, 265)!!.map { it.species to it.level })
                assertEquals(file, 4, GuideRomSource.party(g.t, 265 or (1 shl TRAINER_TABLE_SHIFT))!!.size)
                val p = readSaveProgress(FixtureMemoryReader.load(fixture), cfg, g.t)!!
                assertEquals(file, "LEADERS TATE AND LIZA", GUIDE_RECHARGED_EMERALD.bosses.first { !it.isDone(p) }.title)
                // Route 114 (0.29), where the save stands.
                val route114 = GuideRomSource.encounters(g.t, 0, 29)!!
                assertEquals(file, 100, route114.grass.sumOf { it.percent })
                assertTrue(file, route114.water.isNotEmpty())
            }
        } finally {
            emeraldHack = null
        }
    }

    /** The translations: their own tables (Quetzal Spanish) or English's (Unbound French), names in their language. */
    @Test fun `quetzal spanish and unbound french`() {
        load(quetzalEs)?.let { rom ->
            assertTrue(guideTablesMatchRom(rom, quetzalEs.t))
            for (boss in GUIDE_QUETZAL.bosses) for ((label, id) in boss.teams(noProgress)) {
                val team = GuideRomSource.party(quetzalEs.t, id)
                assertTrue("${boss.title} $label ($id)", team != null && team.size in 1..6 && team.all { it.species > 0 && it.level in 1..100 })
            }
            // Jagged Pass, where the save stands.
            assertTrue(GuideRomSource.encounters(quetzalEs.t, 24, 13)!!.grass.any { speciesNamesQuetzal[it.species] == "Phanpy" })
        }
        load(unboundFr)?.let { rom ->
            assertTrue(guideTablesMatchRom(rom, unboundFr.t))
            assertEquals(listOf(15, 16), GuideRomSource.party(unboundFr.t, 719)!!.map { it.level })
        }
    }

    /** Quetzal Alpha 0.8.4, every build: its tables, Hoenn's bosses (NORMAL and HARD), the next one on Spanish Alpha 9's
     * save (six badges), Jagged Pass's wild Pokémon (where that save stands). */
    @Test fun `quetzal 0_8_4`() {
        for (g in listOf(
            Game("Pokemon Quetzal (Alpha 0.8.4) (English).gba", GameKind.QUETZAL, NATIVE_QUETZAL_V084, "quetzal_084"),
            Game("Pokemon Quetzal (Alpha 0.8.4) (Spanish).gba", GameKind.QUETZAL, NATIVE_QUETZAL_V084_ES, "quetzal_es_084"),
            Game("Moemon Quetzal (Alpha 0.8.4 Hotfix) (English).gba", GameKind.QUETZAL, NATIVE_QUETZAL_MOEMON_V084, "quetzal_moemon_084"),
            Game("Moemon Quetzal (Alpha 0.8.4 Hotfix) (Spanish).gba", GameKind.QUETZAL, NATIVE_QUETZAL_MOEMON_V084_ES, "quetzal_moemon_es_084"),
        )) {
            val rom = load(g) ?: continue
            assertTrue(g.rom, guideTablesMatchRom(rom, g.t))
            assertEquals(GUIDE_QUETZAL_V084, gameGuide(g.t.guide))
            for (boss in GUIDE_QUETZAL_V084.bosses) for ((label, id) in boss.teams(noProgress)) {
                val team = GuideRomSource.party(g.t, id)
                val what = "${g.rom} ${boss.title} $label ($id)"
                assertTrue(what, team != null && team.size in 1..6)
                assertTrue(what, team!!.all { it.species in 1 until 1520 && it.level in 1..100 && it.moves.isNotEmpty() })
            }
            val p = readSaveProgress(FixtureMemoryReader.load(g.fixture), g.cfg, g.t)!!
            assertEquals("LEADERS TATE AND LIZA", GUIDE_QUETZAL_V084.bosses.first { !it.isDone(p) }.title)
            assertTrue(GuideRomSource.encounters(g.t, 24, 13)!!.grass.any { speciesNamesQuetzal[it.species] == "Phanpy" })
        }
    }

    private val imperium = Game("Pokemon Emerald Imperium (v1.3.1).gba", GameKind.IMPERIUM, NATIVE_IMPERIUM, "imperium")
    private val quetzal = Game("Pokemon Quetzal (Alpha 9.0) (English).gba", GameKind.QUETZAL, NATIVE_QUETZAL, "quetzal")

    /** Every boss team of Imperium's and Quetzal's guides (Quetzal's NORMAL and HARD, three regions' tables). */
    @Test fun `imperium and quetzal tables and teams`() = listOf(imperium, quetzal).forEach { g ->
        val rom = load(g) ?: return@forEach
        assertTrue(g.kind.name, guideTablesMatchRom(rom, g.t))
        for (boss in gameGuide(g.t.guide)!!.bosses) for ((label, id) in boss.teams(noProgress)) {
            val team = GuideRomSource.party(g.t, id)
            val what = "${g.kind} ${boss.title} $label ($id)"
            assertTrue(what, team != null && team.size in 1..6)
            assertTrue(what, team!!.all { it.species > 0 && it.level in 1..100 && it.moves.isNotEmpty() })
        }
    }

    /** ROXANNE's team; HERE on each save's map (Imperium: Route 101 has the Shinx a walk met; Quetzal: Kanto Route 1). */
    @Test fun `imperium and quetzal HERE and NEXT BOSS`() {
        load(imperium)?.let {
            assertEquals(listOf(15, 15, 15, 14).sorted(), GuideRomSource.party(imperium.t, 265)!!.map { m -> m.level }.sorted())
            val here = GuideRomSource.encounters(imperium.t, 0, 16)!!
            assertTrue(here.grass.any { e -> speciesNamesImperium[e.species] == "Shinx" })
            val p = readSaveProgress(FixtureMemoryReader.load("imperium"), NATIVE_IMPERIUM, imperium.t)!!
            assertEquals("LEADER ROXANNE", GUIDE_IMPERIUM.bosses.first { b -> !b.isDone(p) }.title)
        }
        load(quetzal)?.let {
            val here = GuideRomSource.encounters(quetzal.t, 0x25, 0x4C)!!
            assertTrue(listOf("Pidgey", "Rattata", "Fletchling", "Wooloo", "Lechonk").all { n -> here.grass.any { e -> speciesNamesQuetzal[e.species] == n } })
            val p = readSaveProgress(FixtureMemoryReader.load("quetzal"), NATIVE_QUETZAL, quetzal.t)!!
            // On Kanto's Route 1 (map group 0x25): Kanto's campaign first.
            assertEquals("LEADER BROCK", GUIDE_QUETZAL.bossesFor!!(0x25).first { b -> !b.isDone(p) }.title)
        }
    }

    private val lazarus = Game("Pokemon Lazarus (v2.0).gba", GameKind.LAZARUS, NATIVE_LAZARUS, "lazarus")
    private val seaglass = Game("Pokemon Emerald Seaglass (v3.0).gba", GameKind.EMERALD_SEAGLASS, NATIVE_EMERALD_SEAGLASS, "emerald_seaglass")
    private val tmt2 = Game("Pokemon Too Many Types 2 (v1.5.2).gba", GameKind.TMT2, NATIVE_TMT2, "tmt2")
    private val soulgold = Game("Pokemon SoulGold (v1.1.4).gba", GameKind.SOULGOLD, NATIVE_SOULGOLD, "soulgold")
    private val soulgold12 = Game("Pokemon SoulGold (v1.2).gba", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2, "soulgold_v12")
    private val soulgold12b = Game("Pokemon SoulGold (v1.2) (Alt 1).gba", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2B, "soulgold_v12b")
    private val soulgold12c = Game("Pokemon SoulGold (v1.2) (Alt 2).gba", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2C, "soulgold_v12c")
    private val soulgold121 = Game("Pokemon SoulGold (v1.2.1).gba", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2_1, "soulgold_v121")
    private val soulgold121b = Game("Pokemon SoulGold (v1.2.1) (Alt 1).gba", GameKind.SOULGOLD, NATIVE_SOULGOLD_V1_2_1, "soulgold_v121b")
    private val expansion = listOf(lazarus, seaglass, tmt2, soulgold, soulgold12, soulgold12b, soulgold12c, soulgold121, soulgold121b)

    /** The expansion hacks' tables and every team (SoulGold's Kanto BROCK has mons with no moves listed: the game fills them). */
    @Test fun `expansion hacks tables and teams`() = expansion.forEach { g ->
        val rom = load(g) ?: return@forEach
        assertTrue(g.fixture, guideTablesMatchRom(rom, g.t))
        for (boss in gameGuide(g.t.guide)!!.bosses) for ((label, id) in boss.teams(noProgress)) {
            val team = GuideRomSource.party(g.t, id)
            val what = "${g.fixture} ${boss.title} $label ($id)"
            assertTrue(what, team != null && team.size in 1..6)
            assertTrue(what, team!!.all { it.species > 0 && it.level in 1..100 })
        }
        val progress = readSaveProgress(FixtureMemoryReader.load(g.fixture), g.cfg, g.t)
        assertNotNull(g.fixture, gameGuide(g.t.guide)!!.bosses.firstOrNull { !it.isDone(progress!!) })
    }

    /** HERE on a map each was walked on headless until a wild battle came (that foe is in the table). */
    @Test fun `expansion hacks HERE`() = listOf(lazarus to (0 to 79), seaglass to (0 to 16), tmt2 to (0 to 32), soulgold to (0 to 16), soulgold12c to (0 to 16), soulgold121 to (0 to 16)).forEach { (g, map) ->
        load(g) ?: return@forEach
        val here = GuideRomSource.encounters(g.t, map.first, map.second)
        assertNotNull(g.fixture, here)
        // TMT2's tables have 11 land slots: the game's last 1% reads past them.
        assertEquals(g.fixture, if (g === tmt2) 99 else 100, here!!.grass.sumOf { it.percent })
    }

    /** Lazarus takes a map's second wild header at night (GetCurrentMapWildMonHeaderId's clock check): Acrisia City
     * (0.57) has HOOTHOOT only then, PIKIPEK only by day. Its caves (group 24) never switch. */
    @Test fun `Lazarus night wild sets`() {
        load(lazarus) ?: return
        val t = lazarus.t
        fun at(hour: Int) = SaveProgress(ByteArray(0x12C), ByteArray(0x200), varBanks = listOf(SaveBank(GBA_VAR_HOUR, 0, 1) to byteArrayOf(hour.toByte(), 0)))
        val fixture = readSaveProgress(FixtureMemoryReader.load(lazarus.fixture), lazarus.cfg, t)!!
        assertEquals(10, fixture.variable(GBA_VAR_HOUR)) // the capture's clock: 10:58
        assertEquals(0, t.wildSetOf(0, 57, fixture))
        assertEquals(1, t.otherTimeSetOf(0, 57, fixture))
        for (h in listOf(0, 6, 20, 23)) assertEquals("$h:00", 1, t.wildSetOf(0, 57, at(h)))
        for (h in listOf(7, 12, 19)) assertEquals("$h:00", 0, t.wildSetOf(0, 57, at(h)))
        assertEquals(0, t.wildSetOf(24, 112, at(22)))
        assertEquals(null, t.otherTimeSetOf(24, 112, at(22)))
        val day = GuideRomSource.encounters(t, 0, 57, 0)!!.grass.map { it.species }
        val night = GuideRomSource.encounters(t, 0, 57, 1)!!.grass.map { it.species }
        assertTrue(731 in day && 731 !in night) // PIKIPEK
        assertTrue(163 in night && 163 !in day) // HOOTHOOT
        assertEquals(100, GuideRomSource.encounters(t, 0, 57, 1)!!.grass.sumOf { it.percent })
        assertEquals(2, GuideRomSource.encounterSets(t, 0, 57).size) // the DEX's AREA sees both
        assertEquals(1, GuideRomSource.encounterSets(t, 24, 112).size)
    }

    @Test fun `generated WHERE IS`() = all.forEach { g ->
        select(g)
        val page = generatedWhereIs(g.t.guide, { "AREA $it" }) { false }
        assertNotNull(g.kind.name, page)
        assertTrue(g.kind.name, page!!.sections.any { it.heading == "KEY ITEMS" && it.entries.size >= 5 })
    }
}
