package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * AREA maps: the map the player is in (a cave, a building) drawn whole from the ROM, with the area's other floors - the
 * maps joined to it by warps that share its map section and aren't outdoors (Mt. Moon's 1F / B1F / B2F, Silph Co.'s
 * eleven floors, a two-storey house). Nothing is bundled and nothing game-specific is coded here: a ROM needs only its
 * [AreaMapTables] row (gamedata/gen3/area_maps.toml by CRC32, written by scripts/gen_area_maps.py --scan), so the 3DS /
 * Linux companion can draw the same maps from the same data. Tiles are raw, LZ77 or smol (pokeemerald-expansion's); a map
 * grid raw or LZ77 (Monomyth's indoor maps).
 *
 * Every Gen 3 game keeps a map the same way: gMapGroups -> MapHeader (+0 layout, +4 events, +0x14 section, +0x17 type),
 * MapLayout (+0 width, +4 height, +0xC grid of u16 metatile ids, +0x10 / +0x14 primary / secondary tileset), Tileset (+0
 * compressed, +4 tiles, +8 palettes, +0xC metatiles: 8 tile entries each, bottom layer then top; 12 in a three-layer
 * engine, Lazarus's), MapEvents (+1 warp count,
 * +8 warps of {s16 x, s16 y, u8 elevation, u8 warpId, u8 mapNum, u8 mapGroup}). The engines differ only in how many
 * tiles / metatiles / palettes the primary tileset holds and whether the header carries a floor number.
 *
 * Plain arithmetic over [read] (the bus: ROM at 0x08000000): any thread, no Android.
 */
object AreaMap {
    data class MapId(val group: Int, val num: Int)

    /** One map of an area: its size in metatiles, the floor the header says (null = none), the label its chip shows. */
    data class Floor(val id: MapId, val width: Int, val height: Int, val floorNum: Int?, val label: String) {
        /**
         * Big enough to need a map: at least two screens across (30 metatiles) or down (20), or more than three screens
         * of room (450). The line, from every indoor map of FireRed and Emerald: a Pokémon Center (15 x 10, Indigo
         * Plateau's 25 x 18), a mart, a house, a department store floor (19 x 16), a museum or lab hall (28 x 11) and the
         * small gyms stay under it; caves, hideouts, Silph Co.'s floors (36 x 22), Pokémon Tower (24 x 20), the ship's
         * decks and the puzzle gyms (Fuchsia's 15 x 23, Saffron's 29 x 25, Sootopolis's 17 x 26) are over it.
         */
        val large: Boolean get() = width >= 2 * SCREEN_W || height >= 2 * SCREEN_H || width * height > 3 * SCREEN_W * SCREEN_H
    }

    /** The maps of the area around [here], in the order their chips go (lowest floor first). */
    class Area(val here: MapId, val mapsec: Int, val floors: List<Floor>) {
        fun indexOf(id: MapId) = floors.indexOfFirst { it.id == id }

        /** Worth a map of its own: one of its floors is [Floor.large]. A Pokémon Center, a mart, a house or a department
         * store keeps the region map, however many floors it has. */
        val worthShowing: Boolean
            get() = floors.any { it.large }

        /** The AREA map is what [id]'s floor opens on: the place is [worthShowing] and the floor bigger than the screen
         * (Silph Co.'s 10F, 17 x 19, with the rest of the building). A room of a screen or less (a ship's cabin, an
         * elevator, a cave's stair room) opens on the region map, the AREA map a button away. */
        fun opensOn(id: MapId): Boolean =
            worthShowing && floors.getOrNull(indexOf(id))?.let { it.width > SCREEN_W || it.height > SCREEN_H } == true
    }

    internal class MapInfo(
        val id: MapId, val header: Long, val layout: Long, val width: Int, val height: Int,
        val mapsec: Int, val type: Int, val floorNum: Int?, val warps: List<MapId>,
    )

    /** True when [t]'s map table reads as one in this ROM: two in three of its groups start with a map that reads with a
     * sane layout (a group can be empty - Rogue's - or hold only deleted maps - 8 of Amethyst's 43). Rows go by the ROM's
     * CRC, so this only guards against a wrong row: no AREA map rather than garbage. */
    fun matches(t: AreaMapTables, read: (Long, Int) -> ByteArray): Boolean = runCatching {
        val groups = t.mapGroupSizes.indices.filter { t.mapGroupSizes[it] > 0 }
        if (groups.isEmpty()) return false
        // A group's first readable map among its first four (Amethyst's groups often start with deleted ones).
        groups.count { g -> (0 until minOf(4, t.mapGroupSizes[g])).any { info(t, MapId(g, it), read) != null } } * 3 >= groups.size * 2
    }.getOrDefault(false)

    internal fun header(t: AreaMapTables, id: MapId, read: (Long, Int) -> ByteArray): Long? {
        if (id.group !in t.mapGroupSizes.indices || id.num !in 0 until t.mapGroupSizes[id.group]) return null
        val list = u32(read, t.mapGroups + 4L * id.group) xor t.mapGroupsXor
        if (!inRom(list)) return null
        return u32(read, list + 4L * id.num).takeIf(::inRom)
    }

    internal fun info(t: AreaMapTables, id: MapId, read: (Long, Int) -> ByteArray): MapInfo? = runCatching {
        val h = header(t, id, read) ?: return null
        val hb = read(h, 0x1C)
        val layout = u32(hb, 0)
        if (!inRom(layout)) return null
        val lb = read(layout, 0x18)
        val w = u32(lb, 0).toInt()
        val hh = u32(lb, 4).toInt()
        if (w !in 1..MAX_SIDE || hh !in 1..MAX_SIDE || !inRom(u32(lb, 0xC)) || !inRom(u32(lb, 0x10)) || !inRom(u32(lb, 0x14))) return null
        val warps = mutableListOf<MapId>()
        val events = u32(hb, 4)
        if (inRom(events)) {
            val eb = read(events, 0x14)
            val n = eb[1].toInt() and 0xFF
            val list = u32(eb, 8)
            if (n in 1..MAX_WARPS && inRom(list)) {
                val wb = read(list, 8 * n)
                for (i in 0 until n) {
                    val g = wb[8 * i + 7].toInt() and 0xFF
                    if (g != DYNAMIC) warps += MapId(g, wb[8 * i + 6].toInt() and 0xFF)
                }
            }
        }
        val floor = if (t.floorNumOff >= 0) hb[t.floorNumOff].toInt().takeIf { it != 0 } else null
        // SoulGold's map section is a u16, which moves the map type a byte on.
        val mapsec = if (t.mapSecWide) (hb[0x14].toInt() and 0xFF) or ((hb[0x15].toInt() and 0xFF) shl 8) else hb[0x14].toInt() and 0xFF
        MapInfo(id, h, layout, w, hh, mapsec, hb[if (t.mapSecWide) 0x18 else 0x17].toInt() and 0xFF, floor, warps)
    }.getOrNull()

    /** The area around [here]; null outdoors (a town, a route, the sea) or where the map can't be read. */
    fun area(t: AreaMapTables, here: MapId, read: (Long, Int) -> ByteArray): Area? {
        val start = info(t, here, read) ?: return null
        if (start.type in OUTDOOR) return null
        val found = LinkedHashMap<MapId, MapInfo>()
        val queue = ArrayDeque<MapId>().apply { add(here) }
        fun walk() {
            while (queue.isNotEmpty() && found.size < MAX_MAPS) {
                val id = queue.removeFirst()
                if (id in found) continue
                val m = if (id == here) start else info(t, id, read) ?: continue
                if (m.type in OUTDOOR || m.mapsec != start.mapsec) continue
                found[id] = m
                for (w in m.warps) if (w !in found) queue += w
            }
        }
        walk()
        // An elevator's doors are dynamic warps (back to the floor it was called from): nothing leads out of it, so the
        // floors are found the other way, from the maps of its group whose warps lead in.
        if (found.size == 1) {
            for (n in 0 until t.mapGroupSizes.getOrElse(here.group) { 0 }) {
                val id = MapId(here.group, n)
                if (id != here && info(t, id, read)?.warps?.contains(here) == true) queue += id
            }
            walk()
        }
        return Area(here, start.mapsec, floors(found.values.toList(), here))
    }

    /**
     * The chips' order and labels: numbered floors lowest first (B2F, B1F, 1F, 2F ... RF), each floor's maps by id; maps
     * with no number after them by id (the decomps list a place's maps top floor down or entrance first - Mt. Moon 1F,
     * B1F, B2F - so the id order is the game's own), numbered 1, 2, 3. In an area with numbered floors, an unnumbered
     * room (an elevator, a side room) has no chip of its own unless the player is in it.
     */
    internal fun floors(maps: List<MapInfo>, here: MapId): List<Floor> {
        val byId = compareBy<MapInfo>({ it.id.group }, { it.id.num })
        val numbered = maps.filter { it.floorNum != null }.sortedWith(compareBy<MapInfo> { floorOrder(it.floorNum!!) }.then(byId))
        val plain = maps.filter { it.floorNum == null }.sortedWith(byId)
            .filter { numbered.isEmpty() || it.id == here }
        val out = mutableListOf<Floor>()
        val seen = HashMap<String, Int>()
        for (m in numbered) {
            val base = floorLabel(m.floorNum!!)
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            out += Floor(m.id, m.width, m.height, m.floorNum, if (n == 1) base else "$base-$n")
        }
        plain.forEachIndexed { i, m -> out += Floor(m.id, m.width, m.height, null, (i + 1).toString()) }
        return out
    }

    private fun floorOrder(n: Int) = if (n == ROOF) Int.MAX_VALUE else n

    /** The game's own way of writing a floor (the elevator's and the map name popup's): 1F, B1F, RF for the roof. */
    fun floorLabel(n: Int): String = when {
        n == ROOF -> "RF"
        n < 0 -> "B${-n}F"
        else -> "${n}F"
    }

    /**
     * Map [id] drawn whole, a metatile 16 x 16 pixels, both layers, in its tilesets' own palettes (no time of day or
     * weather); undefined squares see-through. Null when it can't be read.
     */
    fun render(t: AreaMapTables, id: MapId, read: (Long, Int) -> ByteArray): RomArt.Image? = runCatching {
        val m = info(t, id, read) ?: return null
        val lb = read(m.layout, 0x1C)
        // Heart and Soul: a layout's own numbers by its kind (+0x18).
        val n = t.numbersFor(lb[0x18].toInt())
        val primary = tileset(t, n, u32(lb, 0x10), primary = true, read) ?: return null
        val secondary = tileset(t, n, u32(lb, 0x14), primary = false, read) ?: return null
        // Palettes 0 until primaryPalettes from the primary's list, the rest (to 13) from the secondary's, as the game loads them.
        val pal = IntArray(16 * 16)
        for (p in 0 until 13) {
            val src = if (p < n.palettes) primary.palettes else secondary.palettes
            for (c in 0 until 16) pal[p * 16 + c] = bgr555((src[p * 32 + 2 * c].toInt() and 0xFF) or ((src[p * 32 + 2 * c + 1].toInt() and 0xFF) shl 8))
        }
        val backdrop = pal[0]
        val w = m.width
        val h = m.height
        val grid = grid(u32(lb, 0xC), w, h, read) ?: return null
        val img = RomArt.Image(w * 16, h * 16)
        val cache = HashMap<Int, IntArray?>()
        fun tileRow(tile: Int, y: Int): Int? {
            val (src, i) = if (tile < n.tiles) primary.tiles to tile else secondary.tiles to tile - n.tiles
            val o = i * 32 + y * 4
            if (o < 0 || o + 4 > src.size) return null
            return (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or ((src[o + 2].toInt() and 0xFF) shl 16) or ((src[o + 3].toInt() and 0xFF) shl 24)
        }
        fun metatile(mid: Int): IntArray? = cache.getOrPut(mid) {
            val (src, i) = if (mid < n.metatiles) primary.metatiles to mid else secondary.metatiles to mid - n.metatiles
            val size = t.metatileSize
            if (i * size + size > src.size) return@getOrPut null
            val px = IntArray(256) { backdrop }
            // Each layer's four tiles, bottom layer first (two layers, or three).
            for (k in 0 until size / 2) {
                val e = (src[i * size + 2 * k].toInt() and 0xFF) or ((src[i * size + 2 * k + 1].toInt() and 0xFF) shl 8)
                val tile = e and 0x3FF
                val hf = e and 0x400 != 0
                val vf = e and 0x800 != 0
                val p = e ushr 12
                val ox = (k and 1) * 8
                val oy = ((k shr 1) and 1) * 8
                for (y in 0 until 8) {
                    val row = tileRow(tile, if (vf) 7 - y else y) ?: continue
                    for (x in 0 until 8) {
                        val sx = if (hf) 7 - x else x
                        val v = (row ushr (4 * sx)) and 0xF
                        if (v != 0) px[(oy + y) * 16 + ox + x] = pal[p * 16 + v]
                    }
                }
            }
            px
        }
        for (my in 0 until h) for (mx in 0 until w) {
            val e = (grid[2 * (my * w + mx)].toInt() and 0xFF) or ((grid[2 * (my * w + mx) + 1].toInt() and 0xFF) shl 8)
            val mid = e and t.metatileMask
            if (mid == t.metatileMask) continue
            val px = metatile(mid) ?: continue
            for (y in 0 until 16) px.copyInto(img.argb, (my * 16 + y) * img.width + mx * 16, y * 16, y * 16 + 16)
        }
        img
    }.getOrNull()

    /**
     * [img] [f] times smaller, each pixel the average of its f x f block (the HUD minimap's floor: a metatile 4 pixels, so
     * a GBA pixel each and nothing dropped, as a nearest-neighbour shrink would). A block mostly see-through stays so.
     */
    fun shrink(img: RomArt.Image, f: Int): RomArt.Image {
        val out = RomArt.Image(maxOf(1, img.width / f), maxOf(1, img.height / f))
        for (oy in 0 until out.height) for (ox in 0 until out.width) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (y in oy * f until oy * f + f) for (x in ox * f until ox * f + f) {
                val c = img.argb[y * img.width + x]
                if (c ushr 24 == 0) continue
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            if (n * 2 < f * f) continue
            out.argb[oy * out.width + ox] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    private class Tileset(val tiles: ByteArray, val palettes: ByteArray, val metatiles: ByteArray)

    /** A layout's grid, [w] x [h] u16s: raw, or LZ77 when its header says so with exactly that size (Monomyth packs its
     * indoor maps' grids; a raw grid can't start with that header: its first cell would need these very bytes). */
    private fun grid(at: Long, w: Int, h: Int, read: (Long, Int) -> ByteArray): ByteArray? {
        if (!inRom(at)) return null
        val size = w * h * 2
        val head = read(at, 4)
        val packed = head[0].toInt() == 0x10 &&
            ((head[1].toInt() and 0xFF) or ((head[2].toInt() and 0xFF) shl 8) or ((head[3].toInt() and 0xFF) shl 16)) == size
        return if (packed) Gfx.lz77(read(at, 4 + size + size / 8 + 8)).takeIf { it.size == size } else read(at, size)
    }

    private fun tileset(t: AreaMapTables, n: AreaMapTables.Numbers, addr: Long, primary: Boolean, read: (Long, Int) -> ByteArray): Tileset? {
        if (!inRom(addr)) return null
        val b = read(addr, 0x10)
        val compressed = b[0].toInt() != 0
        val tilesAt = u32(b, 4)
        val palsAt = u32(b, 8)
        val metasAt = u32(b, 0xC)
        if (!inRom(tilesAt) || !inRom(palsAt) || !inRom(metasAt)) return null
        val tileCount = if (primary) n.tiles else TILES_TOTAL - n.tiles
        val tiles = if (compressed) {
            val head = read(tilesAt, 8)
            if (head[0].toInt() == 0x10) {
                val size = (head[1].toInt() and 0xFF) or ((head[2].toInt() and 0xFF) shl 8) or ((head[3].toInt() and 0xFF) shl 16)
                if (size !in 32..TILES_TOTAL * 32) return null
                // Worst case: a flag byte per 8 literals.
                Gfx.lz77(read(tilesAt, 4 + size + size / 8 + 8))
            } else if (Smol.isSmol(head)) {
                // pokeemerald-expansion's smol (SoulGold, the newer hacks): its output is imageSize words of 4 bytes; the
                // stream is never longer than that plus its tables.
                val size = ((u32(head, 0) shr 4) and 0x3FFF).toInt() * 4
                if (size !in 32..TILES_TOTAL * 32) return null
                Smol.decompress(read(tilesAt, size + 256))
            } else return null
        } else read(tilesAt, tileCount * 32)
        val metaCount = if (primary) n.metatiles else maxOf(METATILES_TOTAL, t.metatileMask + 1) - n.metatiles
        return Tileset(tiles, read(palsAt, 16 * 32), read(metasAt, metaCount * t.metatileSize))
    }

    private fun bgr555(c: Int): Int {
        val r = c and 31
        val g = (c shr 5) and 31
        val b = (c shr 10) and 31
        return (0xFF shl 24) or (((r shl 3) or (r shr 2)) shl 16) or (((g shl 3) or (g shr 2)) shl 8) or ((b shl 3) or (b shr 2))
    }

    private fun inRom(a: Long) = a in 0x08000000L until 0x0A000000L
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun u32(read: (Long, Int) -> ByteArray, a: Long) = u32(read(a, 4), 0)

    /** MapHeader.mapType: town, city, route, underwater, ocean route - the region map's places; and none (0), which the
     * expansion hacks give some of theirs (Wishes of Tomorrow's towns). */
    private val OUTDOOR = setOf(0, 1, 2, 3, 5, 6)
    private const val DYNAMIC = 0x7F
    private const val ROOF = 127
    private const val MAX_SIDE = 512
    private const val MAX_WARPS = 64
    private const val MAX_MAPS = 64
    private const val SCREEN_W = 15
    private const val SCREEN_H = 10
    private const val TILES_TOTAL = 1024
    private const val METATILES_TOTAL = 1024
}

/**
 * What AREA maps need of a ROM (gamedata/gen3/area_maps.toml, by CRC32: [AREA_MAP_TABLES]): gMapGroups and its groups'
 * sizes, the primary tileset's tiles / metatiles / palettes (FireRed's engine 640 / 640 / 7, Emerald's 512 / 512 / 6;
 * hacks differ: R.O.W.E. 512 / 2048 / 6) and where MapHeader keeps the floor number (FireRed's engine +0x1A; Emerald has
 * none: -1).
 */
data class AreaMapTables(
    val mapGroups: Long,
    /** XORed into each group pointer (Unbound scrambles them), 0 = plain. */
    val mapGroupsXor: Long = 0,
    val mapGroupSizes: List<Int>,
    val primaryTiles: Int,
    val primaryMetatiles: Int,
    val primaryPalettes: Int,
    val floorNumOff: Int = -1,
    /** A metatile's bytes: 16 (two layers), 24 (three: Lazarus). */
    val metatileSize: Int = 16,
    /** The map grid's metatile id bits, also its undefined square (0x3FF; R.O.W.E. 0xFFF, SoulGold 0x7FF). */
    val metatileMask: Int = 0x3FF,
    /** MapHeader's map section is a u16 at +0x14 and its map type at +0x18 (SoulGold), not u8s at +0x14 / +0x17. */
    val mapSecWide: Boolean = false,
    /** The numbers for a layout whose byte +0x18 isn't 0 (Heart and Soul's FireRed-style layouts among its Emerald ones);
     * null = every layout uses the ones above. */
    val alt: Numbers? = null,
) {
    /** Where secondary tiles and metatiles start, and the palettes the primary loads. */
    data class Numbers(val tiles: Int, val metatiles: Int, val palettes: Int)

    /** The numbers for a layout whose byte +0x18 is [kind]. */
    fun numbersFor(kind: Int): Numbers =
        if (alt != null && kind != 0) alt else Numbers(primaryTiles, primaryMetatiles, primaryPalettes)
}

/** The running ROM's CRC32, as the activity looks EXPAND up by (a randomized retail cart: retail's), 0 = none. Set by the
 * activity when a game loads; picks the [AREA_MAP_TABLES] row. */
@Volatile var activeAreaMapCrc: Long = 0

/** The running ROM's AREA map tables ([AREA_MAP_TABLES]); null = none. */
private fun activeAreaMapTables(): AreaMapTables? = AREA_MAP_TABLES[activeAreaMapCrc]

/**
 * The AREA maps of the running ROM, read through [PokedexSource.reader] (the bus) and kept for the process: areas and
 * pictures by map. Reads off the UI thread ([area], [image]); the cached* calls never read.
 */
object AreaMaps {
    private val lock = SynchronizedObject()
    @Volatile private var checked: AreaMapTables? = null
    @Volatile private var ok = false
    private val areas = LockedMap<AreaMap.MapId, Optional<AreaMap.Area>>()
    /** The pictures, newest last: at most [KEEP_IMAGES] (a big cave floor is ~5 MB), the oldest dropped. */
    private val images = LinkedHashMap<AreaMap.MapId, Optional<RomArt.Image>>()
    /** The HUD minimap's pictures ([AreaMap.shrink], a sixteenth of the size), as many. */
    private val minis = LinkedHashMap<AreaMap.MapId, Optional<RomArt.Image>>()
    private const val KEEP_IMAGES = 16

    private class Optional<T>(val value: T?)

    private val read: (Long, Int) -> ByteArray = { a, n -> PokedexSource.reader.readCoreMemory(a, n) }

    /** The tables when they match this ROM; checked once per ROM. */
    fun tables(): AreaMapTables? {
        val t = activeAreaMapTables() ?: return null
        synchronized(lock) {
            if (checked !== t) {
                ok = AreaMap.matches(t, read)
                checked = t
                areas.clear()
                images.clear()
                minis.clear()
            }
            return t.takeIf { ok }
        }
    }

    fun cachedArea(id: AreaMap.MapId): AreaMap.Area? = areas[id]?.value
    fun cachedImage(id: AreaMap.MapId): RomArt.Image? = synchronized(lock) { images[id]?.value }
    fun cachedMiniImage(id: AreaMap.MapId): RomArt.Image? = synchronized(lock) { minis[id]?.value }

    /** The area [id] is in, or null (outdoors, no tables). Every map of it gets the same answer from then on. */
    fun area(id: AreaMap.MapId): AreaMap.Area? {
        areas[id]?.let { return it.value }
        val t = tables() ?: return null
        val a = AreaMap.area(t, id, read)
        areas[id] = Optional(a)
        return a
    }

    fun image(id: AreaMap.MapId): RomArt.Image? {
        synchronized(lock) { images[id]?.let { return it.value } }
        val t = tables() ?: return null
        val img = AreaMap.render(t, id, read)
        synchronized(lock) {
            images[id] = Optional(img)
            while (images.size > KEEP_IMAGES) images.remove(images.keys.first())
        }
        return img
    }

    /** [image] at a quarter of each side: a metatile [MINI_SCALE] pixels, for the HUD minimap. */
    fun miniImage(id: AreaMap.MapId): RomArt.Image? {
        synchronized(lock) { minis[id]?.let { return it.value } }
        val mini = image(id)?.let { AreaMap.shrink(it, 16 / MINI_SCALE) }
        synchronized(lock) {
            minis[id] = Optional(mini)
            while (minis.size > KEEP_IMAGES) minis.remove(minis.keys.first())
        }
        return mini
    }

    /** A metatile's pixels in [miniImage]. */
    const val MINI_SCALE = 4

    /** [PokedexSource.romChanged]. */
    fun romChanged() {
        synchronized(lock) { checked = null; ok = false; areas.clear(); images.clear(); minis.clear() }
    }
}
