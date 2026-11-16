package com.pokedaisy.app.companion.data

/**
 * A name table found in the ROM ([RomNameTables.locate]): id's name at `base + id * stride`
 * (offsets from 0x08000000), either the string itself (vanilla's gSpeciesNames, expansion's
 * gSpeciesInfo[].speciesName) or a pointer to it ([pointers]: expansion's gMovesInfo[].name).
 * [agrees]: the share of the config's names the ROM holds that sit at their own id here.
 */
class RomNameTable(val base: Long, val stride: Int, val pointers: Boolean, val agrees: Double = 0.0) {
    /** Where entry [id]'s string starts, or -1 (a pointer out of the ROM). */
    fun stringAt(reader: MemoryReader, id: Int, size: Long): Long {
        val at = base + id.toLong() * stride
        if (at < 0 || at + 4 > size) return -1
        if (!pointers) return at
        val b = runCatching { reader.readCoreMemory(0x08000000L + at, 4) }.getOrNull() ?: return -1
        val p = ((b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)) - 0x08000000L
        return if (p in 0 until size) p else -1
    }

    /** Whether entry [id] reads as [key] ([RomNames.key]). */
    fun reads(reader: MemoryReader, names: RomNames, id: Int, key: String, size: Long): Boolean {
        val s = stringAt(reader, id, size)
        return s >= 0 && names.readsAt(reader, s, key)
    }

    override fun toString() = "0x${(0x08000000L + base).hex(8, upper = true)}+$stride*id${if (pointers) " (ptr)" else ""} ${agrees.fixed(2)}"
}

/**
 * Finds a config's name tables in a ROM from its own names, wherever a build put them - so a
 * rebuild with moved tables is checked id by id, and a different game whose ids name other
 * Pokémon (or more of them) is told apart even when every one of its names is somewhere in the ROM.
 */
object RomNameTables {
    private const val ANCHORS = 8
    private const val SAMPLE = 32
    private const val MAX_STRIDE = 0x400
    /** A table must put at least this share of the sampled names at their own ids. */
    const val AGREES_MIN = 0.8

    /** [entries] (id -> key) the ROM holds, by id - the ones a table is located and checked by. */
    fun present(names: RomNames, entries: Map<Int, String>): List<Pair<Int, String>> =
        entries.entries.filter { names.holds(it.value) }.sortedBy { it.key }.map { it.key to it.value }

    /** The strings the first names of [present] start at: the targets of a pointer table's words. */
    fun anchorOffsets(names: RomNames, present: List<Pair<Int, String>>): Set<Int> =
        present.take(ANCHORS).flatMapTo(HashSet()) { names.at(it.second).asList() }

    /**
     * Every aligned ROM word pointing at one of [targets], by target: what [locate] needs to find a
     * pointer table. One pass over the ROM.
     */
    fun pointersTo(reader: MemoryReader, size: Long, targets: Set<Int>): Map<Int, IntArray> {
        if (targets.isEmpty()) return emptyMap()
        val out = HashMap<Int, IntArray>()
        val chunk = 1 shl 20
        var off = 0L
        while (off < size) {
            val len = minOf(chunk.toLong(), size - off).toInt() and 3.inv()
            if (len == 0) break
            val b = reader.readCoreMemory(0x08000000L + off, len)
            var i = 0
            while (i < len) {
                val top = b[i + 3].toInt() and 0xFF
                if (top == 0x08 || top == 0x09) {
                    val v = ((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or
                        ((top - 0x08) shl 24))
                    if (v in targets) {
                        val seen = out[v]
                        if (seen == null || seen.size < 16) out[v] = (seen ?: IntArray(0)) + (off + i).toInt()
                    }
                }
                i += 4
            }
            off += len
        }
        return out
    }

    /**
     * The table [present] (a config's names the ROM holds, by id) sits in: base and stride voted
     * for by pairs of its first names' copies, then checked on a spread of the rest. Inline strings
     * first; [pointers] (from [pointersTo]) for a table of pointers. Null when none agrees enough.
     */
    fun locate(reader: MemoryReader, names: RomNames, size: Long, present: List<Pair<Int, String>>, pointers: Map<Int, IntArray>? = null): RomNameTable? {
        if (present.size < 6) return null
        val anchors = present.take(ANCHORS)
        val sample = if (present.size <= SAMPLE) present else List(SAMPLE) { present[it * (present.size - 1) / (SAMPLE - 1)] }
        fun best(positions: (String) -> IntArray, ptr: Boolean): RomNameTable? {
            val votes = HashMap<Long, Int>()
            for (i in anchors.indices) for (j in i + 1 until anchors.size) {
                val (ia, ka) = anchors[i]
                val (ib, kb) = anchors[j]
                val did = ib - ia
                for (pa in positions(ka)) for (pb in positions(kb)) {
                    val d = pb - pa
                    if (d <= 0 || d % did != 0) continue
                    val s = d / did
                    if (s !in 4..MAX_STRIDE) continue
                    val base = pa.toLong() - ia.toLong() * s
                    if (base < 0) continue
                    val k = base * (MAX_STRIDE + 1) + s
                    votes[k] = (votes[k] ?: 0) + 1
                }
            }
            return votes.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(3).map { (k, _) ->
                val t = RomNameTable(k / (MAX_STRIDE + 1), (k % (MAX_STRIDE + 1)).toInt(), ptr)
                val agrees = sample.count { (id, key) -> t.reads(reader, names, id, key, size) }.toDouble() / sample.size
                RomNameTable(t.base, t.stride, ptr, agrees)
            }.maxByOrNull { it.agrees }?.takeIf { it.agrees >= AGREES_MIN }
        }
        return best(names::at, false) ?: pointers?.let { p ->
            best({ key -> names.at(key).flatMap { (p[it] ?: IntArray(0)).asList() }.toIntArray() }, true)
        }
    }
}
