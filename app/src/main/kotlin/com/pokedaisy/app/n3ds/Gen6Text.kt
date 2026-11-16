package com.pokedaisy.app.n3ds

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The names a Gen 6 game shows, read from the player's own cart (never bundled): the cart's RomFS (the first NCCH
 * partition's read-only file system), the game's text archive for the save's language (a GARC, `a/0/7/<n>`), and its
 * text files (each line XORed with a key that turns 3 bits a character, starting from 0x7C89 + 0x2983 a line).
 * Pokemon X / Y: species file 80, moves 13, items 96, abilities 34, natures 47, types 17, places 72 (checked on the
 * US cart, every language).
 */
class Gen6Text private constructor(private val files: List<ByteArray>, private val layout: N3dsLayout) {
    val species: List<String> by lazy { lines(layout.speciesFile) }
    val moves: List<String> by lazy { lines(layout.movesFile) }
    val items: List<String> by lazy { lines(layout.itemsFile) }
    val abilities: List<String> by lazy { lines(layout.abilitiesFile) }
    val natures: List<String> by lazy { lines(layout.naturesFile) }
    val types: List<String> by lazy { lines(layout.typesFile) }
    val places: List<String> by lazy { lines(layout.placesFile) }

    fun lines(file: Int): List<String> = files.getOrNull(file)?.let(::textLines).orEmpty()

    companion object {
        /** X / Y's text files (the other games': [N3dsLayout]). */
        const val SPECIES = 80
        const val MOVES = 13
        const val ITEMS = 96
        const val ABILITIES = 34
        const val NATURES = 47
        const val TYPES = 17
        const val PLACES = 72

        /** The save's language (MyStatus +0x2D: 1 Japanese, 2 English, 3 French, 4 Italian, 5 German, 7 Spanish,
         * 8 Korean) to its text archive in Pokemon X / Y's RomFS. Japanese is the kanji one. */
        fun archiveFor(language: Int): String = N3dsLayout.XY.textArchive(language)

        /** The save's language as a game code's language letter (AUTO's app language follows it); null for none. */
        fun languageLetter(language: Int): Char? = when (language) {
            1 -> 'J'
            2 -> 'E'
            3 -> 'F'
            4 -> 'I'
            5 -> 'D'
            7 -> 'S'
            8 -> 'K'
            else -> null
        }

        private val cache = HashMap<String, Gen6Text>()

        /** [cart]'s text in [language]; null when the cart can't be read that far. Kept per cart and language. */
        fun load(cart: File, language: Int, layout: N3dsLayout = N3dsLayout.XY): Gen6Text? = synchronized(cache) {
            val key = "${cart.absolutePath}:${cart.length()}:$language"
            cache[key] ?: runCatching {
                RomFs(cart).use { fs -> fs.read(layout.textArchive(language))?.let { Gen6Text(garcFiles(it), layout) } }
            }.getOrNull()?.also { cache[key] = it }
        }

        /** A GARC file that's an LZ11 stream ("ZO" bundles, Sun / Moon's zones), unpacked; others as they are. */
        fun unlz(f: ByteArray): ByteArray =
            if (f.isNotEmpty() && f[0].toInt() == 0x11) com.pokedaisy.app.companion.data.Gen5Lz.decompress(f) ?: ByteArray(0) else f

        /** A packed file ("WD", "EA": u16 count at +2, then count + 1 u32 offsets from +4, each from the file's start):
         * its parts. */
        fun packed(f: ByteArray): List<ByteArray> {
            if (f.size < 8) return emptyList()
            val b = le(f)
            val n = b.getShort(2).toInt() and 0xFFFF
            if (4 + 4 * (n + 1) > f.size) return emptyList()
            return (0 until n).map { i ->
                val a = b.getInt(4 + 4 * i)
                val z = b.getInt(8 + 4 * i)
                if (a < 0 || z > f.size || z < a) ByteArray(0) else f.copyOfRange(a, z)
            }
        }

        private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        /** A GARC's files (each one's first sub-file), in order. */
        fun garcFiles(blob: ByteArray): List<ByteArray> {
            val b = le(blob)
            require(String(blob, 0, 4, Charsets.US_ASCII) == "CRAG") { "not a GARC" }
            val headerSize = b.getInt(4)
            val dataOff = b.getInt(0x10)
            var o = headerSize
            require(String(blob, o, 4, Charsets.US_ASCII) == "OTAF")
            o += b.getInt(o + 4)
            require(String(blob, o, 4, Charsets.US_ASCII) == "BTAF")
            val count = b.getInt(o + 8)
            var p = o + 12
            val out = ArrayList<ByteArray>(count)
            repeat(count) {
                val bits = b.getInt(p)
                p += 4
                var first: ByteArray? = null
                for (bit in 0 until 32) if ((bits ushr bit) and 1 != 0) {
                    val start = b.getInt(p)
                    val end = b.getInt(p + 4)
                    p += 12
                    if (first == null) first = blob.copyOfRange(dataOff + start, dataOff + end)
                }
                out += first ?: ByteArray(0)
            }
            return out
        }

        /** A Gen 6 text file's lines, the game's variables (0x10, a count, its words) left out. */
        fun textLines(f: ByteArray): List<String> {
            if (f.size < 0x10) return emptyList()
            val b = le(f)
            val lineCount = b.getShort(2).toInt() and 0xFFFF
            val base = b.getInt(0x0C)
            var key = 0x7C89
            val out = ArrayList<String>(lineCount)
            for (i in 0 until lineCount) {
                val off = b.getInt(base + 4 + i * 8)
                val len = b.getShort(base + 8 + i * 8).toInt() and 0xFFFF
                val chars = IntArray(len)
                var k = key
                for (j in 0 until len) {
                    chars[j] = (b.getShort(base + off + j * 2).toInt() and 0xFFFF) xor k
                    k = ((k shl 3) or (k ushr 13)) and 0xFFFF
                }
                key = (key + 0x2983) and 0xFFFF
                val s = StringBuilder()
                var j = 0
                while (j < len) {
                    val c = chars[j]
                    if (c == 0) break
                    if (c == 0x10) { j += 2 + (chars.getOrElse(j + 1) { 0 }); continue }
                    s.append(c.toChar())
                    j++
                }
                out += s.toString()
            }
            return out
        }
    }
}

/**
 * Pokemon X / Y's zones: each zone's place name ([Gen6Text.places]), from the cart's zone records (`a/0/1/2`: one LZ11
 * "ZO" bundle a zone, and last all the zones' 0x38-byte records together; a record's +0x1C, low 9 bits, is the place).
 * Kept per cart.
 */
object Gen6Zones {
    private val cache = HashMap<String, IntArray>()

    /** Each zone's line in the place names; null when the cart can't be read that far. Omega Ruby / Alpha Sapphire keep
     * the records in their zone archive's second-to-last file, Sun / Moon in the first of theirs, compressed, 0x54 bytes
     * each with a u16 line ([N3dsLayout]). */
    fun load(cart: File, layout: N3dsLayout = N3dsLayout.XY): IntArray? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                val files = fs.read(layout.zonesPath)?.let { Gen6Text.garcFiles(it) } ?: return@use null
                val at = if (layout.zoneRecordsFile < 0) files.size + layout.zoneRecordsFile else layout.zoneRecordsFile
                val all = files.getOrNull(at)?.let(Gen6Text::unlz)?.takeIf { it.isNotEmpty() && it.size % layout.zoneRecord == 0 }
                    ?: return@use null
                val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(all.size / layout.zoneRecord) { z -> b.getShort(z * layout.zoneRecord + 0x1C).toInt() and layout.zonePlaceMask }
            }
        }.getOrNull()?.also { cache[key] = it }
    }

    /** [zone]'s place name in [text], or "" (an unknown zone, a blank line). */
    fun placeName(zones: IntArray?, text: Gen6Text?, zone: Int): String {
        val line = zones?.getOrNull(zone) ?: return ""
        return text?.places?.getOrNull(line).orEmpty()
    }
}

