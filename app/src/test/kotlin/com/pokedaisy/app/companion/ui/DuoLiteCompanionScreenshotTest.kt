package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import org.junit.Before
import org.junit.Rule

/**
 * Every [CompanionScreenshotTest] shot again on the Retroid Pocket Duo Lite's 1280x960 screen (4:3),
 * at the Thor's density. Same tests, same names with this class's prefix:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*DuoLiteCompanionScreenshotTest'
 */
class DuoLiteCompanionScreenshotTest : CompanionScreenshotTest() {
    @get:Rule
    override val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1280, screenHeight = 960, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )
}
