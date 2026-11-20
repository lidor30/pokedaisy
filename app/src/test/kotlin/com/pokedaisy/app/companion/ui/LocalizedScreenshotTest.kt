package com.pokedaisy.app.companion.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.withMovesVs
import com.pokedaisy.app.companion.i18n.AppLanguage
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.ui.theme.AppTheme
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The companion's main screens in each non-English language - for checking
 * that translations fit (German runs longest) and Japanese renders in the
 * pixel font. Shots are named `..._<screen>_<LANG>.png`:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*LocalizedScreenshotTest'
 */
@RunWith(Parameterized::class)
class LocalizedScreenshotTest(private val lang: AppLanguage) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun languages() = AppLanguage.entries.filter { it != AppLanguage.EN }
    }

    @Before fun hosts() = installTestHosts(paparazzi.context)
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Before
    fun setUp() {
        CompanionScreenshotTest().game()
        L10n.apply(lang.code, null)
    }

    @After
    fun tearDown() = L10n.apply("EN", null)

    private fun tab(name: String) = paparazzi.snapshot(lang.code) {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = name)
    }

    @Test fun settings() = tab("SETTINGS")
    @Test fun party() = tab("PARTY")
    @Test fun items() = tab("ITEMS")
    @Test fun dex() = tab("DEX")
    @Test fun guide() = tab("GUIDE")
    @Test fun map() = tab("MAP")
    @Test fun states() = tab("STATES")
    @Test fun more() = tab("MORE")

    /** Battle INFO against an Onix with the game in this language: its types in the game's words (GitHub #40). */
    @Test fun battleInfoTypes() {
        com.pokedaisy.app.companion.data.romLanguage = lang.romLetter ?: 'E'   // no game is in Portuguese
        try {
            paparazzi.snapshot(lang.code) {
                AppTheme {
                    val party = SampleCompanion.snapshot.party
                    val mu = com.pokedaisy.app.companion.data.typeMatchups(
                        com.pokedaisy.app.companion.data.typeIdOf("Rock"), com.pokedaisy.app.companion.data.typeIdOf("Ground"),
                    )
                    val onix = party[1].copy(
                        name = "ONIX", types = listOf("Rock", "Ground"),
                        weaknesses = mu.weaknesses, resistances = mu.resistances, immunities = mu.immunities,
                    )
                    BattleInfoScreen(listOf(party[0].withMovesVs(onix)), listOf(onix), isDouble = false, showHints = true, onBack = {}, onShowSuggestions = {})
                }
            }
        } finally {
            com.pokedaisy.app.companion.data.romLanguage = 'E'
        }
    }

    /** The dex page's EVOLVE (EEVEE: stones, friendship by day / at night) and MOVES (BULBASAUR: level-up, TM / HM). */
    @Test fun dexEvolve() = dexSection(133, DexSection.EVOLVE)
    @Test fun dexMoves() = dexSection(1, DexSection.MOVES)

    private fun dexSection(entry: Int, section: DexSection) {
        val dex = SampleCompanion.snapshot.pokedex!!
        val g = com.pokedaisy.app.companion.data.GUIDE_TABLES_FIRERED_REV1
        val species = com.pokedaisy.app.companion.data.PokedexSource.speciesFor(dex.tables, entry)
        com.pokedaisy.app.companion.data.PokedexSource.entry(dex.tables, entry)
        com.pokedaisy.app.companion.data.PokedexSource.frontSprite(dex.tables, species)
        com.pokedaisy.app.companion.data.DexDetails.family(dex.tables, species)?.forEach {
            com.pokedaisy.app.companion.data.DecompIconSource.get(it.from); com.pokedaisy.app.companion.data.DecompIconSource.get(it.to)
        }
        com.pokedaisy.app.companion.data.DexDetails.levelUp(dex.tables, g, species)
        com.pokedaisy.app.companion.data.DexDetails.teachable(dex.tables, g, species)
        paparazzi.snapshot(lang.code) {
            AppTheme {
                val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                ui.open = entry
                ui.section = section
                PokedexEntryScreen(dex, ui, entry, guide = g)
            }
        }
    }

    @Test
    fun partyStats() = paparazzi.snapshot(lang.code) {
        AppTheme { MonDetailScreen(SampleCompanion.snapshot.party, 0, {}, {}, initialShowStats = true) }
    }

    @Test
    fun partyMoves() = paparazzi.snapshot(lang.code) {
        AppTheme { MonDetailScreen(SampleCompanion.snapshot.party, 0, {}, {}) }
    }

    @Test
    fun battleInfo() = paparazzi.snapshot(lang.code) {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            BattleInfoScreen(
                listOf(party[0].withMovesVs(party[1])), listOf(party[1]), isDouble = false, showHints = true,
                onBack = {}, onShowSuggestions = {}, onShowStats = {},
            )
        }
    }

    @Test
    fun battleStats() = paparazzi.snapshot(lang.code) {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            BattleStatsScreen(listOf(party[0]), listOf(party[1]), "FOE", onBack = {})
        }
    }

    /** The LANGUAGE row - "(LANGUAGE)" beside the word, so it's findable from any language - in the settings look. */
    /** WHICH GAME IS THIS? - its message, LIKELY tags and NONE OF THESE must fit in every language. */
    @Test fun gamePicker() = paparazzi.snapshot(lang.code) {
        CompanionScreen(
            SnapshotView(
                connected = false, error = "unrecognized FireRed-based ROM hack", unsupported = true,
                gamePicker = SamplePicker.picker(miss = com.pokedaisy.app.companion.data.BestEffort.Miss.NO_PARTY),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), askForSupport = {}, gamePick = SamplePicker.actions,
        )
    }

    /** NOT SUPPORTED after NONE OF THESE, both buttons side by side: the longest labels must still fit. */
    @Test fun gamePickerDeclined() = paparazzi.snapshot(lang.code) {
        CompanionScreen(
            SnapshotView(connected = false, error = "unrecognized FireRed-based ROM hack", unsupported = true, gamePicker = SamplePicker.picker(declined = true)),
            SampleCompanion.Slots, SampleCompanion.Settings(), askForSupport = {}, gamePick = SamplePicker.actions,
        )
    }

    @Test fun languageRow() = languageShot(picker = false)

    /** Its picker: each language in its own name. */
    @Test fun languagePicker() = languageShot(picker = true)

    private fun languageShot(picker: Boolean) = paparazzi.snapshot(lang.code) {
        AppTheme {
            val m = rememberGbaTextMetrics()
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                OptionListWindow(m, androidx.compose.ui.Modifier.fillMaxSize()) {
                    GroupedRows(
                        listOf(
                            groupTitle("COMPANION"),
                            SettingRow("LANGUAGE", L10n.settingLabel(com.pokedaisy.app.companion.i18n.LANGUAGE_AUTO)) {},
                            SettingRow("TAB BAR", "5 TABS") {},
                        ),
                        m, cursor = -1, onClick = {},
                    )
                }
                if (picker) {
                    val opts = L10n.options
                    OptionSelector("LANGUAGE", opts.map { it.second }, opts.first().second, { it }, m, onPick = {}, onDismiss = {})
                }
            }
        }
    }

    @Test
    fun suggestions() = paparazzi.snapshot(lang.code) {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            SuggestionsScreen(party, foe = party[1], onBack = {}, onShowInfo = {}, active = listOf(party[0]))
        }
    }
}