/** A decrypted 3DS cart image's RomFS (NCSD, the first NCCH partition, IVFC level 3): files by path. */
class RomFs(cart: File) : AutoCloseable {
    private val raf = RandomAccessFile(cart, "r")
    private val dirs: ByteArray
    private val files: ByteArray
    private val data: Long

    init {
        val h = read(0, 0x200)
        require(String(h, 0x100, 4, Charsets.US_ASCII) == "NCSD") { "not a cart image" }
        val part0 = (le(h).getInt(0x120).toLong() and 0xFFFFFFFFL) * MEDIA_UNIT
        val n = read(part0, 0x200)
        require(String(n, 0x100, 4, Charsets.US_ASCII) == "NCCH")
        val romfs = part0 + (le(n).getInt(0x1B0).toLong() and 0xFFFFFFFFL) * MEDIA_UNIT
        val iv = read(romfs, 0x60)
        require(String(iv, 0, 4, Charsets.US_ASCII) == "IVFC") { "no RomFS" }
        val master = le(iv).getInt(8).toLong()
        val blockSize = 1L shl le(iv).getInt(0x4C)
        val level3 = romfs + (0x60 + master + blockSize - 1) / blockSize * blockSize
        val l3 = le(read(level3, 0x28))
        dirs = read(level3 + l3.getInt(0x0C), l3.getInt(0x10))
        files = read(level3 + l3.getInt(0x1C), l3.getInt(0x20))
        data = level3 + l3.getInt(0x24)
    }

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun read(off: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        raf.seek(off)
        raf.readFully(b)
        return b
    }

