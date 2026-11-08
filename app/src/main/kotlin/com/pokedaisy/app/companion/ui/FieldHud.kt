package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.data.guideId
import com.pokedaisy.app.companion.data.gameGuide
import com.pokedaisy.app.companion.data.isDsGame
import com.pokedaisy.app.companion.data.isGen5
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.FieldHudLayout
import com.pokedaisy.app.companion.FieldHudLook
import com.pokedaisy.app.companion.FieldHudPart
import com.pokedaisy.app.companion.FieldHudSize
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.FlyAction
import com.pokedaisy.app.companion.ItemUse
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.data.GuideRomSource
import com.pokedaisy.app.companion.data.wildSetOf
import com.pokedaisy.app.companion.data.ItemUseOutcome
import com.pokedaisy.app.companion.data.ItemUseResult
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.MapEncounters
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.POCKET_BERRIES
import com.pokedaisy.app.companion.data.POCKET_ITEMS
import com.pokedaisy.app.companion.data.basePocket
import com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS
import com.pokedaisy.app.companion.data.POCKET_POKE_BALLS
import com.pokedaisy.app.companion.data.POCKET_TM_HM
import com.pokedaisy.app.companion.data.PartyTarget
import com.pokedaisy.app.companion.data.PlayerMapTile
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.PokedexState
import com.pokedaisy.app.companion.data.SlotVerdict
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.activeRegionMap
import com.pokedaisy.app.companion.data.gameCase
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * FIELD HUD (SETTINGS > HUD, on by default): a modern game's heads-up display over the overworld - the party
 * (level, HP and status; a tap opens the Pokémon's summary), a minimap (a tap opens the whole MAP, FLY included), the
 * bag's Poké Balls, and BAG / WILD buttons (a compact bag with USE; this map's wild Pokémon, as GUIDE > HERE reads
 * them). The game keeps running under every panel. The player picks its [FieldHudLayout], [FieldHudLook] and
 * [FieldHudSize], and which parts show ([FieldHudPart]).
 *
 * PokeDaisyActivity shows it only on the field (EmulatorEngine.onField: no battle, menu screen, region map or -
 * at EXPAND - text box), GBA games only, and not on a phone held upright. FieldHudScreenshotTest renders it over a
 * game frame.
 */

/** What the HUD has open over the game. */
enum class FieldHudPanel { MAP, BAG, WILD, SUMMARY }

/** One wild species of this map by one method (GUIDE's method names: "GRASS / CAVE", ...), its slots added up. */
data class HudWild(
    val method: String,
    val species: Int,
    val minLevel: Int,
    val maxLevel: Int,
    val percent: Int,
    val seen: Boolean,
    val caught: Boolean,
)

/** [enc]'s slots merged per species and method, most common first; seen / caught from [dex] (null = all seen). */
fun hudWild(enc: MapEncounters?, dex: PokedexState?): List<HudWild> {
    if (enc == null) return emptyList()
    fun national(species: Int) = dex?.let { PokedexSource.nationalOf(it.tables, species) } ?: 0
    return listOf(
        tk("GRASS / CAVE") to enc.grass, tk("SURFING") to enc.water, tk("ROCK SMASH") to enc.rockSmash,
        tk("OLD ROD") to enc.oldRod, tk("GOOD ROD") to enc.goodRod, tk("SUPER ROD") to enc.superRod,
    ).flatMap { (method, slots) ->
        slots.groupBy { it.species }.map { (species, s) ->
            val n = national(species)
            HudWild(
                method, species, s.minOf { it.minLevel }, s.maxOf { it.maxLevel }, s.sumOf { it.percent },
                seen = dex == null || n in dex.seen, caught = dex != null && n in dex.caught,
            )
        }.sortedByDescending { it.percent }
    }
}

/** SETTINGS > HUD's page, the same on both screens: the switch, LAYOUT / LOOK / SIZE, then which parts show. */
fun fieldHudRows(
    on: Boolean,
    style: FieldHudStyle,
    setOn: (Boolean) -> Unit,
    setStyle: (FieldHudStyle) -> Unit,
): List<SettingRow> {
    fun onOff(b: Boolean) = if (b) tk("ON") else tk("OFF")
    return listOf(
        SettingRow(tk("HUD"), onOff(on), subtitle = "Party, map and more on the field") { setOn(!on) },
        // LAYOUT, LOOK and SIZE cycle on a tap, not a pick-list (the user's call, like SHADERS' FILTER): each one
        // shows on the HUD at once, over the game, while the next tap is a thumb away.
        // The compact HUD has one layout (FieldHudFit): no choice while the picture is too narrow for the others.
        SettingRow(
            tk("LAYOUT"), style.layout.label, enabled = on && !FieldHudFit.tight,
            subtitle = if (FieldHudFit.tight) "Unavailable: screen too small" else null,
        ) {
            if (!FieldHudFit.tight) setStyle(style.copy(layout = style.layout.next()))
        },
        SettingRow(tk("LOOK"), style.look.label, enabled = on) {
            setStyle(style.copy(look = style.look.next()))
        },
        SettingRow(tk("SIZE"), style.size.label, enabled = on) {
            setStyle(style.copy(size = style.size.next()))
        },
        groupTitle(tk("SHOW")),
    ) + FieldHudPart.entries.map { p ->
        SettingRow(p.label, onOff(style.shows(p)), enabled = on, labelBadge = when (p) {
            FieldHudPart.PC -> NewFeatures.badge(NewFeatures.HUD_PC)
            FieldHudPart.REPEL -> NewFeatures.badge(NewFeatures.HUD_REPEL)
            else -> null
        }) {
            setStyle(style.copy(hidden = if (style.shows(p)) style.hidden + p else style.hidden - p))
        }
    }
}

/** The next value round (LAYOUT / LOOK / SIZE cycle on a tap). */
private inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

// ---- Looks --------------------------------------------------------------------------------------

/** The HUD's colours in one [FieldHudLook]. */
internal class HudInk(
    val text: Color, val shadow: Color,
    val accent: Color, val accentShadow: Color, val onAccent: Color,
    val muted: Color,
    val cell: Color, val cellOn: Color, val divider: Color,
)

// A little more see-through than it was (0xB8, the user's call with the HP box look, 2026-11-09).
private val GLASS_FILL = Color(0xA0121620)
private val GLASS_EDGE = Color(0x66FFFFFF)

private val GLASS_INK = HudInk(
    text = Color(0xFFF4F6FA), shadow = Color(0xFF0C0F15),
    accent = Color(0xFFFFCF5A), accentShadow = Color(0xFF3A2A08), onAccent = Color(0xFF0C0F15),
    muted = Color(0xFFB2BACB),
    cell = Color(0x26FFFFFF), cellOn = Color(0x59FFFFFF), divider = GLASS_EDGE,
)

/** WHITE GLASS: the same see-through panel in white, dark text with a light shadow (the OPTION text's way round). */
private val WHITE_FILL = Color(0xACF6F7FA)
private val WHITE_EDGE = Color(0x59000000)

private val WHITE_INK = HudInk(
    text = Color(0xFF262B34), shadow = Color(0xFFD9DCE3),
    accent = Color(0xFFC8321E), accentShadow = Color(0xFFF2C9C0), onAccent = Color.White,
    muted = Color(0xFF6A7282),
    cell = Color(0x1F000000), cellOn = Color(0x4D000000), divider = WHITE_EDGE,
)

/** The quick menu's tiles in GLASS / WHITE GLASS: the two-tone face's top, its low half and the edge (a step more solid
 * than the panels, so a tile reads over the game). */
internal val HudGlassFace = Triple(Color(0xC8283044), Color(0xAA121620), GLASS_EDGE)
internal val HudWhiteGlassFace = Triple(Color(0xD8FFFFFF), Color(0xC8E4E7EE), WHITE_EDGE)

/** A button inside a HUD panel (USE's row, BAG's pockets, WILD's KEEP OPEN, a panel's close): nearly solid, so it reads
 * over the map and the game behind a see-through panel (the user's call). */
private val HudGlassButton = Triple(Color(0xEC283044), Color(0xE0121620), Color(0x78FFFFFF))
private val HudWhiteGlassButton = Triple(Color(0xF0FFFFFF), Color(0xE8E4E7EE), Color(0x64000000))

/** The HUD's "this one": the party screen's selected slot's orange (FireRed / Emerald), one GBA pixel inside the edge. */
internal val HUD_SELECTED = Color(0xFFF07038)

/** How far the HP box's shadow reaches down and right of a HUD window (GBA pixels): the battle HP box's tail. */
internal const val HUD_SHADOW = 3

/** The shadow's colour in this look: dark under the glass, the window's own outer line under WINDOW's white. */
@Composable
private fun hudShadow(look: FieldHudLook): Color = when (look) {
    FieldHudLook.GLASS -> Color(0x80060810)
    FieldHudLook.WHITE_GLASS -> Color(0x4C141820)
    FieldHudLook.WINDOW -> OptionColors.titleLayers.first().first
}

internal val LocalHudLook = staticCompositionLocalOf { FieldHudLook.GLASS }

/** A see-through look (GLASS or WHITE GLASS), not the app's windows. */
internal val FieldHudLook.glass get() = this != FieldHudLook.WINDOW

internal val ink: HudInk
    @Composable get() = when (LocalHudLook.current) {
        FieldHudLook.GLASS -> GLASS_INK
        FieldHudLook.WHITE_GLASS -> WHITE_INK
        FieldHudLook.WINDOW -> HudInk(
            OptionColors.label, OptionColors.labelShadow,
            OptionColors.value, OptionColors.valueShadow, Color.White,
            OptionColors.muted,
            OptionColors.listFill, if (OptionColors.dark) OptionColors.rowSelected else Color(0xFFFFE9A8), OptionColors.divider,
        )
    }

/**
 * A HUD window behind [this], shaped like the games' battle HP box (the user's pick of five looks, 2026-11-09): its shadow
 * [HUD_SHADOW] GBA pixels down and to the right, then the look's window - the OPTION title window, or a see-through panel
 * with a pixel edge. The shadow is drawn past the window's box: keep that much room on its right and bottom.
 */
@Composable
internal fun Modifier.hudPanel(u: Dp, radius: Int = 3, shadow: Boolean = true): Modifier {
    val look = LocalHudLook.current
    val shade = hudShadow(look)
    return drawBehind {
        val px = u.toPx()
        if (shadow) drawPixelRoundRect(shade, Offset(HUD_SHADOW * px, HUD_SHADOW * px), size, radius = (radius + 1) * px, step = px)
        when (look) {
            FieldHudLook.WINDOW -> drawLayeredBox(OptionColors.titleLayers.inPx(px), OptionColors.titleFill, radius = radius * px)
            FieldHudLook.GLASS -> {
                drawPixelRoundRect(GLASS_FILL, radius = (radius + 1) * px, step = px)
                drawPixelRoundFrame(GLASS_EDGE, Offset.Zero, size, (radius + 1) * px, px, step = px)
            }
            FieldHudLook.WHITE_GLASS -> {
                drawPixelRoundRect(WHITE_FILL, radius = (radius + 1) * px, step = px)
                drawPixelRoundFrame(WHITE_EDGE, Offset.Zero, size, (radius + 1) * px, px, step = px)
            }
        }
    }
}

/**
 * The app's two-tone button face in the HUD's LOOK (the OPTION window's, or the glass's: its colour a step lighter on top,
 * the soft split, its own colour under it) behind [this]. [solid]: a button inside a panel, nearly opaque so it reads over
 * the map; else the quick menu's tile, as see-through as the HUD. [shadow]: the HP box's, like the HUD's windows.
 * [selected]: the party screen's orange, a GBA pixel inside the edge.
 */
