package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.stripAccents
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.zIndex
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
    /** [labelIcon]'s size in GBA pixels, when it isn't a line high (the HUD's / QUICK MENU's 16-pixel icons): the row grows
     * to fit it. 0 = a line high. */
    val iconPixels: Int = 0,
    /** A line under the label saying what the row is for (English, translated where it's drawn). */
    val subtitle: String? = null,
    /** Hidden words Settings' search also matches (English, space-separated): the terms players use elsewhere. */
    val keywords: String = "",
    /** The row opens a page of its own (not a pick-list): a pixel chevron at its right end. */
    val opensPage: Boolean = false,
    /** A group title drawn to stand out ([featuredGroupTitle]). */
    val featured: Boolean = false,
    /** The value as a slider beside [value] (GAME VOLUME): touch or LEFT / RIGHT move it; a tap on the row's label does
     * [onClick] as usual. */
    val slider: SettingSlider? = null,
    /** More (or less) of the line for the label than the page's default: a long label beside a short value. */
    val labelWeight: Float? = null,
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
    // CO-OP (FeatureFlags.coop): moves to SETTING_KEYWORDS, with its manual page, when the flag goes.
    "LINK" to "co-op coop connection connected status partner online",
    "PAIR" to "co-op coop multiplayer multi player two player online friend partner link invite qr code join netplay play together",
    "PARTNERS" to "co-op coop paired friends unpair forget remove partner",
    "NEARBY RANGE" to "co-op coop range distance join co-op battle battle together how close steps",
    "RELAY" to "co-op coop server relay address url self hosted",
    "RELAY ONLY" to "co-op coop privacy hide ip address relay only",
    "PLAY TOGETHER ONLY" to "co-op coop lock together story wait partner both players new game only",
)

/**
 * Search words per row, by its English label (both screens' rows share them): what other emulators
 * and players call the same thing, and what's on the row's sub-page. English only - the label
 * itself is matched in the language on screen too.
 */
