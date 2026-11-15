package com.pokedaisy.app.companion.data

import java.nio.file.Files
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StateResumeTest {
    private val root = Files.createTempDirectory("pokedaisy-resume").toFile().toOkioPath()
    private val files = StateFiles(root)
    private var game = 3
    private var matches = 1
    private var reads = 0
    private var failWrite = false
    private var failRead = false
    private var enabled = true
    private val resume = StateResume(files,
        write = { p -> if (failWrite) false else { p.writeText(game.toString()); true } },
        read = { p -> reads++; if (failRead) false else { game = p.readText().toInt(); true } },
        matchesSave = { matches }, enabled = { enabled }, snapshot = { game.toString().encodeToByteArray() })
    @After fun clean() = root.deleteRecursively()

    @Test fun aMatchingSaveResumesTheGameAndKeepsItsCompanionData() {
        assertTrue(resume.save()); game = 9
        assertTrue(resume.restore()); assertEquals(3, game)
        assertEquals("3", SnapshotCache.fileFor(files.resumeFile).readText())
    }

    @Test fun aChangedOrUnverifiableSaveNeverReachesTheLoader() {
        for (verdict in listOf(0, -1)) {
            assertTrue(resume.save()); matches = verdict; game = 9
            assertFalse(resume.restore()); assertEquals(9, game); assertEquals(0, reads)
            assertFalse(files.resumeFile.isFile)
            assertTrue((root / "resume.replaced").isFile)
        }
    }

    @Test fun aFreshBootMarkerSurvivesFailedWritesAndBlocksManualFallback() {
        assertTrue(files.saveSlot(7, { it.writeText("7"); true }))
        resume.startFresh()
        assertFalse(resume.restore())
        failWrite = true; assertFalse(resume.save())
        assertTrue(files.freshBootFile.isFile)
        failWrite = false; game = 4; assertTrue(resume.save())
        assertFalse(files.freshBootFile.isFile)
        game = 8; assertTrue(resume.restore()); assertEquals(4, game)
    }

    @Test fun disablingResumeDoesNotLoadOrKeepAnOldAutomaticSnapshot() {
        assertTrue(resume.save()); enabled = false; game = 9
        assertFalse(resume.restore()); assertEquals(0, reads)
        assertTrue(resume.save()); assertFalse(files.resumeFile.isFile)
        enabled = true; assertFalse(resume.restore())
    }

    @Test fun unreadableAutomaticStatesAreParkedAndManualSlotsAreKept() {
        assertTrue(resume.save()); game = 9; failRead = true
        assertFalse(resume.restore()); assertEquals(9, game)
        assertFalse(files.resumeFile.isFile)
        assertTrue(files.saveSlot(7, { it.writeText("7"); true }))
        assertFalse(resume.restore()); assertTrue(files.exists(7))
        failRead = false; assertTrue(resume.restore()); assertEquals(7, game)
    }
}
