package com.pokedaisy.app.companion.data

import java.io.File
import java.io.RandomAccessFile

/**
 * A FireRed-engine hack's own region map - Unbound's Borrius, Odyssey's,
 * Gaia's, Amethyst's, ... - read out of the ROM being played. CFRU and most
 * binary hacks keep FireRed 1.0's region_map.c code where it is and only
 * repoint its data, so the literal-pool words that code loads its tiles,
 * palette, tilemaps and map-section tables from still say where each hack
 * keeps them. [read] follows those words (each has to land on data of the
 * right shape, or the ROM gets no map); [load] caches the screens as PNGs
 * beside [RomArt]'s and publishes the sections as [current], which
 * [activeMapSecData] prefers over the bundled tables for every game but
 * FireRed itself.
 */
object RomRegionMap {
    /** One ROM's map: [images] are regionmap/<name>.png assets, [sections] index them. */
    class Data(val images: Array<String>, val sections: Map<Int, MapSecInfo>, val layouts: List<RegionLayout?> = emptyList())

    class Parsed(val images: List<RomArt.Image>, val sections: Map<Int, MapSecInfo>, val layouts: List<RegionLayout?> = emptyList())

    /** Random access to a ROM file: [read] returns fewer bytes past the end. */
    interface Rom {
        val size: Int
        fun read(off: Int, n: Int): ByteArray
    }

    class BytesRom(private val b: ByteArray) : Rom {
        override val size get() = b.size
        override fun read(off: Int, n: Int): ByteArray = b.copyOfRange(off.coerceIn(0, b.size), (off + n).coerceIn(0, b.size))
    }

    /** The running ROM's map, or null (not loaded yet, or not a ROM this can read). Set by [load], or by previews. */
    @Volatile
    var current: Data? = null

    // FireRed 1.0 (BPRE rev 0), the base CFRU and most binary hacks build on.
    private const val LIT_GFX = 0xC0330 // sRegionMap_Gfx, LZ77 4bpp
    private const val LIT_PAL = 0xC02EC // sRegionMap_Pal
    private val LIT_TILEMAPS = intArrayOf(0xC035C, 0xC0370, 0xC0388, 0xC03A4) // Kanto, Sevii 1-3, 4-5, 6-7: LZ77 30x20
    private const val LIT_SEVII = 0xC00BC // sSeviiMapsecs[3][30]
    private const val SEVII_CMP = 0xC0064 // InitRegionMap's `cmp r0, #SEVII_MAPSEC_START - 1` + `bls`
    private const val LIT_NAMES = 0xC0C94 // sMapNames
    private const val LIT_CORNERS = 0xC3D3C // sMapSectionTopLeftCorners, {u16 x, u16 y}
    private const val LIT_DIMS = 0xC3D38 // sMapSectionDimensions, {u16 w, u16 h}
    // GetSelectedMapSection's sRegionMapSections_{Kanto,Sevii123,Sevii45,Sevii67}[2][15][22]: the cursor grids
    private val LIT_GRIDS = intArrayOf(0xC4194, 0xC419C, 0xC41A4, 0xC41CC)
    private const val GRID_W = 22
    private const val GRID_H = 15
    private const val FIRST_MAPSEC = 0x58 // MAPSEC_PALLET_TOWN, where the tables start
    private const val SECTIONS = 109 // sMapNames' length
    private const val MAPSEC_NONE = 0xC5

    // The corner tables are in the cursor grid, 4 tiles in from the
    // screen the tilemaps fill - see MapSecData.kt.
    private const val GRID_OFFSET = 4
    private const val SCREEN_BYTES = 30 * 20 * 2

    /** Bump when the images change, so ROMs read before get new ones. */
    private const val VERSION = 3 // 2: junk Sevii screens dropped (Odyssey, Sword and Shield Ultimate Plus); 3: and unused ones (GS Chronicles, Gaia)

    // FireRed 1.0's own Sevii tilemaps and region map tiles: a hack that moved the tiles but still points a screen
    // at one of these draws FireRed's islands in its own tiles.
    private val FIRERED_SEVII_TILEMAPS = setOf(0x3F0AFC, 0x3F0C0C, 0x3F0CF0)
    private const val FIRERED_TILES = 0x3EF61C

    /** Reads [rom] in the background and publishes its map as [current]. */
    fun load(filesDir: File, romKey: String, rom: File) {
        current = null
        Thread({
            try {
                current = loadNow(filesDir, romKey, rom) ?: return@Thread
                RomArt.announce()
            } catch (t: Throwable) {
                android.util.Log.w("pokedaisy", "RomRegionMap: read failed", t)
            }
        }, "pokedaisy-region-map").apply { isDaemon = true; start() }
    }

