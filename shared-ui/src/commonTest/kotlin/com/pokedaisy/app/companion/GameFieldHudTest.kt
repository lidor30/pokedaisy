package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.ui.fieldHudShown
import kotlin.test.*

class GameFieldHudTest {
    private val field = SnapshotView(connected = true, game = GameKind.EMERALD,
        location = LocationView("Test field", null, 0, 0, 0, 0))

    @Test fun requiresEnabledLandscapeAndSettledField() {
        assertTrue(fieldHudShown(true, false, true, field))
        assertFalse(fieldHudShown(false, false, true, field))
        assertFalse(fieldHudShown(true, true, true, field))
        assertFalse(fieldHudShown(true, false, false, field))
    }

    @Test fun gameTransitionsCannotExposeAnOldPartyOverlay() {
        for (snapshot in listOf(field.copy(connected = false), field.copy(game = null),
            field.copy(unsupported = true), field.copy(inBattle = true),
            field.copy(location = LocationView("", null, 0, 0, 0, 0)),
            field.copy(blocked = BlockedGame("Test", emptySet(), emptySet(), emptySet(), "Blocked")))) {
            assertFalse(fieldHudShown(true, false, true, snapshot))
        }
    }

    @Test fun settingsReuseInstalledPreferenceFormatsAcrossHostRecreation() {
        val store = PrefsCompanionSettingsTest.Store()
        val settings = PrefsCompanionSettingsTest.Settings(Prefs(store))
        settings.setFieldHud(false)
        val style = FieldHudStyle(layout = FieldHudLayout.RAILS, look = FieldHudLook.WHITE_GLASS,
            hidden = setOf(FieldHudPart.PC, FieldHudPart.REPEL))
        settings.setFieldHudStyle(style)
        val reopened = PrefsCompanionSettingsTest.Settings(Prefs(store))
        assertFalse(reopened.fieldHud)
        assertEquals(style, reopened.fieldHudStyle)
        reopened.setFieldHud(true)
        assertTrue(settings.fieldHud)
    }
}
