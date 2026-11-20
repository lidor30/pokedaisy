package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.SharedSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsAvailabilityTest {
    @Test fun visibilitySurvivesRenamingAndOmitsEmptyGroups() {
        val volume = SettingRow("A renamed volume control", "50%", setting = SharedSetting.GAME_VOLUME) {}
        val rows = listOf(groupTitle("Sound"), volume, groupTitle("System"), SettingRow("Other", null) {})
        assertEquals(rows.take(2), onlyRows(rows, setOf(SharedSetting.GAME_VOLUME)))
    }

    @Test fun libraryAndGameExposeDifferentActions() {
        val library = SharedSetting.available(running = false, cheats = false, gba = false)
        assertTrue(SharedSetting.GAME_VOLUME in library)
        assertFalse(SharedSetting.GAME_SPEED in library)
        assertFalse(SharedSetting.CHEATS in library)
        assertFalse(SharedSetting.BOSS_RETRY in library)
        assertEquals(SharedSetting.entries.toSet(), SharedSetting.available(running = true, cheats = true, gba = true))
    }
}
