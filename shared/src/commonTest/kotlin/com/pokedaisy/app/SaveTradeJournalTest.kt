package com.pokedaisy.app

import com.pokedaisy.app.companion.data.StateFiles
import com.pokedaisy.app.companion.data.StateResume
import com.pokedaisy.app.companion.data.readBytes
import com.pokedaisy.app.companion.data.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class SaveTradeJournalTest {
    private val fs = FileSystem.SYSTEM

    @Test fun aPartlyWrittenTradeRollsBackAndKeepsAnExternalChange() = fixture { file, journal, a, b ->
        journal.write(a, b)
        b.edit.path.writeBytes(b.edit.updated)
        val external = "an unrelated game save".encodeToByteArray()
        a.edit.path.writeBytes(external)

        val recovered = SaveTradeJournal(file, Path::writeBytes)
        assertTrue(recovered.recover({ true }) { error("A partial trade must not clear resume state") })
        assertContentEquals(external, a.edit.path.readBytes())
        assertContentEquals(b.edit.expected, b.edit.path.readBytes())
        assertFalse(fs.exists(file))
    }

    @Test fun bothWrittenSavesStayCommittedWhenFinalizationWasInterrupted() = fixture { file, journal, a, b ->
        journal.write(a, b)
        a.edit.path.writeBytes(a.edit.updated)
        b.edit.path.writeBytes(b.edit.updated)
        val finished = mutableListOf<String>()
        assertFalse(journal.recover({ true }) { side ->
            if (side.stateKey == b.stateKey) error("Finalization interrupted")
            finished += side.stateKey
        })
        assertTrue(fs.exists(file))

        val reopened = SaveTradeJournal(file, Path::writeBytes)
        assertTrue(reopened.recover({ true }) { finished += it.stateKey })
        assertContentEquals(a.edit.updated, a.edit.path.readBytes())
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())
        assertEquals(listOf(a.stateKey, a.stateKey, b.stateKey), finished)
        assertFalse(fs.exists(file))
    }

    @Test fun unrecognizedPathOrFailedRecoveryKeepsTheJournal() = fixture { file, journal, a, b ->
        journal.write(a, b)
        b.edit.path.writeBytes(b.edit.updated)

        assertFalse(journal.recover({ it.stateKey != b.stateKey }) { error("Rejected path") })
        assertTrue(fs.exists(file))
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())

        val unwritable = SaveTradeJournal(file, { _, _ -> error("Could not sync the rollback") })
        assertFalse(unwritable.recover({ true }) { error("Partial trade") })
        assertTrue(fs.exists(file))
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())

        assertTrue(journal.recover({ true }) { error("Partial trade") })
        assertContentEquals(b.edit.expected, b.edit.path.readBytes())
        assertFalse(fs.exists(file))
    }

    @Test fun aDetachedSaveFolderDefersRecoveryUntilBothSavesAreVisible() = fixture { file, journal, a, b ->
        journal.write(a, b)
        b.edit.path.writeBytes(b.edit.updated)
        fs.delete(a.edit.path)

        assertFalse(journal.recover({ true }) { error("A save is unavailable") })
        assertTrue(fs.exists(file))
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())

        a.edit.path.writeBytes(a.edit.expected)
        assertTrue(journal.recover({ true }) { error("Partial trade") })
        assertContentEquals(b.edit.expected, b.edit.path.readBytes())
        assertFalse(fs.exists(file))
    }

    @Test fun reopeningAfterAMarkerFailureFinishesBothSidesBeforeResume() = fixture { file, journal, a, b ->
        val first = StateFiles(file.parent!! / a.stateKey)
        val second = StateFiles(file.parent!! / b.stateKey)
        val states = mapOf(a.stateKey to first, b.stateKey to second)
        for (state in states.values) {
            state.resumeFile.writeBytes(byteArrayOf(1))
            state.stateFile(2).writeBytes(byteArrayOf(2))
        }

        fun host(sync: (Path, ByteArray) -> Unit) = ClosedSaveEdits(
            files = SaveEditFiles(
                backup = { StateFiles.backupBattery(it) },
                writeSynced = sync,
                publish = fs::atomicMove,
            ),
            journal = journal,
            accept = { it.stateKey in states },
            states = states::getValue,
        )

        var journalSeen = false
        val crashed = host { path, bytes ->
            if (path.parent == file.parent) journalSeen = fs.exists(file)
            check(path.parent != second.directory)
            path.writeBytes(bytes)
        }
        assertIs<SaveEditFiles.Result.Failed>(crashed.trade(a, b))
        assertTrue(journalSeen)
        assertTrue(fs.exists(file))
        assertTrue(fs.exists(first.freshBootFile))
        assertFalse(fs.exists(second.freshBootFile))

        assertTrue(host(Path::writeBytes).recover())
        assertFalse(fs.exists(file))
        for (state in states.values) {
            assertNull(StateResume.stage(state))
            assertFalse(fs.exists(state.resumeFile))
            assertContentEquals(byteArrayOf(2), state.stateFile(2).readBytes())
        }
        assertContentEquals(a.edit.updated, a.edit.path.readBytes())
        assertContentEquals(b.edit.updated, b.edit.path.readBytes())
    }

    private fun fixture(action: (Path, SaveTradeJournal, SaveTradeJournal.Side, SaveTradeJournal.Side) -> Unit) {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-trade-${Random.nextLong().toULong()}"
        fs.createDirectories(dir)
        try {
            val file = dir / "pending"
            fun side(name: String): SaveTradeJournal.Side {
                val path = dir / "$name.sav"
                val expected = "$name original".encodeToByteArray()
                path.writeBytes(expected)
                return SaveTradeJournal.Side(SaveEditFiles.Edit(path, expected, "$name edited".encodeToByteArray()), "$name-key")
            }
            action(file, SaveTradeJournal(file, Path::writeBytes), side("a"), side("b"))
        } finally {
            fs.deleteRecursively(dir)
        }
    }
}
