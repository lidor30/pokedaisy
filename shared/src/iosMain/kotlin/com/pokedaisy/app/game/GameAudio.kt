@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import platform.AVFAudio.*
import platform.Foundation.NSLog

/**
 * The game's sound on the device: AVAudioEngine pulls it from [ring] on its own thread, interleaved 16-bit stereo at
 * [PkApiCore.SAMPLE_RATE], which the game's thread fills after each frame ([push]). Mixed with other apps' sound and
 * quiet with the silent switch, like any game.
 */
class GameAudio {
    /** About 85 ms: room for a few frames' sound while the display's refreshes and the game's frames drift. */
    val ring = AudioRing(4096)
    private val scratch = ShortArray(8192)
    private var engine: AVAudioEngine? = null
    private var node: AVAudioSourceNode? = null

    /** Game loudness under the device volume, including mute and quiet automated presses. */
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            engine?.mainMixerNode?.outputVolume = field
        }

    fun start() {
        if (engine != null) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryAmbient, error = null)
        session.setPreferredSampleRate(PkApiCore.SAMPLE_RATE.toDouble(), error = null)
        session.setPreferredIOBufferDuration(0.01, error = null)
        session.setActive(true, error = null)

        val format = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = PkApiCore.SAMPLE_RATE.toDouble(),
            channels = 2u,
            interleaved = true,
        )
        val ring = ring
        // The device's thread: reads the ring only (nothing allocated, nothing waited on).
        val node = AVAudioSourceNode(format) { _, _, frameCount, data ->
            val buffers = data?.pointed
            if (buffers != null && buffers.mNumberBuffers > 0u) {
                val out = buffers.mBuffers[0].mData?.reinterpret<ShortVar>()
                if (out != null) ring.read(out, frameCount.toInt())
            }
            0
        }
        val engine = AVAudioEngine()
        engine.attachNode(node)
        engine.connect(node, to = engine.mainMixerNode, format = format)
        engine.mainMixerNode.outputVolume = volume
        engine.prepare()
        if (!engine.startAndReturnError(null)) NSLog("PokeDaisy: the game's sound didn't start")
        this.engine = engine
        this.node = node
    }

    /** The last frame's sound into the ring (the game's thread, after [PkApiCore.runFrame]). */
    fun push() {
        val n = PkApiCore.audio(scratch)
        if (n > 0) ring.write(scratch, n)
    }

    fun stop() {
        val engine = engine ?: return
        engine.stop()
        node?.let { engine.detachNode(it) }
        this.engine = null
        node = null
        ring.clear()
    }

    /** Stops the sound and frees the ring; the object is done with. */
    fun release() {
        stop()
        ring.free()
    }
}
