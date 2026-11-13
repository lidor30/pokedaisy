package com.pokedaisy.app.companion.data

/**
 * A Game Boy Color (Gen 2) game's POKéDEX and item text, read from the cart's own bytes ([Gen1Dex.rom]:
 * the running Game Boy cart, which PokeDaisyActivity sets for either generation - a cart is
 * bank-switched, so the bus can't reach most of it): the page (PokedexDataPointerTable: category,
 * feet / inches, pounds x 10, two pages of text), BaseData (stats, types), and the front sprite, Gen 2's
 * LZ ([Gen2Lz]) coloured with the species' palette. Addresses are pret/pokecrystal's symbols
 * ([Gen2DexTables]); the seen / caught flags are WRAM bit arrays by species ([readGen2PokedexState]). Crystal's
 * species ids are its Dex numbers; Prism's aren't ([Gen2DexTables.dexOrder]), so every table here is by species.
 */
class Gen2DexTables(
    val baseData: Pair<Int, Int>,           // BaseData: [baseDataSize] bytes per species
    val dexEntryPointers: Pair<Int, Int>,   // PokedexDataPointerTable: a word per species ...
    val dexEntryBanks: Pair<Int, Int>,      // ... in the bank GetDexEntryPointer.PokedexEntryBanks names per 64
    val picPointers: Pair<Int, Int>,        // PokemonPicPointers: {bank, ptr} front then back, per species
    val unownPicPointers: Pair<Int, Int>,   // UnownPicPointers: the same, per letter (A first)
    val picsBanks: Pair<Int, Int>,          // FixPicBank.PicsBanks: the real bank by a stored one's index (see frontPixels)
    val palettes: Pair<Int, Int>,           // PokemonPalettes: {normal, shiny} x 2 RGB555 colours, from entry 0
    val itemDescriptions: Pair<Int, Int>,   // ItemDescriptions: a word per item below TM01, same bank
    val tmHmMoves: Pair<Int, Int>,          // TMHMMoves: the move each TM / HM teaches
    val moveDescriptions: Pair<Int, Int>,   // MoveDescriptions: a word per move, same bank
    val firstTm: Int,                       // TM01's item id
    val tmItems: IntArray,                  // the TM / HM item ids, in TMHMMoves' order
    // Crystal Clear's text dictionary: PlaceNextChar's table of 3-byte entries for bytes 0x16-0x5F (0 + a bank-0
    // string such as "with " or "#MON", or 1 + a routine), which packs its descriptions and dex pages; null = none.
    val textDict: Pair<Int, Int>? = null,
    /** Prism's text engine, which packs its dex pages and descriptions ([Gen2PackedText]); null = plain text. Its page
     * holds the category, height in decimetres, weight in hectograms, page 1's length in a byte, then the two halves
     * (Crystal's: feet x 100 + inches and pounds x 10, its halves split by a 0x50). */
    val packed: Gen2PackedText? = null,
    /** The species in the game's own Pokédex order, No.1 first, when its numbers aren't the species ids (Prism). */
    val dexOrder: IntArray? = null,
    /** BaseData's record size: Crystal's 32 bytes, Prism's 24 (no TM bits; the fields read here stay put). */
    val baseDataSize: Int = 32,
    /** [picPointers]' bank byte is the bank itself (Prism) rather than an index for FixPicBank ([picsBanks]). */
    val picBankDirect: Boolean = false,
    /** The species whose pics are by letter ([unownPicPointers]); 0 = none (Prism has no UNOWN). */
    val unown: Int = 201,
    /** The last item with a description in [itemDescriptions] (Crystal's run up to TM01; Prism's 253 items). */
    val lastItem: Int = firstTm - 1,
    /** Gold / Silver's GetDexEntryPointer: the page's bank is this one + (species - 1) / 64, no [dexEntryBanks] table
     * (-1: Crystal's table). */
    val dexEntryFirstBank: Int = -1,
    /** Crystal Legacy: [dexEntryPointers] holds a bank and an address per species, no [dexEntryBanks] table. */
    val dexEntryFar: Boolean = false,
    /** Gold / Silver's FixPicBank: a table of {stored bank, real bank} pairs, 0xFF-terminated - a bank not listed is
     * the real one (null: Crystal's [picsBanks] index). */
    val picBankFixes: Pair<Int, Int>? = null,
    /** Polished Crystal: this build's ROM tables ([polishedCrystalAddrs]): its pages, body data, pics, pic sizes, palettes and
     * base data are by the species' table index ([Gen2Dex]'s polished branch); null = Crystal's formats. */
    val polished: IntArray? = null,
    /** Height (a byte, dm) and weight (a word, hg) instead of Crystal's feet x 100 + inches and pounds x 10. */
    val metricBody: Boolean = false,
) {
    private val dexNumbers: IntArray? = dexOrder?.let { o ->
        IntArray((o.maxOrNull() ?: 0) + 1).also { m -> o.forEachIndexed { i, s -> if (m[s] == 0) m[s] = i + 1 } }
    }

    /** The entries the dex lists: Crystal's 251 Dex numbers, or [dexOrder]'s. */
    val dexCount: Int get() = dexOrder?.size ?: 251

    /** The species listed as dex No.[dex] (0 = none). */
    fun speciesOf(dex: Int): Int =
        if (dexOrder != null) dexOrder.getOrElse(dex - 1) { 0 } else if (dex in 1..251) dex else 0

    /** The dex number [species] is listed under (0 = none: Prism's EGG). */
    fun dexOf(species: Int): Int =
        if (dexNumbers != null) dexNumbers.getOrElse(species) { 0 } else if (species in 1..251) species else 0
}

