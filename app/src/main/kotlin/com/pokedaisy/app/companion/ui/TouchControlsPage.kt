package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchLayout
import com.pokedaisy.app.companion.TouchPosition
import com.pokedaisy.app.companion.TouchPreset
import com.pokedaisy.app.companion.TouchShow
import com.pokedaisy.app.companion.TouchSize
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.i18n.tk

/** LAYOUT's value for the player's own (EDIT LAYOUT's) layout. */
val CUSTOM_LAYOUT = tk("CUSTOM")

/**
 * SETTINGS > TOUCH CONTROLS' rows, shared by both screens: when they show, the layout (a preset or CUSTOM), EDIT
 * LAYOUT, where they sit upright, SIZE, OPACITY, the D-pad, FADE WHEN IDLE, VIBRATION, RESET LAYOUT, then the extra
 * buttons, each on or off. Every row steps to its next value on a tap, no pick-list (the user's call: each change shows
 * in the preview at once). [underGame] is the upright companion's place (null: no such place on this device), shared
 * with DISPLAY OPTIONS' COMPANION; [onEdit] opens EDIT LAYOUT.
 */
fun touchControlsRows(
    style: TouchControlsStyle,
    set: (TouchControlsStyle) -> Unit,
    onEdit: () -> Unit,
    underGame: Boolean? = null,
    setUnderGame: (Boolean) -> Unit = {},
    /** ASPECT > EXPAND is on, and the touch controls on screen: what the COMPANION row means then. */
    expand: Boolean = false,
    touchShown: Boolean = true,
): List<SettingRow> {
    fun onOff(b: Boolean) = if (b) tk("ON") else tk("OFF")
    fun <T> List<T>.after(t: T): T = this[(indexOf(t) + 1) % size]
    // null = CUSTOM, after the presets once there is one.
    val layouts: List<TouchPreset?> = TouchPreset.entries + listOf(null)
    val layoutNow: TouchPreset? = if (style.useCustom) null else style.preset
    return listOfNotNull(
        SettingRow(tk("SHOW"), style.show.label, subtitle = "AUTO: hidden with a controller") {
            set(style.copy(show = TouchShow.entries.after(style.show)))
        },
        SettingRow(tk("LAYOUT"), layoutNow?.label ?: CUSTOM_LAYOUT, subtitle = "Where the buttons sit") {
            // The presets, then CUSTOM once there is one.
            val next = if (style.hasCustom) layouts.after(layoutNow) else TouchPreset.entries.after(style.preset)
            set(if (next == null) style.copy(useCustom = true) else style.copy(preset = next, useCustom = false))
        },
        SettingRow(tk("EDIT LAYOUT"), null, opensPage = true, subtitle = "Drag the buttons into place") { onEdit() },
        // Nothing to move in EXPAND + UNDER GAME's strip (it's the buttons' own height), nor a CUSTOM layout.
        (expand && underGame == true && touchShown).let { inStrip ->
            SettingRow(
                tk("POSITION"), style.position.label, enabled = !style.useCustom && !inStrip,
                subtitle = if (inStrip) "EXPAND: the buttons fill their own space" else "Upright: top, middle or bottom of their space",
            ) {
                if (!style.useCustom && !inStrip) set(style.copy(position = TouchPosition.entries.after(style.position)))
            }
        },
        underGame?.let { under -> uprightCompanionRow(under, expand, touchShown, setUnderGame) },
        SettingRow(tk("SIZE"), style.size.label) {
            set(style.copy(size = TouchSize.entries.after(style.size)))
        },
        SettingRow(tk("OPACITY"), "${style.opacity}%", subtitle = "How solid the buttons are") {
            set(style.copy(opacity = TouchControlsStyle.OPACITIES.after(style.opacity)))
        },
        SettingRow(tk("D-PAD"), if (style.eightWay) tk("8-WAY") else tk("4-WAY"), subtitle = "4-WAY: one direction at a time") {
            set(style.copy(eightWay = !style.eightWay))
        },
        SettingRow(tk("FADE WHEN IDLE"), onOff(style.fadeIdle), subtitle = "Fade out when not in use") {
            set(style.copy(fadeIdle = !style.fadeIdle))
        },
        SettingRow(tk("VIBRATION"), listOf(tk("OFF"), tk("LIGHT"), tk("MEDIUM"), tk("STRONG"))[style.vibration.coerceIn(0, 3)], subtitle = "How strong each press feels") {
            set(style.copy(vibration = (style.vibration + 1) % 4))
        },
        SettingRow(tk("RESET LAYOUT"), null, subtitle = "Forget CUSTOM, back to the preset") {
            set(style.copy(useCustom = false, portrait = TouchLayout(), landscape = TouchLayout()))
        }.takeIf { style.hasCustom },
        groupTitle(tk("EXTRA BUTTONS")),
    ) + TouchButton.extras.map { b ->
        SettingRow(b.label, onOff(b in style.extras), subtitle = extraSubtitle(b)) {
            set(style.copy(extras = if (b in style.extras) style.extras - b else style.extras + b))
        }
    }
}

