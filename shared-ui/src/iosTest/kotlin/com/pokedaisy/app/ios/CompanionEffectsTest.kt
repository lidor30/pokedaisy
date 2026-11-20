package com.pokedaisy.app.ios

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.pokedaisy.app.CompanionShaders
import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.*

class CompanionEffectsTest {
    private fun pixels(filter: ScreenFilter, colors: Boolean = false, alpha: Int = 255): IntArray {
        val settings = CompanionShaders.settings(colors, filter, GridStrength.MEDIUM, true)
        val effect = assertNotNull(IosCompanionEffects.imageFilter(settings, 8f))
        return Surface.makeRasterN32Premul(16, 16).use { surface ->
            Paint().use { paint ->
                paint.color = (alpha shl 24) or 0x648CC8
                paint.imageFilter = effect
                surface.canvas.clear(0)
                surface.canvas.drawRect(Rect.makeWH(16f, 16f), paint)
            }
            surface.makeImageSnapshot().use { image ->
                IntArray(256).also { image.toComposeImageBitmap().readPixels(it) }
            }
        }.also { effect.close() }
    }

    @Test fun everySharedUiFilterCompilesAndKeepsItsOwnResult() {
        val pictures = ScreenFilter.entries.map { pixels(it, colors = true).toList() }
        assertEquals(ScreenFilter.entries.size, pictures.distinct().size)
        assertTrue(pictures.flatten().all { it ushr 24 == 255 })
    }

    @Test fun correctedColorsMatchTheGameAndPreserveAlpha() {
        val corrected = pixels(ScreenFilter.NONE, colors = true)
        val want = intArrayOf(89, 140, 168)
        for (pixel in corrected) for (channel in 0..2) {
            val value = (pixel ushr (16 - channel * 8)) and 255
            assertTrue(abs(value - want[channel]) <= 1, "channel $channel: $value")
        }
        assertTrue(pixels(ScreenFilter.NONE, colors = true, alpha = 128).all { it ushr 24 == 128 })
    }

    @Test fun lcdUsesWholeUiCellsAndTwoPixelLines() {
        val picture = pixels(ScreenFilter.LCD)
        val normal = 0xFF648CC8.toInt()
        assertEquals(normal, picture[3 * 16 + 3])
        assertEquals(normal, picture[11 * 16 + 11])
        assertNotEquals(normal, picture[3 * 16 + 1])
        assertEquals(picture[3 * 16 + 1], picture[3 * 16 + 9])
    }

    @Test fun disabledCompanionAddsNoFilterEvenWithGameEffectsEnabled() {
        val settings = CompanionShaders.settings(true, ScreenFilter.CRT, GridStrength.STRONG, false)
        assertFalse(settings.active)
        assertNull(IosCompanionEffects.imageFilter(settings, 8f))
    }
}
