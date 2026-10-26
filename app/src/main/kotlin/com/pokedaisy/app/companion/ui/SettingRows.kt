package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import com.pokedaisy.app.companion.i18n.tk
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRequester
import com.pokedaisy.app.companion.i18n.tr

/**
 * One line of a settings list: [value] (red, in the value column) or null for a plain
 * command row; [header] = a group title. [badge] goes after the value ("BETA"),
 * [labelBadge] after the label ("BETA"); [enabled] false greys the row out.
 */
class SettingRow(
    val label: String, val value: String?, val badge: String? = null, val header: Boolean = false,
    val enabled: Boolean = true, val labelBadge: String? = null,
    val labelIcon: (@Composable () -> Unit)? = null,
    /** A line under the label saying what the row is for (English, translated where it's drawn). */
    val subtitle: String? = null,
    /** Hidden words Settings' search also matches (English, space-separated): the terms players use elsewhere. */
    val keywords: String = "",
    /** The row opens a page of its own (not a pick-list): a pixel chevron at its right end. */
    val opensPage: Boolean = false,
    /** A group title drawn to stand out ([featuredGroupTitle]). */
    val featured: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Settings' search: the rows whose label, value, subtitle or keywords hold every word of [query]
 * (in English or the language on screen, accents and case aside), each under its group's title.
 */
fun filterRows(rows: List<SettingRow>, query: String): List<SettingRow> {
    val words = fold(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return rows
    val out = mutableListOf<SettingRow>()
    var group: SettingRow? = null
    for (row in rows) {
        if (row.header) { group = row; continue }
        val text = fold(
            listOfNotNull(
                row.label, tr(row.label), row.value, row.value?.let { tr(it) }, row.subtitle, row.subtitle?.let { tr(it) },
                row.keywords, SETTING_KEYWORDS[row.label] ?: FLAGGED_SETTING_KEYWORDS[row.label],
            ).joinToString(" "),
        )
        if (words.all { it in text }) {
            if (group != null && out.lastOrNull { it.header } !== group) out += group
            out += row
        }
    }
    return out
}

/**
 * Search words for rows behind a feature flag (FeatureFlags), kept out of [SETTING_KEYWORDS] so the manual's check
 * (website/test/docs.test.js) doesn't ask for them while players can't see the row. Move a row's line there when its
 * flag goes and document it.
 */
val FLAGGED_SETTING_KEYWORDS: Map<String, String> = mapOf(
    "ScreenScraper" to "scraper scraping media artwork art box art boxart covers screenshots logos wheel fanart videos synopsis",
)

/**
 * Search words per row, by its English label (both screens' rows share them): what other emulators
 * and players call the same thing, and what's on the row's sub-page. English only - the label
 * itself is matched in the language on screen too.
 */
val SETTING_KEYWORDS: Map<String, String> = mapOf(
    "HELP" to "manual docs documentation guide how to faq instructions",
    "FF" to "fast forward turbo speed up",
    "DUAL-SCREEN LAUNCHER" to "frontend launcher two screens dual screen media game list swap",
    "FF SPEED" to "fast forward speed rate cap multiplier turbo",
    "FF MODE" to "smart fast forward menus battles normal",
    "FF MUSIC" to "fast forward audio sound music steady sped up mute",
    "REWIND" to "rewind undo go back time",
    "TOUCH CONTROLS" to "touch controls on screen controls virtual gamepad overlay touch buttons layout edit move resize size scale opacity transparency see through haptic haptics vibration vibrate turbo dpad d-pad fade hide phone",
    "GAME BUTTONS" to "key bindings keybinds keybinding controls remap remapping rebind mapping input controller gamepad buttons turbo",
    "HOTKEYS" to "shortcuts combos save state load state slot rewind fast forward key bindings restart close quit exit app companion open panel window",
    "QUICK MENU" to "radial ring wheel circle grid style labels names icons only shortcut actions pc fly repel super max bike bicycle get off dismount rod fishing wild encounters save load state fast forward l3 thumb stick",
    "STATUS BAR" to "clock time battery money location top bar",
    "BATTLE BUTTONS" to "battle buttons moves fight switch run flee side touch single screen one screen expand widescreen",
    "HUD" to "field hud heads up display overlay party minimap mini map balls bag wild encounters overworld legends single screen",
    "ASPECT" to "stretch ratio fullscreen full screen size scale 3:2 wide widescreen 16:9 expand zoom",
    "AUTO ZOOM IN" to "expand wide zoom close-up closeup dialogue conversation sign menu text box pull in automatic",
    "SHADERS" to "filter lcd crt scanlines grid gba colors color correction effects screen look smoothing xbr xbrz scalefx upscale hd edges unlit pixel transparency backlight",
    "LIGHT COMPANION" to "lag laggy slow slowdown stutter performance weak device low end battery buttons late input delay unresponsive animations smooth",
    "UNLIT SCREEN" to "pixel transparency transparent backlight backlit no backlight unlit reflective backing shadow drop shadow original gba screen white",
    "SMOOTHING" to "xbr xbrz scalefx upscale upscaler hd edges smooth anti alias filter",
    "OVERLAY" to "overlay overlays border borders bezel bezels frame skin retroarch background wallpaper decoration",
    "COMPANION" to "portrait phone bottom position layout",
    "COMPANION VIEW" to "single screen one screen window modal popup middle center side panel docked layout",
    "COMPANION BUTTON" to "companion tab arrow edge tab open button hide white corner bottom right",
    "SWAP SCREENS" to "dual screen display switch top bottom tv cast mirror mirroring smart view hdmi external",
    "SINGLE SCREEN" to "one screen single screen dual screen multitask multitasking second screen free other apps window modal popup",
    "SPEED OPTIONS" to "scene speeds battle speed text speed menu speed ff mode ff music fast forward smart steady",
    "DISPLAY OPTIONS" to "status bar shaders filter overlay border frame companion position portrait",
    "THEME" to "colors look background skin style",
    "FPS / CPU" to "fps frame rate framerate cpu usage performance speed overlay counter 60",
    "DARK MODE" to "dark night black theme light colors appearance auto system",
    "RESUME GAMES" to "auto resume continue launch boot",
    "FOLDERS" to "roms folder saves directory path location storage retroarch syncthing save states",
    "COVER ART" to "box art boxart covers steamgriddb images thumbnails api key",
    "HIDDEN GAMES" to "hide unhide library show",
    "CHEATS" to "gameshark action replay codebreaker codes",
    "RetroAchievements" to "achievements cheevos ra login account trophies",
    "LANGUAGE" to "language translation locale english japanese french german italian spanish portuguese português brazil brasil portugal idioma",
    "VERSION" to "update upgrade check release about",
    "CRASH REPORTS" to "crash report bug crashlytics privacy diagnostics send",
    "LICENSES" to "license legal open source credits notices about",
    "RUN SETUP" to "setup onboarding wizard first time welcome",
    "TAB BAR" to "tabs layout bar order",
    "BATTLE HINTS" to "suggestions recommendations best move tips",
    "FOE IVS" to "enemy opponent ivs stats",
    "BATTLE FOCUS" to "battle info compact one page no scrolling layout enemy opponent ability held item",
    "CLICK SOUND" to "sound effects button click audio",
    "TWEAKS" to "animations bounce blink cursor jump to battle effects motion",
    "SCENE SPEEDS" to "battle speed text speed instant text fast text menu speed overworld speed faster battles quality of life qol bike speed surf speed faster bike faster surfing",
    "AUTO-RUN" to "auto run autorun running shoes run sprint dash always run hold b",
    "RUN INDOORS" to "run inside buildings indoor running shoes houses",
    "MAKEOVER" to "makeover look looks style customize customise appearance costume outfit skin dress up player rival",
    "FOLLOWER" to "follower following pokemon walking pokemon partner companion pet behind you lead heartgold soulsilver yellow pikachu",
    "DAY AND NIGHT" to "day and night daytime nighttime night time dusk dawn sunset evening clock time of day tint darker dark lighting dns",
    "TEXT LOOK" to "text look text box textbox dialogue dialog box message box window frame font letters typeface firered emerald",
    "RIVAL LOOK" to "rival look rival costume outfit skin sprite appearance dress up brendan may other hero opponent",
    "PLAYER LOOK" to "player look hero heroine character protagonist trainer costume outfit skin sprite appearance swap play as another game red leaf brendan may",
    "REUSABLE TMS" to "reusable tm tms technical machine machines reuse infinite unlimited keep tm single use consumed used up teach moves",
    "WILD ENCOUNTERS" to "wild encounters encounter rate random battles grass caves repel no encounters fewer battles",
)

private fun fold(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** A group's title row in [GroupedRows]. */
fun groupTitle(label: String) = SettingRow(label, null, header = true) {}

/** A group's title that stands out from the others (MAKEOVER, the user's call): a framed blue band with gold stars and
 * [tagline] under the title. */
fun featuredGroupTitle(label: String, tagline: String) = SettingRow(label, null, header = true, subtitle = tagline, featured = true) {}

/**
 * A settings list in groups, shared by both screens' SETTINGS: group titles (red, like
 * the bag's pocket names) over [OptionLine] rows, one column at the normal text size
 * (big touch targets), scrolling when it outgrows the window, then [footer]. [onClick]
 * gets the row's index in [rows]; [cursor] is the white row (-1: none). [scroll] is hoisted
 * by a list that sub-pages / pick-lists replace, so coming back keeps its place.
 */
@Composable
fun GroupedRows(
    rows: List<SettingRow>,
    m: GbaTextMetrics,
    cursor: Int,
    onClick: (Int) -> Unit,
    scroll: ScrollState = rememberScrollState(),
    /** The label column's share of a row (with its subtitle); TWEAKS' ON / OFF need little room. */
    labelWeight: Float = 0.58f,
    /** A row scrolled into view once, then [onRevealed] (SWAP SCREENS, after the swap rebuilt the page); -1 = none. */
    reveal: Int = -1,
    onRevealed: () -> Unit = {},
    /** Given to the first row (not a group title): where D-pad focus starts. */
    firstRowFocus: androidx.compose.ui.focus.FocusRequester? = null,
    /** Row i took the D-pad's focus: the cursor follows it, so only one row is white. */
    onFocused: ((Int) -> Unit)? = null,
    /** A column of the group titles on the left ([GroupTabs]) where the window is wide enough: a tap scrolls the list
     * to that group. For a long list (SETTINGS' home pages); it's still one list. */
    groupTabs: Boolean = false,
    footer: @Composable () -> Unit = {},
) {
    val groups = if (groupTabs) rows.indices.filter { rows[it].header } else emptyList()
    if (groups.size < MIN_GROUP_TABS) {
        GroupedRowsList(rows, m, cursor, onClick, scroll, labelWeight, reveal, onRevealed, firstRowFocus, onFocused, null, footer)
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < GROUP_TABS_MIN_WIDTH || maxWidth < maxHeight) {
            GroupedRowsList(rows, m, cursor, onClick, scroll, labelWeight, reveal, onRevealed, firstRowFocus, onFocused, null, footer)
            return@BoxWithConstraints
        }
        // Each group title's top in the list (content pixels), as laid out.
        val tops = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
        // The row the player last moved to (the D-pad's focus, the cursor): its group's tab lights, even while the
        // scroll still shows the group before at the top (DOWN from a group's last row onto the next group's first).
        var follow by remember { mutableStateOf(-1) }
        // The cursor it opened on doesn't count: the list may have been dragged away from it before.
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(cursor) {
            if (opened && cursor >= 0) follow = cursor
            opened = true
        }
        val focused: (Int) -> Unit = { i -> follow = i; onFocused?.invoke(i) }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            GroupTabs(rows, groups, tops, scroll, follow, m)
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxSize()) {
                GroupedRowsList(rows, m, cursor, onClick, scroll, labelWeight, reveal, onRevealed, firstRowFocus, focused, tops, footer)
            }
        }
    }
}

