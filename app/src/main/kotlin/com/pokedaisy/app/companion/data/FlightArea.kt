package com.pokedaisy.app.companion.data

import java.io.File
import java.util.zip.CRC32

/**
 * FLIGHT AREA (SETTINGS > GAMEPLAY, [com.pokedaisy.app.companion.FlightAreaMode]): FREE FLIGHT and the scenic FLY cross only
 * into maps the player has seen, so flying never skips the story (a sleeping giant on Route 12, Saffron's guards, Victory
 * Road). The game keeps no record of routes, so the app keeps one per save ([SeenMaps]): every map the player stands on,
 * plus, the first time a save is met, what the save itself proves ([seed]): the towns its Fly map lists, the maps joining
 * two of them (crossed to get from one to the other), and every map with a trainer it has beaten. pk_flight.c gets it as a
 * bit per map ([bits], MgbaCore.pkFlightArea) and turns the bird at the edge of a map it hasn't got; [ScenicFly.read]
 * plans routes over those maps only (a town past them flies the classic way).
 *
 * Map ids are `group shl 8 or num`, as pk_flight.h's.
 */
object FlightArea {
    /** pk_flight.h's PK_FLIGHT_AREA_BYTES: a bit for every group / num pair. */
    const val BYTES = 8192

    /** TRAINER_FLAGS_START in every engine FREE FLIGHT runs (pret's, CFRU's, the expansion's): beaten = this + trainer id. */
    const val TRAINER_FLAGS_START = 0x500

    /** The highest trainer id read as one: past it a script's bytes weren't a trainerbattle. */
    private const val MAX_TRAINER = 0x400

    fun id(m: ScenicFly.MapId) = (m.group shl 8) or m.num

    /** pk_flight_set_area's bits: bit id % 8 of byte id / 8. */
    fun bits(maps: Collection<Int>): ByteArray = ByteArray(BYTES).also { b ->
        for (id in maps) if (id in 0 until BYTES * 8) b[id shr 3] = (b[id shr 3].toInt() or (1 shl (id and 7))).toByte()
    }

    /** A map's [connections] (other maps' ids, either way) and the [trainers] its people battle (trainerbattle ids). */
    class MapInfo(val id: Int, val connections: List<Int>, val trainers: List<Int>)