@Composable
internal fun Modifier.hudButtonFace(u: Dp, selected: Boolean = false, solid: Boolean = true, shadow: Boolean = false): Modifier {
    val look = LocalHudLook.current
    val shade = hudShadow(look)
    return drawBehind {
        val px = u.toPx()
        val r = 4 * px
        if (shadow) drawPixelRoundRect(shade, Offset(HUD_SHADOW * px, HUD_SHADOW * px), size, radius = r, step = px)
        when (look) {
            FieldHudLook.WINDOW -> drawLayeredBox(OptionColors.titleLayers.inPx(px), OptionColors.titleFill, radius = 3 * px, twoTone = true)
            FieldHudLook.GLASS, FieldHudLook.WHITE_GLASS -> {
                val (top, low, edge) = when {
                    look == FieldHudLook.GLASS -> if (solid) HudGlassButton else HudGlassFace
                    else -> if (solid) HudWhiteGlassButton else HudWhiteGlassFace
                }
                // See-through: each band painted once (drawTwoTone paints the low colour under the top one, which doubles a
                // translucent fill).
                val split = size.height * 0.47f
                fun band(c: Color, from: Float, to: Float) = clipRect(top = from, bottom = to) { drawPixelRoundRect(c, radius = r, step = px) }
                band(top, 0f, split)
                band(androidx.compose.ui.graphics.lerp(top, low, 0.35f), split, split + px)
                band(androidx.compose.ui.graphics.lerp(top, low, 0.7f), split + px, split + 2 * px)
                band(low, split + 2 * px, size.height)
                drawPixelRoundFrame(edge, Offset.Zero, size, r, px, step = px)
            }
        }
        if (selected) drawPixelRoundFrame(HUD_SELECTED, Offset(px, px), Size(size.width - 2 * px, size.height - 2 * px), r - px, px, step = px)
    }
}

/** The picked one of a panel's cells (BAG's item): the party screen's orange round its edge. */
private fun Modifier.hudPicked(u: Dp, picked: Boolean): Modifier = if (!picked) this else drawBehind {
    val px = u.toPx()
    drawPixelRoundFrame(HUD_SELECTED, Offset.Zero, size, 3 * px, px, step = px)
}

/** A small button inside a HUD panel: [label] (or [icon]) on [hudButtonFace]. */
@Composable
private fun HudPanelButton(
    label: String?, m: GbaTextMetrics, modifier: Modifier = Modifier, selected: Boolean = false, icon: List<String>? = null, onClick: () -> Unit,
) {
    val u = m.u
    val small = rememberGbaTextMetrics(0.75f)
    Box(
        modifier.hudButtonFace(u, selected).soundClickable(onClick = onClick)
            .heightIn(min = small.lineHeight + u * 6).padding(horizontal = u * 5, vertical = u * 3),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) ShadowedPixelIcon(icon, ink.text, ink.shadow, m)
        if (label != null) GbaText(label, ink.text, ink.shadow, small, bold = gameBoldLabels())
    }
}

/** How much deeper the look's window edge reaches in than the glass's one GBA pixel: WINDOW's frame layers. Content
 * against an edge (the minimap's name, a button's label) adds it, so it keeps the same room in every look. */
@Composable
internal fun hudEdge(u: Dp): Dp = if (LocalHudLook.current == FieldHudLook.WINDOW) u * 2 else 0.dp

/** Whole screen pixels per party-icon pixel: one a GBA pixel (3 on the Thor). */
private fun hudIconScale(m: GbaTextMetrics): Int = max(1, m.px)

/** A party icon's slot: the middle 24x24 of its 32x32 (the sprite rarely fills the edges), [hudIconScale]d -
 * 72 px on the Thor. [HudSpecies] draws the whole icon over it, spilling past the slot. */
@Composable
internal fun hudIconSize(m: GbaTextMetrics): Dp = with(LocalDensity.current) { (24 * hudIconScale(m)).toDp() }

/** [asset]'s party icon, the whole 32x32 at the slot's scale, centred on a [slot] that fits its middle 24x24. */
@Composable
internal fun HudSpecies(asset: String?, slot: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(slot), contentAlignment = Alignment.Center) {
        Box(Modifier.wrapContentSize(unbounded = true)) { SpeciesIcon(asset, slot * 32 / 24) }
    }
}

/** An item icon at the same scale as [hudIconSize]. */
@Composable
private fun hudItemSize(m: GbaTextMetrics): Dp = with(LocalDensity.current) { (24 * max(1, (m.px * 2 / 3f).roundToInt())).toDp() }

/**
 * An item's icon over [size], a GBA icon's 24 pixels. A DS game's icons are 32 x 32 with the picture well inside, so
 * there they're drawn at their own 32 pixels on the same scale (a third bigger, still whole pixels), centred on the
 * picture, over the same room.
 */
@Composable
private fun HudItemIcon(asset: String?, size: Dp, modifier: Modifier = Modifier) {
    if (!activeGame.isDsGame) {
        ItemIcon(asset, size, modifier)
        return
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        ItemIcon(asset, size * 32 / 24, Modifier.wrapContentSize(unbounded = true), centerArt = true)
    }
}

// ---- The HUD ------------------------------------------------------------------------------------

/**
 * The FIELD HUD over the game screen (this composable's whole box; the GBA's 3:2 is its middle, which RAILS keep
 * beside). [shown] false fades it out and closes what's open (a battle or a menu took the screen). [clearTop] /
 * [clearBottom] (px): room kept clear at the top and bottom - the touch controls' L / R and its D-pad / A / B. [itemUse]
 * / [fly]: the bag's USE and the map's FLY, as on the companion's tabs. [onOpenCompanion]: a single screen's closed
 * side panel - its open button joins BAG and WILD (null = none). [wild] / [initialPanel]: for screenshot tests
 * (the wild Pokémon are read from the ROM otherwise; tests can't tap).
 */
