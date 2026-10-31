package com.pokedaisy.app

import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Plays a cached FF background-music clip (see FfMusicCache — nothing
 * bundled, rendered on-device from the player's ROM) while the emulator
 * itself stays muted during fast-forward: the song's intro once, then its
 * loop forever, handed over gaplessly (MediaPlayer.setNextMediaPlayer; the
 * loop starts exactly where the intro ends). Driven by
 * [EmulatorEngine.onFfMusicChanged], which only fires when the desired
 * clip actually changes — call [setClip] on the UI thread.
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

    private var introPlayer: MediaPlayer? = null
    private var loopPlayer: MediaPlayer? = null
    private var current: File? = null

    /** SOUND > GAME VOLUME / MUTE GAME (GameVolume), on the players already going too: posted to the UI thread. The
     *  activity keeps one player for its life ([release] only stops it), so the listener stays. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val onVolume: () -> Unit = { main.post { applyVolume() } }
    init { GameVolume.addListener(onVolume) }

    private fun applyVolume() {
        val g = GameVolume.gain
        for (p in listOfNotNull(introPlayer, loopPlayer)) runCatching { p.setVolume(g, g) }
    }

    /** clip == null stops playback (leaving FF, or nothing cached yet for this song). */
    fun setClip(clip: Clip?) {
        if (clip?.loop == current && (clip == null || introPlayer != null || loopPlayer != null)) return
        stop()
        current = clip?.loop
        if (clip == null) return
        runCatching { start(clip) }.onFailure {
            Log.w("pokedaisy", "ffmusic: couldn't play ${clip.loop.name}", it)
            stop()
        }
    }

    private fun start(clip: Clip) {
        val at = (SystemClock.elapsedRealtime() - clip.songStartedAt).coerceAtLeast(0L)
        val loop = MediaPlayer().apply {
            setDataSource(clip.loop.absolutePath)
            isLooping = true
            prepare()
        }
        loopPlayer = loop
        loop.setVolume(GameVolume.gain, GameVolume.gain)
        val intro = clip.intro?.let { f ->
            runCatching {
                MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    setVolume(GameVolume.gain, GameVolume.gain)
                }
            }.getOrNull()
        }?.takeIf { it.duration > 0 }
        val introMs = intro?.duration?.toLong() ?: 0L
        if (intro != null && at < introMs) {
            // Still in the intro: play the rest of it, then straight into the loop.
            introPlayer = intro
            if (at > 0) intro.seekTo(at.toInt())
            intro.setNextMediaPlayer(loop)
            intro.setOnCompletionListener { p -> if (introPlayer === p) { p.release(); introPlayer = null } }
            intro.start()
        } else {
            intro?.release()
            val loopMs = loop.duration.toLong()
            // Without an intro the clip's own timeline starts at the song's start
            // (near enough): just past the intro, at the same place in the loop.
            if (loopMs > 0) {
                val into = ((at - introMs) % loopMs).toInt()
                if (into > 0) loop.seekTo(into)
            }
            loop.start()
        }
    }

    private fun stop() {
        introPlayer?.let { runCatching { it.setNextMediaPlayer(null) }; it.release() }
        introPlayer = null
        loopPlayer?.release()
        loopPlayer = null
    }

    fun release() {
        stop()
        current = null
    }
}
