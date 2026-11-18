package com.pokedaisy.app.companion.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameScreenGripTest {
    private fun constrained(canHide: Boolean = true, save: (PortraitPanelState) -> Unit = {}) =
        GameScreenState(portrait = PortraitPanelState(enabled = true, underGame = true, padStrip = 600, canHide = canHide),
            savePortrait = save).apply { width = 1080; height = 1800; gameHeight = 1080; gripHeight = 28 }

    @Test fun tappingTheOnlyAvailableHeightHidesThenRestoresAndPersists() {
        val saved = mutableListOf<Boolean>()
        val state = constrained { saved += it.hidden }
        state.stepPortrait()
        assertTrue(state.portrait.hidden)
        state.stepPortrait()
        assertFalse(state.portrait.hidden)
        assertEquals(listOf(true, false), saved)
    }

    @Test fun aDsTouchScreenStaysVisibleAtTheOnlyAvailableHeight() {
        val state = constrained(canHide = false)
        repeat(4) { state.stepPortrait(); assertFalse(state.portrait.hidden) }
    }
}
