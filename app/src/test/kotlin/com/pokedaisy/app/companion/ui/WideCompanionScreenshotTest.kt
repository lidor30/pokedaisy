package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Before
import org.junit.Rule

/**
 * Every [CompanionScreenshotTest] shot again on the Thor's top screen
 * (1920x1080): where the companion goes with
 * SWAP SCREENS on and the game on the bottom screen. Same tests, same names
 * with this class's prefix:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*WideCompanionScreenshotTest'
 */
class WideCompanionScreenshotTest : CompanionScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
            // Paparazzi 1.3.4's layoutlib only has the density buckets: 2.0 gives the pixel
            // font the 3 px per font pixel it has on the real Thor (2.625 bottom, ~2.5 top).
            density = Density.XHIGH,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )
}
