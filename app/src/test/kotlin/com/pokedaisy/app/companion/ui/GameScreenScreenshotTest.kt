package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.ui.theme.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual shared game slots, controls and companion; the native game surface is a colored stand-in. */
class GameScreenScreenshotTest {
    private val upright = DeviceConfig.PIXEL_5.copy(screenWidth = 1536, screenHeight = 2048,
        density = Density.XHIGH, orientation = ScreenOrientation.PORTRAIT)
    @get:Rule val paparazzi = Paparazzi(deviceConfig = upright, theme = "android:Theme.Material.NoActionBar")
    @Before fun hosts() = installTestHosts(paparazzi.context)

    @Test fun portrait() = paparazzi.snapshot { Scene() }
    @Test fun controller() = paparazzi.snapshot { Scene(visible = false) }
    @Test fun gameBoy() = paparazzi.snapshot { Scene(gameBoy = true) }
    @Test fun landscape() {
        paparazzi.unsafeUpdateConfig(deviceConfig = upright.copy(orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot { Scene() }
    }
    @Test fun window() {
        paparazzi.unsafeUpdateConfig(deviceConfig = upright.copy(orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot { Scene(sidePanel = SidePanelState(enabled = true, open = true)) }
    }
    @Test fun unlockedSidePanel() {
        paparazzi.unsafeUpdateConfig(deviceConfig = upright.copy(orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot { Scene(sidePanel = SidePanelState(enabled = true, open = true, window = false, widthFraction = .4f)) }
    }
    @Test fun hiddenSidePanel() {
        paparazzi.unsafeUpdateConfig(deviceConfig = upright.copy(orientation = ScreenOrientation.LANDSCAPE))
        paparazzi.snapshot { Scene(sidePanel = SidePanelState(enabled = true)) }
    }
    @Test fun hiddenPortraitCompanion() = paparazzi.snapshot { Scene(portraitHidden = true) }
    @Test fun tallBook() = paparazzi.snapshot {
        Scene(sidePanel = SidePanelState().withEnabled(true).withBook(740..796))
    }

    @Composable private fun Scene(
        visible: Boolean = true, gameBoy: Boolean = false, portraitHidden: Boolean = false,
        sidePanel: SidePanelState = SidePanelState(enabled = true, open = true, window = false, docked = true),
    ) {
        AppTheme {
            val style = TouchControlsStyle(xyButtons = false, extras = setOf(TouchButton.FF))
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val dp = LocalDensity.current.density
                val portrait = maxHeight >= maxWidth && sidePanel.book == null
                val state = androidx.compose.runtime.remember { GameScreenState(side = sidePanel,
                    portrait = PortraitPanelState(enabled = portrait, hidden = portraitHidden,
                        padStrip = if (visible) TouchGeometry.naturalHeight(style, maxWidth.value * dp, dp, gameBoy) else 0)) }
                val content: @Composable () -> Unit = {
                    CompanionScreen(SampleCompanion.snapshot, settings = SampleCompanion.Settings())
                }
                GameScreen(state, gameHeight = { (it / if (gameBoy) (160f / 144) else 1.5f).toInt() },
                    game = {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val aspect = if (gameBoy) 160f / 144 else 1.5f
                            val width = minOf(maxWidth, maxHeight * aspect)
                            Box(Modifier.size(width, width / aspect).align(if (portrait) androidx.compose.ui.Alignment.TopCenter else androidx.compose.ui.Alignment.Center)
                                .background(Color(0xFF38684B)))
                        }
                    },
                    companion = {
                        if (!portrait) SidePanelHost(window = sidePanel.isWindow, onClose = state::hide, content = content)
                        else SidePanelCompanion(content = content)
                    },
                    controls = { if (visible) TouchControls(style, portrait, TouchInput(), Modifier.fillMaxSize(),
                        gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy) })
            }
        }
    }
}
