package com.pokedaisy.app.companion.coop

import kotlin.math.abs

/**
 * An invite as a QR code: a square grid of modules (true = dark), error correction M, no quiet zone (the page draws its
 * own). Our own encoder in common Kotlin (ISO/IEC 18004, byte mode only): the smallest version 1-40 that holds the text,
 * Reed-Solomon over GF(256), and the mask with the lowest penalty score.
 */
object CoopQr {
    fun encode(text: String): Array<BooleanArray> {
        val data = text.encodeToByteArray()
        val version = (1..40).firstOrNull { 4 + countBits(it) + data.size * 8 <= dataCodewords(it) * 8 }
            ?: throw IllegalArgumentException("text too long for a QR code")
        val qr = Grid(version)
        qr.drawFunctionPatterns()
        qr.drawCodewords(withEcc(codewords(data, version), version))
        val best = (0 until 8).minBy { mask ->
            qr.applyMask(mask)
            qr.drawFormatBits(mask)
            qr.penalty().also { qr.applyMask(mask) }   // XOR again undoes it
        }
        qr.applyMask(best)
        qr.drawFormatBits(best)
        return Array(qr.size) { y -> BooleanArray(qr.size) { x -> qr.dark[y][x] } }
    }

    // Level M, by version (index 0 unused).
    private val ECC_PER_BLOCK = intArrayOf(
        -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
        26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )
    private val BLOCKS = intArrayOf(
        -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
        17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )

    private fun countBits(version: Int) = if (version <= 9) 8 else 16

    /** Modules left for codewords once the function patterns are drawn. */
    private fun rawModules(version: Int): Int {
        var n = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            n -= (25 * align - 10) * align - 55
            if (version >= 7) n -= 36
        }
        return n
    }

    private fun dataCodewords(version: Int) = rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

    /** Mode, count, bytes, terminator and padding: the data codewords. */
    private fun codewords(data: ByteArray, version: Int): ByteArray {
        val capacity = dataCodewords(version) * 8
        val bits = ArrayList<Boolean>(capacity)
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
        put(0b0100, 4)
        put(data.size, countBits(version))
        for (b in data) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, capacity - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity) { put(pad, 8); pad = pad xor (0xEC xor 0x11) }
        return ByteArray(capacity / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) }.toByte() }
    }

    /** Splits into blocks, adds each block's error correction, and interleaves them. */
    private fun withEcc(data: ByteArray, version: Int): ByteArray {
        val blocks = BLOCKS[version]
        val ecc = ECC_PER_BLOCK[version]
        val raw = rawModules(version) / 8
        val shortBlocks = blocks - raw % blocks
        val shortLen = raw / blocks - ecc   // data codewords in a short block; long ones have one more
        val divisor = divisor(ecc)
        val dataParts = ArrayList<ByteArray>()
        val eccParts = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until blocks) {
            val part = data.copyOfRange(k, k + shortLen + if (i < shortBlocks) 0 else 1)
            k += part.size
            dataParts.add(part)
            eccParts.add(remainder(part, divisor))
        }
        val out = ArrayList<Byte>(raw)
        for (i in 0..shortLen) for (p in dataParts) if (i < p.size) out.add(p[i])
        for (i in 0 until ecc) for (p in eccParts) out.add(p[i])
        return out.toByteArray()
    }

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    /** The generator polynomial of [degree], highest coefficient (always 1) left out. */
    private fun divisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = mul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = mul(root, 0x02)
        }
        return result
    }

    private fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor mul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private class Grid(val version: Int) {
        val size = version * 4 + 17
        val dark = Array(size) { BooleanArray(size) }
        private val function = Array(size) { BooleanArray(size) }

        private fun set(x: Int, y: Int, on: Boolean) {
            dark[y][x] = on
            function[y][x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val align = alignmentPositions()
            val last = align.size - 1
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
            drawFormatBits(0)   // reserves the area; the real bits come with the mask
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val on = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, on)
                    set(b, a, on)
                }
            }
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                val d = maxOf(abs(dx), abs(dy))
                if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val n = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2
            val result = IntArray(n)
            result[0] = 6
            var pos = size - 7
            for (i in n - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        /** Level M (bits 00) and [mask], BCH-protected, in both copies, plus the dark module. */
        fun drawFormatBits(mask: Int) {
            val data = mask   // (M = 0b00) shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        /** The codewords' bits in the zigzag over two-module columns, right to left, skipping the timing column. */
        fun drawCodewords(codewords: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < codewords.size * 8) {
                        dark[y][x] = (codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val flip = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip) dark[y][x] = !dark[y][x]
            }
        }

        /** The standard's four penalty rules: runs, 2x2 blocks, finder look-alikes, dark balance. */
        fun penalty(): Int {
            var score = 0
            fun line(get: (Int) -> Boolean) {
                var run = 1
                for (i in 1..size) {
                    if (i < size && get(i) == get(i - 1)) run++ else {
                        if (run >= 5) score += 3 + run - 5
                        run = 1
                    }
                }
                for (i in 0..size - 11) {
                    val a = (0 until 11).all { get(i + it) == FINDER_LIKE[it] }
                    val b = (0 until 11).all { get(i + it) == FINDER_LIKE[10 - it] }
                    if (a) score += 40
                    if (b) score += 40
                }
            }
            for (y in 0 until size) line { dark[y][it] }
            for (x in 0 until size) line { dark[it][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = dark[y][x]
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) score += 3
            }
            val total = size * size
            val darkCount = dark.sumOf { row -> row.count { it } }
            val k = (abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            return score + k * 10
        }
    }

    private val FINDER_LIKE = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
}
