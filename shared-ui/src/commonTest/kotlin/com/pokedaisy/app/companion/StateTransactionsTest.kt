package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.data.*
import kotlin.random.Random
import kotlin.test.*
import okio.FileSystem
import okio.Path

class StateTransactionsTest {
    private inline fun temporary(test: (StateFiles) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-state-transactions-${Random.nextLong().toULong()}"
        val files = StateFiles(root, extraSidecars = listOf(".ra"))
        try { test(files) } finally { root.deleteRecursively() }
    }
    private fun ra(path: Path) = path.parent!! / "${path.name}.ra"

    @Test fun stateAndAchievementSidecarsFollowSaveUndoAndDelete() = temporary { files ->
        var moment = "first"
        var progress: String? = "first achievements"
        val events = mutableListOf<Triple<Hotkeys.Action, Int, Boolean>>()
        val slots = StateSlotController(files, onCore = { it() }, write = { path ->
            path.writeText(moment); progress?.let { ra(path).writeText(it) }; true
        }, read = { path -> moment = path.readText(); progress = ra(path).takeIf { it.isFile }?.readText(); true },
            beforeLoad = {}, snapshot = { moment.encodeToByteArray() },
            completed = { action, index, ok -> events += Triple(action, index, ok) })
        slots.requestSave(4)
        moment = "second"; progress = null; slots.requestSave(4)
        assertFalse(ra(files.stateFile(4)).isFile)
        assertEquals("first achievements", ra(files.bakFile(4)).readText())
        slots.requestUndoSave()
        assertEquals("first achievements", ra(files.stateFile(4)).readText())
        assertEquals("first", SnapshotCache.fileFor(files.stateFile(4)).readText())
        moment = "live"; progress = "live achievements"; slots.requestLoad(4)
        assertEquals("first", moment); assertEquals("first achievements", progress)
        slots.requestUndoLoad()
        assertEquals("live", moment); assertEquals("live achievements", progress)
        assertEquals(5, events.size); assertTrue(events.all { it.second == 4 && it.third })
        slots.requestDelete(4)
        assertFalse(files.exists(4)); assertFalse(ra(files.stateFile(4)).isFile)
        assertFalse(ra(files.bakFile(4)).isFile)
    }

    @Test fun failedPartialWritesPreserveSlotsAndNeverReuseStagedSidecars() = temporary { files ->
        assertTrue(files.saveSlot(0, { path -> path.writeText("good"); ra(path).writeText("good progress"); true }))
        assertFalse(files.saveSlot(0, { path -> path.writeText("partial"); ra(path).writeText("partial progress"); false }))
        assertEquals("good", files.stateFile(0).readText())
        assertEquals("good progress", ra(files.stateFile(0)).readText())
        assertFalse((files.directory / "ss0.tmp").isFile)
        assertFalse((files.directory / "ss0.tmp.ra").isFile)
        assertTrue(files.saveSlot(0, { path -> path.writeText("no progress"); true }))
        assertFalse(ra(files.stateFile(0)).isFile)
        assertTrue(files.undoSave(0)); assertEquals("good progress", ra(files.stateFile(0)).readText())
    }

    @Test fun failedUndoCaptureRefusesTheLoadAndReportsFailure() = temporary { files ->
        files.stateFile(1).writeText("saved")
        var loads = 0
        var result: Boolean? = null
        val slots = StateSlotController(files, onCore = { it() }, write = { false },
            read = { loads++; true }, beforeLoad = {}, completed = { action, index, ok ->
                assertEquals(Hotkeys.Action.LOAD_STATE, action); assertEquals(1, index); result = ok
            })
        slots.requestLoad(1)
        assertEquals(0, loads); assertEquals(false, result)
    }
}
