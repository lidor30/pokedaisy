@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package render

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.LibraryActivity
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.SettingsActivity
import com.pokedaisy.app.overlay.BuiltInFrames
import com.pokedaisy.app.overlay.Box
import com.pokedaisy.app.overlay.OverlayChoice
import com.pokedaisy.app.overlay.OverlayGeometry
import com.pokedaisy.app.overlay.OverlayStore
import com.pokedaisy.app.overlay.OverlayWindow
import com.pokedaisy.app.overlay.Sprite
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.pokedaisy.app.SetupState
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.BattleInput
import com.pokedaisy.app.companion.StateSlots
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.CardStyle
import com.pokedaisy.app.companion.data.TrainerCardArt
import com.pokedaisy.app.companion.data.TrainerCardInfo
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.MoveVsFoe
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.POCKET_ITEMS
import com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS
import com.pokedaisy.app.companion.data.POCKET_POKE_BALLS
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.MemoryReader
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.readSaveProgress
import com.pokedaisy.app.companion.data.TypeMatchup
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.expProgress
import com.pokedaisy.app.companion.data.gameCase
import com.pokedaisy.app.companion.data.lookupLocation
import com.pokedaisy.app.companion.data.speciesName
import com.pokedaisy.app.companion.data.TYPE_NONE
import com.pokedaisy.app.companion.data.typeIdOf
import com.pokedaisy.app.companion.data.typeMatchups
import com.pokedaisy.app.companion.data.withMovesVs
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RomRegionMap
import com.pokedaisy.app.companion.data.activeRegionMap
import com.pokedaisy.app.companion.BatteryStatus
import com.pokedaisy.app.companion.DeviceBattery
import com.pokedaisy.app.companion.ui.CompanionScreen
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private val scratch = File(System.getProperty("scratch") ?: "build/scratch").apply { mkdirs() }

/** Slots 0-2 saved (with a stand-in screenshot), the rest empty; slot 1 current. */
class FakeSlots : StateSlots {
    private val thumbs = File(scratch, "thumbs").apply { mkdirs() }

    init {
        // Any 3:2 picture will do for a thumbnail: a party backdrop RomArt rebuilt (romArt()),
        // scaled like a real one (160 wide), else plain stripes.
        val src = sequenceOf("partybg/hns.png", "partybg/firered.png", "partybg/emerald.png")
            .firstNotNullOfOrNull { RomArt.file(android.content.Context().filesDir, it) }?.let { ImageIO.read(it) }
            ?: BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until 160) for (x in 0 until 240) setRGB(x, y, if (y / 2 % 2 == 0) 0xB5B5B5 else 0xA5A5A5)
            }
        val thumb = BufferedImage(160, 106, BufferedImage.TYPE_INT_RGB)
        thumb.createGraphics().apply { drawImage(src, 0, 0, 160, 106, null); dispose() }
        for (i in 0..2) ImageIO.write(thumb, "png", File(thumbs, "ss$i.png"))
    }

    override fun list() = (0..9).map { i ->
        val f = File(thumbs, "ss$i.png")
        StateSlots.Slot(i, f.isFile, 1_790_000_000_000L + i * 3_600_000L, f.takeIf { it.isFile }?.absolutePath)
    }
    override val currentIndex = 1
    override fun requestSave(index: Int) {}
    override fun requestLoad(index: Int) {}
    override fun requestUndoSave() {}
    override fun requestUndoLoad() {}
}

class FakeSettings(showHintsInitially: Boolean = true, initialTabs: List<String>? = null) : CompanionSettings {
    private var ffSpeed = 4f
    private var ff = false
    private var music = com.pokedaisy.app.companion.FfMusicMode.STEADY
    private var touch = 0
    private var hints = showHintsInitially
    override val ffMaxSpeed get() = ffSpeed
    override fun setFfMaxSpeed(v: Float) { ffSpeed = v }
    override val ffToggled get() = ff
    override fun setFfToggled(on: Boolean) { ff = on }
    override val ffMusicMode get() = music
    override fun setFfMusicMode(mode: com.pokedaisy.app.companion.FfMusicMode) { music = mode }
    private var ffModeValue = com.pokedaisy.app.companion.FfMode.SMART
    override val ffMode get() = ffModeValue
    override fun setFfMode(mode: com.pokedaisy.app.companion.FfMode) { ffModeValue = mode }
    override val touchControlsMode get() = touch
    override fun setTouchControlsMode(v: Int) { touch = v }
    override fun gbaControlBindings() = GbaControls.Btn.entries.associateWith {
        listOf(when (it) { GbaControls.Btn.START2 -> "BUTTON_X"; GbaControls.Btn.SELECT2 -> "BUTTON_Y"; else -> "BUTTON_${it.name}" })
    }
    override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {}
    private var hotkeysOn = true
    override val hotkeysEnabled get() = hotkeysOn
    override fun setHotkeysEnabled(on: Boolean) { hotkeysOn = on }
    // SAVE STATE on X, so picking X elsewhere shows the KEY IN USE confirm.
    override fun hotkeyBindings() = Hotkeys.Action.entries.associateWith { if (it == Hotkeys.Action.SAVE_STATE) listOf("BUTTON_X") else listOf("BUTTON_Y") }
    override fun setHotkeyBinding(action: Hotkeys.Action, keyName: String?, takeFrom: List<Hotkeys.Action>) {}
    override fun restartGame() {}
    override fun closeGame() {}
    override val canCloseCompanion = true   // the Thor: two screens, so SETTINGS shows CLOSE COMPANION
    override val showHints get() = hints
    override fun setShowHints(on: Boolean) { hints = on }
    private var click = true
    override val clickSound get() = click
    override fun setClickSound(on: Boolean) { click = on }
    private var bar = false
    override val statusBar get() = bar
    override fun setStatusBar(on: Boolean) { bar = on }
    private var stretch = false
    override val stretchGame get() = stretch
    override fun setStretchGame(on: Boolean) { stretch = on }
    private var colors = false
    override val gbaColors get() = colors
    override fun setGbaColors(on: Boolean) { colors = on }
    private var filter = com.pokedaisy.app.companion.ScreenFilter.NONE
    override val screenFilter get() = filter
    override fun setScreenFilter(filter: com.pokedaisy.app.companion.ScreenFilter) { this.filter = filter }
    private var grid = com.pokedaisy.app.companion.GridStrength.MEDIUM
    override val gridStrength get() = grid
    override fun setGridStrength(strength: com.pokedaisy.app.companion.GridStrength) { grid = strength }
    private var overlayKey = ""
    override val overlayChoices get() = listOf("" to "NONE", "builtin:DAISY" to "DAISY", "builtin:BEZEL" to "BEZEL", "user:wood-frame" to "WOOD FRAME")
    override val overlay get() = overlayKey
    override fun setOverlay(key: String) { overlayKey = key }
    private var onCompanion = true
    override val companionShaders get() = onCompanion
    override fun setCompanionShaders(on: Boolean) { onCompanion = on }
    // The Thor: SWAP SCREENS shows.
    override val hasSecondScreen = true
    private var swap = false
    override val swapScreens get() = swap
    override fun setSwapScreens(on: Boolean) { swap = on }
    // SETTINGS > CHEATS: three cheats, one of them on; the master switch on.
    private var cheatsOn = true
    private var cheatList = previewCheats()
    override val cheats get() = cheatList
    override val cheatsEnabled get() = cheatsOn
    override fun setCheatsEnabled(on: Boolean) { cheatsOn = on }
    override fun setCheatEnabled(index: Int, on: Boolean) {
        cheatList = cheatList.toMutableList().also { it[index] = it[index].copy(enabled = on) }
    }
    override val gameName = "Pokémon FireRed"
    override val romFileName = "firered-qol.gba"
    private var tabs = initialTabs ?: com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS
    override val companionTabs get() = tabs
    override fun setCompanionTabs(tabs: List<String>) { this.tabs = tabs }
    override fun guideNoticeAccepted(game: String) = true
    override fun acceptGuideNotice(game: String) {}
}

/** `-PmonIcons` icon sheets, relative to the app's assets dir (the render working dir); null when unset. */
fun monIcon(species: Int): String? =
    System.getProperty("monIcons").orEmpty().takeIf { it.isNotBlank() }?.let { "$it/$species.png" }

