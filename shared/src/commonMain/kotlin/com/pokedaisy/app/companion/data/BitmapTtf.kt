package com.pokedaisy.app.companion.data

import okio.Buffer
import okio.BufferedSink

/**
 * A TrueType font built from 8x8 bitmap glyphs - how a Game Boy game's own font
 * (read from the player's ROM, never bundled) becomes something Compose can set
 * text in. Each lit pixel run is a square-cornered rectangle on a 100-unit pixel
 * at 1600 units per em, with Pixel Operator's ascent / descent: the app's
 * GbaTextMetrics scale either font to whole screen pixels the same way.
 *
 * [glyphs] rows are top to bottom, bit 7 = the left pixel (the GB's 1bpp tile
 * rows); rows 0..[baselineRow] sit above the baseline, the rest below it.
 */
object BitmapTtf {
    private const val UPM = 1600
    private const val PX = 100
    private const val ASCENT = 1300
    private const val DESCENT = -300
    private const val LINE_GAP = 72

    fun build(family: String, glyphs: Map<Char, ByteArray>, advancePx: Int = 8, baselineRow: Int = 6): ByteArray {
        val chars = glyphs.keys.sorted()
        val advance = advancePx * PX
        // Glyph 0 is .notdef (empty); glyph i + 1 is chars[i].
        val outlines = listOf(IntArray(0)) + chars.map { outline(glyphs.getValue(it), baselineRow) }
        val glyf = Buffer()
        val loca = IntArray(outlines.size + 1)
        var maxPoints = 0
        var maxContours = 0
        var xMin = 0; var yMin = 0; var xMax = 0; var yMax = 0
        outlines.forEachIndexed { i, rects ->
            loca[i] = glyf.size.toInt()
            if (rects.isNotEmpty()) {
                val n = rects.size / 4
                maxContours = maxOf(maxContours, n)
                maxPoints = maxOf(maxPoints, n * 4)
                val gx0 = (0 until n).minOf { rects[it * 4] }
                val gy0 = (0 until n).minOf { rects[it * 4 + 1] }
                val gx1 = (0 until n).maxOf { rects[it * 4 + 2] }
                val gy1 = (0 until n).maxOf { rects[it * 4 + 3] }
                xMin = minOf(xMin, gx0); yMin = minOf(yMin, gy0); xMax = maxOf(xMax, gx1); yMax = maxOf(yMax, gy1)
                val d = glyf
                d.writeShort(n); d.writeShort(gx0); d.writeShort(gy0); d.writeShort(gx1); d.writeShort(gy1)
                for (c in 0 until n) d.writeShort(c * 4 + 3)          // endPtsOfContours
                d.writeShort(0)                                       // no instructions
                repeat(n * 4) { d.writeByte(1) }                      // all on-curve, full-size coords
                // Clockwise: up the left edge, along the top, down the right edge.
                var px = 0
                var py = 0
                val xs = IntArray(n * 4); val ys = IntArray(n * 4)
                for (c in 0 until n) {
                    val x0 = rects[c * 4]; val y0 = rects[c * 4 + 1]; val x1 = rects[c * 4 + 2]; val y1 = rects[c * 4 + 3]
                    intArrayOf(x0, x0, x1, x1).copyInto(xs, c * 4)
                    intArrayOf(y0, y1, y1, y0).copyInto(ys, c * 4)
                }
                for (x in xs) { d.writeShort(x - px); px = x }
                for (y in ys) { d.writeShort(y - py); py = y }
                while (glyf.size % 4 != 0L) glyf.writeByte(0)
            }
        }
        loca[outlines.size] = glyf.size.toInt()

        val tables = listOf(
            "OS/2" to os2(chars, advance),
            "cmap" to cmap(chars),
            "glyf" to glyf.readByteArray(),
            "head" to head(xMin, yMin, xMax, yMax),
            "hhea" to hhea(advance, outlines.size),
            "hmtx" to bytes { d -> outlines.forEach { r -> d.writeShort(advance); d.writeShort(if (r.isEmpty()) 0 else (0 until r.size / 4).minOf { r[it * 4] }) } },
            "loca" to bytes { d -> loca.forEach { d.writeInt(it) } },
            "maxp" to bytes { d ->
                d.writeInt(0x00010000); d.writeShort(outlines.size); d.writeShort(maxPoints); d.writeShort(maxContours)
                d.writeShort(0); d.writeShort(0); d.writeShort(2); repeat(8) { d.writeShort(0) }   // composites, zones, the rest 0
            },
            "name" to name(family),
            "post" to bytes { d -> d.writeInt(0x00030000); d.writeInt(0); d.writeShort(-PX); d.writeShort(PX); d.writeInt(1); repeat(4) { d.writeInt(0) } },
        ).sortedBy { it.first }.toMap()   // the table directory in tag order
        return assemble(tables)
    }

    /** Rectangles {x0, y0, x1, y1} (font units), one per run of lit pixels in a row. */
    private fun outline(rows: ByteArray, baselineRow: Int): IntArray {
        val out = ArrayList<Int>()
        rows.forEachIndexed { r, bits ->
            var x = 0
            while (x < 8) {
                if ((bits.toInt() shr (7 - x)) and 1 == 0) { x++; continue }
                val start = x
                while (x < 8 && (bits.toInt() shr (7 - x)) and 1 == 1) x++
                val top = (baselineRow + 1 - r) * PX
                out += listOf(start * PX, top - PX, x * PX, top)
            }
        }
        return out.toIntArray()
    }

    private fun bytes(write: (BufferedSink) -> Unit): ByteArray = Buffer().also { write(it) }.readByteArray()

