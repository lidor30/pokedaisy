package com.pokedaisy.app

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays a cached FF background-music clip (see FfMusicCache — nothing
 * bundled, rendered on-device from the player's ROM) while the emulator
 * itself stays muted during fast-forward: the song's intro once, then its
 * loop forever, in one stream (the loop starts exactly where the intro ends).
 * Driven by [EmulatorEngine.onFfMusicChanged], which only fires when the
 * desired clip actually changes — call [setClip] on the UI thread.
 *
 * Through an AudioTrack built like the game's own (USAGE_GAME, low latency), not a MediaPlayer: a MediaPlayer plays
 * as media on the deep-buffer output, where the AYN Thor's settings app runs a system-wide equalizer that also takes
 * over its volume - the clips came out louder and harsher than the game they stand in for (most of all a Game Boy
 * game's square waves). The game's track goes to the primary output, which has no effects; so does this one - unless
 * SOUND > AUDIO EFFECTS sends both to the media output, for the player's own equalizer (AudioEffectsTrack).
 */
class FfMusicPlayer {

    /**
     * [loop] repeats forever after [intro] (null for a clip recorded before
     * intros were kept: loop only). [songStartedAt] (elapsedRealtime) is when
     * the game started the song, so playback picks up where the song is by
     * now: from the top for a song that just started, further on when FF
     * comes on mid-song or comes back after a menu or a moment off.
     */
    class Clip(val loop: File, val intro: File?, val songStartedAt: Long)

    private var streamer: Streamer? = null
    private var current: File? = null

    /** SOUND > GAME VOLUME / MUTE GAME (GameVolume), on the track already going too. The activity keeps one player
     *  for its life ([release] only stops it), so the listener stays. */
    private val onVolume: () -> Unit = { streamer?.applyVolume() }
    init { GameVolume.addListener(onVolume) }

    /** clip == null stops playback (leaving FF, or nothing cached yet for this song). */
    fun setClip(clip: Clip?) {
        if (clip?.loop == current && (clip == null || streamer != null)) return
        stop()
        current = clip?.loop
        if (clip == null) return
        streamer = runCatching { Streamer(clip).also { it.start() } }.onFailure {
            Log.w("pokedaisy", "ffmusic: couldn't play ${clip.loop.name}", it)
        }.getOrNull()
    }

    private fun stop() {
        streamer?.finish()
        streamer = null
    }

    fun release() {
        stop()
        current = null
    }

    /**
     * One clip's playback: its own thread writing the WAVs' samples into the track, blocking, so the track paces it. The
     * clip is opened there too: a FLAC is decoded first ([FlacPcm], ~0.2 s a minute of music), never on the caller's thread.
     */
    private class Streamer(private val clip: Clip) : Thread("pokedaisy-ffmusic") {
        private var loop: Wav? = null
        private var intro: Wav? = null
        private var track: AudioTrack? = null
        private val lock = Any()
        @Volatile private var running = true
        private var released = false

        fun applyVolume() = synchronized(lock) { if (!released) runCatching { track?.setVolume(GameVolume.gain) } }

        fun finish() {
            running = false
            // A blocking write returns once the track is paused; the thread releases it.
            synchronized(lock) { if (!released) runCatching { track?.pause(); track?.flush() } }
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val loop = Wav(FlacPcm.wavFor(clip.loop)).also { loop = it }
                val intro = clip.intro?.let { runCatching { Wav(FlacPcm.wavFor(it)) }.getOrNull() }
                    ?.takeIf { it.frames > 0 && it.rate == loop.rate }.also { intro = it }
                val track = synchronized(lock) {
                    if (!running) return
                    buildTrack(loop.rate).also { track = it }
                }
                applyVolume()
                val atFrames = ((SystemClock.elapsedRealtime() - clip.songStartedAt).coerceAtLeast(0L) * loop.rate / 1000L)
                val introFrames = intro?.frames ?: 0L
                track.play()
                if (intro != null && atFrames < introFrames) {
                    // Still in the intro: play the rest of it, then straight into the loop.
                    if (!intro.play(atFrames)) return
                    loop.repeat(0L)
                } else if (loop.frames > 0) {
                    // Without an intro the clip's own timeline starts at the song's start (near enough): just past
                    // the intro, at the same place in the loop.
                    loop.repeat((atFrames - introFrames) % loop.frames)
                }
            } catch (e: Exception) {
                if (running) Log.w("pokedaisy", "ffmusic: playback of ${clip.loop.name} stopped", e)
            } finally {
                synchronized(lock) {
                    released = true
                    track?.let { t -> runCatching { t.stop() }; t.release() }
                }
                intro?.close()
                loop?.close()
            }
        }

        private val buf = ByteArray(CHUNK_FRAMES * 4)

        /** Writes [wav] from frame [from] to its end; false once stopped. */
        private fun Wav.play(from: Long): Boolean {
            seek(from)
            while (running) {
                val n = read(buf)
                if (n <= 0) return running
                var off = 0
                while (off < n) {
                    val w = track!!.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0 || !running) return false
                    off += w
                }
            }
            return false
        }

        /** Writes [this] from frame [from], then from its start again, until stopped. */
        private fun Wav.repeat(from: Long) {
            var at = from
            while (play(at)) at = 0L
        }

        private fun buildTrack(rate: Int): AudioTrack {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4096)
            return AudioTrack.Builder()
                .setAudioAttributes(AudioEffectsTrack.attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf * 4, rate / 10 * 4))   // ~100 ms: the writer is the only work here
                .setTransferMode(AudioTrack.MODE_STREAM)
                .let(AudioEffectsTrack::mode)
                .build()
        }
    }

    /** A clip as [writeWav] writes it: 16-bit stereo PCM, its samples from byte [DATA]. */
    private class Wav(file: File) {
        private val raf = RandomAccessFile(file, "r")
        val rate: Int
        val frames: Long

        init {
            val head = ByteArray(DATA)
            raf.readFully(head)
            require(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 36, 4, Charsets.US_ASCII) == "data") {
                "not one of FfMusicCache's WAVs"
            }
            rate = le32(head, 24)
            frames = (raf.length() - DATA) / 4
        }

        fun seek(frame: Long) = raf.seek(DATA + frame.coerceIn(0L, frames) * 4)

        fun read(into: ByteArray): Int = raf.read(into)

        fun close() = runCatching { raf.close() }

        private fun le32(b: ByteArray, i: Int) =
            (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

        companion object {
            const val DATA = 44
        }
    }

    private companion object {
        const val CHUNK_FRAMES = 2048
    }
}
