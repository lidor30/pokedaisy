package com.pokedaisy.app

import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pokedaisy.app.companion.ui.OptionLine
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionRows
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.hasGrid
import com.pokedaisy.app.companion.next
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.ui.OptionTextField
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.drawLayeredBox
import com.pokedaisy.app.companion.ui.inPx
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.APP_THEMES
import com.pokedaisy.app.companion.ui.theme.QolColors
import com.pokedaisy.app.companion.ui.theme.QolTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.ui.PixelRoundedShape
import com.pokedaisy.app.companion.ui.drawPixelRoundRect

/**
 * Settings, in the companion SETTINGS tab's OPTION-screen look: a hub of
 * `LABEL  VALUE` rows (FF speed / touch pad / theme open a pick-one list,
 * on/off rows flip in place) plus one page each for Hotkeys, Game buttons,
 * Folders and Cover art. BACK pops back to the hub; BACK on the hub leaves.
 */
class SettingsActivity : ComponentActivity() {

    private enum class Screen { HOME, HOTKEYS, CONTROLS, SHADERS, OVERLAYS, FOLDERS, COVER_ART, HIDDEN, ACHIEVEMENTS, CHEATS, CHEAT_ADD, LICENSES }

    private lateinit var prefs: Prefs
    private var screen by mutableStateOf(Screen.HOME)
    private var deletingFile by mutableStateOf<File?>(null)
    private var folderTick by mutableIntStateOf(0)

    private var capturing by mutableStateOf<Hotkeys.Action?>(null)
    private var capturingCtrl by mutableStateOf<GbaControls.Btn?>(null)
    private var captured by mutableStateOf<List<Int>>(emptyList())
    private val stillHeld = LinkedHashSet<Int>()
    /** A captured chord that other hotkeys already have exactly ([taken]): MOVE takes it off them. */
    private class HotkeyClash(val action: Hotkeys.Action, val keys: List<Int>, val taken: List<Hotkeys.Action>)
    private var hotkeyClash by mutableStateOf<HotkeyClash?>(null)
    private var revision by mutableIntStateOf(0)
    private var aspectPicker by mutableStateOf(false)
    /** HOME's scroll, kept while a sub-page is open so BACK returns to the same rows. */
    private val homeScroll = androidx.compose.foundation.ScrollState(0)
    /** This screen's width / height - the top screen's, for the ASPECT preview. */
    private var screenAspect by mutableFloatStateOf(16f / 9f)

    private val filesRoot get() = getExternalFilesDir(null) ?: filesDir

    /** The game's screen's width / height: this one, or the second screen with SWAP SCREENS on. */
    private fun gameScreenAspect(): Float =
        if (prefs.swapScreens) Screens.secondAspect(this) ?: screenAspect else screenAspect

    /** SWAP SCREENS only means something with two. */
    private val hasSecondScreen by lazy { Screens.hasSecond(this) }

    private val updates by lazy { AppUpdateFlow(this) }

    /** Opened from a game's library menu (CHEATS): BACK from its cheats leaves, not to the hub. */
    private var cheatsOnly = false
    /** HOME's search box; kept while a sub-page it led to is open. */
    private var searchQuery by mutableStateOf("")
    private var controlsOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RomFolder.useBestEffortStore(this)
        prefs = Prefs(this)
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        RetroAchievements.init(this)
        intent?.getStringExtra(EXTRA_CHEATS_ROM)?.let { path ->
            cheatsOnly = true
            cheatRom = File(path)
            screen = Screen.CHEATS
        }
        // First-time setup's GAME BUTTONS step: the rebind page alone, BACK returns to setup.
        if (intent?.getStringExtra(EXTRA_SCREEN) == SCREEN_CONTROLS) {
            controlsOnly = true
            screen = Screen.CONTROLS
        }
        setContent { QolTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        com.pokedaisy.app.companion.ui.OptionColors.inGame = false
        updates.onResume()
    }

