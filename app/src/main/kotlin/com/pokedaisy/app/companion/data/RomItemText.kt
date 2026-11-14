package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/**
 * Where a game keeps its item descriptions: the pointer to item `id`'s text sits at
 * [base] + (id - [first]) * [stride] + [descOff], for ids from [first] (and fewer than
 * [count] of them). [descOff] can be negative: the expansion generators anchor [base] on
 * each record's name, and gItemsInfo keeps the description 8 bytes before it. [idOff] is
 * the record's u16 itemId where it has one (vanilla struct Item), so the table can be
 * checked against the ROM; -1 = none. [next] covers a second range of ids (Emerald
 * Rogue's own items, in gRogueItems).
 */
data class ItemDescTable(
    val base: Long,
    val stride: Int,
    val descOff: Int,
    val idOff: Int = -1,
    val first: Int = 0,
    val count: Int = Int.MAX_VALUE,
    val next: ItemDescTable? = null,
)

/** Vanilla struct Item (FireRed / LeafGreen / Emerald / Ruby / Sapphire and the binary hacks
 * on them): 44 bytes, name[14], itemId +0x0E, description pointer +0x14. [base] = gItems[0];
 * [count] = ITEMS_COUNT where ids past it would read past the table (Ruby / Sapphire: 349). */
fun vanillaItems(base: Long, count: Int = Int.MAX_VALUE) = ItemDescTable(base, 44, 0x14, idOff = 0x0E, count = count)

/** The Japanese releases' struct Item: 10-byte names, 40-byte records. */
fun japaneseItems(base: Long, count: Int = Int.MAX_VALUE) = ItemDescTable(base, 40, 0x10, idOff = 0x0A, count = count)

/** Ruby / Sapphire's ITEMS_COUNT: the companion uses Emerald's ids, which go further. */
const val RUBY_SAPPHIRE_ITEMS = 349

/**
 * Item descriptions, read from the player's own ROM - the games' text isn't bundled
 * (names are; descriptions are prose). The Poller picks the running game's table
 * ([NativeConfig.itemDescs], or [findVanillaItems] where that table isn't in the ROM)
 * and reads through [PokedexSource.reader], like the dex's
 * flavour text. Line breaks become spaces (the bag rewraps), each text is read once.
 */
object RomItemText {
    @Volatile
    var table: ItemDescTable? = null
        private set

    private val cache = LockedMap<Int, String>()

    /** The running game's table (null = none: "" for every item). Clears what an earlier ROM left. */
    fun use(t: ItemDescTable?) {
        table = t
        cache.clear()
    }

    /** Item [id]'s description as the game's bag shows it, or "" (no table, no ROM, no text). */
    fun description(id: Int): String {
        val t = table ?: return ""
        if (id <= 0) return ""
        cache[id]?.let { return it }
        // A failed read (no core yet) isn't cached: the next sample tries again.
        val text = runCatching { read(t, id) }.getOrNull() ?: return ""
        cache[id] = text
        return text
    }

    private fun read(t: ItemDescTable, id: Int): String {
        var seg: ItemDescTable? = t
        while (seg != null && (id < seg.first || id - seg.first >= seg.count)) seg = seg.next
        if (seg == null) return ""
        val reader = PokedexSource.reader
        val at = seg.base + (id - seg.first).toLong() * seg.stride + seg.descOff
        val ptr = Gfx.u32(reader.readCoreMemory(at, 4), 0)
        if (!Gfx.inRom(ptr)) return ""
        // The text's terminator is within a few lines; a ROM's last bytes can't be over-read.
        val raw = runCatching { reader.readCoreMemory(ptr, MAX_TEXT) }.getOrNull()
            ?: reader.readCoreMemory(ptr, 64)
        return clean(Gen3Text.decode(raw))
    }

    /** One line, as the bag rewraps it: runs of spaces (line breaks) collapsed, the
     * apostrophe straight like the bundled names'. Japanese keeps its full-width spaces. */
    internal fun clean(s: String): String {
        val text = s.replace('’', '\'').replace(Gen3Text.UNKNOWN.toString(), "")
        return if (romLanguage == 'J') text.trim() else text.trim().split(WHITESPACE).joinToString(" ")
    }

    /**
     * Finds a vanilla gItems by its shape - item ids 1, 2, 3 ... at the same offset in
     * consecutive records, the description a ROM pointer - for a build whose table isn't
     * at a known address. Scans the ROM once; null if
     * nothing matches.
     */
    fun findVanillaItems(reader: MemoryReader, romSize: Long): ItemDescTable? {
        val chunk = 1 shl 20
        val back = 64 // item 0's record, before item 1's id
        val ahead = 44 * 10
        var off = 0L
        while (off < romSize) {
            val start = maxOf(0L, off - back)
            val len = minOf(off + chunk + ahead, romSize).minus(start).toInt()
            val b = runCatching { reader.readCoreMemory(ROM_BASE + start, len) }.getOrNull() ?: return null
            // A record starts 4-aligned, so its itemId (at +14 or +10) sits at 2 mod 4.
            var p = (off - start).toInt() + 2
            val end = minOf((off + chunk - start).toInt(), len - 2)
            while (p <= end) {
                if (b[p].toInt() == 1 && b[p + 1].toInt() == 0) {
                    for ((stride, idOff, make) in SHAPES) {
                        if (p - idOff - stride < 0 || p + stride * 8 + 2 > len) continue
                        if ((1 until 8).all { k -> u16le(b, p + stride * k) == k + 1 }) {
                            val t = make(ROM_BASE + start + p - idOff - stride)
                            if (Gfx.inRom(Gfx.u32(b, p - idOff + t.descOff))) return t
                        }
                    }
                }
                p += 4
            }
            off += chunk
        }
        return null
    }

    /** True if [t] is a vanilla-shaped table whose records really are items 1..8 here. */
    fun matchesRom(reader: MemoryReader, t: ItemDescTable): Boolean {
        if (t.idOff < 0) return true
        return runCatching {
            val b = reader.readCoreMemory(t.base, t.stride * 9)
            (1..8).all { k -> u16le(b, t.stride * k + t.idOff) == k }
        }.getOrDefault(false)
    }

    private const val ROM_BASE = 0x08000000L
    private const val MAX_TEXT = 256
    private val WHITESPACE = Regex("\\s+")
    private val SHAPES: List<Triple<Int, Int, (Long) -> ItemDescTable>> =
        listOf(Triple(44, 0x0E) { b: Long -> vanillaItems(b) }, Triple(40, 0x0A) { b: Long -> japaneseItems(b) })
}
