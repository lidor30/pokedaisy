package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.coop.lock.CoopLockActions
import com.pokedaisy.app.companion.coop.lock.CoopLockUi
import com.pokedaisy.app.companion.coop.lock.LockRefusal
import com.pokedaisy.app.companion.coop.lock.LockWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopLockUiTest {
    private class Calls : CoopLockActions {
        val log = mutableListOf<String>()
        override fun propose() { log += "propose" }
        override fun accept() { log += "accept" }
        override fun decline() { log += "decline" }
        override fun turnOff() { log += "turnOff" }
    }

    @Test fun subtitleFollowsRefusal() {
        val expected = mapOf(
            LockRefusal.NOT_PAIRED to "Pair with a partner first",
            LockRefusal.NOT_CONNECTED to "Your partner must be connected",
            LockRefusal.OWN_NOT_NEW to "You already have a POKéMON",
            LockRefusal.PARTNER_NOT_NEW to "Your partner already has a POKéMON",
        )
        for (r in LockRefusal.entries) {
            val ui = CoopLockUi(refusal = r)
            assertEquals(expected[r], coopLockSubtitle(ui))
            assertFalse(coopLockTappable(ui))
        }
        assertEquals("Waiting for your partner to accept", coopLockSubtitle(CoopLockUi(refusal = null, proposalSent = true)))
    }

    @Test fun noSubtitleWhenItCanTurnOn() {
        val ui = CoopLockUi(refusal = null)
        assertNull(coopLockSubtitle(ui))
        assertTrue(coopLockTappable(ui))
        assertNull(coopLockSubtitle(CoopLockUi(locked = true, refusal = null)))
    }

    @Test fun tapsAskTheRightQuestion() {
        val a = Calls()
        val on = coopLockAsk(CoopLockUi(refusal = null), a, "LEAF")!!
        assertTrue(on.message.startsWith("Turn on PLAY TOGETHER ONLY for both games?"))
        on.onConfirm(); assertEquals(listOf("propose"), a.log)
        val off = coopLockAsk(CoopLockUi(locked = true, refusal = null), a, "LEAF")!!
        assertTrue(off.message.startsWith("Turn off PLAY TOGETHER ONLY?"))
        off.onConfirm(); assertEquals("turnOff", a.log.last())
        val prop = coopLockAsk(CoopLockUi(partnerProposed = true), a, "LEAF")!!
        assertEquals("LEAF wants to PLAY TOGETHER ONLY.", prop.message)
        assertEquals("ACCEPT", prop.confirmLabel)
        prop.onConfirm(); prop.onCancel(); assertEquals(listOf("propose", "turnOff", "accept", "decline"), a.log)
        assertNull(coopLockAsk(CoopLockUi(refusal = LockRefusal.NOT_CONNECTED), a, "LEAF"))
    }

    @Test fun rowHiddenWithoutState() {
        assertNull(coopLockRow(null, null, "", {}))
        val row = coopLockRow(CoopLockUi(locked = true, refusal = null), Calls(), "LEAF") {}
        assertNotNull(row)
        assertEquals("ON", row!!.value)
    }

    @Test fun overlayTextsByWait() {
        assertEquals(listOf("Waiting for your partner..."), coopWaitLines(LockWait.Partner, "LEAF"))
        assertEquals(listOf("Waiting for LEAF to get here.", "Step away to cancel."), coopWaitLines(LockWait.AtStory(3), "LEAF"))
    }
}
