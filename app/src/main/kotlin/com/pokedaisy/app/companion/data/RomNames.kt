package com.pokedaisy.app.companion.data

/**
 * Which names the ROM's own text holds - BEST EFFORT's check of what the companion would show
 * (`BestEffort.kt`). A name counts when the ROM has it as a whole string: the game's text bytes,
 * not preceded by a letter and ended by 0xFF, wherever it sits - compared loosely, since the
 * shared tables use today's spellings: case-blind ([Gen3Text.foldTable]), spaces, hyphens,
 * periods and apostrophes ignored, é as E, ♀ / ♂ as F / M (FireRed's "THUNDERPUNCH", "POKé BALL",
 * "NIDORAN♀" are the app's "Thunder Punch", "Poke Ball", "Nidoran-F").
 * So it holds for a rebuild of a game (its tables moved, its names didn't) and fails for another
 * hack that happens to share the engine's RAM (Galar's towns aren't Johto's).
 *
 * One pass over the ROM looks for every name at once (keyed by their first 3 bytes), so callers
 * hand [scan] every name they may ask about; [has] answers from its result.
 */
class RomNames private constructor(private val found: Map<String, IntArray>, private val norm: IntArray) {
    /** Whether the ROM holds [name]; null when it can't tell (a character with no game byte, or
     * shorter than 3). Encoded with the [romLanguage] in force now - set it as [scan] had it. */
    fun has(name: String): Boolean? = key(name)?.let { it in found }

    /** Whether the ROM holds this [key]. */
    fun holds(key: String): Boolean = key in found

    /** The name [scan] found starting at this ROM offset, if any. */
    fun keyAt(offset: Int): String? = byOffset[offset]
    private val byOffset: Map<Int, String> by lazy { HashMap<Int, String>().also { m -> found.forEach { (k, at) -> at.forEach { m[it] = k } } } }

    /** Where in the ROM (offsets from 0x08000000) the first few copies of [key] start. */
    fun at(key: String): IntArray = found[key] ?: IntArray(0)

    /** Whether the string at [romOffset] of [reader]'s ROM reads as [key] (then 0xFF), compared like [scan]. */
    fun readsAt(reader: MemoryReader, romOffset: Long, key: String): Boolean {
        val raw = runCatching { reader.readCoreMemory(0x08000000L + romOffset, key.length * 2 + 4) }.getOrNull() ?: return false
        var k = 0
        for (b in raw) {
            val v = norm[b.toInt() and 0xFF]
            if (v < 0) continue
            if (k == key.length) return v == 0xFF
            if (v != key[k].code) return false
            k++
        }
        return false
    }

