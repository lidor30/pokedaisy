package com.pokedaisy.app.ios

import com.pokedaisy.app.SaveEditFiles
import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class IosSaveEditorFilesTest {
    @Test fun committedEditsKeepTheOriginalBatteryAndClearOnlyAutomaticResume() = isolated { base ->
        val battery = base / "game.sav"
        val states = StateFiles(base / "states")
        battery.writeText("before")
        states.resumeFile.writeText("old automatic resume")
        states.stateFile(4).writeText("manual")
        val expected = battery.readBytes()
        val edited = "after".encodeToByteArray()

        val result = assertIs<SaveEditFiles.Result.Written>(commitIosSaveEdit(battery, states, expected, edited))

        assertContentEquals(expected, result.backup.readBytes())
        assertContentEquals(edited, battery.readBytes())
        assertContentEquals("manual".encodeToByteArray(), states.stateFile(4).readBytes())
        assertContentEquals("fresh".encodeToByteArray(), states.freshBootFile.readBytes())
        assertFalse(FileSystem.SYSTEM.exists(states.resumeFile))
    }

    @Test fun aStaleEditDoesNotSetFreshBootOrCreateABackup() = isolated { base ->
        val battery = base / "game.sav"
        val states = StateFiles(base / "states")
        battery.writeText("other editor")
        states.resumeFile.writeText("automatic")
        val expected = "old".encodeToByteArray()

        assertIs<SaveEditFiles.Result.Failed>(commitIosSaveEdit(battery, states, expected, "new".encodeToByteArray()))
        assertContentEquals("other editor".encodeToByteArray(), battery.readBytes())
        assertTrue(FileSystem.SYSTEM.exists(states.resumeFile))
        assertFalse(FileSystem.SYSTEM.exists(states.freshBootFile))
        assertTrue(StateFiles.batteryBackups(battery).isEmpty())
    }

    private fun isolated(action: (Path) -> Unit) {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-ios-save-edit-${Random.nextLong().toULong()}"
        FileSystem.SYSTEM.createDirectories(directory)
        try { action(directory) } finally { FileSystem.SYSTEM.deleteRecursively(directory) }
    }
}
