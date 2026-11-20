package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DS games' GUIDE (GuideGen4.kt over the data scripts/gen_gen4_guide.py generates from pret's decomps) on the
 * `platinum` fixture (a new game: TURTWIG, no badges) and the `heartgold` one (the player's save: all 16 badges, the
 * game cleared). The facts pinned are the decomps' (pokeplatinum res/field/encounters/encounters_route_201.json,
 * res/trainers/data/leader_roark.json; pokeheartgold files/fielddata/encountdata/gs_enc_data.json, trainers.json).
 */
class Gen4GuideTest {
    private fun platinum(hour: Int = 12): SaveProgress {
        activeGame = GameKind.PLATINUM
        val r = FixtureMemoryReader.load("platinum")
        return readGen4Progress(r, GEN4_PLATINUM_US, 0x0227E164L + GEN4_PLATINUM_US.bodyOff, hour)!!
    }

    private fun heartGold(hour: Int = 12): SaveProgress {
        activeGame = GameKind.HEARTGOLD
        val r = FixtureMemoryReader.load("heartgold")
        return readGen4Progress(r, GEN4_HEARTGOLD_US, 0x0227C28CL + GEN4_HEARTGOLD_US.bodyOff, hour)!!
    }

    private fun MapEncounters.grassNames() = grass.map { speciesName(it.species) to it.percent }

    @Test
    fun `the guides are the DS games' own`() {
        assertEquals(GuideId.PLATINUM, guideId(GameKind.PLATINUM, null))
        assertEquals(GuideId.HEARTGOLD, guideId(GameKind.HEARTGOLD, null))
        assertEquals(GuideId.SOULSILVER, guideId(GameKind.SOULSILVER, null))
        assertTrue(gameGuide(GuideId.PLATINUM)!!.world === PLATINUM_WORLD)
        assertTrue(gameGuide(GuideId.SOULSILVER)!!.world === SOULSILVER_WORLD)
        for (id in listOf(GuideId.PLATINUM, GuideId.HEARTGOLD, GuideId.SOULSILVER)) assertFalse(gameGuide(id)!!.verified)
    }

    @Test
    fun `Platinum - the save's flags, vars and badges`() {
        val p = platinum()
        assertTrue(p.flag(135))                       // FLAG_TALKED_TO_MOM
        assertEquals(387, p.variable(0x4030))         // VAR_PLAYER_STARTER: TURTWIG
        assertFalse((0 until 8).any { p.flag(GEN4_BADGE_FLAG + it) })
        assertEquals(12, p.variable(GEN4_VAR_HOUR))
    }

    @Test
    fun `Platinum - NEXT BOSS is ROARK, with his team`() {
        val p = platinum()
        val bosses = gameGuide(GuideId.PLATINUM)!!.bosses
        assertEquals(13, bosses.size)
        val next = bosses.first { !it.isDone(p) }
        assertEquals("LEADER ROARK", next.title)
        val team = PLATINUM_WORLD.party(next.trainer(p), p)
        assertEquals(listOf("GEODUDE" to 12, "ONIX" to 12, "CRANIDOS" to 14), team.map { speciesName(it.species) to it.level })
        assertEquals(listOf("Stealth Rock", "Rock Throw"), team[0].moves.map { lookupMove(it).name })
        for (b in bosses) {
            val t = PLATINUM_WORLD.party(b.trainer(p), p)
            assertTrue(b.title, t.size in 3..6 && t.all { it.moves.isNotEmpty() && it.level in 2..100 })
        }
        assertEquals("CHAMPION CYNTHIA", bosses.last().title)
        assertEquals(62, PLATINUM_WORLD.party(bosses.last().trainer(p), p).maxOf { it.level })   // GARCHOMP
    }

    @Test
    fun `Platinum - ROUTE 201's grass by the clock`() {
        // Its 12 slots: STARLY / BIDOOF, KRICKETOT in slot 4 (the morning's); slots 3 and 4 take the day pair (STARLY,
        // BIDOOF) or the night's (KRICKETOT, BIDOOF).
        val morning = PLATINUM_WORLD.encounters(0, 342, platinum(hour = 6))
        assertEquals(listOf("STARLY" to 50, "BIDOOF" to 40, "KRICKETOT" to 10), morning.grassNames())
        assertEquals(2 to 3, morning.grass.first().minLevel to morning.grass.first().maxLevel)
        val day = PLATINUM_WORLD.encounters(0, 342, platinum(hour = 12))
        assertEquals(setOf("STARLY" to 50, "BIDOOF" to 50), day.grassNames().toSet())
        val night = PLATINUM_WORLD.encounters(0, 342, platinum(hour = 22))
        assertEquals(listOf("BIDOOF" to 50, "STARLY" to 40, "KRICKETOT" to 10), night.grassNames())
        assertEquals("DAY", PLATINUM_WORLD.clockNote(platinum(hour = 12)))
        assertEquals("NIGHT", PLATINUM_WORLD.clockNote(platinum(hour = 2)))
        // The player's house: no wild Pokémon.
        assertTrue(PLATINUM_WORLD.encounters(0, 414, platinum()).isEmpty)
    }

