package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.lerp
import com.pokedaisy.app.companion.ui.theme.nightSurface
import com.pokedaisy.app.companion.ui.theme.nightShadow
import com.pokedaisy.app.companion.ui.theme.nightInk
import com.pokedaisy.app.companion.FavoriteItems
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pokedaisy.app.companion.data.RomArt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.ItemUseOutcome
import com.pokedaisy.app.companion.data.ItemUseResult
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.itemName
import com.pokedaisy.app.companion.data.POCKET_BERRIES
import com.pokedaisy.app.companion.data.POCKET_ITEMS
import com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS
import com.pokedaisy.app.companion.data.POCKET_POKE_BALLS
import com.pokedaisy.app.companion.data.POCKET_TM_HM
import com.pokedaisy.app.companion.data.POCKET_MEDICINE
import com.pokedaisy.app.companion.data.POCKET_MAIL
import com.pokedaisy.app.companion.data.POCKET_BATTLE_ITEMS
import com.pokedaisy.app.companion.data.NdsArt
import com.pokedaisy.app.companion.data.lookupMove
import com.pokedaisy.app.companion.data.tmMovesHeartGold
import com.pokedaisy.app.companion.data.OriginHeartGold
import com.pokedaisy.app.companion.data.originHeartGold
import com.pokedaisy.app.companion.data.HEARTGOLD_TM01
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.isHgss
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.data.itemNamesEmeraldGame
import com.pokedaisy.app.companion.data.itemNamesFireRedGame
import com.pokedaisy.app.companion.data.runningEmeraldHack
import com.pokedaisy.app.companion.data.runningFireRedHack
import com.pokedaisy.app.companion.data.localText
import com.pokedaisy.app.companion.data.pocketLabel
import com.pokedaisy.app.companion.data.activePocketOrder
import com.pokedaisy.app.companion.ui.theme.pixelFontFamily
import com.pokedaisy.app.companion.data.isGameBoyGame
import com.pokedaisy.app.companion.data.isGen2
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.foundation.interaction.collectIsFocusedAsState

/**
 * DEFAULT keeps the bag's own slot order, as the game lists it, with the player's
 * starred items ([FavoriteItems]) first. [label] is English, translated where drawn.
 */
private enum class ItemSort(val label: String) { DEFAULT(tk("Default")), NAME(tk("Name")), TYPE(tk("Type")), COUNT(tk("Count")) }

/**
 * FireRed and Emerald print item names and pocket titles in ALL CAPS
 * ("POKé BALL", "PARLYZ HEAL"), so the bag screen uses each game's own names
 * (ItemTextGame.kt) rather than the app-wide Title Case tables. Games whose item
 * table was extracted from their own ROM already carry the game's casing, and
 * descriptions always do: they're read from the ROM (RomItemText).
 */
private fun gameItemNames(game: GameKind): Map<Int, String>? = when (game) {
    // A localized game's own (GameText), already in its casing.
    GameKind.FIRERED -> localText?.items ?: runningFireRedHack?.itemsGame ?: itemNamesFireRedGame
    // Recharged Emerald: its own whole table, in its mixed case.
    GameKind.EMERALD -> localText?.items ?: runningEmeraldHack?.items ?: itemNamesEmeraldGame
    else -> null
}

/** Games whose bag prints pocket titles in caps; their item tables already are. */
/** [id]'s name as this game's bag writes it (its own casing and spelling: "EXP. SHARE"). */
internal fun bagItemName(id: Int): String = gameItemNames(activeGame)?.get(id) ?: itemName(id)

private fun capsBagLabels(game: GameKind): Boolean =
    gameItemNames(game) != null || game == GameKind.HEART_AND_SOUL || game == GameKind.ORANGE_ISLANDS ||
        (game.isGen2 && game != GameKind.PRISM && game != GameKind.POLISHED_CRYSTAL)   // Prism's and Polished's bags are mixed case

/** The FAVORITES tab's id among the pocket tabs (pocket ids are 0 and up): the starred items. */
private const val FAVORITES_TAB = -1

private fun capsPocketLabel(pocket: Int?): String = when (pocket) {
    null -> tr("ALL")
    FAVORITES_TAB -> tr("FAVORITES")
    POCKET_ITEMS -> tr("ITEMS")
    POCKET_POKE_BALLS -> tr("POKé BALLS")
    POCKET_TM_HM -> tr("TMs & HMs")
    POCKET_BERRIES -> tr("BERRIES")
    POCKET_KEY_ITEMS -> tr("KEY ITEMS")
    else -> tr(pocketLabel(pocket).uppercase())
}

/** [pocketLabel] (Title Case, for the other games' bags) in the app's language. */
private fun titlePocketLabel(pocket: Int?): String = when (pocket) {
    null -> tr("All")
    FAVORITES_TAB -> tr("Favorites")
    POCKET_ITEMS -> tr("Items")
    POCKET_POKE_BALLS -> tr("Poke Balls")
    POCKET_TM_HM -> tr("TMs & HMs")
    POCKET_BERRIES -> tr("Berries")
    POCKET_KEY_ITEMS -> tr("Key Items")
    else -> tr(pocketLabel(pocket))
}

/**
 * One game's bag-screen look. The Items tab is drawn entirely from plain
 * Compose shapes + text in these colors (no per-game image assets, unlike the
 * party slots), so a new game only needs a new palette. Layer widths are in u.
 */
private data class BagPalette(
    /**
     * The list window's frame, outermost first. The folder tabs reuse its
     * first [tabLayerCount] layers as their own border, filled with the next
     * layer's color - the band the selected tab opens onto.
     */
    val panelLayers: List<Pair<Color, Int>>,
    val tabLayerCount: Int,
    val fill: Color,
    /** Line under each row; null = none (Emerald). */
    val divider: Color?,
    val text: Color,
    val textShadow: Color,
    val tabText: Color,
    val tabTextShadow: Color,
    /** Under the selected tab's label, like FireRed's pocket-title bar. */
    val tabUnderline: Color,
    /** Description box (and sort button) frame, outermost first. */
    val descLayers: List<Pair<Color, Int>>,
    val descFill: Color,
    val descText: Color,
    val descTextShadow: Color,
    val iconBox: Color,
    val iconBoxEdge: Color,
    /** The bag screen's own 2u-striped backdrop, where it differs from the party one. */
    val backdrop: Pair<Color, Color>? = null,
    /** An image (rom-art path, [RomArt]) tiled over [backdrop] once a ROM has supplied it. */
    val backdropArt: String? = null,
    /** [divider]'s dash, on/off in u (the vanilla bag's 6/2); null = solid. */
    val dividerDash: Pair<Int, Int>? = 6 to 2,
    /** [divider]'s width in u, and an optional 1u shadow line under it. */
    val dividerWidth: Int = 2,
    val dividerShadow: Color? = null,
    /** A DS game's own bag-screen pattern ([DsBackdrop]) in place of [backdrop]'s stripes, which dark mode still dims. */
    val pattern: DsPattern? = null,
) {
    val tabLayers get() = panelLayers.take(tabLayerCount)
    val tabFill get() = panelLayers[tabLayerCount].first
    /** How far the selected tab's fill reaches over the window's frame: onto its [tabFill] band. */
    val selectedFillReach get() = tabLayers.sumOf { it.second }
    /** ...and its outer border: only over the window's own outer border, for a clean corner. */
    val selectedBorderReach get() = panelLayers[0].second
    val panelFrame get() = panelLayers.sumOf { it.second }

    /** Dark mode's version: the same windows in their own hues, dark, with light text (theme/NightColors.kt). */
    fun night(scrim: Float = OptionColors.backdropScrim): BagPalette = copy(
        panelLayers = panelLayers.map { (c, w) -> c.nightSurface() to w },
        fill = fill.nightSurface(),
        divider = divider?.nightShadow(),
        text = text.nightInk(), textShadow = textShadow.nightShadow(),
        tabText = tabText.nightInk(), tabTextShadow = tabTextShadow.nightShadow(),
        descLayers = descLayers.map { (c, w) -> c.nightSurface() to w },
        descFill = descFill.nightSurface(),
        descText = descText.nightInk(), descTextShadow = descTextShadow.nightShadow(),
        iconBox = iconBox.nightSurface(), iconBoxEdge = iconBoxEdge.nightSurface(),
        backdrop = backdrop?.let { (a, b) -> lerp(a, NIGHT, scrim) to lerp(b, NIGHT, scrim) },
        dividerShadow = dividerShadow?.nightSurface(),
    )
}

private val nightBags = java.util.concurrent.ConcurrentHashMap<BagPalette, BagPalette>()

/** Sampled 1:1 from a FireRed bag screenshot. */
private val FireRedBag = BagPalette(
    panelLayers = listOf(Color(0xFF686868) to 2, Color(0xFFE8E0A8) to 1, Color(0xFFF0C870) to 2, Color(0xFFD0B050) to 2),
    tabLayerCount = 2,
    fill = Color(0xFFF8F8C8), divider = Color(0xFFE8E0A8),
    text = Color(0xFF606060), textShadow = Color(0xFFD0D0C8),
    tabText = Color(0xFFF8F8F8), tabTextShadow = Color(0xFF606060), tabUnderline = Color(0xFFD88848),
    descLayers = listOf(Color(0xFF005070) to 2, Color(0xFF10A8D8) to 1), descFill = Color(0xFF0078C0),
    descText = Color(0xFFF8F8F8), descTextShadow = Color(0xFF606060),
    iconBox = Color(0xFFF8F8F8), iconBoxEdge = Color(0xFFB8D0D0),
)

/**
 * Sampled 1:1 from an Emerald bag screen (mGBA): navy/slate/grey window frame
 * around a gold band, no row lines, black text, a white description box, and
 * purple/blue striped backdrop. The underline borrows the pocket arrows' red.
 */
private val EmeraldBag = BagPalette(
    panelLayers = listOf(Color(0xFF335170) to 2, Color(0xFF2C3941) to 1, Color(0xFF636372) to 1, Color(0xFFFBE898) to 2),
    tabLayerCount = 3,
    fill = Color(0xFFFFFFD3), divider = null,
    text = Color(0xFF000000), textShadow = Color(0xFFD6D6CF),
    tabText = Color(0xFF000000), tabTextShadow = Color(0xFFB89D63), tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF7B7B7B) to 1), descFill = Color(0xFFFFFFFF),
    descText = Color(0xFF000000), descTextShadow = Color(0xFFD6D6CF),
    iconBox = Color(0xFFFFFFFF), iconBoxEdge = Color(0xFF7B7B7B),
    backdrop = Color(0xFFC871F7) to Color(0xFF5882F7),
)

/**
 * Sampled 1:1 from Pokémon Monomyth's bag screen (headless, the user's save): Emerald's layout in its own colours - a
 * cream list in a grey line, a pale gold edge and a gold band, black text, the pocket title's orange arrows, a white
 * description box, over pale-blue checks.
 */
