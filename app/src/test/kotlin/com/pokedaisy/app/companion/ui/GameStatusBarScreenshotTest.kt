package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.BatteryStatus
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The top screen's status bar over a stand-in for the game, laid out like
 * GameStageLayout does on the Thor's 1920x1080 top screen: the bar as wide
 * as the game, the pair filling the height. See CompanionScreenshotTest.
 */
class GameStatusBarScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Composable
    private fun Stage(bar: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            // Wide enough for a 3:2 game under a bar of about this height.
            val w = with(LocalDensity.current) { 1480.toDp() }
            Column(Modifier.width(w)) {
                bar()
                Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Color(0xFF3A6B3A)))
            }
        }
    }

    @Test
    fun fireRed() = paparazzi.snapshot {
        Stage { GameStatusBar("FireRed", "Pallet Town", 224300, "14:05", battery = BatteryStatus(82, false)) }
    }

    @Test
    fun noMoneyLongName() = paparazzi.snapshot {
        Stage {
            GameStatusBar(
                "Pokemon - Emerald Version (USA, Europe) Randomizer Nuzlocke Edition", "Route 104", null, "2:05 PM",
                battery = BatteryStatus(12, true),
            )
        }
    }

    @Test
    fun starting() = paparazzi.snapshot {
        Stage { GameStatusBar("Pokémon Unbound", null, null, "09:41", battery = BatteryStatus(100, false)) }
    }
}
