package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.ui.GameScreenState
import kotlin.test.*

class GameScreenPreferencesTest {
    @Test fun bothHostDefaultsAndSavedPanelChoicesUseTheSameContract() {
        for (defaults in listOf(Prefs.Defaults(), Prefs.Defaults(sidePanelDocked = true, companionWindow = false))) {
            val store = PrefsCompanionSettingsTest.Store()
            val prefs = Prefs(store, defaults)
            val state = GameScreenState(prefs)
            assertEquals(defaults.sidePanelDocked, state.side.docked)
            assertEquals(defaults.companionWindow, state.side.window)
            state.setSide(state.side.copy(widthFraction = .42f, docked = false), save = true)
            state.setPortrait(state.portrait.copy(ratio = .6f, hidden = true), save = true)
            val reopened = GameScreenState(Prefs(store, defaults))
            assertEquals(.42f, reopened.side.widthFraction); assertFalse(reopened.side.docked)
            assertEquals(.6f, reopened.portrait.ratio); assertTrue(reopened.portrait.hidden)
        }
    }

    @Test fun dsPanelSizesCannotOverwritePortraitChoicesAndCallbacksKeepTheirOwners() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        prefs.portraitCompanionRatio = .7f; prefs.portraitCompanionHidden = true
        var window: Boolean? = null
        var hidden = 0
        val state = GameScreenState(prefs, windowChanged = { window = it }, companionHidden = { hidden++ })
        state.setPortrait(state.portrait.copy(canHide = false, ratio = .4f, hidden = false), save = true)
        assertEquals(.4f, prefs.portraitDsRatio)
        assertEquals(.7f, prefs.portraitCompanionRatio); assertTrue(prefs.portraitCompanionHidden)
        state.setSide(state.side.withEnabled(true).copy(window = true, open = true))
        assertEquals(true, window)
        state.hide(); assertEquals(false, window); assertEquals(1, hidden)
    }
}
