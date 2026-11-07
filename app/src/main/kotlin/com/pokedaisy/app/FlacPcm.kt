package com.pokedaisy.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * A STEADY clip as [FfMusicPlayer] streams it: a 16-bit stereo WAV. Clips are cached as FLAC ([FlacWriter]) since
 * 2026-11-01, but the player went on opening every one as a WAV and played nothing (2026-11-03: "not one of
 * FfMusicCache's WAVs" on the Thor). A FLAC is decoded once with Android's own decoder (lossless, so the loop stays
 * sample-exact) into the app's cache dir, where the last few stay for the next time FF comes on.
 */
internal object FlacPcm {
    private const val KEEP = 6
    private const val TIMEOUT_US = 10_000L

    /** [clip] itself if it's a WAV, else its decoded WAV (made now if it isn't there yet). On a worker thread. */
    fun wavFor(clip: File): File {
        if (!clip.name.endsWith(".flac")) return clip
        val dir = File(System.getProperty("java.io.tmpdir") ?: clip.parent, "ffmusic-pcm").apply { mkdirs() }
        // Keyed by the ROM's folder, the file and its size / time: a clip recorded again gets decoded again.
        val out = File(dir, "${clip.parentFile?.name}-${clip.nameWithoutExtension}-${clip.length()}-${clip.lastModified()}.wav")
        if (out.isFile && out.length() >= WAV_HEADER) return out.also { it.setLastModified(System.currentTimeMillis()) }
        val tmp = File(dir, out.name + ".tmp")
        decode(clip, tmp)
        check(tmp.renameTo(out)) { "couldn't keep ${out.name}" }
        dir.listFiles()?.filter { it.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        return out
    }

    private fun decode(flac: File, wav: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(flac.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) { "not a stereo clip" }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            RandomAccessFile(wav, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(WAV_HEADER))
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var chunk = ByteArray(0)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            if (chunk.size < info.size) chunk = ByteArray(info.size)
                            buf.get(chunk, 0, info.size)
                            out.write(chunk, 0, info.size)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                val data = out.length() - WAV_HEADER
                out.seek(0)
                out.write(header(rate, data))
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private const val WAV_HEADER = 44

    /** A 16-bit stereo PCM WAV header for [data] bytes of samples at [rate] (as FfMusicCache's WAVs were). */
    private fun header(rate: Int, data: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(WAV_HEADER).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(data.toInt())
        return b.array()
    }
}
