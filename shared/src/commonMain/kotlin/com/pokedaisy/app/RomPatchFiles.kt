package com.pokedaisy.app

import com.pokedaisy.app.companion.data.romCrc32
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.Source
import okio.buffer
import okio.use

/** The same checksum lookup and safe output file for the library's patch flows. */
object RomPatchFiles {
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

    private val outputLock = SynchronizedObject()

    fun <T> findBase(candidates: List<T>, size: Long, crc: Long, sizeOf: (T) -> Long,
                     crcOf: (T) -> Long, cached: (T) -> Boolean = { false },
                     onProgress: (Float) -> Unit = {}): T? {
        val sized = candidates.filter { runCatching { sizeOf(it) }.getOrNull() == size }.sortedBy { !cached(it) }
        sized.forEachIndexed { index, candidate ->
            val checksum = runCatching { crcOf(candidate) }.getOrNull()
            onProgress((index + 1f) / sized.size)
            if (checksum == crc) return candidate
        }
        return null
    }

    fun outputName(patchName: String, extension: String): String {
        val base = patchName.substringBeforeLast('.').replace('/', '_').replace('\u0000', '_')
        return "$base.$extension"
    }

    fun readPatch(source: Source): ByteArray = source.use {
        val bytes = Buffer()
        while (bytes.size <= BpsPatch.MAX_BYTES) {
            val count = it.read(bytes, minOf(65536, BpsPatch.MAX_BYTES + 1 - bytes.size))
            if (count == -1L) return bytes.readByteArray()
        }
        throw BpsPatch.Error(BpsPatch.Reason.TOO_BIG)
    }

    data class Result(val path: Path, val existed: Boolean)

    fun apply(patch: BpsPatch, dir: Path, name: String, readBase: () -> Source, onProgress: (Float) -> Unit = {}): Result {
        if (patch.sourceSize > BpsPatch.MAX_BYTES - patch.targetSize) throw BpsPatch.Error(BpsPatch.Reason.TOO_BIG)
        onProgress(0f)
        val source = readBase().buffer().use {
            if (!it.request(patch.sourceSize)) throw BpsPatch.Error(BpsPatch.Reason.WRONG_SOURCE)
            val bytes = it.readByteArray(patch.sourceSize)
            if (!it.exhausted()) throw BpsPatch.Error(BpsPatch.Reason.WRONG_SOURCE)
            bytes
        }
        onProgress(0.1f)
        val target = patch.apply(source) { onProgress(0.1f + it * 0.8f) }
        return synchronized(outputLock) { publish(dir, name, target, patch.targetCrc, onProgress) }
    }

    private fun publish(dir: Path, name: String, bytes: ByteArray, checksum: Long, progress: (Float) -> Unit): Result {
        val files = FileSystem.SYSTEM
        files.createDirectories(dir)
        val extension = name.substringAfterLast('.')
        val stem = name.substringBeforeLast('.')
        var out = dir / name
        var next = 2
        while (files.exists(out)) {
            if (files.metadataOrNull(out)?.size == bytes.size.toLong() && runCatching { romCrc32(out) }.getOrNull() == checksum) {
                progress(1f)
                return Result(out, existed = true)
            }
            out = dir / "$stem (${next++}).$extension"
        }
        val temporary = dir / ".${out.name}.part"
        try {
            files.sink(temporary).buffer().use { sink ->
                var position = 0
                while (position < bytes.size) {
                    val count = minOf(1 shl 20, bytes.size - position)
                    sink.write(bytes, position, count)
                    position += count
                    progress(0.9f + 0.1f * position / bytes.size)
                }
            }
            files.atomicMove(temporary, out)
        } finally {
            runCatching { files.delete(temporary) }
        }
        progress(1f)
        return Result(out, existed = false)
    }
}
