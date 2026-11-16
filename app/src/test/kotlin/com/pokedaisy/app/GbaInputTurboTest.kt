package com.pokedaisy.app

import com.pokedaisy.app.companion.data.CoreKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** TURBO A / B (issue #32): held, they're pulsed by the engine, never part of the steady mask. */
class GbaInputTurboTest {
    private val turboKey = 1001
    private val aKey = 1002

    private fun input() = GbaInput().apply {
        setControls(mapOf(turboKey to (CoreKey.A or GbaControls.TURBO), aKey to CoreKey.A))
    }

    @Test fun turboIsItsOwnMask() {
        val i = input()
        i.onKey(turboKey, true)
        assertEquals(0, i.mask)
        assertEquals(CoreKey.A, i.turboMask)
        i.onKey(turboKey, false)
        assertEquals(0, i.turboMask)
    }

    @Test fun plainAStaysPlain() {
        val i = input()
        i.onKey(aKey, true)
        assertEquals(CoreKey.A, i.mask)
        assertEquals(0, i.turboMask)
    }

    @Test fun aScriptMutesTurbo() {
        val i = input()
        i.onKey(turboKey, true)
        i.setScript(CoreKey.B, exclusive = true)
        assertEquals(0, i.turboMask)
        i.setScript(0, exclusive = false)
        assertEquals(CoreKey.A, i.turboMask)
    }
}
