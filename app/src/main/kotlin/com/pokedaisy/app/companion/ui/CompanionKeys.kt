package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.ui.theme.AppColors
import kotlinx.coroutines.launch

/** The game's buttons as the companion's window reads them (through the player's own GAME BUTTONS bindings); X / Y
 * only for a DS game. */
enum class MenuKey { UP, DOWN, LEFT, RIGHT, A, B, L, R, START, SELECT, X, Y }

/**
 * The game's buttons for the companion while it's open as a window over the game (one screen, SidePanel):
 * handlers take a key newest first, like [CompanionBack] - an overlay that steers its own cursor registers over the
 * tab under it, and returns false for keys it leaves alone. B never comes here: it's the companion's back
 * ([CompanionBack]), and with nothing left to go back from it closes the window.
 *
 * [repeat]: a key held down repeating (a direction, so a cursor runs down a list).
 */
class CompanionKeys {
    private class Entry(var enabled: Boolean, var onKey: (MenuKey, Boolean) -> Boolean)

    private val entries = mutableListOf<Entry>()
    private class StickEntry(var enabled: Boolean, var onStick: (Float, Float) -> Boolean)
    private val sticks = mutableListOf<StickEntry>()

    /** The left stick's position (-1..1 each way, as the pad reports it) to the newest enabled stick handler; false = none
     * wants it, and it steers as the D-pad does. */
    fun stick(x: Float, y: Float): Boolean = sticks.asReversed().any { it.enabled && it.onStick(x, y) }

    @Composable
    internal fun StickHandler(enabled: Boolean, onStick: (Float, Float) -> Boolean) {
        val current by rememberUpdatedState(onStick)
        val entry = remember { StickEntry(enabled) { x, y -> current(x, y) } }
        SideEffect { entry.enabled = enabled }
        DisposableEffect(entry) {
            sticks += entry
            onDispose { sticks -= entry }
        }
    }

    /** Hands [key] to the newest enabled handler that takes it; false = none did. */
    fun key(key: MenuKey, repeat: Boolean = false): Boolean =
        entries.asReversed().any { it.enabled && it.onKey(key, repeat) }

    @Composable
    fun Handler(enabled: Boolean, onKey: (MenuKey, Boolean) -> Boolean) {
        val current by rememberUpdatedState(onKey)
        val entry = remember { Entry(enabled) { k, r -> current(k, r) } }
        SideEffect { entry.enabled = enabled }
        DisposableEffect(entry) {
            entries += entry
            onDispose { entries -= entry }
        }
    }
}

val LocalCompanionKeys = staticCompositionLocalOf<CompanionKeys?> { null }

/** [onStick] gets the left stick's position while [enabled] (a smooth cursor: HeartGold / SoulSilver's map) instead of
 * D-pad steps; true = taken. No-op outside the companion's window. */
@Composable
fun CompanionStickHandler(enabled: Boolean = true, onStick: (x: Float, y: Float) -> Boolean) {
    LocalCompanionKeys.current?.StickHandler(enabled, onStick)
}

/** [onKey] gets the window's keys while [enabled] and no newer handler took them; true = taken. No-op outside it. */
@Composable
fun CompanionKeyHandler(enabled: Boolean = true, onKey: (key: MenuKey, repeat: Boolean) -> Boolean) {
    LocalCompanionKeys.current?.Handler(enabled, onKey)
}

/**
 * Where the D-pad's focus starts in the companion's window when nothing has it yet (a tap left touch mode on, a tab
 * just opened): the newest registered place - SETTINGS' cursor row ([focusStart]), an open pop-up's current choice
 * ([OptionOverlay]). Nothing registered: Compose's own entry from the edge the key points away from.
 */
class FocusStarts {
    internal val entries = mutableListOf<() -> androidx.compose.ui.focus.FocusRequester?>()

    /** Focuses the newest start; false = none, or it couldn't take focus. */
    fun focusNewest(): Boolean {
        for (e in entries.asReversed()) {
            val r = e() ?: continue
            return runCatching { r.requestFocus(); true }.getOrDefault(false)
        }
        return false
    }
}

val LocalFocusStarts = staticCompositionLocalOf<FocusStarts?> { null }

/** This element is where the D-pad starts in the companion's window while [on] (the white cursor row). No-op
 * outside the window. */
fun androidx.compose.ui.Modifier.focusStart(on: Boolean): androidx.compose.ui.Modifier = composed {
    val starts = LocalFocusStarts.current ?: return@composed this
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (on) {
        DisposableEffect(requester) {
            val entry: () -> androidx.compose.ui.focus.FocusRequester? = { requester }
            starts.entries += entry
            onDispose { starts.entries -= entry }
        }
    }
    focusRequester(requester)
}

/**
 * A list row the D-pad stops on although a tap does nothing there (an achievement, a leaderboard entry, a guide fact):
 * white while focused like [OptionLine], so the companion's window can walk - and scroll - a list of them. [selected]
 * keeps it white anyway (the list's own cursor row).
 */
fun androidx.compose.ui.Modifier.focusRow(selected: Boolean = false): androidx.compose.ui.Modifier = composed {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    background(if (selected || focused) OptionColors.rowSelected else androidx.compose.ui.graphics.Color.Transparent)
        .focusable(interactionSource = interaction)
}

/**
 * A page that scrolls with nothing on it to tap (the DEX entry's AREA and MOVES): one D-pad stop, ringed just outside
 * its edge while it has the focus ([shape]), where UP / DOWN scroll it by [step]; at its top or bottom the key moves the focus on instead. A page
 * that fits isn't a stop.
 */
fun androidx.compose.ui.Modifier.dpadScroll(
    state: androidx.compose.foundation.gestures.ScrollableState,
    shape: androidx.compose.ui.graphics.Shape,
    step: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { step.toPx() }
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val by = when (e.key) {
            Key.DirectionDown -> if (state.canScrollForward) px else return@onPreviewKeyEvent false
            Key.DirectionUp -> if (state.canScrollBackward) -px else return@onPreviewKeyEvent false
            else -> return@onPreviewKeyEvent false
        }
        scope.launch { state.animateScrollBy(by) }
        true
    }
        // The ring just outside the page's edge: inside, it covered the rows' first letters.
        .drawWithContent {
            drawContent()
            if (focused) {
                val w = 4.dp.toPx()
                // A stroke centred on an outline grown by w and moved back by w / 2: it covers -w..0, outside the page.
                translate(-w / 2, -w / 2) {
                    drawOutline(
                        shape.createOutline(androidx.compose.ui.geometry.Size(size.width + w, size.height + w), layoutDirection, this),
                        AppColors.focusRing,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                    )
                }
            }
        }
        // Only while there's something to scroll: a page that fits is no stop at all.
        .focusable(enabled = state.canScrollForward || state.canScrollBackward, interactionSource = interaction)
}