fun snapshot() = SnapshotView(
    connected = true, facingDirection = "down", x = 12, y = 7, game = activeGame,
    // Highlight in the region-map image's own 240x160 coordinates.
    // MAPSEC 0x58 (Pallet Town in FireRed) through the data layer, so a game
    // without region data (Odyssey) renders what it really would.
    location = lookupLocation(0x58), regionMapSectionId = 0x58,
    party = listOf(
        MonView(
            4, "CHARMANDER", 12, 30, 35, "", listOf("Fire"), null, GENDER_SYMBOL_MALE,
            moves = listOf(
                MoveView("Scratch", "Normal", 35, 40), MoveView("Growl", "Normal", 40, 0),
                MoveView("Ember", "Fire", 25, 40), MoveView("Metal Claw", "Steel", 35, 50),
            ),
            weaknesses = listOf(TypeMatchup("Water", "2x", 200), TypeMatchup("Ground", "2x", 200), TypeMatchup("Rock", "2x", 200)),
            resistances = listOf(
                TypeMatchup("Fire", "½x", 50), TypeMatchup("Grass", "½x", 50), TypeMatchup("Ice", "½x", 50),
                TypeMatchup("Bug", "½x", 50), TypeMatchup("Steel", "½x", 50),
            ),
        ),
        MonView(
            16, "PIDGEY", 9, 5, 26, "PSN", listOf("Normal", "Flying"), null, GENDER_SYMBOL_FEMALE,
            moves = listOf(MoveView("Tackle", "Normal", 35, 35), MoveView("Sand-Attack", "Ground", 15, 0), MoveView("Gust", "Flying", 35, 40)),
            weaknesses = listOf(TypeMatchup("Electric", "2x", 200), TypeMatchup("Ice", "2x", 200), TypeMatchup("Rock", "2x", 200)),
            resistances = listOf(TypeMatchup("Grass", "½x", 50), TypeMatchup("Bug", "½x", 50)),
            immunities = listOf(TypeMatchup("Ground", "0x", 0), TypeMatchup("Ghost", "0x", 0)),
        ),
    // Names through the data layer, so they carry the game's own casing.
    ).map { mon ->
        mon.copy(
            name = speciesName(mon.species), iconAsset = monIcon(mon.species),
            exp = expProgress(mon.species, mon.level, if (mon.species == 4) 1_100L else 500L),
            moves = mon.moves.map { it.copy(name = gameCase(it.name)) },
        )
    },
    items = listOf(
        ItemView(13, "Potion", 3, null, POCKET_ITEMS, "A small spray that heals a little HP (preview text)."),
        ItemView(4, "Poké Ball", 10, null, POCKET_POKE_BALLS),
        ItemView(349, "Oak's Parcel", 1, null, POCKET_KEY_ITEMS),
    ),
)

class Shot(
    val name: String,
    val w: Int,
    val h: Int,
    val density: Float,
    val content: () -> (@Composable () -> Unit),
    val action: SkikoComposeUiTest.() -> Unit = {},
)

/** The device's BACK for the shot being rendered; the `*-back` shots press it. */
var previewBack = com.pokedaisy.app.companion.ui.CompanionBack()

fun SkikoComposeUiTest.pressBack() {
    runOnIdle { previewBack.back() }
    waitForIdle()
}

fun companion(
    tab: String,
    mon: Int? = null,
    busy: Boolean = false,
    hints: Boolean = true,
    battery: BatteryStatus = BatteryStatus(76, charging = false),
    cardBack: Boolean = false,
    snap: () -> SnapshotView = ::snapshot,
): () -> (@Composable () -> Unit) = {
    DeviceBattery.status.value = battery
    val sv = snap()
    val back = com.pokedaisy.app.companion.ui.CompanionBack().also { previewBack = it }
    val content: @Composable () -> Unit = {
        CompanionScreen(sv, FakeSlots(), FakeSettings(hints), FakeBattleInput(busy), initialTab = tab, initialMonIndex = mon, initialCardBack = cardBack, back = back)
    }
    content
}

class FakeBattleInput(override val busy: Boolean) : BattleInput {
    override fun selectAction(actionIndex: Int) {}
    override fun selectMove(moveIndex: Int) {}
    override fun back() {}
    // Personalities are faked in party() below, so the POKéMON pane renders like FireRed's / Emerald's.
    override val canSwitch get() = true
}

/** The TRAINER CARD: FireRed's (and Unbound's) for a boy with every badge and the
 * Hall of Fame done, Emerald's for a girl partway through, so both looks get a
 * shot. Unbound's player pics come from its ROM: -Pgame=UNBOUND -Prom=<rom>. */
fun card(): SnapshotView {
    val s = snapshot()
    val kanto = activeGame == GameKind.FIRERED || activeGame == GameKind.UNBOUND
    return s.copy(
        trainerCard = TrainerCardInfo(
            style = when (activeGame) {
                GameKind.FIRERED -> CardStyle.KANTO
                GameKind.UNBOUND -> CardStyle.UNBOUND
                GameKind.GLAZED -> CardStyle.GLAZED
                GameKind.IMPERIUM -> CardStyle.IMPERIUM
                else -> CardStyle.HOENN
            },
            name = TrainerCardArt.enc(if (activeGame == GameKind.UNBOUND) "Kai" else if (kanto) "RED" else "MAY"),
            female = !kanto,
            trainerId = if (kanto) 24601 else 31415,
            hours = if (kanto) 48 else 12, minutes = if (kanto) 14 else 5,
            money = if (kanto) 224300 else 3141,
            dexCaught = if (kanto) 151 else 42,
            badges = if (kanto) 0xFF else 0x1F,
            stars = if (kanto) 1 else 0,
            hofDebut = if (kanto) (45 shl 16) or (20 shl 8) or 53 else 0,
            trades = 3,
            linkWins = if (kanto) 0 else 2, linkLosses = if (kanto) 0 else 1,
            stickers = if (kanto) listOf(1, 0, 0) else emptyList(),
        ),
    )
}

/** A wild battle: the party's lead against a Pidgey, at [state]. */
fun battle(state: Int): SnapshotView {
    val s = snapshot()
    val foe = s.party[1].copy(moves = emptyList())
    val me = s.party[0].let { m ->
        m.copy(moves = m.moves.map { mv ->
            mv.copy(vs = listOf(MoveVsFoe(foe.name, "1x", 100)))
        })
    }
    return s.copy(
        inBattle = true, battleInputState = state, battlePlayer = listOf(me), battleOpponent = listOf(foe),
        party = s.party.mapIndexed { i, m -> m.copy(personality = 0x1000L + i) },
    )
}

/** A trainer battle (Brock-like): the party's lead against an Onix, a fainted
 * Geodude and a third, unseen Pokémon behind it - move verdicts computed for real. */
fun trainerBattle(state: Int, six: Boolean = false): SnapshotView {
    val s = snapshot()
    fun foe(species: Int, level: Int, hp: Int, maxHp: Int, vararg types: String): MonView {
        val ids = types.map(::typeIdOf)
        val mu = typeMatchups(ids[0], ids.getOrElse(1) { TYPE_NONE })
        return MonView(
            species, speciesName(species), level, hp, maxHp, "", types.toList(), monIcon(species),
            GENDER_SYMBOL_MALE, weaknesses = mu.weaknesses, resistances = mu.resistances, immunities = mu.immunities,
        )
    }
    val team = listOf(foe(74, 12, 0, 33, "Rock", "Ground"), foe(95, 14, 21, 40, "Rock", "Ground"), foe(74, 12, 33, 33, "Rock", "Ground")) +
        // A full party of six: Geodude and Onix seen, four more behind them.
        if (six) listOf(foe(111, 16, 40, 40, "Ground", "Rock"), foe(27, 13, 35, 35, "Ground"), foe(104, 15, 38, 38, "Ground")) else emptyList()
    val me = s.party[0].withMovesVs(team[1]).let { it.copy(moves = it.moves.mapIndexed { i, mv -> if (i == 1) mv.copy(pp = 0) else mv }) }
    return s.copy(
        inBattle = true, battleInputState = state, battlePlayer = listOf(me), battleOpponent = listOf(team[1]),
        enemyParty = team, enemyActive = 1,
    )
}

/** A fuller party behind the lead, so SUGGESTIONS has picks to rank (Squirtle's
 * Water Gun and Bulbasaur's Vine Whip are 4x on Onix; Pidgey is weak to Rock). */