val SETTING_KEYWORDS: Map<String, String> = mapOf(
    "HELP" to "manual docs documentation guide how to faq instructions",
    "DISCORD" to "discord server community chat support help contact report bug feedback ideas suggestions developers dev",
    "FF" to "fast forward turbo speed up",
    "DUAL-SCREEN LAUNCHER" to "frontend launcher two screens dual screen media game list swap",
    "FF SPEED" to "fast forward speed rate cap multiplier turbo",
    "FF MODE" to "smart fast forward menus battles normal",
    "FF MUSIC" to "fast forward audio sound music steady sped up mute",
    "REWIND" to "rewind undo go back time",
    "TOUCH CONTROLS" to "touch controls on screen controls virtual gamepad overlay touch buttons layout edit move resize size scale opacity transparency see through haptic haptics vibration vibrate turbo dpad d-pad fade hide phone look colors colours style theme skin skins deltaskin delta left handed lefty mirrored",
    "LOOK" to "style appearance glass solid outline console window touch controls buttons hud",
    "COLORS" to "touch controls buttons colours color theme dark light indigo glacier spice kiwi fuchsia retro primary",
    "GBA SKIN" to "touch controls skin skins deltaskin delta controller picture",
    "IMPORT SKIN" to "touch controls skin skins deltaskin delta add install controller picture",
    "GAME BUTTONS" to "key bindings keybinds keybinding controls remap remapping rebind mapping input controller gamepad buttons turbo start 2 select 2 x y ds nds game boy gba to companion d-pad dpad directions up down left right arrows 8bitdo micro keyboard mode",
    "HOTKEYS" to "shortcuts combos save state load state slot rewind fast forward key bindings restart close quit exit app companion open panel window",
    "QUICK MENU" to "radial ring wheel circle grid style labels names icons only shortcut actions pc fly flash dark cave light repel super max bike bicycle get off dismount rod fishing wild encounters save load state fast forward l3 thumb stick",
    "STATUS BAR" to "clock time battery money location top bar",
    "HIDE BATTLE MENU" to "hide battle menu buttons on screen fight bag pokemon run moves move list text box arrow dual screen two screens second screen bottom screen thor companion distracting clutter",
    "BATTLE BUTTONS" to "battle buttons moves fight switch run flee side touch single screen one screen expand widescreen",
    "HUD" to "field hud heads up display overlay party minimap mini map balls bag wild encounters overworld legends single screen",
    "ASPECT" to "stretch ratio fullscreen full screen size scale 3:2 wide widescreen 16:9 expand zoom",
    "ZOOM" to "expand wide zoom out see further map farther camera distance no right stick r3 touch",
    "AUTO ZOOM IN" to "expand wide zoom close-up closeup dialogue conversation sign menu text box pull in automatic",
    "DS TOUCH SCREEN" to "bottom screen touch screen stretch fill full fullscreen bar black bar companion button game screen dual screen thor nintendo ds",
    "DS CORE" to "emulator core engine melonds dsperate bananads banana bananaos weak slow slower old cheap low end budget performance speed faster lag laggy stutter 32-bit 32bit armv7 tablet retroid pocket duo lite anbernic rg ds nintendo ds heartgold soulsilver platinum black white",
    "DS CPU BOOST" to "overclock cpu clock speed boost faster lag slowdown slow stutter stuttering smooth smoother frame rate fps 30fps 60fps choppy laggy nintendo ds heartgold soulsilver platinum black white",
    "DS RESOLUTION" to "internal resolution upscale upscaling scale scaling hd high definition sharp sharper crisp 3d render renderer gpu opengl 2x 4x 6x 8x jaggies jagged blurry nintendo ds platinum heartgold soulsilver",
    "DS LAYOUT" to "screen layout screens dual screen two screens bottom screen touch screen touchscreen side by side stacked vertical hybrid picture in picture pip large small single screen one screen floating window popup nintendo ds heartgold soulsilver",
    "SHADERS" to "filter lcd crt scanlines grid gba colors color correction effects screen look smoothing xbr xbrz scalefx upscale hd edges unlit pixel transparency backlight",
    "COMPANION LOOK" to "skin skins theme style look game heartgold soulsilver platinum black white firered leafgreen emerald ruby sapphire menus colors classic",
    "LIGHT COMPANION" to "lag laggy slow slowdown stutter performance weak device low end battery buttons late input delay unresponsive animations smooth",
    "UNLIT SCREEN" to "pixel transparency transparent backlight backlit no backlight unlit reflective backing shadow drop shadow original gba screen white",
    "SMOOTHING" to "xbr xbrz scalefx upscale upscaler hd edges smooth anti alias filter",
    "OVERLAY" to "overlay overlays border borders bezel bezels frame skin retroarch background wallpaper decoration",
    "COMPANION" to "portrait phone bottom position layout",
    "COMPANION VIEW" to "single screen one screen window modal popup middle center side panel docked layout",
    "FOLD LAYOUT" to "foldable fold folding flip galaxy z fold pixel fold hinge tabletop flex mode book half open flat unfolded always dual screen two screens",
    "TABLETOP CONTROLS" to "foldable fold tabletop laptop touch controls buttons dpad bottom half sides hinge flex mode",
    "COMPANION BUTTON" to "companion tab arrow edge tab open button hide white corner bottom right",
    "SWAP SCREENS" to "dual screen display switch top bottom tv cast mirror mirroring smart view hdmi external",
    "SINGLE SCREEN" to "one screen single screen dual screen multitask multitasking second screen free other apps window modal popup",
    "SPEED OPTIONS" to "scene speeds battle speed text speed menu speed ff mode ff music fast forward smart steady",
    "DISPLAY OPTIONS" to "status bar shaders filter overlay border frame companion position portrait",
    "THEME" to "colors colours look background skin style themes cherry rainbow ocean mint lavender sunset forest lemon holiday seasonal christmas halloween easter valentine lunar new year limited",
    "FPS / CPU" to "fps frame rate framerate cpu usage performance speed overlay counter 60",
    "DARK MODE" to "dark night black theme light colors appearance auto system",
    "FONT" to "font text letters typeface readable reading pixel smooth bold system size small legible",
    "RESUME GAMES" to "auto resume continue launch boot",
    "FOLDERS" to "roms folder folders multiple add link saves directory path location storage retroarch syncthing save states backup backups restore",
    "COVER ART" to "box art boxart covers steamgriddb images thumbnails api key",
    "HIDDEN GAMES" to "hide unhide library show",
    "CHEATS" to "gameshark action replay codebreaker codes",
    "RetroAchievements" to "achievements cheevos ra login account trophies",
    "LANGUAGE" to "language translation locale english japanese french german italian spanish portuguese português brazil brasil portugal idioma korean 한국어 언어",
    "VERSION" to "update upgrade check release about",
    "BETA UPDATES" to "beta test testing tester pre-release prerelease release candidate rc early preview unstable update channel discord",
    "CRASH REPORTS" to "crash report bug crashlytics privacy diagnostics send",
    "LICENSES" to "license legal open source credits notices about",
    "RUN SETUP" to "setup onboarding wizard first time welcome",
    "TAB BAR" to "tabs layout bar order",
    "BATTLE HINTS" to "suggestions recommendations best move tips",
    "BATTLE STYLE" to "battle screen look theme gen 4 gen 5 platinum black white buttons design",
    "BATTLE INFO" to "hide battle info suggestions ratings effectiveness buttons only clean",
    "ACHIEVEMENT POPUPS" to "retroachievements cheevos achievements notifications notices popup unlock fanfare sound quiet hide",
    "PROGRESS POPUPS" to "retroachievements cheevos achievements notifications notices popup progress counter quiet hide",
    "CHALLENGE ICONS" to "retroachievements cheevos achievements leaderboard tracker badge badges corner hide icons overlay",
    "BATTLE VIEW" to "battle tab default info buttons suggestions best picks first opens",
    "GAME NAMES" to "file name filename rom title library naming",
    "SUPPORTED ONLY" to "filter hide unsupported not supported compatible library",
    "LOOK AROUND" to "right stick freecam free camera look pan view",
    "FOE IVS" to "enemy opponent ivs stats",
    "BATTLE FOCUS" to "battle info compact one page no scrolling layout enemy opponent ability held item",
    "CLICK SOUND" to "sound effects button click audio",
    "GAME VOLUME" to "volume sound audio loudness quieter louder level percent music sfx",
    "MUTE GAME" to "mute unmute silent silence quiet sound off audio volume hotkey",
    "AUDIO EFFECTS" to "equalizer equaliser eq jamesdsp james dsp viper sound audio bass speaker enhancer",
    "TWEAKS" to "animations bounce blink cursor jump to battle effects motion",
    "SCENE SPEEDS" to "battle speed text speed instant text fast text menu speed overworld speed faster battles quality of life qol bike speed surf speed faster bike faster surfing",
    "AUTO-RUN" to "auto run autorun running shoes run sprint dash always run hold b",
    "RUN INDOORS" to "run inside buildings indoor running shoes houses",
    "RUNNING SHOES" to "running shoes run sprint dash hold b faster walking game boy red blue yellow gold silver crystal bike speed",
    "MAKEOVER" to "makeover look looks style customize customise appearance costume outfit skin dress up player rival",
    "FOLLOWER" to "follower following pokemon walking pokemon partner companion pet behind you lead heartgold soulsilver yellow pikachu",
    "DAY AND NIGHT" to "day and night daytime nighttime night time dusk dawn sunset evening clock time of day tint darker dark lighting dns",
    "TEXT LOOK" to "text look text box textbox dialogue dialog box message box window frame font letters typeface firered emerald",
    "RIVAL LOOK" to "rival look rival costume outfit skin sprite appearance dress up brendan may other hero opponent",
    "PLAYER LOOK" to "player look hero heroine character protagonist trainer costume outfit skin sprite appearance swap play as another game red leaf brendan may",
    "NUZLOCKE" to "nuzlocke challenge hardcore run tracker encounter first catch per route area death dead faint permadeath graveyard dupes clause",
    "TRADE EVOS" to "trade evolutions trading evolve evolves evolution evolutions traded link cable friend second game without trading held item",
    "INSTANT TEXT" to "instant text speed fast dialogue dialog words letters message box skip immediately faster",
    "RIDE ON" to "ride on bike bicycle motorcycle motorbike scooter vespa moped vehicle cycling replace bike look mach acro",
    "FLY ON" to "fly on vehicle balloon hot air balloon helicopter zeppelin airship blimp plane airplane aeroplane propeller ride mount bird flight free flight take off",
    "FLIGHT AREA" to "flight area where you've been where you have been anywhere visited seen places limit restrict progress story skip sequence break free flight take off fly over map achievements",
    "FLY STYLE" to "fly style scenic classic flight animation flying over map travel town cutscene bird fly to",
    "EXP SHARE" to "exp share experience all party team whole party level up faster grinding exp all split full bench",
    "AUTO REFILL" to "auto refill repel repels super max keep away spray wild encounters avoid use another run out wore off again automatic",
    "POKéMON PICTURES" to "pokemon pictures party pictures sprites front sprite icons party icons detailed pictures recognise recognize team images crystal",
    "LAST BALL" to "last ball throw ball catch quick ball button one tap poke ball pokeball great ultra repeat throw again catching capture",
    "BOSS RETRY" to "boss retry try again redo rematch lost loss black out blackout white out whiteout gym leader trainer battle restart undo",
    "LOW HP BEEP" to "low hp beep beeping alarm sound health red annoying noise mute silence",
    "FIELD POISON" to "field poison overworld walking damage poisoned steps faint hurt outside battle",
    "DESCRIPTIONS" to "descriptions subtitles explanations help text grey line under setting compact clean shorter list",
    "GROUP TABS" to "group tabs side buttons rail categories left column sections hide tabs wider list",
    "LEVEL CAP" to "level cap max level limit hard mode harder overlevel over leveled gym leader boss ace stop exp",
    "NO HMS NEEDED" to "hm hms hidden machine machines free slave field moves cut surf strength rock smash waterfall dive fly flash without teaching badge only any pokemon",
    "REUSABLE TMS" to "reusable tm tms technical machine machines reuse infinite unlimited keep tm single use consumed used up teach moves",
    "SHINY ALERT" to "shiny alert hunt hunting counter encounters count chain notify notification warn sparkle stop fast forward found",
    "SHINY ODDS" to "shiny shinies shiny odds shiny rate shiny hunting hunt shiny charm sparkle rare color colour chance",
    "TIPS" to "tips hints help popup pop up feature discovery introduce new features suggestions what can it do",
    "WILD ENCOUNTERS" to "wild encounters encounter rate random battles grass caves repel no encounters fewer battles",
)

