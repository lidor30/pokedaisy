package com.pokedaisy.app

import com.pokedaisy.app.companion.*
import com.pokedaisy.app.companion.ui.touchControlsRows
import org.junit.Assert.*
import org.junit.Test

class TouchControlsHostTest {
    @Test fun limitedHostsOfferWorkingActionsAndPreserveOtherChoices() {
        val available = TouchButton.entries.toSet() - setOf(TouchButton.X, TouchButton.Y,
            TouchButton.QUICK_MENU, TouchButton.ZOOM, TouchButton.REWIND)
        val original = TouchControlsStyle(extras = TouchButton.extras.toSet())
        var stored = original
        val rows = touchControlsRows(original, set = { stored = it }, onEdit = {}, buttons = available)
        val labels = rows.map { it.label }
        assertTrue(labels.containsAll(listOf("FF", "SAVE STATE", "LOAD STATE", "TURBO A", "TURBO B")))
        assertFalse(labels.any { it in listOf("QUICK MENU", "ZOOM", "REWIND", "X AND Y", "GBA SKIN") })
        rows.first { it.label == "LOOK" }.onClick()
        assertNotEquals(original.look, stored.look)
        assertEquals(original.extras, stored.extras)
        assertTrue(stored.xyButtons)
        val drawn = stored.forButtons(available)
        assertFalse(drawn.xyButtons)
        assertEquals(setOf(TouchButton.FF, TouchButton.TURBO_A, TouchButton.TURBO_B,
            TouchButton.SAVE_STATE, TouchButton.LOAD_STATE), drawn.extras)
    }
}
