package com.pokedaisy.app

import com.pokedaisy.app.companion.data.BestEffort
import com.pokedaisy.app.companion.data.BestEffortReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What SHARE sends: exactly the fields the consent window lists, and nothing that doesn't fit their shape. */
class BestEffortShareTest {
    private val r = BestEffortReport(
        sha1 = "0ce2a880aa097f1dce4e1db8ee513d0e82d15859", size = 33554432, gameCode = "BPRE", revision = 0,
        matchedAs = "UNBOUND", full = false, off = setOf(BestEffort.Part.DEX),
    )

    @Test fun theDocument() {
        assertEquals(
            """{"fields":{"sha1":{"stringValue":"0ce2a880aa097f1dce4e1db8ee513d0e82d15859"},"size":{"integerValue":"33554432"},""" +
                """"gameCode":{"stringValue":"BPRE"},"revision":{"integerValue":"0"},"matchedAs":{"stringValue":"UNBOUND"},""" +
                """"full":{"booleanValue":false},"off":{"arrayValue":{"values":[{"stringValue":"DEX"}]}},"appVersion":{"stringValue":"1.1.4"}}}""",
            BestEffortShare.body(r, "1.1.4"),
        )
    }

    @Test fun nothingOff() {
        val json = BestEffortShare.body(r.copy(full = true, off = emptySet()), "1.1.4")!!
        assert(""""off":{"arrayValue":{}}""" in json)
    }

    @Test fun theWindowListsWhatIsSent() {
        assertEquals(
            listOf("ROM SHA-1", "ROM SIZE", "GAME CODE", "READ AS", "MATCH", "PARTS OFF", "APP VERSION"),
            r.shareFields("1.1.4").map { it.first },
        )
    }

    /** Anything not shaped like a hash / code / id is refused before it could carry other text. */
    @Test fun oddValuesAreNotSent() {
        assertNull(BestEffortShare.body(r.copy(sha1 = "not a hash"), "1.1.4"))
        assertNull(BestEffortShare.body(r.copy(gameCode = "BP\"E"), "1.1.4"))
        assertNull(BestEffortShare.body(r.copy(matchedAs = "lidor's rom"), "1.1.4"))
        assertNull(BestEffortShare.body(r, "1.1.4 \"x\""))
    }
}
