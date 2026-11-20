package com.pokedaisy.app.overlay

import com.pokedaisy.app.companion.data.fixed
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Shared border pixels and placement. Android's GL and the shared Compose screen use these same algorithms.

/** A rectangle in 0..1 of an overlay image, from its top-left: RetroArch's `overlayN_viewport` (x, y, w, h). */
data class Viewport(val x: Float, val y: Float, val w: Float, val h: Float) {
    override fun toString() = "${fmt(x)},${fmt(y)},${fmt(w)},${fmt(h)}"

    companion object {
        /** "x,y,w,h", each in 0..1 and the rectangle inside the image; else null. */
        fun parse(s: String?): Viewport? {
            val v = s?.split(',')?.map { it.trim().toFloatOrNull() ?: return null } ?: return null
            if (v.size != 4) return null
            val (x, y, w, h) = v
            if (x < 0f || y < 0f || w <= 0f || h <= 0f || x + w > 1.001f || y + h > 1.001f) return null
            return Viewport(x, y, w, h)
        }

        private fun fmt(f: Float) = f.toDouble().fixed(5).trimEnd('0').trimEnd('.')
    }
}

/** A box on the view, in view pixels from its top-left. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

/** Where an overlay and the game go on the view ([OverlayGeometry.layout]); [onTop]: the overlay is drawn over the game
 * (it has a window), else behind it (a background). */
data class OverlayPlacement(val overlay: Box, val game: Box, val onTop: Boolean)

object OverlayGeometry {
    /** An overlay this close to the view's shape fills it (the odd pixel of stretch) rather than leaving thin bars. */
    private const val FILL_TOLERANCE = 0.04f

    /**
     * Places an [imgW] x [imgH] overlay on a [viewW] x [viewH] view and the [gameW] x [gameH] game in its [window]:
     * the overlay fitted to the view, centred (filling it when their shapes nearly match), or at exactly [scale] view
     * pixels per image pixel when that's set (the built-in frames, drawn at the game's own pixel size); the game fitted
     * in the window at its own shape (filling it when that's within a few percent), or filling it with [stretch]
     * (ASPECT > STRETCH). No window: the overlay is a
     * background and the game sits on it as it would without one. Game edges land on whole pixels.
     */
    fun layout(
        viewW: Int, viewH: Int, imgW: Int, imgH: Int, window: Viewport?,
        gameW: Int, gameH: Int, stretch: Boolean, scale: Int = 0,
    ): OverlayPlacement {
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()
        val overlay = when {
            // Whole pixels, so the frame's window lines up with the game's (snapped) edges.
            scale > 0 -> Box(floor((vw - imgW * scale) / 2f), floor((vh - imgH * scale) / 2f), imgW.toFloat() * scale, imgH.toFloat() * scale)
            imgW <= 0 || imgH <= 0 -> Box(0f, 0f, vw, vh)
            abs(imgW.toFloat() / imgH / (vw / vh) - 1f) <= FILL_TOLERANCE -> Box(0f, 0f, vw, vh)
            else -> fit(imgW.toFloat() / imgH, Box(0f, 0f, vw, vh))
        }
        if (window == null) {
            val game = if (stretch) Box(0f, 0f, vw, vh) else fit(gameW.toFloat() / gameH, Box(0f, 0f, vw, vh))
            return OverlayPlacement(overlay, snap(game), onTop = false)
        }
        val win = Box(overlay.x + window.x * overlay.w, overlay.y + window.y * overlay.h, window.w * overlay.w, window.h * overlay.h)
        // A window drawn by hand is rarely exactly 3:2: one this close is filled, not edged with black slivers.
        val near = abs(win.w / win.h / (gameW.toFloat() / gameH) - 1f) <= FILL_TOLERANCE
        val game = if (stretch || near) win else fit(gameW.toFloat() / gameH, win)
        return OverlayPlacement(overlay, snap(game), onTop = true)
    }