    private fun name(meta: ByteArray, o: Int, lenOff: Int): String {
        val len = le(meta).getInt(o + lenOff)
        return String(meta, o + lenOff + 4, len, Charsets.UTF_16LE)
    }

    /** The file at [path] ("a/0/7/4"), whole; null when there's none. */
    fun read(path: String): ByteArray? {
        val (at, size) = find(path) ?: return null
        if (size > Int.MAX_VALUE) return null
        return read(at, size.toInt())
    }

    /**
     * One file of the GARC at [path] (its [index]th entry's first sub-file) without reading the rest: the archive's
     * header and tables first, then that file alone. For archives far too big to hold (Sun / Moon's encounters, 348 MB).
     */
    fun garcFile(path: String, index: Int): ByteArray? {
        val (at, size) = find(path) ?: return null
        if (size < 0x1C) return null
        val head = read(at, 0x1C)
        if (String(head, 0, 4, Charsets.US_ASCII) != "CRAG") return null
        val dataOff = le(head).getInt(0x10)
        if (dataOff <= 0 || dataOff > size || dataOff > 0x400000) return null
        val tables = read(at, dataOff)
        val b = le(tables)
        var o = b.getInt(4)
        if (String(tables, o, 4, Charsets.US_ASCII) != "OTAF") return null
        o += b.getInt(o + 4)
        if (String(tables, o, 4, Charsets.US_ASCII) != "BTAF") return null
        val count = b.getInt(o + 8)
        if (index !in 0 until count) return null
        var p = o + 12
        for (i in 0 until count) {
            val bits = b.getInt(p)
            p += 4
            var first = true
            for (bit in 0 until 32) if ((bits ushr bit) and 1 != 0) {
                if (i == index && first) {
                    val start = b.getInt(p)
                    val end = b.getInt(p + 4)
                    if (end < start || dataOff.toLong() + end > size) return null
                    return read(at + dataOff + start, end - start)
                }
                first = false
                p += 12
            }
            if (i == index) return ByteArray(0)
        }
        return null
    }

