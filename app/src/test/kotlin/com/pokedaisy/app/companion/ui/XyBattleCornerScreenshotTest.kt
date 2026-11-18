package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.MoveVsFoe
import org.junit.Rule
import org.junit.Test

/**
 * Pokemon X's battle corner (XyBattleCorner) on its move list, with and without each move's power (BATTLE HINTS), over a
 * plain 5:3 picture: 16:9 and a small dense 640 x 480 screen.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*XyBattleCornerScreenshotTest'
 */
abstract class XyBattleCornerShots(w: Int, h: Int, density: Density = Density.XHIGH) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = w, screenHeight = h, density = density, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val moves = listOf(
        MoveView("Tackle", "NORMAL", 35, 50, listOf(MoveVsFoe("FOE", "1x", 100)), maxPp = 35),
        MoveView("Growl", "NORMAL", 40, 0, maxPp = 40),
        MoveView("Vine Whip", "GRASS", 25, 45, listOf(MoveVsFoe("FOE", "½x", 50)), maxPp = 25),
        MoveView("Thunder Shock", "ELECTRIC", 30, 40, listOf(MoveVsFoe("FOE", "2x", 200)), maxPp = 30),
    )

    private fun shot(power: Boolean) = paparazzi.snapshot {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val fw = minOf(w, h * 5f / 3f)
            val fh = fw * 3f / 5f
            val frame = Rect((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF5E8A54), frame.topLeft, frame.size) }
                XyBattleCorner(DsBattleMenuState(), frame, 2, false, moves, null, 0.5f, power = power)
            }
        }
    }

    @Test fun moves() = shot(false)
    @Test fun movesPower() = shot(true)
}

class XyBattleCornerScreenshotTest : XyBattleCornerShots(1920, 1080)
class XyBattleCornerSmallScreenshotTest : XyBattleCornerShots(640, 480, Density.HIGH)
