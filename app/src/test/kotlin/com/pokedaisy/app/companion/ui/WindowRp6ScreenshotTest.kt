package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The companion's window on the Retroid Pocket 6 itself: 1920x1080 at its 369 dpi, so the window comes out at the
 * density the device gives it (the other side-panel tests use 2.0). Pages that only overflowed there go here.
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*WindowRp6ScreenshotTest'
 */
class WindowRp6ScreenshotTest {
    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
            density = Density.create(369),
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before
    fun game() {
        activeGame = GameKind.FIRERED
        OptionColors.inGame = true
    }

    /** GUIDE's first-time notice: GO BACK and I UNDERSTAND side by side. */
    @Test fun guideNotice() = paparazzi.snapshot {
        Window { CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings().apply { noticeAccepted = false }, initialTab = "GUIDE") }
    }

    /** An unchecked guide's notice (SoulGold's on the RP6): one more sentence, which pushed the buttons off the bottom.
     * The notice alone over the game's backdrop, the tab bar's height kept free under it as CompanionScreen does. */
    @Test fun guideNoticeUnverified() = paparazzi.snapshot {
        Window {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF4E8A7E)).padding(12.dp)) {
                    GuideNotice(verified = false, onAccept = {}, onBack = {})
                }
                Box(Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF4E8A7E)))
            }
        }
    }

    // The companion's text a step smaller on a big screen (SidePanel's compactText), tab by tab, and today's beside it.
    @Test fun partyCompact() = paparazzi.snapshot { Window(compact = true) { Tab("PARTY") } }

    @Test fun partyNormal() = paparazzi.snapshot { Window { Tab("PARTY") } }

    @Test fun settingsCompact() = paparazzi.snapshot { Window(compact = true) { Tab("SETTINGS") } }

    @Test fun settingsNormal() = paparazzi.snapshot { Window { Tab("SETTINGS") } }

    @Test fun summaryCompact() = paparazzi.snapshot {
        Window(compact = true) { CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialMonIndex = 0) }
    }

    @Test fun battleCompact() = paparazzi.snapshot {
        Window(compact = true) {
            CompanionScreen(BattleButtonsScreenshotTest.battle, SampleCompanion.Slots, SampleCompanion.Settings(), BattleButtonsScreenshotTest.input, initialTab = "BATTLE")
        }
    }

    @Test fun bagCompact() = paparazzi.snapshot { Window(compact = true) { Tab("ITEMS") } }

    @Test fun guideCompact() = paparazzi.snapshot { Window(compact = true) { Tab("GUIDE") } }

    @Composable
    private fun Tab(tab: String) = CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab)

    @Composable
    private fun Window(compact: Boolean = false, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF3A5A4A))) {
            SidePanelHost(window = true, onClose = {}, compactText = compact, content = content)
        }
    }
}
