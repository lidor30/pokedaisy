package com.pokedaisy.app.game

import com.pokedaisy.app.companion.data.CoreKey
import com.pokedaisy.app.companion.data.KeyCodes
import platform.GameController.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GameControllerBindingsTest {
    @Test fun snapshotFaceButtonsAndTriggersHaveDistinctCanonicalKeys() {
        val pad = assertNotNull(GCController.controllerWithExtendedGamepad().extendedGamepad)
        pad.buttonA.setValue(1f)
        pad.buttonX.setValue(1f)
        pad.leftShoulder.setValue(1f)
        pad.leftTrigger.setValue(1f)
        assertEquals(setOf(KeyCodes.BUTTON_A, KeyCodes.code("BUTTON_X")!!, KeyCodes.BUTTON_L1, KeyCodes.BUTTON_L2), pad.canonicalKeys())
        pad.leftShoulder.setValue(0f)
        assertEquals(setOf(KeyCodes.BUTTON_A, KeyCodes.code("BUTTON_X")!!, KeyCodes.BUTTON_L2), pad.canonicalKeys())
    }

    @Test fun nativeUpIsConvertedToSharedNegativeYAndDpadOverridesStick() {
        val pad = assertNotNull(GCController.controllerWithExtendedGamepad().extendedGamepad)
        pad.leftThumbstick.setValueForXAxis(1f, yAxis = 1f)
        assertEquals(CoreKey.RIGHT or CoreKey.UP, pad.canonicalAxes())
        pad.dpad.setValueForXAxis(-1f, yAxis = 0f)
        assertEquals(CoreKey.LEFT or CoreKey.UP, pad.canonicalAxes())
    }
}
