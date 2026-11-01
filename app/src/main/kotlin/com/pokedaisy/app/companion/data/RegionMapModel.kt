package com.pokedaisy.app.companion.data

/**
 * One region map's cursor grid: which map section each tile is - the table
 * the game's own map cursor reads the name under it from. [layers] are
 * [w]x[h] mapsec bytes ([none] = nothing there): FireRed has two, the map
 * itself and the dungeons on it (Mt. Moon's icon on Route 4); Emerald one. The grid starts [offX], [offY] tiles into the region map's
 * 30x20 screen, the space [MapSecInfo]'s rects are in. [cellBytes] 2: u16
 * mapsec ids (SoulGold's 314 sections).
 */
class RegionLayout(
    val offX: Int, val offY: Int, val w: Int, val h: Int, val none: Int, val layers: List<ByteArray>, val cellBytes: Int = 1,
) {
    /** The mapsec at screen tile ([tx], [ty]) on [layer], or -1. */
    fun at(layer: Int, tx: Int, ty: Int): Int {
        val x = tx - offX
        val y = ty - offY
        if (layer >= layers.size || x !in 0 until w || y !in 0 until h) return -1
        return cell(layer, y * w + x).takeIf { it != none } ?: -1
    }

    /** Every cell of [layer], [none] included. */
    fun cells(layer: Int): List<Int> = (0 until w * h).map { cell(layer, it) }

    private fun cell(layer: Int, i: Int): Int {
        val l = layers[layer]
        return if (cellBytes == 2) (l[2 * i].toInt() and 0xFF) or ((l[2 * i + 1].toInt() and 0xFF) shl 8) else l[i].toInt() and 0xFF
    }
}

/** [hex] (two digits a byte) as bytes - the generated layouts' format. */
internal fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** A tile rect on a region map's screen, in 8px tiles. */
data class MapTiles(val x: Int, val y: Int, val w: Int, val h: Int) {
    operator fun contains(t: Pair<Int, Int>) = t.first in x until x + w && t.second in y until y + h
}

/** What's under a tile: its map section and, on FireRed, a dungeon there. */
data class MapPick(val mapsec: Int, val dungeon: Int)

/** A sprite the game draws over its map ([RegionMapModel.marks]): [asset] (regionmap/<name>.png, 16x16) centred on map
 * pixel ([x], [y]), or [visitedAsset] once first-arrival flag [visitedFlag] is set; [hiddenUntilVisited] = none before. */
data class RegionMapMark(val x: Int, val y: Int, val asset: String, val visitedAsset: String, val visitedFlag: Int, val hiddenUntilVisited: Boolean)

/**
 * A game's region maps for the Map tab: the screens ([images], regionmap/<name>.png),
 * the map sections on them ([sections], [MapSecInfo.region] indexes [images])
 * and, where known, each screen's cursor grid ([layouts], by image). Tile (tx, ty) covers map pixels from
 * ([originX] + tx * [tile], [originY] + ty * [tile]): the GBA maps' 8px tiles from the corner, Platinum's 7px Town Map
 * blocks from where its grid starts. [marks]: sprites over the map (Platinum's towns, coloured once visited). [points]: a
 * section's own point on the map (Black's Town Map places), where the game centres the player and its cursor.
 */
