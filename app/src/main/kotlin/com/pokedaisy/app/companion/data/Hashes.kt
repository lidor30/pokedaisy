package com.pokedaisy.app.companion.data

import okio.HashingSink
import okio.blackholeSink
import okio.buffer

/*
 * CRC32 and SHA-1 without java.util.zip / java.security, which the iPad build doesn't have: CRC32 in plain Kotlin
 * (the zlib polynomial, java.util.zip.CRC32's values), SHA-1 on okio.
 */

/** java.util.zip.CRC32's API and values: [update] in pieces, [value] the CRC so far (0..0xFFFFFFFF). */
internal class Crc32 {
    private var crc = 0xFFFFFFFF.toInt()

    val value: Long get() = (crc.inv()).toLong() and 0xFFFFFFFFL

    fun update(b: Int) { crc = TABLE[(crc xor b) and 0xFF] xor (crc ushr 8) }

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException("$len bytes at $off of ${b.size}")
        var c = crc
        for (i in off until off + len) c = TABLE[(c xor b[i].toInt()) and 0xFF] xor (c ushr 8)
        crc = c
    }

    fun reset() { crc = 0xFFFFFFFF.toInt() }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }
    }
}

/** The CRC32 of [len] bytes of [b] from [off]. */
internal fun crc32(b: ByteArray, off: Int = 0, len: Int = b.size - off): Long = Crc32().apply { update(b, off, len) }.value

/** SHA-1 fed in pieces, as Sha1() was. */
internal class Sha1 {
    private val hashing = HashingSink.sha1(blackholeSink())
    private val sink = hashing.buffer()

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size - off) { sink.write(b, off, len) }

    /** The 20-byte digest of everything given so far. */
    fun digest(): ByteArray { sink.flush(); return hashing.hash.toByteArray() }
}

/** The SHA-1 of [b]. */
internal fun sha1(b: ByteArray): ByteArray = Sha1().apply { update(b) }.digest()
