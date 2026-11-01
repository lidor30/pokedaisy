package com.pokedaisy.app

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * One of the game's own sounds, rendered from the ROM: the menu click
 * (SE_SELECT, [CLICK]) the companion's buttons play (companion/ui/ClickSound.kt's
 * LocalClickSound), and the level-up fanfare ([FANFARE]) a RetroAchievements
 * unlock plays. Nothing is bundled: [FfMusicRenderer] renders each once per
 * ROM on its render core and hands it to [write]; it's kept as
 * `sfx/<crc>-<name>.wav`. A ROM whose sound engine the renderer can't drive
 * (pokeemerald-expansion hacks) borrows the newest one another game rendered.
 */
class GameClickSound(filesDir: File, romCrc: String, name: String = CLICK) {
    private val dir = File(filesDir, "sfx").apply { mkdirs() }
    private val suffix = "-$name.wav"
    private val own = File(dir, "$romCrc$suffix")
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    @Volatile private var soundId = 0

    init {
        pool.setOnLoadCompleteListener { p, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            val old = soundId
            soundId = id
            if (old != 0 && old != id) p.unload(old)
        }
        load()
    }

    /** Whether this ROM's own sound has been rendered yet. */
    val hasOwn: Boolean get() = own.isFile && own.length() > 0

    private fun load() {
        val f = own.takeIf { hasOwn }
            ?: dir.listFiles { f -> f.name.endsWith(suffix) && f.length() > 0 }?.maxByOrNull { it.lastModified() }
            ?: return
        runCatching { pool.load(f.path, 1) } // released under a late render: nothing to play anyway
    }

    /** Stores this ROM's sound ([len] samples of interleaved stereo [pcm]), silence trimmed off both ends, and plays it from now on. */
    fun write(pcm: ShortArray, len: Int, sampleRate: Int) {
        fun loud(frame: Int) = maxOf(Math.abs(pcm[frame * 2].toInt()), Math.abs(pcm[frame * 2 + 1].toInt())) > SILENCE
        val frames = len / 2
        val first = (0 until frames).firstOrNull(::loud) ?: return
        val last = (frames - 1 downTo first).first(::loud)
        writeWav(own, pcm, (last - first + 1) * 2, sampleRate, from = first * 2)
        load()
    }

    fun play() {
        val id = soundId
        // The game's own sound, so SOUND > GAME VOLUME / MUTE GAME (GameVolume) applies; muted, it isn't played.
        val g = GameVolume.gain
        if (id != 0 && g > 0f) pool.play(id, g, g, 1, 0, 1f)
    }

    fun release() = pool.release()

    companion object {
        const val CLICK = "select"
        const val FANFARE = "fanfare"
        private const val SILENCE = 8 // |sample| at or below this counts as silence
    }
}
