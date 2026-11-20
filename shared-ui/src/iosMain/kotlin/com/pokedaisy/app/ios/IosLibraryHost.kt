@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.romFileStem
import com.pokedaisy.app.companion.ui.LibraryIcons
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.pokedaisy.app.companion.SaveImportFlow
import com.pokedaisy.app.GameInfo
import com.pokedaisy.app.GameInfoDialog
import com.pokedaisy.app.GameInfoDetails
import com.pokedaisy.app.GameSaves
import com.pokedaisy.app.RomPatchFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pokedaisy.app.companion.gameButtonRequest
import com.pokedaisy.app.companion.SetupFlow
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
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

internal fun iosRomTitle(path: String) = romFileStem(path.substringAfterLast('/'))

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
    private var savedGames by mutableStateOf<Set<String>>(emptySet())
    private var saveRefresh by mutableIntStateOf(0)
    private val library = com.pokedaisy.app.companion.LibraryCoordinator({ prefs })
    private var setup by mutableStateOf<SetupFlow<Int>?>(
        if (!iosPreferences.setupDone && paths.isEmpty()) newSetup() else null)
    private enum class AddChoice(val label: String) { ROM("ADD A ROM FILE"), PATCH("PATCH A ROM") }
    private var choosingAdd by mutableStateOf(false)
    private var choosingSaveGame by mutableStateOf(false)
    private var infoPath by mutableStateOf<String?>(null)
    private var renaming by mutableStateOf<String?>(null)
    private var notice by mutableStateOf<Pair<String, String>?>(failed?.let { tr("ERROR") to it.substringAfterLast('/') })
    private val saveImports = SaveImportFlow<String>(GameSaves.MAX_BYTES)
    private val patches = RomPatchFlow(
        find = { patch, patchName, progress ->
            val files = FileSystem.SYSTEM
            val dir = RomPicker.romsDir.toPath()
            val base = RomPatchFiles.findBase(
                PokeDaisyHost.games().map { it.toPath() }, patch.sourceSize, patch.sourceCrc,
                sizeOf = { files.metadata(it).size ?: -1L },
                crcOf = ::romCrc32, onProgress = progress,
            )
            base?.let {
                val name = RomPatchFiles.outputName(patchName, it.name.substringAfterLast('.').lowercase())
                RomPatchFlow.Plan(it, label(it.toString()), dir, name)
            }
        },
        write = { patch, plan, progress ->
            val result = RomPatchFiles.apply(patch, plan.dir, plan.name, { FileSystem.SYSTEM.source(plan.base) }, progress)
            RomPatchFlow.Result(result.path, result.existed, RomPatchFlow.Support.LISTED, label(result.path.toString()))
        },
        onAdded = { result ->
            if (!result.existed) prefs.recordAdded(listOf(result.file.name))
            showImportedGames()
        },
    )
    private var screen by mutableStateOf(DpSize.Zero)
    private var scale by mutableStateOf(1f)
    private lateinit var host: UIViewController
    private lateinit var appSettings: IosCompanionSettings
    private val back = CompanionBack()
    private val focus = LibraryFocus()

    fun controller(): UIViewController {
        appSettings = IosCompanionSettings(null, null, null,
            onClose = {}, onRestart = {}, onDisableResume = ::disableResume,
            onEditTouch = { touch.editStandalone(settingsStage()) }, readTouchStage = ::settingsStage, presentFrom = { host })
        L10n.apply(appSettings.appLanguage, null)
        host = ComposeUIViewController(configure = { configureUiAutomation() }) {
            LaunchedEffect(paths, saveRefresh) {
                val listed = paths
                savedGames = withContext(Dispatchers.Default) {
                    listed.filter { RomPicker.savePathFor(it).toPath().length > 0 }.toSet()
                }
            }
            DisposableEffect(Unit) { onDispose { saveImports.close(); patches.close(); setup?.close() } }
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

            AppTheme { CompactTextOnSmallWindows {
                CompositionLocalProvider(LocalCompanionBack provides back) {
                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        SideEffect { screen = DpSize(maxWidth, maxHeight); scale = density }
                        val flow = setup
                        if (flow == null) Library() else Setup(flow)
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

    private fun refreshGames() {
        paths = PokeDaisyHost.games()
        saveRefresh++
    }

    private fun key(path: String) = path.substringAfterLast('/')
    private fun label(path: String) = prefs.displayName(key(path), iosRomTitle(path))
    private fun category(path: String): RomCategory = when (path.substringAfterLast('.').lowercase()) {
        "gb" -> RomCategory.GB; "gbc" -> RomCategory.GBC; else -> RomCategory.GBA
    }
    private fun entries(): List<LibraryGame> {
        val games = paths.map { path ->
            val id = key(path)
            LibraryGame(path, label(path), "roms/$id", playing = prefs.last == id,
                isNew = prefs.isNew(id),
                hasSave = path in savedGames, favorite = id in prefs.favorites)
        }.sortedBy { it.label.lowercase() }
        return prefs.ordered(games) { key(it.id) }
    }

    @Composable
    private fun Library() {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(1f)
        val all = entries()
        val games = all.filter { com.pokedaisy.app.companion.matchesLibrarySearch(library.query, listOf(it.label, key(it.id))) }
        val projection = com.pokedaisy.app.companion.LibraryProjection(prefs, games, { key(it.id) },
            { category(it.id) }, available = all)
        val views = projection.shown
        val view = projection.open(library.view)
        val shown = projection.games(view)
        val tools = listOf(
            LibraryTool("PATCH", "PATCH A ROM", "Turn a .bps patch into a game", NewFeatures.badge(NewFeatures.ROM_PATCH), ::pickPatch),
            LibraryTool("load_save", "LOAD SAVE", "The game starts from this save next time, not from where you left off.", null,
                open = { if (paths.isEmpty()) library.selectSection(LibrarySection.GAMES) else choosingSaveGame = true }),
        )
        val state = LibraryPresentation(shown, library.grid, library.section, views, view,
            projection.counts, games.isNotEmpty(), library.query,
            tr("Add your own GBA or Game Boy ROMs from Files. Copies are kept in PokeDaisy's folder."), tools)
        val actions = library.actions(add = { choosingAdd = true }, refresh = ::refreshGames)
        LibraryScreen(state, actions, m, small, key = { it.id }, focus = focus,
            settings = {
                var account by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    if (account) {
                        CompanionBackHandler { account = false }
                        Column {
                            OptionTitleWindow(tr("RetroAchievements"), m, onBack = { account = false })
                            Spacer(Modifier.height(m.u * 4))
                            RaAccountScreen(IosAchievements.session, iosPreferences, m, small)
                        }
                    } else key(library.settingsPage) {
                        CompanionSettingsScreen(appSettings, application = true, initialPage = library.settingsPage,
                            applicationFooter = { AppAboutFooter(NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "", m) { url ->
                                NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
                            } },
                            homeExtras = listOf(
                                SettingRow("RUN SETUP", null, opensPage = true,
                                    labelBadge = NewFeatures.badge(NewFeatures.IOS_SETUP_BUTTONS)) { setup?.close(); setup = newSetup(fromSettings = true) },
                                SettingRow("RetroAchievements", null, opensPage = true) { account = true },
                            ))
                    }
                }
            }) { game, up ->
            var menu by remember(game.id) { mutableStateOf(false) }
            val openMenu = libraryLongPress { menu = true }
            val cover: @Composable (Modifier) -> Unit = { LibraryCover(null, category(game.id), m, it) }
            val items = listOf(
                LibraryMenuItem(if (game.favorite) "UNFAVORITE" else "FAVORITE",
                    if (game.favorite) LibraryIcons.StarBorder else Icons.Filled.Star,
                    pixel = if (game.favorite) PixelIcons.starOutline else PixelIcons.star) { prefs.toggleFavorite(key(game.id)) },
                LibraryMenuItem("INFO", Icons.Filled.Info, opens = true) { infoPath = game.id },
                LibraryMenuItem("RENAME", Icons.Filled.Edit, opens = true) { renaming = game.id },
                LibraryMenuItem("LOAD SAVE", LibraryIcons.FileOpen, opens = true) { pickSave(game.id) })
            val onPlay = {
                playGame(game.id)
            }
            if (prefs.grid) LibraryRomTile(game, m, small, upTo = up, onClick = onPlay,
                onLongClick = openMenu, cover = cover, menu = { LibraryGameMenu(menu, { menu = false }, items, m, screen) })
            else LibraryRomRow(game, m, small, upTo = up, onClick = onPlay,
                onLongClick = openMenu, cover = cover, menu = {
                    Box { LibraryMenuDots(m) { menu = true }; LibraryGameMenu(menu, { menu = false }, items, m, screen) }
                })
        }
    }

    private fun newSetup(fromSettings: Boolean = false): SetupFlow<Int> =
        SetupFlow(listOf(0, 1, 2), fromSettings, entered = { appSettings.keyCapture.cancel() }, exited = { reason ->
            appSettings.keyCapture.cancel()
            if (reason == SetupFlow.Exit.COMPLETE) iosPreferences.setupDone = true
            setup = null
        })

    @Composable
    private fun Setup(flow: SetupFlow<Int>) {
        val page = flow.visit
        val step = page.step
        var bindingsRevision by remember(flow) { mutableIntStateOf(0) }
        DisposableEffect(flow) { onDispose { appSettings.keyCapture.cancel() } }
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(1f)
        SetupFrame(flow.index, flow.steps.size, flow.canGoBack, busy = flow.busy, last = flow.last,
            skipping = step == 0 && paths.isEmpty(), m,
            onBack = { flow.back(page) }, onNext = { flow.next(page) }) {
            if (step == 0) SetupRomsPage(SetupRomsModel(if (paths.isEmpty()) emptyList() else listOf("PokeDaisy/roms"),
                paths.map(::label), linked = false), m, small, onChoose = ::addGames)
            else if (step == 1) SetupSavesPage(SetupSavesModel(null), m, small, onChoose = null, onUse = {})
            else {
                val bindings = remember(bindingsRevision) { appSettings.gbaControlBindings() }
                SetupButtonsPage(bindings, appSettings.keyCapture, m, small,
                    request = { appSettings.gameButtonRequest(it) { bindingsRevision++ } },
                    group = appSettings.buttonGroup, usesSecondScreen = appSettings.usesSecondScreen)
            }
        }
    }

    @Composable
    private fun Dialogs() {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(1f)
        val scope = rememberCoroutineScope()
        LibraryNavigationDialogs(library, m, small)
        if (choosingAdd) OptionSelector(tr("ADD GAMES"), AddChoice.entries, AddChoice.ROM, { tr(it.label) }, m,
            onPick = {
                choosingAdd = false
                when (it) {
                    AddChoice.ROM -> addGames()
                    AddChoice.PATCH -> pickPatch()
                }
            },
            onDismiss = { choosingAdd = false },
            badge = { if (it == AddChoice.PATCH) NewFeatures.badge(NewFeatures.ROM_PATCH) else null })
        infoPath?.let { game ->
            var info by remember(game) { mutableStateOf<GameInfo?>(null) }
            val title = label(game)
            LaunchedEffect(game) {
                val recent = prefs.played.entries.sortedByDescending { it.value }.indexOfFirst { it.key == key(game) } + 1
                info = withContext(Dispatchers.Default) { readIosGameInfo(game, title, recent) }
            }
            val current = info
            if (current == null) {
                OptionConfirm(tr("INFO"), "…", tr("CLOSE"), m,
                    onConfirm = { infoPath = null }, onDismiss = { infoPath = null }, cancelLabel = null)
            } else {
                GameInfoDialog(current, m, small, { infoPath = null }, onOpenUrl = { url ->
                    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it) }
                })
            }
        }
        renaming?.let { game ->
            LibraryRenameDialog(label(game), m, small, onDismiss = { renaming = null }) { name ->
                prefs.rename(key(game), name, iosRomTitle(game))
                renaming = null
            }
        }
        PatchWindow(patches, m, small, onPlay = { playGame(it.toString()) }, describePath = ::patchDisplayPath)
        if (choosingSaveGame && paths.isNotEmpty()) OptionSelector(tr("LOAD SAVE"), paths, paths.first(), ::label, m,
            onPick = { choosingSaveGame = false; pickSave(it) }, onDismiss = { choosingSaveGame = false })
        LaunchedEffect(saveImports.importing) {
            val picked = saveImports.importing ?: return@LaunchedEffect
            val result = withContext(NonCancellable + Dispatchers.Default) {
                runCatching { PokeDaisyHost.loadSave(picked.target, picked.bytes) }
                    .getOrElse { tr("Can't write the save") }
            }
            if (saveImports.finished(picked)) {
                notice = tr("LOAD SAVE") to (result ?: tr("Save loaded"))
                refreshGames()
            }
        }
        saveImports.pending?.let { picked ->
            val currentExists = remember(picked) { RomPicker.savePathFor(picked.target).toPath().isFile }
            val backup = "saves/${iosRomTitle(picked.target)}.backup-*.sav".takeIf { currentExists }
            val size = GameInfoDetails.sizeLabel(picked.bytes.size.toLong())
            LibraryLoadSaveDialog(label(picked.target), "${picked.fileName} ($size)", backup, m,
                onConfirm = { saveImports.confirm(picked) }, onDismiss = { saveImports.dismiss(picked) })
        }
        notice?.let { (title, message) ->
            OptionConfirm(title, message, tr("OK"), m, onConfirm = { notice = null }, onDismiss = { notice = null }, cancelLabel = null)
        }
    }

    private fun addGames() {
        val known = paths.map(::key).toSet()
        PokeDaisyHost.addGames(host) { added ->
            prefs.recordAdded(added.map(::key).filter { it !in known })
            if (added.isNotEmpty()) showImportedGames() else refreshGames()
        }
    }

    private fun showImportedGames() {
        refreshGames()
        library.search("")
        prefs.setView(LibraryView.ALL)
        if (setup == null) library.selectSection(LibrarySection.GAMES)
    }

    private fun playGame(path: String) {
        if (saveImports.importing != null) return
        prefs.recordPlay(key(path))
        play(path)
    }

    private fun patchDisplayPath(path: Path): String =
        if (path.name == "roms") "PokeDaisy/roms" else "PokeDaisy/roms/${path.name}"

    private fun pickPatch() {
        RomPicker.pickData(host) { picked ->
            if (picked != null) {
                val path = picked.toPath()
                patches.start(path.name) { FileSystem.SYSTEM.source(path) }
            }
        }
    }

    private fun pickSave(rom: String) {
        val pick = saveImports.begin(rom) ?: return
        RomPicker.pickSave(host) { picked ->
            if (picked == null) saveImports.cancel(pick)
            else {
                val bytes = runCatching { GameSaves.readPicked(FileSystem.SYSTEM.source(picked.toPath())) }.getOrNull()
                finishSaveRead(pick, bytes, picked.substringAfterLast('/'))
            }
        }
    }

    private fun finishSaveRead(pick: SaveImportFlow.Request<String>, bytes: ByteArray?, name: String) {
        val problem = saveImports.ready(pick, bytes, name) ?: return
        val message = when (problem.failure) {
            SaveImportFlow.Failure.UNREADABLE -> tr("Couldn't read {0}", name)
            SaveImportFlow.Failure.TOO_LARGE -> tr("That isn't a game save")
        }
        notice = tr("LOAD SAVE") to message
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
