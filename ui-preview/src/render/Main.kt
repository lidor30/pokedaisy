@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package render

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.SkikoComposeUiTest
import com.pokedaisy.app.companion.data.isGen5
import com.pokedaisy.app.companion.data.save.BoxSlot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithText
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.pokedaisy.app.companion.data.isGameBoyGame
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

class FakeSettings(showHintsInitially: Boolean = true, initialTabs: List<String>? = null, oneTap: Boolean = false, expandOffer: Boolean = false,
                   expand: Boolean = false) : CompanionSettings {
    private var oneTapOn = oneTap
    override val oneTapMoves get() = oneTapOn
    override fun setOneTapMoves(on: Boolean) { oneTapOn = on }
    private val offer = androidx.compose.runtime.mutableStateOf(expandOffer)
    override val expandOffer get() = offer.value
    override fun answerExpandOffer(turnOn: Boolean) { offer.value = false }
    private var ffSpeed = 4f
    private var ff = false
    private var music = com.pokedaisy.app.companion.FfMusicMode.STEADY
    private var touch = com.pokedaisy.app.companion.TouchControlsStyle()
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
    override val touchStyle get() = touch
    override fun setTouchStyle(style: com.pokedaisy.app.companion.TouchControlsStyle) { touch = style }
    // SCENE SPEEDS: battles at 2x, the rest SAME; AUTO-RUN on (the preview's game is one the field reads know).
    private var sceneBattle = 2f
    private var sceneText = 0f
    private var sceneMenu = 0f
    override val battleSpeed get() = sceneBattle
    override fun setBattleSpeed(v: Float) { sceneBattle = v }
    override val textSpeed get() = sceneText
    override fun setTextSpeed(v: Float) { sceneText = v }
    override val menuSpeed get() = sceneMenu
    private var sceneBike = 3f
    private var sceneSurf = 0f
    override val bikeSpeed get() = sceneBike
    override fun setBikeSpeed(v: Float) { sceneBike = v }
    override val surfSpeed get() = sceneSurf
    override fun setSurfSpeed(v: Float) { sceneSurf = v }
    override val fieldMovesReadable get() = true
    override fun setMenuSpeed(v: Float) { sceneMenu = v }
    override val fieldReadable get() = true
    private var autoRunOn = true
    override val autoRun get() = autoRunOn
    override fun setAutoRun(on: Boolean) { autoRunOn = on }
    private var runIndoorsOn = false
    override val runIndoors get() = runIndoorsOn
    override fun setRunIndoors(on: Boolean) { runIndoorsOn = on }
    override val runIndoorsSupported get() = true
    private var wildPercent = 50
    override val wildEncounters get() = wildPercent
    override fun setWildEncounters(percent: Int) { wildPercent = percent }
    override val wildEncountersSupported get() = true
    private var reusableTmsOn = false
    override val reusableTms get() = reusableTmsOn
    override fun setReusableTms(on: Boolean) { reusableTmsOn = on }
    override val reusableTmsSupported get() = true
    private var shinyOddsValue = com.pokedaisy.app.companion.data.ShinyOdds.NORMAL
    override val shinyOdds get() = shinyOddsValue
    override fun setShinyOdds(odds: Int) { shinyOddsValue = odds }
    override val shinyOddsChoices get() = com.pokedaisy.app.companion.data.ShinyOdds.DEBUG_CHOICES
    override val shinyOddsSupported get() = com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.FIRERED ||
        com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.EMERALD
    override val reusableTmsBuiltIn get() = com.pokedaisy.app.companion.data.activeGame in com.pokedaisy.app.companion.data.ReusableTms.BUILT_IN
    // PLAYER LOOK: the other games' heroes kept, the player in their own.
    private var lookWorn: String? = null
    override val playerLookSupported get() = true
    override val playerLooks get() =
        (if (com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.EMERALD) listOf("RED", "LEAF", "MAY", "RS_BRENDAN", "RS_MAY", "GOLD", "KRIS")
        else listOf("LEAF", "BRENDAN", "MAY", "RS_BRENDAN", "RS_MAY", "GOLD", "KRIS")) + listOf("MON:25", "MON:258", "MON:384")
    override fun playerLookLabel(name: String) = when (name) { "MON:25" -> "PIKACHU"; "MON:258" -> "MUDKIP"; "MON:384" -> "RAYQUAZA"; else -> super.playerLookLabel(name) }
    override val playerLook get() = lookWorn
    override val playerLookOwn get() =
        if (com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.EMERALD) "BRENDAN" else "RED"
    override fun setPlayerLook(look: String?) { lookWorn = look }
    override val rivalLookSupported get() = com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.EMERALD
    private var rivalOn = true
    override val rivalLook get() = rivalOn
    override fun setRivalLook(on: Boolean) { rivalOn = on }
    private var followerOn = false
    override val followerSupported get() = true
    override val followerReady get() = true
    override val follower get() = followerOn
    override fun setFollower(on: Boolean) { followerOn = on }
    private var dayNightOn = false
    override val dayNightSupported get() = true
    override val dayNight get() = dayNightOn
    override fun setDayNight(on: Boolean) { dayNightOn = on }
    private var textOn: String? = null
    override val textLooks get() =
        listOf(if (com.pokedaisy.app.companion.data.activeGame == com.pokedaisy.app.companion.data.GameKind.EMERALD) "FIRERED" else "EMERALD")
    override val textLook get() = textOn
    override fun setTextLook(style: String?) { textOn = style }
    override fun gbaControlBindings() = GbaControls.Btn.entries.associateWith {
        listOf(when (it) { GbaControls.Btn.X, GbaControls.Btn.START_2, GbaControls.Btn.TO_COMPANION -> "BUTTON_X"; GbaControls.Btn.Y, GbaControls.Btn.SELECT_2 -> "BUTTON_Y"; else -> "BUTTON_${it.name}" })
    }
    override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {}
    // The shots' games are GBA ones (a DS game's rows: X / Y).
    override val buttonGroup get() = GbaControls.Group.GBA
    override val keyCapture get() = previewCapture
    private var hotkeysOn = true
    override val hotkeysEnabled get() = hotkeysOn
    override fun setHotkeysEnabled(on: Boolean) { hotkeysOn = on }
    // SAVE STATE on X, so picking X elsewhere shows the KEY IN USE confirm.
    override fun hotkeyBindings() = Hotkeys.Action.entries.associateWith { if (it == Hotkeys.Action.SAVE_STATE) listOf("BUTTON_X") else listOf("BUTTON_Y") }
    override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {}
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
    private var aspect = if (expand) com.pokedaisy.app.companion.GameAspect.WIDE else com.pokedaisy.app.companion.GameAspect.ORIGINAL
    override val gameAspect get() = aspect
    override fun setGameAspect(aspect: com.pokedaisy.app.companion.GameAspect) { this.aspect = aspect }
    private var colors = false
    override val gbaColors get() = colors
    override fun setGbaColors(on: Boolean) { colors = on }
    private var unlit = false
    override val unlitScreen get() = unlit
    override fun setUnlitScreen(on: Boolean) { unlit = on }
    private var filter = com.pokedaisy.app.companion.ScreenFilter.NONE
    override val screenFilter get() = filter
    override fun setScreenFilter(filter: com.pokedaisy.app.companion.ScreenFilter) { this.filter = filter }
    private var grid = com.pokedaisy.app.companion.GridStrength.MEDIUM
    override val gridStrength get() = grid
    override fun setGridStrength(strength: com.pokedaisy.app.companion.GridStrength) { grid = strength }
    private var smooth = com.pokedaisy.app.companion.Smoothing.OFF
    override val smoothing get() = smooth
    override fun setSmoothing(mode: com.pokedaisy.app.companion.Smoothing) { smooth = mode }
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
    override val romFileName = "firered-hack.gba"
    private var tabs = initialTabs ?: com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS
    override val companionTabs get() = tabs
    override fun setCompanionTabs(tabs: List<String>) { this.tabs = tabs }
    override fun guideNoticeAccepted(game: String) = true
    override fun acceptGuideNotice(game: String) {}
}

/** `-PmonIcons` icon sheets, relative to the app's assets dir (the render working dir); null when unset.
 * The docs render (DocsShots.kt) draws invented creatures instead. */
fun monIcon(species: Int): String? = if (docsMode) creatureIcon(species) else
    System.getProperty("monIcons").orEmpty().takeIf { it.isNotBlank() }?.let { "$it/$species.png" }

/** [snapshot] with the PC's boxes: box 1 nearly full (two gaps), box 2 a few, the rest empty; the default wallpapers. */
fun boxesSnapshot(): SnapshotView {
    val base = snapshot()
    fun mon(species: Int, level: Int) = base.party[species % 2].copy(
        species = species, name = speciesName(species), level = level, hp = 20 + level, maxHp = 20 + level, status = "",
        iconAsset = monIcon(species), exp = expProgress(species, level, 0L),
    )
    val first = listOf(1, 4, 7, 10, 13, 16, 19, 21, 23, 25, 27, 29, 0, 35, 37, 39, 41, 43, 46, 48, 0, 52, 54, 56, 58, 60, 63, 66, 69, 72)
    val boxes = (0 until 14).map { b ->
        val mons = when (b) {
            // The first holds an item (the window's last line).
            0 -> first.mapIndexed { i, sp -> if (sp == 0) null else mon(sp, 5 + i).copy(heldItemId = if (i == 0) 13 else 0) }
            1 -> List(30) { i -> listOf(74, 77, 79, 81, 84).getOrNull(i / 3)?.takeIf { i % 3 == 0 }?.let { mon(it, 20 + i) } }
            else -> List(30) { null }
        }
        com.pokedaisy.app.companion.data.BoxView("BOX${b + 1}", b % 4, mons)
    }
    return base.copy(pcBoxes = com.pokedaisy.app.companion.data.BoxesView(0, boxes))
}

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

/** The companion's rebind capture (FakeSettings hands it out): the `*-capture` shots press keys into it. */
var previewCapture = com.pokedaisy.app.companion.KeyCapture()

/** Holds [keys] down in [previewCapture] (as the game's activity would pass them on); [release] lets them go. */
fun SkikoComposeUiTest.pressKeys(vararg keys: Int, release: Boolean = false) {
    runOnIdle {
        keys.forEach { previewCapture.onKey(it, true) }
        if (release) keys.forEach { previewCapture.onKey(it, false) }
    }
    waitForIdle()
}

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
    oneTap: Boolean = false,
    expandOffer: Boolean = false,
    /** The ITEMS tab's USE / GIVE ([PreviewItemUse]): its row marks and party picks. */
    items: Boolean = false,
    /** With [items]: TAKE finds no room in the bag. */
    bagFull: Boolean = false,
    expand: Boolean = false,
    /** SETTINGS opened on this row, as after SWAP SCREENS. */
    settingsRow: String? = null,
    /** SETTINGS > TAB BAR's pick (null: the default five). */
    barTabs: List<String>? = null,
    snap: () -> SnapshotView = ::snapshot,
): () -> (@Composable () -> Unit) = {
    previewCapture = com.pokedaisy.app.companion.KeyCapture()
    DeviceBattery.status.value = battery
    val sv = snap()
    val back = com.pokedaisy.app.companion.ui.CompanionBack().also { previewBack = it }
    val content: @Composable () -> Unit = {
        CompanionScreen(
            sv, FakeSlots(), FakeSettings(hints, initialTabs = barTabs, oneTap = oneTap, expandOffer = expandOffer, expand = expand), FakeBattleInput(busy), initialTab = tab,
            initialMonIndex = mon, initialCardBack = cardBack, back = back, initialSettingsRow = settingsRow, itemUse = if (items) PreviewItemUse(sv.party, bagFull) else null,
        )
    }
    content
}

/**
 * USE / GIVE as retail FireRed rev 1 answers them (FieldItems.kt), for every game's preview - so each
 * BagPalette shows the row mark, though the app has it on FireRed rev 1 only so far. The lead holds
 * nothing, the second a QUICK CLAW; a Potion helps whoever is below full HP.
 */
