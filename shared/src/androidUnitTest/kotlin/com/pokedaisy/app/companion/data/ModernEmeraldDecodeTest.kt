package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE_MODERN_EMERALD (Pokémon Modern Emerald v3.6): a new game played headless (Classic preset), given six Pokémon and a bag
 * through its own givemon / additem and saved in the moving truck (the `modern_emerald` fixtures' READMEs); what each check
 * expects is what the game was given and what its own screens showed.
 */
class ModernEmeraldDecodeTest {
    private val cfg = NATIVE_MODERN_EMERALD

    private fun use(settings: ModernEmerald.Settings = ModernEmerald.Settings()) {
        activeGame = GameKind.EMERALD
        emeraldHack = ModernEmerald.text(settings)
    }

    @After fun tearDown() {
        emeraldHack = null
    }

    @Test
    fun `the save's party, bag, money and place`() {
        use()
        val ram = FixtureMemoryReader.load("modern_emerald")
        assertEquals(ModernEmerald.Settings(trainerId = ModernEmerald.read(ram, cfg).trainerId), ModernEmerald.read(ram, cfg))
        val t = decodeNative("modern_emerald", cfg)
        assertEquals(
            listOf("Treecko" to 5, "Sylveon" to 30, "Gardevoir" to 35, "Marill" to 12, "Gallade" to 40, "Mawile" to 20),
            t.party.map { speciesName(it.species) to it.level },
        )
        assertEquals(200, t.party[1].heldItem)   // LEFTOVERS
        assertTrue(t.party.all { it.hp == it.maxHp && it.maxHp > 0 })
        val bag = t.items.associate { itemName(it.itemId) to (it.quantity to it.pocket) }
        assertEquals(5 to POCKET_ITEMS, bag["Potion"])
        assertEquals(2 to POCKET_ITEMS, bag["Repel"])
        assertEquals(10 to POCKET_POKE_BALLS, bag["Poké Ball"])
        assertEquals(3 to POCKET_BERRIES, bag["Oran Berry"])
        assertEquals(1, t.items.count { it.pocket == POCKET_TM_HM })
        assertPocketsInRange(t)
        assertEquals(3000L, t.money)
        assertEquals(0, t.playerGender)
        assertFalse(t.inBattle)
        // The moving truck (MAP_INSIDE_OF_TRUCK, MAPSEC_INSIDE_OF_TRUCK): Emerald's map ids.
        assertEquals(25 to 40, t.mapGroup to t.mapNum)
        assertEquals(84, t.regionMapSectionId)
    }

    @Test
    fun `a scripted wild battle`() {
        use()
        val ram = FixtureMemoryReader.load("modern_emerald_battle")
        val t = decodeNative("modern_emerald_battle", cfg)
        assertTrue(t.inBattle)
        assertFalse(t.isDoubleBattle)
        val me = t.battleMons[BATTLE_POS_PLAYER_LEFT]
        assertEquals("Treecko" to 5, speciesName(me.species) to me.level)
        val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
        assertEquals("Zigzagoon" to 5, speciesName(foe.species) to foe.level)
        assertEquals(0 to BATTLE_INPUT_ACTION_SELECT, readNativeBattleInputFast(ram, cfg))
        assertTrue(t.enemyParty.isEmpty())
        assertEquals(listOf(288 to 5), t.battleFoes.map { it.species to it.level })
    }

    @Test
    fun `its names, types and chart follow the save's settings`() {
        use()
        assertEquals("Sylveon", speciesName(444))
        assertEquals("Dark Pulse", lookupMove(355).name)
        assertEquals("Timid Mint", itemName(397))
        assertEquals("Fairy", typeName(ModernEmerald.FAIRY))
        assertFalse(movesAreVanilla)
        fun types(species: Int) = activeSpeciesTypeData.getValue(species).let { typeName(it.type1) to typeName(it.type2) }
        // Classic: FAIRY TYPE off keeps GARDEVOIR Psychic (its types_old); new species are Fairy regardless.
        assertEquals("Psychic" to "Psychic", types(394))
        assertEquals("Fairy" to "Fairy", types(444))
        // Gen VI+ chart: Fairy beats Dragon, Dragon doesn't touch it.
        assertEquals(200, activeTypeEffectiveness[18 * 100 + 16])
        assertEquals(0, activeTypeEffectiveness[16 * 100 + 18])
        // FAIRY TYPE on: GARDEVOIR is Psychic / Fairy.
        use(ModernEmerald.Settings(fairyTypes = true))
        assertEquals("Psychic" to "Fairy", types(394))
        // The Modern chart is the other table.
        assertNotEquals(ModernEmerald.typeChart(ModernEmerald.Settings()), ModernEmerald.typeChart(ModernEmerald.Settings(modernChart = true)))
    }

    @Test
    fun `the randomizer's types are its seeded ones`() {
        val plain = ModernEmerald.Settings(trainerId = 0x8750F482)
        val random = plain.copy(randomTypes = true)
        val a = ModernEmerald.speciesTypes(277, random)!!
        assertEquals(a, ModernEmerald.speciesTypes(277, random))   // the same every time for one trainer ID
        assertTrue(a.type1 != 9 && a.type2 != 9)
        // CHAOS draws fresh numbers in the game: nothing to follow, the plain types stay.
        assertEquals(ModernEmerald.speciesTypes(277, plain), ModernEmerald.speciesTypes(277, random.copy(chaos = true)))
    }
}
