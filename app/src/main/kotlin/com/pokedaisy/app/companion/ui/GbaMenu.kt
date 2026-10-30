package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.ui.theme.isDaisyTheme
import com.pokedaisy.app.companion.ui.theme.DarkMode
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.pokedaisy.app.companion.data.isHgss
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.isGameBoyGame
import com.pokedaisy.app.companion.data.isRedBlue
import com.pokedaisy.app.companion.data.isPureRgb
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import com.pokedaisy.app.companion.ui.theme.rememberGameBackground
import kotlin.math.max

/**
 * FireRed's OPTION screen, sampled 1:1 from the game (headless mGBA): a white
 * title window, a grey list window whose current row is white, grey labels
 * and red values. Fixed for every game and theme. The building blocks below
 * (title window, list window, `LABEL  VALUE` rows, buttons, overlays) are the
 * app's shared menu look - the companion's SETTINGS and STATES tabs and the
 * top-screen Library/Settings are all built from them.
 */
object OptionColors {
    // A Game Boy game gets its own windows instead (Gen 1: black text on white, no
    // shadows, the text box's double line - sampled headless from Yellow's menus).
    // Read through activeGame like the rest of the per-game look (CompanionScreen is
    // keyed on the game, so a switch redraws) - only while a game is open ([inGame]):
    // the top-screen Library / Settings keep the app's own look after Yellow closes.
    // Crystal's (Gen 2) menus are the same black-on-white text boxes.
    private val gen1 get() = inGame && activeGame.isGameBoyGame

    /** A game's screens are up (PokeDaisyActivity resumed); Compose state, so the look follows at once. */
    var inGame: Boolean
        get() = inGameState.value
        set(v) { inGameState.value = v }
    private val inGameState = androidx.compose.runtime.mutableStateOf(false)

    /** DARK MODE ([DarkMode]): every colour below has its dark twin, Gen 1's included. */
    val dark get() = DarkMode.on

    // Gen 1: black on white; dark mode turns its windows round (white on black), same lines.
    // Red / Blue run as a Super Game Boy: their white and black are its palettes' (sampled headless).
    private val G1_INK get() = if (dark) Color(0xFFE8E8E8) else gbInk(activeGame)
    private val G1_PAPER get() = if (dark) Color(0xFF181818) else gbPaper(activeGame)

    /** A Game Boy game's own black and white in light mode: Yellow's and Gen 2's GBC ones, Red / Blue's SGB ones,
     * PureRed / PureBlue's pure black (their Color palettes, sampled headless). */
    fun gbInk(game: GameKind?): Color = if (game.isRedBlue) Color(0xFF181010) else if (game.isPureRgb) Color.Black else Color(0xFF181818)
    fun gbPaper(game: GameKind?): Color = if (game.isRedBlue) Color(0xFFFFEFFF) else Color(0xFFFFFFFF)
    private val G1_FRAME get() = listOf(G1_PAPER to 2, G1_INK to 1, G1_PAPER to 1, G1_INK to 1, G1_PAPER to 1)

    // The PokéDaisy theme's light backdrop on the top screen (Library, Settings, setup): text drawn straight
    // on it takes the windows' dark grey instead of white. Never inside a game (the game's own backdrops),
    // nor in dark mode (its backdrop is dark).
    private val daisy get() = !inGame && isDaisyTheme && !dark

