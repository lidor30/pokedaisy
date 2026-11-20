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
import com.pokedaisy.app.companion.coop.CoopRelay
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.CoopSettingsPages
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
import com.pokedaisy.app.companion.data.save.MonRef
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
    private var restorePath by mutableStateOf<String?>(null)

    private data class LinkRequest(val initial: String? = null)
    private var link by mutableStateOf<LinkRequest?>(null)
    private var choosingEditSave by mutableStateOf(false)
    private var collection by mutableStateOf<CollectionState?>(null)
    private var collectionPick: MonRef? = null
    private var tradeRequest by mutableStateOf<IosTradeRequest?>(null)
    private var editingSave by mutableStateOf<String?>(null)
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
    private enum class CoopPage(val title: String) { HOME("CO-OP"), PAIR("PAIR"), PARTNERS("PARTNERS"), RELAY("RELAY") }
    private var coopPage by mutableStateOf<CoopPage?>(null)
    private var coopRevision by mutableIntStateOf(0)
    private val coopPages by lazy {
        CoopSettingsPages(
            session = { IosCoopHost.session },
            host = IosCoopHost.pairingHost({ paths }, ::label, { host }),
            changed = { coopRevision++ },
        )
    }
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
            val coopInvite by IosCoopHost.invite.collectAsState()
            val availableForInvite = setup == null && link == null && tradeRequest == null &&
                editingSave == null && collection == null
            LaunchedEffect(coopInvite, availableForInvite) {
                val ticket = coopInvite
                if (ticket != null && availableForInvite) {
                    library.selectSection(LibrarySection.SETTINGS)
                    openCoopPair(ticket)
                    IosCoopHost.consumeInvite(ticket)
                }
            }
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
                        val edit = editingSave
                        val flow = setup
                        val collectionScreen = collection
                        val trade = tradeRequest
                        val linkRequest = link
                        if (linkRequest != null) {
                            IosLinkPlay(paths.filter { category(it) == RomCategory.GBA }, linkRequest.initial, ::label,
                                onClose = { link = null }, onSaves = ::refreshGames)
                        } else if (trade != null) {
                            IosSaveTrade(paths, trade, ::label, onClose = { tradeRequest = null }, onSaved = ::refreshGames)
                        } else if (edit != null) {
                            IosSaveEditor(edit, label(edit), paths, ::label,
                                onClose = { editingSave = null; collectionPick = null },
                                onSaved = ::refreshGames, onTrade = { tradeRequest = it }, initialPick = collectionPick)
                        } else if (collectionScreen != null) {
                            IosSaveCollection(paths, ::label, collectionScreen,
                                onClose = { collection = null; collectionPick = null },
                                onOpen = { path, at -> collectionPick = at; editingSave = path },
                                onTrade = { path, at -> tradeRequest = IosTradeRequest(first = path, firstPick = at) })
                        } else {
                            if (flow == null) Library() else Setup(flow)
                            Dialogs()
                            touch.EditorOverlay()
                        }
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
            LibraryTool("EDIT", "SAVE EDITOR", "Change a game's Pokémon, bag and Pokédex",
                NewFeatures.badge(NewFeatures.IOS_SAVE_EDITOR), open = { choosingEditSave = true }),
            LibraryTool("TRADE", "TRADE SAVES", "Move Pokémon between your games' saves",
                NewFeatures.badge(NewFeatures.IOS_SAVE_TRADE), open = { tradeRequest = IosTradeRequest() }),
            LibraryTool("LINK", "LINK PLAY", "Play two to four GBA games with a link cable",
                null, open = { link = LinkRequest() }),
            LibraryTool("COLLECTION", "COLLECTION", "Every Pokémon in all your saves, in one list",
                NewFeatures.badge(NewFeatures.IOS_SAVE_COLLECTION), open = { collection = CollectionState() }),
        )
        val state = LibraryPresentation(shown, library.grid, library.section, views, view,
            projection.counts, games.isNotEmpty(), library.query,
            tr("Add your own GBA or Game Boy ROMs from Files. Copies are kept in PokeDaisy's folder."), tools)
        val actions = library.actions(add = { choosingAdd = true }, refresh = ::refreshGames)
        LibraryScreen(state, actions, m, small, key = { it.id }, focus = focus,
            settings = {
                var account by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    if (coopPage != null) {
                        CoopSettings(m, small)
                    } else if (account) {
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
                                SettingRow(tk("CO-OP"), null, opensPage = true,
                                    labelBadge = NewFeatures.badge(NewFeatures.IOS_COOP)) { coopPage = CoopPage.HOME },
                            ))
                    }
                }
            }) { game, up ->
            var menu by remember(game.id) { mutableStateOf(false) }
            val openMenu = libraryLongPress { menu = true }
            val cover: @Composable (Modifier) -> Unit = { LibraryCover(null, category(game.id), m, it) }
            val items = listOfNotNull(
                LibraryMenuItem(if (game.favorite) "UNFAVORITE" else "FAVORITE",
                    if (game.favorite) LibraryIcons.StarBorder else Icons.Filled.Star,
                    pixel = if (game.favorite) PixelIcons.starOutline else PixelIcons.star) { prefs.toggleFavorite(key(game.id)) },
                LibraryMenuItem("INFO", Icons.Filled.Info, opens = true) { infoPath = game.id },
                LibraryMenuItem("RENAME", Icons.Filled.Edit, opens = true) { renaming = game.id },
                LibraryMenuItem("LOAD SAVE", LibraryIcons.FileOpen, opens = true) { pickSave(game.id) },
                LibraryMenuItem("RESTORE BACKUP", LibraryIcons.History, opens = true) { restorePath = game.id },
                if (category(game.id) == RomCategory.GBA)
                    LibraryMenuItem("LINK PLAY", LibraryIcons.Link, opens = true) { link = LinkRequest(game.id) }
                else null,
                if (category(game.id) == RomCategory.GBA)
                    LibraryMenuItem("SAVE EDITOR", LibraryIcons.Inventory2, opens = true) { editingSave = game.id }
                else null)
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

    private fun openCoopPair(ticket: String?) {
        if (coopPage == CoopPage.PAIR) coopPages.leave()
        coopPages.open(ticket)
        coopPage = CoopPage.PAIR
    }

    @Composable
    private fun CoopSettings(m: GbaTextMetrics, small: GbaTextMetrics) {
        val page = coopPage ?: return
        fun back() {
            if (page == CoopPage.PAIR && coopPages.picking) {
                coopPages.leave()
                return
            }
            if (page == CoopPage.PAIR) coopPages.leave()
            coopPage = if (page == CoopPage.HOME) null else CoopPage.HOME
        }
        CompanionBackHandler { back() }
        DisposableEffect(Unit) {
            onDispose {
                coopPages.leave()
                coopPage = null
            }
        }
        Column(Modifier.fillMaxSize()) {
            OptionTitleWindow(tk(page.title), m, onBack = { back() })
            Spacer(Modifier.height(m.u * 4))
            when (page) {
                CoopPage.HOME -> {
                    LaunchedEffect(coopRevision) { coopPages.loadPairs() }
                    val rows = listOf(
                        SettingRow(tk("PAIR"), null, opensPage = true) { openCoopPair(null) },
                        SettingRow(tk("PARTNERS"), coopPages.pairs.size.takeIf { it > 0 }?.toString() ?: tk("NONE"),
                            opensPage = true) { coopPage = CoopPage.PARTNERS },
                        SettingRow(tk("RELAY"), CoopRelay.rowValue(iosPreferences.coopRelayUrl, IosCoopHost.defaultRelay),
                            opensPage = true) { coopPage = CoopPage.RELAY },
                        SettingRow(tk("RELAY ONLY"), tk(if (IosCoopHost.relayOnly) "ON" else "OFF")) {
                            if (IosCoopHost.session.changeRelay { iosPreferences.coopRelayOnly = !iosPreferences.coopRelayOnly }) {
                                coopRevision++
                            }
                        },
                    )
                    OptionListWindow(m, Modifier.fillMaxWidth()) {
                        GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() })
                    }
                }
                CoopPage.PAIR -> coopPages.PairPage(m, small) { coopPage = CoopPage.HOME }
                CoopPage.PARTNERS -> {
                    coopPages.PartnersPage(m, coopRevision)
                    coopPages.UnpairConfirm(m)
                }
                CoopPage.RELAY -> coopPages.RelayPage(m, small)
            }
        }
    }

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
        restorePath?.let { game ->
            var files by remember(game) { mutableStateOf<List<Pair<Path, String>>?>(null) }
            LaunchedEffect(game) {
                files = withContext(Dispatchers.Default) {
                    val save = RomPicker.savePathFor(game).toPath()
                    runCatching {
                        iosSaveBackups(save).map { backup ->
                            val date = FoundationDates.shortDateTime(backup.lastModified)
                            backup to "$date  ${GameInfoDetails.sizeLabel(backup.length)}"
                        }
                    }.getOrDefault(emptyList())
                }
            }
            val backups = files
            if (backups.isNullOrEmpty()) {
                OptionConfirm(tr("RESTORE BACKUP"),
                    if (backups == null) "…" else tr("No backups of this game's save yet"),
                    tr("CLOSE"), m, onConfirm = { restorePath = null }, onDismiss = { restorePath = null }, cancelLabel = null)
            } else {
                LibraryRestoreBackupDialog(backups, { it.second }, m,
                    onPick = { (path, _) ->
                        val pick = saveImports.begin(game) ?: return@LibraryRestoreBackupDialog
                        restorePath = null
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) {
                                runCatching { GameSaves.readPicked(FileSystem.SYSTEM.source(path)) }.getOrNull()
                            }
                            finishSaveRead(pick, bytes, path.name)
                        }
                    },
                    onDismiss = { restorePath = null },
                )
            }
        }
        if (choosingEditSave) {
            IosSaveEditorPicker(paths, ::label,
                onPick = { choosingEditSave = false; editingSave = it },
                onClose = { choosingEditSave = false })
        }
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
