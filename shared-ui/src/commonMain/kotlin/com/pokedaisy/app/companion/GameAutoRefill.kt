package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.GameCalls
import com.pokedaisy.app.companion.data.ItemUseOutcome
import com.pokedaisy.app.companion.data.ItemUseResult
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.RepelRefill
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.game.GameSession
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Tracks the active Repel from samples and schedules at most one refill between game frames. */
class GameAutoRefill(private val prefs: Prefs, private val session: GameSession) {
    private val lock = SynchronizedObject()
    private var previousSteps = 0
    private var activeKind: Int? = null
    private var pending = false
    private var generation = 0
    private var enabled = prefs.repelRefill

    fun set(on: Boolean) {
        prefs.repelRefill = on
        synchronized(lock) {
            enabled = on
            if (!on) {
                generation++
                pending = false
            }
        }
    }

    fun observe(
        snapshot: SnapshotView,
        steps: Int,
        prepare: (Pair<Int, Int>) -> ((GameCalls, Boolean) -> ItemUseOutcome)?,
        used: (Int) -> Unit,
    ) {
        if (!snapshot.connected) return
        val (version, kind) = synchronized(lock) {
            if (steps == 0) activeKind = null
            else if (previousSteps == 0) activeKind = RepelRefill.kindOf(REPEL_KINDS, steps)
            previousSteps = steps
            if (!enabled || pending || snapshot.inBattle || steps !in 1..RepelRefill.REFILL_AT) return
            val next = RepelRefill.pick(REPEL_KINDS, { id -> snapshot.items.any { it.itemId == id && it.quantity > 0 } }, activeKind)
                ?: return
            pending = true
            generation to next
        }
        val work = runCatching { prepare(kind) }.onFailure { Logs.w("repel refill preparation failed", it) }.getOrNull()
        if (work == null) {
            finish(version, kind.first, null)
            return
        }
        session.requestField(false) { calls, busy ->
            if (!synchronized(lock) { enabled && version == generation }) return@requestField
            val outcome = runCatching { work(calls, busy) }.onFailure { Logs.w("repel refill failed", it) }.getOrNull()
            if (finish(version, kind.first, outcome)) used(kind.first)
        }
    }

    private fun finish(version: Int, item: Int, outcome: ItemUseOutcome?): Boolean = synchronized(lock) {
        if (version != generation) return@synchronized false
        pending = false
        val ran = outcome?.result == ItemUseResult.USED
        if (ran) activeKind = item
        ran
    }

    /** Jobs are invalidated before resetting or replacing the current game's memory. */
    fun reset() = synchronized(lock) {
        generation++
        previousSteps = 0
        activeKind = null
        pending = false
    }
}