    /** [light] or, in dark mode, [dark] (both FireRed OPTION-style; Gen 1 has its own). */
    private fun c(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val hintBar get() = if (gen1) c(0xFF181818, 0xFF303030) else c(0xFF007BC6, 0xFF0B5C94)
    val hintText get() = Color(0xFFFFFFFF)
    val hintShadow get() = if (gen1) Color.Transparent else c(0xFF636363, 0xFF06263D)
    // Dark windows keep the light ones' build - a dark outer line, a lighter inner one - in slate.
    val titleLayers get() = if (gen1) G1_FRAME else if (dark) listOf(Color(0xFF0B0D11) to 2, Color(0xFF465061) to 1)
        else listOf(Color(0xFF63737B) to 2, Color(0xFFCED6D6) to 1)
    val titleFill get() = if (gen1) G1_PAPER else c(0xFFFFFFFF, 0xFF262B34)
    val titleText get() = if (gen1) G1_INK else c(0xFF636363, 0xFFE3E6EC)
    // The game's shadow sits between the text and the window (lighter than grey text on white): mirrored in dark.
    val titleShadow get() = if (gen1) Color.Transparent else c(0xFFD6D6CE, 0xFF3F4652)
    val listLayers get() = if (gen1) G1_FRAME else if (dark) listOf(
        Color(0xFF050608) to 1, Color(0xFF6F6FB8) to 1, Color(0xFF3B3653) to 2,
        Color(0xFF242932) to 1, Color(0xFF2D333E) to 2,
    ) else listOf(
        Color(0xFF293131) to 1, Color(0xFF8C8CCE) to 1, Color(0xFF736B84) to 2,
        Color(0xFFDED6DE) to 1, Color(0xFFFFFFFF) to 2,
    )
    val listFill get() = if (gen1) G1_PAPER else c(if (usesKantoMenu) 0xFFDEDEDE else 0xFFE0DFDF, 0xFF1D2128)
    /** The cursor row (Gen 1 marks it with its ▶; here a light grey band, a lighter slate in dark). */
    val rowSelected get() = if (gen1) (if (activeGame.isRedBlue) c(0xFFE6D6E6, 0xFF3A3A3A) else c(0xFFDCDCDC, 0xFF3A3A3A))
        else c(0xFFFFFFFF, 0xFF353C49)
    // FR/LG's standard menu text palette, expanded from BGR555 like RomArt's pixels.
    val label get() = if (gen1) G1_INK else c(if (usesKantoMenu) 0xFF636363 else 0xFF575656, 0xFFCDD1D9)
    val labelShadow get() = if (gen1) Color.Transparent else c(if (usesKantoMenu) 0xFFD6D6CE else 0xFFBCBBB4, 0xFF3A404B)
    val value get() = if (gen1) G1_INK else c(if (usesKantoMenu) 0xFFE70808 else 0xFFCB0707, 0xFFFF6A5C)
    val valueShadow get() = if (gen1) Color.Transparent else c(if (usesKantoMenu) 0xFFFFBD73 else 0xFFE0A564, 0xFF6E2A22)
    /** Something done in the game went through: FireRed's stdpal_0 green (TEXT_COLOR_GREEN). */
    val success get() = if (gen1) G1_INK else c(0xFF209C08, 0xFF6BD65A)
    /** The party menu's field moves (FLY, CUT...): TEXT_COLOR_BLUE on LIGHT_BLUE, FireRed's stdpal_0. */
    val fieldMove get() = if (gen1) G1_INK else c(0xFF3152CD, 0xFF86A6FF)
    val fieldMoveShadow get() = if (gen1) Color.Transparent else c(0xFFA4C5F6, 0xFF26315E)
    /** A field-move button's frame: [titleLayers]' build in those blues. */
    val fieldMoveLayers get() = if (gen1) G1_FRAME else if (dark) listOf(Color(0xFF0B0D11) to 2, Color(0xFF4A62C8) to 1)
        else listOf(Color(0xFF3152CD) to 2, Color(0xFFA4C5F6) to 1)
    /** A featured settings group's band (MAKEOVER): the field-move blue's frame over a pale blue, so it stands out from
     * the red group titles. Gen 1: its black band, like the other titles there. */
    val featureFill get() = if (gen1) G1_INK else c(0xFFDCE8FC, 0xFF1C2546)
    val featureText get() = if (gen1) G1_PAPER else fieldMove
    val featureShadow get() = if (gen1) Color.Transparent else fieldMoveShadow
    /** Secondary text (paths, timestamps, hints) - lighter than [label] (dimmer in dark). */
    val muted get() = if (gen1) c(0xFF787878, 0xFF9A9A9A) else c(0xFF8C8C94, 0xFF8F95A3)
    val mutedShadow get() = if (gen1) Color.Transparent else c(0xFFD6D6D6, 0xFF2C313A)
    /** The dark outer line of [listLayers], for frames drawn around images. */
    val frameDark get() = if (gen1) c(0xFF181818, 0xFF000000) else c(0xFF293131, 0xFF050608)
    val frameLight get() = if (gen1) G1_PAPER else c(0xFF8C8CCE, 0xFF6F6FB8)
    /** Text straight on a game backdrop: white with the hint bar's grey shadow (black on Gen 1's white,
     * the windows' grey on PokéDaisy's light backdrop). Dark mode's backdrops are dimmed: white. */
    val onBackdrop get() = if (gen1) G1_INK else if (daisy) label else Color(0xFFFFFFFF)
    val onBackdropShadow get() = if (gen1) Color.Transparent else if (daisy) labelShadow else c(0xFF404850, 0xFF000000)
    /** A tab chip's frame: the title window's, or for Gen 1 just its double line (black, white, black) - its
     * full text-box frame is six pixels a side, which left a narrow chip no room for CHEEVOS. */
    val chipLayers get() = if (gen1) listOf(G1_INK to 1, G1_PAPER to 1, G1_INK to 1) else titleLayers
    /** The tab bar's chips: the open one white over grey idle ones (Gen 1: the cursor row's grey over white;
     * dark: the cursor row's slate over the list's). */
    val tabSelectedFill get() = if (gen1) rowSelected else if (dark) rowSelected else titleFill
    val tabIdleFill get() = if (gen1) G1_PAPER else listFill
    /** Settings group titles: Gen 1's value colour is the rows' black, so its titles get a black band (white text). */
    val groupTitleFill: Color? get() = if (gen1) G1_INK else null
    val groupTitleText get() = if (gen1) G1_PAPER else value
    val groupTitleShadow get() = if (gen1) Color.Transparent else valueShadow
    /** The dashed line between list rows (the bag list's 6/2 dash). */
    val divider get() = if (gen1) c(0xFFB0B0B0, 0xFF505050) else c(0xFFC6C5C5, 0xFF353B46)
    /** Gen 1's pictures are ink on the Game Boy's paper (the paper left clear): in dark mode they get their
     * white back, a card behind the sprite, or their outline vanishes into the dark. Null = no card. */
    val spritePaper: Color? get() = if (gen1 && dark) Color(0xFFFFFFFF) else null
    /** Text on an [OptionBadge] (the value colour's pill): white, or Gen 1's paper (its value is the ink). */
    val badgeText get() = if (gen1) G1_PAPER else Color(0xFFFFFFFF)
    /** A NEW tag's fill (the library's just-added games): a party-menu HP green, softer at night. */
    val newBadge get() = if (gen1) G1_INK else c(0xFF2F9E44, 0xFF3FAF57)
    /** A favorite game's star (the library): a Poké Ball gold. */
    val favorite get() = c(0xFFE0A010, 0xFFF2C24A)
    val newBadgeShadow get() = if (gen1) Color.Transparent else c(0x99135C24, 0x99103A1A)
    /** The dark box behind a picture that isn't there yet (covers, badges, state thumbnails); darker
     * still in dark mode, so it stays a hole in the window rather than matching it. */
    val imageWell get() = c(0xFF20242C, 0xFF0C0E12)
    /** Over everything a dark mode backdrop draws (a game's party art): how much black. 0 in light mode. */
    val backdropScrim get() = if (dark) 0.55f else 0f

    // The docs reader (DocsScreen.kt): a page of reading text in Atkinson Hyperlegible, dark on white
    // (light on slate in dark mode; Gen 1's ink on its paper).
    val readingPaper get() = if (gen1) G1_PAPER else c(0xFFFFFFFF, 0xFF262B34)
    val readingText get() = if (gen1) G1_INK else c(0xFF26262E, 0xFFE3E6EC)
    val readingMuted get() = if (gen1) c(0xFF505050, 0xFFB0B0B0) else c(0xFF5E5D6B, 0xFFA9AFBC)
    val readingLink get() = if (gen1) G1_INK else c(0xFF2F5FD0, 0xFF8AB4FF)
    /** Behind a `code` span: a button or setting name, set in the pixel font. */
    val readingCode get() = if (gen1) c(0xFFE4E4E4, 0xFF303030) else c(0xFFF0EFF5, 0xFF353C49)
    /** A search hit's matched words. */
    val readingMark get() = c(0xFFFFE58A, 0xFF6B5A1E)
    /** A callout's bar and tint: [kind] is tip / note / warning. */
    fun noteBar(kind: String) = when (kind) {
        "tip" -> c(0xFF2C9A44, 0xFF4CC266)
        "warning" -> c(0xFFE83028, 0xFFFF6A5C)
        else -> c(0xFFF8D030, 0xFFE0B830)
    }
    fun noteFill(kind: String) = if (gen1) G1_PAPER else when (kind) {
        "tip" -> c(0xFFEFFAF1, 0xFF1F3326)
        "warning" -> c(0xFFFFF1EF, 0xFF3A2422)
        else -> c(0xFFFFFAF0, 0xFF37321F)
    }
}

/**
 * The OPTION look's colours for one part of the UI, read through [optionLook]: [Live] (OptionColors, the default,
 * dark mode included) unless a screen lays another over it with [LocalOptionLook] - the FIELD HUD's GLASS panels.
 * Only the summary screens (MonDetailScreen and its sections) and [OptionListWindow] read it so far.
 */
interface OptionLook {
    val label: Color
    val labelShadow: Color
    val value: Color
    val valueShadow: Color
    val muted: Color
    val mutedShadow: Color
    val titleFill: Color
    val listFill: Color
    val listLayers: List<Pair<Color, Int>>
    /** A bar's dark outline (the summary's HP / EXP / IV bars). */
    val frameDark: Color
    /** The summary's [Separator]: a dark line over a light one. */
    val separatorDark: Color
    val separatorLight: Color
    /** The summary's party strip: the squares, and the shown Pokémon's. */
    val stripIdle: Color
    val stripIdleFrame: Color
    val stripLit: Color
    val stripLitFrame: Color
    /** More room inside [OptionListWindow] at its left and right, in GBA pixels (the HUD's summary). */
    val windowPadSides: Int get() = 0

