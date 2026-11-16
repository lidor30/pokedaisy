package com.pokedaisy.app.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualInputGateTest {
    @Test fun modalOwnsHeldAndNewlyPressedButtonsUntilRelease() {
        val gate = ManualInputGate()
        assertEquals(1, gate.filter(1))
        gate.setBlocked(ManualInputGate.Owner.COMPANION, true, 1)
        assertEquals(0, gate.filter(3))
        gate.setBlocked(ManualInputGate.Owner.COMPANION, false, 3)
        assertEquals(0, gate.filter(3))
        assertEquals(4, gate.filter(7)) // a fresh, unrelated button works
        assertEquals(0, gate.filter(0))
        assertEquals(3, gate.filter(3))
    }
    @Test fun closingAWindowDoesNotReleaseAnEditorsOwnership() {
        val gate = ManualInputGate()
        gate.setBlocked(ManualInputGate.Owner.COMPANION, true, 0)
        gate.setBlocked(ManualInputGate.Owner.EDITOR, true, 1)
        gate.setBlocked(ManualInputGate.Owner.COMPANION, false, 1)
        assertTrue(gate.blocked)
        assertEquals(0, gate.filter(2))
        gate.setBlocked(ManualInputGate.Owner.EDITOR, false, 2)
        assertFalse(gate.blocked)
        assertEquals(0, gate.filter(2))
        gate.filter(0)
        assertEquals(2, gate.filter(2))
    }
    @Test fun quickOpenCloseAndPausedFramesStillQuarantineClosingKeys() {
        val gate = ManualInputGate()
        gate.setBlocked(ManualInputGate.Owner.COMPANION, true, 1)
        gate.setBlocked(ManualInputGate.Owner.COMPANION, false, 3)
        assertEquals(0, gate.filter(3))
        assertEquals(0, gate.filter(2))
        assertEquals(1, gate.filter(3)) // the released bit can be pressed again independently
    }
    @Test fun sustainedTurboHoldDoesNotCountItsOffFramesAsARelease() {
        val gate = ManualInputGate()
        gate.setBlocked(ManualInputGate.Owner.COMPANION, true, 1)
        gate.setBlocked(ManualInputGate.Owner.COMPANION, false, 1)
        repeat(8) { assertEquals(0, gate.filter(1)) }
        gate.filter(0)
        assertEquals(1, gate.filter(1))
    }
}
