package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.MoveVsFoe
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The 3DS battle on one screen as a player sees it: a real top-screen battle frame (headless, the players' carts and saves:
 * never committed, so opt-in: POKEDAISY_N3DS_FRAMES names a folder of 400 x 240 `sun.png`, `moon.png`, `oras.png`) filling a
 * 16:9 screen at 5:3, with the battle corner (XyBattleCorner) over its bottom right, on its command menu and its moves with
 * BATTLE HINTS. The moves are the frames' own battlers'.
 *
 *     POKEDAISY_N3DS_FRAMES=<dir> ./gradlew :app:recordPaparazziDebug --tests '*N3dsBattleOneScreenScreenshotTest'
 */
class N3dsBattleOneScreenScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private fun vs(m: String, pct: Int) = listOf(MoveVsFoe("FOE", m, pct))

    // Sun: Sylveon against a Grubbin (BUG).
    private val sun = listOf(
        MoveView("Hyper Voice", "NORMAL", 16, 90, vs("1x", 100), maxPp = 16),
        MoveView("Psyshock", "PSYCHIC", 16, 80, vs("1x", 100), maxPp = 16),
        MoveView("Hidden Power", "NORMAL", 24, 60, vs("1x", 100), maxPp = 24),
        MoveView("Shadow Ball", "GHOST", 24, 80, vs("1x", 100), maxPp = 24),
    )
    // Moon: Mimikyu against a Gumshoos (NORMAL).
    private val moon = listOf(
        MoveView("Shadow Sneak", "GHOST", 47, 40, vs("0x", 0), maxPp = 48),
        MoveView("Play Rough", "FAIRY", 16, 90, vs("1x", 100), maxPp = 16),
        MoveView("Swords Dance", "NORMAL", 31, 0, maxPp = 32),
        MoveView("Leech Life", "BUG", 16, 80, vs("1x", 100), maxPp = 16),
    )
    // Alpha Sapphire: Latias against a Zigzagoon (NORMAL).
    private val oras = listOf(
        MoveView("Heal Pulse", "PSYCHIC", 10, 0, maxPp = 10),
        MoveView("Dragon Breath", "DRAGON", 20, 60, vs("1x", 100), maxPp = 20),
        MoveView("Mist Ball", "PSYCHIC", 5, 70, vs("1x", 100), maxPp = 5),
        MoveView("Psycho Shift", "PSYCHIC", 10, 0, maxPp = 10),
    )

    private fun shot(frame: String, mode: Int, moves: List<MoveView>) {
        val dir = System.getenv("POKEDAISY_N3DS_FRAMES")?.let(::File)
        val file = dir?.let { File(it, "$frame.png") }
        assumeTrue(file != null && file.isFile)
        val picture = android.graphics.BitmapFactory.decodeFile(file!!.path).asImageBitmap()
        paparazzi.snapshot {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val fw = minOf(w, h * 5f / 3f)
                val fh = fw * 3f / 5f
                val rect = Rect((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
                val d = LocalDensity.current
                Box(Modifier.fillMaxSize()) {
                    Image(
                        picture, null,
                        Modifier.offset(with(d) { rect.left.toDp() }, with(d) { rect.top.toDp() })
                            .size(with(d) { rect.width.toDp() }, with(d) { rect.height.toDp() }),
                        contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
                    )
                    XyBattleCorner(DsBattleMenuState(), rect, mode, false, moves, null, 0.5f, power = mode == 2)
                }
            }
        }
    }

    @Test fun sunCommand() = shot("sun", 1, sun)
    @Test fun sunMoves() = shot("sun", 2, sun)
    @Test fun moonCommand() = shot("moon", 1, moon)
    @Test fun moonMoves() = shot("moon", 2, moon)
    @Test fun alphaSapphireCommand() = shot("oras", 1, oras)
    @Test fun alphaSapphireMoves() = shot("oras", 2, oras)
}
