package com.pokedaisy.app.companion.data

/**
 * pokeemerald-expansion's "smol" graphics compression (tANS entropy coding +
 * LZ-style instructions over u16 symbols). Newer expansion hacks (Heart and
 * Soul v2.0.6) store item icon tiles this way instead of LZ77. A plain,
 * un-unrolled port of SmolDecompressData() in the expansion's
 * src/decompress.c - it produces the same bytes, it just isn't fast.
 *
 * Header (8 bytes): u32 mode:4 | imageSize:14 | symSize:14, then
 * u32 initialState:6 | bitstreamSize:13 | loSize:13. Output is imageSize*4
 * bytes. Mode 0 is LZ77 (its 0x10 id byte has a zero low nibble), 7 is a
 * frame container (unused here), 8 a tilemap ([decompressTilemap]).
 */
object Smol {

    private const val TANS_TABLE_SIZE = 64

    /** True if [src] starts with a smol data header (modes 1..6). */
    fun isSmol(src: ByteArray): Boolean = src.size >= 8 && (src[0].toInt() and 0x0F) in 1..6

    fun decompress(src: ByteArray): ByteArray {
        val h0 = u32(src, 0)
        val h1 = u32(src, 4)
        val mode = (h0 and 0xF).toInt()
        require(mode in 1..6) { "not smol (mode $mode)" }
        val imageSize = ((h0 shr 4) and 0x3FFF).toInt()
        val symSize = ((h0 shr 18) and 0x3FFF).toInt()
        val bitstreamSize = ((h1 shr 6) and 0x1FFF).toInt()
        val loSize = ((h1 shr 19) and 0x1FFF).toInt()
        if (loSize == 0 || symSize == 0) return ByteArray(0)

        val data = 8
        val loEncoded = mode == 4 || mode == 5 || mode == 6
        val symEncoded = mode == 2 || mode == 3 || mode == 5 || mode == 6
        val symDelta = mode == 3 || mode == 6
        var loFreqs = 0
        var symFreqs = 0
        var streamWord = 0
        when (mode) {
            4 -> { loFreqs = data; streamWord = 3 }
            2, 3 -> { symFreqs = data; streamWord = 3 }
            5, 6 -> { loFreqs = data; symFreqs = data + 12; streamWord = 6 }
        }

        val reader = BitReader(src, data, streamWord, (h1 and 0x3F).toInt())
        var leftover = data
        var lo = ByteArray(loSize)
        var sym = IntArray(symSize)
        if (loEncoded) {
            val table = buildTable(src, loFreqs)
            for (i in 0 until loSize) {
                val a = reader.next(table)
                val b = reader.next(table)
                lo[i] = (a or (b shl 4)).toByte()
            }
            leftover += 12
        }
        if (symEncoded) {
            val table = buildTable(src, symFreqs)
            var cur = 0
            for (i in 0 until symSize) {
                var v = 0
                for (n in 0 until 4) {
                    var x = reader.next(table)
                    if (symDelta) { cur = (cur + x) and 0xF; x = cur }
                    v = v or (x shl (4 * n))
                }
                sym[i] = v
            }
            leftover += 12
        }
        if (loEncoded || symEncoded) leftover += 4 * bitstreamSize
        if (!symEncoded) {
            sym = IntArray(symSize) { u16(src, leftover + 2 * it) }
            leftover += symSize * 2
        }
        if (!loEncoded) lo = src.copyOfRange(leftover, leftover + loSize)

        return decodeInstructions(lo, sym, imageSize * 4)
    }

    /**
     * Mode 8, SmolDecompressTilemap: u32 mode:4 | tilemapSize:14 | symSize:14
     * and u32 instruction-byte count, then the raw u16 symbols (padded to a
     * word) and the instruction bytes. The decoded u16s are deltas: each tile
     * entry is the running sum of them.
     */
    fun decompressTilemap(src: ByteArray): ByteArray {
        val h0 = u32(src, 0)
        require((h0 and 0xF).toInt() == 8) { "not a smol tilemap" }
        val size = ((h0 shr 4) and 0x3FFF).toInt()
        val symSize = ((h0 shr 18) and 0x3FFF).toInt()
        val loSize = u32(src, 4).toInt()
        val sym = IntArray(symSize) { u16(src, 8 + 2 * it) }
        val loAt = 8 + symSize * 2 + 2 * (symSize % 2)
        val out = decodeInstructions(src.copyOfRange(loAt, loAt + loSize), sym, size)
        var acc = 0
        for (w in 0 until size / 2) {
            acc = (acc + u16(out, 2 * w)) and 0xFFFF
            out[2 * w] = acc.toByte()
            out[2 * w + 1] = (acc shr 8).toByte()
        }
        return out
    }

