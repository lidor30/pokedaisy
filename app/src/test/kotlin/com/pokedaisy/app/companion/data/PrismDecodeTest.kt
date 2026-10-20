package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pokémon Prism v0.95 build 254 (Game Boy Color, a Crystal-engine hack) on a new game played headless - see the prism
 * fixtures' READMEs: the save booted (prism), a wild battle in ACQUA MINES (prism_battle, prism_battle_menu) and LEADER
 * JOSIAH's battle started through the game's own script code (prism_trainer).
 */
class PrismDecodeTest {
    private fun read(fixture: String): Telemetry {
        activeGame = GameKind.PRISM
        romLanguage = 'E'
        romGameCode = ""
        activeGen2 = GEN2_PRISM
        return readGen2Telemetry(FixtureMemoryReader.load(fixture), GEN2_PRISM)
    }

    @Test
    fun `party - Crystal's struct, Prism's own species ids`() {
        val t = read("prism")
        assertFalse(t.inBattle)
        assertEquals(listOf("Larvitar", "Shinx"), t.party.map { speciesName(it.species) })
        assertEquals(listOf("Larvitar", "Shinx"), t.party.map { it.nickname })
        val larvitar = t.party[0]
        assertEquals(246 to 5, larvitar.species to larvitar.level)   // not its Dex number: Prism's own id
        assertEquals(19 to 21, larvitar.hp to larvitar.maxHp)
        assertEquals(listOf("Scratch", "Leer"), larvitar.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(GENDER_SYMBOL_MALE, larvitar.genderSymbol)
        val shinx = t.party[1]
        assertEquals(27 to 2, shinx.species to shinx.level)
        assertEquals(listOf("Tackle"), shinx.moves.filter { it != 0 }.map { lookupMove(it).name })
        assertEquals(14 to 14, shinx.hp to shinx.maxHp)
    }

    @Test
    fun `money, its bigger pockets, map and place`() {
        val t = read("prism")
        assertEquals(3000L, t.money)
        assertEquals(listOf("Potion" to 3, "Antidote" to 1, "Oran Berry" to 2, "Poké Ball" to 8, "Bicycle" to 1),
            t.items.map { itemName(it.itemId) to it.quantity })
        assertEquals(listOf(POCKET_ITEMS, POCKET_ITEMS, POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_KEY_ITEMS), t.items.map { it.pocket })
        assertEquals(31 to 1, t.mapGroup to t.mapNum)
        assertEquals(29 to 30, t.x to t.y)
        val place = lookupLocation(t.regionMapSectionId)
        assertEquals("Acqua Mines", place.mapSecName)
        assertEquals(Gen2Art.PRISM_TOWN_MAPS[0], place.regionMapAsset)   // Naljo
        assertEquals((5 * 60L + 37) * 60 + 11, t.frameCounter)   // 0:05:37, frame 11
        // Its own dex: LARVITAR (species 246) is No.001, SHINX (27) No.016 - both caught (PrismDexTest has the pages).
        assertEquals(setOf(1, 16), t.pokedex!!.caught)
    }

    @Test
    fun `TMs - a bit each, named by their moves`() {
        // TM01 and TM03 in the first byte of wTMsHMs, HM05 in its thirteenth (101 bits): the TM pocket lists them once.
        val bits = ByteArray(13).also { it[0] = 0x05; it[12] = 0x10 }
        val reader = object : MemoryReader {
            override fun readCoreMemory(addr: Long, len: Int): ByteArray {
                val fixture = FixtureMemoryReader.load("prism")
                if (addr == GEN2_PRISM.tmsHms) return bits.copyOf(len)
                return fixture.readCoreMemory(addr, len)
            }
        }
        activeGame = GameKind.PRISM
        activeGen2 = GEN2_PRISM
        val tms = readGen2Bag(reader, GEN2_PRISM).filter { it.pocket == POCKET_TM_HM }
        assertEquals(listOf("TM01 DynamicPunch" to 1, "TM03 Curse" to 1, "HM05 Rock Smash" to 1),
            tms.map { itemName(it.itemId) to it.quantity })
    }

    @Test
    fun `its own types and type chart`() {
        activeGame = GameKind.PRISM
        assertEquals("Fairy", typeName(10))
        assertEquals("Gas", typeName(11))
        assertEquals("Sound", typeName(13))
        assertEquals(listOf("Rock", "Ground"), activeSpeciesTypeData.getValue(246).let { listOf(typeName(it.type1), typeName(it.type2)) })
        assertEquals(200, activeTypeEffectiveness[20 * 100 + 22])   // Fire on Grass
        assertEquals(0, activeTypeEffectiveness[0 * 100 + 8])        // Normal on Ghost
        assertEquals(50, activeTypeEffectiveness[22 * 100 + 20])     // Grass on Fire
        assertEquals("Noise Pulse", lookupMove(254).name)
    }

    @Test
    fun `wild battle - both battlers`() {
        val t = read("prism_battle")
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Larvitar" to 5, speciesName(me.species) to me.level)
        assertEquals("Rock", typeName(me.type1))
        assertEquals("Ground", typeName(me.type2))
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Shinx" to 2, speciesName(foe.species) to foe.level)
        assertEquals("Electric", typeName(foe.type1))
        assertTrue(t.enemyParty.isEmpty())
    }

    @Test
    fun `trainer battle - LEADER JOSIAH's two`() {
        val t = read("prism_trainer")
        assertTrue(t.inBattle)
        assertEquals(listOf("Charmander", "Slugma"), t.enemyParty.map { speciesName(it.species) })
        assertEquals(0, t.enemyActive)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Charmander" to 10, speciesName(foe.species) to foe.level)
        assertEquals("Fire", typeName(foe.type1))
    }

    @Test
    fun `battle menus - Crystal's, where Crystal has them`() {
        assertEquals(BATTLE_INPUT_MOVE_SELECT to 1, readGen2BattleInput(FixtureMemoryReader.load("prism_battle"), GEN2_PRISM))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 3, readGen2BattleInput(FixtureMemoryReader.load("prism_battle_menu"), GEN2_PRISM))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 0, readGen2BattleInput(FixtureMemoryReader.load("prism_trainer"), GEN2_PRISM))
        assertEquals(BATTLE_INPUT_NONE to -1, readGen2BattleInput(FixtureMemoryReader.load("prism"), GEN2_PRISM))
    }

    @Test
    fun `growth rates - its own, by species id`() {
        val t = read("prism")
        assertEquals(156L, expProgress(246, 5, t.party[0].exp)!!.levelStart)   // Slow: Lv5 at 156
        assertEquals(270L, expProgress(246, 5, t.party[0].exp)!!.nextLevel)
        assertEquals(t.party[1].exp, expProgress(27, 2, t.party[1].exp)!!.levelStart)   // caught at Lv2
    }

    @Test
    fun `its landmarks on its six town maps`() {
        activeGame = GameKind.PRISM
        assertEquals(113, activeMapSecData.size)
        assertEquals(6, activeRegionMapImages.size)
        assertEquals(Triple(0, 3, 13), activeMapSecData.getValue(0x24).let { Triple(it.region, it.x, it.y) })   // Acqua Mines
        assertEquals("Seashore City" to 1, activeMapSecData.getValue(0x31).let { it.name to it.region })   // Rijon
        assertEquals("Goldenrod City" to 2, activeMapSecData.getValue(0x60).let { it.name to it.region })
        assertEquals(-1, activeMapSecData.getValue(0x70).region)   // Mystery Zone: on no map
    }

    @Test
    fun `detected by SHA-1`() {
        assertTrue(TelemetrySampler.PRISM_V0_95_254_SHA1 in TelemetrySampler.SUPPORTED_GB_SHA1S)
        assertEquals("Pokémon Prism", com.pokedaisy.app.GameTitles.BY_SHA1[TelemetrySampler.PRISM_V0_95_254_SHA1])
        // The "LOL PRISM" file isn't Prism (Crystal rev 0 with a joke in its first 32 KB): not supported.
        assertFalse("3569e15d76bcb0c1062f1c242e1d263ca6b6411d" in TelemetrySampler.SUPPORTED_GB_SHA1S)
    }
}
