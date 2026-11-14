package com.pokedaisy.app.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudLayout
import com.pokedaisy.app.companion.FieldHudLook
import com.pokedaisy.app.companion.FieldHudPart
import com.pokedaisy.app.companion.FieldHudSize
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.GUIDE_TABLES_FIRERED_REV1
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.GuideRomSource
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.LocationView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.POCKET_BERRIES
import com.pokedaisy.app.companion.data.POCKET_ITEMS
import com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS
import com.pokedaisy.app.companion.data.POCKET_POKE_BALLS
import com.pokedaisy.app.companion.data.POKEDEX_FIRERED_REV1
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RomFileReader
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.ui.theme.DarkMode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import okio.Path.Companion.toOkioPath

/**
 * The FIELD HUD over a game frame on the Thor's 1920x1080 top screen: each [FieldHudLayout] in each
 * [FieldHudLook], the [FieldHudSize]s, EXPAND and ORIGINAL (black bars), the panels it opens, dark mode,
 * the touch pad's clearance, and the companion's SETTINGS page that picks it all.
 *
 * The frame under it is a real EXPAND capture when one is on this machine - `mgba_dump`'s `wideon 22` +
 * `wideshot` on the player's own ROM and save, converted to PNG into `app/build/field-hud/fr.png` /
 * `em.png` (never committed: game art) - else a drawn stand-in.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*FieldHudScreenshotTest'
 */
class FieldHudScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )


    @Before
    fun game() {
        activeGame = GameKind.FIRERED
        OptionColors.inGame = true
        val art = java.io.File("build/rom-art-paparazzi").also { RomArt.dirOverride = it }
        for (path in listOf(RomFileReader.FIRERED_REV1_PATH, RomFileReader.EMERALD_PATH)) {
            java.io.File(path).takeIf { it.isFile }?.let { RomArt.extractTo(it.readBytes(), art) }
        }
        RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)?.let { rom ->
            PokedexSource.reader = rom
            DecompIconSource.reader = rom
            DecompIconSource.tables = NATIVE_FIRERED_REV1.iconTables
            party.forEach { DecompIconSource.get(it.species) }
            items.forEach { DecompIconSource.getItem(it.itemId) }
            val enc = GuideRomSource.encounters(GUIDE_TABLES_FIRERED_REV1, 3, 43)
            wild = hudWild(enc, snapshot.pokedex)
            wild?.forEach { DecompIconSource.get(it.species) }
        }
    }

    @After
    fun reset() {
        DarkMode.override = null
        activeGame = GameKind.FIRERED
    }

    // ---- The three layouts in both looks, at EXPAND (CORNERS + GLASS is the default) ----

    @Test fun cornersGlass() = shot(FieldHudStyle())

    @Test fun cornersWindow() = shot(FieldHudStyle(look = FieldHudLook.WINDOW))

    @Test fun cornersWhiteGlass() = shot(FieldHudStyle(look = FieldHudLook.WHITE_GLASS))

    @Test fun bagOpenWhiteGlass() = shot(FieldHudStyle(look = FieldHudLook.WHITE_GLASS), panel = FieldHudPanel.BAG)

    @Test fun summaryOpenWhiteGlass() = shot(FieldHudStyle(look = FieldHudLook.WHITE_GLASS), panel = FieldHudPanel.SUMMARY)

    /** CORNERS 2: the right side swapped - the minimap bottom right, the balls and buttons top right. */
    @Test fun corners2Glass() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2))

    @Test fun corners2Window() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2, FieldHudLook.WINDOW))

    @Test fun corners2Original() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2), expand = false)

    @Test fun corners2BagOpen() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2), panel = FieldHudPanel.BAG, companion = true)

    @Test fun railsGlass() = shot(FieldHudStyle(FieldHudLayout.RAILS))

    @Test fun railsWindow() = shot(FieldHudStyle(FieldHudLayout.RAILS, FieldHudLook.WINDOW))

    @Test fun dockGlass() = shot(FieldHudStyle(FieldHudLayout.DOCK))

    @Test fun dockWindow() = shot(FieldHudStyle(FieldHudLayout.DOCK, FieldHudLook.WINDOW))

    // ---- SIZE ----

    @Test fun sizeSmall() = shot(FieldHudStyle(size = FieldHudSize.SMALL))

    @Test fun sizeLarge() = shot(FieldHudStyle(size = FieldHudSize.LARGE))

    @Test fun railsLarge() = shot(FieldHudStyle(FieldHudLayout.RAILS, size = FieldHudSize.LARGE))

    // ---- ORIGINAL aspect: the GBA's 3:2 between black bars ----

    @Test fun railsOriginal() = shot(FieldHudStyle(FieldHudLayout.RAILS), expand = false)

    /** ORIGINAL: the HUD stays on the game's picture, out of the black bars. */
    @Test fun cornersOriginal() = shot(FieldHudStyle(), expand = false)

    @Test fun dockOriginal() = shot(FieldHudStyle(FieldHudLayout.DOCK), expand = false)

    @Test fun bagOpenOriginal() = shot(FieldHudStyle(), expand = false, panel = FieldHudPanel.BAG)

    // ---- What the HUD opens (the game keeps running under it) ----

    @Test fun mapOpen() = shot(FieldHudStyle(), panel = FieldHudPanel.MAP)

    @Test fun bagOpen() = shot(FieldHudStyle(), panel = FieldHudPanel.BAG)

    /** A full ITEMS pocket: the third row half shown, so the grid reads as scrolling. */
    @Test fun bagManyItems() = shot(
        FieldHudStyle(), panel = FieldHudPanel.BAG,
        snap = snapshot.copy(items = snapshot.items + (1..14).map { ItemView(it, "Ball $it", it, spriteAsset("items", it), POCKET_ITEMS) }),
    )

    /** A Game Boy game's balls: no item icons, so a drawn ball in their place. */
    @Test fun ballsWithoutIcons() = shot(
        FieldHudStyle(),
        snap = snapshot.copy(items = snapshot.items.map { if (it.pocket == POCKET_POKE_BALLS) it.copy(iconAsset = null) else it }),
    )

    /** The bag under the game's own word: Unbound's CUBE, on the button and the panel. */
    @Test fun bagOwnWord() {
        activeGame = GameKind.UNBOUND
        shot(FieldHudStyle(), panel = FieldHudPanel.BAG)
    }

    /** The BAG of a game without item pictures (the Game Boy games): a list of names. */
    @Test fun bagOpenWithoutIcons() = shot(
        FieldHudStyle(), panel = FieldHudPanel.BAG,
        snap = snapshot.copy(items = snapshot.items.map { it.copy(iconAsset = null) }),
    )

    @Test fun bagOpenWindow() = shot(FieldHudStyle(look = FieldHudLook.WINDOW), panel = FieldHudPanel.BAG)

    @Test fun bagOpenDock() = shot(FieldHudStyle(FieldHudLayout.DOCK), panel = FieldHudPanel.BAG)

    @Test fun wildOpen() = shot(FieldHudStyle(), panel = FieldHudPanel.WILD)

    /** A party icon tapped: the first Pokémon's summary over the game. */
    @Test fun summaryOpen() = shot(FieldHudStyle(), panel = FieldHudPanel.SUMMARY)

    // ---- Dark mode, fewer parts, the touch pad, another game ----

    @Test fun windowDark() {
        DarkMode.override = true
        shot(FieldHudStyle(look = FieldHudLook.WINDOW))
    }

    /** Party and minimap only (balls, BAG and WILD switched off). */
    @Test fun cornersMinimal() = shot(FieldHudStyle(hidden = setOf(FieldHudPart.BALLS, FieldHudPart.BAG, FieldHudPart.WILD)))

    /** One screen (the RP6), the companion's side panel closed: its open button beside BAG and WILD. */
    @Test fun singleScreen() = shot(FieldHudStyle(), companion = true)

    /** A game with BAG > PC: PC left of BAG, on one screen with COMPANION too. */
    @Test fun pcButton() = shot(FieldHudStyle(), companion = true, pc = true)

    /** A REPEL working: its steps left in a window over the balls, the bar draining. */
    @Test fun repelSteps() = shot(FieldHudStyle(), pc = true, snap = snapshot.copy(progress = repelProgress(187)))

    /** The same in the other layouts: CORNERS 2 (under the balls), RAILS (narrow, in the column), the compact HUD. */
    @Test fun repelStepsCorners2() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2), pc = true, snap = snapshot.copy(progress = repelProgress(120)))

    @Test fun repelStepsRails() = shot(FieldHudStyle(FieldHudLayout.RAILS), pc = true, snap = snapshot.copy(progress = repelProgress(60)))

    @Test fun repelStepsCompact() = shot(FieldHudStyle(), expand = false, companion = true, pc = true, gameRect = DOCKED_GAME, snap = snapshot.copy(progress = repelProgress(187)))

    /** Its last steps: gold, the bar red (DOCK's bar, no window of its own). */
    @Test fun repelStepsLow() = shot(FieldHudStyle(FieldHudLayout.DOCK), pc = true, snap = snapshot.copy(progress = repelProgress(14)))

    @Test fun pcButtonCorners2() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2), pc = true)

    @Test fun pcButtonRails() = shot(FieldHudStyle(FieldHudLayout.RAILS), companion = true, pc = true)

    @Test fun pcButtonDock() = shot(FieldHudStyle(FieldHudLayout.DOCK), companion = true, pc = true)

    // A side panel locked beside the game (half the screen: the game at 4x, 960x640, in the left half): too narrow for
    // the party beside the buttons, so the compact HUD whatever LAYOUT says - the balls over PC / BAG / WILD / MAP bottom left.
    @Test fun compactDocked() = shot(FieldHudStyle(), expand = false, companion = true, pc = true, gameRect = DOCKED_GAME)

    @Test fun compactDockedCorners2() = shot(FieldHudStyle(FieldHudLayout.CORNERS_2), expand = false, companion = true, pc = true, gameRect = DOCKED_GAME)

    @Test fun compactDockedBagOpen() = shot(FieldHudStyle(), expand = false, panel = FieldHudPanel.BAG, companion = true, pc = true, gameRect = DOCKED_GAME)

    @Test fun compactDockedRails() = shot(FieldHudStyle(FieldHudLayout.RAILS), expand = false, companion = true, pc = true, gameRect = DOCKED_GAME)

    @Test fun compactDockedDock() = shot(FieldHudStyle(FieldHudLayout.DOCK), expand = false, companion = true, pc = true, gameRect = DOCKED_GAME)

    @Test fun compactDockedMapOpen() = shot(FieldHudStyle(), expand = false, panel = FieldHudPanel.MAP, companion = true, pc = true, gameRect = DOCKED_GAME)

    /** A wider docked game (the panel at its narrowest): the whole HUD fits again. */
    @Test fun dockedWide() = shot(FieldHudStyle(), expand = false, companion = true, pc = true, gameRect = Rect(0f, 0f, 1440f, 960f))

    @Test fun singleScreenDock() = shot(FieldHudStyle(FieldHudLayout.DOCK), companion = true)

    /** The touch pad up: the HUD keeps clear of its L / R row and its D-pad / A / B (TouchControlsView's sizes at 1080 px). */
    @Test fun touchPad() = shot(FieldHudStyle(), clear = 160 to 482)

    @Test fun cornersEmerald() {
        activeGame = GameKind.EMERALD
        shot(
            FieldHudStyle(), frame = "em",
            snap = snapshot.copy(
                game = GameKind.EMERALD, regionMapSectionId = 26, location = LocationView("ROUTE 111", "hoenn", 0, 0, 0, 0),
                mapWidth = 40, mapHeight = 140, x = 20, y = 90,
            ),
        )
    }

    /** Black (the DS): its Town Map in the minimap, the player's figure on Route 1's point (the art from the cart, when
     * it's on this machine: NdsArt). */
    @Test fun cornersBlack() {
        activeGame = GameKind.BLACK
        val rom = java.io.File(java.io.File(com.pokedaisy.app.companion.data.RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds")
        if (rom.isFile) com.pokedaisy.app.companion.data.NdsArt.extractTo(rom.toOkioPath(), java.io.File("build/rom-art-paparazzi"))
        shot(
            FieldHudStyle(),
            snap = snapshot.copy(
                game = GameKind.BLACK, regionMapSectionId = com.pokedaisy.app.companion.data.mapPlaceIdsBlack.getValue(317),
                location = LocationView("Route 1", "unova", 0, 0, 0, 0), regionMapTile = 232 / 8 to 148 / 8,
            ),
        )
    }

    /** The companion's SETTINGS rows around HUD: its NEW tag (NewFeatures, before init = new). */
    @Test
    fun settingsNewTag() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val px = LocalDensity.current
            Box(Modifier.width(with(px) { 1240.toDp() }).fillMaxHeight()) {
                GameBackdrop(GameKind.FIRERED)
                val m = rememberGbaTextMetrics()
                Column(Modifier.fillMaxSize().padding(m.u * 6)) {
                    OptionListWindow(m) {
                        GroupedRows(
                            listOf(
                                groupTitle("SCREEN"),
                                SettingRow("STATUS BAR", "OFF") {},
                                SettingRow("HUD", "ON", labelBadge = com.pokedaisy.app.companion.NewFeatures.badge(com.pokedaisy.app.companion.NewFeatures.HUD)) {},
                                SettingRow("SHADERS", "NONE") {},
                            ),
                            m, cursor = 2, onClick = {},
                        )
                    }
                }
            }
        }
    }

    /** SETTINGS > HUD on the companion (its bottom-screen size). */
    @Test
    fun settingsPage() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val px = LocalDensity.current
            Box(Modifier.width(with(px) { 1240.toDp() }).fillMaxHeight()) {
                GameBackdrop(GameKind.FIRERED)
                CompanionSettingsScreen(SampleCompanion.Settings(), initialPage = "FIELD_HUD")
            }
        }
    }

    /** The same with the HUD on a picture too narrow for its layouts: LAYOUT greyed out, saying so. */
    @Test
    fun settingsPageTight() {
        FieldHudFit.tight = true
        try { settingsPage() } finally { FieldHudFit.tight = false }
    }

    private fun shot(
        style: FieldHudStyle,
        expand: Boolean = true,
        panel: FieldHudPanel? = null,
        frame: String = "fr",
        snap: SnapshotView = snapshot,
        clear: Pair<Int, Int> = 0 to 0,
        companion: Boolean = false,
        pc: Boolean = false,
        gameRect: Rect? = null,
    ) = paparazzi.snapshot { FieldHudScene(style, wild, expand, panel, frame, snap, clear, companion, pc, gameRect) }

    companion object {
        var wild: List<HudWild>? = null

        /** The game at 4x beside a half-screen side panel. */
        val DOCKED_GAME = Rect(0f, 220f, 960f, 860f)

        val party = listOf(
            // The companion's sample CHARMANDER's moves, types and matchups, grown up: the summary has something to show.
            SampleCompanion.snapshot.party[0].copy(species = 5, name = "CHARMELEON", level = 24, hp = 61, maxHp = 68, iconAsset = spriteAsset("pokemon", 5), stats = null),
            mon(17, "PIDGEOTTO", 21, 40, 58, "PSN"),
            mon(25, "PIKACHU", 19, 9, 45, "PAR"),
            mon(43, "ODDISH", 16, 44, 44, "SLP"),
            mon(74, "GEODUDE", 15, 0, 41, ""),
            mon(129, "MAGIKARP", 10, 30, 30, "BRN"),
        )

        val items = listOf(
            ItemView(4, "Poké Ball", 12, spriteAsset("items", 4), POCKET_POKE_BALLS),
            ItemView(3, "Great Ball", 4, spriteAsset("items", 3), POCKET_POKE_BALLS),
            ItemView(2, "Ultra Ball", 1, spriteAsset("items", 2), POCKET_POKE_BALLS),
            ItemView(13, "Potion", 5, spriteAsset("items", 13), POCKET_ITEMS, "A spray-type wound medicine. It restores the HP of one POKéMON by 20 points."),
            ItemView(22, "Super Potion", 2, spriteAsset("items", 22), POCKET_ITEMS),
            ItemView(14, "Antidote", 3, spriteAsset("items", 14), POCKET_ITEMS),
            ItemView(18, "Parlyz Heal", 1, spriteAsset("items", 18), POCKET_ITEMS),
            ItemView(17, "Awakening", 2, spriteAsset("items", 17), POCKET_ITEMS),
            ItemView(86, "Repel", 4, spriteAsset("items", 86), POCKET_ITEMS),
            ItemView(85, "Escape Rope", 1, spriteAsset("items", 85), POCKET_ITEMS),
            ItemView(139, "Oran Berry", 3, spriteAsset("items", 139), POCKET_BERRIES),
            ItemView(360, "Bicycle", 1, spriteAsset("items", 360), POCKET_KEY_ITEMS),
            ItemView(262, "Old Rod", 1, spriteAsset("items", 262), POCKET_KEY_ITEMS),
        )

        val snapshot: SnapshotView = SampleCompanion.snapshot.copy(
            party = party,
            items = items,
            regionMapSectionId = 124,
            location = LocationView("ROUTE 24", "kanto", 0, 0, 0, 0),
            mapType = 3, mapWidth = 40, mapHeight = 60, x = 14, y = 30, playerGender = 0,
        )

        fun mon(species: Int, name: String, level: Int, hp: Int, maxHp: Int, status: String) =
            MonView(species, name, level, hp, maxHp, status, emptyList(), spriteAsset("pokemon", species))
    }
}

