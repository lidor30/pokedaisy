package com.pokedaisy.app

import com.pokedaisy.app.companion.matchesLibrarySearch
import kotlin.test.*

class LibrarySearchTest {
    private val names = listOf("Pokémon FireRed", "1636 - Pokemon - FireRed Version (USA, Europe) (Rev 1).gba")

    @Test fun wordsInAnyOrderAnyCaseNoAccents() {
        assertTrue(matchesLibrarySearch("red fire", names))
        assertTrue(matchesLibrarySearch("POKEMON", names))
        assertTrue(matchesLibrarySearch("pokémon", names))
        assertTrue(matchesLibrarySearch("  fire   ", names))
    }

    @Test fun eachWordCanComeFromEitherName() {
        assertTrue(matchesLibrarySearch("firered rev", names))
    }

    @Test fun aWordInNeitherNameIsNoMatch() {
        assertFalse(matchesLibrarySearch("emerald", names))
        assertFalse(matchesLibrarySearch("fire emerald", names))
    }

    @Test fun nothingTypedMatchesEverything() {
        assertTrue(matchesLibrarySearch("", names))
    }
}