    // ---- physical-key capture (Hotkeys / Game buttons screens) ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (capturing == null && capturingCtrl == null) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) { cancelCapture(); return true }
        if (keyCode !in captured) captured = captured + keyCode
        stillHeld.add(keyCode)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (capturing == null && capturingCtrl == null) return super.onKeyUp(keyCode, event)
        stillHeld.remove(keyCode)
        if (stillHeld.isEmpty() && captured.isNotEmpty()) {
            capturing?.let { action ->
                // The same chord on another hotkey: asked first, never two actions on one chord.
                val taken = Hotkeys.clashes(Hotkeys.load(filesRoot).rawBindings, action, captured.map { Hotkeys.keyName(it) })
                if (taken.isEmpty()) Hotkeys.setBinding(filesRoot, action, captured)
                else hotkeyClash = HotkeyClash(action, captured, taken)
            }
            capturingCtrl?.let { GbaControls.setBinding(filesRoot, it, captured.first()) }
            revision++
            cancelCapture()
        }
        return true
    }

    private fun cancelCapture() {
        capturing = null; capturingCtrl = null; captured = emptyList(); stillHeld.clear()
    }

    // ---- shell --------------------------------------------------------------

    @Composable
    private fun Root() {
        BackHandler(enabled = screen != Screen.HOME) { cancelCapture(); goBack() }
        BackHandler(enabled = aspectPicker) { aspectPicker = false }
        BackHandler(enabled = hotkeyClash != null) { hotkeyClash = null }
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(textScale = 1f)

        Box(modifier = Modifier.fillMaxSize().onSizeChanged {
            if (it.width > 0 && it.height > 0) screenAspect = it.width.toFloat() / it.height
        }) {
            AppBackdrop()
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                OptionTitleWindow(
                    title = when (screen) {
                        Screen.HOME -> tk("SETTINGS")
                        Screen.HOTKEYS -> tk("HOTKEYS")
                        Screen.CONTROLS -> tk("GAME BUTTONS")
                        Screen.SHADERS -> tk("SHADERS")
                        Screen.OVERLAYS -> tk("OVERLAY")
                        Screen.FOLDERS -> tk("FOLDERS")
                        Screen.COVER_ART -> tk("COVER ART")
                        Screen.HIDDEN -> tk("HIDDEN GAMES")
                        Screen.ACHIEVEMENTS -> "RetroAchievements"
                        Screen.CHEATS -> tk("CHEATS")
                        Screen.CHEAT_ADD -> tk("ADD CODE")
                        Screen.LICENSES -> tk("LICENSES")
                    },
                    m = m,
                    onBack = {
                        cancelCapture()
                        goBack()
                    },
                )
                Spacer(Modifier.height(m.u * 4))
                when (screen) {
                    Screen.HOME -> HomeScreen(m)
                    Screen.HOTKEYS -> HotkeysScreen(m, small)
                    Screen.CONTROLS -> ControlsScreen(m, small)
                    Screen.SHADERS -> ShadersScreen(m)
                    Screen.OVERLAYS -> OverlaysScreen(m, small)
                    Screen.FOLDERS -> FoldersScreen(m, small)
                    Screen.COVER_ART -> CoverArtScreen(m, small)
                    Screen.HIDDEN -> HiddenScreen(m, small)
                    Screen.ACHIEVEMENTS -> AchievementsScreen(m, small)
                    Screen.CHEATS -> CheatsScreen(m, small)
                    Screen.CHEAT_ADD -> CheatAddScreen(m, small)
                    Screen.LICENSES -> LicensesScreen(m, small)
                }
            }

            selector?.let { sel ->
                OptionSelector(
                    sel.title, sel.options, sel.current, { it }, m,
                    onPick = { sel.onPick(it); selector = null; revision++ },
                    onDismiss = { selector = null },
                    badge = sel.badge,
                )
            }

            if (aspectPicker) {
                val shot by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { newestStateThumb()?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull()
                    }
                }
                AspectPicker(
                    prefs.stretchGame, gameScreenAspect(), prefs.statusBar && !prefs.statusBarOnCompanion, shot, m,
                    onPick = { prefs.stretchGame = it; aspectPicker = false; revision++ },
                    onDismiss = { aspectPicker = false },
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
                        c.keys.joinToString(" + ") { keyLabel(Hotkeys.keyName(it)) },
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

    /** Back one page: ADD CODE to CHEATS, a page to the hub, the hub (or cheats opened from a game's menu) out. */
    private fun goBack() {
        when {
            screen == Screen.CHEAT_ADD -> screen = Screen.CHEATS
            screen == Screen.HOME || (screen == Screen.CHEATS && cheatsOnly) || (screen == Screen.CONTROLS && controlsOnly) -> finish()
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
        val onPick: (String) -> Unit,
    )

    private var selector by mutableStateOf<Selector?>(null)

    // ---- hub --------------------------------------------------------------

    /** Like the companion's SETTINGS tab: `LABEL  VALUE` rows; on/off rows
     * flip on tap, multi-choice rows open a pick-one list, the rest open
     * their own page. */
    @Composable
    private fun HomeScreen(m: GbaTextMetrics) {
        @Suppress("UNUSED_EXPRESSION") revision
        val rateLabels = RATES.map(::rateLabel)
        // Upper case, but the game's small é (POKéDAISY, like POKéMON).
        val themeLabels = APP_THEMES.map { it.label.uppercase().replace('É', 'é') }
        val themeIdx = APP_THEMES.indexOfFirst { it.id == prefs.appTheme }.coerceAtLeast(0)
        fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
        // The same groups and order as the bottom screen's SETTINGS where they overlap.
        val rows = listOfNotNull(
            groupTitle(tk("FAST-FORWARD")),
            SettingRow(tk("FF SPEED"), rateLabel(prefs.ffMaxSpeed)) {
                selector = Selector(tk("FF SPEED"), rateLabels, rateLabel(prefs.ffMaxSpeed)) {
                    prefs.ffMaxSpeed = RATES[rateLabels.indexOf(it)]
                }
            },
            // SMART: menus and the region map at 1x; NORMAL: fast everywhere.
            SettingRow(tk("FF MODE"), prefs.ffMode.label) {
                val modes = FfMode.entries
                selector = Selector(tk("FF MODE"), modes.map { it.label }, prefs.ffMode.label) { l -> prefs.ffMode = modes.first { it.label == l } }
            },
            // STEADY / SPED-UP / OFF (FfMusicMode's order), unfinished ones tagged ALPHA.
            SettingRow(tk("FF MUSIC"), prefs.ffMusicMode.label, badge = tk("ALPHA").takeIf { prefs.ffMusicMode.alpha }) {
                val modes = FfMusicMode.entries
                selector = Selector(
                    tk("FF MUSIC"), modes.map { it.label }, prefs.ffMusicMode.label,
                    badge = { l -> tk("ALPHA").takeIf { modes.first { it.label == l }.alpha } },
                ) { l -> prefs.ffMusicMode = modes.first { it.label == l } }
            },
            // Keeps the last ~20 s; the REWIND HOLD hotkey plays them backwards.
            SettingRow(tk("REWIND"), if (prefs.rewind) tk("ON") else tk("OFF")) { prefs.rewind = !prefs.rewind; revision++ },
            groupTitle(tk("CONTROLS")),
            // Takes effect the next time a game is opened.
            SettingRow(tk("TOUCH PAD"), TOUCH_NAMES[prefs.touchControlsMode]) {
                selector = Selector(tk("TOUCH PAD"), TOUCH_NAMES, TOUCH_NAMES[prefs.touchControlsMode]) {
                    prefs.touchControlsMode = TOUCH_NAMES.indexOf(it)
                }
            },
            SettingRow(tk("GAME BUTTONS"), null, subtitle = "Key bindings: which button presses A, B, START...") { screen = Screen.CONTROLS },
            SettingRow(tk("HOTKEYS"), onOff(prefs.hotkeysEnabled)) { screen = Screen.HOTKEYS },
            groupTitle(tk("SCREEN")),
            // Game, location, money, clock and battery: OFF, over the game, or over the companion's tabs.
            SettingRow(tk("STATUS BAR"), statusBarLabel(prefs.statusBar, prefs.statusBarOnCompanion)) {
                selector = Selector(tk("STATUS BAR"), STATUS_BAR_PLACES, statusBarLabel(prefs.statusBar, prefs.statusBarOnCompanion)) {
                    val i = STATUS_BAR_PLACES.indexOf(it)
                    if (i > 0) prefs.statusBarOnCompanion = i == 2
                    prefs.statusBar = i > 0
                }
            },
            // The game at 3:2 or stretched to fill the screen; picked by preview.
            SettingRow(tk("ASPECT"), aspectLabel(prefs.stretchGame)) { aspectPicker = true },
            // FILTER (LCD / LCD PAPER / SCANLINES / CRT) and GBA COLORS, on their own page.
            SettingRow(tk("SHADERS"), prefs.screenFilter.label) { screen = Screen.SHADERS },
            // A frame around the game: the built-in ones and imported RetroArch overlays, on their own page.
            SettingRow(tk("OVERLAY"), overlayLabel(overlayChoices(overlayStore), prefs.overlay)) { screen = Screen.OVERLAYS },
            // One screen held upright: the companion along the bottom, or right under the game (the touch pad below it).
            SettingRow(tk("COMPANION"), portraitPlaceLabel(prefs.portraitCompanionUnderGame)) {
                prefs.portraitCompanionUnderGame = !prefs.portraitCompanionUnderGame
                revision++
            }.takeIf { !hasSecondScreen },
            // Game and companion trade screens; the game picks it up on resume.
            SettingRow(tk("SWAP SCREENS"), onOff(prefs.swapScreens)) {
                prefs.swapScreens = !prefs.swapScreens
                revision++
            }.takeIf { hasSecondScreen },
            // Changes the colors immediately, everywhere — the companion screen too.
            SettingRow(tk("THEME"), themeLabels[themeIdx]) {
                selector = Selector(tk("THEME"), themeLabels, themeLabels[themeIdx]) {
                    val spec = APP_THEMES[themeLabels.indexOf(it)]
                    prefs.appTheme = spec.id
                    QolColors.applyTheme(spec)
                }
            },
            groupTitle(tk("LIBRARY")),
            // On: a game opens where it was left. Off: at its title screen, from the save.
            SettingRow(tk("RESUME GAMES"), onOff(prefs.autoResume)) {
                prefs.autoResume = !prefs.autoResume
                revision++
            },
            SettingRow(tk("FOLDERS"), null) { screen = Screen.FOLDERS },
            SettingRow(tk("COVER ART"), onOff(prefs.steamGridDbApiKey != null || prefs.raWebApiKey != null)) { screen = Screen.COVER_ART },
            SettingRow(tk("HIDDEN GAMES"), RomFolder.hiddenRoms(this@SettingsActivity, prefs).size.takeIf { it > 0 }?.toString() ?: tk("NONE")) { screen = Screen.HIDDEN },
            // A game's GameShark / Action Replay / CodeBreaker codes, on their own page.
            SettingRow(tk("CHEATS"), onOff(prefs.cheatsEnabled)) { screen = Screen.CHEATS },
            groupTitle(tk("ONLINE")),
            // Signed in = on; the account's name, else OFF.
            SettingRow(
                "RetroAchievements", prefs.raUsername?.uppercase() ?: tk("OFF"), labelBadge = tk("BETA"),
                labelIcon = { GoldTrophy() },
            ) { screen = Screen.ACHIEVEMENTS },
            groupTitle(tk("APP")),
            // AUTO: the ROM's language in game, the device's here. Applies at once, both screens.
            SettingRow("LANGUAGE", L10n.settingLabel(prefs.appLanguage)) {
                val opts = L10n.options
                selector = Selector("LANGUAGE", opts.map { it.second }, opts.first { it.first == prefs.appLanguage }.second) { l ->
                    prefs.appLanguage = opts.first { it.second == l }.first
                    L10n.apply(prefs.appLanguage, null)
                }
            },
            // Tap: look for a newer release on GitHub now.
            SettingRow(tk("VERSION"), if (updates.checking) tk("CHECKING…") else BuildConfig.VERSION_NAME) { updates.check(manual = true) },
            // The GPL's notices and every bundled component's license.
            SettingRow(tk("LICENSES"), null) { screen = Screen.LICENSES },
            // Back to the library, which opens the first-time setup again.
            SettingRow(tk("RUN SETUP"), null) { prefs.setupRequested = true; finish() },
        )
        // Search: every row whose name, value or keywords (SETTING_KEYWORDS: "rebind", "keybinds"...) match.
        val shown = filterRows(rows, searchQuery)
        Column(Modifier.fillMaxSize()) {
            OptionTextField(
                searchQuery, { searchQuery = it }, m, Modifier.fillMaxWidth(),
                placeholder = tr("SEARCH SETTINGS (e.g. KEY BINDINGS)"),
            )
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                if (shown.isEmpty()) {
                    GbaText(
                        tr("NOTHING MATCHES \"{0}\"", searchQuery.trim()), OptionColors.muted, OptionColors.mutedShadow, m,
                        modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                    )
                } else if (searchQuery.isBlank()) {
                    GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() }, scroll = homeScroll)
                } else {
                    GroupedRows(shown, m, cursor = -1, onClick = { shown[it].onClick() })
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
            // The colours as the GBA's own LCD showed them; stacks with any filter.
            SettingRow(tk("GBA COLORS"), if (prefs.gbaColors) tk("ON") else tk("OFF")) {
                prefs.gbaColors = !prefs.gbaColors
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

    /** Version, author, Buy Me a Coffee and the repo, under the settings list. */
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
    private fun Hint(text: String, m: GbaTextMetrics, small: GbaTextMetrics) {
        OptionListWindow(m, Modifier.fillMaxWidth().padding(bottom = m.u * 4)) {
            GbaText(
                text, OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                modifier = Modifier.padding(horizontal = m.u * 6, vertical = m.u * 2),
            )
        }
    }

    // ---- RetroAchievements --------------------------------------------------

    /** Sign in / out of RetroAchievements. Only the token the server returns is
     * kept (Prefs); the password goes straight to the login call. */
    @Composable
    private fun AchievementsScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val user by RetroAchievements.user.collectAsState()
        val signingIn by RetroAchievements.signingIn.collectAsState()
        val state by RetroAchievements.state.collectAsState()
        var name by remember { mutableStateOf(prefs.raUsername ?: "") }
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val savedName = prefs.raUsername.takeIf { prefs.raToken != null }

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Hint(
                tr("Earn RetroAchievements (retroachievements.org) as you play. Unlocks pop up on the second screen, and its CHEEVOS tab (under SETTINGS > TOOLS there) lists the game's set. Only ROMs RetroAchievements knows by their exact hash have achievements. Softcore only for now. Your password isn't stored: the app keeps the login token RetroAchievements sends back."),
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
        }
    }

    // ---- cheats -------------------------------------------------------------

    /** The game CHEATS shows (its library menu's, else the last one played), its CRC once
     * worked out, and its cheats (cheats/Cheats.kt). */
    private var cheatRom by mutableStateOf<File?>(null)
    private var cheatCrc by mutableStateOf<String?>(null)
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
            add(SettingRow(tk("CHEATS"), onOff(master)) { prefs.cheatsEnabled = !master; revision++ })
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
            val result = text?.let { store.import(it, name) }
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
                tr("Type or paste a GameShark, Action Replay or CodeBreaker code, one line or many. AUTO works out which it is; pick the type when a code doesn't take."),
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
                    // Back by the list row's own inset, so TYPE lines up with NAME and CODE.
                    OptionLine(tk("TYPE"), type.label, selected = false, m, Modifier.offset(x = -m.u * 4), height = m.rowHeight * 1.2f) {
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
                            val r = CheatCheck.check(Cheat(label, lines), type)
                            val ok = r.cheat
                            if (ok == null) {
                                error = when {
                                    r.bad.isEmpty() -> tr("That isn't a code.")
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

    /** One rebindable row: tap to capture (the row turns white and the value
     * reads PRESS KEYS…), tap again to cancel. */
    @Composable
    private fun CaptureRow(
        label: String, keys: List<String>, isCapturing: Boolean, prompt: String, m: GbaTextMetrics,
        divider: Boolean, enabled: Boolean = true, onClick: () -> Unit,
    ) {
        val value = when {
            isCapturing && captured.isEmpty() -> prompt
            isCapturing -> captured.joinToString(" + ") { keyLabel(Hotkeys.keyName(it)) }
            else -> keys.joinToString(" / ") { keyLabel(it) }.ifEmpty { "-" }
        }
        OptionLine(label, value, selected = isCapturing, m, height = m.rowHeight * 1.2f, labelWeight = 0.5f, divider = divider, enabled = enabled, onClick = onClick)
    }

    @Composable
    private fun HotkeysScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val hotkeys = remember(revision) { Hotkeys.load(filesRoot) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Hint(
                tr("Tap a hotkey, then press one or more keys/buttons together and release — e.g. hold Select and tap R1 for a chord. BACK cancels."),
                m, small,
            )
            // While a hotkey waits for keys: CLEAR leaves it unbound ("-").
            capturing?.let { action ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.u * 8),
                    modifier = Modifier.fillMaxWidth().padding(bottom = m.u * 4),
                ) {
                    OptionButton(tr("CLEAR {0}", tr(action.title)), m, modifier = Modifier.weight(1f), emphasis = true, onClick = {
                        Hotkeys.clearBinding(filesRoot, action)
                        revision++
                        cancelCapture()
                    })
                    OptionButton(tk("CANCEL"), m, modifier = Modifier.weight(1f), onClick = ::cancelCapture)
                }
            }
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Off, the binds stay as they are, greyed out, and every key goes to the game.
                    val on = prefs.hotkeysEnabled
                    OptionLine(
                        tk("HOTKEYS"), if (on) tk("ON") else tk("OFF"), selected = false, m, height = m.rowHeight * 1.2f,
                        labelWeight = 0.5f, divider = true,
                    ) { cancelCapture(); prefs.hotkeysEnabled = !on; revision++ }
                    Hotkeys.Action.entries.forEachIndexed { i, action ->
                        CaptureRow(
                            action.title, hotkeys.rawBindings[action].orEmpty(),
                            isCapturing = capturing == action, prompt = tk("PRESS KEYS…"), m = m,
                            divider = i < Hotkeys.Action.entries.lastIndex, enabled = on,
                        ) {
                            if (capturing == action) cancelCapture() else { cancelCapture(); capturing = action }
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
            Hint(tr("Which physical button is each GBA button. The D-pad and analog stick are fixed."), m, small)
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GbaControls.Btn.entries.forEachIndexed { i, btn ->
                        CaptureRow(
                            "GBA ${btn.label}", ctrls[btn].orEmpty(),
                            isCapturing = capturingCtrl == btn, prompt = tk("PRESS A BUTTON…"), m = m,
                            divider = i < GbaControls.Btn.entries.lastIndex,
                        ) {
                            if (capturingCtrl == btn) cancelCapture() else { cancelCapture(); capturingCtrl = btn }
                        }
                    }
                }
            }
        }
    }

    // ---- folders --------------------------------------------------------

    @Composable
    private fun FoldersScreen(m: GbaTextMetrics, small: GbaTextMetrics) {
        val tick = folderTick
        val savesDir = remember(tick) { SavesLocation.dir(this@SettingsActivity, prefs) }
        val statesDir = remember { File(getExternalFilesDir(null), "states").apply { mkdirs() } }
        val pickSavesDir = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            prefs.savesDirOverride = path
            folderTick++
        }
        val pickExtraSavesDir = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            prefs.extraSaveDirs = prefs.extraSaveDirs + path
            folderTick++
        }
        val pickRomsDir = rememberLauncherForActivityResult(StorageAccess.PickFolder()) { uri ->
            val path = pickedFolder(uri) ?: return@rememberLauncherForActivityResult
            if (path != prefs.romsFolder) {
                prefs.romsFolder = path
                RomFolder.clearCache(this)
            }
            rescanRomsFolder()
        }

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            RomsFolderSection(m, small, onChoose = { pickFolder(pickRomsDir) })
            Spacer(Modifier.height(m.u * 4))
            FolderSection(tr("GAME SAVES (.SAV / .SRM)"), savesDir, tick, m, small) {
                OptionButton(tk("CHANGE FOLDER"), small, onClick = { pickFolder(pickSavesDir) })
                if (prefs.savesDirOverride != null) {
                    OptionButton(tk("USE DEFAULT"), small, onClick = { prefs.savesDirOverride = null; folderTick++ })
                }
            }
            Spacer(Modifier.height(m.u * 4))
            AlsoLookInSection(tick, m, small, onAdd = { pickFolder(pickExtraSavesDir) })
            Spacer(Modifier.height(m.u * 4))
            FolderSection(tr("SAVE STATES"), statesDir, tick, m, small)
        }
    }

    /** The linked ROMs folder: no file list or delete buttons here - those are
     * the player's own files, not ours. */
    @Composable
    private fun RomsFolderSection(m: GbaTextMetrics, small: GbaTextMetrics, onChoose: () -> Unit) {
        val tick = folderTick
        val folder = prefs.romsFolder
        val found = remember(tick, folder) { RomFolder.found(this@SettingsActivity, prefs).size }
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = m.u * 4)) {
                GbaText(tr("ROMS FOLDER"), OptionColors.label, OptionColors.labelShadow, m)
                GbaText(folder ?: tr("NOT LINKED"), OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3)
                GbaText(
                    when {
                        folder == null -> tr("Link the folder you keep your games in: its supported ROMs join the library and play from there.")
                        romsScanning -> tr("LOOKING FOR GAMES…")
                        else -> tr("{0} GAME(S) IN THE LIBRARY · CHECKED FOR NEW ONES EACH TIME THE APP OPENS", found)
                    },
                    OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE,
                )
                Row(
                    modifier = Modifier.padding(vertical = m.u * 4),
                    horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                ) {
                    OptionButton(if (folder == null) tk("CHOOSE FOLDER") else tk("CHANGE FOLDER"), small, onClick = onChoose)
                    if (folder != null) {
                        OptionButton(tk("RESCAN"), small, enabled = !romsScanning, onClick = { rescanRomsFolder() })
                        OptionButton(tk("UNLINK"), small, onClick = { prefs.romsFolder = null; RomFolder.clearCache(this@SettingsActivity); folderTick++ })
                    }
                }
            }
        }
    }

    private var romsScanning by mutableStateOf(false)

    private fun rescanRomsFolder() {
        if (romsScanning) return
        romsScanning = true
        folderTick++
        Thread({
            RomFolder.scan(this, prefs)
            runOnUiThread { romsScanning = false; folderTick++ }
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
                GbaText(tr("ALSO LOOK IN"), OptionColors.label, OptionColors.labelShadow, m)
                GbaText(
                    tr("A game's save found in one of these folders is played and saved right there, so another emulator keeps seeing it. New saves go to the saves folder above. A game can have its own folder too: SAVE FOLDER in its library menu."),
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
        title: String,
        dir: File,
        tick: Int,
        m: GbaTextMetrics,
        small: GbaTextMetrics,
        extraButtons: @Composable () -> Unit = {},
    ) {
        val ctx = LocalContext.current
        val files = remember(tick, dir) {
            // Hidden folders skipped: a synced saves folder's .stversions / .stfolder hold every old copy.
            dir.walkTopDown().onEnter { it == dir || !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.relativeToOrSelf(dir).path }.toList()
        }
        val total = files.sumOf { it.length() }
        OptionListWindow(m, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = m.u * 4)) {
                GbaText(title, OptionColors.label, OptionColors.labelShadow, m)
                GbaText(dir.absolutePath, OptionColors.muted, OptionColors.mutedShadow, small, maxLines = 3)
                GbaText(tr("{0} FILE(S) · {1}", files.size, humanSize(total)), OptionColors.muted, OptionColors.mutedShadow, small)
                Row(
                    modifier = Modifier.padding(vertical = m.u * 4),
                    horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                ) {
                    OptionButton(tk("COPY PATH"), small, onClick = { copyPath(ctx, dir.absolutePath) })
                    extraButtons()
                }

                if (files.isEmpty()) {
                    GbaText(tr("(EMPTY)"), OptionColors.muted, OptionColors.mutedShadow, small)
                } else {
                    files.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = m.u * 2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GbaText(f.relativeToOrSelf(dir).path, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
                            GbaText(humanSize(f.length()), OptionColors.muted, OptionColors.mutedShadow, small)
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
        val RATES = floatArrayOf(0f, 2f, 3f, 4f, 5f, 6f, 8f, 10f)
        val TOUCH_NAMES = listOf(tk("AUTO"), tk("ALWAYS"), tk("NEVER"))
        const val REPO_URL = "https://github.com/lidor30/pokedaisy"
        /** A ROM path: open straight on that game's CHEATS (its library menu). */
        const val EXTRA_CHEATS_ROM = "cheats_rom"
        /** Opens one page alone ([SCREEN_CONTROLS]: GAME BUTTONS, for setup); BACK finishes. */
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_CONTROLS = "CONTROLS"
        /** Cheat files are text; anything bigger isn't one. */
        const val MAX_CHEAT_FILE = 1 shl 20
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

        fun rateLabel(v: Float) = if (v <= 0f) tk("INFINITE") else "${v.toInt()}×"

        fun keyLabel(name: String) = name.replace("BUTTON_", "").replace('_', ' ')

        fun humanSize(bytes: Long): String = when {
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
            bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
