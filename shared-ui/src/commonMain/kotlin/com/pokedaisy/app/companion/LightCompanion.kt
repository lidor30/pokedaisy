package com.pokedaisy.app.companion

import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.i18n.tk

/** SETTINGS > DISPLAY OPTIONS > LIGHT COMPANION ([LightCompanion]); [id] is what Prefs keeps. */
enum class LightCompanionMode(val id: Int, val label: String) {
    AUTO(0, tk("AUTO")), ON(1, tk("ON")), OFF(2, tk("OFF"));

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * The companion made lighter for a slow device (an RG DS report: the game's buttons did nothing after FIGHT until the
 * companion had drawn the move list). The device's buttons reach the game on the UI thread, the one that draws the
 * companion too, so on a weak chip a busy companion leaves the game's presses waiting. While [on]:
 *  - a sample reaches the companion only when something it shows changed, at most every [MIN_UPDATE_MS]
 *    (TelemetryStore; a battle's menu changing always goes through at once);
 *  - its animations are off (CompanionTweaks' icon bounce, map cursor blink, tab slides; the battle pane's crossfade);
 *  - SHADERS > ON COMPANION is off (a shader over every companion view, each frame).
 * AUTO (the default) turns it on for a device that looks weak ([deviceWeak], from its CPU and memory) or once the
 * game's buttons have come late [STRIKES] times within [STRIKE_WINDOW_MS] ([noteKeyDelay]), which is remembered.
 * Compose state, so both screens follow a change at once.
 */
object LightCompanion {
    /** What the player picked. */
    var mode by mutableStateOf(LightCompanionMode.AUTO)
    /** AUTO's verdict: the device looked weak at start, or its buttons have come late here before. */
    var deviceWeak by mutableStateOf(false)

    /** Whether the companion runs light now. */
    val on: Boolean get() = when (mode) {
        LightCompanionMode.ON -> true
        LightCompanionMode.OFF -> false
        LightCompanionMode.AUTO -> deviceWeak
    }

    /** A sample reaches the companion at most this often while [on] (a battle's menu changing aside). */
    const val MIN_UPDATE_MS = 500L

    /** A game button handled this late (ms after it was pressed) counts as a strike. */
    const val LATE_KEY_MS = 150L
    const val STRIKES = 3
    const val STRIKE_WINDOW_MS = 60_000L

    private val strikes = ArrayDeque<Long>()
    private val strikesLock = SynchronizedObject()

    /**
     * A game button reached the game [delayMs] after it was pressed ([nowMs] = uptime). Returns true when that made
     * AUTO decide the device is weak (the caller remembers it for the next start).
     */
    fun noteKeyDelay(delayMs: Long, nowMs: Long): Boolean {
        if (delayMs < LATE_KEY_MS || deviceWeak) return false
        synchronized(strikesLock) {
            strikes.addLast(nowMs)
            while (strikes.isNotEmpty() && nowMs - strikes.first() > STRIKE_WINDOW_MS) strikes.removeFirst()
            if (strikes.size < STRIKES) return false
            strikes.clear()
        }
        deviceWeak = true
        return true
    }
}
