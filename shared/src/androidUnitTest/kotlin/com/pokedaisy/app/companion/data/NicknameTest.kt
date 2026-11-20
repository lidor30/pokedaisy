package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The party's nicknames, as the game's own party menu prints them (GitHub #228). */
class NicknameTest {
    @Test
    fun `retail FireRed's party`() {
        activeGame = GameKind.FIRERED
        val t = decodeNative("firered_vanilla", NATIVE_FIRERED_REV1)
        assertTrue(t.party.isNotEmpty())
        // The fixture's Pokémon kept their default names: the species in the game's capitals.
        val mons = t.party.filter { !it.isEgg }
        assertEquals(mons.map { speciesName(it.species).uppercase() }, mons.map { it.nickname })
        // Its egg: Gen 3 keeps an egg's name as Japanese (language 1), so none is read - the UI says EGG anyway.
        assertNull(t.party.single { it.isEgg }.nickname)
    }

    @Test
    fun `SoulGold's 12-letter nicknames`() {
        val t = decodeNative("soulgold_v12", NATIVE_SOULGOLD_V1_2)
        assertEquals(listOf("Cyndaquil"), t.party.map { it.nickname })
    }

    @Test
    fun `a Japanese Pokemon in a Western game falls back to its species`() {
        val raw = ByteArray(MON_STRUCT_SIZE)
        raw[8] = 0x51   // kana in a Western table
        raw[0x12] = 1   // LANGUAGE_JAPANESE
        assertNull(gen3Nickname(raw, 0, VANILLA_PARTY_MON))
    }
}