    /** [OptionColors] itself, read when used (so dark mode follows at once). */
    object Live : OptionLook {
        override val label get() = OptionColors.label
        override val labelShadow get() = OptionColors.labelShadow
        override val value get() = OptionColors.value
        override val valueShadow get() = OptionColors.valueShadow
        override val muted get() = OptionColors.muted
        override val mutedShadow get() = OptionColors.mutedShadow
        override val titleFill get() = OptionColors.titleFill
        override val listFill get() = OptionColors.listFill
        override val listLayers get() = OptionColors.listLayers
        override val frameDark get() = OptionColors.frameDark
        override val separatorDark get() = if (OptionColors.dark) Color(0xFF0E1116) else Color(0xFFB8B8C0)
        override val separatorLight get() = if (OptionColors.dark) Color(0xFF3A414D) else Color(0xFFE8E8EC)
        // A slate blue, the lit one white (dark mode: deeper, the lit one the cursor row's).
        override val stripIdle get() = if (OptionColors.dark) Color(0xFF2E3A52) else Color(0xFFA8B4C8)
        override val stripIdleFrame get() = if (OptionColors.dark) Color(0xFF151B27) else Color(0xFF68748C)
        override val stripLit get() = if (OptionColors.dark) Color(0xFF4A5468) else Color(0xFFF8F8F8)
        override val stripLitFrame get() = if (OptionColors.dark) Color(0xFF6F7A90) else Color(0xFFC0C8D8)
    }
}

val LocalOptionLook = androidx.compose.runtime.staticCompositionLocalOf<OptionLook> { OptionLook.Live }

/** The [OptionLook] here. */
val optionLook: OptionLook
    @Composable get() = LocalOptionLook.current

/** A dashed row divider along the bottom edge, from [start] px in - the bag
 * list's 6-on/2-off dash, 2 GBA px thick. [u] is one GBA pixel in px. */
fun DrawScope.drawRowDivider(color: Color, u: Float, start: Float) {
    val y = size.height - u
    drawLine(
        color, Offset(start, y), Offset(size.width - start, y), strokeWidth = 2 * u,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * u, 2 * u)),
    )
}

