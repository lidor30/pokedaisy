package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [FfMusicBudget]: which STEADY clips go when the cache is over its budget, and when the background pass may add more. */
class FfMusicBudgetTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("v6") }

    /** A song of [bytes] (loop + intro) for [rom], written at [written]. */
    private fun song(rom: String, key: String, written: Long, bytes: Int = 1000, wav: Boolean = false) {
        val dir = File(root, rom).apply { mkdirs() }
        val ext = if (wav) "wav" else "flac"
        File(dir, "$key.$ext").apply { writeBytes(ByteArray(bytes - 100)); setLastModified(written) }
        File(dir, "$key.intro.$ext").apply { writeBytes(ByteArray(100)); setLastModified(written) }
    }

    private fun left() = FfMusicBudget.songs(root).map { "${it.rom}/${it.key}" }.toSet()

    @Test fun budgetIsCappedAndAShareOfFreeSpace() {
        assertEquals(FfMusicBudget.MAX_BYTES, FfMusicBudget.budget(0, 64L shl 30))
        assertEquals(500L shl 20, FfMusicBudget.budget(500L shl 20, 1500L shl 20))
    }

    @Test fun neverPlayedGoFirstThenLongestAgo() {
        song("aaaa", "song_1", written = 1_000_000)
        song("aaaa", "song_2", written = 2_000_000)
        song("bbbb", "song_3", written = 3_000_000)
        song("bbbb", "song_4", written = 4_000_000)
        song("bbbb", "song_5", written = 5_000_000)
        FfMusicBudget.append(root, "aaaa", "song_1", 9_000)
        FfMusicBudget.append(root, "bbbb", "song_3", 8_000)
        FfMusicBudget.append(root, "aaaa", "song_1", 10_000)   // played again: the latest counts
        // 5000 bytes cached + 7000 free: a budget of 3000.
        val freed = FfMusicBudget.trim(root, freeBytes = 7000)
        assertEquals(2000L, freed)
        // song_4 / song_2 never played go first (oldest written first), then nothing more is needed.
        assertEquals(setOf("aaaa/song_1", "bbbb/song_3", "bbbb/song_5"), left())
        // 3000 cached + 9000 free: a budget of 3000 - fits, nothing goes.
        assertEquals(0L, FfMusicBudget.trim(root, freeBytes = 9000))
        // A budget of 1000: song_5 (never played), then song_3 (played before song_1).
        FfMusicBudget.trim(root, freeBytes = 1000)
        assertEquals(setOf("aaaa/song_1"), left())
    }

    @Test fun keptSongStays() {
        song("aaaa", "song_1", written = 1_000_000)
        song("aaaa", "song_2", written = 2_000_000)
        FfMusicBudget.trim(root, keep = setOf("aaaa/song_1"), freeBytes = 2000)
        assertEquals(setOf("aaaa/song_1"), left())
    }

    @Test fun wavsFromBeforeFlacCount() {
        song("aaaa", "song_1", written = 1_000_000, bytes = 3000, wav = true)
        song("aaaa", "song_2", written = 2_000_000)
        assertEquals(4000L, FfMusicBudget.songs(root).sumOf { it.bytes })
        FfMusicBudget.trim(root, freeBytes = 0)   // a budget of 1000: the old WAV goes
        assertEquals(setOf("aaaa/song_2"), left())
    }

    @Test fun backgroundOnlyFillsFreeRoom() {
        song("aaaa", "song_1", written = 1_000_000)
        assertTrue(FfMusicBudget.hasRoom(root, freeBytes = 1L shl 30, now = 0))
        // A budget of a quarter of 100 MB leaves less than the headroom a song needs.
        assertFalse(FfMusicBudget.hasRoom(root, freeBytes = 100L shl 20, now = 1_000_000))
    }

    @Test fun playedLogIsCompacted() {
        song("aaaa", "song_1", written = 1_000_000)
        repeat(500) { FfMusicBudget.append(root, "aaaa", "song_1", it.toLong()) }
        repeat(500) { FfMusicBudget.append(root, "aaaa", "gone", it.toLong()) }
        FfMusicBudget.trim(root, freeBytes = 1L shl 30)
        assertEquals(listOf("aaaa song_1 499"), File(root, ".played").readLines())
    }

    @Test fun songKeys() {
        assertEquals("song_00a1b2c", FfMusicBudget.songKey("song_00a1b2c.flac"))
        assertEquals("song_00a1b2c", FfMusicBudget.songKey("song_00a1b2c.intro.flac"))
        assertEquals("gb_02_1f", FfMusicBudget.songKey("gb_02_1f.intro.wav"))
        assertNull(FfMusicBudget.songKey("song_00a1b2c.flac.tmp"))
    }
}
