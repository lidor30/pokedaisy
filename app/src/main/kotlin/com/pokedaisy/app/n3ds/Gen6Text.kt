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
class Gen6Text private constructor(private val files: List<ByteArray>) {
    val species: List<String> by lazy { lines(SPECIES) }
    val moves: List<String> by lazy { lines(MOVES) }
    val items: List<String> by lazy { lines(ITEMS) }
    val abilities: List<String> by lazy { lines(ABILITIES) }
    val natures: List<String> by lazy { lines(NATURES) }
    val types: List<String> by lazy { lines(TYPES) }
    val places: List<String> by lazy { lines(PLACES) }

    fun lines(file: Int): List<String> = files.getOrNull(file)?.let(::textLines).orEmpty()

    companion object {
        const val SPECIES = 80
        const val MOVES = 13
        const val ITEMS = 96
        const val ABILITIES = 34
        const val NATURES = 47
        const val TYPES = 17
        const val PLACES = 72

        /** The save's language (MyStatus +0x2D: 1 Japanese, 2 English, 3 French, 4 Italian, 5 German, 7 Spanish,
         * 8 Korean) to its text archive in Pokemon X / Y's RomFS. Japanese is the kanji one. */
        fun archiveFor(language: Int): String = "a/0/7/" + when (language) {
            1 -> 3
            3 -> 5
            4 -> 6
            5 -> 7
            7 -> 8
            8 -> 9
            else -> 4
        }

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
        fun load(cart: File, language: Int): Gen6Text? = synchronized(cache) {
            val key = "${cart.absolutePath}:${cart.length()}:$language"
            cache[key] ?: runCatching {
                RomFs(cart).use { fs -> fs.read(archiveFor(language))?.let { Gen6Text(garcFiles(it)) } }
            }.getOrNull()?.also { cache[key] = it }
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
    private const val RECORD = 0x38
    private val cache = HashMap<String, IntArray>()

    /** Each zone's line in the place names; null when the cart can't be read that far. */
    fun load(cart: File): IntArray? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                val files = fs.read("a/0/1/2")?.let { Gen6Text.garcFiles(it) } ?: return@use null
                val all = files.lastOrNull()?.takeIf { it.isNotEmpty() && it.size % RECORD == 0 } ?: return@use null
                val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(all.size / RECORD) { z -> b.getShort(z * RECORD + 0x1C).toInt() and 0x1FF }
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
        val d = le(dirs)
        val f = le(files)
        var dir = 0
        val parts = path.trim('/').split('/')
        for ((i, part) in parts.withIndex()) {
            if (i == parts.lastIndex) {
                var file = d.getInt(dir + 0x0C)
                while (file != NONE) {
                    if (name(files, file, 0x1C) == part) {
                        val size = f.getLong(file + 0x10)
                        if (size > Int.MAX_VALUE) return null
                        return read(data + f.getLong(file + 0x08), size.toInt())
                    }
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

    fun load(cart: File): List<Move>? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                fs.read("a/2/1/2")?.let { Gen6Text.garcFiles(it) }?.map { f ->
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

    fun load(cart: File): List<IntArray>? = synchronized(cache) {
        val key = "${cart.absolutePath}:${cart.length()}"
        cache[key] ?: runCatching {
            RomFs(cart).use { fs ->
                fs.read("a/2/1/8")?.let { Gen6Text.garcFiles(it) }?.map { f ->
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