private fun fold(s: String): String = stripAccents(s.lowercase())

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
    val groups = if (groupTabs && SettingsLook.tabs) rows.indices.filter { rows[it].header } else emptyList()
    // Inside [RailedWindow] the list draws the window itself, so the group tabs can stay out of it.
    val railed = LocalRailedWindow.current
    @Composable fun Card(content: @Composable () -> Unit) {
        if (railed) OptionListWindow(m, Modifier.fillMaxSize(), content = content) else content()
    }
    if (groups.size < MIN_GROUP_TABS) {
        Card { GroupedRowsList(rows, m, cursor, onClick, scroll, labelWeight, reveal, onRevealed, firstRowFocus, onFocused, null, footer) }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < GROUP_TABS_MIN_WIDTH || maxWidth < maxHeight) {
            Card { GroupedRowsList(rows, m, cursor, onClick, scroll, labelWeight, reveal, onRevealed, firstRowFocus, onFocused, null, footer) }
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
        val railHeight = maxHeight
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            GroupTabs(rows, groups, tops, scroll, follow, m, outside = railed, height = railHeight)
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxSize()) {
                Card { GroupedRowsList(rows, m, cursor, onClick, scroll, labelWeight, reveal, onRevealed, firstRowFocus, focused, tops, footer) }
            }
        }
    }
}

