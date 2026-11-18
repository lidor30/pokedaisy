package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.CompanionFocusStripe
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The silver strip X puts along the bottom of the game's picture while the buttons are on the companion's screen, on
 * the Thor's 1920x1080 top screen: a stand-in 3:2 game at 6.75x (1620x1080), the arrow at rest. See CompanionScreenshotTest.
 */
class CompanionFocusScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Test
    fun thorTopScreen() = paparazzi.snapshot {
        val game = Rect(150f, 0f, 1770f, 1080f)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            with(LocalDensity.current) {
                Box(
                    Modifier.offset(game.left.toDp(), game.top.toDp()).size(game.width.toDp(), game.height.toDp())
                        .background(Color(0xFF3A6B3A)),
                )
            }
            CompanionFocusStripe(shown = true, frame = game, gameRows = 160, onClose = {})
        }
    }
}
