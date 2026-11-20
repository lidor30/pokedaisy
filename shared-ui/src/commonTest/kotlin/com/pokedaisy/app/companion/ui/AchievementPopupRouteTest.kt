package com.pokedaisy.app.companion.ui

import kotlin.test.*

/**
 * GitHub #500: a DS game's touch screen sits where the companion goes, so RetroAchievements' popups played out on a hidden
 * companion. A popup now goes to the companion on screen, else to the host over the game.
 */
class AchievementPopupRouteTest {
    private var touchScreenUp = true
    private val hiddenCompanion: () -> Boolean = { !touchScreenUp }
    private val windowCompanion: () -> Boolean = { false }

    @AfterTest
    fun tearDown() {
        AchievementPopupRoute.remove(hiddenCompanion)
        AchievementPopupRoute.remove(windowCompanion)
    }

    @Test
    fun aHiddenCompanionRoutesPopupsToTheGame() {
        AchievementPopupRoute.add(hiddenCompanion)
        AchievementPopupRoute.add(windowCompanion)
        assertFalse(AchievementPopupRoute.companionSeen())
        assertTrue(AchievementPopupRoute.takes(overGame = true, shown = { true }))
        assertFalse(AchievementPopupRoute.takes(overGame = false, shown = hiddenCompanion))
    }

    @Test
    fun aVisibleCompanionShowsPopups() {
        AchievementPopupRoute.add(hiddenCompanion)
        touchScreenUp = false   // COMPANION on the bottom screen
        assertTrue(AchievementPopupRoute.companionSeen())
        assertTrue(AchievementPopupRoute.takes(overGame = false, shown = hiddenCompanion))
        assertFalse(AchievementPopupRoute.takes(overGame = true, shown = { true }))
    }

    @Test
    fun aGameWithoutACompanionShowsPopups() {
        assertTrue(AchievementPopupRoute.takes(overGame = true, shown = { true }))
    }
}
