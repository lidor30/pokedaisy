package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.PortraitLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PortraitPanel's sizes on a Pixel 6-class phone (1080x2400, the game 720 tall), and controller D-pad diagonals. */
class PortraitLayoutTest {
    private val w = 1080
    private val h = 2400
    private val game = 720

    @Test
    fun companionKeepsItsShapeAndStaysOffTheGame() {
        assertEquals(941, PortraitLayout.companionHeight(w, h, game, PortraitLayout.DEFAULT_RATIO))
        assertEquals(648, PortraitLayout.companionHeight(w, h, game, 0.1f))      // no smaller than MIN_RATIO
        assertEquals(h - game, PortraitLayout.companionHeight(w, h, game, 10f))  // never over the game
    }

    @Test
    fun dragsSnapToTheDefaultAndTheLargest() {
        val max = PortraitLayout.maxRatio(w, h, game)
        assertEquals(PortraitLayout.DEFAULT_RATIO, PortraitLayout.snap(w, h, game, PortraitLayout.DEFAULT_RATIO + 40f / w), 0f)
        assertEquals(max, PortraitLayout.snap(w, h, game, max - 30f / w), 0f)
        assertEquals(1.2f, PortraitLayout.snap(w, h, game, 1.2f), 0f)
    }

    @Test
    fun tapsStepThroughTheSizes() {
        val max = PortraitLayout.maxRatio(w, h, game)
        assertEquals(PortraitLayout.DEFAULT_RATIO, PortraitLayout.next(w, h, game, PortraitLayout.MIN_RATIO), 0f)
        assertEquals(max, PortraitLayout.next(w, h, game, PortraitLayout.DEFAULT_RATIO), 0f)
        assertEquals(PortraitLayout.MIN_RATIO, PortraitLayout.next(w, h, game, max), 0f)
    }

    @Test
    fun touchPadTakesTheGapOnlyWhenItFits() {
        assertTrue(PortraitLayout.padInGap(w, h - game - 941))
        assertFalse(PortraitLayout.padInGap(w, 100))
    }

    @Test
    fun gripNeverCoversTheGame() {
        val grip = 63
        // Along the bottom, full height: the grip keeps its strip under the game, the companion everything below it.
        val full = PortraitLayout.arrange(w, h, game, grip, 10f, underGame = false)
        assertEquals(PortraitLayout.Grip.ABOVE, full.grip)
        assertEquals(game, full.gripTop)
        assertEquals(h - game - grip, full.companionHeight)
        assertEquals(0, full.topInset(grip))
        // Only a screen too short for the smallest companion and the grip puts it inside the companion's top.
        val short = PortraitLayout.arrange(w, 1400, game, grip, 10f, underGame = false)
        assertEquals(PortraitLayout.Grip.INSIDE, short.grip)
        assertEquals(game, short.gameBottom)
        assertEquals(grip, short.topInset(grip))
        // Default size: above the companion, the touch pad in the gap over it.
        val normal = PortraitLayout.arrange(w, h, game, grip, PortraitLayout.DEFAULT_RATIO, underGame = false)
        assertEquals(PortraitLayout.Grip.ABOVE, normal.grip)
        assertEquals(h - 941 - grip, normal.gripTop)
        assertEquals(game, normal.padTop)
        assertEquals(normal.gripTop, normal.padBottom)
    }

    @Test
    fun wideFillsEverythingAboveTheCompanion() {
        val grip = 63
        // ASPECT > WIDE: the game's area runs down to the grip, the pad over the game - wherever COMPANION puts it.
        for (under in listOf(false, true)) {
            val a = PortraitLayout.arrange(w, h, game, grip, PortraitLayout.DEFAULT_RATIO, underGame = under, expand = true)
            assertEquals(h - 941, a.companionTop)
            assertEquals(h - 941 - grip, a.gameAreaBottom)
            assertEquals(0, a.padTop)
            assertEquals(a.gameAreaBottom, a.padBottom)
        }
    }

