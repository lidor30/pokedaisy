package com.pokedaisy.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.save.Gen3SaveData
import com.pokedaisy.app.companion.data.save.Gen3SaveEditor
import com.pokedaisy.app.companion.data.save.Gen3SaveFile
import com.pokedaisy.app.companion.data.save.RomMonIcons
import com.pokedaisy.app.companion.data.save.TradeSide
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.AppBackdrop
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.MonIconLoader
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.PcBoxesScreen
import com.pokedaisy.app.companion.ui.PcBoxesState
import com.pokedaisy.app.companion.ui.TradeScreen
import com.pokedaisy.app.companion.ui.TradeState
import com.pokedaisy.app.companion.ui.bitmap
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppTheme
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * A game's SAVE EDITOR (its library menu, or TOOLS with the game picked first: [EXTRA_PICK]), and TRADE with another
 * game's save from there: the save files read and edited while the games are closed - the library is in front, so a
 * game's engine has stopped and written its save, and it reloads the file when it starts again. Reading and writing run
 * off the UI thread. TOOLS > TRADE SAVES opens the trade alone ([EXTRA_TRADE]): the two games picked, then the trade,
 * with no editor page before or after it (the user's call, 2026-11-03: it read as a page inside the editor).
 */
class SaveEditorActivity : ComponentActivity() {

    /** A game's save as read: what the editor edits, and the bytes the file must still hold when it's written. */
    private class Opened(
        val rom: File, val name: String, val gameCode: String, val bytes: ByteArray,
        /** A Gen 3 save (the editor's every page, and TRADE), or [ds]'s HeartGold / SoulSilver one (its POKéMON page). */
        val file: Gen3SaveFile?,
        val ds: Pair<com.pokedaisy.app.companion.data.save.Gen4SaveFile, com.pokedaisy.app.companion.data.save.Gen4GameData>? = null,
    ) {
        fun editor(): com.pokedaisy.app.companion.data.save.MonSaveEditor =
            file?.let { Gen3SaveEditor(it) } ?: ds!!.let { (f, g) -> com.pokedaisy.app.companion.data.save.Gen4SaveEditor(f, g) }
    }

    private sealed class Page {
        object Loading : Page()
        /** A pick-list is up (the game, or the trade's two): the title alone behind it. */
        object Picking : Page()
        class Message(val text: String) : Page()
        class Ready(val state: PcBoxesState, val opened: Opened) : Page()
        class Trade(val state: TradeState, val a: Opened, val b: Opened) : Page()
        class PalPark(val state: com.pokedaisy.app.companion.ui.PalParkState, val gba: Opened, val ds: Opened) : Page()
    }

    private lateinit var prefs: Prefs
    private lateinit var rom: File
    private var gameName = ""
    private var page by mutableStateOf<Page>(Page.Loading)
    private var saving by mutableStateOf(false)
    /** TRADE's pick-list: the other games with a save it can read. */
    private var partners by mutableStateOf<List<Pair<File, String>>?>(null)
    /** TRADE SAVES: the first game's pick-list, before any game is open. */
    private var firstPick by mutableStateOf<List<Pair<File, String>>?>(null)
    /** TRADE SAVES (TOOLS): the trade alone - its first game is read for it, not opened in the editor. */
    private var tradeOnly = false
    /** TRADE SAVES' first game, read. */
    private var tradeFrom: Opened? = null
    /** SAVE EDITOR (TOOLS): the game's pick-list, before any game is open. */
    private var editPick by mutableStateOf<List<Pair<File, String>>?>(null)
    /** PAL PARK (TOOLS): Pal Park alone - the GBA game picked, then the DS one, then Pal Park. */
    private var palParkOnly = false
    /** PAL PARK's two pick-lists: the GBA game to bring Pokémon from, then the DS game. */
    private var palFromPick by mutableStateOf<List<Pair<File, String>>?>(null)
    private var palToPick by mutableStateOf<List<Pair<File, String>>?>(null)
    private var palFrom: Opened? = null
    /** MISSING's other saves, in the order its entries name them. */
    private var missingRoms: List<File> = emptyList()
    /** Icons per ROM path, once loaded (absent = none for that ROM). */
    private val icons = mutableStateMapOf<String, MonIconLoader>()

    /** The pop-ups open over the editor (pick-lists, amounts, names, confirms): B / BACK closes the newest. */
    private val overlays = com.pokedaisy.app.companion.ui.OverlayStack()
    /** Where the D-pad starts when nothing has the focus (the selected slot, a pop-up's choice). */
    private val starts = com.pokedaisy.app.companion.ui.FocusStarts()
    private var composeHasFocus = false
    /** B / BACK closed a pop-up: its key up is the pop-up's too. */
    private var backTaken = false

    /** A controller or keyboard steers the editor ([saveEditorKey], SaveEditorKeys.kt). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        saveEditorKey(this, event, overlays, starts, composeHasFocus, { backTaken }, { backTaken = it }) ?: super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        val path = intent?.getStringExtra(EXTRA_ROM)
        tradeOnly = path == null && intent?.getBooleanExtra(EXTRA_TRADE, false) == true
        palParkOnly = path == null && intent?.getBooleanExtra(EXTRA_PAL_PARK, false) == true
        val pick = path == null && intent?.getBooleanExtra(EXTRA_PICK, false) == true
        if (path == null && !tradeOnly && !pick && !palParkOnly) { finish(); return }
        // The editor's text a step smaller everywhere (the user's call: more of the save on screen), like Settings':
        // a font pixel down, so it stays crisp.
        setContent {
            AppTheme {
                com.pokedaisy.app.companion.ui.SaveEditorText {
                    // The D-pad's pop-ups (focus in, out and back, B closes the newest) and where it starts ([FocusStarts]).
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pokedaisy.app.companion.ui.LocalOverlayStack provides overlays,
                        com.pokedaisy.app.companion.ui.LocalFocusStarts provides starts,
                    ) {
                        Box(Modifier.fillMaxSize().onFocusChanged { composeHasFocus = it.hasFocus }) { Root() }
                    }
                }
            }
        }
        when {
            path != null -> openGame(File(path))
            tradeOnly -> pickFirst()
            palParkOnly -> pickPalParkFrom()
            else -> pickToEdit()
        }
    }

    /** SAVE EDITOR from TOOLS: which game's save to open. */
    private fun pickToEdit() {
        page = Page.Picking
        Thread {
            val list = readableGames()
            runOnUiThread {
                if (list.isEmpty()) page = Page.Message(tr("SAVE EDITOR needs a game with a save it can read: FireRed, LeafGreen, Emerald, Ruby, Sapphire, HeartGold, SoulSilver or Platinum, saved once in the game."))
                else editPick = list
            }
        }.start()
    }

    /** TRADE SAVES: [f]'s save read as the trade's first side, then the other game's pick-list. */
    private fun openTradeFrom(f: File) {
        rom = f
        gameName = GameTitles.label(this, prefs, rom)
        page = Page.Loading
        Thread {
            val o = open(f)
            runOnUiThread {
                if (o is Opened) { tradeFrom = o; page = Page.Picking; pickPartner() } else page = Page.Message(o as String)
            }
            loadIcons(f)
        }.start()
    }

    /** The trade's two saves read again (after it was saved): further trades start from the files as written. */
    private fun reopenTrade(aRom: File, bRom: File) {
        page = Page.Loading
        Thread {
            val a = open(aRom)
            val b = open(bRom)
            runOnUiThread {
                page = if (a is Opened && b is Opened) {
                    tradeFrom = a
                    Page.Trade(TradeState(TradeSide(a.name, a.editor(), a.gameCode), TradeSide(b.name, b.editor(), b.gameCode)), a, b)
                } else Page.Message((a as? String) ?: (b as String))
            }
        }.start()
    }

    /** Off the trade: TRADE SAVES' is the trade alone, so it leaves; a game's editor goes back to that game. */
    private fun leaveTrade() {
        if (tradeOnly) finish() else load()
    }

    private fun openGame(f: File) {
        rom = f
        gameName = GameTitles.label(this, prefs, rom)
        load()
    }

    /** The library's games with a save the editor can read; a trade's partner ([with]): one of the same generation. Blocking. */
    private fun readableGames(except: File? = null, with: File? = null, onlyDs: Boolean? = null): List<Pair<File, String>> {
        val ds = onlyDs ?: with?.let { SaveFileEdits.isDsEditable(it) }
        return RomFolder.libraryRoms(this, prefs)
            .filter {
                it.absolutePath != except?.absolutePath &&
                    when (ds) {
                        null -> SaveFileEdits.formatFor(it) != null || SaveFileEdits.isDsEditable(it)
                        true -> SaveFileEdits.isDsEditable(it)
                        false -> SaveFileEdits.formatFor(it) != null
                    } &&
                    SavesLocation.saveFor(this, prefs, it).isFile
            }
            .map { it to GameTitles.label(this, prefs, it) }
    }

    /** TRADE SAVES: which game to trade from (then [pickPartner] for the other). */
    private fun pickFirst() {
        page = Page.Picking
        Thread {
            val list = readableGames()
            runOnUiThread {
                if (list.size < 2) page = Page.Message(tr("TRADE SAVES needs two games with a save it can read, each saved once in the game: FireRed, LeafGreen, Emerald, Ruby and Sapphire trade with each other, HeartGold, SoulSilver and Platinum with each other."))
                else firstPick = list
            }
        }.start()
    }

    /** PAL PARK: which GBA game to bring Pokémon from (then [pickPalParkTo] for the DS game). */
    private fun pickPalParkFrom() {
        page = Page.Picking
        Thread {
            val gba = readableGames(onlyDs = false)
            val ds = readableGames(onlyDs = true)
            runOnUiThread {
                if (gba.isEmpty() || ds.isEmpty()) page = Page.Message(tr("PAL PARK needs a GBA game (FireRed, LeafGreen, Emerald, Ruby or Sapphire) and HeartGold, SoulSilver or Platinum, each with a save it can read, saved once in the game."))
                else palFromPick = gba
            }
        }.start()
    }

    private fun openPalParkFrom(f: File) {
        page = Page.Loading
        Thread {
            val o = open(f)
            runOnUiThread {
                if (o is Opened) { palFrom = o; page = Page.Picking; pickPalParkTo() } else page = Page.Message(o as String)
            }
            loadIcons(f)
        }.start()
    }

    private fun pickPalParkTo() {
        Thread {
            val list = readableGames(onlyDs = true)
            runOnUiThread { palToPick = list }
        }.start()
    }

    /** Pal Park between [gba] and [ds] (both read), with [pick] in the GBA PC already picked. */
    private fun startPalPark(gba: Opened, ds: Opened, pick: com.pokedaisy.app.companion.data.save.BoxSlot? = null) {
        val st = com.pokedaisy.app.companion.ui.PalParkState(TradeSide(gba.name, gba.editor(), gba.gameCode), TradeSide(ds.name, ds.editor(), ds.gameCode))
        if (pick != null) { st.boxGba = pick.box; st.toggle(pick) }
        page = Page.PalPark(st, gba, ds)
        Thread { loadIcons(gba.rom); loadIcons(ds.rom) }.start()
    }

    private fun openPalPark(gbaRom: File, dsRom: File, pick: com.pokedaisy.app.companion.data.save.BoxSlot? = null) {
        val before = page
        page = Page.Loading
        Thread {
            val a = open(gbaRom)
            val b = open(dsRom)
            runOnUiThread {
                if (a is Opened && b is Opened) startPalPark(a, b, pick)
                else { Toast.makeText(this, (a as? String) ?: (b as String), Toast.LENGTH_LONG).show(); page = if (palParkOnly) Page.Message((a as? String) ?: (b as String)) else before }
            }
        }.start()
    }

    private fun leavePalPark() {
        if (palParkOnly) finish() else load()
    }

    private fun savePalPark(p: Page.PalPark) {
        if (saving) return
        saving = true
        Thread {
            val result = runCatching {
                SaveFileEdits.commitBoth(
                    this, prefs,
                    SaveFileEdits.Edit(p.gba.rom, p.gba.bytes, p.state.gba.editor.build()),
                    SaveFileEdits.Edit(p.ds.rom, p.ds.bytes, p.state.ds.editor.build()),
                )
            }.getOrElse { SaveFileEdits.Result.Failed(it.message ?: it.toString()) }
            runOnUiThread { saved(result) }
        }.start()
    }

    /**
     * MISSING: the other games' saves this one could get a Pokémon from (its own generation's, and a GBA game's for a DS
     * one through Pal Park), read off the UI thread, then what this Pokédex lacks.
     */
    private fun loadMissing(ready: Page.Ready) {
        val s = ready.state
        s.missing = com.pokedaisy.app.companion.ui.MissingView(null, emptyList())
        val target = TradeSide(ready.opened.name, s.editor, ready.opened.gameCode)
        val ds = ready.opened.ds != null
        Thread {
            val others = readableGames(except = rom).map { it.first }
                .filter { if (ds) true else !SaveFileEdits.isDsEditable(it) }
                .mapNotNull { f -> (open(f) as? Opened)?.let { f to it } }
            val sides = others.map { (_, o) -> TradeSide(o.name, o.editor(), o.gameCode) }
            val entries = runCatching { com.pokedaisy.app.companion.data.save.MissingDex.find(target, sides) }.getOrElse { emptyList() }
            runOnUiThread {
                missingRoms = others.map { it.first }
                if (s.missing != null) s.missing = com.pokedaisy.app.companion.ui.MissingView(entries, sides)
            }
            others.forEach { loadIcons(it.first) }
        }.start()
    }

    /** A MISSING row: the trade (or Pal Park) that brings it, with it picked on the other side. */
    private fun getMissing(ready: Page.Ready, o: com.pokedaisy.app.companion.data.save.MissingDex.Owned) {
        if (ready.state.changed) {
            ready.state.missing = null
            ready.state.notice = tr("Save your changes first, then bring it over")
            return
        }
        val src = missingRoms.getOrNull(o.source) ?: return
        if (o.palPark) {
            val at = (o.at as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.at ?: return
            openPalPark(src, rom, at)
        } else startTrade(src, pick = o.at)
    }

    override fun onResume() {
        super.onResume()
        OptionColors.inGame = false
    }

    private fun load() {
        page = Page.Loading
        Thread {
            val next = when (val o = open(rom)) {
                is Opened -> Page.Ready(PcBoxesState(o.editor()), o)
                else -> Page.Message(o as String)
            }
            runOnUiThread { page = next }
            loadIcons(rom)
        }.start()
    }

    /** [rom]'s save as an [Opened], or why there's none (a String). Blocking. */
    private fun open(rom: File): Any {
        if (SaveFileEdits.isDsEditable(rom)) return openDs(rom)
        val format = SaveFileEdits.formatFor(rom)
            ?: return tr("The save editor reads FireRed, LeafGreen, Emerald, Ruby, Sapphire, HeartGold, SoulSilver and Platinum saves (and builds that keep their save layout). This game's isn't one yet.")
        val save = SavesLocation.saveFor(this, prefs, rom)
        if (!save.isFile) return tr("No save yet - save once in the game first.")
        val bytes = runCatching { save.readBytes() }.getOrNull() ?: return tr("Can't read the save {0}", save.path)
        return when (val parsed = Gen3SaveFile.parse(bytes, format)) {
            is Gen3SaveFile.Parsed.Failed -> parsed.reason
            is Gen3SaveFile.Parsed.Ok -> {
                val why = Gen3SaveData(parsed.save).mismatch()
                if (why != null) tr("This save doesn't look like a {0} save ({1}), so it's left alone.", format.label, why)
                else Opened(rom, GameTitles.label(this, prefs, rom), RomIdentity.gameCode(rom).orEmpty(), bytes, parsed.save)
            }
        }
    }

    /** A HeartGold / SoulSilver or Platinum save as an [Opened] (the cart's own tables read for it), or why there's none. Blocking. */
    private fun openDs(rom: File): Any {
        val save = SavesLocation.saveFor(this, prefs, rom)
        if (!save.isFile) return tr("No save yet - save once in the game first.")
        val bytes = runCatching { save.readBytes() }.getOrNull() ?: return tr("Can't read the save {0}", save.path)
        val cart = RomArchive.playable(rom, cacheDir) ?: rom
        val game = runCatching { com.pokedaisy.app.companion.data.NdsArt.readSaveTables(cart.toOkioPath()) }.getOrNull()
            ?: return tr("The save editor reads FireRed, LeafGreen, Emerald, Ruby, Sapphire, HeartGold, SoulSilver and Platinum saves (and builds that keep their save layout). This game's isn't one yet.")
        return when (val parsed = com.pokedaisy.app.companion.data.save.Gen4SaveFile.parse(bytes)) {
            is com.pokedaisy.app.companion.data.save.Gen4SaveFile.Parsed.Failed -> parsed.reason
            is com.pokedaisy.app.companion.data.save.Gen4SaveFile.Parsed.Ok -> {
                val why = if (parsed.save.layout != game.layout) "it's a ${parsed.save.layout.label} save"
                    else com.pokedaisy.app.companion.data.save.Gen4SaveData(game, parsed.save.general, parsed.save.pc, parsed.save.layout).mismatch()
                if (why != null) tr("This save doesn't look like a {0} save ({1}), so it's left alone.", game.layout.label, why)
                else Opened(rom, GameTitles.label(this, prefs, rom), RomIdentity.ndsGameCode(rom).orEmpty(), bytes, null, parsed.save to game)
            }
        }
    }

    /** [rom]'s party icons from the ROM file, when it's one whose icon tables are known. Blocking; once per ROM. */
    private fun loadIcons(rom: File) {
        if (rom.absolutePath in icons) return
        val tables = RomMonIcons.tablesFor(RomIdentity.sha1(rom), RomIdentity.gameCode(rom), RomIdentity.revision(rom)) ?: return
        val file = RomArchive.playable(rom, cacheDir) ?: rom
        val source = RomMonIcons.open(file.toOkioPath(), tables) ?: return
        runOnUiThread { icons[rom.absolutePath] = MonIconLoader { source.get(it)?.bitmap() } }
    }

    private fun save(ready: Page.Ready) {
        if (saving) return
        saving = true
        Thread {
            val result = runCatching {
                SaveFileEdits.commit(this, prefs, rom, ready.opened.bytes, ready.state.editor.build())
            }.getOrElse { SaveFileEdits.Result.Failed(it.message ?: it.toString()) }
            runOnUiThread { saved(result) }
        }.start()
    }

    private fun saveTrade(t: Page.Trade) {
        if (saving) return
        saving = true
        Thread {
            val result = runCatching {
                SaveFileEdits.commitBoth(
                    this, prefs,
                    SaveFileEdits.Edit(t.a.rom, t.a.bytes, t.state.a.editor.build()),
                    SaveFileEdits.Edit(t.b.rom, t.b.bytes, t.state.b.editor.build()),
                )
            }.getOrElse { SaveFileEdits.Result.Failed(it.message ?: it.toString()) }
            runOnUiThread { saved(result) }
        }.start()
    }

    private fun saved(result: SaveFileEdits.Result) {
        saving = false
        when (result) {
            is SaveFileEdits.Result.Written -> {
                Toast.makeText(this, tr("Saved. The previous save is in {0}", result.backup.parentFile?.name ?: SaveBackups.DIR_NAME), Toast.LENGTH_LONG).show()
                // Further edits start from the files as written.
                val t = page as? Page.Trade
                val pp = page as? Page.PalPark
                when {
                    tradeOnly && t != null -> reopenTrade(t.a.rom, t.b.rom)
                    palParkOnly && pp != null -> openPalPark(pp.gba.rom, pp.ds.rom)
                    else -> load()
                }
            }
            is SaveFileEdits.Result.Failed -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
        }
    }

    /** TRADE: the library's other games whose saves the editor can read. */
    private fun pickPartner() {
        Thread {
            val list = readableGames(except = rom, with = rom)
            runOnUiThread {
                if (list.isEmpty()) Toast.makeText(this, tr("No other game here has a save the save editor can read"), Toast.LENGTH_LONG).show()
                else partners = list
            }
        }.start()
    }

    private fun startTrade(partner: File, pick: com.pokedaisy.app.companion.data.save.MonRef? = null) {
        val before = page
        val a = tradeFrom ?: (before as? Page.Ready)?.opened ?: return
        page = Page.Loading
        Thread {
            val next = when (val o = open(partner)) {
                is Opened -> {
                    val st = TradeState(TradeSide(a.name, a.editor(), a.gameCode), TradeSide(o.name, o.editor(), o.gameCode))
                    // From MISSING: the Pokémon to bring already picked on the other side.
                    pick?.let { st.selB = it; (it as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.let { pc -> st.boxB = pc.at.box } }
                    Page.Trade(st, a, o)
                }
                else -> null.also { runOnUiThread { Toast.makeText(this, o as String, Toast.LENGTH_LONG).show() } }
            }
            runOnUiThread {
                // An unreadable partner: TRADE SAVES asks again, the editor goes back to its game.
                if (next != null) page = next else if (tradeOnly) { page = Page.Picking; pickPartner() } else page = before
            }
            loadIcons(partner)
        }.start()
    }

    @Composable
    private fun Root() {
        val m = rememberGbaTextMetrics()
        when (val p = page) {
            is Page.Ready -> {
                val s = p.state
                // BACK closes what's open first: a pick-list or amount, a confirm, then a pending MOVE, then asks before dropping edits.
                BackHandler {
                    when {
                        s.editing != null -> s.editing = null
                        s.missing != null -> s.missing = null
                        s.confirmSave -> s.confirmSave = false
                        s.confirmDiscard -> s.confirmDiscard = false
                        s.detail != null -> s.detail = null
                        s.moving != null -> s.moving = null
                        s.changed -> s.confirmDiscard = true
                        else -> finish()
                    }
                }
                PcBoxesScreen(
                    s, gameName, m, onBack = { finish() }, onSave = { save(p) }, saving = saving,
                    icons = icons[rom.absolutePath], onTrade = { pickPartner() },
                    onMissing = { loadMissing(p) }, onGet = { getMissing(p, it) },
                )
            }
            is Page.Trade -> {
                val s = p.state
                BackHandler {
                    when {
                        s.evolving != null -> s.evolving = null
                        s.confirmSave -> s.confirmSave = false
                        s.confirmDiscard -> s.confirmDiscard = false
                        s.changed -> s.confirmDiscard = true
                        else -> leaveTrade()
                    }
                }
                TradeScreen(
                    s, m, onBack = { leaveTrade() }, onSave = { saveTrade(p) }, saving = saving,
                    iconsA = icons[p.a.rom.absolutePath], iconsB = icons[p.b.rom.absolutePath],
                )
            }
            is Page.PalPark -> {
                val s = p.state
                BackHandler {
                    when {
                        s.confirmSave -> s.confirmSave = false
                        s.confirmDiscard -> s.confirmDiscard = false
                        s.changed -> s.confirmDiscard = true
                        else -> leavePalPark()
                    }
                }
                com.pokedaisy.app.companion.ui.PalParkScreen(
                    s, m, onBack = { leavePalPark() }, onSave = { savePalPark(p) }, saving = saving,
                    iconsGba = icons[p.gba.rom.absolutePath], iconsDs = icons[p.ds.rom.absolutePath],
                )
            }
            Page.Loading -> MessagePage(tr("Reading the save…"), m)
            Page.Picking -> MessagePage("", m)
            is Page.Message -> MessagePage(p.text, m)
        }
        firstPick?.let { list ->
            OptionSelector(
                tk("TRADE FROM"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { firstPick = null; openTradeFrom(list[it].first) },
                onDismiss = { finish() },
            )
        }
        editPick?.let { list ->
            OptionSelector(
                tk("PICK A GAME"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { editPick = null; openGame(list[it].first) },
                onDismiss = { finish() },
            )
        }
        palFromPick?.let { list ->
            OptionSelector(
                tk("BRING FROM"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { palFromPick = null; openPalParkFrom(list[it].first) },
                onDismiss = { finish() },
            )
        }
        palToPick?.let { list ->
            OptionSelector(
                tk("BRING TO"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { palToPick = null; val from = palFrom; if (from != null) openPalPark(from.rom, list[it].first) },
                onDismiss = { palToPick = null; palFrom = null; pickPalParkFrom() },
            )
        }
        // The trade's other game: from TRADE SAVES BACK picks the first one again; in the editor it just closes.
        partners?.let { list ->
            OptionSelector(
                tk("TRADE WITH"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { partners = null; startTrade(list[it].first) },
                onDismiss = { partners = null; if (tradeOnly) { tradeFrom = null; pickFirst() } },
            )
        }
    }

    @Composable
    private fun MessagePage(text: String, m: com.pokedaisy.app.companion.ui.GbaTextMetrics) {
        BackHandler { finish() }
        Box(Modifier.fillMaxSize()) {
            AppBackdrop()
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OptionTitleWindow(when { tradeOnly -> tk("TRADE SAVES"); palParkOnly -> tk("PAL PARK"); else -> tk("SAVE EDITOR") }, m, trailing = gameName, onBack = { finish() })
                Spacer(Modifier.height(m.u * 4))
                // A pick-list over it (Picking): the title alone.
                if (text.isNotEmpty()) OptionListWindow(m, Modifier.fillMaxWidth()) {
                    GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4))
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROM = "rom"
        /** TOOLS > TRADE SAVES: no [EXTRA_ROM], the two games picked first, then the trade alone. */
        const val EXTRA_TRADE = "trade"
        /** TOOLS > SAVE EDITOR: no [EXTRA_ROM], the game picked first. */
        const val EXTRA_PICK = "pick"
        /** TOOLS > PAL PARK: no [EXTRA_ROM], the GBA game and the DS game picked, then Pal Park alone. */
        const val EXTRA_PAL_PARK = "pal_park"
    }
}