    /** [read] [rom], writing its screens (once per [romKey]) where [RomArt]'s art goes. */
    fun loadNow(filesDir: File, romKey: String, rom: File): Data? {
        val parsed = RandomAccessFile(rom, "r").use { read(FileRom(it)) } ?: return null
        val dir = File(RomArt.dir(filesDir), "regionmap").apply { mkdirs() }
        val names = parsed.images.mapIndexed { i, img ->
            val name = "rom-$romKey-v$VERSION-$i"
            val f = File(dir, "$name.png")
            if (!f.isFile) {
                val tmp = File(dir, "$name.png.tmp")
                tmp.writeBytes(img.png())
                tmp.renameTo(f)
            }
            name
        }
        return Data(names.toTypedArray(), parsed.sections, parsed.layouts)
    }

    private class FileRom(private val f: RandomAccessFile) : Rom {
        override val size = f.length().toInt()
        override fun read(off: Int, n: Int): ByteArray {
            if (off < 0 || off >= size) return ByteArray(0)
            val out = ByteArray(minOf(n, size - off))
            f.seek(off.toLong())
            f.readFully(out)
            return out
        }
    }

    /** The region map [rom] carries, or null if it isn't a FireRed 1.0 ROM whose map code is where this expects. */
    fun read(rom: Rom): Parsed? {
        val header = rom.read(0xAC, 0x11)
        // Rev 1 has its code elsewhere. Anything else is taken as rev 0's code - Unbound's French
        // translation left its revision byte at 0x9E - and the literal pools below must still
        // land on data of the right shape.
        if (header.size < 0x11 || String(header, 0, 4, Charsets.US_ASCII) != "BPRE" || header[0x10].toInt() == 1) return null

        fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun ptr(at: Int): Int? {
            val b = rom.read(at, 4)
            if (b.size < 4) return null
            val p = u16(b, 0).toLong() or (u16(b, 2).toLong() shl 16)
            return (p - 0x08000000L).takeIf { it in 0 until rom.size }?.toInt()
        }
        fun lz(off: Int): ByteArray? =
            try {
                RomArt.lz77(rom.read(off, 0x12000), 0, -1)
            } catch (_: IndexOutOfBoundsException) {
                null
            }

        val tiles = ptr(LIT_GFX)?.let(::lz)?.takeIf { it.size % 32 == 0 } ?: return null
        val pal = RomArt.palette(rom.read(ptr(LIT_PAL) ?: return null, 16 * 32)).takeIf { it.size >= 16 } ?: return null

        // Hacks with one region point every slot at the same tilemap: one image each.
        val mapOffs = LIT_TILEMAPS.map { ptr(it) ?: return null }
        val distinct = mapOffs.distinct()
        val screens = distinct.map { off ->
            val map = lz(off)?.takeIf { it.size in SCREEN_BYTES / 2..0x800 } ?: return null
            RomArt.screen(tiles, pal, fullScreen(map), 30)
        }
        val imageOf = mapOffs.map { distinct.indexOf(it) }
        val crops = screens.map { backdropCrop(it, pal[0]) }
        // A Sevii screen with none of the backdrop border the main one has is FireRed's tilemap
        // drawn with the hack's own tiles - junk (Sword and Shield Ultimate Plus redrew only Galar,
        // yet its Isle of Armor / Crown Tundra sections still sit on those screens; Odyssey's are
        // never shown). Dropped: their sections go on the main map, with nothing highlighted.
        fun uncut(c: IntArray) = c[0] == 0 && c[1] == 0 && c[2] == 30 && c[3] == 20
        val junk = screens.indices.filter { it != imageOf[0] && uncut(crops[it]) && !uncut(crops[imageOf[0]]) }.toMutableSet()
        val images = screens.mapIndexed { i, img ->
            val (left, top, right, bottom) = crops[i]
            RomArt.Image((right - left) * 8, (bottom - top) * 8).also { out ->
                for (y in 0 until out.height) ((top * 8 + y) * 240 + left * 8).let { img.argb.copyInto(out.argb, y * out.width, it, it + out.width) }
            }
        }

        val names = ptr(LIT_NAMES) ?: return null
        val corners = rom.read(ptr(LIT_CORNERS) ?: return null, SECTIONS * 4)
        val dims = rom.read(ptr(LIT_DIMS) ?: return null, SECTIONS * 4)
        val sevii = rom.read(ptr(LIT_SEVII) ?: return null, 3 * 30)
        if (corners.size < SECTIONS * 4 || dims.size < SECTIONS * 4 || sevii.size < 3 * 30) return null
        // Hacks move where the Sevii sections start (Unbound and Odyssey past
        // the last one) or cut the check out altogether (Gaia): all one map.
        val cmp = rom.read(SEVII_CMP, 4)
        val seviiStart = if (cmp.size == 4 && cmp[1].toInt() == 0x28 && (cmp[3].toInt() and 0xFF) == 0xD9) (cmp[0].toInt() and 0xFF) + 1 else 0x100

        fun regionOf(mapsec: Int): Int {
            if (mapsec < seviiStart) return 0
            for (j in 0 until 3) for (i in 0 until 30) {
                val m = sevii[j * 30 + i].toInt() and 0xFF
                if (m == MAPSEC_NONE) break
                if (m == mapsec) return j + 1
            }
            return 0
        }

        val sections = HashMap<Int, MapSecInfo>()
        for (i in 0 until SECTIONS) {
            val name = titleCase(Gen3Text.decode(rom.read(ptr(names + i * 4) ?: break, 32)))
            if (name.isEmpty()) continue
            val mapsec = FIRST_MAPSEC + i
            val x = u16(corners, i * 4)
            val y = u16(corners, i * 4 + 2)
            val w = u16(dims, i * 4)
            val h = u16(dims, i * 4 + 2)
            val image = imageOf[regionOf(mapsec)].let { if (it in junk) imageOf[0] else it }
            // {0,0} is the tables' "not on the map" (dungeons, most buildings):
            // the map still shows, with nothing highlighted.
            sections[mapsec] = if ((x == 0 && y == 0) || w == 0 || h == 0 || imageOf[regionOf(mapsec)] in junk) {
                MapSecInfo(name, image, 0, 0, 0, 0)
            } else {
                MapSecInfo(name, image, x + GRID_OFFSET - crops[image][0], y + GRID_OFFSET - crops[image][1], w, h)
            }
        }
        if (sections.isEmpty()) return null
        // FireRed's own Sevii tilemap in the hack's moved tiles, holding no section or hardly any placed on it: junk
        // too (GS Chronicles keeps its Kanto routes 12-27 there, three of 21 with a box; Gaia none at all). Its
        // sections go on the main map, nothing highlighted. Amethyst and Orange Islands redrew theirs: most placed.
        if (ptr(LIT_GFX) != FIRERED_TILES) {
            for (i in screens.indices) {
                if (i == imageOf[0] || i in junk || distinct[i] !in FIRERED_SEVII_TILEMAPS) continue
                val on = sections.values.filter { it.region == i }
                if (on.isEmpty() || on.count { it.w > 0 } * 4 < on.size) {
                    junk.add(i)
                    for (e in sections.entries) if (e.value.region == i) e.setValue(MapSecInfo(e.value.name, imageOf[0], 0, 0, 0, 0))
                }
            }
        }
        // Each image's cursor grid (the first region drawn on it), kept only
        // if it names this ROM's own sections - else taps fall back to the rects.
        val layouts = images.indices.map { image ->
            if (image in junk) return@map null
            val off = ptr(LIT_GRIDS[imageOf.indexOf(image)]) ?: return@map null
            val bytes = rom.read(off, 2 * GRID_W * GRID_H)
            if (bytes.size < 2 * GRID_W * GRID_H) return@map null
            val named = bytes.map { it.toInt() and 0xFF }.filter { it != MAPSEC_NONE }
            if (named.isEmpty() || named.count { it in sections } < named.size * 9 / 10) return@map null
            RegionLayout(
                GRID_OFFSET - crops[image][0], GRID_OFFSET - crops[image][1], GRID_W, GRID_H, MAPSEC_NONE,
                (0 until 2).map { l -> bytes.copyOfRange(l * GRID_W * GRID_H, (l + 1) * GRID_W * GRID_H) },
            )
        }
        if (junk.isEmpty()) return Parsed(images, sections, layouts)
        val kept = images.indices.filter { it !in junk }
        return Parsed(
            kept.map { images[it] },
            sections.mapValues { (_, s) -> s.copy(region = kept.indexOf(s.region)) },
            kept.map { layouts[it] },
        )
    }