    /**
     * Every map in gMapGroups: its connections and the trainers its object events battle. A trainer is an object whose script
     * starts with trainerbattle (0x5C) - pret's / CFRU's / the expansion before 1.12 put the trainer id right after the type
     * byte (+2), 1.12's TrainerBattleParameter after a flags and a local id byte (+3); which one a ROM uses is voted on by
     * where the first text pointer's top byte falls (+7 or +8). ROM reads only.
     */
    fun readMaps(f: FlightFns, read: (Long, Int) -> ByteArray): Map<Int, MapInfo> {
        fun u32(a: Long) = read(a, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        fun rom(a: Long) = a in ROM_START until ROM_END
        class Raw(val id: Int, val conns: List<Int>, val scripts: List<ByteArray>)
        val raws = ArrayList<Raw>()
        for (g in f.mapGroupSizes.indices) {
            val list = u32(f.mapGroups + 4L * g) xor f.mapGroupsXor
            if (!rom(list)) continue
            val headers = read(list, 4 * f.mapGroupSizes[g])
            for (n in 0 until f.mapGroupSizes[g]) {
                val h = (0..3).fold(0L) { v, i -> v or ((headers[4 * n + i].toLong() and 0xFF) shl (8 * i)) }
                if (!rom(h)) continue
                val head = read(h, 0x10)
                fun word(o: Int) = (0..3).fold(0L) { v, i -> v or ((head[o + i].toLong() and 0xFF) shl (8 * i)) }
                val conns = ArrayList<Int>()
                val c = word(0xC)
                if (rom(c)) {
                    val cn = u32(c).toInt()
                    val cl = u32(c + 4)
                    if (cn in 1..16 && rom(cl)) {
                        val e = read(cl, 12 * cn)
                        for (k in 0 until cn) if ((e[12 * k].toInt() and 0xFF) in 1..4)
                            conns += ((e[12 * k + 8].toInt() and 0xFF) shl 8) or (e[12 * k + 9].toInt() and 0xFF)
                    }
                }
                val scripts = ArrayList<ByteArray>()
                val ev = word(4)
                if (rom(ev)) {
                    val count = read(ev, 1)[0].toInt() and 0xFF
                    val objs = u32(ev + 4)
                    if (count in 1..64 && rom(objs)) {
                        val t = read(objs, OBJECT_TEMPLATE * count)
                        for (k in 0 until count) {
                            val o = OBJECT_TEMPLATE * k + 0x10
                            val sp = (0..3).fold(0L) { v, i -> v or ((t[o + i].toLong() and 0xFF) shl (8 * i)) }
                            if (!rom(sp)) continue
                            val sb = read(sp, 13)
                            if (sb[0].toInt() and 0xFF == 0x5C) scripts += sb
                        }
                    }
                }
                raws += Raw((g shl 8) or n, conns, scripts)
            }
        }
        fun top(b: Byte) = (b.toInt() and 0xFF).let { it == 0x08 || it == 0x09 }
        val all = raws.flatMap { it.scripts }
        val newFormat = all.count { top(it[8]) } > all.count { top(it[7]) }
        val at = if (newFormat) 3 else 2
        val maps = HashMap<Int, MapInfo>()
        for (r in raws) {
            val trainers = r.scripts.map { (it[at].toInt() and 0xFF) or ((it[at + 1].toInt() and 0xFF) shl 8) }.filter { it in 1 until MAX_TRAINER }
            maps[r.id] = MapInfo(r.id, r.conns, trainers)
        }
        // Connections both ways: a map joined only one way still lies between the two.
        for (r in raws) for (c in r.conns) maps[c]?.let { o -> if (r.id !in o.connections) maps[c] = MapInfo(o.id, o.connections + r.id, o.trainers) }
        return maps
    }

    /**
     * What a save proves the player has seen, on top of [known] (maps already kept): the [visited] towns' maps, every map on
     * a shortest way between two of them through maps that aren't towns (Route 3 once Pewter and Cerulean are both visited:
     * [towns] are every town's map, so a way never runs through a third town), and each map with a trainer [beaten] there
     * that touches what's seen by then (a chain of them too) - a hack whose trainer flags aren't numbered as the engine's
     * (TRAINER_FLAGS_START) can't open a map far off. [noFly] maps are never crossed.
     */
    fun seed(
        maps: Map<Int, MapInfo>, towns: Set<Int>, visited: Set<Int>, beaten: (Int) -> Boolean,
        noFly: Set<Int> = emptySet(), known: Set<Int> = emptySet(),
    ): Set<Int> {
        val out = HashSet<Int>(visited)
        out += known
        for (t in visited) {
            if (t !in maps) continue
            val parent = HashMap<Int, Int>()
            parent[t] = t
            val queue = ArrayDeque<Int>()
            queue += t
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (n in maps[cur]?.connections ?: continue) {
                    if (n in parent || n in noFly || n !in maps) continue
                    parent[n] = cur
                    if (n in towns) {
                        if (n in visited) {
                            var k = cur
                            while (k != t) { out += k; k = parent.getValue(k) }
                        }
                        continue
                    }
                    queue += n
                }
            }
        }
        val fought = maps.values.filter { it.id !in out && it.id !in noFly && it.trainers.any(beaten) }.map { it.id }.toMutableSet()
        do {
            val next = fought.filter { id -> maps.getValue(id).connections.any { it in out } }
            out += next
            fought -= next.toSet()
        } while (next.isNotEmpty())
        return out
    }

