package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RomFileReader
import com.pokedaisy.app.companion.data.activeGame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import okio.Path.Companion.toOkioPath

/**
 * The companion's window (SETTINGS > COMPANION VIEW > WINDOW) on a one-screen 4:3 handheld, 1280x960: the window
 * nearly as wide as the screen, its X beside it, no room for the buttons' legend. See SidePanelScreenshotTest.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*SidePanel43ScreenshotTest'
 */
class SidePanel43ScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1280, screenHeight = 960, orientation = ScreenOrientation.LANDSCAPE,
            density = Density.XHIGH,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before
    fun game() {
        activeGame = GameKind.FIRERED
        OptionColors.inGame = true
        val art = java.io.File("build/rom-art-paparazzi").also { RomArt.dirOverride = it.toOkioPath() }
        for (path in listOf(RomFileReader.FIRERED_REV1_PATH, RomFileReader.EMERALD_PATH)) {
            java.io.File(path).takeIf { it.isFile }?.let { RomArt.extractTo(it.readBytes(), art.toOkioPath()) }
        }
        RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)?.let { rom ->
            DecompIconSource.reader = rom
            DecompIconSource.tables = NATIVE_FIRERED_REV1.iconTables
            SampleCompanion.snapshot.party.forEach { DecompIconSource.get(it.species) }
        }
    }

    @Test fun party() = paparazzi.snapshot { Window("PARTY") }

    @Test fun settings() = paparazzi.snapshot { Window("SETTINGS") }

    /** The game at its largest whole scale under the window (5x is 1200x800), dimmed, with the window over it. */
    @Composable
    private fun Window(tab: String) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FakeGame(Modifier.offset(x = px(40), y = px(80)).size(px(1200), px(800)))
            SidePanelHost(window = true, onClose = {}) {
                CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab)
            }
        }
    }

    @Composable
    private fun px(v: Int): Dp = with(LocalDensity.current) { v.toDp() }
}
