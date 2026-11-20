package com.pokedaisy.app.companion

import kotlin.test.*

/** The support window's timing: 5 hours of play first, 5 more after REMIND ME LATER, never once answered. */
class SupportAskTest {
    private val h = SupportAsk.HOUR

    @Test fun asksAfter5HoursOfPlay() {
        assertFalse(SupportAsk.due(played = 4 * h, askAt = SupportAsk.FIRST_ASK, done = false))
        assertTrue(SupportAsk.due(played = 5 * h, askAt = SupportAsk.FIRST_ASK, done = false))
        assertTrue(SupportAsk.due(played = 40 * h, askAt = SupportAsk.FIRST_ASK, done = false))
    }

    @Test fun remindMeLaterAsksAgain5HoursOfPlayLater() {
        val at = SupportAsk.remindAt(played = 12 * h)
        assertEquals(17 * h, at)
        assertFalse(SupportAsk.due(played = 16 * h, askAt = at, done = false))
        assertTrue(SupportAsk.due(played = 17 * h, askAt = at, done = false))
    }

    @Test fun neverAgainOnceAnswered() {
        assertFalse(SupportAsk.due(played = 100 * h, askAt = SupportAsk.FIRST_ASK, done = true))
    }

    @Test fun theSitesAreTheWebsiteSOwnPages() {
        assertEquals(
            listOf("https://buymeacoffee.com/lidor30g", "https://www.patreon.com/cw/pokedaisy", "https://ko-fi.com/pokedaisy"),
            SupportAsk.Site.entries.map { it.url },
        )
    }
}
