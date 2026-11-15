package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.TipId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Feature tips on one screen: the card at the top of the game, over the HUD's game frame (1920x1080).
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*FeatureTip*ScreenshotTest'
 */
class FeatureTipGameScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = FieldHudScreenshotTest()

    @Before fun game() = setup.game()

    @After fun reset() = setup.reset()

    private fun shot(id: TipId) = paparazzi.snapshot {
        Box(Modifier.fillMaxSize()) {
            FieldHudScene(FieldHudStyle(), FieldHudScreenshotTest.wild)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                // Between the area name's corner and the minimap: about half the screen.
                val w = with(androidx.compose.ui.platform.LocalDensity.current) { 1040.toDp() }
                FeatureTipCard(featureTipText(id), Modifier.width(w), keys = keysFor(id), maxWidth = w)
            }
        }
    }

    @Test fun expand() = shot(TipId.EXPAND)
    @Test fun zoom() = shot(TipId.ZOOM)
    @Test fun quickMenu() = shot(TipId.QUICK_MENU)
    @Test fun hud() = shot(TipId.HUD)
    @Test fun autoRun() = shot(TipId.AUTO_RUN)
    @Test fun wild() = shot(TipId.WILD)
    @Test fun pc() = shot(TipId.PC)
    @Test fun fly() = shot(TipId.FLY)
    @Test fun reusableTms() = shot(TipId.REUSABLE_TMS)
}

/** The keys a card names: the default bindings. */
internal fun keysFor(id: TipId) = when (id) {
    TipId.ZOOM -> listOf("R3" to "ZOOM", "RIGHT STICK" to "LOOK AROUND")
    TipId.QUICK_MENU -> listOf("L3" to "QUICK MENU")
    else -> emptyList()
}

/** Feature tips on two screens: the card on the companion (the Thor's bottom screen), the game left clear. */
class FeatureTipCompanionScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val setup = CompanionScreenshotTest()

    @Before fun game() = setup.game()

    private fun shot(id: TipId) = paparazzi.snapshot {
        Box(Modifier.fillMaxSize()) {
            CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "PARTY")
            Box(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                FeatureTipCard(featureTipText(id), Modifier.fillMaxWidth(), keys = keysFor(id), roomy = true)
            }
        }
    }

    @Test fun expand() = shot(TipId.EXPAND)
    @Test fun zoom() = shot(TipId.ZOOM)
    @Test fun quickMenu() = shot(TipId.QUICK_MENU)
    @Test fun hud() = shot(TipId.HUD)
    @Test fun autoRun() = shot(TipId.AUTO_RUN)
    @Test fun wild() = shot(TipId.WILD)
    @Test fun pc() = shot(TipId.PC)
    @Test fun fly() = shot(TipId.FLY)
    @Test fun reusableTms() = shot(TipId.REUSABLE_TMS)

    /** SETTINGS > APP > TIPS, with its NEW tag. */
    @Test fun settingsRow() = paparazzi.snapshot {
        CompanionScreen(
            SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "SETTINGS", initialSettingsRow = "TIPS",
        )
    }
}
