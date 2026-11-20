@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.FfMusicCache
import com.pokedaisy.app.MusicRenderHost
import com.pokedaisy.app.RenderedGameSound
import com.pokedaisy.app.SongRenderer
import com.pokedaisy.app.SteadyMusic
import com.pokedaisy.app.SteadyMusicSource
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.data.InProcessReader
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.RomIdentityReader
import com.pokedaisy.app.core.openSoundRenderCore
import com.pokedaisy.app.game.GameViewController
import com.pokedaisy.app.game.steadyClockMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/** Retires the worker with its game and supplies iOS storage and the native player's song observations. */
internal class IosSteadyMusic(
    private val game: GameViewController, private val rom: Path, private val crc: String, private val gba: Boolean,
    private val click: RenderedGameSound,
) {
    private class Active(val owner: SteadyMusic, val worker: SongRenderer, val source: SteadyMusicSource)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var active: Active? = null
    private var initialized = false
    private val needsClick = gba && !click.hasOwn
    private var pauseStarted: Long? = null

    fun frame(mode: FfMusicMode, wantsFf: Boolean, clickOn: Boolean) {
        val steady = mode == FfMusicMode.STEADY
        if (!initialized && (steady || clickOn && needsClick)) {
            initialized = true
            scope.launch {
                active = withContext(Dispatchers.IO) {
                    runCatching { prepare(steady) }.onFailure { Logs.i("FF music: ${it.message}") }.getOrNull()
                }?.also { it.worker.start() }
            }
        }
        val pipe = active
        if (pipe == null) return
        val key = if (steady) pipe.source.current(InProcessReader) else null
        pipe.owner.observe(key, steady, wantsFf, pipe.worker::request, pipe.worker::willRender)
        game.steadyClip = pipe.owner.clip
        game.steadyAwaiting = pipe.owner.awaitingClip
    }

    fun pause(paused: Boolean) {
        val now = steadyClockMillis()
        if (paused) pauseStarted = now else {
            pauseStarted?.let { active?.owner?.holdFor(now - it) }
            pauseStarted = null
        }
    }

    fun close() {
        scope.cancel()
        active?.worker?.stop()
        active = null
        game.steadyClip = null
        game.steadyAwaiting = false
    }

    private fun prepare(prefetch: Boolean): Active? {
        val source = SteadyMusicSource.forCartridge(gba) { RomIdentityReader.forPath(rom).sha1() } ?: return null
        val caches = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()).toPath()
        val cache = FfMusicCache(caches / "PokeDaisy", crc, gameBoy = !gba,
            freeBytes = {
                val metadata = NSFileManager.defaultManager.attributesOfFileSystemForPath(caches.toString(), error = null)
                (metadata?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
            }, log = Logs::i)
        val host = MusicRenderHost(::openSoundRenderCore, report = { message, error ->
            Logs.i(if (error == null) message else "$message: ${error.message}")
        })
        val worker = source.renderer(rom, crc, cache, host, click = click, prefetch = prefetch)
        return Active(SteadyMusic(cache, ::steadyClockMillis), worker, source)
    }
}
