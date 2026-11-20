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
 * The companion in Omega Ruby / Alpha Sapphire's and Sun / Moon's skins (CompanionSkins.POKEMON_ORAS / POKEMON_SM, the
 * party screen in each version's colour, XyTint), at the Thor bottom screen's size. A look, not a gate:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*N3dsGamesCompanionScreenshotTest'
 */
class N3dsGamesCompanionScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before fun inGame() {
        OptionColors.inGame = true
    }

    @After fun reset() {
        activeGame = GameKind.FIRERED
        OptionColors.inGame = false
    }

    private fun tab(game: GameKind, name: String) = paparazzi.snapshot {
        activeGame = game
        CompanionScreen(SampleCompanion.snapshot.copy(game = game), SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = name)
    }

    @Test fun omegaRubyParty() = tab(GameKind.POKEMON_OMEGA_RUBY, "PARTY")
    @Test fun omegaRubyBag() = tab(GameKind.POKEMON_OMEGA_RUBY, "ITEMS")
    @Test fun alphaSapphireParty() = tab(GameKind.POKEMON_ALPHA_SAPPHIRE, "PARTY")
    @Test fun alphaSapphireSettings() = tab(GameKind.POKEMON_ALPHA_SAPPHIRE, "SETTINGS")
    @Test fun sunParty() = tab(GameKind.POKEMON_SUN, "PARTY")
    @Test fun sunBag() = tab(GameKind.POKEMON_SUN, "ITEMS")
    @Test fun moonParty() = tab(GameKind.POKEMON_MOON, "PARTY")
    @Test fun moonSettings() = tab(GameKind.POKEMON_MOON, "SETTINGS")
}
