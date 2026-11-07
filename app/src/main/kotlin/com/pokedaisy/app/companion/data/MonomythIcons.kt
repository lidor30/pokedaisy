package com.pokedaisy.app.companion.data

/**
 * Pokémon Monomyth's packed party icons ([IconTables.monIconPacked]): gMonIconTable points at a blob per species, not at
 * vanilla's raw 1 KB of tiles. Its sprite copy queue hands a frame to an ARM decoder it runs from the stack (0x081C4284,
 * patched by mode), ported here and checked by drawing the user's party (both frames, clean):
 *  - header u32: dictionary tiles (byte 0), sub-tile records (byte 1), the frame count (byte 3), and the per-frame code size
 *    in bits 16-17 (byte, halfword, word: 4 / 8 / 16 tiles, or four words: 64 tiles);
 *  - per frame, 2 bits a tile: 00 empty, 01 a raw tile from the word stream, 10 a tile built row by row from the streams,
 *    11 a byte from the byte stream - a dictionary tile from [sub-tile records] on, else a record whose streams build it;
 *  - a built tile: a u16 mask, 2 bits a row - a word, nothing, a byte or a halfword, the last two at the row's left or right
 *    end by the tile's place in the frame (the bit the mode patches in: tile counter >> shift-1).
 */
internal object MonomythIcons {
    /** Frames 0 and 1 of the icon at [base] (32x64, 4bpp, vanilla's layout), or null when the blob doesn't add up. */
    fun unpack(rd: (Long, Int) -> ByteArray, base: Long): ByteArray? = runCatching {
        val blob = rd(base, BLOB_BYTES)
        frame(blob, 0) + frame(blob, 1)
    }.getOrNull()?.takeIf { it.size == 2 * FRAME_BYTES }

    private fun frame(b: ByteArray, frame: Int): ByteArray {
        fun u8(o: Int) = b[o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u32(o: Int) = (u16(o).toLong() or (u16(o + 2).toLong() shl 16))
        val h = u32(0)
        val dictTiles = (h and 0xFF).toInt()
        val records = ((h shr 8) and 0xFF).toInt()
        val frames = (h ushr 24).toInt()
        var r0 = 4
        val codes: LongArray
        val perCode: Int
        val shift: Int
        when (((h shr 16) and 3).toInt()) {
            0 -> { codes = longArrayOf(u8(r0 + frame).toLong() shl 24); r0 += frames; perCode = 4; shift = 1 }
            1 -> { codes = longArrayOf(u16(r0 + frame * 2).toLong() shl 16); r0 += frames * 2; perCode = 8; shift = 1 }
            2 -> { codes = longArrayOf(u32(r0 + frame * 4)); r0 += frames * 4; perCode = 16; shift = 2 }
            else -> { codes = LongArray(4) { u32(r0 + frame * 16 + 4 * it) }; r0 += frames * 16; perCode = 16; shift = 3 }
        }
        r0 = (r0 + 3) and 3.inv()
        val dict = r0 + records * 4
        val streams = dict + dictTiles * 32
        val table = streams + frame * 6
        val data = (streams + frames * 6 + 3) and 3.inv()
        val out = java.io.ByteArrayOutputStream(FRAME_BYTES)
        fun put32(w: Long) { for (k in 0 until 4) out.write(((w shr (8 * k)) and 0xFF).toInt()) }
        // Word, halfword and byte streams: the frame's own, or a record's for one tile.
        class Streams(var w: Int, var h: Int, var b: Int)
        val main = Streams(data + u16(table + 4) * 4, data + u16(table + 2) * 2, data + u16(table))
        fun built(s: Streams, counter: Int) {
            var mask = u16(s.h).toLong() shl 16; s.h += 2
            val right = (counter shr (shift - 1)) and 1 == 1
            repeat(8) {
                val c = (mask ushr 31) and 1L; val n = (mask ushr 30) and 1L
                mask = (mask shl 2) and 0xFFFFFFFFL
                val w = when {
                    c == 1L && n == 1L -> 0L
                    c == 1L -> u32(s.w).also { s.w += 4 }
                    n == 1L -> u16(s.h).toLong().also { s.h += 2 }.let { if (right) it shl 16 else it }
                    else -> u8(s.b).toLong().also { s.b += 1 }.let { if (right) it shl 24 else it }
                }
                put32(w)
            }
        }
        for (start in codes) {
            var code = start
            for (counter in perCode - 1 downTo 0) {
                val c = (code ushr 31) and 1L; val n = (code ushr 30) and 1L
                code = (code shl 2) and 0xFFFFFFFFL
                when {
                    c == 0L && n == 0L -> out.write(ByteArray(32))
                    c == 0L -> { out.write(b, main.w, 32); main.w += 32 }
                    n == 0L -> built(main, counter)
                    else -> {
                        val ip = u8(main.b); main.b += 1
                        if (ip >= records) out.write(b, dict + (ip - records) * 32, 32)
                        else {
                            val w = u32(r0 + ip * 4)
                            val base = data + (w ushr 16).toInt() * 4
                            built(Streams(base, base + ((w shr 8) and 0xFF).toInt() * 2, base + (w and 0xFF).toInt()), counter)
                        }
                    }
                }
            }
        }
        // Only the first 16 tiles are the 32x32 frame (a 64-tile code's are more than an icon needs).
        return out.toByteArray().copyOf(FRAME_BYTES)
    }

    private const val FRAME_BYTES = 512
    /** Enough for any icon blob (the user's party's are 0x450-0x550 bytes). */
    private const val BLOB_BYTES = 0x1000
}