/** [GroupedRows] is inside a [RailedWindow]. */
private val LocalRailedWindow = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The window round a settings page. With [rail] it's left to the [GroupedRows] inside, which draws it round the list alone, the
 * group tabs on the page's backdrop beside it (the user's call: buttons, not part of the card); any other page, a plain
 * [OptionListWindow].
 */
@Composable
fun RailedWindow(m: GbaTextMetrics, modifier: Modifier, rail: Boolean, content: @Composable () -> Unit) {
    if (!rail) return OptionListWindow(m, modifier, content = content)
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRailedWindow provides true, content = content)
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
    /** Beside the window ([RailedWindow]), not in it. */
    outside: Boolean = false,
    /** The rail's height: beside the window, the tabs share it out. */
    height: androidx.compose.ui.unit.Dp? = null,
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
    TabRail(groups.map { tr(rows[it].label) }, groups.indexOf(current), m, outside, height.takeIf { outside }) { i ->
        val g = groups[i]
        picked = g
        val top = tops[g] ?: return@TabRail
        scope.launch { scroll.animateScrollTo(top.coerceAtMost(scroll.maxValue)) }
    }
}

/**
 * Tabs down a window's left edge, [selected] lit like the tab bar's chosen chip, a dashed rule between them and the page:
 * [GroupedRows]' group tabs, and pages split into panes (CO-OP's PAIR). [labels] are drawn as given (translated).
 */
