package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.FixtureMemoryReader
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.MemoryReader
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RomFileReader
import com.pokedaisy.app.companion.data.ShinyOdds
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.buildSnapshotView
import com.pokedaisy.app.companion.data.readNativeTelemetry
import com.pokedaisy.app.companion.data.resetNativeBagCache
import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import com.pokedaisy.app.companion.ui.theme.AppTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random
import okio.Path.Companion.toOkioPath

/**
 * Shiny icons on retail FireRed, from real data: the `firered_shiny_battle` fixture (a wild PIKACHU SHINY ODDS made shiny,
 * headless) with the party's lead made shiny the same way, read through the retail config the Poller marks and the
 * player's own ROM (icons and palettes are never bundled - skipped without it). PARTY, the summary, battle INFO and the
 * HUD, at the Thor's bottom screen (the HUD at its top screen):
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*ShinyIconsScreenshotTest'
 */
open class ShinyIconsScreenshotTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private lateinit var view: SnapshotView

    /** The fixture's RAM with writes: the lead's record rewritten to a shiny personality. */
    private class Ram(src: MemoryReader) : MemoryReader {
        private val ewram = src.readCoreMemory(0x02000000L, 0x40000)
        private val iwram = src.readCoreMemory(0x03000000L, 0x8000)
        private fun at(addr: Long) = if (addr >= 0x03000000L) iwram to (addr - 0x03000000L).toInt() else ewram to (addr - 0x02000000L).toInt()
        override fun readCoreMemory(addr: Long, size: Int): ByteArray { val (b, o) = at(addr); return b.copyOfRange(o, o + size) }
        fun write(addr: Long, bytes: ByteArray) { val (b, o) = at(addr); bytes.copyInto(b, o) }
    }

    @Before
    fun setUp() {
        val rom = RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)
        assumeTrue("retail FireRed rev 1 isn't on this machine", rom != null)
        activeGame = GameKind.FIRERED
        OptionColors.inGame = true
        resetNativeBagCache()
        // The party slots' art comes from the ROM too.
        val art = java.io.File("build/rom-art-paparazzi").also { RomArt.dirOverride = it.toOkioPath() }
        RomArt.extractTo(java.io.File(RomFileReader.FIRERED_REV1_PATH).readBytes(), art.toOkioPath())
        val cfg = NATIVE_FIRERED_REV1.copy(shinyBelow = 8)
        val ram = Ram(FixtureMemoryReader.load("firered_shiny_battle"))
        val lead = Gen3BoxMon.read(ram.readCoreMemory(cfg.playerParty, Gen3BoxMon.SIZE), 0)
        val shiny = ShinyOdds.shinyPersonality(lead.personality, lead.otId, lead.species, Random(1))!!
        ram.write(cfg.playerParty, lead.withPersonality(shiny))
        view = buildSnapshotView(readNativeTelemetry(rom!!.withRam(ram), cfg)).copy(game = GameKind.FIRERED)
        PokedexSource.reader = rom
        DecompIconSource.reader = rom
        DecompIconSource.tables = cfg.iconTables
        // Decoded ahead, so the first frame has them (the composables only peek the caches synchronously).
        (view.party + view.battlePlayer + view.battleOpponent).forEach {
            DecompIconSource.get(it.species)
            DecompIconSource.getShiny(it.species)
        }
        view.items.forEach { DecompIconSource.getItem(it.itemId) }
    }

    @Test fun party() = paparazzi.snapshot {
        CompanionScreen(view, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "PARTY")
    }

    @Test fun summary() = paparazzi.snapshot {
        CompanionScreen(view, SampleCompanion.Slots, SampleCompanion.Settings(), initialMonIndex = 0)
    }

    @Test fun battleInfo() = paparazzi.snapshot {
        AppTheme {
            BattleInfoScreen(view.battlePlayer, view.battleOpponent, isDouble = false, showHints = true, onBack = {}, onShowSuggestions = {})
        }
    }

    @Test fun hud() = paparazzi.snapshot {
        FieldHudScene(FieldHudStyle(), null, snap = view.copy(inBattle = false, battlePlayer = emptyList(), battleOpponent = emptyList()))
    }
}

/** The same on the RG DS's 640x480 bottom screen (a short companion, the text a step smaller). */
class RgDsShinyIconsScreenshotTest : ShinyIconsScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 640, screenHeight = 480, density = Density.HIGH, xdpi = 200, ydpi = 200,
            orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )
}
