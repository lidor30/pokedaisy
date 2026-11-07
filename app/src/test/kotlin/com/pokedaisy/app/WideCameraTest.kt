package com.pokedaisy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WideCameraTest {
    private fun thor() = WideCamera().apply { viewW = 1920; viewH = 1080 }

    /** Runs [seconds] of frames at 60 Hz; returns the last window. */
    private fun WideCamera.run(seconds: Float, close: Boolean = false, start: Long = 1L,
                               scene: Int = if (close) WideCamera.SCENE_TEXT else WideCamera.SCENE_FIELD): WideCamera.Window {
        var t = start
        var w: WideCamera.Window? = null
        repeat((seconds * 60).toInt()) {
            w = frame(scene, t)
            t += 16_666_667L
        }
        return w!!
    }

    @Test fun stepsAreWholePixelsOnTheThor() {
        // 6.75 screen px per GBA px at 1x; 6 shows too little more (180 rows), 2 more than pk_wide's window.
        assertArrayEquals(floatArrayOf(6.75f, 5f, 4f, 3f), WideCamera.levelsFor(1920, 1080), 0.001f)
    }

    @Test fun aGameBoyGameZoomsInItsOwnPixels() {
        // Yellow's 160x144 at the Thor's 1080 rows: 7.5 at 1x, then 6 / 5 / 4 / 3 (7 shows too little more; 2 is wider
        // than pk_wide's window).
        assertArrayEquals(floatArrayOf(7.5f, 6f, 5f, 4f, 3f), WideCamera.levelsFor(1920, 1080, 160, 144), 0.001f)
        val w = thor().apply { frameW = 160; frameH = 144 }.run(0.1f)
        assertEquals(7.5f, w.scale, 0.001f)
        // The frame (0..160) in the middle of a window 1920 / 7.5 = 256 px wide, its full height.
        assertTrue(w.x <= -48 && w.x + w.w >= 208)
        assertEquals(0, w.y)
        assertEquals(144, w.h)
    }

    @Test fun aPhoneUprightGoesOnAboveAndBelow() {
        // 1080 wide, ~1400 for the game above the companion: 1x is the frame's full width (4.5), the map above / below.
        assertArrayEquals(floatArrayOf(4.5f, 3f, 2f), WideCamera.levelsFor(1080, 1400), 0.001f)
        val w = WideCamera().apply { viewW = 1080; viewH = 1400 }.run(0.1f)
        assertEquals(0, w.x)
        assertTrue(w.y < -70 && w.y + w.h > 230)
    }

    @Test fun oneXIsTheGameAtFullHeight() {
        val w = thor().run(0.1f)
        assertEquals(6.75f, w.scale, 0.001f)
        // The frame (0..240) sits in the middle of a window 1920 / 6.75 = 284.4 px wide.
        assertTrue(w.x <= -22 && w.x + w.w >= 262)
        assertEquals(0, w.y)
    }

    @Test fun zoomsOutAndBackInForACloseUp() {
        val cam = thor()
        cam.level = 3
        val out = cam.run(1.5f)   // a start counts as a close-up for half a second
        assertEquals(3f, out.scale, 0.001f)
        assertTrue(out.w in 640..642 && out.h in 360..362)
        val close = cam.run(1f, close = true, start = 2_000_000_000L)
        assertEquals(6.75f, close.scale, 0.001f)
        // Back out only once the close-up has been over for a moment.
        val back = cam.run(1.5f, start = 3_000_000_000L)
        assertEquals(3f, back.scale, 0.001f)
    }

    @Test fun aBattleStartPullsInAsOneSmoothMotionBeforeTheWipe() {
        val cam = thor()
        cam.level = 3
        cam.run(1.5f)
        // The flashes last ~0.8 s: the pull-in is over by then, and never jumps (no frame moves the scale by more
        // than a tenth of the way; the first and last frames move least).
        var t = 2_000_000_000L
        var last = cam.frame(WideCamera.SCENE_BATTLE_START, t)!!.scale
        val steps = ArrayList<Float>()
        repeat(48) {
            t += 16_666_667L
            val s = cam.frame(WideCamera.SCENE_BATTLE_START, t)!!.scale
            steps += s - last
            last = s
        }
        assertEquals(6.75f, last, 0.001f)
        assertTrue("eased, no jump: " + steps, steps.all { it >= 0f && it < 0.375f })
        assertTrue("eases in and out: " + steps, steps.first() < steps[15] && steps.last() < steps[15])
        // A text box pulls in a little quicker, under half a second.
        cam.run(1.5f, start = 4_000_000_000L)
        val text = cam.run(0.45f, start = 6_000_000_000L, scene = WideCamera.SCENE_TEXT)
        assertEquals(6.75f, text.scale, 0.001f)
    }

    @Test fun aFadeBetweenMapsHoldsTheZoom() {
        val cam = thor()
        cam.level = 3
        cam.run(1.5f)
        // A door: half a second of black, then the next map. Zoomed out throughout, no pull-in.
        var t = 2_000_000_000L
        repeat(30) {
            assertEquals(3f, cam.frame(WideCamera.SCENE_FADE, t)!!.scale, 0.001f)
            t += 16_666_667L
        }
        assertEquals(3f, cam.run(0.5f, start = t).scale, 0.001f)
        // And a fade while close up (a battle's end) stays close until the field is really back.
        cam.run(1f, start = 4_000_000_000L, scene = WideCamera.SCENE_OFF)
        assertEquals(6.75f, cam.run(1f, start = 5_000_000_000L, scene = WideCamera.SCENE_FADE).scale, 0.001f)
    }

    @Test fun zoomInOnTextOffLeavesTextZoomedOutButNotMenusOrBattles() {
        val cam = thor()
        cam.level = 3
        cam.zoomOnText = false
        cam.run(1.5f)
        assertEquals(3f, cam.run(1f, start = 2_000_000_000L, scene = WideCamera.SCENE_TEXT).scale, 0.001f)
        assertEquals(6.75f, cam.run(1f, start = 3_000_000_000L, scene = WideCamera.SCENE_MENU).scale, 0.001f)
        assertEquals(6.75f, cam.run(1f, start = 5_000_000_000L, scene = WideCamera.SCENE_BATTLE_START).scale, 0.001f)
    }

    @Test fun aPictureOverTheFieldHoldsTheZoomEitherWay() {
        val cam = thor()
        cam.level = 3
        cam.run(1.5f)
        assertEquals(3f, cam.run(1f, start = 2_000_000_000L, scene = WideCamera.SCENE_OVERLAY).scale, 0.001f)
        cam.run(1f, start = 4_000_000_000L, scene = WideCamera.SCENE_OFF)
        assertEquals(6.75f, cam.run(1f, start = 5_000_000_000L, scene = WideCamera.SCENE_OVERLAY).scale, 0.001f)
    }

    @Test fun lookStaysWhereItsLeftUntilThePlayerWalks() {
        val cam = thor()
        cam.run(1f)
        cam.stickX = 1f
        cam.run(0.5f, start = 1_100_000_000L)
        cam.stickX = 0f
        val moved = cam.run(0.5f, start = 1_700_000_000L)
        assertTrue("the view went right", moved.x > 20)
        cam.walking = true
        val back = cam.run(1.5f, start = 2_300_000_000L)
        assertTrue("and back to the player once walking", back.x in -24..-21)
    }

    @Test fun walkingWithTheStickKeepsThePlayerOnScreen() {
        val cam = thor()
        cam.walking = true
        cam.stickX = 1f
        val w = cam.run(2f)
        // The player (at the frame's middle, x 120) stays at least 28 px inside the window.
        assertTrue(w.x + w.w - 120 >= 28)
        assertTrue(w.x > 0)
    }

    @Test fun aDsGameZoomsOutAsFarAsItsMarginsGo() {
        // 256 x 192 at 5.625: 5 shows too little more, 2 (960 x 540) more than the 752 x 384 the slices cover.
        val cam = thor().apply { ndsMode(true) }
        assertArrayEquals(floatArrayOf(5.625f, 4f, 3f), cam.levels(), 0.001f)
        cam.level = 2
        val out = cam.run(1.5f)
        assertEquals(3f, out.scale, 0.001f)
        assertTrue(out.x >= -248 && out.x + out.w <= 504 && out.y >= -96 && out.y + out.h <= 288)
    }

    @Test fun aDsGamesLookAroundStopsAtItsMargins() {
        val cam = thor().apply { ndsMode(true) }
        cam.level = 1
        cam.run(1f)
        cam.stickX = 1f
        cam.stickY = -1f
        val w = cam.run(3f, start = 1_100_000_000L)
        // 4x: 480 x 270, so 136 px of room right and 57 above.
        assertEquals(504, w.x + w.w)
        assertEquals(-96, w.y)
        // A 20:9 phone gets 3x too (740 x 360).
        assertArrayEquals(floatArrayOf(5.625f, 4f, 3f), WideCamera().apply { viewW = 2220; viewH = 1080; ndsMode(true) }.levels(), 0.001f)
        cam.ndsMode(false)
        assertArrayEquals(floatArrayOf(6.75f, 5f, 4f, 3f), cam.levels(), 0.001f)
    }
}
