package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.Gen5Lz
import com.pokedaisy.app.companion.data.RomArt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pokemon X / Y's Town Map, read from the player's cart (never bundled; found 2026-11-15 on the US cart):
 * - The picture: RomFS `a/0/6/9`, a GARC of one LZ11 file, the game's UI archive (u32 count, that many 0x40-byte names,
 *   then the files in that order: `.bclan`s, `townmap_lower.bclyt`, `townmap_upper.bclyt`, `.bclim`s). A BCLIM is cut by
 *   its footer (`CLIM FF FE 14 00`, the image data's size at +0x24, before it); `townmap_bg1` is the 320 x 210 Kalos map
 *   (ETC1), `hero_obj` the player's green pin (24 x 24, ETC1A4).
 * - Where a zone is: the zone's record (`a/0/1/2`'s last file, 0x38 bytes) +0x1A = its outdoor zone on the map; the spot
 *   table (`a/0/6/8`, 0x40 bytes a spot) has that zone at +0 and, at +4, the pane in `townmap_lower.bclyt` whose name is
 *   that number in base 36; the pane's translation (+0x24) and size (+0x44), y up round the picture's middle, give the
 *   picture pixel (160 + x, 105 - y). Checked: Vaniville (212, 180), Route 2 (212, 145), Lumiose's centre (166, 78).
 */
object Gen6TownMap {
    const val MAP = "n3ds/pokemonx/townmap.png"
    const val PIN = "n3ds/pokemonx/townmap_pin.png"
    const val WIDTH = 320
    const val HEIGHT = 210
    private const val VERSION = 1

    /** A place on the picture, its middle and size in the picture's pixels. */
    class Spot(val x: Float, val y: Float, val w: Float, val h: Float)

    private class Data(val map: RomArt.Image, val pin: RomArt.Image?, val spots: Map<Int, Spot>)

    private val cache = HashMap<String, Data?>()

    /** Each zone's place on the map (by zone number); empty when the cart can't be read that far. */
    fun spots(cart: File): Map<Int, Spot> = load(cart)?.spots.orEmpty()

    /** Writes the map and the pin into [dir] once (a marker keeps the cart's size and [VERSION]); true when it wrote. */
    fun extractTo(cart: File, dir: File): Boolean {
        val marker = File(dir, "$MAP-built")
        val stamp = "v$VERSION ${cart.length()}"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return false
        val data = load(cart) ?: return false
        write(dir, MAP, data.map.png())
        data.pin?.let { write(dir, PIN, it.png()) }
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        return true
    }