fun SnapshotView.withBench(): SnapshotView {
    fun mon(species: Int, level: Int, hp: Int, maxHp: Int, types: List<String>, vararg moves: MoveView): MonView {
        val ids = types.map(::typeIdOf)
        val mu = typeMatchups(ids[0], ids.getOrElse(1) { TYPE_NONE })
        return MonView(
            species, speciesName(species), level, hp, maxHp, "", types, monIcon(species), GENDER_SYMBOL_MALE,
            moves = moves.map { it.copy(name = gameCase(it.name)) }.toList(),
            weaknesses = mu.weaknesses, resistances = mu.resistances, immunities = mu.immunities,
        )
    }
    val bench = listOf(
        mon(7, 11, 31, 31, listOf("Water"), MoveView("Tackle", "Normal", 35, 35), MoveView("Tail Whip", "Normal", 30, 0), MoveView("Bubble", "Water", 30, 20), MoveView("Water Gun", "Water", 25, 40)),
        mon(1, 10, 12, 30, listOf("Grass", "Poison"), MoveView("Tackle", "Normal", 35, 35), MoveView("Growl", "Normal", 40, 0), MoveView("Vine Whip", "Grass", 10, 35)),
    )
    return copy(party = party + bench)
}

/** Serves ROM reads (0x08000000+) from a ROM file. */
class RomFile(private val rom: ByteArray) : MemoryReader {
    override fun readCoreMemory(addr: Long, size: Int): ByteArray {
        val o = (addr - 0x08000000L).toInt()
        return rom.copyOfRange(o, o + size)
    }
}

/** Serves RAM reads from a save fixture's ewram.bin / iwram.bin (app/src/test/resources/fixtures). */
class RamFixture(dir: File) : MemoryReader {
    private val ewram = File(dir, "ewram.bin").readBytes()
    private val iwram = File(dir, "iwram.bin").readBytes()
    override fun readCoreMemory(addr: Long, size: Int): ByteArray {
        val (base, buf) = if (addr >= 0x03000000L) 0x03000000L to iwram else 0x02000000L to ewram
        val o = (addr - base).toInt()
        return buf.copyOfRange(o, o + size)
    }
}

/**
 * The GUIDE's live pages for retail FireRed / Emerald: -Prom=<a ROM
 * byte-identical to retail (rev 1 for FireRed)> plus the repo's
 * <game>_vanilla save fixture. Null when no ROM was given (or it's another game).
 */
fun liveGuide(): ((SnapshotView) -> SnapshotView)? {
    val path = System.getProperty("rom").orEmpty()
    val (cfg, fixture) = when (activeGame) {
        GameKind.EMERALD -> NATIVE_EMERALD_RETAIL to "emerald_vanilla"
        GameKind.FIRERED -> NATIVE_FIRERED_REV1 to "firered_vanilla"
        else -> return null
    }
    if (path.isBlank()) return null
    val tables = cfg.guideTables!!
    PokedexSource.reader = RomFile(File(path).readBytes())
    // The render task runs in app/src/main/assets.
    val ram = RamFixture(File("../../test/resources/fixtures/$fixture"))
    val progress = readSaveProgress(ram, cfg, tables)
    val sb1 = ram.readCoreMemory(cfg.saveBlock1Ptr, 4).let { (it[0].toLong() and 0xFF) or (it[1].toLong() and 0xFF shl 8) or (it[2].toLong() and 0xFF shl 16) or (it[3].toLong() and 0xFF shl 24) }
    val pos = ram.readCoreMemory(sb1, 6)
    // gMapHeader.regionMapSectionId (+0x14): the name HERE shows.
    val mapsec = ram.readCoreMemory(cfg.mapHeader + 0x14, 1)[0].toInt() and 0xFF
    return { s ->
        s.copy(
            guideTables = tables, progress = progress, mapGroup = pos[4].toInt(), mapNum = pos[5].toInt(),
            location = lookupLocation(mapsec), regionMapSectionId = mapsec,
        )
    }
}

/**
 * The DEX page's sections (INFO / EVOLVE / AREA / MOVES) for retail FireRed / Emerald,
 * read from -Prom with the save fixture's dex flags; empty without a ROM.
 */
fun dexShots(game: GameKind, live: ((SnapshotView) -> SnapshotView)?, w: Int, h: Int, d: Float): List<Shot> {
    if (live == null) return emptyList()
    val (cfg, fixture) = if (game == GameKind.EMERALD) NATIVE_EMERALD_RETAIL to "emerald_vanilla" else NATIVE_FIRERED_REV1 to "firered_vanilla"
    val dex = com.pokedaisy.app.companion.data.readPokedexState(RamFixture(File("../../test/resources/fixtures/$fixture")), cfg, cfg.pokedex!!)
        ?: return emptyList()
    val g = game.name.lowercase()
    // EEVEE's family / INFO, MAGIKARP's rods and routes (Emerald: ZIGZAGOON's), BULBASAUR's moves (Emerald: TREECKO's).
    val entries = mapOf(
        com.pokedaisy.app.companion.ui.DexSection.INFO to 133,
        com.pokedaisy.app.companion.ui.DexSection.EVOLVE to 133,
        com.pokedaisy.app.companion.ui.DexSection.AREA to if (game == GameKind.EMERALD) 263 else 129,
        com.pokedaisy.app.companion.ui.DexSection.MOVES to if (game == GameKind.EMERALD) 252 else 1,
    )
    return entries.map { (section, entry) ->
        Shot("$g-dex-${section.name.lowercase()}", w, h, d, {
            val s = live(snapshot())
            val content: @Composable () -> Unit = {
                com.pokedaisy.app.companion.ui.theme.QolTheme {
                    val ui = androidx.compose.runtime.remember {
                        com.pokedaisy.app.companion.ui.DexUiState(androidx.compose.foundation.lazy.LazyListState()).apply { open = entry; this.section = section }
                    }
                    com.pokedaisy.app.companion.ui.PokedexEntryScreen(dex, ui, entry, guide = s.guideTables)
                }
            }
            content
        })
    }
}

/** Canned SteamGridDB answers for the cover picker; thumbnails are local mon icons. */
class FakeCoverSource(private val known: Boolean, private val boxArt: Boolean = false, override val steamGridDb: Boolean = true) : com.pokedaisy.app.CoverSource {
    private val icon = { n: Int -> monIcon(n).orEmpty() }
    // The RA box art: a stand-in image (the preview has no network), a bigger mon.
    override fun raBoxArt(rom: File) = if (boxArt) com.pokedaisy.app.raBoxArtIcon(icon(6)) else null
    override fun gameFor(rom: File) =
        if (known) com.pokedaisy.app.SteamGridDbClient.GameHit(33987, "Pokémon FireRed") else null
    override fun search(term: String) = listOf(
        "Pokémon Amethyst", "Pokémon Amethyst Version", "Pokémon Crystal", "Pokémon Emerald",
    ).mapIndexed { i, n -> com.pokedaisy.app.SteamGridDbClient.GameHit(1000 + i, n) }
    override fun icons(gameId: Int) = listOf(6, 3, 9, 25, 150, 1, 4, 7, 144, 145, 146, 94).mapIndexed { i, n ->
        com.pokedaisy.app.SteamGridDbClient.Icon(
            n, icon(n), icon(n),
            if (i < 3) com.pokedaisy.app.SteamGridDbClient.PREFERRED_AUTHOR_STEAM64 else "1",
            if (i < 3) "Favourite" else "Someone$i",
        )
    }
    override fun thumbBytes(url: String) = File(url).takeIf { it.isFile }?.readBytes()
}

/** [make] with a SteamGridDB key saved ([activity] clears it first - the prefs shim is shared by every shot). */
fun withApiKey(make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    Prefs(android.content.Context()).steamGridDbApiKey = "preview-key"
    return make()
}

/** [make] with ASPECT = [stretch] and the STATUS BAR on, plus a savestate thumbnail for the
 * ASPECT preview when a game frame is around: native-capture/menu-shots/thor/top.png (untracked -
 * the Thor's top screen, `adb exec-out screencap`, cropped to the game and scaled to 240x160). */
