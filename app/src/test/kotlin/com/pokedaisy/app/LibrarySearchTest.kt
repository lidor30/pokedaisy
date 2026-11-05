package com.pokedaisy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private val names = listOf("Pokémon FireRed", "1636 - Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")

    @Test fun `words in any order, any case, no accents`() {
        assertTrue(matchesSearch("red fire", names))
        assertTrue(matchesSearch("POKEMON", names))
        assertTrue(matchesSearch("pokémon", names))
        assertTrue(matchesSearch("  fire   ", names))
    }

    @Test fun `each word can come from either name`() {
        assertTrue(matchesSearch("firered rev", names))
    }

    @Test fun `a word in neither name is no match`() {
        assertFalse(matchesSearch("emerald", names))
        assertFalse(matchesSearch("fire emerald", names))
    }

    @Test fun `nothing typed matches everything`() {
        assertTrue(matchesSearch("", names))
    }
}
