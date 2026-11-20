package com.pokedaisy.app

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Why the co-op game is held and why LOAD STATE / REWIND are refused, by reason, so two features turning the same
 * switch on and off never undo each other. Any reason on = held / blocked.
 */
object CoopGuards {
    enum class Hold { LOCK_START, LOCK_LINK, BATTLE }
    enum class StateBlock { LOCK, BATTLE }

    private val lock = SynchronizedObject()
    private val holds = mutableSetOf<Hold>()
    private val blocks = mutableSetOf<StateBlock>()

    @Volatile var held = false
        private set
    @Volatile var statesBlocked = false
        private set

    /** True from a co-op battle's start until its results are saved; set by the battle engine. */
    @Volatile var battleActive: () -> Boolean = { false }

    fun hold(reason: Hold, on: Boolean) = synchronized(lock) {
        if (on) holds.add(reason) else holds.remove(reason)
        held = holds.isNotEmpty()
    }

    fun blockStates(reason: StateBlock, on: Boolean) = synchronized(lock) {
        if (on) blocks.add(reason) else blocks.remove(reason)
        statesBlocked = blocks.isNotEmpty()
    }

    /** Whether this one reason blocks LOAD STATE / REWIND (the other reasons may too). */
    fun statesBlockedBy(reason: StateBlock): Boolean = synchronized(lock) { reason in blocks }

    /** The game closed: every reason off. [battleActive] stays as set. */
    fun reset() = synchronized(lock) {
        holds.clear(); blocks.clear()
        held = false; statesBlocked = false
    }
}
