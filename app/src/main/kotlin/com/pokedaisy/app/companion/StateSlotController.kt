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
            if (!files.exists(index)) return@perform false
            beforeLoad()
            // Refuse the load if its undo snapshot couldn't be made.
            if (!files.writeState(files.undoLoadFile, write, snapshot = snapshot())) return@perform false
            load(files.stateFile(index))
        }
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

    private fun perform(message: String, kind: Hotkeys.Action? = null, action: () -> Boolean) {
        if (closed.value) return
        val index = currentIndex
        val ok = runCatching { onCore { !closed.value && action() } }.getOrDefault(false)
        if (closed.value) return
        if (!ok) failed(message)
        if (kind != null) completed(kind, index, ok)
    }
}
