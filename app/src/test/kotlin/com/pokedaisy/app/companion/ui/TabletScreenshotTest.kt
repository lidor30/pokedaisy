package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A 10" tablet held sideways (the Galaxy Tab A, 1920x1200 at 240 dpi): the companion window and the HUD's MAP and
 * summary, which all stop at [OVERLAY_MAX_HEIGHT_DP] there, so they come out about the same size.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*TabletScreenshotTest'
 */
class TabletScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1200, orientation = ScreenOrientation.LANDSCAPE,
            density = Density.HIGH,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before
    fun game() {
        activeGame = GameKind.FIRERED
        OptionColors.inGame = true
    }

    @Test fun companionWindow() = paparazzi.snapshot {
        Window { CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "PARTY") }
    }

    @Test fun hudMap() = paparazzi.snapshot { FieldHudScene(FieldHudStyle(), wild = null, panel = FieldHudPanel.MAP) }

    @Test fun hudSummary() = paparazzi.snapshot { FieldHudScene(FieldHudStyle(), wild = null, panel = FieldHudPanel.SUMMARY) }

    @Composable
    private fun Window(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF3A5A4A))) {
            SidePanelHost(window = true, onClose = {}, compactText = true, content = content)
        }
    }
}
