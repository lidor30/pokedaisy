package com.pokedaisy.app.companion

/**
 * Bridge for the bottom-screen savestate panel: it lists slots (backed by
 * SaveStates on disk) and asks the emulator to save/load. Implemented by
 * PokeDaisyActivity so the companion module stays independent of the emulator.
 */
interface StateSlots {
    data class Slot(
        val index: Int,
        val present: Boolean,
        val savedAtMillis: Long,
        val thumbPath: String?,
        /** The thumbnail file's write time: it lands just after the state, so a list read
         * between the two must still change (and redraw the card) once it does. */
        val thumbModifiedMillis: Long = 0,
    )

    /** All slots, 0..9, in order. */
    fun list(): List<Slot>

    /** The slot the hardware save/load hotkeys currently target. */
    val currentIndex: Int

    fun requestSave(index: Int)
    fun requestLoad(index: Int)

    /** Delete slot [index] and the files saved beside it (thumbnail, undo copy, cached data). */
    fun requestDelete(index: Int)

    /** Undo the most recent save (restore the current slot's previous contents). */
    fun requestUndoSave()

    /** Undo the most recent load (return to the state from just before it). */
    fun requestUndoLoad()
}
