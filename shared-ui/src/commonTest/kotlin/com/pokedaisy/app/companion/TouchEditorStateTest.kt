package com.pokedaisy.app.companion

import androidx.compose.ui.geometry.Rect
import com.pokedaisy.app.companion.ui.TouchEditorState
import kotlin.test.*

class TouchEditorStateTest {
    private fun place(n: Float) = TouchPlace(TouchPlace.Anchor.START, n, TouchPlace.Anchor.END, n)
    private fun stage(portrait: Boolean = true, gb: Boolean = false) =
        TouchStage(100f, 200f, 1f, portrait, Rect(0f, 0f, 100f, 80f), Rect(0f, 80f, 100f, 200f), gameBoy = gb)

    @Test fun doneKeepsTheOtherOrientationAndCurrentSettingsAndClosesExactlyOnce() {
        val other = TouchLayout(mapOf(TouchButton.B to place(20f)))
        var style = TouchControlsStyle(landscape = other)
        var closed = 0
        val editor = TouchEditorState({ style }, { style = it }, { closed++ })
        assertTrue(editor.open(stage())); assertFalse(editor.open(stage(false)))
        style = style.copy(opacity = 25)
        editor.done(TouchLayout(mapOf(TouchButton.A to place(10f))))
        assertFalse(editor.isOpen); assertEquals(1, closed)
        assertEquals(place(10f), style.portrait.places[TouchButton.A])
        assertEquals(other, style.landscape); assertEquals(25, style.opacity); assertTrue(style.useCustom)
        editor.dismiss(); editor.done(TouchLayout()); assertEquals(1, closed)
        assertTrue(editor.open(stage(false)))
        editor.done(TouchLayout(mapOf(TouchButton.B to place(30f))))
        assertEquals(2, closed); assertEquals(place(10f), style.portrait.places[TouchButton.A])
    }

    @Test fun unavailableButtonsKeepTheirStoredPositionsAndRawCapabilities() {
        var style = TouchControlsStyle(useCustom = true, extras = setOf(TouchButton.FF, TouchButton.QUICK_MENU),
            portrait = TouchLayout(mapOf(TouchButton.L to place(5f), TouchButton.QUICK_MENU to place(15f))))
        val editor = TouchEditorState({ style }, { style = it })
        editor.open(stage(gb = true), TouchButton.entries.toSet() - TouchButton.QUICK_MENU)
        editor.done(TouchLayout(mapOf(TouchButton.A to place(25f))))
        assertEquals(place(5f), style.portrait.places[TouchButton.L])
        assertEquals(place(15f), style.portrait.places[TouchButton.QUICK_MENU])
        assertEquals(place(25f), style.portrait.places[TouchButton.A])
        assertTrue(TouchButton.QUICK_MENU in style.extras)
    }

    @Test fun dismissDoesNotWriteAndFailedStorageKeepsTheEditorOpenForRetry() {
        var writes = 0
        var closed = 0
        var fail = true
        val editor = TouchEditorState({ TouchControlsStyle() }, {
            if (fail) error("unavailable storage")
            writes++
        }, { closed++ })
        editor.open(stage()); editor.dismiss(); editor.dismiss()
        assertEquals(0, writes); assertEquals(1, closed)
        editor.open(stage())
        assertFails { editor.done(TouchLayout()) }
        assertTrue(editor.isOpen); assertEquals(1, closed)
        fail = false; editor.done(TouchLayout())
        assertFalse(editor.isOpen); assertEquals(1, writes); assertEquals(2, closed)
    }
}
