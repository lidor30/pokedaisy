package com.pokedaisy.app

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CoopGuardsEngineTest {
    @Test fun noFrameWhileHeld() { assertFalse(EmulatorEngine.shouldRunFrame(held = true)); assertTrue(EmulatorEngine.shouldRunFrame(false)) }
    @Test fun slotLoadRefusedWhileBlocked() { assertTrue(EmulatorEngine.refuseLoad(statesBlocked = true, isResume = false)) }
    @Test fun resumeLoadNeverRefused() { assertFalse(EmulatorEngine.refuseLoad(true, isResume = true)) }
    @Test fun rewindOffWhileBlocked() { assertFalse(EmulatorEngine.rewindAllowed(true)); assertTrue(EmulatorEngine.rewindAllowed(false)) }
    @Test fun stopAndSaveWinOverHold() {
        assertEquals(LoopAction.STOP, EmulatorEngine.loopAction(true, true, true))
        assertEquals(LoopAction.SAVE_STATE, EmulatorEngine.loopAction(true, false, true))
        assertEquals(LoopAction.HOLD, EmulatorEngine.loopAction(true, false, false))
        assertEquals(LoopAction.RUN, EmulatorEngine.loopAction(false, false, false))
        assertEquals(LoopAction.RUN, EmulatorEngine.loopAction(false, true, true))
    }
}