    @Test
    fun `HeartGold - every badge and the game cleared, so NEXT BOSS is RED`() {
        val p = heartGold()
        assertTrue((0 until 16).all { p.flag(GEN4_BADGE_FLAG + it) })
        assertTrue(p.flag(HG_FLAG_GAME_CLEAR))
        val bosses = gameGuide(GuideId.HEARTGOLD)!!.bosses
        val next = bosses.first { !it.isDone(p) }
        assertEquals("RED", next.title)
        val red = HEARTGOLD_WORLD.party(next.trainer(p), p)
        assertEquals("PIKACHU" to 88, speciesName(red[0].species) to red[0].level)
        val falkner = HEARTGOLD_WORLD.party(HEARTGOLD_WORLD.trainer("FALKNER"), p)
        assertEquals(listOf("PIDGEY" to 9, "PIDGEOTTO" to 13), falkner.map { speciesName(it.species) to it.level })
        assertEquals(listOf("Tackle", "Sand-Attack"), falkner[0].moves.map { lookupMove(it).name })
    }

    @Test
    fun `HeartGold and SoulSilver - each game's own wild Pokemon, by the clock`() {
        // ROUTE 29: PIDGEY / SENTRET by day, HOOTHOOT / RATTATA at night.
        val morning = HEARTGOLD_WORLD.encounters(0, 33, heartGold(hour = 6)).grassNames().toMap()
        assertEquals(mapOf("PIDGEY" to 55, "SENTRET" to 40, "RATTATA" to 5), morning)
        val night = HEARTGOLD_WORLD.encounters(0, 33, heartGold(hour = 22)).grassNames().toMap()
        assertTrue("HOOTHOOT" in night && "PIDGEY" !in night)
        assertEquals(100, night.values.sum())
        assertEquals("MORNING", HEARTGOLD_WORLD.clockNote(heartGold(hour = 6)))
        // ROUTE 36: GROWLITHE in HeartGold's grass, VULPIX in SoulSilver's.
        val hg = HEARTGOLD_WORLD.encounters(0, 40, heartGold()).grassNames().toMap()
        val ss = SOULSILVER_WORLD.encounters(0, 40, heartGold()).grassNames().toMap()
        assertTrue("GROWLITHE" in hg && "VULPIX" !in hg)
        assertTrue("VULPIX" in ss && "GROWLITHE" !in ss)
    }

    @Test
    fun `HERE's items - item balls and hidden items by the map's place, with the save's done flags`() {
        // ROUTE 202's POTION ball (FLAG_OBTAINED_ROUTE_202_POTION), not picked up in the new game.
        val route202 = areaThings(GuideId.PLATINUM, areaSection(GameKind.PLATINUM, 343, -1))
        val potion = route202.single { it.kind == AreaKind.ITEM && it.flag == 1014 }
        assertEquals(17 to "", potion.id to potion.where)
        assertFalse(platinum().flag(potion.flag))
        // ROUTE 29's POTION ball and its hidden NUGGET: the player's HeartGold save left both (it picked up 6 of the
        // game's 257 item balls - FLAG_HIDE_ITEMBALL_*, set by the ball's HidePerson - and none of its hidden items).
        val route29 = areaThings(GuideId.HEARTGOLD, areaSection(GameKind.HEARTGOLD, 33, -1))
        val ball = route29.single { it.kind == AreaKind.ITEM && it.flag == 0x439 }
        assertEquals(17, ball.id)
        assertTrue(route29.any { it.kind == AreaKind.HIDDEN && it.id == 92 })   // NUGGET
        val p = heartGold()
        assertFalse(route29.any { p.flag(it.flag) })
        assertTrue(p.flag(0x442))   // one of the balls it did pick up
        // A building's items count with its town, named by the map they're in.
        assertTrue(areaThings(GuideId.HEARTGOLD, areaSection(GameKind.HEARTGOLD, 33, -1)).isNotEmpty())
        assertTrue(allAreaThings(GuideId.PLATINUM).values.flatten().any { it.where.isNotEmpty() })
    }

    @Test
    fun `HeartGold and SoulSilver - each game's own wing and bell on the hand-written pages`() {
        fun titles(id: GuideId) = gameGuide(id)!!.pages.flatMap { p -> p.sections.flatMap { it.entries.map { e -> e.title } } }
        assertTrue("RAINBOW WING" in titles(GuideId.HEARTGOLD) && "CLEAR BELL" in titles(GuideId.HEARTGOLD))
        assertTrue("SILVER WING" in titles(GuideId.SOULSILVER) && "TIDAL BELL" in titles(GuideId.SOULSILVER))
        // The player's save has every HM: WHERE IS marks all eight.
        val bag = readGen4Telemetry(FixtureMemoryReader.load("heartgold").also { activeGame = GameKind.HEARTGOLD }, GEN4_HEARTGOLD_US)
            .items.map { it.itemId }.toSet()
        val hms = gameGuide(GuideId.HEARTGOLD)!!.pages.first { it.title == "WHERE IS" }.sections.first().entries
        assertEquals(8, hms.size)
        assertTrue(hms.all { GuideContext(null, bag, null).has(it.have) })
    }
}