@Composable
fun FieldHud(
    snapshot: SnapshotView,
    shown: Boolean,
    style: FieldHudStyle,
    modifier: Modifier = Modifier,
    clearTop: Int = 0,
    clearBottom: Int = 0,
    itemUse: ItemUse? = null,
    fly: FlyAction? = null,
    onOpenCompanion: (() -> Unit)? = null,
    /** A DS game whose touch screen floats in (DS LAYOUT): TOUCH opens it by hand ([DsTouchWindow]); null = no button. */
    onTouchScreen: (() -> Unit)? = null,
    /** A DS game's touch screen is in the screen's bottom right corner (DS LAYOUT's LARGE + SMALL, PICTURE IN PICTURE): the
     * corners layouts move the balls and buttons to the bottom left and the party under the minimap ([DsCornerLayout]). */
    dsCorner: Boolean = false,
    /** With [dsCorner]: the share of the picture's height, from its bottom, a touch screen covers there (PICTURE IN
     * PICTURE): the party keeps above it, three to a row where two columns won't fit. */
    dsClearBottom: Float = 0f,
    /** Where the game's picture is in this box (px; ORIGINAL / STRETCH / an overlay): the HUD stays inside it (RAILS
     * beside it). Null = the whole box (EXPAND). */
    frame: Rect? = null,
    /** The touch controls' buttons (px, this box's): CORNERS' party, minimap and balls / buttons each move off them, the
     * least way that's clear, and stay in their corners where nothing's there (GitHub #493). */
    avoid: List<Rect> = emptyList(),
    wild: List<HudWild>? = null,
    initialPanel: FieldHudPanel? = null,
    /** Bumped to close whatever panel is open (the QUICK MENU opening over it). */
    closePanels: Int = 0,
    /** A tip's SHOW ME ([FeatureTipHost]): bumping [openTick] opens [openPanel] (its party slot / pocket second). */
    openPanel: Pair<FieldHudPanel, Int>? = null,
    openTick: Int = 0,
    /** The player tapped something on the HUD (its tip is done with). */
    onTap: () -> Unit = {},
    /** A panel opened (true) or closed: no tip comes up over one. */
    onPanel: (Boolean) -> Unit = {},
) {
    var panel by remember { mutableStateOf(initialPanel) }
    var summary by remember { mutableStateOf(0) }
    // The pocket the BAG opens on: the balls' own when they were tapped (-1 = the first).
    var bagPocket by remember { mutableStateOf(-1) }
    // PC: why the game's PC didn't open (not now, in a battle), over the HUD for a moment.
    var pcNotice by remember { mutableStateOf<ItemNotice?>(null) }
    // Gone (the game closed): SETTINGS no longer hears of a screen too small for it.
    DisposableEffect(Unit) { onDispose { FieldHudFit.tight = false } }
    // WILD's KEEP OPEN: no dim, the game plays on under it, and it's back after a battle.
    var wildKept by remember { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!shown) panel = null else if (wildKept) panel = FieldHudPanel.WILD }
    LaunchedEffect(closePanels) { if (closePanels != 0) panel = null }
    LaunchedEffect(openTick) {
        val (p, i) = openPanel ?: return@LaunchedEffect
        if (openTick == 0) return@LaunchedEffect
        if (p == FieldHudPanel.SUMMARY) summary = i
        if (p == FieldHudPanel.BAG) bagPocket = i
        panel = p
    }
    val panelOpen = panel != null
    LaunchedEffect(panelOpen) { onPanel(panelOpen) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 220 else 120), label = "field-hud")
    if (alpha == 0f && !shown) return

    // This map's wild Pokémon, read off the UI thread (null: no ROM tables for this game, so no WILD button).
    val live = snapshot.guideTables
    val wildSet = live?.wildSetOf(snapshot.mapGroup, snapshot.mapNum, snapshot.progress) ?: 0
    val read by produceState(
        live?.let { GuideRomSource.cachedEncounters(it, snapshot.mapGroup, snapshot.mapNum, wildSet) },
        live, snapshot.mapGroup, snapshot.mapNum, wildSet,
    ) {
        if (live != null) value = withContext(Dispatchers.IO) {
            snapshot.pokedex?.let { PokedexSource.nationalOf(it.tables, 1) } // warm the species -> dex number map
            GuideRomSource.encounters(live, snapshot.mapGroup, snapshot.mapNum, wildSet)
        }
    }
    // A game whose guide has its own tables (a DS game, Polished Crystal): GUIDE > HERE's world, for the save and clock.
    val world = if (live == null) remember(snapshot.game) { gameGuide(guideId(snapshot.game, null))?.world } else null
    val worldHere = remember(world, snapshot.mapGroup, snapshot.mapNum, snapshot.progress) {
        world?.encounters(snapshot.mapGroup, snapshot.mapNum, snapshot.progress)
    }
    val wildHere = wild ?: when {
        live != null -> remember(read, snapshot.pokedex) { hudWild(read, snapshot.pokedex) }
        world != null -> remember(worldHere, snapshot.pokedex) { hudWild(worldHere, snapshot.pokedex) }
        else -> null
    }

    // SIZE: a density whose GBA pixel (rememberGbaTextMetrics' 20dp / 16) is exactly the wanted whole screen pixels.
    val base = LocalDensity.current
    val baseK = with(base) { (20.dp.toPx() / 16f).roundToInt().coerceAtLeast(2) }
    val k = (baseK + style.size.step).coerceAtLeast(2)
    val hudDensity = remember(k, base.fontScale) { Density(k * 16f / 20f, base.fontScale) }
    val scale = HUD_SCALE

    CompositionLocalProvider(LocalHudLook provides style.look) {
        BoxWithConstraints(modifier.fillMaxSize().alpha(alpha)) {
            // The picture's insets from this box's edges (px): what the HUD keeps to.
            val boxW = with(base) { maxWidth.toPx() }
            val boxH = with(base) { maxHeight.toPx() }
            val inL = frame?.left?.coerceAtLeast(0f) ?: 0f
            val inT = frame?.top?.coerceAtLeast(0f) ?: 0f
            val inR = frame?.let { (boxW - it.right).coerceAtLeast(0f) } ?: 0f
            val inB = frame?.let { (boxH - it.bottom).coerceAtLeast(0f) } ?: 0f
            CompositionLocalProvider(LocalDensity provides hudDensity) {
                val m = rememberGbaTextMetrics(1f)
                // The touch controls' rows to keep clear of, cut back where they'd leave the HUD less than its own room
                // (a big D-pad and A / B along the bottom, GitHub #493): on the picture, and RAILS' whole box.
                val minRoom = with(hudDensity) { m.u.toPx() } * HUD_MIN_ROOM * scale
                val (padT, padB) = hudClears(clearTop.toFloat(), clearBottom.toFloat(), inT, inB, boxH, minRoom)
                val (railT, railB) = hudClears(clearTop.toFloat(), clearBottom.toFloat(), 0f, 0f, boxH, minRoom)
                val pc = itemUse?.takeIf { it.canPc() }?.let { use ->
                    {
                        onTap()
                        use.openPc { o -> pcNotice = if (o.result == ItemUseResult.USED) null else ItemNotice(0, itemUseMessage(o), ok = false) }
                    }
                }
                // REPEL's steps left (the game's VAR_REPEL_STEP_COUNT), where the game's var is known.
                val repel = if (style.shows(FieldHudPart.REPEL)) snapshot.repelSteps
                    ?: itemUse?.let { u -> u.repelVar?.let { snapshot.progress?.variable(it) }?.let(u::repelSteps) } ?: 0 else 0
                // What a full one lasted, for the bar: the kind whose steps cover what's left, kept while it counts down.
                var repelFull by remember { mutableStateOf(0) }
                if (repel <= 0) repelFull = 0
                else if (repel > repelFull) repelFull = com.pokedaisy.app.companion.REPEL_KINDS.firstOrNull { it.second >= repel }?.second ?: repel
                val parts = HudParts(snapshot, style, wildHere, m, onOpenCompanion, pc, repel, repelFull, onTouchScreen) { p, i ->
                    onTap()
                    if (p == FieldHudPanel.SUMMARY) summary = i
                    if (p == FieldHudPanel.BAG) bagPocket = i
                    panel = p
                }
                with(hudDensity) {
                    // Too narrow for the party beside its buttons (a docked side panel's game): every LAYOUT gives way to
                    // the compact HUD, and SETTINGS > HUD > LAYOUT says why it's off ([FieldHudFit]). Decided in the
                    // same layout pass that draws it, so the wrong one never shows for a frame.
                    HudFit(parts, (boxW - inL - inR) / scale) { tight ->
                        SideEffect { FieldHudFit.tight = tight }
                        if (tight) {
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(start = inL.toDp(), top = inT.toDp(), end = inR.toDp(), bottom = inB.toDp())
                                    .padding(top = padT.toDp(), bottom = padB.toDp()),
                            ) {
                                Box(Modifier.fillMaxSize().hudScale(scale)) { CompactLayout(parts) }
                            }
                        } else if (style.layout == FieldHudLayout.RAILS) {
                            // RAILS use the room beside the picture: the whole box, told where the picture's sides are.
                            Box(Modifier.fillMaxSize().padding(top = railT.toDp(), bottom = railB.toDp()).hudScale(scale)) {
                                RailsLayout(parts, (inL / scale).toDp(), (inR / scale).toDp())
                            }
                        } else {
                            // The others stay on the picture; the touch controls' rows only where they reach into it.
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(start = inL.toDp(), top = inT.toDp(), end = inR.toDp(), bottom = inB.toDp())
                                    .padding(top = padT.toDp(), bottom = padB.toDp()),
                            ) {
                                // The buttons in the layout's own space: from the padded box's corner, before the scale.
                                val oy = inT + padT
                                val obstacles = avoid.map { Rect((it.left - inL) / scale, (it.top - oy) / scale, (it.right - inL) / scale, (it.bottom - oy) / scale) }
                                Box(Modifier.fillMaxSize().hudScale(scale)) {
                                    when (style.layout) {
                                        FieldHudLayout.CORNERS -> if (dsCorner) DsCornerLayout(parts, dsClearBottom) else CornersLayout(parts, swapped = false, obstacles)
                                        FieldHudLayout.CORNERS_2 -> if (dsCorner) DsCornerLayout(parts, dsClearBottom) else CornersLayout(parts, swapped = true, obstacles)
                                        else -> DockLayout(parts)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // The panels at the companion's own size, whatever SIZE says.
            val m = rememberGbaTextMetrics(1f)
            val dock = style.layout == FieldHudLayout.DOCK
            // CORNERS 2's buttons are top right: BAG and WILD open there (the compact HUD's: bottom left, panelPlace).
            val top = style.layout == FieldHudLayout.CORNERS_2
            val close = { panel = null }
            // BAG and WILD open over the buttons: on the picture, like them (RAILS: the screen's corner).
            val onPicture = style.layout != FieldHudLayout.RAILS || FieldHudFit.tight
            val inset = with(base) {
                if (onPicture) PaddingValues(start = inL.toDp(), top = inT.toDp(), end = inR.toDp(), bottom = inB.toDp()) else PaddingValues()
            }
            ItemNoticeHost(pcNotice, m, Modifier.align(Alignment.TopCenter).padding(inset).padding(top = m.u * 6, start = m.u * 8, end = m.u * 8)) { pcNotice = null }
            when (panel) {
                FieldHudPanel.MAP -> HudMapPanel(snapshot, m, fly, close)
                FieldHudPanel.BAG -> HudBagPanel(snapshot, m, itemUse, dock, top, inset, bagPocket, close)
                FieldHudPanel.WILD -> HudWildPanel(snapshot, wildHere.orEmpty(), m, dock, top, inset, wildKept, { wildKept = !wildKept }) { wildKept = false; close() }
                FieldHudPanel.SUMMARY -> HudSummaryPanel(snapshot, summary, { summary = it }, m, itemUse, close)
                null -> Unit
            }
        }
    }
}

/** The HUD's picture too narrow for the party beside its buttons (FieldHud's compact HUD): SETTINGS > HUD > LAYOUT is off
 * meanwhile, saying why. Compose state, set by the HUD on each layout; false once it's gone. */
object FieldHudFit {
    var tight by mutableStateOf(false)
}

/** Every SIZE drawn 12.5% bigger than its whole GBA pixel (the user's call: a whole pixel more was too much; NORMAL
 * 3 -> 3.375 on the RP6, liked over 3.5), so the HUD's pixels are no longer whole screen pixels. */
private const val HUD_SCALE = 1.125f

/** The height the HUD keeps for itself between the touch controls' rows (HUD GBA pixels, before [HUD_SCALE]): CORNERS'
 * minimap over its balls and buttons, with their margins. */
private const val HUD_MIN_ROOM = 150

/**
 * The room the HUD keeps clear at the top and bottom of the picture (px, past its own insets [inT] / [inB]) for touch
 * controls reaching [clearTop] / [clearBottom] down / up from the box's edges: both cut back by the same share where
 * they'd leave less than [minRoom] between them (or all the picture has, if less), so the HUD never squeezes into a strip.
 */
internal fun hudClears(clearTop: Float, clearBottom: Float, inT: Float, inB: Float, boxH: Float, minRoom: Float): Pair<Float, Float> {
    val t = (clearTop - inT).coerceAtLeast(0f)
    val b = (clearBottom - inB).coerceAtLeast(0f)
    val picture = (boxH - inT - inB).coerceAtLeast(0f)
    val allowed = (picture - minRoom).coerceAtLeast(0f)
    if (t + b <= allowed) return t to b
    val k = allowed / (t + b)
    return t * k to b * k
}

/** Lays the content out in this box shrunk by [scale], then draws it scaled back up from the top left: the parts keep
 * their places (corners stay corners) and taps still land (Compose hit-tests through the layer). */
private fun Modifier.hudScale(scale: Float): Modifier = layout { measurable, c ->
    val w = c.maxWidth
    val h = c.maxHeight
    val p = measurable.measure(Constraints.fixed((w / scale).roundToInt(), (h / scale).roundToInt()))
    layout(w, h) {
        p.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** What every layout draws from. */
private class HudParts(
    val snapshot: SnapshotView,
    val style: FieldHudStyle,
    val wild: List<HudWild>?,
    val m: GbaTextMetrics,
    /** Opens the single screen's side panel (the companion); null = no button for it. */
    val companion: (() -> Unit)?,
    /** Opens the game's PC (ItemUse.openPc); null = this game has none. */
    pcOpen: (() -> Unit)?,
    /** Steps left on the REPEL in effect (0 = none, or not shown): its own window by the balls ([RepelSteps]). */
    val repel: Int,
    /** The steps it started from (the bar's whole). */
    val repelFull: Int,
    /** Opens a DS game's touch screen over the game (DS LAYOUT); null = no button for it. */
    val touch: (() -> Unit)?,
    /** Opens a panel; the index is the party member for SUMMARY, the pocket for BAG (-1: the first). */
    val open: (FieldHudPanel, Int) -> Unit,
) {
    val party = if (style.shows(FieldHudPart.PARTY)) snapshot.party.take(6) else emptyList()
    val balls = if (style.shows(FieldHudPart.BALLS)) snapshot.items.filter { it.pocket == POCKET_POKE_BALLS && it.quantity > 0 } else emptyList()
    val bag = style.shows(FieldHudPart.BAG) && snapshot.items.isNotEmpty()
    val wildButton = style.shows(FieldHudPart.WILD) && wild != null
    val map = style.shows(FieldHudPart.MAP)
    val pc = pcOpen?.takeIf { style.shows(FieldHudPart.PC) }
    /** The balls' window shows. */
    val ballsRow = balls.isNotEmpty()
    /** Someone has a status tag (or fainted): the party's tiles get a row for the tags over their icons, and the party's
     * window is that much taller; with none it's shorter (the user's call). */
    val tagRow = party.any { !it.isEgg && (it.hp == 0 || it.status.isNotEmpty()) }
}

// ---- Layouts ------------------------------------------------------------------------------------

/** CORNERS (the minimap top right, the balls and buttons bottom right) and CORNERS 2 ([swapped]: the other way round). */
@Composable
private fun CornersLayout(p: HudParts, swapped: Boolean, obstacles: List<Rect> = emptyList()) {
    val u = p.m.u
    Layout(
        content = {
            // On the right, never top left: the games slide a new area's name in there (FireRed's and Emerald's sign).
            if (p.map) {
                HudMiniMap(p.snapshot, mapW = 104, mapH = 64, m = p.m, modifier = Modifier.layoutId(CornerPart.MAP)) { p.open(FieldHudPanel.MAP, 0) }
            }
            PartyRow(p, Modifier.layoutId(CornerPart.PARTY))
            Column(
                Modifier.layoutId(CornerPart.BUTTONS),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(u * 4),
            ) {
                // The buttons toward the screen's edge: under the balls at the bottom, over them at the top.
                // The REPEL's steps on the far side of the balls from the buttons.
                if (swapped) HudButtons(p, vertical = false)
                if (!swapped) RepelSteps(p)
                if (p.ballsRow) HudBalls(p, vertical = false)
                if (swapped) RepelSteps(p)
                if (!swapped) HudButtons(p, vertical = false)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, c ->
        val pad = (u * 6).roundToPx()
        val gap = (u * 2).toPx()
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val box = Rect(pad.toFloat(), pad.toFloat(), (c.maxWidth - pad).toFloat(), (c.maxHeight - pad).toFloat())
        val parts = measurables.associate { (it.layoutId as CornerPart) to it.measure(loose) }
        // Each part's corner as CORNERS / CORNERS 2 puts it.
        val corners = listOf(
            CornerPart.PARTY to (false to true),
            CornerPart.BUTTONS to (true to !swapped),
            CornerPart.MAP to (true to swapped),
        ).mapNotNull { (part, corner) -> parts[part]?.let { it to corner } }
        val at = hudCornerPlaces(corners.map { (pl, corner) -> HudCornerItem(pl.width.toFloat(), pl.height.toFloat(), corner.first, corner.second) }, box, obstacles, gap)
        layout(c.maxWidth, c.maxHeight) { corners.forEachIndexed { i, (pl, _) -> pl.place(at[i].left.roundToInt(), at[i].top.roundToInt()) } }
    }
}

private enum class CornerPart { MAP, PARTY, BUTTONS }

/** A part of CORNERS: its size and its corner. */
internal data class HudCornerItem(val w: Float, val h: Float, val right: Boolean, val bottom: Boolean)

/**
 * Where CORNERS' [items] go in [box] around the touch controls' buttons ([obstacles]): each in its corner where that's
 * clear, else stepped off the buttons ([hudCornerSpot]). Placed in every order, the one that leaves the most parts clear,
 * then moves them least, wins: one part going first can take the only clear place another had.
 */
internal fun hudCornerPlaces(items: List<HudCornerItem>, box: Rect, obstacles: List<Rect>, gap: Float): List<Rect> {
    fun corner(it: HudCornerItem): Rect {
        val x = if (it.right) box.right - it.w else box.left
        val y = if (it.bottom) box.bottom - it.h else box.top
        return Rect(x, y, x + it.w, y + it.h)
    }
    fun orders(left: List<Int>): List<List<Int>> =
        if (left.size <= 1) listOf(left) else left.flatMap { i -> orders(left - i).map { listOf(i) + it } }
    var best: List<Rect>? = null
    var bestMissed = Int.MAX_VALUE
    var bestMoved = Float.MAX_VALUE
    for (order in orders(items.indices.toList())) {
        val at = arrayOfNulls<Rect>(items.size)
        var missed = 0
        var moved = 0f
        for (i in order) {
            val c = corner(items[i])
            val spot = hudCornerSpot(items[i].w, items[i].h, items[i].right, items[i].bottom, box, obstacles, at.filterNotNull(), gap)
            if (spot == null) missed++ else moved += abs(spot.left - c.left) + abs(spot.top - c.top)
            at[i] = spot ?: c
        }
        if (missed < bestMissed || (missed == bestMissed && moved < bestMoved)) {
            best = at.map { it!! }; bestMissed = missed; bestMoved = moved
        }
    }
    return best ?: emptyList()
}

/**
 * Where a [w] x [h] part of CORNERS goes: its corner of [box] ([right] / [bottom]) if nothing in [obstacles] (the touch
 * controls) or [taken] (the parts already placed) is there, else the clear place nearest that corner - slid along its
 * edge or in from it past the buttons in the way, [gap] from them - inside [box]. Null: nowhere clear.
 */
internal fun hudCornerSpot(
    w: Float, h: Float, right: Boolean, bottom: Boolean, box: Rect, obstacles: List<Rect>, taken: List<Rect>, gap: Float,
): Rect? {
    val x0 = if (right) box.right - w else box.left
    val y0 = if (bottom) box.bottom - h else box.top
    val corner = Rect(x0, y0, x0 + w, y0 + h)
    val hits = obstacles + taken
    fun clear(r: Rect) = hits.none { it.left < r.right && it.right > r.left && it.top < r.bottom && it.bottom > r.top }
    if (clear(corner)) return corner
    // Every edge a part could stop against: the corner's, and just past each button on either side.
    val xs = listOf(x0) + hits.flatMap { listOf(it.right + gap, it.left - gap - w) }
    val ys = listOf(y0) + hits.flatMap { listOf(it.bottom + gap, it.top - gap - h) }
    return xs.filter { it >= box.left - 0.5f && it + w <= box.right + 0.5f }
        .flatMap { x -> ys.filter { it >= box.top - 0.5f && it + h <= box.bottom + 0.5f }.map { y -> Rect(x, y, x + w, y + h) } }
        .filter(::clear)
        .minByOrNull { abs(it.left - x0) + abs(it.top - y0) }
}

/** CORNERS with a DS game's touch screen in the bottom right corner: the minimap top right with the party under it in two
 * columns, the balls over the buttons in the bottom left corner (the user's arrangement). Where the touch screen sits over
 * the picture ([clearBottom], a share of its height) and two columns would reach it, the party goes three to a row. */
@Composable
private fun DsCornerLayout(p: HudParts, clearBottom: Float) {
    val u = p.m.u
    Box(Modifier.fillMaxSize().padding(u * 6)) {
        SubcomposeLayout(Modifier.align(Alignment.TopEnd)) { c ->
            val loose = c.copy(minWidth = 0, minHeight = 0)
            val room = (c.maxHeight + 2 * (u * 6).roundToPx()) * (1f - clearBottom) - (u * 6).roundToPx()
            fun column(perRow: Int) = subcompose(perRow) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(u * 4)) {
                    if (p.map) HudMiniMap(p.snapshot, mapW = 104, mapH = 64, m = p.m) { p.open(FieldHudPanel.MAP, 0) }
                    PartyColumns(p, perRow)
                }
            }.map { it.measure(loose) }
            val two = column(2)
            val placed = if (clearBottom > 0f && (two.maxOfOrNull { it.height } ?: 0) > room) column(3) else two
            val w = placed.maxOfOrNull { it.width } ?: 0
            layout(w, placed.maxOfOrNull { it.height } ?: 0) { placed.forEach { it.place(w - it.width, 0) } }
        }
        BallsAndButtons(p, Modifier.align(Alignment.BottomStart), alignment = Alignment.Start)
    }
}

/** The party [perRow] to a row in a panel (a DS game's corners, [DsCornerLayout]). */
@Composable
private fun PartyColumns(p: HudParts, perRow: Int) {
    if (p.party.isEmpty()) return
    val u = p.m.u
    Column(Modifier.hudPanel(u).padding(horizontal = u * 5, vertical = u * 4), verticalArrangement = Arrangement.spacedBy(u * 3)) {
        p.party.withIndex().chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                row.forEach { (i, mon) -> HudMonTile(mon, p.m, p.tagRow) { p.open(FieldHudPanel.SUMMARY, i) } }
            }
        }
    }
}

/** The party as one row of tiles in a panel (CORNERS). */
@Composable
private fun PartyRow(p: HudParts, modifier: Modifier) {
    if (p.party.isEmpty()) return
    val u = p.m.u
    Row(
        modifier.hudPanel(u).padding(horizontal = u * 5, vertical = u * 4),
        horizontalArrangement = Arrangement.spacedBy(u * 3),
    ) { p.party.forEachIndexed { i, mon -> HudMonTile(mon, p.m, p.tagRow) { p.open(FieldHudPanel.SUMMARY, i) } } }
}

/** CORNERS' bottom-right corner: the balls over the buttons. */
@Composable
private fun BallsAndButtons(p: HudParts, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.End, mapButton: Boolean = false) {
    Column(modifier, horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(p.m.u * 4)) {
        RepelSteps(p)
        if (p.ballsRow) HudBalls(p, vertical = false)
        HudButtons(p, vertical = false, mapButton = mapButton)
    }
}

/**
 * [content] given whether the picture is too narrow for the party beside the balls and buttons (CORNERS' bottom row;
 * [width] in the HUD's own px, before [hudScale]): then the compact HUD, whatever LAYOUT says. Both measured at their own
 * widths in the same layout pass, so it follows the picture (a side panel docked or not, SIZE) without a wrong frame.
 */
@Composable
private fun HudFit(p: HudParts, width: Float, content: @Composable (tight: Boolean) -> Unit) {
    SubcomposeLayout(Modifier.fillMaxSize()) { c ->
        val loose = Constraints()
        val partyW = if (p.party.isEmpty()) 0 else subcompose("party") { PartyRow(p, Modifier) }.maxOfOrNull { it.measure(loose).width } ?: 0
        val rightW = subcompose("right") { BallsAndButtons(p) }.maxOfOrNull { it.measure(loose).width } ?: 0
        val tight = partyW > 0 && partyW + rightW + 3 * (p.m.u * 6).roundToPx() > width
        val placeables = subcompose(tight) { content(tight) }.map { it.measure(c) }
        layout(c.maxWidth, c.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

/** The compact HUD (too narrow a picture, [HudFit]): no party or minimap - the balls over PC / BAG / WILD and a MAP
 * button, all in the bottom left corner, the balls left-aligned too. */
@Composable
private fun CompactLayout(p: HudParts) {
    Box(Modifier.fillMaxSize().padding(p.m.u * 6)) {
        BallsAndButtons(p, Modifier.align(Alignment.BottomStart), alignment = Alignment.Start, mapButton = true)
    }
}

@Composable
private fun RailsLayout(p: HudParts, left: Dp, right: Dp) {
    val u = p.m.u
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The room beside the picture (EXPAND: beside the middle 3:2, where the rebuilt map is); on a screen with next
        // to none, a rail keeps a usable width over the game.
        val middle = ((maxWidth - minOf(maxWidth, maxHeight * 1.5f)) / 2)
        val side = (if (left > 0.dp || right > 0.dp) minOf(left, right) else middle).coerceAtLeast(u * 50)
        if (p.party.isNotEmpty()) {
            // As wide as the margin, or its rows if they need more (LARGE).
            Column(
                Modifier.align(Alignment.CenterStart).padding(u * 2).width(IntrinsicSize.Max).widthIn(min = side - u * 4)
                    .hudPanel(u).padding(top = u * 6, bottom = u * 3, start = u, end = u * 2),
                verticalArrangement = Arrangement.spacedBy(u * 4),
            ) { p.party.forEachIndexed { i, mon -> HudMonRow(mon, p.m) { p.open(FieldHudPanel.SUMMARY, i) } } }
        }
        Column(
            Modifier.align(Alignment.TopEnd).width(side).fillMaxHeight().padding(u * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(u * 4),
        ) {
            val inner = (side / u).toInt() - 4 - 8
            if (p.map) HudMiniMap(p.snapshot, mapW = inner, mapH = inner, m = p.m, nameLines = 2) { p.open(FieldHudPanel.MAP, 0) }
            Spacer(Modifier.weight(1f))
            RepelSteps(p, narrow = true)
            if (p.ballsRow) HudBalls(p, vertical = true)
            HudButtons(p, vertical = true)
        }
    }
}

/** DOCK: the party, balls and buttons in one bar along the bottom, the minimap top right; CORNERS where that bar is
 * wider than the picture (CORNERS fits, or the HUD would be compact). */
@Composable
private fun DockLayout(p: HudParts) {
    val u = p.m.u
    SubcomposeLayout(Modifier.fillMaxSize()) { c ->
        val barW = subcompose("bar") { DockBar(p, party = true, Modifier) }.maxOfOrNull { it.measure(Constraints()).width } ?: 0
        val fits = barW + 2 * (u * 6).roundToPx() <= c.maxWidth
        val placeables = subcompose(fits) {
            if (!fits) CornersLayout(p, swapped = false)
            else Box(Modifier.fillMaxSize().padding(u * 6)) {
                if (p.map) HudMiniMap(p.snapshot, mapW = 80, mapH = 52, m = p.m, modifier = Modifier.align(Alignment.TopEnd)) { p.open(FieldHudPanel.MAP, 0) }
                DockBar(p, party = true, Modifier.align(Alignment.BottomCenter))
            }
        }.map { it.measure(c) }
        layout(c.maxWidth, c.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

/** DOCK's bar: the party ([party]), the balls, then the buttons, with dividers. */
@Composable
private fun DockBar(p: HudParts, party: Boolean, modifier: Modifier) {
    val u = p.m.u
    val mons = if (party) p.party else emptyList()
    val buttons = p.bag || p.wildButton || p.companion != null || p.pc != null || p.touch != null
    if (mons.isEmpty() && !p.ballsRow && p.repel <= 0 && !buttons) return
    Row(
        modifier.hudPanel(u).padding(horizontal = u * 5, vertical = u * 4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 3),
    ) {
        mons.forEachIndexed { i, mon -> HudMonTile(mon, p.m, p.tagRow) { p.open(FieldHudPanel.SUMMARY, i) } }
        if (p.ballsRow) {
            if (mons.isNotEmpty()) DockDivider(p.m)
            HudBallsInline(p)
        }
        if (p.repel > 0) {
            if (mons.isNotEmpty() || p.ballsRow) DockDivider(p.m)
            RepelSteps(p, framed = false)
        }
        if (buttons) {
            if (mons.isNotEmpty() || p.ballsRow || p.repel > 0) DockDivider(p.m)
            HudButtons(p, vertical = false, framed = false)
        }
    }
}

@Composable
private fun DockDivider(m: GbaTextMetrics) {
    Box(Modifier.width(m.u).height(m.u * 30).background(ink.divider))
}

// ---- Party --------------------------------------------------------------------------------------

/**
 * One party member as a tile: its status tag (in a row of its own over the icon, [tagRow]: when anyone in the party has
 * one), its icon, the HP bar under it. No level and no lit lead: the party at a glance (the user's call with the HP box
 * look). A tap opens its summary.
 */
@Composable
private fun HudMonTile(mon: MonView, m: GbaTextMetrics, tagRow: Boolean, onClick: () -> Unit) {
    val u = m.u
    val icon = hudIconSize(m)
    Column(
        Modifier.width(icon + u * 2).soundClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tagRow) {
            HudMonTag(mon, u)
            Spacer(Modifier.height(u * 2))
        }
        HudMonIcon(mon, icon, u, tag = false)
        if (!mon.isEgg) {
            // Room under the sprite (it spills past its slot) before the bar.
            Spacer(Modifier.height(u * 3))
            HudHpBar(mon.hp, mon.maxHp, Modifier.fillMaxWidth().height(u * 5))
        }
    }
}

/** One party member as a row (RAILS): the icon, then its status tag (if any) over the HP bar. */
@Composable
private fun HudMonRow(mon: MonView, m: GbaTextMetrics, onClick: () -> Unit) {
    val u = m.u
    Row(Modifier.soundClickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        HudMonIcon(mon, hudIconSize(m), u, tag = false)
        if (!mon.isEgg) {
            Column(Modifier.padding(start = u).width(u * 26), verticalArrangement = Arrangement.spacedBy(u * 2)) {
                if (mon.hp == 0 || mon.status.isNotEmpty()) HudMonTag(mon, u)
                HudHpBar(mon.hp, mon.maxHp, Modifier.fillMaxWidth().height(u * 5))
            }
        }
    }
}

/** [mon]'s status tag (PSN, SLP, FNT...), or its room kept empty: every tile in a party with a tag row lines up. */
@Composable
private fun HudMonTag(mon: MonView, u: Dp) {
    val density = LocalDensity.current
    val upx = with(density) { u.roundToPx() }
    val status = if (!mon.isEgg && mon.hp == 0) "FNT" else if (mon.isEgg) "" else mon.status
    Box(Modifier.alpha(if (status.isEmpty()) 0f else 1f)) {
        HudStatusChip(status.ifEmpty { "FNT" }, u, slotTextMetrics(density, max(1, (upx * 0.6f).roundToInt())))
    }
}

/** The party icon; fainted ones faded with FNT on them, a status chip (PSN, SLP, ...) over its top ([tag]: the HUD's party
 * puts it in a row of its own instead), NUZLOCKE's gravestone. */
@Composable
internal fun HudMonIcon(mon: MonView, size: Dp, u: Dp, tag: Boolean = true) {
    val density = LocalDensity.current
    val upx = with(density) { u.roundToPx() }
    val fainted = !mon.isEgg && mon.hp == 0
    Box(Modifier.size(size)) {
        HudSpecies(mon.iconAsset, size, Modifier.alpha(if (fainted) 0.4f else 1f))
        val status = if (fainted) "FNT" else mon.status
        if (tag && !mon.isEgg && status.isNotEmpty()) {
            // Over the icon's top edge, as wide as it needs (the icon box is narrower than "PSN" at some sizes).
            Box(Modifier.align(Alignment.TopCenter).wrapContentSize(unbounded = true).offset(y = -u * 2)) {
                HudStatusChip(status, u, slotTextMetrics(density, max(1, (upx * 0.6f).roundToInt())))
            }
        }
        // NUZLOCKE: out of the run, healed or not - a small gravestone in its bottom-right corner.
        if (com.pokedaisy.app.companion.Nuzlocke.isDead(mon.personality)) {
            PixelArt(TabIcons.grave.bitmap, TabIcons.grave.palette, Modifier.align(Alignment.BottomEnd).size(size * 0.45f))
        }
    }
}

/**
 * The party tab's status chip ([StatusChip]: its colours, a darker rim) as tall as its text: that one is sized for the
 * party slot's bigger pixel, and at the HUD's the letters' last row and shadow were cut off.
 */
@Composable
private fun HudStatusChip(status: String, u: Dp, m: GbaTextMetrics) {
    val color = if (status == "FNT") Color(0xFF6B6B6B) else com.pokedaisy.app.companion.ui.theme.AppColors.statusColor(status)
    Box(
        Modifier
            .background(androidx.compose.ui.graphics.lerp(color, Color.Black, 0.35f), PixelRoundedShape(u * 2))
            .padding(u)
            .background(color, PixelRoundedShape(u))
            .padding(horizontal = u * 3),
        contentAlignment = Alignment.Center,
    ) { GbaText(status, Color.White, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.5f), m) }
}

/** The party menu's HP bar in the running game's colours, without the "HP" label. */
@Composable
internal fun HudHpBar(hp: Int, maxHp: Int, modifier: Modifier) {
    val p = partyPaletteFor(activeGame)
    val u = with(LocalDensity.current) { gbaPixelPx() }
    Canvas(modifier) {
        val w = size.width
        drawPixelRoundRect(p.hpFrame, Offset.Zero, Size(w, 5 * u), radius = u, step = u)
        val inner = w - 2 * u
        val fill = if (maxHp > 0) (kotlin.math.floor(inner / u * hp.coerceIn(0, maxHp) / maxHp) * u).coerceAtLeast(if (hp > 0) u else 0f) else 0f
        val c = when {
            maxHp <= 0 || hp * 2 > maxHp -> p.hpGreen
            hp * 5 > maxHp -> p.hpYellow
            else -> p.hpRed
        }
        drawRect(p.hpEmpty.top, Offset(u, u), Size(inner, u))
        drawRect(p.hpEmpty.main, Offset(u, 2 * u), Size(inner, 2 * u))
        if (fill > 0f) {
            drawRect(c.top, Offset(u, u), Size(fill, u))
            drawRect(c.main, Offset(u, 2 * u), Size(fill, 2 * u))
        }
    }
}

// ---- Balls and buttons --------------------------------------------------------------------------

/** The bag's Poké Balls, most first (three at most): each one's icon and how many. A tap opens the BAG. */
@Composable
private fun HudBalls(p: HudParts, vertical: Boolean) {
    val u = p.m.u
    val mod = Modifier.hudPanel(u).soundClickable { p.open(FieldHudPanel.BAG, POCKET_POKE_BALLS) }
        .padding(horizontal = if (vertical) u * 2 else u * 4, vertical = u * 3)
    val balls = p.balls.sortedByDescending { it.quantity }.take(3)
    if (vertical) {
        val small = rememberGbaTextMetrics(0.75f)
        Column(mod) {
            balls.forEach { BallCount(it, p.m, small) }
        }
    } else {
        Row(mod, horizontalArrangement = Arrangement.spacedBy(u * 4)) { balls.forEach { BallCount(it, p.m, p.m) } }
    }
}

/** [HudBalls] without its own window, for the DOCK's bar. */
@Composable
private fun HudBallsInline(p: HudParts) {
    Row(Modifier.soundClickable { p.open(FieldHudPanel.BAG, POCKET_POKE_BALLS) }, horizontalArrangement = Arrangement.spacedBy(p.m.u * 3)) {
        p.balls.sortedByDescending { it.quantity }.take(3).forEach { BallCount(it, p.m, p.m) }
    }
}

private val REPEL_GREEN = Color(0xFF58D080)
private val REPEL_YELLOW = Color(0xFFF8C030)
private val REPEL_RED = Color(0xFFE84838)

/** What's left of the REPEL as a bar [w] wide, its colour by how much (an HP bar's way). */
@Composable
private fun RepelBar(p: HudParts, w: Dp) {
    val u = p.m.u
    val left = if (p.repelFull > 0) (p.repel / p.repelFull.toFloat()).coerceIn(0f, 1f) else 0f
    val fill = when {
        left > 0.5f -> REPEL_GREEN
        left > 0.2f -> REPEL_YELLOW
        else -> REPEL_RED
    }
    Box(Modifier.width(w).height(u * 4).background(ink.cell, PixelRoundedShape(u)).padding(u)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(left).background(fill))
    }
}

/**
 * REPEL's steps left while one works (nothing otherwise): its own window by the balls - the spray's icon, the count (gold
 * for the last 20 steps) with "steps", and a bar draining from green to yellow to red as it runs out. [framed] false: no
 * window (the DOCK's bar); [narrow]: the count over the bar beside a smaller icon (RAILS' column). A tap opens the BAG.
 */
@Composable
private fun RepelSteps(p: HudParts, framed: Boolean = true, narrow: Boolean = false) {
    if (p.repel <= 0) return
    val u = p.m.u
    val low = p.repel <= 20
    val count = rememberGbaTextMetrics(1.25f)
    val small = rememberGbaTextMetrics(0.75f)
    Row(
        (if (framed) Modifier.hudPanel(u) else Modifier).soundClickable { p.open(FieldHudPanel.BAG, -1) }
            // Even room on both sides as drawn: the spray's icon has empty pixels round its picture, the text none.
            .padding(start = if (framed) u * 2 else 0.dp, end = if (framed) u * 5 else 0.dp, top = if (framed) u * 3 else 0.dp, bottom = if (framed) u * 3 else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * if (narrow) 2 else 3),
    ) {
        HudItemIcon(spriteAsset("items", REPEL_ICON_ITEM), hudItemSize(p.m) * if (narrow) 1f else 1.25f)
        Column(verticalArrangement = Arrangement.spacedBy(u)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(u * 2)) {
                GbaText("${p.repel}", if (low) ink.accent else ink.text, if (low) ink.accentShadow else ink.shadow, count)
                // "steps" alone: the translated "{0} steps" without its number.
                if (!narrow) GbaText(tr("{0} steps", "").trim(), ink.muted, ink.shadow, small, modifier = Modifier.padding(bottom = u))
            }
            RepelBar(p, u * if (narrow) 30 else 46)
        }
    }
}

/** The plain REPEL's icon for the counter (the game doesn't keep which kind is working). */
private const val REPEL_ICON_ITEM = 86

@Composable
private fun BallCount(item: ItemView, m: GbaTextMetrics, text: GbaTextMetrics) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The Game Boy games have no item icons: a drawn ball in the icon's place.
        if (item.iconAsset != null) HudItemIcon(item.iconAsset, hudItemSize(m))
        else PixelArt(PixelIcons.pokeBall, CAUGHT_BALL, Modifier.size(hudItemSize(m)).padding(hudItemSize(m) / 8))
        // Elite Redux keeps no counts: the ball alone.
        if (com.pokedaisy.app.companion.data.bagHasCounts) GbaText("x${item.quantity}", ink.text, ink.shadow, text)
    }
}

/** BAG and WILD (with how many species here aren't caught yet). */
@Composable
private fun HudButtons(p: HudParts, vertical: Boolean, framed: Boolean = true, mapButton: Boolean = false) {
    val u = p.m.u
    val buttons = buildList<@Composable () -> Unit> {
        // Left of BAG: the game's own PC, not a HUD panel: the corner arrow says it leaves for the game ([PixelIcons.inGame]).
        p.pc?.let { openPc -> add { HudButton(PC_ICON, PC_COLORS, tk("PC"), null, p.m, framed, inGame = true, onClick = openPc) } }
        // The game's own word for its bag (CUBE, PACK, ITEM...), as the tab chip has it.
        if (p.bag) add { HudButton(BAG_ICON, BAG_COLORS, companionTabLabel("ITEMS"), null, p.m, framed) { p.open(FieldHudPanel.BAG, -1) } }
        if (p.wildButton) {
            val uncaught = p.wild.orEmpty().distinctBy { it.species }.count { !it.caught }
            add { HudButton(GRASS_ICON, GRASS_COLORS, tk("WILD"), uncaught.takeIf { it > 0 }?.toString(), p.m, framed) { p.open(FieldHudPanel.WILD, 0) } }
        }
        // The compact HUD ([CompactLayout]): no minimap, so the map from a button, right of WILD.
        if (mapButton && p.map) add { HudButton(MAP_ICON, MAP_COLORS, tk("MAP"), null, p.m, framed) { p.open(FieldHudPanel.MAP, 0) } }
        // A DS game: its touch screen floating in over the game (the game's own one opens it by itself when it's wanted).
        p.touch?.let { openTouch -> add { HudButton(TOUCH_ICON, TOUCH_COLORS, tk("TOUCH"), null, p.m, framed, onClick = openTouch) } }
        // One screen: the side panel's open tab, as one of these buttons while the HUD is up (SidePanel.tabInHud).
        p.companion?.let { openCompanion ->
            add { HudButton(COMPANION_ICON, mapOf('1' to ink.text), tk("COMPANION"), null, p.m, framed, onClick = openCompanion) }
        }
    }
    if (vertical) Column(verticalArrangement = Arrangement.spacedBy(u * 3)) { buttons.forEach { it() } }
    else Row(horizontalArrangement = Arrangement.spacedBy(u * 3)) { buttons.forEach { it() } }
}

/** A square button: pixel [icon] over a small label; [badge] a red count on its corner; [inGame] a small arrow in its
 * top-right corner: it opens the game's own screen, not a HUD panel. */
@Composable
private fun HudButton(
    icon: List<String>, colors: Map<Char, Color>, label: String, badge: String?, m: GbaTextMetrics, framed: Boolean,
    inGame: Boolean = false, onClick: () -> Unit,
) {
    val u = m.u
    val small = rememberGbaTextMetrics(0.75f)
    Box {
        Column(
            (if (framed) Modifier.hudPanel(u) else Modifier).soundClickable(onClick = onClick)
                .widthIn(min = u * 34).padding(horizontal = u * 3).padding(top = u * 4, bottom = u * 4 + hudEdge(u)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelArt(icon, colors, Modifier.size(u * 16))
            // The label in the room under the icon, with as much under it (not pressed against the button's edge).
            Spacer(Modifier.height(u * 2))
            GbaText(tr(label), ink.text, ink.shadow, small, bold = gameBoldLabels())
        }
        if (inGame) {
            ShadowedPixelIcon(PixelIcons.inGame, ink.text, ink.shadow, m, Modifier.align(Alignment.TopEnd).padding(top = u * 2, end = u * 2))
        }
        badge?.let { Box(Modifier.align(Alignment.TopEnd).offset(x = u * 3, y = -u * 3)) { OptionBadge(it, m) } }
    }
}

// ---- Minimap ------------------------------------------------------------------------------------

/**
 * The region map around the player, one map pixel per GBA pixel ([mapW] x [mapH] of them), their head in the
 * middle (kept inside the map), and the place's name under it. A tap opens the whole MAP. No map for the game =
 * the name alone.
 */
@Composable
private fun HudMiniMap(
    snapshot: SnapshotView, mapW: Int, mapH: Int, m: GbaTextMetrics, modifier: Modifier = Modifier, nameLines: Int = 1,
    onClick: () -> Unit,
) {
    val u = m.u
    val gen = rememberArtGeneration()
    val model = remember(snapshot.game, gen) { activeRegionMap() }
    val region = model?.sections?.get(snapshot.regionMapSectionId)?.region?.takeIf { it in model.images.indices }
    val bitmap = regionMapBitmap(region?.let { model.images[it] })
    val labelStyle = MapLabelStyle.of(snapshot.game)
    // Black's and White's Town Map: the player's figure, on their place's point (the reader's cell, [blackTownMap]'s points).
    val black = snapshot.game.isGen5
    val head = regionMapBitmap(if (black) com.pokedaisy.app.companion.data.NdsArt.gen5Player(snapshot.game) else labelStyle.headAsset(snapshot.playerGender, snapshot.game))
    val cursor = labelStyle.cursorColor
    val small = rememberGbaTextMetrics(0.75f)
    val name = snapshot.location.mapSecName
    if ((model == null || bitmap == null) && name.isEmpty()) return
    Column(
        modifier.hudPanel(u).soundClickable(onClick = onClick).padding(u * 4 + hudEdge(u)).padding(bottom = u),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (model != null && bitmap != null) {
            val tile = (if (black) snapshot.regionMapTile else null)
                ?: PlayerMapTile.of(model, snapshot.regionMapSectionId, snapshot.x, snapshot.y, snapshot.mapWidth, snapshot.mapHeight, snapshot.mapType)
            val point = if (black && tile != null) model.pick(0, tile.first, tile.second)?.mapsec?.let { model.points[it] } else null
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            val headImage = remember(head) { head?.asImageBitmap() }
            val crop = remember(bitmap) { regionMapContentBounds(bitmap) }
            Box(Modifier.size(u * mapW, u * mapH).clip(PixelRoundedShape(u * 2))) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = u.toPx()
                    val cx = (point?.first ?: tile?.let { it.first * TILE + TILE / 2 } ?: (crop.left + crop.width / 2)).toFloat()
                    val cy = (point?.second ?: tile?.let { it.second * TILE + TILE / 2 } ?: (crop.top + crop.height / 2)).toFloat()
                    val left = (cx - mapW / 2f).coerceIn(crop.left.toFloat(), maxOf(crop.left.toFloat(), crop.right - mapW.toFloat())).toInt()
                    val top = (cy - mapH / 2f).coerceIn(crop.top.toFloat(), maxOf(crop.top.toFloat(), crop.bottom - mapH.toFloat())).toInt()
                    val w = minOf(mapW, crop.right - left)
                    val h = minOf(mapH, crop.bottom - top)
                    // A map smaller than the window sits in its middle.
                    val ox = (mapW - w) / 2f
                    val oy = (mapH - h) / 2f
                    drawImage(
                        image, srcOffset = IntOffset(left, top), srcSize = IntSize(w, h),
                        dstOffset = IntOffset((ox * s).toInt(), (oy * s).toInt()),
                        dstSize = IntSize((w * s).toInt(), (h * s).toInt()), filterQuality = FilterQuality.None,
                    )
                    if (headImage == null && tile != null) {
                        // No head for this game (Lazarus): the map's own cursor marks the tile instead.
                        translate(ox * s, oy * s) {
                            drawMapCursor(
                                tile.first * TILE - left, tile.second * TILE - top, (tile.first + 1) * TILE - left, (tile.second + 1) * TILE - top,
                                s, s, big = false, color = cursor,
                            )
                        }
                    }
                    if (headImage != null && point != null) {
                        drawImage(
                            headImage,
                            dstOffset = IntOffset(
                                ((point.first - headImage.width / 2 - left + ox) * s).toInt(),
                                ((point.second - headImage.height / 2 - top + oy) * s).toInt(),
                            ),
                            dstSize = IntSize((headImage.width * s).toInt(), (headImage.height * s).toInt()), filterQuality = FilterQuality.None,
                        )
                    } else if (headImage != null && tile != null) {
                        drawImage(
                            headImage,
                            dstOffset = IntOffset(
                                ((tile.first * TILE + TILE / 2 - HEAD / 2 - left + ox) * s).toInt(),
                                ((tile.second * TILE + TILE / 2 - HEAD / 2 - top + oy) * s).toInt(),
                            ),
                            dstSize = IntSize((HEAD * s).toInt(), (HEAD * s).toInt()), filterQuality = FilterQuality.None,
                        )
                    }
                }
                // A tap opens it bigger: the expand mark in the corner says so.
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(u * 2).size(u * 11).background(Color(0x99000000), PixelRoundedShape(u * 2)),
                    contentAlignment = Alignment.Center,
                ) { PixelIcon(PixelIcons.expand, Color.White, Modifier.size(u * 7)) }
            }
            Spacer(Modifier.height(u * 3))
        }
        if (name.isNotEmpty()) {
            Box(Modifier.width(u * mapW)) {
                GbaText(gameCase(name).uppercase().replace('É', 'é'), ink.text, ink.shadow, small, maxLines = nameLines)
            }
        }
    }
}

private const val TILE = 8
private const val HEAD = 16

// ---- Panels -------------------------------------------------------------------------------------

/**
 * A panel over the HUD: a dim behind it, where a tap closes it (BACK too). [content] gets the modifier for its own
 * window, which keeps the taps inside it; everything around that is the dim. The game keeps running.
 */
@Composable
internal fun HudPanelScrim(onClose: () -> Unit, alignment: Alignment, clear: Boolean = false, content: @Composable (window: Modifier) -> Unit) {
    // [clear]: the panel alone, no dim, and BACK and taps beside it stay the game's.
    if (!clear) CompanionBackHandler(onBack = onClose)
    // Silent: a tap on a panel's empty part does nothing, so it makes no click either.
    val keep = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    val tapSource = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().then(
            if (clear) Modifier
            else Modifier.background(Color(0x66000000)).soundClickable(interactionSource = tapSource, indication = null, onClick = onClose),
        ),
        contentAlignment = alignment,
    ) { content(keep) }
}

/** A panel's first line: its icon, title, [extra] buttons and a close X, each a button of its own. */
@Composable
private fun PanelHeading(icon: List<String>, colors: Map<Char, Color>, title: String, m: GbaTextMetrics, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    val u = m.u
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelArt(icon, colors, Modifier.size(u * 12))
        Spacer(Modifier.width(u * 4))
        GbaText(tr(title), ink.accent, ink.accentShadow, m, Modifier.weight(1f))
        extra()
        Spacer(Modifier.width(u * 4))
        HudPanelButton(null, m, icon = PixelIcons.close, onClick = onClose)
    }
}