private val MonomythBag = BagPalette(
    panelLayers = listOf(Color(0xFF7B7B7B) to 1, Color(0xFFE7DEA5) to 1, Color(0xFFF7CE73) to 2, Color(0xFFD6B54A) to 1),
    tabLayerCount = 3,
    fill = Color(0xFFFFFFCE), divider = null,
    text = Color(0xFF000000), textShadow = Color(0xFFD6D6CE),
    tabText = Color(0xFF000000), tabTextShadow = Color(0xFFD6B54A), tabUnderline = Color(0xFFFF5200),
    descLayers = listOf(Color(0xFF7B7B7B) to 1), descFill = Color(0xFFFFFFFF),
    descText = Color(0xFF000000), descTextShadow = Color(0xFFD6D6CE),
    iconBox = Color(0xFFFFFFFF), iconBoxEdge = Color(0xFF7B7B7B),
    backdrop = Color(0xFFC6E7FF) to Color(0xFFADCEE7),
)

/**
 * Sampled 1:1 from Heart and Soul's bag screen (headless mGBA): Emerald's bag
 * layout re-skinned - a cream list window in a grey/charcoal/slate frame
 * around a gold band, black text, the gold pocket banner with its orange
 * arrows (the underline), a white description box and icon box, and grey
 * 2px-striped backdrop.
 */
private val HeartAndSoulBag = BagPalette(
    panelLayers = listOf(Color(0xFF8C8C8C) to 2, Color(0xFF393139) to 1, Color(0xFF636373) to 1, Color(0xFFFFE78C) to 2),
    tabLayerCount = 3,
    fill = Color(0xFFFFFFCE), divider = null,
    text = Color(0xFF000000), textShadow = Color(0xFFD6D6CE),
    tabText = Color(0xFF000000), tabTextShadow = Color(0xFFBD9C5A), tabUnderline = Color(0xFFFF5200),
    descLayers = listOf(Color(0xFF7B7B7B) to 1), descFill = Color(0xFFFFFFFF),
    descText = Color(0xFF000000), descTextShadow = Color(0xFFD6D6CE),
    iconBox = Color(0xFFFFFFFF), iconBoxEdge = Color(0xFF7B7B7B),
    backdrop = Color(0xFFC6C6C6) to Color(0xFFADADAD),
)

/**
 * Sampled 1:1 from Unbound's cube (bag) screen: slate windows with a
 * dark/light green double line, white text with a grey shadow, solid 1px row
 * lines, a green-edged white icon box, on the screen's flat light grey.
 */
private val UnboundBag = BagPalette(
    panelLayers = listOf(Color(0xFF3A3949) to 1, Color(0xFF4EA06F) to 1, Color(0xFF62C791) to 1, Color(0xFF3A3949) to 1),
    tabLayerCount = 3,
    fill = Color(0xFF3A3949), divider = Color(0xFF4A4951),
    text = Color(0xFFFEFBFF), textShadow = Color(0xFF636163),
    tabText = Color(0xFFFEFBFF), tabTextShadow = Color(0xFF636163), tabUnderline = Color(0xFF62C791),
    descLayers = listOf(Color(0xFF3A3949) to 1, Color(0xFF62C791) to 1), descFill = Color(0xFF3A3949),
    descText = Color(0xFFFEFBFF), descTextShadow = Color(0xFF636163),
    iconBox = Color(0xFFFEFBFF), iconBoxEdge = Color(0xFF4EA06F),
    backdrop = Color(0xFFE5E3E6) to Color(0xFFE5E3E6),
    dividerDash = null, dividerWidth = 1,
)

/**
 * Sampled from Amethyst's bag screen: a light-blue list in a dark-navy frame
 * with dashed blue row lines and the vanilla grey text, the pocket title's
 * navy box with a blue edge (the tabs), the black description bar with its
 * grey-to-black top edge, a light icon box, the orange pocket arrows (the
 * underline), and the screen's navy backdrop.
 */
private val AmethystBag = BagPalette(
    panelLayers = listOf(Color(0xFF061937) to 1, Color(0xFF4C8AF7) to 1, Color(0xFF061937) to 2),
    tabLayerCount = 2,
    fill = Color(0xFFBBD5FB), divider = Color(0xFF4C8AF7),
    text = Color(0xFF636363), textShadow = Color(0xFFD6D6CF),
    tabText = Color(0xFFFFFFFF), tabTextShadow = Color(0xFF636363), tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF525252) to 1, Color(0xFF424242) to 1, Color(0xFF313131) to 1, Color(0xFF212121) to 1),
    descFill = Color(0xFF080808),
    descText = Color(0xFFFFFFFF), descTextShadow = Color(0xFF636363),
    iconBox = Color(0xFFEFEFEF), iconBoxEdge = Color(0xFF848484),
    backdrop = Color(0xFF19418F) to Color(0xFF183877),
)

/**
 * Sampled from Radical Red's bag screen: a white list with dotted grey row
 * lines (1px dots with a light shadow) and the vanilla grey text, framed
 * black / white / black around a charcoal band like its "Items" title box
 * (the tabs: charcoal, white text), the charcoal description box in the same
 * edges, a black-edged white icon box, the orange pocket arrows (underline)
 * and the navy backdrop behind the list.
 */
private val RadicalRedBag = BagPalette(
    panelLayers = listOf(Color(0xFF000000) to 1, Color(0xFFFFFFFF) to 1, Color(0xFF000000) to 1, Color(0xFF313131) to 2),
    tabLayerCount = 3,
    fill = Color(0xFFFFFFFF), divider = Color(0xFF949494),
    text = Color(0xFF636363), textShadow = Color(0xFFD6D6CF),
    tabText = Color(0xFFFFFFFF), tabTextShadow = Color(0xFF636363), tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF000000) to 2, Color(0xFFFFFFFF) to 1, Color(0xFF000000) to 1),
    descFill = Color(0xFF313131),
    descText = Color(0xFFFFFFFF), descTextShadow = Color(0xFF636363),
    iconBox = Color(0xFFFFFFFF), iconBoxEdge = Color(0xFF000000),
    backdrop = Color(0xFF19418F) to Color(0xFF183877),
    dividerDash = 1 to 3, dividerWidth = 1, dividerShadow = Color(0xFFCECECE),
)

/**
 * Sampled from Sword and Shield Ultimate Plus' bag screen (FireRed's layout): a cream list with
 * dashed beige row lines and the vanilla grey text, framed charcoal / dark red / red / near-black
 * (the tabs: red with white text), the black description bar, a light icon box with a gold
 * edge, the orange pocket arrows (underline) and a flat light-grey backdrop.
 */
private val SwordShieldBag = BagPalette(
    panelLayers = listOf(Color(0xFF393939) to 2, Color(0xFFA50018) to 1, Color(0xFFD60021) to 3, Color(0xFF292929) to 2),
    tabLayerCount = 2,
    fill = Color(0xFFF7E7D6), divider = Color(0xFFD6CEC6),
    text = Color(0xFF636363), textShadow = Color(0xFFD6D6CE),
    tabText = Color(0xFFFFFFFF), tabTextShadow = Color(0xFF636363), tabUnderline = Color(0xFFFF5200),
    descLayers = listOf(Color(0xFF393939) to 1), descFill = Color(0xFF000000),
    descText = Color(0xFFFFFFFF), descTextShadow = Color(0xFF636363),
    iconBox = Color(0xFFDEDED6), iconBoxEdge = Color(0xFFEFB518),
    backdrop = Color(0xFFDEDED6) to Color(0xFFDEDED6),
)

/**
 * Sampled from GS Chronicles' bag screen (headless): its rows are light blue-grey pills in a
 * charcoal line with a grey inner edge, the vanilla grey text, the pocket title the same pill,
 * the navy description box with white text, a light icon box with a slate edge, the orange
 * pocket arrows (underline) and blue 2px stripes.
 */
private val GsChroniclesBag = BagPalette(
    panelLayers = listOf(Color(0xFF292929) to 1, Color(0xFFB5B5B5) to 1, Color(0xFF292929) to 1, Color(0xFFC6CEDE) to 1),
    tabLayerCount = 3,
    fill = Color(0xFFC6CEDE), divider = Color(0xFF292929),
    text = Color(0xFF636363), textShadow = Color(0xFFD6D6CE),
    tabText = Color(0xFF636363), tabTextShadow = Color(0xFFD6D6CE), tabUnderline = Color(0xFFFF5200),
    descLayers = listOf(Color(0xFF29394A) to 1), descFill = Color(0xFF001031),
    descText = Color(0xFFFFFFFF), descTextShadow = Color(0xFF636363),
    iconBox = Color(0xFFC6CEDE), iconBoxEdge = Color(0xFF29394A),
    backdrop = Color(0xFF08397B) to Color(0xFF004AA5),
    dividerDash = null, dividerWidth = 1, dividerShadow = Color(0xFFB5B5B5),
)

/**
 * Sampled from Odyssey's bag screen: light grey-cream rows split by solid
 * gold lines on a gold ground, the vanilla grey text, the dark pocket-title
 * box (the tabs: dark with white text, gold edge), the dark description bar,
 * a gold-edged light icon box, the orange pocket arrows (underline).
 */
private val OdysseyBag = BagPalette(
    panelLayers = listOf(Color(0xFFDDA839) to 2, Color(0xFF292929) to 2),
    tabLayerCount = 1,
    fill = Color(0xFFDEDED7), divider = Color(0xFFE6B743),
    text = Color(0xFF636363), textShadow = Color(0xFFD6D6CF),
    tabText = Color(0xFFFFFFFF), tabTextShadow = Color(0xFF636363), tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF292929) to 2), descFill = Color(0xFF393939),
    descText = Color(0xFFFFFFFF), descTextShadow = Color(0xFF636363),
    iconBox = Color(0xFFDEDED7), iconBoxEdge = Color(0xFFDDA839),
    backdrop = Color(0xFFE6B743) to Color(0xFFDDA839),
    dividerDash = null, dividerWidth = 2,
)

/**
 * Sampled from Emerald Rogue's bag (headless mGBA): Emerald's layout with a
 * navy / purple-grey frame around an amber band, a beige list, the amber
 * pocket banner, Emerald's white description and icon boxes, and navy stripes.
 */
private val RogueBag = BagPalette(
    panelLayers = listOf(Color(0xFF100042) to 2, Color(0xFF39315A) to 1, Color(0xFF636373) to 1, Color(0xFFEFB552) to 2),
    tabLayerCount = 3,
    fill = Color(0xFFEFDEBD), divider = null,
    text = Color(0xFF000000), textShadow = Color(0xFFD6D6CE),
    tabText = Color(0xFF000000), tabTextShadow = Color(0xFFBD9C5A), tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF7B7B7B) to 1), descFill = Color(0xFFFFFFFF),
    descText = Color(0xFF000000), descTextShadow = Color(0xFFD6D6CE),
    iconBox = Color(0xFFFFFFFF), iconBoxEdge = Color(0xFF7B7B7B),
    backdrop = Color(0xFF21087B) to Color(0xFF100042),
)

