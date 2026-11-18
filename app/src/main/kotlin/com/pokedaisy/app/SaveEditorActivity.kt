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
import com.pokedaisy.app.companion.ui.imageBitmap
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
        /** A Gen 3 save (the editor's every page, and TRADE), or [ds]'s HeartGold / SoulSilver / Platinum one, or [gen5]'s Black / White one. */
        val file: Gen3SaveFile?,
        val ds: Pair<com.pokedaisy.app.companion.data.save.Gen4SaveFile, com.pokedaisy.app.companion.data.save.Gen4GameData>? = null,
        val gen5: Pair<com.pokedaisy.app.companion.data.save.Gen5SaveFile, com.pokedaisy.app.companion.data.save.Gen5GameData>? = null,
    ) {
        fun editor(): com.pokedaisy.app.companion.data.save.MonSaveEditor = file?.let { Gen3SaveEditor(it) }
            ?: ds?.let { (f, g) -> com.pokedaisy.app.companion.data.save.Gen4SaveEditor(f, g) }
            ?: gen5!!.let { (f, g) -> com.pokedaisy.app.companion.data.save.Gen5SaveEditor(f, g) }
    }

    /** A Black / White (or sequel) save as read: POKé TRANSFER's receiving side. */
    private class OpenedGen5(
        val rom: File, val name: String, val bytes: ByteArray,
        val file: com.pokedaisy.app.companion.data.save.Gen5SaveFile, val game: com.pokedaisy.app.companion.data.save.Gen5GameData,
    ) {
        fun editor() = com.pokedaisy.app.companion.data.save.Gen5SaveEditor(file, game)
    }

    private sealed class Page {
        object Loading : Page()
        /** A pick-list is up (the game, or the trade's two): the title alone behind it. */
        object Picking : Page()
        class Message(val text: String) : Page()
        class Ready(val state: PcBoxesState, val opened: Opened) : Page()
        class Trade(val state: TradeState, val a: Opened, val b: Opened) : Page()
        class PalPark(val state: com.pokedaisy.app.companion.ui.PalParkState, val gba: Opened, val ds: Opened) : Page()
        class Collection(val state: com.pokedaisy.app.companion.ui.CollectionState) : Page()
        class Transfer(val state: com.pokedaisy.app.companion.ui.PokeTransferState, val gen4: Opened, val gen5: OpenedGen5) : Page()
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
    /** COLLECTION (TOOLS): every save's Pokémon; OPEN, TRADE and PAL PARK come back to it. */
    private var collectionOnly = false
    /** COLLECTION's saves as read, in the order its items name them. */
    private var collectionOpened: List<Opened> = emptyList()
    /** PAL PARK from COLLECTION: the GBA PC Pokémon to have picked once the DS game is chosen. */
    private var palPick: com.pokedaisy.app.companion.data.save.BoxSlot? = null
    /** POKé TRANSFER (TOOLS): the Gen 4 game picked, then the Gen 5 one, then the transfer alone. */
    private var transferOnly = false
    private var transferFromPick by mutableStateOf<List<Pair<File, String>>?>(null)
    private var transferToPick by mutableStateOf<List<Pair<File, String>>?>(null)
    private var transferFrom: File? = null
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
        collectionOnly = path == null && intent?.getBooleanExtra(EXTRA_COLLECTION, false) == true
        transferOnly = path == null && intent?.getBooleanExtra(EXTRA_POKE_TRANSFER, false) == true
        val pick = path == null && intent?.getBooleanExtra(EXTRA_PICK, false) == true
        if (path == null && !tradeOnly && !pick && !palParkOnly && !collectionOnly && !transferOnly) { finish(); return }
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
            collectionOnly -> openCollection()
            transferOnly -> pickTransferFrom()
            else -> pickToEdit()
        }
    }

    /** POKé TRANSFER: which HeartGold / SoulSilver / Platinum save to send from (then [pickTransferTo]). */
    private fun pickTransferFrom() {
        page = Page.Picking
        Thread {
            val from = readableGames(onlyDs = true)
            val to = gen5Games()
            runOnUiThread {
                if (from.isEmpty() || to.isEmpty()) page = Page.Message(tr("POKé TRANSFER needs Diamond, Pearl, HeartGold, SoulSilver or Platinum and Black, White, Black 2 or White 2, each with a save it can read, saved once in the game."))
                else transferFromPick = from
            }
        }.start()
    }

    /** The library's Black / White games (and sequels) with a save. Blocking. */
    private fun gen5Games(): List<Pair<File, String>> = RomFolder.libraryRoms(this, prefs)
        .filter { SaveFileEdits.isGen5(it) && SavesLocation.saveFor(this, prefs, it).isFile }
        .map { it to GameTitles.label(this, prefs, it) }

    private fun pickTransferTo() {
        Thread {
            val list = gen5Games()
            runOnUiThread { transferToPick = list }
        }.start()
    }

    /** A Black / White save as an [OpenedGen5] (the cart's names and personal records read for it), or why there's none. Blocking. */
    private fun openGen5(rom: File): Any {
        val save = SavesLocation.saveFor(this, prefs, rom)
        if (!save.isFile) return tr("No save yet - save once in the game first.")
        val bytes = runCatching { save.readBytes() }.getOrNull() ?: return tr("Can't read the save {0}", save.path)
        val cart = RomArchive.playable(rom, cacheDir) ?: rom
        val game = runCatching { com.pokedaisy.app.companion.data.NdsArt.readGen5SaveTables(cart.toOkioPath()) }.getOrNull()
            ?: return tr("POKé TRANSFER reads Black, White, Black 2 and White 2 saves. This game's isn't one.")
        return when (val parsed = com.pokedaisy.app.companion.data.save.Gen5SaveFile.parse(bytes)) {
            is com.pokedaisy.app.companion.data.save.Gen5SaveFile.Parsed.Failed -> parsed.reason
            is com.pokedaisy.app.companion.data.save.Gen5SaveFile.Parsed.Ok ->
                if (parsed.save.layout != game.layout) tr("This save doesn't look like a {0} save ({1}), so it's left alone.", game.layout.label, parsed.save.layout.label)
                else OpenedGen5(rom, GameTitles.label(this, prefs, rom), bytes, parsed.save, game)
        }
    }

    /** POKé TRANSFER from MISSING: BACK returns to the Black / White save's editor instead of leaving. */
    private var transferFromEditor = false

    /** POKé TRANSFER between [gen4Rom] and [gen5Rom], [pick] in the Gen 4 PC picked already; [back]: opened from the editor. */
    private fun openTransfer(gen4Rom: File, gen5Rom: File, pick: com.pokedaisy.app.companion.data.save.BoxSlot? = null, back: Boolean = false) {
        val before = page
        page = Page.Loading
        Thread {
            val a = open(gen4Rom)
            val b = openGen5(gen5Rom)
            runOnUiThread {
                page = if (a is Opened && b is OpenedGen5) {
                    transferFromEditor = back
                    val st = com.pokedaisy.app.companion.ui.PokeTransferState(
                        a.name, a.editor() as com.pokedaisy.app.companion.data.save.Gen4SaveEditor, b.name, b.editor(),
                    )
                    if (pick != null) { st.boxGen4 = pick.box; st.toggle(pick) }
                    Page.Transfer(st, a, b)
                } else if (back) {
                    Toast.makeText(this, (a as? String) ?: (b as String), Toast.LENGTH_LONG).show()
                    before
                } else Page.Message((a as? String) ?: (b as String))
            }
        }.start()
    }

    private fun leaveTransfer() {
        if (transferFromEditor) { transferFromEditor = false; load() } else finish()
    }

    private fun saveTransfer(p: Page.Transfer) {
        if (saving) return
        saving = true
        Thread {
            val result = runCatching {
                SaveFileEdits.commitBoth(
                    this, prefs,
                    SaveFileEdits.Edit(p.gen4.rom, p.gen4.bytes, p.state.gen4.build()),
                    SaveFileEdits.Edit(p.gen5.rom, p.gen5.bytes, p.state.gen5.build()),
                )
            }.getOrElse { SaveFileEdits.Result.Failed(it.message ?: it.toString()) }
            runOnUiThread { saved(result) }
        }.start()
    }

    /** COLLECTION: every readable save read off the UI thread, then their Pokémon in one list. */
    private fun openCollection() {
        val st = com.pokedaisy.app.companion.ui.CollectionState()
        (page as? Page.Collection)?.state?.let { old -> st.query = old.query; st.shinyOnly = old.shinyOnly; st.sort = old.sort; st.game = old.game }
        page = Page.Collection(st)
        tradeFrom = null
        palFrom = null
        palPick = null
        Thread {
            val opened = readableGames(gen5 = true).mapNotNull { (f, _) -> open(f) as? Opened }
            val sides = opened.map { TradeSide(it.name, it.editor(), it.gameCode) }
            runOnUiThread { collectionOpened = opened; st.load(sides) }
            opened.forEach { loadIcons(it.rom) }
        }.start()
    }

    /** Back from a page COLLECTION opened: to COLLECTION, read again (a save may have changed). False when not from it. */
    private fun backToCollection(): Boolean {
        if (!collectionOnly) return false
        openCollection()
        return true
    }

    private fun collectionOpen(item: com.pokedaisy.app.companion.data.save.SaveCollection.Item) {
        val o = collectionOpened.getOrNull(item.source) ?: return
        rom = o.rom
        gameName = o.name
        load(focus = item.at)
    }

    private fun collectionTrade(item: com.pokedaisy.app.companion.data.save.SaveCollection.Item) {
        val o = collectionOpened.getOrNull(item.source) ?: return
        rom = o.rom
        gameName = o.name
        tradeFrom = o
        collectionTradePick = item.at
        pickPartner()
    }

    private fun collectionPalPark(item: com.pokedaisy.app.companion.data.save.SaveCollection.Item) {
        val o = collectionOpened.getOrNull(item.source) ?: return
        palFrom = o
        palPick = (item.at as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.at
        pickPalParkTo()
    }

    /** COLLECTION's TRADE: the Pokémon to have picked on its own side once the other game is chosen. */
    private var collectionTradePick: com.pokedaisy.app.companion.data.save.MonRef? = null

    /** SAVE EDITOR from TOOLS: which game's save to open. */
    private fun pickToEdit() {
        page = Page.Picking
        Thread {
            val list = readableGames(gen5 = true)
            runOnUiThread {
                if (list.isEmpty()) page = Page.Message(tr("SAVE EDITOR needs a game with a save it can read: FireRed, LeafGreen, Emerald, Ruby, Sapphire, HeartGold, SoulSilver, Platinum, Black, White, Black 2 or White 2, saved once in the game."))
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
        if (backToCollection()) return
        if (tradeOnly) finish() else load()
    }

    private fun openGame(f: File) {
        rom = f
        gameName = GameTitles.label(this, prefs, rom)
        load()
    }

    /** The library's games with a save the editor can read; a trade's partner ([with]): one of the same generation. Blocking. */
    private fun readableGames(
        except: File? = null, with: File? = null, onlyDs: Boolean? = null,
        /** Black / White and their sequels too (the editor and COLLECTION; they don't trade here yet). */
        gen5: Boolean = false,
    ): List<Pair<File, String>> {
        // A Black / White save trades with the others of its generation alone.
        if (with != null && SaveFileEdits.isGen5(with)) {
            return RomFolder.libraryRoms(this, prefs)
                .filter { it.absolutePath != except?.absolutePath && SaveFileEdits.isGen5(it) && SavesLocation.saveFor(this, prefs, it).isFile }
                .map { it to GameTitles.label(this, prefs, it) }
        }
        val ds = onlyDs ?: with?.let { SaveFileEdits.isDsEditable(it) }
        return RomFolder.libraryRoms(this, prefs)
            .filter {
                it.absolutePath != except?.absolutePath &&
                    when (ds) {
                        null -> SaveFileEdits.formatFor(it) != null || SaveFileEdits.isDsEditable(it) || (gen5 && SaveFileEdits.isGen5(it))
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
            val list = readableGames(gen5 = true)
            runOnUiThread {
                if (list.size < 2) page = Page.Message(tr("TRADE SAVES needs two games with a save it can read, each saved once in the game: FireRed, LeafGreen, Emerald, Ruby and Sapphire trade with each other, Diamond, Pearl, Platinum, HeartGold and SoulSilver with each other, Black, White, Black 2 and White 2 with each other."))
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
                if (gba.isEmpty() || ds.isEmpty()) page = Page.Message(tr("PAL PARK needs a GBA game (FireRed, LeafGreen, Emerald, Ruby or Sapphire) and Diamond, Pearl, HeartGold, SoulSilver or Platinum, each with a save it can read, saved once in the game."))
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
        if (backToCollection()) return
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
        val gen5 = ready.opened.gen5 != null
        Thread {
            // A Black / White save: the HeartGold / SoulSilver / Platinum saves (Poké Transfer) and the other Gen 5 ones (trades).
            val others = readableGames(except = rom, gen5 = gen5).map { it.first }
                .filter { if (gen5) SaveFileEdits.isDsEditable(it) || SaveFileEdits.isGen5(it) else if (ds) true else !SaveFileEdits.isDsEditable(it) }
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
        when (o.way) {
            com.pokedaisy.app.companion.data.save.MissingDex.Way.PAL_PARK -> {
                val at = (o.at as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.at ?: return
                openPalPark(src, rom, at)
            }
            // This save's own Pokémon, picked on this side: TRADE AND BACK is then one press.
            com.pokedaisy.app.companion.data.save.MissingDex.Way.TRADE_BACK -> startTrade(src, pickA = o.at)
            com.pokedaisy.app.companion.data.save.MissingDex.Way.POKE_TRANSFER -> {
                val at = (o.at as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.at ?: return
                openTransfer(src, rom, pick = at, back = true)
            }
            else -> startTrade(src, pick = o.at)
        }
    }

    override fun onResume() {
        super.onResume()
        OptionColors.inGame = false
    }

    private fun load(focus: com.pokedaisy.app.companion.data.save.MonRef? = null) {
        page = Page.Loading
        Thread {
            val next = when (val o = open(rom)) {
                is Opened -> Page.Ready(PcBoxesState(o.editor()).also { st ->
                    // From COLLECTION: on the Pokémon picked there.
                    focus?.let { at -> st.selected = at; (at as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.let { st.box = it.at.box } }
                }, o)
                else -> Page.Message(o as String)
            }
            runOnUiThread { page = next }
            loadIcons(rom)
        }.start()
    }

    /** [rom]'s save as an [Opened], or why there's none (a String). Blocking. */
    private fun open(rom: File): Any {
        if (SaveFileEdits.isDsEditable(rom)) return openDs(rom)
        if (SaveFileEdits.isGen5(rom)) return openGen5Editor(rom)
        val format = SaveFileEdits.formatFor(rom)
            ?: return tr("The save editor reads FireRed, LeafGreen, Emerald, Ruby, Sapphire, Diamond, Pearl, HeartGold, SoulSilver and Platinum saves (and builds that keep their save layout). This game's isn't one yet.")
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
            ?: return tr("The save editor reads FireRed, LeafGreen, Emerald, Ruby, Sapphire, Diamond, Pearl, HeartGold, SoulSilver and Platinum saves (and builds that keep their save layout). This game's isn't one yet.")
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

    /** A Black / White (or sequel) save for the editor's pages, or why there's none. Blocking. */
    private fun openGen5Editor(rom: File): Any = when (val o = openGen5(rom)) {
        is OpenedGen5 -> o.file.let { f ->
            com.pokedaisy.app.companion.data.save.Gen5SaveData(o.game, f.data).mismatch()
                ?.let { why -> tr("This save doesn't look like a {0} save ({1}), so it's left alone.", o.game.layout.label, why) }
                ?: Opened(rom, o.name, RomIdentity.ndsGameCode(rom).orEmpty(), o.bytes, null, gen5 = f to o.game)
        }
        else -> o
    }

    /** [rom]'s party icons from the ROM file: a DS cart's own archive (NdsArt.monIcons), a GBA ROM's tables when they're
     * known and check out (RomMonIcons). Blocking; once per ROM. */
    private fun loadIcons(rom: File) {
        if (rom.absolutePath in icons) return
        val loader = if (RomIdentity.isNds(rom)) {
            val cart = RomArchive.playable(rom, cacheDir) ?: rom
            val source = runCatching { com.pokedaisy.app.companion.data.NdsArt.monIcons(cart.toOkioPath()) }.getOrNull() ?: return
            MonIconLoader { species, egg -> source.get(species, egg)?.imageBitmap() }
        } else {
            val tables = RomMonIcons.tablesFor(RomIdentity.sha1(rom), RomIdentity.gameCode(rom), RomIdentity.revision(rom)) ?: return
            val file = RomArchive.playable(rom, cacheDir) ?: rom
            val source = RomMonIcons.open(file.toOkioPath(), tables) ?: return
            MonIconLoader { species, egg ->
                source.get(if (egg) com.pokedaisy.app.companion.data.save.SPECIES_EGG else species)?.imageBitmap()
            }
        }
        runOnUiThread { icons[rom.absolutePath] = loader }
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
                val tp = page as? Page.Transfer
                when {
                    tp != null -> openTransfer(tp.gen4.rom, tp.gen5.rom, back = transferFromEditor)
                    (tradeOnly || collectionOnly) && t != null -> reopenTrade(t.a.rom, t.b.rom)
                    (palParkOnly || collectionOnly) && pp != null -> openPalPark(pp.gba.rom, pp.ds.rom)
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

    private fun startTrade(
        partner: File, pick: com.pokedaisy.app.companion.data.save.MonRef? = null, pickA: com.pokedaisy.app.companion.data.save.MonRef? = null,
    ) {
        val before = page
        val a = tradeFrom ?: (before as? Page.Ready)?.opened ?: return
        page = Page.Loading
        Thread {
            val next = when (val o = open(partner)) {
                is Opened -> {
                    val st = TradeState(TradeSide(a.name, a.editor(), a.gameCode), TradeSide(o.name, o.editor(), o.gameCode))
                    // From MISSING: the Pokémon to bring already picked on the other side.
                    pick?.let { st.selB = it; (it as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.let { pc -> st.boxB = pc.at.box } }
                    pickA?.let { st.selA = it; (it as? com.pokedaisy.app.companion.data.save.MonRef.Pc)?.let { pc -> st.boxA = pc.at.box } }
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
                        else -> if (!backToCollection()) finish()
                    }
                }
                PcBoxesScreen(
                    s, gameName, m, onBack = { if (!backToCollection()) finish() }, onSave = { save(p) }, saving = saving,
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
            is Page.Collection -> {
                BackHandler { finish() }
                com.pokedaisy.app.companion.ui.CollectionScreen(
                    p.state, m, onBack = { finish() },
                    icons = { i -> collectionOpened.getOrNull(i)?.let { icons[it.rom.absolutePath] } },
                    onOpen = { collectionOpen(it) }, onTrade = { collectionTrade(it) }, onPalPark = { collectionPalPark(it) },
                )
            }
            is Page.Transfer -> {
                val s = p.state
                BackHandler {
                    when {
                        s.confirmSave -> s.confirmSave = false
                        s.confirmDiscard -> s.confirmDiscard = false
                        s.changed -> s.confirmDiscard = true
                        else -> leaveTransfer()
                    }
                }
                com.pokedaisy.app.companion.ui.PokeTransferScreen(s, m, onBack = { leaveTransfer() }, onSave = { saveTransfer(p) }, saving = saving)
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
                onPick = { palToPick = null; val from = palFrom; if (from != null) openPalPark(from.rom, list[it].first, palPick) },
                onDismiss = { palToPick = null; palFrom = null; if (collectionOnly) palPick = null else pickPalParkFrom() },
            )
        }
        transferFromPick?.let { list ->
            OptionSelector(
                tk("SEND FROM"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { transferFromPick = null; transferFrom = list[it].first; pickTransferTo() },
                onDismiss = { finish() },
            )
        }
        transferToPick?.let { list ->
            OptionSelector(
                tk("SEND TO"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { transferToPick = null; transferFrom?.let { from -> openTransfer(from, list[it].first) } },
                onDismiss = { transferToPick = null; transferFrom = null; pickTransferFrom() },
            )
        }
        // The trade's other game: from TRADE SAVES BACK picks the first one again; in the editor it just closes.
        partners?.let { list ->
            OptionSelector(
                tk("TRADE WITH"), list.indices.toList(), -1, { list[it].second }, m,
                onPick = { partners = null; startTrade(list[it].first, pickA = collectionTradePick); collectionTradePick = null },
                onDismiss = {
                    partners = null
                    if (collectionOnly) { tradeFrom = null; collectionTradePick = null } else if (tradeOnly) { tradeFrom = null; pickFirst() }
                },
            )
        }
    }

    @Composable
    private fun MessagePage(text: String, m: com.pokedaisy.app.companion.ui.GbaTextMetrics) {
        BackHandler { finish() }
        Box(Modifier.fillMaxSize()) {
            AppBackdrop()
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OptionTitleWindow(when { tradeOnly -> tk("TRADE SAVES"); palParkOnly -> tk("PAL PARK"); collectionOnly -> tk("COLLECTION"); transferOnly -> tk("POKé TRANSFER"); else -> tk("SAVE EDITOR") }, m, trailing = gameName, onBack = { finish() })
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
        /** TOOLS > COLLECTION: no [EXTRA_ROM], every save's Pokémon in one list, OPEN / TRADE / PAL PARK from it. */
        const val EXTRA_COLLECTION = "collection"
        /** TOOLS > POKé TRANSFER: no [EXTRA_ROM], the HeartGold / SoulSilver / Platinum game then the Black / White one, then the transfer alone. */
        const val EXTRA_POKE_TRANSFER = "poke_transfer"
    }
}
