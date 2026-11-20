package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.i18n.tr

/** Selection lifetime for Link Play, including duplicate cartridges and stale native scans. */
class LinkSetupFlow<T : Any> {
    class Request internal constructor(val slot: Int)
    class Choice<T : Any> internal constructor(val slot: Int, val games: List<T>)

    private var request: Request? = null
    private val selected = mutableStateListOf<T>()
    val slots: List<T> get() = selected
    val canStart: Boolean get() = slots.size >= MIN_GAMES
    var choice by mutableStateOf<Choice<T>?>(null)
        private set

    fun seed(game: T) { if (selected.isEmpty()) selected.add(game) }

    fun begin(slot: Int): Request? {
        if (slot !in 0..selected.size || slot >= MAX_GAMES) return null
        choice = null
        return Request(slot).also { request = it }
    }

    /** True only for the active scan. Its host handles the empty library message. */
    fun ready(scan: Request, games: List<T>): Boolean {
        if (request !== scan) return false
        request = null
        if (games.isNotEmpty()) choice = Choice(scan.slot, games)
        return true
    }

    fun choose(pick: Choice<T>, game: T?) {
        if (choice !== pick) return
        choice = null
        val index = pick.slot
        if (game == null) {
            if (index < selected.size) selected.removeAt(index)
        } else if (game in pick.games) {
            if (index < selected.size) selected[index] = game else selected.add(game)
        }
    }

    fun clearPicker() {
        request = null
        choice = null
    }

    fun dismiss(pick: Choice<T>) { if (choice === pick) choice = null }

    fun rowLabel(index: Int, label: (T) -> String, key: (T) -> String): String {
        val game = selected[index]
        val copies = selected.take(index).count { key(it) == key(game) }
        return if (copies == 0) label(game) else tr("{0}, save {1}", label(game), copies + 1)
    }

    companion object {
        const val MIN_GAMES = 2
        const val MAX_GAMES = 4
    }
}