/**
 * Lazarus' and Seaglass' bag (both expansion's plain look): white windows in
 * a thin dark line, black text with no shadow, on flat cream.
 */
private val PlainExpansionBag = BagPalette(
    panelLayers = listOf(Color(0xFF293942) to 1, Color(0xFFFFFFFF) to 1),
    tabLayerCount = 1,
    fill = Color(0xFFFFFFFF), divider = null,
    text = Color(0xFF000000), textShadow = Color.Transparent,
    tabText = Color(0xFF000000), tabTextShadow = Color.Transparent, tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF293942) to 1), descFill = Color(0xFFFFFFFF),
    descText = Color(0xFF000000), descTextShadow = Color.Transparent,
    iconBox = Color(0xFFFFFFFF), iconBoxEdge = Color(0xFF293942),
    backdrop = Color(0xFFFFFFCE) to Color(0xFFFFFFCE),
)

/** R.O.W.E.'s bag: light grey windows in charcoal / grey lines, slate text with a grey shadow, on charcoal. */
private val RoweBag = BagPalette(
    panelLayers = listOf(Color(0xFF181818) to 1, Color(0xFFBDBDBD) to 1, Color(0xFFD6D6D6) to 1),
    tabLayerCount = 2,
    fill = Color(0xFFEFEFEF), divider = null,
    text = Color(0xFF39394A), textShadow = Color(0xFFA5A5A5),
    tabText = Color(0xFF39394A), tabTextShadow = Color(0xFFA5A5A5), tabUnderline = Color(0xFFEC5F2A),
    descLayers = listOf(Color(0xFF181818) to 1, Color(0xFFBDBDBD) to 1), descFill = Color(0xFFEFEFEF),
    descText = Color(0xFF39394A), descTextShadow = Color(0xFFA5A5A5),
    iconBox = Color(0xFFEFEFEF), iconBoxEdge = Color(0xFF181818),
    backdrop = Color(0xFF292929) to Color(0xFF292929),
)

/**
 * Sampled 1:1 from Super Mariomon's bag (headless, its MEDICINE pocket): a sky-blue list window -
 * a slate line, a light edge, a deeper blue band - with white names on a grey-brown shadow, a
 * white description box and icon box in grey lines, black text, the pocket banner's orange
 * arrows, and the screen's brown chevrons behind.
 */
private val MariomonBag = BagPalette(
    panelLayers = listOf(Color(0xFF293942) to 1, Color(0xFF9CBDFF) to 1, Color(0xFF638CDE) to 2),
    tabLayerCount = 2,
    fill = Color(0xFF9CBDFF), divider = null,
    text = Color.White, textShadow = Color(0xFF736B63),
    tabText = Color.White, tabTextShadow = Color(0xFF736B63), tabUnderline = Color(0xFFFF5200),
    descLayers = listOf(Color(0xFF7B7B7B) to 1), descFill = Color.White,
    descText = Color.Black, descTextShadow = Color(0xFFB5B5B5),
    iconBox = Color.White, iconBoxEdge = Color(0xFF7B7B7B),
    backdrop = Color(0xFFC69C7B) to Color(0xFFA57B63),
)

/**
 * Sampled from SoulGold's bag screen (headless): white list and description
 * windows in a charcoal / slate / blue frame, black text with a light grey
 * shadow, and its night sky behind - one still frame of the game's slowly
 * scrolling stars (RomArt's bagbg/soulgold.png, from the ROM).
 */
private val SoulGoldBag = BagPalette(
    panelLayers = listOf(Color(0xFF292929) to 2, Color(0xFF4A4A6B) to 1, Color(0xFF6373C6) to 1, Color(0xFF94A5DE) to 1),
    tabLayerCount = 3,
    fill = Color.White, divider = null,
    text = Color.Black, textShadow = Color(0xFFD6D6CE),
    tabText = Color.Black, tabTextShadow = Color(0xFFD6D6CE), tabUnderline = Color(0xFFEF5A29),
    descLayers = listOf(Color(0xFF292929) to 1, Color(0xFF4A4A6B) to 1), descFill = Color.White,
    descText = Color.Black, descTextShadow = Color(0xFFD6D6CE),
    iconBox = Color.White, iconBoxEdge = Color(0xFF292929),
    backdrop = Color(0xFF181842) to Color(0xFF181842),
    backdropArt = RomArt.BAG_STARS_SOULGOLD,
)

/**
 * HeartGold / SoulSilver's bag (headless, the player's saves): white item cells in a grey band and a dark grey line on
 * pink, dark grey names on a light shadow, the red cursor; the description in a grey box, white on a dark shadow.
 */
private val HeartGoldBag = BagPalette(
    panelLayers = listOf(Color(0xFF515151) to 2, Color(0xFFBABABA) to 2, Color(0xFFDBDBDB) to 1),
    tabLayerCount = 2,
    fill = Color(0xFFFBFBFB), divider = Color(0xFFDBDBDB),
    text = Color(0xFF515151), textShadow = Color(0xFFDBDBDB),
    tabText = Color(0xFF515151), tabTextShadow = Color(0xFFDBDBDB), tabUnderline = Color(0xFFE30000),
    descLayers = listOf(Color(0xFF000000) to 1, Color(0xFF717179) to 1), descFill = Color(0xFF92929A),
    descText = Color(0xFFFBFBFB), descTextShadow = Color(0xFF515959),
    iconBox = Color(0xFFFBFBFB), iconBoxEdge = Color(0xFF515151),
    backdrop = Color(0xFFDB82AA) to Color(0xFFCB6992),
)

/**
 * HeartGold / SoulSilver's bag takes the open pocket's colours (its touch screen's BG palette per pocket, bag graphics
 * member 41's colours 4-6): the backdrop, its stripe, and the darkest under the pocket buttons' band.
 */
private val HGSS_POCKET_COLORS = listOf(
    Triple(0xFFDB82AA, 0xFFCB6992, 0xFFBA5179), Triple(0xFFEB8251, 0xFFE37138, 0xFFDB6128),
    Triple(0xFFE3A238, 0xFFCB8A20, 0xFFBA7108), Triple(0xFF92C330, 0xFF82AA20, 0xFF719218),
    Triple(0xFF30BA59, 0xFF20A249, 0xFF188230), Triple(0xFF18AAD3, 0xFF1892B2, 0xFF208292),
    Triple(0xFF4982F3, 0xFF3071E3, 0xFF2059CB), Triple(0xFFA259EB, 0xFF9249DB, 0xFF7938C3),
).map { (a, b, c) -> Triple(Color(a), Color(b), Color(c)) }

// Flat, as the game's bag is (the stripes were the other games' look).
private val hgssBags = HGSS_POCKET_COLORS.map { (main, _, _) -> HeartGoldBag.copy(backdrop = main to main) }

/** What HeartGold / SoulSilver's backdrop follows: the open pocket (0-7, the game's order) and the pocket buttons' row -
 * its top in root pixels and screen pixels per game pixel - for the silver band behind it. [HgssBagBody] sets both. One
 * per companion ([LocalHgssBag]): the bottom screen's and the window's are up at once, and a shared one flickered between
 * their pockets. */
internal class HgssBagState {
    var pocket by mutableStateOf(0)
    var band by mutableStateOf<Pair<Float, Float>?>(null)
}

/** This companion's [HgssBagState] (CompanionScreen provides it). */
internal val LocalHgssBag = androidx.compose.runtime.staticCompositionLocalOf<HgssBagState?> { null }

/**
 * Pokémon Yellow (Game Boy): Gen 1's ITEM window - black text on white inside the
 * text box's double line (black, white, black), no shadows, no row lines.
 */
private val YellowBag = BagPalette(
    panelLayers = listOf(Color(0xFF181818) to 1, Color.White to 1, Color(0xFF181818) to 1, Color.White to 1),
    tabLayerCount = 3,
    fill = Color.White, divider = null,
    text = Color(0xFF181818), textShadow = Color.Transparent,
    tabText = Color(0xFF181818), tabTextShadow = Color.Transparent, tabUnderline = Color(0xFF181818),
    descLayers = listOf(Color(0xFF181818) to 1, Color.White to 1, Color(0xFF181818) to 1), descFill = Color.White,
    descText = Color(0xFF181818), descTextShadow = Color.Transparent,
    iconBox = Color.White, iconBoxEdge = Color(0xFF181818),
    backdrop = Color.White to Color.White,
)

/**
 * Pokémon Crystal (Game Boy Color): its PACK, sampled headless - Yellow's black-on-white text boxes,
 * the cursor's red under the pocket, and the PACK screen's blue checks (two blues) behind it.
 */
private val CrystalBag = YellowBag.copy(
    tabUnderline = Color(0xFFFF0000),
    backdrop = Color(0xFF7B7BFF) to Color(0xFF0000FF),
)

/**
 * Pokémon Crystal Clear: the same text boxes in its PACK's red (the player picks the PACK's colour at the start; red is
 * the default, sampled headless): the cursor, and the red checks of the PACK's side panel behind it.
 */
private val CrystalClearBag = YellowBag.copy(
    tabUnderline = Color(0xFFFF0000),
    backdrop = Color(0xFFFF0000) to Color(0xFFAD0000),
)

/**
 * Pokémon Polished Crystal: its bag, sampled headless - Crystal's text boxes and red cursor over the magenta checks of its
 * side panel (the same on every pocket).
 */
private val PolishedCrystalBag = YellowBag.copy(
    tabUnderline = Color(0xFFFF0000),
    backdrop = Color(0xFFFF73FF) to Color(0xFFFF39FF),
)

/** Pokémon Red / Blue: Yellow's ITEM window in the Super Game Boy's white and black (sampled headless). */
private val RedBlueBag = YellowBag.copy(
    panelLayers = listOf(Color(0xFF181010) to 1, Color(0xFFFFEFFF) to 1, Color(0xFF181010) to 1, Color(0xFFFFEFFF) to 1),
    fill = Color(0xFFFFEFFF),
    text = Color(0xFF181010), tabText = Color(0xFF181010), tabUnderline = Color(0xFF181010),
    descLayers = listOf(Color(0xFF181010) to 1, Color(0xFFFFEFFF) to 1, Color(0xFF181010) to 1), descFill = Color(0xFFFFEFFF),
    descText = Color(0xFF181010),
    iconBox = Color(0xFFFFEFFF), iconBoxEdge = Color(0xFF181010),
    backdrop = Color(0xFFFFEFFF) to Color(0xFFFFEFFF),
)

/** PureRed / PureBlue (on a Color, headless): Yellow's ITEM window in pure black on white. */
private val PureRgbBag = YellowBag.copy(
    panelLayers = listOf(Color.Black to 1, Color.White to 1, Color.Black to 1, Color.White to 1),
    text = Color.Black, tabText = Color.Black, tabUnderline = Color.Black,
    descLayers = listOf(Color.Black to 1, Color.White to 1, Color.Black to 1),
    descText = Color.Black, iconBoxEdge = Color.Black,
)

