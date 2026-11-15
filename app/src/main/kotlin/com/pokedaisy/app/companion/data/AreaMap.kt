package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * AREA maps: the map the player is in (a cave, a building) drawn whole from the ROM, with the area's other floors - the
 * maps joined to it by warps that share its map section and aren't outdoors (Mt. Moon's 1F / B1F / B2F, Silph Co.'s
 * eleven floors, a two-storey house). Nothing is bundled and nothing game-specific is coded here: a game needs only an
 * [AreaMapTables] record (gamedata/gen3/records/, schema gen3_area_map.toml), so the 3DS / Linux companion can draw the
 * same maps from the same data.
 *
 * Every Gen 3 game keeps a map the same way: gMapGroups -> MapHeader (+0 layout, +4 events, +0x14 section, +0x17 type),
 * MapLayout (+0 width, +4 height, +0xC grid of u16 metatile ids, +0x10 / +0x14 primary / secondary tileset), Tileset (+0
 * compressed, +4 tiles, +8 palettes, +0xC metatiles: 8 tile entries each, bottom layer then top), MapEvents (+1 warp count,
 * +8 warps of {s16 x, s16 y, u8 elevation, u8 warpId, u8 mapNum, u8 mapGroup}). The engines differ only in how many
 * tiles / metatiles / palettes the primary tileset holds and whether the header carries a floor number.
 *
 * Plain arithmetic over [read] (the bus: ROM at 0x08000000): any thread, no Android.
 */
object AreaMap {
    data class MapId(val group: Int, val num: Int)

    /** One map of an area: its size in metatiles, the floor the header says (null = none), the label its chip shows. */
    data class Floor(val id: MapId, val width: Int, val height: Int, val floorNum: Int?, val label: String)

    /** The maps of the area around [here], in the order their chips go (lowest floor first). */
    class Area(val here: MapId, val mapsec: Int, val floors: List<Floor>) {
        fun indexOf(id: MapId) = floors.indexOfFirst { it.id == id }

        /** Worth a map of its own: more than one floor, or a map bigger than the screen (15 x 10 metatiles). A plain
         * Pokémon Center or a one-room house keeps the region map. */
        val worthShowing: Boolean
            get() = floors.size > 1 || floors.any { it.width > SCREEN_W || it.height > SCREEN_H }
    }

    internal class MapInfo(
        val id: MapId, val header: Long, val layout: Long, val width: Int, val height: Int,
        val mapsec: Int, val type: Int, val floorNum: Int?, val warps: List<MapId>,
    )