    /** A tilemap cut short of the whole 30x20 screen (Unbound's) ends, from its last whole row, in its top-left tile - the border's. */
    private fun fullScreen(map: ByteArray): ByteArray {
        if (map.size >= SCREEN_BYTES) return map
        val out = map.copyOf(SCREEN_BYTES)
        for (i in map.size / 60 * 60 until SCREEN_BYTES) out[i] = map[i and 1]
        return out
    }

    /**
     * The tiles of [img] inside its border of plain [backdrop] (the BG's
     * transparent colour, which the game covers with its frame), as
     * {left, top, right, bottom}; at most a quarter off each side.
     */
    private fun backdropCrop(img: RomArt.Image, backdrop: Int): IntArray {
        fun plain(x0: Int, y0: Int, w: Int, h: Int) =
            (y0 until y0 + h).all { y -> (x0 until x0 + w).all { x -> img.argb[y * 240 + x] == backdrop } }
        var left = 0
        var right = 30
        var top = 0
        var bottom = 20
        while (top < 5 && plain(0, top * 8, 240, 8)) top++
        while (bottom > 15 && plain(0, bottom * 8 - 8, 240, 8)) bottom--
        while (left < 7 && plain(left * 8, top * 8, 8, (bottom - top) * 8)) left++
        while (right > 23 && plain(right * 8 - 8, top * 8, 8, (bottom - top) * 8)) right--
        return intArrayOf(left, top, right, bottom)
    }

    /** ALL-CAPS names (the ones hacks kept from FireRed) in the hack's own title case, like gen_unbound_data.py. */
    private fun titleCase(s: String): String {
        if (s.any { it in 'a'..'z' }) return s
        return buildString {
            var start = true
            for (c in s) {
                append(if (start) c.uppercaseChar() else c.lowercaseChar())
                start = !c.isLetter() && c != '’'
            }
        }
    }
}