/** [GroupedRows]' tabs need this many groups, and a window this wide (and wider than tall). */
private const val MIN_GROUP_TABS = 3
private val GROUP_TABS_MIN_WIDTH = 400.dp

/**
 * [GroupedRows]' group tabs: the group titles down the window's left edge, the one the list is showing lit like a
 * selected tab; a tap scrolls the list to that group. Touch only (the D-pad walks the rows). The group of the row the
 * player last moved to ([follow]) or the tab last tapped stays lit until the list is dragged; then the group at the top
 * decides (at the list's very end, the last).
 */
@Composable
private fun GroupTabs(
    rows: List<SettingRow>,
    groups: List<Int>,
    tops: Map<Int, Int>,
    scroll: ScrollState,
    follow: Int,
    m: GbaTextMetrics,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picked by remember { mutableStateOf(-1) }
    // A row moved to: its group, wherever the scroll left the group titles.
    LaunchedEffect(follow) { if (follow >= 0) groups.lastOrNull { it <= follow }?.let { picked = it } }
    // Dragging the list hands the choice back to the scroll.
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) picked = -1 }
    }
    val atEnd = scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 1
    val slack = with(androidx.compose.ui.platform.LocalDensity.current) { (m.u * 4).roundToPx() }
    val shown = groups.lastOrNull { (tops[it] ?: Int.MAX_VALUE) <= scroll.value + slack } ?: groups.first()
    // At the very end the last group is on screen whatever reached the top.
    val current = picked.takeIf { it >= 0 } ?: if (atEnd) groups.last() else shown
    val small = rememberGbaTextMetrics(1f)
    androidx.compose.foundation.layout.Column(
        Modifier
            .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
            .fillMaxHeight()
            .drawBehind {
                // A dashed rule between the tabs and the list, like the rows' dividers.
                val px = m.u.toPx()
                var y = 4 * px
                while (y < size.height - 4 * px) {
                    drawRect(OptionColors.divider, androidx.compose.ui.geometry.Offset(size.width - px, y), androidx.compose.ui.geometry.Size(px, 2 * px))
                    y += 4 * px
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(start = m.u * 4, end = m.u * 6, top = m.u * 4, bottom = m.u * 4),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(m.u * 2),
    ) {
        for (g in groups) {
            GroupTab(tr(rows[g].label), g == current, small) {
                picked = g
                val top = tops[g] ?: return@GroupTab
                scope.launch { scroll.animateScrollTo(top.coerceAtMost(scroll.maxValue)) }
            }
        }
    }
}

/** One of [GroupTabs]: a tab chip's frame (white with a red label when [selected], like the tab bar's) at the list's
 * smaller text. */
@Composable
private fun GroupTab(label: String, selected: Boolean, m: GbaTextMetrics, onClick: () -> Unit) {
    val u = m.u
    androidx.compose.foundation.layout.Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val px = u.toPx()
                drawLayeredBox(OptionColors.chipLayers.inPx(px), if (selected) OptionColors.tabSelectedFill else OptionColors.tabIdleFill, radius = 3 * px)
            }
            .focusProperties { canFocus = false }
            .soundClickable(onClick = onClick)
            .padding(horizontal = u * 6, vertical = u * 4),
    ) {
        GbaText(
            label,
            if (selected) OptionColors.value else OptionColors.label,
            if (selected) OptionColors.valueShadow else OptionColors.labelShadow,
            m, bold = gameBoldLabels(),
        )
    }
}