/** Pokémon Ghost Grey's bag (headless): Yellow's black-on-white boxes with its grey row lines, on white. */
private val GhostGreyBag = YellowBag.copy(
    panelLayers = listOf(Color.Black to 1, Color.White to 1, Color.Black to 1, Color.White to 1),
    divider = Color(0xFFADADAD),
    text = Color.Black, tabText = Color.Black, tabUnderline = Color.Black,
    descLayers = listOf(Color.Black to 1, Color.White to 1, Color.Black to 1), descText = Color.Black,
    iconBoxEdge = Color.Black,
)

/**
 * Pokémon Black's bag (headless, its touch screen): white names on charcoal rows in a grey line, the blue between them,
 * a blue description panel with a pale icon box (its top screen), all over its blue dotted screen.
 */
private val BlackBag = BagPalette(
    panelLayers = listOf(Color(0xFF282828) to 1, Color(0xFFB2B2B2) to 1, Color(0xFF282828) to 1, Color(0xFF616161) to 2),
    tabLayerCount = 3,
    fill = Color(0xFF303030), divider = Color(0xFF2069D3), dividerDash = null,
    text = Color(0xFFFBFBFB), textShadow = Color(0xFF616161),
    tabText = Color(0xFFFBFBFB), tabTextShadow = Color(0xFF282828), tabUnderline = Color(0xFF088AFB),
    descLayers = listOf(Color(0xFF004192) to 1, Color(0xFF1871E3) to 1), descFill = Color(0xFF0049A2),
    descText = Color(0xFFFBFBFB), descTextShadow = Color(0xFF7992AA),
    iconBox = Color(0xFFCBF3FB), iconBoxEdge = Color(0xFF71B2F3),
    backdrop = Color(0xFF2069D3) to Color(0xFF00519A), pattern = DsPattern.BLACK_BAG,
)

/**
 * Pokémon Platinum's bag (headless, a few items poked into the save's pocket): its white list in a grey-blue frame,
 * dark text with a grey shadow and no row lines, the pocket tab's orange, the purple description bar with white text,
 * over its touch screen's yellows.
 */
private val PlatinumBag = BagPalette(
    panelLayers = listOf(Color(0xFF717992) to 2, Color(0xFFBAD3DB) to 1, Color(0xFFDBEBEB) to 2),
    tabLayerCount = 2,
    fill = Color(0xFFFBFBFB), divider = null,
    text = Color(0xFF101820), textShadow = Color(0xFFAABABA),
    tabText = Color(0xFF101820), tabTextShadow = Color(0xFFAABABA), tabUnderline = Color(0xFFF3AA51),
    descLayers = listOf(Color(0xFF51619A) to 1, Color(0xFFA2AAF3) to 1), descFill = Color(0xFF7151F3),
    descText = Color(0xFFFBFBFB), descTextShadow = Color(0xFF303030),
    iconBox = Color(0xFFFBFBFB), iconBoxEdge = Color(0xFF717992),
    backdrop = Color(0xFFEBCB79) to Color(0xFFEBC369), pattern = DsPattern.PLATINUM_BAG,
)

/** Whether [ItemsBackdrop] replaces the party backdrop behind this game's Items tab. */
fun hasItemsBackdrop(game: GameKind): Boolean = bagPaletteFor(game).backdrop != null

/**
 * The game's own bag-screen backdrop - 2u horizontal stripes in two colors,
 * drawn natively - for games whose bag doesn't share the party menu's
 * backdrop (Emerald's purple/blue vs its olive party screen).
 */
@Composable
fun ItemsBackdrop(game: GameKind, modifier: Modifier = Modifier) {
    val hgss = LocalHgssBag.current
    val pal = bagPaletteFor(game, hgss?.pocket ?: 0)
    val (a, b) = pal.backdrop ?: return
    pal.pattern?.let { DsBackdrop(it, modifier); return }
    val m = rememberGbaTextMetrics()
    val context = LocalContext.current
    val gen = rememberArtGeneration()
    val art = remember(pal.backdropArt, gen) { pal.backdropArt?.let { GameArt.get(context, it)?.asImageBitmap() } }
    // Dark mode: an image backdrop (SoulGold's night sky) under the game backdrops' scrim; stripes come pre-dimmed.
    val scrim = OptionColors.backdropScrim
    // HeartGold / SoulSilver: the band behind the pocket buttons, laid out in root pixels by HgssBagBody.
    var origin by remember { mutableStateOf(0f) }
    val band = hgss?.band?.takeIf { game.isHgss }
    val shades = HGSS_POCKET_COLORS.getOrNull(hgss?.pocket ?: 0)
    Canvas(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot().y }
            .then(if (scrim > 0f && art != null) Modifier.drawWithContent { drawContent(); drawRect(NIGHT.copy(alpha = scrim)) } else Modifier)
            .then(if (band != null && shades != null) Modifier.drawWithContent { drawContent(); drawHgssBand(band.first - origin, band.second, pal, shades) } else Modifier),
    ) {
        if (art != null) {
            // Whole GBA pixels, tiled like the game's wrapping BG layer.
            val w = art.width * m.px
            val h = art.height * m.px
            var y = 0
            while (y < size.height) {
                var x = 0
                while (x < size.width) {
                    drawImage(art, dstOffset = IntOffset(x, y), dstSize = IntSize(w, h), filterQuality = FilterQuality.None)
                    x += w
                }
                y += h
            }
            return@Canvas
        }
        val stripe = 2 * m.px.toFloat()
        drawRect(b)
        var y = 0f
        while (y < size.height) {
            drawRect(a, Offset(0f, y), Size(size.width, stripe))
            y += 2 * stripe
        }
    }
}

/**
 * HeartGold / SoulSilver's band behind the bag's pocket buttons (its touch screen, headless): white from the top to the
 * buttons' middle, then silver (2 light, 12 mid) to 2 under them, a grey line and a dark one, then the
 * pocket's own two darker lines into its backdrop. [top] = the buttons' top, [k] screen pixels per game pixel.
 */
private fun DrawScope.drawHgssBand(top: Float, k: Float, pal: BagPalette, shades: Triple<Color, Color, Color>) {
    // White from the screen's top, as the game's header.
    drawRect(pal.fill, Offset.Zero, Size(size.width, top + 12 * k))
    var y = top + 12 * k
    fun line(c: Color, px: Int) { drawRect(c, Offset(0f, y), Size(size.width, px * k)); y += px * k }
    line(pal.panelLayers[2].first, 2)
    line(pal.panelLayers[1].first, 12)
    line(Color(0xFF8A8A8A).let { if (OptionColors.dark) it.copy(alpha = 0.6f) else it }, 2)
    line(pal.panelLayers[0].first, 2)
    line(shades.third, 2)
    line(shades.second, 2)
}

/** Same grouping as the companion backdrop (rememberGameBackground). */
private fun bagPaletteFor(game: GameKind, hgssPocket: Int = 0): BagPalette =
    // Gen 1's white goes nearly black, like its party backdrop (GameBackdrop).
    lightBagFor(game, hgssPocket).let { if (OptionColors.dark) nightBags.getOrPut(it) { it.night(if (game.isGameBoyGame) 0.9f else OptionColors.backdropScrim) } else it }

private fun lightBagFor(game: GameKind, hgssPocket: Int = 0): BagPalette = when (game) {
    GameKind.EMERALD -> EmeraldBag
    GameKind.HEART_AND_SOUL -> HeartAndSoulBag
    GameKind.UNBOUND -> UnboundBag
    GameKind.AMETHYST -> AmethystBag
    GameKind.RADICAL_RED -> RadicalRedBag
    GameKind.ODYSSEY -> OdysseyBag
    GameKind.SWORD_SHIELD -> SwordShieldBag
    GameKind.GS_CHRONICLES -> GsChroniclesBag
    GameKind.EMERALD_ROGUE -> RogueBag
    GameKind.LAZARUS, GameKind.EMERALD_SEAGLASS -> PlainExpansionBag
    GameKind.ROWE -> RoweBag
    GameKind.SOULGOLD -> SoulGoldBag
    GameKind.YELLOW, GameKind.YELLOW_LEGACY -> YellowBag
    GameKind.RED, GameKind.BLUE -> RedBlueBag
    GameKind.PURE_RED, GameKind.PURE_BLUE -> PureRgbBag
    // Gold's and Silver's PACK is Crystal's, pixel for pixel (sampled headless in both).
    GameKind.CRYSTAL, GameKind.GOLD, GameKind.SILVER, GameKind.CRYSTAL_LEGACY -> CrystalBag
    GameKind.CRYSTAL_CLEAR -> CrystalClearBag
    // Prism's PACK: Crystal's blue checks and text boxes.
    GameKind.PRISM -> CrystalBag
    GameKind.POLISHED_CRYSTAL -> PolishedCrystalBag
    GameKind.MARIOMON -> MariomonBag
    GameKind.MONOMYTH -> MonomythBag
    GameKind.HEARTGOLD, GameKind.SOULSILVER -> hgssBags.getOrElse(hgssPocket) { HeartGoldBag }
    GameKind.PLATINUM -> PlatinumBag
    GameKind.BLACK, GameKind.WHITE, GameKind.BLACK2, GameKind.WHITE2 -> BlackBag
    // Too Many Types 2's, Glazed's, Imperium's and Quetzal's bags are Emerald's; Gaia's and Celia's are FireRed's.
    // Elite Redux's and Team Rocket Edition: Dragonsden's bag screens are Emerald's too.
    // Dreamstone Mysteries' is Emerald's (headless: its bag screen in battle, seven pockets).
    GameKind.DREAMSTONE,
    GameKind.TMT2, GameKind.GLAZED, GameKind.IMPERIUM, GameKind.QUETZAL, GameKind.ELITE_REDUX, GameKind.TEAM_ROCKET,
    GameKind.WISHES -> EmeraldBag
    GameKind.FIRERED -> if (com.pokedaisy.app.companion.data.runningGhostGrey) GhostGreyBag else FireRedBag
    else -> FireRedBag
}