    /** The largest box of [aspect] (w / h) in [area], centred. */
    fun fit(aspect: Float, area: Box): Box {
        if (aspect <= 0f || area.w <= 0f || area.h <= 0f) return area
        return if (area.w / area.h > aspect) {
            val w = area.h * aspect
            Box(area.x + (area.w - w) / 2f, area.y, w, area.h)
        } else {
            val h = area.w / aspect
            Box(area.x, area.y + (area.h - h) / 2f, area.w, h)
        }
    }

    private fun snap(b: Box): Box {
        val l = b.x.roundToInt()
        val t = b.y.roundToInt()
        val r = (b.x + b.w).roundToInt()
        val bt = (b.y + b.h).roundToInt()
        return Box(l.toFloat(), t.toFloat(), (r - l).toFloat(), (bt - t).toFloat())
    }
}

/** A drawn frame: [argb] pixels, [w] x [h], meant to be shown at [scale] view pixels each with the game in [window]. */
class Frame(val argb: IntArray, val w: Int, val h: Int, val scale: Int, val window: Viewport)

/** Pixel art to stamp on a frame: rows of palette keys ('.' = nothing). */
class Sprite(val rows: List<String>, val palette: Map<Char, Int>) {
    val w get() = rows.maxOfOrNull { it.length } ?: 0
    val h get() = rows.size
}

/**
 * The built-in overlays: frames of our own, in the app's pixel-art look, drawn at the game's own pixel size and
 * shown at a whole-number scale - one frame pixel is one game pixel, so they're as crisp as the game. Nothing of any
 * console's: no shell, no buttons, no logos but PokeDaisy's own mark.
 */
object BuiltInFrames {
    enum class Style { DAISY, BEZEL }

    fun key(style: Style) = "builtin:${style.name}"
    fun style(key: String): Style? = Style.entries.firstOrNull { this.key(it) == key }

    /** Frame pixels kept around the game at the least, either side / above and below. */
    private const val MIN_SIDE = 8
    private const val MIN_TOP = 4

    /** The whole-number scale the game gets inside a frame on a [viewW] x [viewH] view (at least 1). */
    fun scaleFor(viewW: Int, viewH: Int, gameW: Int, gameH: Int): Int =
        min(viewW / (gameW + 2 * MIN_SIDE), viewH / (gameH + 2 * MIN_TOP)).coerceAtLeast(1)

    private data class Dimensions(val w: Int, val h: Int, val scale: Int, val window: Viewport)
    private fun dimensions(viewW: Int, viewH: Int, gameW: Int, gameH: Int): Dimensions {
        val s = scaleFor(viewW, viewH, gameW, gameH)
        val w = maxOf(gameW + 2, ceil(viewW.toFloat() / s).toInt())
        val h = maxOf(gameH + 2, ceil(viewH.toFloat() / s).toInt())
        return Dimensions(w, h, s, Viewport((w - gameW) / 2 / w.toFloat(), (h - gameH) / 2 / h.toFloat(), gameW.toFloat() / w, gameH.toFloat() / h))
    }

    /** Picture and border bounds without rasterizing any art, also used by touch/HUD geometry. */
    fun placement(viewW: Int, viewH: Int, gameW: Int, gameH: Int, stretch: Boolean = false): OverlayPlacement {
        val d = dimensions(viewW, viewH, gameW, gameH)
        return OverlayGeometry.layout(viewW, viewH, d.w, d.h, d.window, gameW, gameH, stretch, d.scale)
    }