/** The whole MAP tab (PLACES, FLY...), big, over the game: its title window as wide as the map comes out. */
@Composable
private fun HudMapPanel(snapshot: SnapshotView, m: GbaTextMetrics, fly: FlyAction?, onClose: () -> Unit) {
    val u = m.u
    val titleM = rememberGbaTextMetrics()
    // The map's own shape (its screen without the border), so the title above it lines up with it.
    val gen = rememberArtGeneration()
    val model = remember(snapshot.game, gen) { activeRegionMap() }
    val region = model?.sections?.get(snapshot.regionMapSectionId)?.region?.takeIf { it in model.images.indices } ?: 0
    val bitmap = regionMapBitmap(model?.images?.getOrNull(region))
    val aspect = remember(bitmap) { bitmap?.let { regionMapContentBounds(it) }?.let { it.width.toFloat() / it.height } ?: 1.5f }
    val glass = LocalHudLook.current.glass
    HudPanelScrim(onClose, Alignment.Center) { window ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(u * 10), contentAlignment = Alignment.Center) {
            val gap = u * 3
            // The title's height: a line and its 6u padding each way. Title and map no taller than the companion window.
            val mapH = minOf(maxHeight, OVERLAY_MAX_HEIGHT_DP.dp) - titleM.lineHeight - titleM.u * 12 - gap
            // The map at its own shape, never stretched: as tall as there's room for, or as wide.
            val mapW = minOf(maxWidth, mapH * aspect)
            Column(Modifier.width(mapW).then(window), verticalArrangement = Arrangement.spacedBy(gap)) {
                if (glass) GlassTitle(tk("MAP"), gameCase(snapshot.location.mapSecName), titleM, onClose)
                else OptionTitleWindow(tk("MAP"), titleM, trailing = gameCase(snapshot.location.mapSecName), onBack = onClose)
                // MapScreen draws its own frame: no window around it.
                Box(Modifier.width(mapW).height(mapW / aspect)) { MapScreen(snapshot, fly = fly, keepShape = true) }
            }
        }
    }
}

