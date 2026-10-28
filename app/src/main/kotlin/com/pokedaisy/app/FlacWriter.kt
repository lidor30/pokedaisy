package com.pokedaisy.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Writes interleaved 16-bit stereo PCM as a FLAC file, the STEADY clips' format ([FfMusicCache]): lossless and
 * sample-exact, so a clip cut at the song's own loop ([M4aLoopSplice]) still loops without a gap - AAC / Opus / MP3
 * pad the start and end of a file, which would put a gap at every seam. MediaPlayer plays it on every Android version
 * the app runs on. About half a WAV's size for the games' music (silence and held notes much less).
 *
 * Our own encoder, not MediaCodec's (whose FLAC encoder differs by device): the format's subset that pays for chip
 * music - fixed predictors (orders 0-4), partitioned Rice residuals, the four stereo modes, CONSTANT for silence,
 * VERBATIM when nothing beats it - plus a STREAMINFO with the samples' MD5 and a SEEKTABLE (MediaPlayer seeks into a
 * clip when FF comes on mid-song). `FlacWriterTest` decodes what it writes and checks every sample.
 */
internal object FlacWriter {
    /** Samples per channel in each frame (4096: the format's usual size, a code of its own in the frame header). */
    const val BLOCK = 4096

    /** Frames between two seek points: ~1 s at 48 kHz. */
    private const val SEEK_EVERY = 12

    /** Writes [len] shorts of [pcm] from [from] (interleaved stereo, so [len] / 2 samples per channel) to [file],
     * through a temp file so a half-written one is never read as valid. */
    fun write(file: File, pcm: ShortArray, len: Int, sampleRate: Int, from: Int = 0) {
        val samples = len / 2
        val frames = (samples + BLOCK - 1) / BLOCK
        val seekPoints = (frames + SEEK_EVERY - 1) / SEEK_EVERY
        val headerSize = 4 + 4 + STREAMINFO_SIZE + if (seekPoints > 0) 4 + SEEK_POINT_SIZE * seekPoints else 0
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val md5 = MessageDigest.getInstance("MD5")
        val frameOffsets = LongArray(frames)
        var minFrame = 0
        var maxFrame = 0
        val enc = FrameEncoder(sampleRate)
        BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { out ->
            out.write(ByteArray(headerSize))   // filled in below, once the frames' sizes are known
            var offset = 0L
            for (f in 0 until frames) {
                val first = f * BLOCK
                val size = enc.encode(pcm, from + first * 2, minOf(BLOCK, samples - first), f, md5)
                out.write(enc.out.buf, 0, size)
                frameOffsets[f] = offset
                offset += size
                minFrame = if (f == 0) size else minOf(minFrame, size)
                maxFrame = maxOf(maxFrame, size)
            }
        }
        val header = BitWriter(headerSize)
        header.write(0x664C6143, 32)   // "fLaC"
        header.write(if (seekPoints > 0) 0 else 1, 1)   // last metadata block?
        header.write(0, 7)   // STREAMINFO
        header.write(STREAMINFO_SIZE, 24)
        header.write(BLOCK, 16)
        header.write(BLOCK, 16)
        header.write(minFrame, 24)
        header.write(maxFrame, 24)
        header.write(sampleRate, 20)
        header.write(1, 3)   // channels - 1
        header.write(15, 5)   // bits per sample - 1
        header.write((samples.toLong() ushr 32).toInt(), 4)
        header.write(samples, 32)
        for (b in md5.digest()) header.write(b.toInt() and 0xFF, 8)
        if (seekPoints > 0) {
            header.write(1, 1)
            header.write(3, 7)   // SEEKTABLE
            header.write(SEEK_POINT_SIZE * seekPoints, 24)
            for (p in 0 until seekPoints) {
                val f = p * SEEK_EVERY
                val sample = f.toLong() * BLOCK
                header.write((sample ushr 32).toInt(), 32); header.write(sample.toInt(), 32)
                header.write((frameOffsets[f] ushr 32).toInt(), 32); header.write(frameOffsets[f].toInt(), 32)
                header.write(minOf(BLOCK, samples - f * BLOCK), 16)
            }
        }
        check(header.pos == headerSize)
        RandomAccessFile(tmp, "rw").use { it.write(header.buf, 0, headerSize) }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("couldn't move ${tmp.name} into place")
        }
    }

    private const val STREAMINFO_SIZE = 34
    private const val SEEK_POINT_SIZE = 18
}

