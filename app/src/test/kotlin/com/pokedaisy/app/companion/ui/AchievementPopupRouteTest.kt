package com.pokedaisy.app.companion.ui

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #500: a DS game's touch screen sits where the companion goes, so RetroAchievements' popups played out on a hidden
 * companion. A popup now goes to the companion on screen, else to the host over the game.
 */
class AchievementPopupRouteTest {
    private var touchScreenUp = true
    private val hiddenCompanion: () -> Boolean = { !touchScreenUp }
    private val windowCompanion: () -> Boolean = { false }

    @After
    fun tearDown() {
        AchievementPopupRoute.remove(hiddenCompanion)
        AchievementPopupRoute.remove(windowCompanion)
    }

    @Test
    fun `no companion on screen - the game's host shows it`() {
        AchievementPopupRoute.add(hiddenCompanion)
        AchievementPopupRoute.add(windowCompanion)
        assertFalse(AchievementPopupRoute.companionSeen())
        assertTrue(AchievementPopupRoute.takes(overGame = true, shown = { true }))
        assertFalse(AchievementPopupRoute.takes(overGame = false, shown = hiddenCompanion))
    }

    @Test
    fun `the companion on screen - it shows it, not the game's host`() {
        AchievementPopupRoute.add(hiddenCompanion)
        touchScreenUp = false   // COMPANION on the bottom screen
        assertTrue(AchievementPopupRoute.companionSeen())
        assertTrue(AchievementPopupRoute.takes(overGame = false, shown = hiddenCompanion))
        assertFalse(AchievementPopupRoute.takes(overGame = true, shown = { true }))
    }

    @Test
    fun `no companion at all (one screen, none composed) - the game's host`() {
        assertTrue(AchievementPopupRoute.takes(overGame = true, shown = { true }))
    }
}