class PreviewItemUse(private val party: List<MonView>, private val bagFull: Boolean = false) : com.pokedaisy.app.companion.ItemUse {
    override fun canTake() = true
    // BOXES' SWAP: the button shows, a pick goes through.
    override fun canSwapBox() = true
    override fun swapBox(box: Int, slot: Int, boxPersonality: Long, partySlot: Int, partyPersonality: Long, done: (com.pokedaisy.app.companion.data.BoxSwapResult) -> Unit) =
        done(com.pokedaisy.app.companion.data.BoxSwapResult.SWAPPED)
    // PC anywhere: the button shows; a tap answers "not now" (a menu open), as the BAG pops it up.
    override fun canPc() = true
    override fun openPc(done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) =
        done(com.pokedaisy.app.companion.data.ItemUseOutcome(com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW))
    // TAKE's answer: the QUICK CLAW can come off, or ([bagFull]) the bag has no room for it.
    override fun heldItems(done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) = done(com.pokedaisy.app.companion.data.PartyTargets(
        com.pokedaisy.app.companion.data.ItemUseResult.USED,
        party.mapIndexed { i, m ->
            val held = if (i == 1) 183 else 0
            val verdict = when {
                held == 0 -> com.pokedaisy.app.companion.data.SlotVerdict.NO_EFFECT
                bagFull -> com.pokedaisy.app.companion.data.SlotVerdict.BAG_FULL
                else -> com.pokedaisy.app.companion.data.SlotVerdict.OK
            }
            com.pokedaisy.app.companion.data.PartyTarget(i, m.personality, held, verdict)
        },
    ))
    private val support = com.pokedaisy.app.companion.data.FieldItemSupport(
        com.pokedaisy.app.companion.data.FIELD_ITEMS_FIRERED_REV1, setOf(13, 4, 200, 182, 183),
    )
    override fun canUse(itemId: Int) = support.canUse(itemId)
    override fun canGive(itemId: Int) = support.canGive(itemId)
    override fun marked(itemId: Int) = support.marked(itemId)
    override fun picksMon(itemId: Int) = support.picksMon(itemId)
    override fun picksMove(itemId: Int) = support.picksMove(itemId)
    override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {}
    override fun targets(itemId: Int, give: Boolean, done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) {
        done(com.pokedaisy.app.companion.data.PartyTargets(
            com.pokedaisy.app.companion.data.ItemUseResult.USED,
            party.mapIndexed { i, m ->
                val verdict = when {
                    m.isEgg -> com.pokedaisy.app.companion.data.SlotVerdict.EGG
                    give || m.hp in 1 until m.maxHp -> com.pokedaisy.app.companion.data.SlotVerdict.OK
                    else -> com.pokedaisy.app.companion.data.SlotVerdict.NO_EFFECT
                }
                com.pokedaisy.app.companion.data.PartyTarget(i, m.personality, if (i == 1) 183 else 0, verdict)
            },
        ))
    }
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

/** A real shiny battle, read the way the app reads it: the <game>_shiny_battle fixture (a foe SHINY ODDS made shiny,
 * headless) through the retail config the Poller marks (shinyBelow 8). */
fun realShinyBattle(state: Int): SnapshotView {
    val (cfg, fixture) = if (activeGame == GameKind.EMERALD) NATIVE_EMERALD_RETAIL to "emerald_shiny_battle"
        else NATIVE_FIRERED_REV1 to "firered_shiny_battle"
    // The render task runs in app/src/main/assets.
    val t = com.pokedaisy.app.companion.data.readNativeTelemetry(RamFixture(File("../../test/resources/fixtures/$fixture")), cfg.copy(shinyBelow = 8))
    // buildSnapshotView points the icon loader at the ROM's tables (read through the core): keep the preview's own.
    val icons = com.pokedaisy.app.companion.data.DecompIconSource.tables
    val real = com.pokedaisy.app.companion.data.buildSnapshotView(t)
    com.pokedaisy.app.companion.data.DecompIconSource.tables = icons
    // The preview's own battle (connected, its game, its party) with the real battlers in it.
    return battle(state).copy(battlePlayer = real.battlePlayer, battleOpponent = real.battleOpponent)
}

/** The wild foe shiny (SHINY ODDS' marks). */
fun SnapshotView.shinyFoe(): SnapshotView = copy(battleOpponent = battleOpponent.map { it.copy(shiny = true) })

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
        GameKind.EMERALD -> if (com.pokedaisy.app.companion.data.emeraldLegacy) com.pokedaisy.app.companion.data.NATIVE_EMERALD_LEGACY to "emerald_legacy"
            else NATIVE_EMERALD_RETAIL to "emerald_vanilla"
        GameKind.FIRERED -> NATIVE_FIRERED_REV1 to "firered_vanilla"
        GameKind.LAZARUS -> com.pokedaisy.app.companion.data.NATIVE_LAZARUS to "lazarus"
        else -> return null
    }
    if (path.isBlank()) return null
    val tables = cfg.guideTables!!
    PokedexSource.reader = RomFile(File(path).readBytes())
    // The render task runs in app/src/main/assets. -Phour=22: the game's clock at that hour (Lazarus's night lists).
    val fixtureRam = RamFixture(File("../../test/resources/fixtures/$fixture"))
    val hour = System.getProperty("hour").orEmpty().toIntOrNull()
    val hourAddr = tables.nightWild?.hourAddr
    val ram = if (hour == null || hourAddr == null) fixtureRam else object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int) =
            fixtureRam.readCoreMemory(addr, size).also { if (addr <= hourAddr && hourAddr < addr + size) it[(hourAddr - addr).toInt()] = hour.toByte() }
    }
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

/** The town the guide-town shots stand in: Emerald's RUSTBORO CITY, Lazarus's ACRISIA CITY (its day / night wild lists), FireRed's CELADON CITY. */
fun guideTown(game: GameKind) = when (game) {
    GameKind.EMERALD -> Triple(0, 3, 10)
    GameKind.LAZARUS -> Triple(0, 57, 0x5A)
    else -> Triple(3, 6, 94)
}

/**
 * The DEX page's sections (INFO / EVOLVE / AREA / MOVES) for retail FireRed / Emerald,
 * read from -Prom with the save fixture's dex flags; empty without a ROM.
 */
fun dexShots(game: GameKind, live: ((SnapshotView) -> SnapshotView)?, w: Int, h: Int, d: Float): List<Shot> {
    if (live == null) return emptyList()
    val (cfg, fixture) = when {
        game == GameKind.EMERALD && com.pokedaisy.app.companion.data.emeraldLegacy -> com.pokedaisy.app.companion.data.NATIVE_EMERALD_LEGACY to "emerald_legacy"
        game == GameKind.EMERALD -> NATIVE_EMERALD_RETAIL to "emerald_vanilla"
        else -> NATIVE_FIRERED_REV1 to "firered_vanilla"
    }
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
                com.pokedaisy.app.companion.ui.theme.AppTheme {
                    val ui = androidx.compose.runtime.remember {
                        com.pokedaisy.app.companion.ui.DexUiState(androidx.compose.foundation.lazy.LazyListState()).apply { open = entry; this.section = section }
                    }
                    // As CompanionScreen sets it up (a short screen: the RG DS's -PcompanionH=480).
                    com.pokedaisy.app.companion.ui.CompanionScreenSize { com.pokedaisy.app.companion.ui.PokedexEntryScreen(dex, ui, entry, guide = s.guideTables) }
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
    override fun thumbBytes(url: String) = File(url).takeIf { it.isFile }?.let { f ->
        // The docs' creature sheets have two frames: show the first, like a cover would.
        if (!docsMode) f.readBytes() else ImageIO.read(f).let { img ->
            java.io.ByteArrayOutputStream().also { ImageIO.write(img.getSubimage(0, 0, img.width, minOf(img.width, img.height)), "png", it) }.toByteArray()
        }
    }
}

/** [make] with a SteamGridDB key saved ([activity] clears it first - the prefs shim is shared by every shot). */
fun withApiKey(make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    Prefs(android.content.Context()).steamGridDbApiKey = "preview-key"
    return make()
}

/** [make] with ASPECT = [aspect] and the STATUS BAR on, plus a savestate thumbnail for the
 * ASPECT preview when a game frame is around: native-capture/menu-shots/thor/top.png (untracked -
 * the Thor's top screen, `adb exec-out screencap`, cropped to the game and scaled to 240x160). */
fun withAspect(aspect: com.pokedaisy.app.companion.GameAspect, make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    val ctx = android.content.Context()
    Prefs(ctx).gameAspect = aspect
    Prefs(ctx).statusBar = true
    val shot = File("../../../../native-capture/menu-shots/thor/top.png")
    if (shot.isFile) shot.copyTo(File(ctx.filesDir, "states/0badcafe/ss1.png").apply { parentFile.mkdirs() }, overwrite = true)
    return make()
}

/** TOUCH CONTROLS set to [style] before [make] (the activity's reset puts the defaults back first). */
fun withTouch(style: com.pokedaisy.app.companion.TouchControlsStyle, make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    Prefs(android.content.Context()).touchControlsStyle = style
    return make()
}

/** A [w]x[h] phone at [density] with the touch controls as [style] lays them out, over the stand-in game. */
fun touchScene(w: Int, h: Int, density: Float, style: com.pokedaisy.app.companion.TouchControlsStyle): () -> (@Composable () -> Unit) = {
    {
        val stage = com.pokedaisy.app.companion.ui.simulateTouchStage(w.toFloat(), h.toFloat(), density)
        com.pokedaisy.app.companion.ui.TouchControlsPreview(stage, style, null, androidx.compose.ui.Modifier.fillMaxSize())
    }
}

/** Taps [button] in a [w]x[h] EDIT LAYOUT at [density] (its place worked out the way the editor places it). */
fun SkikoComposeUiTest.tapTouchButton(w: Int, h: Int, density: Float, style: com.pokedaisy.app.companion.TouchControlsStyle, button: com.pokedaisy.app.companion.TouchButton) {
    val stage = com.pokedaisy.app.companion.ui.simulateTouchStage(w.toFloat(), h.toFloat(), density)
    val placed = com.pokedaisy.app.companion.ui.TouchGeometry.place(style, stage.area.width, stage.area.height, density, stage.portrait)
    val item = placed.item(button) ?: return
    onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(stage.area.left + item.cx, stage.area.top + item.cy)) }
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
    val rom = File(ctx.filesDir, "roms/firered-hack.gba")
    val crc = SettingsActivity.cheatCrcOf(rom)!!
    if (seeded) com.pokedaisy.app.cheats.CheatStore.forCrc(ctx.filesDir, crc).save(previewCheats())
    Prefs(ctx).cheatsEnabled = seeded
    return make().apply { intent.putExtra(SettingsActivity.EXTRA_CHEATS_ROM, rom.absolutePath) }
}

/** [make] with two of the fake library's games hidden (after [activity]'s reset). */
fun withHidden(make: () -> androidx.activity.ComponentActivity): androidx.activity.ComponentActivity {
    val ctx = android.content.Context()
    val roms = File(ctx.filesDir, "roms")
    Prefs(ctx).hiddenRoms = setOf(File(roms, "Pokemon Gaia (v3.2).gba").absolutePath, File(roms, "1636 - Pokemon Radical Red.gba").absolutePath)
    return make()
}

/** Runs the activity's onCreate against a fake library and returns what it passed to setContent. */
/** The preview library dated ([com.pokedaisy.app.RomAdded]): every game set up a week ago, but
 * Gaia and Lazarus added an hour and a day-and-a-half ago (only Gaia still NEW), Unbound just now. */
fun datedLibrary() {
    val ctx = android.content.Context()
    val now = System.currentTimeMillis()
    val hour = 3_600_000L
    val roms = File(ctx.filesDir, "roms").listFiles().orEmpty().filter { it.isFile }
    val fresh = mapOf("Pokemon Gaia (v3.2).gba" to now - hour, "Pokemon Lazarus (v2.0).gba" to now - 36 * hour, "Pokemon Unbound (v2.1.1.1).gba" to now)
    File(ctx.filesDir, "rom-added.tsv").writeText(roms.joinToString("") { f ->
        val at = fresh[f.name]
        "${f.absolutePath}\t${at ?: now - 7 * 24 * hour}\t${if (at == null) 1 else 0}\n"
    })
    com.pokedaisy.app.RomAdded.reload()
    Prefs(ctx).librarySort = com.pokedaisy.app.LibrarySort.NAME.id
    Prefs(ctx).favoriteRoms = emptySet()
}

/** [datedLibrary] with two favorites: Radical Red and Emerald. */
fun favoriteLibrary() {
    datedLibrary()
    val ctx = android.content.Context()
    val roms = File(ctx.filesDir, "roms")
    Prefs(ctx).favoriteRoms = setOf("1636 - Pokemon Radical Red.gba", "Pokemon - Emerald Version (USA, Europe).gba")
        .map { File(roms, it).absolutePath }.toSet()
}

/**
 * [favoriteLibrary] with a game on every console (Yellow, Crystal, a Crystal hack, Platinum), so every quick
 * view has a pill, and a play history (Emerald last, then Crystal, Unbound, Yellow) for RECENT; [view] open.
 */
