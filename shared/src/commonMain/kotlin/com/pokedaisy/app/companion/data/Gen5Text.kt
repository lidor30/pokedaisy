package com.pokedaisy.app.companion.data

/**
 * A Gen 5 DS game's text (Black / White), as its message files (a/0/0/2 ...) hold it: a section count, a line count,
 * then each section's offset; a section is its size and a table of (offset, length in u16s, flags) per line, then the
 * lines. A line's u16s are XORed with a key that starts at the last one XOR 0xFFFF and is rotated right by 3 bits a
 * code, walking backwards. The codes are UTF-16 but for a few: 0xFFFF ends a line, 0xFFFE breaks it, 0xF000 starts a
 * control code (its command, an argument count and the arguments, skipped), 0xF100 starts a 9-bit packed string, and
 * 0x246D / 0x246E are ♂ / ♀. Read from the player's own cart for names and the DEX's pages: none of the text is bundled.
 */
object Gen5Text {
    /** Every line of the message file [file]'s first section (index 0 is the game's line 0). */
    fun lines(file: ByteArray): List<String> {
        if (file.size < 0x10) return emptyList()
        val count = u16(file, 2)
        val section = u32(file, 0x0C).toInt()
        return (0 until count).map { i ->
            val e = section + 4 + i * 8
            if (e + 8 > file.size) return@map ""
            val off = section + u32(file, e).toInt()
            val len = u16(file, e + 4)
            if (len == 0 || off < 0 || off + len * 2 > file.size) return@map ""
            val codes = IntArray(len) { u16(file, off + it * 2) }
            var key = codes[len - 1] xor 0xFFFF
            for (j in len - 1 downTo 0) {
                codes[j] = codes[j] xor key
                key = ((key ushr 3) or (key shl 13)) and 0xFFFF
            }
            decode(codes)
        }
    }

    private fun decode(codes: IntArray): String {
        val sb = StringBuilder()
        var j = 0
        while (j < codes.size) {
            when (val c = codes[j]) {
                0xFFFF -> break
                0xFFFE -> sb.append('\n')
                0xF000 -> {
                    // {COMMAND args}: the command, its argument count, the arguments
                    j += 3 + codes.getOrElse(j + 2) { 0 }
                    continue
                }
                0xF100 -> {
                    // A packed string: 9 bits a character, low bits first, until 0x1FF.
                    var bits = 0L
                    var have = 0
                    var k = j + 1
                    while (k < codes.size) {
                        bits = bits or (codes[k].toLong() shl have)
                        have += 16
                        k++
                        while (have >= 9) {
                            val ch = (bits and 0x1FF).toInt()
                            bits = bits ushr 9
                            have -= 9
                            if (ch == 0x1FF) return sb.toString()
                            sb.append(ch.toChar())
                        }
                    }
                    return sb.toString()
                }
                0x246D -> sb.append('♂')
                0x246E -> sb.append('♀')
                else -> sb.append(c.toChar())
            }
            j++
        }
        return sb.toString()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))
}