    /** [path]'s data offset in the cart and its size; null when there's none. */
    private fun find(path: String): Pair<Long, Long>? {
        val d = le(dirs)
        val f = le(files)
        var dir = 0
        val parts = path.trim('/').split('/')
        for ((i, part) in parts.withIndex()) {
            if (i == parts.lastIndex) {
                var file = d.getInt(dir + 0x0C)
                while (file != NONE) {
                    if (name(files, file, 0x1C) == part) return (data + f.getLong(file + 0x08)) to f.getLong(file + 0x10)
                    file = f.getInt(file + 0x04)
                }
                return null
            }
            var child = d.getInt(dir + 0x08)
            while (child != NONE && name(dirs, child, 0x14) != part) child = d.getInt(child + 0x04)
            if (child == NONE) return null
            dir = child
        }
        return null
    }

    override fun close() = raf.close()

    private companion object {
        const val MEDIA_UNIT = 0x200L
        const val NONE = -1
    }
}

/**
 * Pokemon X / Y's moves as the battle needs them, from the cart (RomFS `a/2/1/2`: 618 records of 36 bytes, +0 type,
 * +2 category, +3 power, +4 accuracy, +5 PP). Kept per cart.
 */
object Gen6Moves {
    class Move(val type: Int, val category: Int, val power: Int)

    /** The types by their Gen 6 numbers, as the companion names them (its colours and chart go by these). */
    val TYPES = listOf(
        "NORMAL", "FIGHTING", "FLYING", "POISON", "GROUND", "ROCK", "BUG", "GHOST", "STEEL",
        "FIRE", "WATER", "GRASS", "ELECTRIC", "PSYCHIC", "ICE", "DRAGON", "DARK", "FAIRY",
    )

    private val cache = HashMap<String, List<Move>>()

    /** Omega Ruby / Alpha Sapphire and Sun / Moon pack every move's record into one file (36 / 40 bytes each, the same
     * first fields: Tackle 50 / 40, Thunderbolt 90). */
    fun load(cart: File, layout: N3dsLayout = N3dsLayout.XY): List<Move>? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                val files = fs.read(layout.movesPath)?.let { Gen6Text.garcFiles(it) } ?: return@use null
                val records = if (layout.movesPacked) files.firstOrNull()?.let(Gen6Text::packed) ?: return@use null else files
                records.map { f ->
                    if (f.size < 6) Move(0, 0, 0) else Move(f[0].toInt() and 0xFF, f[2].toInt() and 0xFF, f[3].toInt() and 0xFF)
                }
            }
        }.getOrNull()?.also { cache[key] = it }
    }
}

/**
 * Pokemon X / Y's species types, from the cart (RomFS `a/2/1/8`, the personal records: one a species and form, +6 / +7
 * the two types by [Gen6Moves.TYPES], the same twice for one type). Kept per cart.
 */
object Gen6Personal {
    private val cache = HashMap<String, List<IntArray>>()

    fun load(cart: File, layout: N3dsLayout = N3dsLayout.XY): List<IntArray>? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                fs.read(layout.personalPath)?.let { Gen6Text.garcFiles(it) }?.map { f ->
                    if (f.size < 8) intArrayOf(0, 0) else intArrayOf(f[6].toInt() and 0xFF, f[7].toInt() and 0xFF)
                }
            }
        }.getOrNull()?.also { cache[key] = it }
    }
}

