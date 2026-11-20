package com.pokedaisy.app

import okio.Path

/**
 * The on-device recording that follows the current song through fast-forward and SMART's 1× stretches.
 * [songStartedAt] is on the host's monotonic clock so playback can resume partway through the intro or loop.
 */
data class SteadyMusicClip(val loop: Path, val intro: Path?, val songStartedAt: Long)

/** The same song, cache ownership and pending-vs-fallback decisions on both hosts. Frame thread only. */
class SteadyMusic(private val cache: FfMusicCache, private val nowMillis: () -> Long) {
    private var songKey: String? = null
    private var songStartedAt = 0L
    private var requested: String? = null

    var clip: SteadyMusicClip? = null
        private set
    var awaitingClip = false
        private set

    fun observe(key: String?, steadyEnabled: Boolean, wantsFf: Boolean, request: (String) -> Unit, willRender: (String) -> Boolean) {
        if (key != null && key != songKey) {
            songKey = key
            songStartedAt = nowMillis()
        }
        if (!steadyEnabled) requested = null
        else if (key != null && key != requested) {
            requested = key
            cache.notePlayed(key)
            if (!cache.complete(key)) request(key)
        }
        val loop = if (wantsFf && steadyEnabled && key != null) cache.fileIfCached(key) else null
        if (loop != clip?.loop) {
            clip = if (loop != null && key != null) SteadyMusicClip(loop, cache.introIfCached(key), songStartedAt) else null
        }
        awaitingClip = wantsFf && steadyEnabled && loop == null && key != null && willRender(key)
    }

    fun holdFor(millis: Long) {
        if (songKey == null) return
        songStartedAt += millis.coerceAtLeast(0L)
        clip = clip?.copy(songStartedAt = songStartedAt)
    }
}

/** Where a steady clip starts when FF begins midway through a song. Frames use the clip's original sample rate. */
class SteadyMusicPosition(val insideIntro: Boolean, val frame: Long) {
    companion object {
        fun at(elapsedMillis: Long, rate: Int, introFrames: Long, loopFrames: Long): SteadyMusicPosition {
            val frames = elapsedMillis.coerceAtLeast(0L) * rate / 1000L
            return if (frames < introFrames) SteadyMusicPosition(true, frames)
            else SteadyMusicPosition(false, (frames - introFrames) % loopFrames.coerceAtLeast(1L))
        }
    }
}
