@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.pokedaisy.app.companion.LibraryPreferences
import com.pokedaisy.app.companion.LibrarySort
import com.pokedaisy.app.companion.NewFeatures
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.*
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.game.RomPicker
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/** Files, persistence and launching for shared library/setup/settings presenters. There is no second native screen. */
internal class IosLibraryHost(
    private val touch: IosTouchControls,
    private val play: (String) -> Unit,
    failed: String?,
) {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val prefs = LibraryPreferences({ defaults.stringForKey("library_preferences") },
        { defaults.setObject(it, "library_preferences") })
    private var paths by mutableStateOf(PokeDaisyHost.games())
    private val library = com.pokedaisy.app.companion.LibraryCoordinator({ prefs })
    private var setupFromSettings = false
    private var setupStep by mutableStateOf<Int?>(if (!iosPreferences.setupDone && paths.isEmpty()) 0 else null)
    private var choosingSaveGame by mutableStateOf(false)
    private var notice by mutableStateOf<Pair<String, String>?>(failed?.let { tr("ERROR") to it.substringAfterLast('/') })
    private data class PendingSave(val rom: String, val name: String, val bytes: ByteArray)
    private var pendingSave by mutableStateOf<PendingSave?>(null)
    private var screen by mutableStateOf(DpSize.Zero)
    private var scale by mutableStateOf(1f)
    private lateinit var host: UIViewController
    private lateinit var appSettings: IosCompanionSettings
    private val back = CompanionBack()
    private val focus = LibraryFocus()

    fun controller(): UIViewController {
        appSettings = IosCompanionSettings(null, null, null,
            onClose = {}, onRestart = {}, onDisableResume = ::disableResume,
            onEditTouch = { touch.editStandalone(settingsStage()) }, readTouchStage = ::settingsStage)
        L10n.apply(appSettings.appLanguage, null)
        host = ComposeUIViewController(configure = { configureUiAutomation() }) {
            AppTheme { CompactTextOnSmallWindows {
                CompositionLocalProvider(LocalCompanionBack provides back) {
                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        SideEffect { screen = DpSize(maxWidth, maxHeight); scale = density }
                        val step = setupStep
                        if (step == null) Library() else Setup(step)
                        Dialogs()
                        touch.EditorOverlay()
                    }
                }
            } }
        }
        return host
    }

    private fun settingsStage(): com.pokedaisy.app.companion.TouchStage? {
        if (screen.width.value <= 0 || screen.height.value <= 0) return null
        return gamePanelLayout(screen.width.value * scale, screen.height.value * scale, scale,
            underGame = IosTouchPreferences.underGame, style = IosTouchPreferences.style)
    }

    private fun key(path: String) = path.substringAfterLast('/')
    private fun label(path: String) = key(path).substringBeforeLast('.')
    private fun category(path: String): RomCategory = when (path.substringAfterLast('.').lowercase()) {
        "gb" -> RomCategory.GB; "gbc" -> RomCategory.GBC; else -> RomCategory.GBA
    }
    private fun entries(): List<LibraryGame> {
        val games = paths.map { path ->
            val id = key(path)
            LibraryGame(path, label(path), "roms/$id", playing = prefs.last == id,
                isNew = prefs.isNew(id),
                hasSave = RomPicker.savePathFor(path).toPath().length > 0, favorite = id in prefs.favorites)
        }.filter { game -> com.pokedaisy.app.companion.matchesLibrarySearch(library.query, listOf(game.label, key(game.id))) }
            .sortedBy { it.label.lowercase() }
        return prefs.ordered(games) { key(it.id) }
    }

    private fun filtered(games: List<LibraryGame>, view: LibraryView): List<LibraryGame> =
        prefs.filtered(games, view, { key(it.id) }, { category(it.id) })

    @Composable
    private fun Library() {
        val capturing = appSettings.keyCapture.active
        LaunchedEffect(capturing) {
            if (capturing) while (true) {
                kotlinx.coroutines.delay(16)
                appSettings.pollKeyCapture()
            }
        }
        androidx.compose.runtime.DisposableEffect(appSettings) {
            val center = platform.Foundation.NSNotificationCenter.defaultCenter
            val observer = center.addObserverForName(platform.UIKit.UIApplicationWillResignActiveNotification, null, null) {
                appSettings.keyCapture.cancel()
            }
            onDispose { center.removeObserver(observer); appSettings.keyCapture.cancel() }
        }

        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(1f)
        val games = entries()
        val views = listOf(LibraryView.ALL, LibraryView.FAVORITES, LibraryView.RECENT) +
            listOf(LibraryView.GB, LibraryView.GBC, LibraryView.GBA).filter { view -> paths.any { category(it) == view.platform } }
        val view = library.view.takeIf { it in views } ?: LibraryView.ALL
        val shown = filtered(games, view)
        val tools = listOf(LibraryTool("save_editor", "LOAD SAVE",
            "The game starts from this save next time, not from where you left off.", null,
            open = { if (paths.isEmpty()) library.selectSection(LibrarySection.GAMES) else choosingSaveGame = true }))
        val state = LibraryPresentation(shown, library.grid, library.section, views, view,
            views.associateWith { filtered(games, it).size }, games.isNotEmpty(), library.query,
            tr("Add your own GBA or Game Boy ROMs from Files. Copies are kept in PokeDaisy's folder."), tools)
        val actions = library.actions(add = ::addGames, refresh = { paths = PokeDaisyHost.games() })
        LibraryScreen(state, actions, m, small, key = { it.id }, focus = focus,
            settings = {
                key(library.settingsPage) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                        CompanionSettingsScreen(appSettings, application = true, initialPage = library.settingsPage,
                            applicationFooter = { AppAboutFooter(NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "", m) { url ->
                                NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
                            } },
                            homeExtras = listOf(SettingRow("RUN SETUP", null, opensPage = true,
                                labelBadge = NewFeatures.badge(NewFeatures.IPAD_APP_UI)) { setupFromSettings = true; setupStep = 0 }))
                    }
                }
            }) { game, up ->
            var menu by remember(game.id) { mutableStateOf(false) }
            val openMenu = libraryLongPress { menu = true }
            val cover: @Composable (Modifier) -> Unit = { LibraryCover(null, category(game.id), m, it) }
            val items = listOf(
                LibraryMenuItem(if (game.favorite) "UNFAVORITE" else "FAVORITE",
                    if (game.favorite) Icons.Filled.StarBorder else Icons.Filled.Star,
                    pixel = if (game.favorite) PixelIcons.starOutline else PixelIcons.star) { prefs.toggleFavorite(key(game.id)) },
                LibraryMenuItem("LOAD SAVE", Icons.Filled.FileOpen, opens = true) { pickSave(game.id) })
            val onPlay = { prefs.recordPlay(key(game.id)); play(game.id) }
            if (prefs.grid) LibraryRomTile(game, m, small, upTo = up, onClick = onPlay,
                onLongClick = openMenu, cover = cover, menu = { LibraryGameMenu(menu, { menu = false }, items, m, screen) })
            else LibraryRomRow(game, m, small, upTo = up, onClick = onPlay,
                onLongClick = openMenu, cover = cover, menu = {
                    Box { LibraryMenuDots(m) { menu = true }; LibraryGameMenu(menu, { menu = false }, items, m, screen) }
                })
        }
    }

    @Composable
    private fun Setup(step: Int) {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(1f)
        SetupFrame(step, 2, step > 0 || setupFromSettings, busy = false, last = step == 1, skipping = step == 0 && paths.isEmpty(),
            m, onBack = { setupStep = if (step == 0) null else step - 1 },
            onNext = {
                if (step == 0) setupStep = 1 else { iosPreferences.setupDone = true; setupStep = null }
            }) {
            if (step == 0) SetupRomsPage(SetupRomsModel(if (paths.isEmpty()) emptyList() else listOf("PokeDaisy/roms"),
                paths.map(::label), linked = false), m, small, onChoose = ::addGames)
            else SetupSavesPage(SetupSavesModel(null), m, small, onChoose = null, onUse = {})
        }
    }

    @Composable
    private fun Dialogs() {
        val m = rememberGbaTextMetrics()
        LibraryNavigationDialogs(library, m, rememberGbaTextMetrics(1f))
        if (choosingSaveGame && paths.isNotEmpty()) OptionSelector(tr("LOAD SAVE"), paths, paths.first(), ::label, m,
            onPick = { choosingSaveGame = false; pickSave(it) }, onDismiss = { choosingSaveGame = false })
        pendingSave?.let { picked ->
            val base = label(picked.rom)
            val backup = "saves/$base.backup-*.sav"
            OptionConfirm(tr("LOAD SAVE?"),
                tr("{0}: replace its save with {1}? The current save is kept, renamed to {2}.", base, picked.name, backup) +
                    "\n" + tr("The game starts from this save next time, not from where you left off."), tr("LOAD"), m,
                onConfirm = {
                    val result = PokeDaisyHost.loadSave(picked.rom, picked.bytes)
                    pendingSave = null
                    notice = tr("LOAD SAVE") to (result ?: tr("Save loaded"))
                    paths = PokeDaisyHost.games()
                }, onDismiss = { pendingSave = null })
        }
        notice?.let { (title, message) ->
            OptionConfirm(title, message, tr("OK"), m, onConfirm = { notice = null }, onDismiss = { notice = null }, cancelLabel = null)
        }
    }

    private fun addGames() {
        val known = paths.map(::key).toSet()
        PokeDaisyHost.addGames(host) { added ->
            prefs.recordAdded(added.map(::key).filter { it !in known })
            paths = PokeDaisyHost.games()
            if (added.isNotEmpty()) {
                library.search("")
                prefs.setView(LibraryView.ALL)
                if (setupStep == null) library.selectSection(LibrarySection.GAMES)
            }
        }
    }

    private fun pickSave(rom: String) {
        RomPicker.pickSave(host) { picked ->
            if (picked != null) {
                val bytes = runCatching {
                    val path = picked.toPath()
                    require(path.length in 1L..(1L shl 20))
                    path.readBytes()
                }.getOrNull()
                if (bytes == null) notice = tr("LOAD SAVE") to tr("This doesn't look like a GBA save file.")
                else pendingSave = PendingSave(rom, picked.substringAfterLast('/'), bytes)
            }
        }
    }

    private fun disableResume(): Boolean = runCatching {
        val directory = RomPicker.statesDir.toPath()
        FileSystem.SYSTEM.list(directory).filter { it.isDirectory }.forEach { StateResume.startFresh(StateFiles(it)) }
        true
    }.getOrElse {
        notice = tr("RESUME GAMES") to tr("Couldn't update resume data. Keep an in-game save too.")
        false
    }
}
