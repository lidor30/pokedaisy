@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.companion.data.EmulatorCore
import com.pokedaisy.app.companion.data.InProcessReader
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PkApiCore] in the iPad simulator, on linux/make_diagnostic.py's ROMs (no commercial content), which
 * shared/build.gradle.kts writes and names in PK_DIAGNOSTIC_DIR: the data code reads the running game through it.
 */
class PkApiCoreTest {
    private val dir = assertNotNull(getenv("PK_DIAGNOSTIC_DIR")?.toKString(), "PK_DIAGNOSTIC_DIR not set").trimEnd('/')

    @AfterTest
    fun close() = PkApiCore.stop()

    @Test
    fun gameBoyRunsAndIsRead() {
        val save = "$dir/pk_api_ios.sav"
        unlink(save)
        assertTrue(PkApiCore.start("$dir/diagnostic.gb", save))
        assertSame(PkApiCore, EmulatorCore.current)
        assertEquals(EmulatorCore.PLATFORM_GB, PkApiCore.platform())
        assertEquals(160, PkApiCore.videoWidth)
        assertEquals(144, PkApiCore.videoHeight)
        assertEquals(32768L, PkApiCore.romSize())
        assertEquals("", PkApiCore.romCode())
        assertContentEquals("PD-TEST-GB".encodeToByteArray(), PkApiCore.romRead(0x134, 10))
        assertNull(PkApiCore.romRead(32768 - 4, 10))

        repeat(30) { PkApiCore.runFrame() }
        // The ROM adds one to WRAM 0xC000 each frame; read the way the companion's decoders read.
        val before = InProcessReader.readCoreMemory(0xC000, 1)[0]
        val samples = ShortArray(8192)
        var heard = false
        repeat(10) {
            PkApiCore.runFrame()
            val n = PkApiCore.audio(samples)
            assertTrue(n in 1000..4000, "$n samples a frame")
            if ((0 until n).any { samples[it] != 0.toShort() }) heard = true
        }
        val after = InProcessReader.readCoreMemory(0xC000, 1)[0]
        assertEquals(10, (after - before) and 0xFF)
        assertTrue(heard, "no sound from the tone")

        PkApiCore.finishSave(120)
        PkApiCore.stop()
        assertSame(EmulatorCore.None, EmulatorCore.current)
        assertEquals(-1, PkApiCore.platform())
        assertNull(PkApiCore.readBytes(0xC000, 1))
    }

    @Test
    fun gbaRunsAndIsRead() {
        assertTrue(PkApiCore.start("$dir/diagnostic.gba", null))
        assertEquals(EmulatorCore.PLATFORM_GBA, PkApiCore.platform())
        assertEquals("BPRE", PkApiCore.romCode())
        assertContentEquals("BPRE".encodeToByteArray(), PkApiCore.readBytes(0x080000AC, 4))
        assertNull(PkApiCore.romRead(0, 4))
        repeat(10) { PkApiCore.runFrame() }
        val px = assertNotNull(PkApiCore.video()).reinterpret<ByteVar>()
        assertEquals(240, PkApiCore.videoWidth)
        assertEquals(160, PkApiCore.videoHeight)
        // Mode 3 stripes: the first two pixels differ.
        assertNotEquals((0..2).map { px[it] }, (4..6).map { px[it] })
        val thumbnail = assertNotNull(com.pokedaisy.app.companion.data.RomArt.Image.decodePng(assertNotNull(PkApiCore.stateThumbnail())))
        assertEquals(160, thumbnail.width)
        assertTrue(thumbnail.argb.all { it ushr 24 == 255 }, "state thumbnails must be opaque")
        assertNotEquals(thumbnail.argb[0], thumbnail.argb[1])
    }

    @Test
    fun audioRatesSupportSlowMotionAndFastForwardOnBothCores() {
        val samples = ShortArray(16384)
        for (rom in listOf("diagnostic.gba", "diagnostic.gb")) {
            assertTrue(PkApiCore.start("$dir/$rom", null))
            // Let the Game Boy boot and enable its LCD/audio before timing full frames.
            repeat(60) { PkApiCore.runFrame() }
            for (speed in listOf(0.25, 1.0, 2.0, 10.0)) {
                PkApiCore.setAudioRate(PkApiCore.SAMPLE_RATE / speed)
                repeat(4) { PkApiCore.runFrame() }
                // The core mixes sound in blocks, so compare a half second rather than one frame's boundary.
                var count = 0
                repeat(30) {
                    PkApiCore.runFrame()
                    count += PkApiCore.audio(samples)
                }
                val expected = PkApiCore.SAMPLE_RATE / speed / com.pokedaisy.app.game.FramePacer.FPS * 2 * 30
                assertTrue(kotlin.math.abs(count - expected) < expected * 0.01 + 128,
                    "$rom at ${speed}x: $count samples, expected $expected")
            }
            PkApiCore.stop()
        }
    }

