package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** TURBO A / B (issue #32): held, they're pulsed by the engine, never part of the steady mask. */
class GbaInputTurboTest {
    private val turboKey = 1001
    private val aKey = 1002

    private fun input() = GbaInput().apply {
        setControls(mapOf(turboKey to (MgbaCore.Key.A or GbaControls.TURBO), aKey to MgbaCore.Key.A))
    }

    @Test fun turboIsItsOwnMask() {
        val i = input()
        i.onKey(turboKey, true)
        assertEquals(0, i.mask)
        assertEquals(MgbaCore.Key.A, i.turboMask)
        i.onKey(turboKey, false)
        assertEquals(0, i.turboMask)
    }

    @Test fun plainAStaysPlain() {
        val i = input()
        i.onKey(aKey, true)
        assertEquals(MgbaCore.Key.A, i.mask)
        assertEquals(0, i.turboMask)
    }

    @Test fun aScriptMutesTurbo() {
        val i = input()
        i.onKey(turboKey, true)
        i.setScript(MgbaCore.Key.B, exclusive = true)
        assertEquals(0, i.turboMask)
        i.setScript(0, exclusive = false)
        assertEquals(MgbaCore.Key.A, i.turboMask)
    }

    /** The QUICK MENU's TURBO A: the plain A key mashes while it's on, and only A. */
    @Test fun theQuickMenusTurboLatchesA() {
        val i = input()
        i.turboLatch = MgbaCore.Key.A
        i.onKey(aKey, true)
        assertEquals(0, i.mask)
        assertEquals(MgbaCore.Key.A, i.turboMask)
        i.setTouchBits(MgbaCore.Key.B)
        assertEquals(MgbaCore.Key.B, i.mask)
        i.turboLatch = 0
        assertEquals(MgbaCore.Key.A or MgbaCore.Key.B, i.mask)
        assertEquals(0, i.turboMask)
    }
}
