package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FfMusicKey] against real RAM snapshots (see [FixtureMemoryReader]). */
class FfMusicKeyTest {
    private fun key(fixture: String) = FfMusicKey.current(FixtureMemoryReader.load(fixture))

    @Test fun emeraldRoute114PlaysItsMapMusic() {
        // Route 114's map music is MUS_ROUTE110: gSongTable[360].header in retail Emerald.
        assertEquals("song_09092f8", key("emerald_vanilla"))
    }

    @Test fun stoppedMusicHasNoKey() {
        // Captured with the BGM player paused and no tracks running.
        assertNull(key("firered_vanilla"))
    }

    @Test fun everyGameSharesTheSoundEngineLayout() {
        // The m4a player chain is the same in the decomps, CFRU and expansion hacks.
        for (f in listOf("unbound", "radical_red", "heart_and_soul", "emerald_rogue", "tmt2", "leafgreen_rev1", "ruby_rev1")) {
            val k = key(f)
            assertTrue("$f: $k", k != null && k.startsWith("song_"))
        }
    }
}
