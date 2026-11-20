package com.pokedaisy.app.ios

import com.pokedaisy.app.companion.ui.borderImage
import com.pokedaisy.app.overlay.BuiltInFrames
import kotlin.test.*

class GameBorderImageTest {
    @Test fun cachedBorderBitmapRetainsEveryArtPixelAndTheTransparentPictureWindow() {
        for (style in BuiltInFrames.Style.entries) {
            val frame = BuiltInFrames.render(style, 852, 360, 240, 160)
            val image = borderImage(frame)
            assertEquals(frame.w, image.width)
            assertEquals(frame.h, image.height)
            val pixels = IntArray(frame.argb.size)
            image.readPixels(pixels)
            assertContentEquals(frame.argb, pixels)
        }
    }
}
