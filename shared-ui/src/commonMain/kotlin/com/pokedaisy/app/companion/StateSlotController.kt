package com.pokedaisy.app.companion

import com.pokedaisy.app.Hotkeys
import kotlinx.atomicfu.atomic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.i18n.tr
import okio.Path

/** Shared slot actions. The host serializes [onCore] between frames, and backs up battery data in [beforeLoad]. */
class StateSlotController(
    val files: StateFiles,
    private val onCore: (() -> Boolean) -> Boolean,
    private val write: (Path) -> Boolean,
    private val read: (Path) -> Boolean,
    private val beforeLoad: () -> Unit,
    private val thumbnail: () -> ByteArray? = { null },
    private val snapshot: () -> ByteArray? = { null },
    private val loaded: (Path) -> Unit = {},
    private val failed: (String) -> Unit = {},
    initialIndex: Int = 0,
    private val selected: (Int) -> Unit = {},
    private val completed: (Hotkeys.Action, Int, Boolean) -> Unit = { _, _, _ -> },
    private val allowed: (Hotkeys.Action?) -> Boolean = { true },
) : StateSlots {
    private val closed = atomic(false)
    fun close() { closed.value = true }
    override var currentIndex by mutableStateOf(initialIndex.coerceIn(0, 9))
        private set

    override fun list(): List<StateSlots.Slot> = if (closed.value) emptyList() else (0..9).map { i ->
        val state = files.stateFile(i)
        val thumb = files.thumbFile(i)
        StateSlots.Slot(i, files.exists(i), state.lastModified,
            thumb.takeIf { it.isFile }?.toString(), thumb.lastModified)
    }

    override fun requestSave(index: Int) {
        if (!select(index)) return
        perform(tr("Slot {0}: save failed", index), Hotkeys.Action.SAVE_STATE) {
            files.saveSlot(index, write, thumbnail(), snapshot())
        }
    }

    override fun requestLoad(index: Int) {
        if (!select(index)) return
        perform(tr("Slot {0}: nothing to load", index), Hotkeys.Action.LOAD_STATE) {
            loadWithUndo(files.stateFile(index))
        }
    }

    /** The trainer's start is separate from all interactive slots and their save backups. */
    fun captureRetry(): Boolean = runCatching {
        !closed.value && onCore { !closed.value && files.writeState(files.retryFile, write, snapshot = snapshot()) }
    }.getOrDefault(false)

    fun loadRetry() = perform(tr("Can't go back to that battle"), Hotkeys.Action.LOAD_STATE, index = -1) {
        loadWithUndo(files.retryFile)
    }

    private fun loadWithUndo(source: Path): Boolean {
        if (!source.isFile || source.length <= 0L) return false
        beforeLoad()
        // Refuse the load if its undo snapshot couldn't be made.
        if (!files.writeState(files.undoLoadFile, write, snapshot = snapshot())) return false
        return load(source)
    }

    override fun requestDelete(index: Int) {
        if (index !in 0..9) return
        perform(tr("Couldn't delete slot {0}", index)) { files.deleteSlot(index); true }
    }

    override fun requestUndoSave() = perform(tr("Nothing to undo"), Hotkeys.Action.UNDO_SAVE) { files.undoSave(currentIndex) }

    override fun requestUndoLoad() = perform(tr("Nothing to undo"), Hotkeys.Action.UNDO_LOAD) {
        if (!files.undoLoadFile.isFile) return@perform false
        beforeLoad()
        load(files.undoLoadFile)
    }

    private fun load(path: Path): Boolean {
        if (!read(path)) return false
        if (!closed.value) loaded(path)
        return true
    }

    /** Change the current slot without writing or loading a state. */
    fun select(index: Int): Boolean {
        if (closed.value || index !in 0..9) return false
        currentIndex = index
        selected(index)
        return true
    }

    private fun perform(message: String, kind: Hotkeys.Action? = null, index: Int = currentIndex, action: () -> Boolean) {
        if (closed.value || !allowed(kind)) return
        val ok = runCatching { onCore { !closed.value && action() } }.getOrDefault(false)
        if (closed.value) return
        if (!ok) failed(message)
        if (kind != null) completed(kind, index, ok)
    }
}
