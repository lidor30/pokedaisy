package com.pokedaisy.app.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualInputGateTest {
    @Test fun aClosingAimingStickMustCentreBeforeAnyDirectionCanPlay() {
        val gate = ManualInputGate()
        gate.quarantineUntilNeutral()
        assertEquals(0, gate.filter(1, neutral = false))
        assertEquals(0, gate.filter(2, neutral = false)) // Turning a held stick is not a fresh press.
        assertEquals(0, gate.filter(0, neutral = false)) // A diagonal can project to no game direction.
        assertTrue(gate.awaitingNeutral)
        assertEquals(0, gate.filter(0, neutral = true))
        assertFalse(gate.awaitingNeutral)
        assertEquals(2, gate.filter(2, neutral = false))
    }
    @Test fun aMenusOwnShortcutStillYieldsToEditorsCaptureAndBackground() {
        val gate = ManualInputGate()
        gate.setBlocked(ManualInputGate.Owner.COMPANION, true, 0)
        assertFalse(gate.blockedExcept(ManualInputGate.Owner.COMPANION))
        for (owner in listOf(ManualInputGate.Owner.EDITOR, ManualInputGate.Owner.BINDING, ManualInputGate.Owner.BACKGROUND)) {
            gate.setBlocked(owner, true, 1)
            assertTrue(gate.blockedExcept(ManualInputGate.Owner.COMPANION))
            gate.setBlocked(owner, false, 1)
            assertFalse(gate.blockedExcept(ManualInputGate.Owner.COMPANION))
        }
        assertTrue(gate.blocked)
        assertEquals(0, gate.filter(1))
    }
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