    private fun load(cart: File): Data? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        if (cache.containsKey(key)) return cache[key]
        val data = runCatching { read(cart) }.getOrNull()
        cache[key] = data
        data
    }

    private fun read(cart: File): Data? = RomFs(cart).use { fs ->
        val arc = fs.read("a/0/6/9")?.let { Gen6Text.garcFiles(it).firstOrNull() }?.let(::unpack) ?: return@use null
        val names = archiveNames(arc) ?: return@use null
        val images = names.filter { it.endsWith(".bclim") }.zip(clims(arc)).toMap()
        val map = images["townmap_bg1.bclim"]?.let { decode(it) } ?: return@use null
        val pin = images["hero_obj.bclim"]?.let { decode(it) }
        val panes = clyt(arc)?.let(::panes).orEmpty()
        val spotFile = fs.read("a/0/6/8")?.let { Gen6Text.garcFiles(it).firstOrNull() }?.let(::unpack) ?: ByteArray(0)
        val zoneFile = fs.read("a/0/1/2")?.let { Gen6Text.garcFiles(it).lastOrNull() }?.let(::unpack) ?: ByteArray(0)
        val sb = le(spotFile)
        // The spots: their zone -> the pane's place.
        val byZone = HashMap<Int, Spot>()
        for (i in 0 until spotFile.size / 0x40) {
            val zone = sb.getInt(i * 0x40)
            val pane = panes[base36(sb.getInt(i * 0x40 + 4))] ?: continue
            byZone.putIfAbsent(zone, Spot(WIDTH / 2f + pane[0], HEIGHT / 2f - pane[1], pane[2], pane[3]))
        }
        // Every zone through its outdoor zone (+0x1A).
        val zb = le(zoneFile)
        val spots = HashMap<Int, Spot>()
        for (z in 0 until zoneFile.size / 0x38) {
            val parent = zb.getShort(z * 0x38 + 0x1A).toInt() and 0xFFFF
            (byZone[parent] ?: byZone[z])?.let { spots[z] = it }
        }
        Data(map, pin, spots)
    }

    private fun unpack(f: ByteArray): ByteArray = if (f.isNotEmpty() && f[0].toInt() == 0x11) Gen5Lz.decompress(f) ?: f else f
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun base36(n: Int): String = Integer.toString(n, 36)

    /** The UI archive's file names, or null when [d] isn't one. */
    private fun archiveNames(d: ByteArray): List<String>? {
        if (d.size < 8) return null
        val n = le(d).getInt(0)
        if (n !in 1..1999 || 4 + n * 0x40 > d.size) return null
        return (0 until n).map { i ->
            val o = 4 + i * 0x40
            var e = o
            while (e < o + 0x40 && d[e].toInt() != 0) e++
            String(d, o, e - o, Charsets.US_ASCII)
        }
    }

    private class Clim(val w: Int, val h: Int, val format: Int, val data: ByteArray)

    /** Every BCLIM in [d], in order, by its footer. */
    private fun clims(d: ByteArray): List<Clim> {
        val b = le(d)
        val out = ArrayList<Clim>()
        var p = 0
        while (true) {
            p = indexOf(d, CLIM, p)
            if (p < 0) break
            if (p + 0x28 <= d.size && String(d, p + 0x14, 4, Charsets.US_ASCII) == "imag") {
                val w = b.getShort(p + 0x1C).toInt() and 0xFFFF
                val h = b.getShort(p + 0x1E).toInt() and 0xFFFF
                val format = b.getInt(p + 0x20)
                val size = b.getInt(p + 0x24)
                if (size in 1..p) out += Clim(w, h, format, d.copyOfRange(p - size, p))
            }
            p += CLIM.size
        }
        return out
    }

    /** The first layout (townmap_lower.bclyt). */
    private fun clyt(d: ByteArray): ByteArray? {
        val p = indexOf(d, CLYT, 0).takeIf { it >= 0 } ?: return null
        val size = le(d).getInt(p + 0xC)
        return if (size > 0 && p + size <= d.size) d.copyOfRange(p, p + size) else null
    }

    /** A layout's panes by name: translation x, y and size w, h. */
    private fun panes(l: ByteArray): Map<String, FloatArray> {
        val b = le(l)
        var p = b.getShort(6).toInt() and 0xFFFF
        val count = b.getInt(0x10)
        val out = HashMap<String, FloatArray>()
        repeat(count) {
            if (p + 8 > l.size) return out
            val magic = String(l, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            if (magic in PANES && p + 0x4C <= l.size) {
                var e = p + 0xC
                while (e < p + 0x1C && l[e].toInt() != 0) e++
                val name = String(l, p + 0xC, e - p - 0xC, Charsets.US_ASCII)
                out[name] = floatArrayOf(b.getFloat(p + 0x24), b.getFloat(p + 0x28), b.getFloat(p + 0x44), b.getFloat(p + 0x48))
            }
            if (size <= 0) return out
            p += size
        }
        return out
    }

    /** A BCLIM as ARGB: ETC1 (10) and ETC1A4 (11) in 8 x 8 tiles of four 4 x 4 blocks; the picture is the first [h]
     * stored rows, top first, of a power-of-two store. Other formats: null (the map and pin are ETC). */
    private fun decode(c: Clim): RomArt.Image? {
        if (c.format != 10 && c.format != 11) return null
        val sw = pow2(c.w)
        val sh = pow2(c.h)
        val img = RomArt.Image(c.w, c.h)
        val alpha = c.format == 11
        var p = 0
        val b = le(c.data)
        for (ty in 0 until sh step 8) for (tx in 0 until sw step 8) for (k in 0 until 4) {
            val bx = tx + (k and 1) * 4
            val by = ty + (k shr 1) * 4
            var a = -1L
            if (alpha) { if (p + 8 > c.data.size) return img; a = b.getLong(p); p += 8 }
            if (p + 8 > c.data.size) return img
            val word = b.getLong(p)
            p += 8
            etc1(word) { x, y, rgb ->
                val px = bx + x
                val py = by + y
                if (px < c.w && py < c.h) {
                    val av = if (alpha) ((a ushr ((x * 4 + y) * 4)) and 15).toInt() * 17 else 255
                    img.argb[py * c.w + px] = (av shl 24) or rgb
                }
            }
        }
        return img
    }

    private fun pow2(v: Int): Int { var n = 8; while (n < v) n *= 2; return n }

    private val MODIFIERS = arrayOf(intArrayOf(2, 8), intArrayOf(5, 17), intArrayOf(9, 29), intArrayOf(13, 42),
        intArrayOf(18, 60), intArrayOf(24, 80), intArrayOf(33, 106), intArrayOf(47, 183))

    /** One ETC1 block ([word]: little-endian, the colours in its high half), each pixel's RGB to [out]. */
    private inline fun etc1(word: Long, out: (x: Int, y: Int, rgb: Int) -> Unit) {
        val hi = (word ushr 32).toInt()
        val lo = word.toInt()
        val diff = (hi ushr 1) and 1
        val flip = hi and 1
        val t1 = (hi ushr 5) and 7
        val t2 = (hi ushr 2) and 7
        val c1 = IntArray(3)
        val c2 = IntArray(3)
        if (diff == 1) {
            for ((i, s) in intArrayOf(27, 19, 11).withIndex()) {
                val v = (hi ushr s) and 31
                var d = (hi ushr (s - 3)) and 7
                if (d > 3) d -= 8
                val v2 = (v + d) and 31
                c1[i] = (v shl 3) or (v shr 2)
                c2[i] = (v2 shl 3) or (v2 shr 2)
            }
        } else {
            for ((i, s) in intArrayOf(28, 20, 12).withIndex()) {
                c1[i] = ((hi ushr s) and 15) * 17
                c2[i] = ((hi ushr (s - 4)) and 15) * 17
            }
        }
        for (x in 0 until 4) for (y in 0 until 4) {
            val i = x * 4 + y
            val idx = (((lo ushr (i + 16)) and 1) shl 1) or ((lo ushr i) and 1)
            val second = if (flip == 1) y >= 2 else x >= 2
            val c = if (second) c2 else c1
            val m = MODIFIERS[if (second) t2 else t1]
            val mod = when (idx) { 0 -> m[0]; 1 -> m[1]; 2 -> -m[0]; else -> -m[1] }
            val r = (c[0] + mod).coerceIn(0, 255)
            val g = (c[1] + mod).coerceIn(0, 255)
            val bl = (c[2] + mod).coerceIn(0, 255)
            out(x, y, (r shl 16) or (g shl 8) or bl)
        }
    }

    private val CLIM = byteArrayOf(0x43, 0x4C, 0x49, 0x4D, 0xFF.toByte(), 0xFE.toByte(), 0x14, 0x00)
    private val CLYT = byteArrayOf(0x43, 0x4C, 0x59, 0x54, 0xFF.toByte(), 0xFE.toByte())
    private val PANES = setOf("pan1", "pic1", "bnd1", "txt1", "wnd1")

    private fun indexOf(d: ByteArray, pat: ByteArray, from: Int): Int {
        var i = from
        outer@ while (i <= d.size - pat.size) {
            for (k in pat.indices) if (d[i + k] != pat[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    private fun write(dir: File, path: String, bytes: ByteArray) {
        val out = File(dir, path)
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
    }
}
