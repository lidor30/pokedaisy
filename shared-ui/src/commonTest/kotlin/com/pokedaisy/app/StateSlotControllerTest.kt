package com.pokedaisy.app

import com.pokedaisy.app.companion.StateSlotController
import com.pokedaisy.app.companion.data.*
import kotlin.random.Random
import kotlin.test.*
import okio.FileSystem
import okio.SYSTEM

class StateSlotControllerTest {
    private val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pokedaisy-state-slots-${Random.nextLong().toULong()}")
        .also { FileSystem.SYSTEM.createDirectories(it) }
    private val files = StateFiles(root)
    private var game = 1
    private var rejectWrite = false
    private var rejectBackup = false
    private var backups = 0
    private var errors = 0
    private val slots = StateSlotController(files, onCore = { it() },
        write = { p -> if (rejectWrite) false else { p.writeText(game.toString()); true } },
        read = { p -> p.readText().toIntOrNull()?.let { game = it; true } ?: false },
        beforeLoad = { if (rejectBackup) error("backup unavailable"); backups++ },
        failed = { errors++ })
    @AfterTest fun clean() = root.deleteRecursively()

    @Test fun saveLoadAndBothUndosRestoreTheExpectedMoment() {
        slots.requestSave(3)
        game = 2; slots.requestSave(3)
        slots.requestUndoSave()
        game = 9; slots.requestLoad(3)
        assertEquals(1, game); assertEquals(3, slots.currentIndex)
        slots.requestUndoLoad()
        assertEquals(9, game); assertEquals(2, backups); assertEquals(0, errors)
    }

    @Test fun loadIsRefusedWhenTheBackupOrUndoSnapshotFails() {
        slots.requestSave(0)
        game = 5; rejectBackup = true; slots.requestLoad(0)
        assertEquals(5, game)
        rejectBackup = false; rejectWrite = true; slots.requestLoad(0)
        assertEquals(5, game); assertEquals(2, errors)
    }

    @Test fun deletesAndInvalidSlotRequestsDoNotReachOtherFiles() {
        slots.requestSave(2); slots.requestDelete(2)
        assertFalse(files.exists(2))
        slots.requestSave(-1); slots.requestLoad(10); slots.requestDelete(100)
        assertEquals(2, slots.currentIndex)
        assertEquals(0, errors)
        assertEquals(10, slots.list().size)
    }
    @Test fun selectingASlotDoesNotRunTheCoreOrWriteAState() {
        var coreCalls = 0
        var selected = -1
        val controller = StateSlotController(files, onCore = { coreCalls++; it() },
            write = { error("unexpected write") }, read = { error("unexpected load") }, beforeLoad = {},
            selected = { selected = it })
        assertTrue(controller.select(9)); assertEquals(9, selected)
        assertFalse(controller.select(10)); assertFalse(controller.select(-1))
        assertEquals(9, controller.currentIndex); assertEquals(0, coreCalls)
        assertTrue(controller.list().none { it.present })
    }


    @Test fun closingLeavesSavedFilesAndRejectsEveryNewOperation() {
        slots.requestSave(2)
        slots.close(); slots.close()
        game = 9
        slots.requestSave(2); slots.requestLoad(2); slots.requestDelete(2)
        slots.requestUndoLoad(); slots.requestUndoSave()
        assertFalse(slots.select(3))
        assertTrue(slots.list().isEmpty())
        assertTrue(files.exists(2)); assertEquals("1", files.stateFile(2).readText())
        assertEquals(9, game); assertEquals(0, backups); assertEquals(0, errors)
    }

    @Test fun retiringWhileWaitingForTheCorePreventsDiskAndCallbacks() {
        lateinit var controller: StateSlotController
        controller = StateSlotController(files, onCore = { action -> controller.close(); action() },
            write = { error("retired write") }, read = { error("retired read") }, beforeLoad = {},
            failed = { error("retired failure popup") }, completed = { _, _, _ -> error("retired result") })
        controller.requestSave(0)
        assertFalse(files.exists(0))
    }

    @Test fun retiringDuringAReadSuppressesLoadedAndResultCallbacks() {
        files.stateFile(0).writeText("1")
        lateinit var controller: StateSlotController
        controller = StateSlotController(files, onCore = { it() },
            write = { it.writeText("undo"); true }, read = { controller.close(); true }, beforeLoad = {},
            loaded = { error("retired telemetry") }, failed = { error("retired popup") },
            completed = { _, _, _ -> error("retired result") })
        controller.requestLoad(0)
        assertTrue(files.exists(0))
    }
}
