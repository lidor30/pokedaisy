package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polished Crystal's GUIDE (GuidePolishedCrystal.kt over GuidePolishedCrystalGen.kt) on the polished_crystal fixtures: a new
 * game saved on ROUTE 29 with no badges and the clock at DAY (see its README). The facts pinned here are the source's
 * (v3.2.3: data/wild/johto_grass.asm, data/wild/treemons.asm, data/trainers/parties.asm, maps/Route29.asm).
 */
class PolishedCrystalGuideTest {
    private fun read(fixture: String = "polished_crystal", cfg: Gen2Config = GEN2_POLISHED_CRYSTAL): SnapshotView {
        activeGame = GameKind.POLISHED_CRYSTAL
        romLanguage = 'E'
        romGameCode = ""
        activeGen2 = cfg
        return buildSnapshotView(readGen2Telemetry(FixtureMemoryReader.load(fixture), cfg)).copy(game = GameKind.POLISHED_CRYSTAL)
    }

    @Test
    fun `the save's flags, badges and clock`() {
        for (fixture in listOf("polished_crystal", "polished_crystal_3_2_0", "polished_crystal_3_2_1", "polished_crystal_3_2_2")) {
            val p = read(fixture).progress
            assertNotNull(fixture, p)
            p!!
            assertEquals(fixture, 0, p.polishedBadges())
            assertEquals(fixture, PC_DAY, p.polishedTimeOfDay())
            // CYNDAQUIL from ELM (EVENT_GOT_A_POKEMON_FROM_ELM, EVENT_GOT_CYNDAQUIL_FROM_ELM), no gym beaten yet.
            assertTrue(fixture, p.flag(27) && p.flag(28))
            assertFalse(fixture, p.flag(29) || p.flag(30))
            assertFalse(fixture, p.flag(PC_EVENT_BEAT_FALKNER))
        }
    }

    @Test
    fun `the guide is Polished Crystal's own`() {
        assertEquals(GuideId.POLISHED_CRYSTAL, guideId(GameKind.POLISHED_CRYSTAL, null))
        val g = gameGuide(GuideId.POLISHED_CRYSTAL)!!
        assertFalse(g.verified)
        assertTrue(g.world === PolishedCrystalWorld)
        // Other Game Boy games still have none.
        assertEquals(null, guideId(GameKind.GOLD, null))
    }

    @Test
    fun `NEXT BOSS is FALKNER with his team as the game defines it`() {
        val v = read()
        val p = v.progress!!
        val bosses = gameGuide(GuideId.POLISHED_CRYSTAL)!!.bosses
        val next = bosses.first { !it.isDone(p) }
        assertEquals("LEADER FALKNER", next.title)
        val team = PolishedCrystalWorld.party(next.trainer(p), p)
        assertEquals(listOf("Natu" to 10, "Hoothoot" to 11, "Pidgeotto" to 13), team.map { speciesName(it.species) to it.level })
        assertEquals(listOf("Peck", "Leer", "Night Shade", "Mud-Slap"), team[0].moves.map { lookupMove(it).name })
        assertEquals(listOf("Gust", "Mud-Slap", "Quick Attack", "Roost"), team[2].moves.map { lookupMove(it).name })
        assertEquals("Pecha Berry", itemName(team[2].heldItem))
        assertEquals(0, team[0].heldItem)
        // Every boss has a team of up to six, each with its moves.
        for (b in bosses) {
            val t = PolishedCrystalWorld.party(b.trainer(p), p)
            assertTrue(b.title, t.size in 1..6 && t.all { it.moves.isNotEmpty() && it.level in 2..100 })
        }
        // The League's second teams (all 16 badges) are stronger; the faithful builds keep a few moves of the original games.
        assertEquals(75, PolishedCrystalWorld.party(PolishedCrystalWorld.trainer("LANCE_2"), p).minOf { it.level })
        activeGen2 = GEN2_POLISHED_CRYSTAL_FAITHFUL
        val faithfulLance = PolishedCrystalWorld.party(PolishedCrystalWorld.trainer("LANCE"), p)
        activeGen2 = GEN2_POLISHED_CRYSTAL
        val lance = PolishedCrystalWorld.party(PolishedCrystalWorld.trainer("LANCE"), p)
        assertEquals(lance.map { it.species }, faithfulLance.map { it.species })
        assertTrue(lance.map { it.moves } != faithfulLance.map { it.moves })
    }

