package com.pokedaisy.app.companion.data

/**
 * A Game Boy (Gen 1) game's POKéDEX, read from the cart's own bytes ([rom] - a GB cart
 * is bank-switched, so the bus can't reach most of it): the page (PokedexEntryPointers:
 * category, feet / inches, pounds x 10, the text), base stats and types (BaseStats), and
 * the front sprite - Gen 1's own compression (home/uncompress.asm), coloured with the
 * mon's GBC palette. Addresses are pret/pokeyellow's symbols ([Gen1DexTables]); the
 * seen / owned flags are WRAM bit arrays by Dex number ([readGen1PokedexState]).
 */
data class Gen1DexTables(
    val baseStats: Pair<Int, Int>,          // BaseStats: 28 bytes per Dex number
    val dexEntryPointers: Pair<Int, Int>,   // by internal index - 1, in the same bank as the entries
    val monsterPalettes: Pair<Int, Int>,    // a palette id per Dex number (0 = MISSINGNO.)
    val cgbPalettes: Pair<Int, Int>,        // CGBBasePalettes: 4 RGB555 colours per id
    /** The first of the "Pics" banks, and the internal indexes where the next one starts (UncompressMonSprite). */
    val picsBank: Int,
    val picsBankBreaks: IntArray,
    val fossilKabutops: Int,
    val fossilKabutopsBank: Int,
    val internalToDex: IntArray,
    /** wPokedexOwned / wPokedexSeen: 19 bytes each, bit n-1 = Dex No. n. */
    val owned: Long,
    val seen: Long,
    /** The European carts keep the page's height in decimetres (1 byte) and weight in hectograms (2), English's
     * feet / inches and pounds x 10 (4 bytes), and print with their own characters. */
    val metric: Boolean = false,
    val charset: Gen1Charset = Gen1Charset.EN,
    /** Red / Blue keep MEW apart (added last, in bank 1): its base stats ([BaseStats] holds 150) and its pics' bank. */
    val mewBaseStats: Pair<Int, Int>? = null,
    val mewPicsBank: Int = 0,
    /**
     * PureRGB's shapes: 35-byte base data whose front pic's bank is its own byte ([picBankOffset], BASE_PICBANK), and
     * pages that are fixed 8-byte records by Dex number ([dexEntryData]: feet, inches, pounds x 10, a text_far) with the
     * category apart - [categoryMapping] gives each Dex number the index of its string in [categoryStrings] (@-ended).
     */
    val baseStatsSize: Int = 28,
    val picBankOffset: Int = -1,
    val dexEntryData: Pair<Int, Int>? = null,
    val categoryMapping: Pair<Int, Int>? = null,
    val categoryStrings: Pair<Int, Int>? = null,
    /** The text command that points at the page's text: TX_FAR 0x17 (PureRGB renumbered its commands: TX_FAR_END 0x15). */
    val textFar: Int = 0x17,
)

val GEN1_DEX_YELLOW = Gen1DexTables(
    baseStats = 0x0E to 0x43DE,
    dexEntryPointers = 0x10 to 0x450B,
    monsterPalettes = 0x1C to 0x6921,
    cgbPalettes = 0x1C to 0x6AF9,
    picsBank = 0x09,
    picsBankBreaks = intArrayOf(0x1F, 0x4A, 0x74, 0x99),   // TANGELA + 1, MOLTRES + 1, BEEDRILL + 2, STARMIE + 1
    fossilKabutops = 0xB6,
    fossilKabutopsBank = 0x0B,
    internalToDex = gen1InternalToDexYellow,
    owned = 0xD2F6L,
    seen = 0xD309L,
)

/**
 * Red and Blue (pret/pokered: every address the same in both builds): their own dex pages (Yellow rewrote the text),
 * palettes - Super Game Boy ones, as the game shows them on one (mGBA runs a DMG cart with SGB support as one) - and
 * MEW apart in bank 1 ([Gen1DexTables.mewBaseStats]).
 */
