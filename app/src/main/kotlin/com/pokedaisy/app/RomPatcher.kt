package com.pokedaisy.app

import java.io.File
import java.util.zip.CRC32

/**
 * PATCH A ROM (the library's +, Settings > LIBRARY): a BPS patch applied to a ROM the player already has. The patch
 * names its base ROM by size and CRC32, so [findBase] looks through every ROM the library knows for it (no picking);
 * [apply] writes the result beside the player's other games of its kind ([outputDir]), named after the patch.
 * Never touches the base ROM. File work only (no Android): [RomPatcherTest].
 */
object RomPatcher {

    /** Base ROMs a patch is often for, by CRC32 (checked on the player's own dumps): the name "not found" gives. */
    val KNOWN_BASES: Map<Long, String> = mapOf(
        0xDD88761CL to "Pokémon FireRed (USA)",
        0x84EE4776L to "Pokémon FireRed (USA, Europe) (Rev 1)",
        0xD69C96CCL to "Pokémon LeafGreen (USA)",
        0xDAFFECECL to "Pokémon LeafGreen (USA, Europe) (Rev 1)",
        0x1F1C08FBL to "Pokémon Emerald (USA, Europe)",
        0x61641576L to "Pokémon Ruby (USA, Europe) (Rev 1)",
        0xAEAC73E6L to "Pokémon Ruby (USA, Europe) (Rev 2)",
        0xBAFEDAE5L to "Pokémon Sapphire (USA, Europe) (Rev 1)",
        0x9CC4410EL to "Pokémon Sapphire (USA, Europe) (Rev 2)",
        0x9F7FDD53L to "Pokémon Red (USA, Europe)",
        0xD6DA8A1AL to "Pokémon Blue (USA, Europe)",
        0x7D527D62L to "Pokémon Yellow (USA, Europe)",
        0xEE6F5188L to "Pokémon Crystal (USA)",
        0x3358E30AL to "Pokémon Crystal (USA, Europe) (Rev 1)",
        0xBB6DD80CL to "Pokémon Crystal (Australia)",
        0x6BDE3C3EL to "Pokémon Gold (USA, Europe)",
        0x8AD48636L to "Pokémon Silver (USA, Europe)",
    )

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
        val sized = candidates.distinctBy { it.absolutePath }
            .filter { it.isFile && runCatching { RomArchive.romSize(it) }.getOrDefault(-1L) == size }
        // Ones already known first: a second look for the same patch is instant.
        val ordered = sized.sortedBy { cache?.get(it) == null }
        ordered.forEachIndexed { i, f ->
            val c = cache?.get(f) ?: runCatching { crcOf(f) }.getOrNull()?.also { cache?.put(f, it) }
            onProgress((i + 1f) / ordered.size)
            if (c == crc) return f
        }
        return null
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
    fun outputName(patchName: String, extension: String): String {
        val base = patchName.substringBeforeLast('.').trim().ifEmpty { "patched" }
        return RomUris.sanitizeFileName("$base.$extension")
    }

    /** What [apply] did: [file] holds the patched ROM; [existed] = the same ROM was already there, so it was kept. */
    class Result(val file: File, val existed: Boolean)

    /**
     * [patch] applied to [base], written to [dir] as [name] (` (2)` and on if another ROM has that name; the same
     * ROM there already is kept as it is). [onProgress]: 0..1 - reading the base, patching, writing. Throws
     * [BpsPatch.Error] or an IO error; nothing is left behind on a failure.
     */
    fun apply(patch: BpsPatch, base: File, dir: File, name: String, onProgress: (Float) -> Unit = {}): Result {
        if (patch.sourceSize + patch.targetSize > BpsPatch.MAX_BYTES) throw BpsPatch.Error(BpsPatch.Reason.TOO_BIG)
        onProgress(0f)
        val source = RomArchive.open(base).use { it.readBytes() }
        onProgress(0.1f)
        val target = patch.apply(source) { onProgress(0.1f + it * 0.8f) }
        dir.mkdirs()
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var out = File(dir, name)
        var n = 2
        while (out.exists()) {
            if (out.length() == target.size.toLong() && runCatching { crcOf(out) }.getOrNull() == patch.targetCrc) {
                onProgress(1f)
                return Result(out, existed = true)
            }
            out = File(dir, "$stem ($n).$ext")
            n++
        }
        val tmp = File(dir, ".${out.name}.part")
        try {
            tmp.outputStream().use { s ->
                val chunk = 1 shl 20
                var at = 0
                while (at < target.size) {
                    val len = minOf(chunk, target.size - at)
                    s.write(target, at, len)
                    at += len
                    onProgress(0.9f + 0.1f * at / target.size)
                }
            }
            if (!tmp.renameTo(out)) throw java.io.IOException("couldn't name ${out.name}")
        } finally {
            tmp.delete()
        }
        onProgress(1f)
        return Result(out, existed = false)
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
