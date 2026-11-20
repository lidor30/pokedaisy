package com.pokedaisy.app.game

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.GbaInput
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.data.CoreKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolledGameKeysTest {
    private fun keys() = PolledGameKeys().apply {
        input.setControls(mapOf(1001 to CoreKey.A, 1002 to CoreKey.A, 1003 to (CoreKey.B or GbaControls.TURBO)))
    }

    @Test fun aliasesAndDisconnectReleaseIndependently() {
        val k = keys()
        k.poll(setOf(1001, 1002), false)
        assertEquals(CoreKey.A, k.input.mask)
        k.poll(setOf(1002), false)
        assertEquals(CoreKey.A, k.input.mask)
        k.poll(emptySet(), false)
        assertEquals(0, k.input.mask)
    }

    @Test fun ownershipChangesBetweenPollsKeepHeldKeysOutUntilRelease() {
        val k = keys()
        k.poll(setOf(1001), false)
        k.quarantine(setOf(1001)) // open
        k.quarantine(setOf(1001, 1003)) // close before another frame
        k.poll(setOf(1001, 1003), false)
        assertEquals(0, k.input.mask)
        assertEquals(0, k.input.turboMask)
        k.poll(setOf(1001), false)
        k.poll(setOf(1001, 1003), false)
        assertEquals(CoreKey.B, k.input.turboMask)
        assertEquals(0, k.input.mask)
    }

    @Test fun captureIgnoresTheOpeningKeyAndQuarantinesAStillHeldKeyOnCancel() {
        val k = keys()
        var done = emptyList<Int>()
        var held = setOf(1001)
        k.capture.onActiveChanged = { k.quarantine(held) }
        k.poll(held, false)
        k.capture.start(KeyCapture.Request("test", false, { done = it }))
        held = emptySet(); k.poll(held, true)
        assertTrue(k.capture.active)
        held = setOf(1003); k.poll(held, true)
        k.capture.cancel()
        k.poll(held, false)
        assertEquals(0, k.input.turboMask)
        assertEquals(emptyList(), done)
        held = emptySet(); k.poll(held, false)
        held = setOf(1003); k.poll(held, false)
        assertEquals(CoreKey.B, k.input.turboMask)
    }

    @Test fun captureCompletesThroughTheRealModelAndCannotLeakAnotherPressFromThatPoll() {
        val k = keys()
        var held = emptySet<Int>()
        var done = emptyList<Int>()
        k.capture.onActiveChanged = { k.quarantine(held) }
        k.capture.start(KeyCapture.Request("test", false, { done = it }))
        held = setOf(1003); k.poll(held, true)
        held = setOf(1001); k.poll(held, true) // release finishes; the new A must not escape
        assertEquals(listOf(1003), done)
        assertEquals(0, k.input.mask)
        k.poll(held, false)
        assertEquals(0, k.input.mask)
        held = emptySet(); k.poll(held, false)
        held = setOf(1001); k.poll(held, false)
        assertEquals(CoreKey.A, k.input.mask)
    }

    @Test fun axisBoundaryAndHatPriorityAreTheSameOnBothPlatforms() {
        assertEquals(0, GbaInput.axisBits(.5f, -.5f))
        assertEquals(CoreKey.RIGHT or CoreKey.UP, GbaInput.axisBits(.51f, -.51f))
        assertEquals(CoreKey.LEFT or CoreKey.DOWN, GbaInput.axisBits(1f, 1f, -1f, 0f))
    }
}
