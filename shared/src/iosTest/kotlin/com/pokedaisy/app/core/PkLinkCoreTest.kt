@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import kotlinx.cinterop.toKString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.FileSystem
import okio.SYSTEM
import platform.posix.getenv

class PkLinkCoreTest {
    @Test fun twoCartridgesRenderTheirOwnFramesAndDrainAudio() {
        val fixtures = checkNotNull(getenv("PK_DIAGNOSTIC_DIR")) { "Diagnostic fixture directory missing" }.toKString()
        val rom = "$fixtures/diagnostic.gba"
        val fs = FileSystem.SYSTEM
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-link-${Random.nextLong().toULong()}"
        fs.createDirectories(directory)
        try {
            val saves = listOf((directory / "first.sav").toString(), (directory / "second.sav").toString())
            assertFalse(PkLinkCore.open(listOf(rom, "$fixtures/diagnostic.gb"), saves))
            assertEquals(0, PkLinkCore.count)
            assertTrue(PkLinkCore.open(listOf(rom, rom), saves))
            assertEquals(2, PkLinkCore.count)
            assertEquals(48_000, PkLinkCore.sampleRate)
            PkLinkCore.keys(0, 1)
            PkLinkCore.audioRate(48_000.0)
            repeat(5) { assertTrue(PkLinkCore.frame(), PkLinkCore.status()) }
            for (slot in 0..1) {
                assertEquals(240, PkLinkCore.width(slot))
                assertEquals(160, PkLinkCore.height(slot))
                assertFalse(PkLinkCore.copyFrame(slot, ByteArray(10)))
                val frame = ByteArray(240 * 160 * 4)
                assertTrue(PkLinkCore.copyFrame(slot, frame))
                assertTrue((3 until frame.size step 4).all { frame[it] == 0xFF.toByte() })
                assertTrue(PkLinkCore.readAudio(slot, ShortArray(8192)) > 0)
            }
        } finally {
            PkLinkCore.close(3)
            fs.deleteRecursively(directory)
        }
        assertEquals(0, PkLinkCore.count)
        assertFalse(PkLinkCore.saving)
    }
}