class RegionMapModel(
    val images: Array<String>,
    val sections: Map<Int, MapSecInfo>,
    val layouts: List<RegionLayout?>,
    val tile: Int = 8,
    val originX: Int = 0,
    val originY: Int = 0,
    val marks: List<RegionMapMark> = emptyList(),
    val points: Map<Int, Pair<Int, Int>> = emptyMap(),
) {
    /**
     * The section and dungeon at tile ([tx], [ty]) of image [region]: the grid
     * if there is one, else the smallest rect there - with a grid, only among
     * sections it leaves out (Emerald Rogue's hub areas).
     */
    fun pick(region: Int, tx: Int, ty: Int): MapPick? {
        val layout = layouts.getOrNull(region)
        if (layout != null) {
            val m = layout.at(0, tx, ty).takeIf { it in sections } ?: -1
            val d = layout.at(1, tx, ty).takeIf { it in sections } ?: -1
            if (m >= 0 || d >= 0) return MapPick(m, d)
        }
        val onGrid = layout?.let { gridSections.getOrPut(region) { it.layers.indices.flatMap { l -> it.cells(l) }.toSet() } }
        val m = sections.entries
            .filter { (id, s) -> s.region == region && s.w > 0 && s.h > 0 && (tx to ty) in MapTiles(s.x, s.y, s.w, s.h) && (onGrid == null || id !in onGrid) }
            .minByOrNull { (_, s) -> s.w * s.h }?.key ?: return null
        return MapPick(m, -1)
    }

    private val gridSections = HashMap<Int, Set<Int>>()

    /**
     * Where [mapsec] is on its image: its rect, or - for a section the
     * tables leave off the map (FireRed's dungeons) - every grid tile that
     * names it, one rect each (Diglett's Cave has both ends). Empty = nowhere.
     */
    fun tilesOf(mapsec: Int): List<MapTiles> {
        val s = sections[mapsec] ?: return emptyList()
        if (s.region !in images.indices) return emptyList()
        if (s.w > 0 && s.h > 0) return listOf(MapTiles(s.x, s.y, s.w, s.h))
        val layout = layouts.getOrNull(s.region) ?: return emptyList()
        val out = ArrayList<MapTiles>()
        for (layer in layout.layers.indices) for (y in 0 until layout.h) for (x in 0 until layout.w) {
            val t = MapTiles(x + layout.offX, y + layout.offY, 1, 1)
            if (layout.at(layer, t.x, t.y) == mapsec && t !in out) out += t
        }
        return out
    }

    /**
     * The places the PLACES list offers: every named section that's on a
     * map, once per name (FireRed names some twice), in three groups.
     */
    fun places(): List<Pair<String, List<Int>>> {
        val seen = HashSet<String>()
        val towns = ArrayList<Int>()
        val routes = ArrayList<Int>()
        val other = ArrayList<Int>()
        for ((id, s) in sections.entries.sortedBy { it.key }) {
            if (s.name.isBlank() || tilesOf(id).isEmpty() || !seen.add(s.name.lowercase())) continue
            when {
                isRoute(s.name) -> routes += id
                isTown(s.name) -> towns += id
                else -> other += id
            }
        }
        routes.sortWith(compareBy({ routeNumber(sections.getValue(it).name) }, { sections.getValue(it).name }))
        other.sortBy { sections.getValue(it).name.lowercase() }
        return listOf("TOWNS & CITIES" to towns, "ROUTES" to routes, "OTHER PLACES" to other).filter { it.second.isNotEmpty() }
    }

    private fun isRoute(name: String) = name.startsWith("route ", ignoreCase = true)

    private fun routeNumber(name: String) = name.substringAfter(' ').takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    private fun isTown(name: String): Boolean {
        val last = name.trim().substringAfterLast(' ').lowercase()
        return last in TOWN_WORDS
    }

    private companion object {
        val TOWN_WORDS = setOf("town", "city", "village", "island", "plateau")
    }
}

/** The Map tab's maps for the running game - the same source [lookupLocation] names places from. */
fun activeRegionMap(): RegionMapModel? {
    if (activeGame == GameKind.BLACK) return blackTownMap
    if (activeGame == GameKind.WHITE) return whiteTownMap
    if (activeGame == GameKind.BLACK2) return black2TownMap
    if (activeGame == GameKind.WHITE2) return white2TownMap
    if (activeGame == GameKind.PLATINUM) return platinumTownMap
    if (activeGame.isHgss) return PokegearMap.model()
    val rom = if (readsRomRegionMap) RomRegionMap.current else null
    if (rom != null) return RegionMapModel(rom.images, rom.sections, rom.layouts)
    if (activeGame == GameKind.ODYSSEY) return null // see lookupLocation
    val images = activeRegionMapImages
    if (images.isEmpty()) return null
    val layouts = when (activeGame) {
        // Recharged Yellow keeps FireRed's region map screens and cursor grids.
        GameKind.FIRERED, GameKind.RECHARGED_YELLOW -> regionLayoutsFireRed
        GameKind.EMERALD, GameKind.EMERALD_SEAGLASS, GameKind.TMT2, GameKind.ELITE_REDUX -> regionLayoutsEmerald
        GameKind.EMERALD_ROGUE -> regionLayoutsRogue
        GameKind.LAZARUS -> regionLayoutsLazarus
        GameKind.SOULGOLD -> regionLayoutsSoulGold
        GameKind.GLAZED -> regionLayoutsGlazed
        GameKind.HEART_AND_SOUL -> regionLayoutsHns
        GameKind.IMPERIUM -> regionLayoutsImperium
        GameKind.DREAMSTONE -> regionLayoutsDreamstone
        GameKind.WISHES -> regionLayoutsWishes
        GameKind.TEAM_ROCKET -> if (teamRocketV3) regionLayoutsTeamRocketV3 else emptyList()
        GameKind.MONOMYTH -> regionLayoutsMonomyth
        GameKind.QUETZAL -> if (quetzalV084) regionLayoutsEmerald else regionLayoutsQuetzal
        GameKind.ROWE -> regionLayoutsRowe
        GameKind.MARIOMON -> if (mariomonV13) regionLayoutsMariomonV13 else regionLayoutsMariomon
        else -> emptyList()
    }
    return RegionMapModel(images, activeMapSecData, layouts)
}