    companion object {
        private const val MIN_LEN = 3
        // Copies of a name kept: enough to find its table among a few stray ones (dialogue, a second list).
        private const val MAX_COPIES = 8
        private const val CHUNK = 1 shl 20

        /** [name] as [scan] compares it, null when it can't be compared (a character with no game
         * byte, no letter at all - "034", "????" placeholders - or shorter than 3 letters). */
        fun key(name: String): String? {
            if (name.none { it.isLetter() }) return null
            val loose = buildString {
                for (c in name) when (c) {
                    ' ', '-', '.', '\'', '’', '　' -> {}
                    'é', 'É' -> append('E')
                    '♀' -> append('F')
                    '♂' -> append('M')
                    else -> append(c)
                }
            }
            val b = Gen3Text.encode(loose) ?: return null
            if (b.size < MIN_LEN) return null
            val n = norm()
            return String(CharArray(b.size) { n[b[it].toInt() and 0xFF].toChar() })
        }

        /** Byte -> its loose form ([key]): case folded, é / ♀ / ♂ replaced, -1 for a byte to skip. */
        private fun norm(): IntArray {
            val n = Gen3Text.foldTable()
            if (romLanguage != 'J') {
                val e = Gen3Text.encode("E")!![0].toInt() and 0xFF
                n[0x1B] = e; n[0x06] = e
                n[0xB6] = Gen3Text.encode("F")!![0].toInt() and 0xFF
                n[0xB5] = Gen3Text.encode("M")!![0].toInt() and 0xFF
                for (b in intArrayOf(0x00, 0xAE, 0xAD, 0xB4)) n[b] = -1
            } else {
                n[0x00] = -1
            }
            return n
        }

        /** Letters and digits (after folding): a match may not start right after one. Not the accented
         * capitals: their bytes (0x01-0x15) are the small numbers a struct's fields hold right before a name. */
        private fun wordBytes(): BooleanArray = BooleanArray(256) { b ->
            if (romLanguage == 'J') b in 0x01..0xA0 else b in 0xBB..0xD4 || b in 0xA1..0xAA
        }

        /**
         * Every one of [keys] ([key]ed names) the first [size] bytes of the ROM at 0x08000000 hold,
         * its text read as [lang] (the header's language letter: 'J' is kana). Reads in 1 MB chunks
         * (the core bridge caps a read at 16 MB).
         */
        fun scan(reader: MemoryReader, size: Long, keys: Collection<String>, lang: Char = 'E'): RomNames {
            val saved = romLanguage
            romLanguage = if (lang == 'J') 'J' else 'E'
            try {
                return scanAs(reader, size, keys)
            } finally {
                romLanguage = saved
            }
        }

        private fun scanAs(reader: MemoryReader, size: Long, keys: Collection<String>): RomNames {
            val pats = keys.distinct().filter { it.length >= MIN_LEN }
            if (pats.isEmpty() || size <= 0) return RomNames(emptyMap(), norm())
            val heads = HashMap<Int, MutableList<String>>()
            val bits = LongArray((1 shl 24) / 64) // one bit per head
            for (p in pats) {
                val h = head(p[0].code, p[1].code, p[2].code)
                bits[h ushr 6] = bits[h ushr 6] or (1L shl h)
                heads.getOrPut(h) { ArrayList(1) } += p
            }
            val maxLen = pats.maxOf { it.length }
            val norm = norm()
            val word = wordBytes()
            val found = HashMap<String, IntArray>()
            var off = 0L
            var prev = 0xFF
            while (off < size) {
                val len = minOf(CHUNK.toLong(), size - off).toInt()
                // The chunk plus enough after it for a name (and its 0xFF) that starts near its end -
                // skipped bytes don't count, so a generous margin.
                val tail = minOf((4L * maxLen + 8), size - off - len).toInt()
                val raw = reader.readCoreMemory(0x08000000L + off, len + tail)
                val f = IntArray(raw.size)
                // A skipped byte (a space, a struct's 0x00 padding) before an element: a word boundary.
                val gap = BooleanArray(raw.size)
                val rawAt = IntArray(raw.size)
                var n = 0
                var starts = 0
                var skipped = false
                for (j in raw.indices) {
                    val v = norm[raw[j].toInt() and 0xFF]
                    if (v >= 0) { gap[n] = skipped; rawAt[n] = j; f[n++] = v; skipped = false } else skipped = true
                    if (j == len - 1) starts = n
                }
                for (i in 0 until starts) {
                    val before = if (i == 0) prev else f[i - 1]
                    if ((!gap[i] && word[before]) || i + MIN_LEN > n) continue
                    val h = head(f[i], f[i + 1], f[i + 2])
                    if (bits[h ushr 6] and (1L shl h) == 0L) continue
                    for (p in heads.getValue(h)) {
                        val end = i + p.length
                        if (end >= n || f[end] != 0xFF) continue
                        val seen = found[p]
                        if (seen != null && seen.size >= MAX_COPIES) continue
                        var k = MIN_LEN
                        while (k < p.length && f[i + k] == p[k].code) k++
                        if (k == p.length) found[p] = (seen ?: IntArray(0)) + (off + rawAt[i]).toInt()
                    }
                }
                if (starts > 0) prev = f[starts - 1]
                off += len
            }
            return RomNames(found, norm)
        }

        private fun head(a: Int, b: Int, c: Int) = (a shl 16) or (b shl 8) or c
    }
}
