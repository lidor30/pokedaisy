package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.MonView
import kotlin.test.*

class BattlePartyIdentityTest {
    private val first = MonView(64, "Kadabra", 36, 81, 81, "", listOf("Psychic"), null, personality = 11)
    private val second = first.copy(personality = 12, nickname = "SECOND")

    @Test fun matchingSpeciesLevelAndHpDoesNotMakeAnotherPokemonAlreadyOut() {
        assertTrue(isOut(first, listOf(first)))
        assertFalse(isOut(second, listOf(first)))
        assertTrue(canSendIn(second, listOf(first)))
        assertFalse(canSendIn(first, listOf(first)))
    }

    @Test fun battleChangesToSpeciesLevelAndHpDoNotChangeKnownIdentity() {
        assertTrue(isOut(first, listOf(first.copy(species = 132, level = 37, maxHp = 85))))
        assertFalse(isOut(second, listOf(first.copy(hp = 1))))
    }

    @Test fun unidentifiedReadersKeepTheExistingCompatibilityFallback() {
        assertTrue(isOut(first.copy(personality = 0), listOf(first)))
        assertTrue(isOut(first, listOf(first.copy(personality = 0))))
        assertFalse(isOut(first, listOf(first.copy(personality = 0, species = 25))))
    }

    @Test fun doublesExcludeBothActiveIdentitiesButNotALookalikeBenchMember() {
        val third = first.copy(personality = 13)
        assertFalse(canSendIn(first, listOf(first, second)))
        assertFalse(canSendIn(second, listOf(first, second)))
        assertTrue(canSendIn(third, listOf(first, second)))
        assertFalse(canSendIn(third.copy(hp = 0), listOf(first, second)))
        assertFalse(canSendIn(third.copy(isEgg = true), listOf(first, second)))
    }
}
