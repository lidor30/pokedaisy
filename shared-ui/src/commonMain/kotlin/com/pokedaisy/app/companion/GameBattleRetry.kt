package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.SnapshotView
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Offers a retry only for the current trainer battle, after a successful capture and a sampled loss. */
class GameBattleRetry(private val prefs: Prefs) {
    private val lock = SynchronizedObject()
    private var enabled = prefs.battleRetry
    private var generation = 0
    private var attempted = false
    private var captured = false
    private var lost = false
    var asking by mutableStateOf(false)
        private set

    fun set(on: Boolean) = synchronized(lock) {
        prefs.battleRetry = on
        if (enabled != on) resetBattle()
        enabled = on
    }

    fun observe(snapshot: SnapshotView, allowed: Boolean, capture: () -> Boolean) {
        if (!snapshot.connected) return
        val version = synchronized(lock) {
            if (!enabled || !allowed || snapshot.blocked != null) {
                resetBattle()
                return
            }
            if (!snapshot.inBattle) {
                if (captured && lost) asking = true
                clearCapture()
                return
            }
            asking = false
            if (snapshot.party.isNotEmpty() && snapshot.party.all { it.isEgg || it.hp <= 0 }) lost = true
            if (attempted || snapshot.isTrainerBattle != true) return
            attempted = true
            ++generation
        }
        val saved = runCatching(capture).onFailure { Logs.w("battle retry capture failed", it) }.getOrDefault(false)
        synchronized(lock) { if (generation == version) captured = saved }
    }

    fun answer(retry: Boolean): Boolean = synchronized(lock) {
        val accepted = asking && retry
        asking = false
        accepted
    }

    fun reset() = synchronized(lock) { resetBattle() }

    private fun resetBattle() {
        asking = false
        clearCapture()
    }

    private fun clearCapture() {
        generation++
        attempted = false
        captured = false
        lost = false
    }
}