    /** [style]'s frame for a [viewW] x [viewH] view around a [gameW] x [gameH] game; [logo] goes in its left margin when it fits. */
    fun render(style: Style, viewW: Int, viewH: Int, gameW: Int, gameH: Int, logo: Sprite? = null): Frame {
        val (w, h, s, window) = dimensions(viewW, viewH, gameW, gameH)
        val gx = (w - gameW) / 2
        val gy = (h - gameH) / 2
        val c = Canvas(w, h)
        val margin = min(gx, gy)
        // The screen's surround: as thick as the margins allow, at most 5.
        val b = (margin - 1).coerceIn(1, 5)
        when (style) {
            Style.DAISY -> {
                c.fill(0, 0, w, h, DAISY_BLUE)
                // The website's dot grid, lined up on the screen's corner.
                for (y in (gy % 6) - 3 until h step 6) for (x in (gx % 6) - 3 until w step 6) c.set(x, y, DAISY_DOT)
                c.roundRect(gx - b + 1, gy - b + 1, gameW + 2 * b, gameH + 2 * b, b + 2, DAISY_SHADOW)
                c.roundRect(gx - b, gy - b, gameW + 2 * b, gameH + 2 * b, b + 2, DAISY_OUTLINE)
                c.roundRect(gx - b + 1, gy - b + 1, gameW + 2 * b - 2, gameH + 2 * b - 2, b + 1, DAISY_SURROUND)
                c.fill(gx - b + 3, gy - b + 1, gameW + 2 * b - 6, 1, DAISY_SHINE)
                if (b > 1) c.fill(gx - 1, gy - 1, gameW + 2, gameH + 2, DAISY_INNER)
                logo?.let { c.stampInMargin(it, gx - b, h, DAISY_SHADOW) }
            }
            Style.BEZEL -> {
                c.fill(0, 0, w, h, BEZEL_BG)
                c.roundRect(gx - b, gy - b, gameW + 2 * b, gameH + 2 * b, b + 2, BEZEL_SURROUND)
                c.fill(gx + 2, gy - b + 1, gameW - 4, 1, BEZEL_SHINE)
                if (b > 1) c.fill(gx - 1, gy - 1, gameW + 2, gameH + 2, BEZEL_INNER)
            }
        }
        // The game's own rectangle is see-through: it shows from under the frame.
        c.fill(gx, gy, gameW, gameH, 0)
        return Frame(c.px, w, h, s, window)
    }

    private const val DAISY_BLUE = 0xFF3396FF.toInt()
    private const val DAISY_DOT = 0xFF5CADFF.toInt()
    private const val DAISY_SHADOW = 0xFF2275D6.toInt()
    private const val DAISY_OUTLINE = 0xFF141C26.toInt()
    private const val DAISY_SURROUND = 0xFF2A3646.toInt()
    private const val DAISY_SHINE = 0xFF46566B.toInt()
    private const val DAISY_INNER = 0xFF0C1118.toInt()
    private const val BEZEL_BG = 0xFF16181C.toInt()
    private const val BEZEL_SURROUND = 0xFF24272D.toInt()
    private const val BEZEL_SHINE = 0xFF33373F.toInt()
    private const val BEZEL_INNER = 0xFF0A0B0D.toInt()

    private class Canvas(val w: Int, val h: Int) {
        val px = IntArray(w * h)

        fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }

        fun fill(x: Int, y: Int, fw: Int, fh: Int, c: Int) {
            for (yy in maxOf(0, y) until min(h, y + fh)) for (xx in maxOf(0, x) until min(w, x + fw)) px[yy * w + xx] = c
        }

        /** A rectangle with pixel-stepped corners of radius [r] (like PixelRoundedShape). */
        fun roundRect(x: Int, y: Int, rw: Int, rh: Int, r: Int, c: Int) {
            val rr = min(r, min(rw, rh) / 2)
            for (row in 0 until rh) {
                val fromEdge = min(row, rh - 1 - row)
                val inset = if (fromEdge < rr) {
                    val d = rr - fromEdge - 0.5f
                    (rr - sqrt(rr * rr - d * d) + 0.5f).let(::floor).toInt()
                } else 0
                fill(x + inset, y + row, rw - 2 * inset, 1, c)
            }
        }

        /** [s] centred in the margin left of [right], with a drop shadow, if there's room for it. */
        fun stampInMargin(s: Sprite, right: Int, h: Int, shadow: Int) {
            // Room on both sides of it, or none at all: squeezed against the screen's edge it looked like a mistake.
            if (right < s.w + 8 || h < s.h + 4) return
            val x0 = (right - s.w) / 2
            val y0 = (h - s.h) / 2
            for ((dx, dy, shade) in listOf(Triple(1, 1, true), Triple(0, 0, false))) {
                s.rows.forEachIndexed { y, row ->
                    row.forEachIndexed { x, k ->
                        val col = s.palette[k] ?: return@forEachIndexed
                        set(x0 + x + dx, y0 + y + dy, if (shade) shadow else col)
                    }
                }
            }
        }
    }
}
