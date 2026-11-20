package com.pokedaisy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FfMusicCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun clip(cache: FfMusicCache, key: String) = cache.writeClip(key, ShortArray(8) { 1 }, 0, 8, 48000)

    @Test fun gameBoyClipsFromBeforeItsSoundChangedAreRecordedAgain() {
        val dir = tmp.root
        // Clips a Game Boy game had before the stamp (mGBA's own resampling, GitHub #269 / #327) - and a GBA game's.
        clip(androidFfMusicCache(dir, "0000000a"), "gb_02_eb")
        androidFfMusicCache(dir, "0000000a").prefetched = true
        clip(androidFfMusicCache(dir, "0000000b"), "song_06d96f4")

        val gb = androidFfMusicCache(dir, "0000000a", gameBoy = true)
        assertFalse(gb.has("gb_02_eb"))
        assertFalse(gb.prefetched)   // the background pass runs again
        clip(gb, "gb_02_eb")
        assertTrue(androidFfMusicCache(dir, "0000000a", gameBoy = true).has("gb_02_eb"))   // only once
        assertTrue(androidFfMusicCache(dir, "0000000b").has("song_06d96f4"))   // a GBA game's clips stay
    }
}
