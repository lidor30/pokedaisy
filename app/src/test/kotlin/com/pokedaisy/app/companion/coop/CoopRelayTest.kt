package com.pokedaisy.app.companion.coop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoopRelayTest {
    @Test fun normalizes() {
        assertEquals("https://relay.example.net", CoopRelay.normalize(" https://relay.example.net/ "))
        assertEquals("https://r.example:8443", CoopRelay.normalize("https://r.example:8443"))
        listOf(
            "http://r.example", "https://r.example/path", "https://u@r.example", "r.example", "https://",
            "https://a b", "https://r.example?x=1", "https://r.example:99999", "https://" + "a".repeat(254),
        ).forEach { assertNull(it, CoopRelay.normalize(it)) }
    }

    @Test fun effectiveOrder() {
        assertEquals("https://mine", CoopRelay.effective("https://mine", "https://built"))
        assertEquals("https://built", CoopRelay.effective(null, "https://built"))
        assertNull(CoopRelay.effective(null, ""))
    }

    @Test fun blankOverrideIsNoOverride() {
        assertEquals("https://built", CoopRelay.effective("", "https://built"))
        assertEquals("https://built", CoopRelay.effective("  ", "https://built"))
        assertNull(CoopRelay.effective(" ", ""))
        assertEquals("BUILT-IN", CoopRelay.rowValue("", "https://built"))
        assertEquals("NONE", CoopRelay.rowValue("  ", ""))
    }

    @Test fun rowValue() {
        assertEquals("BUILT-IN", CoopRelay.rowValue(null, "https://built"))
        assertEquals("NONE", CoopRelay.rowValue(null, ""))
        assertEquals("mine.example:8443", CoopRelay.rowValue("https://mine.example:8443", "https://built"))
    }
}
