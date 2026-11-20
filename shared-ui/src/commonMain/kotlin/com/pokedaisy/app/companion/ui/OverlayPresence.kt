package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager

/** Paired ownership notifications, including removal of an open overlay from a retained screen. */
@Composable
internal fun OverlayPresence(active: Boolean, takesFocus: Boolean = false, changed: (Boolean) -> Unit) {
    val current by rememberUpdatedState(changed)
    val focus = if (takesFocus) LocalFocusManager.current else null
    // Take focus on entry, before the host accepts modal keys. Recomposition must leave any text field
    // inside this overlay alone; non-modal kept windows do not take focus from the companion.
    DisposableEffect(active, focus) {
        if (active) focus?.clearFocus(force = true)
        onDispose {}
    }
    DisposableEffect(Unit) { onDispose { current(false) } }
    SideEffect { current(active) }
}
