package com.pokedaisy.app.companion.data

import java.nio.file.Files
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StateFilesTest {
    private val root = Files.createTempDirectory("pokedaisy-state-files").toFile().toOkioPath()
    private val files = StateFiles(root / "states")
    @After fun clean() { root.deleteRecursively() }
    private fun save(slot: Int, text: String) = files.saveSlot(slot, { it.writeText(text); true }, text.encodeToByteArray(), text.encodeToByteArray())

    @Test fun aFailedWritePreservesTheSlotAndItsUndo() {
        assertTrue(save(0, "first")); assertTrue(save(0, "second"))
        assertFalse(files.saveSlot(0, { it.writeText("partial"); false }))
        assertEquals("second", files.stateFile(0).readText())
        assertEquals("first", files.bakFile(0).readText())
        assertTrue(files.undoSave(0))
        assertEquals("first", files.stateFile(0).readText())
        assertEquals("first", files.thumbFile(0).readText())
        assertEquals("first", SnapshotCache.fileFor(files.stateFile(0)).readText())
        assertFalse((files.directory / "ss0.tmp").isFile)
    }

    @Test fun deleteRemovesTheEntireSlotAndAbsentPicturesDoNotStayStale() {
        assertTrue(save(2, "old"))
        assertTrue(files.saveSlot(2, { it.writeText("new"); true }))
        assertFalse(files.thumbFile(2).isFile)
        assertFalse(SnapshotCache.fileFor(files.stateFile(2)).isFile)
        files.deleteSlot(2)
        assertFalse(files.exists(2)); assertFalse(files.bakFile(2).isFile)
        assertFalse(files.bakThumbFile(2).isFile)
        assertFalse(SnapshotCache.fileFor(files.bakFile(2)).isFile)
    }

    @Test fun theRomKeySurvivesARenameButNotChangedContents() {
        val a = root / "a.gba"; val b = root / "b.gba"
        a.writeText("same game"); b.writeText("same game")
        assertEquals(StateFiles.romKey(a), StateFiles.romKey(b))
        b.writeText("another game"); assertNotEquals(StateFiles.romKey(a), StateFiles.romKey(b))
    }

    @Test fun batteryBackupsKeepDistinctVersionsAndNeverChangeTheOriginal() {
        val save = root / "game.sav"
        save.writeText("first"); StateFiles.backupBattery(save)
        StateFiles.backupBattery(save)
        assertEquals(1, (root / "pokedaisy-backups").listFiles().size)
        save.writeText("second"); StateFiles.backupBattery(save)
        assertEquals(2, (root / "pokedaisy-backups").listFiles().size)
        assertEquals("second", save.readText())
        StateFiles.backupBattery(save, keep = 1)
        assertEquals(1, (root / "pokedaisy-backups").listFiles().size)
        assertEquals("second", (root / "pokedaisy-backups").listFiles().single().readText())
    }

    @Test fun latestResumeSourceIgnoresEmptyStatesAndHonoursTimestamps() {
        files.resumeFile.writeText("resume")
        assertTrue(java.io.File(files.resumeFile.toString()).setLastModified(5_000))
        files.stateFile(3).writeText("newest")
        assertTrue(java.io.File(files.stateFile(3).toString()).setLastModified(10_000))
        files.stateFile(4).writeText("")
        assertEquals(files.stateFile(3), files.latestResumeSource())
    }
}
