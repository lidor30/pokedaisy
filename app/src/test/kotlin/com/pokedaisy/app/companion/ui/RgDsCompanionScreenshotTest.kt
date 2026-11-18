package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Before
import org.junit.Rule

/**
 * Every [CompanionScreenshotTest] shot again on the Anbernic RG DS's 640x480 bottom screen, at 240 dpi (its density isn't
 * known yet; at any density up to ~1.6x the GBA pixel is 2 screen pixels, so the layout comes out the same): a short
 * screen ([isShortCompanion]), the text a step smaller. Same tests, same names with this class's prefix:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*RgDsCompanionScreenshotTest'
 */
class RgDsCompanionScreenshotTest : CompanionScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 640, screenHeight = 480, density = Density.HIGH, xdpi = 200, ydpi = 200,
            orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )
}
