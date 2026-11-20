package com.pokedaisy.app.companion

import com.pokedaisy.app.overlay.*
import kotlin.test.*

class GameBorderTest {
    @Test fun builtInFramesRetainAndroidsPictureAndTransparentWindow() {
        for (style in BuiltInFrames.Style.entries) {
            val frame = BuiltInFrames.render(style, 1920, 1080, 240, 160)
            assertEquals(6, frame.scale)
            assertEquals(Box(240f, 60f, 1440f, 960f), BuiltInFrames.placement(1920, 1080, 240, 160).game)
            assertEquals(0, frame.argb[90 * frame.w + 160] ushr 24)
            assertEquals(255, frame.argb[90 * frame.w + 39] ushr 24)
            assertEquals(style, BuiltInFrames.style(BuiltInFrames.key(style)))
        }
        assertNull(BuiltInFrames.style("user:my-import"))
        assertNull(BuiltInFrames.style(""))
    }

    @Test fun pictureBoundsMatchTheArtAfterPhoneTabletAndGameBoyResizes() {
        for ((vw, vh) in listOf(852 to 360, 568 to 688, 1136 to 688, 300 to 200)) {
            for ((gw, gh) in listOf(240 to 160, 160 to 144)) {
                val frame = BuiltInFrames.render(BuiltInFrames.Style.DAISY, vw, vh, gw, gh)
                val placed = BuiltInFrames.placement(vw, vh, gw, gh)
                assertEquals(OverlayGeometry.layout(vw, vh, frame.w, frame.h, frame.window, gw, gh, false, frame.scale), placed)
                assertEquals(gw * frame.scale.toFloat(), placed.game.w)
                assertEquals(gh * frame.scale.toFloat(), placed.game.h)
                assertTrue(placed.game.x >= 0 && placed.game.y >= 0)
                assertTrue(placed.game.x + placed.game.w <= vw && placed.game.y + placed.game.h <= vh)
            }
        }
    }

    @Test fun storedViewportKeepsItsPortableFixedPointFormat() {
        val viewport = Viewport(0.25f, 0.203f, 0.5f, 0.5935f)
        assertEquals("0.25,0.203,0.5,0.5935", viewport.toString())
        assertEquals(viewport, Viewport.parse(viewport.toString()))
    }
}
