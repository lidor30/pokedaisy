package com.pokedaisy.app

import android.util.Log
import com.pokedaisy.app.companion.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * RetroArch's compressed saves. With its SaveRAM Compression on (Settings > Saving), RetroArch writes an `.srm` in its
 * RZIP container: `#RZIPv` + version + `#`, the chunk size (u32 LE), the save's size (u64 LE), then chunks, each a u32 LE
 * length and a zlib stream (libretro-common's rzip_stream.c; version 2, in nightlies since 2026-09, holds Zstandard
 * frames instead). Unbound's 128 KB save comes out ~3 KB. mGBA takes the file as the flash chip itself: it grew it to
 * 128 KB, filled the rest with 0xFF, and the game saw garbage and started a new game, its first save writing over the
 * player's (a Discord report on 1.4.0: "a smaller save than expected", LOAD SAVE and renaming to `.sav` didn't help).
 *
 * So a compressed save is unpacked in place before the emulator opens it ([unpackForPlay]), a backup of the
 * compressed file kept first; RetroArch reads a plain save just as well. One that can't be unpacked is never handed to
 * the emulator.
 */
object RzipSave {

    private const val HEADER = 20
    const val DEFLATE = 1
    const val ZSTD = 2

    sealed class Result {
        /** Not an RZIP file: a plain save, used as it is. */
        object NotRzip : Result()
        class Unpacked(val bytes: ByteArray) : Result()
        /** RZIP, but not readable here: [zstd] = RetroArch's newer Zstandard container. */
        class Unreadable(val zstd: Boolean) : Result()
    }

    fun isRzip(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == '#'.code.toByte() && bytes[1] == 'R'.code.toByte() && bytes[2] == 'Z'.code.toByte() &&
            bytes[3] == 'I'.code.toByte() && bytes[4] == 'P'.code.toByte() && bytes[5] == 'v'.code.toByte() &&
            bytes[7] == '#'.code.toByte()

    fun isRzip(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 8) return false
        val head = ByteArray(8)
        file.inputStream().use { if (it.read(head) != 8) return false }
        isRzip(head)
    }.getOrDefault(false)

    /** [bytes] unpacked. Anything after the last chunk is ignored: 1.4.0's mGBA grew such a file to the chip's size
     * with 0xFF, its chunks untouched, so those still unpack to the player's save. */
    fun decode(bytes: ByteArray): Result {
        if (!isRzip(bytes)) return Result.NotRzip
        val version = bytes[6].toInt() and 0xFF
        if (version == ZSTD) return Result.Unreadable(zstd = true)
        if (version != DEFLATE || bytes.size < HEADER) return Result.Unreadable(zstd = false)
        val chunkSize = u32(bytes, 8)
        val total = u32(bytes, 12) or (u32(bytes, 16) shl 32)
        if (chunkSize <= 0 || total <= 0 || total > GameSaves.MAX_BYTES) return Result.Unreadable(zstd = false)
        val out = ByteArrayOutputStream(total.toInt())
        var p = HEADER
        while (out.size() < total) {
            if (p + 4 > bytes.size) return Result.Unreadable(zstd = false)
            val n = u32(bytes, p)
            p += 4
            if (n <= 0 || p + n > bytes.size) return Result.Unreadable(zstd = false)
            val chunk = inflate(bytes, p, n.toInt(), minOf(chunkSize, total - out.size()).toInt())
                ?: return Result.Unreadable(zstd = false)
            out.write(chunk)
            p += n.toInt()
        }
        if (out.size().toLong() != total) return Result.Unreadable(zstd = false)
        return Result.Unpacked(out.toByteArray())
    }

    /** One chunk: a whole zlib stream (RetroArch's deflate keeps zlib's wrapper; a raw one is taken too) giving 1 to
     * [max] bytes. */
    private fun inflate(src: ByteArray, off: Int, len: Int, max: Int): ByteArray? {
        for (raw in listOf(false, true)) {
            val inf = Inflater(raw)
            try {
                inf.setInput(src, off, len)
                val buf = ByteArray(max + 1)
                var got = 0
                while (!inf.finished() && got < buf.size) {
                    val k = inf.inflate(buf, got, buf.size - got)
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    got += k
                }
                if (inf.finished() && got in 1..max) return buf.copyOf(got)
            } catch (_: Exception) {
            } finally {
                inf.end()
            }
        }
        return null
    }

    /** A backup holding exactly [bytes] ([save] as RetroArch left it) is there, made now if it wasn't. SaveBackups'
     * own check skips one equal to the newest but for its last bytes (an RTC footer on a plain save), which a
     * compressed file's tail isn't, so a copy of its own is made then. */
    private fun backedUp(save: File, bytes: ByteArray): Boolean = runCatching {
        fun kept() = SaveBackups.list(save).any { b -> b.length() == bytes.size.toLong() && b.readBytes().contentEquals(bytes) }
        if (kept()) return true
        SaveBackups.snapshot(save, force = true)
        if (kept()) return true
        val dir = SaveBackups.dir(save).apply { mkdirs() }
        val name = GameSaves.backupName(save).substringBeforeLast('.') + "-compressed." + save.extension
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(dir, name)) && kept()
    }.getOrDefault(false)

    /** Logcat, a no-op in the JVM's unit tests (no android.util.Log there). */
    private fun log(message: String) {
        runCatching { Log.i("pokedaisy", message) }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    /** Why a compressed save can't be played here, for the player. */
    fun unreadableMessage(): String =
        tr("This save was compressed by RetroArch in a way PokeDaisy can't read yet, so it was left as it is. In RetroArch, turn off Settings > Saving > SaveRAM Compression, then load the game there and save once.")

    /**
     * Makes [save] a plain save before the emulator opens it, if RetroArch left it compressed: a backup of the
     * compressed file first (whatever SAVE BACKUPS says), then the unpacked bytes written in its place. Returns null when
     * [save] is ready to play (no file, a plain one, or unpacked), else why it can't be played: the file is left alone
     * then, and the caller must not start the game on it. Blocking file I/O.
     */
    fun unpackForPlay(save: File): String? {
        if (!isRzip(save)) return null
        val bytes = runCatching { save.readBytes() }.getOrElse { return unreadableMessage() }
        return when (val r = decode(bytes)) {
            Result.NotRzip -> null
            is Result.Unreadable -> {
                log("compressed save not readable (zstd=${r.zstd}): ${save.name}")
                unreadableMessage()
            }
            is Result.Unpacked -> {
                if (!backedUp(save, bytes)) return tr("Couldn't back up {0}, so it was left as it is", save.name)
                val tmp = File(save.parentFile, "${save.name}.unpack.tmp")
                val ok = runCatching { tmp.writeBytes(r.bytes); tmp.renameTo(save) }.getOrDefault(false)
                if (!ok) {
                    tmp.delete()
                    return tr("Can't write the save to {0}", save.parentFile?.absolutePath ?: save.name)
                }
                log("unpacked RetroArch's compressed save ${save.name}: ${bytes.size} -> ${r.bytes.size} bytes")
                null
            }
        }
    }
}