/** A party member's summary (the PARTY tab's), over the game; up / down step through the party. */
@Composable
private fun HudSummaryPanel(
    snapshot: SnapshotView, index: Int, onIndex: (Int) -> Unit, m: GbaTextMetrics, itemUse: ItemUse?, onClose: () -> Unit,
) {
    val u = m.u
    val learn = snapshot.pokedex?.tables
        ?.takeIf { com.pokedaisy.app.companion.data.MoveLearning.available(it, snapshot.guideTables) }
        ?.let { LearnContext(it, snapshot.guideTables, snapshot.items, true) }
    // GLASS: the summary's windows and text in the HUD's see-through look too.
    val look = when (LocalHudLook.current) {
        FieldHudLook.GLASS -> HudGlassLook
        FieldHudLook.WHITE_GLASS -> HudWhiteGlassLook
        FieldHudLook.WINDOW -> HudWindowLook
    }
    HudPanelScrim(onClose, Alignment.Center) { window ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(u * 10), contentAlignment = Alignment.Center) {
            // The companion's own proportions (the Thor's bottom screen), as tall as there's room for, up to the companion
            // window's height.
            val h = minOf(maxHeight, OVERLAY_MAX_HEIGHT_DP.dp)
            Box(Modifier.width(minOf(maxWidth, h * 1.15f)).height(h).then(window)) {
                CompositionLocalProvider(LocalOptionLook provides look) {
                    MonDetailScreen(
                        snapshot.party, index.coerceIn(0, (snapshot.party.size - 1).coerceAtLeast(0)),
                        onIndexChange = onIndex, onBack = onClose, learn = learn, itemUse = itemUse,
                    )
                }
            }
        }
    }
}