/** The Thor's top screen (1920x1080): a game frame and the HUD over it. */
@Composable
internal fun FieldHudScene(
    style: FieldHudStyle,
    wild: List<HudWild>?,
    expand: Boolean = true,
    panel: FieldHudPanel? = null,
    frame: String = "fr",
    snap: SnapshotView = FieldHudScreenshotTest.snapshot,
    clear: Pair<Int, Int> = 0 to 0,
    companion: Boolean = false,
    /** A game with BAG > PC: the HUD's PC button. */
    pc: Boolean = false,
    /** Where the game's picture is (px), in place of [expand]'s. */
    gameRect: Rect? = null,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        GameFrame(frame, expand)
        FieldHud(
            snap, shown = true, style, clearTop = clear.first, clearBottom = clear.second, wild = wild, initialPanel = panel,
            itemUse = if (pc) PcItemUse else null,
            onOpenCompanion = if (companion) ({}) else null,
            // ORIGINAL: the picture's 3:2 in the middle, as EmulatorView reports it; EXPAND: the whole screen.
            frame = gameRect ?: if (expand) null else Rect(150f, 0f, 1770f, 1080f),
        )
    }
}

/** An [ItemUse] whose game has the PC (BAG > PC). */
private object PcItemUse : com.pokedaisy.app.companion.ItemUse {
    override fun canUse(itemId: Int) = false
    override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) = Unit
    override fun canPc() = true
    override val repelVar = 0x4020
}

