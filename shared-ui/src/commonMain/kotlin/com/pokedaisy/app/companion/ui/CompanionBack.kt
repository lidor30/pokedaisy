package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The device's BACK button for the companion: whatever is open undoes itself
 * (an overlay closes, a summary returns to its list, a battle menu cancels),
 * newest first - like androidx's BackHandler, which needs an
 * OnBackPressedDispatcherOwner the Presentation, Paparazzi and ui-preview
 * don't have. The game's activity calls [back] on a BACK tap from either
 * screen; with nothing open it does nothing (hold BACK still leaves the game).
 */
class CompanionBack {
    private class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** Runs the newest enabled handler; false = nothing to go back from. */
    fun back(): Boolean {
        val e = entries.lastOrNull { it.enabled } ?: return false
        e.onBack()
        return true
    }

    @Composable
    internal fun Handler(enabled: Boolean, onBack: () -> Unit) {
        val current by rememberUpdatedState(onBack)
        val entry = remember { Entry(enabled) { current() } }
        SideEffect { entry.enabled = enabled }
        DisposableEffect(entry) {
            entries += entry
            onDispose { entries -= entry }
        }
    }
}

val LocalCompanionBack = staticCompositionLocalOf<CompanionBack?> { null }

/** [onBack] runs on the device's BACK while [enabled] and nothing newer is open. No-op outside the companion. */
@Composable
fun CompanionBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    LocalCompanionBack.current?.Handler(enabled, onBack)
}
