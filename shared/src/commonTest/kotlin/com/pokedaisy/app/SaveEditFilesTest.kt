package com.pokedaisy.app

import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class SaveEditFilesTest {
    private val fs = FileSystem.SYSTEM
    private val previous = "old progress".encodeToByteArray()
    private val updated = "updated progress".encodeToByteArray()

    @Test fun aShortStagedWriteCannotReplaceTheBattery() = inTemp { dir ->
        val path = dir / "game.sav"
        val backup = dir / "backup.sav"
        path.writeBytes(previous)
        val disk = SaveEditFiles(
            backup = { fs.copy(it, backup); backup },
            writeSynced = { stage, bytes -> stage.writeBytes(bytes.copyOf(3)) },
            publish = { _, _ -> error("Publishing a truncated copy") },
        )

        assertIs<SaveEditFiles.Result.Failed>(disk.write(SaveEditFiles.Edit(path, previous, updated)))
        assertContentEquals(previous, path.readBytes())
        assertContentEquals(previous, backup.readBytes())
        assertEquals(setOf("game.sav", "backup.sav"), fs.list(dir).map { it.name }.toSet())
    }

    @Test fun aSaveChangedWhileStagingKeepsTheExternalWriter() = inTemp { dir ->
        val path = dir / "game.sav"
        val backup = dir / "backup.sav"
        val changed = "external change".encodeToByteArray()
        path.writeBytes(previous)
        val disk = SaveEditFiles(
            backup = { fs.copy(it, backup); backup },
            writeSynced = { stage, bytes -> stage.writeBytes(bytes); path.writeBytes(changed) },
            publish = { _, _ -> error("Publishing over an external change") },
        )

        assertIs<SaveEditFiles.Result.Failed>(disk.write(SaveEditFiles.Edit(path, previous, updated)))
        assertContentEquals(changed, path.readBytes())
        assertContentEquals(previous, backup.readBytes())
    }

    @Test fun staleFirstSaveRestoresTheSecondWithoutReplacingItsBackup() = inTemp { dir ->
        val a = dir / "alpha.sav"
        val b = dir / "beta.sav"
        val backup = dir / "beta-only-backup.sav"
        val external = "external change".encodeToByteArray()
        a.writeBytes(external)
        b.writeBytes(previous)
        var backups = 0
        val disk = SaveEditFiles(
            backup = { source ->
                // Models a one-backup retention policy.
                fs.delete(backup)
                fs.copy(source, backup)
                backups++
                backup
            },
            writeSynced = Path::writeBytes,
            publish = fs::atomicMove,
        )

        val result = disk.writeBoth(SaveEditFiles.Edit(a, previous, updated), SaveEditFiles.Edit(b, previous, updated))
        assertTrue(assertIs<SaveEditFiles.Result.Failed>(result).reason.contains("changed"))
        assertEquals(1, backups)
        assertContentEquals(external, a.readBytes())
        assertContentEquals(previous, b.readBytes())
        assertContentEquals(previous, backup.readBytes())
    }

    @Test fun aPublishedEditKeepsFreshBootIfTheResultIsInterrupted() = inTemp { dir ->
        val save = dir / "game.sav"
        val backup = dir / "backup.sav"
        val states = StateFiles(dir / "states")
        save.writeBytes(previous)
        states.resumeFile.writeBytes(previous)
        states.stateFile(4).writeBytes(previous)
        val disk = SaveEditFiles(
            backup = { fs.copy(it, backup); backup },
            writeSynced = Path::writeBytes,
            publish = { stage, target ->
                assertTrue(fs.exists(states.freshBootFile))
                fs.atomicMove(stage, target)
                error("Interrupted after replacing the battery")
            },
        )

        assertIs<SaveEditFiles.Result.Failed>(disk.write(SaveEditFiles.Edit(save, previous, updated)) { states })
        assertContentEquals(updated, save.readBytes())
        assertContentEquals(previous, backup.readBytes())
        assertContentEquals(previous, states.stateFile(4).readBytes())
        assertTrue(fs.exists(states.freshBootFile))
    }

    @Test fun aFailedReplacementRestoresAnExistingEmptyMarkerAndKeepsResume() = inTemp { dir ->
        val save = dir / "game.srm"
        val backup = dir / "backup.srm"
        val states = StateFiles(dir / "states")
        save.writeBytes(previous)
        states.resumeFile.writeBytes(previous)
        states.freshBootFile.writeBytes(byteArrayOf()) // Older Android editors used a zero-byte marker.
        val disk = SaveEditFiles(
            backup = { fs.copy(it, backup); backup },
            writeSynced = Path::writeBytes,
            publish = { _, _ -> error("Battery replacement failed") },
        )

        assertIs<SaveEditFiles.Result.Failed>(disk.write(SaveEditFiles.Edit(save, previous, updated)) { states })
        assertContentEquals(previous, save.readBytes())
        assertContentEquals(previous, states.resumeFile.readBytes())
        assertContentEquals(byteArrayOf(), states.freshBootFile.readBytes())
    }

    @Test fun anUnwritableMarkerPreventsBatteryPublish() = inTemp { dir ->
        val save = dir / "game.sav"
        val backup = dir / "backup.sav"
        val states = StateFiles(dir / "states")
        save.writeBytes(previous)
        states.resumeFile.writeBytes(previous)
        var published = false
        val disk = SaveEditFiles(
            backup = { fs.copy(it, backup); backup },
            writeSynced = { path, bytes ->
                check(path.parent != states.directory)
                path.writeBytes(bytes)
            },
            publish = { _, _ -> published = true },
        )

        assertIs<SaveEditFiles.Result.Failed>(disk.write(SaveEditFiles.Edit(save, previous, updated)) { states })
        assertFalse(published)
        assertFalse(fs.exists(states.freshBootFile))
        assertContentEquals(previous, save.readBytes())
        assertContentEquals(previous, states.resumeFile.readBytes())
    }

    private fun inTemp(test: (Path) -> Unit) {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-edit-${Random.nextLong().toULong()}"
        fs.createDirectories(directory)
        try { test(directory) } finally { fs.deleteRecursively(directory) }
    }
}