    @Test
    fun `the League order follows its flags`() {
        val bosses = gameGuide(GuideId.POLISHED_CRYSTAL)!!.bosses
        // All eight Johto badges and EVENT_BEAT_ELITE_FOUR: the first League is done, Kanto's gyms come next.
        val flags = ByteArray(PC_EVENT_FLAG_BYTES)
        fun set(n: Int) { flags[n / 8] = (flags[n / 8].toInt() or (1 shl (n % 8))).toByte() }
        listOf(PC_EVENT_BEAT_FALKNER, PC_EVENT_BEAT_BUGSY, PC_EVENT_BEAT_WHITNEY, PC_EVENT_BEAT_MORTY, PC_EVENT_BEAT_CHUCK,
            PC_EVENT_BEAT_JASMINE, PC_EVENT_BEAT_PRYCE, PC_EVENT_BEAT_CLAIR, PC_EVENT_BEAT_ELITE_FOUR).forEach(::set)
        fun progress(badges: Int) = SaveProgress(flags.copyOf(), ByteArray(0), flagBanks = listOf(
            SaveBank(PC_BADGE_FLAG, 0, 16) to byteArrayOf((badges and 0xFF).toByte(), (badges shr 8).toByte()),
        ))
        assertEquals("LEADER BROCK", bosses.first { !it.isDone(progress(0xFF)) }.title)
        // Every Kanto gym too, and all 16 badges: the rematch.
        listOf(PC_EVENT_BEAT_BROCK, PC_EVENT_BEAT_MISTY, PC_EVENT_BEAT_LTSURGE, PC_EVENT_BEAT_ERIKA, PC_EVENT_BEAT_JANINE,
            PC_EVENT_BEAT_SABRINA, PC_EVENT_BEAT_BLAINE, PC_EVENT_BEAT_BLUE).forEach(::set)
        assertEquals("ELITE FOUR WILL, REMATCH", bosses.first { !it.isDone(progress(0xFFFF)) }.title)
        // WILL beaten in that run (his room's flag, with 16 badges): KOGA's second team next.
        set(PC_EVENT_BEAT_ELITE_4_WILL)
        assertEquals("ELITE FOUR KOGA, REMATCH", bosses.first { !it.isDone(progress(0xFFFF)) }.title)
        set(PC_EVENT_BEAT_ELITE_FOUR_AGAIN)
        assertEquals("RED", bosses.first { !it.isDone(progress(0xFFFF)) }.title)
    }

    @Test
    fun `HERE on ROUTE 29 lists the day's grass and the trees`() {
        val v = read()
        val here = PolishedCrystalWorld.encounters(v.mapGroup, v.mapNum, v.progress)
        // Day: PIDGEY 30 + 20, SENTRET 30 + 10, RATTATA 5, HOPPIP 3 + 2 (GrassMonProbTable).
        assertEquals(
            listOf("Pidgey" to 50, "Sentret" to 40, "Rattata" to 5, "Hoppip" to 5),
            here.grass.map { speciesName(it.species) to it.percent },
        )
        assertEquals(2 to 3, here.grass[0].minLevel to here.grass[0].maxLevel)
        assertTrue(here.water.isEmpty() && here.oldRod.isEmpty())
        // TREEMON_SET_ROUTE.
        assertEquals(listOf("Hoothoot" to 50, "Exeggcute" to 20, "Spinarak" to 15, "Ledyba" to 15), here.headbutt.map { speciesName(it.species) to it.percent })
        assertEquals(listOf("Murkrow", "Pineco", "Exeggcute"), here.headbuttRare.map { speciesName(it.species) })
        assertEquals("DAY", PolishedCrystalWorld.clockNote(v.progress))
        // At night (and in the evening, which uses the night's list): HOOTHOOT and RATTATA.
        for (tod in listOf(PC_NITE, PC_EVE)) {
            val night = SaveProgress(ByteArray(PC_EVENT_FLAG_BYTES), ByteArray(0), varBanks = listOf(SaveBank(PC_VAR_TIME_OF_DAY, 0, 1) to byteArrayOf(tod.toByte(), 0)))
            assertEquals(listOf("Hoothoot" to 55, "Rattata" to 45), PolishedCrystalWorld.encounters(v.mapGroup, v.mapNum, night).grass.map { speciesName(it.species) to it.percent })
        }
    }

