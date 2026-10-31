package com.pokedaisy.app.companion.data

import java.io.InputStream
import java.security.MessageDigest

/**
 * One retail FireRed / Emerald ROM as collection dumps of it may differ ([RETAIL_DUMPS], from
 * `scripts/gen_retail_dumps.py`): bytes [0, [end]) with [ranges] zeroed hash to [sha1].
 */
class RetailDump(
    val code: String,
    val rev: Int,
    /** One past the ROM's last byte that isn't 0x00 / 0xFF padding: a trimmed dump keeps everything before it. */
    val end: Int,
    /** What a cart-compatibility patch may rewrite: the cart's GPIO bytes, AgbMain's wait states, the flash code,
     *  its chip descriptors and the save warnings - plus retail's empty space, which nothing reads. */
    val ranges: List<IntRange>,
    val sha1: String,
    /** The cart's own SHA-1 (pret's), in [com.pokedaisy.app.companion.data.Poller.RETAIL_SHA1S]. */
    val retailSha1: String,
)

/**
 * Retail FireRed / Emerald dumps that aren't the cart byte for byte: the end's padding trimmed off, the save code
 * patched to save without the cart's flash chip (its warnings reworded, another chip's ID), the wait states changed,
 * a repack's leftovers in the empty space. Collections ship them that way;
 * their RAM and game code are retail's, so they're read as retail (library, website and in game alike). Anything
 * else changed - a mod, a hack - still isn't.
 */
object RetailDumps {
    private const val CHUNK = 1 shl 20

    /** The retail SHA-1 a ROM of this [code] / [rev] and [size] is a dump of, reading it from [input] (from its
     *  start, in order), or null. */
    fun matchStream(code: String?, rev: Int?, size: Long, input: () -> InputStream): String? {
        val dump = candidate(code, rev, size) ?: return null
        return input().use { s ->
            val ok = normalizedSha1(dump) { len -> readFully(s, len) } == dump.sha1
            if (ok) dump.retailSha1 else null
        }
    }

    /** [matchStream] over a reader that gives [len] bytes at an offset (the game's bus), in order. */
    fun match(code: String?, rev: Int?, size: Long, read: (offset: Long, len: Int) -> ByteArray?): String? {
        val dump = candidate(code, rev, size) ?: return null
        var off = 0L
        val ok = normalizedSha1(dump) { len -> read(off, len)?.takeIf { it.size == len }.also { off += len } } == dump.sha1
        return if (ok) dump.retailSha1 else null
    }

    private fun candidate(code: String?, rev: Int?, size: Long): RetailDump? =
        RETAIL_DUMPS.firstOrNull { it.code == code && it.rev == rev }?.takeIf { size >= it.end && size <= 0x1000000L }

    /** SHA-1 of bytes [0, end) with the ranges zeroed, [next] giving each following chunk; null if one's short. */
    private fun normalizedSha1(dump: RetailDump, next: (Int) -> ByteArray?): String? = maskedSha1(dump.end, dump.ranges, emptyList(), next)

    /** SHA-1 of bytes [0, [end]) with the text in [fold] upper-cased (Gen 3's a-z as A-Z) and then [ranges] zeroed,
     * [next] giving each following chunk; null if one's short. [RandomizedRetail] hashes the same way. */
    internal fun maskedSha1(end: Int, ranges: List<IntRange>, fold: List<IntRange>, next: (Int) -> ByteArray?): String? {
        val digest = MessageDigest.getInstance("SHA-1")
        var off = 0
        while (off < end) {
            val len = minOf(CHUNK, end - off)
            val chunk = next(len) ?: return null
            for (r in fold) {
                val a = maxOf(r.first, off) - off
                val z = minOf(r.last + 1, off + len) - off
                for (i in a until z) {
                    val c = chunk[i].toInt() and 0xFF
                    if (c in 0xD5..0xEE) chunk[i] = (c - 0x1A).toByte()
                }
            }
            for (r in ranges) {
                val a = maxOf(r.first, off) - off
                val z = minOf(r.last + 1, off + len) - off
                if (a < z) java.util.Arrays.fill(chunk, a, z, 0)
            }
            digest.update(chunk)
            off += len
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [len] bytes from [s], or null at its end. */
    internal fun readFully(s: InputStream, len: Int): ByteArray? {
        val buf = ByteArray(len)
        var n = 0
        while (n < len) {
            val r = s.read(buf, n, len - n)
            if (r < 0) return null
            n += r
        }
        return buf
    }
}
