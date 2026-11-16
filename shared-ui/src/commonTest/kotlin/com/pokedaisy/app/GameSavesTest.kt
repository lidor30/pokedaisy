package com.pokedaisy.app

import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.SnapshotCache
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import okio.*

class GameSavesTest {
    private val fs = FileSystem.SYSTEM
    private inline fun temporary(test: (Path, StateFiles) -> Unit) {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-save-import-${Random.nextLong().toULong()}"
        fs.createDirectories(dir)
        try { test(dir, StateFiles(dir / "states")) } finally { fs.deleteRecursively(dir) }
    }
    private fun bytes(path: Path) = fs.read(path) { readByteArray() }
    private fun put(path: Path, value: String) = fs.write(path) { writeUtf8(value) }

    @Test fun replacementKeepsPreviousBatteryAndCommitsFreshBootBeforeTheSave() = temporary { dir, states ->
        val save = dir / "Diagnostic.sav"
        put(save, "old battery"); put(states.resumeFile, "old resume"); put(SnapshotCache.fileFor(states.resumeFile), "old cache")
        val result = assertIs<GameSaves.Result.Loaded>(GameSaves.load(save, "new battery".encodeToByteArray(), { states }))
        assertContentEquals("new battery".encodeToByteArray(), bytes(save))
        assertContentEquals("old battery".encodeToByteArray(), bytes(assertNotNull(result.backup)))
        assertTrue(fs.exists(states.freshBootFile)); assertFalse(fs.exists(states.resumeFile))
        assertFalse(fs.exists(SnapshotCache.fileFor(states.resumeFile)))
    }

    @Test fun rejectedInputNeverOpensStateStorageOrTouchesTheExistingSave() = temporary { dir, states ->
        val save = dir / "Diagnostic.sav"; put(save, "keep")
        for (picked in listOf(byteArrayOf(), ByteArray(GameSaves.MAX_BYTES + 1), "#RZIPv1#".encodeToByteArray())) {
            assertIs<GameSaves.Result.Failed>(GameSaves.load(save, picked, { error("invalid input requested state storage") }))
            assertContentEquals("keep".encodeToByteArray(), bytes(save))
        }
        assertFalse(fs.exists(states.freshBootFile)); assertTrue(GameSaves.backups(dir, "Diagnostic").isEmpty())
    }

    @Test fun failedCommitRestoresTheOldBatteryAndLeavesNoStagedFile() = temporary { dir, states ->
        val save = dir / "Diagnostic.sav"; put(save, "keep")
        put(states.resumeFile, "keep resume"); put(SnapshotCache.fileFor(states.resumeFile), "keep cache")
        val failing = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (source.name == "Diagnostic.sav.tmp" && target == save) throw IOException("injected commit failure")
                super.atomicMove(source, target)
            }
        }
        for (marker in listOf(null, "previous marker")) {
            if (marker != null) put(states.freshBootFile, marker)
            assertIs<GameSaves.Result.Failed>(GameSaves.load(save, "replace".encodeToByteArray(), { states }, fs = failing))
            assertContentEquals("keep".encodeToByteArray(), bytes(save))
            assertContentEquals("keep resume".encodeToByteArray(), bytes(states.resumeFile))
            assertContentEquals("keep cache".encodeToByteArray(), bytes(SnapshotCache.fileFor(states.resumeFile)))
            assertFalse(fs.exists(dir / "Diagnostic.sav.tmp"))
            if (marker == null) assertFalse(fs.exists(states.freshBootFile))
            else assertContentEquals(marker.encodeToByteArray(), bytes(states.freshBootFile))
        }
    }

    @Test fun failedRestorationKeepsTheBackupAndReportsItsName() = temporary { dir, states ->
        val save = dir / "Diagnostic.sav"; put(save, "keep")
        val failing = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (target == save) throw IOException("injected publication/restoration failure")
                super.atomicMove(source, target)
            }
        }
        val result = assertIs<GameSaves.Result.Failed>(GameSaves.load(save, "replace".encodeToByteArray(), { states }, fs = failing))
        val kept = GameSaves.backups(dir, "Diagnostic").single()
        assertContentEquals("keep".encodeToByteArray(), bytes(kept))
        assertTrue(result.reason.contains(kept.name)); assertTrue(fs.exists(states.freshBootFile))
    }

    @Test fun importsInTheSameSecondKeepDistinctReadableBackupsInOrder() = temporary { dir, states ->
        val save = dir / "Diagnostic.sav"; put(save, "first")
        val first = assertIs<GameSaves.Result.Loaded>(GameSaves.load(save, "second".encodeToByteArray(), { states }, now = 1_000L))
        val second = assertIs<GameSaves.Result.Loaded>(GameSaves.load(save, "third".encodeToByteArray(), { states }, now = 1_000L))
        assertNotEquals(first.backup, second.backup)
        assertTrue(assertNotNull(second.backup).name.endsWith(".sav"))
        assertEquals(listOf(second.backup, first.backup), GameSaves.backups(dir, "Diagnostic"))
        assertContentEquals("first".encodeToByteArray(), bytes(assertNotNull(first.backup)))
        assertContentEquals("second".encodeToByteArray(), bytes(second.backup))
        assertContentEquals("third".encodeToByteArray(), bytes(save))
        assertEquals("Diagnostic.backup-20261006-113012.sav", GameSaves.backupName("Diagnostic.sav",
            Instant.parse("2026-10-06T11:30:12Z").toEpochMilliseconds(), TimeZone.UTC))
    }
}
