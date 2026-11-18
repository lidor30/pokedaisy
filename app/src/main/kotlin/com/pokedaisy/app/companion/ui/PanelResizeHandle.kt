package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.sign

/**
 * A companion grip/lock that resizes along one axis and still lets its child handle a tap.
 * Accumulated pointer deltas keep the drag distance stable while the handle itself moves.
 * Platform hosts can share the gesture; the game's native touch-input path is independent of this handle.
 */
@Composable
fun PanelResizeHandle(
    vertical: Boolean,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    // Model updates while resizing keep the same gesture modifier and read the latest callbacks.
    val gesture = remember(vertical) {
        Modifier.pointerInput(vertical) {
            var dragging = false
            var distance = 0f
            var first = true
            val begin: (Offset) -> Unit = {
                dragging = true
                distance = 0f
                first = true
                start()
            }
            val finish: () -> Unit = {
                if (dragging) {
                    dragging = false
                    end()
                }
            }
            val moved: (PointerInputChange, Float) -> Unit = { change, amount ->
                // Keep the full touch distance after crossing slop; a mouse has its own smaller threshold.
                if (first && amount != 0f) {
                    distance = if (change.type == PointerType.Mouse) 0f else viewConfiguration.touchSlop * sign(amount)
                    first = false
                }
                distance += amount
                drag(distance)
            }
            try {
                if (vertical) detectVerticalDragGestures(begin, finish, finish, moved)
                else detectHorizontalDragGestures(begin, finish, finish, moved)
            } finally {
                finish()
            }
        }
    }
    Box(modifier.then(gesture)) { content() }
}
