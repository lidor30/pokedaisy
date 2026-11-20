package com.pokedaisy.app.companion.data

import okio.Path

/** A piece of a game's text look: the dialogue box's tiles and palette, the normal font's glyphs and their widths. */
enum class TextPart { BOX, BOX_PAL, FONT, FONT_WIDTHS }

/**
 * TEXT LOOK (SETTINGS > MAKEOVER): one game's dialogue box and letters in another - FireRed's blue-and-white box and
 * font in Emerald, Emerald's teal box and font in FireRed / LeafGreen - taken from the player's own ROM of that game,
 * like [PlayerLook] (blocks into the running ROM in memory; nothing bundled, the save untouched).
 *
 * The two games' boxes are the same window (30 tiles across, 6 rows) drawn from different tile sets: Emerald's 14 tiles
 * (two columns on the left, one on the right, every middle row alike), FireRed's 18 (a pill: two columns on each side,
 * changing row by row). Emerald's is re-cut into FireRed's slots and comes out whole ([cut]); FireRed's needs FireRed's
 * layout, so in Emerald its 13 tiles go in as they are and the game's frame routines are swapped for one drawing that
 * layout ([emeraldDialogue], scripts/text_look_dialogue.s). Both draw the frame with palette entries 10-15 and their
 * text with 1-9 (the same colours), so the frame's six colours go into the host's box palette as they are.
 *
 * The fonts have one format (512 glyphs of four 2bpp tiles, a byte of width each) and go in whole, wherever the host
 * keeps its own (FireRed: four copies). Every language has the box; the font differs by language, so letters only change
 * where the host's font is the source's language's (the fingerprints are English's: English carts and their hacks).
 */
/** Code found by fingerprint ([TextLook.findCode]): [anchorBytes] (hex, 8 bytes no build changes) at [anchor] into it,
 * [length] bytes in all, [crc] = CRC32 of them with the bytes a build can change ([TextLook.mask]) zeroed. */
class CodeSig(val anchor: Int, val anchorBytes: String, val length: Int, val crc: Long)

object TextLook {
    /** The games with a text look of their own: FireRed (LeafGreen's is the same) and Emerald. */
    val STYLES = listOf(LookEngine.FIRERED, LookEngine.EMERALD)

    /** A host's text style (null: none of ours - Ruby / Sapphire, whose boxes and fonts are built another way). */
    fun styleOf(engine: LookEngine?): LookEngine? = engine?.takeIf { it in STYLES }

    fun blob(style: LookEngine, part: TextPart): TextBlob? = TextBlob.entries.firstOrNull { it.style == style && it.part == part }

    /**
     * FireRed's box tiles from Emerald's: -1 keeps FireRed's own (tiles the box never draws). FireRed's slots: top row 0
     * (col 0), 1, 2 (filled across), 3, 4 (col 29); the middle rows 5, 6, 8, 9 (two of them) and 10-13 (the other two).
     * Emerald's: top 1 (col 0), 3, 4 (across), 5, 6 (col 29); the middle rows 7 (col 0), 9 (col 1, white), 10 (col 29).
     * The bottom row is the top one flipped in both, and the column inside the right edge is white in Emerald's.
     */
    private val FIRERED_FROM_EMERALD = intArrayOf(1, 3, 4, 5, 6, 7, 9, -1, 9, 10, 7, 9, 9, 10, -1, -1, -1, -1)