fun platformLibrary(view: com.pokedaisy.app.LibraryView = com.pokedaisy.app.LibraryView.ALL) {
    favoriteLibrary()
    val ctx = android.content.Context()
    val prefs = Prefs(ctx)
    val roms = File(ctx.filesDir, "roms")
    val extra = mapOf(
        "Pokemon - Yellow Version (USA, Europe).gb" to Triple("Pokémon Yellow", "GB", "GB"),
        "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc" to Triple("Pokémon Crystal", "GBC", "GBC"),
        "Pokemon Crystal Clear (v2.6.0).gbc" to Triple("Pokémon Crystal Clear", "HACK", "GBC"),
        "4997 - Pokemon - Platinum Version (USA).nds" to Triple("Pokémon Platinum", "NDS", "NDS"),
    )
    extra.keys.forEach { File(roms, it).apply { writeText("x"); setLastModified(1_700_000_000_000L) } }
    // Dated a week ago like the others (datedLibrary), so they aren't NEW.
    val added = File(ctx.filesDir, "rom-added.tsv")
    added.appendText(extra.keys.joinToString("") { "${File(roms, it).absolutePath}\t${System.currentTimeMillis() - 7 * 24 * 3_600_000L}\t1\n" })
    com.pokedaisy.app.RomAdded.reload()
    // Every game's title, category and console (GameTitles' cache): the activity()'s six are GBA.
    val titles = File(ctx.filesDir, "rom-titles.tsv")
    // activity()'s rows (its headers and six GBA carts) and these.
    val lines = titles.readLines().filter { it.isNotBlank() } + extra.map { (n, t) ->
        val f = File(roms, n)
        "${f.absolutePath}\t${f.length()}\t${f.lastModified()}\t${t.first}\t${t.second}\t${t.third}"
    }
    titles.writeText(lines.joinToString("") { "$it\n" })
    com.pokedaisy.app.GameTitles.reload()
    for (n in listOf("Pokemon - Yellow Version (USA, Europe).gb", "Pokemon Unbound (v2.1.1.1).gba",
            "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc", "Pokemon - Emerald Version (USA, Europe).gba")) {
        prefs.pushRecentRom(File(roms, n).absolutePath)
        Thread.sleep(2)
    }
    prefs.lastRomPath = File(roms, "Pokemon - Emerald Version (USA, Europe).gba").absolutePath
    prefs.libraryView = view.id
}

fun pcBoxes(icons: Boolean = false): () -> (@Composable () -> Unit) = {
    val state = pcBoxesDemo()
    val content: @Composable () -> Unit = {
        com.pokedaisy.app.companion.ui.PcBoxesScreen(
            state, "Pokémon FireRed", com.pokedaisy.app.companion.ui.rememberGbaTextMetrics(), onBack = {}, onSave = {},
            icons = if (icons) demoIcons else null, onTrade = {},
        )
    }
    content
}

fun trade(setup: com.pokedaisy.app.companion.ui.TradeState.() -> Unit = {}): () -> (@Composable () -> Unit) = {
    val state = tradeDemo().apply(setup)
    val content: @Composable () -> Unit = {
        com.pokedaisy.app.companion.ui.TradeScreen(
            state, com.pokedaisy.app.companion.ui.rememberGbaTextMetrics(), onBack = {}, onSave = {}, iconsA = demoIcons, iconsB = demoIcons,
        )
    }
    content
}

/** The Library just after an update, its WHAT'S NEW up with 1.3.2's notes. */
fun whatsNewLibrary() = LibraryActivity().apply { whatsNew = com.pokedaisy.app.companion.WhatsNew.notesFor("1.3.3") }

/** The Library after 5 hours of play: the support window up. */
fun supportLibrary() = LibraryActivity().apply { supportAsk = true }

/** The activity [activity] made last (a shot's action presses its keys). */
var lastActivity: androidx.activity.ComponentActivity? = null

fun activity(expandIntro: Boolean = false, start: Boolean = false, make: () -> androidx.activity.ComponentActivity): () -> (@Composable () -> Unit) = {
    val ctx = android.content.Context()
    val roms = File(ctx.filesDir, "roms").apply { mkdirs() }
    // Dump-style file names, as players have them; firered-hack stays unrecognised (a hack it doesn't know).
    val names = listOf(
        "firered-hack.gba", "Pokemon Unbound (v2.1.1.1).gba", "Pokemon - Emerald Version (USA, Europe).gba",
        "1636 - Pokemon Radical Red.gba", "Pokemon Gaia (v3.2).gba", "Pokemon Emerald Imperium (v1.3.1).gba",
    )
    roms.listFiles()?.forEach { it.delete() }
    names.forEach { File(roms, it).apply { writeText("x"); setLastModified(1_700_000_000_000L) } }
    // What GameTitles.identify would have cached for the real ROMs (the fakes don't hash to them).
    val titles = mapOf(names[1] to "Pokémon Unbound", names[2] to "Pokémon Emerald", names[3] to "Pokémon Radical Red", names[4] to "Pokémon Gaia")
    // ... and what kind each is (the cover when a game has no art): Emerald retail GBA, the rest hacks.
    // Headed like GameTitles writes it (#known, #categories), else every row reads as stale; all GBA carts.
    val known = com.pokedaisy.app.GameTitles::class.java.getDeclaredField("KNOWN").apply { isAccessible = true }.get(null) as String
    File(ctx.filesDir, "rom-titles.tsv").writeText("$known\n#categories\t2\n" + names.joinToString("") { n ->
        val f = File(roms, n)
        val kind = if (n == names[2]) "GBA" else "HACK"
        "${f.absolutePath}\t${f.length()}\t${f.lastModified()}\t${titles[n].orEmpty()}\t$kind\tGBA\n"
    })
    com.pokedaisy.app.GameTitles.reload()
    File(ctx.filesDir, "saves").mkdirs()
    File(ctx.filesDir, "saves/firered-hack.sav").writeText("x")
    val prefs = Prefs(ctx)
    // ScreenScraper off, as players get it (FeatureFlags): its shots turn it on with withScreenScraper in their make().
    com.pokedaisy.app.BuildConfig.FEATURE_SCREENSCRAPER = false
    com.pokedaisy.app.ScreenScraperClient.enabled = false
    // EXPAND's one-time introduction covers the Library: only its own shot shows it.
    prefs.expandIntroSeen = !expandIntro
    // The classic library, no ScreenScraper popup: the launcher / popup shots turn them on in their make().
    prefs.dualScreenLauncher = false
    prefs.launcherSwapped = false
    prefs.screenScraperAsked = true
    prefs.screenScraperUser = null; prefs.screenScraperPassword = null
    File(ctx.filesDir, "media").deleteRecursively()
    prefs.steamGridDbApiKey = null
    prefs.libraryViewMode = 0
    prefs.romsFolders = emptyList()
    prefs.hiddenRoms = emptySet()
    prefs.hotkeysEnabled = true
    prefs.savesDirOverride = null
    prefs.gameAspect = com.pokedaisy.app.companion.GameAspect.ORIGINAL
    prefs.statusBar = false
    // The default theme (PokéDaisy); a shot that wants another sets it in its make().
    prefs.appTheme = com.pokedaisy.app.companion.ui.theme.DAISY_THEME_ID
    File(ctx.filesDir, "states").deleteRecursively()
    File(ctx.filesDir, "cheats").deleteRecursively()
    prefs.cheatsEnabled = false
    prefs.touchControlsStyle = com.pokedaisy.app.companion.TouchControlsStyle()
    prefs.portraitCompanionUnderGame = false
    prefs.libraryView = com.pokedaisy.app.LibraryView.ALL.id
    prefs.lastRomPath = File(roms, names[0]).absolutePath
    names.take(3).reversed().forEach { prefs.pushRecentRom(File(roms, it).absolutePath) }
    make().also { a ->
        lastActivity = a
        a.onCreate(null)
        if (start) a.onStart()
    }
    androidx.activity.compose.Captured.content!!
}

/** ScreenScraper turned on (FeatureFlags.screenScraper), as a build with `-Ppokedaisy.screenScraper=true` would have it. */
fun <T> withScreenScraper(make: () -> T): T {
    com.pokedaisy.app.BuildConfig.FEATURE_SCREENSCRAPER = true
    com.pokedaisy.app.ScreenScraperClient.enabled = true
    return make()
}

/** The launcher (a second screen, Settings > DUAL-SCREEN LAUNCHER on): [swapped] = the list on the
 * second screen; [media] = stand-in ScreenScraper media for two games; [signedIn] = an account. */
fun launcher(swapped: Boolean = false, media: Boolean = true, signedIn: Boolean = true, second: Boolean = false, grid: Boolean = false): () -> (@Composable () -> Unit) {
    val main = activity(start = true) {
        val ctx = android.content.Context()
        val prefs = Prefs(ctx)
        prefs.dualScreenLauncher = true
        prefs.launcherSwapped = swapped
        prefs.libraryViewMode = if (grid) 1 else 0
        if (signedIn) { prefs.screenScraperUser = "preview"; prefs.screenScraperPassword = "x" }
        if (media) fakeMedia(ctx)
        // Media or an account only mean something with ScreenScraper on; without either, the launcher players get now.
        if (media || signedIn) withScreenScraper { LibraryActivity() }.apply { launcherVideo = null }
        else LibraryActivity().apply { launcherVideo = null }
    }
    return if (!second) main else ({ main(); com.pokedaisy.app.CapturedSecond.content!! })
}

/**
 * Stand-in ScreenScraper media for the preview library's first two games - drawn shapes and made-up
 * facts, never a game's real art or text (the site / previews' rule).
 */
fun fakeMedia(ctx: android.content.Context) {
    val root = File(ctx.filesDir, "media").apply { deleteRecursively(); mkdirs() }
    // Real media from `gradle sscheck` (-PssMedia): each preview ROM gets its game's folder.
    val real = System.getProperty("ssMedia").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
    if (real != null) {
        mapOf(
            "firered-hack.gba" to "Pokémon FireRed", "Pokemon Unbound (v2.1.1.1).gba" to "Pokémon Unbound",
            "Pokemon - Emerald Version (USA, Europe).gba" to "Pokémon Emerald", "1636 - Pokemon Radical Red.gba" to "Pokémon Radical Red",
            "Pokemon Gaia (v3.2).gba" to "Pokémon Gaia", "Pokemon Emerald Imperium (v1.3.1).gba" to "Pokémon Emerald Imperium",
        ).forEach { (rom, title) -> File(real, title).takeIf { it.isDirectory }?.copyRecursively(File(root, rom), overwrite = true) }
        return
    }
    fun png(f: File, w: Int, h: Int, draw: (java.awt.Graphics2D) -> Unit) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        draw(g)
        g.dispose()
        f.parentFile.mkdirs()
        javax.imageio.ImageIO.write(img, "png", f)
    }
    listOf(
        Triple("firered-hack.gba", java.awt.Color(0xD8, 0x50, 0x30), "Preview Quest Red"),
        Triple("Pokemon Unbound (v2.1.1.1).gba", java.awt.Color(0x60, 0x40, 0xB0), "Preview Quest Violet"),
    ).forEach { (rom, tint, name) ->
        val dir = File(root, rom)
        png(File(dir, "box.img"), 480, 480) { g ->
            g.paint = java.awt.GradientPaint(0f, 0f, tint, 480f, 480f, tint.darker().darker())
            g.fillRect(0, 0, 480, 480)
            g.color = java.awt.Color(255, 255, 255, 200)
            g.fillOval(120, 140, 240, 240)
            g.color = java.awt.Color.WHITE
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 44)
            g.drawString("BOX ART", 150, 90)
        }
        png(File(dir, "screenshot.img"), 240, 160) { g ->
            g.color = java.awt.Color(0x58, 0xA8, 0x48); g.fillRect(0, 0, 240, 160)
            g.color = java.awt.Color(0x38, 0x80, 0x30)
            for (x in 0 until 240 step 16) for (y in 0 until 160 step 16) if ((x + y) / 16 % 3 == 0) g.fillRect(x, y, 16, 16)
            g.color = java.awt.Color(0xF0, 0xE0, 0xA0); g.fillRect(96, 0, 48, 160)
            g.color = tint; g.fillRect(112, 72, 16, 16)
        }
        png(File(dir, "fanart.img"), 1280, 720) { g ->
            g.paint = java.awt.GradientPaint(0f, 0f, tint.brighter(), 1280f, 720f, java.awt.Color(0x20, 0x30, 0x60))
            g.fillRect(0, 0, 1280, 720)
            g.color = java.awt.Color(255, 255, 255, 60)
            for (i in 0 until 9) g.fillOval(i * 150 - 40, 380 - (i % 3) * 120, 260, 260)
        }
        png(File(dir, "wheel.img"), 800, 200) { g ->
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 96)
            g.color = java.awt.Color(0x20, 0x20, 0x40); g.drawString("PREVIEW", 112, 148)
            g.color = java.awt.Color(0xF8, 0xD0, 0x30); g.drawString("PREVIEW", 104, 140)
        }
        File(dir, "info.json").writeText(
            org.json.JSONObject()
                .put("id", "1").put("found", true).put("checkedAt", 1L)
                .put("name", name)
                .put("synopsis", "A made-up story for the preview: a young trainer sets out from a quiet town with one partner, crosses eight routes, meets a rival at every turn and finds out what the strange tower on the hill is hiding.")
                .put("released", "2026-01-01")
                .put("developer", "Preview Studio")
                .put("publisher", "Sample Games")
                .put("genre", "Role playing game / Adventure")
                .put("players", "1-2")
                .put("rating", "17")
                .toString(),
        )
    }
}