@Composable
fun TabRail(
    labels: List<String>, selected: Int, m: GbaTextMetrics,
    /** Beside a window rather than in one: no rule, no room for a frame. */
    outside: Boolean = false,
    /** The height to fill (SETTINGS' rail beside its window, the user's call): the tabs stretch to share it out where they fit,
     * else they keep their own height and the rail scrolls. */
    fill: androidx.compose.ui.unit.Dp? = null,
    /** D-pad stops that pick their tab on focus (GUIDE's pages); SETTINGS' are touch only. */
    focusable: Boolean = false,
    /** The tabs' text size against a GBA pixel. */
    textScale: Float = 1f,
    onSelect: (Int) -> Unit,
) {
    val small = rememberGbaTextMetrics(textScale)
    // A MENU STYLE: folder tabs on the window's left side, touching, the open one running into the window.
    val folder = outside && OptionColors.joined
    val gap = if (folder) 0.dp else m.u * if (fill != null) 3 else 2
    val tabMin = fill?.let { ((it - gap * (labels.size - 1)) / labels.size.coerceAtLeast(1)) } ?: 0.dp
    androidx.compose.foundation.layout.Column(
        Modifier
            .then(if (folder) Modifier.zIndex(1f) else Modifier)
            .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
            .fillMaxHeight()
            .drawBehind {
                if (outside) return@drawBehind
                // A dashed rule between the tabs and the list, like the rows' dividers.
                val px = m.u.toPx()
                var y = 4 * px
                while (y < size.height - 4 * px) {
                    drawRect(OptionColors.divider, androidx.compose.ui.geometry.Offset(size.width - px, y), androidx.compose.ui.geometry.Size(px, 2 * px))
                    y += 4 * px
                }
            }
            .verticalScroll(rememberScrollState())
            .then(
                if (folder) Modifier
                else if (outside) Modifier.padding(end = m.u * 4)
                else Modifier.padding(start = m.u * 4, end = m.u * 6, top = m.u * 4, bottom = m.u * 4),
            ),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
    ) {
        labels.forEachIndexed { i, label ->
            if (folder) FolderTab(label, i == selected, small, tabMin, focusable) { onSelect(i) }
            else GroupTab(label, i == selected, small, tabMin) { onSelect(i) }
        }
    }
}

