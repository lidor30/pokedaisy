package com.pokedaisy.app.companion

import com.pokedaisy.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Library's WHAT'S NEW: notes for every release, shown once after an update and never on a fresh install. */
class WhatsNewTest {
    @Test fun `the app's own version has notes`() {
        // Written with `make bump` (docs/RELEASING.md): an update without them would show nothing new.
        assertNotNull("no WHAT'S NEW for ${BuildConfig.VERSION_NAME} in WhatsNew.RELEASES", WhatsNew.notesFor(BuildConfig.VERSION_NAME))
    }

    @Test fun `notes are short and in their sections`() {
        for (n in WhatsNew.RELEASES) {
            assertTrue(n.version, n.sections.isNotEmpty() && n.sections.all { it.items.isNotEmpty() })
            assertTrue(n.version, n.sections.all { it.title == WhatsNew.NEW || it.title == WhatsNew.FIXES })
            // A window, not the release page: a handful of lines (15 since 1.4.0, the user's call: a tool a line).
            assertTrue("${n.version}: ${n.sections.sumOf { it.items.size }} lines", n.sections.sumOf { it.items.size } <= 15)
            // Each line leads with what it's about and a colon, which the window draws red.
            for (item in n.sections.flatMap { it.items }) assertTrue("${n.version}: no lead-in: $item", item.indexOf(": ") in 1..36)
        }
        assertEquals(WhatsNew.RELEASES.size, WhatsNew.RELEASES.map { it.version }.toSet().size)
    }

    @Test fun `shown once after an update`() {
        assertEquals("1.3.2", WhatsNew.due("1.3.2", lastSeen = "1.3.1", updated = true)?.version)
        assertEquals("1.3.2", WhatsNew.due("1.3.2", lastSeen = null, updated = true)?.version)   // from a version before it
        assertNull(WhatsNew.due("1.3.2", lastSeen = "1.3.2", updated = true))
        assertNull(WhatsNew.due("1.3.2", lastSeen = null, updated = false))                      // a fresh install
        assertNull(WhatsNew.due("0.0.1", lastSeen = null, updated = true))                       // no notes
    }
}