    private fun head(xMin: Int, yMin: Int, xMax: Int, yMax: Int) = bytes { d ->
        d.writeInt(0x00010000); d.writeInt(0x00010000); d.writeInt(0)   // version, revision, checkSumAdjustment (set later)
        d.writeInt(0x5F0F3CF5); d.writeShort(0x000B); d.writeShort(UPM)
        d.writeLong(0); d.writeLong(0)                                    // created / modified
        d.writeShort(xMin); d.writeShort(yMin); d.writeShort(xMax); d.writeShort(yMax)
        d.writeShort(0); d.writeShort(8); d.writeShort(2); d.writeShort(1); d.writeShort(0)
    }

    private fun hhea(advance: Int, numGlyphs: Int) = bytes { d ->
        d.writeInt(0x00010000); d.writeShort(ASCENT); d.writeShort(DESCENT); d.writeShort(LINE_GAP)
        d.writeShort(advance); d.writeShort(0); d.writeShort(0); d.writeShort(advance)
        d.writeShort(1); d.writeShort(0); d.writeShort(0); repeat(4) { d.writeShort(0) }
        d.writeShort(0); d.writeShort(numGlyphs)
    }

    private fun os2(chars: List<Char>, advance: Int) = bytes { d ->
        d.writeShort(4); d.writeShort(advance); d.writeShort(400); d.writeShort(5); d.writeShort(0)
        repeat(8) { d.writeShort(PX * 4) }                          // sub/superscript sizes + offsets
        d.writeShort(PX); d.writeShort(PX * 3)                      // strikeout
        d.writeShort(0); repeat(10) { d.writeByte(0) }              // family class, panose
        d.writeInt(1); repeat(3) { d.writeInt(0) }                  // Basic Latin
        d.writeUtf8("NONE"); d.writeShort(0x40)                    // vendor, REGULAR
        d.writeShort(chars.first().code); d.writeShort(chars.last().code)
        d.writeShort(ASCENT); d.writeShort(DESCENT); d.writeShort(LINE_GAP)
        d.writeShort(ASCENT); d.writeShort(-DESCENT)
        d.writeInt(1); d.writeInt(0)                                // Latin 1 code page
        d.writeShort(PX * 5); d.writeShort(PX * 7); d.writeShort(0); d.writeShort(' '.code); d.writeShort(1)
    }

    /** Format 4, one segment per character (glyph i + 1 for chars[i]). */
    private fun cmap(chars: List<Char>) = bytes { d ->
        val segs = chars.size + 1
        val sub = bytes { s ->
            s.writeShort(4); s.writeShort(16 + segs * 8); s.writeShort(0)
            var p = 1; var e = 0
            while (p * 2 <= segs) { p *= 2; e++ }
            s.writeShort(segs * 2); s.writeShort(p * 2); s.writeShort(e); s.writeShort(segs * 2 - p * 2)
            chars.forEach { s.writeShort(it.code) }; s.writeShort(0xFFFF)                    // endCode
            s.writeShort(0)
            chars.forEach { s.writeShort(it.code) }; s.writeShort(0xFFFF)                    // startCode
            chars.forEachIndexed { i, c -> s.writeShort((i + 1 - c.code) and 0xFFFF) }; s.writeShort(1) // idDelta
            repeat(segs) { s.writeShort(0) }                                                 // idRangeOffset
        }
        d.writeShort(0); d.writeShort(1); d.writeShort(3); d.writeShort(1); d.writeInt(12); d.write(sub)
    }

    private fun name(family: String) = bytes { d ->
        val strings = listOf(1 to family, 2 to "Regular", 4 to family, 6 to family.filter { it.isLetterOrDigit() })
        val data = strings.map { utf16beBytes(it.second) }
        d.writeShort(0); d.writeShort(strings.size); d.writeShort(6 + 12 * strings.size)
        var off = 0
        strings.forEachIndexed { i, (id, _) ->
            d.writeShort(3); d.writeShort(1); d.writeShort(0x409); d.writeShort(id); d.writeShort(data[i].size); d.writeShort(off)
            off += data[i].size
        }
        data.forEach { d.write(it) }
    }

    private fun checksum(b: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i < b.size) {
            var w = 0L
            for (k in 0 until 4) w = (w shl 8) or (if (i + k < b.size) (b[i + k].toLong() and 0xFF) else 0L)
            sum = (sum + w) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun assemble(tables: Map<String, ByteArray>): ByteArray {
        val n = tables.size
        var p = 1; var e = 0
        while (p * 2 <= n) { p *= 2; e++ }
        val d = Buffer()
        d.writeInt(0x00010000); d.writeShort(n); d.writeShort(p * 16); d.writeShort(e); d.writeShort(n * 16 - p * 16)
        var offset = 12 + 16 * n
        val padded = tables.mapValues { (_, b) -> b.copyOf((b.size + 3) / 4 * 4) }
        for ((tag, b) in tables) {
            d.writeUtf8(tag); d.writeInt(checksum(b).toInt()); d.writeInt(offset); d.writeInt(b.size)
            offset += padded.getValue(tag).size
        }
        padded.values.forEach { d.write(it) }
        val font = d.readByteArray()
        // head.checkSumAdjustment: 0xB1B0AFBA minus the whole font's checksum.
        val headOff = 12 + 16 * n + tables.keys.takeWhile { it != "head" }.sumOf { padded.getValue(it).size }
        val adj = (0xB1B0AFBAL - checksum(font)) and 0xFFFFFFFFL
        for (k in 0 until 4) font[headOff + 8 + k] = (adj shr (24 - 8 * k)).toByte()
        return font
    }
}
