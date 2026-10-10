package com.pokedaisy.app.overlay

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * SCREEN > OVERLAY: a frame drawn around the game on the top screen. Two built-in frames of
 * our own ([BuiltInFrames]) and whatever the player imports - RetroArch's overlay format
 * (a .cfg naming a PNG, the game shown through the PNG's see-through window), a .zip of
 * those, or a bare PNG. None are bundled: the common console borders carry the console
 * maker's marks, so players bring their own (see docs/DEVELOPMENT.md's "Overlays").
 *
 * Everything here is plain Kotlin (no Android), so ui-preview compiles it and the unit tests
 * run it on the JVM; decoding and drawing are EmulatorView's / OverlayBitmaps'.
 */

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

        private fun fmt(f: Float) = "%.5f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.')
    }
}

/** A box on the view, in view pixels from its top-left. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

/** One overlay of a RetroArch .cfg: its image (the path as written, relative to the .cfg), where the game goes
 * if it says ([viewport]; else the image's see-through window is found, [OverlayWindow]), and its name. */
data class OverlayLayer(val image: String, val viewport: Viewport? = null, val name: String? = null)

/**
 * RetroArch's overlay .cfg: `key = value` lines (values maybe quoted, `#` comments), `overlays = N`, then per
 * overlay `overlayK_overlay` (its image), `overlayK_viewport` and `overlayK_name`. Touch-button entries
 * (`overlayK_descN...`) are left out: these overlays are only drawn, the touch pad has its own buttons.
 */
object OverlayCfg {
    private const val MAX_LAYERS = 16

    fun keys(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            value = if (value.startsWith("\"")) {
                val end = value.indexOf('"', 1)
                if (end > 0) value.substring(1, end) else value.substring(1)
            } else {
                value.substringBefore(" #").substringBefore("\t#").trim()
            }
            out[key] = value
        }
        return out
    }

    fun parse(text: String): List<OverlayLayer> {
        val k = keys(text)
        val count = k["overlays"]?.toIntOrNull()?.coerceIn(0, MAX_LAYERS)
            // No count: every overlayK_overlay there is.
            ?: (0 until MAX_LAYERS).count { k["overlay${it}_overlay"] != null }
        return (0 until count).mapNotNull { i ->
            val image = k["overlay${i}_overlay"]?.trim()?.replace('\\', '/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            OverlayLayer(image, Viewport.parse(k["overlay${i}_viewport"]), k["overlay${i}_name"]?.takeIf { it.isNotBlank() })
        }
    }

    /** Our own copy's .cfg: [layers] with their images flat beside it, plus the name the library shows. */
    fun write(name: String, layers: List<OverlayLayer>): String = buildString {
        appendLine("# Imported by PokeDaisy (RetroArch overlay format).")
        appendLine("pokedaisy_name = \"${name.replace("\"", "")}\"")
        appendLine("overlays = ${layers.size}")
        layers.forEachIndexed { i, l ->
            appendLine("overlay${i}_overlay = \"${l.image}\"")
            appendLine("overlay${i}_full_screen = true")
            appendLine("overlay${i}_descs = 0")
            l.viewport?.let { appendLine("overlay${i}_viewport = \"$it\"") }
            l.name?.let { appendLine("overlay${i}_name = \"${it.replace("\"", "")}\"") }
        }
    }
}

/** A PNG's size from its header, without decoding it; null if it isn't a PNG. */
object PngInfo {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun size(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 24) return null
        for (i in SIGNATURE.indices) if (head[i] != SIGNATURE[i]) return null
        // The first chunk is IHDR: length (4), "IHDR" (4), width (4), height (4).
        if (String(head, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        fun u32(at: Int) = ((head[at].toInt() and 0xFF) shl 24) or ((head[at + 1].toInt() and 0xFF) shl 16) or
            ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
        return u32(16) to u32(20)
    }

    fun size(file: File): Pair<Int, Int>? = runCatching {
        file.inputStream().use { s -> ByteArray(24).also { b -> if (s.readNBytesCompat(b) < 24) return null } }
    }.getOrNull()?.let(::size)

    private fun InputStream.readNBytesCompat(b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }
}

/**
 * The see-through window an overlay image shows the game through, for a .cfg that doesn't say where it goes
 * (most border packs set RetroArch's viewport by hand instead) or a bare PNG: the transparent region around the
 * image's centre (alpha under half), as a rectangle. Null when there's none worth the name - an opaque image, or a
 * see-through patch too small or too ragged to be a screen - and the image then goes behind the game instead.
 */
object OverlayWindow {
    private const val CLEAR_ALPHA = 128
    /** Smaller than this share of the image isn't a screen. */
    private const val MIN_AREA = 0.04f
    /** How much of its bounding box the region fills: a screen is a (rounded) rectangle. */
    private const val MIN_FILL = 0.6f

