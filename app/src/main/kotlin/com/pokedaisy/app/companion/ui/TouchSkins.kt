package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pokedaisy.app.companion.InstalledSkin
import com.pokedaisy.app.companion.SkinFit
import com.pokedaisy.app.companion.SkinRepresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A Delta skin's pictures, drawn at the size they're shown: a PDF page rendered to it, a PNG decoded (and scaled when
 * drawn). The decoding is the platform's ([decode]: the app's uses Android's PdfRenderer / BitmapFactory, ui-preview's
 * reads PNGs); the last few are kept, so the game, SETTINGS' preview and a rotation don't render them again.
 */
object SkinImages {
    /** [file] as a [w]x[h] px picture, or null when it can't be read. */
    @Volatile var decode: (file: File, w: Int, h: Int) -> ImageBitmap? = { _, _, _ -> null }

    /** No picture bigger than this a side (a 4K tablet's skin is still under it). */
    const val MAX_SIDE = 4096

    private val cache = object : LinkedHashMap<String, ImageBitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 3
    }

    fun load(skin: InstalledSkin, rep: SkinRepresentation, w: Int, h: Int): ImageBitmap? {
        val file = rep.asset?.let { skin.file(it) } ?: return null
        val k = minOf(1f, MAX_SIDE.toFloat() / max(w, h).coerceAtLeast(1))
        val tw = (w * k).roundToInt().coerceAtLeast(1)
        val th = (h * k).roundToInt().coerceAtLeast(1)
        val key = "${file.path}:${file.lastModified()}:$tw:$th"
        synchronized(cache) { cache[key] }?.let { return it }
        val image = runCatching { decode(file, tw, th) }.getOrNull() ?: return null
        synchronized(cache) { cache[key] = image }
        return image
    }
}

/** [fit]'s picture, loaded off the main thread at the size it's drawn; null until then (or when it can't be read). */
@Composable
fun rememberSkinImage(skin: InstalledSkin?, fit: SkinFit?): State<ImageBitmap?> {
    val w = fit?.image?.width?.roundToInt() ?: 0
    val h = fit?.image?.height?.roundToInt() ?: 0
    return produceState<ImageBitmap?>(null, skin, fit?.rep, w, h) {
        value = if (skin == null || fit == null || w <= 0 || h <= 0) null
            else withContext(Dispatchers.IO) { SkinImages.load(skin, fit.rep, w, h) }
    }
}

/**
 * A skin as [fit] lays it, with [image] its picture: only inside [SkinFit.clip], and - for a solid skin - not over the
 * game, which shows through where its screen is. [held] = the items pressed now (bits by index), lit a little.
 */
internal fun DrawScope.drawSkin(fit: SkinFit, image: ImageBitmap?, held: Long = 0L) {
    val c = fit.clip
    clipRect(c.left, c.top, c.right, c.bottom) {
        val game = fit.game?.takeIf { !fit.rep.translucent }
        val draw: DrawScope.() -> Unit = {
            if (image != null) {
                drawImage(
                    image, dstOffset = IntOffset(fit.image.left.roundToInt(), fit.image.top.roundToInt()),
                    dstSize = IntSize(fit.image.width.roundToInt(), fit.image.height.roundToInt()), filterQuality = FilterQuality.Medium,
                )
            } else {
                // Until the picture is ready (or when it can't be read): the touch areas, outlined, so the buttons work and show.
                for (p in fit.items) drawRect(Color(0x66FFFFFF), p.frame.topLeft, p.frame.size, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }
        if (game != null) clipRect(game.left, game.top, game.right, game.bottom, ClipOp.Difference) { draw() } else draw()
        fit.items.forEachIndexed { i, p ->
            if (i < 64 && held and (1L shl i) != 0L) {
                val r = minOf(p.frame.width, p.frame.height) / 2
                drawPixelRoundRect(Color(0x40FFFFFF), Offset(p.frame.left, p.frame.top), Size(p.frame.width, p.frame.height), r)
            }
        }
    }
}

/** [fit]'s touch areas as TouchGeometry items, for what keeps clear of the controls (the HUD) and what a touch misses. */
internal fun skinPlaced(fit: SkinFit, dp: Float): TouchGeometry.Placed = TouchGeometry.Placed(
    fit.w, fit.h,
    fit.items.map { p ->
        val b = p.item.buttons.firstOrNull() ?: com.pokedaisy.app.companion.TouchButton.DPAD
        TouchGeometry.Item(b, p.frame.center.x, p.frame.center.y, p.frame.width, p.frame.height, TouchGeometry.Shape.PILL)
    },
    com.pokedaisy.app.companion.TouchPreset.CLASSIC, dp,
)