/** Explanatory text in its own small window, above a page's list (top-screen Settings' pages). */
@Composable
fun PageHint(text: String, m: GbaTextMetrics, small: GbaTextMetrics) {
    OptionListWindow(m, Modifier.fillMaxWidth().padding(bottom = m.u * 4)) {
        GbaText(
            text, OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
            modifier = Modifier.padding(horizontal = m.u * 6, vertical = m.u * 2),
        )
    }
}

/**
 * [TabRail]'s tab under a MENU STYLE: the window's own frame layers round its left, top and bottom, open on the right. The
 * open one is the window's paper and runs over the window's left frame, so the two read as one; the others are the
 * style's section colour.
 */
@Composable
private fun FolderTab(
    label: String, selected: Boolean, m: GbaTextMetrics, minHeight: androidx.compose.ui.unit.Dp, focusable: Boolean, onClick: () -> Unit,
) {
    val u = m.u
    val style = OptionColors.style ?: return GroupTab(label, selected, m, minHeight, onClick)
    val layers = OptionColors.listLayers
    val frame = layers.sumOf { it.second }
    val fill = if (selected) OptionColors.listFill else OptionColors.tint(style.sectionFill)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused && !selected) onClick() }
    androidx.compose.foundation.layout.Box(
        contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .drawBehind {
                val px = u.toPx()
                val reach = if (selected) frame * px else 0f
                val radius = 3 * px
                // Run past the right edge (no right-hand frame), cut where the tab meets the window, or past its frame.
                clipRect(right = size.width + reach) {
                    var inset = 0f
                    var r = radius
                    val w = size.width + reach + radius + frame * px
                    for ((color, width) in layers) {
                        drawPixelRoundRect(color, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Size(w - 2 * inset, size.height - 2 * inset), r.coerceAtLeast(0f), px)
                        inset += width * px
                        r -= width * px
                    }
                    drawPixelRoundRect(fill, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Size(w - 2 * inset, size.height - 2 * inset), r.coerceAtLeast(0f), px)
                }
            }
            .focusProperties { canFocus = focusable }
            .soundClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = u * (frame + 4), end = u * 6, top = u * (frame + 1), bottom = u * (frame + 1)),
    ) {
        if (selected) GbaText(label, OptionColors.value, OptionColors.valueShadow, m, bold = gameBoldLabels())
        else GbaText(label, style.sectionText, style.sectionShadow, m, bold = gameBoldLabels())
    }
}

/** One of [TabRail]'s tabs: a tab chip's frame (white with a red label when [selected], like the tab bar's) at the list's
 * smaller text. */