/** One frame at a time: picks each channel's cheapest coding and the stereo mode, writes the frame into [out]. */
private class FrameEncoder(private val sampleRate: Int) {
    // A frame's worst case: VERBATIM, a 16-bit and a 17-bit (side) channel, plus headers.
    val out = BitWriter(FlacWriter.BLOCK * 33 / 8 + 64)
    private val left = IntArray(FlacWriter.BLOCK)
    private val right = IntArray(FlacWriter.BLOCK)
    private val mid = IntArray(FlacWriter.BLOCK)
    private val side = IntArray(FlacWriter.BLOCK)
    private val residual = IntArray(FlacWriter.BLOCK)
    private val pcmBytes = ByteArray(FlacWriter.BLOCK * 4)
    private val plans = Array(4) { Plan() }
    private val sums = LongArray(1 shl MAX_PARTITION_ORDER)

    /** Encodes [n] samples per channel of [pcm] from [at] as frame [index]; returns its size in bytes. */
    fun encode(pcm: ShortArray, at: Int, n: Int, index: Int, md5: MessageDigest): Int {
        for (i in 0 until n) {
            val l = pcm[at + 2 * i].toInt()
            val r = pcm[at + 2 * i + 1].toInt()
            left[i] = l; right[i] = r
            mid[i] = (l + r) shr 1
            side[i] = l - r
            pcmBytes[4 * i] = l.toByte(); pcmBytes[4 * i + 1] = (l shr 8).toByte()
            pcmBytes[4 * i + 2] = r.toByte(); pcmBytes[4 * i + 3] = (r shr 8).toByte()
        }
        md5.update(pcmBytes, 0, n * 4)
        val (pl, pr, pm, ps) = plans
        plan(left, n, 16, pl); plan(right, n, 16, pr); plan(mid, n, 16, pm); plan(side, n, 17, ps)
        // Independent, left / side, right / side, mid / side: the cheapest pair (FLAC's channel assignments 1, 8-10).
        val costs = longArrayOf(pl.bits + pr.bits, pl.bits + ps.bits, ps.bits + pr.bits, pm.bits + ps.bits)
        val mode = costs.indices.minByOrNull { costs[it] }!!

        out.reset()
        out.write(0xFFF8, 16)   // sync code, fixed block size
        val blockCode = when {
            n == FlacWriter.BLOCK -> 12
            n <= 256 -> 6
            else -> 7
        }
        out.write(blockCode, 4)
        out.write(rateCode(sampleRate), 4)
        out.write(intArrayOf(1, 8, 9, 10)[mode], 4)
        out.write(4, 3)   // 16 bits per sample
        out.write(0, 1)
        writeUtf8(index)
        if (blockCode == 6) out.write(n - 1, 8) else if (blockCode == 7) out.write(n - 1, 16)
        out.write(crc8(out.buf, 0, out.pos), 8)
        when (mode) {
            0 -> { writeSubframe(left, n, 16, pl); writeSubframe(right, n, 16, pr) }
            1 -> { writeSubframe(left, n, 16, pl); writeSubframe(side, n, 17, ps) }
            2 -> { writeSubframe(side, n, 17, ps); writeSubframe(right, n, 16, pr) }
            else -> { writeSubframe(mid, n, 16, pm); writeSubframe(side, n, 17, ps) }
        }
        out.alignToByte()
        out.write(crc16(out.buf, 0, out.pos), 16)
        return out.pos
    }

    /** How a channel is coded and what it costs (an upper bound: Rice sizes are estimated from sums). */
    private class Plan {
        var kind = VERBATIM
        var order = 0
        var partitionOrder = 0
        var wideParams = false
        val params = IntArray(1 shl MAX_PARTITION_ORDER)
        var bits = 0L
    }