/** Gen 6's type chart (18 types, Fairy included), by [Gen6Moves.TYPES]' numbers: how hard a move's type hits. */
object Gen6Types {
    // An attacking type: what it hits for double, for half and not at all (the rest normal).
    private val RULES = mapOf(
        "NORMAL" to Triple("", "ROCK STEEL", "GHOST"),
        "FIGHTING" to Triple("NORMAL ROCK STEEL ICE DARK", "FLYING POISON BUG PSYCHIC FAIRY", "GHOST"),
        "FLYING" to Triple("FIGHTING BUG GRASS", "ROCK STEEL ELECTRIC", ""),
        "POISON" to Triple("GRASS FAIRY", "POISON GROUND ROCK GHOST", "STEEL"),
        "GROUND" to Triple("POISON ROCK STEEL FIRE ELECTRIC", "BUG GRASS", "FLYING"),
        "ROCK" to Triple("FLYING BUG FIRE ICE", "FIGHTING GROUND STEEL", ""),
        "BUG" to Triple("GRASS PSYCHIC DARK", "FIGHTING FLYING POISON GHOST STEEL FIRE FAIRY", ""),
        "GHOST" to Triple("GHOST PSYCHIC", "DARK", "NORMAL"),
        "STEEL" to Triple("ROCK ICE FAIRY", "STEEL FIRE WATER ELECTRIC", ""),
        "FIRE" to Triple("BUG STEEL GRASS ICE", "ROCK FIRE WATER DRAGON", ""),
        "WATER" to Triple("GROUND ROCK FIRE", "WATER GRASS DRAGON", ""),
        "GRASS" to Triple("GROUND ROCK WATER", "FLYING POISON BUG STEEL FIRE GRASS DRAGON", ""),
        "ELECTRIC" to Triple("FLYING WATER", "GRASS ELECTRIC DRAGON", "GROUND"),
        "PSYCHIC" to Triple("FIGHTING POISON", "STEEL PSYCHIC", "DARK"),
        "ICE" to Triple("FLYING GROUND GRASS DRAGON", "STEEL FIRE WATER ICE", ""),
        "DRAGON" to Triple("DRAGON", "STEEL", "FAIRY"),
        "DARK" to Triple("GHOST PSYCHIC", "FIGHTING DARK FAIRY", ""),
        "FAIRY" to Triple("FIGHTING DRAGON DARK", "POISON STEEL FIRE", ""),
    )

    /** [attacker][defender] in percent: 0, 50, 100 or 200. */
    private val CHART: Array<IntArray> = Array(Gen6Moves.TYPES.size) { a ->
        val (double, half, zero) = RULES.getValue(Gen6Moves.TYPES[a])
        IntArray(Gen6Moves.TYPES.size) { d ->
            val t = Gen6Moves.TYPES[d]
            when (t) {
                in double.split(' ') -> 200
                in half.split(' ') -> 50
                in zero.split(' ') -> 0
                else -> 100
            }
        }
    }

    /** Percent (0, 25 ... 400) for a move of type [atk] on a Pokemon of [def1] / [def2] (the same twice for one type);
     * 100 when a type is out of range. */
    fun multiplierPct(atk: Int, def1: Int, def2: Int): Int {
        val row = CHART.getOrNull(atk) ?: return 100
        val a = row.getOrNull(def1) ?: return 100
        if (def2 == def1) return a
        return a * (row.getOrNull(def2) ?: 100) / 100
    }
}

/**
 * The wild Pokemon per zone, from the cart.
 * - X / Y: each zone's bundle in `a/0/1/2` ("ZO", LZ11) has its encounter table as its fourth section (0x188 bytes: a
 *   0x10 header, then 94 slots of 4 bytes: u16 species (11 bits) + form (5; 30 / 31 = any), level, level), in this
 *   order: grass, yellow / purple / red flowers, tall grass (12 each), surfing and Rock Smash (5 each), the three rods
 *   (3 each), three hordes of 5. Zone 259 (Route 2): Bunnelby, Zigzagoon, Fletchling, Pidgey, Scatterbug, Weedle at 2-4.
 * - Omega Ruby / Alpha Sapphire: the same bundles in `a/0/1/3`, a 0x0E header, then 61 slots: grass, tall grass (12
 *   each), 3 the DexNav's hidden ones (left out), surfing and Rock Smash (5), the rods (3 each), three hordes of 5.
 *   Route 101: Wurmple, Zigzagoon, Poochyena at 2.
 * - Sun / Moon ([sunMoon]): a zone's world (`a/0/7/7` file 1), the world's zone -> area pairs (`a/0/9/1`), the area's
 *   tables (the encounter archive's file 11 x area + 9).
 * The rates are the games' fixed ones per slot.
 */
object Gen6Wild {
    class Slot(val species: Int, val form: Int, val minLevel: Int, val maxLevel: Int, val percent: Int)

    /** One way of meeting them ([method]: the HUD's heading, an English key `tr` translates). */
    class Table(val method: String, val slots: List<Slot>)

