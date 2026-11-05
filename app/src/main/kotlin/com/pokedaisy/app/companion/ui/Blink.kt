package com.pokedaisy.app.companion.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * A flag that flips every [halfPeriodMs] (false first) - the game's two-frame sprite animations (party icons, the map
 * cursor). It runs on the frame clock itself: an infinite transition followed the device's animation speed setting, so a
 * phone at 0.5x ran the game's sprites twice as fast, and at 0 they stood still. Infinite frames, so screenshot tests still
 * go idle, and the state only changes on a flip, so whatever draws from it redraws then, not every frame (a 60 Hz frame
 * counter read in a Canvas redrew the whole companion screen 60 times a second).
 */
@Composable
fun rememberBlink(halfPeriodMs: Int, enabled: Boolean = true): State<Boolean> {
    val on = remember { mutableStateOf(false) }
    if (enabled && halfPeriodMs > 0) {
        LaunchedEffect(halfPeriodMs) {
            val half = halfPeriodMs * 1_000_000L
            val start = withInfiniteAnimationFrameNanos { it }
            while (true) withInfiniteAnimationFrameNanos { on.value = (it - start) / half % 2 == 1L }
        }
    } else {
        SideEffect { on.value = false }
    }
    return on
}

/** GBA frames (60 per second) to milliseconds. */
fun gbaFramesMs(frames: Int): Int = frames * 1000 / 60

/** How long a party icon holds a pose, for [PartySlotLayout.ICON_FRAME_TICKS]' [ticks]. UpdateMonIconFrame shows a pose, then
 * counts its duration down to 0 before it looks again, and the loop's jump takes a frame of its own: a cycle is 2 x ticks + 3
 * frames (15 at full HP, a quarter longer than the table's 12). */
fun partyIconPoseMs(ticks: Int): Int = (2 * ticks + 3) * 1000 / 120