    /** True when [t]'s map table reads as one in this ROM: every group's list and its first map in ROM with a sane
     * layout. A config shared with a hack that moved its maps then shows no AREA map rather than garbage. */
    fun matches(t: AreaMapTables, read: (Long, Int) -> ByteArray): Boolean = runCatching {
        if (t.mapGroupSizes.isEmpty()) return false
        t.mapGroupSizes.indices.all { g -> info(t, MapId(g, 0), read) != null }
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
        MapInfo(id, h, layout, w, hh, hb[0x14].toInt() and 0xFF, hb[0x17].toInt() and 0xFF, floor, warps)
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
        val lb = read(m.layout, 0x18)
        val primary = tileset(t, u32(lb, 0x10), primary = true, read) ?: return null
        val secondary = tileset(t, u32(lb, 0x14), primary = false, read) ?: return null
        // Palettes 0 until primaryPalettes from the primary's list, the rest (to 13) from the secondary's, as the game loads them.
        val pal = IntArray(16 * 16)
        for (p in 0 until 13) {
            val src = if (p < t.primaryPalettes) primary.palettes else secondary.palettes
            for (c in 0 until 16) pal[p * 16 + c] = bgr555((src[p * 32 + 2 * c].toInt() and 0xFF) or ((src[p * 32 + 2 * c + 1].toInt() and 0xFF) shl 8))
        }
        val backdrop = pal[0]
        val w = m.width
        val h = m.height
        val grid = read(u32(lb, 0xC), w * h * 2)
        val img = RomArt.Image(w * 16, h * 16)
        val cache = HashMap<Int, IntArray?>()
        fun tileRow(tile: Int, y: Int): Int? {
            val (src, i) = if (tile < t.primaryTiles) primary.tiles to tile else secondary.tiles to tile - t.primaryTiles
            val o = i * 32 + y * 4
            if (o < 0 || o + 4 > src.size) return null
            return (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or ((src[o + 2].toInt() and 0xFF) shl 16) or ((src[o + 3].toInt() and 0xFF) shl 24)
        }
        fun metatile(mid: Int): IntArray? = cache.getOrPut(mid) {
            val (src, i) = if (mid < t.primaryMetatiles) primary.metatiles to mid else secondary.metatiles to mid - t.primaryMetatiles
            if (i * 16 + 16 > src.size) return@getOrPut null
            val px = IntArray(256) { backdrop }
            for (k in 0 until 8) {
                val e = (src[i * 16 + 2 * k].toInt() and 0xFF) or ((src[i * 16 + 2 * k + 1].toInt() and 0xFF) shl 8)
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

    private class Tileset(val tiles: ByteArray, val palettes: ByteArray, val metatiles: ByteArray)

    private fun tileset(t: AreaMapTables, addr: Long, primary: Boolean, read: (Long, Int) -> ByteArray): Tileset? {
        if (!inRom(addr)) return null
        val b = read(addr, 0x10)
        val compressed = b[0].toInt() != 0
        val tilesAt = u32(b, 4)
        val palsAt = u32(b, 8)
        val metasAt = u32(b, 0xC)
        if (!inRom(tilesAt) || !inRom(palsAt) || !inRom(metasAt)) return null
        val tileCount = if (primary) t.primaryTiles else TILES_TOTAL - t.primaryTiles
        val tiles = if (compressed) {
            val head = read(tilesAt, 4)
            if (head[0].toInt() != 0x10) return null
            val size = (head[1].toInt() and 0xFF) or ((head[2].toInt() and 0xFF) shl 8) or ((head[3].toInt() and 0xFF) shl 16)
            if (size !in 32..TILES_TOTAL * 32) return null
            // Worst case: a flag byte per 8 literals.
            Gfx.lz77(read(tilesAt, 4 + size + size / 8 + 8))
        } else read(tilesAt, tileCount * 32)
        val metaCount = if (primary) t.primaryMetatiles else METATILES_TOTAL - t.primaryMetatiles
        return Tileset(tiles, read(palsAt, 16 * 32), read(metasAt, metaCount * 16))
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

    /** MapHeader.mapType: town, city, route, underwater, ocean route - the region map's places. */
    private val OUTDOOR = setOf(1, 2, 3, 5, 6)
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
 * What AREA maps need of a game (gamedata/schema/gen3_area_map.toml; records in gamedata/gen3/records/): gMapGroups and its
 * groups' sizes, the primary tileset's tiles / metatiles / palettes (FireRed's engine 640 / 640 / 7, Emerald's 512 / 512 /
 * 6) and where MapHeader keeps the floor number (FireRed's engine +0x1A; Emerald has none: -1).
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
    /** The map grid's metatile id bits, also its undefined square (0x3FF everywhere but SoulGold's 0x7FF). */
    val metatileMask: Int = 0x3FF,
)

/** The running game's AREA map tables: its config's, checked against the ROM ([AreaMap.matches]); null = none. English
 * Unbound runs without a config of its own set (the Poller reads it through NATIVE_UNBOUND_WITH_DEX). */
private fun activeAreaMapTables(): AreaMapTables? =
    activeNativeConfig?.areaMap ?: if (activeGame == GameKind.UNBOUND) NATIVE_UNBOUND_WITH_DEX.areaMap else null

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
            }
            return t.takeIf { ok }
        }
    }

    fun cachedArea(id: AreaMap.MapId): AreaMap.Area? = areas[id]?.value
    fun cachedImage(id: AreaMap.MapId): RomArt.Image? = synchronized(lock) { images[id]?.value }

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

    /** [PokedexSource.romChanged]. */
    fun romChanged() {
        synchronized(lock) { checked = null; ok = false; areas.clear(); images.clear() }
    }
}
