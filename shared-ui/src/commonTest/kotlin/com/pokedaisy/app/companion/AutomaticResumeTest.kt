package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlin.random.Random
import kotlin.test.*
import okio.FileSystem
import okio.Path

class AutomaticResumeTest {
    private inline fun temporary(test: (StateFiles) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-automatic-resume-${Random.nextLong().toULong()}"
        val files = StateFiles(root, extraSidecars = listOf(".ra"))
        try { test(files) } finally { root.deleteRecursively() }
    }
    private fun ra(path: Path) = path.parent!! / "${path.name}.ra"

    @Test fun stagedManualFallbackKeepsItsCacheAndAchievementProgress() = temporary { files ->
        val manual = files.stateFile(3)
        manual.writeText("manual state"); ra(manual).writeText("manual achievements")
        SnapshotCache.fileFor(manual).writeText("manual companion")
        assertEquals(files.resumeFile, StateResume.stage(files))
        assertEquals("manual state", files.resumeFile.readText())
        assertEquals("manual achievements", ra(files.resumeFile).readText())
        assertEquals("manual companion", SnapshotCache.fileFor(files.resumeFile).readText())
        assertEquals("manual state", manual.readText())
        files.moveState(files.resumeFile, files.directory / "resume.other-core")
        assertFalse(files.resumeFile.isFile); assertFalse(ra(files.resumeFile).isFile)
        assertEquals("manual achievements", ra(files.directory / "resume.other-core").readText())
    }

    @Test fun importedSaveRemainsFreshAcrossFailedCaptureAndANewSession() = temporary { files ->
        files.stateFile(0).writeText("old moment")
        files.resumeFile.writeText("old automatic"); ra(files.resumeFile).writeText("old achievements")
        StateResume.startFresh(files)
        assertFalse(files.resumeFile.isFile); assertFalse(ra(files.resumeFile).isFile)
        assertNull(StateResume.stage(files))
        var reads = 0
        fun session(writeOk: Boolean) = StateResume(files,
            write = { if (writeOk) { it.writeText("fresh moment"); true } else false },
            read = { reads++; true }, matchesSave = { 1 })
        assertFalse(session(false).restore()); assertFalse(session(false).save())
        assertFalse(session(true).restore()); assertEquals(0, reads)
        assertTrue(files.freshBootFile.isFile)
        assertTrue(session(true).save()); assertFalse(files.freshBootFile.isFile)
        assertTrue(session(true).restore()); assertEquals(1, reads)
    }

    /** A changed save (0) and another DS core's state (2) are parked; -1 (no save in the state) loads (StateResumeTest). */
    @Test fun changedSavesAndOtherCoreStatesNeverReachTheCoreAndCanBeParked() = temporary { files ->
        var reads = 0
        for (verdict in listOf(0, 2)) {
            files.resumeFile.writeText("automatic")
            ra(files.resumeFile).writeText("progress")
            val parked = files.directory / "parked-$verdict"
            val resume = StateResume(files, write = { false }, read = { reads++; true },
                matchesSave = { verdict }, rejected = { path, result ->
                    assertEquals(verdict, result); files.moveState(path, parked)
                })
            assertFalse(resume.restore(files.resumeFile)); assertEquals(0, reads)
            assertEquals("automatic", parked.readText()); assertEquals("progress", ra(parked).readText())
        }
    }
}