/**
 * Platinum's Town Map (NdsArt rebuilds its pictures from the cart): its 30 x 30 blocks, 7 pixels each, start at screen
 * (22, -37) - the game centres a block's sprites on 7 * x + 25, 7 * z - 34 - which is (2, -37) in the picture, cropped
 * 20 pixels in from the left ([NdsArt.PLATINUM_MAP_CROP]). Its sections are the places (location names), named by the
 * blocks through the main map matrix; its towns are the fly locations' blocks, grey until first arrived at.
 */
private val platinumTownMap: RegionMapModel by lazy {
    val crop = NdsArt.PLATINUM_MAP_CROP
    RegionMapModel(
        images = arrayOf(NdsArt.PLATINUM_MAP),
        sections = placeNamesPlatinum.mapValues { (id, name) -> MapSecInfo(NdsArt.names?.place(id) ?: name, 0, 0, 0, 0, 0) },
        layouts = listOf(RegionLayout(0, 0, 30, 30, 0xFFFF, listOf(hexBytes(TOWN_MAP_GRID_PLATINUM)), cellBytes = 2)),
        tile = 7,
        originX = 22 - crop[0],
        originY = -37 - crop[1],
        marks = townMapMarksPlatinum.map {
            RegionMapMark(it.x - crop[0], it.y - crop[1], NdsArt.platinumBlock(it.shape, 0), NdsArt.platinumBlock(it.shape, 1 + it.palette),
                it.firstArrival, it.hiddenUntilVisited)
        },
    )
}

/**
 * Black's Town Map (NdsArt rebuilds its picture and sprites from the cart): no blocks of its own - each place is a point
 * ([townMapPlacesBlack]) the game's cursor snaps to - so its grid here is 8-pixel cells, each the place whose point is
 * within 8 pixels of it (the records' touch area: their point 4 pixels up and down, radius 8), the nearest one where two
 * reach, a point's own cell always its place's. Its sections are every place name (the player's own zone names one the
 * map may not show: a house's, a cave's), named by the cart. Arrived-at towns, Victory Road and Unity Tower get the
 * game's sprite (Unity Tower none before), Liberty Garden its island's once the bag holds the Liberty Pass.
 */
internal val blackTownMap: RegionMapModel by lazy { gen5TownMap(townMapPlacesBlack) }

/** White's: Black's picture (the same files in its cart), its own places - White Forest where Black City is. */
internal val whiteTownMap: RegionMapModel by lazy { gen5TownMap(townMapPlacesWhite) }

/**
 * Black 2's: its own picture ([NdsArt.BLACK2_MAP]) and places, the same kind of map. Its arrival flags start lower
 * ([GEN5_BW2_FIRST_ARRIVAL]); arrived-at towns and the new places FLY reaches (kind 3) get the towns' dot, Unity Tower
 * its ring. A place the story reveals (kind 4) gets the green mark once its flag is set ([GEN5_BW2_SHOWN_BIT]: N's
 * Castle, the Abyssal Ruins); the Plasma Frigate's is one of the game's own checks, so it gets none. Checked against
 * the game's own Town Map headless on the player's save.
 */
internal val black2TownMap: RegionMapModel by lazy { gen5TownMap(townMapPlacesBlack2, sequel = true) }