    @Test
    fun `badge-relative levels and surfing's bonus`() {
        // AdjustLevelForBadges: LEVEL_FROM_BADGES + n is BadgeBaseLevels[badges] + n, within 2..99.
        assertEquals(4 + 1, PolishedCrystalWorld.level(PC_LEVEL_FROM_BADGES + 1, 0))
        assertEquals(61 - 3, PolishedCrystalWorld.level(PC_LEVEL_FROM_BADGES - 3, 16))
        assertEquals(2, PolishedCrystalWorld.level(PC_LEVEL_FROM_BADGES - 4, 0))
        assertEquals(30, PolishedCrystalWorld.level(30, 8))
        // ROUTE 30's fishing pond (POLIWAG on every rod) and any surfing map list a 4-level spread.
        val everyMap = (0..40).flatMap { g -> (1..60).map { g to it } }
        val surf = everyMap.map { (g, n) -> PolishedCrystalWorld.encounters(g, n, null).water }.first { it.isNotEmpty() }
        assertTrue(surf.all { it.maxLevel >= it.minLevel + 4 })
    }

    @Test
    fun `ROUTE 29's area - its item ball, LYRA's POKé BALLs, and ELM's starters with the one you took`() {
        val v = read()
        assertEquals("Route 29", v.location.mapSecName)
        val things = areaThings(GuideId.POLISHED_CRYSTAL, v.regionMapSectionId)
        val potion = things.single { it.kind == AreaKind.ITEM }
        assertEquals("Potion", itemName(potion.id))
        assertFalse(v.progress!!.flag(potion.flag))   // still lying there
        assertTrue(things.any { it.kind == AreaKind.GIFT && itemName(it.id) == "Poké Ball" && it.qty == 5 })
        assertTrue(things.any { it.kind == AreaKind.GIFT && itemName(it.id) == "Silk Scarf" })
        // NEW BARK TOWN's ELM'S LAB: the three starters, each with its own flag, CYNDAQUIL's set.
        val newBark = activeMapSecData.entries.first { it.value.name == "New Bark Town" }.key
        val starters = areaThings(GuideId.POLISHED_CRYSTAL, newBark).filter { it.kind == AreaKind.MON }
        assertEquals(setOf("Chikorita", "Cyndaquil", "Totodile"), starters.map { speciesName(it.id) }.toSet())
        assertEquals(listOf("Cyndaquil"), starters.filter { v.progress!!.flag(it.flag) }.map { speciesName(it.id) })
        assertTrue(starters.all { it.where == "ELMS LAB" })
        // A trade's done flag is its wTradeFlags bit (VIOLET CITY's: POLIWAG for a VOLTORB).
        val trade = allAreaThings(GuideId.POLISHED_CRYSTAL).values.flatten().first { it.kind == AreaKind.TRADE && speciesName(it.id) == "Voltorb" }
        assertEquals("Poliwag", speciesName(trade.wants))
        assertTrue(trade.flag >= PC_TRADE_FLAG)
        assertFalse(v.progress!!.flag(trade.flag))
    }

    @Test
    fun `WHERE IS is built from the area data`() {
        read()
        val page = generatedWhereIs(GuideId.POLISHED_CRYSTAL, { activeMapSecData[it]?.name?.uppercase() ?: "?" }) { false }!!
        val hms = page.sections.first { it.heading == "HMs" }.entries.map { it.title }
        assertTrue(hms.toString(), hms.any { it.startsWith("HM01") } && hms.any { it.startsWith("HM03") })
        val gifts = page.sections.first { it.heading == "GIFT POKéMON" }.entries.map { it.title }
        assertTrue(gifts.toString(), "Eevee" in gifts && "Shuckle" in gifts)
    }
}