@Composable
private fun GroupedRowsList(
    rows: List<SettingRow>,
    m: GbaTextMetrics,
    cursor: Int,
    onClick: (Int) -> Unit,
    scroll: ScrollState,
    labelWeight: Float,
    reveal: Int,
    onRevealed: () -> Unit,
    firstRowFocus: androidx.compose.ui.focus.FocusRequester?,
    onFocused: ((Int) -> Unit)?,
    /** Each group title's top in the list, for [GroupTabs]. */
    tops: MutableMap<Int, Int>?,
    footer: @Composable () -> Unit,
) {
    val firstRow = rows.indexOfFirst { !it.header }
    // A game font is set larger to match Pixel Operator's letters (Gen 1's 7px capitals): its line is taller for the same
    // letters, so the rows take the GBA games' height instead - the Game Boy games' SETTINGS were a third rows short.
    // Subtitles there are their own (smaller) line; the GBA games keep a full line for theirs.
    val gameScale = com.pokedaisy.app.companion.ui.theme.LocalGameTextScale.current
    val rowHeight = m.rowHeight * 1.2f / gameScale
    val subtitleLine = if (gameScale == 1f) m.lineHeight else subtitleMetrics(m).lineHeight + m.u * 2
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        rows.forEachIndexed { i, row ->
            if (row.header && row.featured) {
                val track = if (tops == null) Modifier else Modifier.onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
                FeaturedTitle(row, m, top = if (i == 0) 1 else 6, modifier = track)
            } else if (row.header) {
                val track = if (tops == null) Modifier else Modifier.onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
                val band = OptionColors.groupTitleFill
                if (band == null) {
                    GbaText(
                        tr(row.label), OptionColors.value, OptionColors.valueShadow, m,
                        modifier = track.padding(start = m.u * 8, top = m.u * (if (i == 0) 1 else 6), bottom = m.u),
                    )
                } else {
                    // Black text on white rows (Gen 1): the title is a black band, so it can't pass for a row.
                    GbaText(
                        tr(row.label), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m,
                        modifier = track
                            .padding(start = m.u * 2, end = m.u * 2, top = m.u * (if (i == 0) 1 else 6), bottom = m.u * 2)
                            .fillMaxWidth()
                            .drawBehind { drawPixelRoundRect(band, radius = m.u.toPx() * 2, step = m.u.toPx()) }
                            .padding(horizontal = m.u * 6, vertical = m.u * 2),
                    )
                }
            } else {
                OptionLine(
                    row.label, row.value, selected = i == cursor, m,
                    modifier = (if (i == reveal) Modifier.revealOnce(onRevealed) else Modifier)
                        .then(if (i == firstRow && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier)
                        // The companion's window: the D-pad starts on the cursor row.
                        .focusStart(i == cursor),
                    // A one-line row's letters sit low in their line (room above the capitals, none under): a little more
                    // under them, so the space below matches the space above.
                    height = if (row.subtitle != null) rowHeight + subtitleLine else rowHeight + m.u * ONE_LINE_ROW_EXTRA,
                    bottomPadding = if (row.subtitle != null) 0.dp else m.u * ONE_LINE_ROW_EXTRA,
                    divider = rows.getOrNull(i + 1)?.header == false, labelBadge = row.labelBadge, labelIcon = row.labelIcon,
                    valueBadge = row.badge, enabled = row.enabled, subtitle = row.subtitle, labelWeight = labelWeight,
                    chevron = row.opensPage,
                    onFocus = onFocused?.let { f -> { f(i) } },
                ) { onClick(i) }
            }
        }
        footer()
    }
}

