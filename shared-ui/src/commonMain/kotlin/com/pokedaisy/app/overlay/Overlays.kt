package com.pokedaisy.app.overlay

import com.pokedaisy.app.companion.data.*
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.Source
import okio.buffer
import okio.openZip
import okio.use
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.min

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

/** One overlay of a RetroArch .cfg: its image (the path as written, relative to the .cfg), where the game goes
 * if it says ([viewport]; else the image's see-through window is found, [OverlayWindow]), and its name. */
data class OverlayLayer(val image: String, val viewport: Viewport? = null, val name: String? = null)

/**
 * RetroArch's overlay .cfg: `key = value` lines (values maybe quoted, `#` comments), `overlays = N`, then per
 * overlay `overlayK_overlay` (its image), `overlayK_viewport` and `overlayK_name`. Touch-button entries
 * (`overlayK_descN...`) are left out: these overlays are only drawn, the touch controls has its own buttons.
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
        if (head.copyOfRange(12, 16).decodeToString() != "IHDR") return null
        fun u32(at: Int) = ((head[at].toInt() and 0xFF) shl 24) or ((head[at + 1].toInt() and 0xFF) shl 16) or
            ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
        return u32(16) to u32(20)
    }

    fun size(file: Path): Pair<Int, Int>? = runCatching {
        FileSystem.SYSTEM.source(file).buffer().use { it.readByteArray(24) }
    }.getOrNull()?.let(::size)

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

        val seen = LongArray((w * h + 63) / 64)
        fun seen(i: Int) = seen[i ushr 6] and (1L shl (i and 63)) != 0L
        fun mark(i: Int) { seen[i ushr 6] = seen[i ushr 6] or (1L shl (i and 63)) }
        val stack = IntArray(w * h)
        var top = 0
        stack[top++] = start
        mark(start)
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
            if (x > 0 && !seen(i - 1) && clear(i - 1)) { mark(i - 1); stack[top++] = i - 1 }
            if (x < w - 1 && !seen(i + 1) && clear(i + 1)) { mark(i + 1); stack[top++] = i + 1 }
            if (y > 0 && !seen(i - w) && clear(i - w)) { mark(i - w); stack[top++] = i - w }
            if (y < h - 1 && !seen(i + w) && clear(i + w)) { mark(i + w); stack[top++] = i + w }
        }
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        if (count < MIN_AREA * w * h || count < MIN_FILL * boxW * boxH) return null
        return Viewport(minX.toFloat() / w, minY.toFloat() / h, boxW.toFloat() / w, boxH.toFloat() / h)
    }
}

/** An overlay the player imported: its folder under [OverlayStore.dir], the name shown, and its layers' images. */
class StoredOverlay(val id: String, val name: String, val layers: List<Layer>) {
    class Layer(val file: Path, val viewport: Viewport?, val w: Int, val h: Int)

    /** The layer made for a view of [aspect] (w / h): the one whose image is closest to that shape (a pack's
     * landscape and portrait versions). */
    fun layerFor(aspect: Float): Layer? = layers.minByOrNull { abs(it.w.toFloat() / it.h - aspect) }
}

/**
 * The imported overlays: one folder each under `<files>/overlays/`, an `overlay.cfg` (RetroArch's format, rewritten
 * with its images flat beside it) and those PNGs. Imports read RetroArch .cfg files (with their images: picked
 * together, read beside the .cfg when its real path is readable, or picked when asked for), .zip packs and bare PNGs.
 */
class OverlayStore(root: Path) {
    val dir = root / "overlays"

    fun list(): List<StoredOverlay> =
        dir.listFiles().filter { it.isDirectory }.mapNotNull(::read).sortedBy { it.name.lowercase() }

    fun get(id: String): StoredOverlay? = if (!safeId(id)) null else (dir / id).takeIf { it.isDirectory }?.let(::read)

    fun delete(id: String): Boolean {
        if (!safeId(id) || !(dir / id).isDirectory) return false
        return runCatching { (dir / id).deleteRecursively(); true }.getOrDefault(false)
    }

    /** Every imported overlay gone (REMOVE ALL); how many there were. */
    fun deleteAll(): Int = list().count { delete(it.id) }

