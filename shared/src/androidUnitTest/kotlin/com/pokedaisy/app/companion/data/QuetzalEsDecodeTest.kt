package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quetzal Spanish Alpha 9 v0: English Quetzal's RAM, its own ROM addresses, and names in the
 * languages its IDIOMA options pick (all Spanish on this save) - see the fixtures' READMEs.
 */
class QuetzalEsDecodeTest {
    @After fun reset() {
        quetzalNames = QuetzalNames()
    }

    private fun select(key: String) {
        activeGame = GameKind.QUETZAL
        quetzalNames = readQuetzalNames(FixtureMemoryReader.load(key), NATIVE_QUETZAL_ES)
    }

    @Test
    fun `the save's IDIOMA options`() {
        assertEquals(QuetzalNames(species = 1, moves = 1, items = 1, places = 1),
            readQuetzalNames(FixtureMemoryReader.load("quetzal_es"), NATIVE_QUETZAL_ES))
        // The English saves: English everywhere, and LUGARES' default is the release's own.
        assertEquals(QuetzalNames(), readQuetzalNames(FixtureMemoryReader.load("quetzal"), NATIVE_QUETZAL))
        assertEquals(QuetzalNames(), readQuetzalNames(FixtureMemoryReader.load("quetzal_johto"), NATIVE_QUETZAL))
    }

    @Test
    fun `party, bag, location and money decode, in Spanish`() {
        select("quetzal_es")
        val t = decodeNative("quetzal_es", NATIVE_QUETZAL_ES)
        assertEquals(listOf("Gyarados", "Charizard", "Primeape", "Pidgeot", "Rapidash", "Kadabra"), t.party.map { speciesName(it.species) })
        assertTrue(t.party.all { it.level == 42 })
        assertEquals(listOf("Triturar", "Surf", "Mordisco", "Cascada"), t.party[0].moves.map { lookupMove(it).name })
        // Spanish names over Quetzal's own types / powers.
        assertEquals(moveDataQuetzal.getValue(t.party[0].moves[0]).copy(name = "Triturar"), activeMoveData.getValue(t.party[0].moves[0]))
        val bag = t.items.associate { itemName(it.itemId) to it.quantity }
        assertEquals(3403, bag["Master Ball"])
        assertEquals(1867, bag["Radiante Ball"])
        assertEquals(7, bag["Poción"])
        assertEquals(51, bag["Revivir"])
        assertEquals(3, bag["Cuerda Huida"])
        assertTrue(t.items.any { it.pocket == POCKET_KEY_ITEMS && itemName(it.itemId) == "Bicicleta" })
        assertPocketsInRange(t)
        assertEquals(24 to 13, t.mapGroup to t.mapNum)
        assertEquals(0x4C, t.regionMapSectionId)
        assertEquals("Desfiladero", activeMapSecData[t.regionMapSectionId]!!.name)
        // The guide still matches its English name.
        assertEquals("Jagged Pass", englishMapSecName(t.regionMapSectionId, "Desfiladero"))
        // Past Gen 3's 999,999 - as its START menu shows.
        assertEquals(1_048_458L, t.money)
        assertFalse(t.inBattle)
    }

    @Test
    fun `each option on its own`() {
        activeGame = GameKind.QUETZAL
        quetzalNames = QuetzalNames()
        assertEquals(listOf("Tackle", "Potion", "Type: Null", "Route 1"),
            listOf(lookupMove(33).name, itemName(28), speciesName(772), activeMapSecData.getValue(0x65).name))
        quetzalNames = QuetzalNames(moves = 1)
        assertEquals("Placaje" to "Potion", lookupMove(33).name to itemName(28))
        quetzalNames = QuetzalNames(items = 2) // Latin American Spanish
        assertEquals("Pokébola" to "Poción", itemName(1) to itemName(28))
        quetzalNames = QuetzalNames(species = 1, places = 2)
        assertEquals("Código Cero" to "Ciudad Violeta", speciesName(772) to activeMapSecData.getValue(0x102).name)
    }

    @Test
    fun `a scripted wild battle - its own battle handlers`() {
        select("quetzal_es_battle")
        val ram = FixtureMemoryReader.load("quetzal_es_battle")
        val t = readNativeTelemetry(ram, NATIVE_QUETZAL_ES)
        assertTrue(t.inBattle)
        assertEquals("Gyarados" to 42, t.battleMons[BATTLE_POS_PLAYER_LEFT].let { speciesName(it.species) to it.level })
        assertEquals("Pidgey" to 3, t.battleMons[BATTLE_POS_OPPONENT_LEFT].let { speciesName(it.species) to it.level })
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, NATIVE_QUETZAL_ES))
        // English's handler addresses don't match the Spanish build's code.
        assertEquals(null, readNativeBattleInputFast(ram, NATIVE_QUETZAL)?.takeIf { it.second == BATTLE_INPUT_ACTION_SELECT })
    }
}