fun withAspect(stretch: Boolean, make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    val ctx = android.content.Context()
    Prefs(ctx).stretchGame = stretch
    Prefs(ctx).statusBar = true
    val shot = File("../../../../native-capture/menu-shots/thor/top.png")
    if (shot.isFile) shot.copyTo(File(ctx.filesDir, "states/0badcafe/ss1.png").apply { parentFile.mkdirs() }, overwrite = true)
    return make()
}

fun fakeRelease() = com.pokedaisy.app.AppUpdater.Release(
    "1.0.5",
    "- LOAD SAVE: put another save file into a game\n- INFO on every game\n- Fixes for the ROMs folder scan",
    "", 0, "",
)

/** What a player might have for FireRed: a typed code, two from a RetroArch .cht. */
fun previewCheats() = listOf(
    com.pokedaisy.app.cheats.Cheat("INFINITE MONEY", listOf("82025838 FFFF"), "", true),
    com.pokedaisy.app.cheats.Cheat("Master Code (must be on)", listOf("000014D1 000A", "1003DBB8 0007"), "", false),
    com.pokedaisy.app.cheats.Cheat("Wild Pokemon are always shiny", listOf("12345678 9ABCDEF0"), "GSAv1", false),
)

/** [make] on the fake library's first game's CHEATS page (as its library menu opens it),
 * with [previewCheats] saved for it when [seeded]. */
fun withCheats(seeded: Boolean = true, make: () -> SettingsActivity): androidx.activity.ComponentActivity {
    val ctx = android.content.Context()
    val rom = File(ctx.filesDir, "roms/firered-qol.gba")
    val crc = SettingsActivity.cheatCrcOf(rom)!!
    if (seeded) com.pokedaisy.app.cheats.CheatStore.forCrc(ctx.filesDir, crc).save(previewCheats())
    Prefs(ctx).cheatsEnabled = seeded
    return make().apply { intent.putExtra(SettingsActivity.EXTRA_CHEATS_ROM, rom.absolutePath) }
}

/** [make] with two of the fake library's games hidden (after [activity]'s reset). */
fun withHidden(make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    val ctx = android.content.Context()
    val roms = File(ctx.filesDir, "roms")
    Prefs(ctx).hiddenRoms = setOf(File(roms, "Pokemon - Gaia (v3.2).gba").absolutePath, File(roms, "1636 - Pokemon Radical Red.gba").absolutePath)
    return make()
}

/** Runs the activity's onCreate against a fake library and returns what it passed to setContent. */
fun activity(make: () -> androidx.activity.ComponentActivity): () -> (@Composable () -> Unit) = {
    val ctx = android.content.Context()
    val roms = File(ctx.filesDir, "roms").apply { mkdirs() }
    // Dump-style file names, as players have them; firered-qol stays unrecognised (a QoL build).
    val names = listOf(
        "firered-qol.gba", "Pokemon - Unbound (v2.1.1.1).gba", "Pokemon - Emerald Version (USA, Europe).gba",
        "1636 - Pokemon Radical Red.gba", "Pokemon - Gaia (v3.2).gba", "Pokemon Emerald Imperium (World) (v1.3.1).gba",
    )
    roms.listFiles()?.forEach { it.delete() }
    names.forEach { File(roms, it).apply { writeText("x"); setLastModified(1_700_000_000_000L) } }
    // What GameTitles.identify would have cached for the real ROMs (the fakes don't hash to them).
    val titles = mapOf(names[1] to "Pokémon Unbound", names[2] to "Pokémon Emerald", names[3] to "Pokémon Radical Red", names[4] to "Pokémon Gaia")
    File(ctx.filesDir, "rom-titles.tsv").writeText(names.joinToString("") { n ->
        val f = File(roms, n)
        "${f.absolutePath}\t${f.length()}\t${f.lastModified()}\t${titles[n].orEmpty()}\n"
    })
    File(ctx.filesDir, "saves").mkdirs()
    File(ctx.filesDir, "saves/firered-qol.sav").writeText("x")
    val prefs = Prefs(ctx)
    prefs.steamGridDbApiKey = null
    prefs.libraryViewMode = 0
    prefs.romsFolder = null
    prefs.hiddenRoms = emptySet()
    prefs.hotkeysEnabled = true
    prefs.savesDirOverride = null
    prefs.stretchGame = false
    prefs.statusBar = false
    // The default theme (PokéDaisy); a shot that wants another sets it in its make().
    prefs.appTheme = com.pokedaisy.app.companion.ui.theme.DAISY_THEME_ID
    File(ctx.filesDir, "states").deleteRecursively()
    File(ctx.filesDir, "cheats").deleteRecursively()
    prefs.cheatsEnabled = false
    prefs.lastRomPath = File(roms, names[0]).absolutePath
    names.take(3).reversed().forEach { prefs.pushRecentRom(File(roms, it).absolutePath) }
    make().onCreate(null)
    androidx.activity.compose.Captured.content!!
}

/** The window and any popups over it (a DropdownMenu is its own root), each
 * drawn where it sits in the window. */
fun SkikoComposeUiTest.captureAllRoots(): java.awt.image.BufferedImage {
    val roots = onAllNodes(isRoot())
    val nodes = roots.fetchSemanticsNodes()
    val base = roots[0].captureToImage().toAwtImage()
    val g = base.createGraphics()
    for (i in 1 until nodes.size) {
        val pos = nodes[i].positionInWindow
        g.drawImage(roots[i].captureToImage().toAwtImage(), pos.x.toInt(), pos.y.toInt(), null)
    }
    g.dispose()
    return base
}

/**
 * FireRed / Emerald art comes from the ROM (RomArt), so rebuild it into the
 * shimmed filesDir from -PartRoms (comma-separated), -Prom and the decomp
 * builds under $DECOMPS, whichever exist. With none, those games show their fallbacks.
 */
fun romArt() {
    val decomps = System.getProperty("decomps").orEmpty()
    val roms = System.getProperty("artRoms").orEmpty().split(",") + System.getProperty("rom").orEmpty() +
        (if (decomps.isBlank()) emptyList() else
            listOf("pokefirered/pokefirered_rev1.gba", "pokeemerald/pokeemerald.gba").map { File(decomps, it).path })
    val dir = RomArt.dir(android.content.Context().filesDir)
    for (f in roms.filter { it.isNotBlank() }.map(::File).filter { it.isFile }.distinct()) {
        if (RomArt.OUTPUTS.all { File(dir, it).isFile }) break
        val wrote = RomArt.extractTo(f.readBytes(), dir)
        if (wrote.isNotEmpty()) println("rom art: ${wrote.size} files from ${f.name}")
    }
    RomArt.OUTPUTS.filter { !File(dir, it).isFile }.takeIf { it.isNotEmpty() }?.let { println("rom art missing (no ROM for it): $it") }
}

/** The game's home town, which its own region map shows (Emerald has no Pallet Town rect). */
fun homeMapsec(game: GameKind) = when (game) {
    GameKind.EMERALD, GameKind.EMERALD_ROGUE -> 0x00 // MAPSEC_LITTLEROOT_TOWN
    GameKind.LAZARUS -> 0x5A // Acrisia City
    else -> 0x58 // MAPSEC_PALLET_TOWN
}