@Composable
private fun GroupTab(
    label: String, selected: Boolean, m: GbaTextMetrics,
    /** At least this tall ([TabRail]'s fill): taller than its label needs where the rail has room. */
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit,
) {
    val u = m.u
    androidx.compose.foundation.layout.Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .drawBehind {
                val px = u.toPx()
                drawLayeredBox(OptionColors.chipLayers.inPx(px), if (selected) OptionColors.tabSelectedFill else OptionColors.tabIdleFill, radius = 3 * px, twoTone = true)
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
    // DESCRIPTIONS hidden ([SettingsLook]): rows go without their grey line.
    val descriptions = SettingsLook.descriptions
    fun subtitleOf(row: SettingRow) = row.subtitle?.takeIf { descriptions }
    fun heightOf(row: SettingRow) = (if (subtitleOf(row) != null) rowHeight + subtitleLine else rowHeight + m.u * ONE_LINE_ROW_EXTRA)
        // A big icon: its size and a few pixels over and under it.
        .let { if (row.iconPixels > 0) maxOf(it, m.u * (row.iconPixels + 8)) else it }
    @Composable fun RowAt(i: Int, row: SettingRow) {
        if (row.header && row.featured) {
            val track = if (tops == null) Modifier else Modifier.onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
            FeaturedTitle(row, m, top = if (i == 0) 1 else 6, modifier = track)
        } else if (row.header) {
            val track = if (tops == null) Modifier else Modifier.onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
            GroupTitleLine(row.label, m, first = i == 0, modifier = track)
        } else {
            OptionLine(
                row.label, row.value, selected = i == cursor, m,
                modifier = (if (i == reveal) Modifier.revealOnce(onRevealed) else Modifier)
                    .then(if (i == firstRow && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier)
                    // The companion's window: the D-pad starts on the cursor row.
                    .focusStart(i == cursor),
                // A one-line row's letters sit low in their line (room above the capitals, none under): a little more
                // under them, so the space below matches the space above.
                height = heightOf(row),
                bottomPadding = if (subtitleOf(row) != null) 0.dp else m.u * ONE_LINE_ROW_EXTRA,
                divider = rows.getOrNull(i + 1)?.header == false, labelBadge = row.labelBadge, labelIcon = row.labelIcon,
                labelIconSize = if (row.iconPixels > 0) m.u * row.iconPixels else null,
                valueBadge = row.badge, enabled = row.enabled, subtitle = subtitleOf(row), labelWeight = row.labelWeight ?: labelWeight,
                chevron = row.opensPage, slider = row.slider,
                // A menu style's bands: every other row of a group.
                stripe = (i - (rows.subList(0, i).indexOfLast { it.header } + 1)) % 2 == 1,
                onFocus = onFocused?.let { f -> { f(i) } },
            ) { onClick(i) }
        }
    }
    // A list of hundreds (an imported cheat file: RetroArch's Platinum one has 2321) is composed only where it's on
    // screen: all of them at once held the UI thread for seconds, and the app stopped answering (GitHub #786). Its
    // scroll position isn't [scroll]'s, which only SETTINGS' home pages (far shorter) keep.
    if (rows.size > LAZY_ROWS && tops == null) {
        val lazy = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = cursor.coerceIn(0, rows.size - 1))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = lazy) {
            items(rows.size) { i -> RowAt(i, rows[i]) }
            item { footer() }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        rows.forEachIndexed { i, row -> RowAt(i, row) }
        footer()
    }
}

/** Past this many rows a [GroupedRows] list is lazy. */
private const val LAZY_ROWS = 100

/** A group's title in a settings list ([groupTitle]'s look, for lists built row by row: GAME BUTTONS). [first] = the
 * list's top line, with less room above it. */
@Composable
fun GroupTitleLine(label: String, m: GbaTextMetrics, first: Boolean, modifier: Modifier = Modifier) {
    val band = OptionColors.groupTitleFill
    val style = OptionColors.style
    if (style != null) return StyledGroupTitle(label, m, first, style, modifier)
    if (band == null) {
        GbaText(
            tr(label), OptionColors.value, OptionColors.valueShadow, m,
            modifier = modifier.padding(start = m.u * 8, top = m.u * (if (first) 1 else 6), bottom = m.u),
        )
    } else {
        // Black text on white rows (Gen 1): the title is a black band, so it can't pass for a row.
        GbaText(
            tr(label), OptionColors.groupTitleText, OptionColors.groupTitleShadow, m,
            modifier = modifier
                .padding(start = m.u * 2, end = m.u * 2, top = m.u * (if (first) 1 else 6), bottom = m.u * 2)
                .fillMaxWidth()
                .drawBehind { drawPixelRoundRect(band, radius = m.u.toPx() * 2, step = m.u.toPx()) }
                .padding(horizontal = m.u * 6, vertical = m.u * 2),
        )
    }
}

/**
 * A group title in a [com.pokedaisy.app.companion.ui.theme.UiStyle] other than CLASSIC: SUMMARY / NOTEBOOK a tab sitting on
 * a rule across the list (the summary's TRAINER MEMO, the bag's pocket tab), DEVICE a band the list's width, CARD a pill.
 */
@Composable
internal fun StyledGroupTitle(
    label: String, m: GbaTextMetrics, first: Boolean, style: com.pokedaisy.app.companion.ui.theme.UiStylePalette, modifier: Modifier,
) {
    val u = m.u
    val fill = OptionColors.tint(style.sectionFill)
    val small = rememberGbaTextMetrics(1f)
    val text = @Composable { mod: Modifier -> GbaText(tr(label), style.sectionText, style.sectionShadow, small, modifier = mod) }
    val top = u * (if (first) 1 else 6)
    // The summary pages' section band, the list's width.
    text(
        modifier.padding(start = u * 2, end = u * 2, top = top, bottom = u * 2).fillMaxWidth()
            .drawBehind { drawPixelRoundRect(fill, radius = u.toPx() * 3, step = u.toPx()) }
            .padding(horizontal = u * 6, vertical = u * 2),
    )
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
 * How SETTINGS' lists look, on both screens (the two rows at the top of HOME, [settingsLookRows]): the grey description line
 * under a row ([descriptions]; a featured group's tagline stays) and the group tabs beside a long list ([tabs]). Compose
 * state, so a flip redraws both screens; [onChange] keeps them (the app sets it to Prefs at start).
 */
object SettingsLook {
    var descriptions by mutableStateOf(true)
    var tabs by mutableStateOf(true)
    var onChange: ((descriptions: Boolean, tabs: Boolean) -> Unit)? = null

    fun set(descriptions: Boolean = this.descriptions, tabs: Boolean = this.tabs) {
        this.descriptions = descriptions
        this.tabs = tabs
        onChange?.invoke(descriptions, tabs)
    }
}

/** SETTINGS' first two rows, on both screens: DESCRIPTIONS and GROUP TABS ([SettingsLook]). [changed] redraws the page. */
fun settingsLookRows(changed: () -> Unit): List<SettingRow> {
    fun shown(on: Boolean) = if (on) tk("SHOWN") else tk("HIDDEN")
    return listOf(
        SettingRow(
            tk("DESCRIPTIONS"), shown(SettingsLook.descriptions),
            labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.SETTINGS_LOOK),
            subtitle = "The grey line under each setting",
        ) { SettingsLook.set(descriptions = !SettingsLook.descriptions); changed() },
        SettingRow(
            tk("GROUP TABS"), shown(SettingsLook.tabs),
            labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.SETTINGS_LOOK),
            subtitle = "The group buttons beside this list",
        ) { SettingsLook.set(tabs = !SettingsLook.tabs); changed() },
    )
}

/** APP > TIPS, on both screens ([com.pokedaisy.app.companion.FeatureTips]): now and then a card about a feature the
 *  player hasn't found yet. */
fun tipsRow(on: Boolean, set: (Boolean) -> Unit): SettingRow = SettingRow(
    tk("TIPS"), if (on) tk("ON") else tk("OFF"), subtitle = "Now and then, a feature you haven't tried yet",
    labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.TIPS),
) { set(!on) }

/** COMPANION LOOK (SCREEN, under THEME, both screens): GAME = the companion and the HUD in the running game's own look
 * where it has one ([CompanionSkins]), CLASSIC = the app's own in every game. [changed] redraws the page. */
fun gameLookRow(changed: () -> Unit): SettingRow = SettingRow(
    tk("COMPANION LOOK"), if (CompanionSkins.enabled) tk("GAME") else tk("CLASSIC"),
    labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.GAME_LOOK),
    subtitle = "The game's own style, or the app's classic one",
) { CompanionSkins.change(!CompanionSkins.enabled); changed() }

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
