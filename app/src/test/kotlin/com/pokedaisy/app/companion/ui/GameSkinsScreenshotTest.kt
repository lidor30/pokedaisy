package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.FieldHudLook
import com.pokedaisy.app.companion.FieldHudStyle
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.ui.theme.DarkMode
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The companion and the HUD in each DS / GBA game's skin (CompanionSkins: HeartGold / SoulSilver, Platinum, Black / White,
 * Black 2 / White 2, FireRed / LeafGreen, Emerald, Ruby / Sapphire): PARTY, BAG, SETTINGS at the Thor's bottom screen, the
 * HUD (LOOK GAME) with its BAG open at the top screen's 1920x1080. A look, not a gate:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*GameSkinsScreenshotTest'
 */
class GameSkinsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before fun hosts() = installTestHosts(paparazzi.context)

    @After fun reset() {
        CompanionSkins.override = null
        DarkMode.override = null
        activeGame = GameKind.FIRERED
        OptionColors.inGame = false
    }

    private fun use(game: GameKind, skin: CompanionSkin) {
        activeGame = game
        OptionColors.inGame = true
        CompanionSkins.override = skin
    }

    private fun tabs(game: GameKind, skin: CompanionSkin, dark: Boolean = false) {
        use(game, skin)
        if (dark) DarkMode.override = true
        for (tab in listOf("PARTY", "ITEMS", "SETTINGS")) {
            paparazzi.snapshot(tab.lowercase()) {
                CompanionScreen(SampleCompanion.snapshot.copy(game = game), SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab)
            }
        }
    }

    @Test fun heartgold() = tabs(GameKind.HEARTGOLD, CompanionSkins.HEARTGOLD)
    @Test fun platinum() = tabs(GameKind.PLATINUM, CompanionSkins.PLATINUM)
    @Test fun black() = tabs(GameKind.BLACK, CompanionSkins.UNOVA)
    @Test fun black2() = tabs(GameKind.BLACK2, CompanionSkins.UNOVA2)
    @Test fun firered() = tabs(GameKind.FIRERED, CompanionSkins.FIRERED)
    @Test fun emerald() = tabs(GameKind.EMERALD, CompanionSkins.EMERALD)
    @Test fun rubySapphire() = tabs(GameKind.EMERALD, CompanionSkins.RUBY_SAPPHIRE)
    /** A light skin gives way to the app's own dark windows in DARK MODE. */
    @Test fun heartgoldDark() = tabs(GameKind.HEARTGOLD, CompanionSkins.HEARTGOLD, dark = true)
}

/** The HUD (LOOK GAME) in each skin over the top screen's 1920x1080, with its BAG and a summary open too. */
class GameSkinsHudScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1920, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before fun hosts() = installTestHosts(paparazzi.context)

    @After fun reset() {
        CompanionSkins.override = null
        activeGame = GameKind.FIRERED
        OptionColors.inGame = false
    }

    private fun hud(game: GameKind, skin: CompanionSkin) {
        activeGame = game
        OptionColors.inGame = true
        CompanionSkins.override = skin
        val style = FieldHudStyle(look = FieldHudLook.GAME)
        val snap = FieldHudScreenshotTest.snapshot.copy(game = game)
        paparazzi.snapshot("hud") { FieldHudScene(style, FieldHudScreenshotTest.wild, true, null, "fr", snap, 0 to 0, false, false, null) }
        paparazzi.snapshot("hud-bag") { FieldHudScene(style, FieldHudScreenshotTest.wild, true, FieldHudPanel.BAG, "fr", snap, 0 to 0, false, false, null) }
        paparazzi.snapshot("hud-quick") {
            val state = QuickMenuState().apply { trigger(true); items = com.pokedaisy.app.companion.QuickAction.entries; key(MenuKey.RIGHT); key(MenuKey.RIGHT) }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                FieldHudScene(style, FieldHudScreenshotTest.wild, true, null, "fr", snap, 0 to 0, false, false, null)
                QuickMenu(state, snap, QuickAll, com.pokedaisy.app.companion.QuickAction.entries.toSet(), FieldHudLook.GAME)
            }
        }
        paparazzi.snapshot("hud-summary") { FieldHudScene(style, FieldHudScreenshotTest.wild, true, FieldHudPanel.SUMMARY, "fr", snap, 0 to 0, false, false, null) }
    }

    @Test fun heartgold() = hud(GameKind.HEARTGOLD, CompanionSkins.HEARTGOLD)
    @Test fun platinum() = hud(GameKind.PLATINUM, CompanionSkins.PLATINUM)
    @Test fun black() = hud(GameKind.BLACK, CompanionSkins.UNOVA)
    @Test fun black2() = hud(GameKind.BLACK2, CompanionSkins.UNOVA2)
    @Test fun firered() = hud(GameKind.FIRERED, CompanionSkins.FIRERED)
    @Test fun emerald() = hud(GameKind.EMERALD, CompanionSkins.EMERALD)
    @Test fun rubySapphire() = hud(GameKind.EMERALD, CompanionSkins.RUBY_SAPPHIRE)
}

/** Every QUICK MENU action offered, none running. */
private object QuickAll : QuickActions {
    override fun offered(action: com.pokedaisy.app.companion.QuickAction) = true
    override fun run(action: com.pokedaisy.app.companion.QuickAction) {}
    override fun flyTo(mapsec: Int, targets: com.pokedaisy.app.companion.data.FlyTargets) {}
    override fun riding() = false
    override fun flying() = false
    override fun wildEncounters() = 100
}

/** SETTINGS > HUD and > QUICK MENU with their SHOW rows' icons, on a screen tall enough for every row. */
class SettingsRowIconsScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1240, screenHeight = 2700, orientation = ScreenOrientation.PORTRAIT),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before fun hosts() = installTestHosts(paparazzi.context)

    private fun page(id: String) = paparazzi.snapshot {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            GameBackdrop(GameKind.FIRERED)
            CompanionSettingsScreen(SampleCompanion.Settings(), initialPage = id)
        }
    }

    @Test fun hud() = page("FIELD_HUD")
    @Test fun quickMenu() = page("QUICK_MENU")
}
