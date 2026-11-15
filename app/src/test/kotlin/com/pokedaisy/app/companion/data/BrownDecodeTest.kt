package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GEN1_BROWN (Pokémon Brown 6.1.2 / 6.0.1): Red's RAM and reader over its own save (the `brown*` fixtures' READMEs), its
 * own tables to Dex No. 252 (gen_gen1_tables.py brown / brown_601). What each check expects is what the game's own party,
 * bag and battle screens showed.
 */
class BrownDecodeTest {
    private fun read(fixture: String, v601: Boolean = false): Telemetry {
        activeGame = GameKind.BROWN
        brownV601 = v601
        return readGen1Telemetry(FixtureMemoryReader.load(fixture), GEN1_BROWN)
    }

    @After fun reset() {
        brownV601 = false
    }

    @Test
    fun `its save - the party, the bag and money in both builds`() {
        assertEquals(6, readGen1Telemetry(FixtureMemoryReader.load("brown"), GEN1_BROWN).party.size)
        for ((fixture, v601) in listOf("brown" to false, "brown_v601" to true)) {
            val t = read(fixture, v601)
            assertFalse(t.inBattle)
            assertEquals(
                listOf("FERALIGATR" to 68, "AERODACTYL" to 33, "OMANYTE" to 35, "KABUTO" to 35, "CHIKORITA" to 12, "CYNDAQUIL" to 11),
                t.party.map { speciesName(it.species) to it.level },
            )
            assertEquals(listOf(160, 142, 138, 140, 152, 155), t.party.map { it.species })
            assertEquals(224 to 224, t.party[0].hp to t.party[0].maxHp)
            assertEquals(listOf("Waterfall", "Crunch", "Mega Kick", "Hydro Pump"), t.party[0].moves.map { lookupMove(it).name })
            assertEquals(77019L, t.money)
            val bag = t.items.associate { itemName(it.itemId) to it.quantity }
            assertEquals(15, bag["Ultra Ball"])
            assertEquals(1, bag["HM01"])
            assertEquals(1, bag["Dubious Disc"])
            assertEquals(15, t.items.size)
            assertEquals(8 to (11 to 7), t.mapNum to (t.x to t.y))
        }
    }

    @Test
    fun `a wild battle - its own species and types, Red's battle menu`() {
        val r = FixtureMemoryReader.load("brown_battle")
        val t = read("brown_battle")
        assertTrue(t.inBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("FERALIGATR" to 68, speciesName(me.species) to me.level)
        assertEquals("CHIKORITA" to 12, speciesName(foe.species) to foe.level)
        assertEquals("Grass" to "Grass", typeName(foe.type1) to typeName(foe.type2))
        assertEquals(BATTLE_INPUT_ACTION_SELECT to 0, readGen1BattleInput(r, GEN1_BROWN))
    }

    @Test
    fun `its tables - Rijon's places, its own types and the 6_0_1 differences`() {
        activeGame = GameKind.BROWN
        assertEquals("PHANCERO", speciesName(252))
        assertEquals("Wood", typeName(28))
        assertEquals("Fairy", typeName(31))
        assertEquals("Gravel Town", activeMapSecData[0]?.name)
        assertEquals("Old Amber", itemName(50))
        assertEquals("Giga Impact", lookupMove(226).name)
        brownV601 = true
        assertEquals("Brick", itemName(50))
        assertEquals("Route 59", activeMapSecData[73]?.name)
    }
}
