package com.pokedaisy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level routing guards, like PerformanceGuardsTest. Actual EQ routing needs an Android device. */
class GameAudioTrackTest {
    private val src = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    private val appDir = File(src, "com/pokedaisy/app")
    private fun read(name: String) = File(appDir, name).readText()

    @Test
    fun `game output requests buffered media for system effects`() {
        val policy = read("GameAudioTrack.kt")
        assertTrue("use the media route that can carry the user's system EQ", ".setUsage(AudioAttributes.USAGE_MEDIA)" in policy)
        assertTrue("request deep buffering rather than the fast output", ".setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)" in policy)
        assertFalse("do not bypass system effects with a fast track", "PERFORMANCE_MODE_LOW_LATENCY" in policy)
        assertFalse("do not switch back to the Thor's game route", "USAGE_GAME" in policy)
    }

    @Test
    fun `live audio steady clips and link play share the output policy`() {
        for (name in listOf("EmulatorEngine.kt", "FfMusicPlayer.kt", "LinkActivity.kt")) {
            val source = read(name)
            assertTrue("$name must use the shared EQ-compatible policy", "GameAudioTrack.build(" in source)
            assertFalse("$name must not build a track with a different policy", "AudioTrack.Builder()" in source)
        }
    }

    @Test
    fun `shared policy keeps streaming stereo PCM and the callers buffer size`() {
        val policy = read("GameAudioTrack.kt")
        for (call in listOf(
            ".setSampleRate(sampleRate)",
            ".setEncoding(AudioFormat.ENCODING_PCM_16BIT)",
            ".setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)",
            ".setBufferSizeInBytes(bufferSizeInBytes)",
            ".setTransferMode(AudioTrack.MODE_STREAM)",
        )) assertTrue("keep $call", call in policy)
    }
}
