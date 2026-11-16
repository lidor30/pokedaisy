package com.pokedaisy.app

import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchPosition
import com.pokedaisy.app.companion.ui.TouchGeometry
import com.pokedaisy.app.companion.ui.SidePanelState
import com.pokedaisy.app.companion.ui.gamePanelLayout
import org.junit.Assert.*
import org.junit.Test

class GamePanelLayoutTest {
    @Test fun panelsFitTabletsAndSmallMultitaskingWindows() {
        for ((w, h) in listOf(744f to 1134f, 1134f to 744f, 1366f to 1024f, 400f to 180f, 180f to 400f, 400f to 400f, 0f to 0f)) {
            for (visible in listOf(true, false)) for (gb in listOf(true, false)) for (under in listOf(true, false)) {
                val stage = gamePanelLayout(w, h, 1f, visible, gameBoy = gb, underGame = under, style = TouchControlsStyle())
                val companion = stage.companion!!
                for (r in listOf(stage.game, stage.area, companion)) {
                    assertTrue("$r in $w x $h", r.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h)
                    assertTrue(r.width >= 0f && r.height >= 0f)
                }
                if (!visible) assertEquals(0f, stage.area.height, 0f)
                if (stage.game.height > 0) assertEquals(if (gb) 160f / 144 else 1.5f, stage.game.width / stage.game.height, 0.001f)
                assertFalse(stage.game.overlaps(companion))
                assertFalse(stage.area.overlaps(companion))
            }
        }
    }

    /** UNDER GAME (the default), as on Android: the controls get their own strip along the bottom, their natural
     * height, and the companion gives up the room - on an iPad mini and an iPhone alike, never over the game. */
    @Test fun uprightUnderGameKeepsAStripAlongTheBottom() {
        val style = TouchControlsStyle()
        for ((w, h) in listOf(744f to 1100f, 402f to 778f, 400f to 1000f)) {
            val stage = gamePanelLayout(w, h, 1f, underGame = true, style = style)
            val companion = stage.companion!!
            assertEquals("$w x $h", h, stage.area.bottom, 0f)
            assertEquals("$w x $h", companion.bottom, stage.area.top, 0.5f)
            assertTrue(stage.area.height >= TouchGeometry.naturalHeight(style, w, 1f) - 1)
            assertFalse(stage.area.overlaps(stage.game))
            assertEquals(stage.game.bottom, companion.top, 0.5f)
        }
    }

    /** AT BOTTOM on a screen with no gap under the game: the controls lie over the game, as on an Android tablet. */
    @Test fun uprightAtBottomWithNoGapLiesOverTheGame() {
        val stage = gamePanelLayout(744f, 1100f, 1f, underGame = false, style = TouchControlsStyle())
        assertEquals(0f, stage.area.top, 0f)
        assertEquals(stage.game.bottom, stage.area.bottom, 0.5f)
        assertEquals(1100f, stage.companion!!.bottom, 0.5f)
        val top = TouchGeometry.place(TouchControlsStyle(position = TouchPosition.TOP), stage.area.width, stage.area.height, 1f, true)
        val bottom = TouchGeometry.place(TouchControlsStyle(position = TouchPosition.BOTTOM), stage.area.width, stage.area.height, 1f, true)
        assertTrue(top.items.minOf { it.top } < bottom.items.minOf { it.top })
    }

    @Test fun sidewaysControlsUseGameAreaWithoutAnExtraStrip() {
        val stage = gamePanelLayout(1100f, 744f, 1f)
        assertEquals(0f, stage.area.top, 0f)
        assertEquals(744f, stage.area.bottom, 0f)
        assertEquals(stage.area.right, stage.companion!!.left, 0f)
        assertEquals(stage.area.center, stage.game.center)
        assertEquals(stage.game, gamePanelLayout(1100f, 744f, 1f, controlsVisible = false).game)
    }

    @Test fun sharedSideLayoutUsesAndroidsGameAndControlsExclusions() {
        for (window in listOf(false, true)) for (docked in listOf(false, true)) for (open in listOf(false, true)) {
            val state = SidePanelState(enabled = true, open = open, window = window, docked = docked, widthFraction = .4f)
            val stage = gamePanelLayout(1200f, 700f, 1f, sidePanel = state)
            val native = state.arrange(1200)
            assertEquals(1200f - native.controlsRightMargin, stage.area.right, 0f)
            assertEquals((1200f - native.gameRightMargin) / 2, stage.game.center.x, .001f)
            when {
                !open -> assertNull(stage.companion)
                window -> assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 1200f, 700f), stage.companion)
                else -> assertEquals(720f, stage.companion!!.left, 0f)
            }
        }
    }

    @Test fun foldedBookReservesTheHingeBetweenGameAndCompanion() {
        val state = SidePanelState().withEnabled(true).withBook(570..630)
        val stage = gamePanelLayout(1200f, 700f, 1f, sidePanel = state)
        assertEquals(570f, stage.area.right, 0f)
        assertTrue(stage.game.right <= 570)
        assertEquals(630f, stage.companion!!.left, 0f)
        val tallBook = gamePanelLayout(700f, 1200f, 1f, sidePanel = state.withBook(330..370))
        assertFalse(tallBook.portrait)
        assertEquals(330f, tallBook.area.right, 0f)
        assertTrue(tallBook.game.right <= 330)
        assertEquals(370f, tallBook.companion!!.left, 0f)
    }

    @Test fun sharedPortraitLayoutRespectsSavedSizeAndHiddenChoice() {
        val small = gamePanelLayout(400f, 1000f, 1f, portraitRatio = .6f)
        val large = gamePanelLayout(400f, 1000f, 1f, portraitRatio = 1.2f)
        assertTrue(small.companion!!.height < large.companion!!.height)
        val hidden = gamePanelLayout(400f, 1000f, 1f, portraitHidden = true, style = TouchControlsStyle())
        assertNull(hidden.companion)
        assertTrue(hidden.game.height > 0)
        assertEquals(1000f, hidden.area.bottom, 0f)
    }
}