    private fun plan(x: IntArray, n: Int, bps: Int, p: Plan) {
        if ((1 until n).all { x[it] == x[0] }) {
            p.kind = CONSTANT
            p.bits = 8L + bps
            return
        }
        p.kind = VERBATIM
        p.bits = 8L + n.toLong() * bps
        // The fixed order with the smallest residuals (summed from the same sample for every order, as libFLAC does).
        val maxOrder = minOf(4, n - 1)
        val abs = LongArray(5)
        for (i in maxOrder until n) {
            val e0 = x[i]
            val e1 = e0 - x[i - 1]
            abs[0] += kotlin.math.abs(e0).toLong()
            abs[1] += kotlin.math.abs(e1).toLong()
            if (maxOrder >= 2) {
                val e2 = e1 - (x[i - 1] - x[i - 2])
                abs[2] += kotlin.math.abs(e2).toLong()
                if (maxOrder >= 3) {
                    val e3 = e2 - (x[i - 1] - 2 * x[i - 2] + x[i - 3])
                    abs[3] += kotlin.math.abs(e3).toLong()
                    if (maxOrder >= 4) abs[4] += kotlin.math.abs(e3 - (x[i - 1] - 3 * x[i - 2] + 3 * x[i - 3] - x[i - 4])).toLong()
                }
            }
        }
        val order = (0..maxOrder).minByOrNull { abs[it] }!!
        fixedResidual(x, n, order)
        // Partitions: the finest order the block allows, sums merged pairwise for the coarser ones.
        var finest = 0
        while (finest < MAX_PARTITION_ORDER && n % (1 shl (finest + 1)) == 0 && (n shr (finest + 1)) > order) finest++
        val parts = 1 shl finest
        val partLen = n shr finest
        java.util.Arrays.fill(sums, 0, parts, 0L)
        for (i in order until n) sums[i / partLen] += fold(residual[i]).toLong()
        var bestBits = Long.MAX_VALUE
        var bestOrder = 0
        for (po in finest downTo 0) {
            if (po < finest) for (j in 0 until (1 shl po)) sums[j] = sums[2 * j] + sums[2 * j + 1]
            var total = 0L
            var wide = false
            val len = n shr po
            for (j in 0 until (1 shl po)) {
                val count = if (j == 0) len - order else len
                val k = riceParam(sums[j], count)
                if (k > 14) wide = true
                total += riceBits(sums[j], count, k)
            }
            total += (1 shl po).toLong() * if (wide) 5 else 4
            if (total < bestBits) { bestBits = total; bestOrder = po }
        }
        val fixedBits = 8L + order * bps + 2 + 4 + bestBits
        if (fixedBits >= p.bits) return
        p.kind = FIXED
        p.order = order
        p.partitionOrder = bestOrder
        p.bits = fixedBits
        // The chosen partitioning's parameters (the sums were merged in place: redo them at that order).
        val len = n shr bestOrder
        java.util.Arrays.fill(sums, 0, 1 shl bestOrder, 0L)
        for (i in order until n) sums[i / len] += fold(residual[i]).toLong()
        p.wideParams = false
        for (j in 0 until (1 shl bestOrder)) {
            p.params[j] = riceParam(sums[j], if (j == 0) len - order else len)
            if (p.params[j] > 14) p.wideParams = true
        }
    }

    private fun writeSubframe(x: IntArray, n: Int, bps: Int, p: Plan) {
        when (p.kind) {
            CONSTANT -> { out.write(0, 8); out.write(x[0], bps) }
            VERBATIM -> { out.write(0b00000010, 8); for (i in 0 until n) out.write(x[i], bps) }
            else -> {
                out.write((0b001000 or p.order) shl 1, 8)
                for (i in 0 until p.order) out.write(x[i], bps)
                fixedResidual(x, n, p.order)
                out.write(if (p.wideParams) 1 else 0, 2)
                out.write(p.partitionOrder, 4)
                val len = n shr p.partitionOrder
                var i = p.order
                for (j in 0 until (1 shl p.partitionOrder)) {
                    val k = p.params[j]
                    out.write(k, if (p.wideParams) 5 else 4)
                    val end = (j + 1) * len
                    while (i < end) {
                        val u = fold(residual[i++])
                        out.writeZeros(u ushr k)
                        out.write((1 shl k) or (u and ((1 shl k) - 1)), k + 1)
                    }
                }
            }
        }
    }