@Composable
fun ItemsScreen(
    items: List<ItemView>,
    modifier: Modifier = Modifier,
    itemUse: com.pokedaisy.app.companion.ItemUse? = null,
    /** A USE / GIVE result popped up first - for screenshot tests. */
    initialNotice: ItemNotice? = null,
    /** The item whose description is open first - for screenshot tests, which can't tap. */
    initialItemId: Int? = null,
    /** More under an open item's description, in the bar's text colours (a TM's party row). */
    itemExtra: (@Composable (item: ItemView, text: Color, shadow: Color) -> Unit)? = null,
    /** TM / HM items' moves, shown after their names ("TM06 TOXIC") as the games' TM cases do. */
    tmMoves: Map<Int, String> = emptyMap(),
    /** The party, for USE on a Pokémon and GIVE (the PARTY tab's slots, [ItemPartyPicker]). */
    party: List<MonView> = emptyList(),
    /** Opens [initialItemId]'s party pick first - false USE, true GIVE - for screenshot tests, which can't tap. */
    initialPick: Boolean? = null,
) {
    var selectedCategory by remember { mutableStateOf<Int?>(null) } // null = All, FAVORITES_TAB = the starred items
    var sort by remember { mutableStateOf(ItemSort.DEFAULT) }
    var selectedItem by remember { mutableStateOf(items.firstOrNull { it.itemId == initialItemId }) }
    var selectedKey by remember { mutableStateOf(selectedItem?.let { "${it.itemId}#0" }) }
    // A D-pad's A and a tap mean different things on the selected item (USE / GIVE vs closing it).
    val inputModes = androidx.compose.ui.platform.LocalInputModeManager.current
    val actionFocus = remember { FocusRequester() }
    val hgssState = LocalHgssBag.current ?: remember { HgssBagState() }
    val pal = bagPaletteFor(activeGame, hgssState.pocket)
    val caps = gameItemNames(activeGame)
    val shown = items.map { item ->
        val name = caps?.get(item.itemId) ?: item.name
        item.copy(name = tmMoves[item.itemId]?.let { "$name $it" } ?: name)
    }
    val m = rememberGbaTextMetrics()
    val u = m.u
    // What the last USE / GIVE did, for the row it was done from (its key): shown in the description bar
    // while that row is open.
    var messageFor by remember { mutableStateOf<Pair<String?, String>?>(null) }
    val message = messageFor?.takeIf { it.first == selectedKey }?.second
    fun say(text: String) { messageFor = selectedKey to text }
    // A USE / GIVE that went through also pops up over the bag for a moment, with the item: the description bar
    // alone was easy to miss (and an item used to the last leaves the bag, taking its bar with it).
    var notice by remember { mutableStateOf<ItemNotice?>(initialNotice) }
    fun report(o: ItemUseOutcome, itemId: Int, text: String) {
        // IN_GAME (a TM: the game's own teach screen) pops up only: kept in the bar, it still asked for a pick after the
        // game's screen had closed, cancelled or not, until another item was opened.
        if (o.result != ItemUseResult.IN_GAME) say(text)
        if (o.result == ItemUseResult.USED || o.result == ItemUseResult.GIVEN || o.result == ItemUseResult.IN_GAME) notice = ItemNotice(itemId, text)
    }
    // The party pick a USE (medicine) or GIVE opened, over the bag.
    var pick by remember { mutableStateOf<ItemPick?>(null) }
    fun startPick(item: ItemView, give: Boolean) {
        val use = itemUse ?: return
        use.targets(item.itemId, give) { t ->
            if (t.result == ItemUseResult.USED) pick = ItemPick(item, give, t.targets)
            else say(itemUseMessage(ItemUseOutcome(t.result)))
        }
    }
    // In the first composition, before anything reads the pick: a screenshot draws one frame.
    remember { if (initialPick != null) selectedItem?.let { startPick(it, initialPick) }; true }

    // Only categories the bag actually has something in - an empty pocket
    // (e.g. no Key Items exported by a pre-v4 ROM) shouldn't show as a dead
    // tab with nothing behind it.
    val pocketTabs = activePocketOrder
    val categoriesPresent = pocketTabs.filter { p -> items.any { it.pocket == p } }
    val favorites = FavoriteItems.ids
    // FAVORITES under ALL, only while the bag holds a starred item.
    val hasFavorites = items.any { it.itemId in favorites }
    // Unstarring the last one closes the tab: back to ALL.
    LaunchedEffect(hasFavorites) { if (!hasFavorites && selectedCategory == FAVORITES_TAB) selectedCategory = null }
    // HeartGold / SoulSilver: the game's eight pockets, always, opening on ITEMS - no ALL (HgssBagBody).
    val hgss = activeGame.isHgss
    val category = (selectedCategory ?: POCKET_ITEMS.takeIf { hgss }).takeUnless { it == FAVORITES_TAB && !hasFavorites }
        ?: POCKET_ITEMS.takeIf { hgss }
    // A bag of one pocket (Gen 1's single list) needs no tab besides ALL (and FAVORITES).
    val tabs = if (hgss) HGSS_POCKETS + listOfNotNull(FAVORITES_TAB.takeIf { hasFavorites })
    else listOf<Int?>(null) + listOfNotNull(FAVORITES_TAB.takeIf { hasFavorites }) +
        categoriesPresent.takeIf { it.size > 1 }.orEmpty()
    val capsLabels = capsBagLabels(activeGame)
    val tabLabels = tabs.map { if (capsLabels) capsPocketLabel(it) else titlePocketLabel(it) }
    val sortLabel = { s: ItemSort -> tr(s.label).let { if (capsLabels) it.uppercase() else it } }

    val filtered = when (category) {
        null -> shown
        FAVORITES_TAB -> shown.filter { it.itemId in favorites }
        else -> shown.filter { it.pocket == category }
    }
    val sorted: List<ItemView> = when (sort) {
        // A stable sort: starred items keep the bag's order among themselves, as do the rest.
        ItemSort.DEFAULT -> filtered.sortedBy { it.itemId !in favorites }
        ItemSort.NAME -> filtered.sortedBy { it.name }
        ItemSort.COUNT -> filtered.sortedWith(compareByDescending<ItemView> { it.quantity }.thenBy { it.name })
        ItemSort.TYPE -> filtered.sortedWith(
            compareBy<ItemView> { pocketTabs.indexOf(it.pocket).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.name },
        )
    }

    // Tab column is as wide as its widest label (incl. the sort button's,
    // which also carries its icon).
    val font = pixelFontFamily()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scaleX = gameFontScaleX(m)
    val tabWidth = remember(tabLabels, capsLabels, m, font, scaleX) {
        val style = gbaTextStyle(font, m, scaleX)
        val widestTab = tabLabels.maxOf { measurer.measure(it, style).size.width }
        val widestSort = ItemSort.entries.maxOf { measurer.measure(sortLabel(it), style).size.width }
        with(density) {
            maxOf(
                widestTab.toDp() + u * 20, // FolderTab's 10u side padding (frame included)
                widestSort.toDp() + u * (17 + SORT_ICON_U + 4),
            )
        }
    }
    val panelFrame = u * pal.panelFrame

    // A row's key: the item, and which stack of it this is - the bag can hold
    // the same item twice (99 Repels and 5 more), and LazyColumn throws on a
    // repeated key.
    val keys = remember(sorted) {
        val seen = HashMap<Int, Int>()
        sorted.map { "${it.itemId}#${seen.merge(it.itemId, 1, Int::plus)!! - 1}" }
    }

    // Keep the tapped row on screen once the window shrinks for the
    // description box.
    val listState = rememberLazyListState()
    // The description box overlays the bottom of the whole tab; the window
    // shrinks by its (measured) height so no row hides behind it.
    var descHeight by remember { mutableStateOf(0.dp) }
    // A star moves the open item to the top (or back down): follow it there too.
    LaunchedEffect(selectedItem, favorites) {
        val index = keys.indexOf(selectedKey)
        if (index < 0) return@LaunchedEffect
        // Two frames: the box measures itself, then the window shrinks to fit.
        withFrameNanos { }
        withFrameNanos { }
        val visible = listState.layoutInfo.visibleItemsInfo
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        if (visible.none { it.index == index && it.offset + it.size <= viewportEnd }) {
            listState.animateScrollToItem(index)
        }
    }
    // A new order starts from the top of the list.
    LaunchedEffect(sort) { listState.scrollToItem(0) }

    pick?.let { p ->
        ItemPartyPicker(
            p, party, m, modifier,
            itemName = caps?.get(p.item.itemId) ?: p.item.name,
            picksMove = !p.give && itemUse?.picksMove(p.item.itemId) == true,
            onCancel = { pick = null },
            onPick = { target, move ->
                val use = itemUse
                val name = party.getOrNull(target.slot)?.name.orEmpty()
                val done = { o: ItemUseOutcome ->
                    pick = null
                    report(o, p.item.itemId, itemUseMessage(o, mon = name, item = bagItemName(p.item.itemId), held = target.heldItem))
                }
                when {
                    use == null -> pick = null
                    p.give -> use.give(p.item.itemId, target, done)
                    else -> use.useOn(p.item.itemId, target, move, done)
                }
            },
        )
        return
    }

    Box(modifier = modifier) {
        // HeartGold / SoulSilver: the game's own bag layout - the pockets as icon tabs along the top, the items as cards in
        // two columns (HgssBagBody).
        if (hgss) HgssBagBody(
            tabs = tabs, category = category, onTab = { selectedCategory = it; selectedItem = null },
            items = sorted, keys = keys, empty = if (items.isEmpty()) tr("No items") else tr("No items in this pocket"),
            selectedKey = selectedKey.takeIf { selectedItem != null }, favorites = favorites,
            marked = { itemUse?.marked(it) == true }, pal = pal, m = m,
            sortLabel = sortLabel(sort), onSort = { sort = ItemSort.entries[(sort.ordinal + 1) % ItemSort.entries.size] },
            onPc = itemUse?.takeIf { it.canPc() }?.let { use ->
                { use.openPc { o -> if (o.result != ItemUseResult.USED) notice = ItemNotice(0, itemUseMessage(o), ok = false) } }
            },
            bottomSpace = if (selectedItem != null) descHeight + u * 2 else 0.dp,
            state = hgssState,
        ) { item, key ->
            if (selectedItem != null && key == selectedKey) {
                if (inputModes.inputMode == androidx.compose.ui.input.InputMode.Keyboard) runCatching { actionFocus.requestFocus() }
                else selectedItem = null
            } else {
                selectedItem = item
                selectedKey = key
            }
        } else {
        // List window, starting where the folder tabs end.
        Column(Modifier.fillMaxSize().padding(start = tabWidth)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .drawBehind { drawLayeredBox(pal.panelLayers.inPx(u.toPx()), pal.fill, radius = 4 * u.toPx()) }
                    .padding(panelFrame + u * 3),
            ) {
                if (items.isEmpty() || sorted.isEmpty()) {
                    GbaText(
                        if (items.isEmpty()) tr("No items") else tr("No items in this pocket"),
                        pal.text, pal.textShadow, m,
                        modifier = Modifier.padding(start = u * 8, top = u * 2),
                    )
                } else Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        itemsIndexed(sorted, key = { i, _ -> keys[i] }) { i, item ->
                            val key = keys[i]
                            ItemRow(
                                item, pal, m, selected = selectedItem != null && key == selectedKey, favorite = item.itemId in favorites,
                                marked = itemUse?.marked(item.itemId) == true,
                                // With none picked, the D-pad starts on the first item, like the game's bag cursor.
                                start = selectedItem == null && i == 0,
                                // The D-pad (the companion's window): the cursor picks the item as it moves, as in
                                // the game's bag, its description showing. Only then: a mouse click focuses the row
                                // too, and picking it here first made the click close it again.
                                onFocus = {
                                    if (inputModes.inputMode == androidx.compose.ui.input.InputMode.Keyboard) {
                                        selectedItem = item
                                        selectedKey = key
                                    }
                                },
                            ) {
                                if (selectedItem != null && key == selectedKey) {
                                    // A on the item the cursor's on goes to its USE / GIVE; a tap closes it again.
                                    if (inputModes.inputMode == androidx.compose.ui.input.InputMode.Keyboard) {
                                        runCatching { actionFocus.requestFocus() }
                                    } else selectedItem = null
                                } else {
                                    selectedItem = item
                                    selectedKey = key
                                }
                            }
                        }
                    }
                    // What the Poké Ball beside a count means, whenever the list has one.
                    if (itemUse != null && sorted.any { itemUse.marked(it.itemId) }) MarkLegend(pal)
                }
            }

            if (selectedItem != null) Spacer(Modifier.height(descHeight + u * 2))
        }

        // Folder tabs: ALL (flush with the window's top edge) stays put, and so do the PC and sort buttons pinned at
        // the bottom; on a screen too short for every tab (the Thor's, with 6+ pockets) only the tabs between them
        // scroll.
        Column(Modifier.width(tabWidth + panelFrame).fillMaxHeight()) {
            val tab: @Composable (Int) -> Unit = { i ->
                FolderTab(
                    tabLabels[i], pal, m,
                    selected = category == tabs[i],
                    first = i == 0,
                    tabWidth = tabWidth, panelFrame = panelFrame,
                ) {
                    selectedCategory = tabs[i]
                    selectedItem = null
                }
                Spacer(Modifier.height(u * 2))
            }
            if (tabs.isNotEmpty()) tab(0)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (i in 1 until tabs.size) tab(i)
            }
            Column(verticalArrangement = Arrangement.spacedBy(u * 4), modifier = Modifier.padding(top = u * 2)) {
                // PC anywhere (GitHub #85): the game's own PC over the field; a "not now" pops up over the bag.
                if (itemUse?.canPc() == true) {
                    PcButton(pal, m, width = tabWidth - u * 4) {
                        itemUse.openPc { o -> if (o.result != ItemUseResult.USED) notice = ItemNotice(0, itemUseMessage(o), ok = false) }
                    }
                }
                SortButton(sortLabel(sort), pal, m, width = tabWidth - u * 4) {
                    sort = ItemSort.entries[(sort.ordinal + 1) % ItemSort.entries.size]
                }
            }
        }
        }

        // Full width of the tab, over the bottom of the folder tabs / sort
        // button when it has to be.
        ItemNoticeHost(notice, m, Modifier.align(Alignment.TopCenter).padding(top = u * 6, start = u * 8, end = u * 8)) { notice = null }
        selectedItem?.let { item ->
            CompanionBackHandler { selectedItem = null }
            ItemDescriptionBar(
                item, pal, m,
                onDismiss = { selectedItem = null },
                favorite = item.itemId in favorites,
                onFavorite = { FavoriteItems.toggle(item.itemId) },
                actionFocus = actionFocus,
                message = message,
                // An item used or given away to the last leaves the bag: nothing left to tap.
                onUse = itemUse?.takeIf { it.canUse(item.itemId) && items.any { i -> i.itemId == item.itemId } }?.let { use ->
                    {
                        if (use.picksMon(item.itemId)) startPick(item, give = false)
                        else use.use(item.itemId) { report(it, item.itemId, itemUseMessage(it)) }
                    }
                },
                onGive = itemUse?.takeIf { it.canGive(item.itemId) && items.any { i -> i.itemId == item.itemId } }?.let { { startPick(item, give = true) } },
                extra = itemExtra,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { descHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

/** HeartGold / SoulSilver's eight pockets in their bag's order (its pocket buttons, left to right: [NdsArt.heartGoldPocket]'s
 * 0-7). */
private val HGSS_POCKETS = listOf(
    POCKET_ITEMS, POCKET_MEDICINE, POCKET_POKE_BALLS, POCKET_TM_HM, POCKET_BERRIES, POCKET_MAIL, POCKET_BATTLE_ITEMS, POCKET_KEY_ITEMS,
)

/** Before a cart has given its art: each pocket's own kind of item, from the item icons. */
private fun hgssPocketItem(pocket: Int?): Int? = when (pocket) {
    POCKET_ITEMS -> 92          // NUGGET
    POCKET_MEDICINE -> 17       // POTION
    POCKET_POKE_BALLS -> 4      // POKé BALL
    POCKET_TM_HM -> 328         // TM01
    POCKET_BERRIES -> 155       // ORAN BERRY
    POCKET_MAIL -> 137          // GRASS MAIL
    POCKET_BATTLE_ITEMS -> 57   // X ATTACK
    POCKET_KEY_ITEMS -> 450     // BICYCLE
    else -> null
}

/** One of the bag's pocket buttons from the cart ([NdsArt.heartGoldPocket]), [open] or not; null before the art is in. */
@Composable
private fun hgssPocketArt(pocket: Int, open: Boolean): android.graphics.Bitmap? {
    val context = LocalContext.current
    val gen = rememberArtGeneration()
    return remember(pocket, open, gen) { GameArt.get(context, "${NdsArt.heartGoldPocket(pocket, open)}.png") }
}

/** The FAVORITES button in the pocket buttons' own look: a pocket button ([open] or not) with its icon painted out by
 * the fill each row has at its left edge, cached per button. */
private val hgssBlankButtons = java.util.concurrent.ConcurrentHashMap<android.graphics.Bitmap, android.graphics.Bitmap>()

private fun hgssBlankButton(button: android.graphics.Bitmap): android.graphics.Bitmap = hgssBlankButtons.getOrPut(button) {
    val w = button.width
    val px = IntArray(w * button.height)
    button.getPixels(px, 0, w, 0, 0, w, button.height)
    val inset = 3
    for (y in inset until button.height - inset) {
        val fill = px[y * w + inset]
        for (x in inset until w - inset) px[y * w + x] = fill
    }
    android.graphics.Bitmap.createBitmap(px, w, button.height, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * HeartGold / SoulSilver's bag (its touch screen, headless): the eight pocket buttons in a row along the top - the
 * cart's own, the open one lit - then the pocket's items as cards in two columns (the item's icon, its name and "×count"
 * under it, the open one in a red line), SORT and PC along the bottom where the game has its page number and CANCEL.
 * FAVORITES (a star in the buttons' look) closes the row while the bag holds a starred item; there's no ALL.
 */
@Composable
private fun HgssBagBody(
    tabs: List<Int?>, category: Int?, onTab: (Int?) -> Unit,
    items: List<ItemView>, keys: List<String>, empty: String,
    selectedKey: String?, favorites: Set<Int>, marked: (Int) -> Boolean,
    pal: BagPalette, m: GbaTextMetrics,
    sortLabel: String, onSort: () -> Unit, onPc: (() -> Unit)?,
    bottomSpace: Dp,
    state: HgssBagState,
    onClick: (ItemView, String) -> Unit,
) {
    val u = m.u
    val small = rememberGbaTextMetrics(0.8f)
    val density = LocalDensity.current
    // The backdrop follows the open pocket and draws the band behind the buttons; both go when the bag does.
    val gamePocket = HGSS_POCKETS.indexOf(category)
    SideEffect { if (gamePocket >= 0) state.pocket = gamePocket }
    DisposableEffect(state) { onDispose { state.band = null } }
    Column(Modifier.fillMaxSize()) {
        // The game's 24-pixel buttons at as many whole screen pixels per game pixel as leave 2 pixels between them,
        // spread across the band with a small margin at its ends.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slotPx = with(density) { maxWidth.toPx() } / maxOf(tabs.size, 8)
        val buttonPx = maxOf(1, (slotPx / 26).toInt()) * 24
        val button = with(density) { buttonPx.toDp() }
        val k = buttonPx / 24f
        Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = with(density) { (4 * k).toDp() }, start = with(density) { (3 * k).toDp() }, end = with(density) { (3 * k).toDp() })
                .onGloballyPositioned { state.band = it.positionInRoot().y to k },
        ) {
            for (t in tabs) {
                val selected = category == t
                val noRipple = remember { MutableInteractionSource() }
                val focused by noRipple.collectIsFocusedAsState()
                val gamePocket = HGSS_POCKETS.indexOf(t)
                val art = hgssPocketArt(if (gamePocket >= 0) gamePocket else 0, selected)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(button)
                        .gbaFocusRing(focused, PixelRoundedShape(button / 6))
                        .soundClickable(interactionSource = noRipple, indication = null) { onTab(t) }
                        // Pictures only: the pocket's name for TalkBack (and the previews' taps).
                        .semantics { contentDescription = titlePocketLabel(t) },
                ) {
                    when {
                        art != null -> Image(
                            bitmap = (if (gamePocket >= 0) art else hgssBlankButton(art)).asImageBitmap(), contentDescription = null,
                            filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize(),
                        )
                        // No cart art yet: a drawn button, the item standing in for the pocket's icon.
                        else -> Box(
                            Modifier.fillMaxSize().drawBehind {
                                val px = u.toPx()
                                drawPixelRoundRect(if (selected) pal.tabUnderline else pal.panelLayers[0].first, radius = 4 * px)
                                drawPixelRoundRect(
                                    if (selected) pal.fill else pal.panelLayers.last().first,
                                    Offset(2 * px, 2 * px), Size(size.width - 4 * px, size.height - 4 * px), radius = 3 * px,
                                )
                            },
                        )
                    }
                    if (t == FAVORITES_TAB) {
                        // The star about as big as the pockets' icons (~15 of the button's 24 pixels), whole screen pixels.
                        val cell = with(density) { maxOf(1, buttonPx * 15 / 24 / 10).toDp() }
                        ShadowedPixelIcon(PixelIcons.star, OptionColors.favorite, pal.textShadow, m, cell = cell)
                    } else if (art == null) {
                        hgssPocketItem(t)?.let { ItemIcon(spriteAsset("items", it), size = button * 5 / 6) }
                    }
                }
            }
        }
        // Past the band (2 pixels of silver, its two lines and the pocket's two) and a gap.
        Spacer(Modifier.height(with(density) { (14 * k).toDp() }))
        }
        }
        if (items.isEmpty()) {
            GbaText(empty, pal.text, pal.textShadow, m, modifier = Modifier.weight(1f).padding(start = u * 8, top = u * 4))
        } else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(u * 4),
            verticalArrangement = Arrangement.spacedBy(u * 4),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(items.size, key = { keys[it] }) { i ->
                val item = items[i]
                val key = keys[i]
                HgssItemCard(item, pal, m, small, selected = key == selectedKey, favorite = item.itemId in favorites,
                    marked = marked(item.itemId), start = selectedKey == null && i == 0) { onClick(item, key) }
            }
        }
        // The game's bottom row (page number, CANCEL): the bag's order and the PC.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = u * 4),
        ) {
            SortButton(sortLabel, pal, small, width = u * 64, onClick = onSort)
            Spacer(Modifier.weight(1f))
            if (onPc != null) PcButton(pal, small, width = u * 34, onClick = onPc)
        }
        Spacer(Modifier.height(bottomSpace))
    }
}