/** A save whose REPEL has [steps] left (FireRed's VAR_REPEL_STEP_COUNT, 0x4020). */
internal fun repelProgress(steps: Int) = com.pokedaisy.app.companion.data.SaveProgress(
    ByteArray(0x120), ByteArray(0x200).also { it[0x40] = steps.toByte(); it[0x41] = (steps shr 8).toByte() },
)

/** The captured frame (284x160: EXPAND's 22 px margins), whole at EXPAND, its middle 240x160 at ORIGINAL. */
@Composable
private fun GameFrame(name: String, expand: Boolean) {
    val file = java.io.File("build/field-hud/$name.png")
    val image = remember(file) { file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    if (image == null) {
        val px = LocalDensity.current
        FakeGame(Modifier.padding(start = with(px) { 150.toDp() }).width(with(px) { 1620.toDp() }).fillMaxHeight())
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        if (expand) {
            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        } else {
            drawImage(
                image, srcOffset = IntOffset(22, 0), srcSize = IntSize(240, 160),
                dstOffset = IntOffset(150, 0), dstSize = IntSize(1620, 1080), filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * [FieldHudScreenshotTest]'s scenes cropped at 1:1 (Paparazzi scales a 1920 px shot down to 1000):
 * the bottom-left and bottom-right halves of the screen, one over the other.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*FieldHudCloseUpScreenshotTest'
 */
class FieldHudCloseUpScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1000, screenHeight = 1000, orientation = ScreenOrientation.PORTRAIT,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    @Test fun cornersGlass() = closeUp(FieldHudStyle(), 0 to 580, 920 to 580)

    @Test fun cornersWindow() = closeUp(FieldHudStyle(look = FieldHudLook.WINDOW), 0 to 580, 920 to 580)

    @Test fun cornersMinimap() = closeUp(FieldHudStyle(), 0 to 0, 920 to 0)

    @Test fun railsLeft() = closeUp(FieldHudStyle(FieldHudLayout.RAILS), 0 to 0, 0 to 580)

    @Test fun railsRight() = closeUp(FieldHudStyle(FieldHudLayout.RAILS), 920 to 0, 920 to 580)

    @Test fun dockGlass() = closeUp(FieldHudStyle(FieldHudLayout.DOCK), 0 to 580, 920 to 580)

    @Test fun sizeLarge() = closeUp(FieldHudStyle(size = FieldHudSize.LARGE), 0 to 580, 920 to 580)

    /** PC (its corner arrow: the game's own PC), BAG and WILD. */
    @Test fun pcButton() = closeUp(FieldHudStyle(), 920 to 580, pc = true)

    /** Two 1000x500 crops of the scene, their top-left corners at [crops], one over the other. */
    private fun closeUp(style: FieldHudStyle, vararg crops: Pair<Int, Int>, pc: Boolean = false) = paparazzi.snapshot {
        Column(Modifier.fillMaxSize().background(Color.DarkGray)) {
            for ((x, y) in crops) Crop(x, y) { FieldHudScene(style, FieldHudScreenshotTest.wild, companion = pc, pc = pc) }
        }
    }

    @Composable
    private fun Crop(x: Int, y: Int, content: @Composable () -> Unit) {
        val px = LocalDensity.current
        Box(Modifier.size(with(px) { 1000.toDp() }, with(px) { 500.toDp() }).clipToBounds()) {
            Box(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset(x = with(px) { -x.toDp() }, y = with(px) { -y.toDp() })
                    .requiredSize(with(px) { 1920.toDp() }, with(px) { 1080.toDp() }),
            ) { content() }
        }
    }
}