/** GBA pixels added under a [GroupedRows] row without a subtitle. */
private const val ONE_LINE_ROW_EXTRA = 2

/** [featuredGroupTitle]'s band: the title between two gold stars, its tagline under it, in a field-move blue frame. */
@Composable
private fun FeaturedTitle(row: SettingRow, m: GbaTextMetrics, top: Int, modifier: Modifier = Modifier) {
    val u = m.u
    val small = subtitleMetrics(m)
    Column(
        modifier
            .padding(start = u * 2, end = u * 2, top = u * top, bottom = u * 2)
            .fillMaxWidth()
            .drawBehind {
                val px = u.toPx()
                drawLayeredBox(OptionColors.fieldMoveLayers.inPx(px), OptionColors.featureFill, radius = 3 * px)
            }
            .padding(horizontal = u * 8, vertical = u * 4),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ShadowedPixelIcon(PixelIcons.star, OptionColors.favorite, OptionColors.featureShadow, m)
            GbaText(
                tr(row.label), OptionColors.featureText, OptionColors.featureShadow, m,
                modifier = Modifier.padding(horizontal = u * 4),
            )
            ShadowedPixelIcon(PixelIcons.star, OptionColors.favorite, OptionColors.featureShadow, m)
        }
        row.subtitle?.let { GbaText(tr(it), OptionColors.label, OptionColors.labelShadow, small, modifier = Modifier.padding(top = u)) }
    }
}