/**
 * Prism's text engine (no source: read from its code). A text is a run of segments, each one of
 * - a plain string up to its 0x50 (PlaceString at 00:2FC5; control characters 0x44-0x5F as its table at
 *   [controlChars] has them: some print a bank-0 word, "Poké" or "Trainer");
 * - a reference to another text: 0x04-0x43 then a byte (the address, its high byte less 0x3C, then its low byte) and
 *   the bank, whose bit 7 says the text ends after it (00:32AB);
 * - 03 and a Huffman-coded stream (35:4E14): a bit at a time, most significant first, down the tree at [tree] (two
 *   bytes a node: below 0x70 the next node, else a leaf). Leaves 0x78-0x7B are 0x58-0x5B, 0xBC-0xCF the control
 *   characters 0x44-0x57, 0x7D + 4 bits a character from [rareChars], 0x7C + 6 bits one from [rareRanges] ({count,
 *   end} pairs: the value's place in them, counted back from the end); a 0x50 leaf ends the stream;
 * - 01 and code to run (00:32C5): only seen choosing between two texts by the UNITS option ([cfd1] bit 2, clear =
 *   metric, the game's default), which is read as the metric one;
 * and the text ends at a 0x50 where a segment would start, or at 0x57 (done) anywhere, after which the caller reads
 * on from just past it (wc2d0: the next byte, past the stream's last whole byte).
 */
class Gen2PackedText(
    val tree: Pair<Int, Int>,
    val rareChars: Pair<Int, Int>,
    val rareRanges: Pair<Int, Int>,
    val controlChars: Pair<Int, Int>,
)

/** Prism v0.95 build 254's text engine ([Gen2PackedText]). */
val PRISM_TEXT = Gen2PackedText(
    tree = 0x35 to 0x4E9A,
    rareChars = 0x35 to 0x4F74,
    rareRanges = 0x35 to 0x4F84,
    controlChars = 0x00 to 0x301C,
)

private const val PICS_FIX = 0x36

val GEN2_DEX_CRYSTAL = Gen2DexTables(
    baseData = 0x14 to 0x5424,
    dexEntryPointers = 0x11 to 0x4378,
    dexEntryBanks = 0x11 to 0x4351,
    picPointers = 0x48 to 0x4000,
    unownPicPointers = 0x49 to 0x4000,
    picsBanks = 0x14 to 0x51D4,
    palettes = 0x02 to 0x68CE,
    itemDescriptions = 0x72 to 0x4987,
    tmHmMoves = 0x04 to 0x567A,
    moveDescriptions = 0x0B to 0x4B52,
    firstTm = 0xBF,
    tmItems = gen2TmItemsCrystal,
)

/** Crystal Legacy v1.3.1 (its pokecrystal.sym): its own base data, pages, palettes and text; TM01 is item 0xC1. */
val GEN2_DEX_CRYSTAL_LEGACY = Gen2DexTables(
    baseData = 0x14 to 0x5449,
    dexEntryPointers = 0x11 to 0x43CD,
    dexEntryBanks = 0x11 to 0x43CD,   // unused: [dexEntryFar]
    picPointers = 0x48 to 0x4000,
    unownPicPointers = 0x49 to 0x4000,
    picsBanks = 0x14 to 0x51F9,
    palettes = 0x02 to 0x6C83,
    itemDescriptions = 0x72 to 0x498C,
    tmHmMoves = 0x04 to 0x567A,
    moveDescriptions = 0x0B to 0x4E9A,
    firstTm = 0xC1,
    tmItems = gen2TmItemsCrystalLegacy,
    dexEntryFar = true,
)

/**
 * Gold and Silver (pret/pokegold): every table at the same address in both versions; the pages and front pics they point
 * at differ (each version's own dex text), so each reads its own ROM. The page's bank is computed (64 species a bank from
 * 68:4000, GetDexEntryPointer) and the pics' banks fixed by a pair table (FixPicBank, 14:5878).
 */
val GEN2_DEX_GOLD_SILVER = Gen2DexTables(
    baseData = 0x14 to 0x5B0B,
    dexEntryPointers = 0x11 to 0x4360,
    dexEntryBanks = 0x11 to 0x4360,   // unused: [dexEntryFirstBank]
    picPointers = 0x12 to 0x4000,
    unownPicPointers = 0x1F to 0x4000,
    picsBanks = 0x14 to 0x5878,       // unused: [picBankFixes]
    palettes = 0x02 to 0x6D3D,
    itemDescriptions = 0x6E to 0x4000,
    tmHmMoves = 0x04 to 0x5A66,
    moveDescriptions = 0x6D to 0x4000,
    firstTm = 0xBF,
    tmItems = gen2TmItemsCrystal,
    dexEntryFirstBank = 0x68,
    picBankFixes = 0x14 to 0x5878,
)

/** Johto's 251 by National number (the game's own New / Old dex modes are orderings of the same flags). */
val POKEDEX_CRYSTAL = PokedexTables(
    entries = 0, frontPics = 0, palettes = 0, speciesInfo = 0, speciesToNational = 0, abilityNames = 0, footprints = 0,
    speciesCount = 252, nationalCount = 251, regionalCount = 251, regionName = null,
    gen2 = GEN2_DEX_CRYSTAL,
)

val POKEDEX_CRYSTAL_LEGACY = POKEDEX_CRYSTAL.copy(gen2 = GEN2_DEX_CRYSTAL_LEGACY)

/** German Crystal (Kristall): Crystal's tables moved, its pages metric with no "POKéMON" after the category. */
val GEN2_DEX_CRYSTAL_DE = Gen2DexTables(
    baseData = 0x14 to 0x540E,
    dexEntryPointers = 0x11 to 0x436C,
    dexEntryBanks = 0x11 to 0x4346,
    picPointers = 0x48 to 0x4000,
    unownPicPointers = 0x49 to 0x4000,
    picsBanks = 0x14 to 0x51BE,
    palettes = 0x02 to 0x68CE,
    itemDescriptions = 0x72 to 0x4979,
    tmHmMoves = 0x04 to 0x5671,
    moveDescriptions = 0x0B to 0x4B55,
    firstTm = 0xBF,
    tmItems = gen2TmItemsCrystal,
    metricBody = true,
)
val POKEDEX_CRYSTAL_DE = POKEDEX_CRYSTAL.copy(
    gen2 = GEN2_DEX_CRYSTAL_DE, categorySuffix = "", metric = true, decimalPoint = '.', unitSpace = false,
)

