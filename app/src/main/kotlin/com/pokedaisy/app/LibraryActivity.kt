package com.pokedaisy.app

import com.pokedaisy.app.companion.ui.produceKeyedState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image as CoverImage
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.LOGO_PALETTE
import com.pokedaisy.app.companion.ui.LOGO_ROWS
import com.pokedaisy.app.companion.ui.PixelArt
import com.pokedaisy.app.companion.ui.AppBackdrop
import com.pokedaisy.app.companion.ui.BackdropText
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionBadge
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionConfirm
import com.pokedaisy.app.companion.ui.OptionLine
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.OptionTextField
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.drawLayeredFrame
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import com.pokedaisy.app.companion.ui.theme.windowOn
import java.io.File
import com.pokedaisy.app.companion.ui.PixelRoundedShape

/** Square ratio matching SteamGridDB's icons (not its portrait "grid" box
 * art — those looked wrong stretched/cropped into a list row, and icons are
 * both simpler to fetch and a better fit for a compact list/grid of ROMs
 * anyway) — every cover, fetched or manually picked, is normalized to this. */
private const val ICON_ASPECT = 1f

/** The BLOCKED tag ([com.pokedaisy.app.companion.data.BlockedGames]): black, white text, in both looks. */
internal val BLOCKED_BADGE = androidx.compose.ui.graphics.Color(0xFF202020)
internal val BLOCKED_BADGE_SHADOW = androidx.compose.ui.graphics.Color(0x99000000)

/**
 * Launcher screen: pick a ROM to play, or import one. ROMs live in
 * `Android/data/<pkg>/files/roms/`; a save + savestates folder is keyed per ROM
 * by CRC32, so importing/removing ROMs never disturbs progress.
 */
class LibraryActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var romsDir: File
    private lateinit var coversDir: File

    private val importRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startImport(it, play = false) }
    }

    /** PATCH A ROM: a .bps applied to a ROM the library has (internal for ui-preview). */
    internal val patcher by lazy {
        RomPatchFlow(this) { out ->
            generateAutoCoverAsync(out)
            identifyGames()
            bump()
        }
    }
    /** SETTINGS opened as the rail's / bar's tab: its own GAMES / TOOLS come back here as the place to show. */
    private val settingsTab = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        when (r.data?.getStringExtra(SettingsActivity.EXTRA_SECTION)) {
            com.pokedaisy.app.companion.ui.LibrarySection.TOOLS.name -> { toolsOpen = true; launcher.toolCursor = 0 }
            com.pokedaisy.app.companion.ui.LibrarySection.GAMES.name -> toolsOpen = false
        }
    }

    private val pickPatch = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { patcher.start(it) }
    }

    /** LOAD SAVE: the save file picked for a game, waiting on its confirm (internal for ui-preview). */
    internal class PendingSaveLoad(val rom: File, val bytes: ByteArray, val fileName: String)
    internal var saveLoad by mutableStateOf<PendingSaveLoad?>(null)
    private var saveTarget: File? = null
    /** RESTORE BACKUP: [rom]'s save backups to pick one from, newest first. */
    internal class BackupPick(val rom: File, val files: List<File>)
    internal var backupPick by mutableStateOf<BackupPick?>(null)
    private val pickSaveFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val rom = saveTarget
        saveTarget = null
        if (uri != null && rom != null) readPickedSave(rom, uri)
    }

    /** The GitHub update check / offer (internal for the ui-preview harness). */
    internal val updates by lazy { AppUpdateFlow(this) }

    /** INFO's modal while non-null (internal for the ui-preview harness). */
    internal var gameInfo by mutableStateOf<GameInfo?>(null)

    /** First-time setup over the library while non-null (internal for the ui-preview harness). */
    internal var setup by mutableStateOf<SetupState?>(null)

    /** The one-time "send crash reports?" ask (CrashReports.shouldAsk) is up. Internal for ui-preview. */
    internal var crashAsk by mutableStateOf(false)
    private val pickRomsFolder = registerForActivityResult(StorageAccess.PickFolder()) { uri ->
        pickedFolder(uri)?.let(::linkRomsFolder)
    }
    private val pickSavesFolder = registerForActivityResult(StorageAccess.PickFolder()) { uri ->
        pickedFolder(uri)?.let(::useSavesFolder)
    }

    /** SAVE FOLDER's game, while its folder picker is up. */
    private var saveFolderTarget: File? = null
    private val pickGameSaveFolder = registerForActivityResult(StorageAccess.PickFolder()) { uri ->
        val rom = saveFolderTarget ?: return@registerForActivityResult
        saveFolderTarget = null
        pickedFolder(uri)?.let { setGameSaveFolder(rom, it) }
    }

    /** A copied ROM the second screen can't read, waiting on the "add anyway?"
     * confirm - still in [importDir], not the library. Internal for the
     * ui-preview harness. */
    internal class PendingImport(val tmp: File, val name: String, val sourcePath: String?, val play: Boolean)
    internal var pendingImport by mutableStateOf<PendingImport?>(null)
    private lateinit var importDir: File

    // "Set Cover" picker — GetContent (not OpenDocument) since any image source
    // (gallery, Files, a saved screenshot of a fan cover) should work, and this
    // is a one-shot read (copied into our own cover file immediately), not a
    // persistent content:// reference that needs OpenDocument's URI permission.
    private var pendingCoverTarget: File? = null
    private val pickCoverImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val rom = pendingCoverTarget
        pendingCoverTarget = null
        if (uri != null && rom != null) setManualCover(rom, uri)
        bump()
    }

    private var revision by mutableStateOf(0)

    /** What a library card shows that costs file IO - its name (a size / date check of the ROM), whether it has a save
     * (a look in every saves folder), NEW, a cover - worked out once per game per [revision]. A lazy list or grid
     * composes a card again each time it scrolls back into view, and doing this there on the UI thread made the grid
     * stutter (a quarter of its frames late on the Thor, the ROMs on its SD card). Main thread only. */
    private class CardFacts(
        val label: String, val hasSave: Boolean, val isNew: Boolean, val hasCover: Boolean,
        /** What kind of game it is (GameTitles.category), for the cover when there is no art; null until identified. */
        val category: RomCategory?,
    )
    private val cardFacts = HashMap<String, CardFacts>()
    private var cardFactsRevision = -1

    private fun factsFor(rom: File): CardFacts {
        if (cardFactsRevision != revision) {
            cardFacts.clear()
            cardFactsRevision = revision
        }
        return cardFacts.getOrPut(rom.absolutePath) {
            CardFacts(
                label = romLabel(rom),
                hasSave = SavesLocation.saveFor(this, prefs, rom).exists(),
                isNew = RomAdded.isNew(this, rom),
                hasCover = coverFile(rom).let { it.isFile && it.length() > 0 },
                category = GameTitles.category(this, rom),
            )
        }
    }

    /** Decoded covers, by path and [revision]: decoded off the UI thread once, not each time a card comes back. */
    private val coverBitmaps = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }

    private fun bump() {
        unsupportedRoms = RomFolder.unsupported(this)
        blockedRoms = RomFolder.blocked(this)
        partialRoms = RomFolder.partial(this)
        revision++
        if (launcherOn) refreshLauncherGames()
    }

    /** ROMs the second screen can't read, per [RomFolder]'s cache: their NOT SUPPORTED tag (internal for ui-preview). */
    internal var unsupportedRoms by mutableStateOf(emptySet<String>())

    /** Games on the blocked list ([com.pokedaisy.app.companion.data.BlockedGames]): a black BLOCKED tag, over NOT SUPPORTED. */
    internal var blockedRoms by mutableStateOf(emptySet<String>())

    /** ROMs read through a PARTIAL best-effort match: their PARTIALLY SUPPORTED tag (internal for ui-preview). */
    internal var partialRoms by mutableStateOf(emptySet<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened on the bottom screen: start over on the top one, so the app uses both (the launcher's media below).
        if (Screens.reopenOnMainDisplay(this)) return
        RomFolder.useBestEffortStore(this)
        BestEffortShare.flush(this)   // a shared best-effort match that couldn't go out last time
        prefs = Prefs(this)
        // No ROM here: AUTO = the device's language.
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        // Signs the saved RetroAchievements account in (INFO shows its progress per game).
        com.pokedaisy.app.achievements.RetroAchievements.init(this)
        romsDir = File(getExternalFilesDir(null), "roms").apply { mkdirs() }
        coversDir = File(getExternalFilesDir(null), "covers").apply { mkdirs() }
        // Next to roms/ (same volume, so accepting an import is a rename).
        importDir = File(getExternalFilesDir(null), "import").apply { mkdirs() }

        // If launched via VIEW (open a .gba with the app), import + play it.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { startImport(it, play = true) }
        } else if (LaunchActivity.romUri(intent) != null) {
            // A frontend that only knows the app (its launcher intent) plus a ROM:
            // straight to the game, as if it had started LaunchActivity itself.
            startActivity(
                Intent(intent).setClass(this, LaunchActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            finish()
            return
        }

        // The device's marker can change between visits (FeatureFlags): read before setup builds its steps.
        ScreenScraperClient.enabled = FeatureFlags.screenScraper(this)
        FeatureFlags.applyBlocked(this)
        sort = LibrarySort.of(prefs.librarySort)
        quickView = LibraryView.of(prefs.libraryView)
        // The second screen (and so SWAP) is the game's media page: ScreenScraper's, off until its flag is on.
        launcher.swapped = prefs.launcherSwapped && ScreenScraperClient.enabled
        if (intent?.action != Intent.ACTION_VIEW && !prefs.setupDone && listRoms().isEmpty()) startSetup()
        // The update's news, once: ScreenScraper, to everyone not signed in (setup has its own step).
        // Only once ScreenScraper is on (FeatureFlags): until then nobody is asked, so it still comes when it ships.
        ssIntro = ScreenScraperClient.enabled && !prefs.screenScraperAsked && prefs.screenScraperAccount == null
        // EXPAND's introduction, once: to everyone not already on it (first-time setup goes first).
        expandIntro = !prefs.expandIntroSeen && prefs.gameAspect != com.pokedaisy.app.companion.GameAspect.WIDE
        // BATTLE STYLE's pick, once: to everyone (first-time setup and EXPAND's introduction go first).
        battleStyleIntro = !prefs.battleStyleAsked
        // WHAT'S NEW, once after an update to a version with notes. A fresh install has nothing to tell: its version
        // counts as seen, so the next update's notes are the first.
        val updated = runCatching {
            packageManager.getPackageInfo(packageName, 0).let { it.firstInstallTime != it.lastUpdateTime }
        }.getOrDefault(false)
        com.pokedaisy.app.companion.WhatsNew.due(BuildConfig.VERSION_NAME, prefs.whatsNewSeen, updated)?.let { whatsNew = it }
        if (!updated) prefs.whatsNewSeen = BuildConfig.VERSION_NAME

        setContent {
            AppTheme { com.pokedaisy.app.companion.ui.CompactTextOnSmallWindows {
                // The library's windows (INFO, RENAME, the pick-lists...) register their BACK here, as on the companion.
                androidx.compose.runtime.CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalCompanionBack provides libraryBack) {
                    val s = setup
                    when {
                        s != null -> SetupHost(s)
                        launcherOn -> LauncherMainWindow()
                        else -> LibraryScreen()
                    }
                }
            } }
        }

        // Once ever, from the third open on and two days after the install: may we send crash reports?
        if (savedInstanceState == null && setup == null && CrashReports.shouldAsk(this, prefs)) crashAsk = true

        // Every app open looks for a newer GitHub release. Not in debug builds: a
        // release APK can't install over a debug-signed one (Settings > VERSION still checks).
        if (savedInstanceState == null && !BuildConfig.DEBUG) updates.check()
    }

    override fun onStart() {
        super.onStart()
        syncLauncher()
    }

    override fun onStop() {
        super.onStop()
        secondScreen.dismiss()
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        setup?.capture?.cancel()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        // Brought back on the bottom screen (its icon tapped there while open): over to the top one again.
        if (Screens.reopenOnMainDisplay(this)) return
        com.pokedaisy.app.companion.ui.OptionColors.inGame = false
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        unsupportedRoms = RomFolder.unsupported(this)
        blockedRoms = RomFolder.blocked(this)
        partialRoms = RomFolder.partial(this)
        // The support window, once its play time has come (back from a game, usually); never on WHAT'S NEW's visit.
        if (!supportAsk && whatsNew == null) {
            supportAsk = com.pokedaisy.app.companion.SupportAsk.due(prefs.playMillis, prefs.supportAskAt, prefs.supportAskDone)
        }
        // A limited-time theme's dates have come (and an ended one gives way to the theme worn before it).
        com.pokedaisy.app.companion.ui.theme.loadTheme(this)
        if (seasonalTheme == null) {
            seasonalTheme = com.pokedaisy.app.companion.ui.theme.seasonalThemeToAnnounce(
                com.pokedaisy.app.companion.ui.theme.ThemeClock.today(), prefs.seasonalThemesShown, prefs.appTheme,
            )
        }
        if (prefs.setupRequested) {
            prefs.setupRequested = false
            startSetup()
            setup?.fromSettings = true
        }
        setup?.let { s ->
            // Bindings changed elsewhere meanwhile (a game's companion): show what's bound now.
            if (s.step == SetupState.Step.BUTTONS) readButtons(s)
            // Back from Android's All files access page: carry on with the folder pick it was for.
            s.hasAccess = StorageAccess.hasAllFilesAccess(this)
            val waiting = s.awaitingAccess
            s.awaitingAccess = null
            if (s.hasAccess) when (waiting) {
                SetupState.Step.ROMS -> pickRomsFolder.launch(null)
                SetupState.Step.SAVES -> pickSavesFolder.launch(null)
                else -> {}
            }
        }
        if (awaitingFolderAccess) {
            awaitingFolderAccess = false
            if (StorageAccess.hasAllFilesAccess(this)) pickRomsFolder.launch(null)
        }
        rescanRomsFolder()
        identifyGames()
        updates.onResume()
        // The manual (Settings > HELP), fetched in the background so it's there offline later.
        if (com.pokedaisy.app.companion.ui.DocsSources.override == null) DocsStore.prefetch(this)
        bump()   // refresh list / PLAYING marker after returning
        launcher.mediaChanged()   // Settings may have fetched some
        syncLauncher()
    }

    /** The library's order, SORT BY's picker, and the game RENAME / DELETE are open for (both views). */
    private var sort by mutableStateOf(LibrarySort.NAME)
    private var pickingSort by mutableStateOf(false)
    /** The grid view (else the list), the classic library's and the launcher's alike (Prefs.libraryViewMode). */
    private var gridViewState: androidx.compose.runtime.MutableState<Boolean>? = null
    private var gridView: Boolean
        get() = (gridViewState ?: mutableStateOf(prefs.libraryViewMode == 1).also { gridViewState = it }).value
        set(v) {
            (gridViewState ?: mutableStateOf(v).also { gridViewState = it }).value = v
            prefs.libraryViewMode = if (v) 1 else 0
        }

    /** The open quick view (the pills over the games, Prefs.libraryView). */
    private var quickView by mutableStateOf(LibraryView.ALL)

    /** TOOLS is open in the games' place (the rail's / bar's TOOLS). Internal for ui-preview. */
    internal var toolsOpen by mutableStateOf(false)

    /** The library's TOOLS, in the order they're shown. */
    private fun libraryTools(): List<com.pokedaisy.app.companion.ui.LibraryTool> {
        val nf = com.pokedaisy.app.companion.NewFeatures
        return listOf(
            com.pokedaisy.app.companion.ui.LibraryTool("PATCH", "PATCH A ROM", "Turn a .bps patch into a game", nf.badge(nf.ROM_PATCH)) {
                pickPatch.launch(arrayOf("*/*"))
            },
            com.pokedaisy.app.companion.ui.LibraryTool("LINK", "LINK PLAY", "Trade and battle between your games", nf.badge(nf.LINK_PLAY)) {
                startActivity(Intent(this, LinkActivity::class.java))
            },
            com.pokedaisy.app.companion.ui.LibraryTool("EDIT", "SAVE EDITOR", "Change a game's boxes, bag and money", nf.badge(nf.SAVE_TRADE)) {
                startActivity(Intent(this, SaveEditorActivity::class.java).putExtra(SaveEditorActivity.EXTRA_PICK, true))
            },
            com.pokedaisy.app.companion.ui.LibraryTool("TRADE", "TRADE SAVES", "Move Pokémon between your games' saves", nf.badge(nf.SAVE_TRADE)) {
                startActivity(Intent(this, SaveEditorActivity::class.java).putExtra(SaveEditorActivity.EXTRA_TRADE, true))
            },
        )
    }

    /** The library ([sortLibrary]'s order) split into its quick views. */
    private class QuickViews(val byView: Map<LibraryView, List<File>>) {
        /** A console's (or HACKS') pill only while the library holds one of its games; the others always (an empty one says how to fill it). */
        val shown = LibraryView.entries.filter { !it.byKind || byView[it].orEmpty().isNotEmpty() }
        val counts = byView.mapValues { it.value.size }
        val library get() = byView[LibraryView.ALL].orEmpty()
        /** [want], or ALL while it has no pill (a console with no games left). */
        fun open(want: LibraryView) = want.takeIf { it in shown } ?: LibraryView.ALL
        fun games(want: LibraryView) = byView[open(want)].orEmpty()
    }

    /** Main thread: each game's console and kind are cache reads (GameTitles), once per call. */
    private fun quickViewsOf(library: List<File>): QuickViews {
        val platforms = library.associateWith { GameTitles.platform(this, it) }
        val categories = library.associateWith { GameTitles.category(this, it) }
        return QuickViews(LibraryView.entries.associateWith { libraryViewOf(this, prefs, library, it, platforms::get, categories::get) })
    }

    private fun pickQuickView(view: LibraryView) {
        toolsOpen = false
        quickView = view
        prefs.libraryView = view.id
        refreshLauncherGames()
    }

    /** What an empty FAVORITES / RECENT says. */
    private fun emptyViewText(view: LibraryView) =
        if (view == LibraryView.FAVORITES) tr("No favorites yet. Long press a game and pick FAVORITE to keep it here.")
        else tr("No games played yet. The games you play show here, the last one first.")
    /** The open quick view's pill: where D-pad UP from the games' top row lands. */
    private val quickViewFocus = FocusRequester()

    /** The header's SEARCH (GitHub #123): what the games are filtered by, [shownRoms]; [searching] = its window is up. */
    private var searchQuery by mutableStateOf("")
    private var searching by mutableStateOf(false)

    private fun search(query: String) {
        searchQuery = query
        if (launcherOn) refreshLauncherGames()
    }

    private var renaming by mutableStateOf<File?>(null)
    private var deleting by mutableStateOf<File?>(null)

    @Composable
    private fun LibraryScreen() {
        @Suppress("UNUSED_EXPRESSION") revision
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(textScale = 1f)
        val library = remember(revision, sort, unsupportedRoms, searchQuery) { sortLibrary(this@LibraryActivity, prefs, shownRoms(), sort) }
        // Each game's console (a hack's is its base game's), once per revision: the quick views filter on it.
        val views = remember(revision, library) { quickViewsOf(library) }
        val view = views.open(quickView)
        val roms = views.games(view)

        val density = androidx.compose.ui.platform.LocalDensity.current
        val tools = remember(revision) { libraryTools() }
        val toolsNew = tools.any { it.newBadge != null }
        val pickSection: (com.pokedaisy.app.companion.ui.LibrarySection) -> Unit = { s ->
            when (s) {
                com.pokedaisy.app.companion.ui.LibrarySection.GAMES -> toolsOpen = false
                com.pokedaisy.app.companion.ui.LibrarySection.TOOLS -> toolsOpen = true
                com.pokedaisy.app.companion.ui.LibrarySection.SETTINGS -> openSettings()
            }
        }
        val section = if (toolsOpen) com.pokedaisy.app.companion.ui.LibrarySection.TOOLS else com.pokedaisy.app.companion.ui.LibrarySection.GAMES
        // BACK on TOOLS goes back to the games, not out of the app.
        BackHandler(enabled = toolsOpen) { toolsOpen = false }
        // BACK with a search on shows the whole library again.
        BackHandler(enabled = !toolsOpen && searchQuery.isNotEmpty()) { search("") }
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize().onSizeChanged { screenSize = with(density) { DpSize(it.width.toDp(), it.height.toDp()) } }) {
            // The window's own shape from the first frame (screenSize is only known after one: an upright phone showed the
            // rail for a frame before its bar).
            val wide = maxWidth >= maxHeight
            AppBackdrop(logos = true)
            // A wide screen: GAMES / TOOLS / SETTINGS down the left, the library beside it.
            Row(Modifier.fillMaxSize()) {
            if (wide) com.pokedaisy.app.companion.ui.LibraryNav(
                section, vertical = true, m, small, toolsBadge = toolsNew && !toolsOpen, top = SCREEN_MARGIN, onPick = pickSection,
            )
            // The lists scroll to the screen's edges (their margin is padding inside them), so a card
            // slides off the screen rather than being cut at an inner box's edge.
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                OptionTitleWindow(if (narrowHeader && !toolsOpen) "" else "POKéDAISY", m, Modifier.padding(start = SCREEN_MARGIN, end = SCREEN_MARGIN, top = SCREEN_MARGIN), leading = {
                    PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight))
                }) {
                    // TOOLS has no games to sort, show, add or refresh: HELP alone.
                    if (!toolsOpen) {
                    // The header's first button: where UP from the top of the games lands.
                    HeaderIcon(Icons.AutoMirrored.Filled.Sort, tr("Sort"), m, Modifier.focusRequester(headerFirst)) { pickingSort = true }
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        headerFirstAttached = true
                        onDispose { headerFirstAttached = false }
                    }
                    HeaderIcon(Icons.Filled.Search, tr("SEARCH"), m) { searching = true }
                    HeaderIcon(if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, tr("Toggle view"), m) {
                        gridView = !gridView
                    }
                    HeaderIcon(Icons.Filled.Add, tr("Add games"), m) { addMenu = true }
                    HeaderIcon(Icons.Filled.Refresh, tr("Refresh"), m) { refreshLibrary() }
                    }
                    // The manual (Settings > HELP), on its own.
                    HeaderIcon(Icons.AutoMirrored.Filled.HelpOutline, tr("Help"), m) {
                        startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCREEN, SettingsActivity.SCREEN_HELP))
                    }
                    // SETTINGS is the rail's / bar's.
                }
                Spacer(Modifier.height(m.u * (if (library.isNotEmpty() || toolsOpen) 8 else 4)))
                SearchStrip(m)

                if (library.isNotEmpty() && !toolsOpen) {
                    com.pokedaisy.app.companion.ui.QuickViewPills(
                        views.shown, view, views.counts, m, small,
                        onPick = ::pickQuickView,
                        contentPadding = PaddingValues(horizontal = SCREEN_MARGIN),
                        focusRequester = quickViewFocus,
                    )
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        quickViewFocusAttached = true
                        onDispose { quickViewFocusAttached = false }
                    }
                    Spacer(Modifier.height(m.u * 8))
                }

                if (toolsOpen) {
                    com.pokedaisy.app.companion.ui.ToolsPage(
                        tools, m, small,
                        contentPadding = PaddingValues(start = SCREEN_MARGIN, end = SCREEN_MARGIN, bottom = SCREEN_MARGIN),
                        modifier = Modifier.weight(1f),
                    )
                } else if (library.isNotEmpty() && roms.isEmpty()) {
                    OptionListWindow(m, Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN)) {
                        GbaText(
                            emptyViewText(view),
                            OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(m.u * 4),
                        )
                    }
                } else if (roms.isEmpty()) {
                    OptionListWindow(m, Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN)) {
                        GbaText(
                            if (searchQuery.isNotBlank()) tr("No games match your search.") else tr(
                                "No ROMs yet. Tap + to add a ROM file (a .gba, .zip or .7z) or your ROMs folders, or drop files into {0}",
                                "Android/data/$packageName/files/roms/",
                            ),
                            OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(m.u * 4),
                        )
                    }
                } else if (gridView) {
                    // A list of its own per view: another view starts at its top, not at the last one's first game.
                    androidx.compose.runtime.key(view) {
                        RomGrid(
                            roms, m, small,
                            modifier = Modifier.weight(1f),
                            onRename = { renaming = it },
                            onDelete = { deleting = it },
                        )
                    }
                } else {
                    androidx.compose.runtime.key(view) {
                        RomList(
                            roms, m, small,
                            modifier = Modifier.weight(1f),
                            onRename = { renaming = it },
                            onDelete = { deleting = it },
                        )
                    }
                }
                // A tall screen: GAMES / TOOLS / SETTINGS along the bottom.
                if (!wide) com.pokedaisy.app.companion.ui.LibraryNav(
                    section, vertical = false, m, small, toolsBadge = toolsNew && !toolsOpen, onPick = pickSection,
                )
            }
            }

            LibraryDialogs(m, small)
            GameLoadingCover(m)
        }
    }

    /**
     * While a game is got ready to start ([preparing]: a big DS cart's checksum, an archive unpacked - White 2 took ~6 s):
     * the library dimmed, the app's rocking Poké Ball and the game's name over it, and every tap swallowed (keys too, in
     * [dispatchKeyEvent]). It replaced a toast that let the library go on being used meanwhile (the user's call, 2026-11-03).
     */
    @Composable
    private fun GameLoadingCover(m: GbaTextMetrics) {
        val rom = preparing ?: return
        // Fades in, so a game that's ready at once never flashes it.
        val shown = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(rom) { shown.animateTo(1f, androidx.compose.animation.core.tween(180)) }
        val noTaps = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(interactionSource = noTaps, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The loader draws at three GBA pixels a ball pixel: its ball, shadow and dots are ~66 tall.
                Box(Modifier.size(m.u * 60, m.u * 72)) { com.pokedaisy.app.companion.ui.DsLoader() }
                Spacer(Modifier.height(m.u * 14))
                GbaText(tr("Getting {0} ready…", romLabel(rom)), Color.White, Color.Black.copy(alpha = 0.5f), m)
            }
        }
    }

    /** The header's +: one ROM file, a ROMs folder (several can be linked), or the folders linked so far. */
    private enum class AddChoice(val label: String) {
        FILE(tk("ADD A ROM FILE")), FOLDER(tk("ADD A ROMS FOLDER")), MANAGE(tk("MANAGE ROMS FOLDERS")), PATCH(tk("PATCH A ROM")),
    }

    /** The + menu is up (internal for ui-preview). */
    internal var addMenu by mutableStateOf(false)

    private fun pickAdd(choice: AddChoice) {
        addMenu = false
        when (choice) {
            AddChoice.FILE -> importRom.launch(arrayOf("*/*"))
            AddChoice.FOLDER -> chooseRomsFolder()
            AddChoice.MANAGE -> startActivity(
                Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCREEN, SettingsActivity.SCREEN_ROMS_FOLDERS),
            )
            AddChoice.PATCH -> pickPatch.launch(arrayOf("*/*"))
        }
    }

    /** ADD A ROMS FOLDER: folders are opened by raw path, so All files access comes first (onResume picks up from there). */
    private fun chooseRomsFolder() {
        if (!StorageAccess.hasAllFilesAccess(this)) {
            awaitingFolderAccess = true
            StorageAccess.requestAllFilesAccess(this)
            return
        }
        pickRomsFolder.launch(null)
    }

    /** ADD A ROMS FOLDER is waiting on Android's All files access page. */
    private var awaitingFolderAccess = false

    /** Every window that opens over the library (the classic view or the launcher's main screen). */
    @Composable
    private fun LibraryDialogs(m: GbaTextMetrics, small: GbaTextMetrics) {
        Box(Modifier.fillMaxSize()) {
            if (addMenu) {
                OptionSelector(
                    tr("ADD GAMES"), AddChoice.entries.toList(), AddChoice.FILE, { tr(it.label) }, m,
                    onPick = ::pickAdd,
                    onDismiss = { addMenu = false },
                    badge = { if (it == AddChoice.PATCH) com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.ROM_PATCH) else null },
                )
            }
            if (searching) SearchWindow(m, small)
            if (pickingSort) {
                OptionSelector(
                    tr("SORT BY"), LibrarySort.entries.toList(), sort, { tr(it.label) }, m,
                    onPick = { sort = it; prefs.librarySort = it.id; pickingSort = false; refreshLauncherGames() },
                    onDismiss = { pickingSort = false },
                )
            }

            renaming?.let { rom ->
                RenameDialog(
                    current = romLabel(rom),
                    m = m, small = small,
                    onDismiss = { renaming = null },
                    onConfirm = { name ->
                        prefs.setRomDisplayName(rom, name.takeIf { it != GameTitles.defaultLabel(this@LibraryActivity, rom) })
                        renaming = null
                        bump()
                    },
                )
            }

            coverPickerFor?.let { rom ->
                CoverPicker(
                    rom = rom,
                    displayName = romLabel(rom),
                    source = coverSourceOverride ?: LibraryCoverSource(
                        prefs.steamGridDbApiKey, prefs.raWebApiKey,
                        GameMedia.root(this@LibraryActivity).takeIf { ScreenScraperClient.enabled },
                    )
                        .takeIf { prefs.steamGridDbApiKey != null || prefs.raWebApiKey != null || prefs.screenScraperAccount != null },
                    saving = coverSaving,
                    error = coverError,
                    m = m, small = small,
                    onPick = { icon -> saveSteamGridCover(rom, icon) },
                    onDismiss = { if (!coverSaving) coverPickerFor = null },
                )
            }

            pendingImport?.let { p ->
                OptionConfirm(
                    title = tr("NOT SUPPORTED"),
                    message = tr(
                        "{0} isn't supported by the second screen: the game plays, but the companion can't show its party, map, items or battles. Add it anyway?",
                        p.name.substringBeforeLast('.'),
                    ),
                    confirmLabel = if (p.play) tr("PLAY") else tr("ADD"),
                    m = m,
                    onDismiss = { p.tmp.delete(); pendingImport = null },
                    onConfirm = { pendingImport = null; finishImport(p) },
                )
            }

            gameInfo?.let { info -> GameInfoDialog(
                    info, m, small, onDismiss = { gameInfo = null },
                    onOpenUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    onForgetBestEffort = { sha1 ->
                        com.pokedaisy.app.companion.data.BestEffortStore.forget(sha1)
                        gameInfo = null
                        rescanRomsFolder()
                    },
                    onPickGame = { gp, id ->
                        val store = com.pokedaisy.app.companion.data.BestEffortStore
                        when (id) {
                            GAME_AUTO -> store.forget(gp.sha1)
                            GAME_NONE -> store.decline(gp.sha1)
                            // Its ROM-side parts are checked when the game next runs (the companion's sampler).
                            else -> com.pokedaisy.app.companion.data.BestEffort.candidate(id)?.let { c ->
                                store.save(gp.sha1, com.pokedaisy.app.companion.data.BestEffort.Match(c, full = false, off = emptySet(), score = 0), com.pokedaisy.app.companion.data.BestEffortStore.Source.PICKED, verified = false)
                            }
                        }
                        gameInfo = null
                        rescanRomsFolder()
                    },
                ) }

            PatchWindow(
                patcher, m, small,
                onPlay = ::play,
                onAskSupport = { rom -> Thread({ val url = RomSupportRequest.url(rom); runOnUiThread { openUrl(url) } }, "pokedaisy-support-url").start() },
            )

            UpdateDialog(updates, m, small)

            // After any update offer, before the other one-time windows: this update's news.
            whatsNew?.takeIf { whatsNewShown }?.let { notes ->
                // Shown = seen, whatever the answer: only once per version.
                LaunchedEffect(notes) { prefs.whatsNewSeen = notes.version }
                com.pokedaisy.app.companion.ui.WhatsNewWindow(
                    notes, m, small,
                    onReleasePage = { openUrl("${AppUpdater.RELEASES_PAGE}/tag/v${notes.version}") },
                    onClose = { whatsNew = null },
                )
            }

            // After any update offer, never over setup (this screen isn't composed then).
            if (crashAsk && updates.release == null && !whatsNewShown && !supportShown) {
                // Shown = asked, whatever the answer (or none, if the app goes away): only once.
                LaunchedEffect(Unit) { prefs.crashReportsAsked = true }
                OptionConfirm(
                    title = tr("CRASH REPORTS?"),
                    message = tr(
                        "When PokéDaisy crashes, it can send a report so the bug gets fixed sooner: the device model, Android version and where the app crashed. Never your ROMs, saves or anything personal. You can change this any time in Settings.",
                    ),
                    confirmLabel = tr("SEND REPORTS"),
                    cancelLabel = tk("NO THANKS"),
                    m = m,
                    onDismiss = { crashAsk = false },
                    onConfirm = { crashAsk = false; CrashReports.setEnabled(prefs, true) },
                )
            }

            // The support window, alone: never with an update offer, WHAT'S NEW or setup. A site or NO THANKS ends it;
            // REMIND ME LATER (or BACK, a tap outside) asks again after 5 more hours of play.
            if (supportShown) {
                com.pokedaisy.app.companion.ui.SupportWindow(
                    m, small,
                    onSite = { site -> prefs.supportAskDone = true; supportAsk = false; openUrl(site.url) },
                    onLater = { prefs.supportAskAt = com.pokedaisy.app.companion.SupportAsk.remindAt(prefs.playMillis); supportAsk = false },
                    onNoThanks = { prefs.supportAskDone = true; supportAsk = false },
                )
            }

            // A limited-time theme's dates have come: once per window, alone like the support window, after it.
            if (seasonalShown) seasonalTheme?.let { theme ->
                val window = theme.windowOn()
                if (window == null) { seasonalTheme = null; return@let }
                // Shown = told, whatever the answer: once per window.
                LaunchedEffect(theme.id) {
                    prefs.seasonalThemesShown = prefs.seasonalThemesShown + com.pokedaisy.app.companion.ui.theme.announceKey(theme, window)
                }
                com.pokedaisy.app.companion.ui.SeasonalThemeWindow(
                    theme, window.endInclusive, m, small,
                    onTry = { com.pokedaisy.app.companion.ui.theme.pickTheme(this@LibraryActivity, theme); seasonalTheme = null },
                    onNoThanks = { seasonalTheme = null },
                )
            }

            // After the crash-reports question, one at a time.
            if (expandIntro && setup == null && !crashAsk && updates.release == null && !whatsNewShown && !supportShown && !seasonalShown) {
                com.pokedaisy.app.companion.ui.ExpandIntro(
                    m, small,
                    onTurnOn = {
                        prefs.gameAspect = com.pokedaisy.app.companion.GameAspect.WIDE
                        prefs.expandIntroSeen = true
                        expandIntro = false
                    },
                    onNotNow = { prefs.expandIntroSeen = true; expandIntro = false },
                )
            }

            // After EXPAND's, one at a time: which battle screen.
            if (battleStyleIntroShown) {
                com.pokedaisy.app.companion.ui.BattleStyleIntro(
                    m, small, com.pokedaisy.app.companion.BattleStyle.of(prefs.battleStyle),
                    onPick = { prefs.battleStyle = it.id; prefs.battleStyleAsked = true; battleStyleIntro = false },
                    onDismiss = { prefs.battleStyleAsked = true; battleStyleIntro = false },
                )
            }

            backupPick?.let { b ->
                OptionSelector(
                    tr("RESTORE BACKUP"), b.files.indices.toList(), -1, { backupLabel(b.files[it]) }, m,
                    onPick = { backupPick = null; readBackup(b.rom, b.files[it]) },
                    onDismiss = { backupPick = null },
                )
            }

            saveLoad?.let { p ->
                val name = romLabel(p.rom)
                val current = remember(p) { SavesLocation.saveFor(this@LibraryActivity, prefs, p.rom) }
                val size = p.bytes.size.toLong()
                val what = "${p.fileName} (${GameInfo.sizeLabel(size)}${GameSaves.kind(size)?.let { " · $it" } ?: ""})"
                OptionConfirm(
                    title = tr("LOAD SAVE?"),
                    message = (if (current.isFile) {
                        tr(
                            "{0}: replace its save with {1}? The current save is kept, renamed to {2}.",
                            name, what, GameSaves.backupName(current),
                        )
                    } else {
                        tr("{0} has no save yet: use {1} as its save?", name, what)
                    }) + " " + tr("The game starts from this save next time, not from where you left off.") +
                        (if (GameSaves.kind(size) == null) " " + tr("This doesn't look like a GBA save file.") else ""),
                    confirmLabel = tr("LOAD"),
                    m = m,
                    onDismiss = { saveLoad = null },
                    onConfirm = { saveLoad = null; loadSave(p) },
                )
            }

            deleting?.let { rom ->
                OptionConfirm(
                    title = tr("DELETE ROM?"),
                    message = tr(
                        "{0}: removes the .{1} file. Saves and save states are kept.",
                        romLabel(rom), rom.extension,
                    ),
                    confirmLabel = tr("DELETE"),
                    m = m,
                    onDismiss = { deleting = null },
                    onConfirm = {
                        rom.delete()
                        RomAdded.forget(this@LibraryActivity, rom)
                        prefs.favoriteRoms = prefs.favoriteRoms - rom.absolutePath
                        prefs.setRomDisplayName(rom, null)
                        prefs.setRomSourcePath(rom, null)
                        prefs.setRomCoverManual(rom, false)
                        prefs.setRomCoverSource(rom, null)
                        coverFile(rom).delete()
                        GameMedia.forget(GameMedia.root(this@LibraryActivity), rom)
                        if (prefs.lastRomPath == rom.absolutePath) prefs.lastRomPath = null
                        deleting = null
                        bump()
                    },
                )
            }

            // The update's news, after the other one-time windows: ScreenScraper's game media.
            if (ssIntroShown) {
                LaunchedEffect(Unit) { prefs.screenScraperAsked = true }
                ScreenScraperIntro(
                    ssLogin, mediaSync, m, small,
                    onSignIn = { ssLogin.signIn(prefs, { r -> runOnUiThread { r() } }) { startMediaSync() } },
                    onSignUp = { openUrl(ScreenScraperClient.SIGN_UP_PAGE) },
                    onClose = { ssIntro = false },
                )
            }
        }
    }

    @Composable
    private fun HeaderIcon(
        icon: ImageVector, label: String, m: GbaTextMetrics, modifier: Modifier = Modifier,
        /** The launcher's cursor is on it (its own, not Compose focus): the focus ring. */
        cursor: Boolean = false,
        onClick: () -> Unit,
    ) {
        // A phone held upright: tighter buttons, so all six fit beside the logo (the gear was pushed off the end).
        val narrow = narrowHeader
        Spacer(Modifier.width(m.u * (if (narrow) 2 else 4)))
        OptionButton(
            null, m, onClick = onClick, modifier = modifier.gbaFocusRing(cursor, PixelRoundedShape(m.u * 3)), icon = icon,
            contentDescription = label, sidePadding = if (narrow) 4 else 8,
        )
    }

    /** The library is narrower than [NARROW_HEADER] (a phone held upright): its header drops the name, keeps the logo. */
    private val narrowHeader get() = screenSize.width > 0.dp && screenSize.width < NARROW_HEADER

    /** One of the launcher header's buttons: drawn in [LauncherList], pressed by A there ([launcherKey]). */
    private class HeaderButton(val icon: ImageVector, val label: String, val onClick: () -> Unit)

    /** The launcher's header, left to right. */
    private fun launcherHeader(): List<HeaderButton> = listOfNotNull(
        // TOOLS has no games to sort, show, add or refresh: HELP alone.
        HeaderButton(Icons.AutoMirrored.Filled.Sort, tr("Sort")) { pickingSort = true }.takeIf { !toolsOpen },
        HeaderButton(Icons.Filled.Search, tr("SEARCH")) { searching = true }.takeIf { !toolsOpen },
        HeaderButton(if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, tr("Toggle view")) { gridView = !gridView }.takeIf { !toolsOpen },
        HeaderButton(Icons.Filled.Add, tr("Add games")) { addMenu = true }.takeIf { !toolsOpen },
        HeaderButton(Icons.Filled.Refresh, tr("Refresh")) { refreshLibrary() }.takeIf { !toolsOpen },
        HeaderButton(Icons.AutoMirrored.Filled.HelpOutline, tr("Help")) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCREEN, SettingsActivity.SCREEN_HELP))
        },
        // SETTINGS is the rail's / bar's (and Y's), as in the classic library.
    )

    /** The launcher's rail runs down the left (a wide screen) rather than along the bottom, as last laid out. */
    private var launcherRailVertical = true
    /** TOOLS' cards, as last laid out. */
    private var launcherTools: List<com.pokedaisy.app.companion.ui.LibraryTool> = emptyList()

    /** The launcher's rail / bar: a place picked (a tap, or A on it). */
    private fun launcherPick(section: com.pokedaisy.app.companion.ui.LibrarySection) {
        launcher.navCursor = -1
        when (section) {
            com.pokedaisy.app.companion.ui.LibrarySection.GAMES -> toolsOpen = false
            com.pokedaisy.app.companion.ui.LibrarySection.TOOLS -> { toolsOpen = true; launcher.toolCursor = 0 }
            com.pokedaisy.app.companion.ui.LibrarySection.SETTINGS -> openSettings()
        }
    }

    /** Onto the rail / bar, on the open place. */
    private fun launcherToRail() {
        launcher.headerCursor = -1
        launcher.viewCursor = false
        launcher.navCursor = if (toolsOpen) 1 else 0
    }

    /** The header's leftmost button (sort). */
    private val headerFirst = FocusRequester()
    /** [headerFirst] is on a button: only [LibraryScreen]'s header has it. The launcher's list draws the same cards
     * under a header of its own, and UP to an unattached requester threw ("FocusRequester is not initialized"). */
    private var headerFirstAttached = false
    private val headerUp: FocusRequester? get() = quickViewFocus.takeIf { quickViewFocusAttached } ?: headerFirst.takeIf { headerFirstAttached }
    /** [quickViewFocus] is on a pill (the classic library with games in it). */
    private var quickViewFocusAttached = false

    /** For a card in the games' top row: D-pad UP goes to the open quick view's pill (else the header's leftmost
     * button). A lazy list's top row had nowhere to go - UP did nothing. */
    private val toHeader: () -> FocusRequester? = { headerUp }
    private val noUp: () -> FocusRequester? = { null }

    @Composable
    private fun RomList(
        roms: List<File>,
        m: GbaTextMetrics,
        small: GbaTextMetrics,
        modifier: Modifier = Modifier,
        onRename: (File) -> Unit,
        onDelete: (File) -> Unit,
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = SCREEN_MARGIN, end = SCREEN_MARGIN, bottom = SCREEN_MARGIN),
            verticalArrangement = Arrangement.spacedBy(m.u * 4),
        ) {
            itemsIndexed(roms, key = { _, rom -> rom.absolutePath }) { i, rom ->
                RomRow(rom, m, small, onRename = { onRename(rom) }, onDelete = { onDelete(rom) }, upTo = if (i == 0) toHeader else noUp)
            }
        }
    }

    @Composable
    private fun RomGrid(
        roms: List<File>,
        m: GbaTextMetrics,
        small: GbaTextMetrics,
        modifier: Modifier = Modifier,
        onRename: (File) -> Unit,
        onDelete: (File) -> Unit,
    ) {
        val grid = rememberLazyGridState()
        // The top row is what the grid laid out as row 0.
        fun topRow(index: Int): () -> FocusRequester? = {
            headerUp?.takeIf { grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.row == 0 }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = GRID_TILE_MIN),
            state = grid,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = SCREEN_MARGIN, end = SCREEN_MARGIN, bottom = SCREEN_MARGIN),
            horizontalArrangement = Arrangement.spacedBy(m.u * 4),
            verticalArrangement = Arrangement.spacedBy(m.u * 4),
        ) {
            gridItemsIndexed(roms, key = { _, rom -> rom.absolutePath }) { i, rom ->
                RomTile(rom, m, small, onRename = { onRename(rom) }, onDelete = { onDelete(rom) }, upTo = topRow(i))
            }
        }
    }

    /** Shared cover-image box: the auto-captured or manually-set PNG if one
     * exists yet, else a placeholder (cover capture/import is
     * fire-and-forget, so a just-imported ROM briefly has neither). Framed
     * like the save-state screenshots. */
    @Composable
    private fun CoverThumb(rom: File, m: GbaTextMetrics, modifier: Modifier = Modifier) {
        val key = "${rom.absolutePath}#$revision"
        val hasCover = factsFor(rom).hasCover
        // From the cache at once when it's been decoded before; else decoded off the UI thread (the placeholder meanwhile).
        val bitmap by produceKeyedState(if (hasCover) coverBitmaps.get(key) else null, key, hasCover) {
            if (value == null && hasCover) {
                val file = coverFile(rom)
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                }?.also { coverBitmaps.put(key, it) }
            }
        }
        val u = m.u
        Box(
            modifier = modifier
                .aspectRatio(ICON_ASPECT)
                .clip(PixelRoundedShape(u * 3))
                .background(com.pokedaisy.app.companion.ui.OptionColors.imageWell)
                .drawWithContent {
                    drawContent()
                    val px = u.toPx()
                    drawLayeredFrame(listOf(OptionColors.frameDark to px, OptionColors.frameLight to px), radius = 3 * px)
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            if (shown != null) {
                CoverImage(
                    bitmap = shown,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                )
            } else {
                // No art: the game's kind (GB / GBC / GBA / NDS / HACK) as a little pixel picture, once the background
                // title pass has looked at the file (GameTitles.identify); the plain icon until then.
                val category = factsFor(rom).category
                if (category != null) {
                    com.pokedaisy.app.companion.ui.CategoryArt(category, Modifier.fillMaxSize())
                } else {
                    Icon(
                        Icons.Filled.VideogameAsset, contentDescription = null,
                        tint = OptionColors.muted, modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }

    private val MENU_ITEM_MIN_HEIGHT = 48.dp
    /** The library's margin to the screen's edges. */
    private val SCREEN_MARGIN = 16.dp

    /** About a menu column's width (Material's menus are 112-280 dp). */
    private val MENU_COLUMN_WIDTH = 280.dp
    /** Held upright, the narrowest screen that still shows a label in each of two columns. */
    private val MENU_UPRIGHT_MIN_WIDTH = 320.dp
    private val NARROW_HEADER = 600.dp

    /** The library screen's size, for a game's menu to pick one or two columns. */
    private var screenSize by mutableStateOf(DpSize.Zero)

    /** One entry of a game's menu: the classic view's dropdown and the launcher's B. */
    /** [opens]: it opens a window, a picker or another screen (the launcher's menu comes back when that closes). */
    /** [pixel]: a [com.pokedaisy.app.companion.ui.PixelIcons] bitmap drawn instead of [icon] (the favorite stars). */
    private class RomMenuItem(
        val label: String, val icon: ImageVector, val danger: Boolean = false, val opens: Boolean = false,
        val pixel: List<String>? = null, val onClick: () -> Unit,
    )

    @Composable
    private fun RomOptionsMenu(
        expanded: Boolean,
        onDismiss: () -> Unit,
        rom: File,
        m: GbaTextMetrics,
        onRename: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val items = romMenuItems(rom, onRename, onDelete)
        RomMenuDropdown(expanded, onDismiss, items, m)
    }

    /** What a game's menu offers, in order (DELETE last). */
    private fun romMenuItems(rom: File, onRename: () -> Unit, onDelete: () -> Unit): List<RomMenuItem> {
        return buildList {
            if (rom.absolutePath in prefs.favoriteRoms) {
                add(RomMenuItem(tr("UNFAVORITE"), Icons.Filled.StarBorder, pixel = com.pokedaisy.app.companion.ui.PixelIcons.starOutline) { setFavorite(rom, false) })
            } else {
                add(RomMenuItem(tr("FAVORITE"), Icons.Filled.Star, pixel = com.pokedaisy.app.companion.ui.PixelIcons.star) { setFavorite(rom, true) })
            }
            add(RomMenuItem(tr("INFO"), Icons.Filled.Info, opens = true) { showInfo(rom) })
            add(RomMenuItem(tr("RENAME"), Icons.Filled.Edit, opens = true, onClick = onRename))
            add(RomMenuItem(tr("REPLACE COVER"), Icons.Filled.Search, opens = true) { coverError = null; coverPickerFor = rom })
            add(RomMenuItem(tr("SET COVER"), Icons.Filled.Image, opens = true) { pendingCoverTarget = rom; pickCoverImage.launch("image/*") })
            if (prefs.romCoverManual(rom)) {
                add(RomMenuItem(tr("RESET COVER"), Icons.Filled.Refresh) { resetCover(rom) })
            }
            // Two to four GBA games on one link cable (LinkActivity): trades, battles, record mixing between them.
            if (rom.extension.lowercase() !in setOf("gb", "gbc", "nds")) {
                add(RomMenuItem(tr("LINK PLAY"), Icons.Filled.Link, opens = true) {
                    startActivity(Intent(this@LibraryActivity, LinkActivity::class.java).putExtra(LinkActivity.EXTRA_ROM, rom.absolutePath))
                })
            }
            add(RomMenuItem(tr("LOAD SAVE"), Icons.Filled.FileOpen, opens = true) { saveTarget = rom; pickSaveFile.launch(arrayOf("*/*")) })
            add(RomMenuItem(tr("RESTORE BACKUP"), Icons.Filled.History, opens = true) { showBackups(rom) })
            // The save's party and PC boxes, read (and edited) from the file; Game Boy and DS saves aren't Gen 3's.
            if (rom.extension.lowercase() !in setOf("gb", "gbc", "nds")) {
                add(RomMenuItem(tr("SAVE EDITOR"), Icons.Filled.Inventory2, opens = true) {
                    startActivity(Intent(this@LibraryActivity, SaveEditorActivity::class.java).putExtra(SaveEditorActivity.EXTRA_ROM, rom.absolutePath))
                })
            }
            // This game's saves in a folder of its own (another emulator's), or back to the usual search.
            add(RomMenuItem(tr("SAVE FOLDER"), Icons.Filled.Folder, opens = true) {
                if (!StorageAccess.hasAllFilesAccess(this@LibraryActivity)) {
                    StorageAccess.requestAllFilesAccess(this@LibraryActivity)
                    Toast.makeText(this@LibraryActivity, tr("Grant \"All files access\", then tap the button again"), Toast.LENGTH_LONG).show()
                } else {
                    saveFolderTarget = rom
                    pickGameSaveFolder.launch(null)
                }
            })
            if (prefs.romSaveDir(rom) != null) {
                add(RomMenuItem(tr("DEFAULT SAVE FOLDER"), Icons.Filled.FolderOff) { setGameSaveFolder(rom, null) })
            }
            // The game's cheats, on Settings' CHEATS page.
            add(RomMenuItem(tr("CHEATS"), Icons.Filled.Code, opens = true) {
                startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_CHEATS_ROM, rom.absolutePath))
            })
            // A shortcut straight into the game, on Android's home screen (GitHub #182).
            add(RomMenuItem(tr("ADD TO HOME"), Icons.Filled.Home) {
                if (!HomeShortcut.request(this@LibraryActivity, rom, romLabel(rom), coverFile(rom))) {
                    Toast.makeText(this@LibraryActivity, tr("Your home screen can't add shortcuts"), Toast.LENGTH_LONG).show()
                }
            })
            add(RomMenuItem(tr("HIDE"), Icons.Filled.VisibilityOff) { hideRom(rom) })
            // A linked folder's ROM is the player's own file: it can be hidden, never deleted.
            if (!RomFolder.isLinked(prefs, rom)) {
                add(RomMenuItem(tr("DELETE"), Icons.Filled.Delete, danger = true, opens = true, onClick = onDelete))
            }
        }
    }

    @Composable
    private fun RomMenuDropdown(expanded: Boolean, onDismiss: () -> Unit, items: List<RomMenuItem>, m: GbaTextMetrics) {
        @Composable
        fun MenuItem(item: RomMenuItem) {
            val fg = if (item.danger) OptionColors.value else OptionColors.label
            DropdownMenuItem(
                text = { GbaText(item.label, fg, if (item.danger) OptionColors.valueShadow else OptionColors.labelShadow, m) },
                leadingIcon = {
                    val px = item.pixel
                    if (px != null) com.pokedaisy.app.companion.ui.PixelIcon(px, fg, Modifier.size(24.dp)) else Icon(item.icon, null, tint = fg)
                },
                onClick = { onDismiss(); item.onClick() },
            )
        }
        // Two columns where they fit across the screen and one would run off it, or fill it: the list had grown past a
        // landscape screen's height, and held upright it took the whole screen (the user's call).
        val itemHeight = maxOf(MENU_ITEM_MIN_HEIGHT, m.lineHeight + 16.dp)
        // Upright the columns share the screen's width (a phone has no room for two of MENU_COLUMN_WIDTH).
        val upright = screenSize.height > screenSize.width && screenSize.width >= MENU_UPRIGHT_MIN_WIDTH
        val twoColumns = upright || (
            screenSize.width >= MENU_COLUMN_WIDTH * 2 + 32.dp && itemHeight * items.size + 16.dp > screenSize.height - 32.dp
            )
        // Square corners on the popup's own surface (its shape must be a
        // CornerBasedShape - no pixel steps possible), and a subtle 1u frame
        // just inside it rather than Material's smooth-rounded edge.
        val u = m.u
        MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(0.dp))) {
            DropdownMenu(
                expanded = expanded, onDismissRequest = onDismiss,
                modifier = Modifier
                    .background(OptionColors.titleFill)
                    .drawWithContent {
                        drawContent()
                        drawLayeredFrame(listOf(MenuBorder to u.toPx()), radius = 0f)
                    },
            ) {
                if (!twoColumns) {
                    items.forEach { MenuItem(it) }
                } else {
                    // Down the first column, then the second (DELETE last, bottom right); a 1u rule between.
                    val half = (items.size + 1) / 2
                    val rowWidth = if (upright) Modifier.width(screenSize.width - 32.dp) else Modifier
                    Row(rowWidth.height(IntrinsicSize.Min)) {
                        val column = if (upright) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max)
                        Column(column) { items.take(half).forEach { MenuItem(it) } }
                        Box(Modifier.fillMaxHeight().padding(vertical = u * 4).width(u).background(MenuBorder))
                        Column(column) { items.drop(half).forEach { MenuItem(it) } }
                    }
                }
            }
        }
    }

    /** "⋮" button + its menu, sharing one Box so the popup anchors at the icon. [menu] / [onMenu]: the
     * row's own (a long press on the row opens it too). */
    @Composable
    private fun RomMenuButton(rom: File, m: GbaTextMetrics, menu: Boolean, onMenu: (Boolean) -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
        Box {
            MenuDots(m) { onMenu(true) }
            RomOptionsMenu(menu, onDismiss = { onMenu(false) }, rom = rom, m = m, onRename = onRename, onDelete = onDelete)
        }
    }

    /** The "⋮" button alone (the launcher's opens its own menu). */
    @Composable
    private fun MenuDots(m: GbaTextMetrics, onClick: () -> Unit) {
        Box(modifier = Modifier.clip(PixelRoundedShape(m.u * 3)).clickable(onClick = onClick).padding(m.u * 3)) {
            Icon(Icons.Filled.MoreVert, contentDescription = tr("ROM options"), tint = OptionColors.label, modifier = Modifier.size(m.lineHeight))
        }
    }

    /** A long press's menu: opens it with a light tick (the device's long-press haptic). */
    @Composable
    private fun longPressMenu(open: () -> Unit): () -> Unit {
        val haptic = LocalHapticFeedback.current
        return { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open() }
    }

    /** A favorite's star, beside its title. */
    @Composable
    private fun FavoriteStar(size: androidx.compose.ui.unit.Dp) {
        // The bag's pixel star (its starred items), in the favorites' gold.
        com.pokedaisy.app.companion.ui.PixelIcon(
            com.pokedaisy.app.companion.ui.PixelIcons.star, OptionColors.favorite,
            Modifier.size(size).semantics { contentDescription = tr("Favorite") },
        )
    }

    private fun setFavorite(rom: File, on: Boolean) {
        prefs.favoriteRoms = if (on) prefs.favoriteRoms + rom.absolutePath else prefs.favoriteRoms - rom.absolutePath
        bump()
    }

    /** A card in the OPTION list-window look; the last-played ROM gets the
     * list's white "cursor" fill. D-pad focus tints it like a focused row. */
    @Composable
    @OptIn(ExperimentalFoundationApi::class)
    private fun RomCard(
        selected: Boolean,
        m: GbaTextMetrics,
        modifier: Modifier = Modifier,
        onLongClick: (() -> Unit)? = null,
        /** Where D-pad UP goes from this card, if not the card above ([toHeader] for the top row). */
        upTo: () -> FocusRequester? = noUp,
        /** The launcher's cursor is on it (the launcher moves its own, not Compose focus): ring and white fill. */
        cursor: Boolean = false,
        onClick: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hasFocus by interaction.collectIsFocusedAsState()
        val focused = hasFocus || cursor
        OptionListWindow(
            m,
            modifier
                // The list window's own corner (OptionListWindow draws its frame at a 2u radius).
                .gbaFocusRing(focused, PixelRoundedShape(m.u * 2))
                .focusProperties { upTo()?.let { up = it } }
                .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
            fill = if (selected || focused) OptionColors.rowSelected else OptionColors.listFill,
        ) { content() }
    }

    @Composable
    private fun RomRow(
        rom: File, m: GbaTextMetrics, small: GbaTextMetrics, onRename: () -> Unit, onDelete: () -> Unit, upTo: () -> FocusRequester? = noUp,
        /** In the launcher (null = the classic library). */
        launcherCard: LauncherCard? = null,
        /** The file's path under the name (the launcher shows it on the main screen only). */
        showPath: Boolean = true,
    ) {
        val isLast = rom.absolutePath == prefs.lastRomPath
        val facts = factsFor(rom)
        val isNew = facts.isNew
        val display = facts.label
        // Existing progress indicator: does a .sav/.srm already exist for this ROM?
        val hasSave = facts.hasSave
        val favorite = rom.absolutePath in prefs.favoriteRoms
        // The ⋮ menu opens from a long press on the row too, like a grid tile.
        var menu by remember { mutableStateOf(false) }
        RomCard(
            // In the launcher only its cursor gets the white fill (two white rows read as two cursors).
            if (launcherCard != null) false else isLast, m, Modifier.fillMaxWidth(),
            onLongClick = longPressMenu { launcherCard?.onMenu?.invoke() ?: run { menu = true } }, upTo = upTo,
            cursor = launcherCard?.cursor == true,
            onClick = launcherCard?.onTap ?: { play(rom) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverThumb(rom, m, modifier = Modifier.height(56.dp))
                Spacer(Modifier.width(m.u * 6))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GbaText(display, OptionColors.label, OptionColors.labelShadow, m, Modifier.weight(1f, fill = false))
                        if (favorite) {
                            Spacer(Modifier.width(m.u * 4))
                            FavoriteStar(small.lineHeight)
                        }
                        if (hasSave) {
                            Spacer(Modifier.width(m.u * 4))
                            Icon(
                                Icons.Filled.Save, contentDescription = tr("Save file exists"),
                                tint = OptionColors.label, modifier = Modifier.size(small.lineHeight),
                            )
                        }
                        if (isLast) {
                            Spacer(Modifier.width(m.u * 4))
                            GbaText(tr("PLAYING"), OptionColors.value, OptionColors.valueShadow, small)
                        }
                        if (isNew) {
                            Spacer(Modifier.width(m.u * 4))
                            OptionBadge(tk("NEW"), m, fill = OptionColors.newBadge, shadow = OptionColors.newBadgeShadow)
                        }
                        if (rom.absolutePath in blockedRoms) {
                            Spacer(Modifier.width(m.u * 4))
                            OptionBadge(tk("BLOCKED"), m, fill = BLOCKED_BADGE, shadow = BLOCKED_BADGE_SHADOW)
                        } else if (rom.absolutePath in unsupportedRoms) {
                            Spacer(Modifier.width(m.u * 4))
                            OptionBadge(tk("NOT SUPPORTED"), m)
                        } else if (rom.absolutePath in partialRoms) {
                            Spacer(Modifier.width(m.u * 4))
                            OptionBadge(tk("PARTIALLY SUPPORTED"), m, fill = OptionColors.muted)
                        }
                    }
                    if (showPath) {
                        GbaText(
                            prefs.romSourcePath(rom) ?: rom.absolutePath,
                            OptionColors.muted, OptionColors.mutedShadow, small,
                        )
                    }
                }
                if (launcherCard != null) MenuDots(m, launcherCard.onMenu)
                else RomMenuButton(rom, m, menu, { menu = it }, onRename, onDelete)
            }
        }
    }

    @Composable
    private fun RomTile(
        rom: File, m: GbaTextMetrics, small: GbaTextMetrics, onRename: () -> Unit, onDelete: () -> Unit, upTo: () -> FocusRequester? = noUp,
        /** In the launcher (null = the classic library). */
        launcherCard: LauncherCard? = null,
    ) {
        val isLast = rom.absolutePath == prefs.lastRomPath
        val facts = factsFor(rom)
        val isNew = facts.isNew
        val display = facts.label
        val hasSave = facts.hasSave
        // Just the cover and the title (+ save marker); the options menu is a long press.
        var menu by remember { mutableStateOf(false) }
        Box {
            RomCard(
                if (launcherCard != null) false else isLast, m, Modifier.fillMaxWidth(),
                onLongClick = longPressMenu { launcherCard?.onMenu?.invoke() ?: run { menu = true } },
                upTo = upTo,
                cursor = launcherCard?.cursor == true,
                onClick = launcherCard?.onTap ?: { play(rom) },
            ) {
                Column {
                    CoverThumb(rom, m, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(m.u * 3))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GbaText(display, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f))
                        if (rom.absolutePath in prefs.favoriteRoms) {
                            Spacer(Modifier.width(m.u * 2))
                            FavoriteStar(small.lineHeight)
                        }
                        if (hasSave) {
                            Spacer(Modifier.width(m.u * 2))
                            Icon(
                                Icons.Filled.Save, contentDescription = tr("Save file exists"),
                                tint = OptionColors.label, modifier = Modifier.size(small.lineHeight),
                            )
                        }
                    }
                    val support = when (rom.absolutePath) {
                        in blockedRoms -> tk("BLOCKED") to BLOCKED_BADGE
                        in unsupportedRoms -> tk("NOT SUPPORTED") to OptionColors.value
                        in partialRoms -> tk("PARTIALLY SUPPORTED") to OptionColors.muted
                        else -> null
                    }
                    if (isNew || support != null) {
                        Spacer(Modifier.height(m.u * 2))
                        Row(horizontalArrangement = Arrangement.spacedBy(m.u * 2)) {
                            if (isNew) OptionBadge(tk("NEW"), m, fill = OptionColors.newBadge, shadow = OptionColors.newBadgeShadow)
                            support?.let { (label, fill) ->
                                OptionBadge(label, m, fill = fill, shadow = if (fill == BLOCKED_BADGE) BLOCKED_BADGE_SHADOW else OptionColors.valueShadow.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
            RomOptionsMenu(menu, onDismiss = { menu = false }, rom = rom, m = m, onRename = onRename, onDelete = onDelete)
        }
    }

    /** A card in the launcher: its cursor, a tap (the cursor onto it, then play), a long press / ⋮ (the launcher's menu). */
    private class LauncherCard(val cursor: Boolean, val onTap: () -> Unit, val onMenu: () -> Unit)

    /** The header's SEARCH: the games behind it follow what's typed. A window, so the launcher (every key its own) can
     * have it too: while one is up keys go to Compose. */
    @Composable
    private fun SearchWindow(m: GbaTextMetrics, small: GbaTextMetrics) {
        val focus = remember { FocusRequester() }
        // A text field taking the focus brings the keyboard up by itself.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        OptionOverlay({ searching = false }, Modifier.widthIn(max = 520.dp)) {
            Column {
                OptionTitleWindow(tr("SEARCH"), m)
                Spacer(Modifier.height(m.u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    Column {
                        OptionTextField(
                            searchQuery, ::search, small,
                            Modifier.padding(horizontal = m.u * 8, vertical = m.u * 2).focusRequester(focus),
                            placeholder = tr("SEARCH GAMES"), leadingIcon = com.pokedaisy.app.companion.ui.PixelIcons.search,
                        )
                        OptionLine(tr("CLEAR"), null, selected = false, m, height = m.rowHeight * 1.3f) { search(""); searching = false }
                        OptionLine(tr("DONE"), null, selected = true, m, height = m.rowHeight * 1.3f) { searching = false }
                    }
                }
            }
        }
    }

    /** What the library is filtered by, over the games: a tap opens SEARCH again, CLEAR shows them all. */
    @Composable
    private fun SearchStrip(m: GbaTextMetrics) {
        if (searchQuery.isBlank() || toolsOpen) return
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN).padding(bottom = m.u * 4),
            horizontalArrangement = Arrangement.spacedBy(m.u * 4),
        ) {
            OptionButton(tr("SEARCH") + ": " + searchQuery, m, modifier = Modifier.weight(1f, fill = false), onClick = { searching = true })
            OptionButton(tr("CLEAR"), m, onClick = { search("") })
        }
    }

    @Composable
    private fun RenameDialog(
        current: String,
        m: GbaTextMetrics,
        small: GbaTextMetrics,
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit,
    ) {
        var text by remember { mutableStateOf(current) }
        OptionOverlay(onDismiss, Modifier.widthIn(max = 520.dp)) {
            Column {
                OptionTitleWindow(tr("RENAME"), m)
                Spacer(Modifier.height(m.u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    Column {
                        GbaText(
                            tr("Name shown in the list (display only - the file keeps its name)."),
                            OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 2),
                        )
                        OptionTextField(text, { text = it }, small, Modifier.padding(horizontal = m.u * 8, vertical = m.u * 2))
                        OptionLine(tr("SAVE"), null, selected = false, m, height = m.rowHeight * 1.3f) { onConfirm(text) }
                        OptionLine(tr("CANCEL"), null, selected = true, m, height = m.rowHeight * 1.3f, onClick = onDismiss)
                    }
                }
            }
        }
    }

    /** Imported ROMs plus the linked folders' supported ones (from their last scan). */
    /** The library, alphabetical. Every ROM it lists for the first time is dated ([RomAdded]): NEW
     * unless setup is running or a newly linked folder's first scan brought it in. Any thread. */
    /** [listRoms] as the library shows it: SUPPORTED ONLY leaves out what the companion can't read. */
    private fun shownRoms(): List<File> = listRoms().let { roms ->
        if (prefs.librarySupportedOnly) roms.filterNot { it.absolutePath in unsupportedRoms } else roms
    }.let { roms ->
        val query = searchQuery
        if (query.isBlank()) roms else roms.filter { matchesSearch(query, listOf(romLabel(it), it.name)) }
    }

    private fun listRoms(): List<File> = RomFolder.libraryRoms(this, prefs).also { roms ->
        RomAdded.record(this, roms, initial = setup != null || linkingFolder)
    }

    /** A folder was just linked: its first scan's ROMs are the library being set up, not new games. */
    @Volatile private var linkingFolder = false

    private fun coverFile(rom: File): File = File(coversDir, "${rom.name}.png")

    /** A launch waiting on [RomCrcs.prepare] (its game): [GameLoadingCover] dims the library over it, and nothing else
     * can be pressed or started meanwhile. Internal for ui-preview. */
    internal var preparing by mutableStateOf<File?>(null)

    private fun play(rom: File) {
        if (preparing != null) return
        // A ROM whose CRC isn't known (or an archive) is read / unpacked first, off the UI thread: the game's window did
        // it on its own and froze on a 512 MB DS cart.
        if (RomCrcs.needsPrepare(this, rom)) {
            preparing = rom
            kotlin.concurrent.thread(name = "pokedaisy-prepare") {
                RomCrcs.prepare(this, rom)
                runOnUiThread {
                    launch(rom)
                    preparing = null
                }
            }
            return
        }
        launch(rom)
    }

    private fun launch(rom: File) {
        // The game puts its own window on the second screen.
        secondScreen.dismiss()
        launcher.menu = null
        prefs.lastRomPath = rom.absolutePath
        prefs.pushRecentRom(rom.absolutePath)
        // Opened on the bottom screen, the game still goes to the top one (Screens.mainDisplayLaunch).
        val onMain = Screens.mainDisplayLaunch(this)
        val intent = Intent(this, PokeDaisyActivity::class.java).putExtra(EXTRA_ROM, rom.absolutePath)
            .putExtra(EXTRA_LAUNCHED_FROM, CrashReports.LAUNCHED_FROM_LIBRARY)
        if (onMain != null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent, onMain)
    }

    /** Blocking: fetches+caches [rom]'s cover over the network - its
     * RetroAchievements box art when a Web API key is set, else SteamGridDB's
     * icon for a known game ([CoverArtSync.fetchOne] — shared with
     * SettingsActivity's Cover Art screen). Call only from a background thread
     * — never the UI thread (network I/O), never inside a Compose recomposition
     * (the ROM's SHA1 / MD5 are whole-file reads). */
    private fun generateAutoCoverBlocking(rom: File, ss: CoverArtSync.ScreenScraperPass = CoverArtSync.ScreenScraperPass.of(prefs)): Boolean =
        CoverArtSync.fetchOne(rom, coverFile(rom), prefs, ss = ss, mediaRoot = GameMedia.root(this), label = romLabel(rom)) ==
            CoverArtSync.Status.FETCHED

    /** Fire-and-forget wrapper for a single ROM — used right after import or
     * a manual Reset Cover, both triggered from the UI thread. */
    private fun generateAutoCoverAsync(rom: File) {
        Thread({
            generateAutoCoverBlocking(rom)
            runOnUiThread { launcher.mediaChanged(); bump() }
        }, "pokedaisy-cover-fetch").apply { isDaemon = true; start() }
    }

    /** Backfills a cover for every listed ROM that doesn't have one yet
     * (skips ROMs with a manually-picked cover) — called on Refresh so ROMs
     * imported before an API key was set, or before this feature existed,
     * catch up. Runs entirely off the UI thread: hashing + fetching several
     * ROMs in a row is real work (see generateAutoCoverBlocking's doc). */
    private fun backfillCovers() {
        Thread({
            val ss = CoverArtSync.ScreenScraperPass.of(prefs)
            val any = listRoms().map { generateAutoCoverBlocking(it, ss) }.any { it }
            runOnUiThread {
                // ScreenScraper media may have landed without a new cover.
                if (prefs.screenScraperAccount != null) launcher.mediaChanged()
                if (any || prefs.screenScraperAccount != null) bump()
            }
        }, "pokedaisy-cover-backfill-scan").apply { isDaemon = true; start() }
    }

    /** REPLACE COVER's window: the ROM it's open for (internal for the ui-preview harness). */
    internal var coverPickerFor by mutableStateOf<File?>(null)
    /** Canned picker data for the ui-preview harness (no network there). */
    internal var coverSourceOverride: CoverSource? = null
    private var coverSaving by mutableStateOf(false)
    /** EXPAND's one-time introduction is up ([com.pokedaisy.app.companion.ui.ExpandIntro]). */
    private var expandIntro by mutableStateOf(false)
    /** This update's WHAT'S NEW, until it's closed (internal for the ui-preview harness). */
    internal var whatsNew by mutableStateOf<com.pokedaisy.app.companion.WhatsNew.Notes?>(null)
    /** The support window is up ([com.pokedaisy.app.companion.SupportAsk]; internal for ui-preview). */
    internal var supportAsk by mutableStateOf(false)
    private var coverError by mutableStateOf<String?>(null)

    /** Downloads the picked icon over [rom]'s cover (only swapped once it has
     * fully arrived) and marks it manual, so Refresh won't touch it and
     * RESET COVER can go back to the automatic pick. */
    private fun saveSteamGridCover(rom: File, icon: SteamGridDbClient.Icon) {
        coverSaving = true
        coverError = null
        Thread({
            // ScreenScraper's tile is a file already here (its path); the others are downloads.
            val ok = if (icon.url.startsWith("/")) {
                runCatching { File(icon.url).copyTo(coverFile(rom), overwrite = true) }.isSuccess
            } else {
                SteamGridDbClient.download(icon.url, coverFile(rom))
            }
            runOnUiThread {
                coverSaving = false
                if (ok) {
                    prefs.setRomCoverManual(rom, true)
                    coverPickerFor = null
                    bump()
                } else {
                    coverError = tr("COULDN'T DOWNLOAD THAT ONE - TRY ANOTHER")
                }
            }
        }, "pokedaisy-cover-pick").apply { isDaemon = true; start() }
    }

    /** INFO: the quick facts at once, then again with the hashes (a whole-ROM read). */
    private fun showInfo(rom: File) {
        gameInfo = GameInfo.read(this, prefs, rom, hashes = false)
        Thread({
            val full = GameInfo.read(this, prefs, rom, hashes = true)
            runOnUiThread { if (gameInfo?.title == full.title) gameInfo = full }
        }, "pokedaisy-game-info").apply { isDaemon = true; start() }
    }

    /** Reads the picked save (anything over [GameSaves.MAX_BYTES] isn't one) for its confirm. */
    private fun readPickedSave(rom: File, uri: Uri) {
        Thread({
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(1 shl 14)
                    while (out.size() <= GameSaves.MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            val name = RomUris.displayName(this, uri) ?: tr("the picked file")
            runOnUiThread {
                when {
                    bytes == null || bytes.isEmpty() -> Toast.makeText(this, tr("Couldn't read {0}", name), Toast.LENGTH_LONG).show()
                    bytes.size > GameSaves.MAX_BYTES -> Toast.makeText(this, tr("{0} is too big to be a GBA save", name), Toast.LENGTH_LONG).show()
                    else -> saveLoad = PendingSaveLoad(rom, bytes, name)
                }
            }
        }, "pokedaisy-read-save").apply { isDaemon = true; start() }
    }

    /** Every backup of [rom]'s save: the ones each start takes ([SaveBackups]) and LOAD SAVE's. */
    private fun showBackups(rom: File) {
        val save = SavesLocation.saveFor(this, prefs, rom)
        val dir = save.parentFile ?: SavesLocation.dir(this, prefs)
        val files = (SaveBackups.list(save) + RomArchive.saveNames(rom).flatMap { GameSaves.backups(dir, it) })
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.name.substringAfter(".backup-") }
        if (files.isEmpty()) {
            Toast.makeText(this, tr("No backups of this game's save yet"), Toast.LENGTH_LONG).show()
        } else {
            backupPick = BackupPick(rom, files)
        }
    }

    /** `2026-10-07 23:35  FLASH 128K` from `<game>.backup-20261007-233512.srm`. */
    private fun backupLabel(f: File): String {
        val stamp = f.name.substringAfter(".backup-").substringBefore('.')
        val date = runCatching {
            val d = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).parse(stamp)!!
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(d)
        }.getOrDefault(f.name)
        return date + (GameSaves.kind(f.length())?.let { "  $it" } ?: "")
    }

    /** A backup goes through LOAD SAVE's own confirm, so the current save is kept too. */
    private fun readBackup(rom: File, f: File) {
        Thread({
            val bytes = runCatching { f.readBytes() }.getOrNull()
            runOnUiThread {
                if (bytes == null || bytes.isEmpty()) Toast.makeText(this, tr("Couldn't read {0}", f.name), Toast.LENGTH_LONG).show()
                else saveLoad = PendingSaveLoad(rom, bytes, f.name)
            }
        }, "pokedaisy-read-backup").apply { isDaemon = true; start() }
    }

    private fun loadSave(p: PendingSaveLoad) {
        Thread({
            val result = GameSaves.load(this, prefs, p.rom, p.bytes)
            runOnUiThread {
                val msg = when (result) {
                    is GameSaves.Result.Loaded -> result.backup?.let { tr("Save loaded - the old one is {0}", it.name) } ?: tr("Save loaded")
                    is GameSaves.Result.Failed -> result.reason
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                bump()
            }
        }, "pokedaisy-load-save").apply { isDaemon = true; start() }
    }

    /** Takes [rom] out of the library, keeping the file, its name, cover and saves;
     * Settings > HIDDEN GAMES shows it again. */
    private fun hideRom(rom: File) {
        prefs.hiddenRoms = prefs.hiddenRoms + rom.absolutePath
        Toast.makeText(this, tr("Hidden - show it again in Settings > Hidden games"), Toast.LENGTH_SHORT).show()
        bump()
    }

    private fun setManualCover(rom: File, uri: Uri) {
        runCatching {
            val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
            val cropped = cropToIconAspect(src)
            val out = coverFile(rom)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, "${out.name}.tmp")
            tmp.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 92, it) }
            tmp.renameTo(out)
            prefs.setRomCoverManual(rom, true)
        }
    }

    /** Reverts to the auto cover: clears the manual flag, drops the current
     * file, and immediately re-queues a fetch so a cover is never left
     * simply missing (for a known, fetchable hack; otherwise back to the
     * placeholder icon). */
    private fun resetCover(rom: File) {
        prefs.setRomCoverManual(rom, false)
        coverFile(rom).delete()
        bump()
        generateAutoCoverAsync(rom)
    }

    /** Center-crops [src] to [ICON_ASPECT] (never upscales), then caps the
     * long edge at [MAX_COVER_WIDTH] so a manually-picked photo doesn't
     * balloon the covers/ folder. */
    private fun cropToIconAspect(src: Bitmap): Bitmap {
        val srcRatio = src.width.toFloat() / src.height
        val (cw, ch) = if (srcRatio > ICON_ASPECT) {
            (src.height * ICON_ASPECT).toInt().coerceAtLeast(1) to src.height
        } else {
            src.width to (src.width / ICON_ASPECT).toInt().coerceAtLeast(1)
        }
        val x = ((src.width - cw) / 2).coerceAtLeast(0)
        val y = ((src.height - ch) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(src, x, y, cw.coerceAtMost(src.width - x), ch.coerceAtMost(src.height - y))
        if (cropped.width <= MAX_COVER_WIDTH) return cropped
        val scale = MAX_COVER_WIDTH.toFloat() / cropped.width
        return Bitmap.createScaledBitmap(cropped, MAX_COVER_WIDTH, (cropped.height * scale).toInt().coerceAtLeast(1), true)
    }

    /**
     * Copies [uri] aside and checks it with [CompanionSupport] (a whole-file
     * hash for big ROMs, so off the UI thread): a supported ROM goes straight
     * into the library, anything else waits on the "not supported" confirm.
     * A `.zip` / `.7z` is unpacked: its ROM joins the library under the
     * archive's name (`Emerald.zip` -> `Emerald.gba`).
     */
    private fun startImport(uri: Uri, play: Boolean) {
        // VIEW is exported: any app can point it anywhere. Never this app's own files (RomIntake).
        if (RomIntake.isOwn(this, uri)) return
        Thread({
            val pending = try {
                var tmp = File(importDir, "import-${System.currentTimeMillis()}.gba")
                try {
                    contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { RomIntake.copyCapped(input, it) } }
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
                var name = RomUris.sanitizeFileName(RomUris.displayName(this, uri) ?: "imported-${System.currentTimeMillis()}.gba")
                // A BPS patch picked as a ROM file (or opened with the app): PATCH A ROM takes it from here.
                if (BpsPatch.looksLikeBps(tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } })) {
                    tmp.delete()
                    runOnUiThread { patcher.start(uri, name) }
                    return@Thread
                }
                RomArchive.sniff(tmp)?.let { format ->
                    val rom = File(importDir, "${tmp.name}.rom")
                    val entry = RomArchive.extract(tmp, rom, format)
                    tmp.delete()
                    if (entry == null) {
                        runOnUiThread { Toast.makeText(this, tr("No GBA ROM in {0}", name), Toast.LENGTH_LONG).show() }
                        return@Thread
                    }
                    val base = name.takeIf { it.substringAfterLast('.', "").lowercase() in RomArchive.EXTENSIONS }
                        ?.substringBeforeLast('.') ?: entry.name.substringAfterLast('/').substringBeforeLast('.')
                    name = RomUris.sanitizeFileName("$base.${entry.extension}")
                    tmp = rom
                }
                if (tmp.length() > 0 && RomIdentity.looksLikeRom(tmp)) {
                    PendingImport(tmp, name, RomUris.originalPath(this, uri), play)
                } else {
                    tmp.delete(); null
                }
            } catch (t: Throwable) {
                null
            } ?: return@Thread
            val supported = CompanionSupport.isSupported(pending.tmp)
            runOnUiThread { if (supported) finishImport(pending) else pendingImport = pending }
        }, "pokedaisy-import").apply { isDaemon = true; start() }
    }

    /** Moves an accepted import into the library (replacing a same-named ROM, as before). */
    private fun finishImport(p: PendingImport) {
        val out = File(romsDir, p.name)
        if (!p.tmp.renameTo(out)) {
            runCatching { p.tmp.copyTo(out, overwrite = true) }
            p.tmp.delete()
        }
        if (out.length() > 0) {
            prefs.setRomSourcePath(out, p.sourcePath)
            generateAutoCoverAsync(out)
            identifyGames()
            if (p.play) play(out)
        }
        bump()
    }


    // ---- the dual-screen launcher ---------------------------------------------

    /** Both screens' launcher state (internal for ui-preview). */
    internal val launcher = LauncherState().apply { onPickView = { pickQuickView(it) } }

    /** With a second screen (and Settings > DUAL-SCREEN LAUNCHER on): the launcher, not the classic view. */
    internal var launcherOn by mutableStateOf(false)

    /** The second screen's window: whichever of the list / media the main screen isn't showing. */
    private val secondScreen by lazy { LauncherSecondScreen(this) { LauncherSecondWindow() } }

    /** The main window's coroutine scope - X's animation needs its frame clock. */
    private var launcherScope: kotlinx.coroutines.CoroutineScope? = null

    /** Turns the launcher on or off by the displays and the setting, showing / hiding the second screen. */
    private fun syncLauncher() {
        // Only from the main display: on the second one the "other" screen would be this same one.
        val want = prefs.dualScreenLauncher && setup == null && Screens.hasSecond(this) && Screens.isOnMainDisplay(this)
        val was = launcherOn
        // The second screen only shows the game's media, which is ScreenScraper's: without it (its flag off) the list
        // stays on this one alone, and the second screen is left to the system rather than shown empty.
        val media = ScreenScraperClient.enabled
        launcherOn = want && (!media || secondScreen.show())
        if (!launcherOn || !media) secondScreen.dismiss()
        if (launcherOn && !was) refreshLauncherGames()
    }

    /** The launcher's quick views (its pills and their counts), as [refreshLauncherGames] last split the library. */
    private var launcherViews by mutableStateOf<QuickViews?>(null)

    /** The launcher's games: the open quick view of the library in its order (favorites first), the last played
     * selected at first. */
    private fun refreshLauncherGames() {
        val views = quickViewsOf(sortLibrary(this, prefs, shownRoms(), sort))
        launcherViews = views
        launcher.views = views.shown
        val open = views.open(quickView)
        val fresh = open != launcher.view && launcher.games.isNotEmpty()
        launcher.view = open
        launcher.setGames(views.games(quickView), prefer = prefs.lastRomPath, fresh = fresh)
    }

    /** Every window over the main screen (pass keys to them, not the launcher). */
    private val libraryDialogOpen
        get() = searching || pickingSort || addMenu || renaming != null || deleting != null || gameInfo != null || coverPickerFor != null ||
            pendingImport != null || patcher.step != null || saveLoad != null || backupPick != null || updates.release != null || crashAsk ||
            (expandIntro && !crashAsk) || battleStyleIntroShown || ssIntroShown || whatsNewShown || supportShown || seasonalShown

    /** BACK for the library's windows (OptionOverlay & co.): the newest open one closes. */
    private val libraryBack = com.pokedaisy.app.companion.ui.CompanionBack()
    /** B / BACK went to a window: its key up is that window's too (or Android's own BACK would follow). */
    private var backTaken = false

    // Setup's GAME BUTTONS: analog L2 / R2 (Retroid's triggers send no keys) as BUTTON_L2 / R2, like the game takes them.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val capture = setup?.capture
        if (capture != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK) && capture.onTriggers(
                maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_THROTTLE)),
            )
        ) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A game being got ready ([GameLoadingCover]): no key acts until it starts.
        if (preparing != null) return true
        // Setup's GAME BUTTONS row waiting for a button: every key is its, before the launcher, the windows or the focus.
        if (setup?.capture?.onKey(event) == true) return true
        if (launcherOn && setup == null && !libraryDialogOpen && launcherKey(event)) return true
        // B (or BACK) with a window open closes that window, back to what opened it: the launcher's menu comes back
        // ([MenuReturnWatch]) - it used to drop to the games, or leave the library.
        if (setup == null && (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 && libraryDialogOpen) backTaken = libraryBack.back()
                if (backTaken) return true
            } else if (event.action == KeyEvent.ACTION_UP && backTaken) {
                backTaken = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** The launcher's controls: the D-pad walks the list (the grid's rows and columns, the quick views' pills),
     * A opens the game, B its options, X swaps the screens, Y opens Settings (L / R a page - LEFT / RIGHT too in the
     * list view - SELECT the sort). True = handled. */
    private fun launcherKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val code = event.keyCode
        launcher.menu?.let { menu ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> if (down) menu.move(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) menu.move(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) menu.moveColumn(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) menu.moveColumn(1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (first) {
                    launcher.menu = null
                    menu.items.getOrNull(menu.cursor)?.onClick?.invoke()
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (first) launcher.menu = null
                else -> return code in LAUNCHER_KEYS
            }
            return true
        }
        val vertical = launcherRailVertical
        // BACK on TOOLS (or the rail) goes back to the games, not out of the app; its key up is taken too.
        if (code == KeyEvent.KEYCODE_BACK && (toolsOpen || launcher.navCursor >= 0)) {
            if (first) {
                launcher.navCursor = -1
                toolsOpen = false
                backTaken = true
            }
            return true
        }
        // On the GAMES / TOOLS / SETTINGS rail (a bar on a tall screen): along it, A opens the place, RIGHT (UP: the bar)
        // or B back to what's open.
        if (launcher.navCursor >= 0) {
            val last = com.pokedaisy.app.companion.ui.LibrarySection.entries.lastIndex
            val back = if (vertical) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_UP
            when {
                code == (if (vertical) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT) ->
                    if (down) launcher.navCursor = (launcher.navCursor - 1).coerceAtLeast(0)
                code == (if (vertical) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT) ->
                    if (down) launcher.navCursor = (launcher.navCursor + 1).coerceAtMost(last)
                code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ->
                    if (first) launcherPick(com.pokedaisy.app.companion.ui.LibrarySection.entries[launcher.navCursor])
                code == back || code == KeyEvent.KEYCODE_BUTTON_B -> if (first) launcher.navCursor = -1
                code == KeyEvent.KEYCODE_BUTTON_Y -> if (first) openSettings()
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP ||
                    code == KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                else -> return code in LAUNCHER_KEYS
            }
            return true
        }
        // LEFT off the left edge (the header's first button, the first quick view, the grid's first column) onto the rail.
        if (vertical && code == KeyEvent.KEYCODE_DPAD_LEFT && !toolsOpen && (
                launcher.headerCursor == 0 ||
                    (launcher.viewCursor && launcher.views.indexOf(launcher.view) <= 0) ||
                    (launcher.headerCursor < 0 && !launcher.viewCursor && launcher.columns > 1 && launcher.selected % launcher.columns == 0)
                )
        ) {
            if (first) launcherToRail()
            return true
        }
        // DOWN off the games' last row onto the bar (a tall screen).
        if (!vertical && code == KeyEvent.KEYCODE_DPAD_DOWN && !toolsOpen && launcher.headerCursor < 0 && !launcher.viewCursor &&
            launcher.selected / launcher.columns.coerceAtLeast(1) >= launcher.games.lastIndex.coerceAtLeast(0) / launcher.columns.coerceAtLeast(1)
        ) {
            if (first) launcherToRail()
            return true
        }
        // TOOLS in the games' place: the D-pad walks its cards (UP off the top row to the header, LEFT off the first column /
        // DOWN off the last row to the rail / bar), A opens the tool, B back to the games.
        if (toolsOpen && launcher.headerCursor < 0) {
            val n = launcherTools.size
            val cols = launcher.toolColumns.coerceAtLeast(1)
            val c = launcher.toolCursor.coerceIn(0, (n - 1).coerceAtLeast(0))
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> if (down) { if (c - cols < 0) launcher.headerCursor = 0 else launcher.toolCursor = c - cols }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                    if (c + cols <= n - 1) launcher.toolCursor = c + cols else if (!vertical) launcherToRail()
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) { if (c % cols == 0) { if (vertical) launcherToRail() } else launcher.toolCursor = c - 1 }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && c % cols < cols - 1 && c + 1 < n) launcher.toolCursor = c + 1
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_START ->
                    if (first) launcherTools.getOrNull(c)?.open?.invoke()
                KeyEvent.KEYCODE_BUTTON_B -> if (first) toolsOpen = false
                KeyEvent.KEYCODE_BUTTON_Y -> if (first) openSettings()
                else -> return code in LAUNCHER_KEYS
            }
            return true
        }
        // On the quick views' row: LEFT / RIGHT open the next view (moveColumn), A / B go back down to the games.
        if (launcher.viewCursor) {
            when (code) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_B -> {
                    if (first) launcher.moveRow(1)
                    return true
                }
            }
        }
        // Up in the header: A presses the button, B goes back down to the list (as DOWN does).
        if (launcher.headerCursor >= 0) {
            when (code) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (first) launcherHeader().getOrNull(launcher.headerCursor)?.onClick?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (first) launcher.headerCursor = -1
                    return true
                }
                // Over TOOLS, DOWN goes back to its cards (there are no quick views there).
                KeyEvent.KEYCODE_DPAD_DOWN -> if (toolsOpen) {
                    if (down) launcher.headerCursor = -1
                    return true
                }
            }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) launcher.moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) launcher.moveRow(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) launcher.moveColumn(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) launcher.moveColumn(1)
            KeyEvent.KEYCODE_BUTTON_L1 -> if (down) launcher.page(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (down) launcher.page(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_START ->
                if (first) launcher.current?.let(::play)
            KeyEvent.KEYCODE_BUTTON_B -> if (first) openLauncherMenu()
            KeyEvent.KEYCODE_BUTTON_X -> if (first) swapLauncher()
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) openSettings()
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (first) pickingSort = true
            else -> return false
        }
        return true
    }

    private fun openLauncherMenu() {
        val rom = launcher.current ?: return
        launcher.menu = LauncherMenu(
            romLabel(rom),
            romMenuItems(rom, onRename = { renaming = rom }, onDelete = { deleting = rom }).mapIndexed { i, item ->
                LauncherMenu.Item(item.label, item.icon, item.danger, item.opens, item.pixel) {
                    // B / BACK out of what it opens lands back here, on this entry.
                    if (item.opens) {
                        menuReturn = MenuReturn(rom, i)
                        menuReturnArmed = false
                    }
                    item.onClick()
                }
            },
        )
    }

    /** The launcher's menu to bring back (its game and entry) once what an entry opened has closed. */
    private class MenuReturn(val rom: File, val cursor: Int)
    private var menuReturn: MenuReturn? = null
    /** What it opened has shown (a window over the library, or another screen in front of it). */
    private var menuReturnArmed = false
    /** Between onResume and onPause, as Compose state. */
    private var isResumed by mutableStateOf(false)

    /** Watches for [menuReturn]'s window / screen to close, then opens the menu again where it was - unless the game went
     * (DELETE, HIDE). In the main window's composition: it needs the library's dialogs' state. */
    @Composable
    private fun MenuReturnWatch() {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { libraryDialogOpen || !isResumed }.collect { busy ->
                val r = menuReturn ?: return@collect
                if (busy) {
                    menuReturnArmed = true
                    return@collect
                }
                if (!menuReturnArmed) return@collect
                menuReturn = null
                menuReturnArmed = false
                if (launcher.games.none { it.absolutePath == r.rom.absolutePath }) return@collect
                if (launcher.current?.absolutePath != r.rom.absolutePath) launcher.select(r.rom)
                openLauncherMenu()
                launcher.menu?.let { it.cursor = r.cursor.coerceIn(0, it.items.lastIndex) }
            }
        }
    }

    /** X: the list and the media trade screens, animated on both. */
    private fun swapLauncher() {
        if (!ScreenScraperClient.enabled) return
        launcherScope?.launch { launcher.swap { prefs.launcherSwapped = it } }
    }

    /** SETTINGS as the library's third tab: the rail / bar stays, no slide between them (SettingsActivity.EXTRA_TAB). */
    private fun openSettings() {
        settingsTab.launch(
            Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TAB, true)
                .putExtra(SettingsActivity.EXTRA_TOOLS_NEW, libraryTools().any { it.newBadge != null }),
            androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(this, 0, 0),
        )
    }

    /** The launcher on the activity's (main) screen: the list, or the media after a swap, under the library's windows. */
    @Composable
    private fun LauncherMainWindow() {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(textScale = 1f)
        val scope = rememberCoroutineScope()
        androidx.compose.runtime.SideEffect { launcherScope = scope }
        MenuReturnWatch()
        Box(Modifier.fillMaxSize().background(OptionColors.imageWell)) {
            Box(Modifier.fillMaxSize().launcherSwapMotion(launcher, onMain = true)) {
                if (launcher.swapped) LauncherMedia(m, small) else LauncherList(m, small, onMain = true)
            }
            LibraryDialogs(m, small)
            GameLoadingCover(m)
        }
    }

    /** The second screen's half (the media, or the list after a swap). */
    @Composable
    internal fun LauncherSecondWindow() {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(textScale = 1f)
        Box(Modifier.fillMaxSize().background(OptionColors.imageWell)) {
            Box(Modifier.fillMaxSize().launcherSwapMotion(launcher, onMain = false)) {
                if (launcher.swapped) LauncherList(m, small, onMain = false) else LauncherMedia(m, small)
            }
        }
    }

    @Composable
    private fun LauncherMedia(m: GbaTextMetrics, small: GbaTextMetrics) {
        LauncherMediaPane(
            launcher, GameMedia.root(this), ::coverFile, ::launcherFacts, m, small,
            video = launcherVideo,
        )
    }

    /** Plays a game's video in the media screen (null in ui-preview: no player there). */
    internal var launcherVideo: (@Composable (File, Modifier) -> Unit)? = { f, mod -> LauncherVideo(f, mod) }

    private fun launcherFacts(rom: File): LauncherFacts {
        val played = prefs.romLastPlayed(rom.absolutePath).takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
        }
        return LauncherFacts(
            label = romLabel(rom),
            lastPlayed = played,
            hasSave = SavesLocation.saveFor(this, prefs, rom).exists(),
            signedIn = prefs.screenScraperAccount != null,
        )
    }

    /** The launcher's game list, the classic library's look: a header (sort, view, import, refresh, help, settings),
     * the quick views' pills, then the open view's games as rows or tiles - the cursor's the white card with the focus ring - and the
     * button legend bottom right. [onMain]: on the main display (the Thor's top screen, the wide one), where a row
     * has room for the file's path. A tap plays it (the cursor moves onto it first, so BACK finds it there). */
    @Composable
    private fun LauncherList(m: GbaTextMetrics, small: GbaTextMetrics, onMain: Boolean) {
        @Suppress("UNUSED_EXPRESSION") revision
        val games = launcher.games
        val grid = gridView
        val views = launcherViews
        val context = with(androidx.compose.ui.platform.LocalDensity.current) { (m.rowHeight / 2).roundToPx() }
        fun card(rom: File) = LauncherCard(
            cursor = launcher.isCursor(rom),
            onTap = { launcher.select(rom); play(rom) },
            onMenu = { launcher.select(rom); openLauncherMenu() },
        )
        val tools = remember(revision) { libraryTools() }
        val toolsNew = tools.any { it.newBadge != null }
        val section = if (toolsOpen) com.pokedaisy.app.companion.ui.LibrarySection.TOOLS else com.pokedaisy.app.companion.ui.LibrarySection.GAMES
        val railCursor = com.pokedaisy.app.companion.ui.LibrarySection.entries.getOrNull(launcher.navCursor)
        SideEffect { launcherTools = tools }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            AppBackdrop(logos = true)
            // GAMES / TOOLS / SETTINGS as in the classic library: down the left of a wide screen, along the bottom of a tall one.
            val wide = maxWidth >= maxHeight
            SideEffect { launcherRailVertical = wide }
            // The bar's height (a tall screen): the legend floats over it, not on it.
            var barHeight by remember { mutableStateOf(0.dp) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            Row(Modifier.fillMaxSize()) {
            if (wide) com.pokedaisy.app.companion.ui.LibraryNav(
                section, vertical = true, m, small, toolsBadge = toolsNew && !toolsOpen, top = SCREEN_MARGIN, cursor = railCursor,
                onPick = ::launcherPick,
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OptionTitleWindow("POKéDAISY", m, Modifier.padding(start = SCREEN_MARGIN, end = SCREEN_MARGIN, top = SCREEN_MARGIN), leading = {
                    PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight))
                }) {
                    val buttons = launcherHeader()
                    SideEffect { launcher.headerCount = buttons.size }
                    buttons.forEachIndexed { i, b ->
                        HeaderIcon(b.icon, b.label, m, cursor = launcher.headerCursor == i, onClick = b.onClick)
                    }
                }
                val library = views?.library.orEmpty()
                Spacer(Modifier.height(m.u * (if (library.isNotEmpty() || toolsOpen) 8 else 4)))
                SearchStrip(m)
                if (views != null && library.isNotEmpty() && !toolsOpen) {
                    com.pokedaisy.app.companion.ui.QuickViewPills(
                        views.shown, launcher.view, views.counts, m, small,
                        onPick = { launcher.viewCursor = false; pickQuickView(it) },
                        contentPadding = PaddingValues(horizontal = SCREEN_MARGIN),
                        cursor = launcher.viewCursor,
                    )
                    Spacer(Modifier.height(m.u * 8))
                }
                // Room at the end for the legend, so the last game can scroll clear of it.
                val padding = PaddingValues(start = SCREEN_MARGIN, end = SCREEN_MARGIN, bottom = SCREEN_MARGIN + m.rowHeight * 1.6f)
                if (toolsOpen) {
                    com.pokedaisy.app.companion.ui.ToolsPage(
                        tools, m, small, contentPadding = padding, modifier = Modifier.weight(1f),
                        cursor = launcher.toolCursor.takeIf { launcher.navCursor < 0 && launcher.headerCursor < 0 } ?: -1,
                        onColumns = { launcher.toolColumns = it },
                    )
                } else if (games.isEmpty()) {
                    OptionListWindow(m, Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN)) {
                        GbaText(
                            if (library.isNotEmpty()) emptyViewText(launcher.view) else if (searchQuery.isNotBlank()) tr("No games match your search.") else tr(
                                "No ROMs yet. Tap + to add a ROM file (a .gba, .zip or .7z) or your ROMs folders, or drop files into {0}",
                                "Android/data/$packageName/files/roms/",
                            ),
                            OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.padding(m.u * 4),
                        )
                    }
                } else if (grid) {
                    val state = rememberLazyGridState(launcher.scrollIndex, launcher.scrollOffset)
                    KeepScroll({ state.firstVisibleItemIndex }, { state.firstVisibleItemScrollOffset })
                    val spacing = m.u * 4
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        // GridCells.Adaptive's own count, worked out here so the D-pad knows the rows.
                        val columns = maxOf(1, ((maxWidth - SCREEN_MARGIN * 2 + spacing) / (GRID_TILE_MIN + spacing)).toInt())
                        SideEffect { launcher.columns = columns }
                        // Another quick view starts at its top (the grid would otherwise keep its first game's place).
                        LaunchedEffect(launcher.view) { if (state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) state.scrollToItem(0) }
                        LaunchedEffect(launcher.selected, games, columns) {
                            val target = launcher.selected
                            val info = state.layoutInfo
                            keepInView(
                                target, info.visibleItemsInfo.map { intArrayOf(it.index, it.offset.y, it.size.height) },
                                info.viewportEndOffset - info.afterContentPadding, context,
                                { state.animateScrollBy(it) }, { state.scrollToItem(it) },
                            )
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = padding,
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                        ) {
                            gridItemsIndexed(games, key = { _, rom -> rom.absolutePath }) { _, rom ->
                                RomTile(rom, m, small, onRename = { renaming = rom }, onDelete = { deleting = rom }, launcherCard = card(rom))
                            }
                        }
                    }
                } else {
                    val state = androidx.compose.foundation.lazy.rememberLazyListState(launcher.scrollIndex, launcher.scrollOffset)
                    KeepScroll({ state.firstVisibleItemIndex }, { state.firstVisibleItemScrollOffset })
                    SideEffect { launcher.columns = 1 }
                    LaunchedEffect(launcher.view) { if (state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) state.scrollToItem(0) }
                    LaunchedEffect(launcher.selected, games) {
                        val target = launcher.selected
                        val info = state.layoutInfo
                        keepInView(
                            target, info.visibleItemsInfo.map { intArrayOf(it.index, it.offset, it.size) },
                            info.viewportEndOffset - info.afterContentPadding, context,
                            { state.animateScrollBy(it) }, { state.scrollToItem(it) },
                        )
                    }
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = padding,
                        verticalArrangement = Arrangement.spacedBy(m.u * 4),
                    ) {
                        itemsIndexed(games, key = { _, rom -> rom.absolutePath }) { _, rom ->
                            RomRow(rom, m, small, onRename = { renaming = rom }, onDelete = { deleting = rom }, launcherCard = card(rom), showPath = onMain)
                        }
                    }
                }
                if (!wide) com.pokedaisy.app.companion.ui.LibraryNav(
                    section, vertical = false, m, small, toolsBadge = toolsNew && !toolsOpen, cursor = railCursor,
                    modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                    onPick = ::launcherPick,
                )
            }
            }
            ButtonLegend(
                if (toolsOpen) listOf(
                    LegendEntry("A", tr("OPEN")) { launcherTools.getOrNull(launcher.toolCursor)?.open?.invoke() },
                    LegendEntry("B", tr("BACK")) { toolsOpen = false },
                    LegendEntry("Y", tr("SETTINGS")) { openSettings() },
                ) else listOfNotNull(
                    LegendEntry("A", tr("OPEN")) { launcher.current?.let(::play) },
                    LegendEntry("B", tr("OPTIONS")) { openLauncherMenu() },
                    // SWAP comes with the second screen's media page (ScreenScraper).
                    LegendEntry("X", tr("SWAP")) { swapLauncher() }.takeIf { ScreenScraperClient.enabled },
                    LegendEntry("Y", tr("SETTINGS")) { openSettings() },
                ),
                small,
                Modifier.align(Alignment.BottomEnd).padding(start = SCREEN_MARGIN, end = SCREEN_MARGIN, bottom = SCREEN_MARGIN + if (wide) 0.dp else barHeight),
            )
            launcher.menu?.let { menu -> LauncherMenuOverlay(menu, m) { launcher.menu = null } }
        }
    }

    /** The list's scroll, kept for when it moves to the other screen (or the view changes) - read as it goes away,
     * never while composing: that made the whole list recompose on every scrolled frame (the grid stuttered). */
    @Composable
    private fun KeepScroll(index: () -> Int, offset: () -> Int) {
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                launcher.scrollIndex = index()
                launcher.scrollOffset = offset()
            }
        }
    }

    /**
     * Scrolls lazy item [target] fully into view with some of its neighbours: [items] are the visible ones as
     * (index, offset, size) along the scroll, [viewEnd] where the legend's room starts, [context] the most of what's
     * around it to show too (px). Off screen, it jumps there.
     */
    private suspend fun keepInView(
        target: Int, items: List<IntArray>, viewEnd: Int, context: Int,
        scrollBy: suspend (Float) -> Unit, scrollTo: suspend (Int) -> Unit,
    ) {
        val item = items.firstOrNull { it[0] == target }
        if (item == null) {
            scrollTo(target)
            return
        }
        val top = item[1]
        val bottom = top + item[2]
        // A little of what's past it (half a row at most: a grid's tall tiles scrolled the strip away); the strip at the
        // top goes all the way up, its title with it.
        val margin = if (target == 0) 0 else minOf(item[2] / 2, context)
        when {
            top < margin -> scrollBy((top - margin).toFloat())
            bottom > viewEnd - margin -> scrollBy((bottom - viewEnd + margin).toFloat())
        }
    }

    // ---- ScreenScraper ----------------------------------------------------------

    /** The update's one-time "set up ScreenScraper?" window: wanted (set in onCreate), then shown once nothing else is up. */
    internal var ssIntro by mutableStateOf(false)
    private val ssIntroShown get() = ssIntro && setup == null && !crashAsk && updates.release == null && !expandIntro && !battleStyleIntro && !whatsNewShown && !supportShown && !seasonalShown
    /** WHAT'S NEW is up: after any update offer, never over setup. */
    private val whatsNewShown get() = whatsNew != null && setup == null && updates.release == null
    /** The support window is up: after any update offer and WHAT'S NEW, never over setup. */
    private val supportShown get() = supportAsk && setup == null && updates.release == null && !whatsNewShown

    /** BATTLE STYLE's one-time pick ([com.pokedaisy.app.companion.ui.BattleStyleIntro]): wanted (set in onCreate), shown once nothing else is up. */
    internal var battleStyleIntro by mutableStateOf(false)
    private val battleStyleIntroShown get() = battleStyleIntro && setup == null && !crashAsk && updates.release == null && !expandIntro && !whatsNewShown && !supportShown && !seasonalShown

    /** A limited-time theme to announce ([com.pokedaisy.app.companion.ui.theme.seasonalThemeToAnnounce]; internal for ui-preview). */
    internal var seasonalTheme by mutableStateOf<com.pokedaisy.app.companion.ui.theme.ThemeSpec?>(null)
    /** Its popup is up: after any update offer, WHAT'S NEW and the support window, never over setup or the crash question. */
    private val seasonalShown get() = seasonalTheme != null && setup == null && updates.release == null && !whatsNewShown && !supportShown && !crashAsk

    /** The popup's sign-in (internal for ui-preview). */
    internal val ssLogin by lazy { ScreenScraperLogin.of(prefs) }

    /** The library's media pass after a ScreenScraper sign-in (the popup shows its progress). */
    internal val mediaSync = CoverSync()

    private fun startMediaSync(sync: CoverSync = mediaSync) {
        sync.start(this, prefs, replace = false) {
            launcher.mediaChanged()
            bump()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ---- first-time setup ---------------------------------------------------

    private fun startSetup() {
        setup = SetupState().apply {
            hasAccess = StorageAccess.hasAllFilesAccess(this@LibraryActivity)
            romsFolders = prefs.romsFolders
            if (romsFolders.isNotEmpty()) found = RomFolder.found(this@LibraryActivity, prefs).map(::romLabel)
            savesDir = prefs.savesDirOverride
            apiKey = prefs.steamGridDbApiKey.orEmpty()
            raKey = prefs.raWebApiKey.orEmpty()
            keySaved = prefs.steamGridDbApiKey != null || prefs.raWebApiKey != null
            ssLogin = ScreenScraperLogin.of(prefs)
        }
        secondScreen.dismiss()
        launcherOn = false
    }

    @Composable
    private fun SetupHost(s: SetupState) {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(textScale = 1f)
        BackHandler { setupBack(s) }
        SetupScreen(
            s, m, small,
            onChooseRoms = { chooseFolder(s, SetupState.Step.ROMS) },
            onRemoveRoms = { path -> unlinkRomsFolder(path) },
            onChooseSaves = { chooseFolder(s, SetupState.Step.SAVES) },
            onUseSaves = { path -> useSavesFolder(path) },
            onOpenPage = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onSaveKey = {
                prefs.steamGridDbApiKey = s.apiKey
                prefs.raWebApiKey = s.raKey
                s.keySaved = prefs.steamGridDbApiKey != null || prefs.raWebApiKey != null
                if (s.keySaved) s.covers.start(this, prefs, replace = false)
            },
            onBack = { setupBack(s) },
            onScreenScraperSignIn = { s.ssLogin.signIn(prefs, { r -> runOnUiThread { r() } }) { startMediaSync(s.covers) } },
            onScreenScraperSignOut = { s.ssLogin.signOut(prefs) },
            buttonRequest = { btn ->
                val dir = getExternalFilesDir(null) ?: filesDir
                KeyCapture.Request(
                    btn.title, chord = false,
                    onDone = { keys -> GbaControls.setBinding(dir, btn, keys.first()); readButtons(s) },
                    onClear = if (btn.canClear) ({ GbaControls.clearBinding(dir, btn); readButtons(s) }) else null,
                )
            },
            onNext = {
                val steps = s.steps
                if (s.step == steps.last()) finishSetup() else enterStep(s, steps[s.index + 1])
            },
        )
    }

    /** BACK: the previous step; on the first, leaves setup as if skipped - back to
     * Settings when RUN SETUP opened it. */
    private fun setupBack(s: SetupState) {
        if (s.index > 0) {
            enterStep(s, s.steps[s.index - 1])
            return
        }
        finishSetup()
        if (s.fromSettings) startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun enterStep(s: SetupState, step: SetupState.Step) {
        s.capture.cancel()
        s.step = step
        if (step == SetupState.Step.BUTTONS) readButtons(s)
        if (step == SetupState.Step.SAVES) {
            Thread({
                val found = runCatching { SavesLocation.suggestions(prefs) }.getOrDefault(emptyList())
                runOnUiThread { s.suggestions = found }
            }, "pokedaisy-saves-suggest").apply { isDaemon = true; start() }
        }
    }

    /** The GAME BUTTONS step's list: A, B, L, R, START, SELECT (+ the second START / SELECT, turbo) as bound now. */
    private fun readButtons(s: SetupState) {
        s.buttons = GbaControls.rawBindings(getExternalFilesDir(null) ?: filesDir)
    }

    private fun finishSetup() {
        setup?.capture?.cancel()
        prefs.setupDone = true
        // Setup had its own GAME MEDIA step: no ScreenScraper popup after it (none while ScreenScraper is off).
        if (ScreenScraperClient.enabled) prefs.screenScraperAsked = true
        ssIntro = false
        setup = null
        bump()
        syncLauncher()
    }

    /** Folders are opened by raw path, so All files access comes first: Android's page
     * opens, and onResume carries on with the pick once the player comes back. */
    private fun chooseFolder(s: SetupState, step: SetupState.Step) {
        if (!StorageAccess.hasAllFilesAccess(this)) {
            s.awaitingAccess = step
            StorageAccess.requestAllFilesAccess(this)
            return
        }
        if (step == SetupState.Step.ROMS) pickRomsFolder.launch(null) else pickSavesFolder.launch(null)
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

    /**
     * SAVE FOLDER: [rom]'s save lives in [path] from now on (null: the saves folders' search again). A
     * folder without a save for it gets a copy of the current one - copied, never moved, so the
     * old file stays where it was.
     */
    private fun setGameSaveFolder(rom: File, path: String?) {
        val before = SavesLocation.saveFor(this, prefs, rom)
        prefs.setRomSaveDir(rom, path)
        val after = SavesLocation.saveFor(this, prefs, rom)
        if (before.isFile && !after.exists() && before.absolutePath != after.absolutePath) {
            runCatching { before.copyTo(after) }
                .onSuccess { Toast.makeText(this, tr("Save copied to {0}", after.parent ?: ""), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this, tr("Can't write the save to {0}", after.parent ?: ""), Toast.LENGTH_LONG).show() }
        }
        revision++
    }

    /** A picked ROMs folder joins the linked ones (setup's ROMS step, the +'s ADD A ROMS FOLDER) and is scanned. */
    private fun linkRomsFolder(path: String) {
        val folders = prefs.romsFolders
        val s = setup
        RomFolder.coveredBy(folders, path)?.let { already ->
            Toast.makeText(this, if (already == File(path).absolutePath) tr("That folder is already linked") else tr("Already linked through {0}", already), Toast.LENGTH_LONG).show()
            return
        }
        prefs.romsFolders = RomFolder.withFolder(folders, path)
        linkingFolder = true
        s?.romsFolders = prefs.romsFolders
        s?.found = null
        // The +: say what it found once the scan is done.
        if (s == null) {
            reportScan = true
            showRefreshToast(tr("Looking for games…"))
        }
        rescanRomsFolder(s)
    }

    /** Setup's REMOVE on a linked folder: its games leave the library, the files stay where they are. */
    private fun unlinkRomsFolder(path: String) {
        prefs.romsFolders = prefs.romsFolders - path
        RomFolder.forgetLater(this, path)
        setup?.let { s ->
            s.romsFolders = prefs.romsFolders
            s.found = RomFolder.found(this, prefs).map(::romLabel)
        }
        bump()
    }

    private fun useSavesFolder(path: String?) {
        prefs.savesDirOverride = path
        setup?.savesDir = path
        bump()
    }

    private var scanningFolder = false
    /** The Refresh button is waiting on a scan to say what it found. */
    private var reportScan = false
    private var refreshToast: Toast? = null

    /** The header's Refresh: rescans the linked folders and catches up on covers,
     * with a toast now ("Refreshing…") and one when the scan is done saying what
     * the library has. */
    private fun refreshLibrary() {
        bump()
        backfillCovers()
        if (prefs.romsFolders.isEmpty()) {
            showRefreshToast(tr("Library refreshed: {0}", gamesLabel(listRoms().size)))
            return
        }
        reportScan = true
        showRefreshToast(tr("Refreshing library…"))
        rescanRomsFolder()
    }

    /** One toast at a time: the result replaces "Refreshing…" instead of queueing behind it. */
    private fun showRefreshToast(text: String) {
        refreshToast?.cancel()
        refreshToast = Toast.makeText(this, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun gamesLabel(n: Int) = if (n == 1) tr("1 game") else tr("{0} games", n)

    private fun refreshResult(r: RomFolder.ScanResult): String {
        if (r.unreadable.size == prefs.romsFolders.size) return tr("Couldn't read your ROMs folder - check All files access in SETTINGS > FOLDERS")
        val parts = mutableListOf(gamesLabel(listRoms().size))
        if (r.added.isNotEmpty()) parts += tr("{0} new", r.added.size)
        val skipped = r.files - r.supported
        if (skipped > 0) parts += tr("{0} not supported", skipped)
        if (r.unreadable.isNotEmpty()) parts += tr("{0} folder(s) couldn't be read", r.unreadable.size)
        return tr("Library refreshed: {0}", parts.joinToString(", "))
    }

    /** Looks for new or changed ROMs in the linked folders, off the UI thread; [s]
     * shows the progress (setup's ROMS step). New games get their covers. */
    private fun rescanRomsFolder(s: SetupState? = null) {
        // Setup's own scan always runs (RomFolder.scan queues it behind a running one).
        // A Refresh during another scan (the one onResume starts) gets that one's result.
        if (scanningFolder && s == null) return
        if (prefs.romsFolders.isEmpty()) {
            // Nothing linked: just the imported ROMs' NOT SUPPORTED verdicts.
            Thread({
                RomFolder.checkImported(this)
                runOnUiThread { bump() }
            }, "pokedaisy-rom-check").apply { isDaemon = true; start() }
            return
        }
        scanningFolder = true
        s?.scanning = true
        s?.checked = 0
        s?.toCheck = 0
        Thread({
            val result = RomFolder.scan(this, prefs) { checked, total ->
                if (s != null) runOnUiThread { s.checked = checked; s.toCheck = total }
            }
            RomFolder.checkImported(this)
            GameTitles.identify(this, listRoms())
            // The linked folders' ROMs are dated now (as the library being set up, not NEW).
            if (result.readable) linkingFolder = false
            val found = RomFolder.found(this, prefs).map(::romLabel)
            runOnUiThread {
                scanningFolder = false
                s?.scanning = false
                s?.found = found
                if (result.added.isNotEmpty()) backfillCovers()
                if (reportScan) {
                    reportScan = false
                    showRefreshToast(refreshResult(result))
                }
                bump()
            }
        }, "pokedaisy-rom-scan").apply { isDaemon = true; start() }
    }

    private fun romLabel(rom: File) = GameTitles.label(this, prefs, rom)

    /** Names the library's games ([GameTitles]: hashes new ROMs) off the UI thread,
     * redrawing once any name changed. The folder scan does the same for what it finds. */
    private fun identifyGames() {
        Thread({
            val roms = listRoms() + RomFolder.hiddenRoms(this, prefs)
            if (GameTitles.identify(this, roms)) runOnUiThread { bump() }
        }, "pokedaisy-game-titles").apply { isDaemon = true; start() }
    }

    companion object {
        /** The options popup's subtle 1u frame. */
        private val MenuBorder get() = if (OptionColors.dark) Color(0xFF4A5262) else Color(0xFFA8A8B0)
        const val EXTRA_ROM = "rom"
        /** Who opened the game, for crash reports ([CrashReports.gameStarted]): the library, or the app that sent it. */
        const val EXTRA_LAUNCHED_FROM = "launchedFrom"
        /** The grid view's narrowest tile (GridCells.Adaptive's minimum). */
        private val GRID_TILE_MIN = 150.dp
        /** Keys the launcher's menu swallows (the launcher's own), so they don't act behind it. */
        private val LAUNCHER_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        )
        private const val MAX_COVER_WIDTH = 480
        /** Decoded covers kept ([coverBitmaps]): ~36 at the largest (480 px square). */
        private const val COVER_CACHE_BYTES = 32 * 1024 * 1024
    }
}
