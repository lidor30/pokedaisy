package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.RomFileReader
import com.pokedaisy.app.companion.data.activeGame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt
import okio.Path.Companion.toOkioPath

/**
 * A phone held upright (PortraitPanel): the game across the top, the companion
 * docked under it at the size the player picked, the grip on its top edge, and
 * the touch controls in the gap between when there's no controller. On a Pixel 6
 * (1080x2400, 420 dpi - the common modern phone shape; a Galaxy S2x is 1080x2340).
 * Laid out with PortraitLayout, the same math the app's views use; the game is
 * a drawn stand-in (Paparazzi can't run the core). See CompanionScreenshotTest.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*PortraitScreenshotTest'
 */
class PortraitScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
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

    /** The default: the companion at the Thor bottom screen's shape, a controller connected. */
    @Test fun controller() = paparazzi.snapshot { Scene(PortraitLayout.DEFAULT_RATIO, touchPad = false) }

    /** No controller: the touch controls takes the gap between game and companion. */
    @Test fun touchPad() = paparazzi.snapshot { Scene(PortraitLayout.DEFAULT_RATIO, touchPad = true) }

    /** The smallest companion: the most room for the touch controls. */
    @Test fun smallest() = paparazzi.snapshot { Scene(PortraitLayout.MIN_RATIO, touchPad = true) }

    /** Dragged all the way up: everything under the game but the grip's strip, which keeps the companion's room its own. */
    @Test fun tallest() = paparazzi.snapshot { Scene(10f, touchPad = true) }

    /** SETTINGS > COMPANION > UNDER GAME: the companion right under the game, the touch controls at the screen's bottom. */
    @Test fun underGame() = paparazzi.snapshot { Scene(PortraitLayout.DEFAULT_RATIO, touchPad = true, underGame = true) }

    @Test fun underGameSmallest() = paparazzi.snapshot { Scene(PortraitLayout.MIN_RATIO, touchPad = true, underGame = true) }

    @Test fun underGameTallest() = paparazzi.snapshot { Scene(10f, touchPad = true, underGame = true) }

    @Test fun settings() = paparazzi.snapshot { Scene(PortraitLayout.DEFAULT_RATIO, touchPad = false, tab = "SETTINGS") }

    @Test fun map() = paparazzi.snapshot { Scene(PortraitLayout.DEFAULT_RATIO, touchPad = false, tab = "MAP") }

    /** The screen as PortraitPanel lays it out for [ratio] (PortraitLayout.arrange). */
    @Composable
    private fun Scene(ratio: Float, touchPad: Boolean, tab: String = "PARTY", underGame: Boolean = false) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val gameH = (w / 1.5f).roundToInt()
            val gripH = with(LocalDensity.current) { (rememberGbaTextMetrics().u * 24).toPx().roundToInt() }
            val a = PortraitLayout.arrange(w, h, gameH, gripH, ratio, underGame)
            FakeGame(Modifier.fillMaxWidth().height(px(a.gameBottom)))
            if (touchPad) {
                // The game's TouchControlsView hosts this composable (a ComposeView in an AndroidView doesn't draw here).
                TouchControls(
                    com.pokedaisy.app.companion.TouchControlsStyle(), portrait = true, input = TouchInput(),
                    modifier = Modifier.fillMaxWidth().offset(y = px(a.padTop)).height(px(a.padBottom - a.padTop)),
                )
            }
            Box(Modifier.fillMaxWidth().offset(y = px(a.companionTop)).height(px(a.companionHeight))) {
                CompositionLocalProvider(LocalCompanionTopInset provides a.topInset(gripH)) {
                    SidePanelCompanion {
                        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab)
                    }
                }
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = px(a.gripTop))) { PortraitGrip(a.grip) {} }
        }
    }

    @Composable
    private fun px(v: Int): Dp = with(LocalDensity.current) { v.toDp() }
}
