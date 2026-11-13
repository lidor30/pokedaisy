package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.theme.DarkMode
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.coop.CoopInvite
import com.pokedaisy.app.companion.coop.CoopRelay
import com.pokedaisy.app.companion.coop.CoopSession
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.overlay.overlayChoices
import com.pokedaisy.app.overlay.overlayLabel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.achievements.RetroAchievements
import com.pokedaisy.app.cheats.Cheat
import com.pokedaisy.app.cheats.CheatCheck
import com.pokedaisy.app.cheats.CheatCodes
import com.pokedaisy.app.cheats.CheatStore
import com.pokedaisy.app.cheats.CheatType
import com.pokedaisy.app.companion.ui.AppBackdrop
import com.pokedaisy.app.companion.ui.AspectPicker
import com.pokedaisy.app.companion.ui.aspectLabel
import com.pokedaisy.app.companion.ui.portraitPlaceLabel
import com.pokedaisy.app.companion.ui.CoffeeCup
import com.pokedaisy.app.companion.ui.DiscordMark
import com.pokedaisy.app.companion.Discord
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GitHubMark
import com.pokedaisy.app.companion.ui.GoldTrophy
import com.pokedaisy.app.companion.ui.GroupedRows
import com.pokedaisy.app.companion.ui.filterRows
import com.pokedaisy.app.companion.ui.STATUS_BAR_PLACES
import com.pokedaisy.app.companion.ui.statusBarLabel
import com.pokedaisy.app.companion.ui.SettingRow
import com.pokedaisy.app.companion.ui.groupTitle
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.KeyBindRow
import com.pokedaisy.app.companion.ui.OptionLine
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.RailedWindow
import com.pokedaisy.app.companion.ui.OptionRows
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.hasGrid
import com.pokedaisy.app.companion.next
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.ui.OptionTextField
import com.pokedaisy.app.companion.ui.PixelIcons
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.drawLayeredBox
import com.pokedaisy.app.companion.ui.inPx
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.AppTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.ui.PixelRoundedShape
import com.pokedaisy.app.companion.ui.drawPixelRoundRect

/**
 * Settings, in the companion SETTINGS tab's OPTION-screen look: a hub of
 * `LABEL  VALUE` rows (FF speed / touch controls / theme open a pick-one list,
 * on/off rows flip in place) plus one page each for Hotkeys, Game buttons,
 * Folders and Cover art. BACK pops back to the hub; BACK on the hub leaves.
 */
class SettingsActivity : ComponentActivity() {

    private enum class Screen { HOME, SPEED, DISPLAY, HOTKEYS, CONTROLS, SHADERS, FIELD_HUD, QUICK_MENU, TOUCH, OVERLAYS, FOLDERS, ROMS_FOLDERS, SAVES_FOLDER, SAVE_SEARCH, STATES_FOLDER, BACKUPS, COVER_ART, SCREENSCRAPER, HIDDEN, ACHIEVEMENTS, CHEATS, CHEAT_ADD, LICENSES, HELP, COOP_PAIR, COOP_JOIN, COOP_PARTNERS, COOP_RELAY }

    private lateinit var prefs: Prefs
    private var screen by mutableStateOf(Screen.HOME)
    private var deletingFile by mutableStateOf<File?>(null)
    private var folderTick by mutableIntStateOf(0)

    /** HOTKEYS / GAME BUTTONS: a row waiting for the keys to bind (KeyCapture takes every key meanwhile). */
    private val capture = com.pokedaisy.app.companion.KeyCapture()
    /** A captured chord that other hotkeys already have exactly ([taken]): MOVE takes it off them. */
    private class HotkeyClash(val action: Hotkeys.Action, val keys: List<Int>, val taken: List<Hotkeys.Action>)
    private var hotkeyClash by mutableStateOf<HotkeyClash?>(null)
    private var revision by mutableIntStateOf(0)
    private var aspectPicker by mutableStateOf(false)
    /** SCREEN > DS LAYOUT's pick-list with its miniatures (DsLayoutPicker) is open. */
    private var dsLayoutPicker by mutableStateOf(false)

    /** TOUCH CONTROLS' EDIT LAYOUT, over a stand-in of the game's screen; BACK goes to it ([touchBack]) while open. */
    private var touchEditing by mutableStateOf(false)
    private val touchBack = com.pokedaisy.app.companion.ui.CompanionBack()
    /** HOME's scroll, kept while a sub-page is open so BACK returns to the same rows. */
    private val homeScroll = androidx.compose.foundation.ScrollState(0)
    /** HOME has put the focus on its first setting (once: coming back from a sub-page keeps where it was). */
    private var homeFocused = false
    /** HOME's first setting, and whether anything on HOME has the focus: a D-pad press with nothing focused goes there
     * (opened by touch, the screen is in touch mode and focus can't be asked for until a key leaves it). */
    private var homeFirstRow: androidx.compose.ui.focus.FocusRequester? = null
    private var homeHasFocus = false

