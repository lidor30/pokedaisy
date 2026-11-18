package com.pokedaisy.app.companion.data


/** Shared GBA graphics decode helpers (4bpp tiles, BGR555 palette, BIOS LZ77). */
object Gfx {

    fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    fun inRom(ptr: Long): Boolean = ptr in 0x08000000L until 0x0A000000L

    /**
     * Blit [wTiles]x[hTiles] linear 4bpp tiles from [tiles] (at [tilesOff])
     * through a 16-colour BGR555 palette in [palRaw] (at [palOff]); palette
     * index 0 is transparent.
     */
    fun decode4bpp(
        tiles: ByteArray, tilesOff: Int, palRaw: ByteArray, palOff: Int,
        wTiles: Int, hTiles: Int,
    ): RomArt.Image {
        val pal = IntArray(16)
        for (i in 0 until 16) {
            val c = (palRaw[palOff + i * 2].toInt() and 0xFF) or
                ((palRaw[palOff + i * 2 + 1].toInt() and 0xFF) shl 8)
            var r = (c and 0x1F) shl 3
            var g = ((c shr 5) and 0x1F) shl 3
            var b = ((c shr 10) and 0x1F) shl 3
            r = r or (r shr 5); g = g or (g shr 5); b = b or (b shr 5)
            pal[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val w = wTiles * 8
        val h = hTiles * 8
        val px = IntArray(w * h)
        var tile = 0
        for (ty in 0 until hTiles) {
            for (tx in 0 until wTiles) {
                val base = tilesOff + tile * 32
                for (row in 0 until 8) {
                    for (col in 0 until 8) {
                        if (base + row * 4 + col / 2 >= tiles.size) continue
                        val byteVal = tiles[base + row * 4 + col / 2].toInt() and 0xFF
                        val idx = if (col % 2 == 0) byteVal and 0x0F else byteVal shr 4
                        px[(ty * 8 + row) * w + (tx * 8 + col)] = if (idx == 0) 0 else pal[idx]
                    }
                }
                tile++
            }
        }
        return RomArt.Image(w, h, px)
    }

    /**
     * GBA BIOS LZ77 (type 0x10): 4-byte header then flag-grouped literal /
     * back-reference tokens. [src] may be longer than the compressed stream.
     */
    fun lz77(src: ByteArray): ByteArray {
        require(src.size >= 4 && src[0].toInt() and 0xFF == 0x10) { "not LZ77" }
        val size = (src[1].toInt() and 0xFF) or ((src[2].toInt() and 0xFF) shl 8) or
            ((src[3].toInt() and 0xFF) shl 16)
        val out = ByteArray(size)
        var outPos = 0
        var p = 4
        while (outPos < size) {
            val flags = src[p].toInt() and 0xFF; p++
            var bit = 0
            while (bit < 8 && outPos < size) {
                if (flags and (0x80 shr bit) == 0) {
                    out[outPos++] = src[p]; p++
                } else {
                    val b0 = src[p].toInt() and 0xFF
                    val b1 = src[p + 1].toInt() and 0xFF
                    p += 2
                    val n = (b0 shr 4) + 3
                    val disp = (((b0 and 0x0F) shl 8) or b1) + 1
                    for (i in 0 until n) {
                        if (outPos >= size) break
                        out[outPos] = out[outPos - disp]
                        outPos++
                    }
                }
                bit++
            }
        }
        return out
    }
}
