package com.pokedaisy.app.achievements

import com.pokedaisy.app.companion.AchievementPopup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings > RetroAchievements > NOTIFICATIONS: which popups the two switches let through. */
class PopupSettingsTest {
    @Test
    fun masterOffHidesEveryKind() {
        AchievementPopup.Kind.entries.forEach { assertFalse(popupAllowed(it, notifications = false, progressNotifications = true)) }
    }

    @Test
    fun progressHasItsOwnSwitch() {
        assertFalse(popupAllowed(AchievementPopup.Kind.PROGRESS, notifications = true, progressNotifications = false))
        assertTrue(popupAllowed(AchievementPopup.Kind.UNLOCKED, notifications = true, progressNotifications = false))
        assertTrue(popupAllowed(AchievementPopup.Kind.MASTERED, notifications = true, progressNotifications = false))
        assertTrue(popupAllowed(AchievementPopup.Kind.NOTICE, notifications = true, progressNotifications = false))
    }

    @Test
    fun bothOnShowsEverything() {
        AchievementPopup.Kind.entries.forEach { assertTrue(popupAllowed(it, notifications = true, progressNotifications = true)) }
    }
}
