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
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.SnapshotView
import org.junit.Rule
import org.junit.Test

/**
 * Pokemon X's field HUD (XyFieldHud) with a full party of six, the minimap and all four buttons, where the top screen
 * is the whole height: 16:9, a 4:3 screen (the 5:3 picture letterboxed), and a small dense 640 x 480 one (the cells
 * shrink to fit); and Pokemon Y's, in its red (XyTint), with and without the WILD panel. Its pictures (map, icons) come
 * from the cart, so blanks here.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*XyFieldHudScreenshotTest'
 */
abstract class XyFieldHudShots(w: Int, h: Int, density: Density = Density.XHIGH) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = w, screenHeight = h, density = density, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private fun mon(n: String, hp: Int, st: String = "") =
        MonView(species = 1, name = n, level = 50, hp = hp, maxHp = 100, status = st, types = emptyList(), iconAsset = null)

    private fun snap(game: GameKind) = SnapshotView(
        connected = true, game = game,
        party = listOf(mon("A", 100), mon("B", 40, "PAR"), mon("C", 10), mon("D", 0), mon("E", 100, "PSN"), mon("F", 75)),
    )

    @Test fun hud() = shot(GameKind.POKEMON_X)
    @Test fun hudY() = shot(GameKind.POKEMON_Y)
    @Test fun hudYWild() = shot(GameKind.POKEMON_Y, openWild = true)
    @Test fun hudOmegaRuby() = shot(GameKind.POKEMON_OMEGA_RUBY, openWild = true)
    @Test fun hudAlphaSapphire() = shot(GameKind.POKEMON_ALPHA_SAPPHIRE)
    @Test fun hudSun() = shot(GameKind.POKEMON_SUN, openWild = true)
    @Test fun hudMoon() = shot(GameKind.POKEMON_MOON, liveMap = true)

    /** Sun / Moon's live map (Rotom's off the touch screen): a drawn stand-in, roads on grass, the player's arrow. */
    private fun rotomStandIn(): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(260, 124, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(0xFF4C8A3C.toInt())
        val road = android.graphics.Paint().apply { color = 0xFF8C8C8C.toInt() }
        c.drawRect(40f, 50f, 260f, 66f, road)
        c.drawRect(122f, 0f, 138f, 124f, road)
        c.drawCircle(130f, 58f, 7f, android.graphics.Paint().apply { color = 0xFFF2C21A.toInt() })
        return b
    }

    private fun shot(game: GameKind, openWild: Boolean = false, liveMap: Boolean = false) = paparazzi.snapshot {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            // The 5:3 top screen, as large as fits, centred.
            val fw = minOf(w, h * 5f / 3f)
            val fh = fw * 3f / 5f
            val frame = Rect((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF5E8A54), frame.topLeft, frame.size) }
                XyFieldHud(
                    snap(game), true, frame, openWild = openWild,
                    wild = listOf(XyWildRow("GRASS / CAVE", "Pidgey", null, 2, 3, 40)),
                    map = if (liveMap) XyMapSpot("", null, 260, 124, 130f, 58f, 0f, 0f, live = rotomStandIn())
                        else XyMapSpot("missing.png", null, 320, 210, 212f, 145f, 15f, 8f),
                    onTouchScreen = {}, onOpenCompanion = {},
                )
            }
        }
    }
}

class XyFieldHudScreenshotTest : XyFieldHudShots(1920, 1080)
class XyFieldHud43ScreenshotTest : XyFieldHudShots(1440, 1080)
class XyFieldHudSmallScreenshotTest : XyFieldHudShots(640, 480, Density.HIGH)
