package com.pokedaisy.app.companion.coop.lock

import com.pokedaisy.app.companion.coop.lock.CoopLockRules.holdForLink
import com.pokedaisy.app.companion.coop.lock.CoopLockRules.refusal
import com.pokedaisy.app.companion.coop.lock.LockRefusal.*
import org.junit.Assert.*
import org.junit.Test

class CoopLockRulesTest {
    @Test fun refusalOrder() { assertEquals(NOT_PAIRED, refusal(false, false, 3, 3)); assertEquals(NOT_CONNECTED, refusal(true, false, 0, 0))
        assertEquals(OWN_NOT_NEW, refusal(true, true, 1, 0)); assertEquals(PARTNER_NOT_NEW, refusal(true, true, 0, null)); assertNull(refusal(true, true, 0, 0)) }
    @Test fun shortDropDoesNotHold() { assertFalse(holdForLink(true, false, false, 2999)); assertTrue(holdForLink(true, false, false, 3000))
        assertFalse(holdForLink(true, false, true, 60_000)); assertFalse(holdForLink(false, false, false, 60_000)); assertFalse(holdForLink(true, true, false, 60_000)) }
}
