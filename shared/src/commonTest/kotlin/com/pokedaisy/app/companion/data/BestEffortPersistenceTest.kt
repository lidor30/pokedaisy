package com.pokedaisy.app.companion.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/** Uses the real filesystem on JVM and iOS: choices must survive reopening and remain scoped to one ROM. */
class BestEffortPersistenceTest {
    private val firstRom = "a".repeat(40)
    private val secondRom = "b".repeat(40)

    @Test fun pickedMatchSurvivesReopening() = withStore { dir ->
        val candidate = assertNotNull(BestEffort.candidate("EMERALD"))
        val off = setOf(BestEffort.Part.DEX, BestEffort.Part.GUIDE)
        BestEffortStore.save(firstRom.uppercase(), BestEffort.Match(candidate, false, off, 0),
            BestEffortStore.Source.PICKED, verified = false)
        assertTrue(FileSystem.SYSTEM.exists(dir / "best-effort.tsv"))
        reopen(dir)
        val saved = assertNotNull(BestEffortStore.load(firstRom))
        assertEquals("EMERALD", saved.id)
        assertEquals(BestEffortStore.Source.PICKED, saved.source)
        assertFalse(saved.full)
        assertFalse(saved.verified)
        assertEquals(off, saved.off)
        assertEquals(off, BestEffortStore.matchOf(saved)?.off)
        assertNull(BestEffortStore.load(secondRom))
    }

    @Test fun declinedRomDoesNotEraseAnotherMatch() = withStore { dir ->
        val candidate = assertNotNull(BestEffort.candidate("EMERALD"))
        BestEffortStore.save(firstRom, BestEffort.Match(candidate, true, emptySet(), 0), BestEffortStore.Source.PICKED)
        BestEffortStore.decline(secondRom.uppercase())
        reopen(dir)
        assertTrue(BestEffortStore.declined(secondRom))
        assertNull(BestEffortStore.load(secondRom))
        assertEquals(setOf(firstRom), BestEffortStore.all().keys)
        assertEquals("EMERALD", BestEffortStore.load(firstRom)?.id)
        assertFalse(BestEffortStore.declined(firstRom))
    }

    @Test fun pickingAgainReplacesADecline() = withStore { dir ->
        BestEffortStore.decline(firstRom)
        reopen(dir)
        val candidate = assertNotNull(BestEffort.candidate("EMERALD"))
        BestEffortStore.save(firstRom, BestEffort.Match(candidate, true, emptySet(), 0), BestEffortStore.Source.PICKED)
        reopen(dir)
        assertFalse(BestEffortStore.declined(firstRom))
        assertTrue(assertNotNull(BestEffortStore.load(firstRom)).verified)
        BestEffortStore.forget(firstRom)
        reopen(dir)
        assertNull(BestEffortStore.load(firstRom))
        assertFalse(BestEffortStore.declined(firstRom))
    }

    private fun reopen(dir: Path) {
        BestEffortStore.dir = null
        BestEffortStore.dir = dir.toString().toPath()
    }

    private fun withStore(block: (Path) -> Unit) {
        val oldDir = BestEffortStore.dir
        val oldChanged = BestEffortStore.onChanged
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-picks-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(dir)
        try {
            BestEffortStore.dir = dir
            BestEffortStore.onChanged = null
            block(dir)
        } finally {
            BestEffortStore.dir = oldDir
            BestEffortStore.onChanged = oldChanged
            FileSystem.SYSTEM.deleteRecursively(dir)
        }
    }
}
