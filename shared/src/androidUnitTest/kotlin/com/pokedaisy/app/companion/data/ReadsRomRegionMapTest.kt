package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only GBA games draw a ROM's own region map: Crystal Clear opened after Unbound drew Unbound's (RomRegionMap.current
 * is the last GBA game's until another one loads). */
class ReadsRomRegionMapTest {
    private val before = activeGame

    @After fun restore() { activeGame = before }

    @Test fun `game boy and ds games never read a rom's region map`() {
        for (kind in GameKind.entries.filter { it.isGameBoyGame || it.isDsGame }) {
            activeGame = kind
            assertFalse(kind.name, readsRomRegionMap)
        }
    }

    @Test fun `fire red engine hacks still do`() {
        activeGame = GameKind.UNBOUND
        assertTrue(readsRomRegionMap)
    }
}
