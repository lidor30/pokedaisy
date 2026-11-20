package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSessionLifecycleTest {
    private class Host(slot: LinkSessionLifecycle.Slot = LinkSessionLifecycle.Slot()) {
        val calls = mutableListOf<String>()
        var finishing = false
        val closeCallbacks = mutableListOf<() -> Unit>()

        val session = LinkSessionLifecycle(slot, { finishing }, { calls += "play" }) { closed ->
            calls += "close"
            closeCallbacks += closed
        }

        fun opened() = session.openSucceeded { calls += "prepare" }
        fun failed() = session.openFailed { calls += "failure" }
        fun finishClose() = closeCallbacks.removeAt(0).invoke()
    }

    @Test
    fun pausedOpenWaitsForResume() {
        val host = Host()
        host.session.resume()
        assertTrue(host.session.beginOpen())
        host.session.pause()
        host.opened()
        assertFalse(host.session.active)
        assertTrue(host.calls.isEmpty())

        host.session.resume()
        assertTrue(host.session.active)
        assertEquals(listOf("prepare", "play"), host.calls)
        host.session.pause()
        host.session.resume()
        assertEquals(listOf("prepare", "play", "play"), host.calls)
    }

    @Test
    fun lateOpenAfterDestroyClosesOnceBeforeReplacementCanOpen() {
        val slot = LinkSessionLifecycle.Slot()
        val old = Host(slot)
        val replacement = Host(slot)
        old.session.resume()
        assertTrue(old.session.beginOpen())
        old.session.destroy()
        assertFalse(replacement.session.beginOpen())

        old.opened()
        old.session.destroy()
        assertEquals(listOf("close"), old.calls)
        assertFalse(replacement.session.beginOpen())
        old.finishClose()
        assertTrue(replacement.session.beginOpen())
        replacement.session.resume()
        replacement.opened()
        old.session.destroy()
        assertEquals(listOf("prepare", "play"), replacement.calls)
        assertEquals(listOf("close"), old.calls)
    }

    @Test
    fun finishingActivityDiscardsOpenBeforeDestroy() {
        val host = Host()
        host.session.resume()
        assertTrue(host.session.beginOpen())
        host.finishing = true
        host.opened()
        host.session.destroy()

        assertEquals(listOf("close"), host.calls)
    }

    @Test
    fun destroyedActivityDiscardsAnOpenAlreadyWaitingForResume() {
        val host = Host()
        assertTrue(host.session.beginOpen())
        host.opened()
        host.session.destroy()

        assertEquals(listOf("close"), host.calls)
    }

    @Test
    fun destroyDuringConfirmedCloseDoesNotCloseTwiceOrDeliverUiCallback() {
        val slot = LinkSessionLifecycle.Slot()
        val host = Host(slot)
        val replacement = Host(slot)
        assertTrue(host.session.beginOpen())
        host.session.resume()
        host.opened()
        host.session.close { host.calls += "finish" }
        host.session.destroy()
        assertFalse(replacement.session.beginOpen())

        host.finishClose()
        assertTrue(replacement.session.beginOpen())
        assertEquals(listOf("prepare", "play", "close"), host.calls)
    }

    @Test
    fun failedOpenReleasesSlotAndReportsOnlyToLiveActivity() {
        val slot = LinkSessionLifecycle.Slot()
        val old = Host(slot)
        val replacement = Host(slot)
        assertTrue(old.session.beginOpen())
        old.session.destroy()
        old.failed()
        assertTrue(old.calls.isEmpty())

        assertTrue(replacement.session.beginOpen())
        replacement.failed()
        assertEquals(listOf("failure"), replacement.calls)
        assertTrue(replacement.session.beginOpen())
    }
}
