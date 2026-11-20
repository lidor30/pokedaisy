package com.pokedaisy.app

/** Main-thread ownership of Android's process-wide JNI link session, including in-flight opens and closes. */
internal class LinkSessionLifecycle(
    private val slot: Slot,
    private val isFinishing: () -> Boolean,
    private val startPlaying: () -> Unit,
    private val closeNative: (() -> Unit) -> Unit,
) {
    class Slot {
        internal var owner: LinkSessionLifecycle? = null
    }

    private var resumed = false
    private var destroyed = false
    private var opened = false
    private var prepare: (() -> Unit)? = null

    val active: Boolean
        get() = resumed && opened && !destroyed && !isFinishing()

    fun beginOpen(): Boolean {
        if (slot.owner != null || destroyed || isFinishing()) return false
        slot.owner = this
        return true
    }

    fun openFailed(report: () -> Unit) {
        slot.owner = null
        if (!destroyed && !isFinishing()) report()
    }

    fun openSucceeded(prepare: () -> Unit) {
        opened = true
        if (destroyed || isFinishing()) {
            close()
        } else {
            this.prepare = prepare
            activate()
        }
    }

    fun resume() {
        resumed = true
        activate()
    }

    fun pause() {
        resumed = false
    }

    fun destroy() {
        destroyed = true
        close()
    }

    fun close(onClosed: () -> Unit = {}) {
        if (!opened) return
        opened = false
        prepare = null
        // Keep exclusive ownership until the old loop and native save finalization have both finished.
        closeNative {
            slot.owner = null
            if (!destroyed && !isFinishing()) onClosed()
        }
    }

    private fun activate() {
        if (!active) return
        val preparePlaying = prepare
        prepare = null
        preparePlaying?.invoke()
        startPlaying()
    }
}
