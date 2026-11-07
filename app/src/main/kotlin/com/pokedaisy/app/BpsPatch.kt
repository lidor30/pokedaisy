package com.pokedaisy.app

import java.util.zip.CRC32

/**
 * A BPS patch (byuu's format, what most ROM hacks ship as): `BPS1`, the source / target sizes, a metadata string,
 * the actions, then the source, target and patch CRC32s. The source CRC is what finds the ROM a patch is for
 * ([RomPatcher.findBase]); the target CRC checks the result. Pure, no Android: [BpsPatchTest].
 */
class BpsPatch private constructor(
    private val bytes: ByteArray,
    val sourceSize: Long,
    val targetSize: Long,
    /** The patch's own notes (often empty, sometimes XML), trimmed. */
    val metadata: String,
    private val actionsStart: Int,
    val sourceCrc: Long,
    val targetCrc: Long,
) {
    /** Why a patch couldn't be read or applied. */
    class Error(val reason: Reason) : Exception(reason.name)

    enum class Reason { NOT_BPS, DAMAGED, WRONG_SOURCE, TOO_BIG, BAD_RESULT }

    /**
     * [source] patched. Checks [source] against the patch's size and CRC first, the result against the target's
     * after. [onProgress] gets 0..1 as the result is written (at most ~100 times).
     */
    fun apply(source: ByteArray, onProgress: (Float) -> Unit = {}): ByteArray {
        if (source.size.toLong() != sourceSize || crc32(source, source.size) != sourceCrc) throw Error(Reason.WRONG_SOURCE)
        if (targetSize > MAX_BYTES) throw Error(Reason.TOO_BIG)
        val target = ByteArray(targetSize.toInt())
        val end = bytes.size - FOOTER
        val c = Cursor(bytes, actionsStart, end)
        var out = 0
        var sourceRel = 0L
        var targetRel = 0L
        val step = maxOf(1, target.size / 100)
        var nextReport = step
        while (c.at < end) {
            val data = c.number()
            val length = (data shr 2) + 1
            if (out + length > target.size) throw Error(Reason.DAMAGED)
            val n = length.toInt()
            when ((data and 3).toInt()) {
                SOURCE_READ -> {
                    if (out + n > source.size) throw Error(Reason.DAMAGED)
                    System.arraycopy(source, out, target, out, n)
                }
                TARGET_READ -> {
                    if (c.at + n > end) throw Error(Reason.DAMAGED)
                    System.arraycopy(bytes, c.at, target, out, n)
                    c.at += n
                }
                SOURCE_COPY -> {
                    val d = c.number()
                    sourceRel += if (d and 1L != 0L) -(d shr 1) else d shr 1
                    if (sourceRel < 0 || sourceRel + n > source.size) throw Error(Reason.DAMAGED)
                    System.arraycopy(source, sourceRel.toInt(), target, out, n)
                    sourceRel += n
                }
                else -> {   // TARGET_COPY: may overlap what it writes (a run), so byte by byte.
                    val d = c.number()
                    targetRel += if (d and 1L != 0L) -(d shr 1) else d shr 1
                    if (targetRel < 0 || targetRel >= out) throw Error(Reason.DAMAGED)
                    var from = targetRel.toInt()
                    for (i in 0 until n) target[out + i] = target[from++]
                    targetRel += n
                }
            }
            out += n
            if (out >= nextReport) {
                onProgress(out.toFloat() / target.size)
                nextReport = out + step
            }
        }
        if (out != target.size || crc32(target, target.size) != targetCrc) throw Error(Reason.BAD_RESULT)
        onProgress(1f)
        return target
    }

    companion object {
        /** In memory, source and result together: a GBA ROM (32 MB) with room to spare, not a big DS cart. */
        const val MAX_BYTES = 128L shl 20
        private const val FOOTER = 12
        private const val SOURCE_READ = 0
        private const val TARGET_READ = 1
        private const val SOURCE_COPY = 2

        /** "BPS1", the file's first four bytes. */
        fun looksLikeBps(head: ByteArray): Boolean =
            head.size >= 4 && head[0] == 'B'.code.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'S'.code.toByte() && head[3] == '1'.code.toByte()

        /** [bytes] read as a patch, its own CRC checked (a cut-off download fails here, not halfway). */
        fun parse(bytes: ByteArray): BpsPatch {
            if (!looksLikeBps(bytes)) throw Error(Reason.NOT_BPS)
            if (bytes.size < 4 + 3 + FOOTER) throw Error(Reason.DAMAGED)
            val end = bytes.size - FOOTER
            if (crc32(bytes, bytes.size - 4) != u32(bytes, bytes.size - 4)) throw Error(Reason.DAMAGED)
            val c = Cursor(bytes, 4, end)
            val sourceSize = c.number()
            val targetSize = c.number()
            val metaSize = c.number()
            if (metaSize > end - c.at) throw Error(Reason.DAMAGED)
            val meta = String(bytes, c.at, metaSize.toInt(), Charsets.UTF_8).trim()
            c.at += metaSize.toInt()
            return BpsPatch(bytes, sourceSize, targetSize, meta, c.at, u32(bytes, end), u32(bytes, end + 4))
        }

        fun crc32(b: ByteArray, len: Int): Long = CRC32().apply { update(b, 0, len) }.value

        private fun u32(b: ByteArray, i: Int): Long =
            (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)
    }

    /** Reads the patch's numbers: 7 bits a byte, the last one's top bit set, each byte past the first adding one more step. */
    private class Cursor(val bytes: ByteArray, var at: Int, val end: Int) {
        fun number(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                if (at >= end) throw Error(Reason.DAMAGED)
                val x = bytes[at++].toInt() and 0xFF
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) return data
                shift = shift shl 7
                data += shift
                if (shift > (1L shl 56)) throw Error(Reason.DAMAGED)
            }
        }
    }
}
