package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.game.ManualInputGate
import kotlin.test.*

class FieldHudInputCaptureTest {
    @Test fun keptWildAllowsPlayButOpeningAndClosingBagQuarantinesAHeldKey() {
        val input = ManualInputGate()
        val a = 1
        input.setBlocked(ManualInputGate.Owner.COMPANION, fieldHudCapturesInput(FieldHudPanel.WILD, true), a)
        assertEquals(a, input.filter(a))
        input.setBlocked(ManualInputGate.Owner.COMPANION, fieldHudCapturesInput(FieldHudPanel.BAG, true), a)
        assertEquals(0, input.filter(a))
        input.setBlocked(ManualInputGate.Owner.COMPANION, fieldHudCapturesInput(null, true), a)
        assertEquals(0, input.filter(a))
        input.filter(0)
        assertEquals(a, input.filter(a))
    }

    @Test fun unpinningWildCapturesInputAndClosingItCannotReleaseTheLayoutEditor() {
        val input = ManualInputGate()
        input.setBlocked(ManualInputGate.Owner.COMPANION, fieldHudCapturesInput(FieldHudPanel.WILD, false), 0)
        assertEquals(0, input.filter(1))
        input.setBlocked(ManualInputGate.Owner.EDITOR, true, 1)
        input.setBlocked(ManualInputGate.Owner.COMPANION, fieldHudCapturesInput(null, false), 1)
        assertTrue(input.blocked)
        assertEquals(0, input.filter(1))
        input.setBlocked(ManualInputGate.Owner.EDITOR, false, 1)
        assertEquals(0, input.filter(1))
        input.filter(0)
        assertEquals(1, input.filter(1))
    }
}