    /** The pop-ups open over Settings (pick-lists, confirms): B / BACK closes the newest, the focus goes back to its row. */
    private val overlays = com.pokedaisy.app.companion.ui.OverlayStack()
    /** B / BACK closed a pop-up: its key up is the pop-up's too (or Android's BACK would follow and leave Settings). */
    private var backTaken = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capture.onKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) backTaken = overlays.dismissTop()
                if (backTaken) return true
            } else if (event.action == KeyEvent.ACTION_UP && backTaken) {
                backTaken = false
                return true
            }
        }
        val dpad = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (dpad && event.action == KeyEvent.ACTION_DOWN && screen == Screen.HOME && !homeHasFocus && !overlays.isOpen) {
            val first = homeFirstRow
            if (first != null && runCatching { first.requestFocus() }.isSuccess) return true
        }
        return super.dispatchKeyEvent(event)
    }
    /** This screen's width / height - the top screen's, for the ASPECT preview. */
    private var screenAspect by mutableFloatStateOf(16f / 9f)

    /** The window's size (px), for TOUCH CONTROLS' stand-in of the game's screen. */
    private var rootSize by mutableStateOf(androidx.compose.ui.unit.IntSize(1920, 1080))

    private val filesRoot get() = getExternalFilesDir(null) ?: filesDir

    /** The game's screen's width / height: this one, or the second screen with SWAP SCREENS on. */
    private fun gameScreenAspect(): Float =
        if (Screens.swapped(this, prefs)) Screens.secondAspect(this) ?: screenAspect else screenAspect

    /** SWAP SCREENS only means something with two. */
    private val hasSecondScreen by lazy { Screens.hasSecond(this) }

    /** The companion goes on the second screen: there is one, and SINGLE SCREEN is off. */
    private val usesSecondScreen get() = hasSecondScreen && !prefs.singleScreen
    /** DS RESOLUTION's GPU renderer can run here (OpenGL ES 3.2). */
    private val dsGpu by lazy { GlCaps.gles32(this) }
    /** DS games start on DSperate (DS CORE): DS RESOLUTION and DS CPU BOOST are melonDS's, greyed out then. */
    private val dsOnDsperate get() = prefs.dsCore.runs() == MgbaCore.NDS_CORE_DSPERATE

    private val updates by lazy { AppUpdateFlow(this) }

    /** HELP: the manual (DocsScreen.kt), its place kept while Settings is open. */
    private val docsUi = com.pokedaisy.app.companion.ui.DocsUiState()
    private val docsSource by lazy { com.pokedaisy.app.companion.ui.DocsSources.of(this) }
    /** Opened straight on HELP (the library's ? button): BACK from its contents leaves. */
    private var helpOnly = false

    /** Opened straight on ROMS FOLDERS (the library's + > MANAGE ROMS FOLDERS): BACK from it leaves. */
    private var romsFoldersOnly = false

    /** Opened from a game's library menu (CHEATS): BACK from its cheats leaves, not to the hub. */
    private var cheatsOnly = false
    /** HOME's search box; kept while a sub-page it led to is open. */
    private var searchQuery by mutableStateOf("")

    /** Opened as the library's SETTINGS tab ([EXTRA_TAB]): the library's rail / bar beside the pages and its POKéDAISY
     * header over HOME, the library's GAMES / TOOLS handed back to it ([EXTRA_SECTION]). */
    private var tab = false
    /** The library's TOOLS carry a NEW tool (its rail's dot). */
    private var toolsNew = false
    /** The library's own text step (CompactTextOnSmallWindows'), for the rail and header: the pages here are a step smaller. */
    private var navTextStep by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RomFolder.useBestEffortStore(this)
        prefs = Prefs(this)
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        RetroAchievements.init(this)
        tab = intent?.getBooleanExtra(EXTRA_TAB, false) == true
        toolsNew = intent?.getBooleanExtra(EXTRA_TOOLS_NEW, false) == true
        intent?.getStringExtra(EXTRA_CHEATS_ROM)?.let { path ->
            cheatsOnly = true
            cheatRom = File(path)
            screen = Screen.CHEATS
        }
        when (intent?.getStringExtra(EXTRA_SCREEN)) {
            SCREEN_HELP -> { helpOnly = true; screen = Screen.HELP }
            SCREEN_ROMS_FOLDERS -> { romsFoldersOnly = true; screen = Screen.ROMS_FOLDERS }
        }
        // An invite link (CoopLinkActivity is exported: its extra is parsed again here, and ignored with CO-OP off).
        if (FeatureFlags.coop(this)) {
            intent?.getStringExtra(CoopLinkActivity.EXTRA_COOP_TICKET)?.let(CoopInvite::parse)?.let(::openCoopPair)
        }
        setContent {
            // Settings' text a step smaller everywhere (the user's call: the Thor's top screen read it large), not only on
            // small windows (CompactTextOnSmallWindows): a font pixel down, so it stays crisp.
            AppTheme { com.pokedaisy.app.companion.ui.CompactTextOnSmallWindows {
                val libraryStep = com.pokedaisy.app.companion.ui.LocalTextStep.current
                SideEffect { navTextStep = libraryStep }
                androidx.compose.runtime.CompositionLocalProvider(
                    com.pokedaisy.app.companion.ui.LocalTextStep provides -1,
                    com.pokedaisy.app.companion.ui.LocalOverlayStack provides overlays,
                ) { Root() }
            } }
        }
    }

    override fun onResume() {
        super.onResume()
        com.pokedaisy.app.companion.ui.OptionColors.inGame = false
        updates.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.cancel()
    }

    override fun onDestroy() {
        coop.leave()
        super.onDestroy()
    }

    // Analog L2 / R2 (Retroid's triggers send no keys): captured as BUTTON_L2 / R2, the keys the game
    // screen turns them into.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && capture.onTriggers(
                maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_THROTTLE)),
            )
        ) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // ---- shell --------------------------------------------------------------

    @Composable
    private fun Root() {
        BackHandler(enabled = screen != Screen.HOME) { capture.cancel(); goBack() }
        // The library's tab: BACK goes back to its games, with no slide (the rail stays where it is).
        BackHandler(enabled = tab && screen == Screen.HOME) { finishTab(com.pokedaisy.app.companion.ui.LibrarySection.GAMES) }
        BackHandler(enabled = aspectPicker) { aspectPicker = false }
        BackHandler(enabled = dsLayoutPicker) { dsLayoutPicker = false }
        BackHandler(enabled = touchEditing) { touchBack.back() }
        BackHandler(enabled = hotkeyClash != null) { hotkeyClash = null }
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(textScale = 1f)

        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize().onSizeChanged {
            if (it.width > 0 && it.height > 0) screenAspect = it.width.toFloat() / it.height
            rootSize = it
        }) {
            AppBackdrop(logos = tab)
            // The window's own shape from the first frame (rootSize starts as a guess: an upright phone showed the rail
            // for a frame before its bar).
            val wide = maxWidth >= maxHeight
            Row(Modifier.fillMaxSize()) {
            if (tab && wide) LibraryLook { nm, ns ->
                com.pokedaisy.app.companion.ui.LibraryNav(
                    com.pokedaisy.app.companion.ui.LibrarySection.SETTINGS, vertical = true, nm, ns, toolsBadge = toolsNew,
                    top = 16.dp, onPick = ::pickSection,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                if (tab && screen == Screen.HOME) LibraryLook { nm, _ ->
                    // The library's header in place of HOME's SETTINGS title, as on GAMES and TOOLS.
                    OptionTitleWindow(
                        // One button beside it: the name always fits (the games' header drops it on a phone held upright).
                        "POKéDAISY",
                        nm,
                        leading = { com.pokedaisy.app.companion.ui.PixelArt(com.pokedaisy.app.companion.ui.LOGO_ROWS, com.pokedaisy.app.companion.ui.LOGO_PALETTE, Modifier.size(nm.lineHeight)) },
                    ) {
                        Spacer(Modifier.width(nm.u * 4))
                        com.pokedaisy.app.companion.ui.OptionButton(
                            null, nm, onClick = { screen = Screen.HELP },
                            icon = Icons.AutoMirrored.Filled.HelpOutline, contentDescription = tr("Help"),
                        )
                    }
                    Spacer(Modifier.height(nm.u * 8))
                } else {
                OptionTitleWindow(
                    title = when (screen) {
                        Screen.HOME -> tk("SETTINGS")
                        Screen.SPEED -> tk("SPEED OPTIONS")
                        Screen.DISPLAY -> tk("DISPLAY OPTIONS")
                        Screen.HOTKEYS -> tk("HOTKEYS")
                        Screen.CONTROLS -> tk("GAME BUTTONS")
                        Screen.SHADERS -> tk("SHADERS")
                        Screen.FIELD_HUD -> tk("HUD")
                        Screen.QUICK_MENU -> tk("QUICK MENU")
                        Screen.TOUCH -> tk("TOUCH CONTROLS")
                        Screen.OVERLAYS -> tk("OVERLAY")
                        Screen.FOLDERS -> tk("FOLDERS")
                        Screen.ROMS_FOLDERS -> tk("ROMS FOLDERS")
                        Screen.SAVES_FOLDER -> tk("GAME SAVES")
                        Screen.SAVE_SEARCH -> tk("ALSO LOOK IN")
                        Screen.STATES_FOLDER -> tk("SAVE STATES")
                        Screen.BACKUPS -> tk("SAVE BACKUPS")
                        Screen.COVER_ART -> tk("COVER ART")
                        Screen.SCREENSCRAPER -> "ScreenScraper"
                        Screen.HIDDEN -> tk("HIDDEN GAMES")
                        Screen.ACHIEVEMENTS -> "RetroAchievements"
                        Screen.CHEATS -> tk("CHEATS")
                        Screen.CHEAT_ADD -> tk("ADD CODE")
                        Screen.LICENSES -> tk("LICENSES")
                        Screen.HELP -> tk("HELP")
                        Screen.COOP_PAIR, Screen.COOP_JOIN -> tk("PAIR")
                        Screen.COOP_PARTNERS -> tk("PARTNERS")
                        Screen.COOP_RELAY -> tk("RELAY")
                    },
                    m = m,
                    onBack = {
                        capture.cancel()
                        goBack()
                    },
                )
                Spacer(Modifier.height(m.u * 4))
                }
                when (screen) {
                    Screen.HOME -> HomeScreen(m)
                    Screen.SPEED -> SubRowsScreen(m) { speedRows() }
                    Screen.DISPLAY -> SubRowsScreen(m) { displayRows() }
                    Screen.HOTKEYS -> HotkeysScreen(m, small)
                    Screen.CONTROLS -> ControlsScreen(m, small)
                    Screen.SHADERS -> ShadersScreen(m)
                    Screen.FIELD_HUD -> FieldHudScreen(m)
                    Screen.QUICK_MENU -> QuickMenuScreen(m)
                    Screen.TOUCH -> TouchScreen(m)
                    Screen.OVERLAYS -> OverlaysScreen(m, small)
                    Screen.FOLDERS -> SubRowsScreen(m) { folderRows() }
                    Screen.ROMS_FOLDERS -> RomsFoldersScreen(m, small)
                    Screen.SAVES_FOLDER -> SavesFolderScreen(m, small)
                    Screen.SAVE_SEARCH -> AlsoLookInScreen(m, small)
                    Screen.STATES_FOLDER -> StatesFolderScreen(m, small)
                    Screen.BACKUPS -> SaveBackupsScreen(m, small)
                    Screen.COVER_ART -> CoverArtScreen(m, small)
                    Screen.SCREENSCRAPER -> ScreenScraperScreen(m, small)
                    Screen.HIDDEN -> HiddenScreen(m, small)
                    Screen.ACHIEVEMENTS -> AchievementsScreen(m, small)
                    Screen.CHEATS -> CheatsScreen(m, small)
                    Screen.CHEAT_ADD -> CheatAddScreen(m, small)
                    Screen.LICENSES -> LicensesScreen(m, small)
                    Screen.HELP -> com.pokedaisy.app.companion.ui.DocsReader(docsUi, m, docsSource, onOpenUrl = ::openUrl)
                    Screen.COOP_PAIR, Screen.COOP_JOIN -> coop.PairPage(m, small) { screen = Screen.HOME }
                    Screen.COOP_PARTNERS -> coop.PartnersPage(m, revision)
                    Screen.COOP_RELAY -> coop.RelayPage(m, small)
                }
            }
            if (tab && !wide) LibraryLook { nm, ns ->
                com.pokedaisy.app.companion.ui.LibraryNav(
                    com.pokedaisy.app.companion.ui.LibrarySection.SETTINGS, vertical = false, nm, ns, toolsBadge = toolsNew, onPick = ::pickSection,
                )
            }
            }
            }

            selector?.slider?.let { read ->
                // Read after every change (revision), so the pop-up shows the value it just moved to.
                val (value, slider) = revision.let { read() }
                com.pokedaisy.app.companion.ui.OptionSliderPopup(selector!!.title, value, slider, m, onDismiss = { selector = null })
            }
            selector?.takeIf { it.slider == null }?.let { sel ->
                OptionSelector(
                    sel.title, sel.options, sel.current, { it }, m,
                    onPick = { sel.onPick(it); selector = null; revision++ },
                    onDismiss = { selector = null },
                    badge = sel.badge,
                )
            }

            if (touchEditing) {
                val stage = touchStage(androidx.compose.ui.platform.LocalDensity.current.density)
                val shot by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { newestStateThumb()?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull()
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalCompanionBack provides touchBack) {
                    com.pokedaisy.app.companion.ui.TouchLayoutEditor(
                        stage, prefs.touchControlsStyle, m, shot = shot, drawBackdrop = true,
                        onDone = { layout ->
                            prefs.touchControlsStyle = prefs.touchControlsStyle.withLayout(stage.portrait, layout)
                            touchEditing = false
                            revision++
                        },
                    )
                }
            }

            if (aspectPicker) {
                val shot by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { newestStateThumb()?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull()
                    }
                }
                AspectPicker(
                    prefs.gameAspect, gameScreenAspect(), prefs.statusBar && !prefs.statusBarOnCompanion, shot, m,
                    onPick = { prefs.gameAspect = it; aspectPicker = false; revision++ },
                    onDismiss = { aspectPicker = false },
                )
            }

            if (dsLayoutPicker) {
                com.pokedaisy.app.companion.ui.DsLayoutPicker(
                    prefs.dsLayout, m,
                    onPick = { prefs.dsLayout = it; dsLayoutPicker = false; revision++ },
                    onDismiss = { dsLayoutPicker = false },
                )
            }

            if (confirmReplaceAll) {
                OptionConfirm(
                    title = tk("REPLACE ALL COVERS?"),
                    message = tr("Fetches new art from SteamGridDB for every ROM, replacing current covers (manually set ones too). A ROM with no art found keeps its cover."),
                    confirmLabel = tk("REPLACE"),
                    m = m,
                    onDismiss = { confirmReplaceAll = false },
                    onConfirm = { confirmReplaceAll = false; startCoverSync(replace = true) },
                )
            }

            hotkeyClash?.let { c ->
                OptionConfirm(
                    title = tk("KEY IN USE"),
                    message = tr(
                        "{0} is already {1}. Move it to {2}?",
                        com.pokedaisy.app.companion.KeyCapture.chordLabel(c.keys),
                        c.taken.joinToString(", ") { tr(it.title) }, tr(c.action.title),
                    ),
                    confirmLabel = tk("MOVE"),
                    m = m,
                    onDismiss = { hotkeyClash = null },
                    onConfirm = {
                        hotkeyClash = null
                        Hotkeys.setBinding(filesRoot, c.action, c.keys, takeFrom = c.taken)
                        revision++
                    },
                )
            }

            UpdateDialog(updates, m, small)

            removingCheat?.let { i ->
                val c = cheatList.getOrNull(i)
                if (c == null) removingCheat = null else OptionConfirm(
                    title = tk("REMOVE CHEAT?"),
                    message = c.name,
                    confirmLabel = tk("REMOVE"),
                    m = m,
                    onDismiss = { removingCheat = null },
                    onConfirm = {
                        removingCheat = null
                        saveCheats(cheatList.filterIndexed { j, _ -> j != i })
                        if (cheatList.isEmpty()) cheatRemoveMode = false
                    },
                )
            }

            coop.UnpairConfirm(m)

            cheatNotice?.let { (title, message) ->
                OptionConfirm(title, message, tk("OK"), m, onConfirm = { cheatNotice = null }, onDismiss = { cheatNotice = null }, cancelLabel = null)
            }

            overlayNotice?.let { (title, message) ->
                OptionConfirm(title, message, tk("OK"), m, onConfirm = { overlayNotice = null }, onDismiss = { overlayNotice = null }, cancelLabel = null)
            }

            overlayNeeds?.let { need ->
                OptionConfirm(
                    title = tk("PICK THE IMAGE"),
                    message = tr("{0} draws {1}, which wasn't picked with it. Pick {1} next: it's usually beside the .cfg or in a folder next to it.", need.cfg, need.image),
                    confirmLabel = tk("PICK"),
                    m = m,
                    onDismiss = { overlayNeeds = null; overlayPending = null },
                    onConfirm = { overlayNeeds = null; pickOverlayImage.launch(arrayOf("image/*")) },
                )
            }

            if (removingAllOverlays) {
                OptionConfirm(
                    title = tk("REMOVE ALL OVERLAYS?"),
                    message = tr("Every imported overlay ({0}) is deleted from this device. The built-in ones stay.", overlayStore.list().size),
                    confirmLabel = tk("REMOVE ALL"),
                    m = m,
                    onDismiss = { removingAllOverlays = false },
                    onConfirm = {
                        removingAllOverlays = false
                        overlayStore.deleteAll()
                        if (OverlayChoice.of(prefs.overlay) is OverlayChoice.Imported) prefs.overlay = OverlayChoice.None.key
                        overlayRemoveMode = false
                        revision++
                    },
                )
            }

            removingOverlay?.let { (id, label) ->
                OptionConfirm(
                    title = tk("REMOVE OVERLAY?"),
                    message = label,
                    confirmLabel = tk("REMOVE"),
                    m = m,
                    onDismiss = { removingOverlay = null },
                    onConfirm = {
                        removingOverlay = null
                        overlayStore.delete(id)
                        if (prefs.overlay == OverlayChoice.Imported(id).key) prefs.overlay = OverlayChoice.None.key
                        if (overlayStore.list().isEmpty()) overlayRemoveMode = false
                        revision++
                    },
                )
            }

            deletingFile?.let { f ->
                OptionConfirm(
                    title = tk("DELETE FILE?"),
                    message = f.name,
                    confirmLabel = tk("DELETE"),
                    m = m,
                    onDismiss = { deletingFile = null },
                    onConfirm = { f.delete(); deletingFile = null; folderTick++ },
                )
            }
        }
    }

    /** The library's rail / bar and header, at the library's text size (the pages here are a step smaller). */
    @Composable
    private fun LibraryLook(content: @Composable (GbaTextMetrics, GbaTextMetrics) -> Unit) {
        androidx.compose.runtime.CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalTextStep provides navTextStep) {
            content(rememberGbaTextMetrics(), rememberGbaTextMetrics(textScale = 1f))
        }
    }

    /** The tab's rail / bar: GAMES and TOOLS are the library's; SETTINGS again goes back to HOME. */
    private fun pickSection(section: com.pokedaisy.app.companion.ui.LibrarySection) {
        capture.cancel()
        if (section == com.pokedaisy.app.companion.ui.LibrarySection.SETTINGS) {
            cheatRemoveMode = false
            overlayRemoveMode = false
            screen = Screen.HOME
        } else finishTab(section)
    }

    /** Back to the library on [section], with no slide: the rail / bar stays put, only the page beside it changes. */
    private fun finishTab(section: com.pokedaisy.app.companion.ui.LibrarySection) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SECTION, section.name))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    /** Back one page: ADD CODE to CHEATS, a page to the hub, the hub (or cheats opened from a game's menu) out. */
    private fun goBack() {
        when {
            screen == Screen.CHEAT_ADD -> screen = Screen.CHEATS
            screen == Screen.HELP && docsUi.back() -> {}
            screen == Screen.HELP && helpOnly -> finish()
            // A save picked on the invite page: BACK to the save list (a link's page, from outside: the hub).
            screen == Screen.COOP_PAIR && coop.picking -> coop.leave()
            screen == Screen.COOP_PAIR || screen == Screen.COOP_JOIN -> { coop.leave(); screen = Screen.HOME }
            screen == Screen.HOME && tab -> finishTab(com.pokedaisy.app.companion.ui.LibrarySection.GAMES)
            screen == Screen.HOME || (screen == Screen.CHEATS && cheatsOnly) || (screen == Screen.ROMS_FOLDERS && romsFoldersOnly) -> finish()
            // FOLDERS' pages go back to it.
            screen in FOLDER_PAGES -> screen = Screen.FOLDERS
            // SHADERS and OVERLAY open from DISPLAY OPTIONS.
            screen == Screen.SHADERS || screen == Screen.OVERLAYS -> { overlayRemoveMode = false; screen = Screen.DISPLAY }
            else -> { cheatRemoveMode = false; overlayRemoveMode = false; screen = Screen.HOME }
        }
    }

    /** The newest savestate thumbnail of any game (a real frame for the ASPECT preview), or null. */
    private fun newestStateThumb(): File? =
        File(filesRoot, "states").listFiles().orEmpty()
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.isFile && it.name.matches(Regex("ss\\d+\\.png")) }
            .maxByOrNull { it.lastModified() }

    /** A pick-one list for a multi-choice setting; [options] are display labels. */
    private class Selector(
        val title: String,
        val options: List<String>,
        val current: String,
        val badge: (String) -> String? = { null },
        /** A slider setting's pop-up ([com.pokedaisy.app.companion.ui.OptionSliderPopup]) instead of a list: its value and
         *  slider, read fresh on each change. */
        val slider: (() -> Pair<String, com.pokedaisy.app.companion.ui.SettingSlider>)? = null,
        val onPick: (String) -> Unit,
    )

    private var selector by mutableStateOf<Selector?>(null)

    /** SOUND > GAME VOLUME's slider (the row's and its pop-up's): kept and heard at once. */
    private fun setGameVolume(percent: Int) {
        prefs.gameVolume = percent
        GameVolume.load(prefs)
        revision++
    }

    // ---- hub --------------------------------------------------------------

    /** A sub-page that's only rows (SPEED OPTIONS, DISPLAY OPTIONS), drawn like the hub's. */
    @Composable
    private fun SubRowsScreen(m: GbaTextMetrics, rows: () -> List<SettingRow>) {
        @Suppress("UNUSED_EXPRESSION") revision
        val list = rows()
        Column(Modifier.fillMaxWidth()) {
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                GroupedRows(list, m, cursor = -1, onClick = { list[it].onClick() })
            }
        }
    }

    /** SPEED OPTIONS: how fast-forward behaves and sounds (SCENE SPEEDS is the companion's: it needs the game). */
    private fun speedRows(): List<SettingRow> = listOf(
            // SMART: menus and the region map at 1x; NORMAL: fast everywhere.
            SettingRow(tk("FF MODE"), prefs.ffMode.label, subtitle = "SMART slows down for menus and the map") {
                val modes = FfMode.entries
                selector = Selector(tk("FF MODE"), modes.map { it.label }, prefs.ffMode.label) { l -> prefs.ffMode = modes.first { it.label == l } }
            },
            // STEADY / SPED-UP / OFF (FfMusicMode's order).
            SettingRow(tk("FF MUSIC"), prefs.ffMusicMode.label, subtitle = "What you hear while fast-forwarding") {
                val modes = FfMusicMode.entries
                selector = Selector(tk("FF MUSIC"), modes.map { it.label }, prefs.ffMusicMode.label) { l -> prefs.ffMusicMode = modes.first { it.label == l } }
            },
    )

    /** DISPLAY OPTIONS: what's around the game (the status bar, the shaders, an overlay) and an upright phone's companion. */
    private fun displayRows(): List<SettingRow> {
        fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
        return listOfNotNull(
            // Game, location, money, clock and battery: OFF, over the game, or over the companion's tabs.
            SettingRow(tk("STATUS BAR"), statusBarLabel(prefs.statusBar, prefs.statusBarOnCompanion), subtitle = "Place, money, clock and battery") {
                selector = Selector(tk("STATUS BAR"), STATUS_BAR_PLACES, statusBarLabel(prefs.statusBar, prefs.statusBarOnCompanion)) {
                    val i = STATUS_BAR_PLACES.indexOf(it)
                    if (i > 0) prefs.statusBarOnCompanion = i == 2
                    prefs.statusBar = i > 0
                }
            },
            // FILTER (LCD / LCD PAPER / SCANLINES / CRT) and GBA COLORS, on their own page.
            SettingRow(tk("SHADERS"), prefs.screenFilter.label, opensPage = true, subtitle = "LCD, CRT and scanline looks, smoothing") { screen = Screen.SHADERS },
            // A frame around the game: the built-in ones and imported RetroArch overlays, on their own page.
            SettingRow(tk("OVERLAY"), overlayLabel(overlayChoices(overlayStore), prefs.overlay), opensPage = true, subtitle = "A frame around the game") { screen = Screen.OVERLAYS },
            // A slow device: the companion redrawn less, without animations, so the game's buttons don't wait on it.
            com.pokedaisy.app.companion.ui.lightCompanionRow({ title, choices, current, onPick -> selector = Selector(title, choices, current) { onPick(it); revision++ } }) {
                com.pokedaisy.app.companion.LightCompanion.mode = it
                prefs.lightCompanion = it.id
            },
            // One screen held upright: the companion along the bottom, or right under the game (the touch controls below it).
            com.pokedaisy.app.companion.ui.uprightCompanionRow(
                prefs.portraitCompanionUnderGame, prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE,
                touchWouldShow(prefs.touchControlsStyle),
            ) { prefs.portraitCompanionUnderGame = it; revision++ }.takeIf { !usesSecondScreen },
        )
    }

    /** Like the companion's SETTINGS tab: `LABEL  VALUE` rows; on/off rows
     * flip on tap, multi-choice rows open a pick-one list, the rest open
     * their own page. */
    @Composable
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // Key.ButtonB / the keyboard controller, on older Compose
    private fun HomeScreen(m: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        LaunchedEffect(revision) { if (FeatureFlags.coop(this@SettingsActivity)) coop.loadPairs() }
        val rateLabels = RATES.map(::rateLabel)
        // The themes on offer today: the limited-time ones only in their dates.
        val themes = com.pokedaisy.app.companion.ui.theme.availableThemes()
        fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
        // The same groups and order as the bottom screen's SETTINGS where they overlap.
        val rows = listOfNotNull(
            groupTitle(tk("FF / SPEED")),
            SettingRow(tk("FF SPEED"), rateLabel(prefs.ffMaxSpeed)) {
                selector = Selector(tk("FF SPEED"), rateLabels, rateLabel(prefs.ffMaxSpeed)) {
                    prefs.ffMaxSpeed = RATES[rateLabels.indexOf(it)]
                }
            },
            // Keeps the last ~20 s; the REWIND HOLD hotkey plays them backwards.
            SettingRow(tk("REWIND"), if (prefs.rewind) tk("ON") else tk("OFF")) { prefs.rewind = !prefs.rewind; revision++ },
            // FF MODE and FF MUSIC: set once and rarely touched, on their own page (the companion's has SCENE SPEEDS too).
            SettingRow(tk("SPEED OPTIONS"), null, subtitle = "FF mode and FF music", opensPage = true) { screen = Screen.SPEED },
            groupTitle(tk("SOUND")),
            // The game's loudness under the device's own volume (100% = the device's); MUTE GAME keeps it for after.
            SettingRow(tk("GAME VOLUME"), com.pokedaisy.app.companion.ui.gameVolumeLabel(prefs.gameVolume),
                       labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.GAME_VOLUME),
                       slider = com.pokedaisy.app.companion.ui.gameVolumeSlider(prefs.gameVolume, ::setGameVolume)) {
                // A tap opens it in a pop-up with a bigger bar (the user's call).
                selector = Selector(tk("GAME VOLUME"), emptyList(), "", slider = {
                    com.pokedaisy.app.companion.ui.gameVolumeLabel(prefs.gameVolume) to
                        com.pokedaisy.app.companion.ui.gameVolumeSlider(prefs.gameVolume, ::setGameVolume)
                }) {}
            },
            SettingRow(tk("MUTE GAME"), onOff(prefs.gameMuted),
                       labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.GAME_VOLUME)) {
                prefs.gameMuted = !prefs.gameMuted
                GameVolume.load(prefs)
                revision++
            },
            // Equalizer apps and the like on the game's sound (AudioEffects): the game's tracks take it at their next start.
            SettingRow(tk("AUDIO EFFECTS"), onOff(prefs.audioEffects),
                       labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.AUDIO_EFFECTS),
                       subtitle = "For equalizer apps, on the next start") {
                prefs.audioEffects = !prefs.audioEffects
                revision++
            },
            groupTitle(tk("SCREEN")),
            // The game at 3:2, stretched to fill the screen, or WIDE (more of the map, BETA); picked by preview.
            SettingRow(tk("ASPECT"), aspectLabel(prefs.gameAspect),
                       badge = tk("BETA").takeIf { prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE }, subtitle = "Original shape, stretched, or more of the map") { aspectPicker = true },
            // EXPAND only: whether a conversation, sign, menu or picture over the field pulls the zoom in (battles always do).
            SettingRow(tk("AUTO ZOOM IN"), onOff(prefs.expandZoomOnText)) {
                prefs.expandZoomOnText = !prefs.expandZoomOnText
                revision++
            }.takeIf { prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE },
            // EXPAND only: the right stick moves the view.
            SettingRow(
                tk("LOOK AROUND"), onOff(prefs.lookAround), subtitle = "The right stick moves the view",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.LOOK_AROUND),
            ) {
                prefs.lookAround = !prefs.lookAround
                revision++
            }.takeIf { prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE },
            // DS games: which emulator runs them (companion.DsCore): melonDS, or DSperate for weak devices (AUTO picks).
            SettingRow(
                tk("DS CORE"), prefs.dsCore.valueLabel(), subtitle = "DSPERATE runs on weaker devices",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DS_CORE),
            ) {
                val cores = com.pokedaisy.app.companion.DsCore.entries
                selector = Selector(tk("DS CORE"), cores.map { it.label }, prefs.dsCore.label) { l ->
                    prefs.dsCore = cores.first { it.label == l }
                    revision++
                }
            }.takeIf { com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT },
            // DS games: the 3D at 2x to 8x the DS's own, drawn on the GPU (nds/pk_nds_gl.h); ORIGINAL keeps the CPU's,
            // pixel for pixel. N/A without OpenGL ES 3.2, or on DSperate (melonDS's renderer).
            (dsGpu && !dsOnDsperate).let { gpu ->
                SettingRow(
                    tk("DS RESOLUTION"), if (gpu) com.pokedaisy.app.companion.ui.dsResolutionLabel(prefs.dsResolution) else tk("N/A"),
                    enabled = gpu, subtitle = if (dsOnDsperate) "Unavailable on DSPERATE" else null,
                    labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DS_RESOLUTION),
                ) {
                    if (!gpu) return@SettingRow
                    val labels = com.pokedaisy.app.companion.ui.dsResolutionLabels()
                    selector = Selector(tk("DS RESOLUTION"), labels, com.pokedaisy.app.companion.ui.dsResolutionLabel(prefs.dsResolution)) {
                        prefs.dsResolution = labels.indexOf(it) + 1
                    }
                }
            }.takeIf { com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT },
            // DS games: the emulated CPU at twice the DS's clock, so a game's own slow frames go.
            SettingRow(
                tk("DS CPU BOOST"), if (dsOnDsperate) tk("N/A") else onOff(prefs.dsCpuBoost), enabled = !dsOnDsperate,
                subtitle = if (dsOnDsperate) "Unavailable on DSPERATE" else "Fewer slowdowns in DS games",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DS_CPU_BOOST),
            ) {
                if (dsOnDsperate) return@SettingRow
                prefs.dsCpuBoost = !prefs.dsCpuBoost
                revision++
            }.takeIf { com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT },
            // A DS game on two screens: its touch screen at its 4:3 with the COMPANION bar, or stretched over the second display.
            SettingRow(
                tk("DS TOUCH SCREEN"), if (prefs.dsTouchFill) tk("STRETCH") else tk("ORIGINAL"), subtitle = "The touch screen on your second screen",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DS_TOUCH_FILL),
            ) {
                prefs.dsTouchFill = !prefs.dsTouchFill
                revision++
            }.takeIf { com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT && usesSecondScreen },
            // A DS game on one screen held sideways: where its two screens go (DsLayout), picked from miniatures.
            SettingRow(
                tk("DS LAYOUT"), prefs.dsLayout.label, subtitle = "Where a DS game's two screens go",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DS_LAYOUT),
            ) { dsLayoutPicker = true }.takeIf { com.pokedaisy.app.companion.data.TelemetrySampler.NDS_SUPPORT && !usesSecondScreen },
            // The party, a minimap, the balls and BAG / WILD over the game on the field: its own page (layout, look, size, parts).
            SettingRow(tk("HUD"), onOff(prefs.fieldHud), labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.HUD), opensPage = true, subtitle = "Party, minimap and bag over the game") { screen = Screen.FIELD_HUD },
            // One screen: FIGHT, POKéMON and RUN in the room beside a battle (BattleButtons).
            SettingRow(
                tk("BATTLE BUTTONS"), onOff(prefs.battleButtons), subtitle = "FIGHT, POKéMON and RUN beside a battle",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.BATTLE_BUTTONS),
            ) {
                prefs.battleButtons = !prefs.battleButtons
                revision++
            }.takeIf { !usesSecondScreen },
            // STATUS BAR, SHADERS, OVERLAY (and an upright phone's COMPANION): on their own page.
            SettingRow(tk("DISPLAY OPTIONS"), null, subtitle = "Status bar, shaders, overlay", opensPage = true) { screen = Screen.DISPLAY },
            // One screen sideways: the companion as a window in the middle over the game, or the side panel.
            SettingRow(
                tk("COMPANION VIEW"), com.pokedaisy.app.companion.ui.companionViewLabel(prefs.companionWindow),
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.COMPANION_WINDOW), subtitle = "A window over the game, or a side panel",
            ) {
                prefs.companionWindow = !prefs.companionWindow
                revision++
            }.takeIf { !usesSecondScreen },
            // A foldable half open: the game on one side of the hinge, the companion on the other (once the device has
            // shown a hinge).
            SettingRow(
                tk("FOLD LAYOUT"), onOff(prefs.foldLayout),
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.FOLD_LAYOUT),
                subtitle = "Half open: the game on one side of the fold",
            ) {
                prefs.foldLayout = !prefs.foldLayout
                revision++
            }.takeIf { prefs.foldSeen && !usesSecondScreen },
            // The white tab at the screen's right edge that opens it (BACK and the HUD's COMPANION still do).
            SettingRow(
                tk("COMPANION BUTTON"), onOff(prefs.companionTab),
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.COMPANION_TAB),
                subtitle = "The tab at the right edge that opens it",
            ) {
                prefs.companionTab = !prefs.companionTab
                revision++
            }.takeIf { !usesSecondScreen },
            // Game and companion trade screens; the game picks it up on resume.
            SettingRow(tk("SWAP SCREENS"), onOff(Screens.swapped(this, prefs))) {
                Screens.setSwapped(this, prefs, !Screens.swapped(this, prefs))
                revision++
            }.takeIf { usesSecondScreen },
            // Two screens played on one: the second is free for other apps, the companion opens over the game.
            SettingRow(
                tk("SINGLE SCREEN"), onOff(prefs.singleScreen), subtitle = "Second screen free; companion over the game",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.SINGLE_SCREEN),
            ) {
                prefs.singleScreen = !prefs.singleScreen
                revision++
            }.takeIf { hasSecondScreen },
            // Changes the colors immediately, everywhere - the companion screen too.
            SettingRow(
                tk("THEME"), AppColors.theme.label,
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.THEMES),
            ) {
                selector = Selector(
                    tk("THEME"), themes.map { it.label }, AppColors.theme.label,
                    badge = { l -> tk("LIMITED").takeIf { themes.firstOrNull { it.label == l }?.season != null } },
                ) { l -> themes.firstOrNull { it.label == l }?.let { com.pokedaisy.app.companion.ui.theme.pickTheme(this@SettingsActivity, it) } }
            },
            // OFF / ON / AUTO (the device's), cycled on a tap so each look shows at once: both screens switch together.
            SettingRow(tk("DARK MODE"), DarkMode.setting.label) {
                DarkMode.set(this@SettingsActivity, DarkMode.setting.next())
                revision++
            },
            // The game's frames per second and the app's CPU share, in the game's corner.
            SettingRow(tk("FPS / CPU"), onOff(prefs.showPerformance), subtitle = "Speed and CPU use in the game's corner") {
                prefs.showPerformance = !prefs.showPerformance
                revision++
            },
            groupTitle(tk("LIBRARY")),
            // On: a game opens where it was left. Off: at its title screen, from the save.
            SettingRow(tk("RESUME GAMES"), onOff(prefs.autoResume), subtitle = "Open a game right where you left it") {
                prefs.autoResume = !prefs.autoResume
                revision++
            },
            // The library's names: the game's own, or the file's.
            SettingRow(
                tk("GAME NAMES"), if (prefs.libraryFileNames) tk("FILE NAME") else tk("GAME TITLE"), subtitle = "How the library names your games",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.GAME_NAMES),
            ) {
                prefs.libraryFileNames = !prefs.libraryFileNames
                revision++
            },
            // Leaves out what the companion can't read; the games stay in their folders.
            SettingRow(
                tk("SUPPORTED ONLY"), onOff(prefs.librarySupportedOnly), subtitle = "Hide games the companion can't read",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.SUPPORTED_ONLY),
            ) {
                prefs.librarySupportedOnly = !prefs.librarySupportedOnly
                revision++
            },
            // Two screens: the game list on one, the game you're on (its media) on the other. X swaps them.
            SettingRow(tk("DUAL-SCREEN LAUNCHER"), onOff(prefs.dualScreenLauncher), subtitle = "Game list on one screen, its art on the other") {
                prefs.dualScreenLauncher = !prefs.dualScreenLauncher
                revision++
            }.takeIf { hasSecondScreen },
            SettingRow(tk("COVER ART"), onOff(prefs.steamGridDbApiKey != null || prefs.raWebApiKey != null), opensPage = true, subtitle = "Box art from RetroAchievements and SteamGridDB") { screen = Screen.COVER_ART },
            // Every game's media (box art, screenshots, logos, videos, facts): first for covers, and the launcher's other screen.
            SettingRow("ScreenScraper", prefs.screenScraperAccount?.user?.uppercase() ?: tk("OFF"), opensPage = true) {
                ssLogin.refresh(prefs) { r -> runOnUiThread { r() } }
                screen = Screen.SCREENSCRAPER
            }.takeIf { ScreenScraperClient.enabled },
            SettingRow(tk("FOLDERS"), null, opensPage = true, subtitle = "Where ROMs, saves and states live", labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.ROMS_FOLDERS)) { screen = Screen.FOLDERS },
            SettingRow(tk("HIDDEN GAMES"), RomFolder.hiddenRoms(this@SettingsActivity, prefs).size.takeIf { it > 0 }?.toString() ?: tk("NONE"), opensPage = true) { screen = Screen.HIDDEN },
            // A game's GameShark / Action Replay / CodeBreaker codes, on their own page.
            SettingRow(tk("CHEATS"), onOff(prefs.cheatsEnabled), opensPage = true) { screen = Screen.CHEATS },
            // The tools (PATCH A ROM, LINK PLAY, SAVE EDITOR, TRADE SAVES) are the library's TOOLS tab, not rows here (the user's
            // call, 2026-11-03: they have a tab of their own).
            groupTitle(tk("ONLINE")),
            // Signed in = on; the account's name, else OFF.
            SettingRow(
                "RetroAchievements", prefs.raUsername?.uppercase() ?: tk("OFF"), labelBadge = tk("BETA"),
                labelIcon = { GoldTrophy() }, opensPage = true,
            ) { screen = Screen.ACHIEVEMENTS },
            *coopRows().toTypedArray(),
            groupTitle(tk("CONTROLS")),
            SettingRow(tk("GAME BUTTONS"), null, subtitle = "Key bindings: which button presses A, B, START...", opensPage = true) { screen = Screen.CONTROLS },
            SettingRow(tk("HOTKEYS"), onOff(prefs.hotkeysEnabled), opensPage = true, subtitle = "Button combos for states, FF and more") { screen = Screen.HOTKEYS },
            SettingRow(tk("QUICK MENU"), null, subtitle = "Actions on a held button", labelBadge = com.pokedaisy.app.companion.ui.quickMenuBadge(), opensPage = true) { screen = Screen.QUICK_MENU },
            // The on-screen buttons: when they show, layouts, size, extras, EDIT LAYOUT; the game picks it up on resume.
            SettingRow(tk("TOUCH CONTROLS"), prefs.touchControlsStyle.show.label, labelBadge = com.pokedaisy.app.companion.ui.touchControlsBadge(), opensPage = true, subtitle = "The on-screen buttons: layout, look, skins") { screen = Screen.TOUCH },
            groupTitle(tk("APP")),
            // Tap: look for a newer release on GitHub now.
            SettingRow(tk("VERSION"), if (updates.checking) tk("CHECKING…") else BuildConfig.VERSION_NAME) { updates.check(manual = true) },
            // Back to the library, which opens the first-time setup again.
            SettingRow(tk("RUN SETUP"), null, subtitle = "First-time setup again: folders, buttons, art") { prefs.setupRequested = true; finish() },
            // Opt-in crash reports (CrashReports.kt); only in builds that can send them.
            SettingRow(tk("CRASH REPORTS"), onOff(prefs.crashReportsEnabled)) {
                CrashReports.setEnabled(prefs, !prefs.crashReportsEnabled)
                revision++
            }.takeIf { CrashReports.available },
            // A card now and then about a feature the player hasn't found (FeatureTips), in game.
            com.pokedaisy.app.companion.ui.tipsRow(prefs.tips) { on ->
                prefs.tips = on
                com.pokedaisy.app.companion.FeatureTips.scheduler?.enabled = on
                revision++
            },
            // The GPL's notices and every bundled component's license.
            SettingRow(tk("LICENSES"), null, opensPage = true) { screen = Screen.LICENSES },
            // AUTO: the ROM's language in game, the device's here. Applies at once, both screens.
            SettingRow("LANGUAGE", L10n.settingLabel(prefs.appLanguage)) {
                val opts = L10n.options
                selector = Selector("LANGUAGE", opts.map { it.second }, opts.first { it.first == prefs.appLanguage }.second) { l ->
                    prefs.appLanguage = opts.first { it.second == l }.first
                    L10n.apply(prefs.appLanguage, null)
                }
            },
            // PokeDaisy's Discord server in the browser (or the Discord app).
            SettingRow(
                "DISCORD", null,
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.DISCORD), labelIcon = { DiscordMark() },
            ) { openUrl(Discord.URL) },
            // The manual (docs/manual, served by the website), readable offline once fetched.
            SettingRow(tk("HELP"), null, opensPage = true) { screen = Screen.HELP },
        )
        // Search: every row whose name, value or keywords (SETTING_KEYWORDS: "rebind", "keybinds"...) match.
        val shown = filterRows(rows, searchQuery)
        // D-pad focus starts on the first setting (it used to land on the search box first), once per visit to Settings;
        // B / BACK in the search box comes back there too.
        val firstRow = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.DisposableEffect(firstRow) {
            homeFirstRow = firstRow
            onDispose { if (homeFirstRow === firstRow) homeFirstRow = null; homeHasFocus = false }
        }
        // Once the window has the focus (asked before, it went nowhere). A new window starts in touch mode, where
        // nothing takes the focus and the first D-pad press only leaves touch mode: switch to keys first.
        val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
        LaunchedEffect(windowFocused) {
            if (homeFocused || !windowFocused) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            if (runCatching { firstRow.requestFocus() }.isSuccess) homeFocused = true
        }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxSize().onFocusChanged { homeHasFocus = it.hasFocus }) {
            OptionTextField(
                searchQuery, { searchQuery = it }, m,
                Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    val back = e.key == androidx.compose.ui.input.key.Key.Back || e.key == androidx.compose.ui.input.key.Key.ButtonB
                    if (!back) return@onPreviewKeyEvent false
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        keyboard?.hide()
                        runCatching { firstRow.requestFocus() }
                    }
                    true
                },
                placeholder = tr("SEARCH SETTINGS (e.g. KEY BINDINGS)"),
                leadingIcon = PixelIcons.search,
            )
            Spacer(Modifier.height(m.u * 4))
            RailedWindow(m, Modifier.fillMaxWidth().weight(1f), rail = shown.isNotEmpty()) {
                if (shown.isEmpty()) {
                    GbaText(
                        tr("NOTHING MATCHES \"{0}\"", searchQuery.trim()), OptionColors.muted, OptionColors.mutedShadow, m,
                        modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                    )
                } else if (searchQuery.isBlank()) {
                    GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() }, scroll = homeScroll, firstRowFocus = firstRow, groupTabs = true)
                } else {
                    GroupedRows(shown, m, cursor = -1, onClick = { shown[it].onClick() }, firstRowFocus = firstRow)
                }
            }
            Spacer(Modifier.height(m.u * 4))
            AboutFooter(m)
        }
    }

    /** SHADERS: what the game is drawn through, like the companion's page; the game picks it up on resume. */
    @Composable
    private fun ShadersScreen(m: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val smoothingAvailable = remember { GlCaps.gles3(this) }
        val rows = listOf(
            // NONE / LCD grid / LCD PAPER / SCANLINES / CRT.
            // Each tap steps to the next filter, like the companion's.
            SettingRow(tk("FILTER"), prefs.screenFilter.label) {
                prefs.screenFilter = prefs.screenFilter.next()
                revision++
            },
            // SOFT / MEDIUM / STRONG, cycling too; greyed out for a filter with no grid.
            SettingRow(tk("GRID"), prefs.gridStrength.label, enabled = prefs.screenFilter.hasGrid) {
                if (prefs.screenFilter.hasGrid) {
                    prefs.gridStrength = prefs.gridStrength.next()
                    revision++
                }
            },
            // OFF / xBRZ / xBR / SCALEFX, cycling too: smooths the game's edges before FILTER (the game only).
            SettingRow(tk("SMOOTHING"), if (smoothingAvailable) prefs.smoothing.label else tk("N/A"), enabled = smoothingAvailable) {
                if (smoothingAvailable) {
                    prefs.smoothing = prefs.smoothing.next()
                    revision++
                }
            },
            // The colours as the GBA's own LCD showed them; stacks with any filter.
            SettingRow(tk("GBA COLORS"), if (prefs.gbaColors) tk("ON") else tk("OFF")) {
                prefs.gbaColors = !prefs.gbaColors
                revision++
            },
            // The GBA's screen with no light of its own: white shows its backing, dark pixels cast a shadow; stacks too.
            SettingRow(
                tk("UNLIT SCREEN"), if (prefs.unlitScreen) tk("ON") else tk("OFF"),
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.UNLIT_SCREEN),
            ) {
                prefs.unlitScreen = !prefs.unlitScreen
                revision++
            },
            // FILTER and GBA COLORS over the companion too (and the status bar).
            SettingRow(tk("ON COMPANION"), if (prefs.companionShaders) tk("ON") else tk("OFF")) {
                prefs.companionShaders = !prefs.companionShaders
                revision++
            },
        )
        Column(Modifier.fillMaxWidth()) {
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
        }
    }

    /** FIELD HUD: the HUD over the overworld, like the companion's page; the game picks it up on resume. */
    @Composable
    private fun FieldHudScreen(m: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val rows = com.pokedaisy.app.companion.ui.fieldHudRows(
            prefs.fieldHud, prefs.fieldHudStyle,
            setOn = { prefs.fieldHud = it; revision++ },
            setStyle = { prefs.fieldHudStyle = it; revision++ },
        )
        Column(Modifier.fillMaxWidth()) {
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
        }
    }

    /**
     * TOUCH CONTROLS: the companion's page (the preview, the rows); EDIT LAYOUT here moves the buttons over a stand-in of
     * the game's screen laid out for the way the phone is held now. The game picks it all up on resume.
     */
    @Composable
    private fun TouchScreen(m: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val style = prefs.touchControlsStyle
        val skins = remember(revision) { skinStore.list() }
        val rows = com.pokedaisy.app.companion.ui.touchControlsRows(
            style, set = { prefs.touchControlsStyle = it; revision++ },
            onEdit = { touchEditing = true },
            underGame = prefs.portraitCompanionUnderGame.takeIf { !usesSecondScreen },
            setUnderGame = { prefs.portraitCompanionUnderGame = it; revision++ },
            expand = prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE, touchShown = touchWouldShow(style),
            skins = skins, skinUi = touchSkinUi, onImportSkin = { pickSkin.launch(arrayOf("*/*")) },
            feel = { TouchHaptics.buzz(this, it) },
        )
        val shot by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching { newestStateThumb()?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull()
            }
        }
        com.pokedaisy.app.companion.ui.TouchControlsPage(
            touchStage(androidx.compose.ui.platform.LocalDensity.current.density), style, shot, m, Modifier.fillMaxSize(),
            underGame = prefs.portraitCompanionUnderGame, portraitRatio = prefs.portraitCompanionRatio,
            expand = prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE, touchShown = touchWouldShow(style),
            skins = skins, skinUi = touchSkinUi, setStyle = { prefs.touchControlsStyle = it; revision++ },
            onDeleteSkin = { skin ->
                skinStore.delete(skin.id)
                val st = prefs.touchControlsStyle
                prefs.touchControlsStyle = st.copy(skinGba = st.skinGba.takeIf { it != skin.id }, skinGb = st.skinGb.takeIf { it != skin.id })
                revision++
            },
        ) { mod ->
            OptionListWindow(m, mod) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
        }
    }

    /** Whether a game opened now would show the touch controls (SHOW, and AUTO's controller check). */
    private fun touchWouldShow(style: com.pokedaisy.app.companion.TouchControlsStyle) = when (style.show) {
        com.pokedaisy.app.companion.TouchShow.ALWAYS -> true
        com.pokedaisy.app.companion.TouchShow.NEVER -> false
        com.pokedaisy.app.companion.TouchShow.AUTO -> !Controllers.anyConnected()
    }

    /** This screen as the game's would be laid out (no game open here): its size, the companion's place upright,
     * EXPAND with UNDER GAME's strip for the controls. */
    private fun touchStage(density: Float): com.pokedaisy.app.companion.TouchStage {
        val style = prefs.touchControlsStyle
        val expand = prefs.gameAspect == com.pokedaisy.app.companion.GameAspect.WIDE
        val w = rootSize.width.toFloat()
        val h = rootSize.height.toFloat()
        val strip = if (h > w && prefs.portraitCompanionUnderGame && touchWouldShow(style))
            com.pokedaisy.app.companion.ui.TouchGeometry.naturalHeight(style, w, density) else 0
        return com.pokedaisy.app.companion.ui.simulateTouchStage(
            w, h, density,
            portraitRatio = prefs.portraitCompanionRatio, underGame = prefs.portraitCompanionUnderGame,
            expand = expand, padStrip = strip,
        )
    }

    /** QUICK MENU: which actions it shows, like the companion's page; its button is HOTKEYS' QUICK MENU. */
    @Composable
    private fun QuickMenuScreen(m: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val binding = remember(revision) { Hotkeys.load(filesRoot).rawBindings[Hotkeys.Action.QUICK_MENU].orEmpty() }
        val rows = com.pokedaisy.app.companion.ui.quickMenuRows(
            prefs.quickMenuActions, binding.joinToString(", ") { it.replace("BUTTON_", "").replace('_', ' ') }, prefs.quickMenuStyle, prefs.quickMenuLabels,
            setShown = { prefs.quickMenuActions = it; revision++ },
            setStyle = { prefs.quickMenuStyle = it; revision++ },
            setLabels = { prefs.quickMenuLabels = it; revision++ },
            onHotkey = { screen = Screen.HOTKEYS },
        )
        Column(Modifier.fillMaxWidth()) {
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
        }
    }

    // ---- overlays -----------------------------------------------------------

    /** SCREEN > OVERLAY's imports (overlay/Overlays.kt), beside the game's other files. */
    private val overlayStore by lazy { OverlayStore(filesRoot) }
    private var overlayRemoveMode by mutableStateOf(false)
    /** An import's result to show (title, message). */
    private var overlayNotice by mutableStateOf<Pair<String, String>?>(null)
    /** A .cfg picked without its image: asks for it ([pickOverlayImage]); [overlayPending] holds what was picked. */
    private var overlayNeeds by mutableStateOf<OverlayStore.Result.NeedsImage?>(null)
    private var overlayPending: List<OverlayStore.Picked>? = null
    /** The imported overlay (id, label) REMOVE asks about. */
    private var removingOverlay by mutableStateOf<Pair<String, String>?>(null)
    /** REMOVE ALL's confirm is up. */
    private var removingAllOverlays by mutableStateOf(false)

    /** TOUCH CONTROLS > SKINS: the imported Delta skins, their pick-lists, IMPORT SKIN's file picker. */
    private val skinStore by lazy { com.pokedaisy.app.companion.DeltaSkinStore(java.io.File(filesDir, "skins")) }
    private val touchSkinUi = com.pokedaisy.app.companion.ui.TouchSkinUi()
    private val pickSkin = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importSkin(it) } }

    /** IMPORT SKIN: the picked .deltaskin (or a zip of them) into files/skins/; one new skin is picked for its system. */
    private fun importSkin(uri: Uri) {
        Thread {
            val result = runCatching { contentResolver.openInputStream(uri)?.use { skinStore.import(it) } }.getOrNull()
                ?: com.pokedaisy.app.companion.DeltaSkinStore.ImportResult(emptyList(), listOf("it couldn't be opened"))
            runOnUiThread {
                val added = result.added
                if (added.size == 1) {
                    val skin = added.first()
                    skin.skin.system?.let { prefs.touchControlsStyle = prefs.touchControlsStyle.withSkin(it, skin.id) }
                }
                touchSkinUi.notice = when {
                    added.isEmpty() -> tk("NOT A SKIN WE CAN USE") to tr("PokeDaisy reads Delta skins for GBA and Game Boy games: {0}", result.refused.joinToString("; "))
                    added.size == 1 -> tk("SKIN ADDED") to tr("{0} is on for {1} games.", added.first().skin.name, tr(added.first().skin.system?.label ?: ""))
                    else -> tk("SKINS ADDED") to tr("{0} skins added. Pick one under SKINS.", added.size)
                }
                revision++
            }
        }.start()
    }

    private val importOverlayFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importOverlays(uris, emptyList())
    }
    private val pickOverlayImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = overlayPending
        overlayPending = null
        if (uri != null && pending != null) importOverlays(listOf(uri), pending)
    }

    /**
     * OVERLAY: NONE and the built-in frames, then the player's imports - tap one to use it (the game shows it at
     * once from the companion's SETTINGS, or when it next opens from here). IMPORT FILE takes RetroArch overlays
     * (.cfg + its PNG, a .zip of them) and PNGs; REMOVE turns the imports' taps into removes.
     */
    @Composable
    private fun OverlaysScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val choices = overlayChoices(overlayStore)
        val current = choices.firstOrNull { it.first.key == prefs.overlay }?.first?.key ?: OverlayChoice.None.key
        val imported = choices.filter { it.first is OverlayChoice.Imported }
        fun pick(c: OverlayChoice): () -> Unit = { prefs.overlay = c.key; revision++ }
        val rows = buildList {
            add(groupTitle(tk("BUILT-IN")))
            choices.filter { it.first !is OverlayChoice.Imported }.forEach { (c, label) ->
                add(SettingRow(label, tk("ON").takeIf { c.key == current }, onClick = pick(c)))
            }
            add(groupTitle(tk("IMPORTED")))
            if (imported.isEmpty()) add(SettingRow(tk("NONE YET: IMPORT FILE BELOW"), null, enabled = false) {})
            imported.forEach { (c, label) ->
                val id = (c as OverlayChoice.Imported).id
                if (overlayRemoveMode) add(SettingRow(label, tk("REMOVE")) { removingOverlay = id to label })
                else add(SettingRow(label, tk("ON").takeIf { c.key == current }, onClick = pick(c)))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Hint(
                tr(
                    "A frame around the game. {0} and {1} are PokéDaisy's own. {2} adds yours: a RetroArch overlay (.cfg with its PNG), a .zip of them, or a PNG with a see-through screen.",
                    tr("DAISY"), tr("BEZEL"), tr("IMPORT FILE"),
                ),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
            Spacer(Modifier.height(m.u * 4))
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                OptionButton(tk("IMPORT FILE"), m, emphasis = true, onClick = {
                    overlayRemoveMode = false
                    importOverlayFiles.launch(arrayOf("*/*"))
                })
                Spacer(Modifier.weight(1f))
                if (imported.isNotEmpty()) {
                    // REMOVE's mode: one tap per overlay, or all of them at once (asked first).
                    if (overlayRemoveMode) OptionButton(tk("REMOVE ALL"), m, onClick = { removingAllOverlays = true })
                    OptionButton(if (overlayRemoveMode) tk("DONE") else tk("REMOVE"), m, onClick = { overlayRemoveMode = !overlayRemoveMode })
                }
            }
        }
    }

    /** Reads the picked files (plus [earlier] ones, waiting for their image) and imports them, off the UI thread. */
    private fun importOverlays(uris: List<Uri>, earlier: List<OverlayStore.Picked>) {
        Thread {
            var tooBig = false
            val picked = earlier + uris.mapNotNull { uri ->
                val name = RomUris.displayName(this, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: return@mapNotNull null
                val bytes = runCatching { contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_OVERLAY_FILE) } }.getOrNull()
                if (bytes == null) { tooBig = true; return@mapNotNull null }
                // A real path lets a .cfg's images be read from beside it (with All files access).
                OverlayStore.Picked(name, bytes, RomUris.originalPath(this, uri)?.takeIf { it.startsWith("/") })
            }
            val result = when {
                picked.isNotEmpty() -> overlayStore.import(picked)
                tooBig -> OverlayStore.Result.Failed(OverlayStore.Reason.TOO_BIG)
                else -> OverlayStore.Result.Failed(OverlayStore.Reason.NOTHING_USABLE)
            }
            runOnUiThread { onOverlaysImported(result, picked) }
        }.start()
    }

    private fun onOverlaysImported(result: OverlayStore.Result, picked: List<OverlayStore.Picked>) {
        when (result) {
            is OverlayStore.Result.Imported -> {
                val skipped = if (result.skipped > 0) " " + tr("{0} couldn't be used: an image missing, or not a PNG.", result.skipped) else ""
                overlayNotice = if (result.ids.size == 1) {
                    // One new overlay: used at once.
                    val c = OverlayChoice.Imported(result.ids.first())
                    prefs.overlay = c.key
                    tk("OVERLAY ADDED") to tr("{0} is on. Games show it from now on.", overlayLabel(overlayChoices(overlayStore), c.key)) + skipped
                } else {
                    tk("OVERLAYS ADDED") to tr("{0} overlays added. Tap one to use it.", result.ids.size) + skipped
                }
            }
            is OverlayStore.Result.NeedsImage -> {
                overlayPending = picked
                overlayNeeds = result
            }
            is OverlayStore.Result.Failed -> overlayNotice = tk("NOT AN OVERLAY") to when (result.reason) {
                OverlayStore.Reason.TOO_BIG -> tr("That file is too big for an overlay.")
                OverlayStore.Reason.NOTHING_USABLE -> tr("Pick a RetroArch overlay (.cfg with its image), a .zip of them, or a PNG with a see-through screen.")
            }
        }
        revision++
    }

    /** All of [input], or null once it passes [limit] bytes. */
    private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
    }

    /** Version, author, Buy Me a Coffee, the Discord and the repo, under the settings list. */
    @Composable
    private fun AboutFooter(m: GbaTextMetrics) {
        val small = rememberGbaTextMetrics(textScale = 1f)
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 8, vertical = m.u * 3),
            ) {
                GbaText(
                    tr("POKéDAISY {0}  ·  by {1}", BuildConfig.VERSION_NAME, "Lidor Itzhari (@lidor30)"),
                    OptionColors.label, OptionColors.labelShadow, small, modifier = Modifier.weight(1f),
                )
                FooterLink(tr("Buy me a coffee"), m, small, Modifier.padding(start = m.u * 6), { CoffeeCup() }) {
                    openUrl(COFFEE_URL)
                }
                GbaText("  ·  ", OptionColors.label, OptionColors.labelShadow, small)
                FooterLink("Discord", m, small, icon = { DiscordMark() }) { openUrl(Discord.URL) }
                GbaText("  ·  ", OptionColors.label, OptionColors.labelShadow, small)
                FooterLink("GitHub", m, small, icon = { GitHubMark() }) { openUrl(REPO_URL) }
            }
        }
    }

    /** A pixel [icon] then [text] in the value red, the whole of it one tap target. */
    @Composable
    private fun FooterLink(
        text: String, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier,
        icon: @Composable () -> Unit, onClick: () -> Unit,
    ) {
        Row(modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(small.lineHeight)) { icon() }
            Spacer(Modifier.width(m.u * 3))
            GbaText(text, OptionColors.value, OptionColors.valueShadow, small)
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, tr("No browser to open {0}", url), Toast.LENGTH_LONG).show() }
    }

    /** Explanatory text in its own small window, above a page's list. */
    @Composable
    private fun Hint(text: String, m: GbaTextMetrics, small: GbaTextMetrics) = com.pokedaisy.app.companion.ui.PageHint(text, m, small)

    // ---- RetroAchievements --------------------------------------------------

    /** Sign in / out of RetroAchievements. Only the token the server returns is
     * kept (Prefs); the password goes straight to the login call. */
    @Composable
    private fun AchievementsScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val user by RetroAchievements.user.collectAsState()
        val signingIn by RetroAchievements.signingIn.collectAsState()
        val state by RetroAchievements.state.collectAsState()
        var name by remember { mutableStateOf(prefs.raUsername ?: "") }
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val savedName = prefs.raUsername.takeIf { prefs.raToken != null }
        fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Hint(
                tr("Earn RetroAchievements (retroachievements.org) as you play. Unlocks pop up on the second screen, and its CHEEVOS tab (under MORE there) lists the game's set. Only ROMs RetroAchievements knows by their exact hash have achievements. Softcore only for now. Your password isn't stored: the app keeps the login token RetroAchievements sends back."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 4)) {
                    val u = user
                    if (u != null || savedName != null) {
                        GbaText(tr("SIGNED IN AS"), OptionColors.label, OptionColors.labelShadow, m)
                        GbaText((u?.displayName ?: savedName!!).uppercase(), OptionColors.value, OptionColors.valueShadow, m)
                        GbaText(
                            when {
                                u != null -> tr("{0} SOFTCORE POINTS · {1} HARDCORE POINTS", u.softcoreScore, u.score)
                                state.offline -> tr("CAN'T REACH RetroAchievements RIGHT NOW · TRIED AGAIN BY ITSELF")
                                else -> tr("CONNECTING…")
                            },
                            OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 2,
                        )
                        Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                            if (u == null && state.offline) OptionButton(tk("TRY AGAIN"), m, onClick = { RetroAchievements.retry() })
                            OptionButton(tk("SIGN OUT"), m, onClick = { RetroAchievements.logout(); password = ""; revision++ })
                        }
                    } else {
                        GbaText(tr("USERNAME"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                        OptionTextField(name, { name = it; error = null }, small, placeholder = tr("retroachievements.org username"))
                        Spacer(Modifier.height(m.u * 4))
                        GbaText(tr("PASSWORD"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                        OptionTextField(password, { password = it; error = null }, small, placeholder = tr("password"), password = true)
                        error?.let {
                            GbaText(it.uppercase(), OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 4), maxLines = 3)
                        }
                        Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                            OptionButton(
                                if (signingIn) tk("SIGNING IN…") else tk("SIGN IN"), m, emphasis = true,
                                enabled = !signingIn && name.isNotBlank() && password.isNotEmpty(),
                                onClick = {
                                    error = null
                                    RetroAchievements.login(name, password) { ok, message ->
                                        runOnUiThread {
                                            if (ok) { password = ""; revision++ }
                                            else error = message ?: tr("Couldn't sign in")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // What shows over the game / companion; tracking and submissions carry on either way.
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    com.pokedaisy.app.companion.ui.GroupTitleLine(tk("NOTIFICATIONS"), m, first = true)
                    OptionLine(
                        tk("ACHIEVEMENT POPUPS"), onOff(prefs.raPopups), selected = false, m,
                        height = m.rowHeight * 1.2f + m.lineHeight, divider = true,
                        subtitle = "Unlocks, game complete, leaderboards and connection notices",
                        labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.RA_POPUPS),
                    ) {
                        prefs.raPopups = !prefs.raPopups
                        revision++
                    }
                    OptionLine(
                        tk("PROGRESS POPUPS"), onOff(prefs.raProgressPopups), selected = false, m,
                        height = m.rowHeight * 1.2f + m.lineHeight, enabled = prefs.raPopups,
                        subtitle = "A notice when a counting achievement gets closer",
                        labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.RA_POPUPS),
                    ) {
                        prefs.raProgressPopups = !prefs.raProgressPopups
                        revision++
                    }
                }
            }
        }
    }

    // ---- co-op --------------------------------------------------------------

    /** CO-OP's pages and their state (PAIR, PARTNERS, RELAY); internal for ui-preview's stand-in ([CoopSettingsPages.preview]). */
    internal val coop by lazy { CoopSettingsPages(this, { prefs }) { revision++ } }

    /** The CO-OP group, only while the co-op flag is on; the companion's SETTINGS has the same rows in the same place. */
    private fun coopRows(): List<SettingRow> {
        if (!FeatureFlags.coop(this)) return emptyList()
        fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
        return listOf(
            groupTitle(tk("CO-OP")),
            SettingRow(
                tk("PAIR"), null, labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.COOP),
                opensPage = true, subtitle = "Link one of your Emerald saves with a friend's",
            ) { openCoopPair(null) },
            SettingRow(tk("PARTNERS"), coop.pairs.size.takeIf { it > 0 }?.toString() ?: tk("NONE"), opensPage = true) { screen = Screen.COOP_PARTNERS },
            com.pokedaisy.app.companion.ui.nearbyRangeRow(
                prefs.coopNearbyRange,
                { title, choices, current, onPick -> selector = Selector(title, choices, current) { onPick(it); revision++ } },
            ) { prefs.coopNearbyRange = it },
            SettingRow(tk("RELAY"), CoopRelay.rowValue(prefs.coopRelayUrl, BuildConfig.COOP_RELAY_URL), opensPage = true) { screen = Screen.COOP_RELAY },
            SettingRow(tk("RELAY ONLY"), onOff(prefs.coopRelayOnly), subtitle = "Hide your IP address from your partner") {
                prefs.coopRelayOnly = !prefs.coopRelayOnly
                CoopSession.restart(this, prefs)
                revision++
            },
            // PLAY TOGETHER ONLY: no game runs behind this screen, so it only says it needs one (the row is the game's SETTINGS').
            *listOfNotNull(
                com.pokedaisy.app.companion.ui.coopLockRow(
                    com.pokedaisy.app.companion.coop.lock.CoopLockUi(refusal = com.pokedaisy.app.companion.coop.lock.LockRefusal.NOT_CONNECTED),
                    object : com.pokedaisy.app.companion.coop.lock.CoopLockActions {
                        override fun propose() {}
                        override fun accept() {}
                        override fun decline() {}
                        override fun turnOff() {}
                    },
                    "",
                ) {},
            ).toTypedArray(),
        )
    }

    private fun openCoopPair(ticket: String?) {
        coop.open(ticket)
        screen = if (ticket == null) Screen.COOP_PAIR else Screen.COOP_JOIN
    }

    // ---- cheats -------------------------------------------------------------

    /** The game CHEATS shows (its library menu's, else the last one played), its CRC once
     * worked out, and its cheats (cheats/Cheats.kt). */
    private var cheatRom by mutableStateOf<File?>(null)
    private var cheatCrc by mutableStateOf<String?>(null)
    /** The CHEATS page's game is a DS cart: Action Replay DS codes, checked as such (cheats.DsCheats). */
    private var cheatDs by mutableStateOf(false)
    private var cheatList by mutableStateOf<List<Cheat>>(emptyList())
    private var cheatRemoveMode by mutableStateOf(false)
    private var removingCheat by mutableStateOf<Int?>(null)
    /** A title + message to show over CHEATS (an import's result, a file that isn't one; internal for ui-preview). */
    internal var cheatNotice by mutableStateOf<Pair<String, String>?>(null)

    private fun cheatStore(): CheatStore? = cheatCrc?.let { CheatStore.forCrc(filesDir, it) }

    private fun saveCheats(list: List<Cheat>) {
        cheatStore()?.save(list)
        cheatList = list
    }

    /**
     * CHEATS: the master switch, which game, ADD CODE (typed) and IMPORT FILE (a
     * RetroArch .cht or mGBA .cheats), then the game's cheats - tap one to turn it on
     * or off; REMOVE turns the taps into removes. The game loads them when it starts
     * (and live from the companion's SETTINGS > CHEATS).
     */
    @Composable
    private fun CheatsScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val games = remember { RomFolder.libraryRoms(this@SettingsActivity, prefs) }
        val rom = cheatRom ?: remember(games) { prefs.lastRomPath?.let(::File)?.takeIf { it.isFile } ?: games.firstOrNull() }
        LaunchedEffect(rom) {
            cheatCrc = null
            cheatList = emptyList()
            val crc = rom?.let { withContext(Dispatchers.IO) { cheatCrcOf(it) } }
            cheatDs = rom != null && withContext(Dispatchers.IO) { runCatching { RomIdentity.isNds(rom) }.getOrDefault(false) }
            cheatCrc = crc
            cheatList = crc?.let { c -> withContext(Dispatchers.IO) { CheatStore.forCrc(filesDir, c).load() } }.orEmpty()
        }
        val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCheats(uri)
        }
        fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
        val master = prefs.cheatsEnabled
        val ready = rom != null && cheatCrc != null
        val rows = buildList {
            add(SettingRow(tk("CHEATS"), onOff(master), subtitle = "GameShark, Action Replay and CodeBreaker codes") { prefs.cheatsEnabled = !master; revision++ })
            add(SettingRow(tk("GAME"), rom?.let { GameTitles.label(this@SettingsActivity, prefs, it) } ?: tk("NONE")) {
                if (games.isEmpty()) return@SettingRow
                val labels = games.map { GameTitles.label(this@SettingsActivity, prefs, it) }
                val current = rom?.let { games.indexOf(it) }?.takeIf { it >= 0 }?.let { labels[it] } ?: ""
                selector = Selector(tk("GAME"), labels, current) { l -> cheatRom = games[labels.indexOf(l)]; cheatRemoveMode = false }
            })
            add(groupTitle(tk("CODES")))
            when {
                rom == null -> add(SettingRow(tk("NO GAMES IN THE LIBRARY"), null, enabled = false) {})
                cheatCrc == null -> add(SettingRow(tk("LOADING…"), null, enabled = false) {})
                cheatList.isEmpty() -> add(SettingRow(tk("NO CHEATS FOR THIS GAME"), null, enabled = false) {})
                else -> cheatList.forEachIndexed { i, c ->
                    if (cheatRemoveMode) add(SettingRow(c.name, tk("REMOVE")) { removingCheat = i })
                    // Greyed out with the master switch off, like HOTKEYS' binds.
                    else add(SettingRow(c.name, onOff(c.enabled), enabled = master) {
                        saveCheats(cheatList.toMutableList().also { it[i] = c.copy(enabled = !c.enabled) })
                    })
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Hint(
                tr("A cheat can break a save, so the save is backed up every time a game starts (RESTORE BACKUP in its library menu). Achievements pause while a cheat is on."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
            }
            Spacer(Modifier.height(m.u * 4))
            // Under the list, like HIDDEN GAMES' SHOW ALL, so the cheats get its rows.
            Row(horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                OptionButton(tk("ADD CODE"), m, emphasis = true, enabled = ready, onClick = { cheatRemoveMode = false; screen = Screen.CHEAT_ADD })
                OptionButton(tk("IMPORT FILE"), m, enabled = ready, onClick = { importFile.launch(arrayOf("*/*")) })
                Spacer(Modifier.weight(1f))
                if (cheatList.isNotEmpty()) {
                    OptionButton(if (cheatRemoveMode) tk("DONE") else tk("REMOVE"), m, onClick = { cheatRemoveMode = !cheatRemoveMode })
                }
            }
        }
    }

    /** IMPORT FILE: reads the picked file and adds its cheats (all OFF), saying what came in and what didn't. */
    private fun importCheats(uri: Uri) {
        val store = cheatStore() ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: tk("CHEAT")
        Thread {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size <= MAX_CHEAT_FILE } }
            }.getOrNull()?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")
            val result = text?.let { store.import(it, name, ds = cheatDs) }
            val list = store.load()
            runOnUiThread {
                cheatList = list
                cheatNotice = cheatImportNotice(result)
            }
        }.start()
    }

    @Composable
    private fun CheatAddScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(CheatType.AUTO) }
        var error by remember { mutableStateOf<String?>(null) }
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Hint(
                if (cheatDs) tr("Type or paste an Action Replay DS code, one line or many.")
                else tr("Type or paste a GameShark, Action Replay or CodeBreaker code, one line or many. AUTO works out which it is; pick the type when a code doesn't take."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 4)) {
                    GbaText(tr("NAME"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(name, { name = it; error = null }, small, placeholder = tr("e.g. INFINITE MONEY"))
                    Spacer(Modifier.height(m.u * 4))
                    GbaText(tr("CODE"), OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(vertical = m.u * 2))
                    OptionTextField(code, { code = it.uppercase(); error = null }, small, placeholder = "XXXXXXXX YYYYYYYY", minLines = 3, code = true)
                    Spacer(Modifier.height(m.u * 2))
                    // Back by the list row's own inset, so TYPE lines up with NAME and CODE. A DS game has one kind of code.
                    if (!cheatDs) OptionLine(tk("TYPE"), type.label, selected = false, m, Modifier.offset(x = -m.u * 4), height = m.rowHeight * 1.2f) {
                        val types = CheatType.entries
                        selector = Selector(tk("TYPE"), types.map { it.label }, type.label) { l -> type = types.first { it.label == l }; error = null }
                    }
                    error?.let {
                        GbaText(it, OptionColors.value, OptionColors.valueShadow, small, Modifier.padding(top = m.u * 4), maxLines = 4)
                    }
                    Row(Modifier.padding(vertical = m.u * 4), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        OptionButton(tk("CANCEL"), m, onClick = { screen = Screen.CHEATS })
                        OptionButton(tk("ADD"), m, emphasis = true, enabled = code.isNotBlank(), onClick = {
                            val lines = CheatCodes.normalize(code)
                            val label = name.trim().ifEmpty { tr("CHEAT {0}", cheatList.size + 1) }
                            val r = CheatCheck.check(Cheat(label, lines), type, ds = cheatDs)
                            val ok = r.cheat
                            if (ok == null) {
                                error = when {
                                    r.bad.isEmpty() -> tr("That isn't a code.")
                                    cheatDs -> tr("{0} isn't an Action Replay DS line.", r.bad.joinToString(", "))
                                    type == CheatType.AUTO -> tr("mGBA can't read {0}.", r.bad.joinToString(", "))
                                    else -> tr("mGBA can't read {0} as a {1} code.", r.bad.joinToString(", "), tr(type.label))
                                }
                            } else {
                                // Typed in to be used: on (the master switch still decides).
                                saveCheats(cheatList + ok.copy(enabled = true))
                                screen = Screen.CHEATS
                            }
                        })
                    }
                }
            }
        }
    }

    // ---- hidden games -------------------------------------------------------

    /** Games hidden from the library (its menu's HIDE): tap one to show it again. */
    @Composable
    private fun HiddenScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val hidden = remember(revision) { RomFolder.hiddenRoms(this@SettingsActivity, prefs) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Hint(tr("Games hidden from the library with HIDE in their menu. Tap one to show it in the library again."), m, small)
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (hidden.isEmpty()) {
                    GbaText(
                        tr("NO HIDDEN GAMES"), OptionColors.muted, OptionColors.mutedShadow, m,
                        modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                    )
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        hidden.forEachIndexed { i, rom ->
                            OptionLine(
                                GameTitles.label(this@SettingsActivity, prefs, rom), tk("SHOW"), selected = false, m,
                                height = m.rowHeight * 1.2f, labelWeight = 0.8f, divider = i < hidden.lastIndex,
                            ) {
                                prefs.hiddenRoms = prefs.hiddenRoms - rom.absolutePath
                                revision++
                            }
                        }
                    }
                }
            }
            if (hidden.size > 1) {
                Spacer(Modifier.height(m.u * 4))
                Row {
                    Spacer(Modifier.weight(1f))
                    OptionButton(tk("SHOW ALL"), m, onClick = { prefs.hiddenRoms = emptySet(); revision++ })
                }
            }
        }
    }

    /** A hard-wrapped paragraph as running text; a "- " / "(a)" / "1." line starts a new one. */
    private fun reflow(p: String): List<String> {
        val out = mutableListOf<String>()
        for (line in p.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.isEmpty() || line.matches(Regex("^([-*•]|\\(?[a-z0-9]{1,3}[.)]).*"))) out += line
            else out[out.lastIndex] = out.last() + " " + line
        }
        return out
    }

    /** LICENSES: the build's licenses.txt (NOTICE, then each component's text; "=== " starts a part). */
    @Composable
    private fun LicensesScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val parts = remember {
            val text = runCatching { assets.open("licenses.txt").use { it.readBytes().decodeToString() } }.getOrNull()
                ?: "=== PokeDaisy\n\nGNU GPL v3 - https://github.com/lidor30/pokedaisy"
            text.split(Regex("(?m)^=== ")).filter { it.isNotBlank() }.map { part ->
                val title = part.substringBefore('\n').trim()
                // Paragraphs reflowed: the license files are hard-wrapped at ~70 columns.
                title to part.substringAfter('\n').trim().split(Regex("\n\\s*\n"))
                    .flatMap { p -> reflow(p) }.filter { it.isNotBlank() }
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Hint(tr("PokéDaisy and the software it carries, each under its own license."), m, small)
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = m.u * 8, vertical = m.u * 4),
                ) {
                    parts.forEach { (title, paragraphs) ->
                        item {
                            GbaText(
                                title, OptionColors.value, OptionColors.valueShadow, m,
                                modifier = Modifier.padding(top = m.u * 4, bottom = m.u * 2),
                            )
                        }
                        paragraphs.forEach { p ->
                            item {
                                GbaText(
                                    p, OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                                    modifier = Modifier.padding(bottom = m.u * 2),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- cover art (SteamGridDB) --------------------------------------------

    @Composable
    private fun CoverArtScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        var text by remember { mutableStateOf(prefs.steamGridDbApiKey ?: "") }
        var raText by remember { mutableStateOf(prefs.raWebApiKey ?: "") }
        val saved = prefs.steamGridDbApiKey != null || prefs.raWebApiKey != null

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Hint(
                tr("Library covers are fetched once per ROM and kept on-device — nothing is bundled in the app. RetroAchievements has box art for every game with a set, SteamGridDB icons for FireRed, Emerald and the supported hacks; box art wins where there are both. Each needs your own free key: tap GET KEY, copy it from that page, paste it below and tap SAVE. Long-press a game for REPLACE COVER to pick another."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    KeyLabel(tr("{0} WEB API KEY", "RetroAchievements"), m, small) { openUrl(CoverArtSync.RA_KEY_PAGE) }
                    OptionTextField(raText, { raText = it }, small, placeholder = tr("paste key here"))
                    Spacer(Modifier.height(m.u * 4))
                    KeyLabel(tr("{0} API KEY", "SteamGridDB"), m, small) { openUrl(CoverArtSync.STEAMGRIDDB_KEY_PAGE) }
                    OptionTextField(text, { text = it }, small, placeholder = tr("paste key here"))
                    Spacer(Modifier.height(m.u * 4))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
                        OptionButton(
                            if (coverSync.running) tk("FETCHING…") else tk("SAVE"), m, emphasis = true, enabled = !coverSync.running,
                            onClick = {
                                prefs.steamGridDbApiKey = text
                                prefs.raWebApiKey = raText
                                revision++
                                startCoverSync(replace = false)
                            },
                        )
                        if (saved) {
                            OptionButton(
                                tk("CLEAR"), m, enabled = !coverSync.running,
                                onClick = { text = ""; raText = ""; prefs.steamGridDbApiKey = null; prefs.raWebApiKey = null; revision++ },
                            )
                            Spacer(Modifier.weight(1f))
                            OptionButton(tk("REPLACE ALL"), m, enabled = !coverSync.running, onClick = { confirmReplaceAll = true })
                        }
                    }
                }
            }

            Spacer(Modifier.height(m.u * 4))
            CoverSyncPanel(coverSync, m, small)
        }
    }

    // ---- ScreenScraper ------------------------------------------------------------

    /** Sign in / out of ScreenScraper, videos on / off, and a media pass over the library now. */
    @Composable
    private fun ScreenScraperScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Hint(
                tr("ScreenScraper.fr has box art, screenshots, title screens, logos, videos and each game's story. With your free account, PokéDaisy fetches them once per game and keeps them on the device. Its box art comes first for library covers, and with two screens the launcher shows the game you're on, in full, on the other one."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 4)) {
                    ScreenScraperAccountPanel(
                        ssLogin, m, small,
                        onSignIn = { ssLogin.signIn(prefs, { r -> runOnUiThread { r() } }) { revision++; startCoverSync(replace = false) } },
                        onSignOut = { ssLogin.signOut(prefs); revision++ },
                        onSignUp = { openUrl(ScreenScraperClient.SIGN_UP_PAGE) },
                    )
                }
            }
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    // Videos are a few MB a game: the launcher plays them, nothing else needs them.
                    OptionLine(tk("DOWNLOAD VIDEOS"), if (prefs.screenScraperVideos) tk("ON") else tk("OFF"), selected = false, m, divider = true) {
                        prefs.screenScraperVideos = !prefs.screenScraperVideos
                        revision++
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(m.u * 4),
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                    ) {
                        OptionButton(
                            if (coverSync.running) tk("FETCHING…") else tr("FETCH MEDIA NOW"), m, emphasis = true,
                            enabled = !coverSync.running && ssLogin.signedInAs != null,
                            onClick = { startCoverSync(replace = false) },
                        )
                        Spacer(Modifier.weight(1f))
                        OptionButton(
                            tk("REPLACE ALL"), m, enabled = !coverSync.running && ssLogin.signedInAs != null,
                            onClick = { confirmReplaceAll = true },
                        )
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            CoverSyncPanel(coverSync, m, small)
        }
    }

    /** Settings > ScreenScraper's sign-in (internal for ui-preview). */
    internal val ssLogin by lazy { ScreenScraperLogin.of(prefs) }

    /** A key field's title with GET KEY beside it, opening the site's page for that key. */
    @Composable
    private fun KeyLabel(label: String, m: GbaTextMetrics, small: GbaTextMetrics, onGetKey: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = m.u * 4, vertical = m.u * 2),
        ) {
            GbaText(label, OptionColors.label, OptionColors.labelShadow, m)
            Spacer(Modifier.width(m.u * 6))
            OptionButton(tr("GET KEY"), small, onClick = onGetKey)
        }
    }

    // On the activity so the REPLACE ALL confirm (drawn over the whole screen from Root) can start it.
    private val coverSync = CoverSync()
    private var confirmReplaceAll by mutableStateOf(false)

    private fun startCoverSync(replace: Boolean) = coverSync.start(this, prefs, replace)

    // ---- hotkeys / game buttons -------------------------------------------

    /** A hotkey bound to [keys] - asked first when other hotkeys have exactly that chord (MOVE takes it off them). */
    private fun bindHotkey(action: Hotkeys.Action, keys: List<Int>) {
        val taken = Hotkeys.clashes(Hotkeys.load(filesRoot).rawBindings, action, keys.map { Hotkeys.keyName(it) })
        if (taken.isEmpty()) Hotkeys.setBinding(filesRoot, action, keys)
        else hotkeyClash = HotkeyClash(action, keys, taken)
        revision++
    }

    @Composable
    private fun HotkeysScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val hotkeys = remember(revision) { Hotkeys.load(filesRoot) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Hint(
                tr("Tap a hotkey, then press one or more keys/buttons together and release — e.g. hold Select and tap R1 for a chord. BACK cancels."),
                m, small,
            )
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Off, the binds stay as they are, greyed out, and every key goes to the game.
                    val on = prefs.hotkeysEnabled
                    OptionLine(
                        tk("HOTKEYS"), if (on) tk("ON") else tk("OFF"), selected = false, m, height = m.rowHeight * 1.2f,
                        labelWeight = 0.5f, divider = true,
                    ) { capture.cancel(); prefs.hotkeysEnabled = !on; revision++ }
                    Hotkeys.Action.entries.forEachIndexed { i, action ->
                        KeyBindRow(
                            action.title, hotkeys.rawBindings[action], capture, m, small,
                            divider = i < Hotkeys.Action.entries.lastIndex, enabled = on, labelBadge = action.badge,
                        ) {
                            com.pokedaisy.app.companion.KeyCapture.Request(
                                action.title, chord = true,
                                onDone = { keys -> bindHotkey(action, keys) },
                                // CLEAR leaves it unbound ("-").
                                onClear = { Hotkeys.clearBinding(filesRoot, action); revision++ },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ControlsScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val ctrls = remember(revision) { GbaControls.rawBindings(filesRoot) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Hint(tr("Which physical button is each GBA button. The D-pad and analog stick always work; the D-PAD rows add more keys to them."), m, small)
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Every game's buttons, then the DS games' and the GBA / Game Boy games' own.
                    GbaControls.Group.entries.forEach { group ->
                        com.pokedaisy.app.companion.ui.GroupTitleLine(group.title, m, first = group == GbaControls.Group.ALL)
                        val btns = GbaControls.Btn.entries.filter { it.group == group }
                        btns.forEachIndexed { i, btn ->
                            KeyBindRow(
                                btn.title, ctrls[btn], capture, m, small,
                                divider = i < btns.lastIndex,
                                subtitle = btn.subtitle, labelBadge = btn.badge,
                            ) { controlRequest(btn) }
                        }
                    }
                }
            }
        }
    }

    private fun controlRequest(btn: GbaControls.Btn) = com.pokedaisy.app.companion.KeyCapture.Request(
        btn.title, chord = false,
        onDone = { keys -> GbaControls.setBinding(filesRoot, btn, keys.first()); revision++ },
        onClear = if (btn.canClear) ({ GbaControls.clearBinding(filesRoot, btn); revision++ }) else null,
    )

    // ---- folders --------------------------------------------------------

    /** FOLDERS: a row for each kind of folder, each on its own page (it was one long page of all four). */
    private fun folderRows(): List<SettingRow> {
        @Suppress("UNUSED_EXPRESSION") folderTick
        val roms = prefs.romsFolders
        val extra = prefs.extraSaveDirs
        val states = statesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return listOf(
            SettingRow(
                tk("ROMS FOLDERS"), if (roms.isEmpty()) tk("NONE") else roms.size.toString(), opensPage = true,
                subtitle = "Where the library finds your games", labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.ROMS_FOLDERS),
            ) { screen = Screen.ROMS_FOLDERS },
            SettingRow(tk("GAME SAVES"), if (prefs.savesDirOverride == null) tk("DEFAULT") else tk("CUSTOM"), opensPage = true, subtitle = "Where new saves go") {
                screen = Screen.SAVES_FOLDER
            },
            SettingRow(tk("ALSO LOOK IN"), if (extra.isEmpty()) tk("NONE") else extra.size.toString(), opensPage = true, subtitle = "More folders to find a game's save in") {
                screen = Screen.SAVE_SEARCH
            },
            SettingRow(
                tk("SAVE BACKUPS"), if (prefs.saveBackups) tk("ON") else tk("OFF"), opensPage = true,
                subtitle = "A copy of the save each time a game starts",
                labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.SAVE_BACKUPS),
            ) { screen = Screen.BACKUPS },
            SettingRow(tk("SAVE STATES"), humanSize(states), opensPage = true) { screen = Screen.STATES_FOLDER },
        )
    }

    private val statesDir by lazy { File(getExternalFilesDir(null), "states").apply { mkdirs() } }

    /** ROMS FOLDERS: the linked folders, each with its games' count. No file list or delete buttons here - those
     * are the player's own files, not ours. */
    @Composable
    private fun RomsFoldersScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val tick = folderTick
        val pick = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            val folders = prefs.romsFolders
            val already = RomFolder.coveredBy(folders, path)
            if (already != null) {
                Toast.makeText(this, if (already == File(path).absolutePath) tr("That folder is already linked") else tr("Already linked through {0}", already), Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            prefs.romsFolders = RomFolder.withFolder(folders, path)
            rescanRomsFolder()
        }
        val folders = remember(tick) { prefs.romsFolders }
        val counts = remember(tick, folders) { folders.associateWith { RomFolder.found(this@SettingsActivity, prefs, it).size } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = m.u * 4)) {
                    GbaText(
                        tr("Link the folders you keep your games in: their supported ROMs join the library and play from there."),
                        OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                    )
                    GbaText(
                        tr("You can add several. Picking just the folders with your games (gba, gbc, nds) is quicker to look through than one big folder of everything."),
                        OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(top = m.u * 2),
                    )
                    if (folders.isNotEmpty()) {
                        GbaText(
                            if (romsScanning) tr("LOOKING FOR GAMES…")
                            else tr("{0} GAME(S) IN THE LIBRARY · CHECKED FOR NEW ONES EACH TIME THE APP OPENS", counts.values.sum()),
                            OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(top = m.u * 2),
                        )
                    }
                    folders.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(top = m.u * 4), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                GbaText(shortPath(d), OptionColors.label, OptionColors.labelShadow, small, maxLines = 2)
                                GbaText(tr("{0} GAME(S)", counts[d] ?: 0), OptionColors.muted, OptionColors.mutedShadow, small)
                            }
                            Spacer(Modifier.width(m.u * 4))
                            OptionButton(tk("REMOVE"), small, enabled = !romsScanning, onClick = {
                                prefs.romsFolders = prefs.romsFolders - d
                                RomFolder.forgetLater(this@SettingsActivity, d)
                                folderTick++
                            })
                        }
                    }
                    Row(
                        modifier = Modifier.padding(vertical = m.u * 4),
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                    ) {
                        OptionButton(if (folders.isEmpty()) tk("CHOOSE FOLDER") else tk("ADD FOLDER"), small, enabled = !romsScanning, onClick = { pickFolder(pick) })
                        if (folders.isNotEmpty()) {
                            OptionButton(tk("RESCAN"), small, enabled = !romsScanning, onClick = { rescanRomsFolder() })
                        }
                    }
                }
            }
        }
    }

    /** GAME SAVES: where new saves go (the app's own folder unless the player picked one). */
    @Composable
    private fun SavesFolderScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val tick = folderTick
        val savesDir = remember(tick) { SavesLocation.dir(this@SettingsActivity, prefs) }
        val pick = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            prefs.savesDirOverride = path
            folderTick++
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            FolderSection(null, savesDir, tick, m, small) {
                OptionButton(tk("CHANGE FOLDER"), small, onClick = { pickFolder(pick) })
                if (prefs.savesDirOverride != null) {
                    OptionButton(tk("USE DEFAULT"), small, onClick = { prefs.savesDirOverride = null; folderTick++ })
                }
            }
        }
    }

    /** ALSO LOOK IN's page. */
    @Composable
    private fun AlsoLookInScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val pick = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            prefs.extraSaveDirs = prefs.extraSaveDirs + path
            folderTick++
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            AlsoLookInSection(folderTick, m, small, onAdd = { pickFolder(pick) })
        }
    }

    /** SAVE STATES: the app's own states, every file with its delete button. */
    @Composable
    private fun StatesFolderScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            FolderSection(null, statesDir, folderTick, m, small)
        }
    }

    /** SAVE BACKUPS: the copy of the save each game's start takes ([SaveBackups]) - on or off, how many a game
     * keeps and where they go. The save editor backs up whatever this says. */
    @Composable
    private fun SaveBackupsScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val tick = folderTick
        val pick = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            prefs.saveBackupsDir = path
            backupsChanged()
        }
        val on = prefs.saveBackups
        val keep = prefs.saveBackupsKeep
        val folder = remember(tick) { prefs.saveBackupsDir }
        val rows = listOf(
            SettingRow(tk("BACKUPS"), if (on) tk("ON") else tk("OFF"), subtitle = "A copy of the save each time a game starts") {
                prefs.saveBackups = !on
                backupsChanged()
            },
            SettingRow(tk("KEEP PER GAME"), keep.toString(), enabled = on, subtitle = "The oldest go when a new one is made") {
                selector = Selector(tk("KEEP PER GAME"), SaveBackups.KEEP_CHOICES.map { it.toString() }, keep.toString()) {
                    prefs.saveBackupsKeep = it.toInt()
                    backupsChanged()
                }
            },
        )
        val ctx = LocalContext.current
        Column(Modifier.fillMaxWidth()) {
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() }, footer = {
                    // Level with the rows' labels.
                    Column(Modifier.padding(horizontal = m.u * 8).padding(top = m.u * 4)) {
                        GbaText(tr("BACKUP FOLDER"), OptionColors.label, OptionColors.labelShadow, m)
                        if (folder == null) {
                            GbaText(
                                tr("Beside each save, in a {0} folder.", SaveBackups.DIR_NAME),
                                OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE,
                            )
                        } else {
                            GbaText(folder, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3)
                        }
                        GbaText(
                            tr("Backups made before a change stay where they are, and RESTORE BACKUP still finds them."),
                            OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(top = m.u * 2),
                        )
                        Row(
                            modifier = Modifier.padding(vertical = m.u * 4),
                            horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                        ) {
                            OptionButton(tk("CHANGE FOLDER"), small, enabled = on, onClick = { pickFolder(pick) })
                            if (folder != null) {
                                OptionButton(tk("USE DEFAULT"), small, enabled = on, onClick = { prefs.saveBackupsDir = null; backupsChanged() })
                                OptionButton(tk("COPY PATH"), small, onClick = { copyPath(ctx, folder) })
                            }
                        }
                    }
                })
            }
        }
    }

    /** A SAVE BACKUPS choice changed: the next game's start reads it from [SaveBackups.settings]. */
    private fun backupsChanged() {
        SaveBackups.settings = SaveBackups.Settings.from(prefs)
        revision++
        folderTick++
    }

    private var romsScanning by mutableStateOf(false)

    /** A folder added while a scan runs: scan again once it's done. */
    private var rescanAgain = false

    private fun rescanRomsFolder() {
        if (romsScanning) { rescanAgain = true; return }
        romsScanning = true
        folderTick++
        Thread({
            RomFolder.scan(this, prefs)
            runOnUiThread {
                romsScanning = false
                folderTick++
                if (rescanAgain) { rescanAgain = false; rescanRomsFolder() }
            }
        }, "pokedaisy-rom-scan").apply { isDaemon = true; start() }
    }

    /** The picker's folder as a real path (persisting its grant), or null with a toast. */
    private fun pickedFolder(uri: Uri?): String? {
        if (uri == null) return null
        val path = StorageAccess.treePath(uri)
        if (path == null) {
            Toast.makeText(this, tr("Only folders on this device's own storage are supported"), Toast.LENGTH_LONG).show()
            return null
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        return path
    }

    /** Needs "All files access" first (games and saves are opened by raw path,
     * which scoped storage otherwise blocks outside this app's own sandbox). */
    private fun pickFolder(launcher: androidx.activity.result.ActivityResultLauncher<Uri?>) {
        if (!StorageAccess.hasAllFilesAccess(this)) {
            StorageAccess.requestAllFilesAccess(this)
            Toast.makeText(this, tr("Grant \"All files access\", then tap the button again"), Toast.LENGTH_LONG).show()
            return
        }
        launcher.launch(null)
    }

    /** More folders a game's save is looked for in ([SavesLocation.saveFor]), e.g. RetroArch's per-core ones. */
    @Composable
    private fun AlsoLookInSection(tick: Int, m: GbaTextMetrics, small: GbaTextMetrics, onAdd: () -> Unit) {
        val dirs = remember(tick) { prefs.extraSaveDirs }
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = m.u * 4)) {
                GbaText(
                    tr("A game's save found in one of these folders is played and saved right there, so another emulator keeps seeing it. New saves go to the GAME SAVES folder. A game can have its own folder too: SAVE FOLDER in its library menu."),
                    OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE,
                )
                dirs.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(top = m.u * 3), verticalAlignment = Alignment.CenterVertically) {
                        GbaText(d, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f), maxLines = 2)
                        Spacer(Modifier.width(m.u * 4))
                        OptionButton(tk("REMOVE"), small, onClick = { prefs.extraSaveDirs = prefs.extraSaveDirs - d; folderTick++ })
                    }
                }
                Row(modifier = Modifier.padding(vertical = m.u * 4)) {
                    OptionButton(tk("ADD FOLDER"), small, onClick = onAdd)
                }
            }
        }
    }

    @Composable
    private fun FolderSection(
        /** Null: the page's own title says what it is. */
        title: String?,
        dir: File,
        tick: Int,
        m: GbaTextMetrics,
        small: GbaTextMetrics,
        extraButtons: @Composable () -> Unit = {},
    ) {
        val ctx = LocalContext.current
        // Listed off the UI thread, sizes with it: a big synced saves folder walked during composition (and every file's
        // size read again on each recomposition) froze Settings until Android called it not responding (Crashlytics, 1.3.3).
        val listing by produceState<List<Pair<File, Long>>?>(null, tick, dir) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Hidden folders skipped: a synced saves folder's .stversions / .stfolder hold every old copy.
                dir.walkTopDown().onEnter { it == dir || !it.name.startsWith(".") }
                    .filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.relativeToOrSelf(dir).path }
                    .map { it to it.length() }.toList()
            }
        }
        val files = listing.orEmpty()
        val total = files.sumOf { it.second }
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = m.u * 4)) {
                title?.let { GbaText(it, OptionColors.label, OptionColors.labelShadow, m) }
                GbaText(dir.absolutePath, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3)
                if (listing != null) {
                    GbaText(tr("{0} FILE(S) · {1}", files.size, humanSize(total)), OptionColors.muted, OptionColors.mutedShadow, small)
                }
                Row(
                    modifier = Modifier.padding(vertical = m.u * 4),
                    horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                ) {
                    OptionButton(tk("COPY PATH"), small, onClick = { copyPath(ctx, dir.absolutePath) })
                    extraButtons()
                }

                if (listing == null) {
                    // Still listing: nothing yet.
                } else if (files.isEmpty()) {
                    GbaText(tr("(EMPTY)"), OptionColors.muted, OptionColors.mutedShadow, small)
                } else {
                    files.forEach { (f, size) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = m.u * 2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GbaText(f.relativeToOrSelf(dir).path, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
                            GbaText(humanSize(size), OptionColors.muted, OptionColors.mutedShadow, small)
                            Box(
                                modifier = Modifier.clip(PixelRoundedShape(4.dp))
                                    .clickable { deletingFile = f }.padding(6.dp),
                            ) {
                                Icon(Icons.Filled.Delete, tr("Delete {0}", f.name), tint = OptionColors.value, modifier = Modifier.size(small.lineHeight))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun copyPath(ctx: Context, path: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("folder", path))
        Toast.makeText(ctx, tr("Path copied"), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val FOLDER_PAGES = setOf(Screen.ROMS_FOLDERS, Screen.SAVES_FOLDER, Screen.SAVE_SEARCH, Screen.STATES_FOLDER, Screen.BACKUPS)
        val RATES = floatArrayOf(0f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f)
        const val REPO_URL = "https://github.com/lidor30/pokedaisy"
        /** A ROM path: open straight on that game's CHEATS (its library menu). */
        const val EXTRA_CHEATS_ROM = "cheats_rom"
        /** Opens one page alone ([SCREEN_HELP]: the manual); BACK finishes. */
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HELP = "HELP"
        /** Opened as the library's SETTINGS tab (its rail / bar, its header over HOME). */
        const val EXTRA_TAB = "tab"
        /** With [EXTRA_TAB]: the library's TOOLS hold a NEW tool. */
        const val EXTRA_TOOLS_NEW = "tools_new"
        /** The result's library place to show (a [com.pokedaisy.app.companion.ui.LibrarySection]'s name). */
        const val EXTRA_SECTION = "section"
        /** [EXTRA_SCREEN]: FOLDERS > ROMS FOLDERS alone (the library's + > MANAGE ROMS FOLDERS). */
        const val SCREEN_ROMS_FOLDERS = "ROMS_FOLDERS"
        /** Cheat files are text; anything bigger isn't one. */
        const val MAX_CHEAT_FILE = 1 shl 20
        /** Retail Emerald is 16 MB: the only size worth hashing for co-op. */
        const val EMERALD_ROM_BYTES = 16L * 1024 * 1024
        /** One picked overlay file at most (read into memory; a pack of a few 4K borders is ~20 MB). */
        const val MAX_OVERLAY_FILE = 64L * 1024 * 1024

        private val cheatCrcs = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** [rom]'s CRC32 ([SaveStates.crc32], the key of its cheats like its states), remembered
         * by path + size + mtime. Not on the UI thread. */
        fun cheatCrcOf(rom: File): String? {
            val key = "${rom.absolutePath}|${rom.length()}|${rom.lastModified()}"
            cheatCrcs[key]?.let { return it }
            return runCatching { SaveStates.crc32(rom) }.getOrNull()?.also { cheatCrcs[key] = it }
        }

        /** What IMPORT FILE says once it's done (null: the file couldn't be read). */
        fun cheatImportNotice(r: CheatStore.Import?): Pair<String, String> = when {
            r == null -> tk("CAN'T READ THAT FILE") to tr("Pick a RetroArch .cht or an mGBA .cheats file.")
            r.added == 0 && r.badCheats == 0 && r.duplicates == 0 ->
                tk("NO CHEATS FOUND") to tr("That file has no cheats in a format PokeDaisy reads (RetroArch .cht, mGBA .cheats).")
            else -> (if (r.added == 0) tk("NO CHEATS ADDED") else tr("{0} CHEATS ADDED", r.added)) to listOfNotNull(
                tr("They start OFF: tap one to turn it on.").takeIf { r.added > 0 },
                tr("{0} left out: mGBA can't read {1} of their code lines.", r.badCheats, r.badLines).takeIf { r.badCheats > 0 },
                tr("{0} were already in the list.", r.duplicates).takeIf { r.duplicates > 0 },
            ).joinToString(" ")
        }
        const val COFFEE_URL = "https://buymeacoffee.com/lidor30g"

        fun rateLabel(v: Float) = if (v <= 0f) tk("INFINITE") else if (v % 1f == 0f) "${v.toInt()}×" else "$v×"

        fun humanSize(bytes: Long): String = when {
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
            bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
