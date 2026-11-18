package com.pokedaisy.app.core

import com.pokedaisy.app.game.GameSession
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

@OptIn(ExperimentalForeignApi::class)
class GameSessionNativeTest {
    @AfterTest fun close() = PkApiCore.stop()

    @Test fun queuedActionRunsThroughTheSameNativeCoreAndCloseCancelsPendingCalls() {
        val dir = assertNotNull(getenv("PK_DIAGNOSTIC_DIR")?.toKString()).trimEnd('/')
        assertTrue(PkApiCore.start("$dir/diagnostic.gba", null))
        repeat(60) { PkApiCore.runFrame() }
        val session = GameSession(PkApiCore, PkApiCore::callSafe) { _, e -> throw e }
        val sum = byteArrayOf(0x40, 0x18, 0x80.toByte(), 0x18, 0xC0.toByte(), 0x18, 0x70, 0x47)
        var result: Long? = null
        session.post { assertTrue(PkApiCore.write(0x03004000, sum)) }
        session.requestField(false, 2) { calls, busy ->
            assertFalse(busy)
            result = calls.call(0x03004001, 1, 2, 3, 4)
        }
        repeat(2) { session.servicePosted(); session.serviceField(true, true); PkApiCore.runFrame() }
        assertNull(result)
        session.serviceField(true, true)
        assertEquals(10L, result)
        session.requestField(false) { _, _ -> error("closed game called") }
        session.post { error("closed game sample") }
        session.close()
        session.servicePosted(); session.serviceField(true, true)
        PkApiCore.runFrame()
        assertEquals(240, PkApiCore.videoWidth)
    }
}
