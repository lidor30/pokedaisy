package com.pokedaisy.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

/** [FlacWriter] round trips: a small FLAC decoder (the subset FLAC itself defines, LPC aside) reads every sample back,
 * checking both CRCs per frame, the STREAMINFO's MD5 and the SEEKTABLE. */
class FlacWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun roundTrip(pcm: ShortArray, rate: Int = 48000, from: Int = 0, len: Int = pcm.size): Decoded {
        val f = tmp.newFile("clip.flac")
        FlacWriter.write(f, pcm, len, rate, from)
        val d = decode(f.readBytes())
        assertEquals(rate, d.sampleRate)
        assertArrayEquals(pcm.copyOfRange(from, from + len), d.pcm)
        return d
    }

    @Test fun chipMusic() {
        val d = roundTrip(chipMusic(48000 * 10))
        // Music this regular comes out well under a WAV's size.
        assertTrue("${d.bytes} bytes", d.bytes < 48000 * 10 * 4 * 6 / 10)
    }

    @Test fun silenceIsTiny() {
        val d = roundTrip(ShortArray(48000 * 2 * 5))
        assertTrue("${d.bytes} bytes", d.bytes < 4000)
    }

    @Test fun extremesAndNoise() {
        // Full-scale opposites make the side channel need all 17 bits; noise leaves nothing to predict.
        val rnd = java.util.Random(7)
        val pcm = ShortArray(48000 * 2) { i ->
            when ((i / 2) / 5000 % 3) {
                0 -> if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
                1 -> if ((i / 2) % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE
                else -> rnd.nextInt(65536).toShort()
            }
        }
        roundTrip(pcm)
    }

    @Test fun oddLengthsAndOffsets() {
        val music = chipMusic(20000)
        for (samples in listOf(0, 1, 2, 3, 5, 17, 255, 256, 257, 4095, 4096, 4097, 8192 + 100)) {
            tmp.root.listFiles()?.forEach { it.delete() }
            roundTrip(music, from = 2 * 31, len = 2 * samples)
        }
    }

    @Test fun otherRates() {
        for (rate in listOf(44100, 32768)) {
            tmp.root.listFiles()?.forEach { it.delete() }
            roundTrip(chipMusic(10000), rate = rate)
        }
    }

    @Test fun seekTablePointsAtFrames() {
        val d = roundTrip(chipMusic(48000 * 4))
        assertTrue(d.seekPoints.isNotEmpty())
        for ((sample, offset) in d.seekPoints) assertEquals(sample, d.frameStarts[offset])
    }

    @Test fun oldWavsConvert() {
        // FfMusicCache.prepare's path: a clip written as WAV before FLAC, read back and written again.
        val pcm = chipMusic(30000)
        val wav = tmp.newFile("old.wav")
        writeWav(wav, pcm, pcm.size, 48000)
        val (rate, read) = readWav(wav)!!
        assertEquals(48000, rate)
        assertArrayEquals(pcm, read)
        roundTrip(read)
        assertEquals(null, readWav(tmp.newFile("junk.wav").apply { writeBytes(ByteArray(100)) }))
    }

    /** Square waves, a triangle, a decaying noise channel and gaps: what the games' sound chips play. */
    private fun chipMusic(samples: Int): ShortArray {
        val rnd = java.util.Random(1)
        var noise = 0
        return ShortArray(samples * 2) { i ->
            val t = i / 2
            if ((t / 12000) % 5 == 4) return@ShortArray 0
            val sq1 = if ((t / 55) % 2 == 0) 3000 else -3000
            val sq2 = if ((t / 82) % 4 == 0) 2000 else -2000
            val tri = (sin(2 * PI * t / 109.0) * 2500).toInt()
            if (t % 8 == 0) noise = rnd.nextInt(1200) - 600
            val decay = 1.0 - (t % 6000) / 6000.0
            val s = if (i % 2 == 0) sq1 + tri + (noise * decay).toInt() else sq2 + tri - (noise * decay).toInt()
            s.coerceIn(-32768, 32767).toShort()
        }
    }

    class Decoded(
        val sampleRate: Int,
        val pcm: ShortArray,
        val bytes: Int,
        /** (sample, byte offset from the first frame) per seek point. */
        val seekPoints: List<Pair<Long, Long>>,
        /** Byte offset from the first frame -> its first sample. */
        val frameStarts: Map<Long, Long>,
    )

    private class Bits(val b: ByteArray, var pos: Long) {
        fun read(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = b[(pos ushr 3).toInt()].toInt()
                v = (v shl 1) or ((byte ushr (7 - (pos and 7).toInt())) and 1).toLong()
                pos++
            }
            return v
        }
        fun signed(n: Int): Int = (read(n) shl (64 - n) shr (64 - n)).toInt()
        fun bytePos() = (pos ushr 3).toInt()
        fun align() { pos = (pos + 7) and 7L.inv() }
    }

    private fun decode(file: ByteArray): Decoded {
        val r = Bits(file, 0)
        assertEquals(0x664C6143L, r.read(32))
        var last = false
        var rate = 0
        var total = 0L
        var md5 = ByteArray(0)
        val seek = mutableListOf<Pair<Long, Long>>()
        while (!last) {
            last = r.read(1) == 1L
            val type = r.read(7).toInt()
            val len = r.read(24).toInt()
            val end = r.bytePos() + len
            when (type) {
                0 -> {
                    assertEquals(FlacWriter.BLOCK.toLong(), r.read(16)); assertEquals(FlacWriter.BLOCK.toLong(), r.read(16))
                    r.read(24); r.read(24)
                    rate = r.read(20).toInt()
                    assertEquals(1L, r.read(3)); assertEquals(15L, r.read(5))
                    total = r.read(36)
                    md5 = ByteArray(16) { r.read(8).toByte() }
                }
                3 -> repeat(len / 18) {
                    seek += r.read(64) to r.read(64)
                    r.read(16)
                }
            }
            r.pos = end * 8L
        }
        val firstFrame = r.bytePos()
        val out = ShortArray((total * 2).toInt())
        val starts = mutableMapOf<Long, Long>()
        var done = 0L
        var index = 0
        while (done < total) {
            val start = r.bytePos()
            starts[(start - firstFrame).toLong()] = done
            assertEquals(0xFFF8L, r.read(16))
            val blockCode = r.read(4).toInt()
            val rateCode = r.read(4).toInt()
            val chan = r.read(4).toInt()
            assertEquals(4L, r.read(3)); assertEquals(0L, r.read(1))
            assertEquals(index++, utf8(r))
            val n = when (blockCode) {
                12 -> 4096
                6 -> r.read(8).toInt() + 1
                7 -> r.read(16).toInt() + 1
                else -> error("block code $blockCode")
            }
            if (rateCode == 0) Unit else assertEquals(FlacWriterRates[rate], rateCode)
            val crc = r.read(8).toInt()
            assertEquals(crc8(file, start, r.bytePos() - 1), crc)
            val bps0 = if (chan == 9) 17 else 16
            val bps1 = if (chan == 8 || chan == 10) 17 else 16
            val a = subframe(r, n, bps0)
            val b = subframe(r, n, bps1)
            r.align()
            val crcEnd = r.bytePos()
            assertEquals(crc16(file, start, crcEnd), r.read(16).toInt())
            for (i in 0 until n) {
                val (lft, rgt) = when (chan) {
                    1 -> a[i] to b[i]
                    8 -> a[i] to a[i] - b[i]
                    9 -> a[i] + b[i] to b[i]
                    10 -> { val m = (a[i] shl 1) or (b[i] and 1); ((m + b[i]) shr 1) to ((m - b[i]) shr 1) }
                    else -> error("channels $chan")
                }
                out[((done + i) * 2).toInt()] = lft.toShort()
                out[((done + i) * 2 + 1).toInt()] = rgt.toShort()
            }
            done += n
        }
        assertEquals(file.size, r.bytePos())
        val bytes = ByteArray(out.size * 2)
        out.forEachIndexed { i, s -> bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(bytes), md5)
        return Decoded(rate, out, file.size, seek, starts)
    }

    private fun utf8(r: Bits): Int {
        val first = r.read(8).toInt()
        if (first < 0x80) return first
        var extra = 0
        while ((first shl (extra + 1)) and 0x80 != 0) extra++
        var v = first and (0x7F shr (extra + 1))
        repeat(extra) { v = (v shl 6) or (r.read(8).toInt() and 0x3F) }
        return v
    }

    private fun subframe(r: Bits, n: Int, bps: Int): IntArray {
        assertEquals(0L, r.read(1))
        val type = r.read(6).toInt()
        assertEquals(0L, r.read(1))
        val x = IntArray(n)
        when {
            type == 0 -> x.fill(r.signed(bps))
            type == 1 -> for (i in 0 until n) x[i] = r.signed(bps)
            type in 8..12 -> {
                val order = type - 8
                for (i in 0 until order) x[i] = r.signed(bps)
                val method = r.read(2).toInt()
                val paramBits = if (method == 0) 4 else 5
                val po = r.read(4).toInt()
                var i = order
                for (j in 0 until (1 shl po)) {
                    val k = r.read(paramBits).toInt()
                    val count = (n shr po) - if (j == 0) order else 0
                    if (k == (1 shl paramBits) - 1) {
                        val raw = r.read(5).toInt()
                        repeat(count) { x[i++] = if (raw == 0) 0 else r.signed(raw) }
                    } else repeat(count) {
                        var q = 0
                        while (r.read(1) == 0L) q++
                        val u = (q shl k) or r.read(k).toInt()
                        x[i++] = (u ushr 1) xor -(u and 1)
                    }
                }
                for (t in order until n) x[t] += when (order) {
                    0 -> 0
                    1 -> x[t - 1]
                    2 -> 2 * x[t - 1] - x[t - 2]
                    3 -> 3 * x[t - 1] - 3 * x[t - 2] + x[t - 3]
                    else -> 4 * x[t - 1] - 6 * x[t - 2] + 4 * x[t - 3] - x[t - 4]
                }
            }
            else -> error("subframe type $type")
        }
        return x
    }

    private companion object {
        val FlacWriterRates = mapOf(48000 to 10, 44100 to 9, 32000 to 8)
    }
}