/** Gold's and Silver's: the same tables over each version's own pages (two objects, so a dex cache never crosses them). */
val POKEDEX_GOLD = POKEDEX_CRYSTAL.copy(gen2 = GEN2_DEX_GOLD_SILVER)
val POKEDEX_SILVER = POKEDEX_CRYSTAL.copy(gen2 = GEN2_DEX_GOLD_SILVER)

/** German Gold / Silver: English's tables, a few moved; their pages metric, as Kristall's. */
val GEN2_DEX_GOLD_SILVER_DE = Gen2DexTables(
    baseData = 0x14 to 0x5B00,
    dexEntryPointers = 0x11 to 0x4354,
    dexEntryBanks = 0x11 to 0x4354,   // unused: [dexEntryFirstBank]
    picPointers = 0x12 to 0x4000,
    unownPicPointers = 0x1F to 0x4000,
    picsBanks = 0x14 to 0x586D,       // unused: [picBankFixes]
    palettes = 0x02 to 0x6D3D,
    itemDescriptions = 0x6E to 0x4000,
    tmHmMoves = 0x04 to 0x5A5D,
    moveDescriptions = 0x6D to 0x4000,
    firstTm = 0xBF,
    tmItems = gen2TmItemsCrystal,
    dexEntryFirstBank = 0x68,
    picBankFixes = 0x14 to 0x586D,
    metricBody = true,
)
val POKEDEX_GOLD_DE = POKEDEX_CRYSTAL_DE.copy(gen2 = GEN2_DEX_GOLD_SILVER_DE)
val POKEDEX_SILVER_DE = POKEDEX_CRYSTAL_DE.copy(gen2 = GEN2_DEX_GOLD_SILVER_DE)

/**
 * Crystal Clear v2.6.0 (both builds): Crystal's tables moved (found by their contents and the code that reads them,
 * checked in Gen2ArtTest on every build), the pics' pointers where Crystal has them; its text packed with a
 * dictionary ([Gen2DexTables.textDict]).
 */
val GEN2_DEX_CRYSTAL_CLEAR_V2_6_0 = Gen2DexTables(
    baseData = 0x14 to 0x5698,
    dexEntryPointers = 0x15 to 0x7E03,
    dexEntryBanks = 0x15 to 0x7DDC,
    picPointers = 0x48 to 0x4000,
    unownPicPointers = 0x49 to 0x4000,
    picsBanks = 0x14 to 0x542D,
    palettes = 0x02 to 0x6A2C,
    itemDescriptions = 0x72 to 0x49D1,
    tmHmMoves = 0x04 to 0x4F8F,
    moveDescriptions = 0x0B to 0x4EA0,
    firstTm = 0xBF,
    tmItems = gen2TmItemsCrystal,
    textDict = 0x00 to 0x11FF,
)

/** Crystal Clear v2.5.11 (both builds): v2.6.0's tables but for the move descriptions, 0x20 bytes earlier. */
val GEN2_DEX_CRYSTAL_CLEAR_V2_5_11 = GEN2_DEX_CRYSTAL_CLEAR_V2_6_0.let {
    Gen2DexTables(it.baseData, it.dexEntryPointers, it.dexEntryBanks, it.picPointers, it.unownPicPointers, it.picsBanks,
        it.palettes, it.itemDescriptions, it.tmHmMoves, 0x0B to 0x4E80, it.firstTm, it.tmItems, it.textDict)
}

val POKEDEX_CRYSTAL_CLEAR_V2_6_0 = POKEDEX_CRYSTAL.copy(gen2 = GEN2_DEX_CRYSTAL_CLEAR_V2_6_0)
val POKEDEX_CRYSTAL_CLEAR_V2_5_11 = POKEDEX_CRYSTAL.copy(gen2 = GEN2_DEX_CRYSTAL_CLEAR_V2_5_11)

/**
 * Prism v0.95 build 254, found by the code that draws its dex page (no source): DisplayDexEntry (02:79C1) gets the page
 * by species from GetDexEntryPointer (02:7ADA: a word per species at 02:7B1B, the bank per 64 at 00:081D) and prints
 * category, HT (dm), WT (hg) and the page's two halves through its text engine ([PRISM_TEXT]); GetMonFrontpic
 * (0B:6F54) takes {bank, address} from 17:4000 (6 bytes a species, front then back) and the size from BaseData (24
 * bytes at 14:5A0E); the pic's colours are 02:4C84's, by species (02:4939). The bag's text: 11:4AD0 prints an item's
 * from 11:4AF9, the TM pocket a move's (0B:49EB, from 0B:5704). Its one dex lists the species in its own order
 * ([gen2DexOrderPrism], 10:4915), numbered by their place there. Checked against the game's own dex pages headless
 * (PrismDexTest).
 */
val GEN2_DEX_PRISM = Gen2DexTables(
    baseData = 0x14 to 0x5A0E,
    dexEntryPointers = 0x02 to 0x7B1B,
    dexEntryBanks = 0x00 to 0x081D,
    picPointers = 0x17 to 0x4000,
    unownPicPointers = 0x17 to 0x4000,   // unused: no UNOWN ([unown] 0)
    picsBanks = 0x17 to 0x4000,          // unused: the pointers hold the bank ([picBankDirect])
    palettes = 0x02 to 0x4C84,
    itemDescriptions = 0x11 to 0x4AF9,
    tmHmMoves = 0x04 to 0x53A4,
    moveDescriptions = 0x0B to 0x5704,
    firstTm = 0x101,
    tmItems = gen2TmItemsPrism,
    packed = PRISM_TEXT,
    dexOrder = gen2DexOrderPrism,
    baseDataSize = 24,
    picBankDirect = true,
    unown = 0,
    lastItem = 253,
)