    /**
     * Which save this is, to keep its maps apart from another game's on the same ROM: the player's name, gender and trainer
     * ID (SaveBlock2's first 14 bytes but its special-warp flags), CRC'd. Null before a save is loaded (all zero / 0xFF).
     */
    fun saveKey(f: FlightFns, read: (Long, Int) -> ByteArray): String? {
        val sb2 = if (f.saveBlockStatic) f.saveBlock2Ptr
            else read(f.saveBlock2Ptr, 4).let { b -> (0..3).fold(0L) { v, i -> v or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
        if (sb2 !in 0x02000000L until 0x04000000L) return null
        val b = read(sb2, 14)
        val key = b.copyOfRange(0, 9) + b.copyOfRange(10, 14)
        if (key.all { it == 0.toByte() } || key.all { it == 0xFF.toByte() }) return null
        return "%08x".format(CRC32().apply { update(key) }.value)
    }

    /** One save's seen maps, kept in [file] (a line per map, `group.num`). */
    class SeenMaps(private val file: File) {
        private val ids = HashSet<Int>()
        /** The save's flags were read into it ([seed]) this run. */
        var seeded = false

        init {
            runCatching {
                if (file.isFile) file.readLines().forEach { l ->
                    val p = l.trim().split('.')
                    if (p.size == 2) ids += (p[0].toInt() shl 8) or p[1].toInt()
                }
            }
        }

        val maps: Set<Int> get() = synchronized(ids) { ids.toSet() }

        operator fun contains(id: Int) = synchronized(ids) { id in ids }

        /** Adds [more]; true when any was new (then written). */
        fun addAll(more: Collection<Int>): Boolean {
            val added = synchronized(ids) { ids.addAll(more) }
            if (added) runCatching {
                file.parentFile?.mkdirs()
                val text = synchronized(ids) { ids.sorted().joinToString("\n") { "${it shr 8}.${it and 0xFF}" } }
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
            return added
        }

        val size: Int get() = synchronized(ids) { ids.size }

        companion object {
            /** [dir]/flight-area/<rom crc>-<save key>.txt. */
            fun of(dir: File, romKey: String, key: String) = SeenMaps(File(File(dir, "flight-area"), "$romKey-$key.txt"))
        }
    }

    /**
     * FLIGHT AREA for the running game (the activity keeps one per ROM): which save is loaded, its [SeenMaps] seeded once a
     * run from the game's own answers (GetMapsecType for its towns, FlagGet for its trainers), the map the player stands on
     * added as they go. Emu thread only, in a field job (the game's functions are called).
     */
    class Keeper(private val dir: File, private val romKey: String) {
        private var key: String? = null
        @Volatile private var seen: SeenMaps? = null
        private var maps: Map<Int, MapInfo>? = null
        private var sent = -1

        /** The next [tick] sends the bits again (FLIGHT AREA switched back on, a new core). */
        fun resend() { sent = -1 }

        /**
         * The save checked, seeded the first time it's met this run, the player's map added when [standing] (on the field,
         * not flying); pk_flight's bits when they changed, else null. [trainerFlags]: the game's FlagGet is the engine's
         * (Quetzal's flag check is its own region-badge one: no trainers there).
         */
        fun tick(fns: FieldItemFns, g: GameCalls, trainerFlags: Boolean, standing: Boolean): ByteArray? {
            val f = fns.flight ?: return null
            val fly = fns.fly ?: return null
            val k = saveKey(f, g::read) ?: return null
            if (k != key) {
                key = k
                seen = SeenMaps.of(dir, romKey, k)
                sent = -1
            }
            val s = seen ?: return null
            if (!s.seeded) {
                val m = maps ?: readMaps(f, g::read).also { maps = it }
                fun town(mapsec: Int) = ScenicFly.destination(fly, f, mapsec, g::read)?.first?.let(::id)
                val towns = flyTownSections(fly, g).mapNotNull(::town).toSet()
                val visited = flyVisitedTowns(fly, g).mapNotNull(::town).toSet()
                val beaten: (Int) -> Boolean =
                    if (trainerFlags) { t -> g.call(fly.flagGet, TRAINER_FLAGS_START + t) and 0xFF != 0L } else { _ -> false }
                s.addAll(seed(m, towns, visited, beaten, f.noFly.toSet(), s.maps))
                s.seeded = true
            }
            if (standing) ScenicFly.here(f, g::read)?.let { s.addAll(listOf(id(it.first))) }
            if (s.size == sent) return null
            sent = s.size
            return bits(s.maps)
        }

        /** Whether [m] is one of the loaded save's maps (true before a save is known: nothing to hold back yet). */
        fun allows(m: ScenicFly.MapId): Boolean = seen?.contains(id(m)) ?: true
    }

    /** struct ObjectEventTemplate: 24 bytes, the script pointer at +0x10, in every engine FREE FLIGHT runs. */
    private const val OBJECT_TEMPLATE = 24
    private const val ROM_START = 0x08000000L
    private const val ROM_END = 0x0A000000L
}
