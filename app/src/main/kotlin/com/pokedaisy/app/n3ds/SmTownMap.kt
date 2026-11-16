package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.RomArt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sun / Moon's Town Map, read from the player's cart (never bundled; found 2026-11-17 on the US carts):
 * - The pictures: RomFS `a/2/3/4`'s first file, LZ11, an ALYT archive with a SARC inside; `timg/land_0N.bflim` is island
 *   N's map (1 Melemele, 2 Akala, 3 Ula'ula, 4 Poni), 256 x 256 ETC1A4 stored transposed (the BFLIM's swizzle 8).
 * - Where the player is: the field's live position (world x / z, [Gen6Reader.Config.playerPos]) maps onto its island's
 *   picture by one scale and offset for every island ([SCALE], [OFFSET_X], [OFFSET_Y]): fitted on Akala (Sun standing at
 *   Heahea City's Pokemon Center, on its icon), then every island's zone arrival points (`a/0/7/7` +0x10 / +0x18) fall on
 *   their places, and the Moon save's Seafolk Village on its Pokemon Center beside the houseboats.
 * - A zone's island is its record's +0x4E; it's outdoors while its world (`a/0/7/7`'s second file, a u16 a zone) is its
 *   island's main one (the world most of the island's zones are in). Indoors the position is the room's own.
 */
object SmTownMap {
    const val SIZE = 256
    const val SCALE = 0.0059f
    const val OFFSET_X = -11.99f
    const val OFFSET_Y = -26.09f
    private const val VERSION = 1
    private const val ISLANDS = 4

    fun asset(island: Int) = "n3ds/sm/land_$island.png"

    /** A world position on its island's picture. */
    fun spot(x: Float, z: Float): Pair<Float, Float> = (OFFSET_X + x * SCALE) to (OFFSET_Y + z * SCALE)

    /** Each zone's island (0 = none) and whether it's outdoors (its island's main world). */
    class Zones(val island: IntArray, val outdoor: BooleanArray)

    private val zoneCache = HashMap<String, Zones?>()

    fun zones(cart: File): Zones? = synchronized(zoneCache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        if (zoneCache.containsKey(key)) return zoneCache[key]
        val z = runCatching { readZones(cart) }.getOrNull()
        zoneCache[key] = z
        z
    }

    private fun readZones(cart: File): Zones? = RomFs(cart).use { fs ->
        val files = fs.read(N3dsLayout.SM.zonesPath)?.let { Gen6Text.garcFiles(it) } ?: return@use null
        val recs = Gen6Text.unlz(files.getOrNull(0) ?: return@use null)
        val worlds = Gen6Text.unlz(files.getOrNull(1) ?: return@use null)
        val n = recs.size / N3dsLayout.SM.zoneRecord
        val rb = le(recs)
        val wb = le(worlds)
        val island = IntArray(n) { rb.getShort(it * N3dsLayout.SM.zoneRecord + 0x4E).toInt() and 0xFFFF }
        val world = IntArray(n) { if (2 * it + 2 <= worlds.size) wb.getShort(2 * it).toInt() and 0xFFFF else -1 }
        val main = (1..ISLANDS).associateWith { isl ->
            (0 until n).filter { island[it] == isl }.groupingBy { world[it] }.eachCount().maxByOrNull { it.value }?.key ?: -1
        }
        Zones(IntArray(n) { if (island[it] in 1..ISLANDS) island[it] else 0 }, BooleanArray(n) { island[it] in 1..ISLANDS && world[it] == main[island[it]] })
    }

    /** Writes the islands' pictures into [dir] once (a marker keeps the cart's size and [VERSION]); true when it wrote. */
    fun extractTo(cart: File, dir: File): Boolean {
        val marker = File(dir, "${asset(1)}-built")
        val stamp = "v$VERSION ${cart.length()}"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return false
        val pictures = runCatching { readPictures(cart) }.getOrNull() ?: return false
        if (pictures.isEmpty()) return false
        for ((island, img) in pictures) {
            val out = File(dir, asset(island))
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            tmp.writeBytes(img.png())
            tmp.renameTo(out)
        }
        marker.parentFile?.mkdirs()
        marker.writeText(stamp)
        return true
    }

    /** The islands' pictures (for tests too). */
    internal fun readPictures(cart: File): Map<Int, RomArt.Image> = RomFs(cart).use { fs ->
        val arc = fs.read("a/2/3/4")?.let { Gen6Text.garcFiles(it).firstOrNull() }?.let(Gen6Text::unlz) ?: return@use emptyMap()
        val files = sarc(arc)
        (1..ISLANDS).mapNotNull { isl ->
            val f = files["timg/land_0$isl.bflim"] ?: return@mapNotNull null
            flim(f)?.let { isl to it }
        }.toMap()
    }

    /** A SARC's files by name (the first SARC in [d]). */
    private fun sarc(d: ByteArray): Map<String, ByteArray> {
        var k = -1
        for (i in 0..d.size - 4) if (d[i] == 'S'.code.toByte() && d[i + 1] == 'A'.code.toByte() && d[i + 2] == 'R'.code.toByte() && d[i + 3] == 'C'.code.toByte()) { k = i; break }
        if (k < 0) return emptyMap()
        val b = le(d)
        val dataOff = b.getInt(k + 0xC)
        val nodes = b.getShort(k + 0x1A).toInt() and 0xFFFF
        val sfnt = k + 0x20 + nodes * 0x10
        val names = sfnt + 8
        val out = HashMap<String, ByteArray>()
        for (i in 0 until nodes) {
            val at = k + 0x20 + i * 0x10
            val attr = b.getInt(at + 4)
            val start = b.getInt(at + 8)
            val end = b.getInt(at + 12)
            if (attr and 0x01000000 == 0) continue
            val no = names + (attr and 0xFFFF) * 4
            var e = no
            while (e < d.size && d[e].toInt() != 0) e++
            val name = String(d, no, e - no, Charsets.US_ASCII)
            if (k + dataOff + end <= d.size && start <= end) out[name] = d.copyOfRange(k + dataOff + start, k + dataOff + end)
        }
        return out
    }

    /** A BFLIM picture: ETC1 / ETC1A4 only (the islands' maps), swizzle 8 = stored transposed. */
    private fun flim(f: ByteArray): RomArt.Image? {
        if (f.size < 0x28) return null
        val foot = f.size - 0x28
        if (String(f, foot, 4, Charsets.US_ASCII) != "FLIM") return null
        val b = le(f)
        val w = b.getShort(foot + 0x1C).toInt() and 0xFFFF
        val h = b.getShort(foot + 0x1E).toInt() and 0xFFFF
        val format = f[foot + 0x22].toInt() and 0xFF
        val swizzle = f[foot + 0x23].toInt() and 0xFF
        val stored = Gen6TownMap.decodeEtc(f.copyOfRange(0, foot), w, h, format) ?: return null
        if (swizzle != 8) return stored
        val out = RomArt.Image(h, w)
        for (y in 0 until h) for (x in 0 until w) out.argb[x * h + y] = stored.argb[y * w + x]
        return out
    }

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
}