/** Prism's one dex: 253 entries in its own order, metric ("0.6m", "72.0kg": the page as the game prints it by default). */
val POKEDEX_PRISM = POKEDEX_CRYSTAL.copy(
    speciesCount = 255, nationalCount = 253, regionalCount = 253, gen2 = GEN2_DEX_PRISM,
    categorySuffix = "Pokémon", metric = true, decimalPoint = '.', unitSpace = false,
)

/**
 * Polished Crystal's dex for build [sha1]: its National order (289: a species' 9-bit value, less 2 past 0xFF / 0x100), its
 * own tables where that build keeps them; metric (body data is decimetres / hectograms). One object per build, so a dex
 * cache never crosses them.
 */
private val polishedDexes = HashMap<String, PokedexTables>()
fun polishedCrystalPokedex(sha1: String): PokedexTables? = synchronized(polishedDexes) {
    val a = polishedCrystalAddrs[sha1] ?: return null
    polishedDexes.getOrPut(sha1) {
        fun at(k: Int) = (a[k] shr 16) to (a[k] and 0xFFFF)
        // (The Crystal-shaped fields unused by the polished branch point at its own tables too.)
        val g = Gen2DexTables(
            baseData = at(15), dexEntryPointers = at(11), dexEntryBanks = at(11), picPointers = at(13), unownPicPointers = at(13),
            picsBanks = at(13), palettes = at(2), itemDescriptions = at(11), tmHmMoves = at(11), moveDescriptions = at(11),
            firstTm = 0x101, tmItems = gen2TmItemsPolishedCrystal, dexOrder = gen2DexOrderPolishedCrystal,
            baseDataSize = POLISHED_BASE_DATA_SIZE, unown = 0, polished = a,
        )
        POKEDEX_CRYSTAL.copy(
            speciesCount = POLISHED_NUM_SPECIES + 1, nationalCount = gen2DexOrderPolishedCrystal.size,
            regionalCount = gen2DexOrderPolishedCrystal.size, gen2 = g,
            categorySuffix = "Pokémon", metric = true, decimalPoint = '.', unitSpace = false,
        )
    }
}

/** A Game Boy dex (either generation): read from the cart, its tables by Dex number (Prism's by species, in the
 * order [Gen2DexTables.dexOrder] lists them). */
val PokedexTables.gameBoy: Boolean get() = gen1 != null || gen2 != null

/** Gen 2's LZ (home/decompress.asm): literal, iterate, alternate, zero fill, and repeat / flip / reverse back-references. */
object Gen2Lz {
    private val FLIP = IntArray(256) { b -> (0 until 8).fold(0) { acc, i -> acc or (((b shr i) and 1) shl (7 - i)) } }

    /**
     * [polished]: Polished Crystal's variant (its home/decompress.asm, its own compressor's counts): a run repeats its
     * byte n + 2 times and the alternating pair writes n + 3 bytes (the pair, then n + 1 chasing its own tail); a long
     * command's length has one high bit.
     */
    fun decompress(rom: ByteArray, start: Int, max: Int = 0x4000, polished: Boolean = false): ByteArray? = runCatching {
        val bytes = ArrayList<Int>()
        var p = start
        fun u8() = rom[p++].toInt() and 0xFF
        while (true) {
            val b = u8()
            if (b == 0xFF) break
            var cmd = b shr 5
            val length: Int
            if (cmd == 7) {
                cmd = (b shr 2) and 7
                length = (((b and (if (polished) 1 else 3)) shl 8) or u8()) + 1
            } else {
                length = (b and 0x1F) + 1
            }
            when (cmd) {
                0 -> repeat(length) { bytes += u8() }
                1 -> { val v = u8(); repeat(if (polished) length + 1 else length) { bytes += v } }
                2 -> { val a = u8(); val c = u8(); repeat(if (polished) length + 2 else length) { bytes += if (it % 2 == 0) a else c } }
                3 -> repeat(length) { bytes += 0 }
                else -> {
                    val o = u8()
                    val from = if (o and 0x80 != 0) bytes.size - (o and 0x7F) - 1 else (o shl 8) or u8()
                    if (from < 0 || from >= bytes.size) return null
                    when (cmd) {
                        4 -> repeat(length) { bytes += bytes[from + it] }
                        5 -> repeat(length) { bytes += FLIP[bytes[from + it]] }
                        else -> repeat(length) { bytes += bytes[from - it] }
                    }
                }
            }
            if (bytes.size > max) return null
        }
        ByteArray(bytes.size) { bytes[it].toByte() }
    }.getOrNull()
}

object Gen2Dex {
    private fun off(bank: Int, addr: Int) = if (bank == 0) addr else bank * 0x4000 + (addr - 0x4000)
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    /** A line break in the game's text: [flow] joins the lines back into one paragraph. */
    private const val BREAK = "\n"

    private val CHARS: Map<Int, String> = buildMap {
        for (i in 0 until 26) { put(0x80 + i, ('A' + i).toString()); put(0xA0 + i, ('a' + i).toString()) }
        for (i in 0 until 10) put(0xF6 + i, ('0' + i).toString())
        putAll(mapOf(
            0x7F to " ", 0x9A to "(", 0x9B to ")", 0x9C to ":", 0x9D to ";", 0x9E to "[", 0x9F to "]", 0xD0 to "'d",
            0xD1 to "'l", 0xD2 to "'m", 0xD3 to "'r", 0xD4 to "'s", 0xD5 to "'t", 0xD6 to "'v", 0xE0 to "'", 0xE3 to "-",
            0xE6 to "?", 0xE7 to "!", 0xE8 to ".", 0xE9 to "&", 0xEA to "é", 0xEF to "♂", 0xF0 to "¥", 0xF1 to "×",
            0xF2 to ".", 0xF3 to "/", 0xF4 to ",", 0xF5 to "♀", 0x54 to "POKé", 0x75 to "…", 0x56 to "……", 0x5B to "PC",
            0x5C to "TM", 0x5D to "TRAINER", 0x5E to "ROCKET", 0x4A to "PKMN", 0xE1 to "PK", 0xE2 to "MN", 0x1F to " ",
            0x22 to " ", 0x4E to BREAK, 0x4F to BREAK, 0x55 to BREAK, 0x51 to BREAK, 0xC0 to "Ä", 0xC1 to "Ö", 0xC2 to "Ü",
            0xC3 to "ä", 0xC4 to "ö", 0xC5 to "ü",
            0xBA to "…",   // Prism's font (unused in Crystal's)
        ))
    }