fun main(args: Array<String>) {
    romArt()
    // A FireRed-engine hack's own region map (Unbound, Odyssey, ...) from -Prom.
    System.getProperty("rom").orEmpty().takeIf { it.isNotBlank() }?.let { path ->
        val f = File(path)
        RomRegionMap.current = RomRegionMap.loadNow(android.content.Context().filesDir, "preview-%08x".format(f.name.hashCode()), f)
    }
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val only = args.getOrElse(1) { "" }.split(",").filter { it.isNotBlank() }
    val game = GameKind.valueOf(System.getProperty("game") ?: "FIRERED")
    activeGame = game
    // -Plang=FR: the app's text in another language (saved like the LANGUAGE setting, which the activities re-apply).
    System.getProperty("lang").orEmpty().takeIf { it.isNotBlank() }?.let { code ->
        runCatching { com.pokedaisy.app.Prefs(android.content.Context()).appLanguage = code }
        com.pokedaisy.app.companion.i18n.L10n.apply(code, null)
    }
    val g = game.name.lowercase()
    val live = liveGuide()
    // AYN Thor: bottom screen 1240x1080 (landscape), top screen 1920x1080.
    // -PcompanionW / -PcompanionH: a single-screen device's side panel instead, at its density.
    val panelW = System.getProperty("companionW").orEmpty().toIntOrNull()
    val bh = System.getProperty("companionH").orEmpty().toIntOrNull() ?: 1080
    val bw = panelW ?: 1240
    val bd = if (panelW != null) com.pokedaisy.app.companion.ui.sidePanelDensity(bw, bh) else 2.625f
    val tw = 1920; val th = 1080; val td = 2.5f
    val shots = listOf(
        Shot("$g-party", bw, bh, bd, companion("PARTY")),
        Shot("$g-party-detail", bw, bh, bd, companion("PARTY", mon = 0)),
        Shot("$g-party-detail-next", bw, bh, bd, companion("PARTY", mon = 0)) {
            onNodeWithContentDescription("Next Pokémon").performClick()
        },
        // In the game's home town, which its own region map shows (Emerald has no Pallet Town rect).
        Shot("$g-settings-battery-low", bw, bh, bd, companion("SETTINGS", battery = BatteryStatus(9, charging = false))),
        Shot("$g-settings-battery-charging", bw, bh, bd, companion("SETTINGS", battery = BatteryStatus(100, charging = true))),
        Shot("$g-card", bw, bh, bd, companion("CARD", snap = ::card)),
        Shot("$g-card-back", bw, bh, bd, companion("CARD", cardBack = true, snap = ::card)),
        Shot("$g-map", bw, bh, bd, companion("MAP") {
            val sec = homeMapsec(game)
            snapshot().copy(location = lookupLocation(sec), regionMapSectionId = sec)
        }),
        // The female head, partway along a long route (FireRed Route 4 / Emerald Route 104,
        // MAP_TYPE_ROUTE): the head on the tile the game puts it, the cursor round the route.
        Shot("$g-map-route", bw, bh, bd, companion("MAP") {
            val sec = when (game) {
                GameKind.EMERALD, GameKind.EMERALD_ROGUE -> 0x13
                GameKind.LAZARUS -> 0x67 // Erinys Path, 3 tiles wide
                else -> 0x68
            }
            snapshot().copy(
                location = lookupLocation(sec), regionMapSectionId = sec, playerGender = 1, mapType = 3,
                mapWidth = if (game == GameKind.EMERALD) 40 else 90, mapHeight = if (game == GameKind.EMERALD) 60 else 20,
                x = if (game == GameKind.EMERALD) 10 else 40, y = if (game == GameKind.EMERALD) 25 else 8,
            )
        }),
        // A tap on the map: FireRed's Mt. Moon tile on Route 4 (two names), Emerald's Fortree City.
        Shot("$g-map-tap", bw, bh, bd, companion("MAP") {
            val sec = homeMapsec(game)
            snapshot().copy(location = lookupLocation(sec), regionMapSectionId = sec)
        }) {
            val at = if (game == GameKind.EMERALD || game == GameKind.EMERALD_ROGUE) Offset(0.45f, 0.125f) else Offset(0.4375f, 0.306f)
            onRoot().performTouchInput { click(Offset(37 + 1166 * at.x, 37 + 886 * at.y)) }
        },
        *(if (activeRegionMap() != null) arrayOf(
            Shot("$g-map-places", bw, bh, bd, companion("MAP")) { onNodeWithText("PLACES").performClick() },
        ) else emptyArray()),
        *(if (game == GameKind.FIRERED) arrayOf(
            Shot("$g-map-region", bw, bh, bd, companion("MAP")) { onNodeWithText("SEVII", substring = true).performClick() },
        ) else emptyArray()),
        *(if (game == GameKind.FIRERED || game == GameKind.EMERALD) arrayOf(
            Shot("$g-map-pick", bw, bh, bd, companion("MAP")) {
                onNodeWithText("PLACES").performClick()
                onNodeWithText(if (game == GameKind.EMERALD) "LAVARIDGE TOWN" else "CERULEAN CITY").performClick()
            },
        ) else emptyArray()),
        // BACK: each should land where the matching shot without it started.
        Shot("$g-back-party-detail", bw, bh, bd, companion("PARTY", mon = 0)) { pressBack() },
        Shot("$g-back-battle-info", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick(); pressBack()
        },
        Shot("$g-back-battle-suggestions", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("SUGGESTIONS").performClick(); pressBack()
        },
        Shot("$g-back-items-desc", bw, bh, bd, companion("ITEMS")) {
            onNodeWithText("Potion", substring = true, ignoreCase = true).performClick(); pressBack()
        },
        Shot("$g-back-settings-ffspeed", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("FF SPEED").performClick(); pressBack() },
        // Rows below the fold need a scroll + semantic click; each step asserts, so a miss
        // fails loudly. (A GbaText is two Text nodes - its shadow - hence the counting.)
        Shot("$g-back-settings-hotkeys", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("SAVE STATE").onFirst().assertExists()
            pressBack()
            onAllNodesWithText("SAVE STATE").assertCountEquals(0)
        },
        Shot("$g-back-settings-hotkeys-picker", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("SAVE STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("BIND SAVE STATE").onFirst().assertExists()
            pressBack()
            onAllNodesWithText("BIND SAVE STATE").assertCountEquals(0) // the picker closed...
            onAllNodesWithText("SAVE STATE").onFirst().assertExists() // ...still on HOTKEYS
        },
        Shot("$g-back-settings-states", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("STATES").performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("SAVE STATES").onFirst().assertExists()
            pressBack()
            onAllNodesWithText("SAVE STATES").assertCountEquals(0)
            onAllNodesWithText("OPTION").onFirst().assertExists()
        },
        Shot("$g-back-settings-close", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("CLOSE GAME").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); pressBack()
        },
        Shot("$g-battle", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }),
        Shot("$g-battle-trainer", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT, six = true).withBench() }),
        Shot("$g-battle-trainer3", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT).withBench() }),
        Shot("$g-battle-moves", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_MOVE_SELECT) }),
        // The POKéMON pane: from the action menu, and over the game's own party menu.
        Shot("$g-battle-switch", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("POKéMON").performClick()
        },
        // A fuller party against Onix: BEST on the pick, OUT on the lead, a fainted Pidgey.
        Shot("$g-battle-switch-trainer", bw, bh, bd, companion("PARTY") {
            trainerBattle(BATTLE_INPUT_ACTION_SELECT).withBench().let { s ->
                s.copy(party = s.party.mapIndexed { i, m -> m.copy(personality = 0x1000L + i, hp = if (i == 1) 0 else m.hp) })
            }
        }) {
            onNodeWithText("POKéMON").performClick()
        },
        Shot("$g-battle-party-open", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_PARTY_OPEN) }),
        Shot("$g-battle-busy", bw, bh, bd, companion("PARTY", busy = true) { battle(BATTLE_INPUT_ACTION_SELECT) }),
        Shot("$g-battle-info", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-trainer", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-trainer6", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT, six = true) }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-trainer6-tapped", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT, six = true) }) {
            onNodeWithText("INFO").performClick()
            onAllNodesWithContentDescription("Unknown foe Pokémon")[1].performClick()
        },
        Shot("$g-battle-info-nohints", bw, bh, bd, companion("PARTY", hints = false) { trainerBattle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-double", bw, bh, bd, companion("PARTY") {
            val t = trainerBattle(BATTLE_INPUT_ACTION_SELECT)
            val foes = t.enemyParty.drop(1)
            val mine = listOf(t.party[0], t.party[1]).map { me ->
                me.copy(moves = me.moves.map { mv -> mv.copy(vs = foes.map { f -> f.let { me.withMovesVs(it).moves.first { it.name == mv.name }.vs[0] } }) })
            }
            t.copy(isDoubleBattle = true, battlePlayer = mine, battleOpponent = foes, enemyParty = emptyList(), enemyActive = -1)
        }),
        Shot("$g-battle-suggestions", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("SUGGESTIONS").performClick()
        },
        Shot("$g-battle-suggestions-trainer", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT, six = true).withBench() }) {
            onNodeWithText("SUGGESTIONS").performClick()
        },
        Shot("$g-loading", bw, bh, bd, companion("PARTY") { SnapshotView(connected = false, error = "starting…") }),
        Shot("$g-items", bw, bh, bd, companion("ITEMS")),
        Shot("$g-items-desc", bw, bh, bd, companion("ITEMS")) {
            onNodeWithText("Potion", substring = true, ignoreCase = true).performClick()
        },
        Shot("$g-states", bw, bh, bd, companion("STATES")),
        // A ROM the companion can't read: the notice over every tab but SETTINGS.
        Shot("$g-unsupported", bw, bh, bd, companion("PARTY") {
            SnapshotView(connected = false, error = "game code AMTE isn't a supported Pokémon game", unsupported = true)
        }),
        Shot("$g-settings", bw, bh, bd, companion("SETTINGS")),
        Shot("$g-settings-ffspeed", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("FF SPEED").performClick() },
        // GAME BUTTONS' subtitle, further down the list.
        Shot("$g-settings-subtitles", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("TWEAKS").performScrollTo() },
        Shot("$g-settings-ffmusic", bw, bh, bd, companion("SETTINGS")) { onAllNodesWithText("FF MUSIC").onFirst().performClick() },
        Shot("$g-settings-hotkeys", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-buttons", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("GAME BUTTONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // Off: the binds greyed out.
        Shot("$g-settings-hotkeys-off", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("ON").performClick()
        },
        // The key picker: NONE (unbind) above CANCEL.
        Shot("$g-settings-hotkeys-picker", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("LOAD STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // X is SAVE STATE's: binding it to LOAD STATE asks first (the picker's X is the last "X" in the tree).
        Shot("$g-settings-hotkeys-clash", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("LOAD STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("X").onLast().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("KEY IN USE").onFirst().assertExists()
        },
        // CLOSE GAME / RESTART GAME end the scrolling list.
        Shot("$g-settings-bottom", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("CLOSE COMPANION").performScrollTo() },
        Shot("$g-settings-close", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("CLOSE GAME").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // TWEAKS: the small on / off preferences.
        Shot("$g-settings-tweaks", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("TWEAKS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // SHADERS: its page (FILTER, GBA COLORS), then after one FILTER tap (it cycles: NONE -> LCD).
        Shot("$g-settings-shaders", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-shaders-filter", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("FILTER").performClick()
        },
        // OVERLAY: its pick-list (NONE, the built-in frames, an import).
        Shot("$g-settings-overlay", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-cheats", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("CHEATS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("$g-settings-tabs", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("TAB BAR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("$g-settings-tabs-states", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("TAB BAR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("STATES").performSemanticsAction(SemanticsActions.OnClick)
        },
        *dexShots(game, live, bw, bh, bd).toTypedArray(),
        Shot("$g-guide", bw, bh, bd, companion("GUIDE") { live?.invoke(snapshot()) ?: snapshot() }),
        // HERE in a town (FireRed: CELADON CITY, Emerald: RUSTBORO CITY), with the save's flags.
        Shot("$g-guide-town", bw, bh, bd, companion("GUIDE") {
            val s = live?.invoke(snapshot()) ?: snapshot()
            val (group, num, sec) = if (game == GameKind.EMERALD) Triple(0, 3, 10) else Triple(3, 6, 94)
            s.copy(mapGroup = group, mapNum = num, regionMapSectionId = sec, location = lookupLocation(sec))
        }),
        // The same, scrolled down to PEOPLE / ITEMS (the harness can't scroll a lazy list by touch).
        *listOf(7, 14).map { first ->
            Shot("$g-guide-town-$first", bw, bh, bd, {
                val s = (live?.invoke(snapshot()) ?: snapshot()).let {
                    val (group, num, sec) = if (game == GameKind.EMERALD) Triple(0, 3, 10) else Triple(3, 6, 94)
                    it.copy(mapGroup = group, mapNum = num, regionMapSectionId = sec, location = lookupLocation(sec))
                }
                val content: @Composable () -> Unit = {
                    com.pokedaisy.app.companion.ui.theme.QolTheme {
                        val ui = androidx.compose.runtime.remember { com.pokedaisy.app.companion.ui.GuideUiState() }
                        val source = com.pokedaisy.app.companion.ui.rememberGuide(s.game, s.pokedex, s.guideTables)
                        if (source != null) com.pokedaisy.app.companion.ui.GuideScreen(source, ui, s)
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            repeat(20) { androidx.compose.runtime.withFrameNanos { } }
                            ui.list.scrollToItem(first)
                        }
                    }
                }
                content
            })
        }.toTypedArray(),
        Shot("$g-guide-where", bw, bh, bd, companion("GUIDE") { live?.invoke(snapshot()) ?: snapshot() }) {
            onNodeWithText("WHERE IS").performClick()
        },
        Shot("$g-guide-where-open", bw, bh, bd, companion("GUIDE") { live?.invoke(snapshot()) ?: snapshot() }) {
            onNodeWithText("WHERE IS").performClick()
            // A row on screen: the list is lazy, and neither performScrollToNode (the
            // manual clock never idles) nor touch swipes work in this harness.
            onNodeWithText("RUNNING SHOES").performClick()
            onNodeWithText("RUNNING SHOES").performClick()
        },
        Shot("$g-guide-boss", bw, bh, bd, companion("GUIDE") { live?.invoke(snapshot()) ?: snapshot() }) {
            if (live != null) onNodeWithText("NEXT BOSS").performClick() // a live page: -Prom only
        },
        Shot("$g-guide-stuck", bw, bh, bd, companion("GUIDE") { live?.invoke(snapshot()) ?: snapshot() }) {
            onNodeWithText("STUCK?").performClick()
        },
        // The top screen's status bar over a stand-in for the game (GameStageLayout's layout).
        Shot("status-bar", tw, th, td, {
            {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    // 1480 px at 2.5: a 3:2 game under a bar about 90 px tall.
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(592.dp)) {
                        com.pokedaisy.app.companion.ui.GameStatusBar(
                            "firered-qol", "Pallet Town", 224300, "14:05", battery = BatteryStatus(82, charging = false),
                        )
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(1.5f)
                                .background(androidx.compose.ui.graphics.Color(0xFF3A6B3A)),
                        )
                    }
                }
            }
        }),
        Shot("library-list", tw, th, td, activity { LibraryActivity() }),
        // A ROM imported with "add anyway?": the library tags what the second screen can't read.
        Shot("library-tag-unsupported", tw, th * 2, td, activity {
            val ctx = android.content.Context()
            val qol = File(ctx.filesDir, "roms/firered-qol.gba").absolutePath
            File(ctx.filesDir, "rom-folder-scan.tsv").writeText("$qol\t1\t1700000000000\t0\n")
            LibraryActivity().apply {
                unsupportedRoms = setOf(qol)
                partialRoms = setOf(File(ctx.filesDir, "roms/Pokemon - Gaia (v3.2).gba").absolutePath)
            }
        }),
        Shot("library-tag-unsupported-grid", tw, th * 2, td, activity {
            val ctx = android.content.Context()
            val qol = File(ctx.filesDir, "roms/firered-qol.gba").absolutePath
            File(ctx.filesDir, "rom-folder-scan.tsv").writeText("$qol\t1\t1700000000000\t0\n")
            LibraryActivity().apply { unsupportedRoms = setOf(qol) }
        }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        // A phone held upright (1080x2400 @ 2.625, a Pixel 6): the library before a game opens.
        Shot("library-list-phone", 1080, 2400, 2.625f, activity { LibraryActivity() }),
        Shot("library-grid-phone", 1080, 2400, 2.625f, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        Shot("library-grid", tw, th, td, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        // Long-pressing a library tile (not the RECENTLY PLAYED copy) opens its
        // options. Taller than the screen so the first row of tiles is whole.
        Shot("library-grid-menu", tw, th * 2, td, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
            mainClock.advanceTimeBy(500) // lay the grid out before looking for its tiles
            onAllNodesWithText("Pokémon Emerald")[1].performSemanticsAction(SemanticsActions.OnLongClick)
        },
        // INFO on a game (hashes and all), and LOAD SAVE's confirm over a game that has a save.
        Shot("library-info", tw, th, td, activity {
            LibraryActivity().apply {
                val ctx = android.content.Context()
                gameInfo = com.pokedaisy.app.GameInfo.read(ctx, Prefs(ctx), File(ctx.filesDir, "roms/firered-qol.gba"), hashes = true)
            }
        }),
        Shot("library-load-save", tw, th, td, activity {
            LibraryActivity().apply {
                val ctx = android.content.Context()
                saveLoad = LibraryActivity.PendingSaveLoad(File(ctx.filesDir, "roms/firered-qol.gba"), ByteArray(131072), "FireRed - Hall of Fame.sav")
            }
        }),
        // The update offer on opening the app, and while its APK downloads.
        Shot("library-update", tw, th, td, activity {
            LibraryActivity().apply { updates.release = fakeRelease() }
        }),
        Shot("library-update-downloading", tw, th, td, activity {
            LibraryActivity().apply { updates.release = fakeRelease(); updates.progress = 0.45f }
        }),
        // Importing a ROM the second screen can't read asks first.
        Shot("library-unsupported", tw, th, td, activity {
            LibraryActivity().apply {
                pendingImport = LibraryActivity.PendingImport(File("import.gba"), "Metroid Fusion.gba", null, play = false)
            }
        }),
        // REPLACE COVER: a known game's icons, a search's game list, and no API key.
        Shot("library-cover-picker", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-qol.gba"); coverSourceOverride = FakeCoverSource(known = true) }
        }),
        Shot("library-cover-picker-search", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("Pokemon Amethyst.gba"); coverSourceOverride = FakeCoverSource(known = false) }
        }),
        // RetroAchievements box art: first, beside SteamGridDB's icons; alone with no SteamGridDB key.
        Shot("library-cover-picker-boxart", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-qol.gba"); coverSourceOverride = FakeCoverSource(known = true, boxArt = true) }
        }),
        Shot("library-cover-picker-boxart-only", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-qol.gba"); coverSourceOverride = FakeCoverSource(known = false, boxArt = true, steamGridDb = false) }
        }),
        Shot("library-cover-picker-nokey", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-qol.gba") }
        }),
        // First-time setup: each step, before and after its folder is picked.
        Shot("setup-roms", tw, th, td, activity { LibraryActivity().apply { setup = SetupState() } }),
        Shot("setup-roms-from-settings", tw, th, td, activity { LibraryActivity().apply { setup = SetupState().apply { fromSettings = true } } }),
        Shot("setup-roms-noaccess", tw, th, td, activity { LibraryActivity().apply { setup = SetupState().apply { hasAccess = false } } }),
        Shot("setup-roms-scanning", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply { romsFolder = "/storage/XXXX-XXXX/ROMs/GBA"; scanning = true; checked = 14; toCheck = 41 }
            }
        }),
        Shot("setup-roms-found", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply {
                    romsFolder = "/storage/XXXX-XXXX/ROMs/GBA"
                    found = listOf("Pokémon FireRed", "Pokémon Emerald", "Pokémon Unbound", "Pokémon Radical Red", "Pokemon Emerald Imperium")
                }
            }
        }),
        Shot("setup-roms-none", tw, th, td, activity {
            LibraryActivity().apply { setup = SetupState().apply { romsFolder = "/storage/emulated/0/Download"; found = emptyList() } }
        }),
        Shot("setup-saves", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply {
                    step = SetupState.Step.SAVES
                    suggestions = listOf(
                        com.pokedaisy.app.SavesLocation.Suggestion(File("/storage/XXXX-XXXX/RetroArch/saves/mGBA"), 7),
                        com.pokedaisy.app.SavesLocation.Suggestion(File("/storage/emulated/0/ROMs/GBA"), 1),
                    )
                    savesDir = "/storage/XXXX-XXXX/RetroArch/saves/mGBA"
                }
            }
        }),
        Shot("setup-saves-none", tw, th, td, activity { LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.SAVES } } }),
        Shot("setup-buttons", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply {
                    step = SetupState.Step.BUTTONS
                    buttons = listOf("A" to "A / Z", "B" to "B / X", "L" to "L1 / A", "R" to "R1 / S", "START" to "START / ENTER", "SELECT" to "SELECT", "TURBO A" to "")
                }
            }
        }),
        Shot("setup-covers", tw, th, td, activity { LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.COVERS } } }),
        // SAVE runs the real sync over the fake library (matches nothing - no network).
        Shot("setup-covers-saved", tw, th, td, activity {
            LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.COVERS; apiKey = "preview-key" } }
        }) { onNodeWithText("SAVE").performClick() },
        Shot("settings-home", tw, th, td, activity { SettingsActivity() }),
        // Two games hidden from the library with HIDE: their page, and the library without them.
        Shot("settings-hidden", tw, th, td, activity { withHidden { SettingsActivity() } }) {
            onNodeWithText("HIDDEN GAMES").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("library-hidden", tw, th, td, activity { withHidden { LibraryActivity() } }),
        // ASPECT's picker: the drawn stand-in (no savestates), then a real frame with the status bar.
        Shot("settings-aspect", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("ASPECT").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("settings-aspect-stretch", tw, th, td, activity { withAspect(true) { SettingsActivity() } }) {
            onNodeWithText("ASPECT").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-shaders", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-shaders-filter", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("FILTER").performClick()
        },
        Shot("settings-swap", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("SWAP SCREENS").performScrollTo() },
        // OVERLAY: its page with one import (DAISY picked), then REMOVE's mode.
        Shot("settings-overlay", tw, th, td, activity { withOverlays { SettingsActivity() } }) {
            onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-overlay-remove", tw, th, td, activity { withOverlays { SettingsActivity() } }) {
            onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("REMOVE").performClick()
        },
        // The top screen with each overlay around a stand-in game, placed as EmulatorView places them.
        Shot("overlay-daisy", tw, th, 1f, overlayView(OverlayChoice.BuiltIn(BuiltInFrames.Style.DAISY), tw, th)),
        Shot("overlay-bezel", tw, th, 1f, overlayView(OverlayChoice.BuiltIn(BuiltInFrames.Style.BEZEL), tw, th)),
        Shot("overlay-daisy-gb", tw, th, 1f, overlayView(OverlayChoice.BuiltIn(BuiltInFrames.Style.DAISY), tw, th, gameW = 160, gameH = 144)),
        // A single screen with the companion's panel locked beside the game (half the screen).
        Shot("overlay-daisy-docked", 960, th, 1f, overlayView(OverlayChoice.BuiltIn(BuiltInFrames.Style.DAISY), 960, th)),
        Shot("overlay-daisy-4x3", 1024, 768, 1f, overlayView(OverlayChoice.BuiltIn(BuiltInFrames.Style.DAISY), 1024, 768)),
        // An import with no viewport in its .cfg: the window found from its alpha.
        Shot("overlay-imported", tw, th, 1f, overlayView(OverlayChoice.Imported("wood-frame"), tw, th)),
        Shot("overlay-imported-stretch", tw, th, 1f, overlayView(OverlayChoice.Imported("wood-frame"), tw, th, stretch = true)),
        Shot("settings-theme", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("THEME").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        // THEME > FIRERED: the look before PokéDaisy became the default (the stripes, the game's OPTION windows).
        Shot("library-firered-theme", tw, th, td, activity {
            Prefs(android.content.Context()).appTheme = com.pokedaisy.app.companion.ui.theme.FIRERED_THEME_ID
            LibraryActivity()
        }),
        Shot("settings-hotkeys", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("HOTKEYS").performScrollTo().performClick() },
        // A hotkey waiting for keys: CLEAR (unbind) / CANCEL over the list.
        Shot("settings-hotkeys-capture", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("SAVE STATE").performSemanticsAction(SemanticsActions.OnClick)
        },
        // The list's end: LIBRARY / ONLINE (RetroAchievements' BETA tag) / APP.
        Shot("settings-home-bottom", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("RUN SETUP").performScrollTo() },
        Shot("settings-buttons", tw, th * 2, td, activity { SettingsActivity() }) { onNodeWithText("GAME BUTTONS").performScrollTo().performClick() },
        Shot("settings-hotkeys-off", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("ON").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        },
        // Search: "rebind" isn't a row's name - its keywords find GAME BUTTONS.
        Shot("settings-search", tw, th, td, activity { SettingsActivity() }) {
            onNode(hasSetTextAction()).performTextInput("rebind")
        },
        Shot("settings-folders", tw, th * 2, td, activity { SettingsActivity() }) { onNodeWithText("FOLDERS").performScrollTo().performClick() },
        // CHEATS from the hub (no cheats yet), then as a game's library menu opens it.
        Shot("settings-cheats-empty", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("CHEATS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-cheats", tw, th, td, activity { withCheats { SettingsActivity() } }),
        Shot("settings-cheats-remove", tw, th, td, activity { withCheats { SettingsActivity() } }) {
            onNodeWithText("REMOVE").performClick()
            onAllNodesWithText("REMOVE").onFirst().performClick()
        },
        Shot("settings-cheats-game", tw, th, td, activity { withCheats { SettingsActivity() } }) { onNodeWithText("GAME").performClick() },
        Shot("settings-cheats-import-error", tw, th, td, activity {
            withCheats { SettingsActivity().apply { cheatNotice = SettingsActivity.cheatImportNotice(com.pokedaisy.app.cheats.CheatStore.Import(0, 2, 5, 0)) } }
        }),
        Shot("settings-cheats-imported", tw, th, td, activity {
            withCheats { SettingsActivity().apply { cheatNotice = SettingsActivity.cheatImportNotice(com.pokedaisy.app.cheats.CheatStore.Import(12, 1, 2, 3)) } }
        }),
        Shot("settings-cheats-add", tw, th, td, activity { withCheats { SettingsActivity() } }) { onNodeWithText("ADD CODE").performClick() },
        Shot("settings-cheats-add-error", tw, th, td, activity { withCheats { SettingsActivity() } }) {
            onNodeWithText("ADD CODE").performClick()
            onAllNodes(hasSetTextAction())[1].performTextInput("82025838 FFFF\n1234 NOTHEX")
            onNodeWithText("ADD").performClick()
        },
        Shot("settings-cheats-add-type", tw, th, td, activity { withCheats { SettingsActivity() } }) {
            onNodeWithText("ADD CODE").performClick()
            onNodeWithText("TYPE").performClick()
        },
        Shot("settings-coverart", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("COVER ART").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("settings-licenses", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("LICENSES").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("settings-retroachievements", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("RetroAchievements").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-retroachievements-signedin", tw, th, td, activity {
            com.pokedaisy.app.achievements.RetroAchievements.previewSignedIn(
                com.pokedaisy.app.achievements.RaUser("Lidor", "Lidor", null, 0, 1234),
            )
            SettingsActivity()
        }) {
            onNodeWithText("RetroAchievements").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // With a key saved: REPLACE ALL's confirm, then the finished run (the fake
        // ROMs match nothing, so everything is kept - no network involved).
        Shot("settings-coverart-replace", tw, th, td, activity { withApiKey { SettingsActivity() } }) {
            onNodeWithText("COVER ART").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("REPLACE ALL").performClick()
        },
        Shot("settings-coverart-replaced", tw, th, td, activity { withApiKey { SettingsActivity() } }) {
            onNodeWithText("COVER ART").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("REPLACE ALL").performClick()
            onNodeWithText("REPLACE").performClick()
        },
    )
    var failed = 0
    for (shot in shots) {
        if (only.isNotEmpty() && only.none { shot.name.contains(it) }) continue
        try {
            val content = shot.content()
            runSkikoComposeUiTest(Size(shot.w.toFloat(), shot.h.toFloat()), Density(shot.density)) {
                // Manual clock: the party icons / map highlight animate forever,
                // so waitForIdle() would never return.
                mainClock.autoAdvance = false
                setContent(content)
                mainClock.advanceTimeBy(500)
                runCatching { shot.action(this) }
                    .onFailure { println("${shot.name}: action failed: ${it.message?.lineSequence()?.first()}") }
                // Frame by frame, so transitions started by the action run to the end.
                repeat(90) { mainClock.advanceTimeByFrame() }
                Thread.sleep(300) // bitmaps load on Dispatchers.IO
                mainClock.advanceTimeBy(500)
                ImageIO.write(captureAllRoots(), "png", File(out, "${shot.name}.png"))
                println("wrote ${File(out, "${shot.name}.png")}")
            }
        } catch (t: Throwable) {
            failed++
            println("${shot.name}: FAILED $t")
            t.printStackTrace()
        }
    }
    System.exit(if (failed > 0) 1 else 0)
}


/** A RetroArch-style overlay of our own drawing (a wooden picture frame, 1920x1080, its window see-through and
 * off-centre), imported through OverlayStore with a .cfg that gives no viewport - so its window is found from alpha. */
fun sampleOverlay(): OverlayStore {
    val store = OverlayStore(android.content.Context().filesDir)
    if (store.get("wood-frame") != null) return store
    val img = java.awt.image.BufferedImage(1920, 1080, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x8B5A2B), 1920f, 1080f, java.awt.Color(0x5C3A1A))
    g.fillRect(0, 0, 1920, 1080)
    g.color = java.awt.Color(0x3A220E)
    g.fillRoundRect(330, 70, 1280, 880, 40, 40)
    g.composite = java.awt.AlphaComposite.Clear
    g.fillRoundRect(350, 90, 1240, 840, 24, 24)
    g.dispose()
    val png = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    val cfg = "overlays = 1\noverlay0_overlay = \"img/wood frame.png\"\noverlay0_full_screen = true\n"
    store.import(listOf(OverlayStore.Picked("wood_frame.cfg", cfg.toByteArray()), OverlayStore.Picked("wood frame.png", png)))
    return store
}

/** Settings with [sampleOverlay] imported and DAISY picked. */
fun withOverlays(make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    sampleOverlay()
    Prefs(android.content.Context()).overlay = OverlayChoice.BuiltIn(BuiltInFrames.Style.DAISY).key
    return make()
}

/** A stand-in for the game: colour bands and an 8-pixel grid, so the scale and edges show. */
private fun standInGame(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
    val x = i % w
    val y = i / w
    val band = intArrayOf(0x58A8F8, 0x88D0F8, 0x70C850, 0x50A030, 0xD8B070)[minOf(4, y * 5 / h)]
    val grid = if (x % 8 == 0 || y % 8 == 0) 0x202020 else band
    (0xFF shl 24) or if ((x / 16 + y / 16) % 7 == 0) 0xF8F8F8 else grid
}

private fun bitmapOf(argb: IntArray, w: Int, h: Int): androidx.compose.ui.graphics.ImageBitmap {
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, argb, 0, w)
    return img.toComposeImageBitmap()
}

/** The top screen ([viewW] x [viewH]) with [choice] around a [gameW] x [gameH] stand-in game: the same frame /
 * decode / window / placement steps as EmulatorView + OverlayBitmaps, drawn with Compose instead of GL. */
fun overlayView(
    choice: OverlayChoice, viewW: Int, viewH: Int, gameW: Int = 240, gameH: Int = 160, stretch: Boolean = false,
): () -> (@Composable () -> Unit) = {
    val game = bitmapOf(standInGame(gameW, gameH), gameW, gameH)
    val (art, window, scale) = when (choice) {
        is OverlayChoice.BuiltIn -> {
            val logo = Sprite(com.pokedaisy.app.companion.ui.LOGO_ROWS, com.pokedaisy.app.companion.ui.LOGO_PALETTE.mapValues { it.value.toArgb() })
            val f = BuiltInFrames.render(choice.style, viewW, viewH, gameW, gameH, logo)
            Triple(bitmapOf(f.argb, f.w, f.h), f.window, f.scale)
        }
        is OverlayChoice.Imported -> {
            val layer = sampleOverlay().get(choice.id)!!.layerFor(viewW.toFloat() / viewH)!!
            val img = ImageIO.read(layer.file)
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            Triple(img.toComposeImageBitmap(), layer.viewport ?: OverlayWindow.find(px, img.width, img.height), 0)
        }
        OverlayChoice.None -> error("no overlay")
    }
    val p = OverlayGeometry.layout(viewW, viewH, art.width, art.height, window, gameW, gameH, stretch, scale)
    println("overlay ${choice.key}: window=$window scale=$scale game=${p.game} overlay=${p.overlay}")
    val content: @Composable () -> Unit = {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
            drawRect(androidx.compose.ui.graphics.Color.Black)
            fun androidx.compose.ui.graphics.drawscope.DrawScope.box(img: androidx.compose.ui.graphics.ImageBitmap, b: Box, smooth: Boolean) =
                drawImage(
                    img, dstOffset = androidx.compose.ui.unit.IntOffset(b.x.toInt(), b.y.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(b.w.toInt(), b.h.toInt()),
                    filterQuality = if (smooth) androidx.compose.ui.graphics.FilterQuality.Medium else androidx.compose.ui.graphics.FilterQuality.None,
                )
            if (!p.onTop) box(art, p.overlay, scale == 0)
            box(game, p.game, false)
            if (p.onTop) box(art, p.overlay, scale == 0)
        }
    }
    content
}
