package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchControlsStyle
import com.pokedaisy.app.companion.TouchLayout
import com.pokedaisy.app.companion.TouchPreset
import com.pokedaisy.app.companion.TouchSize
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The FIELD HUD with the touch controls up, one screen held sideways (GitHub #493): the HUD keeps clear only of the
 * controls' rows along the top and bottom edges ([TouchGeometry.hudClear]), so a CUSTOM layout with buttons halfway up
 * the side doesn't squeeze it into a strip. A 20:9 phone (2460x1080, the report's) here, 16:9 below.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*TouchHud*ScreenshotTest'
 */
class TouchHudScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 2460, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    /** The report's own layout: FF and A / B halfway up the right side, L / R at the top, SELECT / START at the bottom. */
    @Test fun reportedLayout() = paparazzi.snapshot { TouchHudScene(REPORTED_493) }

    @Test fun sides() = paparazzi.snapshot { TouchHudScene(TouchControlsStyle()) }

    @Test fun raisedLarge() = paparazzi.snapshot { TouchHudScene(TouchControlsStyle(preset = TouchPreset.RAISED, size = TouchSize.LARGE)) }

    companion object {
        /** GitHub #493's buttons, where its screenshot has them (centres, px at 2460x1080), at size LARGE. */
        val REPORTED_493: TouchControlsStyle by lazy {
            val style = TouchControlsStyle(
                size = TouchSize.LARGE,
                extras = setOf(TouchButton.FF, TouchButton.QUICK_MENU, TouchButton.SAVE_STATE, TouchButton.LOAD_STATE),
            )
            val w = 2460f; val h = 1080f; val dp = 2.75f
            val at = mapOf(
                TouchButton.L to (530f to 75f), TouchButton.R to (1860f to 95f),
                TouchButton.QUICK_MENU to (210f to 197f), TouchButton.SAVE_STATE to (210f to 297f), TouchButton.LOAD_STATE to (210f to 395f),
                TouchButton.DPAD to (360f to 665f), TouchButton.FF to (2010f to 512f),
                TouchButton.B to (2050f to 685f), TouchButton.A to (2240f to 610f),
                TouchButton.SELECT to (1555f to 990f), TouchButton.START to (1820f to 990f),
            )
            val placed = TouchGeometry.place(style, w, h, dp, portrait = false)
            val places = placed.items.mapNotNull { item ->
                val (cx, cy) = at[item.button] ?: return@mapNotNull null
                item.button to TouchGeometry.placeOf(item.copy(cx = cx, cy = cy), w, h, dp, 1f)
            }.toMap()
            style.withLayout(portrait = false, TouchLayout(places))
        }
    }
}

/** 16:9 (1920x1080): SIDES gives way to CLASSIC, whose D-pad and A / B reach into the picture. */
class TouchHud169ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    @Test fun reportedLayout() = paparazzi.snapshot { TouchHudScene(TouchHudScreenshotTest.REPORTED_493) }

    @Test fun classic() = paparazzi.snapshot { TouchHudScene(TouchControlsStyle()) }

    @Test fun raisedLarge() = paparazzi.snapshot { TouchHudScene(TouchControlsStyle(preset = TouchPreset.RAISED, size = TouchSize.LARGE)) }
}

/** EXPAND across the screen, the HUD (CORNERS) and the controls over it, the HUD kept to the game's middle columns
 * and clear of the rows along their edges, worked out as TouchControlsView does. */
@Composable
private fun TouchHudScene(touch: TouchControlsStyle, hud: FieldHudStyle = FieldHudStyle()) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val dp = LocalDensity.current.density
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val placed = TouchGeometry.place(touch, w, h, dp, portrait = false)
        val gameW = minOf(w, h * 1.5f)
        val l = (w - gameW) / 2
        val (top, bottom) = TouchGeometry.hudClear(placed, l, l + gameW)
        FakeGame(Modifier.fillMaxSize())
        FieldHud(
            FieldHudScreenshotTest.snapshot, shown = true, hud, clearTop = top, clearBottom = bottom,
            wild = FieldHudScreenshotTest.wild, onOpenCompanion = {},
            // EXPAND under touch controls: the game's middle columns (PokeDaisyActivity's hudTouchFrame).
            frame = Rect(l, 0f, l + gameW, Float.MAX_VALUE),
            avoid = placed.items.map { Rect(it.left, it.top, it.right, it.bottom) },
        )
        TouchControls(touch, portrait = false, input = TouchInput(), modifier = Modifier.fillMaxSize())
    }
}
