package com.pokedaisy.app.game

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/** The input thread owns physical presses. The native frame loop can revoke them when rewind becomes unavailable. */
class RewindHold {
    enum class Source { HARDWARE, TOUCH }
    enum class Change { UNCHANGED, STARTED, STOPPED, DENIED }

    private val lock = SynchronizedObject()
    private val pressed = mutableSetOf<Source>()
    private val owners = mutableSetOf<Source>()

    @Volatile var active = false
        private set

    fun update(source: Source, down: Boolean, allowed: Boolean = true): Change = synchronized(lock) {
        if (down) {
            if (!pressed.add(source)) return@synchronized Change.UNCHANGED
            if (!allowed) return@synchronized Change.DENIED
            owners.add(source)
        } else {
            pressed.remove(source)
            owners.remove(source)
        }
        publish()
    }

    /** Existing presses stay quarantined until released. A new source may start once rewind is available again. */
    fun revoke(): Change = synchronized(lock) {
        owners.clear()
        publish()
    }

    /** A parser/capture reset discards physical presses as well. */
    fun clear(): Change = synchronized(lock) {
        pressed.clear()
        owners.clear()
        publish()
    }

    private fun publish(): Change {
        val next = owners.isNotEmpty()
        if (next == active) return Change.UNCHANGED
        active = next
        return if (next) Change.STARTED else Change.STOPPED
    }
}