    /**
     * Emerald drawing FireRed's box: its own frame routine draws every middle row alike, so the box's slots take
     * FireRed's 13 tiles as they are ([FIRERED_IN_EMERALD], slot 13 kept) and [DIALOGUE_CODE] draws them in FireRed's
     * layout, put in the ROM's empty end and called where the game called its own ([emeraldDialogue]).
     */
    private val FIRERED_IN_EMERALD = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, -1)

    /** scripts/text_look_dialogue.s assembled; its last three words ([CODE_FN], +4, +8) are set per ROM. */
    private val DIALOGUE_CODE = hex(
        "F0B544464D4656465F46F0B486B080460C1C151C5E1E0F9883469348008881469248007802908F48824601200190002004906F1E012000904946003104980918" +
        "A21E3B1C50468446404600F00AF9012000904946013104980918621E3B1C50468446404600F0FDF800964946023104980918221C3B1C50468446404600F0F1F8" +
        "012000904946033104980918A2193B1C50468446404600F0E4F8012000904946043104980918A21901323B1C50468446404600F0D6F80120C00204902F1C5F44" +
        "012000904946003104980918A21E3B1C50468446404600F0C4F8012000904946013104980918621E3B1C50468446404600F0B7F800964946023104980918221C" +
        "3B1C50468446404600F0ABF8012000904946033104980918A2193B1C50468446404600F09EF8012000904946043104980918A21901323B1C50468446404600F0" +
        "90F80020039003985946884200DB7FE02F180022002841D0013988423CD041005B46994201DB0122D2020492012000904946093104980918A21E3B1C50468446" +
        "404600F06EF80120009049460A3104980918621E3B1C50468446404600F061F80120009049460B3104980918A2193B1C50468446404600F054F8012000904946" +
        "0C3104980918A21901323B1C50468446404600F046F837E00122D2020492012000904946053104980918A21E3B1C50468446404600F035F80120009049460631" +
        "04980918621E3B1C50468446404600F028F8012000904946073104980918A2193B1C50468446404600F01BF8012000904946083104980918A21901323B1C5046" +
        "8446404600F00DF80398013003907AE706B0F0BCA046A946B246BB46F0BC01BC004760470DF00DF05EBA5EBA1E9A1E9A",
    )
    private const val CODE_FN = 0x264

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Which bytes of [code] a build can change (scripts/gen_text_look_sigs.py's `mask`): each byte of a BL pair, and
     * every 4-aligned word whose top byte says it's an address (02 / 03 RAM, 08 / 09 ROM). */
    internal fun mask(code: ByteArray, at: Int, n: Int): BooleanArray {
        val keep = BooleanArray(n) { true }
        var i = 0
        while (i + 4 <= n) {
            val h = (code[at + i].toInt() and 0xFF) or ((code[at + i + 1].toInt() and 0xFF) shl 8)
            val h2 = (code[at + i + 2].toInt() and 0xFF) or ((code[at + i + 3].toInt() and 0xFF) shl 8)
            if (h and 0xF800 == 0xF000 && h2 and 0xF800 == 0xF800) { for (k in i until i + 4) keep[k] = false; i += 4 } else i += 2
        }
        for (w in 0 until n - 3 step 4) if ((code[at + w + 3].toInt() and 0xFF) in listOf(2, 3, 8, 9)) for (k in w until w + 4) keep[k] = false
        return keep
    }

    /** Where [sig]'s code sits in [rom], 4-aligned: its anchor bytes, then the CRC of the code with what a build can
     * change zeroed. */
    internal fun findCode(rom: ByteArray, sig: CodeSig): List<Int> {
        val head = hex(sig.anchorBytes)
        val out = ArrayList<Int>()
        var i = 0
        while (true) {
            val at = indexOf(rom, head, i)
            if (at < 0) break
            val start = at - sig.anchor
            if (start >= 0 && start % 4 == 0 && start + sig.length <= rom.size) {
                val keep = mask(rom, start, sig.length)
                val crc = Crc32()
                for (k in 0 until sig.length) crc.update(if (keep[k]) rom[start + k].toInt() and 0xFF else 0)
                if (crc.value == sig.crc) out += start
            }
            i = at + 1
        }
        return out
    }

    private fun indexOf(a: ByteArray, b: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - b.size) {
            for (j in b.indices) if (a[i + j] != b[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u32(rom: ByteArray, at: Int) = (rom[at].toInt() and 0xFF) or ((rom[at + 1].toInt() and 0xFF) shl 8) or
        ((rom[at + 2].toInt() and 0xFF) shl 16) or ((rom[at + 3].toInt() and 0xFF) shl 24)

    private fun word(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** The target of the first BL in the code at [at] (a ROM offset), or -1. */
    private fun firstBl(rom: ByteArray, at: Int, len: Int): Int {
        var i = at
        while (i + 4 <= at + len) {
            val h = (rom[i].toInt() and 0xFF) or ((rom[i + 1].toInt() and 0xFF) shl 8)
            val h2 = (rom[i + 2].toInt() and 0xFF) or ((rom[i + 3].toInt() and 0xFF) shl 8)
            if (h and 0xF800 == 0xF000 && h2 and 0xF800 == 0xF800) {
                var off = ((h and 0x7FF) shl 12) or ((h2 and 0x7FF) shl 1)
                if (off and 0x400000 != 0) off -= 0x800000
                return i + 4 + off
            }
            i += 2
        }
        return -1
    }

    /**
     * The writes making Emerald's ROM ([rom], empty from [free]) call [DIALOGUE_CODE] for both of its dialogue frame
     * routines: two copies in the ROM's last 2 KB (the standard one reading Emerald's tile base 0x200 and palette 15
     * from two constants after them, the other the game's sTileNum / sPaletteNum), and every pointer to the game's own
     * routines moved to them. Null where the code isn't Emerald's (a rebuild: Imperium) or the end isn't empty.
     */
    internal fun emeraldDialogue(rom: ByteArray, free: Int): List<Pair<Int, ByteArray>>? {
        val std = findCode(rom, TextLookCode.DIALOGUE_FRAME).singleOrNull() ?: return null
        val custom = findCode(rom, TextLookCode.DIALOGUE_FRAME_CUSTOM).singleOrNull() ?: return null
        val fill = firstBl(rom, std, TextLookCode.DIALOGUE_FRAME.length).takeIf { it > 0 } ?: return null
        // The custom routine's pool: &sTileNum (u16) then &sPaletteNum (u8), the only EWRAM words in it.
        val customLen = TextLookCode.DIALOGUE_FRAME_CUSTOM.length
        val ewram = (custom until custom + customLen - 3 step 4).map { u32(rom, it) }.filter { it ushr 24 == 2 }.distinct().sorted()
        if (ewram.size != 2) return null
        val at = (rom.size - 0x800) and 3.inv()
        val size = DIALOGUE_CODE.size * 2 + 4
        if (at < free + 0x40000 || (at until at + size).any { rom[it] != 0xFF.toByte() }) return null
        fun code(base: Int, pal: Int) = DIALOGUE_CODE.copyOf().also {
            word(0x08000000 + fill or 1).copyInto(it, CODE_FN)
            word(base).copyInto(it, CODE_FN + 4)
            word(pal).copyInto(it, CODE_FN + 8)
        }
        val consts = at + DIALOGUE_CODE.size * 2
        val writes = arrayListOf(
            at to code(0x08000000 + consts, 0x08000000 + consts + 2),
            at + DIALOGUE_CODE.size to code(ewram[0], ewram[1]),
            consts to byteArrayOf(0x00, 0x02, 0x0F, 0x00),
        )
        val stdPtr = 0x08000000 + std + 1
        val customPtr = 0x08000000 + custom + 1
        var moved = 0
        for (o in 0 until rom.size - 3 step 4) {
            when (u32(rom, o)) {
                stdPtr -> { writes += o to word(0x08000000 + at + 1); moved++ }
                customPtr -> { writes += o to word(0x08000000 + at + DIALOGUE_CODE.size + 1); moved++ }
            }
        }
        return writes.takeIf { moved > 0 }
    }

    /** [host]'s box tiles ([own]) with the source's ([src]) cut into them. */
    internal fun cut(host: LookEngine, own: ByteArray, src: ByteArray): ByteArray {
        val map = if (host == LookEngine.EMERALD) FIRERED_IN_EMERALD else FIRERED_FROM_EMERALD
        val out = own.copyOf()
        map.forEachIndexed { slot, from -> if (from >= 0) src.copyInto(out, slot * 32, from * 32, from * 32 + 32) }
        if (host == LookEngine.EMERALD) {
            // Emerald's window is a column wider and covers the right end's inner column, pale bar and all: the left
            // end's bar (colour 13 in its inner tiles) goes white too, so the box stays even.
            for (slot in intArrayOf(6, 10)) for (i in slot * 32 until slot * 32 + 32) {
                val b = out[i].toInt() and 0xFF
                val lo = (b and 15).let { if (it == 13) 10 else it }
                val hi = (b shr 4).let { if (it == 13) 10 else it }
                out[i] = (lo or (hi shl 4)).toByte()
            }
        }
        return out
    }

    /** [own] (the host's box palette) with the source's frame colours, entries 10-15. */
    internal fun boxPalette(own: ByteArray, src: ByteArray): ByteArray = own.copyOf().also { src.copyInto(it, 20, 20, 32) }

    /**
     * The blocks putting [style]'s text look ([art], from [TextArt]) into the ROM [scan] was made from, a game of [host]'s
     * style: the box where both have theirs, the font where both are the same language's. Empty when nothing fits.
     */
    fun build(scan: PlayerLook.Scan, host: LookEngine, style: LookEngine, art: Map<TextPart, ByteArray>): List<PlayerLook.Block> {
        if (host == style) return emptyList()
        val rom = scan.rom
        val writes = ArrayList<Pair<Int, ByteArray>>()
        fun hits(part: TextPart) = blob(host, part)?.let { scan.hits[it] }.orEmpty()
        val boxAt = hits(TextPart.BOX)
        val srcBox = art[TextPart.BOX]
        val srcPal = art[TextPart.BOX_PAL]
        // Emerald drawing FireRed's box needs its frame code swapped too: without it, no box (the letters still go).
        val code = if (host == LookEngine.EMERALD) emeraldDialogue(rom, scan.free) else emptyList()
        if (boxAt.isNotEmpty() && srcBox != null && srcPal != null && code != null) {
            val own = rom.copyOfRange(boxAt[0], boxAt[0] + blob(host, TextPart.BOX)!!.size)
            boxAt.forEach { writes += it to cut(host, own, srcBox) }
            writes += code
            // The box's palette: the copy nearest its tiles (Emerald keeps the same 32 bytes again elsewhere).
            hits(TextPart.BOX_PAL).minByOrNull { kotlin.math.abs(it - boxAt[0]) }?.let { at ->
                writes += at to boxPalette(rom.copyOfRange(at, at + 32), srcPal)
            }
        }
        val font = art[TextPart.FONT]
        val widths = art[TextPart.FONT_WIDTHS]
        val fontAt = hits(TextPart.FONT)
        val widthsAt = hits(TextPart.FONT_WIDTHS)
        if (font != null && widths != null && fontAt.isNotEmpty() && widthsAt.isNotEmpty()) {
            fontAt.forEach { writes += it to font }
            widthsAt.forEach { writes += it to widths }
        }
        return if (writes.isEmpty()) emptyList() else PlayerLook.merge(rom, writes)
    }
}

/** Each game's text look as decoded from a ROM the player has, in `rom-art/look/text/<style>/` (like [LookArt]). */
object TextArt {
    private fun dir(filesDir: Path?, style: LookEngine) = LookArt.dir(filesDir) / "text" / style.name.lowercase()
    private fun file(filesDir: Path?, style: LookEngine, part: TextPart) = dir(filesDir, style) / "${part.name.lowercase()}.bin"

    /** Keeps whatever [scan] found of each style (once): the box with its palette, the font with its widths. */
    fun keep(filesDir: Path?, scan: PlayerLook.Scan): List<LookEngine> = TextLook.STYLES.filter { style ->
        val pairs = listOf(TextPart.BOX to TextPart.BOX_PAL, TextPart.FONT to TextPart.FONT_WIDTHS)
        var kept = false
        for ((a, b) in pairs) {
            if (file(filesDir, style, a).isFile && file(filesDir, style, b).isFile) continue
            val da = data(scan, style, a) ?: continue
            val db = data(scan, style, b) ?: continue
            dir(filesDir, style).mkdirs()
            file(filesDir, style, a).writeBytes(da)
            file(filesDir, style, b).writeBytes(db)
            kept = true
        }
        kept
    }

    private fun data(scan: PlayerLook.Scan, style: LookEngine, part: TextPart): ByteArray? {
        val b = TextLook.blob(style, part) ?: return null
        val at = scan.hits[b]?.firstOrNull() ?: return null
        return Fingerprints.decode(scan.rom, at, b)
    }

    /** [style]'s kept parts (the box at least), or null. */
    fun load(filesDir: Path?, style: LookEngine): Map<TextPart, ByteArray>? {
        val m = TextPart.entries.mapNotNull { p ->
            val f = file(filesDir, style, p)
            val b = TextLook.blob(style, p)
            if (b != null && f.isFile && f.length == b.size.toLong()) p to f.readBytes() else null
        }.toMap()
        return m.takeIf { TextPart.BOX in it && TextPart.BOX_PAL in it }
    }
}
