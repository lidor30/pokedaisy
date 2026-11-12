package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import java.util.Locale

/** What SETTINGS > FPS / CPU shows, measured over the last half second. */
data class PerfReading(
    /** Frames the core ran per second (~60 at 1x; more while fast-forwarding). */
    val fps: Float = 0f,
    /** New frames that reached the screen per second (the display caps it; below [fps] at 1x = dropped). */
    val shownFps: Float = 0f,
    /** Refreshes per second whose frame came too late to wait for, so it showed a refresh later (a stutter). */
    val latePerSecond: Float = 0f,
    /** The emu thread's work per frame (the core, audio, the companion's reads), average / worst, ms. */
    val frameMs: Float = 0f,
    val frameMaxMs: Float = 0f,
    /** The emu thread's busy share of the time: near 100 it can't keep up. */
    val emulationLoad: Int = 0,
    /** The app's share of the whole CPU (all cores); null = not measured yet. */
    val cpu: Int? = null,
    val cores: Int = 0,
    /** The game screen's measured refresh (0 = unknown) and refreshes per game frame (0 = timer pacing). */
    val refreshHz: Float = 0f,
    val refreshesPerFrame: Int = 0,
    /** Why 1x runs on the timer when it does: 0 not a fallback, 1 the audio's (underruns under vsync pacing), 2 the
     * screen's (its ticks don't follow its refresh) - EmulatorEngine.TIMER_*. */
    val timerFallback: Int = 0,
    /** 1 = real time; Infinity = fast-forward uncapped. */
    val speed: Float = 1f,
    /** Times the audio ran dry since the game started (each one a crackle). */
    val audioDropouts: Int = 0,
    val batteryTempC: Float? = null,
    /** Android's thermal status (PowerManager.THERMAL_STATUS_*), -1 = unknown. */
    val thermal: Int = -1,
    val memoryMb: Int = 0,
    /** The companion's reads of the game per second: full snapshots (1, 4 in a battle) and the fast
     * battle-menu checks (~15, every frame while a switch runs). */
    val snapshotsPerSecond: Float = 0f,
    val battleChecksPerSecond: Float = 0f,
)

/**
 * SETTINGS > FPS / CPU, flush in the game screen's top-left corner like other emulators show it: the
 * game's frames per second and the app's CPU share in white pixel text on a small dark box. A tap on it -
 * or near it: the touch area reaches well past the small text - opens the whole [PerfReading], as shadowed
 * text straight over the game (no box: the user's call); another tap closes it.
 */
@Composable
fun PerformanceOverlay(reading: PerfReading, modifier: Modifier = Modifier, initiallyOpen: Boolean = false) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val tap = Modifier.clickable(interactionSource = noRipple, indication = null) { open = !open }
    if (!open) {
        // The dark box hugs the text; the padding outside it is the touch area (clear, so the game shows).
        Box(modifier.then(tap).padding(end = TOUCH_REACH, bottom = TOUCH_REACH)) {
            Line(
                "%.1f FPS  CPU %s".format(Locale.ROOT, reading.fps, pct(reading.cpu)), m,
                Modifier.background(Color(0xB0000000)).padding(horizontal = u * 3, vertical = u * 1),
            )
        }
        return
    }
    Column(
        modifier
            .then(tap)
            .padding(horizontal = u * 3, vertical = u * 1),
    ) {
        Line(tr("PERFORMANCE"), m, color = PANEL_TITLE)
        Spacer(Modifier.padding(top = u * 2))
        val r = reading
        val stats = listOf(
            Stat("FPS", "%.1f".format(Locale.ROOT, r.fps)),
            Stat(tr("ON SCREEN"), "%.1f".format(Locale.ROOT, r.shownFps)),
            Stat(tr("LATE"), "%.1f/s".format(Locale.ROOT, r.latePerSecond), warn = r.latePerSecond >= 1f),
            Stat(tr("FRAME TIME"), "%.1f / %.1f ms".format(Locale.ROOT, r.frameMs, r.frameMaxMs)),
            Stat(tr("EMULATION"), "${r.emulationLoad}%", warn = r.emulationLoad >= 85),
            Stat("CPU", pct(r.cpu) + (if (r.cores > 0) " / ${r.cores}" else "")),
            Stat(tr("SCREEN"), refreshText(r), warn = r.refreshesPerFrame == 0 && r.timerFallback != 0),
            Stat(tr("GAME SPEED"), if (r.speed.isInfinite()) "FF" else "%sx".format(Locale.ROOT, trimFloat(r.speed))),
            Stat(tr("AUDIO DROPOUTS"), "${r.audioDropouts}"),
            Stat(tr("BATTERY"), r.batteryTempC?.let { "%.1f °C".format(Locale.ROOT, it) } ?: "--"),
            Stat(tr("THERMAL"), tr(thermalLabel(r.thermal)), warn = r.thermal >= 2),
            Stat(tr("MEMORY"), "${r.memoryMb} MB"),
            Stat(tr("SNAPSHOTS"), "%.1f/s".format(Locale.ROOT, r.snapshotsPerSecond)),
            Stat(tr("BATTLE CHECKS"), "%.1f/s".format(Locale.ROOT, r.battleChecksPerSecond)),
        )
        // Two columns, the labels as wide as the widest (lines are all one height, so the rows line up).
        Row {
            Column { stats.forEach { GbaText(it.label, PANEL_LABEL, TEXT_SHADOW, m) } }
            Spacer(Modifier.width(u * 8))
            Column { stats.forEach { GbaText(it.value, if (it.warn) PANEL_WARN else Color.White, TEXT_SHADOW, m) } }
        }
    }
}

@Composable
private fun Line(text: String, m: GbaTextMetrics, modifier: Modifier = Modifier, color: Color = Color.White) =
    GbaText(text, color, TEXT_SHADOW, m, modifier)

private class Stat(val label: String, val value: String, val warn: Boolean = false)

private fun pct(v: Int?) = v?.let { "$it%" } ?: "--"

private fun trimFloat(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

private fun refreshText(r: PerfReading): String {
    val hz = if (r.refreshHz > 0f) "%.0f Hz".format(Locale.ROOT, r.refreshHz) else "--"
    if (r.refreshesPerFrame > 0) return "$hz · VSYNC ${r.refreshesPerFrame}:1"
    return "$hz · " + when (r.timerFallback) {
        1 -> tr("TIMER (AUDIO)")
        2 -> tr("TIMER (SCREEN)")
        else -> tr("TIMER")
    }
}

/** PowerManager.THERMAL_STATUS_* in a word: none OK, light WARM, moderate HOT, severe and up THROTTLING. */
private fun thermalLabel(status: Int): String = when {
    status < 0 -> "--"
    status == 0 -> tk("OK")
    status == 1 -> tk("WARM")
    status == 2 -> tk("HOT")
    else -> tk("THROTTLING")
}

/** How far the compact readout's touch area reaches past its text (right and down; it sits in the corner). */
private val TOUCH_REACH = 40.dp
private val TEXT_SHADOW = Color(0xFF202020)
private val PANEL_TITLE = Color(0xFFFFD866)
private val PANEL_LABEL = Color(0xFFB8BEC8)
private val PANEL_WARN = Color(0xFFFF6A5C)
