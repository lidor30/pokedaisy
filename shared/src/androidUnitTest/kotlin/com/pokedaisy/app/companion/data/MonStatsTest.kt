package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IVs / EVs / nature off the fixtures' party structs. Each value here was
 * checked by recomputing the slot's stored stats from the ROM's base stats
 * (equal, or 1 below where EVs were gained since the last level-up - the game
 * only recomputes stats then).
 */
class MonStatsTest {
    @Test
    fun `retail FireRed - encrypted substructs`() {
        activeGame = GameKind.FIRERED
        val charizard = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1).party[3]
        assertEquals(6, charizard.species)
        val s = charizard.stats!!
        assertEquals(listOf(16, 14, 0, 30, 25, 14), s.ivs)
        assertEquals(listOf(64, 86, 104, 174, 64, 18), s.evs)
        assertEquals(24, s.nature) // Quirky
        assertEquals(listOf(165, 114, 103, 152, 144, 106), s.stats)
    }

    @Test
    fun `Unbound - plaintext substructs`() {
        activeGame = GameKind.UNBOUND
        val s = decodeNative("unbound", NATIVE_UNBOUND).party[0].stats!!
        assertEquals(listOf(5, 8, 21, 16, 31, 26), s.ivs)
        assertEquals(listOf(0, 2, 1, 0, 0, 0), s.evs)
        assertEquals(1, s.nature) // Lonely
    }

    @Test
    fun `Emerald Rogue - 104-byte party mons`() {
        activeGame = GameKind.EMERALD_ROGUE
        val s = decodeNative("emerald_rogue", NATIVE_EMERALD_ROGUE).party[0].stats!!
        assertEquals(listOf(25, 23, 5, 16, 14, 14), s.ivs)
    }

    @Test
    fun `SoulGold's shorter struct gets none`() {
        activeGame = GameKind.SOULGOLD
        assertNull(decodeNative("soulgold", NATIVE_SOULGOLD).party[0].stats)
    }

    @Test
    fun `expansion Mint - hidden nature is XORed in`() {
        val raw = ByteArray(MON_STRUCT_SIZE)
        raw[0] = 3 // personality 3: Adamant
        raw[0x12] = ((5 xor 3) shl 3 or 2).toByte() // language 2, Minted to Bold (5)
        raw[0x20] = 1 // plaintext species 1
        activeGame = GameKind.LAZARUS
        assertEquals(5, decodePartyMon(raw, 0)!!.stats!!.nature)
    }

    @Test
    fun `the ability bit - read, and named only where the engine has no hidden abilities`() {
        activeGame = GameKind.FIRERED
        val party = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1).party
        // The IV word's top bit: KADABRA (64) is on its second ability; 412 is an egg (the summary leaves it out).
        assertEquals(listOf(0, 1, 0, 0, 1, 0), party.map { it.stats?.abilityNum })
        assertTrue(abilitySlotReadable(POKEDEX_FIRERED_REV1, GameKind.FIRERED))
        assertTrue(abilitySlotReadable(POKEDEX_EMERALD, GameKind.EMERALD))
        // CFRU keeps hidden abilities apart, the expansion has a two-bit slot: the bit alone could name the wrong one.
        assertFalse(abilitySlotReadable(POKEDEX_UNBOUND, GameKind.UNBOUND))
        assertFalse(abilitySlotReadable(POKEDEX_RADICAL_RED, GameKind.RADICAL_RED))
        assertFalse(abilitySlotReadable(POKEDEX_IMPERIUM, GameKind.IMPERIUM))
        assertFalse(abilitySlotReadable(POKEDEX_EMERALD, GameKind.LAZARUS))
        // The game's rule: slot 1 with no second ability is the first.
        assertEquals("B", abilityForSlot(listOf("A", "B"), 1))
        assertEquals("A", abilityForSlot(listOf("A"), 1))
        assertEquals("A", abilityForSlot(listOf("A", ""), 1))
        assertNull(abilityForSlot(emptyList(), 0))
    }

    @Test
    fun `nature raises and lowers`() {
        assertEquals(STAT_ATK, natureRaises(3)) // Adamant
        assertEquals(STAT_SPATK, natureLowers(3))
        assertEquals(STAT_SPEED, natureRaises(10)) // Timid
        assertEquals(STAT_ATK, natureLowers(10))
        assertNull(natureRaises(24)) // Quirky
        assertNull(natureLowers(0)) // Hardy
    }

    @Test
    fun `hidden power`() {
        activeGame = GameKind.FIRERED
        assertEquals("Dark", hiddenPowerType(List(6) { 31 }))
        assertEquals(70, hiddenPowerPower(List(6) { 31 }))
        assertEquals("Fighting", hiddenPowerType(List(6) { 0 }))
        assertEquals(30, hiddenPowerPower(List(6) { 0 }))
        // Attack and Defense 30, the rest 31: the classic Hidden Power Ice 70.
        assertEquals("Ice", hiddenPowerType(listOf(31, 30, 30, 31, 31, 31)))
        assertEquals(70, hiddenPowerPower(listOf(31, 30, 30, 31, 31, 31)))
        // Expansion hacks: Gen 6's flat 60, so no power shown.
        activeGame = GameKind.LAZARUS
        assertNull(hiddenPowerPower(List(6) { 31 }))
    }
}