/** Scrolls this row into view, a row's height of its neighbours with it, once it's laid out; then [done]. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.revealOnce(done: () -> Unit): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(size) {
        // A freshly composed page has no layout yet: bringIntoView then had nothing to scroll to.
        if (size == IntSize.Zero) return@LaunchedEffect
        val h = size.height.toFloat()
        requester.bringIntoView(Rect(0f, -h, size.width.toFloat(), 2 * h))
        done()
    }
    onSizeChanged { size = it }.bringIntoViewRequester(requester)
}

/**
 * DISPLAY OPTIONS > LIGHT COMPANION, on both screens ([com.pokedaisy.app.companion.LightCompanion]): AUTO / ON / OFF
 * from a pick-list ([pick]: title, choices, the current one, what to do with the pick); AUTO's subtitle says what it
 * decided for this device.
 */
fun lightCompanionRow(pick: (String, List<String>, String, (String) -> Unit) -> Unit, set: (com.pokedaisy.app.companion.LightCompanionMode) -> Unit): SettingRow {
    val light = com.pokedaisy.app.companion.LightCompanion
    val modes = com.pokedaisy.app.companion.LightCompanionMode.entries
    val current = light.mode.label
    return SettingRow(
        tk("LIGHT COMPANION"), current,
        labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.LIGHT_COMPANION),
        subtitle = when {
            light.mode != com.pokedaisy.app.companion.LightCompanionMode.AUTO -> "For slow devices: fewer redraws, no animations"
            light.on -> "On: this device needs it"
            else -> "Off: this device keeps up"
        },
    ) { pick(tk("LIGHT COMPANION"), modes.map { it.label }, current) { l -> modes.firstOrNull { it.label == l }?.let(set) } }
}
