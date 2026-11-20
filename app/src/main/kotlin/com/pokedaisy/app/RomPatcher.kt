package com.pokedaisy.app

import java.io.File
import java.util.zip.CRC32
import okio.Path.Companion.toOkioPath
import okio.source

/**
 * PATCH A ROM (the library's +, Settings > LIBRARY): a BPS patch applied to a ROM the player already has. The patch
 * names its base ROM by size and CRC32, so [findBase] looks through every ROM the library knows for it (no picking);
 * [apply] writes the result beside the player's other games of its kind ([outputDir]), named after the patch.
 * Never touches the base ROM. File work only (no Android): [RomPatcherTest].
 */
object RomPatcher {

    val KNOWN_BASES get() = RomPatchFiles.KNOWN_BASES

    /** What a ROM is, by its header: the extension and the folder names its kind is kept under. */
    enum class Platform(val extension: String, val folders: List<String>) {
        GBA("gba", listOf("gba")),
        GB("gb", listOf("gb", "gbc")),
        GBC("gbc", listOf("gbc", "gb")),
        NDS("nds", listOf("nds")),
    }

    /** [rom]'s kind from its header bytes (a GB cart's logo at 0x104, a DS cart's logo CRC at 0x15C), else GBA. */
    fun platformOf(rom: ByteArray): Platform = when {
        rom.size > 0x150 && (rom[0x104].toInt() and 0xFF) == 0xCE && (rom[0x105].toInt() and 0xFF) == 0xED &&
            rom[0x106].toInt() == 0x66 && rom[0x107].toInt() == 0x66 ->
            if (rom[0x143].toInt() and 0x80 != 0) Platform.GBC else Platform.GB
        rom.size > 0x200 && (rom[0x15C].toInt() and 0xFF) == 0x56 && (rom[0x15D].toInt() and 0xFF) == 0xCF -> Platform.NDS
        else -> Platform.GBA
    }

    /**
     * The first of [candidates] whose ROM is [size] bytes with this [crc] (an archive's ROM counts), or null. Only
     * same-sized files are read, each CRC kept in [cache] by path + size + time; [onProgress] gets 0..1 over them.
     */
    fun findBase(
        candidates: List<File>, size: Long, crc: Long, cache: CrcCache? = null, onProgress: (Float) -> Unit = {},
    ): File? {
        return RomPatchFiles.findBase(candidates.distinctBy { it.absolutePath }, size, crc,
            sizeOf = { if (it.isFile) RomArchive.romSize(it) else -1 },
            cached = { cache?.get(it) != null },
            crcOf = { file -> cache?.get(file) ?: crcOf(file).also { cache?.put(file, it) } },
            onProgress = onProgress)
    }

    /** [rom]'s CRC32 (the ROM inside, for an archive). */
    fun crcOf(rom: File): Long {
        val crc = CRC32()
        RomArchive.open(rom).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    /**
     * Where a patched ROM of [platform] goes: a linked folder named for its kind (`gba`, `gbc`, ...), or such a folder
     * right inside a linked one - the base ROM's own first; else the base ROM's folder when it's in a linked one; else
     * [imported] (the app's `roms/`). Only folders it can write to.
     */
    fun outputDir(platform: Platform, base: File, linked: List<String>, imported: File): File {
        val roots = linked.map(::File)
        fun kindFolders(root: File): List<File> =
            listOf(root) + (root.listFiles { f -> f.isDirectory }?.toList().orEmpty())
        val byKind = platform.folders.flatMap { name ->
            roots.flatMap(::kindFolders).filter { it.name.equals(name, ignoreCase = true) }
        }.filter { it.isDirectory && it.canWrite() }
        val baseDir = base.absoluteFile.parentFile
        byKind.firstOrNull { baseDir != null && isUnder(baseDir, it) }?.let { return it }
        byKind.firstOrNull()?.let { return it }
        if (baseDir != null && baseDir.canWrite() && roots.any { isUnder(baseDir, it) }) return baseDir
        return imported
    }

    private fun isUnder(f: File, root: File): Boolean {
        val p = f.absolutePath
        val r = root.absolutePath
        return p == r || p.startsWith("$r/")
    }

    /** [base]'s kind, from its header (the ROM inside, for an archive): what the patched ROM will be too. */
    fun platformOf(base: File): Platform = platformOf(RomArchive.readAt(base, 0, 0x200) ?: ByteArray(0))

    /** The patched ROM's extension: a Game Boy base's own `.gb` / `.gbc` (Yellow is a `.gb` that's also a Color
     * cart), else [platform]'s. */
    fun extensionFor(base: File, platform: Platform): String {
        val ext = (if (RomArchive.isArchive(base)) RomArchive.romEntry(base)?.extension else base.extension.lowercase()).orEmpty()
        return if ((platform == Platform.GB || platform == Platform.GBC) && ext in GB_EXTENSIONS) ext else platform.extension
    }

    private val GB_EXTENSIONS = setOf("gb", "gbc")

    /** The patched ROM's file: the patch's name with [extension] (`Unbound.bps` -> `Unbound.gba`). */
    fun outputName(patchName: String, extension: String): String = RomPatchFiles.outputName(patchName, extension)

    /** What [apply] did: [file] holds the patched ROM; [existed] = the same ROM was already there, so it was kept. */
    class Result(val file: File, val existed: Boolean)

    /**
     * [patch] applied to [base], written to [dir] as [name] (` (2)` and on if another ROM has that name; the same
     * ROM there already is kept as it is). [onProgress]: 0..1 - reading the base, patching, writing. Throws
     * [BpsPatch.Error] or an IO error; nothing is left behind on a failure.
     */
    fun apply(patch: BpsPatch, base: File, dir: File, name: String, onProgress: (Float) -> Unit = {}): Result {
        val result = RomPatchFiles.apply(patch, dir.toOkioPath(), name,
            readBase = { RomArchive.open(base).source() }, onProgress = onProgress)
        return Result(result.path.toFile(), result.existed)
    }

    /** CRC32s already worked out, by path + size + modified time (`filesDir/rom-crcs.tsv`). */
    class CrcCache(private val file: File) {
        private class Entry(val size: Long, val modified: Long, val crc: Long)
        private val entries: MutableMap<String, Entry> by lazy {
            runCatching {
                file.readLines().mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size != 4) return@mapNotNull null
                    p[0] to Entry(p[1].toLongOrNull() ?: return@mapNotNull null, p[2].toLongOrNull() ?: return@mapNotNull null, p[3].toLongOrNull(16) ?: return@mapNotNull null)
                }.toMap(HashMap())
            }.getOrDefault(HashMap())
        }

        @Synchronized
        fun get(f: File): Long? = entries[f.absolutePath]?.takeIf { it.size == f.length() && it.modified == f.lastModified() }?.crc

        @Synchronized
        fun put(f: File, crc: Long) {
            entries[f.absolutePath] = Entry(f.length(), f.lastModified(), crc)
            // Only files still there: the cache never outgrows the library.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            runCatching {
                tmp.writeText(entries.filterKeys { File(it).isFile }.entries.joinToString("") { (path, e) ->
                    "$path\t${e.size}\t${e.modified}\t${java.lang.Long.toHexString(e.crc)}\n"
                })
                tmp.renameTo(file)
            }
        }
    }
}
