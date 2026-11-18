package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.SidePanelState
import org.junit.Assert.*
import org.junit.Test

class SidePanelStateTest {
    @Test fun windowStartsClosedAndOwnsInputOnlyWhileOpen() {
        val closed = SidePanelState().withEnabled(true)
        assertTrue(closed.closed)
        assertFalse(closed.windowOpen)
        assertFalse(closed.overGame)
        assertEquals(0, closed.arrange(1200).gameRightMargin)
        val open = closed.show()
        assertTrue(open.windowOpen)
        assertTrue(open.overGame)
        assertFalse(open.tabsShown)
        assertEquals(0, open.arrange(1200).controlsRightMargin)
        assertEquals(closed, open.hide())
    }

    @Test fun unlockedPanelMovesControlsWhileGameKeepsItsWholeArea() {
        val panel = SidePanelState(window = false).withEnabled(true).show()
        assertTrue(panel.overGame)
        assertFalse(panel.windowOpen)
        assertTrue(panel.tabsShown)
        assertEquals(SidePanelState.Arrangement(600, 0, 600), panel.arrange(1200))
        assertEquals(SidePanelState.Arrangement(600, 0, 0), panel.hide().arrange(1200))
    }

    @Test fun dockingKeepsCompanionBesideGameWithoutTakingGameButtons() {
        val panel = SidePanelState(window = false, docked = true).withEnabled(true)
        assertTrue(panel.shown)
        assertFalse(panel.overGame)
        assertFalse(panel.windowOpen)
        assertEquals(SidePanelState.Arrangement(600, 600, 600), panel.arrange(1200))
        // The close tab can still hide a docked panel. Re-enabling restores it from preferences.
        assertTrue(panel.hide().closed)
        assertTrue(panel.hide().withEnabled(false).withEnabled(true).shown)
    }

    @Test fun windowPreferenceWinsOverSavedDockingChoice() {
        val panel = SidePanelState(window = true, docked = true).withEnabled(true)
        assertTrue(panel.closed)
        assertTrue(panel.show().windowOpen)
        assertEquals(0, panel.show().arrange(1200).gameRightMargin)
    }

    @Test fun disablingReleasesMarginsAndReenablingReadsCurrentPreferences() {
        val panel = SidePanelState(window = false, docked = true).withEnabled(true)
        val off = panel.withEnabled(false, window = true, docked = false)
        assertFalse(off.shown)
        assertFalse(off.closed)
        assertFalse(off.overGame)
        assertFalse(off.windowOpen)
        assertFalse(off.window) // Disabling alone doesn't replace the saved choice.
        assertEquals(0, off.arrange(1200).controlsRightMargin)
        val on = off.withEnabled(true, window = true, docked = false)
        assertTrue(on.isWindow)
        assertTrue(on.closed)
        assertSame(on, on.withEnabled(true, window = false, docked = true))
    }

    @Test fun bookHingeOverridesPreferencesAndKeepsBothPagesClearOfHinge() {
        val panel = SidePanelState().withEnabled(true).withBook(570..630)
        assertTrue(panel.shown)
        assertTrue(panel.isDocked)
        assertFalse(panel.isWindow)
        assertFalse(panel.windowOpen)
        assertFalse(panel.overGame)
        assertFalse(panel.tabsShown)
        assertEquals(SidePanelState.Arrangement(570, 630, 630), panel.arrange(1200))
        assertSame(panel, panel.hide())
        val unfolded = panel.withBook(null)
        assertTrue(unfolded.window)
        assertFalse(unfolded.docked)
        assertTrue(unfolded.closed)
        assertTrue(unfolded.show().windowOpen)
    }

    @Test fun fullScreenTemporarilyOverridesDockedModeAndRestoresIt() {
        val docked = SidePanelState(window = false, docked = true).withEnabled(true)
        val full = docked.withFullScreen(true)
        assertTrue(full.isWindow)
        assertTrue(full.closed)
        assertTrue(full.show().windowOpen)
        assertEquals(docked, full.show().withFullScreen(false))
        val book = full.withBook(600..600)
        assertFalse(book.isWindow)
        assertTrue(book.shown)
    }

    @Test fun choosingAnotherViewDoesNotDismissAnOpenCompanion() {
        val window = SidePanelState().withEnabled(true).show()
        val side = window.copy(window = false)
        assertTrue(side.shown)
        assertFalse(side.windowOpen)
        assertTrue(side.overGame)
        assertTrue(side.copy(docked = true).shown)
        assertFalse(side.copy(docked = true).overGame)
    }

    @Test fun resizeClampsWidthAndSnapsOnlyWhenGameScaleFits() {
        val panel = SidePanelState(window = false, docked = true).withEnabled(true)
        assertEquals(.3f, panel.resized(600, 1000f, 1200).widthFraction, 0f)
        assertEquals(.75f, panel.resized(600, -1000f, 1200).widthFraction, 0f)
        val nearFourTimes = panel.copy(widthFraction = .51f)
        assertEquals(.5f, nearFourTimes.snapped(1920, 1080).widthFraction, 0f)
        assertEquals(nearFourTimes, nearFourTimes.snapped(1920, 600))
        val farFromScale = panel.copy(widthFraction = .55f)
        assertEquals(farFromScale, farFromScale.snapped(1920, 1080))
        val book = panel.withBook(900..1020)
        assertSame(book, book.resized(600, 50f, 1920))
        assertSame(book, book.snapped(1920, 1080))
        assertSame(panel, panel.resized(0, 50f, 0))
        assertSame(panel, panel.snapped(0, 0))
    }
}
