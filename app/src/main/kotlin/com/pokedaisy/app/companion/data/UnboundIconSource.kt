package com.pokedaisy.app.companion.data

import android.graphics.Bitmap
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Serves Pokémon Unbound party-icon sprites by pulling them out of the running
 * game over RetroArch the first time each species is asked for, then caching the
 * decoded Bitmap. Nothing is bundled in the APK: Unbound's gMonIconTable is a
 * flat array of pointers, each to a self-contained record of 1024 bytes of 4bpp
 * tiles (32x64, two frames) + a 32-byte BGR555 palette. Mirrors
 * tools/telemetry-viewer/unbound_sprites.go.
 */
object UnboundIconSource {
    private const val MON_ICON_TABLE = 0x09A217FCL
    private const val ITEM_ICON_TABLE = 0x0825A554L
    private const val MON_TILE_BYTES = 1024
    private const val PAL_BYTES = 32
    private const val ITEM_TILE_BYTES = 3 * 3 * 32 // 288

    private val monCache = LockedMap<Int, Bitmap>()
    private val itemCache = LockedMap<Int, Bitmap>()
    private val lock = SynchronizedObject()

    /** Party icon for [species], or null if unavailable (retries later). */
    /** Swappable so tests / previews can read a ROM file instead of the core. */
    @Volatile
    var reader: MemoryReader = InProcessReader

    /** An already-decoded icon (no ROM read), or null. */
    fun peek(species: Int): Bitmap? = monCache[species]

    fun get(species: Int): Bitmap? = cached(monCache, species) { fetchMon(species) }

    /** Bag icon for [itemId], or null if unavailable. */
    fun getItem(itemId: Int): Bitmap? = cached(itemCache, itemId) { fetchItem(itemId) }

    private inline fun cached(cache: LockedMap<Int, Bitmap>, key: Int, fetch: () -> Bitmap?): Bitmap? {
        if (key <= 0) return null
        cache[key]?.let { return it }
        synchronized(lock) {
            cache[key]?.let { return it }
            val bmp = try {
                fetch()
            } catch (e: Exception) {
                null
            } ?: return null
            cache[key] = bmp
            return bmp
        }
    }

    private fun readPtr(addr: Long): Long {
        val b = reader.readCoreMemory(addr, 4)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readChunked(addr: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = minOf(512, size - off)
            reader.readCoreMemory(addr + off, n).copyInto(out, off, 0, n)
            off += n
        }
        return out
    }

    private fun fetchMon(species: Int): Bitmap? {
        val ptr = readPtr(MON_ICON_TABLE + species * 4L)
        if (ptr < 0x08000000L || ptr >= 0x0A000000L) return null
        val rec = readChunked(ptr, MON_TILE_BYTES + PAL_BYTES)
        return decode(rec, 0, rec, MON_TILE_BYTES, 4, 8)
    }

    private fun fetchItem(itemId: Int): Bitmap? {
        val entry = readChunked(ITEM_ICON_TABLE + itemId * 8L, 8)
        val tilesPtr = (entry[0].toLong() and 0xFF) or ((entry[1].toLong() and 0xFF) shl 8) or
            ((entry[2].toLong() and 0xFF) shl 16) or ((entry[3].toLong() and 0xFF) shl 24)
        val palPtr = (entry[4].toLong() and 0xFF) or ((entry[5].toLong() and 0xFF) shl 8) or
            ((entry[6].toLong() and 0xFF) shl 16) or ((entry[7].toLong() and 0xFF) shl 24)
        if (tilesPtr < 0x08000000L || tilesPtr >= 0x0A000000L) return null
        if (palPtr < 0x08000000L || palPtr >= 0x0A000000L) return null
        val tiles = lz77(readChunked(tilesPtr, 512))
        val pal = lz77(readChunked(palPtr, 96))
        if (tiles.size < ITEM_TILE_BYTES || pal.size < PAL_BYTES) return null
        return decode(tiles, 0, pal, 0, 3, 3)
    }

    /** Blit [wTiles]x[hTiles] linear 4bpp tiles from [tiles] (at [tilesOff])
     *  through a 16-colour BGR555 palette in [palRaw] (at [palOff]); index 0
     *  is transparent. */
    private fun decode(
        tiles: ByteArray, tilesOff: Int, palRaw: ByteArray, palOff: Int,
        wTiles: Int, hTiles: Int,
    ): Bitmap {
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
                        val byteVal = tiles[base + row * 4 + col / 2].toInt() and 0xFF
                        val idx = if (col % 2 == 0) byteVal and 0x0F else byteVal shr 4
                        px[(ty * 8 + row) * w + (tx * 8 + col)] = if (idx == 0) 0 else pal[idx]
                    }
                }
                tile++
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** GBA BIOS LZ77 (type 0x10): 4-byte header then flag-grouped literal /
     *  back-reference tokens. [src] may be longer than the compressed stream. */
    private fun lz77(src: ByteArray): ByteArray {
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
