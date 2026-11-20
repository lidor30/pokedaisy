@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.FfMusicCache
import com.pokedaisy.app.SteadyMusicClip
import com.pokedaisy.app.SteadyMusicPosition
import com.pokedaisy.app.core.PkApiCore
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.sizeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeBufferLoops
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.QuartzCore.CACurrentMediaTime
import platform.posix.memcpy

/** Asynchronous clip preparation and sample-ordered intro and loop playback on the same engine as the game. */
internal class SteadyClipPlayback(private val node: AVAudioPlayerNode) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var load: Job? = null
    private var requested: SteadyMusicClip? = null
    private var errorOn: SteadyMusicClip? = null

    val hasMusic: Boolean get() = requested != null && requested != errorOn

    fun select(clip: SteadyMusicClip?) {
        if (requested == clip) return
        requested = clip
        errorOn = null
        load?.cancel()
        node.stop()
        if (clip == null) return
        load = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Prepared(clip) } }
            if (requested != clip) return@launch
            runCatching { result.getOrThrow().play(node) }.onFailure { error ->
                errorOn = clip
                NSLog("PokeDaisy: couldn't play fast-forward music: %@", error.message ?: "")
            }
        }
    }

    fun close() {
        scope.cancel()
        requested = null
        node.stop()
    }

    internal class Prepared(clip: SteadyMusicClip, position: SteadyMusicPosition? = null) {
        private val loop = decode(clip.loop)
        private val head: AVAudioPCMBuffer?

        init {
            val intro = clip.intro?.let { runCatching { decode(it) }.getOrNull() }
            val offset = position ?: SteadyMusicPosition.at(
                steadyClockMillis() - clip.songStartedAt, PkApiCore.SAMPLE_RATE,
                intro?.frameLength?.toLong() ?: 0L, loop.frameLength.toLong(),
            )
            head = if (offset.insideIntro && intro != null) {
                remaining(intro, offset.frame)
            } else if (offset.frame > 0L) {
                remaining(loop, offset.frame)
            } else null
        }

        fun play(node: AVAudioPlayerNode) {
            head?.let { node.scheduleBuffer(it, completionHandler = null) }
            node.scheduleBuffer(loop, atTime = null, options = AVAudioPlayerNodeBufferLoops, completionHandler = null)
            node.play()
        }

        private fun remaining(source: AVAudioPCMBuffer, from: Long): AVAudioPCMBuffer {
            if (from == 0L) return source
            val count = source.frameLength - from.toUInt()
            val result = AVAudioPCMBuffer(source.format, frameCapacity = count)
            val input = checkNotNull(source.floatChannelData)
            val output = checkNotNull(result.floatChannelData)
            repeat(source.format.channelCount.toInt()) { channel ->
                memcpy(checkNotNull(output[channel]), checkNotNull(input[channel]) + from,
                    count.toULong() * sizeOf<FloatVar>().toULong())
            }
            result.frameLength = count
            return result
        }

        private fun decode(path: Path): AVAudioPCMBuffer {
            val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path.toString()),
                commonFormat = AVAudioPCMFormatFloat32, interleaved = false, error = null)
            val format = file.processingFormat
            val maximum = PkApiCore.SAMPLE_RATE.toLong() * FfMusicCache.CAPTURE_SECONDS
            check(file.length in 1L..maximum && format.sampleRate == PkApiCore.SAMPLE_RATE.toDouble() && format.channelCount == 2u) {
                "music clip format doesn't match the game audio engine"
            }
            // AVAudioFile cannot seek to arbitrary offsets in FLAC, so keep seeking in decoded samples.
            val pcm = AVAudioPCMBuffer(format, frameCapacity = file.length.toUInt())
            check(file.readIntoBuffer(pcm, error = null) && pcm.frameLength > 0u) { "empty music clip" }
            return pcm
        }
    }
}

fun steadyClockMillis(): Long = (CACurrentMediaTime() * 1000.0).toLong()