/** Presses [code] (a KeyEvent.KEYCODE_*) on the activity the shot made. */
fun SkikoComposeUiTest.pressKey(code: Int) {
    runOnIdle {
        lastActivity?.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        lastActivity?.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }
    waitForIdle()
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
    // PLAYER LOOK's heroes, kept from the same ROMs (the `*-look` shots wear one).
    val filesDir = android.content.Context().filesDir
    // Ruby's build is retail byte for byte: Ruby / Sapphire's two heroes.
    for (f in (roms + File(decomps, "pokeruby/pokeruby_rev1.gba").path).filter { it.isNotBlank() }.map(::File).filter { it.isFile }.distinct()) {
        if (com.pokedaisy.app.companion.data.LookArt.available(filesDir).size == com.pokedaisy.app.companion.data.LookChar.entries.size) break
        val kept = com.pokedaisy.app.companion.data.LookArt.keep(filesDir, com.pokedaisy.app.companion.data.PlayerLook.scan(f.readBytes()))
        if (kept.isNotEmpty()) println("player look: $kept from ${f.name}")
    }
    // Its Pokémon, from the hacks with following Pokémon among them (each species from the first MonArt.Source that has it).
    for (f in roms.filter { it.isNotBlank() }.map(::File).filter { it.isFile }) {
        val n = com.pokedaisy.app.companion.data.MonArt.keep(filesDir, f.readBytes())
        if (n > 0) println("player look: $n Pokémon from ${f.name}")
    }
}

/** [content] with PLAYER LOOK's [look] worn (the other game's hero: Red in Emerald, Brendan in FireRed). */
fun wearing(game: GameKind, content: () -> (@Composable () -> Unit)): () -> (@Composable () -> Unit) = {
    com.pokedaisy.app.companion.data.PlayerLook.worn.value =
        if (game == GameKind.EMERALD) com.pokedaisy.app.companion.data.LookChar.RED else com.pokedaisy.app.companion.data.LookChar.BRENDAN
    content()
}

/** The game's home town, which its own region map shows (Emerald has no Pallet Town rect). */
fun homeMapsec(game: GameKind) = when (game) {
    GameKind.EMERALD, GameKind.EMERALD_ROGUE -> 0x00 // MAPSEC_LITTLEROOT_TOWN
    GameKind.LAZARUS -> 0x5A // Acrisia City
    else -> 0x58 // MAPSEC_PALLET_TOWN
}

/** A Pokémon in the game's regional Dex, for the DEX entry shots: Sinnoh's opens on TURTWIG. */
fun ndsFirstMon(game: GameKind) = when (game) {
    GameKind.PLATINUM -> "TURTWIG"
    GameKind.BLACK, GameKind.WHITE, GameKind.BLACK2, GameKind.WHITE2 -> "Snivy"
    else -> "BULBASAUR"
}

/** A DS game's real snapshot (-PndsFixture): the fixture's main RAM read the way the app reads it. */
fun ndsSnapshot(): SnapshotView {
    val main = File("../../test/resources/fixtures/${System.getProperty("ndsFixture")}/mainram.bin").readBytes()
    val reader = object : com.pokedaisy.app.companion.data.MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int): ByteArray {
            val o = (addr - 0x02000000L).toInt()
            return if (o < 0 || o + size > main.size) ByteArray(size) else main.copyOfRange(o, o + size)
        }
    }
    // connected / game: what the Poller adds once it has detected the cart
    val telemetry = com.pokedaisy.app.companion.data.gen5ConfigFor(activeGame)?.let { com.pokedaisy.app.companion.data.readGen5Telemetry(reader, it) }
        ?: com.pokedaisy.app.companion.data.readGen4Telemetry(reader, com.pokedaisy.app.companion.data.gen4ConfigFor(activeGame) ?: error("not a DS game: $activeGame"))
    return com.pokedaisy.app.companion.data.buildSnapshotView(telemetry).copy(connected = true, game = activeGame)
}