    fun find(argb: IntArray, w: Int, h: Int): Viewport? {
        if (w <= 0 || h <= 0 || argb.size < w * h) return null
        fun clear(i: Int) = (argb[i] ushr 24) < CLEAR_ALPHA
        // The centre, else the nearest see-through pixel along the centre lines (a logo can sit mid-screen).
        val cx = w / 2
        val cy = h / 2
        val start = sequence {
            yield(cy * w + cx)
            for (d in 1..min(w, h) / 4) {
                if (cx - d >= 0) yield(cy * w + cx - d)
                if (cx + d < w) yield(cy * w + cx + d)
                if (cy - d >= 0) yield((cy - d) * w + cx)
                if (cy + d < h) yield((cy + d) * w + cx)
            }
        }.firstOrNull(::clear) ?: return null

        val seen = java.util.BitSet(w * h)
        val stack = IntArray(w * h)
        var top = 0
        stack[top++] = start
        seen.set(start)
        var count = 0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        while (top > 0) {
            val i = stack[--top]
            count++
            val x = i % w
            val y = i / w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (x > 0 && !seen[i - 1] && clear(i - 1)) { seen.set(i - 1); stack[top++] = i - 1 }
            if (x < w - 1 && !seen[i + 1] && clear(i + 1)) { seen.set(i + 1); stack[top++] = i + 1 }
            if (y > 0 && !seen[i - w] && clear(i - w)) { seen.set(i - w); stack[top++] = i - w }
            if (y < h - 1 && !seen[i + w] && clear(i + w)) { seen.set(i + w); stack[top++] = i + w }
        }
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        if (count < MIN_AREA * w * h || count < MIN_FILL * boxW * boxH) return null
        return Viewport(minX.toFloat() / w, minY.toFloat() / h, boxW.toFloat() / w, boxH.toFloat() / h)
    }
}

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

    /** Frame pixels kept around the game at the least, either side / above and below. */
    private const val MIN_SIDE = 8
    private const val MIN_TOP = 4

    /** The whole-number scale the game gets inside a frame on a [viewW] x [viewH] view (at least 1). */
    fun scaleFor(viewW: Int, viewH: Int, gameW: Int, gameH: Int): Int =
        min(viewW / (gameW + 2 * MIN_SIDE), viewH / (gameH + 2 * MIN_TOP)).coerceAtLeast(1)

