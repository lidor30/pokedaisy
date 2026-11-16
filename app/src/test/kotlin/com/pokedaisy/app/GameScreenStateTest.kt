package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.*
import org.junit.Assert.*
import org.junit.Test

class GameScreenStateTest {
    @Test fun hiddenOrDisabledCompanionsReleaseNativeFocusButDsStaysVisible() {
        var releases = 0
        val state = GameScreenState(side = SidePanelState(window = false, docked = true).withEnabled(true),
            companionHidden = { releases++ })
        state.hide()
        assertEquals(1, releases)
        state.hide()
        assertEquals(1, releases)
        state.setPortrait(PortraitPanelState(enabled = true))
        state.togglePortrait()
        assertEquals(2, releases)
        state.setPortrait(state.portrait.copy(canHide = false))
        state.setPortrait(state.portrait.copy(enabled = false))
        assertEquals(3, releases)
    }
    @Test fun modalOwnershipChangesImmediatelyAndOnlyAtWindowTransitions() {
        val owned = mutableListOf<Boolean>()
        val state = GameScreenState(side = SidePanelState().withEnabled(true), windowChanged = owned::add)
        state.show()
        assertEquals(listOf(true), owned)
        state.show()
        state.setSide(state.side.copy(widthFraction = .6f))
        assertEquals(listOf(true), owned)
        state.hide()
        assertEquals(listOf(true, false), owned)
        state.setSide(state.side.copy(window = false))
        state.show()
        state.toggleDock()
        assertEquals(listOf(true, false), owned)
    }

    @Test fun savedPanelChoicesAreIndependentOfTransientOpenClose() {
        val savedSide = mutableListOf<SidePanelState>()
        val savedPortrait = mutableListOf<PortraitPanelState>()
        val state = GameScreenState(side = SidePanelState(window = false).withEnabled(true),
            saveSide = savedSide::add, savePortrait = savedPortrait::add)
        state.show(); state.hide()
        assertTrue(savedSide.isEmpty())
        state.toggleDock()
        assertTrue(savedSide.single().docked)
        state.togglePortrait()
        assertTrue(savedPortrait.single().hidden)
        assertTrue(savedSide.single().docked)
    }

    @Test fun portraitUsesTheExistingGripAndPadStripRules() {
        for (under in listOf(false, true)) for (expand in listOf(false, true)) for (hidden in listOf(false, true)) {
            val p = PortraitPanelState(enabled = true, underGame = under, expand = expand, hidden = hidden, padStrip = 250)
            val f = gameScreenFrames(400, 1000, 267, 28, SidePanelState(), p)
            val native = PortraitLayout.arrange(400, 1000, 267, 28, p.ratio, under, expand, 250, hidden)
            assertEquals(native.gameAreaBottom.toFloat(), f.game.bottom, 0f)
            assertEquals(native.padTop.toFloat(), f.controls.top, 0f)
            assertEquals(native.padBottom.toFloat(), f.controls.bottom, 0f)
            assertEquals(!hidden, f.companionShown)
            assertTrue(f.companion.height > 0) // hidden UI retains a measurable place, without being placed
            assertEquals(native.gripTop, f.portrait!!.gripTop)
        }
    }

    @Test fun dsCannotBeHiddenAndTabletopAlwaysUsesBothPages() {
        val ds = PortraitPanelState(enabled = true, hidden = true, canHide = false)
        assertTrue(gameScreenFrames(400, 1000, 300, 28, SidePanelState(), ds).companionShown)
        val folded = gameScreenFrames(1200, 800, 800, 28, SidePanelState(), ds.copy(hinge = 360..400))
        assertEquals(360f, folded.game.bottom, 0f)
        assertEquals(folded.game, folded.controls)
        assertEquals(400f, folded.companion.top, 0f)
        assertEquals(800f, folded.companion.bottom, 0f)
        assertNull(folded.portrait) // no resize grip across a hinge
    }

    @Test fun windowAndSidePanelsKeepTheirOwnNativeViewportExclusions() {
        for (window in listOf(false, true)) for (docked in listOf(false, true)) for (open in listOf(false, true)) {
            val side = SidePanelState(enabled = true, window = window, docked = docked, open = open, widthFraction = .4f)
            val f = gameScreenFrames(1200, 700, 800, 0, side, PortraitPanelState())
            val a = side.arrange(1200)
            assertEquals((1200 - a.gameRightMargin).toFloat(), f.game.right, 0f)
            assertEquals((1200 - a.controlsRightMargin).toFloat(), f.controls.right, 0f)
            assertEquals(open, f.companionShown)
            assertTrue(f.companion.width > 0)
            assertEquals(if (window) 0f else 720f, f.companion.left, 0f)
        }
    }

    @Test fun bookLayoutReservesHingeAndZeroSizeFramesStayBounded() {
        val side = SidePanelState().withEnabled(true).withBook(330..370)
        val book = gameScreenFrames(700, 1200, 467, 28, side, PortraitPanelState())
        assertEquals(330f, book.game.right, 0f)
        assertEquals(370f, book.companion.left, 0f)
        for ((w, h) in listOf(0 to 0, 180 to 120, 120 to 180, 400 to 400)) {
            for (p in listOf(PortraitPanelState(), PortraitPanelState(enabled = true), PortraitPanelState(enabled = true, hidden = true))) {
                val f = gameScreenFrames(w, h, 267, 28, side, p)
                for (r in listOf(f.game, f.controls, f.companion)) {
                    assertTrue("$r in $w x $h", r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h)
                    assertTrue(r.width >= 0 && r.height >= 0)
                }
            }
        }
    }
}