    /** Any smol blob: a tilemap (mode 8) or tile data (modes 1..6). */
    fun decompressAny(src: ByteArray): ByteArray =
        if (src.isNotEmpty() && (src[0].toInt() and 0x0F) == 8) decompressTilemap(src) else decompress(src)

    // Each of 16 symbols gets freq[s] consecutive table slots j = f..2f-1;
    // slot j's k = bits to read (6 - floor(log2 j)), y = (j << k) - 64.
    private fun buildTable(src: ByteArray, off: Int): Array<IntArray> {
        val freqs = IntArray(16)
        for (i in 0 until 3) {
            val w = u32(src, off + 4 * i)
            for (j in 0 until 5) freqs[i * 5 + j] = ((w shr (6 * j)) and 0x3F).toInt()
            freqs[15] += ((w and 0xC0000000L) shr (30 - 2 * i)).toInt()
        }
        val table = ArrayList<IntArray>(TANS_TABLE_SIZE)
        for (s in 0 until 16) {
            for (j in freqs[s] until 2 * freqs[s]) {
                var k = 0
                while ((j shl k) < TANS_TABLE_SIZE) k++
                table.add(intArrayOf(s, k, (j shl k) - TANS_TABLE_SIZE, (1 shl k) - 1))
            }
        }
        require(table.size == TANS_TABLE_SIZE) { "bad tANS frequencies" }
        return table.toTypedArray()
    }

    // One continuous little-endian u32 bitstream shared by the lo and symbol
    // decodes; the tANS state carries over between them, like the C statics.
    private class BitReader(val src: ByteArray, val base: Int, var word: Int, var state: Int) {
        var bitIndex = 0
        var bits = u32(src, base + 4 * word)

        fun next(table: Array<IntArray>): Int {
            val (sym, k, y, mask) = table[state]
            state = y + ((bits ushr bitIndex) and mask.toLong()).toInt()
            bitIndex += k
            if (bitIndex >= 32) {
                word++
                bits = u32(src, base + 4 * word)
                bitIndex -= 32
                if (bitIndex != 0) state += ((bits and ((1L shl bitIndex) - 1)) shl (k - bitIndex)).toInt()
            }
            return sym
        }
    }

    // lo holds (length, offset) pairs, 7 bits per byte + continue bit. A
    // nonzero length emits one symbol then copies `length` values from
    // `offset` back (offset 1 = run); length 0 emits `offset` literal symbols.
    private fun decodeInstructions(lo: ByteArray, sym: IntArray, outBytes: Int): ByteArray {
        val out = IntArray(outBytes / 2 + 1)
        var n = 0
        var i = 0
        var si = 0
        fun b(at: Int) = lo[at].toInt() and 0xFF
        while (i < lo.size && n < out.size) {
            val length: Int
            var offset: Int
            if (b(i) and 0x80 != 0) {
                length = (b(i) and 0x7F) or (b(i + 1) shl 7)
                offset = b(i + 2) and 0x7F
                if (b(i + 2) and 0x80 != 0) { offset = offset or (b(i + 3) shl 7); i += 4 } else i += 3
            } else {
                length = b(i) and 0x7F
                offset = b(i + 1) and 0x7F
                if (b(i + 1) and 0x80 != 0) { offset = offset or (b(i + 2) shl 7); i += 3 } else i += 2
            }
            if (length != 0) {
                out[n++] = sym[si++]
                repeat(length) { out[n] = out[n - offset]; n++ }
            } else {
                repeat(offset) { out[n++] = sym[si++] }
            }
        }
        val bytes = ByteArray(outBytes)
        for (w in 0 until minOf(n, outBytes / 2)) {
            bytes[2 * w] = out[w].toByte()
            bytes[2 * w + 1] = (out[w] shr 8).toByte()
        }
        return bytes
    }

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