/** White 2's: Black 2's picture, its own places - White Forest where Black City is. */
internal val white2TownMap: RegionMapModel by lazy { gen5TownMap(townMapPlacesWhite2, sequel = true) }

private fun gen5TownMap(places: List<Gen5TownMapPlace>, sequel: Boolean = false): RegionMapModel {
    val tile = GEN5_TOWN_MAP_TILE
    val w = 256 / tile
    val h = 168 / tile
    val none = 0xFFFF
    val cells = IntArray(w * h) { none }
    for (ty in 0 until h) for (tx in 0 until w) {
        val cx = tx * tile + tile / 2
        val cy = ty * tile + tile / 2
        var best = none
        var bestD = Int.MAX_VALUE
        for (p in places) {
            val dy = when { cy < p.y - 4 -> p.y - 4 - cy; cy > p.y + 4 -> cy - p.y - 4; else -> 0 }
            val d = (cx - p.x) * (cx - p.x) + dy * dy
            if (d <= 8 * 8 && d < bestD) { bestD = d; best = p.place }
        }
        cells[ty * w + tx] = best
    }
    for (p in places) cells[(p.y / tile) * w + p.x / tile] = p.place
    val grid = ByteArray(w * h * 2) { i -> (cells[i / 2] shr (8 * (i % 2))).toByte() }
    val town = if (sequel) NdsArt::black2Town else NdsArt::blackTown
    val shown = if (sequel) gen5ShownFlags(places) else emptyList()
    return RegionMapModel(
        images = arrayOf(if (sequel) NdsArt.BLACK2_MAP else NdsArt.BLACK_MAP),
        sections = (if (sequel) placeNamesBlack2 else placeNamesBlack)
            .mapValues { (id, name) -> MapSecInfo(NdsArt.names?.place(id) ?: name, 0, 0, 0, 0, 0) },
        layouts = listOf(RegionLayout(0, 0, w, h, none, listOf(grid), cellBytes = 2)),
        tile = tile,
        marks = places.mapNotNull { p ->
            val bit = when {
                p.kind == 4 && !sequel -> GEN5_LIBERTY_PASS_BIT
                p.kind == 4 -> shown.indexOf(p.shownBy).takeIf { it >= 0 }?.let { GEN5_BW2_SHOWN_BIT + it } ?: return@mapNotNull null
                p.flag >= 0 && p.kind in intArrayOf(0, 3, 5) -> p.flag - if (sequel) GEN5_BW2_FIRST_ARRIVAL else GEN5_FIRST_ARRIVAL
                else -> return@mapNotNull null
            }
            RegionMapMark(p.x, p.y, town(p.kind), town(p.kind), bit, hiddenUntilVisited = true)
        },
        points = places.associate { it.place to (it.x to it.y) },
    )
}

/** gMapHeader.mapType values (both decomps' MAP_TYPE_*) whose own position places the player on the map. */
private val OUTDOOR_MAP_TYPES = setOf(1, 2, 3, 5, 6) // TOWN, CITY, ROUTE, UNDERWATER, OCEAN_ROUTE

/**
 * The tile the game's region map puts the player's icon on: inside a
 * section of several tiles, the one the player's position on the map
 * ([x], [y] of a [mapW]x[mapH] map) falls in - region_map.c's
 * GetPlayerPositionOnRegionMap. Indoors and underground the game uses the
 * warp back outside instead, which isn't read here: there it's the tile
 * last worked out outdoors in the same section, else the section's first.
 */
object PlayerMapTile {
    private var lastMapsec = -1
    private var lastTile: Pair<Int, Int>? = null

    fun of(model: RegionMapModel, mapsec: Int, x: Int, y: Int, mapW: Int, mapH: Int, mapType: Int): Pair<Int, Int>? {
        val r = model.tilesOf(mapsec).firstOrNull() ?: return null
        if (mapType in OUTDOOR_MAP_TYPES && mapW > 0 && mapH > 0) {
            val dx = minOf(x / maxOf(1, mapW / r.w), r.w - 1).coerceAtLeast(0)
            val dy = minOf(y / maxOf(1, mapH / r.h), r.h - 1).coerceAtLeast(0)
            val t = r.x + dx to r.y + dy
            lastMapsec = mapsec
            lastTile = t
            return t
        }
        lastTile?.let { if (mapsec == lastMapsec && it in r) return it }
        return r.x to r.y
    }
}
