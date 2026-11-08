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
import com.pokedaisy.app.companion.InstalledSkin
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.SkinSystem
import com.pokedaisy.app.companion.TouchColors
import com.pokedaisy.app.companion.TouchLook
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchLayout
import com.pokedaisy.app.companion.TouchPosition
import com.pokedaisy.app.companion.TouchPreset
import com.pokedaisy.app.companion.TouchShow
import com.pokedaisy.app.companion.TouchSize
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr

/** LAYOUT's value for the player's own (EDIT LAYOUT's) layout. */
val CUSTOM_LAYOUT = tk("CUSTOM")

/**
 * SETTINGS > TOUCH CONTROLS' rows, shared by both screens: when they show, the layout (a preset or CUSTOM), EDIT
 * LAYOUT, where they sit upright, SIZE, OPACITY, the D-pad, FADE WHEN IDLE, VIBRATION and its STRENGTH, RESET LAYOUT, then the extra
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
    /** SKINS: the imported skins, the page's pick-lists ([TouchControlsPage]'s), the game's system (whose skin the
     * preview shows) and IMPORT SKIN's file picker (the top screen's; null on the companion, which can't host one). */
    skins: List<InstalledSkin> = emptyList(),
    skinUi: TouchSkinUi? = null,
    skinSystem: SkinSystem = SkinSystem.GBA,
    onImportSkin: (() -> Unit)? = null,
    /** VIBRATION / STRENGTH just changed: one buzz at the new strength (0 = off), com.pokedaisy.app.TouchHaptics. */
    feel: (Int) -> Unit = {},
): List<SettingRow> {
    fun onOff(b: Boolean) = if (b) tk("ON") else tk("OFF")
    fun <T> List<T>.after(t: T): T = this[(indexOf(t) + 1) % size]
    // null = CUSTOM, after the presets once there is one.
    val layouts: List<TouchPreset?> = TouchPreset.entries + listOf(null)
    val layoutNow: TouchPreset? = if (style.useCustom) null else style.preset
    // A skin draws its own buttons: the rows that shape the drawn ones stand aside while it's on.
    val skinned = style.skinFor(skinSystem)?.let { id -> skins.any { it.id == id } } == true
    val skinNote = "A skin is in use"
    return listOfNotNull(
        SettingRow(tk("SHOW"), style.show.label, subtitle = "AUTO: hidden with a controller") {
            set(style.copy(show = TouchShow.entries.after(style.show)))
        },
        SettingRow(tk("LAYOUT"), layoutNow?.label ?: CUSTOM_LAYOUT, enabled = !skinned, subtitle = if (skinned) skinNote else "Where the buttons sit") {
            // The presets, then CUSTOM once there is one.
            val next = if (style.hasCustom) layouts.after(layoutNow) else TouchPreset.entries.after(style.preset)
            if (!skinned) set(if (next == null) style.copy(useCustom = true) else style.copy(preset = next, useCustom = false))
        },
        SettingRow(tk("EDIT LAYOUT"), null, opensPage = true, enabled = !skinned, subtitle = if (skinned) skinNote else "Drag the buttons into place") { if (!skinned) onEdit() },
        // Nothing to move in EXPAND + UNDER GAME's strip (it's the buttons' own height), nor a CUSTOM layout.
        (expand && underGame == true && touchShown).let { inStrip ->
            SettingRow(
                tk("POSITION"), style.position.label, enabled = !style.useCustom && !inStrip && !skinned,
                subtitle = if (inStrip) "EXPAND: the buttons fill their own space" else "Upright: top, middle or bottom of their space",
            ) {
                if (!style.useCustom && !inStrip && !skinned) set(style.copy(position = TouchPosition.entries.after(style.position)))
            }
        },
        underGame?.let { under -> uprightCompanionRow(under, expand, touchShown, setUnderGame) },
        SettingRow(tk("SIZE"), style.size.label, enabled = !skinned) {
            if (!skinned) set(style.copy(size = TouchSize.entries.after(style.size)))
        },
        SettingRow(tk("LOOK"), style.look.label, enabled = !skinned, labelBadge = touchStylesBadge(), subtitle = if (skinned) skinNote else "How the buttons are drawn") {
            if (!skinned) set(style.copy(look = TouchLook.entries.after(style.look)))
        },
        SettingRow(tk("COLORS"), style.colors.label, enabled = !skinned, labelBadge = touchStylesBadge(), subtitle = if (skinned) skinNote else "The buttons' colors") {
            if (!skinned) set(style.copy(colors = TouchColors.entries.after(style.colors)))
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
        SettingRow(
            tk("VIBRATION"), onOff(style.vibrate), subtitle = "A buzz on each press",
            labelBadge = NewFeatures.badge(NewFeatures.TOUCH_VIBRATION),
        ) {
            val next = style.copy(vibrate = !style.vibrate)
            set(next)
            feel(next.vibration)
        },
        // Only while VIBRATION is on; each step buzzes once, so the player feels what they picked.
        SettingRow(tk("STRENGTH"), listOf(tk("LIGHT|vibration"), tk("MEDIUM"), tk("STRONG"))[style.vibrationStrength.coerceIn(1, 3) - 1], subtitle = "How strong each press feels") {
            val next = style.copy(vibrationStrength = style.vibrationStrength.coerceIn(1, 3) % 3 + 1)
            set(next)
            feel(next.vibration)
        }.takeIf { style.vibrate },
        SettingRow(tk("RESET LAYOUT"), null, subtitle = "Forget CUSTOM, back to the preset") {
            set(style.copy(useCustom = false, portrait = TouchLayout(), landscape = TouchLayout()))
        }.takeIf { style.hasCustom },
    ) + skinRows(style, skins, skinUi, onImportSkin) + listOf(groupTitle(tk("EXTRA BUTTONS"))) + TouchButton.extras.map { b ->
        SettingRow(
            b.label, onOff(b in style.extras), enabled = !skinned, subtitle = if (skinned) skinNote else extraSubtitle(b),
            labelBadge = NewFeatures.badge(NewFeatures.TOUCH_MORE_BUTTONS).takeIf { b == TouchButton.ZOOM || b == TouchButton.REWIND },
        ) {
            if (!skinned) set(style.copy(extras = if (b in style.extras) style.extras - b else style.extras + b))
        }
    } + SettingRow(
        // A GBA / Game Boy game's X and Y: the device's X / Y keys (a DS game always has its own).
        tk("X AND Y"), onOff(style.xyButtons), enabled = !skinned,
        subtitle = if (skinned) skinNote else "GBA and Game Boy: X and Y keys for GAME BUTTONS and HOTKEYS",
        labelBadge = NewFeatures.badge(NewFeatures.TOUCH_MORE_BUTTONS),
    ) { if (!skinned) set(style.copy(xyButtons = !style.xyButtons)) }
}

/** SKINS: a skin for GBA games and one for Game Boy games (a pick-list each), IMPORT SKIN and DELETE SKIN. */
private fun skinRows(style: TouchControlsStyle, skins: List<InstalledSkin>, ui: TouchSkinUi?, onImport: (() -> Unit)?): List<SettingRow> {
    if (ui == null) return emptyList()
    val none = skins.isEmpty()
    fun pickRow(label: String, system: SkinSystem): SettingRow {
        val name = style.skinFor(system)?.let { id -> skins.firstOrNull { it.id == id }?.skin?.name }
        val mine = skins.any { it.skin.system == system }
        return SettingRow(
            label, name ?: tk("NONE"), enabled = mine || name != null, labelBadge = touchSkinsBadge(),
            subtitle = when {
                mine || name != null -> null
                onImport == null -> "Import skins in the app's SETTINGS"
                else -> "None imported yet"
            },
        ) { if (mine || name != null) ui.picking = system }
    }
    return listOfNotNull(
        groupTitle(tk("SKINS")),
        pickRow(tk("GBA SKIN"), SkinSystem.GBA),
        pickRow(tk("GAME BOY SKIN"), SkinSystem.GB),
        onImport?.let { SettingRow(tk("IMPORT SKIN"), null, labelBadge = touchSkinsBadge(), subtitle = "A .deltaskin file") { it() } },
        if (onImport != null && !none) SettingRow(tk("DELETE SKIN"), null) { ui.deleting = true } else null,
    )
}

/** SKINS' pick-lists and notices, shown over the page ([TouchControlsPage] draws them). */
class TouchSkinUi {
    /** The system whose skin is being picked. */
    var picking by mutableStateOf<SkinSystem?>(null)
    /** DELETE SKIN's list is open. */
    var deleting by mutableStateOf(false)
    /** The skin DELETE SKIN asks about. */
    var confirmDelete by mutableStateOf<InstalledSkin?>(null)
    /** IMPORT SKIN's outcome: a title and a message. */
    var notice by mutableStateOf<Pair<String, String>?>(null)
}

/** The NEW tags: LOOK / COLORS and SKINS. */
fun touchStylesBadge(): String? = NewFeatures.badge(NewFeatures.TOUCH_STYLES)
fun touchSkinsBadge(): String? = NewFeatures.badge(NewFeatures.TOUCH_SKINS)

/** [ui]'s pick-lists over the page: a skin for a system, the skin to delete and its confirm, IMPORT SKIN's notice. */
@Composable
fun TouchSkinDialogs(
    ui: TouchSkinUi, skins: List<InstalledSkin>, style: TouchControlsStyle, m: GbaTextMetrics,
    set: (TouchControlsStyle) -> Unit, onDelete: (InstalledSkin) -> Unit,
) {
    ui.picking?.let { system ->
        val options = listOf<InstalledSkin?>(null) + skins.filter { it.skin.system == system }
        val current = options.firstOrNull { it?.id == style.skinFor(system) }
        OptionSelector(
            if (system == SkinSystem.GBA) tk("GBA SKIN") else tk("GAME BOY SKIN"), options, current,
            { it?.skin?.name ?: tr("NONE") }, m,
            onPick = { picked -> set(style.withSkin(system, picked?.id)); ui.picking = null },
            onDismiss = { ui.picking = null },
        )
    }
    if (ui.deleting && skins.isNotEmpty()) {
        OptionSelector(
            tk("DELETE SKIN"), skins, skins.first(), { "${it.skin.name} (${tr(it.skin.system?.label ?: "")})" }, m,
            onPick = { ui.confirmDelete = it; ui.deleting = false },
            onDismiss = { ui.deleting = false },
        )
    }
    ui.confirmDelete?.let { skin ->
        OptionConfirm(
            tk("DELETE SKIN?"), tr("{0} goes from this device. Its file stays where you got it.", skin.skin.name), tk("DELETE"), m,
            onConfirm = { onDelete(skin); ui.confirmDelete = null },
            onDismiss = { ui.confirmDelete = null },
        )
    }
    ui.notice?.let { (title, message) ->
        OptionConfirm(title, message, tk("OK"), m, onConfirm = { ui.notice = null }, onDismiss = { ui.notice = null }, cancelLabel = null)
    }
}

private fun extraSubtitle(b: TouchButton): String? = when (b) {
    TouchButton.FF -> "Tap: on or off. Hold: fast while held"
    TouchButton.QUICK_MENU -> "Opens the QUICK MENU"
    TouchButton.TURBO_A, TouchButton.TURBO_B -> "Presses it over and over while held"
    TouchButton.SAVE_STATE -> "Saves a state to the current slot"
    TouchButton.LOAD_STATE -> "Loads the current slot's state"
    TouchButton.ZOOM -> "EXPAND's zoom. Shows while EXPAND is on"
    TouchButton.REWIND -> "Rewinds while held, once REWIND is on"
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
    ?: NewFeatures.badge(NewFeatures.TOUCH_STYLES) ?: NewFeatures.badge(NewFeatures.TOUCH_SKINS)

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
    /** The upright companion's place and height, for the other way of holding the phone (built, not measured). The
     * app's default: right under the game, the buttons along the screen's bottom. */
    underGame: Boolean = true,
    portraitRatio: Float = PortraitLayout.DEFAULT_RATIO,
    /** ASPECT > EXPAND, and the touch controls on screen: UNDER GAME's strip for them. */
    expand: Boolean = false,
    touchShown: Boolean = true,
    /** SKINS: the imported ones and their pick-lists ([touchControlsRows]' skinUi); the preview shows the stage's
     * system's pick. */
    skins: List<InstalledSkin> = emptyList(),
    skinUi: TouchSkinUi? = null,
    setStyle: (TouchControlsStyle) -> Unit = {},
    onDeleteSkin: (InstalledSkin) -> Unit = {},
    rows: @Composable (Modifier) -> Unit,
) {
    val system = if (stage.ds) SkinSystem.DS else if (stage.gameBoy) SkinSystem.GB else SkinSystem.GBA
    val skin = style.skinFor(system)?.let { id -> skins.firstOrNull { it.id == id } }
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
                    TouchControlsPreview(shown, style, shot, Modifier.fillMaxSize(), skin = skin)
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
        skinUi?.let { TouchSkinDialogs(it, skins, style, m, setStyle, onDeleteSkin) }
    }
}