    private fun read(folder: Path): StoredOverlay? {
        val cfg = (folder / CFG).takeIf { it.isFile } ?: return null
        val text = runCatching { cfg.readText() }.getOrNull() ?: return null
        val layers = OverlayCfg.parse(text).mapNotNull { l ->
            val f = (folder / l.image).takeIf { it.isFile && it.parent == folder } ?: return@mapNotNull null
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
     * yet - then [Result.NeedsImage]), or - when there's no .cfg among them - PNGs, each one an overlay of its own. */
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
            val layers = OverlayCfg.parse(cfg.bytes.decodeToString())
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
        // With .cfg files the PNGs are their images, nothing more: a pack's other PNGs (button art, previews, a
        // layout's spare images) once each became an overlay of their own. Only PNGs picked without any .cfg are
        // overlays by themselves.
        if (cfgs.isEmpty()) for (png in pngs) {
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
        val real = cfg.realPath?.toPath()?.parent?.let { it / image } ?: return null
        return runCatching { real.takeIf { it.isFile && it.length <= MAX_IMAGE }?.readBytes() }.getOrNull()
            ?.takeIf { PngInfo.size(it) != null }
    }

    private fun save(baseName: String, layers: List<Pair<OverlayLayer, ByteArray>>): String {
        dir.mkdirs()
        val base = baseName.lowercase().replace(Regex("[^a-z0-9.-]+"), "-").trim('-', '.').take(48).ifEmpty { "overlay" }
        var id = base
        var n = 2
        while (FileSystem.SYSTEM.exists(dir / id)) id = "$base-${n++}"
        val folder = (dir / id).apply { mkdirs() }
        val written = layers.mapIndexed { i, (l, bytes) ->
            (folder / "$i.png").writeBytes(bytes)
            l.copy(image = "$i.png")
        }
        (folder / CFG).writeText(OverlayCfg.write(displayName(baseName), written))
        return id
    }

    /** The .zip's .cfg and PNG files by their path inside it; null when it's bigger than an overlay pack should be. */
    private fun unzip(bytes: ByteArray): List<Entry>? {
        val out = ArrayList<Entry>()
        val temp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-overlay-${Random.nextLong().toULong()}.zip"
        try {
            temp.writeBytes(bytes)
            val zip = FileSystem.SYSTEM.openZip(temp)
            var total = 0L
            var count = 0
            for (entry in zip.listRecursively("/".toPath())) {
                if (++count > MAX_ZIP_ENTRIES) return null
                if (zip.metadataOrNull(entry)?.isRegularFile != true) continue
                val path = entry.toString().removePrefix("/").replace('\\', '/')
                val lower = path.lowercase()
                if (!lower.endsWith(".cfg") && !lower.endsWith(".png")) continue
                if (path.split('/').any { it.startsWith(".") || it == "__MACOSX" }) continue
                val limit = if (lower.endsWith(".cfg")) MAX_CFG else MAX_IMAGE
                val data = zip.source(entry).buffer().use { source ->
                    val buffer = Buffer()
                    while (true) {
                        if (source.read(buffer, 64L * 1024) < 0) break
                        if (buffer.size > limit) return@use null
                    }
                    buffer.readByteArray()
                } ?: continue
                total += data.size
                if (total > MAX_ZIP_TOTAL) return null
                out += Entry(path, data)
            }
        } catch (_: Exception) {
            return emptyList()
        } finally { FileSystem.SYSTEM.delete(temp, mustExist = false) }
        return out
    }

    companion object {
        const val CFG = "overlay.cfg"
        /** One overlay image at most (a 4K border is ~5 MB). */
        const val MAX_IMAGE = 32L * 1024 * 1024
        const val MAX_PICKED_FILE = 64L * 1024 * 1024

        /** Read a native pick off the UI thread, with the same cap as Android's document stream. */
        fun readPicked(file: Path): Picked? = FileSystem.SYSTEM.source(file).use {
            readPicked(file.name, it, file.toString())
        }

        fun readPicked(name: String, source: Source, path: String?): Picked? {
            val buffer = Buffer()
            while (source.read(buffer, 64L * 1024) >= 0) {
                if (buffer.size > MAX_PICKED_FILE) return null
            }
            return Picked(name, buffer.readByteArray(), path)
        }

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
    data class BuiltIn(val style: BuiltInFrames.Style) : OverlayChoice() { override val key = BuiltInFrames.key(style) }
    data class Imported(val id: String) : OverlayChoice() { override val key = "user:$id" }

    companion object {
        fun of(key: String?): OverlayChoice = when {
            key.isNullOrEmpty() -> None
            key.startsWith("builtin:") -> BuiltInFrames.style(key)?.let(::BuiltIn) ?: None
            key.startsWith("user:") -> Imported(key.removePrefix("user:"))
            else -> None
        }

        /** Every choice there is: none, the built-in frames, then the player's imports (by name). */
        fun all(store: OverlayStore): List<OverlayChoice> =
            listOf(None) + BuiltInFrames.Style.entries.map(::BuiltIn) + store.list().map { Imported(it.id) }
    }
}