/** The GLASS look's title bar (the map's): a back arrow, [title], [trailing] right-aligned, in a see-through panel. */
@Composable
private fun GlassTitle(title: String, trailing: String, m: GbaTextMetrics, onBack: () -> Unit) {
    val u = m.u
    val c = ink
    val cell = with(LocalDensity.current) { m.fontPixel.toDp() }
    Row(
        Modifier.fillMaxWidth().hudPanel(u).padding(horizontal = u * 12, vertical = u * 6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(m.lineHeight).soundClickable(onClick = onBack), contentAlignment = Alignment.Center) {
            ShadowedPixelIcon(PixelIcons.arrowBack, c.text, c.shadow, m, cell = cell)
        }
        Spacer(Modifier.width(u * 6))
        GbaText(tr(title), c.text, c.shadow, m)
        Box(Modifier.weight(1f).padding(start = u * 8), contentAlignment = Alignment.CenterEnd) { GbaText(trailing, c.accent, c.accentShadow, m) }
    }
}

/** The summary's windows and text in the GLASS look ([LocalOptionLook] over the HUD's summary). */
private object HudGlassLook : OptionLook {
    override val label = GLASS_INK.text
    override val labelShadow = GLASS_INK.shadow
    override val value = GLASS_INK.accent
    override val valueShadow = GLASS_INK.accentShadow
    override val muted = GLASS_INK.muted
    override val mutedShadow = GLASS_INK.shadow
    override val titleFill = GLASS_FILL
    override val listFill = GLASS_INK.cell
    override val listLayers = listOf(GLASS_EDGE to 1)
    override val frameDark = Color(0xFF0C0F15)
    override val separatorDark = Color(0x66000000)
    override val separatorLight = Color(0x33FFFFFF)
    override val stripIdle = Color(0x40FFFFFF)
    override val stripIdleFrame = Color(0x66000000)
    override val stripLit = Color(0xB3FFFFFF)
    override val stripLitFrame = GLASS_INK.accent
    override val windowPadSides = SUMMARY_PAD_SIDES
}

