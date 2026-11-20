package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.GuideEntry
import com.pokedaisy.app.companion.data.GuidePage
import com.pokedaisy.app.companion.data.GuideSection
import kotlin.test.*

/** The GUIDE's LazyColumn keys: a repeated entry must not reuse a key (it crashed HERE on FireRed). */
class GuideKeysTest {
    @Test fun repeatedEntriesGetUniqueKeys() {
        val stardust = GuideEntry("STARDUST", "Lying around MT. MOON.")
        val hidden = GuideEntry("HIDDEN ITEM", "POTION", hint = "Buried around MT. MOON.")
        val section = GuideSection("ITEMS", listOf(stardust, stardust, hidden, hidden, hidden))
        val page = GuidePage("HERE", listOf(section))
        val keys = GuideUiState().keys(page, section)
        assertEquals(keys.size, keys.toSet().size)
        // The first of each keeps the plain key, so an open entry stays open.
        assertEquals("HERE/ITEMS/STARDUST/null", keys[0])
        assertEquals("HERE/ITEMS/STARDUST/null#2", keys[1])
    }
}
