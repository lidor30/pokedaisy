package com.pokedaisy.app.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import com.pokedaisy.app.companion.ui.LOGO_PALETTE
import com.pokedaisy.app.companion.ui.LOGO_ROWS

/** An overlay ready for [com.pokedaisy.app.EmulatorView]: its pixels, the window the game shows through (null = it goes
 * behind the game), the whole-number [scale] it's drawn at (0 = fitted to the view) and whether it's scaled smoothly. */
class OverlayImage(val bitmap: Bitmap, val window: Viewport?, val scale: Int, val smooth: Boolean)

/** Decoding and drawing overlays into bitmaps (Overlays.kt holds the rest, Android-free). */
internal object OverlayBitmaps {
    private val logo by lazy { Sprite(LOGO_ROWS, LOGO_PALETTE.mapValues { it.value.toArgb() }) }

    /** [style]'s frame for a [viewW] x [viewH] view around a [gameW] x [gameH] game: tiny, so drawn on the spot. */
    fun builtIn(style: BuiltInFrames.Style, viewW: Int, viewH: Int, gameW: Int, gameH: Int): OverlayImage {
        val f = BuiltInFrames.render(style, viewW, viewH, gameW, gameH, logo)
        return OverlayImage(Bitmap.createBitmap(f.argb, f.w, f.h, Bitmap.Config.ARGB_8888), f.window, f.scale, smooth = false)
    }

    /**
     * [layer]'s image, decoded no bigger than it needs to be for a [viewW] x [viewH] view (a 4K border on a 1080p
     * screen comes in at half size), with its window found when the .cfg didn't say ([OverlayWindow]).
     * Reads the file: off the UI thread.
     */
    fun imported(layer: StoredOverlay.Layer, viewW: Int, viewH: Int): OverlayImage? {
        var sample = 1
        while (layer.w / (sample * 2) >= viewW && layer.h / (sample * 2) >= viewH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(layer.file.path, opts) ?: return null
        val window = layer.viewport ?: run {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            OverlayWindow.find(px, bmp.width, bmp.height)
        }
        return OverlayImage(bmp, window, scale = 0, smooth = true)
    }
}