/**
 * Nested rounded-rect *strokes*, outermost first - [drawLayeredBox]'s frame
 * without the fill, for framing something that draws its own content (a map,
 * a screenshot).
 */
fun DrawScope.drawLayeredFrame(layers: List<Pair<Color, Float>>, radius: Float) {
    var inset = 0f
    var r = radius
    for ((color, width) in layers) {
        drawPixelRoundFrame(
            color, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
            r.coerceAtLeast(0f), width,
        )
        inset += width
        r -= width
    }
}

/**
 * The white title window. [onBack] adds a back arrow before the title;
 * [trailing] is right-aligned extra text (e.g. the game's name) and
 * [actions] right-aligned buttons after it.
 */
@Composable
fun OptionTitleWindow(
    title: String,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onBack: (() -> Unit)? = null,
    /** Drawn before the title (after the back arrow): the Library's logo. */
    leading: (@Composable () -> Unit)? = null,
    /** Lines the title may wrap to before it's cut short (a prompt on a narrow screen). */
    titleMaxLines: Int = 1,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val u = m.u
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
            .padding(horizontal = u * 12, vertical = u * 6),
    ) {
        leading?.let { it(); Spacer(Modifier.width(u * 6)) }
        if (onBack != null) {
            CompanionBackHandler(onBack = onBack)
            // Not a D-pad stop: B / BACK is the same thing, and the arrow has no focused look.
            Box(Modifier.focusProperties { canFocus = false }.soundClickable(onClick = onBack).padding(end = u * 6)) {
                // A pixel arrow, a font pixel per cell like the title beside it.
                val cell = with(androidx.compose.ui.platform.LocalDensity.current) { m.fontPixel.toDp() }
                Box(Modifier.size(m.lineHeight).semantics { contentDescription = tr("Back") }, contentAlignment = Alignment.Center) {
                    ShadowedPixelIcon(PixelIcons.arrowBack, OptionColors.titleText, OptionColors.titleShadow, m, cell = cell)
                }
            }
        }
        if (trailing == null) {
            GbaText(tr(title), OptionColors.titleText, OptionColors.titleShadow, m, maxLines = titleMaxLines, modifier = Modifier.weight(1f))
        } else {
            // The title whole; the trailing (the game's name) right-aligned after a gap, and the one
            // that shortens when both don't fit (a wide game font: "OPTI…" read badly).
            GbaText(tr(title), OptionColors.titleText, OptionColors.titleShadow, m)
            Box(Modifier.weight(1f).padding(start = u * 8), contentAlignment = Alignment.CenterEnd) {
                GbaText(trailing, OptionColors.titleText, OptionColors.titleShadow, m)
            }
        }
        actions()
    }
}

/** The grey list window: its multi-line frame around [content]. */
@Composable
fun OptionListWindow(
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    fill: Color = optionLook.listFill,
    content: @Composable () -> Unit,
) {
    val u = m.u
    val look = optionLook
    val layers = look.listLayers
    val frame = layers.sumOf { it.second }
    Box(
        modifier = modifier
            .drawBehind { drawLayeredBox(layers.inPx(u.toPx()), fill, radius = 2 * u.toPx()) }
            .padding(horizontal = u * (frame + 2 + look.windowPadSides), vertical = u * (frame + 2)),
    ) { content() }
}

/**
 * One `LABEL      VALUE` row, the value column starting ~58% across like the
 * game's. Selected (or D-pad-focused) rows turn white, the game's cursor.
 * [value] null = a plain command row; [valueColor] overrides the red.
 */