/** A machine in HeartGold / SoulSilver's bag: its move and number (1-92 a TM, 1-8 an HM), or null. */
private fun hgssMachine(itemId: Int): Triple<String, Boolean, Int>? {
    // Origin HeartGold: 130 TMs, its own moves (TM93 on are later items), from its cart.
    if (originHeartGold) return OriginHeartGold.machine(itemId)?.let { (move, hm, n) -> Triple(lookupMove(move).name, hm, n) }
    val i = itemId - HEARTGOLD_TM01
    if (i !in tmMovesHeartGold.indices) return null
    val hm = i >= 92
    return Triple(lookupMove(tmMovesHeartGold[i]).name, hm, if (hm) i - 91 else i + 1)
}

/**
 * One of HeartGold / SoulSilver's item cards (their bag's touch screen, headless): a dark line (red, with a white one
 * inside, while open) round two tones - white, then silver under a light line, the pocket buttons' band's - the item's
 * icon large on the left, its name on the white and "×count" on the silver. A machine is its move's name over "No.45",
 * an HM "HM" in its blue tag with the number.
 */
@Composable
private fun HgssItemCard(
    item: ItemView, pal: BagPalette, m: GbaTextMetrics, small: GbaTextMetrics,
    selected: Boolean, favorite: Boolean, marked: Boolean, start: Boolean, onClick: () -> Unit,
) {
    val u = m.u
    // The companion's text size, drawn bold: a step up read too big, plain too thin.
    val big = rememberGbaTextMetrics()
    val density = LocalDensity.current
    val cell = with(density) { big.fontPixel.toDp() }
    val smallCell = with(density) { small.fontPixel.toDp() }
    val noRipple = remember { MutableInteractionSource() }
    // Each half a line of the bigger text and some room: the name on the white, the count on the silver.
    val half = big.lineHeight + u * 6
    val height = half * 2
    // The 32-pixel icon at a whole scale, about as tall as the card (its pictures sit well inside their 32).
    val icon = with(density) { (maxOf(1, minOf((height.toPx() * 0.85f / 32).roundToInt(), (height.toPx() / 32).toInt())) * 32).toDp() }
    val machine = remember(item.itemId) { hgssMachine(item.itemId) }
    Box(
        modifier = Modifier
            .focusStart(selected || start)
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val px = u.toPx()
                val r = 8 * px
                drawPixelRoundRect(if (selected) pal.tabUnderline else pal.panelLayers[0].first, radius = r)
                val inset = if (selected) 4 * px else 3 * px
                if (selected) drawPixelRoundRect(pal.fill, Offset(3 * px, 3 * px), Size(size.width - 6 * px, size.height - 6 * px), radius = r - 2 * px)
                val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
                // Silver below, the white over its top half, the light line between.
                drawPixelRoundRect(pal.panelLayers[1].first, Offset(inset, inset), inner, radius = r - inset / 2)
                val mid = half.toPx()
                drawPixelRoundRect(pal.fill, Offset(inset, inset), Size(inner.width, mid - inset), radius = r - inset / 2)
                drawRect(pal.fill, Offset(inset, mid - r), Size(inner.width, r))
                drawRect(pal.panelLayers[2].first, Offset(inset, mid), Size(inner.width, 2 * px))
            }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick),
    ) {
        // The same gap from the frame's inner edge to the picture as from the picture to the text: the picture centred
        // (by its own pixels) in a slot of the 24 of its 32 pixels the pictures fill.
        val gap = u * 6
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = u * 3 + gap)) {
            Box(Modifier.size(icon * 0.75f, icon), contentAlignment = Alignment.Center) {
                ItemIcon(item.iconAsset, size = icon, modifier = Modifier.wrapContentSize(unbounded = true), centerArt = true)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = gap, end = u * 8)) {
                // The name, centred on the white; a long one narrows its letters before it shrinks.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(half)) {
                    FittedGbaText(machine?.first ?: item.name, pal.text, pal.textShadow, big, modifier = Modifier.weight(1f, fill = false), bold = true)
                    if (favorite) {
                        ShadowedPixelIcon(PixelIcons.star, OptionColors.favorite, pal.textShadow, big, cell = cell, modifier = Modifier.padding(start = u * 3))
                    }
                }
                // The number or the count, centred on the silver.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(half)) {
                    when {
                        machine == null -> {
                            Spacer(Modifier.weight(1f))
                            if (marked) ShadowedPixelIcon(PixelIcons.itemAction, pal.text, pal.textShadow, small, cell = smallCell, modifier = Modifier.padding(end = u * 3))
                            GbaText("×${item.quantity}", pal.text, pal.textShadow, big, bold = true)
                        }
                        machine.second -> {
                            Box(
                                Modifier
                                    .drawBehind { drawPixelRoundRect(HM_TAG, radius = 2 * u.toPx()) }
                                    .padding(horizontal = u * 3),
                            ) { GbaText("HM", Color(0xFFFBFBFB), HM_TAG_SHADOW, m, bold = true) }
                            Spacer(Modifier.width(u * 2))
                            GbaText("${machine.third}", pal.text, pal.textShadow, big, bold = true)
                        }
                        else -> {
                            GbaText("No.${machine.third.toString().padStart(2, '0')}", pal.text, pal.textShadow, big, bold = true)
                            Spacer(Modifier.weight(1f))
                            GbaText("×${item.quantity}", pal.text, pal.textShadow, big, bold = true)
                        }
                    }
                }
            }
        }
    }
}

