package com.pokedaisy.app

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * ROMs packed in `.zip` / `.7z` archives, the way RetroArch and most frontends
 * keep them. Everything that only reads a ROM's bytes (game code, hashes, the
 * companion check) goes through [open] / [romSize] / [readAt], which stream
 * the ROM out of an archive without unpacking it. mGBA (built without zlib)
 * and the ROM readers that seek ([RomArt], the FF music renderer, ...) need a
 * real file, so playing an archive extracts it once into the cache
 * ([playable]). An archive's cover is keyed on its own file name; its library
 * name and save on the ROM inside it ([baseName], as RetroArch). Imports unpack instead
 * ([sniff] + [extract]): the library keeps the plain ROM.
 */
object RomArchive {

    enum class Format { ZIP, SEVEN_Z }

    /** File extensions read as archives. */
    val EXTENSIONS = setOf("zip", "7z")
    /** What a ROM inside an archive may be called. */
    val ROM_EXTENSIONS: Set<String> get() = setOf("gba", "agb", "bin") +
        (if (com.pokedaisy.app.companion.data.TelemetrySampler.GAME_BOY_SUPPORT) setOf("gb", "gbc") else emptySet()) +
        (if (com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT) setOf("nds") else emptySet())
    private const val MIN_ROM_BYTES = 0x200L
    /** A GBA ROM is 32 MB at most, a DS cart 512 MB. */
    private const val MAX_ROM_BYTES = 512L shl 20
    /** How many extracted ROMs [playable] keeps; older ones go. */
    private const val KEEP_EXTRACTED = 3
    private val SEVEN_Z_MAGIC = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    /** A ROM inside an archive: its path there and uncompressed size. */
    class Entry(val name: String, val size: Long) {
        val extension get() = name.substringAfterLast('.', "").lowercase()
    }

    /** [f]'s archive format by its extension (library files keep theirs). */
    fun formatOf(f: File): Format? = when (f.extension.lowercase()) {
        "zip" -> Format.ZIP
        "7z" -> Format.SEVEN_Z
        else -> null
    }

    fun isArchive(f: File): Boolean = formatOf(f) != null

    /** [f]'s archive format by its first bytes - for copies whose name says nothing (imports). */
    fun sniff(f: File): Format? = runCatching {
        val b = ByteArray(SEVEN_Z_MAGIC.size)
        val n = f.inputStream().use { readFully(it, b) }
        when {
            n >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte() -> Format.ZIP
            n == b.size && b.contentEquals(SEVEN_Z_MAGIC) -> Format.SEVEN_Z
            else -> null
        }
    }.getOrNull()

    /** The ROM in [archive]: a `.gba` before an `.agb` / `.bin`, then the biggest; null if none. */
    fun romEntry(archive: File, format: Format? = formatOf(archive)): Entry? = runCatching {
        when (format) {
            Format.ZIP -> zipFile(archive).use { z ->
                pick(z.entries().asSequence().filter { !it.isDirectory }.map { Entry(it.name, it.size) })
            }
            Format.SEVEN_Z -> sevenZ(archive).use { z ->
                pick(z.entries.asSequence().filter { !it.isDirectory && it.hasStream() }.map { Entry(it.name, it.size) })
            }
            null -> null
        }
    }.getOrNull()

    /** Skips macOS's `__MACOSX/` and `._` junk and anything too small or big to be a ROM (GB carts start at 32 KB). */
    private fun pick(entries: Sequence<Entry>): Entry? = entries.filter { e ->
        e.extension in ROM_EXTENSIONS && !e.name.substringAfterLast('/').startsWith(".") &&
            !e.name.startsWith("__MACOSX/") && e.size in MIN_ROM_BYTES..MAX_ROM_BYTES
    }.maxWithOrNull(compareBy<Entry>({ it.extension == "gba" }, { it.extension == "gbc" || it.extension == "gb" }, { it.size }))

    /** [entry]'s bytes; closing the stream closes the archive. */
    private fun openEntry(archive: File, entry: Entry, format: Format): InputStream = when (format) {
        Format.ZIP -> {
            val z = zipFile(archive)
            try {
                val e = z.getEntry(entry.name) ?: throw IOException("${entry.name} is gone from ${archive.name}")
                ClosingStream(z.getInputStream(e), z)
            } catch (t: Throwable) {
                z.close(); throw t
            }
        }
        Format.SEVEN_Z -> {
            val z = sevenZ(archive)
            try {
                val e = z.entries.firstOrNull { it.name == entry.name } ?: throw IOException("${entry.name} is gone from ${archive.name}")
                ClosingStream(z.getInputStream(e), z)
            } catch (t: Throwable) {
                z.close(); throw t
            }
        }
    }

    /** [rom]'s bytes: the file's own, or the ROM inside it for an archive. */
    fun open(rom: File): InputStream {
        val format = formatOf(rom) ?: return rom.inputStream()
        val entry = romEntry(rom, format) ?: throw IOException("No GBA ROM in ${rom.name}")
        return openEntry(rom, entry, format)
    }

    /** [rom]'s size as a ROM (an archive's ROM uncompressed); -1 if there's none. */
    fun romSize(rom: File): Long =
        if (isArchive(rom)) romEntry(rom)?.size ?: -1 else rom.length()