@Composable
fun OptionLine(
    label: String,
    value: String?,
    selected: Boolean,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    height: Dp = m.rowHeight,
    labelWeight: Float = 0.58f,
    /** A dashed line under the row, between it and the next. */
    divider: Boolean = false,
    /** An [OptionBadge] after the label ("BETA"). */
    labelBadge: String? = null,
    /** Pixel art before the label (RetroAchievements' trophy), a line high. */
    labelIcon: (@Composable () -> Unit)? = null,
    /** An [OptionBadge] after the value. */
    valueBadge: String? = null,
    /** False greys the row out and ignores taps (a setting another one has turned off). */
    enabled: Boolean = true,
    /** A smaller grey line under the label: what the row is for. */
    subtitle: String? = null,
    /** The row took the D-pad's focus: a list whose cursor follows it (SETTINGS, so only one row is white). */
    onFocus: (() -> Unit)? = null,
    /** A pixel chevron at the row's right end: it opens a page of its own. */
    chevron: Boolean = false,
    /** Room under the text, inside [height]: the label sits that much above the row's middle. */
    bottomPadding: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    if (onFocus != null) {
        val current by androidx.compose.runtime.rememberUpdatedState(onFocus)
        androidx.compose.runtime.LaunchedEffect(focused) { if (focused) current() }
    }
    // A pop-up this row opens gives the focus back to it ([OverlayStack]); in a pop-up, the current choice starts focused.
    val stack = LocalOverlayStack.current
    val self = remember { FocusRequester() }
    if (enabled) LocalOverlayStart.current?.register(self, current = selected)
    val chevronCell = with(androidx.compose.ui.platform.LocalDensity.current) { m.fontPixel.toDp() }
    val chevronRoom = if (chevron) chevronCell * (PixelIcons.chevronRight.maxOf { it.length } + 1) + m.u * 4 else 0.dp
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .focusRequester(self)
            .fillMaxWidth()
            .height(height)
            .background(if (selected || focused) OptionColors.rowSelected else Color.Transparent)
            .then(if (divider) Modifier.drawBehind { drawRowDivider(OptionColors.divider, m.u.toPx(), 4 * m.u.toPx()) } else Modifier)
            .then(if (enabled) Modifier.soundClickable(interactionSource = interaction, indication = null, onClick = { stack?.lastClicked = self; onClick() }) else Modifier)
            .padding(start = m.u * 8, end = m.u * 8, bottom = bottomPadding),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // The label and value columns stay where they are with or without a chevron, so values line up down a list.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(labelWeight), verticalAlignment = Alignment.CenterVertically) {
                    labelIcon?.let { Box(Modifier.size(m.lineHeight)) { it() }; Spacer(Modifier.width(m.u * 4)) }
                    if (enabled) GbaText(tr(label), OptionColors.label, OptionColors.labelShadow, m)
                    else GbaText(tr(label), OptionColors.muted, OptionColors.mutedShadow, m)
                    labelBadge?.let {
                        // NEW (a just-shipped feature, companion.NewFeatures) in the Library's green; anything else red.
                        val new = it == com.pokedaisy.app.companion.NewFeatures.NEW_TAG
                        OptionBadge(
                            it, m, Modifier.padding(start = m.u * 6),
                            fill = if (new) OptionColors.newBadge else OptionColors.value,
                            shadow = if (new) OptionColors.newBadgeShadow else OptionColors.valueShadow.copy(alpha = 0.6f),
                        )
                    }
                }
                if (value != null) {
                    Row(Modifier.weight(1f - labelWeight).padding(end = chevronRoom), verticalAlignment = Alignment.CenterVertically) {
                        if (enabled) GbaText(tr(value), OptionColors.value, OptionColors.valueShadow, m)
                        else GbaText(tr(value), OptionColors.muted, OptionColors.mutedShadow, m)
                        valueBadge?.let { OptionBadge(it, m, Modifier.padding(start = m.u * 6)) }
                    }
                }
            }
            // Under the label and the value both: the whole row's width, so it isn't cut off beside a value.
            subtitle?.let {
                val small = subtitleMetrics(m)
                GbaText(tr(it), OptionColors.muted, OptionColors.mutedShadow, small, modifier = Modifier.padding(end = chevronRoom))
            }
        }
        if (chevron) {
            ShadowedPixelIcon(
                PixelIcons.chevronRight,
                if (enabled) OptionColors.label else OptionColors.muted,
                if (enabled) OptionColors.labelShadow else OptionColors.mutedShadow,
                m, cell = chevronCell, modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** [OptionLine]'s subtitle text: 0.8 of the row's - [m] already holds a game font's larger size, so not scaled again (it
 * came out as large as the label in Yellow, and was cut off). */
@Composable
fun subtitleMetrics(m: GbaTextMetrics): GbaTextMetrics = rememberGbaTextMetrics(0.8f * m.fontPixel / m.px, gameScaled = false)

/** A small tag on a row ("BETA" on a feature that isn't finished): white on the value red, pill-shaped. */
@Composable
fun OptionBadge(
    text: String, m: GbaTextMetrics, modifier: Modifier = Modifier, fill: Color = OptionColors.value,
    shadow: Color = OptionColors.valueShadow.copy(alpha = 0.6f),
) {
    // ~2/3 of the row's own text: 0.85 next to the main lists' 1.25, smaller beside denser text. [m] already holds a game
    // font's larger size: not scaled a second time.
    val small = rememberGbaTextMetrics(0.68f * m.fontPixel / m.px, gameScaled = false)
    Box(
        modifier
            .background(fill, PixelPillShape)
            .padding(horizontal = m.u * 5, vertical = m.u),
    ) {
        GbaText(tr(text), OptionColors.badgeText, shadow, small)
    }
}

/**
 * [OptionLine] rows that share out the available height (big touch targets)
 * between [minRow] and [maxRow] - past the floor the rows scroll instead.
 * [rows]: label, value (null = command row), onClick. [selected]: the row
 * index drawn as the white cursor, if any.
 */
@Composable
fun OptionRows(
    rows: List<Triple<String, String?, () -> Unit>>,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    selected: Int = -1,
    minRow: Dp = m.rowHeight * 1.2f,
    maxRow: Dp = m.lineHeight * 2.4f,
    labelWeight: Float = 0.58f,
    /** Dashed lines between the rows, like the bag list. */
    dividers: Boolean = true,
    /** An [OptionBadge] after row i's value. */
    valueBadge: (Int) -> String? = { null },
    /** False greys row i out ([OptionLine]'s enabled). */
    enabled: (Int) -> Boolean = { true },
    /** An [OptionBadge] after row i's label (NEW). */
    labelBadge: (Int) -> String? = { null },
    /** Row i took the D-pad's focus (the cursor follows it). */
    onFocused: ((Int) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val rowHeight = if (rows.isEmpty() || maxHeight == Dp.Infinity) minRow
        else (maxHeight / rows.size).coerceIn(minRow, maxRow.coerceAtLeast(minRow))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, (label, value, onClick) ->
                OptionLine(
                    label, value, selected = i == selected, m, modifier = Modifier.focusStart(i == selected),
                    height = rowHeight, labelWeight = labelWeight,
                    divider = dividers && i < rows.lastIndex, valueBadge = valueBadge(i), enabled = enabled(i), labelBadge = labelBadge(i),
                    onFocus = onFocused?.let { f -> { f(i) } }, onClick = onClick,
                )
            }
        }
    }
}

