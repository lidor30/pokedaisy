package com.pokedaisy.app.companion.data

/*
 * Reading numbers out of byte arrays, in plain Kotlin (java.nio.ByteBuffer is the JDK's, and this code is shared with
 * the iPad app). The games store everything little-endian.
 */

/** Little-endian reads at absolute offsets: the subset of a little-endian java.nio.ByteBuffer the ROM scanners used,
 * with the same bounds (reading past [limit] throws IndexOutOfBoundsException). */
interface LittleEndianReader {
    fun limit(): Int
    fun get(i: Int): Byte
    fun getShort(i: Int): Short = ((get(i).toInt() and 0xFF) or (get(i + 1).toInt() shl 8)).toShort()
    fun getInt(i: Int): Int = (getShort(i).toInt() and 0xFFFF) or (getShort(i + 2).toInt() shl 16)
    fun getLong(i: Int): Long = (getInt(i).toLong() and 0xFFFFFFFFL) or (getInt(i + 4).toLong() shl 32)
}

/** A byte array (its first [limit] bytes) read little-endian. */
class LittleEndianBytes(val bytes: ByteArray, private val limit: Int = bytes.size) : LittleEndianReader {
    init { require(limit in 0..bytes.size) { "limit $limit of ${bytes.size}" } }

    override fun limit(): Int = limit

    private fun check(i: Int, n: Int) {
        if (i < 0 || i > limit - n) throw IndexOutOfBoundsException("$n bytes at $i, limit $limit")
    }

    override fun get(i: Int): Byte { check(i, 1); return bytes[i] }
    override fun getShort(i: Int): Short { check(i, 2); return ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort() }
    override fun getInt(i: Int): Int { check(i, 4); return s32le(bytes, i) }
    override fun getLong(i: Int): Long { check(i, 8); return (s32le(bytes, i).toLong() and 0xFFFFFFFFL) or (s32le(bytes, i + 4).toLong() shl 32) }
}

/** The signed 32-bit little-endian word at [off]. */
internal fun s32le(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16) or (b[off + 3].toInt() shl 24)

/** [v] as 4 little-endian bytes. */
internal fun le32(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

/** [v] as 4 big-endian bytes. */
internal fun be32(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

/** [len] bytes from [off] as text, one char a byte; bytes past 0x7F become U+FFFD, as a US-ASCII decode gave. */
internal fun asciiString(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
    if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException("$len bytes at $off of ${b.size}")
    return CharArray(len) { val c = b[off + it].toInt() and 0xFF; if (c < 0x80) c.toChar() else '�' }.concatToString()
}

/** [s] as US-ASCII bytes: a char past 0x7F (a surrogate pair counting once) becomes '?', as the JDK's encoder does. */
internal fun asciiBytes(s: String): ByteArray {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) { out += '?'.code.toByte(); i += 2; continue }
        out += if (c.code < 0x80) c.code.toByte() else '?'.code.toByte()
        i++
    }
    return out.toByteArray()
}

/** [s] as UTF-16 big-endian bytes, two a char (no byte order mark). */
internal fun utf16beBytes(s: String): ByteArray = ByteArray(s.length * 2).also { out ->
    s.forEachIndexed { i, c -> out[i * 2] = (c.code ushr 8).toByte(); out[i * 2 + 1] = c.code.toByte() }
}
