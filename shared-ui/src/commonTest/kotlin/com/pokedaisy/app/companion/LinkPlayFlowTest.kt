package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.LinkPlayFlow.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPlayFlowTest {
    private val play = LinkPlayFlow<String>()

    private fun select(game: String) {
        val setup = play.setup
        val scan = assertNotNull(setup.begin(setup.slots.size))
        setup.ready(scan, listOf(game))
        setup.choose(assertNotNull(setup.choice), game)
    }

    private fun start() {
        select("Emerald")
        select("Emerald")
        assertEquals(listOf("Emerald", "Emerald"), play.beginStart())
    }

    @Test fun startRequiresTwoCartridgesAndInvalidatesPendingScans() {
        select("Ruby")
        assertNull(play.beginStart())
        select("Sapphire")
        val pending = assertNotNull(play.setup.begin(0))
        assertEquals(listOf("Ruby", "Sapphire"), play.beginStart())
        assertFalse(play.setup.ready(pending, listOf("Emerald")))
        assertNull(play.beginStart())
        play.back { error("Exited during startup") }
        assertEquals(Phase.STARTING, play.phase)
        play.startFailed("Unreadable save")
        assertEquals(Phase.SETUP, play.phase)
        assertEquals("Unreadable save", play.notice)
        play.notice = null
        assertEquals(listOf("Ruby", "Sapphire"), play.beginStart())
    }

    @Test fun anOpenFailureCanFinishNativeCleanupBeforeRetry() {
        start()
        var closes = 0
        play.startFailed("Open failed") { closes++ }
        play.startFailed("Late failure") { closes++ }
        assertEquals(1, closes)
        assertNull(play.beginStart())
        play.back { error("Exited during cleanup") }
        assertFalse(play.closed(savesFinished = true))
        assertEquals(Phase.SETUP, play.phase)
        assertEquals("Open failed", play.notice)
    }

    @Test fun backAndLeaveWaitForOneSuccessfulClose() {
        var exits = 0
        play.back { exits++ }
        assertEquals(1, exits)
        start()
        play.opened()
        play.back { error("Exited before confirmation") }
        assertTrue(play.confirmingLeave)
        play.back { error("Exited instead of dismissing confirmation") }
        assertFalse(play.confirmingLeave)
        play.leave { error("Closed without confirmation") }
        play.requestLeave()
        var closes = 0
        play.leave { closes++ }
        play.leave { closes++ }
        assertEquals(1, closes)
        assertEquals(Phase.CLOSING, play.phase)
        play.back { error("Exited before the saves finished") }
        assertTrue(play.closed(savesFinished = true))
        assertFalse(play.closed(savesFinished = true))
    }

    @Test fun aSaveFailureReturnsToSetupWithAnExplanation() {
        start()
        play.opened()
        play.requestLeave()
        play.leave {}
        assertFalse(play.closed(savesFinished = false))
        assertEquals(Phase.SETUP, play.phase)
        assertNotNull(play.notice)
    }
}
