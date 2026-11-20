package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.ShinyHunt
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One loaded game's hunt count and encounter latch, shared by the settings and sample threads. */
class GameShinyAlert(private val prefs: Prefs) {
    data class Alert(val name: String, val battles: Int) {
        fun message() = tr("SHINY {0}! After {1} wild battles", name, battles)
    }

    private val lock = SynchronizedObject()
    private var romKey: String? = null
    private var enabled = prefs.shinyAlert
    private var hunt = ShinyHunt()
    var count by mutableIntStateOf(0)
        private set

    fun start(key: String) = synchronized(lock) {
        romKey = key
        enabled = prefs.shinyAlert
        count = prefs.shinyHuntFor(key)
        hunt = ShinyHunt()
    }

    fun set(on: Boolean) = synchronized(lock) {
        if (on == enabled) return@synchronized
        prefs.shinyAlert = on
        enabled = on
        hunt = ShinyHunt()
        val key = romKey
        if (on && key != null) updateCount(key, 0)
    }

    fun observe(snapshot: SnapshotView, supported: Boolean): Alert? = synchronized(lock) {
        val key = romKey ?: return@synchronized null
        if (!enabled || !supported || !snapshot.connected) return@synchronized null
        val step = hunt.sample(snapshot.inBattle, ShinyHunt.wild(snapshot), snapshot.battleOpponent)
        val total = count + if (step.counted) 1 else 0
        val shiny = step.shiny
        if (shiny == null) {
            if (step.counted) updateCount(key, total)
            null
        } else {
            updateCount(key, 0)
            Alert(shiny.shownName, total)
        }
    }

    private fun updateCount(key: String, value: Int) {
        count = value
        prefs.setShinyHuntFor(key, value)
    }

    companion object {
        const val NOTICE_MILLIS = 8000L
    }
}