    /** [style]'s frame for a [viewW] x [viewH] view around a [gameW] x [gameH] game; [logo] goes in its left margin when it fits. */
    fun render(style: Style, viewW: Int, viewH: Int, gameW: Int, gameH: Int, logo: Sprite? = null): Frame {
        val s = scaleFor(viewW, viewH, gameW, gameH)
        val w = maxOf(gameW + 2, ceil(viewW.toFloat() / s).toInt())
        val h = maxOf(gameH + 2, ceil(viewH.toFloat() / s).toInt())
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
        return Frame(c.px, w, h, s, Viewport(gx.toFloat() / w, gy.toFloat() / h, gameW.toFloat() / w, gameH.toFloat() / h))
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

/** An overlay the player imported: its folder under [OverlayStore.dir], the name shown, and its layers' images. */
class StoredOverlay(val id: String, val name: String, val layers: List<Layer>) {
    class Layer(val file: File, val viewport: Viewport?, val w: Int, val h: Int)

    /** The layer made for a view of [aspect] (w / h): the one whose image is closest to that shape (a pack's
     * landscape and portrait versions). */
    fun layerFor(aspect: Float): Layer? = layers.minByOrNull { abs(it.w.toFloat() / it.h - aspect) }
}

/**
 * The imported overlays: one folder each under `<files>/overlays/`, an `overlay.cfg` (RetroArch's format, rewritten
 * with its images flat beside it) and those PNGs. Imports read RetroArch .cfg files (with their images: picked
 * together, read beside the .cfg when its real path is readable, or picked when asked for), .zip packs and bare PNGs.
 */
class OverlayStore(root: File) {
    val dir = File(root, "overlays")

    fun list(): List<StoredOverlay> =
        dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::read).sortedBy { it.name.lowercase() }

    fun get(id: String): StoredOverlay? = File(dir, id).takeIf { it.isDirectory && safeId(id) }?.let(::read)

    fun delete(id: String): Boolean = safeId(id) && File(dir, id).deleteRecursively()

    private fun read(folder: File): StoredOverlay? {
        val cfg = File(folder, CFG).takeIf { it.isFile } ?: return null
        val text = runCatching { cfg.readText() }.getOrNull() ?: return null
        val layers = OverlayCfg.parse(text).mapNotNull { l ->
            val f = File(folder, l.image).takeIf { it.isFile && it.parentFile == folder } ?: return@mapNotNull null
            val (w, h) = PngInfo.size(f) ?: return@mapNotNull null
            StoredOverlay.Layer(f, l.viewport, w, h)
        }
        if (layers.isEmpty()) return null
        val name = OverlayCfg.keys(text)["pokedaisy_name"]?.takeIf { it.isNotBlank() } ?: folder.name
        return StoredOverlay(folder.name, name, layers)
    }

    /** A file the player picked: its [name], its bytes, and its real path when one is known (for a .cfg's images). */
    class Picked(val name: String, val bytes: ByteArray, val path: String? = null)

    sealed class Result {
        /** Overlays now in the store (an import can add several: a pack). */
        data class Imported(val ids: List<String>, val skipped: Int) : Result()
        /** A .cfg whose image isn't among the files: ask for [image] (its file name) and import again with it added. */
        data class NeedsImage(val cfg: String, val image: String) : Result()
        data class Failed(val reason: Reason) : Result()
    }

    enum class Reason { NOTHING_USABLE, TOO_BIG }

    /** Imports [files]: .zip packs, .cfg files (with their images among [files], beside their real path, or not at all
     * yet - then [Result.NeedsImage]) and PNGs no .cfg names (each one an overlay of its own). */
    fun import(files: List<Picked>): Result {
        val entries = ArrayList<Entry>()
        for (f in files) {
            if (f.name.endsWith(".zip", ignoreCase = true)) {
                entries += unzip(f.bytes) ?: return Result.Failed(Reason.TOO_BIG)
            } else {
                entries += Entry(f.name.substringAfterLast('/'), f.bytes, f.path)
            }
        }
        return importEntries(entries)
    }

    /** A file to import: its [path] inside a zip (or just its name), its bytes, and its real path on the device if known. */
    private class Entry(val path: String, val bytes: ByteArray, val realPath: String? = null) {
        val name get() = path.substringAfterLast('/')
        val dirPath get() = path.substringBeforeLast('/', "")
    }

    private fun importEntries(entries: List<Entry>): Result {
        val cfgs = entries.filter { it.name.endsWith(".cfg", ignoreCase = true) }
        val pngs = entries.filter { PngInfo.size(it.bytes) != null }
        val used = HashSet<Entry>()
        val ids = ArrayList<String>()
        var skipped = 0
        var needs: Result.NeedsImage? = null
        for (cfg in cfgs) {
            if (ids.size >= MAX_OVERLAYS) { skipped++; continue }
            val layers = OverlayCfg.parse(cfg.bytes.toString(Charsets.UTF_8))
            if (layers.isEmpty()) { skipped++; continue }
            val found = layers.map { l -> l to findImage(cfg, l.image, pngs, used) }
            var ok = found.filter { it.second != null }
            // One .cfg and one PNG (the image picked when asked for it, under another name): they go together.
            if (ok.isEmpty() && cfgs.size == 1 && pngs.size == 1) ok = listOf(layers.first() to pngs.single().bytes.also { used += pngs.single() })
            if (ok.isEmpty()) {
                if (needs == null) needs = Result.NeedsImage(cfg.name, layers.first().image.substringAfterLast('/'))
                skipped++
                continue
            }
            ids += save(cfg.name.substringBeforeLast('.'), ok.map { (l, img) -> l to img!! })
        }
        for (png in pngs) {
            if (png in used) continue
            if (ids.size >= MAX_OVERLAYS) { skipped++; continue }
            ids += save(png.name.substringBeforeLast('.'), listOf(OverlayLayer(png.name) to png.bytes))
        }
        return when {
            ids.isNotEmpty() -> Result.Imported(ids, skipped)
            needs != null -> needs
            else -> Result.Failed(Reason.NOTHING_USABLE)
        }
    }

    /** [image] as the .cfg [cfg] names it: among the picked PNGs (by path inside a zip, else by name), else beside
     * the .cfg's real path on the device. */
    private fun findImage(cfg: Entry, image: String, pngs: List<Entry>, used: MutableSet<Entry>): ByteArray? {
        val inZip = normalize(if (cfg.dirPath.isEmpty()) image else "${cfg.dirPath}/$image")
        val name = image.substringAfterLast('/')
        val hit = pngs.firstOrNull { normalize(it.path) == inZip } ?: pngs.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (hit != null) { used += hit; return hit.bytes }
        val real = cfg.realPath?.let { File(it).parentFile?.let { d -> File(d, image) } } ?: return null
        return runCatching { real.takeIf { it.isFile && it.length() <= MAX_IMAGE }?.readBytes() }.getOrNull()
            ?.takeIf { PngInfo.size(it) != null }
    }

    private fun save(baseName: String, layers: List<Pair<OverlayLayer, ByteArray>>): String {
        dir.mkdirs()
        val base = baseName.lowercase().replace(Regex("[^a-z0-9.-]+"), "-").trim('-', '.').take(48).ifEmpty { "overlay" }
        var id = base
        var n = 2
        while (File(dir, id).exists()) id = "$base-${n++}"
        val folder = File(dir, id).apply { mkdirs() }
        val written = layers.mapIndexed { i, (l, bytes) ->
            File(folder, "$i.png").writeBytes(bytes)
            l.copy(image = "$i.png")
        }
        File(folder, CFG).writeText(OverlayCfg.write(displayName(baseName), written))
        return id
    }

    /** The .zip's .cfg and PNG files by their path inside it; null when it's bigger than an overlay pack should be. */
    private fun unzip(bytes: ByteArray): List<Entry>? {
        val out = ArrayList<Entry>()
        var total = 0L
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++count > MAX_ZIP_ENTRIES) return null
                    if (e.isDirectory) continue
                    val path = e.name.replace('\\', '/')
                    val lower = path.lowercase()
                    if (!lower.endsWith(".cfg") && !lower.endsWith(".png")) continue
                    if (path.split('/').any { it.startsWith(".") || it == "__MACOSX" }) continue
                    val limit = if (lower.endsWith(".cfg")) MAX_CFG else MAX_IMAGE
                    val data = readCapped(zip, limit) ?: continue
                    total += data.size
                    if (total > MAX_ZIP_TOTAL) return null
                    out += Entry(path, data)
                }
            }
        }
        return out
    }

    private fun readCapped(input: InputStream, limit: Long): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > limit) return null
        }
        return buf.toByteArray()
    }

    companion object {
        const val CFG = "overlay.cfg"
        /** One overlay image at most (a 4K border is ~5 MB). */
        const val MAX_IMAGE = 32L * 1024 * 1024
        const val MAX_CFG = 256L * 1024
        /** What a .zip may unpack to, all told (it's held in memory while it's imported). */
        const val MAX_ZIP_TOTAL = 96L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 5000
        /** Overlays one import adds at most (a whole border collection is hundreds). */
        const val MAX_OVERLAYS = 64

        /** The name shown for an overlay imported from [fileName] (no extension): its words, in capitals. */
        fun displayName(fileName: String): String =
            fileName.replace(Regex("[_]+"), " ").replace(Regex("\\s+"), " ").trim().uppercase().ifEmpty { "OVERLAY" }

        private fun safeId(id: String) = id.isNotEmpty() && id.matches(Regex("[a-z0-9._-]+")) && id != "." && id != ".."

        /** "a/b/../c.png" -> "a/c.png", case-folded (packs made on Windows mix the case of their paths). */
        private fun normalize(path: String): String {
            val parts = ArrayList<String>()
            for (p in path.replace('\\', '/').split('/')) when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
            return parts.joinToString("/").lowercase()
        }
    }
}

/** What SCREEN > OVERLAY holds ([com.pokedaisy.app.Prefs.overlay]): nothing, a built-in frame or an imported overlay. */
sealed class OverlayChoice {
    abstract val key: String

    object None : OverlayChoice() { override val key = "" }
    data class BuiltIn(val style: BuiltInFrames.Style) : OverlayChoice() { override val key = "builtin:${style.name}" }
    data class Imported(val id: String) : OverlayChoice() { override val key = "user:$id" }

    companion object {
        fun of(key: String?): OverlayChoice = when {
            key.isNullOrEmpty() -> None
            key.startsWith("builtin:") -> BuiltInFrames.Style.entries.firstOrNull { it.name == key.removePrefix("builtin:") }?.let(::BuiltIn) ?: None
            key.startsWith("user:") -> Imported(key.removePrefix("user:"))
            else -> None
        }

        /** Every choice there is: none, the built-in frames, then the player's imports (by name). */
        fun all(store: OverlayStore): List<OverlayChoice> =
            listOf(None) + BuiltInFrames.Style.entries.map(::BuiltIn) + store.list().map { Imported(it.id) }
    }
}
