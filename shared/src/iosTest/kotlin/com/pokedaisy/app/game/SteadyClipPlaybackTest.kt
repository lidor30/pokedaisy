@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.FlacWriter
import com.pokedaisy.app.SteadyMusicClip
import com.pokedaisy.app.SteadyMusicPosition
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.get
import okio.FileSystem
import okio.SYSTEM
import okio.Path.Companion.toPath
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineManualRenderingModeOffline
import platform.AVFAudio.AVAudioEngineManualRenderingStatusSuccess
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class SteadyClipPlaybackTest {
    @Test
    fun nativeDecoderPlaysTheRemainingIntroAndRepeatsTheLoopInOrder() {
        val fs = FileSystem.SYSTEM
        val dir = NSTemporaryDirectory().toPath() / "steady-${NSUUID().UUIDString}"
        fs.createDirectories(dir)
        val intro = dir / "intro.flac"
        val loop = dir / "loop.flac"
        val rate = 48000
        val introSamples = ShortArray(128 * 2) { 16000 }
        val loopSamples = ShortArray(192 * 2) { if (it / 2 < 96) -8000 else 8000 }
        val engine = AVAudioEngine()
        try {
            FlacWriter.write(intro, introSamples, introSamples.size, rate)
            FlacWriter.write(loop, loopSamples, loopSamples.size, rate)
            val format = AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 2u)
            val node = AVAudioPlayerNode()
            engine.attachNode(node)
            engine.connect(node, to = engine.mainMixerNode, format = format)
            assertTrue(engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline,
                format = format, maximumFrameCount = 64u, error = null))
            assertTrue(engine.startAndReturnError(null))
            val music = SteadyClipPlayback.Prepared(SteadyMusicClip(loop, intro, 0L),
                SteadyMusicPosition(insideIntro = true, frame = 64L))
            music.play(node)
            val buffer = AVAudioPCMBuffer(format, frameCapacity = 64u)
            val samples = ArrayList<Float>()
            repeat(8) {
                assertTrue(engine.renderOffline(64u, toBuffer = buffer, error = null) == AVAudioEngineManualRenderingStatusSuccess)
                val channel = assertNotNull(assertNotNull(buffer.floatChannelData)[0])
                repeat(buffer.frameLength.toInt()) { samples += channel[it] }
            }
            val start = samples.indexOfFirst { abs(it - 16000f / 32768) < 0.01f }
            assertTrue(start >= 0, "decoded intro is inaudible")
            for (offset in 0 until 64 + 2 * 192) {
                val expected = if (offset < 64) 16000f else if ((offset - 64) % 192 < 96) -8000f else 8000f
                assertTrue(abs(samples[start + offset] - expected / 32768) < 0.01f, "sample $offset at the intro or loop seam")
            }
            node.stop()
        } finally {
            engine.stop()
            fs.deleteRecursively(dir)
        }
    }
}
