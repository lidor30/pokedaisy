package com.pokedaisy.app.companion

import androidx.compose.runtime.mutableStateOf

/**
 * The bag items the player starred on the ITEMS tab, for the running ROM only
 * (another game's ids mean other items): first in the bag's DEFAULT order, a star
 * on each. Compose state, so a tap shows at once; the activity [load]s each ROM's
 * set from Prefs (keyed by its CRC32) and every change is stored through `save`.
 * Previews start empty and store nothing.
 */
object FavoriteItems {
    private val state = mutableStateOf<Set<Int>>(emptySet())
    private var save: (Set<Int>) -> Unit = {}

    val ids: Set<Int> get() = state.value

    operator fun contains(itemId: Int): Boolean = itemId in state.value

    /** [stored] is this ROM's set; [save] writes a changed one back. */
    fun load(stored: Set<Int>, save: (Set<Int>) -> Unit) {
        state.value = stored
        this.save = save
    }

    fun toggle(itemId: Int) {
        val next = if (itemId in state.value) state.value - itemId else state.value + itemId
        state.value = next
        save(next)
    }
}