/** The HM tag on HeartGold / SoulSilver's machine cards, and its letters' shadow. */
private val HM_TAG = Color(0xFF7089AE)
private val HM_TAG_SHADOW = Color(0xFFA5B1C9)

@Composable
private fun FolderTab(
    label: String,
    pal: BagPalette,
    m: GbaTextMetrics,
    selected: Boolean,
    first: Boolean,
    tabWidth: Dp,
    panelFrame: Dp,
    onClick: () -> Unit,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    // Both are open on the right: an unselected tab ends flush against the
    // window's dark outline (tucked behind it). The selected one reaches onto
    // the window's gold frame so it reads as attached - its fill over the
    // window's outline + light line, its own dark border only over the
    // window's outline, so the two borders meet in a clean corner - and it
    // carries the orange underline the game draws under its pocket title.
    val reach = if (selected) pal.selectedFillReach else 0
    val focused by noRipple.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .width(tabWidth + u * reach)
            .drawBehind {
                val px = u.toPx()
                val radius = 4 * px
                val layers = pal.tabLayers.inPx(px)
                if (!selected) {
                    drawLayeredBox(layers, pal.tabFill, radius, openRight = true)
                    return@drawBehind
                }
                val windowEdge = tabWidth.toPx()
                val (border, borderWidth) = layers.first()
                val borderEnd = windowEdge + pal.selectedBorderReach * px
                clipRect(right = borderEnd) { drawLayeredBox(emptyList(), border, radius, openRight = true) }
                inset(left = borderWidth, top = borderWidth, right = 0f, bottom = borderWidth) {
                    drawLayeredBox(layers.drop(1), pal.tabFill, radius - borderWidth, openRight = true)
                }
                if (first) {
                    // Level with the window's top edge: run each border layer
                    // on past the window's rounded (4u) corner so each reads as
                    // one line, then square off the band's own leftover corner.
                    val cornerEnd = windowEdge + radius + px
                    var y = 0f
                    layers.forEachIndexed { i, (color, width) ->
                        val x = if (i == 0) borderEnd else size.width
                        drawRect(color, Offset(x, y), Size(cornerEnd - x, width))
                        y += width
                    }
                    val leftover = radius - y
                    if (leftover > 0f) drawRect(pal.tabFill, Offset(size.width, y), Size(leftover, leftover))
                }
            }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
            // The D-pad's focus (the companion's window), inside the tab.
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .padding(start = u * 10, end = u * (10 + reach), top = u * 6, bottom = u * 6),
    ) {
        // Tab-wide underline, drawn behind the label with its top half under
        // the glyphs' bottom edge (row 14 of the 16px em: baseline + shadow).
        GbaText(
            label, pal.tabText, pal.tabTextShadow, m,
            modifier = Modifier.fillMaxWidth().drawBehind {
                if (!selected) return@drawBehind
                val h = 4 * u.toPx()
                drawPixelRoundRect(
                    pal.tabUnderline, Offset(0f, 14 * m.fontPixel - h / 2), Size(size.width, h),
                    radius = h / 2,
                )
            },
        )
    }
}

/**
 * Deliberately not tab-shaped: a free-standing rounded button in the
 * description bar's colors, with a drawn up/down sort icon, pinned apart from
 * the tabs at the bottom of the column. Each tap moves to the next order.
 */
@Composable
private fun SortButton(label: String, pal: BagPalette, m: GbaTextMetrics, width: Dp, onClick: () -> Unit) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(start = u * 2)
            .width(width)
            .gbaFocusRing(focused, PixelRoundedShape(u * 6))
            .drawBehind {
                drawLayeredBox(pal.descLayers.inPx(u.toPx()), pal.descFill, radius = 6 * u.toPx(), twoTone = true)
            }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(horizontal = u * 6, vertical = u * 6),
    ) {
        SortIcon(pal, m)
        Spacer(Modifier.width(u * 4))
        GbaText(label, pal.descText, pal.descTextShadow, m)
    }
}

/** PC: the game's own PC (WITHDRAW, DEPOSIT...) opened on the field, styled like [SortButton]. */
@Composable
private fun PcButton(pal: BagPalette, m: GbaTextMetrics, width: Dp, onClick: () -> Unit) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(start = u * 2)
            .width(width)
            .gbaFocusRing(focused, PixelRoundedShape(u * 6))
            .drawBehind { drawLayeredBox(pal.descLayers.inPx(u.toPx()), pal.descFill, radius = 6 * u.toPx(), twoTone = true) }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick)
            .padding(horizontal = u * 6, vertical = u * 6),
    ) {
        GbaText(tr("PC"), pal.descText, pal.descTextShadow, m)
        // It opens the game's own PC, not a companion page (the HUD's PC has the same mark).
        Spacer(Modifier.width(u * 3))
        ShadowedPixelIcon(PixelIcons.inGame, pal.descText, pal.descTextShadow, m, cell = m.fontPixel.let { with(LocalDensity.current) { it.toDp() } })
    }
}

/** ▲ over ▼, in font pixels so it matches the text beside it. */
@Composable
private fun SortIcon(pal: BagPalette, m: GbaTextMetrics) {
    Canvas(Modifier.size(m.u * SORT_ICON_U, m.lineHeight)) {
        val fp = m.fontPixel
        val cx = size.width / 2
        fun tri(dx: Float, dy: Float, up: Boolean) = Path().apply {
            val (tip, base) = if (up) 2f to 7f else 15f to 10f
            moveTo(cx - 4 * fp + dx, base * fp + dy)
            lineTo(cx + 4 * fp + dx, base * fp + dy)
            lineTo(cx + dx, tip * fp + dy)
            close()
        }
        for (up in listOf(true, false)) {
            drawPath(tri(fp, fp, up), pal.descTextShadow)
            drawPath(tri(0f, 0f, up), pal.descText)
        }
    }
}

