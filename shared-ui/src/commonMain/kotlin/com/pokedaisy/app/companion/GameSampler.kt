package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*

/** Captures on the game thread, decodes one immutable RAM image at a time, then delivers back there.
 * A burst of requests is coalesced into one follow-up; closing discards pending delivery. */
class GameSampler(
    private val prepare: () -> (() -> SnapshotView?)?,
    private val current: () -> Boolean,
    private val game: CoroutineDispatcher,
    private val after: (SnapshotView) -> Unit,
    private val asynchronous: Boolean = true,
    worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val closed = atomic(false)
    private val reading = atomic(false)
    private val again = atomic(false)
    private var timer: Job? = null

    fun request() {
        if (closed.value || !current()) return
        if (!reading.compareAndSet(false, true)) { again.value = true; return }
        val work = runCatching(prepare).onFailure { Logs.i("sample capture failed: $it") }.getOrNull()
        if (work == null) { reading.value = false; return }
        fun decode(): SnapshotView? = try {
            if (closed.value) null else work()
        } catch (t: Throwable) {
            Logs.i("sample failed: $t"); null
        } finally { reading.value = false }
        if (!asynchronous) finish(decode())
        else scope.launch { val value = decode(); withContext(game) { finish(value) } }
    }

    private fun finish(value: SnapshotView?) {
        if (closed.value || !current()) return
        value?.let(after)
        if (again.getAndSet(false)) request()
    }

    /** Hosts with a frame-based cadence call [request] themselves; iOS uses this foreground-safe timer. */
    fun startPeriodic(millis: Long) {
        require(millis > 0)
        timer?.cancel()
        timer = scope.launch {
            while (isActive) { delay(millis); withContext(game) { request() } }
        }
    }

    fun close() { closed.value = true; timer?.cancel(); scope.cancel() }

    companion object {
        fun forGame(telemetry: TelemetryStore, gameBoy: Boolean, nds: Boolean = false,
                    game: CoroutineDispatcher, current: () -> Boolean, after: (SnapshotView) -> Unit): GameSampler {
            val buffers = if (gameBoy) null else RamSnapshot.Buffers(nds)
            return GameSampler(prepare = {
                if (gameBoy) { { telemetry.refresh() } }
                else RamSnapshot.capture(buffers!!)?.let(telemetry::deferredRefresh)
            }, current, game, after, asynchronous = !gameBoy)
        }
    }
}