private val GEN1_DEX_RED = GEN1_DEX_YELLOW.copy(
    dexEntryPointers = 0x10 to 0x447E,
    monsterPalettes = 0x1C to 0x65C8,
    cgbPalettes = 0x1C to 0x6660,   // SuperPalettes: the same 4 RGB colours per id as CGBBasePalettes
    owned = 0xD2F7L,
    seen = 0xD30AL,
    mewBaseStats = 0x01 to 0x425B,
    mewPicsBank = 0x01,
)

/**
 * PureRed / PureBlue v2.7.6 (Vortyne/pureRGB's pokered.sym; every address the same in both builds): its own base data
 * (MEW in the main table), pages and Super Game Boy palettes (the colours its SGB and COLORS > SGB1 modes give each
 * Pokémon; on a Color it shows them two-tone by default). Seen / owned keep Red's bit order, in WRAM bank 1.
 */
private val GEN1_DEX_PURE_RED = GEN1_DEX_YELLOW.copy(
    baseStats = 0x2C to 0x6445,
    dexEntryPointers = 0 to 0,
    monsterPalettes = 0x1C to 0x6435,
    cgbPalettes = 0x1C to 0x6565,   // SuperPalettes
    internalToDex = gen1InternalToDexPureRgb,
    owned = wramx(1, 0xD2FF),
    seen = wramx(1, 0xD312),
    charset = Gen1Charset.PURE,
    baseStatsSize = 35,
    picBankOffset = 27,
    dexEntryData = 0x10 to 0x4859,
    categoryMapping = 0x10 to 0x4D2F,
    categoryStrings = 0x10 to 0x4DC6,
    textFar = 0x15,   // dex_entry's text_far_end
)

/** Yellow Legacy v1.0.10 (its pokeyellow.sym): its own base data, pages and palettes. */
private val GEN1_DEX_YELLOW_LEGACY = GEN1_DEX_YELLOW.copy(
    baseStats = 0x3B to 0x5272,
    dexEntryPointers = 0x10 to 0x46D3,
    monsterPalettes = 0x1C to 0x68B5,
    cgbPalettes = 0x1C to 0x6A8D,   // GBCBasePalettes
)

/** The European Yellows: English's tables but for the dex pages' text and palettes (port_gen1.py, checked by contents). */
private val GEN1_DEX_YELLOW_DE = GEN1_DEX_YELLOW.copy(dexEntryPointers = 0x10 to 0x4507, monsterPalettes = 0x1C to 0x6900,
    cgbPalettes = 0x1C to 0x6AD8, owned = 0xD2FBL, seen = 0xD30EL, metric = true, charset = Gen1Charset.DF)
private val GEN1_DEX_YELLOW_FR = GEN1_DEX_YELLOW_DE.copy(dexEntryPointers = 0x10 to 0x4508, monsterPalettes = 0x1C to 0x68F2,
    cgbPalettes = 0x1C to 0x6ACA)
private val GEN1_DEX_YELLOW_IT = GEN1_DEX_YELLOW_DE.copy(dexEntryPointers = 0x10 to 0x450D, monsterPalettes = 0x1C to 0x6961,
    cgbPalettes = 0x1C to 0x6B39, charset = Gen1Charset.IS)
private val GEN1_DEX_YELLOW_ES = GEN1_DEX_YELLOW_IT.copy(monsterPalettes = 0x1C to 0x6911, cgbPalettes = 0x1C to 0x6AE9)

