@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.GameSoundFiles
import com.pokedaisy.app.RenderedGameSound
import com.pokedaisy.app.game.GameViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSURL

/** Play the same rendered cartridge click as Android. The recording worker never plays live UI audio. */
internal class IosGameClickSound(private val game: GameViewController, romCrc: String) : RenderedGameSound {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val files = GameSoundFiles(appFilesDir(), romCrc, "select")
    private var loading: Job? = null
    private var players = emptyList<AVAudioPlayer>()
    private var next = 0

    init { load() }

    override val hasOwn: Boolean get() = files.hasOwn

    override fun write(pcm: ShortArray, len: Int, sampleRate: Int) {
        if (files.write(pcm, len, sampleRate)) scope.launch { load() }
    }

    fun play() {
        if (game.muted || game.volume <= 0f || players.isEmpty()) return
        val player = players[next]
        next = (next + 1) % players.size
        player.volume = game.volume
        player.currentTime = 0.0
        player.play()
    }

    fun close() {
        scope.cancel()
        replace(emptyList())
    }

    private fun load() {
        loading?.cancel()
        loading = scope.launch {
            val ready = withContext(Dispatchers.IO) {
                val path = files.available() ?: return@withContext emptyList()
                runCatching {
                    val url = NSURL.fileURLWithPath(path.toString())
                    List(2) { AVAudioPlayer(contentsOfURL = url, error = null) }
                }.getOrDefault(emptyList())
            }
            replace(ready)
        }
    }

    private fun replace(ready: List<AVAudioPlayer>) {
        players.forEach { it.stop() }
        players = ready
        next = 0
    }
}