    @Test
    fun extendedStateRestoresGameBoyMemoryThroughCinterop() {
        val state = "$dir/cinterop-state.ss"
        assertTrue(PkApiCore.start("$dir/diagnostic.gb", "$dir/cinterop-state.sav"))
        repeat(60) { PkApiCore.runFrame() }
        val before = assertNotNull(PkApiCore.readBytes(0xC000, 1))
        assertTrue(PkApiCore.saveState(state))
        assertEquals(1, PkApiCore.stateMatchesSave(state))
        repeat(7) { PkApiCore.runFrame() }
        assertTrue(PkApiCore.loadState(state))
        assertContentEquals(before, PkApiCore.readBytes(0xC000, 1))
        assertEquals(-1, PkApiCore.stateMatchesSave("$dir/missing-state"))
        kotlin.test.assertFalse(PkApiCore.loadState("$dir/missing-state"))
        unlink(state)
    }

    @Test
    fun rewindControlsCrossCinteropAndStateLoadsStartANewTimeline() {
        val state = "$dir/cinterop-rewind.ss"
        assertTrue(PkApiCore.start("$dir/diagnostic.gb", null))
        repeat(60) { PkApiCore.runFrame() }
        PkApiCore.setRewind(16)
        repeat(40) { PkApiCore.runFrame() }
        fun counter() = assertNotNull(PkApiCore.readBytes(0xC000, 1))[0].toInt() and 0xFF
        val before = counter()
        PkApiCore.setRewinding(true)
        repeat(4) { PkApiCore.runFrame() }
        assertTrue(counter() < before)
        PkApiCore.setRewinding(false)
        assertTrue(PkApiCore.saveState(state))
        val saved = counter()
        repeat(8) { PkApiCore.runFrame() }
        assertTrue(PkApiCore.loadState(state))
        PkApiCore.setRewinding(true); PkApiCore.runFrame()
        assertEquals(saved, counter()) // configured capacity retained, old history gone
        PkApiCore.setRewinding(false); PkApiCore.setRewind(0)
        PkApiCore.runFrame(); assertEquals((saved + 1) and 0xFF, counter())
        unlink(state)
    }

    @Test
    fun noGameReadsNothing() {
        PkApiCore.stop()
        assertNull(PkApiCore.readBytes(0x02000000, 4))
        assertNull(PkApiCore.romCode())
        assertEquals(0L, PkApiCore.romSize())
        assertNull(PkApiCore.video())
    }

    @Test
    fun cheatParserAndLiveReplacementCrossCinterop() {
        assertEquals("\n10", PkApiCore.checkCheat("32003000 005A\nnot-a-code", 0, ""))
        assertTrue(PkApiCore.start("$dir/diagnostic.gba", null))
        assertEquals(1, PkApiCore.applyCheats("!reset\n# diagnostic RAM\n32003000 005A\n"))
        repeat(4) { PkApiCore.runFrame() }
        assertEquals(0x5A, assertNotNull(PkApiCore.readBytes(0x02003000, 1))[0].toInt() and 0xFF)
        assertEquals(1, PkApiCore.applyCheats("!reset\n# replacement\n32003000 00A5\n"))
        PkApiCore.runFrame()
        assertEquals(0xA5, assertNotNull(PkApiCore.readBytes(0x02003000, 1))[0].toInt() and 0xFF)
        assertEquals(0, PkApiCore.applyCheats(""))
    }
    @Test
    fun fieldCallsUseTheSharedGameCallsInterface() {
        val calls: com.pokedaisy.app.companion.data.GameCalls = PkApiCore
        assertTrue(PkApiCore.start("$dir/diagnostic.gba", null))
        repeat(60) { PkApiCore.runFrame() }
        val sum = byteArrayOf(0x40, 0x18, 0x80.toByte(), 0x18, 0xC0.toByte(), 0x18, 0x70, 0x47)
        assertTrue(calls.write(0x03004000, sum))
        assertContentEquals(sum, calls.read(0x03004000, sum.size))
        assertTrue(PkApiCore.callSafe())
        assertEquals(10L, calls.call(0x03004001, 1, 2, 3, 4))
        assertTrue(calls.write(0x03004000, byteArrayOf(0, 0x20, 0xC0.toByte(), 0x43, 0x70, 0x47)))
        assertEquals(0xFFFFFFFFL, calls.call(0x03004001))
        kotlin.test.assertFalse(calls.write(0x03007FFF, byteArrayOf(1, 2)))
        kotlin.test.assertFalse(calls.write(0x100000000, byteArrayOf(1)))
        kotlin.test.assertFalse(calls.write(0x02000000, byteArrayOf()))
        kotlin.test.assertFalse(calls.gbCallAt(0x150, 0xC100, 1, 2, 3))
        PkApiCore.runFrame()
        assertTrue(PkApiCore.start("$dir/diagnostic.gb", null))
        repeat(60) { PkApiCore.runFrame() }
        kotlin.test.assertFalse(PkApiCore.callSafe())
        assertEquals(-1L, calls.call(0xC101))
        assertTrue(calls.write(0xC100, byteArrayOf(0x12, 0x34)))
        assertContentEquals(byteArrayOf(0x12, 0x34), calls.read(0xC100, 2))
        assertContentEquals("PD-TEST-GB".encodeToByteArray(), calls.rom(0x134, 10))
        PkApiCore.stop()
        assertEquals(-1L, calls.call(0x03004001))
    }

}