/** The Kanto dex: 151, no regional / national split. Its pages come from [Gen1Dex]. */
val POKEDEX_YELLOW = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    speciesCount = 152, nationalCount = 151, regionalCount = 151, regionName = null,
    gen1 = GEN1_DEX_YELLOW,
)
/** The European pages print metric, "0.4m" / "6.0kg" (a full stop, no space: headless shots of each cart's page). */
private val POKEDEX_YELLOW_METRIC = POKEDEX_YELLOW.copy(metric = true, decimalPoint = '.', unitSpace = false)
val POKEDEX_YELLOW_DE = POKEDEX_YELLOW_METRIC.copy(gen1 = GEN1_DEX_YELLOW_DE)
val POKEDEX_YELLOW_FR = POKEDEX_YELLOW_METRIC.copy(gen1 = GEN1_DEX_YELLOW_FR)
val POKEDEX_YELLOW_IT = POKEDEX_YELLOW_METRIC.copy(gen1 = GEN1_DEX_YELLOW_IT)
val POKEDEX_YELLOW_ES = POKEDEX_YELLOW_METRIC.copy(gen1 = GEN1_DEX_YELLOW_ES)
/** Red's and Blue's: one set of tables over each cart's own bytes (two objects, so a dex cache never crosses them). */
val POKEDEX_RED = POKEDEX_YELLOW.copy(gen1 = GEN1_DEX_RED)
val POKEDEX_BLUE = POKEDEX_YELLOW.copy(gen1 = GEN1_DEX_RED.copy())
/** Yellow Legacy's. */
val POKEDEX_YELLOW_LEGACY = POKEDEX_YELLOW.copy(gen1 = GEN1_DEX_YELLOW_LEGACY)
/** PureRed's and PureBlue's, likewise. */
val POKEDEX_PURE_RED = POKEDEX_YELLOW.copy(gen1 = GEN1_DEX_PURE_RED)
val POKEDEX_PURE_BLUE = POKEDEX_YELLOW.copy(gen1 = GEN1_DEX_PURE_RED.copy())

fun readGen1PokedexState(r: MemoryReader, t: Gen1DexTables, tables: PokedexTables): PokedexState {
    val owned = r.readCoreMemory(t.owned, 19)
    val seen = r.readCoreMemory(t.seen, 19)
    fun bits(b: ByteArray) = (1..151).filter { n -> (b[(n - 1) / 8].toInt() shr ((n - 1) % 8)) and 1 != 0 }.toSet()
    val caught = bits(owned)
    return PokedexState(tables, bits(seen) + caught, caught, national = true)
}

object Gen1Dex {
    /** The running cart's bytes (PokeDaisyActivity sets it for a Game Boy ROM; tests from the file). */
    @Volatile
    var rom: ByteArray? = null

