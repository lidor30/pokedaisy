package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.KeyCodes
import kotlin.test.*

class GameButtonRequestTest {
    @Test fun captureWaitsForReleaseAndPublishesTheCanonicalKeyOnce() {
        val saved = mutableListOf<Pair<GbaControls.Btn, String>>()
        var changed = 0
        val settings = object : CompanionSettings by PrefsCompanionSettingsTest.Settings(Prefs(PrefsCompanionSettingsTest.Store())) {
            override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) { saved += btn to keyName }
        }
        val capture = KeyCapture()
        capture.start(settings.gameButtonRequest(GbaControls.Btn.A) { changed++ })
        capture.onKey(KeyCodes.DPAD_UP, true); capture.onKey(KeyCodes.DPAD_UP, false)
        assertTrue(capture.active); assertTrue(saved.isEmpty())
        capture.onKey(KeyCodes.BUTTON_B, true)
        assertTrue(saved.isEmpty())
        capture.onKey(KeyCodes.BUTTON_B, false)
        assertEquals(listOf(GbaControls.Btn.A to "BUTTON_B"), saved)
        assertEquals(1, changed); assertFalse(capture.active)
        capture.onKey(KeyCodes.BUTTON_B, false)
        assertEquals(1, saved.size)
    }

    @Test fun cancellationKeepsBindingsAndClearIsLimitedToOptionalButtons() {
        val cleared = mutableListOf<GbaControls.Btn>()
        var changed = 0
        val settings = object : CompanionSettings by PrefsCompanionSettingsTest.Settings(Prefs(PrefsCompanionSettingsTest.Store())) {
            override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) { fail("Cancelled capture wrote a binding") }
            override fun clearGbaControlBinding(btn: GbaControls.Btn) { cleared += btn }
        }
        assertNull(settings.gameButtonRequest(GbaControls.Btn.A).onClear)
        val capture = KeyCapture()
        capture.start(settings.gameButtonRequest(GbaControls.Btn.TURBO_A) { changed++ })
        capture.onKey(KeyCodes.BUTTON_A, true)
        capture.cancel(); capture.onKey(KeyCodes.BUTTON_A, false)
        assertEquals(0, changed); assertTrue(cleared.isEmpty())
        capture.start(settings.gameButtonRequest(GbaControls.Btn.TURBO_A) { changed++ })
        capture.clear()
        assertEquals(listOf(GbaControls.Btn.TURBO_A), cleared)
        assertEquals(1, changed); assertFalse(capture.active)
    }
}
