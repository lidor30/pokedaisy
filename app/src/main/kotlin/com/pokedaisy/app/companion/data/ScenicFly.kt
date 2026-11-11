package com.pokedaisy.app.companion.data

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * The scenic FLY's route (SETTINGS > FLY STYLE > SCENIC): from where the player stands to the town's landing spot,
 * flown over the real field (pk_flight.c's route) instead of the game's cut-in and warp. The world is the game's own
 * maps laid side by side the way their connections join them (gMapGroups' headers: layout size, connections and their
 * offsets) - Kanto's towns and routes are one connected piece, the Sevii Islands others - and the route only crosses
 * from one map to the next where the game connects them, as walking would. A town the player's map isn't joined to
 * (another island) has no route: FLY flies the classic way there.
 *
 * Read on the emu thread ([World.read], small ROM reads); the path ([World.route]) is plain arithmetic, for any thread.
 */
object ScenicFly {
    data class MapId(val group: Int, val num: Int)

    /** A connection: [dir] (1 south, 2 north, 3 west, 4 east), the other map's [offset] along the edge. */
    data class Conn(val dir: Int, val offset: Int, val to: MapId)

    /** A map placed in the world: its top-left tile ([x], [y]) and size; [holes] its squares the game leaves undefined
     * (metatile 0x3FF, row by row: Route 20's west edge has a run of them), which nothing crosses. */
    class Placed(val id: MapId, val x: Int, val y: Int, val w: Int, val h: Int, val conns: List<Conn>, val holes: BooleanArray = BooleanArray(w * h)) {
        fun hole(lx: Int, ly: Int) = holes[ly * w + lx]
    }

    /** The connected maps around [start], placed. */
    class World(val maps: List<Placed>) {
        private val index = maps.withIndex().associate { (i, m) -> m.id to i }
        fun placed(id: MapId) = index[id]?.let { maps[it] }

        /**
         * The directions (1 down, 2 up, 3 left, 4 right) from tile ([fx], [fy]) of [from] to ([tx], [ty]) of [to], each map's
         * own coordinates; null when there's no way through joined maps (or either end isn't in this world). A
         * shortest path, then pulled straight: each stretch a staircase between two of its points wherever the
         * staircase stays on joined maps, so a diagonal trip looks like one.
         */
        fun route(from: MapId, fx: Int, fy: Int, to: MapId, tx: Int, ty: Int): ByteArray? {
            val a = placed(from) ?: return null
            val b = placed(to) ?: return null
            if (fx !in 0 until a.w || fy !in 0 until a.h || tx !in 0 until b.w || ty !in 0 until b.h) return null
            val grid = Grid(maps)
            val start = grid.cell(a.x + fx, a.y + fy)
            val goal = grid.cell(b.x + tx, b.y + ty)
            // Each end on its own map's ground: where two maps overlap in the layout (a loop of connections that doesn't
            // close, Heart and Soul's Violet side) the first one placed holds the tile, and flown there the game is on it.
            if (start < 0 || goal < 0 || grid.owner[start] != index[from] || grid.owner[goal] != index[to]) return null
            val path = grid.shortest(start, goal) ?: return null
            return grid.directions(grid.straighten(path))
        }
    }

    /** Every map joined to [start] by connections (breadth first, at most [MAX_MAPS]), laid out from it at (0, 0);
     * [allowed]: FLIGHT AREA's maps ([FlightArea]), null = all ([start] always). */
    fun read(f: FlightFns, start: MapId, read: (Long, Int) -> ByteArray, allowed: ((MapId) -> Boolean)? = null): World? {
        fun u32(a: Long) = read(a, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        fun s32(a: Long) = u32(a).toInt()
        fun header(id: MapId): Long? {
            if (id.group !in f.mapGroupSizes.indices || id.num !in 0 until f.mapGroupSizes[id.group]) return null
            val list = u32(f.mapGroups + 4L * id.group) xor f.mapGroupsXor
            return u32(list + 4L * id.num).takeIf { it in ROM_START until ROM_END }
        }
        val noFly = f.noFly.map { MapId(it shr 8, it and 0xFF) }.toSet()
        val placed = LinkedHashMap<MapId, Placed>()
        val queue = ArrayDeque<Triple<MapId, Int, Int>>()
        queue += Triple(start, 0, 0)
        while (queue.isNotEmpty() && placed.size < MAX_MAPS) {
            val (id, x, y) = queue.removeFirst()
            if (id in placed || id in noFly || (id != start && allowed?.invoke(id) == false)) continue
            val h = header(id) ?: continue
            val layout = u32(h).takeIf { it in ROM_START until ROM_END } ?: continue
            val w = s32(layout)
            val hh = s32(layout + 4)
            if (w !in 1..MAX_SIDE || hh !in 1..MAX_SIDE) continue
            val conns = mutableListOf<Conn>()
            val c = u32(h + 0xC)
            if (c in ROM_START until ROM_END) {
                val n = s32(c)
                val list = u32(c + 4)
                if (n in 1..16 && list in ROM_START until ROM_END) for (i in 0 until n) {
                    val e = read(list + 12L * i, 12)
                    val dir = e[0].toInt() and 0xFF
                    val off = (e[4].toInt() and 0xFF) or ((e[5].toInt() and 0xFF) shl 8) or ((e[6].toInt() and 0xFF) shl 16) or (e[7].toInt() shl 24)
                    if (dir in 1..4) conns += Conn(dir, off, MapId(e[8].toInt() and 0xFF, e[9].toInt() and 0xFF))
                }
            }
            // The map's squares (MapLayout +0xC: a u16 each, metatile id in the low 10 bits; 0x3FF = undefined - SoulGold's
            // 11 bits and 0x7FF: [FlightFns.mapGridUndefined]).
            val holes = BooleanArray(w * hh)
            val data = u32(layout + 0xC)
            if (data in ROM_START until ROM_END) {
                val b = read(data, w * hh * 2)
                val undefined = if (f.mapGridUndefined != 0) f.mapGridUndefined else 0x3FF
                for (i in 0 until w * hh) holes[i] = ((b[2 * i].toInt() and 0xFF) or ((b[2 * i + 1].toInt() and 0xFF) shl 8)) and undefined == undefined
            }
            placed[id] = Placed(id, x, y, w, hh, conns, holes)
            for (k in conns) {
                if (k.to in placed || k.to in noFly || allowed?.invoke(k.to) == false) continue
                val oh = header(k.to) ?: continue
                val ol = u32(oh).takeIf { it in ROM_START until ROM_END } ?: continue
                val ow = s32(ol)
                val ohh = s32(ol + 4)
                queue += when (k.dir) {
                    SOUTH -> Triple(k.to, x + k.offset, y + hh)
                    NORTH -> Triple(k.to, x + k.offset, y - ohh)
                    WEST -> Triple(k.to, x - ow, y + k.offset)
                    else -> Triple(k.to, x + w, y + k.offset)
                }
            }
        }
        return World(placed.values.toList()).takeIf { placed.isNotEmpty() }
    }

    /** The landing spot FLY sets for [mapsec] (sMapFlyDestinations' heal location: its map and tile), or null for a town
     * with none (a plain map warp: the classic FLY). */
    fun destination(fly: FlyFns, f: FlightFns, mapsec: Int, read: (Long, Int) -> ByteArray): Triple<MapId, Int, Int>? {
        // Unbound: its own fly spots (u16 section, u8 num, u8 group, u16 x, u16 y), where its FLY warps.
        fly.spots?.let { s ->
            val t = read(s.addr, s.count * s.stride)
            for (k in 0 until s.count) {
                val o = k * s.stride
                if (((t[o].toInt() and 0xFF) or ((t[o + 1].toInt() and 0xFF) shl 8)) != mapsec) continue
                val x = (t[o + 4].toInt() and 0xFF) or ((t[o + 5].toInt() and 0xFF) shl 8)
                val y = (t[o + 6].toInt() and 0xFF) or ((t[o + 7].toInt() and 0xFF) shl 8)
                return Triple(MapId(t[o + 3].toInt() and 0xFF, t[o + 2].toInt() and 0xFF), x, y)
            }
            return null
        }
        val i = mapsec - fly.firstMapsec
        val hw = fly.healWarp
        // Emerald's Fly map exit (HealWarp): Littleroot by the player's gender (outside their house), Ever Grande / the
        // Battle Frontier outside rather than the table's (the League's door, a gate) - where the classic FLY lands.
        val heal = when {
            hw != null && mapsec == hw.genderMapsec -> {
                val sb2 = if (hw.saveBlock2Static) hw.saveBlock2Ptr
                    else read(hw.saveBlock2Ptr, 4).let { b -> (0..3).fold(0L) { v, k -> v or ((b[k].toLong() and 0xFF) shl (8 * k)) } }
                if (sb2 != 0L && read(sb2 + 8, 1)[0].toInt() and 1 != 0) hw.femaleHeal else hw.maleHeal
            }
            hw != null && mapsec in hw.special -> hw.special.getValue(mapsec)
            i !in 0 until fly.count -> return null
            else -> read(fly.destinations + i * 3L, 3)[2].toInt() and 0xFF
        }
        if (heal !in 1..f.healLocationCount) return null
        val e = read(f.healLocations + (heal - 1L) * f.healLocationSize, 6)
        val x = (e[2].toInt() and 0xFF) or (e[3].toInt() shl 8)
        val y = (e[4].toInt() and 0xFF) or (e[5].toInt() shl 8)
        return Triple(MapId(e[0].toInt() and 0xFF, e[1].toInt() and 0xFF), x, y)
    }

    /** Where the player stands: SaveBlock1's location (map group, num at +4 / +5) and pos (the map's own tile, +0 / +2). */
    fun here(f: FlightFns, read: (Long, Int) -> ByteArray): Triple<MapId, Int, Int>? {
        val sb1 = if (f.saveBlockStatic) f.saveBlock1Ptr
            else read(f.saveBlock1Ptr, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        if (sb1 == 0L) return null
        val b = read(sb1 + f.saveBlock1PosOff, 6)
        val x = (b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)
        val y = (b[2].toInt() and 0xFF) or (b[3].toInt() shl 8)
        return Triple(MapId(b[4].toInt() and 0xFF, b[5].toInt() and 0xFF), x, y)
    }

    /** The world as cells: which map each one is in, and which steps between two maps are a connection. */
    private class Grid(val maps: List<Placed>) {
        val x0 = maps.minOf { it.x }
        val y0 = maps.minOf { it.y }
        val w = maps.maxOf { it.x + it.w } - x0
        val h = maps.maxOf { it.y + it.h } - y0
        val owner = IntArray(w * h) { -1 }
        /** Each map's connections in the header's order: the way, the other map, where the connection puts it (its
         * top-left tile in the world) and whether that's where it was placed. */
        private class Edge(val dir: Int, val to: Int, val x: Int, val y: Int, val placedThere: Boolean)
        private val edges = Array(maps.size) { ArrayList<Edge>() }

        init {
            maps.forEachIndexed { i, m ->
                for (yy in m.y until m.y + m.h) for (xx in m.x until m.x + m.w) {
                    val c = (yy - y0) * w + (xx - x0)
                    // Overlapping layouts: the first map placed keeps the cell. An undefined square stays empty.
                    if (owner[c] < 0) owner[c] = if (m.hole(xx - m.x, yy - m.y)) HOLE else i
                }
            }
            val at = maps.withIndex().associate { (i, m) -> m.id to i }
            maps.forEachIndexed { i, m ->
                m.conns.forEach { k ->
                    val j = at[k.to] ?: return@forEach
                    val o = maps[j]
                    val (ex, ey) = when (k.dir) {
                        SOUTH -> m.x + k.offset to m.y + m.h
                        NORTH -> m.x + k.offset to m.y - o.h
                        WEST -> m.x - o.w to m.y + k.offset
                        else -> m.x + m.w to m.y + k.offset
                    }
                    edges[i] += Edge(k.dir, j, ex, ey, o.x == ex && o.y == ey)
                }
            }
        }


        fun cell(x: Int, y: Int): Int {
            val cx = x - x0
            val cy = y - y0
            return if (cx in 0 until w && cy in 0 until h && owner[cy * w + cx] >= 0) cy * w + cx else -1
        }

        /** The cell one step [dir] from [c], or -1: off the world, or into another map the game doesn't join there. */
        fun step(c: Int, dir: Int): Int {
            val x = c % w
            val y = c / w
            val n = when (dir) {
                SOUTH -> if (y + 1 < h) c + w else -1
                NORTH -> if (y > 0) c - w else -1
                WEST -> if (x > 0) c - 1 else -1
                else -> if (x + 1 < w) c + 1 else -1
            }
            if (n < 0 || owner[n] < 0) return -1
            val a = owner[c]
            val b = owner[n]
            if (a == b) return n
            // Off the edge the game takes the first connection that way whose map spans the spot (fieldmap.c's
            // GetIncomingConnection): two maps joined to one edge may overlap, and the cell's first-placed owner isn't it.
            val wx = n % w + x0
            val wy = n / w + y0
            // A map reached by two paths keeps its first place (Heart and Soul's Johto has such loops): through the other
            // connection the game's edge leads where the layout doesn't show, so no crossing there.
            val to = edges[a].firstOrNull { e -> e.dir == dir && maps[e.to].let { wx in e.x until e.x + it.w && wy in e.y until e.y + it.h } }
            return if (to != null && to.to == b && to.placedThere) n else -1
        }

        /** A shortest path of cells (A*, 4 ways), or null. */
        fun shortest(start: Int, goal: Int): IntArray? {
            val gx = goal % w
            val gy = goal / w
            val cost = IntArray(w * h) { Int.MAX_VALUE }
            val from = IntArray(w * h) { -1 }
            val open = PriorityQueue<Long>(compareBy { it shr 32 })
            cost[start] = 0
            open += start.toLong()
            while (open.isNotEmpty()) {
                val c = (open.poll() and 0xFFFFFFFFL).toInt()
                if (c == goal) break
                for (d in 1..4) {
                    val n = step(c, d)
                    if (n < 0 || cost[c] + 1 >= cost[n]) continue
                    cost[n] = cost[c] + 1
                    from[n] = c
                    val f = cost[n] + abs(n % w - gx) + abs(n / w - gy)
                    open += (f.toLong() shl 32) or n.toLong()
                }
            }
            if (cost[goal] == Int.MAX_VALUE) return null
            val out = ArrayList<Int>()
            var c = goal
            while (c != -1) { out += c; c = from[c] }
            return out.reversed().toIntArray()
        }

        /** The staircase from [a] to [b] (Bresenham's line, one axis at a time), or null if a step of it isn't allowed. */
        fun staircase(a: Int, b: Int): IntArray? {
            val ax = a % w
            val ay = a / w
            val dx = b % w - ax
            val dy = b / w - ay
            val n = abs(dx) + abs(dy)
            val out = IntArray(n + 1)
            out[0] = a
            var c = a
            var sx = 0
            var sy = 0
            for (i in 1..n) {
                // The axis that's further behind its share of the line goes next.
                val wantX = abs(dx) > 0 && (abs(dy) == 0 || (sx + 0.5) / abs(dx) <= (sy + 0.5) / abs(dy))
                val dir = if (wantX) (if (dx > 0) EAST else WEST) else (if (dy > 0) SOUTH else NORTH)
                c = step(c, dir)
                if (c < 0) return null
                if (wantX) sx++ else sy++
                out[i] = c
            }
            return out
        }

        /** [path] pulled straight: from each point, the furthest later one a staircase reaches. */
        fun straighten(path: IntArray): IntArray {
            if (path.size < 3) return path
            val out = ArrayList<Int>()
            out += path[0]
            var i = 0
            while (i < path.size - 1) {
                var j = path.size - 1
                var line: IntArray? = null
                while (j > i + 1) {
                    line = staircase(path[i], path[j])
                    if (line != null) break
                    j -= if (j - i > 64) 8 else 1
                }
                if (j <= i + 1) { j = i + 1; line = intArrayOf(path[i], path[j]) }
                for (k in 1 until line!!.size) out += line[k]
                i = j
            }
            return out.toIntArray()
        }

        fun directions(path: IntArray): ByteArray = ByteArray(path.size - 1) { k ->
            val d = path[k + 1] - path[k]
            when (d) {
                w -> SOUTH
                -w -> NORTH
                -1 -> WEST
                else -> EAST
            }.toByte()
        }
    }

    /** A Grid cell a map leaves undefined: no one's, and kept from being filled by an overlapping map. */
    private const val HOLE = -2
    const val SOUTH = 1
    const val NORTH = 2
    const val WEST = 3
    const val EAST = 4
    private const val MAX_MAPS = 200
    private const val MAX_SIDE = 512
    private const val ROM_START = 0x08000000L
    private const val ROM_END = 0x0A000000L
    /** pk_flight.c's route buffer. */
    const val MAX_STEPS = 4096
}
