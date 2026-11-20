@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.ui.PerfReading
import com.pokedaisy.app.companion.ui.PerformanceCounters
import com.pokedaisy.app.companion.ui.PerformanceOverlay
import com.pokedaisy.app.game.GamePerformanceObserver
import com.pokedaisy.app.game.GameViewController
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.delay
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.thermalState
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage
import kotlin.time.TimeSource

@Composable
internal fun IosPerformanceOverlay(game: GameViewController, telemetry: TelemetryStore, modifier: Modifier = Modifier) {
    val frames = remember(game) { IosPerformanceCounters() }
    DisposableEffect(game, frames) {
        game.performanceObserver = frames
        onDispose { if (game.performanceObserver === frames) game.performanceObserver = null }
    }
    val reading by produceState(PerfReading(), frames) {
        val process = NSProcessInfo.processInfo
        val cores = process.activeProcessorCount.toInt().coerceAtLeast(1)
        var previous = frames.snapshot(telemetry)
        var previousCpu = cpuNanos()
        while (true) {
            delay(500)
            val current = frames.snapshot(telemetry)
            val currentCpu = cpuNanos()
            val elapsed = current.atNanos - previous.atNanos
            val cpu = if (elapsed > 0 && currentCpu != null && previousCpu != null) {
                ((currentCpu - previousCpu) / elapsed.toDouble() * 100 / cores).toInt().coerceIn(0, 100)
            } else null
            value = current.since(previous, frames.takeMaxWork()).copy(
                cpu = cpu, cores = cores, speed = game.performanceSpeed,
                refreshesPerFrame = null, latePerSecond = null, audioDropouts = null, memoryMb = null,
                thermal = when (process.thermalState) {
                    NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> 0
                    NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> 1
                    NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> 2
                    NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> 3
                    else -> -1
                },
            )
            previous = current
            previousCpu = currentCpu
        }
    }
    PerformanceOverlay(reading, modifier)
}

private class IosPerformanceCounters : GamePerformanceObserver {
    private val clock = TimeSource.Monotonic.markNow()
    private var frames = 0L
    private var shown = 0L
    private var refreshes = 0L
    private var work = 0L
    private var maxWork = 0L

    override fun frame(workNanos: Long) {
        frames++
        work += workNanos
        maxWork = maxOf(maxWork, workNanos)
    }

    override fun refresh(submittedPicture: Boolean) {
        refreshes++
        if (submittedPicture) shown++
    }

    fun snapshot(telemetry: TelemetryStore) = PerformanceCounters(clock.elapsedNow().inWholeNanoseconds, frames, shown,
        work, telemetry.samplesTaken, telemetry.battleChecksTaken, refreshes)

    fun takeMaxWork(): Long = maxWork.also { maxWork = 0 }
}

private fun cpuNanos(): Long? = memScoped {
    val usage = alloc<rusage>()
    if (getrusage(RUSAGE_SELF, usage.ptr) != 0) return@memScoped null
    (usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) * 1_000_000_000L +
        (usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) * 1_000L
}