    /** [x]'s residuals under fixed predictor [order], into [residual] from index [order] on. */
    private fun fixedResidual(x: IntArray, n: Int, order: Int) {
        for (i in order until n) residual[i] = when (order) {
            0 -> x[i]
            1 -> x[i] - x[i - 1]
            2 -> x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    /** The frame number, UTF-8 style (FLAC's variable-length integer). */
    private fun writeUtf8(v: Int) {
        if (v < 0x80) { out.write(v, 8); return }
        val extra = when {
            v < 0x800 -> 1
            v < 0x10000 -> 2
            v < 0x200000 -> 3
            v < 0x4000000 -> 4
            else -> 5
        }
        out.write((0xFF00 shr (extra + 1)) and 0xFF or (v ushr (6 * extra)), 8)
        for (s in extra - 1 downTo 0) out.write(0x80 or ((v ushr (6 * s)) and 0x3F), 8)
    }

    companion object {
        const val CONSTANT = 0
        const val VERBATIM = 1
        const val FIXED = 2
        const val MAX_PARTITION_ORDER = 8

        fun fold(e: Int) = (e shl 1) xor (e shr 31)

        /** The Rice parameter for [count] values summing to [sum]: the cheapest by [riceBits]. */
        fun riceParam(sum: Long, count: Int): Int {
            var best = 0
            var bestBits = Long.MAX_VALUE
            for (k in 0..30) {
                val b = riceBits(sum, count, k)
                if (b < bestBits) { bestBits = b; best = k }
            }
            return best
        }

        /** An upper bound on [count] Rice codes with parameter [k] whose values sum to [sum] (floor of the sum >= sum of floors). */
        fun riceBits(sum: Long, count: Int, k: Int) = count.toLong() * (k + 1) + (sum ushr k)

        fun rateCode(rate: Int) = when (rate) {
            88200 -> 1; 176400 -> 2; 192000 -> 3; 8000 -> 4; 16000 -> 5; 22050 -> 6; 24000 -> 7
            32000 -> 8; 44100 -> 9; 48000 -> 10; 96000 -> 11
            else -> 0   // read from STREAMINFO
        }
    }
}

/** Big-endian bits into a byte array (FLAC is MSB first). */
internal class BitWriter(capacity: Int) {
    var buf = ByteArray(capacity)
        private set
    var pos = 0
        private set
    private var acc = 0L
    private var bits = 0

    fun reset() { pos = 0; acc = 0; bits = 0 }

    /** The low [n] (<= 32) bits of [v]. */
    fun write(v: Int, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (v.toLong() and ((1L shl n) - 1))
        bits += n
        while (bits >= 8) {
            bits -= 8
            if (pos == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[pos++] = (acc ushr bits).toByte()
        }
        acc = acc and ((1L shl bits) - 1)
    }

    fun writeZeros(n: Int) {
        var left = n
        while (left > 0) {
            val take = minOf(left, 32)
            write(0, take)
            left -= take
        }
    }

    fun alignToByte() { if (bits > 0) write(0, 8 - bits) }
}

private val CRC8 = IntArray(256) { i ->
    var c = i
    repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
    c and 0xFF
}

private val CRC16 = IntArray(256) { i ->
    var c = i shl 8
    repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
    c and 0xFFFF
}

internal fun crc8(b: ByteArray, from: Int, to: Int): Int {
    var c = 0
    for (i in from until to) c = CRC8[c xor (b[i].toInt() and 0xFF)]
    return c
}

internal fun crc16(b: ByteArray, from: Int, to: Int): Int {
    var c = 0
    for (i in from until to) c = ((c shl 8) xor CRC16[(c ushr 8) xor (b[i].toInt() and 0xFF)]) and 0xFFFF
    return c
}