    /**
     * The game's lines as one paragraph, as the companion shows it: spaces collapsed, and a word the game broke over two
     * lines with a hyphen joined back - a hyphen after a letter, then a lowercase letter: "anger-" / "ed" and "Poké-" /
     * "mon" lose it, a compound keeps it ("fire-" / "type": [COMPOUND_ENDS], every one Prism's text breaks at its line's
     * end). Crystal's capitals never join.
     */
    internal fun flow(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.split('\n')) {
            val l = line.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            if (l.isEmpty()) continue
            val n = sb.length
            when {
                n == 0 -> sb.append(l)
                n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter() && l[0].isLowerCase() -> {
                    if (l.takeWhile { it.isLetter() } !in COMPOUND_ENDS) sb.setLength(n - 1)
                    sb.append(l)
                }
                else -> sb.append(' ').append(l)
            }
        }
        return sb.toString()
    }

    /** Second halves of the hyphenated words Prism's and Polished Crystal's dex pages, item and move descriptions break
     * over two lines. */
    private val COMPOUND_ENDS = setOf(
        "type", "like", "colored", "shaped", "bodied", "white", "strike", "covered", "pitched", "hard", "evolved", "smelling",
        "level", "lowering", "boost", "filled",
    )

    /** Gen 2 text from [o] until its end (@ 0x50); a dex entry's second page (a page break, 0x50 too) when [pages] is 2.
     * [words]: the game's dictionary ([dictionary]), whose bytes stand for whole strings. */
    private fun text(rom: ByteArray, o: Int, pages: Int = 1, words: Map<Int, Int> = emptyMap()): String =
        flow(StringBuilder().also { raw(rom, o, pages, words, it, 0) }.toString())

    private fun raw(rom: ByteArray, o: Int, pages: Int, words: Map<Int, Int>, sb: StringBuilder, depth: Int) {
        var i = o
        var left = pages
        while (i < rom.size && i < o + 400) {
            val c = u8(rom, i++)
            if (c == 0x50) {
                if (--left <= 0) break
                sb.append(BREAK)
                continue
            }
            val word = words[c]
            if (word != null && depth < 4) raw(rom, word, 1, words, sb, depth + 1) else sb.append(CHARS[c] ?: "")
        }
    }

    /** [t]'s text dictionary: a byte -> its string in bank 0 (PlaceNextChar's table: entries of kind 0 that point into
     * the ROM; the rest are routines - line breaks, the player's name - which print as [CHARS] has them). */
    private fun dictionary(rom: ByteArray, t: Gen2DexTables): Map<Int, Int> {
        val (b, a) = t.textDict ?: return emptyMap()
        val o = off(b, a)
        val out = HashMap<Int, Int>()
        for (c in 0x16..0x5F) {
            if (c == 0x1F || c == 0x50) continue   // a space before the lookup / the end of the text
            val e = o + 3 * (c - 0x16)
            val p = u16(rom, e + 1)
            if (u8(rom, e) == 0 && p < 0x4000) out[c] = p
        }
        return out
    }

    // ---- Prism's text engine ([Gen2PackedText]) ----

    @Volatile private var packedWordsFor: Pair<ByteArray, Map<Int, String>>? = null

    /** The control characters whose handler prints a bank-0 word: `push de / ld de, word / jp`. */
    private fun packedWords(rom: ByteArray, p: Gen2PackedText): Map<Int, String> {
        packedWordsFor?.let { (r, w) -> if (r === rom) return w }
        val o = off(p.controlChars.first, p.controlChars.second)
        val out = HashMap<Int, String>()
        for (c in 0x44..0x5F) {
            val h = u16(rom, o + 2 * (c - 0x44))
            if (h + 5 >= 0x4000 || u8(rom, h) != 0xD5 || u8(rom, h + 1) != 0x11 || u8(rom, h + 4) != 0xC3) continue
            val s = u16(rom, h + 2)
            if (s >= 0x4000) continue
            out[c] = buildString { var i = s; while (i < s + 16 && u8(rom, i) != 0x50) append(CHARS[u8(rom, i++)] ?: "") }
        }
        packedWordsFor = rom to out
        return out
    }

    /** One run of Prism's text loop (00:3266) over [rom]: the characters it prints, and where the caller reads on. */
    private class PackedRun(private val rom: ByteArray, private val p: Gen2PackedText) {
        val codes = ArrayList<Int>()
        /** Past the 0x57 (done) that ended the text, in the bank it was read from: wc2d0, what PlaceFarString returns. */
        var next = -1
        private var steps = 0

        private fun byte(bank: Int, addr: Int): Int {
            check(if (bank == 0) addr in 0 until 0x4000 else addr in 0x4000 until 0x8000) { "text left its bank" }
            val o = if (bank == 0) addr else bank * 0x4000 + (addr - 0x4000)
            check(o < rom.size) { "text past the ROM" }
            return rom[o].toInt() and 0xFF
        }

        private fun tick() = check(++steps < 6000 && codes.size < 1200) { "runaway text" }

        /** The text at [bank]:[start] (00:3266): returns at its end - a 0x50 where a segment starts, or a done. */
        fun run(bank: Int, start: Int, depth: Int = 0) {
            check(depth < 8) { "text nested too deep" }
            var a = start
            while (true) {
                tick()
                val c = byte(bank, a++)
                when {
                    c == 0x50 -> return
                    c == 0x03 -> {
                        val (after, done) = huffman(bank, a)
                        a = after
                        if (done) return
                    }
                    // 01: code to run - only the UNITS option's choice of two texts is known:
                    // ld a, [wOptions2] / and 4 / ld hl, metric / ret z / ld hl, imperial / ret.
                    c == 0x01 -> {
                        val ok = byte(bank, a) == 0xFA && byte(bank, a + 3) == 0xE6 && byte(bank, a + 4) == 0x04 &&
                            byte(bank, a + 5) == 0x21 && byte(bank, a + 8) == 0xC8 && byte(bank, a + 9) == 0x21 &&
                            byte(bank, a + 12) == 0xC9
                        check(ok) { "unknown code in text" }
                        a = byte(bank, a + 6) or (byte(bank, a + 7) shl 8)
                    }
                    c < 0x04 -> error("text command $c")
                    c < 0x44 -> {
                        val to = ((c + 0x3C) shl 8) or byte(bank, a)
                        val b = byte(bank, a + 1)
                        a += 2
                        run(b and 0x7F, to, depth + 1)
                        if (b and 0x80 != 0) return
                    }
                    else -> {
                        // A plain string to its 0x50.
                        var ch = c
                        while (ch != 0x50) {
                            tick()
                            if (ch == 0x57) { next = a; return }
                            codes += ch
                            ch = byte(bank, a++)
                        }
                    }
                }
            }
        }

        /** 03's stream from [start] (35:4E14): where the text goes on after it, and whether a done ended it. */
        private fun huffman(bank: Int, start: Int): Pair<Int, Boolean> {
            var a = start
            var left = 0
            var cur = 0
            fun bit(): Int {
                if (left == 0) { cur = byte(bank, a++); left = 8 }
                left--
                return (cur shr left) and 1
            }
            fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
            val tree = off(p.tree.first, p.tree.second)
            while (true) {
                tick()
                var node = 0
                do { node = u8(rom, tree + 2 * node + bit()) } while (node < 0x70)
                val ch = when {
                    node < 0x78 -> node
                    node < 0x7C -> node - 0x20
                    node == 0x7D -> u8(rom, off(p.rareChars.first, p.rareChars.second) + bits(4))
                    node == 0x7C -> rare(bits(6))
                    node < 0xBC -> node
                    node < 0xD0 -> node - 0x78
                    else -> node
                }
                if (ch == 0x50) return a to false
                if (ch == 0x57) { next = a; return a to true }
                codes += ch
            }
        }

        /** Leaf 0x7C's 6 bits: their place in the {count, end} ranges, counted back from the range's end. */
        private fun rare(value: Int): Int {
            var v = value
            val o = off(p.rareRanges.first, p.rareRanges.second)
            for (i in 0 until 8) {
                val n = u8(rom, o + 2 * i)
                if (v < n) return (u8(rom, o + 2 * i + 1) - n + v) and 0xFF
                v -= n
            }
            error("rare character $value")
        }
    }

    /** Prism's text at [bank]:[addr], with the address its caller reads on from (-1: none). */
    private fun packedText(rom: ByteArray, p: Gen2PackedText, bank: Int, addr: Int): Pair<String, Int> {
        val run = PackedRun(rom, p).also { it.run(bank, addr) }
        val words = packedWords(rom, p)
        val s = buildString {
            for (c in run.codes) append(words[c] ?: if (c == 0x5F) BREAK else CHARS[c] ?: "")
        }
        return s to run.next
    }

    /** Polished Crystal's page: PokedexDataPointerTable's {bank, address} (the category, then the two pages, each to its '@'),
     * PokemonBodyData's height (dm) and weight (hg), BaseData's stats and types - all by the species' index. */
    private fun polishedEntry(t: Gen2DexTables, a: IntArray, dex: Int, species: Int): DexEntry? {
        val rom = Gen1Dex.rom ?: return null
        fun at(k: Int) = off(a[k] shr 16, a[k] and 0xFFFF)
        val i = species - 1
        val e = at(11) + 3 * i
        var o = off(u8(rom, e), u16(rom, e + 1))
        fun str(): String = polishedString(rom, o, a).also { o = it.second }.first
        val category = str()
        val description = flow(str() + BREAK + str())
        val body = at(12) + 4 * i
        val s = at(15) + t.baseDataSize * i
        // BASE_ABILITY_1 / 2 / 3 (+13): two regular ones (the same one twice for a species with one) and the hidden one.
        val regular = listOf(u8(rom, s + 13), u8(rom, s + 14)).filter { it != 0 }.distinct()
        val hidden = u8(rom, s + 15).takeIf { it != 0 && it !in regular }
        return DexEntry(
            national = dex,
            species = species,
            category = category,
            heightDm = u8(rom, body),
            weightHg = u16(rom, body + 1),
            description = description,
            type1 = u8(rom, s + 6),
            type2 = u8(rom, s + 7),
            // BaseData: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF -> the page's HP, ATK, DEF, SP. ATK, SP. DEF, SPEED.
            baseStats = listOf(u8(rom, s), u8(rom, s + 1), u8(rom, s + 2), u8(rom, s + 4), u8(rom, s + 5), u8(rom, s + 3)),
            abilities = regular.map { polishedAbilityName(rom, a, it) },
            hiddenAbility = hidden?.let { polishedAbilityName(rom, a, it) },
            catchRate = u8(rom, s + 8),
            genderRatio = gen2GenderRatiosPolishedCrystal.getOrElse(species) { 255 },
            eggGroups = emptyList(),
        )
    }

    /** Polished Crystal's string at [start] in its charmap, to its '@' or <DONE> (a line break read as a space), and the
     * address after it. */
    private fun polishedString(rom: ByteArray, start: Int, a: IntArray? = null): Pair<String, Int> {
        var o = start
        val sb = StringBuilder()
        while (true) {
            val c = u8(rom, o++)
            if (c == 0x53 || c == 0x52) break
            if (c == 0x5D && a != null) {
                // <CTXT>: the rest is Huffman-coded, most significant bit first, to a terminator (ReadHuffmanChar):
                // node n's branches at tree[2n], [2n + 1]; a value from 0x7F is a character, 0xEC-0xFB being 0x4D-0x5C.
                val tree = off(a[25] shr 16, a[25] and 0xFFFF)
                var bits = 0
                var byte = 0
                while (true) {
                    var node = 0
                    while (true) {
                        if (bits == 0) { byte = u8(rom, o++); bits = 8 }
                        val bit = byte shr 7 and 1
                        byte = byte shl 1 and 0xFF
                        bits--
                        node = u8(rom, tree + 2 * node + bit)
                        if (node >= 0x7F) break
                    }
                    val ch = if (node >= 0xEC) node - 0xEC + 0x4D else node
                    if (ch == 0x53 || ch == 0x52 || ch == 0x54) return sb.toString().trim() to o
                    sb.append(if (ch in 0x55..0x59) BREAK else polishedTextChars[ch])
                }
            }
            // <LNBRK> <NEXT> <LINE> <CONT> <PARA>: the next line ([flow] joins a word broken over it).
            sb.append(if (c in 0x55..0x59) BREAK else polishedTextChars[c])
        }
        return sb.toString().trim() to o
    }

    /** Polished Crystal's text [index] of the word table [k] in [polishedCrystalAddrs] (the strings sit in its bank). */
    private fun polishedTableText(rom: ByteArray, a: IntArray, k: Int, index: Int): String {
        val bank = a[k] shr 16
        val ptr = u16(rom, off(bank, a[k] and 0xFFFF) + 2 * index)
        return polishedString(rom, off(bank, ptr), a).first
    }

    /** AbilityNames: a word per ability id, 0 = none. */
    private fun polishedAbilityName(rom: ByteArray, a: IntArray, id: Int): String = polishedTableText(rom, a, 23, id)

    /** Polished Crystal's bag text: ItemDescriptions by item id, KeyItemDescriptions by key item (the companion's 0x200 + n),
     * a TM / HM's move's MoveDescriptions (TMHMMoves) - PrintItemDescription / PrintKeyItemDescription / PrintTMHMDescription. */
    private fun polishedItemDescription(rom: ByteArray, t: Gen2DexTables, a: IntArray, id: Int): String {
        if (id !in itemNamesPolishedCrystal) return ""
        val text = when {
            id > 0x200 -> polishedTableText(rom, a, 21, id - 0x201)
            id > 0x100 -> {
                val i = t.tmItems.indexOf(id).takeIf { it >= 0 } ?: return ""
                val move = u8(rom, off(a[24] shr 16, a[24] and 0xFFFF) + i)
                polishedTableText(rom, a, 22, move - 1)
            }
            id >= 1 -> polishedTableText(rom, a, 20, id - 1)
            else -> return ""
        }
        return flow(text)
    }

    fun entry(t: Gen2DexTables, dex: Int): DexEntry? {
        val rom = Gen1Dex.rom ?: return null
        val species = t.speciesOf(dex).takeIf { it > 0 } ?: return null
        t.polished?.let { return polishedEntry(t, it, dex, species) }
        val (pb, pa) = t.dexEntryPointers
        val far = off(pb, pa) + 3 * (species - 1)
        val bank = if (t.dexEntryFar) u8(rom, far)
            else if (t.dexEntryFirstBank >= 0) t.dexEntryFirstBank + ((species - 1) shr 6)
            else u8(rom, off(t.dexEntryBanks.first, t.dexEntryBanks.second) + ((species - 1) shr 6))
        val ptr = if (t.dexEntryFar) u16(rom, far + 1) else u16(rom, off(pb, pa) + 2 * (species - 1))
        val s = off(t.baseData.first, t.baseData.second) + t.baseDataSize * (species - 1)
        val category: String
        val description: String
        val heightDm: Int
        val weightHg: Int
        var heightIn: Int? = null
        var lbs10: Int? = null
        val p = t.packed
        if (p != null) {
            // Prism: the category, then (where its done left off) HT, WT and page 1's length, then the two pages.
            val (cat, next) = packedText(rom, p, bank, ptr)
            if (next < 0) return null
            val o = off(bank, next)
            category = flow(cat)
            heightDm = u16(rom, o)
            weightHg = u16(rom, o + 2)
            val page1 = packedText(rom, p, bank, next + 5).first
            val page2 = packedText(rom, p, bank, next + 5 + u8(rom, o + 4)).first
            description = flow(page1 + BREAK + page2)
        } else {
            val e = off(bank, ptr)
            var o = e
            while (u8(rom, o) != 0x50) o++
            val words = dictionary(rom, t)
            category = text(rom, e, words = words)
            o++
            if (t.metricBody) {
                heightDm = u8(rom, o)
                weightHg = u16(rom, o + 1)
                description = text(rom, o + 3, pages = 2, words = words)
            } else {
                val height = u16(rom, o)     // feet x 100 + inches
                lbs10 = u16(rom, o + 2)
                heightIn = (height / 100) * 12 + height % 100
                heightDm = Math.round(heightIn * 2.54 / 10).toInt()
                weightHg = Math.round(lbs10 * 0.45359237).toInt()
                description = text(rom, o + 4, pages = 2, words = words)
            }
        }
        return DexEntry(
            national = dex,
            species = species,
            category = category,
            heightDm = heightDm,
            weightHg = weightHg,
            description = description,
            type1 = u8(rom, s + 7),
            type2 = u8(rom, s + 8),
            // BaseData: HP, ATTACK, DEFENSE, SPEED, SP. ATK, SP. DEF -> the page's HP, ATK, DEF, SP. ATK, SP. DEF, SPEED.
            baseStats = listOf(u8(rom, s + 1), u8(rom, s + 2), u8(rom, s + 3), u8(rom, s + 5), u8(rom, s + 6), u8(rom, s + 4)),
            abilities = emptyList(),
            catchRate = u8(rom, s + 9),
            genderRatio = u8(rom, s + 13),
            eggGroups = emptyList(),
            heightIn = heightIn,
            weightLbs10 = lbs10,
        )
    }

    /**
     * [species]' 56x56 front sprite as ARGB pixels (colour 0 clear): the pic's first frame, its tiles in
     * columns, placed in the 7x7-tile box the way PadFrontpic does - centred across (a 5x5 one column in),
     * on the box's bottom.
     */
    fun frontPixels(t: Gen2DexTables, species: Int): IntArray? {
        val rom = Gen1Dex.rom ?: return null
        if (t.dexOf(species) == 0) return null
        t.polished?.let { a ->
            // Polished Crystal: PokemonPicPointers' {bank, front, back, frames} by index, its size a nybble of PokemonPicSizes
            // (the first of a byte's two in its high half), its LZ its own, coloured from PokemonPalettes (index + 1).
            fun at(k: Int) = off(a[k] shr 16, a[k] and 0xFFFF)
            val i = species - 1
            val sz = u8(rom, at(14) + i / 2).let { if (i % 2 == 0) it shr 4 else it and 0xF }
            val e = at(13) + 7 * i
            val tiles = Gen2Lz.decompress(rom, off(u8(rom, e), u16(rom, e + 1)), polished = true) ?: return null
            return placePic(rom, tiles, sz, at(2) + 8 * species)
        }
        val s = off(t.baseData.first, t.baseData.second) + t.baseDataSize * (species - 1)
        val size = u8(rom, s + 17) and 0xF
        if (size !in 1..7) return null
        // UNOWN's pics are by letter: A's for the dex.
        val ptrs = if (species == t.unown) off(t.unownPicPointers.first, t.unownPicPointers.second)
        else off(t.picPointers.first, t.picPointers.second) + 6 * (species - 1)
        val fixes = t.picBankFixes
        val bank = if (t.picBankDirect) u8(rom, ptrs) else if (fixes != null) {
            // FixPicBank (Gold / Silver): the stored bank, or the one its {stored, real} pair names.
            val stored = u8(rom, ptrs)
            var o = off(fixes.first, fixes.second)
            var real = stored
            while (u8(rom, o) != 0xFF) { if (u8(rom, o) == stored) { real = u8(rom, o + 1); break }; o += 2 }
            real
        } else {
            // FixPicBank: the stored bank byte is an index into PicsBanks, offset by its first bank - PICS_FIX.
            val banks = off(t.picsBanks.first, t.picsBanks.second)
            u8(rom, banks + u8(rom, ptrs) - (u8(rom, banks) - PICS_FIX))
        }
        val tiles = Gen2Lz.decompress(rom, off(bank, u16(rom, ptrs + 1))) ?: return null
        return placePic(rom, tiles, size, off(t.palettes.first, t.palettes.second) + 8 * species)
    }

    /** A [size] x [size]-tile pic ([tiles], columns first) in its 7x7 box, in the two colours at [pal] (+ clear, black). */
    private fun placePic(rom: ByteArray, tiles: ByteArray, size: Int, pal: Int): IntArray? {
        if (size !in 1..7 || tiles.size < size * size * 16) return null
        fun color(i: Int): Int {
            val c = u16(rom, pal + 2 * i)
            fun ch(v: Int) = ((v and 31) shl 3) or ((v and 31) shr 2)
            return (0xFF shl 24) or (ch(c) shl 16) or (ch(c shr 5) shl 8) or ch(c shr 10)
        }
        val colors = intArrayOf(0, color(0), color(1), 0xFF000000.toInt())
        val out = IntArray(56 * 56)
        val ox = ((7 - size + 1) / 2) * 8
        val oy = (7 - size) * 8
        for (tile in 0 until size * size) {
            val tx = tile / size
            val ty = tile % size
            for (y in 0 until 8) {
                val lo = u8(tiles, tile * 16 + 2 * y)
                val hi = u8(tiles, tile * 16 + 2 * y + 1)
                for (x in 0 until 8) {
                    val v = ((lo shr (7 - x)) and 1) or (((hi shr (7 - x)) and 1) shl 1)
                    out[(oy + ty * 8 + y) * 56 + ox + tx * 8 + x] = colors[v]
                }
            }
        }
        return out
    }

    /** Item [id]'s bag description, as the game prints it: its own text, or a TM / HM's move's. "" without a cart. */
    fun itemDescription(t: Gen2DexTables, id: Int): String {
        val rom = Gen1Dex.rom ?: return ""
        t.polished?.let { a -> return runCatching { polishedItemDescription(rom, t, a, id) }.getOrDefault("") }
        return runCatching {
            val (bank, ptr) = if (id >= t.firstTm) {
                val i = t.tmItems.indexOf(id).takeIf { it >= 0 } ?: return ""
                val move = u8(rom, off(t.tmHmMoves.first, t.tmHmMoves.second) + i)
                val (mb, ma) = t.moveDescriptions
                mb to u16(rom, off(mb, ma) + 2 * (move - 1))
            } else if (id in 1..t.lastItem) {
                val (ib, ia) = t.itemDescriptions
                ib to u16(rom, off(ib, ia) + 2 * (id - 1))
            } else return ""
            val p = t.packed
            if (p != null) flow(packedText(rom, p, bank, ptr).first) else text(rom, off(bank, ptr), words = dictionary(rom, t))
        }.getOrDefault("")
    }
}
