package com.pokedaisy.app

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * One of the game's own sounds, rendered from the ROM: the menu click
 * (SE_SELECT, [CLICK]) the companion's buttons play (companion/ui/ClickSound.kt's
 * LocalClickSound), and the level-up fanfare ([FANFARE]) a RetroAchievements
 * unlock plays. Nothing is bundled: [FfMusicRenderer] renders each once per
 * ROM on its render core and hands it to [write]; it's kept as
 * `sfx/<crc>-<name>.wav`. A ROM whose sound engine the renderer can't drive
 * (pokeemerald-expansion hacks) borrows the newest one another game rendered.
 */
class GameClickSound(filesDir: File, romCrc: String, name: String = CLICK) : RenderedGameSound {
    private val files = GameSoundFiles(filesDir.toOkioPath(), romCrc, name)
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

    override val hasOwn: Boolean get() = files.hasOwn

    private fun load() {
        val clip = files.available() ?: return
        runCatching { pool.load(clip.toString(), 1) }  // Late render after release is safe.
    }

    override fun write(pcm: ShortArray, len: Int, sampleRate: Int) {
        if (files.write(pcm, len, sampleRate)) load()
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
    }
}
