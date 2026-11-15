package com.pokedaisy.app.companion.data

import org.junit.Assert.*
import org.junit.Test

class AutoRunPolicyTest {
    @Test fun walkingFlipsBAndHoldingBWalks() {
        assertEquals(CoreKey.RIGHT or CoreKey.B, AutoRunPolicy.apply(CoreKey.RIGHT, GameField.KNOWN, true))
        assertEquals(CoreKey.LEFT, AutoRunPolicy.apply(CoreKey.LEFT or CoreKey.B, GameField.KNOWN, true))
    }
    @Test fun noKeyOrUnknownFieldNeverCreatesAPress() {
        assertEquals(0, AutoRunPolicy.apply(0, GameField.KNOWN, true))
        assertEquals(CoreKey.UP, AutoRunPolicy.apply(CoreKey.UP, 0, true))
        assertEquals(CoreKey.UP, AutoRunPolicy.apply(CoreKey.UP, GameField.KNOWN, false))
    }
    @Test fun dialogueBattleBikeSurfAndFreeControlWindowsKeepTheirKeys() {
        for (block in listOf(GameField.LOCKED, GameField.BATTLE, GameField.BIKE, GameField.SURF, GameField.WINDOW)) {
            for (keys in listOf(CoreKey.DOWN, CoreKey.DOWN or CoreKey.B)) {
                assertEquals(keys, AutoRunPolicy.apply(keys, GameField.KNOWN or block, true))
                assertFalse(AutoRunPolicy.walking(keys, GameField.KNOWN or block, true))
            }
        }
    }
    @Test fun sneakingAndBuiltInAutoRunStayInCharge() {
        val sneak = CoreKey.LEFT or CoreKey.A
        assertEquals(sneak, AutoRunPolicy.apply(sneak, GameField.KNOWN, true))
        assertEquals(CoreKey.RIGHT, AutoRunPolicy.apply(CoreKey.RIGHT, GameField.KNOWN, true, builtIn = true))
    }
    @Test fun rubyAndSapphireMapTypesAreNotChangedForIndoorRunning() {
        assertFalse(GameField.supportsRunIndoors("AXVE"))
        assertFalse(GameField.supportsRunIndoors("AXPJ"))
        assertTrue(GameField.supportsRunIndoors("BPRE"))
        assertTrue(GameField.supportsRunIndoors("BPEE"))
    }
}
