package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FireRedVanillaDecodeTest {
    @Test
    fun `party populated, items span multiple pockets`() {
        val t = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1)
        assertTrue(t.party.isNotEmpty())
        t.party.forEach { assertTrue(it.species > 0) }
        // BoxPokemon +0x08: the stored name - the species name when it wasn't nicknamed (LAPRAS here).
        assertEquals("LAPRAS", t.party[0].nickname)
        assertPocketsInRange(t)
        val pockets = t.items.map { it.pocket }.toSet()
        assertTrue("expected items in >1 pocket, got $pockets", pockets.size > 1)
    }

    @Test
    fun `native gender matches the game's own party screen`() {
        // Computed from PID + genderRatiosFireRed (no struct on a retail ROM).
        // Expected values read off the real game's party menu for this exact
        // save (headless mgba_dump `shot`, 2026-09-27): LAPRAS female,
        // KADABRA male, JOLTEON female, CHARIZARD male, (egg), MEOWTH male.
        activeGame = GameKind.FIRERED
        val t = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1)
        assertEquals(
            listOf(GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE, GENDER_SYMBOL_FEMALE, GENDER_SYMBOL_MALE),
            t.party.take(4).map { it.genderSymbol },
        )
        assertEquals(GENDER_SYMBOL_MALE, t.party[5].genderSymbol)
    }

    @Test
    fun `a nickname is read from the party struct`() {
        // The nickname sits outside the encrypted substructs / checksum, so poking it keeps the mon valid.
        romLanguage = 'E'
        val raw = FixtureMemoryReader.load("firered_vanilla").readCoreMemory(NATIVE_FIRERED_REV1.playerParty, MON_STRUCT_SIZE)
        val name = "SPARKY"
        val encoded = Gen3Text.encode(name)!!
        encoded.copyInto(raw, 0x08)
        raw[0x08 + encoded.size] = 0xFF.toByte()
        assertEquals(name, decodePartyMon(raw, 0)!!.nickname)
    }

    @Test
    fun `native read shows the egg as an egg`() {
        // BoxPokemon +0x13 isEgg flag -> SPECIES_EGG, no gender (the game's
        // party screen for this save shows slot 4 as "EGG").
        activeGame = GameKind.FIRERED
        val t = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1)
        assertEquals(SPECIES_EGG_VANILLA, t.party[4].species)
        assertEquals(GENDER_SYMBOL_NONE, t.party[4].genderSymbol)
        assertEquals(5, t.party.count { it.species != SPECIES_EGG_VANILLA })
    }
}
