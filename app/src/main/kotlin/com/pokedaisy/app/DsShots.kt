package com.pokedaisy.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * DS LAYOUT's pictures of the game, for what's behind its screens where they don't fill the screen (the user's call: the
 * game, not a plain colour - like a modal over the page it came from). [field] is the overworld as last seen widened
 * (EXPAND's whole view), kept while a menu screen is up; [current] the game's own picture now, drawn blurred behind a 4:3
 * game (the title, a layout with room around the screens). Copied off the game's SurfaceView ([PixelCopy]) at half size,
 * about once a second ([capture], from the activity's ticker), into two bitmaps a kind used in turn, so a picture being
 * drawn is never the one being written.
 */
class DsShots(private val view: SurfaceView) {
    /** The last overworld picture (null until there's been one this game). */
    val field = mutableStateOf<ImageBitmap?>(null)

    /** The game's picture a moment ago. */
    val current = mutableStateOf<ImageBitmap?>(null)

    /** Whichever of the two was copied last: the game as it is now, overworld or not. */
    val latest = mutableStateOf<ImageBitmap?>(null)

    private val handler = Handler(Looper.getMainLooper())
    private val fieldBufs = arrayOfNulls<Bitmap>(2)
    private val currentBufs = arrayOfNulls<Bitmap>(2)
    private var fieldNext = 0
    private var currentNext = 0
    private var busy = false

    /** Copies the game's picture now into [field] ([intoField]) or [current]. UI thread; one copy at a time. */
    fun capture(intoField: Boolean) {
        if (busy || view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return
        val w = (view.width / SCALE).coerceAtLeast(1)
        val h = (view.height / SCALE).coerceAtLeast(1)
        val bufs = if (intoField) fieldBufs else currentBufs
        val i = if (intoField) fieldNext else currentNext
        val bmp = bufs[i]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bufs[i] = it }
        busy = true
        runCatching {
            PixelCopy.request(view, bmp, { result ->
                busy = false
                if (result != PixelCopy.SUCCESS) return@request
                val shot = bmp.asImageBitmap()
                if (intoField) { field.value = shot; fieldNext = 1 - i }
                else { current.value = shot; currentNext = 1 - i }
                latest.value = shot
            }, handler)
        }.onFailure { busy = false }
    }

    /** A new game: nothing seen yet. */
    fun reset() {
        field.value = null
        current.value = null
        latest.value = null
    }

    private companion object {
        /** The copies' size against the view's: they're dimmed or blurred, a half is plenty. */
        const val SCALE = 2
    }
}