private const val SORT_ICON_U = 10


/** Cursor slot + name + "× qty" on the window's dashed row line, like the vanilla bag list. */
@Composable
private fun ItemRow(
    item: ItemView, pal: BagPalette, m: GbaTextMetrics, selected: Boolean, favorite: Boolean,
    /** The companion can USE / GIVE it here: the Poké Ball mark, in the list's own text colours. */
    marked: Boolean = false,
    /** The row took the D-pad's focus: the list's cursor goes to it ([selected] draws the bag's own cursor). */
    onFocus: () -> Unit = {},
    /** Where the D-pad starts with no item picked (the list's first). */
    start: Boolean = false,
    onClick: () -> Unit,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    val currentOnFocus by rememberUpdatedState(onFocus)
    LaunchedEffect(focused) { if (focused) currentOnFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusStart(selected || start)
            .fillMaxWidth()
            .height(m.rowHeight)
            .drawBehind {
                val divider = pal.divider ?: return@drawBehind
                val px = u.toPx()
                val w = pal.dividerWidth * px
                val shadow = pal.dividerShadow
                val y = size.height - w / 2 - (if (shadow != null) px else 0f)
                val dash = pal.dividerDash?.let { (on, off) -> PathEffect.dashPathEffect(floatArrayOf(on * px, off * px)) }
                if (shadow != null) {
                    drawLine(shadow, Offset(8 * px, y + (w + px) / 2), Offset(size.width, y + (w + px) / 2), strokeWidth = px, pathEffect = dash)
                }
                drawLine(divider, Offset(8 * px, y), Offset(size.width, y), strokeWidth = w, pathEffect = dash)
            }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick),
    ) {
        Box(Modifier.width(u * 8)) { if (selected) BagCursor(pal, m) }
        val cell = with(LocalDensity.current) { m.fontPixel.toDp() }
        // The gold star (the player's own) just before the name.
        if (favorite) {
            ShadowedPixelIcon(PixelIcons.star, OptionColors.favorite, pal.textShadow, m, cell = cell, modifier = Modifier.padding(end = u * 4))
        }
        GbaText(
            item.name, pal.text, pal.textShadow, m,
            modifier = Modifier.weight(1f),
        )
        // The mark before "× qty".
        if (marked) {
            ShadowedPixelIcon(PixelIcons.itemAction, pal.text, pal.textShadow, m, cell = cell, modifier = Modifier.padding(end = u * 4))
        }
        // Elite Redux's bag keeps no counts (an item is owned or not), and shows none.
        if (com.pokedaisy.app.companion.data.bagHasCounts) {
            GbaText("× ${item.quantity}", pal.text, pal.textShadow, m, modifier = Modifier.padding(end = u * 4))
        }
    }
}

/** The row mark's key, under the list: the Poké Ball, then what it means, smaller, in the list's own colours. */
@Composable
private fun MarkLegend(pal: BagPalette) {
    val small = rememberGbaTextMetrics(0.8f)
    val cell = with(LocalDensity.current) { small.fontPixel.toDp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = small.u * 8, top = small.u * 3, bottom = small.u),
    ) {
        ShadowedPixelIcon(PixelIcons.itemAction, pal.text, pal.textShadow, small, cell = cell)
        GbaText(tr("USE or GIVE it from here"), pal.text, pal.textShadow, small, modifier = Modifier.padding(start = small.u * 4))
    }
}

/** The vanilla ▶ list cursor (Pixel Operator has no ▶ glyph, so it's drawn). */
@Composable
private fun BagCursor(pal: BagPalette, m: GbaTextMetrics) {
    val u = m.u
    Canvas(Modifier.size(u * 8, m.lineHeight)) {
        val px = m.fontPixel // in the font's pixels so it matches the caps (rows 4..13 of the 16px em)
        fun tri(dx: Float, dy: Float) = Path().apply {
            moveTo(dx, 4 * px + dy)
            lineTo(dx + 5 * px, 8.5f * px + dy)
            lineTo(dx, 13 * px + dy)
            close()
        }
        drawPath(tri(px, px), pal.textShadow)
        drawPath(tri(0f, 0f), pal.text)
    }
}

/**
 * The vanilla bottom description bar - a two-tone edge, the item's icon in a
 * white box and white outlined text (never truncated) - as a fully framed
 * box across the whole tab.
 * Tapping it (like tapping the row again) dismisses it.
 */
@Composable
private fun ItemDescriptionBar(
    item: ItemView, pal: BagPalette, m: GbaTextMetrics, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    /** The item is starred; [onFavorite] stars / unstars it (the star button, beside USE). */
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    /** What the last USE / GIVE did, in place of the description. */
    message: String? = null,
    /** USE / GIVE buttons; null = none for this item here. */
    onUse: (() -> Unit)? = null,
    onGive: (() -> Unit)? = null,
    extra: (@Composable (item: ItemView, text: Color, shadow: Color) -> Unit)? = null,
    /** Where A on the list's selected item sends the D-pad: USE, else GIVE, else the star. */
    actionFocus: FocusRequester? = null,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    fun Modifier.action(first: Boolean) = if (first && actionFocus != null) focusRequester(actionFocus) else this
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawLayeredBox(pal.descLayers.inPx(u.toPx()), pal.descFill, radius = 4 * u.toPx())
            }
            // A tap anywhere closes it; not a D-pad stop (B does that, and its buttons are).
            .focusProperties { canFocus = false }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onDismiss)
            .padding(start = u * 7, end = u * 7, top = u * 7, bottom = u * 7),
    ) {
    // The row is as tall as its tallest part, so the right column can put the star at the top and USE / GIVE at the bottom.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.height(IntrinsicSize.Min)) {
        // No icon box where the game has no item icons (Gen 1): the text takes the room.
        val hasIcon = item.iconAsset != null
        if (hasIcon) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(u * 28)
                .drawBehind {
                    val px = u.toPx()
                    drawPixelRoundRect(pal.iconBoxEdge, radius = 3 * px)
                    drawPixelRoundRect(
                        pal.iconBox, Offset(px, px), Size(size.width - 2 * px, size.height - 2 * px),
                        radius = 2 * px,
                    )
                },
        ) {
            ItemIcon(item.iconAsset, size = u * 24)
        }
        GbaText(
            message ?: item.description.ifEmpty { item.name },
            pal.descText, pal.descTextShadow, m,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.padding(start = if (hasIcon) u * 8 else 0.dp).weight(1f),
        )
        if (onFavorite != null || onUse != null || onGive != null) {
            // The star in the card's top-right corner, USE / GIVE in its bottom-right one.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(u * 4),
                modifier = Modifier.fillMaxHeight().padding(start = u * 6),
            ) {
                if (onFavorite != null) {
                    // Gold while starred, the button's grey while not.
                    val label = tr(if (favorite) "UNFAVORITE" else "FAVORITE")
                    OptionButton(
                        null, m,
                        onClick = onFavorite,
                        pixelIcon = PixelIcons.star,
                        pixelIconColor = if (favorite) OptionColors.favorite else OptionColors.muted,
                        modifier = Modifier.action(onUse == null && onGive == null).semantics { contentDescription = label },
                    )
                }
                if (onUse != null || onGive != null) {
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(u * 6)) {
                        if (onUse != null) OptionButton("USE", m, onClick = onUse, modifier = Modifier.action(true))
                        if (onGive != null) OptionButton("GIVE", m, onClick = onGive, modifier = Modifier.action(onUse == null))
                    }
                }
            }
        }
    }
    extra?.invoke(item, pal.descText, pal.descTextShadow)
    }
}

/** What a USE / GIVE did, in the companion's words: [mon] the Pokémon it went to, [item] the item, [held]
 * what the Pokémon held before. */
internal fun itemUseMessage(o: ItemUseOutcome, mon: String = "", item: String = "", held: Int = 0): String = when (o.result) {
    ItemUseResult.USED -> if (o.steps > 0) tr("Used! It lasts {0} steps.", o.steps) else tr("Used on {0}.", mon)
    ItemUseResult.STILL_ACTIVE -> tr("The last one is still working.")
    ItemUseResult.NOT_NOW -> tr("Not now: close the game's menus and battles first.")
    ItemUseResult.IN_BATTLE -> tr("You can't do that during a battle.")
    ItemUseResult.NONE_LEFT -> tr("None left in the bag.")
    ItemUseResult.FAILED -> tr("It can't be used here.")
    ItemUseResult.NO_EFFECT -> tr("It won't have any effect.")
    ItemUseResult.GIVEN -> if (o.swapped == 0) tr("{0} is holding {1} now.", mon, item)
        else tr("{0} is holding {1} now. {2} went back to the bag.", mon, item, bagItemName(o.swapped))
    ItemUseResult.BAG_FULL -> tr("The bag is full: {0} can't go back in it.", bagItemName(held))
    ItemUseResult.PARTY_CHANGED -> tr("The party has changed. Try again.")
    ItemUseResult.TAKEN -> tr("Received {0} from {1}.", bagItemName(o.swapped), mon)
    ItemUseResult.IN_GAME -> tr("Pick a POKéMON on the game's screen.")
}

/** What a USE / GIVE did, popped up over the bag ([ItemNoticeWindow]). */
data class ItemNotice(val itemId: Int, val text: String, val ok: Boolean = true)

private const val ITEM_NOTICE_MILLIS = 2600L

/** [notice] (if any) in an [ItemNoticeWindow], gone after a moment or on a tap ([onGone]); a newer one starts its own time. */
@Composable
internal fun ItemNoticeHost(notice: ItemNotice?, m: GbaTextMetrics, modifier: Modifier, onGone: () -> Unit) {
    val n = notice ?: return
    LaunchedEffect(n) { kotlinx.coroutines.delay(ITEM_NOTICE_MILLIS); onGone() }
    ItemNoticeWindow(n, m, modifier, onGone)
}

/** A title window with a green tick, the item and what happened to it; a tap closes it. */
@Composable
private fun ItemNoticeWindow(n: ItemNotice, m: GbaTextMetrics, modifier: Modifier, onDismiss: () -> Unit) {
    val u = m.u
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u * 6),
        modifier = modifier
            .widthIn(max = 560.dp)
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 3 * u.toPx()) }
            .focusProperties { canFocus = false }
            .soundClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(horizontal = u * 10, vertical = u * 6),
    ) {
        val cell = with(LocalDensity.current) { m.fontPixel.toDp() }
        // A "not now" (PC) has neither the tick nor an item.
        if (n.ok) ShadowedPixelIcon(PixelIcons.check, OptionColors.success, OptionColors.titleShadow, m, cell = cell)
        if (n.itemId != 0) ItemIcon(com.pokedaisy.app.companion.data.spriteAsset("items", n.itemId), size = m.lineHeight)
        GbaText(n.text, OptionColors.titleText, OptionColors.titleShadow, m, maxLines = 3)
    }
}

