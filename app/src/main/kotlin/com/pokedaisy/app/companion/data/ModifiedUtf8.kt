package com.pokedaisy.app.companion.data

import okio.BufferedSink
import okio.BufferedSource
import okio.IOException

/*
 * java.io.DataOutputStream.writeUTF / DataInputStream.readUTF's format on okio: a 16-bit big-endian length, then
 * "modified UTF-8" (U+0000 as two bytes, characters past U+FFFF as their two surrogates). SnapshotCache's files are
 * written in it, so a file from before the move to okio still reads, and the other way round.
 */

/** Writes [s] as writeUTF did; throws (writing nothing) if it takes more than 65535 bytes. */
internal fun <T : BufferedSink> T.writeModifiedUtf8(s: String): T {
    var len = 0
    for (c in s) len += when (c.code) { in 0x0001..0x007F -> 1; 0, in 0x0080..0x07FF -> 2; else -> 3 }
    if (len > 65535) throw IOException("encoded string too long: $len bytes")
    writeShort(len)
    for (c in s) {
        val ch = c.code
        when (ch) {
            in 0x0001..0x007F -> writeByte(ch)
            0, in 0x0080..0x07FF -> { writeByte(0xC0 or (ch shr 6)); writeByte(0x80 or (ch and 0x3F)) }
            else -> { writeByte(0xE0 or (ch shr 12)); writeByte(0x80 or ((ch shr 6) and 0x3F)); writeByte(0x80 or (ch and 0x3F)) }
        }
    }
    return this
}

/** Reads a string written by [writeModifiedUtf8] (or writeUTF); throws on malformed bytes or a short source. */
internal fun BufferedSource.readModifiedUtf8(): String {
    val len = readShort().toInt() and 0xFFFF
    val b = readByteArray(len.toLong())
    val sb = StringBuilder(len)
    var p = 0
    while (p < len) {
        val c = b[p].toInt() and 0xFF
        when (c shr 4) {
            in 0..7 -> { sb.append(c.toChar()); p++ }
            12, 13 -> {
                if (p + 2 > len) throw IOException("malformed input: partial character at end")
                val c2 = b[p + 1].toInt()
                if (c2 and 0xC0 != 0x80) throw IOException("malformed input around byte ${p + 1}")
                sb.append((((c and 0x1F) shl 6) or (c2 and 0x3F)).toChar()); p += 2
            }
            14 -> {
                if (p + 3 > len) throw IOException("malformed input: partial character at end")
                val c2 = b[p + 1].toInt()
                val c3 = b[p + 2].toInt()
                if (c2 and 0xC0 != 0x80 || c3 and 0xC0 != 0x80) throw IOException("malformed input around byte ${p + 2}")
                sb.append((((c and 0x0F) shl 12) or ((c2 and 0x3F) shl 6) or (c3 and 0x3F)).toChar()); p += 3
            }
            else -> throw IOException("malformed input around byte $p")
        }
    }
    return sb.toString()
}