    @Test
    fun wideGivesTheCompanionTheRoomOriginalDoes() {
        // GitHub #136, a Galaxy Z Fold 8 upright: WIDE is always along the bottom, and the grip went inside the companion
        // at its largest, 32 px less for its content than ORIGINAL under the game - the battle move cards lost a line.
        val fw = 1848
        val fh = 2448
        val fgame = 1264
        val grip = 72
        val r = 1152f / fw
        val wide = PortraitLayout.arrange(fw, fh, fgame, grip, r, underGame = false, expand = true)
        val original = PortraitLayout.arrange(fw, fh, fgame, grip, r, underGame = true)
        assertEquals(PortraitLayout.Grip.ABOVE, wide.grip)
        assertEquals(original.companionHeight - original.topInset(grip), wide.companionHeight - wide.topInset(grip))
        assertEquals(fh - fgame - grip, wide.companionHeight)
    }

    @Test
    fun underTheGameThePadTakesTheBottom() {
        val grip = 63
        val a = PortraitLayout.arrange(w, h, game, grip, PortraitLayout.DEFAULT_RATIO, underGame = true)
        assertEquals(game, a.companionTop)
        assertEquals(PortraitLayout.Grip.BELOW, a.grip)
        assertEquals(game + 941, a.gripTop)
        assertEquals(game + 941 + grip, a.padTop)
        assertEquals(h, a.padBottom)
        // Full height leaves the grip its strip at the bottom.
        val full = PortraitLayout.arrange(w, h, game, grip, 10f, underGame = true)
        assertEquals(h - game - grip, full.companionHeight)
        assertEquals(h - grip, full.gripTop)
        // With the controls showing, their strip stays free even at full height.
        val fullPad = PortraitLayout.arrange(w, h, game, grip, 10f, underGame = true, padStrip = 400)
        assertEquals(h - 400, fullPad.padTop)
        assertEquals(h - 400 - grip, fullPad.gripTop)
    }

    @Test
    fun underTheGameWithNoGapTheControlsStillGetTheBottom() {
        // A 16:10 tablet with a DS game's 4:3 (900 tall): the companion under the game left no gap, and the controls
        // lay over the game. Their strip is kept at the bottom; the companion gives way first, then the game.
        val tw = 1200
        val th = 1920
        val ds = 900
        val grip = 63
        val strip = 450
        val a = PortraitLayout.arrange(tw, th, ds, grip, PortraitLayout.DEFAULT_RATIO, underGame = true, padStrip = strip)
        assertEquals(th - strip, a.padTop)
        assertEquals(th, a.padBottom)
        assertEquals(a.padTop, a.gripTop + grip)
        assertEquals(a.gameBottom, a.companionTop)
        assertTrue(a.companionHeight >= (tw * PortraitLayout.MIN_RATIO).toInt())
        assertTrue(a.gameBottom <= ds)
        // No controls: as before (nothing reserved).
        val none = PortraitLayout.arrange(tw, th, ds, grip, PortraitLayout.DEFAULT_RATIO, underGame = true)
        assertEquals(0, none.padTop)
        assertEquals(none.gameBottom, none.padBottom)
    }

    @Test
    fun hiddenLeavesOnlyTheGripAlongTheBottom() {
        val grip = 60
        val a = PortraitLayout.arrange(w, h, game, grip, PortraitLayout.DEFAULT_RATIO, underGame = false, hidden = true)
        assertEquals(0, a.companionHeight)
        assertEquals(h - grip, a.gripTop)
        assertEquals(game, a.gameBottom)
        assertEquals(game, a.padTop)      // the touch controls take the room under the game
        assertEquals(h - grip, a.padBottom)
        // EXPAND: the game takes it all, the grip at the bottom.
        val wide = PortraitLayout.arrange(w, h, h, grip, PortraitLayout.DEFAULT_RATIO, underGame = false, expand = true, hidden = true)
        assertEquals(h - grip, wide.gameAreaBottom)
        // EXPAND + UNDER GAME: the touch controls keep their strip, under the grip.
        val strip = PortraitLayout.arrange(w, h, h, grip, PortraitLayout.DEFAULT_RATIO, underGame = true, expand = true, padStrip = 300, hidden = true)
        assertEquals(h - 300, strip.padTop)
        assertEquals(h - 300 - grip, strip.gripTop)
    }

    @Test
    fun diagonalKeysPressBothDirections() {
        val input = GbaInput()
        input.onKey(android.view.KeyEvent.KEYCODE_DPAD_UP_LEFT, true)
        assertEquals(MgbaCore.Key.UP or MgbaCore.Key.LEFT, input.mask)
        input.onKey(android.view.KeyEvent.KEYCODE_DPAD_UP_LEFT, false)
        assertEquals(0, input.mask)
    }
}