fun main(args: Array<String>) {
    // The docs' shots (gradle docsShots) never show a game's art: no ROM art, only the invented map.
    if (docsMode) installDocsArt() else romArt()
    // A DS game's art from its cart (-PndsRom): icons, item icons, the Pokégear map's pieces.
    System.getProperty("ndsRom").orEmpty().takeIf { it.isNotBlank() }?.let { path ->
        val dir = RomArt.dir(android.content.Context().filesDir)
        com.pokedaisy.app.companion.data.PokegearMap.dir = dir
        com.pokedaisy.app.companion.data.NdsArt.dir = dir
        if (com.pokedaisy.app.companion.data.NdsArt.extractTo(File(path), dir)) println("nds art: from ${File(path).name}")
    }
    // A FireRed-engine hack's own region map (Unbound, Odyssey, ...) from -Prom.
    System.getProperty("rom").orEmpty().takeIf { it.isNotBlank() }?.let { path ->
        val f = File(path)
        RomRegionMap.current = RomRegionMap.loadNow(android.content.Context().filesDir, "preview-%08x".format(f.name.hashCode()), f)
    }
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val only = args.getOrElse(1) { "" }.split(",").filter { it.isNotBlank() }
    val game = GameKind.valueOf(System.getProperty("game") ?: "FIRERED")
    activeGame = game
    // -PgameText=EMERALD_LEGACY: Emerald Legacy runs as EMERALD under its own text key (its names, config, fixture).
    System.getProperty("gameText").orEmpty().takeIf { it.isNotBlank() }?.let { com.pokedaisy.app.companion.data.romGameCode = it }
    // -Plang=FR: the app's text in another language (saved like the LANGUAGE setting, which the activities re-apply).
    System.getProperty("lang").orEmpty().takeIf { it.isNotBlank() }?.let { code ->
        runCatching { com.pokedaisy.app.Prefs(android.content.Context()).appLanguage = code }
        com.pokedaisy.app.companion.i18n.L10n.apply(code, null)
    }
    // -Pdark=true: DARK MODE on for every shot.
    if (System.getProperty("dark") == "true") com.pokedaisy.app.companion.ui.theme.DarkMode.override = true
    val g = game.name.lowercase()
    // The battle menu's POKéMON button: PkMn on the Game Boy games (drawn twice there, bold: the first copy is tapped).
    val pokemonButton = if (game.isGameBoyGame) "PkMn" else "POKéMON"
    val live = liveGuide()
    // AYN Thor: bottom screen 1240x1080 (landscape), top screen 1920x1080.
    // -PcompanionW / -PcompanionH: a single-screen device's side panel instead, at its density.
    val panelW = System.getProperty("companionW").orEmpty().toIntOrNull()
    val bh = System.getProperty("companionH").orEmpty().toIntOrNull() ?: 1080
    val bw = panelW ?: 1240
    // -PcompanionDensity: a second screen of its own size at the device's density (the RG DS: -PcompanionW=640
    // -PcompanionH=480 -PcompanionDensity=1.5), not a side panel's.
    val bd = System.getProperty("companionDensity").orEmpty().toFloatOrNull()
        ?: if (panelW != null) com.pokedaisy.app.companion.ui.sidePanelDensity(bw, bh) else 2.625f
    val tw = 1920; val th = 1080; val td = 2.5f
    val real = if (System.getProperty("ndsFixture").orEmpty().isNotBlank()) listOf(
        Shot("$g-real-party", bw, bh, bd, companion("PARTY", snap = ::ndsSnapshot)),
        Shot("$g-real-items", bw, bh, bd, companion("ITEMS", snap = ::ndsSnapshot)),
        // Another pocket: its colours on the backdrop and the band.
        Shot("$g-real-items-medicine", bw, bh, bd, companion("ITEMS", snap = ::ndsSnapshot)) {
            onNodeWithContentDescription("Medicine").performClick()
        },
        // The machines: a move's name over its number, HMs in their tag.
        Shot("$g-real-items-tms", bw, bh, bd, companion("ITEMS", snap = ::ndsSnapshot)) {
            onNodeWithContentDescription("TMs & HMs").performClick()
        },
        // An item open and starred: the red card, its star and the FAVORITES button at the end of the pockets.
        Shot("$g-real-items-favorite", bw, bh, bd, companion("ITEMS", snap = ::ndsSnapshot)) {
            onNodeWithText("Quick Claw").performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
            onNodeWithContentDescription("FAVORITE").performClick()
        },
        Shot("$g-real-map", bw, bh, bd, companion("MAP", snap = ::ndsSnapshot)),
        // Black's Town Map: PLACES, a place picked from it, and a tap by a town's point (the cursor snaps to it).
        *(if (game.isGen5) arrayOf(
            Shot("$g-real-map-places", bw, bh, bd, companion("MAP", snap = ::ndsSnapshot)) { onNodeWithText("PLACES").performClick() },
            Shot("$g-real-map-pick", bw, bh, bd, companion("MAP", snap = ::ndsSnapshot)) {
                onNodeWithText("PLACES").performClick()
                onNodeWithText("Castelia City", ignoreCase = true).performClick()
            },
            Shot("$g-real-map-tap", bw, bh, bd, companion("MAP", snap = ::ndsSnapshot)) {
                onRoot().performTouchInput { click(Offset(37 + 1166 * (136f / 256), 37 + 886 * (46f / 168))) }
            },
        ) else emptyArray()),
        Shot("$g-real-dex", bw, bh, bd, companion("DEX", snap = ::ndsSnapshot)),
        Shot("$g-real-dex-entry", bw, bh, bd, companion("DEX", snap = ::ndsSnapshot)) {
            onAllNodesWithText(ndsFirstMon(game), substring = true, ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-real-dex-johto", bw, bh, bd, companion("DEX", snap = ::ndsSnapshot)) {
            onAllNodesWithText("NATIONAL DEX", ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-real-dex-evolve", bw, bh, bd, companion("DEX", snap = ::ndsSnapshot)) {
            onAllNodesWithText(ndsFirstMon(game), substring = true, ignoreCase = true).onFirst().performClick()
            onAllNodesWithText("EVOLVE", ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-real-dex-moves", bw, bh, bd, companion("DEX", snap = ::ndsSnapshot)) {
            onAllNodesWithText(ndsFirstMon(game), substring = true, ignoreCase = true).onFirst().performClick()
            onAllNodesWithText("MOVES", ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-real-map-zoom", bw, bh, bd, companion("MAP", snap = ::ndsSnapshot)) {
            onNodeWithContentDescription("ZOOM").performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-real-guide", bw, bh, bd, companion("GUIDE", snap = ::ndsSnapshot)),
        // With a battle fixture (-PndsFixture=black_battle, platinum_battle): the companion opens on the battle, as in game.
        Shot("$g-real-battle", bw, bh, bd, companion("PARTY", snap = ::ndsSnapshot)),
        Shot("$g-real-battle-info", bw, bh, bd, companion("PARTY", snap = ::ndsSnapshot)) { onNodeWithText("INFO").performClick() },
        Shot("$g-real-battle-suggestions", bw, bh, bd, companion("PARTY", snap = ::ndsSnapshot)) {
            onNodeWithText("SUGGESTIONS").performClick()
        },
        // HERE on a route: the fixture's save moved to its first route's grass (Platinum's ROUTE 201, HG / SS's ROUTE 29,
        // Black's Route 1).
        Shot("$g-real-guide-route", bw, bh, bd, companion("GUIDE", snap = {
            ndsSnapshot().let { s ->
                val header = when (game) {
                    GameKind.PLATINUM -> 342
                    GameKind.BLACK, GameKind.WHITE -> 317
                    GameKind.BLACK2, GameKind.WHITE2 -> 437   // Route 19, by Aspertia City
                    else -> 33
                }
                val name = when (game) {
                    GameKind.PLATINUM -> com.pokedaisy.app.companion.data.mapPlacesPlatinum
                    GameKind.BLACK, GameKind.WHITE -> com.pokedaisy.app.companion.data.mapPlacesBlack
                    GameKind.BLACK2, GameKind.WHITE2 -> com.pokedaisy.app.companion.data.mapPlacesBlack2
                    else -> com.pokedaisy.app.companion.data.mapPlacesHeartGold
                }[header].orEmpty()
                s.copy(mapNum = header, location = s.location.copy(mapSecName = name))
            }
        })),
        Shot("$g-real-guide-boss", bw, bh, bd, companion("GUIDE", snap = ::ndsSnapshot)) {
            onAllNodesWithText("NEXT BOSS", ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-real-guide-whereis", bw, bh, bd, companion("GUIDE", snap = ::ndsSnapshot)) {
            onAllNodesWithText("WHERE IS", ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-real-guide-stuck", bw, bh, bd, companion("GUIDE", snap = ::ndsSnapshot)) {
            onAllNodesWithText("STUCK?", ignoreCase = true).onFirst().performClick()
        },
    ) else emptyList()
    val all = real + listOf(
        Shot("$g-party", bw, bh, bd, companion("PARTY")),
        // BOXES: the PC's box screen (FireRed's art from -Prom / the decomp build), a summary, the box list, the next box.
        Shot("$g-boxes", bw, bh, bd, companion("BOXES", snap = ::boxesSnapshot)),
        Shot("$g-boxes-detail", bw, bh, bd, companion("BOXES", snap = ::boxesSnapshot, items = true)) {
            onAllNodesWithContentDescription(speciesName(4), ignoreCase = true).onFirst().performClick()
        },
        // SWAP: the summary's button opens the party pick; a pick says what happened.
        Shot("$g-boxes-swap", bw, bh, bd, companion("BOXES", snap = ::boxesSnapshot, items = true)) {
            onAllNodesWithContentDescription(speciesName(4), ignoreCase = true).onFirst().performClick()
            waitForIdle()
            onAllNodesWithText("SWAP", ignoreCase = true).onFirst().performClick()
        },
        Shot("$g-boxes-swapped", bw, bh, bd, companion("BOXES", snap = ::boxesSnapshot, items = true)) {
            onAllNodesWithContentDescription(speciesName(4), ignoreCase = true).onFirst().performClick()
            waitForIdle()
            onAllNodesWithText("SWAP", ignoreCase = true).onFirst().performClick()
            waitForIdle()
            // The first party slot (top left), by where it is: the slots have no text node to find.
            onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.27f, height * 0.15f)) }
        },
        // SWAP's animation, held at moments of it: white, shrinking, the sparks crossing, landing, landed.
        *listOf(300L, 470L, 840L, 1220L, 1600L).map { ms ->
            Shot("$g-boxes-swapanim-$ms", bw, bh, bd, {
                val tab = companion("BOXES", snap = ::boxesSnapshot)()
                val s = boxesSnapshot()
                val boxMon = s.pcBoxes!!.boxes[0].mons[3]!!
                val anim = com.pokedaisy.app.companion.ui.BoxSwapAnim(boxMon, s.party[0], "BOX1", "")
                ({
                    androidx.compose.foundation.layout.Box {
                        tab()
                        com.pokedaisy.app.companion.ui.BoxSwapAnimation(anim, s.pokedex?.tables, onDone = {}, at = ms)
                    }
                })
            })
        }.toTypedArray(),
        Shot("$g-boxes-pick", bw, bh, bd, companion("BOXES", snap = ::boxesSnapshot)) {
            onNodeWithContentDescription("Pick a box").performClick()
        },
        Shot("$g-boxes-next", bw, bh, bd, companion("BOXES", snap = ::boxesSnapshot)) {
            onNodeWithContentDescription("Next box").performClick()
        },
        // The tab on an upright phone: the window over the box.
        Shot("$g-boxes-tall", 1080, 1500, bd, companion("BOXES", snap = ::boxesSnapshot)),
        // The lead holds a Potion, the second a QUICK CLAW: the tag in each slot's corner and the game's marker by the icon.
        Shot("$g-party-held", bw, bh, bd, companion("PARTY") {
            snapshot().let { s -> s.copy(party = s.party.mapIndexed { i, m -> m.copy(heldItemId = listOf(13, 183).getOrElse(i) { 0 }) }) }
        }),
        // The second Pokémon nicknamed (GitHub #228): the slot shows the nickname, the summary adds the species.
        Shot("$g-party-nickname", bw, bh, bd, companion("PARTY") {
            snapshot().let { s -> s.copy(party = s.party.mapIndexed { i, m -> if (i == 1) m.copy(nickname = "Skyler") else m }) }
        }),
        Shot("$g-party-detail-nickname", bw, bh, bd, companion("PARTY", mon = 1) {
            snapshot().let { s -> s.copy(party = s.party.mapIndexed { i, m -> if (i == 1) m.copy(nickname = "Skyler") else m }) }
        }),
        // The second Pokémon's summary with its QUICK CLAW: TAKE, or BAG FULL where the bag has no room for it.
        Shot("$g-party-detail-take", bw, bh, bd, companion("PARTY", mon = 1, items = true)),
        Shot("$g-party-detail-bagfull", bw, bh, bd, companion("PARTY", mon = 1, items = true, bagFull = true)),
        Shot("$g-expand-offer", bw, bh, bd, companion("PARTY", expandOffer = true)),
        Shot("$g-party-detail", bw, bh, bd, companion("PARTY", mon = 0)),
        Shot("$g-party-detail-next", bw, bh, bd, companion("PARTY", mon = 0)) {
            onNodeWithContentDescription("Next Pokémon").performClick()
        },
        // In the game's home town, which its own region map shows (Emerald has no Pallet Town rect).
        // After SWAP SCREENS: SETTINGS again, on that row and scrolled to it.
        Shot("$g-settings-after-swap", bw, bh, bd, companion("SETTINGS", settingsRow = "SWAP SCREENS")),
        // APP > TIPS, with its NEW tag.
        Shot("$g-settings-tips", bw, bh, bd, companion("SETTINGS", settingsRow = "TIPS")),
        Shot("$g-settings-battery-low", bw, bh, bd, companion("SETTINGS", battery = BatteryStatus(9, charging = false))),
        Shot("$g-settings-battery-charging", bw, bh, bd, companion("SETTINGS", battery = BatteryStatus(100, charging = true))),
        Shot("$g-card", bw, bh, bd, companion("CARD", snap = ::card)),
        // PLAYER LOOK worn: the other game's hero on the card and the map, as the game's own now show them.
        Shot("$g-card-look", bw, bh, bd, wearing(game, companion("CARD", snap = ::card))),
        Shot("$g-map-look", bw, bh, bd, wearing(game, companion("MAP") {
            val sec = homeMapsec(game)
            snapshot().copy(location = lookupLocation(sec), regionMapSectionId = sec)
        })),
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
        // R.O.W.E.'s three maps (its region button reads MAP 2 / MAP 3): the buttons' bottom-left row on each.
        *(if (game == GameKind.ROWE) arrayOf(
            Shot("$g-map-region", bw, bh, bd, companion("MAP")) { onNodeWithText("MAP ", substring = true).performClick() },
            Shot("$g-map-region2", bw, bh, bd, companion("MAP")) {
                onNodeWithText("MAP ", substring = true).performClick()
                onNodeWithText("MAP ", substring = true).performClick()
            },
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
        // The device's BACK while a hotkey waits for keys reaches the capture first (the game's activity): it stops
        // waiting, the page stays.
        Shot("$g-back-settings-hotkeys-capture", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("SAVE STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("PRESS KEYS…").onFirst().assertExists()
            pressKeys(android.view.KeyEvent.KEYCODE_BACK)
            onAllNodesWithText("PRESS KEYS…").assertCountEquals(0) // no longer waiting...
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
        // SETTINGS > ONE-TAP MOVES: the action menu with the moves where FIGHT was.
        Shot("$g-battle-onetap", bw, bh, bd, companion("PARTY", oneTap = true) { battle(BATTLE_INPUT_ACTION_SELECT) }),
        // The POKéMON pane: from the action menu, and over the game's own party menu.
        Shot("$g-battle-switch", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onAllNodesWithText(pokemonButton)[0].performClick()
        },
        // A fuller party against Onix: BEST on the pick, OUT on the lead, a fainted Pidgey.
        Shot("$g-battle-switch-trainer", bw, bh, bd, companion("PARTY") {
            trainerBattle(BATTLE_INPUT_ACTION_SELECT).withBench().let { s ->
                s.copy(party = s.party.mapIndexed { i, m -> m.copy(personality = 0x1000L + i, hp = if (i == 1) 0 else m.hp) })
            }
        }) {
            onAllNodesWithText(pokemonButton)[0].performClick()
        },
        Shot("$g-battle-party-open", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_PARTY_OPEN) }),
        Shot("$g-battle-busy", bw, bh, bd, companion("PARTY", busy = true) { battle(BATTLE_INPUT_ACTION_SELECT) }),
        // A shiny wild foe: the battle screen's SHINY plate, INFO's SHINY pill and a double battle's sparkle.
        Shot("$g-battle-shiny", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT).shinyFoe() }),
        Shot("$g-battle-info-shiny", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT).shinyFoe() }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-shiny-double", bw, bh, bd, companion("PARTY") {
            val t = trainerBattle(BATTLE_INPUT_ACTION_SELECT)
            val foes = t.enemyParty.drop(1).mapIndexed { i, f -> f.copy(shiny = i == 0) }
            t.copy(isDoubleBattle = true, battlePlayer = listOf(t.party[0], t.party[1]), battleOpponent = foes, enemyParty = emptyList(), enemyActive = -1)
        }),
        Shot("$g-battle-info", bw, bh, bd, companion("PARTY") { battle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-trainer", bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick()
        },
        // The real thing: a headless battle's shiny foe, read from its RAM (FireRed / Emerald).
        Shot("$g-battle-shiny-real", bw, bh, bd, companion("PARTY") { realShinyBattle(BATTLE_INPUT_ACTION_SELECT) }),
        Shot("$g-battle-info-shiny-real", bw, bh, bd, companion("PARTY") { realShinyBattle(BATTLE_INPUT_ACTION_SELECT) }) {
            onNodeWithText("INFO").performClick()
        },
        // SHINY beside a ten-letter name and a status.
        Shot("$g-battle-info-shiny-long", bw, bh, bd, companion("PARTY") {
            battle(BATTLE_INPUT_ACTION_SELECT).shinyFoe().let { t -> t.copy(battleOpponent = t.battleOpponent.map { it.copy(name = "MASQUERAIN") }) }
        }) {
            onNodeWithText("INFO").performClick()
        },
        Shot("$g-battle-info-trainer6",bw, bh, bd, companion("PARTY") { trainerBattle(BATTLE_INPUT_ACTION_SELECT, six = true) }) {
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
        // Oak's Parcel starred: first in DEFAULT order, a star on its row and a gold one on the button.
        Shot("$g-items-favorite", bw, bh, bd, companion("ITEMS")) {
            onNodeWithText("Parcel", substring = true, ignoreCase = true).performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
            onNodeWithContentDescription("FAVORITE").performClick()
        },
        // ...and its FAVORITES tab opened: the starred items alone.
        Shot("$g-items-favorites-tab", bw, bh, bd, companion("ITEMS")) {
            onNodeWithText("Parcel", substring = true, ignoreCase = true).performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
            onNodeWithContentDescription("FAVORITE").performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
            onNodeWithText("FAVORITES").performClick()
        },
        // USE / GIVE (FieldItems.kt): the row marks (the Potion and the Poké Ball; Oak's Parcel has none)...
        Shot("$g-items-marks", bw, bh, bd, companion("ITEMS", items = true)),
        // PC anywhere: the PC button over the sort button, then its "not now" popped up over the bag.
        Shot("$g-items-pc", bw, bh, bd, companion("ITEMS", items = true)) {
            onNodeWithText("PC").performClick()
        },
        // ...beside a star, on the FAVORITES tab (Potion starred, as is Oak's Parcel, which gets no mark)...
        Shot("$g-items-marks-favorites", bw, bh, bd, companion("ITEMS", items = true)) {
            for (name in listOf("Potion", "Parcel")) {
                onNodeWithText(name, substring = true, ignoreCase = true).performClick()
                repeat(10) { mainClock.advanceTimeByFrame() }
                onNodeWithContentDescription("FAVORITE").performClick()
                repeat(10) { mainClock.advanceTimeByFrame() }
            }
            onNodeWithText("FAVORITES").performClick()
        },
        // ...and the party picks a Potion's USE and GIVE open.
        Shot("$g-items-use-pick", bw, bh, bd, companion("ITEMS", items = true)) {
            onNodeWithText("Potion", substring = true, ignoreCase = true).performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
            onNodeWithText("USE").performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
        },
        Shot("$g-items-give-pick", bw, bh, bd, companion("ITEMS", items = true)) {
            onNodeWithText("Potion", substring = true, ignoreCase = true).performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
            onNodeWithText("GIVE").performClick()
            repeat(10) { mainClock.advanceTimeByFrame() }
        },
        Shot("$g-states", bw, bh, bd, companion("STATES")),
        // A ROM the companion can't read: the notice over every tab but SETTINGS.
        Shot("$g-unsupported", bw, bh, bd, companion("PARTY") {
            SnapshotView(connected = false, error = "game code AMTE isn't a supported Pokémon game", unsupported = true)
        }),
        Shot("$g-settings", bw, bh, bd, companion("SETTINGS")),
        // MORE (left of the gear): the tabs off the bar as app icons, the TAB BAR link under them.
        Shot("$g-more", bw, bh, bd, companion("MORE")),
        Shot("$g-more-full", bw, bh, bd, companion("MORE", barTabs = listOf("PARTY", "MAP"))),
        // Every app icon at once (a game can't have all of them off the bar with a ROM-less preview).
        Shot("$g-more-icons", bw, bh, bd, {
            val content: @Composable () -> Unit = {
                com.pokedaisy.app.companion.ui.theme.AppTheme {
                    com.pokedaisy.app.companion.ui.MoreScreen(com.pokedaisy.app.companion.COMPANION_TABS, {}, {})
                }
            }
            content
        }),
        // A tile opens its tab with MORE lit; the link opens SETTINGS > TAB BAR.
        Shot("$g-more-open", bw, bh, bd, companion("MORE")) { onNodeWithTag("more-STATES").performClick(); repeat(30) { mainClock.advanceTimeByFrame() } },
        Shot("$g-more-tabbar", bw, bh, bd, companion("MORE")) { onNodeWithTag("more-tab-bar").performClick(); repeat(30) { mainClock.advanceTimeByFrame() } },
        // ASPECT on EXPAND: its AUTO ZOOM IN row under it.
        Shot("$g-settings-expand", bw, bh, bd, companion("SETTINGS", expand = true)) {
            onNodeWithText("AUTO ZOOM IN").performScrollTo()
        },
        Shot("$g-settings-ffspeed", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("FF SPEED").performClick() },
        Shot("$g-settings-touch", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // GAME BUTTONS' subtitle, further down the list.
        Shot("$g-settings-subtitles", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("TWEAKS").performScrollTo() },
        Shot("$g-settings-ffmusic", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("SPEED OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onAllNodesWithText("FF MUSIC").onFirst().performScrollTo().performClick() },
        // ASPECT's pick-list: WIDE tagged NOT YET (the preview's settings know no game).
        Shot("$g-settings-aspect", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("ASPECT").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-hotkeys", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // The list's end: RESTART GAME / CLOSE APP with their NEW tags, no keys.
        Shot("$g-settings-hotkeys-end", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("CLOSE APP").performScrollTo()
        },
        Shot("$g-settings-buttons", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("GAME BUTTONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // Off: the binds greyed out.
        Shot("$g-settings-hotkeys-off", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("ON").performClick()
        },
        // A hotkey waiting for keys (the game's screen asks for them): SELECT held, R1 on its way - CLEAR / CANCEL above.
        Shot("$g-settings-hotkeys-capture", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("LOAD STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            pressKeys(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, android.view.KeyEvent.KEYCODE_BUTTON_R1)
        },
        // A GBA button waiting for its button: CANCEL alone (A always keeps a key).
        Shot("$g-settings-buttons-capture", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("GAME BUTTONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("GBA A").performSemanticsAction(SemanticsActions.OnClick)
        },
        // X is SAVE STATE's: binding it to LOAD STATE asks first.
        Shot("$g-settings-hotkeys-clash", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("LOAD STATE").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            pressKeys(android.view.KeyEvent.KEYCODE_BUTTON_X, release = true)
            onAllNodesWithText("KEY IN USE").onFirst().assertExists()
        },
        // CLOSE GAME / RESTART GAME end the scrolling list.
        Shot("$g-settings-bottom", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("CLOSE COMPANION").performScrollTo() },
        Shot("$g-settings-close", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("CLOSE GAME").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // The D-pad onto FF, the first row of FF / SPEED, while MAKEOVER still fills the top: FF / SPEED's group tab lights.
        Shot("$g-settings-group-follow", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("FF").performSemanticsAction(SemanticsActions.RequestFocus)
        },
        // The COMPANION group, ONE-TAP MOVES under BATTLE HINTS.
        Shot("$g-settings-companion", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("ONE-TAP MOVES").performScrollTo()
        },
        // GAMEPLAY: WILD ENCOUNTERS, REUSABLE TMS, AUTO-RUN, RUN INDOORS, CHEATS, then WILD ENCOUNTERS flipped OFF and
        // REUSABLE TMS in view (OFF; -Pgame=UNBOUND: ON, greyed).
        Shot("$g-settings-controls", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("WILD ENCOUNTERS").performScrollTo()
        },
        Shot("$g-settings-reusable-tms", bw, bh, bd, companion("SETTINGS")) {
            // Scrolled to AUTO-RUN, the row under it: a greyed row (Unbound's, Radical Red's: BUILT_IN) won't scroll to itself here.
            onNodeWithText(com.pokedaisy.app.companion.i18n.tr("AUTO-RUN")).performScrollTo()
        },
        // PLAYER LOOK under REUSABLE TMS, then its tiles (OFF = the player's own hero, then the other games' heroes).
        Shot("$g-settings-player-look", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText(com.pokedaisy.app.companion.i18n.tr("RUN INDOORS")).performScrollTo()
        },
        Shot("$g-settings-player-look-pick", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText(com.pokedaisy.app.companion.i18n.tr("PLAYER LOOK")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // SHINY ODDS under REUSABLE TMS, then its pick-list (the debug build's, with 1 IN 4 and 1 IN 1).
        Shot("$g-settings-shiny-odds", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText(com.pokedaisy.app.companion.i18n.tr("CHEATS")).performScrollTo()
        },
        Shot("$g-settings-shiny-odds-pick", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText(com.pokedaisy.app.companion.i18n.tr("SHINY ODDS")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-wild", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("WILD ENCOUNTERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // DISPLAY OPTIONS: STATUS BAR, SHADERS, OVERLAY.
        Shot("$g-settings-display", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // LIGHT COMPANION's pick-list (AUTO / ON / OFF).
        Shot("$g-settings-light", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("LIGHT COMPANION").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // SPEED OPTIONS: SCENE SPEEDS (BATTLE / TEXT / MENU SPEED...) over FF MODE / FF MUSIC, then BATTLE SPEED's pick-list.
        Shot("$g-settings-scenes", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("SPEED OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-scenes-battle", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("SPEED OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("BATTLE SPEED").performClick()
        },
        // TWEAKS: the small on / off preferences.
        Shot("$g-settings-tweaks", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("TWEAKS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // SHADERS: its page (FILTER, GBA COLORS), then after one FILTER tap (it cycles: NONE -> LCD).
        Shot("$g-settings-shaders", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-shaders-filter", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("FILTER").performClick()
        },
        // OVERLAY: its pick-list (NONE, the built-in frames, an import).
        Shot("$g-settings-overlay", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("$g-settings-cheats", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("CHEATS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("$g-settings-tabs", bw, bh, bd, companion("SETTINGS")) { onNodeWithText("TAB BAR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        Shot("$g-settings-tabs-states", bw, bh, bd, companion("SETTINGS")) {
            onNodeWithText("TAB BAR").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("STATES").performSemanticsAction(SemanticsActions.OnClick)
        },
        *dexShots(game, live, bw, bh, bd).toTypedArray(),
        Shot("$g-guide", bw, bh, bd, companion("GUIDE") { live?.invoke(snapshot()) ?: snapshot() }),
        // HERE in a town (guideTown), with the save's flags.
        Shot("$g-guide-town", bw, bh, bd, companion("GUIDE") {
            val s = live?.invoke(snapshot()) ?: snapshot()
            val (group, num, sec) = guideTown(game)
            s.copy(mapGroup = group, mapNum = num, regionMapSectionId = sec, location = lookupLocation(sec))
        }),
        // The same, scrolled down to PEOPLE / ITEMS (the harness can't scroll a lazy list by touch).
        // Lazarus also to its end: the other time of day's section (AT NIGHT / BY DAY, -Phour).
        *(if (game == GameKind.LAZARUS) listOf(7, 14, 40) else listOf(7, 14)).map { first ->
            Shot("$g-guide-town-$first", bw, bh, bd, {
                val s = (live?.invoke(snapshot()) ?: snapshot()).let {
                    val (group, num, sec) = guideTown(game)
                    it.copy(mapGroup = group, mapNum = num, regionMapSectionId = sec, location = lookupLocation(sec))
                }
                val content: @Composable () -> Unit = {
                    com.pokedaisy.app.companion.ui.theme.AppTheme {
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
                            "FireRed", "Pallet Town", 224300, "14:05", battery = BatteryStatus(82, charging = false),
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
        // The quick views (pills over the games): ALL, FAVORITES, RECENT, one per console the library holds and HACKS,
        // each in its colour; the open one striped like the console's cover art.
        Shot("library-views", tw, th, td, activity { platformLibrary(); LibraryActivity() }),
        Shot("library-views-grid", tw, th, td, activity { platformLibrary(); LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        Shot("library-views-favorites", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.FAVORITES); LibraryActivity() }),
        Shot("library-views-recent", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.RECENT); LibraryActivity() }),
        Shot("library-views-gb", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.GB); LibraryActivity() }),
        Shot("library-views-gbc", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.GBC); LibraryActivity() }),
        Shot("library-views-gba", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.GBA); LibraryActivity() }),
        Shot("library-views-ds", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.DS); LibraryActivity() }),
        Shot("library-views-hacks", tw, th, td, activity { platformLibrary(com.pokedaisy.app.LibraryView.HACKS); LibraryActivity() }),
        Shot("library-views-phone", 1080, 2400, 2.625f, activity { platformLibrary(com.pokedaisy.app.LibraryView.GBA); LibraryActivity() }) {
            mainClock.advanceTimeBy(500)   // the open pill scrolled into view
        },
        // A game with no cover art: its kind (CategoryArt), at a list row's size and a grid tile's.
        Shot("library-category-art", 2000, 560, 2.625f, {
            {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF202020)).padding(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    for (size in listOf(44.dp, 120.dp)) {
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                            for (c in com.pokedaisy.app.RomCategory.entries) {
                                com.pokedaisy.app.companion.ui.CategoryArt(c, androidx.compose.ui.Modifier.size(size))
                            }
                        }
                    }
                }
            }
        }),
        // The dual-screen launcher: the list on the top screen, the selected game's media on the bottom.
        Shot("launcher-main", tw, th, td, launcher()),
        Shot("launcher-second", bw, bh, bd, launcher(second = true)),
        Shot("launcher-second-down", bw, bh, bd, launcher(second = true)) { pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) },
        // Signed out, no media: the cover, the library's facts and how to get the rest.
        // ScreenScraper off (the release): the list alone on the main screen, no second screen and no SWAP.
        Shot("launcher-main-off", tw, th, td, launcher(media = false, signedIn = false)),
        Shot("launcher-menu", tw, th, td, launcher()) { pressKey(android.view.KeyEvent.KEYCODE_BUTTON_B) },
        Shot("launcher-menu-down", tw, th, td, launcher()) {
            pressKey(android.view.KeyEvent.KEYCODE_BUTTON_B)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        },
        // After X: the media on the top screen (its wide layout), the list on the bottom.
        // The quick views and the grid view, as the classic library has them; DOWN moves the cursor a game down.
        Shot("launcher-main-down", tw, th, td, launcher()) { pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) },
        // UP off the top game: the open quick view's pill; RIGHT opens the next view (FAVORITES, then RECENT).
        Shot("launcher-views", tw, th, td, launcher()) { pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP) },
        Shot("launcher-views-right", tw, th, td, launcher()) {
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        },
        // UP again: the header's sort button; RIGHT walks the header.
        Shot("launcher-header", tw, th, td, launcher()) {
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        },
        Shot("launcher-header-right", tw, th, td, launcher()) {
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        },
        Shot("launcher-grid", tw, th, td, launcher(grid = true)),
        Shot("launcher-grid-down-right", tw, th, td, launcher(grid = true)) {
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        },
        // INFO from the menu, then B: back to the menu, on INFO (not to the games).
        Shot("launcher-menu-info-back", tw, th, td, launcher()) {
            runOnIdle { lastActivity?.onResume() }
            pressKey(android.view.KeyEvent.KEYCODE_BUTTON_B)
            pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            pressKey(android.view.KeyEvent.KEYCODE_BUTTON_A)
            pressKey(android.view.KeyEvent.KEYCODE_BUTTON_B)
        },
        Shot("launcher-swapped-main", tw, th, td, launcher(swapped = true)),
        Shot("launcher-swapped-second", bw, bh, bd, launcher(swapped = true, second = true)),
        Shot("launcher-phone-bottom", 1280, 960, bd, launcher(second = true)),
        // The update's one-time ScreenScraper window, over the classic library.
        Shot("library-ss-intro", tw, th, td, activity { Prefs(android.content.Context()).screenScraperAsked = false; withScreenScraper { LibraryActivity() } }),
        // PC BOXES (a game's library menu): the save's party and boxes, then a Pokémon picked, moved and saved.
        Shot("pc-boxes", tw, th, td, pcBoxes()),
        Shot("pc-boxes-selected", tw, th, td, pcBoxes()) { onAllNodesWithText("ABRA")[0].performClick() },
        Shot("pc-boxes-moving", tw, th, td, pcBoxes()) {
            onAllNodesWithText("ABRA")[0].performClick(); onNodeWithText("MOVE").performClick()
        },
        Shot("pc-boxes-moved", tw, th, td, pcBoxes()) {
            onAllNodesWithText("ABRA")[0].performClick(); onNodeWithText("MOVE").performClick()
            onNodeWithContentDescription("Next box").performClick(); onAllNodesWithText("PIDGEY")[0].performClick()
        },
        Shot("pc-boxes-save", tw, th, td, pcBoxes()) {
            onAllNodesWithText("ABRA")[0].performClick(); onNodeWithText("MOVE").performClick()
            onAllNodesWithText("EGG")[0].performClick()
            onAllNodesWithText("TREECKO")[0].performClick(); onNodeWithText("MOVE").performClick()
            onAllNodesWithText("EGG")[0].performClick(); onNodeWithText("SAVE").performClick()
        },
        Shot("pc-boxes-phone", 1080, 2400, 2.625f, pcBoxes()) { onAllNodesWithText("TREECKO")[0].performClick() },
        // With the party icons a ROM gives (stand-in dots here: no game art in this repo).
        Shot("pc-boxes-icons", tw, th, td, pcBoxes(icons = true)) { onAllNodesWithText("ABRA")[0].performClick() },
        Shot("pc-boxes-icons-phone", 1080, 2400, 2.625f, pcBoxes(icons = true)),
        // Editing: a party Pokémon's moves and item, the BAG page, an amount, ADD ITEM.
        Shot("pc-boxes-edit", tw, th, td, pcBoxes(icons = true)) { onAllNodesWithText("CHARIZARD")[0].performClick() },
        Shot("pc-boxes-move", tw, th, td, pcBoxes(icons = true)) {
            onAllNodesWithText("CHARIZARD")[0].performClick(); onNodeWithText("WING ATTACK").performClick()
        },
        Shot("pc-boxes-moved-move", tw, th, td, pcBoxes(icons = true)) {
            onAllNodesWithText("CHARIZARD")[0].performClick(); onNodeWithText("WING ATTACK").performClick()
            onNodeWithText("BLAST BURN").performClick()
        },
        Shot("pc-boxes-item", tw, th, td, pcBoxes(icons = true)) {
            onAllNodesWithText("CHARIZARD")[0].performClick(); onNodeWithText("LEFTOVERS").performClick()
        },
        Shot("pc-boxes-bag", tw, th, td, pcBoxes()) { onNodeWithText("BAG").performClick() },
        Shot("pc-boxes-bag-tm", tw, th, td, pcBoxes()) { onNodeWithText("BAG").performClick(); onNodeWithText("TMs & HMs").performClick() },
        Shot("pc-boxes-bag-amount", tw, th, td, pcBoxes()) { onNodeWithText("BAG").performClick(); onNodeWithText("POTION").performClick() },
        Shot("pc-boxes-bag-money", tw, th, td, pcBoxes()) { onNodeWithText("BAG").performClick(); onNodeWithText("MONEY").performClick() },
        Shot("pc-boxes-bag-add", tw, th, td, pcBoxes()) { onNodeWithText("BAG").performClick(); onNodeWithText("ADD ITEM").performClick() },
        Shot("pc-boxes-bag-phone", 1080, 2400, 2.625f, pcBoxes()) { onNodeWithText("BAG").performClick() },
        // TRADE: two saves side by side; KADABRA sent over (it evolves on arrival), MEW refused (no event), a swap.
        // Picks are set on the state (Emerald's KADABRA is its 4th, MEW its 6th; FireRed's box 1 has slot 22 free).
        Shot("trade", tw, th, td, trade()),
        Shot("trade-send", tw, th, td, trade { selA = BoxSlot(0, 21); selB = BoxSlot(0, 3) }),
        Shot("trade-evolve", tw, th, td, trade { selA = BoxSlot(0, 21); selB = BoxSlot(0, 3) }) { onNodeWithText("SEND").performClick() },
        Shot("trade-evolved", tw, th, td, trade { selA = BoxSlot(0, 21); selB = BoxSlot(0, 3) }) {
            onNodeWithText("SEND").performClick(); onNodeWithText("EVOLVE").performClick()
        },
        Shot("trade-refused", tw, th, td, trade { selA = BoxSlot(0, 21); selB = BoxSlot(0, 5) }),
        Shot("trade-swap", tw, th, td, trade { selA = BoxSlot(0, 0); selB = BoxSlot(0, 0) }),
        Shot("trade-phone", 1080, 2400, 2.625f, trade { selA = BoxSlot(0, 0); selB = BoxSlot(0, 0) }),
        Shot("library-expand-intro", tw, th, td, activity(expandIntro = true) { LibraryActivity() }),
        // A phone held upright (a Galaxy S9's 1080x2220 at 3x): the smaller text, the picture over the words, the buttons on screen.
        Shot("library-expand-intro-phone", 1080, 2220, 3f, activity(expandIntro = true) { LibraryActivity() }),
        // WHAT'S NEW after an update (1.3.2's notes): the Thor's top screen, phones both ways, the Duo Lite's and the RG DS's
        // 4:3 screens, a tablet.
        Shot("library-whats-new", tw, th, td, activity { whatsNewLibrary() }),
        Shot("library-support", tw, th, td, activity { supportLibrary() }),
        Shot("library-support-phone", 1080, 2400, 2.625f, activity { supportLibrary() }),
        Shot("library-support-phone-side", 2400, 1080, 2.625f, activity { supportLibrary() }),
        Shot("library-support-4x3", 1280, 960, 2f, activity { supportLibrary() }),
        Shot("library-support-rgds", 640, 480, 1.5f, activity { supportLibrary() }),
        Shot("library-whats-new-phone", 1080, 2400, 2.625f, activity { whatsNewLibrary() }),
        Shot("library-whats-new-phone-side", 2400, 1080, 2.625f, activity { whatsNewLibrary() }),
        Shot("library-whats-new-4x3", 1280, 960, 2f, activity { whatsNewLibrary() }),
        Shot("library-whats-new-rgds", 640, 480, 1.5f, activity { whatsNewLibrary() }),
        Shot("library-whats-new-tablet", 2560, 1600, 2f, activity { whatsNewLibrary() }),
        Shot("settings-phone", 1080, 2220, 3f, activity { SettingsActivity() }),
        // TOUCH CONTROLS: the page with its preview, on a phone both ways, and EDIT LAYOUT over the stand-in.
        Shot("settings-touch", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-touch-phone", 1080, 2400, 2.625f, activity { SettingsActivity() }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-touch-phone-side", 2400, 1080, 2.625f, activity { SettingsActivity() }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // Upright, the companion under the game and the controls at the bottom of the space left below it.
        // EXPAND + UNDER GAME upright: the controls' own strip along the bottom, the companion above it, the map above that.
        Shot("settings-touch-phone-expand", 1080, 2400, 2.625f, activity {
            Prefs(android.content.Context()).portraitCompanionUnderGame = true
            Prefs(android.content.Context()).gameAspect = com.pokedaisy.app.companion.GameAspect.WIDE
            SettingsActivity()
        }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-touch-phone-bottom", 1080, 2400, 2.625f, activity {
            withTouch(com.pokedaisy.app.companion.TouchControlsStyle(position = com.pokedaisy.app.companion.TouchPosition.BOTTOM)) {
                Prefs(android.content.Context()).portraitCompanionUnderGame = true
                SettingsActivity()
            }
        }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-touch-edit", 2400, 1080, 2.625f, activity { SettingsActivity() }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("EDIT LAYOUT").performScrollTo().performClick()
        },
        Shot("settings-touch-edit-selected", 2400, 1080, 2.625f, activity { SettingsActivity() }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("EDIT LAYOUT").performScrollTo().performClick()
            tapTouchButton(2400, 1080, 2.625f, com.pokedaisy.app.companion.TouchControlsStyle(), com.pokedaisy.app.companion.TouchButton.A)
        },
        Shot("settings-touch-edit-phone", 1080, 2400, 2.625f, activity { SettingsActivity() }) {
            onNodeWithText("TOUCH CONTROLS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("EDIT LAYOUT").performScrollTo().performClick()
        },
        // Each preset as the game draws it: a 20:9 phone sideways and upright, a 16:9 one (SIDES falls back to CLASSIC).
        *com.pokedaisy.app.companion.TouchPreset.entries.flatMap { p ->
            val st = com.pokedaisy.app.companion.TouchControlsStyle(preset = p)
            val n = p.name.lowercase()
            listOf(
                Shot("touch-$n-side", 2400, 1080, 2.625f, touchScene(2400, 1080, 2.625f, st)),
                Shot("touch-$n-upright", 1080, 2400, 2.625f, touchScene(1080, 2400, 2.625f, st)),
                Shot("touch-$n-16x9", 1920, 1080, 2.625f, touchScene(1920, 1080, 2.625f, st)),
            )
        }.toTypedArray(),
        Shot("touch-all-extras-side", 2400, 1080, 2.625f, touchScene(2400, 1080, 2.625f, com.pokedaisy.app.companion.TouchControlsStyle(extras = com.pokedaisy.app.companion.TouchButton.extras.toSet()))),
        Shot("touch-all-extras-upright", 1080, 2400, 2.625f, touchScene(1080, 2400, 2.625f, com.pokedaisy.app.companion.TouchControlsStyle(extras = com.pokedaisy.app.companion.TouchButton.extras.toSet()))),
        Shot("touch-large-upright", 1080, 2400, 2.625f, touchScene(1080, 2400, 2.625f, com.pokedaisy.app.companion.TouchControlsStyle(size = com.pokedaisy.app.companion.TouchSize.HUGE, opacity = 100))),
        // The top screen's SPEED OPTIONS (FF MODE, FF MUSIC) and DISPLAY OPTIONS (STATUS BAR, SHADERS, OVERLAY).
        Shot("settings-speed", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("SPEED OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-display", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // A ROM imported with "add anyway?": the library tags what the second screen can't read.
        Shot("library-tag-unsupported", tw, th * 2, td, activity {
            val ctx = android.content.Context()
            val hack = File(ctx.filesDir, "roms/firered-hack.gba").absolutePath
            File(ctx.filesDir, "rom-folder-scan.tsv").writeText("$hack\t1\t1700000000000\t0\n")
            LibraryActivity().apply {
                unsupportedRoms = setOf(hack)
                partialRoms = setOf(File(ctx.filesDir, "roms/Pokemon Gaia (v3.2).gba").absolutePath)
            }
        }),
        Shot("library-tag-unsupported-grid", tw, th * 2, td, activity {
            val ctx = android.content.Context()
            val hack = File(ctx.filesDir, "roms/firered-hack.gba").absolutePath
            File(ctx.filesDir, "rom-folder-scan.tsv").writeText("$hack\t1\t1700000000000\t0\n")
            LibraryActivity().apply { unsupportedRoms = setOf(hack) }
        }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        // Games added in the last day (not by setup): a green NEW tag, in the list and the grid; SORT BY,
        // and the library by DATE ADDED (newest first).
        Shot("library-new", tw, th, td, activity { LibraryActivity().also { datedLibrary() } }),
        Shot("library-new-grid", tw, th * 2, td, activity {
            datedLibrary()
            Prefs(android.content.Context()).librarySort = com.pokedaisy.app.LibrarySort.DATE_ADDED.id
            LibraryActivity()
        }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        Shot("library-sort", tw, th, td, activity { LibraryActivity().also { datedLibrary() } }) {
            onNodeWithContentDescription("Sort").performClick()
        },
        // The header's +: ADD A ROM FILE / ADD A ROMS FOLDER / MANAGE ROMS FOLDERS.
        Shot("library-add-menu", tw, th, td, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Add games").performClick()
        },
        Shot("library-sorted-added", tw, th, td, activity {
            datedLibrary()
            val ctx = android.content.Context()
            Prefs(ctx).librarySort = com.pokedaisy.app.LibrarySort.DATE_ADDED.id
            LibraryActivity()
        }),
        // Favorites first (Radical Red and Emerald starred), in the list and the grid; a long press on a
        // list row opens its menu like a grid tile's (ADD TO / REMOVE FROM FAVORITES on top).
        Shot("library-favorites", tw, th, td, activity { favoriteLibrary(); LibraryActivity() }),
        Shot("library-favorites-grid", tw, th * 2, td, activity { favoriteLibrary(); LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        Shot("library-list-menu", tw, th, td, activity { favoriteLibrary(); LibraryActivity() }) {
            onAllNodesWithText("Pokémon Radical Red")[0].performSemanticsAction(SemanticsActions.OnLongClick)
        },
        // A phone held upright has no room for two: one column, scrolling.
        Shot("library-list-menu-phone", 1080, 2400, 2.625f, activity { favoriteLibrary(); LibraryActivity() }) {
            onAllNodesWithText("Pokémon Radical Red")[0].performSemanticsAction(SemanticsActions.OnLongClick)
        },
        // D-pad focus on a row: the orange ring around it, the row's own size and place unchanged.
        Shot("library-list-focus", tw, th, td, activity { favoriteLibrary(); LibraryActivity() }) {
            onAllNodesWithText("Pokémon Radical Red")[0].performSemanticsAction(SemanticsActions.RequestFocus)
        },
        Shot("library-grid-focus", tw, th * 2, td, activity { favoriteLibrary(); LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
            mainClock.advanceTimeBy(500)
            onAllNodesWithText("Pokémon Radical Red")[0].performSemanticsAction(SemanticsActions.RequestFocus)
        },
        // A phone held upright (1080x2400 @ 2.625, a Pixel 6): the library before a game opens.
        Shot("library-list-phone", 1080, 2400, 2.625f, activity { LibraryActivity() }),
        Shot("library-grid-phone", 1080, 2400, 2.625f, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        // The one-time "send crash reports?" ask (CrashReports.shouldAsk).
        Shot("library-crash-ask", tw, th, td, activity { LibraryActivity().apply { crashAsk = true } }),
        // Settings' APP group, CRASH REPORTS included.
        // Opened: D-pad focus on the first setting, not the search box.
        Shot("settings-home-focus", tw, th, td, activity { SettingsActivity() }),
        Shot("settings-app", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("CRASH REPORTS").performScrollTo() },
        Shot("library-grid", tw, th, td, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
        },
        // Long-pressing a library tile opens its options. Taller than the screen so the first row of tiles is whole.
        Shot("library-grid-menu", tw, th * 2, td, activity { LibraryActivity() }) {
            onNodeWithContentDescription("Toggle view").performClick()
            mainClock.advanceTimeBy(500) // lay the grid out before looking for its tiles
            onAllNodesWithText("Pokémon Emerald")[0].performSemanticsAction(SemanticsActions.OnLongClick)
        },
        // INFO on a game (hashes and all), and LOAD SAVE's confirm over a game that has a save.
        Shot("library-info", tw, th, td, activity {
            LibraryActivity().apply {
                val ctx = android.content.Context()
                gameInfo = com.pokedaisy.app.GameInfo.read(ctx, Prefs(ctx), File(ctx.filesDir, "roms/firered-hack.gba"), hashes = true)
            }
        }),
        // A ROM the app doesn't know: INFO's PICK A GAME, tapped (every config of its base game, AUTO, NONE OF THESE).
        Shot("library-info-pick", tw, th, td, activity {
            LibraryActivity().apply {
                val ctx = android.content.Context()
                val info = com.pokedaisy.app.GameInfo.read(ctx, Prefs(ctx), File(ctx.filesDir, "roms/firered-hack.gba"), hashes = true)
                gameInfo = com.pokedaisy.app.GameInfo(
                    info.title, info.sections, supportUrl = "https://example.invalid",
                    gamePick = com.pokedaisy.app.GameInfo.GamePick("0123456789abcdef0123456789abcdef01234567", "BPEE", "SOULGOLD_V1_2"),
                )
            }
        }) {
            onNodeWithText("PICK A GAME").performClick()
        },
        Shot("library-load-save", tw, th, td, activity {
            LibraryActivity().apply {
                val ctx = android.content.Context()
                saveLoad = LibraryActivity.PendingSaveLoad(File(ctx.filesDir, "roms/firered-hack.gba"), ByteArray(131072), "FireRed - Hall of Fame.sav")
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
            LibraryActivity().apply { coverPickerFor = File("firered-hack.gba"); coverSourceOverride = FakeCoverSource(known = true) }
        }),
        Shot("library-cover-picker-search", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("Pokemon Amethyst.gba"); coverSourceOverride = FakeCoverSource(known = false) }
        }),
        // RetroAchievements box art: first, beside SteamGridDB's icons; alone with no SteamGridDB key.
        Shot("library-cover-picker-boxart", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-hack.gba"); coverSourceOverride = FakeCoverSource(known = true, boxArt = true) }
        }),
        Shot("library-cover-picker-boxart-only", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-hack.gba"); coverSourceOverride = FakeCoverSource(known = false, boxArt = true, steamGridDb = false) }
        }),
        Shot("library-cover-picker-nokey", tw, th, td, activity {
            LibraryActivity().apply { coverPickerFor = File("firered-hack.gba") }
        }),
        // First-time setup: each step, before and after its folder is picked.
        Shot("setup-roms", tw, th, td, activity { LibraryActivity().apply { setup = SetupState() } }),
        Shot("setup-roms-from-settings", tw, th, td, activity { LibraryActivity().apply { setup = SetupState().apply { fromSettings = true } } }),
        Shot("setup-roms-noaccess", tw, th, td, activity { LibraryActivity().apply { setup = SetupState().apply { hasAccess = false } } }),
        Shot("setup-roms-scanning", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply { romsFolders = listOf("/storage/XXXX-XXXX/ROMs/GBA", "/storage/XXXX-XXXX/ROMs/NDS"); scanning = true; checked = 14; toCheck = 41 }
            }
        }),
        Shot("setup-roms-found", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply {
                    romsFolders = listOf("/storage/XXXX-XXXX/ROMs/GBA", "/storage/emulated/0/ROMs/GBC")
                    found = listOf("Pokémon FireRed", "Pokémon Emerald", "Pokémon Unbound", "Pokémon Radical Red", "Pokemon Emerald Imperium")
                }
            }
        }),
        Shot("setup-roms-none", tw, th, td, activity {
            LibraryActivity().apply { setup = SetupState().apply { romsFolders = listOf("/storage/emulated/0/Download"); found = emptyList() } }
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
                    buttons = PREVIEW_BUTTONS
                }
            }
        }),
        // A row waiting for its button, in place: what to do and CANCEL under it.
        Shot("setup-buttons-capture", tw, th, td, activity {
            LibraryActivity().apply {
                setup = SetupState().apply {
                    step = SetupState.Step.BUTTONS
                    buttons = PREVIEW_BUTTONS
                    capture.start(com.pokedaisy.app.companion.KeyCapture.Request("GBA B", chord = false, onDone = {}))
                }
            }
        }),
        // TURBO A can go back to unbound: CLEAR beside CANCEL.
        Shot("setup-buttons-capture-turbo", tw, th, td, activity {
            LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.BUTTONS; buttons = PREVIEW_BUTTONS } }
        }) { onNodeWithText("GBA TURBO A").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        // A row at the window's bottom edge, tapped: its CANCEL line scrolls up into view.
        Shot("setup-buttons-capture-low", tw, th, td, activity {
            LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.BUTTONS; buttons = PREVIEW_BUTTONS } }
        }) { onNodeWithText("GBA R").performSemanticsAction(SemanticsActions.OnClick) },
        // Over the game while the companion waits for keys: GBA A first, then a hotkey with SELECT held.
        Shot("game-capture-button", th * 3 / 2, th, td, {
            previewCapture = com.pokedaisy.app.companion.KeyCapture().apply {
                start(com.pokedaisy.app.companion.KeyCapture.Request("GBA A", chord = false, onDone = {}))
            }
            val capture = previewCapture
            ({ PreviewGame { com.pokedaisy.app.companion.ui.KeyCapturePopup(capture) } })
        }),
        Shot("game-capture-hotkey", th * 3 / 2, th, td, {
            previewCapture = com.pokedaisy.app.companion.KeyCapture().apply {
                start(com.pokedaisy.app.companion.KeyCapture.Request("SAVE STATE", chord = true, onDone = {}, onClear = {}))
                onKey(android.view.KeyEvent.KEYCODE_BUTTON_SELECT, true)
            }
            val capture = previewCapture
            ({ PreviewGame { com.pokedaisy.app.companion.ui.KeyCapturePopup(capture) } })
        }),
        Shot("setup-media", tw, th, td, activity { withScreenScraper { LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.MEDIA } } } }),
        Shot("setup-media-signed-in", tw, th, td, activity {
            withScreenScraper { LibraryActivity().apply { setup = SetupState().apply { step = SetupState.Step.MEDIA; ssLogin = com.pokedaisy.app.ScreenScraperLogin("preview") } } }
        }),
        Shot("settings-screenscraper", tw, th, td, activity { withScreenScraper { SettingsActivity() } }) {
            onNodeWithText("ScreenScraper").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // A password typed, then shown with the eye; and a refused sign-in with the server's own words under it.
        Shot("settings-screenscraper-password", tw, th, td, activity { withScreenScraper { SettingsActivity() } }) {
            runOnIdle { (lastActivity as SettingsActivity).ssLogin.apply { user = "preview"; password = "hunter2" } }
            onNodeWithText("ScreenScraper").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-screenscraper-password-shown", tw, th, td, activity { withScreenScraper { SettingsActivity() } }) {
            runOnIdle { (lastActivity as SettingsActivity).ssLogin.apply { user = "preview"; password = "hunter2" } }
            onNodeWithText("ScreenScraper").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithContentDescription("Show password").performClick()
        },
        Shot("settings-screenscraper-error", tw, th, td, activity { withScreenScraper { SettingsActivity() } }) {
            runOnIdle {
                (lastActivity as SettingsActivity).ssLogin.apply {
                    user = "preview"; password = "hunter2"
                    error = "This build has no ScreenScraper developer access"
                    errorDetail = "ScreenScraper says: Erreur de login : Vérifier vos identifiants développeur !"
                }
            }
            onNodeWithText("ScreenScraper").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
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
        Shot("settings-aspect-stretch", tw, th, td, activity { withAspect(com.pokedaisy.app.companion.GameAspect.STRETCH) { SettingsActivity() } }) {
            onNodeWithText("AUTO ZOOM IN").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-aspect-wide", tw, th, td, activity { withAspect(com.pokedaisy.app.companion.GameAspect.WIDE) { SettingsActivity() } }) {
            onNodeWithText("AUTO ZOOM IN").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-shaders", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-shaders-filter", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("SHADERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("FILTER").performClick()
        },
        Shot("settings-swap", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("SWAP SCREENS").performScrollTo() },
        // OVERLAY: its page with one import (DAISY picked), then REMOVE's mode.
        Shot("settings-overlay", tw, th, td, activity { withOverlays { SettingsActivity() } }) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-overlay-remove", tw, th, td, activity { withOverlays { SettingsActivity() } }) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("REMOVE").performClick()
        },
        // REMOVE's mode > REMOVE ALL: the confirm.
        Shot("settings-overlay-remove-all", tw, th, td, activity { withOverlays { SettingsActivity() } }) {
            onNodeWithText("DISPLAY OPTIONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick); onNodeWithText("OVERLAY").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("REMOVE").performClick()
            onNodeWithText("REMOVE ALL").performClick()
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
        Shot("settings-hotkeys-end", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("CLOSE APP").performScrollTo()
        },
        // The list's end: LIBRARY / ONLINE (RetroAchievements' BETA tag) / APP.
        Shot("settings-home-bottom", tw, th, td, activity { SettingsActivity() }) { onNodeWithText("RUN SETUP").performScrollTo() },
        Shot("settings-buttons", tw, th * 2, td, activity { SettingsActivity() }) { onNodeWithText("GAME BUTTONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) },
        // A GBA button waiting for its button, in place.
        Shot("settings-buttons-capture", tw, th * 2, td, activity { SettingsActivity() }) {
            onNodeWithText("GAME BUTTONS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithText("GBA B").performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-hotkeys-off", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("HOTKEYS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onAllNodesWithText("ON").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        },
        // Search: "rebind" isn't a row's name - its keywords find GAME BUTTONS.
        Shot("settings-search", tw, th, td, activity { SettingsActivity() }) {
            onNode(hasSetTextAction()).performTextInput("rebind")
        },
        // DS RESOLUTION: found by "upscale" (its keywords), and its pick-list.
        Shot("settings-ds-resolution", tw, th, td, activity { SettingsActivity() }) {
            onNode(hasSetTextAction()).performTextInput("upscale")
        },
        Shot("settings-ds-resolution-pick", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("DS RESOLUTION").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // Search: "hints" finds TIPS (its keywords), with its NEW tag.
        Shot("settings-search-tips", tw, th, td, activity { SettingsActivity() }) {
            onNode(hasSetTextAction()).performTextInput("hints")
        },
        Shot("settings-folders", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("FOLDERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        },
        // FOLDERS > ROMS FOLDERS with two folders linked, then empty.
        Shot("settings-folders-roms", tw, th, td, activity {
            Prefs(android.content.Context()).romsFolders = listOf("/storage/XXXX-XXXX/ROMs/gba", "/storage/emulated/0/ROMs/nds")
            SettingsActivity()
        }) {
            onNodeWithText("FOLDERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onNodeWithText("ROMS FOLDERS").performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-folders-roms-empty", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("FOLDERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onNodeWithText("ROMS FOLDERS").performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-folders-saves", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("FOLDERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onNodeWithText("GAME SAVES").performSemanticsAction(SemanticsActions.OnClick)
        },
        Shot("settings-folders-also", tw, th, td, activity { SettingsActivity() }) {
            onNodeWithText("FOLDERS").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            onNodeWithText("ALSO LOOK IN").performSemanticsAction(SemanticsActions.OnClick)
        },
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
    val shots = if (docsMode) docsShots(all, bw, bh, bd, tw, th, td) else all
    var failed = 0
    for (shot in shots) {
        if (only.isNotEmpty() && only.none { shot.name.contains(it) }) continue
        try {
            com.pokedaisy.app.companion.FavoriteItems.load(emptySet()) {} // a shot's stars don't carry into the next
            com.pokedaisy.app.companion.data.PlayerLook.worn.value = null
            val content = shot.content()
            runSkikoComposeUiTest(Size(shot.w.toFloat(), shot.h.toFloat()), Density(shot.density)) {
                // Manual clock: the party icons / map highlight animate forever,
                // so waitForIdle() would never return.
                mainClock.autoAdvance = false
                setContent(content)
                mainClock.advanceTimeBy(500)
                runCatching { shot.action(this) }
                    .onFailure { println("${shot.name}: action failed: ${it.message?.lineSequence()?.first()}"); if (System.getenv("SHOT_TRACE") != null) it.printStackTrace(System.out) }
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

/** The setup / Settings shots' bindings: the defaults, TURBO unbound. */
val PREVIEW_BUTTONS: Map<GbaControls.Btn, List<String>> = GbaControls.Btn.entries.associateWith {
    when (it) {
        GbaControls.Btn.A -> listOf("BUTTON_A", "Z"); GbaControls.Btn.B -> listOf("BUTTON_B", "X")
        GbaControls.Btn.L -> listOf("BUTTON_L1", "A"); GbaControls.Btn.R -> listOf("BUTTON_R1", "S")
        GbaControls.Btn.START -> listOf("BUTTON_START", "ENTER"); GbaControls.Btn.SELECT -> listOf("BUTTON_SELECT")
        GbaControls.Btn.X, GbaControls.Btn.START_2, GbaControls.Btn.TO_COMPANION -> listOf("BUTTON_X")
        GbaControls.Btn.Y, GbaControls.Btn.SELECT_2 -> listOf("BUTTON_Y")
        GbaControls.Btn.TURBO_A, GbaControls.Btn.TURBO_B -> emptyList()
    }
}

/** A stand-in game screen (no ROM art in the shots) for what's drawn over it. */
@Composable
fun PreviewGame(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF58A848)),
    ) { content() }
}