/**
 * A free-standing button in the title window's look (white, grey frame) -
 * for actions that aren't a row of a list (Save/Load, Undo, header icons).
 * [emphasis] draws the label in the value red; [enabled] false greys it out.
 */
@Composable
fun OptionButton(
    label: String?,
    m: GbaTextMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasis: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = label,
    /** A [PixelIcons] bitmap before the label, drawn in the text's own pixels and shadow. */
    pixelIcon: List<String>? = null,
    /** [pixelIcon]'s colour, when it isn't the label's (a gold favorite star). */
    pixelIconColor: Color? = null,
    /** The label's colour and shadow, when they're neither grey nor [emphasis]'s red (a field move's blue). */
    labelColors: Pair<Color, Color>? = null,
    /** The frame's layers, when it isn't the title window's ([OptionColors.fieldMoveLayers]). */
    frameLayers: List<Pair<Color, Int>>? = null,
    /** The room either side of the content, in GBA pixels: less for a row of icon buttons on a narrow screen. */
    sidePadding: Int = 8,
    /** In a pop-up, the D-pad starts here rather than on its first control. */
    focusStart: Boolean = false,
) {
    val u = m.u
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val stack = LocalOverlayStack.current
    val self = remember { FocusRequester() }
    if (enabled) LocalOverlayStart.current?.register(self, current = focusStart)
    val fg = when {
        !enabled -> OptionColors.muted
        labelColors != null -> labelColors.first
        emphasis -> OptionColors.value
        else -> OptionColors.label
    }
    val shadow = when {
        !enabled -> OptionColors.mutedShadow
        labelColors != null -> labelColors.second
        emphasis -> OptionColors.valueShadow
        else -> OptionColors.labelShadow
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .focusRequester(self)
            // The button's own corner: its box below is drawn at a 3u radius.
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .heightIn(min = m.rowHeight)
            .drawBehind {
                drawLayeredBox(
                    (frameLayers ?: OptionColors.titleLayers).inPx(u.toPx()),
                    if (focused) OptionColors.listFill else OptionColors.titleFill,
                    radius = 3 * u.toPx(),
                )
            }
            .soundClickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = { stack?.lastClicked = self; onClick() })
            // A pixel icon alone says what it is (TalkBack; the previews find it by it).
            .then(
                if (label == null && pixelIcon != null && contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier,
            )
            .padding(horizontal = u * sidePadding, vertical = u * 4),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(m.lineHeight))
        }
        if (pixelIcon != null) {
            val cell = with(androidx.compose.ui.platform.LocalDensity.current) { m.fontPixel.toDp() }
            ShadowedPixelIcon(pixelIcon, pixelIconColor ?: fg, shadow, m, cell = cell)
        }
        if ((icon != null || pixelIcon != null) && label != null) Spacer(Modifier.width(u * 4))
        if (label != null) GbaText(tr(label), fg, shadow, m, bold = gameBoldLabels())
    }
}

/** Text drawn straight on a game backdrop (section headers): white, shadowed. */
@Composable
fun BackdropText(text: String, m: GbaTextMetrics, modifier: Modifier = Modifier) {
    GbaText(text, OptionColors.onBackdrop, OptionColors.onBackdropShadow, m, modifier = modifier)
}

/**
 * Black-out overlay for pickers/confirmations. An in-composition overlay, not
 * a Compose `Dialog` - those open a new window, which throws inside the
 * Screen-2 Presentation.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class) // exit / focusGroup, on older Compose
fun OptionOverlay(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompanionBackHandler(onBack = onDismiss)
    val noRipple = remember { MutableInteractionSource() }
    // With an [OverlayStack] (a top-screen activity's D-pad): the focus moves in, stays in, and goes back to what
    // opened it when this closes; B / BACK closes the newest.
    val stack = LocalOverlayStack.current
    // Where the focus starts: the current choice if there is one, else the first control (they register as they compose).
    val start = remember { OverlayStart() }
    // The companion's window: the D-pad starts here, not behind, when nothing has the focus (it was opened by a tap).
    val starts = LocalFocusStarts.current
    if (stack != null && starts != null) {
        androidx.compose.runtime.DisposableEffect(start) {
            val entry: () -> FocusRequester? = { start.preferred ?: start.first }
            starts.entries += entry
            onDispose { starts.entries -= entry }
        }
    }
    if (stack != null) {
        val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
        val e = remember { OverlayStack.Entry({ dismiss() }, stack.lastClicked) }
        androidx.compose.runtime.DisposableEffect(e) {
            stack.entries += e
            onDispose {
                stack.entries -= e
                e.returnTo?.let { runCatching { it.requestFocus() } }
            }
        }
        androidx.compose.runtime.LaunchedEffect(e) {
            androidx.compose.runtime.withFrameNanos { }
            (start.preferred ?: start.first)?.let { runCatching { it.requestFocus() } }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            // The scrim and the window's tap-swallower aren't stops for the D-pad.
            .focusProperties { canFocus = false }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onDismiss)
            .padding(16.dp),
    ) {
        Box(
            modifier
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .focusProperties { canFocus = false }
                .clickable(interactionSource = noRipple, indication = null, onClick = {}),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalOverlayStart provides start.takeIf { stack != null }) { content() }
        }
    }
}

/** A pop-up's first control and its current choice, as they compose: where its focus starts. */
class OverlayStart {
    internal var first: FocusRequester? = null
    internal var preferred: FocusRequester? = null

