package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.android.resources.Density
import com.pokedaisy.app.companion.*
import com.pokedaisy.app.companion.data.LibraryView
import com.pokedaisy.app.companion.data.RomCategory
import com.pokedaisy.app.companion.ui.theme.AppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The actual shared app presenters with iPad-sized data and host capabilities. */
class SharedAppScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1536, screenHeight = 2048,
            density = Density.XHIGH, orientation = ScreenOrientation.PORTRAIT),
        theme = "android:Theme.Material.NoActionBar")
    @Before fun hosts() = installTestHosts(paparazzi.context)
    private val games = listOf(
        LibraryGame("Ember Road.gba", "Ember Road", "roms/Ember Road.gba", favorite = true, hasSave = true),
        LibraryGame("Sea Glass.gba", "Sea Glass", "roms/Sea Glass.gba", isNew = true),
        LibraryGame("Night Trail.gbc", "Night Trail", "roms/Night Trail.gbc"))
    private val actions = LibraryActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    @Composable private fun Frame(section: LibrarySection, settings: @Composable ColumnScope.() -> Unit = {}) {
        val m = rememberGbaTextMetrics()
        val small = rememberGbaTextMetrics(1f)
        val views = listOf(LibraryView.ALL, LibraryView.FAVORITES, LibraryView.RECENT, LibraryView.GBA)
        val state = LibraryPresentation(games, true, section, views, LibraryView.ALL,
            views.associateWith { if (it == LibraryView.FAVORITES) 1 else 3 }, true, "", "")
        LibraryScreen(state, actions, m, small, key = { it.id }, settings = settings) { game, up ->
            LibraryRomTile(game, m, small, upTo = up, onClick = {},
                cover = { LibraryCover(null, if (game.id.endsWith("gbc")) RomCategory.GBC else RomCategory.GBA, m, it) }, menu = {})
        }
    }

    @Test fun library() = paparazzi.snapshot { AppTheme { CompactTextOnSmallWindows { Frame(LibrarySection.GAMES) } } }

    @Test fun settings() = paparazzi.snapshot { AppTheme { CompactTextOnSmallWindows {
        val settings = object : CompanionSettings by SampleCompanion.Settings() {
            override val hasRunningGame = false
            override val resumeGamesEditable = true
            override val shownRows = setOf("TOUCH CONTROLS", "RESUME GAMES", "LANGUAGE", "HELP", "DARK MODE", "THEME", "FF SPEED", "TAB BAR")
        }
        val m = rememberGbaTextMetrics()
        Frame(LibrarySection.SETTINGS) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                CompanionSettingsScreen(settings, application = true,
                    applicationFooter = { AppAboutFooter("preview", m) {} },
                    homeExtras = listOf(SettingRow("RUN SETUP", null, labelBadge = NewFeatures.NEW_TAG) {}))
            }
        }
    } } }

    @Test fun setup() = paparazzi.snapshot { AppTheme { CompactTextOnSmallWindows {
        val m = rememberGbaTextMetrics()
        SetupFrame(0, 3, canGoBack = false, busy = false, last = false, skipping = false, m, {}, {}) {
            SetupRomsPage(SetupRomsModel(listOf("PokeDaisy/roms"), games.map { it.label }, linked = false),
                m, rememberGbaTextMetrics(1f), {})
        }
    } } }

    @Test fun setupButtons() = paparazzi.snapshot { AppTheme { CompactTextOnSmallWindows {
        val m = rememberGbaTextMetrics()
        SetupFrame(2, 3, canGoBack = true, busy = false, last = true, skipping = false, m, {}, {}) {
            SetupButtonsPage(com.pokedaisy.app.GbaControls.Btn.entries.associateWith { listOf("BUTTON_A") },
                KeyCapture(), m, rememberGbaTextMetrics(1f),
                request = { KeyCapture.Request(it.title, chord = false, onDone = {}) },
                group = com.pokedaisy.app.GbaControls.Group.GBA, usesSecondScreen = false)
        }
    } } }

    @Test fun editor() = paparazzi.snapshot { AppTheme {
        val style = TouchControlsStyle(extras = setOf(TouchButton.FF, TouchButton.SAVE_STATE, TouchButton.LOAD_STATE), xyButtons = false)
        val stage = gamePanelLayout(1536f, 2048f, 2f, style = com.pokedaisy.app.companion.TouchControlsStyle())
        TouchLayoutEditor(stage, style, rememberGbaTextMetrics(), onDone = {}, drawBackdrop = true)
    } }
}
