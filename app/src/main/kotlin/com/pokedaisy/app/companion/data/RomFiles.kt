package com.pokedaisy.app.companion.data

import okio.EOFException
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.use

/*
 * Reading ROM files with okio (Kotlin Multiplatform) instead of java.io.RandomAccessFile / InputStream and a mapped
 * java.nio buffer, which the iPad build doesn't have.
 */

/** [block] with [rom] open for reading. */
internal inline fun <T> withRomFile(rom: Path, block: (FileHandle) -> T): T = FileSystem.SYSTEM.openReadOnly(rom).use(block)

/** [len] bytes from [off]; throws EOFException where the file is shorter, as RandomAccessFile.readFully did. */
internal fun FileHandle.readFully(off: Long, len: Int): ByteArray {
    val b = ByteArray(len)
    var got = 0
    while (got < len) {
        val n = read(off + got, b, got, len - got)
        if (n < 0) throw EOFException("${len - got} bytes short at ${off + got}")
        got += n
    }
    return b
}

/** Up to [len] bytes from [off]: fewer where the file ends first. */
internal fun FileHandle.readUpTo(off: Long, len: Int): ByteArray {
    val b = ByteArray(len)
    var got = 0
    while (got < len) {
        val n = read(off + got, b, got, len - got)
        if (n <= 0) break
        got += n
    }
    return if (got == len) b else b.copyOf(got)
}

/** The `(offset, length) -> bytes` reader the cart checks take: null where the file is too short. */
internal fun FileHandle.cartReader(): (Long, Int) -> ByteArray? {
    val size = size()
    return { off, len -> if (off < 0 || off + len > size) null else readFully(off, len) }
}

/**
 * A ROM file read little-endian a window at a time, for scans that run through a whole 32 MB file (the old code
 * mapped it; this keeps it off the heap too). Reads are cheap while they stay near the last one.
 */
internal class FileLittleEndian(private val f: FileHandle) : LittleEndianReader {
    private val size = f.size().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val window = ByteArray(WINDOW)
    private var start = 0
    private var end = 0

    override fun limit(): Int = size

    private fun at(i: Int, n: Int): Int {
        if (i < 0 || i > size - n) throw IndexOutOfBoundsException("$n bytes at $i, limit $size")
        if (i < start || i + n > end) {
            start = maxOf(0, i - BACK)
            val got = f.readUpTo(start.toLong(), minOf(WINDOW, size - start))
            got.copyInto(window)
            end = start + got.size
            if (i + n > end) throw IndexOutOfBoundsException("$n bytes at $i: the file ended at $end")
        }
        return i - start
    }

    override fun get(i: Int): Byte = window[at(i, 1)]
    override fun getShort(i: Int): Short { val p = at(i, 2); return ((window[p].toInt() and 0xFF) or (window[p + 1].toInt() shl 8)).toShort() }
    override fun getInt(i: Int): Int = s32le(window, at(i, 4))
    override fun getLong(i: Int): Long { val p = at(i, 8); return (s32le(window, p).toLong() and 0xFFFFFFFFL) or (s32le(window, p + 4).toLong() shl 32) }

    private companion object {
        const val WINDOW = 1 shl 16
        /** Kept behind each new window: the scanners look back a few dozen bytes from where they are. */
        const val BACK = 1 shl 10
    }
}

/** A key that changes when [file] does (its path, size and time), for per-file caches. */
internal fun fileKey(file: Path): String {
    val m = runCatching { FileSystem.SYSTEM.metadataOrNull(file) }.getOrNull()
    return "$file:${m?.size ?: 0}:${m?.lastModifiedAtMillis ?: 0}"
}

/** The whole of [rom], 1 MiB per read: on Android one 32 MiB read holds a JNI critical lock (stalling GC) for ~20 ms. */
internal fun readRomFile(rom: Path): ByteArray = withRomFile(rom) { f ->
    val size = f.size()
    require(size <= Int.MAX_VALUE) { "$rom is $size bytes" }
    val out = ByteArray(size.toInt())
    var o = 0
    while (o < out.size) {
        val n = f.read(o.toLong(), out, o, minOf(1 shl 20, out.size - o))
        if (n <= 0) break
        o += n
    }
    out
}