    /** Up to [len] bytes of [rom] from [offset] (cheap for an archive too: only its start is unpacked). */
    fun readAt(rom: File, offset: Long, len: Int): ByteArray? = runCatching {
        open(rom).use { s ->
            var left = offset
            while (left > 0) {
                val n = s.skip(left)
                if (n <= 0) { if (s.read() < 0) return null; left-- } else left -= n
            }
            val buf = ByteArray(len)
            val n = readFully(s, buf)
            if (n == len) buf else buf.copyOf(n)
        }
    }.getOrNull()

    private val nameCache = HashMap<String, Pair<Long, List<String>>>()

    /**
     * The names [rom]'s save may go by, the one a new save gets first: the
     * file's name without its extension; for an archive the ROM inside it's
     * first - RetroArch names a zipped game's save after the file in the zip
     * (`Pack.zip` holding `FireRed.gba` -> `FireRed.srm`) - then the archive's
     * own (`FireRed.gba.zip` -> `FireRed.gba`, `FireRed`). Cached per archive.
     */
    fun saveNames(rom: File): List<String> {
        if (!isArchive(rom)) return listOf(rom.nameWithoutExtension)
        val stamp = rom.lastModified() * 31 + rom.length()
        synchronized(nameCache) { nameCache[rom.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second } }
        val own = rom.nameWithoutExtension
        val names = listOfNotNull(
            romEntry(rom)?.name?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() },
            own.takeIf { it.substringAfterLast('.', "").lowercase() in ROM_EXTENSIONS }?.substringBeforeLast('.'),
            own,
        ).distinct()
        synchronized(nameCache) { nameCache[rom.absolutePath] = stamp to names }
        return names
    }

    /** [rom]'s name as a game - the library's default name, and its new save's: see [saveNames]. */
    fun baseName(rom: File): String = saveNames(rom).first()

    /**
     * Writes [archive]'s ROM to [out] (through a `.part` file, so a cut-short
     * copy never looks finished). The entry, or null when there's no ROM in it
     * or it couldn't be read.
     */
    fun extract(archive: File, out: File, format: Format? = formatOf(archive)): Entry? {
        format ?: return null
        val entry = romEntry(archive, format) ?: return null
        val tmp = File(out.parentFile, "${out.name}.part")
        return try {
            openEntry(archive, entry, format).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (tmp.length() != entry.size) throw IOException("short read: ${tmp.length()} of ${entry.size}")
            out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            entry
        } catch (t: Throwable) {
            tmp.delete()
            null
        }
    }

    /**
     * A file the emulator can load for [rom]: [rom] itself, or for an archive
     * its ROM extracted under [cacheRoot] (reused while the archive's path,
     * size and date stay the same; the [KEEP_EXTRACTED] most recently played
     * are kept), named [baseName]. Blocking (unpacks up to 32 MB the first time); null
     * if the archive holds no ROM or can't be read.
     */
    @Synchronized
    fun playable(rom: File, cacheRoot: File): File? {
        if (!isArchive(rom)) return rom
        val root = File(cacheRoot, "rom-archives")
        val pathCrc = CRC32().apply { update(rom.absolutePath.toByteArray()) }.value
        val dir = File(root, "%08x-%d-%d".format(pathCrc, rom.length(), rom.lastModified()))
        val now = System.currentTimeMillis()
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.firstOrNull()?.let {
            dir.setLastModified(now)
            return it
        }
        val entry = romEntry(rom) ?: return null
        dir.mkdirs()
        val out = File(dir, "${baseName(rom)}.${entry.extension}")
        if (extract(rom, out) == null) {
            dir.deleteRecursively()
            return null
        }
        dir.setLastModified(now)
        // Older extractions of this same archive (it changed) and the least recently played others.
        root.listFiles { f -> f.isDirectory && f != dir }?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, old ->
            if (old.name.startsWith("%08x-".format(pathCrc)) || i >= KEEP_EXTRACTED - 1) old.deleteRecursively()
        }
        return out
    }

    /**
     * [archive] as a zip. Names are UTF-8 when the zip says so (or are plain ASCII); Windows' own zip and older tools
     * write them in the DOS code page with no flag, which java.util.zip refuses as UTF-8 ("Pokémon" made the whole
     * archive unreadable, GitHub #1073), so such an archive is opened again reading its unflagged names as Cp437.
     */
    private fun zipFile(archive: File): ZipFile {
        val z = try {
            ZipFile(archive)
        } catch (e: java.util.zip.ZipException) {
            return ZipFile(archive, Charset.forName("Cp437"))
        }
        // Android may decode the names only as they're listed.
        return try {
            z.entries().asSequence().forEach { it.name }
            z
        } catch (e: IllegalArgumentException) {
            z.close()
            ZipFile(archive, Charset.forName("Cp437"))
        }
    }

    private fun sevenZ(archive: File): SevenZFile = SevenZFile.builder().setFile(archive).get()

    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = s.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private class ClosingStream(input: InputStream, private val owner: Closeable) : FilterInputStream(input) {
        override fun close() {
            try { super.close() } finally { owner.close() }
        }
    }
}
