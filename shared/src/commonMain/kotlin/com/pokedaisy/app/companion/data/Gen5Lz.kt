package com.pokedaisy.app.companion.data

import okio.FileHandle

/**
 * The DS's compression as Black / White's cart uses it: the BIOS's LZ77 (type 0x10) and its LZ11 variant (0x11) for
 * NARC members (the front pictures), and the backward LZ ("BLZ") the ARM9 binary is packed with ([arm9]).
 */
object Gen5Lz {
    /** An LZ10 / LZ11 stream (its header byte says which), or null for anything else or a broken one. */
    fun decompress(d: ByteArray): ByteArray? {
        if (d.size < 4) return null
        val type = u8(d, 0)
        if (type != 0x10 && type != 0x11) return null
        var size = u8(d, 1) or (u8(d, 2) shl 8) or (u8(d, 3) shl 16)
        var p = 4
        if (size == 0 && d.size >= 8) { size = u32(d, 4).toInt(); p = 8 }
        if (size <= 0 || size > (16 shl 20)) return null
        val out = ByteArray(size)
        var o = 0
        try {
            while (o < size) {
                val flags = u8(d, p++)
                for (bit in 7 downTo 0) {
                    if (o >= size) break
                    if (flags shr bit and 1 == 0) { out[o++] = d[p++]; continue }
                    val n: Int
                    val disp: Int
                    if (type == 0x10) {
                        val v = (u8(d, p) shl 8) or u8(d, p + 1); p += 2
                        n = (v shr 12) + 3; disp = (v and 0xFFF) + 1
                    } else {
                        val a = u8(d, p)
                        when (a shr 4) {
                            0 -> { n = (((a and 0xF) shl 4) or (u8(d, p + 1) shr 4)) + 0x11
                                disp = (((u8(d, p + 1) and 0xF) shl 8) or u8(d, p + 2)) + 1; p += 3 }
                            1 -> { n = (((a and 0xF) shl 12) or (u8(d, p + 1) shl 4) or (u8(d, p + 2) shr 4)) + 0x111
                                disp = (((u8(d, p + 2) and 0xF) shl 8) or u8(d, p + 3)) + 1; p += 4 }
                            else -> { n = (a shr 4) + 1; disp = (((a and 0xF) shl 8) or u8(d, p + 1)) + 1; p += 2 }
                        }
                    }
                    if (disp > o) return null
                    repeat(n) { if (o < size) { out[o] = out[o - disp]; o++ } }
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return out
    }

    /** The cart's ARM9 binary as it sits in RAM (from [ramBase]): unpacked when its module parameters say it's packed. */
    class Arm9(val bytes: ByteArray, val ramBase: Long) {
        /** [len] bytes at RAM address [addr], or null outside the binary. */
        fun at(addr: Long, len: Int): ByteArray? {
            val o = addr - ramBase
            if (o < 0 || o + len > bytes.size) return null
            return bytes.copyOfRange(o.toInt(), o.toInt() + len)
        }
    }

    fun arm9(f: FileHandle, header: ByteArray): Arm9? {
        val off = u32(header, 0x20)
        val ram = u32(header, 0x28)
        val size = u32(header, 0x2C).toInt()
        if (size <= 0 || size > (4 shl 20)) return null
        val raw = f.readFully(off, size)
        // The module parameters sit 0x1C before the nitro code's magic (0xDEC00621, 0x2106C0DE); +0x14 is where the
        // packed part ends in RAM (0: not packed).
        var params = -1
        for (i in 0..size - 8 step 4) {
            if (u32(raw, i) == 0xDEC00621L && u32(raw, i + 4) == 0x2106C0DEL) { params = i - 0x1C; break }
        }
        if (params < 0) return Arm9(raw, ram)
        val end = u32(raw, params + 0x14)
        if (end == 0L) return Arm9(raw, ram)
        val packedEnd = (end - ram).toInt()
        if (packedEnd !in 8..size) return null
        val unpacked = blz(raw.copyOfRange(0, packedEnd)) ?: return null
        return Arm9(unpacked + raw.copyOfRange(packedEnd, size), ram)
    }

    /** Backward LZ: the footer (encoded length | header length << 24, then the size it grows by) closes [d]; it's
     * unpacked from the end towards the front, the bytes before the encoded part left as they are. */
    fun blz(d: ByteArray): ByteArray? {
        if (d.size < 8) return null
        val lenHdr = u32(d, d.size - 8)
        val inc = u32(d, d.size - 4).toInt()
        val hdrLen = (lenHdr ushr 24).toInt()
        val encLen = (lenHdr and 0xFFFFFF).toInt()
        if (inc < 0 || encLen > d.size || hdrLen > encLen) return null
        val out = d.copyOf(d.size + inc)
        var src = d.size - hdrLen
        var dst = out.size
        val end = d.size - encLen
        try {
            while (src > end) {
                var flags = u8(d, --src)
                for (bit in 0 until 8) {
                    if (src <= end) break
                    if (flags and 0x80 != 0) {
                        src -= 2
                        val v = u8(d, src) or (u8(d, src + 1) shl 8)
                        val n = (v shr 12) + 3
                        val disp = (v and 0xFFF) + 3
                        repeat(n) { dst--; out[dst] = out[dst + disp] }
                    } else {
                        out[--dst] = d[--src]
                    }
                    flags = flags shl 1
                }
            }
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return out
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u32(b: ByteArray, o: Int) =
        (u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24))
}
