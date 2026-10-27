package com.pokedaisy.app

import com.pokedaisy.app.CoopGuards.Hold
import com.pokedaisy.app.CoopGuards.StateBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopGuardsTest {
    @Test fun heldWhileAnyReason() {
        CoopGuards.reset()
        CoopGuards.hold(Hold.LOCK_LINK, true); CoopGuards.hold(Hold.BATTLE, true)
        CoopGuards.hold(Hold.LOCK_LINK, false)
        assertTrue(CoopGuards.held)
        CoopGuards.hold(Hold.BATTLE, false)
        assertFalse(CoopGuards.held)
    }

    @Test fun statesBlockedWhileAnyReason() {
        CoopGuards.reset()
        CoopGuards.blockStates(StateBlock.LOCK, true); CoopGuards.blockStates(StateBlock.BATTLE, true)
        CoopGuards.blockStates(StateBlock.LOCK, false)
        assertTrue(CoopGuards.statesBlocked)
        CoopGuards.blockStates(StateBlock.BATTLE, false)
        assertFalse(CoopGuards.statesBlocked)
    }

    @Test fun statesBlockedByNamesTheReason() {
        CoopGuards.reset()
        CoopGuards.blockStates(StateBlock.BATTLE, true)
        assertTrue(CoopGuards.statesBlocked)
        assertFalse(CoopGuards.statesBlockedBy(StateBlock.LOCK))
        assertTrue(CoopGuards.statesBlockedBy(StateBlock.BATTLE))
        CoopGuards.blockStates(StateBlock.LOCK, true)
        assertTrue(CoopGuards.statesBlockedBy(StateBlock.LOCK))
        CoopGuards.reset()
    }

    @Test fun resetClearsAll() {
        val before = CoopGuards.battleActive
        CoopGuards.battleActive = { true }
        CoopGuards.hold(Hold.LOCK_START, true); CoopGuards.blockStates(StateBlock.LOCK, true)
        CoopGuards.reset()
        assertFalse(CoopGuards.held); assertFalse(CoopGuards.statesBlocked)
        assertTrue(CoopGuards.battleActive())
        CoopGuards.battleActive = before
    }
}