    internal fun register(requester: FocusRequester, current: Boolean) {
        if (first == null) first = requester
        if (current && preferred == null) preferred = requester
    }
}

internal val LocalOverlayStart = androidx.compose.runtime.staticCompositionLocalOf<OverlayStart?> { null }

/**
 * The pop-ups ([OptionOverlay]) open over one top-screen activity, newest last, for its D-pad: B / BACK closes the newest
 * ([dismissTop], from the activity's key handling) and the focus goes back to the row / button that opened it - the last
 * [OptionLine] / [OptionButton] pressed when it opened. Provided as [LocalOverlayStack]; the companion has CompanionBack.
 */
class OverlayStack {
    internal class Entry(val dismiss: () -> Unit, val returnTo: FocusRequester?)
    internal val entries = mutableListOf<Entry>()
    internal var lastClicked: FocusRequester? = null

    /** Whether any pop-up is open. */
    val isOpen: Boolean get() = entries.isNotEmpty()

    /** Closes the newest pop-up; false = none open. */
    fun dismissTop(): Boolean {
        val e = entries.lastOrNull() ?: return false
        e.dismiss()
        return true
    }
}

val LocalOverlayStack = androidx.compose.runtime.staticCompositionLocalOf<OverlayStack?> { null }

/**
 * A pick-one list over the screen: tapping a setting's row opens this instead
 * of stepping through every value. The current choice is the white row and
 * carries a red ◀ marker; tapping any row picks it and closes.
 */