    private fun off(bank: Int, addr: Int) = if (bank == 0) addr else bank * 0x4000 + (addr - 0x4000)
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    /** Gen 1 text from [o] until its end (@, or the dex text's DEXEND 0x5F). */
    private fun text(rom: ByteArray, o: Int, chars: Map<Int, String>): String {
        val sb = StringBuilder()
        var i = o
        while (i < rom.size && i < o + 400) {
            val c = u8(rom, i++)
            if (c == 0x5F) { sb.append('.'); break }   // DEXEND prints the entry's last full stop
            if (c == 0x50) break
            if (c == 0x00 && sb.isEmpty()) continue   // the text command that opens a far text
            sb.append(chars[c] ?: "")
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private const val MEW = 151

    /** [dex]'s 28 bytes of base stats: BaseStats by Dex number, MEW's apart in Red / Blue. */
    private fun baseStatsOff(t: Gen1DexTables, dex: Int): Int =
        t.mewBaseStats?.takeIf { dex == MEW }?.let { off(it.first, it.second) }
            ?: (off(t.baseStats.first, t.baseStats.second) + t.baseStatsSize * (dex - 1))

    private fun internalOf(t: Gen1DexTables, dex: Int): Int = t.internalToDex.indexOfFirst { it == dex }

    fun entry(t: Gen1DexTables, dex: Int): DexEntry? {
        val rom = rom ?: return null
        if (dex !in 1..151) return null
        val chars = t.charset.chars
        val category: String
        var o: Int
        if (t.dexEntryData != null) {
            // PureRGB: the record by Dex number, the category by its index among DexCategoryStrings.
            val (cb, ca) = t.categoryMapping ?: return null
            var s = off(t.categoryStrings!!.first, t.categoryStrings.second)
            repeat(u8(rom, off(cb, ca) + dex - 1)) { while (u8(rom, s) != 0x50) s++; s++ }
            category = text(rom, s, chars)
            o = off(t.dexEntryData.first, t.dexEntryData.second) + 8 * (dex - 1)
        } else {
            val internal = internalOf(t, dex).takeIf { it > 0 } ?: return null
            val (pb, pa) = t.dexEntryPointers
            val e = off(pb, u16(rom, off(pb, pa) + 2 * (internal - 1)))
            o = e
            while (u8(rom, o) != 0x50) o++
            category = text(rom, e, chars)
            o++
        }
        // English: feet, inches, pounds x 10; Europe: decimetres, hectograms (one byte less).
        val feet = u8(rom, o)
        val inches = u8(rom, o + 1)
        val lbs10 = u16(rom, o + 2)
        val dm = u8(rom, o)
        val hg = u16(rom, o + 1)
        val far = o + if (t.metric) 3 else 4
        // text_far: 0x17, address, bank.
        val desc = if (u8(rom, far) == t.textFar) text(rom, off(u8(rom, far + 3), u16(rom, far + 1)), chars) else ""
        val s = baseStatsOff(t, dex)
        return DexEntry(
            national = dex,
            species = dex,
            category = category,
            heightDm = if (t.metric) dm else Math.round((feet * 12 + inches) * 2.54 / 10).toInt(),
            weightHg = if (t.metric) hg else Math.round(lbs10 * 0.45359237).toInt(),
            description = desc,
            type1 = u8(rom, s + 6),
            type2 = u8(rom, s + 7),
            // HP, ATTACK, DEFENSE, SPEED, SPECIAL: Gen 1 has one SPECIAL, its own order.
            baseStats = listOf(u8(rom, s + 1), u8(rom, s + 2), u8(rom, s + 3), u8(rom, s + 4), u8(rom, s + 5)),
            abilities = emptyList(),
            catchRate = u8(rom, s + 8),
            genderRatio = -1,
            eggGroups = emptyList(),
            heightIn = if (t.metric) null else feet * 12 + inches,
            weightLbs10 = if (t.metric) null else lbs10,
        )
    }

    /**
     * The 56x56 front sprite as ARGB pixels (colour 0 clear), placed in its 7x7-tile box
     * the way LoadUncompressedSpriteData centres it: horizontally (rounded up), bottom-aligned.
     */
    fun frontPixels(t: Gen1DexTables, dex: Int): IntArray? {
        val rom = rom ?: return null
        if (dex !in 1..151) return null
        val s = baseStatsOff(t, dex)
        val ptr = u16(rom, s + 11)
        val bank = if (t.picBankOffset >= 0) u8(rom, s + t.picBankOffset) else {
            val internal = internalOf(t, dex).takeIf { it > 0 } ?: return null
            if (internal == t.fossilKabutops) t.fossilKabutopsBank
            else if (dex == MEW && t.mewBaseStats != null) t.mewPicsBank
            else t.picsBank + t.picsBankBreaks.count { internal >= it }
        }
        val pic = Gen1Pic.decompress(rom, off(bank, ptr)) ?: return null
        val palId = u8(rom, off(t.monsterPalettes.first, t.monsterPalettes.second) + dex)
        val pal = off(t.cgbPalettes.first, t.cgbPalettes.second) + 8 * palId
        val colors = IntArray(4) { i ->
            val c = u16(rom, pal + 2 * i)
            fun ch(v: Int) = (v and 31) * 255 / 31
            if (i == 0) 0 else (0xFF shl 24) or (ch(c) shl 16) or (ch(c shr 5) shl 8) or ch(c shr 10)
        }
        val out = IntArray(56 * 56)
        val ox = ((8 - pic.widthTiles) / 2) * 8
        val oy = (7 - pic.heightTiles) * 8
        for (y in 0 until pic.heightTiles * 8) for (x in 0 until pic.widthTiles * 8) {
            val v = pic.pixels[y * pic.widthTiles * 8 + x]
            val px = ox + x
            val py = oy + y
            if (px in 0 until 56 && py in 0 until 56) out[py * 56 + px] = colors[v]
        }
        return out
    }
}

/** Gen 1's sprite compression (home/uncompress.asm): two 1bpp chunks, RLE-packed bit pairs, delta-coded. */
object Gen1Pic {
    class Pic(val widthTiles: Int, val heightTiles: Int, val pixels: IntArray)

    private val DECODE0 = intArrayOf(0x01, 0x32, 0x76, 0x45, 0xFE, 0xCD, 0x89, 0xBA)
    private val DECODE1 = intArrayOf(0xFE, 0xCD, 0x89, 0xBA, 0x01, 0x32, 0x76, 0x45)

    fun decompress(rom: ByteArray, start: Int): Pic? = runCatching {
        var pos = start
        var curByte = 0
        var bitsLeft = 0
        fun bit(): Int {
            if (bitsLeft == 0) { curByte = rom[pos++].toInt() and 0xFF; bitsLeft = 8 }
            bitsLeft--
            return (curByte shr bitsLeft) and 1
        }
        val dims = rom[pos++].toInt() and 0xFF
        val hT = dims and 0xF
        val wT = dims shr 4
        if (wT == 0 || hT == 0 || wT > 8 || hT > 8) return null
        val height = hT * 8
        val size = wT * height                      // bytes per buffer, column-major (8 px per byte)
        val buf = arrayOf(ByteArray(size), ByteArray(size))
        val firstInBuffer2 = bit() == 1
        var mode = 0

        fun chunk(b: ByteArray) {
            var col = 0
            var y = 0
            var shift = 6                            // bit offset 3: bits 7-6 first
            var done = false
            fun put(pair: Int) {
                b[col * height + y] = (b[col * height + y].toInt() or (pair shl shift)).toByte()
                if (++y == height) {
                    y = 0
                    if (shift > 0) shift -= 2 else { shift = 6; if (++col == wT) done = true }
                }
            }
            var rle = bit() == 0
            while (!done) {
                if (rle) {
                    var c = 0
                    while (bit() == 1) c++
                    var v = 0
                    repeat(c + 1) { v = (v shl 1) or bit() }
                    val n = v + (1 shl (c + 1)) - 1
                    repeat(n) { if (!done) put(0) }
                    rle = false
                } else {
                    val pair = (bit() shl 1) or bit()
                    if (pair == 0) rle = true else put(pair)
                }
            }
        }
        // The game's Y-major delta decode: each row left to right, a 1 bit toggles the value.
        fun delta(b: ByteArray) {
            for (y in 0 until height) {
                var last = 0
                for (col in 0 until wT) {
                    val v = b[col * height + y].toInt() and 0xFF
                    fun nyb(n: Int): Int {
                        val t = if (last and 1 == 1) DECODE1 else DECODE0
                        val e = t[n shr 1]
                        val r = if (n and 1 == 0) e shr 4 else e and 0xF
                        last = r
                        return r
                    }
                    val hi = nyb(v shr 4)
                    val lo = nyb(v and 0xF)
                    b[col * height + y] = ((hi shl 4) or lo).toByte()
                }
            }
        }
        val first = if (firstInBuffer2) buf[1] else buf[0]
        val second = if (firstInBuffer2) buf[0] else buf[1]
        chunk(first)
        mode = if (bit() == 0) 0 else 1 + bit()
        chunk(second)
        when (mode) {
            0 -> { delta(buf[0]); delta(buf[1]) }
            1 -> { delta(first); for (i in 0 until size) second[i] = (second[i].toInt() xor first[i].toInt()).toByte() }
            else -> { delta(second); delta(first); for (i in 0 until size) second[i] = (second[i].toInt() xor first[i].toInt()).toByte() }
        }
        // sSpriteBuffer1 is the low bitplane, sSpriteBuffer2 the high one.
        val px = IntArray(wT * 8 * height)
        for (col in 0 until wT) for (y in 0 until height) {
            val lo = buf[0][col * height + y].toInt()
            val hi = buf[1][col * height + y].toInt()
            for (bx in 0 until 8) {
                val v = ((lo shr (7 - bx)) and 1) or (((hi shr (7 - bx)) and 1) shl 1)
                px[y * wT * 8 + col * 8 + bx] = v
            }
        }
        Pic(wT, hT, px)
    }.getOrNull()
}