/** The summary in the WHITE GLASS look: [HudGlassLook] the other way round. */
private object HudWhiteGlassLook : OptionLook {
    override val label = WHITE_INK.text
    override val labelShadow = WHITE_INK.shadow
    override val value = WHITE_INK.accent
    override val valueShadow = WHITE_INK.accentShadow
    override val muted = WHITE_INK.muted
    override val mutedShadow = WHITE_INK.shadow
    override val titleFill = WHITE_FILL
    override val listFill = WHITE_INK.cell
    override val listLayers = listOf(WHITE_EDGE to 1)
    override val frameDark = Color(0xFF3A404C)
    override val separatorDark = Color(0x40000000)
    override val separatorLight = Color(0x80FFFFFF)
    // Nearly solid: a see-through cell showed the buttons' black outline under it.
    override val stripIdle = Color(0xE6E4E7EE)
    override val stripIdleFrame = Color(0xFF9AA2B2)
    override val stripLit = Color(0xE6FFFFFF)
    override val stripLitFrame = WHITE_INK.accent
    override val windowPadSides = SUMMARY_PAD_SIDES
}

/** The summary in the WINDOW look: the app's own colours, with the HUD's room at the sides. */
private object HudWindowLook : OptionLook by OptionLook.Live {
    override val windowPadSides = SUMMARY_PAD_SIDES
}

/** The HUD's summary: more room inside its window at the left and right than the companion's tab gives it. */
private const val SUMMARY_PAD_SIDES = 6

/** Where BAG and WILD open: over the buttons (bottom right, CORNERS 2's top right), or above the DOCK's bar. */
@Composable
private fun panelPlace(dock: Boolean, top: Boolean, u: Dp, inset: PaddingValues): Pair<Alignment, Modifier> = when {
    // The compact HUD's buttons sit bottom left: the panel opens over them there.
    FieldHudFit.tight -> Alignment.BottomStart to Modifier.padding(inset).padding(u * 6)
    dock -> Alignment.BottomCenter to Modifier.padding(inset).padding(start = u * 6, end = u * 6, top = u * 6, bottom = u * 60)
    top -> Alignment.TopEnd to Modifier.padding(inset).padding(u * 6)
    else -> Alignment.BottomEnd to Modifier.padding(inset).padding(u * 6)
}

/**
 * The compact BAG: pocket tabs, the items as a grid of icons with counts (a list of names where the game has no item
 * pictures: the Game Boy games), the picked one's name and text, and USE
 * where the game lets the app use it (FieldItems): straight away, or on the Pokémon picked from the party under it.
 * Items used on a move (PP) are left to the BAG tab.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudBagPanel(
    snapshot: SnapshotView, m: GbaTextMetrics, itemUse: ItemUse?, dock: Boolean, top: Boolean, inset: PaddingValues, startPocket: Int, onClose: () -> Unit,
) {
    val u = m.u
    val small = rememberGbaTextMetrics(0.75f)
    val pockets = listOf(POCKET_ITEMS, POCKET_POKE_BALLS, POCKET_BERRIES, POCKET_TM_HM, POCKET_KEY_ITEMS)
        .filter { pk -> snapshot.items.any { basePocket(it.pocket) == pk } }
    var pocket by remember { mutableStateOf(startPocket.takeIf { it in pockets } ?: pockets.firstOrNull() ?: POCKET_ITEMS) }
    val items = snapshot.items.filter { basePocket(it.pocket) == pocket }
    // The bag as a whole, so every pocket looks the same: no item has a picture.
    val noItemPictures = snapshot.items.all { it.iconAsset == null }
    var pickedId by remember(pocket) { mutableStateOf(items.firstOrNull()?.itemId) }
    val picked = items.firstOrNull { it.itemId == pickedId }
    // What the last USE did, for the item it was done with; the party pick a USE on a Pokémon opened.
    var message by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var targets by remember(pickedId) { mutableStateOf<List<PartyTarget>?>(null) }
    val (align, place) = panelPlace(dock, top, u, inset)
    val c = ink
    HudPanelScrim(onClose, align) { window ->
        Column(place.width(u * 300).hudPanel(u).then(window).padding(u * 7), verticalArrangement = Arrangement.spacedBy(u * 4)) {
            PanelHeading(BAG_ICON, BAG_COLORS, companionTabLabel("ITEMS"), m, onClose)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(u * 3), verticalArrangement = Arrangement.spacedBy(u * 3)) {
                pockets.forEach { pk -> HudPanelButton(pocketLabel(pk), m, selected = pk == pocket) { pocket = pk } }
            }
            // A whole screen pixel per GBA pixel, like the party icons (the HUD's corners use 2/3 of that).
            val icon = with(LocalDensity.current) { (24 * hudIconScale(m)).toDp() }
            // Two and a half rows of items, whatever the pocket holds: the half row says the rest scrolls.
            val listHeight = u * (46 * 2 + 2 * 2 + 23)
            if (noItemPictures) {
                // A game with no item pictures (the Game Boy games): the pocket as a list, as their own PACK shows it -
                // each item's name and count, the picked one's row lit.
                Column(Modifier.height(listHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u)) {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (item.itemId == pickedId) c.cellOn else c.cell, PixelRoundedShape(u * 3))
                                .hudPicked(u, item.itemId == pickedId)
                                .soundClickable { pickedId = item.itemId }
                                .padding(horizontal = u * 5, vertical = u * 3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GbaText(bagItemName(item.itemId), c.text, c.shadow, m, Modifier.weight(1f))
                            if (item.pocket != POCKET_KEY_ITEMS && com.pokedaisy.app.companion.data.bagHasCounts) {
                                GbaText("x${item.quantity}", c.text, c.shadow, m)
                            }
                        }
                    }
                }
            } else Column(Modifier.height(listHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u * 2)) {
                items.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 2)) {
                        row.forEach { item ->
                            Box(
                                Modifier.size(u * 46)
                                    .background(if (item.itemId == pickedId) c.cellOn else c.cell, PixelRoundedShape(u * 3))
                                    .hudPicked(u, item.itemId == pickedId)
                                    .soundClickable { pickedId = item.itemId },
                            ) {
                                HudItemIcon(item.iconAsset, icon, Modifier.align(Alignment.Center).offset(y = -u * 2))
                                if (item.pocket != POCKET_KEY_ITEMS && com.pokedaisy.app.companion.data.bagHasCounts) {
                                    GbaText("x${item.quantity}", c.text, c.shadow, small, Modifier.align(Alignment.BottomEnd).padding(end = u * 2, bottom = u))
                                }
                            }
                        }
                    }
                }
            }
            // The same height whatever the pocket or the item: the name and USE, then two lines for its text, a
            // USE's result or the party pick.
            val use = picked?.let { item -> itemUse?.takeIf { it.canUse(item.itemId) && !it.picksMove(item.itemId) } }
            val iconSlot = hudIconSize(m)
            Column(Modifier.fillMaxWidth().background(c.cell, PixelRoundedShape(u * 3)).padding(u * 4), verticalArrangement = Arrangement.spacedBy(u * 2)) {
                Row(Modifier.fillMaxWidth().height(maxOf(m.rowHeight, m.lineHeight + u * 8)), verticalAlignment = Alignment.CenterVertically) {
                    picked?.let { item ->
                        GbaText(bagItemName(item.itemId), c.accent, c.accentShadow, m, Modifier.weight(1f))
                        if (use != null) HudPanelButton(tr("USE"), m, onClick = {
                            if (use.picksMon(item.itemId)) {
                                use.targets(item.itemId, give = false) { t ->
                                    if (t.result == ItemUseResult.USED) targets = t.targets
                                    else message = item.itemId to itemUseMessage(ItemUseOutcome(t.result))
                                }
                            } else use.use(item.itemId) { o -> message = item.itemId to itemUseMessage(o) }
                        })
                    }
                }
                Box(Modifier.fillMaxWidth().height(maxOf(small.lineHeight * 2, small.lineHeight + u * 2 + iconSlot))) {
                    val item = picked
                    val t = targets
                    when {
                        item == null -> Unit
                        t != null -> Column(verticalArrangement = Arrangement.spacedBy(u * 2)) {
                            GbaText(tr("Use on which POKéMON?"), c.text, c.shadow, small)
                            Row(horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                                t.forEach { target ->
                                    val mon = snapshot.party.getOrNull(target.slot) ?: return@forEach
                                    val ok = target.verdict == SlotVerdict.OK
                                    Box(
                                        Modifier.alpha(if (ok) 1f else 0.35f).soundClickable(enabled = ok) {
                                            targets = null
                                            use?.useOn(item.itemId, target, 0) { o ->
                                                message = item.itemId to itemUseMessage(o, mon = mon.name, item = bagItemName(item.itemId))
                                            }
                                        },
                                    ) { HudMonIcon(mon, iconSlot, u) }
                                }
                            }
                        }
                        else -> {
                            val note = message?.takeIf { it.first == item.itemId }?.second
                            if (note != null) GbaText(note, c.text, c.shadow, small, maxLines = 2)
                            else if (item.description.isNotEmpty()) GbaText(item.description, c.muted, c.shadow, small, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** WILD: this map's wild Pokémon by method - icon, name (??? until seen), levels, how often, a ball once caught. */
