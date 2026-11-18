package com.pokedaisy.app.game

import com.pokedaisy.app.companion.data.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Per-game work and field visibility shared by the two frame loops. Posting is thread safe; servicing,
 * scene observation, reset and close belong to the game thread, between frames. Render/audio pacing
 * stays with the host. A host services posted work, applies dirty patches, then services field work.
 */
class GameSession(
    private val calls: GameCalls,
    private val callSafe: () -> Boolean,
    private val failed: (String, Throwable) -> Unit,
) {
    private class FieldJob(val inBattle: Boolean, var delay: Int, val run: (GameCalls, Boolean) -> Unit) {
        var waited = 0
    }
    private val lock = SynchronizedObject()
    private val posted = ArrayDeque<() -> Unit>()
    private val fields = ArrayDeque<FieldJob>()
    private var open = true
    var mapOpen = false
        private set
    var menuOpen = false
        private set
    var onField = false
        private set
    private var fieldFrames = 0

    fun post(job: () -> Unit) = synchronized(lock) { if (open) posted.addLast(job) }
    fun requestField(inBattle: Boolean, delayFrames: Int = 0, run: (GameCalls, Boolean) -> Unit) =
        synchronized(lock) { if (open) fields.addLast(FieldJob(inBattle, delayFrames, run)) }

    /** Snapshot the batch: work posted by a callback waits until the next service pass. */
    fun servicePosted() {
        val count = synchronized(lock) { posted.size }
        repeat(count) {
            val job = synchronized(lock) { if (open) posted.removeFirstOrNull() else null } ?: return@repeat
            runCatching(job).onFailure { failed("emu job failed", it) }
        }
    }

    /** Jobs wait up to 120 advancing frames for a safe GBA depth, then report their own call failure.
     * Skip this call while a host holds the core without advancing frames (co-op / rewind). */
    fun serviceField(gba: Boolean, gbOnField: Boolean) {
        val count = synchronized(lock) { fields.size }
        repeat(count) {
            val job = synchronized(lock) { if (open) fields.removeFirstOrNull() else null } ?: return@repeat
            if (job.delay > 0) {
                job.delay--
                requeue(job)
            } else if (gba && job.waited < CALL_WAIT_FRAMES && !callSafe()) {
                job.waited++
                requeue(job)
            } else {
                val busy = job.inBattle || if (gba) menuOpen || mapOpen else !gbOnField
                runCatching { job.run(calls, busy) }.onFailure { failed("field item job failed", it) }
            }
        }
    }

    private fun requeue(job: FieldJob) = synchronized(lock) { if (open) fields.addLast(job) }

    /** GBA watcher policy, including shops staying fast; the DS host supplies its existing menu read. */
    fun observeScreens(gba: Boolean, dsMenu: Boolean = false, reader: MemoryReader = InProcessReader) {
        mapOpen = gba && RegionMapWatch.isOpen(reader)
        menuOpen = gba && FfMenuWatch.tick(reader) && !ShopWatch.isOpen(reader) || dsMenu
    }

    /** Positive field detection; fades keep the prior value, field re-entry settles for twenty frames. */
    fun observeField(screen: Boolean, wideScene: Int, battleState: Int, holdPicture: Boolean) {
        if (wideScene == SCENE_FADE) return
        val field = screen && !holdPicture && battleState == BATTLE_INPUT_NONE &&
            (wideScene == -1 || wideScene == SCENE_FIELD)
        fieldFrames = if (field) (fieldFrames + 1).coerceAtMost(FIELD_SETTLE_FRAMES) else 0
        onField = fieldFrames >= FIELD_SETTLE_FRAMES
    }

    /** A new game cannot inherit delayed calls or field visibility from its predecessor. */
    fun reset() {
        close()
        synchronized(lock) { open = true }
    }

    fun close() {
        synchronized(lock) { open = false; posted.clear(); fields.clear() }
        mapOpen = false; menuOpen = false; onField = false; fieldFrames = 0
    }

    companion object {
        private const val CALL_WAIT_FRAMES = 120
        private const val FIELD_SETTLE_FRAMES = 20
        private const val SCENE_FIELD = 0
        private const val SCENE_FADE = 5
    }
}
