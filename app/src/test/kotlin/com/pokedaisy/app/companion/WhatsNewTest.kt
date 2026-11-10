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
            // A hotfix (1.5.0.1) is its release's notes plus its own line.
            val max = if (WhatsNew.releaseOf(n.version) != n.version) 16 else 15
            assertTrue("${n.version}: ${n.sections.sumOf { it.items.size }} lines", n.sections.sumOf { it.items.size } <= max)
            // Each line leads with what it's about and a colon, which the window draws red.
            for (item in n.sections.flatMap { it.items }) assertTrue("${n.version}: no lead-in: $item", item.indexOf(": ") in 1..36)
        }
        assertEquals(WhatsNew.RELEASES.size, WhatsNew.RELEASES.map { it.version }.toSet().size)
    }

    @Test fun `shown once after an update`() {
        assertEquals("1.3.2", WhatsNew.due("1.3.2", lastSeen = "1.3.1", updated = true)?.version)
        // A hotfix: its release's notes with its line first, never again for a player who saw the release's.
        val hotfix = WhatsNew.notesFor("1.5.0.1")!!
        val release = WhatsNew.notesFor("1.5.0")!!
        assertEquals(release.sections.flatMap { it.items }.size + 1, hotfix.sections.flatMap { it.items }.size)
        assertTrue(hotfix.sections.first { it.title == WhatsNew.FIXES }.items.first().startsWith("1.5.0.1 HOTFIX: "))
        assertEquals(null, WhatsNew.due("1.5.0.1", lastSeen = "1.5.0", updated = true))
        assertEquals("1.5.0.1", WhatsNew.due("1.5.0.1", lastSeen = "1.4.0", updated = true)?.version)
        assertEquals("1.5.0", WhatsNew.releaseOf("1.5.0.1"))
        assertEquals("1.3.2", WhatsNew.due("1.3.2", lastSeen = null, updated = true)?.version)   // from a version before it
        assertNull(WhatsNew.due("1.3.2", lastSeen = "1.3.2", updated = true))
        assertNull(WhatsNew.due("1.3.2", lastSeen = null, updated = false))                      // a fresh install
        assertNull(WhatsNew.due("0.0.1", lastSeen = null, updated = true))                       // no notes
    }
}