@Composable
private fun HudWildPanel(snapshot: SnapshotView, wild: List<HudWild>, m: GbaTextMetrics, dock: Boolean, top: Boolean, inset: PaddingValues, kept: Boolean, onKeep: () -> Unit, onClose: () -> Unit) {
    val u = m.u
    val small = rememberGbaTextMetrics(0.75f)
    val icon = hudIconSize(m)
    val (align, place) = panelPlace(dock, top, u, inset)
    val c = ink
    HudPanelScrim(onClose, align, clear = kept) { window ->
        Column(place.width(u * 200).hudPanel(u).then(window).padding(u * 6), verticalArrangement = Arrangement.spacedBy(u * 2)) {
            PanelHeading(GRASS_ICON, GRASS_COLORS, tk("WILD POKéMON"), m, onClose) {
                // On: the party screen's orange, like a picked pocket.
                HudPanelButton(tr("KEEP OPEN"), m, selected = kept, onClick = onKeep)
            }
            GbaText(gameCase(snapshot.location.mapSecName).uppercase().replace('É', 'é'), c.muted, c.shadow, small)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u)) {
                if (wild.isEmpty()) GbaText(tr("No wild POKéMON on this map."), c.text, c.shadow, small)
                wild.groupBy { it.method }.forEach { (method, list) ->
                    GbaText(tr(method), c.accent, c.accentShadow, small, Modifier.padding(top = u * 2))
                    list.forEach { w ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            // An unseen Pokémon stays hidden, like GUIDE > HERE's.
                            Box(Modifier.size(icon), contentAlignment = Alignment.Center) {
                                if (w.seen) HudSpecies(spriteAsset("pokemon", w.species), icon) else GbaText("?", c.muted, c.shadow, m)
                            }
                            Spacer(Modifier.width(u * 2))
                            GbaText(if (w.seen) speciesName(w.species) else "???", c.text, c.shadow, m, Modifier.weight(1f))
                            if (w.caught) {
                                PixelArt(PixelIcons.pokeBall, CAUGHT_BALL, Modifier.size(u * 9))
                                Spacer(Modifier.width(u * 4))
                            }
                            val lv = if (w.minLevel == w.maxLevel) "${w.minLevel}" else "${w.minLevel}-${w.maxLevel}"
                            GbaText(tr("Lv{0}", lv), c.muted, c.shadow, small, Modifier.width(u * 34))
                            Box(Modifier.width(u * 22), contentAlignment = Alignment.CenterEnd) {
                                GbaText("${w.percent}%", c.accent, c.accentShadow, small)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pocketLabel(pocket: Int) = when (pocket) {
    POCKET_ITEMS -> tr("ITEMS")
    POCKET_POKE_BALLS -> tr("POKé BALLS")
    POCKET_BERRIES -> tr("BERRIES")
    POCKET_TM_HM -> tr("TMs & HMs")
    else -> tr("KEY ITEMS")
}

// ---- Pixel art ----------------------------------------------------------------------------------

private val CAUGHT_BALL = mapOf('K' to Color(0xFF202020), 'R' to Color(0xFFE83028), 'W' to Color.White, 'H' to Color(0xFFF8A8A0))

/** A satchel: K outline, B leather, L its light edge, Y the buckle. */
private val BAG_ICON = listOf(
    ".....KKKKKK.....",
    "....KK....KK....",
    "....K......K....",
    "..KKKKKKKKKKKK..",
    ".KBLLLLLLLLLLBK.",
    ".KBBBBBBBBBBBBK.",
    ".KBBBBBBBBBBBBK.",
    ".KKKKKKYYKKKKKK.",
    ".KBBBBKYYKBBBBK.",
    ".KBBBBBKKBBBBBK.",
    ".KBBBBBBBBBBBBK.",
    ".KBBBBBBBBBBBBK.",
    ".KLBBBBBBBBBBLK.",
    ".KLLBBBBBBBBLLK.",
    "..KKKKKKKKKKKK..",
    "................",
)
private val BAG_COLORS = mapOf('K' to Color(0xFF3A2414), 'B' to Color(0xFFC0783A), 'L' to Color(0xFFE8A868), 'Y' to Color(0xFFF8D030))

/** A region map: K frame, G land, B water, Y a road, R a pin with a W glint. */
private val MAP_ICON = listOf(
    "................",
    ".KKKKKKKKKKKKKK.",
    ".KBBBBBGGGGGGGK.",
    ".KBBBBGGGGRRGGK.",
    ".KBBBGGGGRWRRGK.",
    ".KBBGGGGGRRRRGK.",
    ".KBGGGGGGGRRGGK.",
    ".KGGGGYYYYYRGGK.",
    ".KGGGYGGGGGGGGK.",
    ".KGGYGGGGGGGGGK.",
    ".KGGYGGGGGBBBBK.",
    ".KYYGGGGGBBBBBK.",
    ".KGGGGGGBBBBBBK.",
    ".KGGGGGBBBBBBBK.",
    ".KKKKKKKKKKKKKK.",
    "................",
)
private val MAP_COLORS = mapOf(
    'K' to Color(0xFF202838), 'G' to Color(0xFF58B048), 'B' to Color(0xFF4890E0), 'Y' to Color(0xFFF0D060),
    'R' to Color(0xFFE83028), 'W' to Color.White,
)

/** A PC: K outline, L its light grey case, D the darker shade, S the blue screen with an H glint, G the power light. */
internal val PC_ICON = listOf(
    "................",
    ".KKKKKKKKKKKKKK.",
    ".KLLLLLLLLLLLLK.",
    ".KLKKKKKKKKKKLK.",
    ".KLKSSSSSSHHKLK.",
    ".KLKSSSSSHHSKLK.",
    ".KLKSSSSHHSSKLK.",
    ".KLKSSSSSSSSKLK.",
    ".KLKKKKKKKKKKLK.",
    ".KLLLLLLLLLLGLK.",
    ".KDDDDDDDDDDDDK.",
    ".KKKKKKKKKKKKKK.",
    "......KDDK......",
    "....KKKKKKKK....",
    "...KLLLLLLLLK...",
    "...KKKKKKKKKK...",
)
internal val PC_COLORS = mapOf(
    'K' to Color(0xFF202838), 'L' to Color(0xFFD8D8E0), 'D' to Color(0xFF9098A8),
    'S' to Color(0xFF3070C8), 'H' to Color(0xFF90C8F8), 'G' to Color(0xFF40D040),
)

/** The side panel's open arrow (PixelIcons.panelOpen) a size down, its strokes 2 thick: COMPANION. */
internal val COMPANION_ICON = listOf(
    "......11",
    ".....11.",
    "....11..",
    "...11...",
    "..11....",
    ".11.....",
    "11......",
    ".11.....",
    "..11....",
    "...11...",
    "....11..",
    ".....11.",
    "......11",
)

/** A stylus on a touch screen: K outline, S the screen with an H glint, P the pen, T its tip. */
internal val TOUCH_ICON = listOf(
    "................",
    "............KK..",
    "...........KPPK.",
    "..........KPPK..",
    ".KKKKKKKKKPPKK..",
    ".KSSSSSSSKPPKSK.",
    ".KSHHSSSKPPKSSK.",
    ".KSHSSSSKTKSSSK.",
    ".KSSSSSSSKSSSSK.",
    ".KSSSSSSSSSSSSK.",
    ".KSSSSSSSSSSSSK.",
    ".KSSSSSSSSSSSSK.",
    ".KKKKKKKKKKKKKK.",
    "................",
    "................",
    "................",
)
internal val TOUCH_COLORS = mapOf(
    'K' to Color(0xFF202838), 'S' to Color(0xFF3070C8), 'H' to Color(0xFF90C8F8), 'P' to Color(0xFFD8D8E0), 'T' to Color(0xFFE85040),
)

/** A tuft of tall grass: K outline, G dark blades, L light ones. */
internal val GRASS_ICON = listOf(
    "................",
    "...K......K.....",
    "..KGK....KGK..K.",
    "..KGK...KGGK.KGK",
    ".KGLGK..KGLGKKGK",
    ".KGLGK.KGLLGKGLK",
    "KGLLGKKGLLGKGLLK",
    "KGLLGGKGLLGGGLLK",
    "KGLLLGGGLLLGGLLK",
    "KGLLLGGGLLLLGLLK",
    "KGGLLLGGLLLLGLGK",
    "KGGLLLLGLLLLLLGK",
    "KGGGLLLLLLLLLGGK",
    "KGGGGGLLLLLGGGGK",
    "KKKKKKKKKKKKKKKK",
    "................",
)
internal val GRASS_COLORS = mapOf('K' to Color(0xFF183818), 'G' to Color(0xFF389838), 'L' to Color(0xFF78D058))
