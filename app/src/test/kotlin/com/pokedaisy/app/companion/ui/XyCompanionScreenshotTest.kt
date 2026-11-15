package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The companion in Pokemon X's skin (CompanionSkins.POKEMON_XY: its smooth windows, sans-serif, green palette, the
 * party screen and backdrop), at the RP6's one-screen window size. A look, not a gate:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*XyCompanionScreenshotTest'
 */
class XyCompanionScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before fun game() {
        activeGame = GameKind.POKEMON_X
        OptionColors.inGame = true
    }

    @After fun reset() {
        activeGame = GameKind.FIRERED
        OptionColors.inGame = false
    }

    private fun tab(name: String) = paparazzi.snapshot {
        CompanionScreen(SampleCompanion.snapshot.copy(game = GameKind.POKEMON_X), SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = name)
    }

    @Test fun party() = tab("PARTY")
    @Test fun bag() = tab("ITEMS")
    @Test fun states() = tab("STATES")
    @Test fun settings() = tab("SETTINGS")
}
