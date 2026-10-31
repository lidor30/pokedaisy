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
import com.pokedaisy.app.companion.ui.rememberGbaTextMetrics
import com.pokedaisy.app.companion.ui.theme.AppTheme
import java.io.File

/**
 * A game's PC BOXES (its library menu), and TRADE with another game's save from there: the save files read and
 * edited while the games are closed - the library is in front, so a game's engine has stopped and written its save,
 * and it reloads the file when it starts again. Reading and writing run off the UI thread.
 */
class SaveEditorActivity : ComponentActivity() {

    /** A game's save as read: what the editor edits, and the bytes the file must still hold when it's written. */
    private class Opened(val rom: File, val name: String, val gameCode: String, val bytes: ByteArray, val file: Gen3SaveFile)

    private sealed class Page {
        object Loading : Page()
        class Message(val text: String) : Page()
        class Ready(val state: PcBoxesState, val opened: Opened) : Page()
        class Trade(val state: TradeState, val a: Opened, val b: Opened) : Page()
    }

    private lateinit var prefs: Prefs
    private lateinit var rom: File
    private var gameName = ""
    private var page by mutableStateOf<Page>(Page.Loading)
    private var saving by mutableStateOf(false)
    /** TRADE's pick-list: the other games with a save it can read. */
    private var partners by mutableStateOf<List<Pair<File, String>>?>(null)
    /** Icons per ROM path, once loaded (absent = none for that ROM). */
    private val icons = mutableStateMapOf<String, MonIconLoader>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        com.pokedaisy.app.companion.i18n.L10n.apply(prefs.appLanguage, null)
        val path = intent?.getStringExtra(EXTRA_ROM)
        // Off unless FeatureFlags turns it on: nothing reaches the save editor without it, not just the menu row.
        if (path == null || !FeatureFlags.saveEditor(this)) { finish(); return }
        rom = File(path)
        gameName = GameTitles.label(this, prefs, rom)
        setContent { AppTheme { com.pokedaisy.app.companion.ui.CompactTextOnSmallWindows { Root() } } }
        load()
    }

    override fun onResume() {
        super.onResume()
        OptionColors.inGame = false
    }

    private fun load() {
        page = Page.Loading
        Thread {
            val next = when (val o = open(rom)) {
                is Opened -> Page.Ready(PcBoxesState(Gen3SaveEditor(o.file)), o)
                else -> Page.Message(o as String)
            }
            runOnUiThread { page = next }
            loadIcons(rom)
        }.start()
    }

    /** [rom]'s save as an [Opened], or why there's none (a String). Blocking. */
    private fun open(rom: File): Any {
        val format = SaveFileEdits.formatFor(rom)
            ?: return tr("PC BOXES reads FireRed, LeafGreen, Emerald, Ruby and Sapphire saves (and builds that keep their save layout). This game's isn't one yet.")
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

    /** [rom]'s party icons from the ROM file, when it's one whose icon tables are known. Blocking; once per ROM. */
    private fun loadIcons(rom: File) {
        if (rom.absolutePath in icons) return
        val tables = RomMonIcons.tablesFor(RomIdentity.sha1(rom), RomIdentity.gameCode(rom), RomIdentity.revision(rom)) ?: return
        val file = RomArchive.playable(rom, cacheDir) ?: rom
        val source = RomMonIcons.open(file, tables) ?: return
        runOnUiThread { icons[rom.absolutePath] = MonIconLoader { source.get(it) } }
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
                load()   // further edits start from the file as written
            }
            is SaveFileEdits.Result.Failed -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
        }
    }

    /** TRADE: the library's other games whose saves PC BOXES can read. */
    private fun pickPartner() {
        Thread {
            val list = RomFolder.libraryRoms(this, prefs)
                .filter { it.absolutePath != rom.absolutePath && SaveFileEdits.formatFor(it) != null && SavesLocation.saveFor(this, prefs, it).isFile }
                .map { it to GameTitles.label(this, prefs, it) }
            runOnUiThread {
                if (list.isEmpty()) Toast.makeText(this, tr("No other game here has a save PC BOXES can read"), Toast.LENGTH_LONG).show()
                else partners = list
            }
        }.start()
    }

    private fun startTrade(partner: File) {
        val ready = page as? Page.Ready ?: return
        page = Page.Loading
        Thread {
            val next = when (val o = open(partner)) {
                is Opened -> {
                    val a = ready.opened
                    Page.Trade(TradeState(TradeSide(a.name, Gen3SaveEditor(a.file), a.gameCode), TradeSide(o.name, Gen3SaveEditor(o.file), o.gameCode)), a, o)
                }
                else -> null.also { runOnUiThread { Toast.makeText(this, o as String, Toast.LENGTH_LONG).show() } }
            }
            runOnUiThread { page = next ?: ready }
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
                        partners != null -> partners = null
                        s.editing != null -> s.editing = null
                        s.confirmSave -> s.confirmSave = false
                        s.confirmDiscard -> s.confirmDiscard = false
                        s.moving != null -> s.moving = null
                        s.changed -> s.confirmDiscard = true
                        else -> finish()
                    }
                }
                PcBoxesScreen(
                    s, gameName, m, onBack = { finish() }, onSave = { save(p) }, saving = saving,
                    icons = icons[rom.absolutePath], onTrade = { pickPartner() },
                )
                partners?.let { list ->
                    OptionSelector(
                        tk("TRADE WITH"), list.indices.toList(), -1, { list[it].second }, m,
                        onPick = { partners = null; startTrade(list[it].first) },
                        onDismiss = { partners = null },
                    )
                }
            }
            is Page.Trade -> {
                val s = p.state
                BackHandler {
                    when {
                        s.evolving != null -> s.evolving = null
                        s.confirmSave -> s.confirmSave = false
                        s.confirmDiscard -> s.confirmDiscard = false
                        s.changed -> s.confirmDiscard = true
                        else -> load()
                    }
                }
                TradeScreen(
                    s, m, onBack = { load() }, onSave = { saveTrade(p) }, saving = saving,
                    iconsA = icons[p.a.rom.absolutePath], iconsB = icons[p.b.rom.absolutePath],
                )
            }
            Page.Loading -> MessagePage(tr("Reading the save…"), m)
            is Page.Message -> MessagePage(p.text, m)
        }
    }

    @Composable
    private fun MessagePage(text: String, m: com.pokedaisy.app.companion.ui.GbaTextMetrics) {
        BackHandler { finish() }
        Box(Modifier.fillMaxSize()) {
            AppBackdrop()
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OptionTitleWindow(tk("PC BOXES"), m, trailing = gameName, onBack = { finish() })
                Spacer(Modifier.height(m.u * 4))
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(m.u * 4))
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROM = "rom"
    }
}