private fun extraSubtitle(b: TouchButton): String? = when (b) {
    TouchButton.FF -> "Tap: on or off. Hold: fast while held"
    TouchButton.QUICK_MENU -> "Opens the QUICK MENU"
    TouchButton.TURBO_A, TouchButton.TURBO_B -> "Presses it over and over while held"
    TouchButton.SAVE_STATE -> "Saves a state to the current slot"
    TouchButton.LOAD_STATE -> "Loads the current slot's state"
    else -> null
}

/**
 * The upright companion's place (DISPLAY OPTIONS' COMPANION, repeated on TOUCH CONTROLS' page). With EXPAND the game
 * fills everything above the companion, so UNDER GAME means the touch controls' own strip along the bottom - and with
 * no touch controls on screen it means nothing: greyed out, saying why.
 */
fun uprightCompanionRow(under: Boolean, expand: Boolean, touchShown: Boolean, set: (Boolean) -> Unit): SettingRow {
    val idle = expand && !touchShown
    return SettingRow(
        tk("COMPANION"), portraitPlaceLabel(under), enabled = !idle,
        subtitle = when {
            idle -> "EXPAND: the game fills the space above it"
            expand -> "EXPAND: UNDER GAME gives the buttons their own space"
            else -> "Where it sits with the phone upright"
        },
    ) { if (!idle) set(!under) }
}

/** The NEW tag on TOUCH CONTROLS' row (both screens). */
fun touchControlsBadge(): String? = NewFeatures.badge(NewFeatures.TOUCH_CONTROLS)

/**
 * TOUCH CONTROLS' page: the preview of [stage] with [style] on it (UPRIGHT / SIDEWAYS show either way of holding the
 * phone), beside the rows on a wide page, above them on a tall one.
 */
@Composable
fun TouchControlsPage(
    stage: TouchStage,
    style: TouchControlsStyle,
    shot: ImageBitmap?,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    /** The upright companion's place and height, for the other way of holding the phone (built, not measured). */
    underGame: Boolean = false,
    portraitRatio: Float = PortraitLayout.DEFAULT_RATIO,
    /** ASPECT > EXPAND, and the touch controls on screen: UNDER GAME's strip for them. */
    expand: Boolean = false,
    touchShown: Boolean = true,
    rows: @Composable (Modifier) -> Unit,
) {
    var other by remember { mutableStateOf(false) }
    val shown = remember(stage, other, underGame, portraitRatio, expand, touchShown, style) {
        if (!other) stage
        else {
            val w = stage.h
            val upright = stage.w > stage.h   // the other way round from what's shown
            val strip = if (expand && underGame && touchShown && upright) TouchGeometry.naturalHeight(style, w, stage.dp, stage.gameBoy, stage.ds) else 0
            simulateTouchStage(
                w, stage.w, stage.dp, stage.gameAspect, portraitRatio = portraitRatio, underGame = underGame,
                gameBoy = stage.gameBoy, expand = expand, padStrip = strip,
            )
        }
    }
    BoxWithConstraints(modifier) {
        val wide = maxWidth > maxHeight * 1.25f
        val preview: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(m.u * 4)) {
                    TouchControlsPreview(shown, style, shot, Modifier.fillMaxSize())
                }
                // One button naming the way it's shown, a tap shows the other (two side by side didn't fit a narrow
                // column in a game font set larger, Gen 1's).
                val small = rememberGbaTextMetrics(1f, gameScaled = false)
                OptionButton(
                    if (shown.portrait) tk("UPRIGHT") else tk("SIDEWAYS"), small,
                    onClick = { other = !other }, emphasis = true, sidePadding = 6,
                    pixelIcon = PixelIcons.chevronRight,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                preview(Modifier.weight(0.45f).fillMaxHeight())
                Spacer(Modifier.width(m.u * 4))
                rows(Modifier.weight(0.55f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.fillMaxWidth().weight(0.42f))
                Spacer(Modifier.height(m.u * 4))
                rows(Modifier.fillMaxWidth().weight(0.58f))
            }
        }
    }
}