@Composable
fun <T> OptionSelector(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    m: GbaTextMetrics,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    /** An [OptionBadge] after an option's label. */
    badge: (T) -> String? = { null },
    /** A few words above the options in the subtitles' grey (SHINY ODDS' disclaimer), or null. */
    note: String? = null,
) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 480.dp)) {
        BoxWithConstraints {
            // Fit every option + CANCEL on screen when there's room (title
            // window, gap and list frame take ~40u, a note about two lines), else scroll.
            val u = m.u
            val noteRoom = if (note != null) subtitleMetrics(m).lineHeight * 2 + u * 8 else 0.dp
            val room = if (maxHeight == Dp.Infinity) m.rowHeight * 20 else maxHeight - u * 40 - m.lineHeight - noteRoom
            val rowHeight = (room / (options.size + 1)).coerceIn(m.rowHeight, m.rowHeight * 1.3f)
            // The D-pad's row (index, options.size = CANCEL): while it's on another one, the current choice keeps
            // its marker but not the white, so one row is lit.
            var focusedRow by remember { mutableStateOf(-1) }
            Column {
                OptionTitleWindow(title, m, onBack = onDismiss)
                Spacer(Modifier.height(u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        note?.let {
                            GbaText(
                                tr(it), OptionColors.muted, OptionColors.mutedShadow, subtitleMetrics(m), maxLines = Int.MAX_VALUE,
                                modifier = Modifier.padding(horizontal = u * 8, vertical = u * 4),
                            )
                        }
                        options.forEachIndexed { i, opt ->
                            val on = opt == selected
                            OptionLine(
                                label(opt), if (on) "◀" else null, selected = on && (focusedRow < 0 || focusedRow == i), m,
                                height = rowHeight, labelWeight = 0.85f, divider = true, labelBadge = badge(opt),
                                onFocus = { focusedRow = i },
                            ) { onPick(opt) }
                        }
                        OptionLine(
                            tk("CANCEL"), null, selected = false, m, height = rowHeight,
                            onFocus = { focusedRow = options.size }, onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/** OPTION-styled yes/no: title window + message + [confirmLabel] / CANCEL rows. */
@Composable
fun OptionConfirm(
    title: String,
    message: String,
    confirmLabel: String,
    m: GbaTextMetrics,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Null: [confirmLabel] alone, for a notice there's nothing to cancel on. */
    cancelLabel: String? = tk("CANCEL"),
) {
    OptionOverlay(onDismiss, Modifier.widthIn(max = 480.dp)) {
        Column {
            OptionTitleWindow(title, m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    // A long message on a small screen scrolls; the buttons stay below it, on screen.
                    GbaText(
                        message, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE,
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = m.u * 8, vertical = m.u * 4),
                    )
                    // Side by side, each a framed button.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 4, vertical = m.u * 4),
                    ) {
                        cancelLabel?.let { OptionButton(it, m, onClick = onDismiss, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f)) }
                        OptionButton(confirmLabel, m, onClick = onConfirm, emphasis = true, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                    }
                }
            }
        }
    }
}

/** A game's party-menu background, pixel-scaled to fill (see [rememberGameBackground]). */
@Composable
fun GameBackdrop(game: GameKind, modifier: Modifier = Modifier.fillMaxSize()) {
    // Dark mode: the game's art at night, under a blue-black scrim (Gen 1's white nearly all the way).
    val scrim = OptionColors.backdropScrim.let { if (it > 0f && game.isGameBoyGame) 0.9f else it }
    @Suppress("NAME_SHADOWING")
    val modifier = if (scrim > 0f) modifier.drawWithContent { drawContent(); drawRect(NIGHT.copy(alpha = scrim)) } else modifier
    // HeartGold / SoulSilver: their party screen's flat teal, no stripes.
    if (game.isHgss) {
        Canvas(modifier) { drawRect(HGSS_BACKDROP) }
        return
    }
    // Black's and Platinum's party screens: their own patterns (the scrim already on [modifier]).
    dsPartyPattern(game)?.let { DsBackdrop(it, modifier, scrim = 0f); return }
    val bg = rememberGameBackground(game)
    if (bg == null) {
        // Retail FR/LG must not depend on another game having populated the art cache.
        // Match the party slots' integer scale; stretching a 240x160 bitmap creates uneven stripes.
        if (game == GameKind.FIRERED && com.pokedaisy.app.companion.data.runningFireRedHack == null) {
            Canvas(modifier) {
                val stripe = max(1, (size.width / 240f).toInt()).toFloat()
                drawRect(Color(0xFF398C8C))
                var y = 0f
                while (y < size.height) {
                    drawRect(Color(0xFF4AADA5), Offset(0f, y), Size(size.width, stripe))
                    y += 2 * stripe
                }
            }
        }
        return
    }
    if (bg.tiled) {
        // Whole-number scale of a 240px-wide GBA screen - on the
        // Thor the same scale the party slots land on, so the grid
        // matches their pixels like it does in the game.
        Canvas(modifier = modifier) {
            val k = max(1, (size.width / 240f).toInt())
            val step = bg.image.width * k
            for (y in 0 until size.height.toInt() step step) {
                for (x in 0 until size.width.toInt() step step) {
                    drawImage(
                        bg.image, dstOffset = IntOffset(x, y), dstSize = IntSize(step, bg.image.height * k),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
    } else {
        Image(
            bitmap = bg.image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
        )
    }
}

/** HeartGold / SoulSilver's party screen background (headless capture). */
private val HGSS_BACKDROP = Color(0xFF8ADBDB)

/**
 * The top-screen (Library / Settings) backdrop: the 1-GBA-pixel horizontal
 * stripes of FireRed's party-menu screen - the backdrop the companion's tabs
 * sit on - drawn natively so it fills any aspect ratio (the party screen
 * image itself has a dark cancel-button column that looks wrong stretched
 * to 16:9). The FireRed theme uses the game's exact two teals; other themes
 * stripe their own slot colors, so a theme still reskins the app. The
 * PokéDaisy theme (the default) draws the website's backdrop instead ([DaisyBackdrop]).
 */
@Composable
fun AppBackdrop(
    /** The PokéDaisy theme's floating logos (the Library). */
    logos: Boolean = false,
) {
    // PokéDaisy: the website's light backdrop - outside a game only, whose windows keep the game's look.
    if (isDaisyTheme && !OptionColors.inGame) {
        DaisyBackdrop(logos)
        return
    }
    val m = rememberGbaTextMetrics()
    val (light, lightDark) = if (AppColors.currentThemeId == 0) {
        Color(0xFF4AADA5) to Color(0xFF398C8C)
    } else {
        AppColors.slotFill to AppColors.slotFillDark
    }
    // Dark mode: the theme's stripes at night (the game backdrops' scrim, worked out once).
    val scrim = OptionColors.backdropScrim
    val a = if (scrim > 0f) lerp(light, NIGHT, scrim) else light
    val b = if (scrim > 0f) lerp(lightDark, NIGHT, scrim) else lightDark
    Canvas(Modifier.fillMaxSize()) {
        val stripe = m.px.toFloat()
        drawRect(b)
        var y = 0f
        while (y < size.height) {
            drawRect(a, Offset(0f, y), Size(size.width, stripe))
            y += 2 * stripe
        }
    }
}

/** A text input in the title window's look (white, grey frame), pixel font: one line, or
 * [minLines] and more. [password] hides what's typed and keeps the keyboard from learning
 * it, with an eye at the end to show / hide it; [code] asks for capitals with no suggestions
 * (cheat codes); [number] for digits. */
@Composable
fun OptionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    minLines: Int = 1,
    code: Boolean = false,
    number: Boolean = false,
    /** A [PixelIcons] bitmap before the text, in the placeholder's grey (a search box's magnifier). */
    leadingIcon: List<String>? = null,
) {
    val u = m.u
    var shown by remember { androidx.compose.runtime.mutableStateOf(false) }
    // D-pad focus (the library's and Settings' search box) shows like every other control's: the focus ring.
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val hidden = password && !shown
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = minLines == 1,
        minLines = minLines,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = when {
            password -> KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password)
            number -> KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Number)
            code -> KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false, keyboardType = KeyboardType.Ascii)
            else -> KeyboardOptions.Default
        },
        textStyle = gbaTextStyle(pixelFontFamily(), m).copy(color = OptionColors.label),
        cursorBrush = SolidColor(OptionColors.value),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .gbaFocusRing(focused, PixelRoundedShape(u * 2))
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
            .padding(horizontal = u * 6, vertical = u * 5),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                leadingIcon?.let {
                    PixelIcon(it, OptionColors.muted, Modifier.size(m.lineHeight))
                    Spacer(Modifier.width(u * 4))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) GbaText(placeholder, OptionColors.muted, OptionColors.mutedShadow, m)
                    inner()
                }
                if (password) {
                    // SHOW / HIDE what's typed: an eye, struck through while it shows.
                    val noRipple = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .padding(start = u * 4)
                            .clickable(interactionSource = noRipple, indication = null, onClick = { shown = !shown })
                            .semantics { contentDescription = if (shown) tr("Hide password") else tr("Show password") },
                    ) {
                        PixelIcon(if (shown) PixelIcons.eyeClosed else PixelIcons.eyeOpen, OptionColors.label, Modifier.size(m.lineHeight))
                    }
                }
            }
        },
    )
}

/** Dark mode's night: what the backdrops are dimmed towards ([OptionColors.backdropScrim]). */
val NIGHT = Color(0xFF0A0D14)