    private val GRASS = intArrayOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
    private val WATER = intArrayOf(50, 30, 15, 4, 1)
    private val ROD = intArrayOf(60, 35, 5)
    private val TABLES = listOf(
        "GRASS / CAVE" to GRASS, "YELLOW FLOWERS" to GRASS, "PURPLE FLOWERS" to GRASS, "RED FLOWERS" to GRASS,
        "TALL GRASS" to GRASS, "SURFING" to WATER, "ROCK SMASH" to WATER,
        "OLD ROD" to ROD, "GOOD ROD" to ROD, "SUPER ROD" to ROD,
    )
    /** Omega Ruby / Alpha Sapphire's: null = slots read past and left out (the DexNav's hidden Pokemon). */
    private val TABLES_ORAS = listOf(
        "GRASS / CAVE" to GRASS, "TALL GRASS" to GRASS, null to intArrayOf(0, 0, 0), "SURFING" to WATER, "ROCK SMASH" to WATER,
        "OLD ROD" to ROD, "GOOD ROD" to ROD, "SUPER ROD" to ROD,
    )
    private const val HORDES = 3
    private const val HORDE_SIZE = 5
    private const val SLOT = 4

    private val cache = HashMap<String, List<List<Table>>>()

    /** Each zone's tables (empty ones left out), by zone number; null when the cart can't be read that far. */
    fun load(cart: File, layout: N3dsLayout = N3dsLayout.XY): List<List<Table>>? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                if (layout.family == N3dsLayout.Family.SM) return@use sunMoon(fs)
                val files = fs.read(layout.zonesPath)?.let { Gen6Text.garcFiles(it) } ?: return@use null
                val oras = layout.family == N3dsLayout.Family.ORAS
                files.dropLast(if (oras) 2 else 1).map { f -> tables(Gen6Text.unlz(f), oras) }
            }
        }.onFailure { lastError = it }.getOrNull()?.also { cache[key] = it }
    }

    /** Why the last [load] read nothing (tests). */
    @Volatile var lastError: Throwable? = null

    /** A zone bundle's encounter section read into its tables. */
    fun tables(zo: ByteArray, oras: Boolean = false): List<Table> {
        if (zo.size < 0x18 || String(zo, 0, 2, Charsets.US_ASCII) != "ZO") return emptyList()
        val b = ByteBuffer.wrap(zo).order(ByteOrder.LITTLE_ENDIAN)
        val sections = b.getShort(2).toInt() and 0xFFFF
        if (sections < 4) return emptyList()
        val start = b.getInt(4 + 3 * 4)
        val end = b.getInt(4 + 4 * 4)
        val header = if (oras) 0x0E else 0x10
        if (end - start < header + SLOT || end > zo.size) return emptyList()
        var at = start + header
        fun slot(percent: Int): Slot? {
            if (at + SLOT > end) return null
            val raw = b.getShort(at).toInt() and 0xFFFF
            val lo = zo[at + 2].toInt() and 0xFF
            val hi = zo[at + 3].toInt() and 0xFF
            at += SLOT
            val species = raw and 0x7FF
            return if (species == 0) null else Slot(species, raw ushr 11, minOf(lo, hi), maxOf(lo, hi), percent)
        }
        val out = ArrayList<Table>()
        for ((method, rates) in if (oras) TABLES_ORAS else TABLES) {
            val slots = rates.map { slot(it) }.filterNotNull()
            if (method != null && slots.isNotEmpty()) out += Table(method, slots)
        }
        // A horde is 5 Pokemon met together; its 60 / 35 / 5 is the horde's (as the rods'), each of its five carries it.
        val hordes = (0 until HORDES).flatMap { h -> (0 until HORDE_SIZE).mapNotNull { slot(ROD[h]) } }
        if (hordes.isNotEmpty()) out += Table("HORDES", hordes)
        return out
    }

    // ---- Sun / Moon ------------------------------------------------------------------------------------------------

    /** Sun's encounter archive and Moon's (each cart has one, the other path empty). */
    private val SM_ENCOUNTERS = listOf("a/0/8/2", "a/0/8/3")
    private const val SM_TABLE = 0x164
    private const val SM_SET = 4 + 2 * SM_TABLE

    /**
     * Sun / Moon, every zone: an area holds several zones' tables (which patch is which zone's is in the map, not read),
     * so a zone lists its whole area's: per way of meeting them (a table set's +0 kind: 4 surfing, 6 fishing, the rest
     * grass, caves, sand, flowers), each species once at its best chance in a table and its levels' range, by day; ones
     * met only at night under AT NIGHT. A day table: min level, max level, 10 rates, then 8 sets of 10 u32 slots
     * (species 11 bits, form 5; set 0 the wild table, the rest SOS calls), 6 weather slots; the night one follows.
     * Route 1 (zone 0) by day: Yungoos, Pikipek, Caterpie, Ledyba; at night Alolan Rattata, Spinarak.
     */
    private fun sunMoon(fs: RomFs): List<List<Table>>? {
        val zoneFiles = fs.read("a/0/7/7")?.let { Gen6Text.garcFiles(it) } ?: return null
        val zoneCount = Gen6Text.unlz(zoneFiles.getOrNull(0) ?: return null).size / N3dsLayout.SM.zoneRecord
        // Each area's zones, in the order the world lists them (the order of the area's per-zone event lists).
        val areaOf = HashMap<Int, Int>()
        val zonesOf = HashMap<Int, MutableList<Int>>()
        fs.read("a/0/9/1")?.let { Gen6Text.garcFiles(it) }?.forEach { f ->
            val e = Gen6Text.packed(Gen6Text.unlz(f)).firstOrNull() ?: return@forEach
            if (e.size < 16) return@forEach
            val b = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
            var k = b.getInt(8)
            val end = minOf(b.getInt(12), e.size)
            while (k + 4 <= end) {
                val zone = b.getShort(k).toInt() and 0xFFFF
                val area = b.getShort(k + 2).toInt() and 0xFFFF
                areaOf[zone] = area
                zonesOf.getOrPut(area) { ArrayList() }.add(zone)
                k += 4
            }
        }
        // Sun's archive or Moon's (the other path is an empty file): one area's file at a time, never the whole archive.
        val enc = SM_ENCOUNTERS.firstOrNull { p -> fs.garcFile(p, 9) != null } ?: return null
        val byZone = HashMap<Int, List<Table>>()
        val done = HashSet<Int>()
        return (0 until zoneCount).map { z ->
            val area = areaOf[z] ?: return@map emptyList()
            if (done.add(area)) {
                val sets = Gen6Text.packed(Gen6Text.unlz(fs.garcFile(enc, 11 * area + 9) ?: ByteArray(0)))
                val events = Gen6Text.packed(Gen6Text.unlz(fs.garcFile(enc, 11 * area) ?: ByteArray(0)))
                val zones = zonesOf[area].orEmpty()
                val picks = smZoneTables(sets, events, zones.size)
                zones.forEachIndexed { i, zone -> byZone[zone] = smArea(sets, picks[i]) }
            }
            byZone[z].orEmpty()
        }
    }

    /**
     * Which of an area's encounter tables ([sets]) each of its [zoneCount] zones has, from the area's event lists
     * ([events], each a pack of one list a zone, in the world's zone order): list 6 places the grass / water patches,
     * each naming its table (type 6, the table at +4); fishing tables (kind 6) go to the zones with fishing spots (lists
     * 12 / 13), and the tables no patch names (berry piles, rustling spots) to the zones with list 5's other points.
     * Checked on Sun's areas: Kala'e Bay gets the Bagon / Slowpoke table and surfing, Route 3 the Cutiefly / Delibird
     * ones; Hau'oli Outskirts the Slowpoke / Wingull, Route 1 its three level ranges, Melemele Sea the surfing.
     */
    private fun smZoneTables(sets: List<ByteArray>, events: List<ByteArray>, zoneCount: Int): List<Set<Int>> {
        if (zoneCount <= 1) return List(zoneCount) { sets.indices.toSet() }
        fun perZone(list: Int): List<ByteArray> = events.getOrNull(list)?.let(Gen6Text::packed).orEmpty()
        fun count(z: ByteArray) = if (z.size < 4) 0 else ByteBuffer.wrap(z).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        val patches = perZone(6)
        val named = List(zoneCount) { HashSet<Int>() }
        for (zi in 0 until zoneCount) {
            val z = patches.getOrNull(zi) ?: continue
            val b = ByteBuffer.wrap(z).order(ByteOrder.LITTLE_ENDIAN)
            val n = count(z)
            for (r in 0 until n) {
                val o = 4 + r * 28
                if (o + 8 > z.size) break
                val table = b.getInt(o + 4)
                if (b.getInt(o) == 6 && table in sets.indices) named[zi] += table
            }
        }
        if (named.all { it.isEmpty() }) return List(zoneCount) { sets.indices.toSet() }
        val fishing = sets.indices.filter { sets[it].size >= 4 && ByteBuffer.wrap(sets[it]).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 6 }
        val rest = sets.indices.filter { i -> named.none { i in it } && i !in fishing }
        val fishSpots = (0 until zoneCount).filter { zi -> count(perZone(12).getOrNull(zi) ?: ByteArray(0)) > 0 || count(perZone(13).getOrNull(zi) ?: ByteArray(0)) > 0 }
        val points = (0 until zoneCount).filter { zi -> count(perZone(5).getOrNull(zi) ?: ByteArray(0)) > 0 }
        return (0 until zoneCount).map { zi ->
            named[zi] + (if (fishSpots.isEmpty() || zi in fishSpots) fishing.filter { named.none { s -> it in s } } else emptyList()) +
                (if (points.isEmpty() || zi in points) rest else emptyList())
        }
    }

    private class Met(var percent: Int, var min: Int, var max: Int, val form: Int)

    private fun smArea(sets: List<ByteArray>, only: Set<Int>): List<Table> {
        val day = LinkedHashMap<String, LinkedHashMap<Int, Met>>()
        val night = LinkedHashMap<Int, Met>()
        for ((i, e) in sets.withIndex()) {
            if (i !in only) continue
            if (e.size < SM_SET) continue
            val b = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
            val method = when (b.getInt(0)) {
                4 -> "SURFING"
                6 -> "FISHING"
                else -> "GRASS / CAVE"
            }
            for (t in 0 until 2) {
                val o = 4 + t * SM_TABLE
                val lo = e[o].toInt() and 0xFF
                val hi = e[o + 1].toInt() and 0xFF
                val chance = HashMap<Int, Int>()
                val forms = HashMap<Int, Int>()
                for (k in 0 until 10) {
                    val v = b.getInt(o + 12 + k * 4)
                    val species = v and 0x7FF
                    if (species == 0) continue
                    chance[species] = (chance[species] ?: 0) + (e[o + 2 + k].toInt() and 0xFF)
                    forms.putIfAbsent(species, (v ushr 11) and 0x1F)
                }
                val into = if (t == 0) day.getOrPut(method) { LinkedHashMap() } else night
                for ((species, pct) in chance) {
                    val m = into.getOrPut(species) { Met(0, lo, hi, forms[species] ?: 0) }
                    m.percent = maxOf(m.percent, pct)
                    m.min = minOf(m.min, minOf(lo, hi))
                    m.max = maxOf(m.max, maxOf(lo, hi))
                }
            }
        }
        val seenByDay = day.values.flatMap { it.keys }.toSet()
        val out = day.map { (method, mets) -> Table(method, mets.map { (s, m) -> Slot(s, m.form, m.min, m.max, m.percent) }) }.toMutableList()
        val nightOnly = night.filterKeys { it !in seenByDay }
        if (nightOnly.isNotEmpty()) out += Table("AT NIGHT", nightOnly.map { (s, m) -> Slot(s, m.form, m.min, m.max, m.percent) })
        return out.filter { it.slots.isNotEmpty() }
    }
}
