package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.StateSlots
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_FEMALE
import com.pokedaisy.app.companion.data.GENDER_SYMBOL_MALE
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.LocationView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.MoveView
import com.pokedaisy.app.companion.data.POCKET_ITEMS
import com.pokedaisy.app.companion.data.POCKET_KEY_ITEMS
import com.pokedaisy.app.companion.data.POCKET_POKE_BALLS
import com.pokedaisy.app.companion.data.SlotVerdict
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.TypeMatchup
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.DecompIconSource
import com.pokedaisy.app.companion.data.EvolutionSource
import com.pokedaisy.app.companion.data.DexDetails
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL
import com.pokedaisy.app.companion.data.GUIDE_TABLES_FIRERED_REV1
import com.pokedaisy.app.companion.data.GuideRomSource
import com.pokedaisy.app.companion.data.SaveProgress
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.data.withMovesVs
import com.pokedaisy.app.companion.data.NATIVE_EMERALD
import com.pokedaisy.app.companion.data.NATIVE_FIRERED_REV1
import com.pokedaisy.app.companion.data.POKEDEX_EMERALD
import com.pokedaisy.app.companion.data.POKEDEX_UNBOUND
import com.pokedaisy.app.companion.data.UnboundIconSource
import com.pokedaisy.app.companion.data.POKEDEX_FIRERED_REV1
import com.pokedaisy.app.companion.data.PokedexSource
import com.pokedaisy.app.companion.data.NATIVE_UNBOUND_WITH_DEX
import com.pokedaisy.app.companion.data.guideId
import com.pokedaisy.app.companion.data.gameGuide
import com.pokedaisy.app.companion.data.NATIVE_SAPPHIRE
import com.pokedaisy.app.companion.data.readPokedexState
import com.pokedaisy.app.companion.data.NATIVE_TMT2
import com.pokedaisy.app.companion.data.NATIVE_GLAZED
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_ROGUE
import com.pokedaisy.app.companion.data.NATIVE_IMPERIUM
import com.pokedaisy.app.companion.data.NATIVE_QUETZAL
import com.pokedaisy.app.companion.data.NATIVE_QUETZAL_ES
import com.pokedaisy.app.companion.data.NATIVE_QUETZAL_MOEMON_V084
import com.pokedaisy.app.companion.data.NATIVE_QUETZAL_V084_ES
import com.pokedaisy.app.companion.data.NATIVE_QUETZAL_V084
import com.pokedaisy.app.companion.data.NATIVE_ORANGE_ISLANDS
import com.pokedaisy.app.companion.data.NATIVE_RECHARGED_YELLOW_V1_8_0
import com.pokedaisy.app.companion.data.NATIVE_RECHARGED_YELLOW_V1_9_7
import com.pokedaisy.app.companion.data.NATIVE_UNBOUND_FR
import com.pokedaisy.app.companion.data.NATIVE_ROWE
import com.pokedaisy.app.companion.data.NATIVE_MARIOMON_V1_3
import com.pokedaisy.app.companion.data.NATIVE_MARIOMON_V1_5_1
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_SEAGLASS
import com.pokedaisy.app.companion.data.NATIVE_LAZARUS
import com.pokedaisy.app.companion.data.NATIVE_SOULGOLD
import com.pokedaisy.app.companion.data.NATIVE_SOULGOLD_V1_2
import com.pokedaisy.app.companion.data.NATIVE_SOULGOLD_V1_2B
import com.pokedaisy.app.companion.data.NATIVE_SOULGOLD_V1_2_1
import com.pokedaisy.app.companion.data.NATIVE_HEART_AND_SOUL
import com.pokedaisy.app.companion.data.NATIVE_CELIA
import com.pokedaisy.app.companion.data.NATIVE_GAIA_V3_2
import com.pokedaisy.app.companion.data.NATIVE_AMETHYST
import com.pokedaisy.app.companion.data.NATIVE_AMETHYST_V1_4_1
import com.pokedaisy.app.companion.data.amethystV141
import com.pokedaisy.app.companion.data.romGameCode
import com.pokedaisy.app.companion.data.romLanguage
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_DE
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_ES
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_FR
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_IT
import com.pokedaisy.app.companion.data.NATIVE_EMERALD_JA
import com.pokedaisy.app.companion.data.NATIVE_RADICAL_RED_V4_1
import com.pokedaisy.app.companion.data.NATIVE_ODYSSEY
import com.pokedaisy.app.companion.data.PokedexState
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.companion.data.CardStyle
import com.pokedaisy.app.companion.data.TrainerCardArt
import com.pokedaisy.app.companion.data.readTrainerCard
import com.pokedaisy.app.companion.data.RomFileReader
import com.pokedaisy.app.companion.data.FixtureMemoryReader
import com.pokedaisy.app.companion.data.NativeConfig
import com.pokedaisy.app.companion.data.NATIVE_LEAFGREEN_REV1
import com.pokedaisy.app.companion.data.NATIVE_RUBY
import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import com.pokedaisy.app.companion.data.RomItemText
import com.pokedaisy.app.companion.data.buildSnapshotView
import com.pokedaisy.app.companion.data.readNativeTelemetry
import com.pokedaisy.app.companion.data.resetNativeBagCache
import com.pokedaisy.app.companion.ui.theme.AppTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi screenshots of the bottom-screen companion, one per tab, plus the
 * shared OPTION-screen pieces. Not a regression gate (the golden PNGs aren't
 * committed) - it's how to *look* at a UI change without a device:
 *
 *     ./gradlew :app:recordPaparazziDebug --tests '*ScreenshotTest'
 *
 * then open app/src/test/snapshots/images/. Sized like the AYN Thor's bottom
 * screen (1240x1080, landscape). See CLAUDE.md's "UI work" section.
 */
open class CompanionScreenshotTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1240, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    /** A shot in the companion's size class ([CompanionScreenSize]): panes drawn on their own get a short screen's
     * text and layouts too (RgDsCompanionScreenshotTest). */
    protected fun snapshot(content: @androidx.compose.runtime.Composable () -> Unit) =
        paparazzi.snapshot { CompanionScreenSize(content) }

    @Before
    fun game() {
        activeGame = GameKind.FIRERED
        // A retail() shot of another language leaves its ROM's globals behind (Japanese type names on SUGGESTIONS).
        romLanguage = 'E'
        romGameCode = ""
        OptionColors.inGame = true
        RomItemText.use(null) // a retail() shot's game must not leave its descriptions to the next
        // The party menu art, backdrops and region maps come from the ROM too (RomArt).
        val art = java.io.File("build/rom-art-paparazzi").also { RomArt.dirOverride = it }
        for (path in listOf(RomFileReader.FIRERED_REV1_PATH, RomFileReader.EMERALD_PATH)) {
            java.io.File(path).takeIf { it.isFile }?.let { RomArt.extractTo(it.readBytes(), art) }
        }
        // The CARD tab draws from these (normally loaded in the background).
        TrainerCardArt.load(art, CardStyle.KANTO)
        TrainerCardArt.load(art, CardStyle.HOENN)
        // The DEX tab's sprites and entries (and every mon icon) come from the
        // ROM: read the retail one when it's on this machine, else they stay blank.
        RomFileReader.load(RomFileReader.FIRERED_REV1_PATH)?.let { rom ->
            PokedexSource.reader = rom
            DecompIconSource.reader = rom
            DecompIconSource.tables = NATIVE_FIRERED_REV1.iconTables
            // Paparazzi draws one frame: load what the shots show up front
            // (the screens start from these caches instead of loading async).
            val t = POKEDEX_FIRERED_REV1
            for (n in 1..12) {
                val species = PokedexSource.speciesFor(t, n)
                PokedexSource.entry(t, n); PokedexSource.frontSprite(t, species); PokedexSource.footprint(t, species)
                DecompIconSource.get(species)
            }
            SampleCompanion.snapshot.party.forEach { DecompIconSource.get(it.species) }
            EvolutionSource.table(t)
            val g = GUIDE_TABLES_FIRERED_REV1
            GuideRomSource.encounters(g, 3, 43)
            PokedexSource.nationalOf(t, 1)
            (350..420).forEach { GuideRomSource.party(g, it) }
            GuideRomSource.party(g, 438)
        }
    }

    private fun tab(name: String) = snapshot {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = name)
    }

    @Test fun party() = tab("PARTY")

    /** PARTY before the player is in the game (the title screen: GameStart): the game's empty slots, PRESS START. */
    @Test fun waiting() {
        GameStart.waiting = true
        try {
            snapshot { CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings()) }
        } finally {
            GameStart.waiting = false
        }
    }

    /** The companion over the game on the game's screen, filling it (CLOSE COMPANION with SWAP SCREENS: SidePanelHost's
     *  fullScreen) - the window's X on its corner. */
    @Test fun fullScreenWindow() = snapshot {
        SidePanelHost(window = true, onClose = {}, fullScreen = true) {
            CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "SETTINGS")
        }
    }

    /** MORE, left of the gear: the tabs off the bar (CARD, STATES; CHEEVOS with RetroAchievements) as app icons. */
    @Test fun more() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "MORE",
            achievements = SampleCompanion.Achievements(SampleCompanion.achievementsState),
        )
    }

    /** The held-item marker by the icon (from the ROM's own sprite): LEFTOVERS on CHARMANDER, Mail's on PIDGEY. */
    @Test fun partyHeldItems() = snapshot {
        val party = SampleCompanion.snapshot.party.mapIndexed { i, m -> m.copy(heldItemId = if (i == 0) 200 else 121) }
        CompanionScreen(SampleCompanion.snapshot.copy(party = party), SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "PARTY")
    }

    /** The NUZLOCKE tab mid-run: HERE free, the team, encounters both ways and one Pokémon in the graveyard. */
    @Test fun nuzlocke() = nuzlockeShot("NUZLOCKE")

    /** NUZLOCKE's gravestone on a fainted-for-good Pokémon's party slot (healed since), beside a held item. */
    @Test fun partyNuzlocke() = nuzlockeShot("PARTY")

    /** NUZLOCKE on: its gravestone tile joins MORE. */
    @Test fun moreNuzlocke() = nuzlockeShot("MORE")

    /** NUZLOCKE on a full tab bar: the chip's label narrows to fit. */
    @Test fun nuzlockeOnBar() = nuzlockeShot("NUZLOCKE", listOf("PARTY", "DEX", "MAP", "ITEMS", "NUZLOCKE"))

    private fun nuzlockeShot(tab: String, barTabs: List<String> = com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS) {
        val party = SampleCompanion.snapshot.party.mapIndexed { i, m -> m.copy(personality = i + 1L, heldItemId = if (i == 1) 200 else 0) }
        com.pokedaisy.app.companion.Nuzlocke.preview(
            com.pokedaisy.app.companion.data.NuzlockeRun(
                encounters = listOf(
                    com.pokedaisy.app.companion.data.NuzlockeEncounter(89, "ROUTE 1", 16, com.pokedaisy.app.companion.data.NuzlockeOutcome.CAUGHT),
                    com.pokedaisy.app.companion.data.NuzlockeEncounter(90, "ROUTE 2", 19, com.pokedaisy.app.companion.data.NuzlockeOutcome.MISSED),
                    com.pokedaisy.app.companion.data.NuzlockeEncounter(117, "VIRIDIAN FOREST", 10, com.pokedaisy.app.companion.data.NuzlockeOutcome.CAUGHT),
                ),
                mons = party.mapIndexed { i, m ->
                    com.pokedaisy.app.companion.data.NuzlockeMon(
                        m.personality, m.species, m.shownName, m.level, areaName = if (i == 0) "" else "ROUTE 1",
                        dead = i == 1, diedIn = if (i == 1) "VIRIDIAN FOREST" else "", diedLevel = if (i == 1) m.level else 0,
                    )
                },
            ),
        )
        try {
            snapshot {
                CompanionScreen(SampleCompanion.snapshot.copy(party = party, regionMapSectionId = 88), SampleCompanion.Slots, SampleCompanion.Settings(barTabs), initialTab = tab)
            }
        } finally {
            com.pokedaisy.app.companion.Nuzlocke.load(false, null, {}, {})
        }
    }

    /** SETTINGS > STATUS BAR > COMPANION: the game's status bar over the tabs. */
    @Test fun partyStatusBar() {
        CompanionStatusBar.shown = true
        try {
            snapshot {
                CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "PARTY", statusBar = {
                    GameStatusBar("Pokémon FireRed", "PALLET TOWN", 3000, "12:34")
                })
            }
        } finally { CompanionStatusBar.shown = false }
    }

    @Test fun yellowSettingsStatusBar() {
        CompanionStatusBar.shown = true
        try { yellow("yellow", "SETTINGS", statusBar = true) } finally { CompanionStatusBar.shown = false }
    }

    /** The TRAINER CARD, front and back, from the retail ROM and the FireRed fixture save. */
    @Test fun card() = cardShot(back = false)

    @Test fun cardBack() = cardShot(back = true)

    private fun cardShot(back: Boolean) = snapshot {
        val card = readTrainerCard(FixtureMemoryReader.load("firered_vanilla"), NATIVE_FIRERED_REV1)
        CompanionScreen(
            SampleCompanion.snapshot.copy(trainerCard = card), SampleCompanion.Slots, SampleCompanion.Settings(),
            initialTab = "CARD", initialCardBack = back,
        )
    }

    /** RetroAchievements: the set (on the tab bar), signed out, a ROM with no set, and an unlock popup. */
    @Test fun achievements() = achievementsShot(SampleCompanion.achievementsState)

    /** A cheat on: the set's tally stays, with the PAUSED badge by the title. */
    @Test fun achievementsCheatsPaused() = achievementsShot(SampleCompanion.achievementsState.copy(cheatsPaused = true))

    @Test fun achievementsSignedOut() = achievementsShot(com.pokedaisy.app.companion.AchievementsState())

    @Test fun achievementsNoSet() = achievementsShot(
        com.pokedaisy.app.companion.AchievementsState(
            user = "Lidor",
            game = com.pokedaisy.app.companion.AchievementGame(0, null, "5d14746c8e95c0979a374e80ec123096", 0, 0, 0, 0),
        ),
    )

    private fun achievementsShot(state: com.pokedaisy.app.companion.AchievementsState) = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot, SampleCompanion.Slots,
            SampleCompanion.Settings(listOf("PARTY", "DEX", "MAP", "ITEMS", "ACHIEVEMENTS")),
            initialTab = "ACHIEVEMENTS", achievements = SampleCompanion.Achievements(state),
        )
    }

    @Test
    fun achievementPopup() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(),
            achievements = SampleCompanion.Achievements(SampleCompanion.achievementsState),
            initialPopup = com.pokedaisy.app.companion.AchievementPopup(
                com.pokedaisy.app.companion.AchievementPopup.Kind.UNLOCKED, "Boulder Badge",
                "Defeat Brock in Pewter City", points = 5,
            ),
        )
    }

    /** The list's filter: UNLOCKED ONLY, its pick-list, and an unlock popup's achievement marked after a tap. */
    @Test fun achievementsUnlockedOnly() = achievementListShot { AchievementsScreen(it, pad, initialFilter = AchievementFilter.UNLOCKED) }

    @Test fun achievementsFilterPicker() = achievementListShot { AchievementsScreen(it, pad, initialPicking = true) }

    @Test fun achievementsFromPopup() = achievementListShot { AchievementsScreen(it, pad, focus = AchievementFocus(2, unlocked = false)) }

    private val pad = androidx.compose.ui.Modifier.padding(12.dp)

    private fun achievementListShot(content: @androidx.compose.runtime.Composable (com.pokedaisy.app.companion.CompanionAchievements) -> Unit) =
        snapshot {
            AppTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    GameBackdrop(GameKind.FIRERED)
                    content(SampleCompanion.Achievements(SampleCompanion.achievementsState))
                }
            }
        }

    /** SETTINGS > FPS / CPU: the readout flush in the game screen's top-left corner, and the panel a tap opens. */
    @Test fun performanceOverlay() = performanceShot(open = false)

    @Test fun performanceOverlayOpen() = performanceShot(open = true)

    private fun performanceShot(open: Boolean) = snapshot {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF3A6E3C)),
        ) {
            PerformanceOverlay(
                PerfReading(
                    fps = 60.0f, shownFps = 59.8f, latePerSecond = 0.2f, frameMs = 2.8f, frameMaxMs = 5.1f, emulationLoad = 17,
                    cpu = 14, cores = 8, refreshHz = 60f, refreshesPerFrame = 1, audioDropouts = 0,
                    batteryTempC = 36.5f, thermal = 1, memoryMb = 212,
                    // In a battle: 4 snapshots a second, ~15 battle-menu checks.
                    snapshotsPerSecond = 4.0f, battleChecksPerSecond = 15.0f,
                ),
                initiallyOpen = open,
            )
        }
    }

    /** The CHEEVOS tab's leaderboards: the list, and one board's page (the player 17th, below the top 5 shown). */
    @Test fun leaderboards() = leaderboardShot(board = null)

    @Test fun leaderboardPage() = leaderboardShot(board = 11)

    private fun leaderboardShot(board: Int?) = snapshot {
        AppTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                GameBackdrop(GameKind.FIRERED)
                AchievementsScreen(
                    SampleCompanion.Achievements(SampleCompanion.achievementsState, SampleCompanion.leaderboardPage),
                    androidx.compose.ui.Modifier.padding(12.dp),
                    initialLeaderboards = true, initialBoard = board,
                )
            }
        }
    }

    /** A running leaderboard attempt's timer and two challenge badges, over PARTY. */
    @Test
    fun achievementIndicators() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(),
            achievements = SampleCompanion.Achievements(
                SampleCompanion.achievementsState.copy(
                    trackers = listOf(com.pokedaisy.app.companion.LeaderboardTracker(1, "1:23.45")),
                    challenges = listOf(
                        com.pokedaisy.app.companion.ChallengeIndicator(2, "No Healing", null),
                        com.pokedaisy.app.companion.ChallengeIndicator(3, "Solo Run", null),
                    ),
                ),
            ),
        )
    }

    @Test
    fun partyDetail() = snapshot {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialMonIndex = 0)
    }
    /** The summary's STATS page: IVs / EVs / nature / Hidden Power instead of the moves. */
    @Test
    fun partyDetailStats() = snapshot {
        AppTheme { MonDetailScreen(SampleCompanion.snapshot.party, 0, {}, {}, initialShowStats = true) }
    }

    /** TAKE (FieldItems.kt): CHARMANDER holds a LEFTOVERS, so the summary's bottom row has TAKE with its icon... */
    @Test fun partyDetailTake() = snapshot { AppTheme { MonDetailScreen(takeParty, 0, {}, {}, itemUse = takeUse) } }

    /** ...and pops up what it did, like the bag's USE / GIVE. */
    @Test fun partyDetailTaken() = snapshot {
        AppTheme { MonDetailScreen(takeParty, 0, {}, {}, itemUse = takeUse, initialNotice = ItemNotice(200, "Received LEFTOVERS from CHARMANDER.")) }
    }

    /** ...which asks first. */
    @Test fun partyDetailTakeAsk() = snapshot { AppTheme { MonDetailScreen(takeParty, 0, {}, {}, itemUse = takeUse, initialTakeAsk = true) } }

    private val takeParty get() = SampleCompanion.snapshot.party.mapIndexed { i, m -> m.copy(personality = i + 1L, iconAsset = spriteAsset("pokemon", m.species)) }

    private val takeUse = object : com.pokedaisy.app.companion.ItemUse {
        override fun canUse(itemId: Int) = false
        override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {}
        override fun canTake() = true
        override fun heldItems(done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) = done(
            com.pokedaisy.app.companion.data.PartyTargets(
                com.pokedaisy.app.companion.data.ItemUseResult.USED,
                listOf(
                    com.pokedaisy.app.companion.data.PartyTarget(0, 1, 200, SlotVerdict.OK),
                    com.pokedaisy.app.companion.data.PartyTarget(1, 2, 0, SlotVerdict.NO_EFFECT),
                ),
            ),
        )
    }

    /** Battle INFO's STATS page (FOE IVS on): your battler beside the foe. */
    @Test
    fun battleStats() = snapshot {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            BattleStatsScreen(listOf(party[0]), listOf(party[1]), "FOE", onBack = {})
        }
    }

    /** INFO with FOE IVS on in a trainer battle: STATS beside SUGGESTIONS, the FOE TEAM strip still fits. */
    @Test
    fun battleInfoStatsButton() = snapshot {
        AppTheme {
            val you = SampleCompanion.snapshot.party[0]
            BattleInfoScreen(
                listOf(you.withMovesVs(foes[1])), listOf(foes[1]), isDouble = false, showHints = true,
                onBack = {}, onShowSuggestions = {}, onShowStats = {},
                foeTeam = foes, seenFoes = setOf(0, 1), shownFoe = 1,
            )
        }
    }

    @Test
    fun battleInfo() = snapshot {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            BattleInfoScreen(listOf(party[0].withMovesVs(party[1])), listOf(party[1]), isDouble = false, showHints = true, onBack = {}, onShowSuggestions = {})
        }
    }

    /** A trainer battle: move verdicts against an Onix, the FOE TEAM strip, an out-of-PP move. */
    @Test
    fun battleInfoTrainer() = trainerBattle(foeDetails = false)

    /** FOE DETAILS (GitHub #34): the foe's side widened, the player's narrowed to a column. */
    @Test
    fun battleInfoFoeDetails() = trainerBattle(foeDetails = true)

    /** FOE DETAILS in a double battle: two sprites / HP bars on the player's strip, two foe cards. */
    @Test
    fun battleInfoFoeDetailsDouble() = snapshot {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            // The sample party has two: each side gets both.
            val foes = listOf(party[1], party[0]).map {
                it.copy(abilityName = "KEEN EYE", heldItem = "", battleStats = listOf(12, 11, 15, 10, 10), statStages = List(7) { 0 })
            }
            BattleInfoScreen(
                listOf(party[0], party[1]).map { it.withMovesVs(foes) }, foes, isDouble = true, showHints = true,
                onBack = {}, onShowSuggestions = {}, foeDetails = true, onToggleFoeDetails = {},
            )
        }
    }

    private fun trainerBattle(foeDetails: Boolean) = snapshot {
        AppTheme {
            fun foe(species: Int, level: Int, hp: Int, maxHp: Int): MonView {
                val mu = com.pokedaisy.app.companion.data.typeMatchups(
                    com.pokedaisy.app.companion.data.typeIdOf("Rock"),
                    com.pokedaisy.app.companion.data.typeIdOf("Ground"),
                )
                return MonView(
                    species, if (species == 95) "ONIX" else "GEODUDE", level, hp, maxHp, "", listOf("Rock", "Ground"),
                    spriteAsset("pokemon", species), GENDER_SYMBOL_MALE,
                    moves = listOf(
                        MoveView("Tackle", "Normal", 35, 35), MoveView("Bind", "Normal", 20, 15),
                        MoveView("Rock Tomb", "Rock", 9, 50), MoveView("Harden", "Normal", 30, 0),
                    ),
                    weaknesses = mu.weaknesses, resistances = mu.resistances, immunities = mu.immunities,
                    // Brock's Onix after a Harden: Defense +1.
                    abilityName = "STURDY", heldItem = "",
                    battleStats = listOf(18, 41, 21, 13, 16), statStages = listOf(0, 1, 0, 0, 0, 0, 0),
                )
            }
            val team = listOf(foe(74, 12, 0, 33), foe(95, 14, 21, 40), foe(74, 12, 33, 33))
            val me = SampleCompanion.snapshot.party[0].withMovesVs(team[1])
                .let { it.copy(moves = it.moves.mapIndexed { i, mv -> if (i == 1) mv.copy(pp = 0) else mv }) }
            BattleInfoScreen(
                listOf(me), listOf(team[1]), isDouble = false, showHints = true, onBack = {}, onShowSuggestions = {},
                foeTeam = team, seenFoes = setOf(0, 1), shownFoe = 1,
                foeDetails = foeDetails, onToggleFoeDetails = {},
            )
        }
    }

    @Test
    fun battleSuggestions() = snapshot {
        AppTheme {
            val party = SampleCompanion.snapshot.party
            SuggestionsScreen(party, foe = party[1], onBack = {}, onShowInfo = {}, active = listOf(party[0]))
        }
    }

    @Test
    fun unsupported() = snapshot {
        CompanionScreen(
            SnapshotView(connected = false, error = "game code AMTE isn't a supported Pokémon game", unsupported = true),
            SampleCompanion.Slots, SampleCompanion.Settings(), askForSupport = {},
        )
    }

    /** An unknown Emerald-based hack before the player has a Pokémon: WHICH GAME IS THIS?, the likely games first. */
    @Test
    fun gamePicker() = snapshot {
        CompanionScreen(
            SnapshotView(
                connected = false, error = "unrecognized Emerald-based ROM hack (BPEE, 32 MB, sha1 0123456789ab…)", unsupported = true,
                gamePicker = SamplePicker.picker(miss = com.pokedaisy.app.companion.data.BestEffort.Miss.NO_PARTY),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), askForSupport = {}, gamePick = SamplePicker.actions,
        )
    }

    /** ...a party that reads as none of them. */
    @Test
    fun gamePickerNoMatch() = snapshot {
        CompanionScreen(
            SnapshotView(
                connected = false, error = "unrecognized Emerald-based ROM hack", unsupported = true,
                gamePicker = SamplePicker.picker(miss = com.pokedaisy.app.companion.data.BestEffort.Miss.NO_MATCH, likely = false),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), askForSupport = {}, gamePick = SamplePicker.actions,
        )
    }

    /** NONE OF THESE: NOT SUPPORTED again, with PICK A GAME back to the list. */
    @Test
    fun gamePickerDeclined() = snapshot {
        CompanionScreen(
            SnapshotView(
                connected = false, error = "unrecognized Emerald-based ROM hack", unsupported = true,
                gamePicker = SamplePicker.picker(declined = true),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), askForSupport = {}, gamePick = SamplePicker.actions,
        )
    }

    /** The picker over a game it's read as (WRONG GAME? / SETTINGS > GAME): the one in use marked. */
    @Test
    fun gamePickerOverlay() = snapshot {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings())
                GamePickerOverlay(SamplePicker.picker(current = "SOULGOLD_V1_2"), SamplePicker.actions, askForSupport = {}, onDismiss = {})
            }
        }
    }

    /** SETTINGS of a ROM read through best effort: PLAYS AS at the top. */
    @Test
    fun settingsPlaysAs() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot.copy(gamePicker = SamplePicker.picker(current = "SOULGOLD_V1_2")),
            SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "SETTINGS", gamePick = SamplePicker.actions,
        )
    }

    /** A guess from the ROM's names (no party yet): WRONG GAME? / OK. */
    @Test
    fun bestEffortGuessNotice() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot.copy(
                bestEffort = com.pokedaisy.app.companion.data.BestEffortView(
                    "Pokémon SoulGold v1.2", full = false, off = emptySet(), fresh = true, id = "SOULGOLD_V1_2", guess = true,
                ),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), gamePick = SamplePicker.actions,
        )
    }

    /** The player's pick, which the party then didn't hold with: PICK AGAIN / KEEP. */
    @Test
    fun bestEffortDoubtNotice() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot.copy(
                bestEffort = com.pokedaisy.app.companion.data.BestEffortView(
                    "Pokémon Lazarus", full = false, off = emptySet(), fresh = false, id = "LAZARUS", picked = true,
                    doubt = true, suggest = "Pokémon SoulGold v1.2",
                ),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), gamePick = SamplePicker.actions,
        )
    }

    /** A match just found, with the ask to share it: every field it would send, listed. */
    @Test
    fun bestEffortShareNotice() = snapshot {
        val off = setOf(com.pokedaisy.app.companion.data.BestEffort.Part.DEX)
        CompanionScreen(
            SampleCompanion.snapshot.copy(
                bestEffort = com.pokedaisy.app.companion.data.BestEffortView(
                    "Pokémon Unbound", full = false, off = off, fresh = true,
                    report = com.pokedaisy.app.companion.data.BestEffortReport(
                        "0ce2a880aa097f1dce4e1db8ee513d0e82d15859", 33554432, "BPRE", 0, "UNBOUND", false, off,
                    ),
                ),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), shareBestEffort = {}, gamePick = SamplePicker.actions,
        )
    }

    /** A partial match just found: the notice over the tabs. */
    @Test
    fun bestEffortNotice() = snapshot {
        CompanionScreen(
            SampleCompanion.snapshot.copy(
                bestEffort = com.pokedaisy.app.companion.data.BestEffortView(
                    "Pokémon FireRed", full = false,
                    off = setOf(com.pokedaisy.app.companion.data.BestEffort.Part.DEX, com.pokedaisy.app.companion.data.BestEffort.Part.MON_ICONS), fresh = true,
                ),
            ),
            SampleCompanion.Slots, SampleCompanion.Settings(), gamePick = SamplePicker.actions,
        )
    }
    @Test fun dex() = tab("DEX")

    @Test
    fun dexEntry() = snapshot {
        AppTheme {
            val dex = SampleCompanion.snapshot.pokedex!!
            val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
            ui.open = 6
            PokedexEntryScreen(dex, ui, 6)
        }
    }

    /** Emerald's Hoenn dex (TREECKO = No.001), read from the retail Emerald ROM. */
    @Test
    fun dexEmerald() {
        val shot = emeraldDex() ?: return
        snapshot { CompanionScreen(shot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "DEX") }
    }

    @Test
    fun dexEntryEmerald() {
        val shot = emeraldDex() ?: return
        snapshot {
            AppTheme {
                val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                ui.open = 258 // MUDKIP
                PokedexEntryScreen(shot.pokedex!!, ui, 258)
            }
        }
    }

    /**
     * Emerald's GUIDE on NEXT BOSS, live: the retail ROM's trainer tables and the
     * emerald_vanilla save fixture (six badges -> TATE AND LIZA).
     */
    @Test
    fun guideEmerald() {
        emeraldDex() ?: return
        val live = com.pokedaisy.app.companion.data.buildSnapshotView(
            com.pokedaisy.app.companion.data.decodeNative(
                "emerald_vanilla", com.pokedaisy.app.companion.data.NATIVE_EMERALD_RETAIL,
            ),
        )
        val g = com.pokedaisy.app.companion.data.GUIDE_TABLES_EMERALD
        listOf(271, 272).forEach { GuideRomSource.party(g, it) }
        GuideRomSource.encounters(g, live.mapGroup, live.mapNum)
        snapshot {
            AppTheme {
                val source = rememberGuide(GameKind.EMERALD, live.pokedex, live.guideTables)!!
                GuideScreen(source, GuideUiState().apply { page = "NEXT BOSS" }, live)
            }
        }
    }

    /** Ruby's own GUIDE (not Emerald's) on HERE: the real save in SLATEPORT CITY, the ROM's wild table. */
    @Test fun rubyGuideHere() = retailGuide("ruby_rev1", NATIVE_RUBY, RUBY_ROM, "HERE")

    /** Sapphire's STUCK?: its own story (KYOGRE in the CAVE OF ORIGIN), that answer open. */
    @Test fun sapphireGuideStuck() =
        retailGuide("sapphire_rev1", NATIVE_SAPPHIRE, SAPPHIRE_ROM, "STUCK?", "STUCK?/LATE GAME/SOOTOPOLIS CITY's gym is closed/null" to 2)

    // Heart and Soul's GUIDE from the real save (NEW BARK TOWN, no badges): its
    // area data, FALKNER next, and the WHERE IS generated from the area data.
    @Test fun hnsGuideHere() = retailGuide("heart_and_soul", NATIVE_HEART_AND_SOUL, HNS_ROM, "HERE", kind = GameKind.HEART_AND_SOUL)
    @Test fun hnsGuideNextBoss() =
        retailGuide("heart_and_soul", NATIVE_HEART_AND_SOUL, HNS_ROM, "NEXT BOSS", "NEXT BOSS/NEXT: LEADER FALKNER/POKéMON 1/LV 8" to 2, GameKind.HEART_AND_SOUL)
    @Test fun hnsGuideWhereIs() =
        retailGuide("heart_and_soul", NATIVE_HEART_AND_SOUL, HNS_ROM, "WHERE IS", "WHERE IS/HMs/HM01/null" to 1, GameKind.HEART_AND_SOUL)

    // Unbound: each gym leader's four difficulty teams, and the ROM-read WHERE IS.
    @Test fun unboundGuideNextBoss() =
        retailGuide("unbound", NATIVE_UNBOUND_WITH_DEX, "Pokemon Unbound (v2.1.1.1).gba", "NEXT BOSS", kind = GameKind.UNBOUND)
    @Test fun unboundGuideWhereIs() =
        retailGuide("unbound", NATIVE_UNBOUND_WITH_DEX, "Pokemon Unbound (v2.1.1.1).gba", "WHERE IS", "WHERE IS/KEY ITEMS/Go-Goggles/null" to 2, GameKind.UNBOUND)

    // Radical Red: gyms 1-7 and the ROM-read WHERE IS (its HMs).
    @Test fun radicalRedGuideNextBoss() =
        retailGuide("radical_red", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", "NEXT BOSS", kind = GameKind.RADICAL_RED)
    @Test fun radicalRedGuideWhereIs() =
        retailGuide("radical_red", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", "WHERE IS", "WHERE IS/HMs/Hm01/null" to 2, GameKind.RADICAL_RED)

    // Odyssey / Gaia / Amethyst / Celia: NEXT BOSS (NORMAL / HARD, four difficulty tables, a gauntlet) and a ROM-read WHERE IS.
    @Test fun odysseyGuideNextBoss() =
        retailGuide("odyssey", NATIVE_ODYSSEY, "Pokemon Odyssey (v4.1.1).gba", "NEXT BOSS", kind = GameKind.ODYSSEY)
    @Test fun gaiaGuideNextBoss() =
        retailGuide("gaia", NATIVE_GAIA_V3_2, "Pokemon Gaia (v3.2).gba", "NEXT BOSS", kind = GameKind.GAIA)
    @Test fun amethystGuideNextBoss() =
        retailGuide("amethyst", NATIVE_AMETHYST, "Pokemon Amethyst (v1.3.0).gba", "NEXT BOSS", kind = GameKind.AMETHYST)
    @Test fun amethystV141GuideNextBoss() =
        retailGuide("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, "NEXT BOSS", kind = GameKind.AMETHYST)
    @Test fun amethystV141GuideHere() =
        retailGuide("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, "HERE", kind = GameKind.AMETHYST)
    @Test fun celiaGuideNextBoss() =
        retailGuide("celia", NATIVE_CELIA, "Pokemon Celia's Stupid Romhack (v1.1.4).gba", "NEXT BOSS", kind = GameKind.CELIA)
    @Test fun gaiaGuideWhereIs() =
        retailGuide("gaia", NATIVE_GAIA_V3_2, "Pokemon Gaia (v3.2).gba", "WHERE IS", kind = GameKind.GAIA)

    private fun retailGuide(
        fixture: String, cfg: NativeConfig, romFile: String, page: String, open: Pair<String, Int>? = null,
        kind: GameKind = GameKind.EMERALD,
    ) {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + romFile) ?: return
        activeGame = kind
        amethystV141 = cfg === NATIVE_AMETHYST_V1_4_1 // the Poller sets these with activeGame
        com.pokedaisy.app.companion.data.fireRedHack = com.pokedaisy.app.companion.data.fireRedHackFor(cfg)
        com.pokedaisy.app.companion.data.emeraldHack = com.pokedaisy.app.companion.data.emeraldHackFor(cfg)
        com.pokedaisy.app.companion.data.mariomonV13 = cfg === NATIVE_MARIOMON_V1_3
        com.pokedaisy.app.companion.data.quetzalV084 = cfg.quetzalV084
        com.pokedaisy.app.companion.data.eliteRedux265_3 = cfg === com.pokedaisy.app.companion.data.NATIVE_ELITE_REDUX_V2_65_3B
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
        resetNativeBagCache()
        val v = buildSnapshotView(readNativeTelemetry(rom.withRam(FixtureMemoryReader.load(fixture)), cfg)).copy(game = kind)
        PokedexSource.reader = rom
        DecompIconSource.reader = rom
        DecompIconSource.tables = cfg.iconTables
        val live = v.guideTables!!
        GuideRomSource.encounters(live, v.mapGroup, v.mapNum)?.let { e ->
            (e.grass + e.water + e.oldRod + e.goodRod + e.superRod).forEach { DecompIconSource.get(it.species) }
        }
        v.pokedex?.let { PokedexSource.nationalOf(it.tables, 1) }
        v.progress?.let { p -> gameGuide(guideId(kind, live))?.bosses?.forEach { b -> b.trainers(p).forEach { GuideRomSource.party(live, it) } } }
        snapshot {
            AppTheme {
                val ui = androidx.compose.runtime.remember {
                    GuideUiState().apply { this.page = page; open?.let { this.open = it.first; level = it.second } }
                }
                GuideScreen(rememberGuide(v.game, v.pokedex, v.guideTables)!!, ui, v)
            }
        }
    }

    /** Switches the ROM readers to retail Emerald; null (skip) if it isn't on this machine. */
    private fun emeraldDex(): SnapshotView? {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba")
            ?: return null
        activeGame = GameKind.EMERALD
        PokedexSource.reader = rom
        DecompIconSource.reader = rom
        DecompIconSource.tables = NATIVE_EMERALD.iconTables
        val t = POKEDEX_EMERALD
        val hoenn = PokedexSource.regionalOrder(t)!!
        hoenn.take(12).forEach { n ->
            val species = PokedexSource.speciesFor(t, n)
            PokedexSource.entry(t, n); PokedexSource.frontSprite(t, species); PokedexSource.footprint(t, species)
            DecompIconSource.get(species)
        }
        return SampleCompanion.snapshot.copy(
            game = GameKind.EMERALD, party = emptyList(),
            location = LocationView("ROUTE 104", null, 0, 0, 0, 0),
            pokedex = PokedexState(t, seen = hoenn.take(9).toSet(), caught = setOf(252, 255, 258, 261), national = false),
        )
    }

    /** Unbound's National dex (Gen 1-8) and a Gen 8 entry, read from the Unbound ROM. */
    @Test
    fun dexUnbound() {
        val shot = unboundDex() ?: return
        snapshot { CompanionScreen(shot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "DEX") }
    }

    @Test
    fun dexEntryUnbound() {
        val shot = unboundDex() ?: return
        snapshot {
            AppTheme {
                val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                ui.open = 443 // GIBLE
                PokedexEntryScreen(shot.pokedex!!, ui, 443)
            }
        }
    }

    private fun unboundDex(): SnapshotView? {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + "Pokemon Unbound (v2.1.1.1).gba")
            ?: return null
        activeGame = GameKind.UNBOUND
        PokedexSource.reader = rom
        UnboundIconSource.reader = rom
        val t = POKEDEX_UNBOUND
        (listOf(443) + (1..12)).forEach { n ->
            val species = PokedexSource.speciesFor(t, n)
            PokedexSource.entry(t, n); PokedexSource.frontSprite(t, species)
            UnboundIconSource.get(species)
        }
        return SampleCompanion.snapshot.copy(
            game = GameKind.UNBOUND, party = emptyList(),
            location = LocationView("Frozen Heights", null, 0, 0, 0, 0),
            pokedex = PokedexState(t, seen = setOf(1, 4, 7, 246, 443, 451, 686), caught = setOf(4, 443), national = true),
        )
    }

    @Test fun guide() = tab("GUIDE")

    /** The first-open notice (AI-written, may be wrong). */
    @Test
    fun guideNotice() = snapshot {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings().apply { noticeAccepted = false }, initialTab = "GUIDE")
    }

    /** STUCK? with one entry open at its hint. */
    @Test
    fun guideRevealed() = guideShot("STUCK?", "STUCK?/EARLY GAME/An old man blocks the road north of VIRIDIAN/null" to 1)

    /** WHERE IS with the answer open, and Poké Balls on what the save has. */
    @Test
    fun guideWhereIs() = guideShot("WHERE IS", "WHERE IS/KEY ITEMS/BICYCLE/null" to 2)

    /** HERE on ROUTE 24, from the retail ROM: seen species named, one unseen revealed. */
    @Test
    fun guideHere() = guideShot("HERE", "HERE/SUPER ROD/???/LV 15-25  40%" to 1)

    /** NEXT BOSS with BROCK beaten: MISTY next, her first Pokémon's hint open. */
    @Test
    fun guideNextBoss() = guideShot("NEXT BOSS", "NEXT BOSS/NEXT: LEADER MISTY/POKéMON 1/LV 18" to 1)

    /** EVOLUTIONS from the retail ROM: the party, then the special ones. */
    @Test
    fun guideEvolutions() = guideShot("EVOLUTIONS", "EVOLUTIONS/YOUR PARTY/PIDGEY/null" to 2)

    private fun guideShot(page: String, open: Pair<String, Int>) = snapshot {
        AppTheme {
            val ui = androidx.compose.runtime.remember {
                GuideUiState().apply { this.page = page; this.open = open.first; level = open.second }
            }
            val snap = SampleCompanion.snapshot
            GuideScreen(rememberGuide(snap.game, snap.pokedex, snap.guideTables)!!, ui, snap)
        }
    }

    /** The trainer's party for the FOE TEAM shots: GEODUDE fainted, ONIX out, two more unseen. */
    private val foes = listOf(
        MonView(74, "GEODUDE", 12, 0, 30, "", listOf("Rock", "Ground"), spriteAsset("pokemon", 74)),
        MonView(95, "ONIX", 14, 0, 35, "", listOf("Rock", "Ground"), spriteAsset("pokemon", 95)),
        MonView(
            111, "RHYHORN", 15, 40, 40, "", listOf("Ground", "Rock"), spriteAsset("pokemon", 111),
            weaknesses = listOf(TypeMatchup("Water", "4x", 400), TypeMatchup("Grass", "4x", 400)),
            immunities = listOf(TypeMatchup("Electric", "0x", 0)),
        ),
        MonView(142, "AERODACTYL", 16, 40, 40, "", listOf("Rock", "Flying"), spriteAsset("pokemon", 142)),
    )

    /** ONIX fainted; the trainer is about to use RHYHORN ("Will you change POKéMON?"): INFO shows it, your moves rated against it. */
    @Test
    fun battleFoeNext() = snapshot {
        AppTheme {
            val you = SampleCompanion.snapshot.party[0]
            BattleInfoScreen(
                listOf(you.withMovesVs(foes[2])), listOf(foes[2]), isDouble = false, showHints = true, onBack = {}, onShowSuggestions = {},
                foeTeam = foes, seenFoes = setOf(0, 1, 2), shownFoe = 2, foeHeading = "FOE · COMING IN NEXT",
            )
        }
    }

    /** A tapped slot the trainer hasn't sent out yet. */
    @Test
    fun battleFoeTapped() = snapshot {
        AppTheme {
            val you = SampleCompanion.snapshot.party[0]
            BattleInfoScreen(
                listOf(you.withMovesVs(foes[3])), listOf(foes[3]), isDouble = false, showHints = true, onBack = {}, onShowSuggestions = {},
                foeTeam = foes, seenFoes = setOf(0, 1), shownFoe = 3, foeHeading = "FOE · NOT SENT OUT YET",
            )
        }
    }

    // LeafGreen / Ruby: the real decode of each game's headless capture (see
    // LeafGreenDecodeTest / RubySapphireDecodeTest), through the whole UI.
    @Test fun leafGreenParty() = retail("leafgreen_rev1", NATIVE_LEAFGREEN_REV1, LG_ROM, GameKind.FIRERED, "PARTY")
    @Test fun leafGreenItems() = retail("leafgreen_rev1", NATIVE_LEAFGREEN_REV1, LG_ROM, GameKind.FIRERED, "ITEMS")
    // An item's description, read from each game's own ROM (RomItemText).
    @Test fun leafGreenItemDescription() = retail("leafgreen_rev1", NATIVE_LEAFGREEN_REV1, LG_ROM, GameKind.FIRERED, "ITEMS", art = true, describe = true)
    @Test fun rubyItemDescription() = retail("ruby_rev1", NATIVE_RUBY, RUBY_ROM, GameKind.EMERALD, "ITEMS", art = true, describe = true)
    @Test fun emeraldDeItemDescription() = retail("emerald_de", NATIVE_EMERALD_DE, EM_DE_ROM, GameKind.EMERALD, "ITEMS", art = true, describe = true)
    @Test fun emeraldJaItemDescription() = retail("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, GameKind.EMERALD, "ITEMS", art = true, describe = true)
    @Test fun soulGoldV12ItemDescription() = retail("soulgold_v12", NATIVE_SOULGOLD_V1_2, SG12_ROM, GameKind.SOULGOLD, "ITEMS", art = true, describe = true)
    @Test fun glazedItemDescription() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "ITEMS", art = true, describe = true)
    @Test fun quetzalItemDescription() = retail("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "ITEMS", art = true, describe = true)
    @Test fun leafGreenDex() = retail("leafgreen_rev1", NATIVE_LEAFGREEN_REV1, LG_ROM, GameKind.FIRERED, "DEX")
    @Test fun rubyParty() = retail("ruby_rev1", NATIVE_RUBY, RUBY_ROM, GameKind.EMERALD, "PARTY")
    @Test fun rubyItems() = retail("ruby_rev1", NATIVE_RUBY, RUBY_ROM, GameKind.EMERALD, "ITEMS")
    @Test fun rubyDex() = retail("ruby_rev1", NATIVE_RUBY, RUBY_ROM, GameKind.EMERALD, "DEX")
    @Test fun rubyMap() = retail("ruby_rev1", NATIVE_RUBY, RUBY_ROM, GameKind.EMERALD, "MAP")
    @Test fun rubyDexEntry() = retail("ruby_rev1", NATIVE_RUBY, RUBY_ROM, GameKind.EMERALD, "DEX", entry = 384)

    // Radical Red's own species / item tables and icons on a later save (radical_red_1636),
    // and Lazarus's own region map, rebuilt from its ROM.
    @Test fun radicalRedParty() = retail("radical_red_1636", NATIVE_RADICAL_RED_V4_1, RR_ROM, GameKind.RADICAL_RED, "PARTY", art = true)
    // The hacks' own party slots / backdrops, rebuilt from each ROM (RomArt: HNS_* / CFRU_*).
    @Test fun radicalRedV41Party() =
        retail("radical_red", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", GameKind.RADICAL_RED, "PARTY", art = true)
    @Test fun heartAndSoulParty() = retail("heart_and_soul", NATIVE_HEART_AND_SOUL, HNS_ROM, GameKind.HEART_AND_SOUL, "PARTY", art = true)
    @Test fun unboundParty() = retail("unbound", NATIVE_UNBOUND_WITH_DEX, "Pokemon Unbound (v2.1.1.1).gba", GameKind.UNBOUND, "PARTY", art = true)
    @Test fun odysseyParty() = retail("odyssey", NATIVE_ODYSSEY, "Pokemon Odyssey (v4.1.1).gba", GameKind.ODYSSEY, "PARTY", art = true)
    @Test fun radicalRedItems() = retail("radical_red_1636", NATIVE_RADICAL_RED_V4_1, RR_ROM, GameKind.RADICAL_RED, "ITEMS")
    @Test fun lazarusMap() = retail("lazarus_srm", NATIVE_LAZARUS, "Pokemon Lazarus (v2.0).gba", GameKind.LAZARUS, "MAP", art = true)
    @Test fun lazarusBattle() = retail("lazarus_battle", NATIVE_LAZARUS, "Pokemon Lazarus (v2.0).gba", GameKind.LAZARUS, "BATTLE")

    // SoulGold: its own party / bag look (the bag's night sky rebuilt from the ROM), battle and map.
    @Test fun soulGoldParty() = retail("soulgold_battle", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, "PARTY", art = true)
    @Test fun soulGoldSixParty() = retail("soulgold_party", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, "PARTY", art = true)
    @Test fun soulGoldItems() = retail("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, "ITEMS", art = true)
    @Test fun soulGoldBattle() = retail("soulgold_battle", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, "BATTLE", art = true)
    @Test fun soulGoldMap() = retail("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, "MAP", art = true)
    // Emerald Seaglass: its own Hoenn art (from its ROM) and the battle pane, now that its battle memory is mapped.
    @Test fun seaglassMap() = retail("emerald_seaglass", NATIVE_EMERALD_SEAGLASS, SEAGLASS_ROM, GameKind.EMERALD_SEAGLASS, "MAP", art = true)
    @Test fun seaglassBattle() =
        retail("emerald_seaglass_battle", NATIVE_EMERALD_SEAGLASS, SEAGLASS_ROM, GameKind.EMERALD_SEAGLASS, "BATTLE", art = true)
    // Pokémon Glazed: retail Emerald's RAM, its own names / types / map sections, Emerald's bag and party slots on olive.
    @Test fun glazedParty() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "PARTY", art = true)
    @Test fun glazedItems() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "ITEMS", art = true)
    @Test fun glazedMap() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "MAP", art = true)
    @Test fun imperiumParty() = retail("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "PARTY", art = true)
    @Test fun imperiumItems() = retail("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "ITEMS", art = true)
    @Test fun imperiumMap() = retail("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "MAP", art = true)
    @Test fun imperiumBattle() = retail("imperium_battle", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "BATTLE", art = true)
    @Test fun quetzalParty() = retail("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "PARTY", art = true)
    @Test fun quetzalItems() = retail("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "ITEMS", art = true)
    @Test fun quetzalMap() = retail("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "MAP", art = true)
    @Test fun quetzalBattle() = retail("quetzal_battle", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "BATTLE", art = true)
    @Test fun glazedDex() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "DEX", art = true)
    @Test fun glazedDexEntry() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "DEX", entry = 322, art = true)
    @Test fun imperiumDex() = retail("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "DEX", art = true)
    @Test fun imperiumDexEntry() = retail("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "DEX", entry = 4, art = true)
    @Test fun quetzalDex() = retail("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "DEX", art = true)
    @Test fun quetzalDexEntry() = retail("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "DEX", entry = 4, art = true)
    @Test fun glazedCard() = retail("glazed", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "CARD", art = true)
    @Test fun imperiumCard() = retail("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, GameKind.IMPERIUM, "CARD", art = true)
    @Test fun tmt2Battle() = retail("tmt2_battle", NATIVE_TMT2, "Pokemon Too Many Types 2 (v1.5.2).gba", GameKind.TMT2, "BATTLE", art = true)
    @Test fun amethystBattle() = retail("amethyst_battle", NATIVE_AMETHYST, "Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST, "BATTLE", art = true)
    @Test fun heartAndSoulMap() = retail("heart_and_soul", NATIVE_HEART_AND_SOUL, "Pokemon Heart and Soul (v2.0.6).gba", GameKind.HEART_AND_SOUL, "MAP", art = true)
    @Test fun emeraldRogueDex() = hackDex("emerald_rogue", NATIVE_EMERALD_ROGUE, "Pokemon Emerald Rogue (v2.2.1-EX).gba", GameKind.EMERALD_ROGUE)
    @Test fun emeraldRogueDexEntry() = hackDex("emerald_rogue", NATIVE_EMERALD_ROGUE, "Pokemon Emerald Rogue (v2.2.1-EX).gba", GameKind.EMERALD_ROGUE, 744)
    @Test fun imperiumGuideBoss() = retailGuide("imperium", NATIVE_IMPERIUM, IMPERIUM_ROM, "NEXT BOSS", kind = GameKind.IMPERIUM)
    @Test fun quetzalGuideBoss() = retailGuide("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, "NEXT BOSS", kind = GameKind.QUETZAL)
    @Test fun quetzalGuideHere() = retailGuide("quetzal", NATIVE_QUETZAL, QUETZAL_ROM, "HERE", kind = GameKind.QUETZAL)
    @Test fun lazarusGuideBoss() = retailGuide("lazarus", NATIVE_LAZARUS, LAZARUS_ROM, "NEXT BOSS", kind = GameKind.LAZARUS)
    @Test fun seaglassGuideBoss() = retailGuide("emerald_seaglass", NATIVE_EMERALD_SEAGLASS, SEAGLASS_ROM, "NEXT BOSS", kind = GameKind.EMERALD_SEAGLASS)
    @Test fun tmt2GuideBoss() = retailGuide("tmt2", NATIVE_TMT2, TMT2_ROM, "NEXT BOSS", kind = GameKind.TMT2)
    @Test fun tmt2GuideHere() = retailGuide("tmt2", NATIVE_TMT2, TMT2_ROM, "HERE", kind = GameKind.TMT2)
    @Test fun soulGoldGuideBoss() = retailGuide("soulgold", NATIVE_SOULGOLD, SG_ROM, "NEXT BOSS", kind = GameKind.SOULGOLD)
    @Test fun soulGoldGuideHere() = retailGuide("soulgold", NATIVE_SOULGOLD, SG_ROM, "HERE", kind = GameKind.SOULGOLD)
    @Test fun glazedGuideBoss() = retailGuide("glazed", NATIVE_GLAZED, GLAZED_ROM, "NEXT BOSS", kind = GameKind.GLAZED)
    @Test fun roweParty() = retail("rowe", NATIVE_ROWE, ROWE_ROM, GameKind.ROWE, "PARTY", art = true)
    @Test fun roweItems() = retail("rowe", NATIVE_ROWE, ROWE_ROM, GameKind.ROWE, "ITEMS", art = true)
    @Test fun roweMap() = retail("rowe", NATIVE_ROWE, ROWE_ROM, GameKind.ROWE, "MAP", art = true)
    @Test fun roweBattle() = retail("rowe_battle", NATIVE_ROWE, ROWE_ROM, GameKind.ROWE, "BATTLE", art = true)
    @Test fun roweDexEntry() = retail("rowe", NATIVE_ROWE, ROWE_ROM, GameKind.ROWE, "DEX", entry = 884, art = true)
    @Test fun glazedBattle() = retail("glazed_battle", NATIVE_GLAZED, GLAZED_ROM, GameKind.GLAZED, "BATTLE", art = true)
    // Super Mariomon: its own species / names (CAPTURES, POCKETS), its party / bag colours, its world map.
    @Test fun mariomonParty() = retail("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "PARTY", art = true)
    @Test fun mariomonItems() = retail("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "ITEMS", art = true)
    @Test fun mariomonItemDescription() = retail("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "ITEMS", art = true, describe = true)
    @Test fun mariomonMap() = retail("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "MAP", art = true)
    @Test fun mariomonBattle() = retail("mariomon_v1_5_1_battle", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "BATTLE", art = true)
    @Test fun mariomonDex() = retail("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "DEX", art = true)
    @Test fun mariomonDexEntry() = retail("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, GameKind.MARIOMON, "DEX", entry = 7, art = true)
    @Test fun mariomonGuideBoss() = retailGuide("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, "NEXT BOSS", kind = GameKind.MARIOMON)
    @Test fun mariomonGuideHere() = retailGuide("mariomon_v1_5_1", NATIVE_MARIOMON_V1_5_1, MARIOMON_ROM, "HERE", kind = GameKind.MARIOMON)
    @Test fun mariomonV13Party() = retail("mariomon_v1_3", NATIVE_MARIOMON_V1_3, MARIOMON_V13_ROM, GameKind.MARIOMON, "PARTY", art = true)
    @Test fun mariomonV13Items() = retail("mariomon_v1_3", NATIVE_MARIOMON_V1_3, MARIOMON_V13_ROM, GameKind.MARIOMON, "ITEMS", art = true)
    // Pokémon Orange Islands (FireRed 1.0 edited in place): its names, CRYSTL type and own archipelago map.
    @Test fun orangeIslandsParty() = retail("orange_islands", NATIVE_ORANGE_ISLANDS, OI_ROM, GameKind.ORANGE_ISLANDS, "PARTY", art = true)
    @Test fun orangeIslandsItems() = retail("orange_islands", NATIVE_ORANGE_ISLANDS, OI_ROM, GameKind.ORANGE_ISLANDS, "ITEMS", art = true)
    @Test fun orangeIslandsMap() = retail("orange_islands", NATIVE_ORANGE_ISLANDS, OI_ROM, GameKind.ORANGE_ISLANDS, "MAP", art = true, romMap = true)
    @Test fun orangeIslandsBattle() = retail("orange_islands_battle", NATIVE_ORANGE_ISLANDS, OI_ROM, GameKind.ORANGE_ISLANDS, "BATTLE", art = true)
    @Test fun orangeIslandsDexEntry() = retail("orange_islands", NATIVE_ORANGE_ISLANDS, OI_ROM, GameKind.ORANGE_ISLANDS, "DEX", entry = 25, art = true)
    @Test fun orangeIslandsGuideBoss() = retailGuide("orange_islands", NATIVE_ORANGE_ISLANDS, OI_ROM, "NEXT BOSS", kind = GameKind.ORANGE_ISLANDS)
    // Ultra Violet: FireRed's look and tables with its own names; its maps' own HERE / WHERE IS.
    @Test fun ultraVioletParty() = retail("ultra_violet", NATIVE_UV, UV_ROM, GameKind.FIRERED, "PARTY", art = true)
    @Test fun ultraVioletItems() = retail("ultra_violet", NATIVE_UV, UV_ROM, GameKind.FIRERED, "ITEMS", art = true)
    @Test fun ultraVioletMap() = retail("ultra_violet", NATIVE_UV, UV_ROM, GameKind.FIRERED, "MAP", art = true)
    @Test fun ultraVioletBattle() = retail("ultra_violet_battle", NATIVE_UV, UV_ROM, GameKind.FIRERED, "BATTLE", art = true)
    @Test fun ultraVioletDexEntry() = retail("ultra_violet", NATIVE_UV, UV_ROM, GameKind.FIRERED, "DEX", entry = 4, art = true)
    @Test fun ultraVioletGuideBoss() = retailGuide("ultra_violet", NATIVE_UV, UV_ROM, "NEXT BOSS", kind = GameKind.FIRERED)
    @Test fun ultraVioletGuideHere() = retailGuide("ultra_violet", NATIVE_UV, UV_ROM, "HERE", kind = GameKind.FIRERED)

    // FireRed Rocket Edition and Ash Gray: retail FireRed's save booted in each (their fixtures' READMEs).
    @Test fun rocketEditionParty() = retail("rocket_edition", NATIVE_RE, RE_ROM, GameKind.FIRERED, "PARTY", art = true)
    @Test fun rocketEditionItems() = retail("rocket_edition", NATIVE_RE, RE_ROM, GameKind.FIRERED, "ITEMS", art = true)
    @Test fun rocketEditionMap() = retail("rocket_edition", NATIVE_RE, RE_ROM, GameKind.FIRERED, "MAP", art = true)
    @Test fun rocketEditionBattle() = retail("rocket_edition_battle", NATIVE_RE, RE_ROM, GameKind.FIRERED, "BATTLE", art = true)
    @Test fun rocketEditionDexEntry() = retail("rocket_edition", NATIVE_RE, RE_ROM, GameKind.FIRERED, "DEX", entry = 35, art = true)
    @Test fun rocketEditionGuideHere() = retailGuide("rocket_edition", NATIVE_RE, RE_ROM, "HERE", kind = GameKind.FIRERED)
    @Test fun ashGrayParty() = retail("ash_gray", NATIVE_AG, AG_ROM, GameKind.FIRERED, "PARTY", art = true)
    @Test fun ashGrayItems() = retail("ash_gray", NATIVE_AG, AG_ROM, GameKind.FIRERED, "ITEMS", art = true)
    @Test fun ashGrayMap() = retail("ash_gray", NATIVE_AG, AG_ROM, GameKind.FIRERED, "MAP", art = true, romMap = true)
    @Test fun ashGrayBattle() = retail("ash_gray_battle", NATIVE_AG, AG_ROM, GameKind.FIRERED, "BATTLE", art = true)
    @Test fun ashGrayGuideHere() = retailGuide("ash_gray", NATIVE_AG, AG_ROM, "HERE", kind = GameKind.FIRERED)
    // Elite Redux: its save in 2.65.4.1b (the fixtures' READMEs); Emerald's Hoenn map and party art from its ROM.
    @Test fun eliteReduxParty() = retail("elite_redux", NATIVE_ER, ER_ROM, GameKind.ELITE_REDUX, "PARTY", art = true)
    @Test fun eliteReduxItems() = retail("elite_redux", NATIVE_ER, ER_ROM, GameKind.ELITE_REDUX, "ITEMS", art = true)
    @Test fun eliteReduxMap() = retail("elite_redux", NATIVE_ER, ER_ROM, GameKind.ELITE_REDUX, "MAP", art = true)
    @Test fun eliteReduxBattle() = retail("elite_redux_battle", NATIVE_ER, ER_ROM, GameKind.ELITE_REDUX, "BATTLE", art = true)
    // Its battle menu has no BAG: FIGHT, then POKéMON and RUN.
    @Test fun eliteReduxBattleMenu() = retail("elite_redux_battle", NATIVE_ER, ER_ROM, GameKind.ELITE_REDUX, "BATTLE", art = true, controls = true)
    @Test fun eliteReduxDexEntry() = retail("elite_redux", NATIVE_ER, ER_ROM, GameKind.ELITE_REDUX, "DEX", entry = 1072, art = true)
    @Test fun eliteReduxGuideHere() = retailGuide("elite_redux", NATIVE_ER, ER_ROM, "HERE", kind = GameKind.ELITE_REDUX)
    @Test fun teamRocketParty() = retail("team_rocket", NATIVE_TR, TR_ROM, GameKind.TEAM_ROCKET, "PARTY", art = true)
    @Test fun teamRocketItems() = retail("team_rocket", NATIVE_TR, TR_ROM, GameKind.TEAM_ROCKET, "ITEMS", art = true)
    @Test fun teamRocketMap() = retail("team_rocket", NATIVE_TR, TR_ROM, GameKind.TEAM_ROCKET, "MAP", art = true)
    @Test fun teamRocketBattle() = retail("team_rocket_battle", NATIVE_TR, TR_ROM, GameKind.TEAM_ROCKET, "BATTLE", art = true)
    @Test fun teamRocketBattleMenu() = retail("team_rocket_battle", NATIVE_TR, TR_ROM, GameKind.TEAM_ROCKET, "BATTLE", art = true, controls = true)
    @Test fun teamRocketGuideHere() = retailGuide("team_rocket_route", NATIVE_TR, TR_ROM, "HERE", kind = GameKind.TEAM_ROCKET)
    @Test fun teamRocketTrainer() = retail("team_rocket_trainer_next", NATIVE_TR, TR_ROM, GameKind.TEAM_ROCKET, "BATTLE", art = true)
    // Wishes of Tomorrow: its headless new game (the fixtures' READMEs); Emerald's party slots and bag from its ROM.
    @Test fun wishesParty() = retail("wishes", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "PARTY", art = true)
    @Test fun wishesItems() = retail("wishes", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "ITEMS", art = true)
    @Test fun wishesMap() = retail("wishes", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "MAP", art = true)
    @Test fun wishesDex() = retail("wishes", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "DEX", art = true)
    @Test fun wishesDexEntry() = retail("wishes", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "DEX", entry = 155, art = true)
    @Test fun wishesBattle() = retail("wishes_battle", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "BATTLE", art = true)
    @Test fun wishesBattleMenu() = retail("wishes_battle", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "BATTLE", art = true, controls = true)
    @Test fun wishesTrainer() = retail("wishes_trainer", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "BATTLE", art = true)
    @Test fun wishesMega() = retail("wishes_mega", NATIVE_WOT, WOT_ROM, GameKind.WISHES, "BATTLE", art = true)
    @Test fun wishesGuideHere() = retailGuide("wishes_route", NATIVE_WOT, WOT_ROM, "HERE", kind = GameKind.WISHES)
    // Its randomizer: the same route, the wild Pokémon swapped the way this save's game swaps them.
    @Test fun wishesGuideHereRandom() = retailGuide("wishes_random", NATIVE_WOT, WOT_ROM, "HERE", kind = GameKind.WISHES)
    // Pokémon Dreamstone Mysteries: Imperium's party grid colours, Emerald's bag, its Cormoria map, its own DEX order.
    @Test fun dreamstoneParty() = retail("dreamstone", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "PARTY", art = true)
    @Test fun dreamstoneItems() = retail("dreamstone", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "ITEMS", art = true)
    @Test fun dreamstoneMap() = retail("dreamstone", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "MAP", art = true)
    @Test fun dreamstoneDex() = retail("dreamstone", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "DEX", art = true)
    @Test fun dreamstoneDexEntry() = retail("dreamstone", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "DEX", entry = 152, art = true)
    @Test fun dreamstoneBattle() = retail("dreamstone_battle", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "BATTLE", art = true)
    @Test fun dreamstoneBattleMenu() = retail("dreamstone_battle", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "BATTLE", art = true, controls = true)
    @Test fun dreamstoneTrainer() = retail("dreamstone_trainer_next", NATIVE_DSM, DSM_ROM, GameKind.DREAMSTONE, "BATTLE", art = true)
    @Test fun dreamstoneGuideHere() = retailGuide("dreamstone_route", NATIVE_DSM, DSM_ROM, "HERE", kind = GameKind.DREAMSTONE)
    @Test fun dreamstoneGuideBoss() = retailGuide("dreamstone", NATIVE_DSM, DSM_ROM, "NEXT BOSS", kind = GameKind.DREAMSTONE)
    // Ghost Grey: its 1.0.3 save in v1.0.4 (the fixtures' READMEs), its grey map from the ROM.
    @Test fun ghostGreyParty() = retail("ghost_grey", NATIVE_GG, GG_ROM, GameKind.FIRERED, "PARTY", art = true)
    @Test fun ghostGreyItems() = retail("ghost_grey", NATIVE_GG, GG_ROM, GameKind.FIRERED, "ITEMS", art = true)
    @Test fun ghostGreyMap() = retail("ghost_grey", NATIVE_GG, GG_ROM, GameKind.FIRERED, "MAP", art = true, romMap = true)
    @Test fun ghostGreyBattle() = retail("ghost_grey_battle", NATIVE_GG, GG_ROM, GameKind.FIRERED, "BATTLE", art = true)
    @Test fun ghostGreyDexEntry() = retail("ghost_grey", NATIVE_GG, GG_ROM, GameKind.FIRERED, "DEX", entry = 12, art = true)
    @Test fun ghostGreyGuideBoss() = retailGuide("ghost_grey", NATIVE_GG, GG_ROM, "NEXT BOSS", kind = GameKind.FIRERED)
    @Test fun ghostGreyGuideHere() = retailGuide("ghost_grey", NATIVE_GG, GG_ROM, "HERE", kind = GameKind.FIRERED)
    // Sword and Shield Ultimate Plus: Galar on CFRU, its sampled party / bag look, its map from the ROM.
    @Test fun swordShieldParty() = retail("sword_shield_up", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD, "PARTY", art = true)
    @Test fun swordShieldItems() = retail("sword_shield_up", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD, "ITEMS", art = true)
    @Test fun swordShieldMap() = retail("sword_shield_up", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD, "MAP", art = true, romMap = true)
    @Test fun swordShieldBattle() = retail("sword_shield_up_battle", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD, "BATTLE", art = true)
    @Test fun swordShieldDex() = hackDex("sword_shield_up", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD)
    @Test fun swordShieldDexEntry() = retail("sword_shield_up", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD, "DEX", entry = 813, art = true)
    @Test fun swordShieldDexEvolve() = dexSection("sword_shield_up", NATIVE_SS, SS_ROM, GameKind.SWORD_SHIELD, 813, DexSection.EVOLVE)
    @Test fun swordShieldGuideBoss() = retailGuide("sword_shield_up", NATIVE_SS, SS_ROM, "NEXT BOSS", kind = GameKind.SWORD_SHIELD)
    @Test fun swordShieldGuideHere() = retailGuide("sword_shield_up", NATIVE_SS, SS_ROM, "HERE", kind = GameKind.SWORD_SHIELD)
    // GS Chronicles: Johto and Kanto on CFRU, CFRU's party slots, its sampled bag, its Johto map from the ROM.
    @Test fun gsChroniclesParty() = retail("gs_chronicles", NATIVE_GSC, GSC_ROM, GameKind.GS_CHRONICLES, "PARTY", art = true)
    @Test fun gsChroniclesItems() = retail("gs_chronicles", NATIVE_GSC, GSC_ROM, GameKind.GS_CHRONICLES, "ITEMS", art = true)
    @Test fun gsChroniclesMap() = retail("gs_chronicles", NATIVE_GSC, GSC_ROM, GameKind.GS_CHRONICLES, "MAP", art = true, romMap = true)
    @Test fun gsChroniclesBattle() = retail("gs_chronicles_battle", NATIVE_GSC, GSC_ROM, GameKind.GS_CHRONICLES, "BATTLE", art = true)
    @Test fun gsChroniclesDex() = hackDex("gs_chronicles", NATIVE_GSC, GSC_ROM, GameKind.GS_CHRONICLES)
    @Test fun gsChroniclesDexEntry() = retail("gs_chronicles", NATIVE_GSC, GSC_ROM, GameKind.GS_CHRONICLES, "DEX", entry = 155, art = true)
    @Test fun gsChroniclesGuideBoss() = retailGuide("gs_chronicles", NATIVE_GSC, GSC_ROM, "NEXT BOSS", kind = GameKind.GS_CHRONICLES)
    @Test fun gsChroniclesGuideHere() = retailGuide("gs_chronicles", NATIVE_GSC, GSC_ROM, "HERE", kind = GameKind.GS_CHRONICLES)
    // Recharged Yellow (v1.9.7; the user's v1.8.0 save): FireRed's look, its own names, FireRed's region map from its ROM.
    @Test fun rechargedYellowParty() = retail("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "PARTY", art = true)
    @Test fun rechargedYellowItems() = retail("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "ITEMS", art = true, describe = true)
    @Test fun rechargedYellowMap() = retail("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "MAP", art = true)
    @Test fun rechargedYellowBattle() = retail("recharged_yellow_battle", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "BATTLE", art = true)
    @Test fun rechargedYellowTrainerBattle() =
        retail("recharged_yellow_trainer", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "BATTLE", art = true)
    @Test fun rechargedYellowDex() = retail("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "DEX", art = true)
    @Test fun rechargedYellowDexEntry() = retail("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, GameKind.RECHARGED_YELLOW, "DEX", entry = 25, art = true)
    @Test fun rechargedYellowGuideBoss() =
        retailGuide("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, "NEXT BOSS", kind = GameKind.RECHARGED_YELLOW)
    @Test fun rechargedYellowGuideWhereIs() =
        retailGuide("recharged_yellow", NATIVE_RECHARGED_YELLOW_V1_9_7, RY_ROM, "WHERE IS", kind = GameKind.RECHARGED_YELLOW)
    @Test fun rechargedYellowV180Party() =
        retail("recharged_yellow_v180", NATIVE_RECHARGED_YELLOW_V1_8_0, RY180_ROM, GameKind.RECHARGED_YELLOW, "PARTY", art = true)
    // Recharged Emerald (v2.2.8; retail Emerald's save, converted): Emerald's look with its own party art, Hoenn map and names.
    @Test fun rechargedEmeraldParty() = retail("recharged_emerald", RE228, RCE_ROM, GameKind.EMERALD, "PARTY", art = true)
    @Test fun rechargedEmeraldItems() = retail("recharged_emerald", RE228, RCE_ROM, GameKind.EMERALD, "ITEMS", art = true, describe = true)
    @Test fun rechargedEmeraldMap() = retail("recharged_emerald", RE228, RCE_ROM, GameKind.EMERALD, "MAP", art = true)
    @Test fun rechargedEmeraldBattle() = retail("recharged_emerald_battle", RE228, RCE_ROM, GameKind.EMERALD, "BATTLE", art = true)
    @Test fun rechargedEmeraldDexEntry() = retail("recharged_emerald", RE228, RCE_ROM, GameKind.EMERALD, "DEX", entry = 1, art = true)
    @Test fun rechargedEmeraldGuideBoss() = retailGuide("recharged_emerald", RE228, RCE_ROM, "NEXT BOSS", kind = GameKind.EMERALD)
    // Unbound's French translation: English's RAM, French names / descriptions / dex text / map names.
    @Test fun unboundFrParty() = retail("unbound_fr", NATIVE_UNBOUND_FR, UB_FR_ROM, GameKind.UNBOUND, "PARTY", art = true)
    @Test fun unboundFrItems() = retail("unbound_fr", NATIVE_UNBOUND_FR, UB_FR_ROM, GameKind.UNBOUND, "ITEMS", art = true, describe = true)
    @Test fun unboundFrMap() = retail("unbound_fr", NATIVE_UNBOUND_FR, UB_FR_ROM, GameKind.UNBOUND, "MAP", art = true, romMap = true)
    @Test fun unboundFrDexEntry() = retail("unbound_fr", NATIVE_UNBOUND_FR, UB_FR_ROM, GameKind.UNBOUND, "DEX", entry = 4, art = true)
    // Quetzal's Spanish release (every IDIOMA option on Spanish), and a second English save in Johto.
    @Test fun quetzalEsParty() = retail("quetzal_es", NATIVE_QUETZAL_ES, QUETZAL_ES_ROM, GameKind.QUETZAL, "PARTY", art = true)
    @Test fun quetzalEsItems() = retail("quetzal_es", NATIVE_QUETZAL_ES, QUETZAL_ES_ROM, GameKind.QUETZAL, "ITEMS", art = true, describe = true)
    @Test fun quetzalEsMap() = retail("quetzal_es", NATIVE_QUETZAL_ES, QUETZAL_ES_ROM, GameKind.QUETZAL, "MAP", art = true)
    @Test fun quetzalEsBattle() = retail("quetzal_es_battle", NATIVE_QUETZAL_ES, QUETZAL_ES_ROM, GameKind.QUETZAL, "BATTLE", art = true)
    @Test fun quetzalEsDexEntry() = retail("quetzal_es", NATIVE_QUETZAL_ES, QUETZAL_ES_ROM, GameKind.QUETZAL, "DEX", entry = 4, art = true)
    // Quetzal Alpha 0.8.4 and its Moemon Hotfix build (the Spanish Alpha 9 save: its bag is that save's, misread by 0.8.4
    // as by its own bag screen). The Moemon builds' pictures come from the ROM at run time; these PNGs are never committed.
    @Test fun quetzal084Party() = retail("quetzal_084", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, GameKind.QUETZAL, "PARTY", art = true)
    @Test fun quetzal084Items() = retail("quetzal_084", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, GameKind.QUETZAL, "ITEMS", art = true, describe = true)
    @Test fun quetzal084Map() = retail("quetzal_084", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, GameKind.QUETZAL, "MAP", art = true)
    @Test fun quetzal084Battle() = retail("quetzal_084_battle", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, GameKind.QUETZAL, "BATTLE", art = true)
    @Test fun quetzal084DexEntry() = retail("quetzal_084", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, GameKind.QUETZAL, "DEX", entry = 4, art = true)
    @Test fun quetzalEs084Map() = retail("quetzal_es_084", NATIVE_QUETZAL_V084_ES, QUETZAL_ES_084_ROM, GameKind.QUETZAL, "MAP", art = true)
    @Test fun quetzalEs084Party() = retail("quetzal_es_084", NATIVE_QUETZAL_V084_ES, QUETZAL_ES_084_ROM, GameKind.QUETZAL, "PARTY", art = true)
    @Test fun quetzalMoemon084Party() = retail("quetzal_moemon_084", NATIVE_QUETZAL_MOEMON_V084, "Moemon Quetzal (Alpha 0.8.4 Hotfix) (English).gba", GameKind.QUETZAL, "PARTY", art = true)
    @Test fun quetzal084GuideBoss() = retailGuide("quetzal_084", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, "NEXT BOSS", kind = GameKind.QUETZAL)
    @Test fun quetzal084GuideHere() = retailGuide("quetzal_084", NATIVE_QUETZAL_V084, QUETZAL_084_ROM, "HERE", kind = GameKind.QUETZAL)
    @Test fun quetzalJohtoParty() = retail("quetzal_johto", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "PARTY", art = true)
    @Test fun quetzalJohtoMap() = retail("quetzal_johto", NATIVE_QUETZAL, QUETZAL_ROM, GameKind.QUETZAL, "MAP", art = true)
    private val redRom = "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"
    @Test fun redParty() = yellow("red", "PARTY", cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    @Test fun redItems() = yellow("red", "ITEMS", cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    @Test fun redBattleMoves() =
        yellow("red_battle_move2", "BATTLE", controls = true, cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    @Test fun redMap() = yellow("red", "MAP", cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    @Test fun redSettings() = yellow("red", "SETTINGS", cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    @Test fun redDexEntry() = yellow("red", "DEX", dexEntry = 151, cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    // PureRed / PureBlue v2.7.6: the retail Red save, converted by the game (the purered / pureblue fixtures).
    private val pureRedRom = "Pokemon PureRed (v2.7.6).gb"
    private val pureBlueRom = "Pokemon PureBlue (v2.7.6).gb"
    private fun pure(fixture: String, tab: String, blue: Boolean = false, controls: Boolean = false, dexEntry: Int? = null) = yellow(
        fixture, tab, controls = controls, dexEntry = dexEntry,
        cfg = if (blue) com.pokedaisy.app.companion.data.GEN1_PURE_BLUE else com.pokedaisy.app.companion.data.GEN1_PURE_RED,
        romFile = if (blue) pureBlueRom else pureRedRom, game = if (blue) GameKind.PURE_BLUE else GameKind.PURE_RED,
    )
    @Test fun pureRedParty() = pure("purered", "PARTY")
    @Test fun pureRedItems() = pure("purered", "ITEMS")
    @Test fun pureRedMap() = pure("purered", "MAP")
    @Test fun pureRedDex() = pure("purered", "DEX")
    @Test fun pureRedDexEntry() = pure("purered", "DEX", dexEntry = 1)
    @Test fun pureRedBattle() = pure("purered_battle", "BATTLE")
    @Test fun pureRedBattleMoves() = pure("purered_battle", "BATTLE", controls = true)
    @Test fun pureBlueParty() = pure("pureblue", "PARTY", blue = true)
    @Test fun pureBlueMap() = pure("pureblue", "MAP", blue = true)
    @Test fun pureBlueDexEntry() = pure("pureblue", "DEX", blue = true, dexEntry = 100)
    @Test fun yellowParty() = yellow("yellow", "PARTY")
    // The GUIDE (a player's request): HERE in VERMILION CITY (its people and items), on ROUTE 1 and in VIRIDIAN FOREST
    // (the wild Pokémon, caught ones marked), NEXT BOSS, the generated WHERE IS; Red's HERE on ROUTE 1 too.
    @Test fun yellowGuide() = yellow("yellow", "GUIDE", guidePage = "HERE")
    @Test fun yellowGuideRoute() = yellow("yellow", "GUIDE", guidePage = "HERE", onMap = 12)
    @Test fun yellowGuideForest() = yellow("yellow", "GUIDE", guidePage = "HERE", onMap = 51)
    @Test fun yellowGuideNextBoss() = yellow("yellow", "GUIDE", guidePage = "NEXT BOSS")
    @Test fun yellowGuideWhereIs() = yellow("yellow", "GUIDE", guidePage = "WHERE IS")
    @Test fun yellowGuideGerman() = yellow("yellow_de", "GUIDE", guidePage = "HERE", onMap = 12, cfg = com.pokedaisy.app.companion.data.GEN1_YELLOW_DE,
        romFile = yellowDeRom)
    @Test fun redGuideRoute() = yellow("red", "GUIDE", guidePage = "HERE", onMap = 12, cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    @Test fun redGuideNextBoss() = yellow("red", "GUIDE", guidePage = "NEXT BOSS", cfg = com.pokedaisy.app.companion.data.GEN1_RED, romFile = redRom, game = GameKind.RED)
    /** A Normal / Flying summary (all three matchup groups) in Yellow's taller font, under the tab bar: its headings sit on
     * their chips' lines there, and once were clipped to a chip's height. */
    @Test fun yellowPartyDetail() = yellow("yellow", "PARTY", monIndex = 4)
    @Test fun yellowItems() = yellow("yellow", "ITEMS")
    @Test fun yellowBattle() = yellow("yellow_battle", "BATTLE")
    @Test fun yellowBattleMenu() = yellow("yellow_battle_menu", "BATTLE", controls = true)
    @Test fun yellowBattleMoves() = yellow("yellow_battle_move2", "BATTLE", controls = true)
    @Test fun yellowMap() = yellow("yellow", "MAP")
    @Test fun yellowSettings() = yellow("yellow", "SETTINGS")
    @Test fun yellowDex() = yellow("yellow", "DEX")
    @Test fun yellowStates() = yellow("yellow", "STATES")
    @Test fun yellowTweaks() = yellow("yellow", "SETTINGS", page = "TWEAKS")
    @Test fun yellowDexEntry() = yellow("yellow", "DEX", dexEntry = 25)

    // The German, French, Italian and Spanish carts on the English save (yellow_de .. yellow_es).
    private val yellowDe = com.pokedaisy.app.companion.data.GEN1_YELLOW_DE
    private val yellowFr = com.pokedaisy.app.companion.data.GEN1_YELLOW_FR
    private val yellowEs = com.pokedaisy.app.companion.data.GEN1_YELLOW_ES
    private val yellowIt = com.pokedaisy.app.companion.data.GEN1_YELLOW_IT
    private val yellowDeRom = "Pokemon - Gelbe Edition - Special Pikachu Edition (Germany) (GBC,SGB Enhanced).gb"
    private val yellowFrRom = "Pokemon - Version Jaune - Edition Speciale Pikachu (France) (GBC,SGB Enhanced).gb"
    private val yellowItRom = "Pokemon - Versione Gialla - Speciale Edizione Pikachu (Italy) (GBC,SGB Enhanced).gb"
    private val yellowEsRom = "Pokemon - Edicion Amarilla - Edicion Especial Pikachu (Spain) (GBC,SGB Enhanced).gb"
    @Test fun yellowDeParty() = yellow("yellow_de", "PARTY", cfg = yellowDe, romFile = yellowDeRom)
    @Test fun yellowDeItems() = yellow("yellow_de", "ITEMS", cfg = yellowDe, romFile = yellowDeRom)
    @Test fun yellowDeMap() = yellow("yellow_de", "MAP", cfg = yellowDe, romFile = yellowDeRom)
    @Test fun yellowDeBattleMenu() = yellow("yellow_de_battle_menu", "BATTLE", controls = true, cfg = yellowDe, romFile = yellowDeRom)
    @Test fun yellowDeDexEntry() = yellow("yellow_de", "DEX", dexEntry = 25, cfg = yellowDe, romFile = yellowDeRom)
    @Test fun yellowFrBattleMoves() = yellow("yellow_fr_battle", "BATTLE", controls = true, cfg = yellowFr, romFile = yellowFrRom)
    @Test fun yellowFrDexEntry() = yellow("yellow_fr", "DEX", dexEntry = 4, cfg = yellowFr, romFile = yellowFrRom)
    @Test fun yellowItParty() = yellow("yellow_it", "PARTY", cfg = yellowIt, romFile = yellowItRom)
    @Test fun yellowEsItems() = yellow("yellow_es", "ITEMS", cfg = yellowEs, romFile = yellowEsRom)
    @Test fun yellowEsDexEntry() = yellow("yellow_es", "DEX", dexEntry = 25, cfg = yellowEs, romFile = yellowEsRom)
    @Test fun goldParty() = crystal("gold", "PARTY", gold = true)
    @Test fun goldItems() = crystal("gold", "ITEMS", gold = true)
    @Test fun goldBattleMoves() = crystal("gold_battle", "BATTLE", controls = true, gold = true)
    @Test fun goldTrainer() = crystal("gold_trainer", "BATTLE", gold = true)
    @Test fun goldMap() = crystal("gold", "MAP", gold = true)
    @Test fun goldDexEntry() = crystal("gold", "DEX", dexEntry = 155, gold = true)
    @Test fun silverParty() = crystal("silver", "PARTY", silver = true)
    @Test fun silverDexEntry() = crystal("silver", "DEX", dexEntry = 155, silver = true)
    @Test fun crystalParty() = crystal("crystal", "PARTY")
    @Test fun crystalGuide() = crystal("crystal", "GUIDE", guidePage = "HERE")
    @Test fun crystalGuideNextBoss() = crystal("crystal", "GUIDE", guidePage = "NEXT BOSS")
    @Test fun crystalGuideWhereIs() = crystal("crystal", "GUIDE", guidePage = "WHERE IS")
    @Test fun crystalItems() = crystal("crystal", "ITEMS")
    @Test fun crystalBattle() = crystal("crystal_battle", "BATTLE")
    @Test fun crystalBattleMenu() = crystal("crystal_battle_menu", "BATTLE", controls = true)
    @Test fun crystalBattleMoves() = crystal("crystal_battle", "BATTLE", controls = true)
    @Test fun crystalMap() = crystal("crystal", "MAP")
    @Test fun crystalSettings() = crystal("crystal", "SETTINGS")
    @Test fun crystalDex() = crystal("crystal", "DEX")
    @Test fun crystalDexEntry() = crystal("crystal", "DEX", dexEntry = 152)
    @Test fun crystalClearParty() = crystal("crystal_clear", "PARTY", clear = true)
    @Test fun crystalClearItems() = crystal("crystal_clear", "ITEMS", clear = true)
    @Test fun crystalClearBattleMoves() = crystal("crystal_clear_battle", "BATTLE", controls = true, clear = true)
    @Test fun crystalClearTrainer() = crystal("crystal_clear_trainer", "BATTLE", clear = true)
    @Test fun crystalClearMap() = crystal("crystal_clear", "MAP", clear = true)
    @Test fun crystalClearDex() = crystal("crystal_clear", "DEX", clear = true)
    @Test fun crystalClearDexEntry() = crystal("crystal_clear", "DEX", dexEntry = 1, clear = true)
    @Test fun prismParty() = crystal("prism", "PARTY", prism = true)
    @Test fun prismItems() = crystal("prism", "ITEMS", prism = true)
    @Test fun prismMap() = crystal("prism", "MAP", prism = true)
    @Test fun prismBattleMoves() = crystal("prism_battle", "BATTLE", controls = true, prism = true)
    @Test fun prismBattleMenu() = crystal("prism_battle_menu", "BATTLE", controls = true, prism = true)
    @Test fun prismTrainer() = crystal("prism_trainer", "BATTLE", prism = true)
    @Test fun prismDex() = crystal("prism", "DEX", prism = true)
    @Test fun prismDexEntry() = crystal("prism", "DEX", dexEntry = 1, prism = true)       // LARVITAR, caught
    @Test fun polishedParty() = crystal("polished_crystal", "PARTY", polished = true)
    @Test fun polishedItems() = crystal("polished_crystal", "ITEMS", polished = true)
    @Test fun polishedMap() = crystal("polished_crystal", "MAP", polished = true)
    @Test fun polishedItemDescription() = crystal("polished_crystal", "ITEMS", polished = true, describe = true)
    @Test fun polishedBattle() = crystal("polished_crystal_battle", "BATTLE", polished = true)
    @Test fun polishedBattleMoves() = crystal("polished_crystal_battle", "BATTLE", controls = true, polished = true)
    @Test fun polishedBattleMenu() = crystal("polished_crystal_battle_menu", "BATTLE", controls = true, polished = true)
    @Test fun polishedTrainer() = crystal("polished_crystal_trainer", "BATTLE", polished = true)
    @Test fun polishedDex() = crystal("polished_crystal", "DEX", polished = true)
    @Test fun polishedDexEntry() = crystal("polished_crystal", "DEX", dexEntry = 155, polished = true)
    @Test fun polishedDexEntryOwn() = crystal("polished_crystal", "DEX", dexEntry = 255, polished = true)   // past the 8th bit
    // Its GUIDE (the first Game Boy one): HERE on ROUTE 29 by day, FALKNER next, the generated WHERE IS, the TIPS.
    @Test fun polishedGuide() = crystal("polished_crystal", "GUIDE", polished = true)
    @Test fun polishedGuideNextBoss() =
        crystal("polished_crystal", "GUIDE", polished = true, guidePage = "NEXT BOSS", guideOpen = "NEXT BOSS/NEXT: LEADER FALKNER/POKéMON 3/LV 13" to 2)
    // ELM'S LAB (LYRA's battle there): NEW BARK TOWN's people - the starters, CYNDAQUIL marked taken - and items.
    @Test fun polishedTrainerHere() = crystal("polished_crystal_trainer", "GUIDE", polished = true, guidePage = "HERE")
    @Test fun polishedGuideWhereIs() = crystal("polished_crystal", "GUIDE", polished = true, guidePage = "WHERE IS")
    @Test fun polishedGuideTips() =
        crystal("polished_crystal", "GUIDE", polished = true, guidePage = "TIPS", guideOpen = "TIPS/WILD POKéMON/Why did the POKéMON on this route change?/null" to 2)
    @Test fun prismDexEntryOwn() = crystal("prism", "DEX", dexEntry = 239, prism = true)  // PHANCERO, one of its own
    @Test fun crystalStatusBar() {
        CompanionStatusBar.shown = true
        try { crystal("crystal", "PARTY", statusBar = true) } finally { CompanionStatusBar.shown = false }
    }
    @Test fun soulGoldV12Party() = retail("soulgold_v12_battle", NATIVE_SOULGOLD_V1_2, SG12_ROM, GameKind.SOULGOLD, "PARTY", art = true)
    @Test fun soulGoldV12Items() = retail("soulgold_v12", NATIVE_SOULGOLD_V1_2, SG12_ROM, GameKind.SOULGOLD, "ITEMS", art = true)
    @Test fun soulGoldV12Battle() = retail("soulgold_v12_battle", NATIVE_SOULGOLD_V1_2, SG12_ROM, GameKind.SOULGOLD, "BATTLE", art = true)
    @Test fun soulGoldV12Map() = retail("soulgold_v12", NATIVE_SOULGOLD_V1_2, SG12_ROM, GameKind.SOULGOLD, "MAP", art = true)
    @Test fun soulGoldV12DexEntry() = hackDex("soulgold_v12", NATIVE_SOULGOLD_V1_2, SG12_ROM, GameKind.SOULGOLD, 25)
    @Test fun soulGoldV12bParty() = retail("soulgold_v12b_battle", NATIVE_SOULGOLD_V1_2B, SG12B_ROM, GameKind.SOULGOLD, "PARTY", art = true)
    @Test fun soulGoldV12bItems() = retail("soulgold_v12b", NATIVE_SOULGOLD_V1_2B, SG12B_ROM, GameKind.SOULGOLD, "ITEMS", art = true)
    @Test fun soulGoldV12bDexEntry() = hackDex("soulgold_v12b", NATIVE_SOULGOLD_V1_2B, SG12B_ROM, GameKind.SOULGOLD, 25)
    @Test fun soulGoldV121Party() = retail("soulgold_v121_battle", NATIVE_SOULGOLD_V1_2_1, SG121_ROM, GameKind.SOULGOLD, "PARTY", art = true)
    @Test fun soulGoldV121Items() = retail("soulgold_v121", NATIVE_SOULGOLD_V1_2_1, SG121_ROM, GameKind.SOULGOLD, "ITEMS", art = true)
    @Test fun soulGoldV121Battle() = retail("soulgold_v121_battle", NATIVE_SOULGOLD_V1_2_1, SG121_ROM, GameKind.SOULGOLD, "BATTLE", art = true)
    @Test fun soulGoldV121Map() = retail("soulgold_v121", NATIVE_SOULGOLD_V1_2_1, SG121_ROM, GameKind.SOULGOLD, "MAP", art = true)
    @Test fun soulGoldV121DexEntry() = hackDex("soulgold_v121", NATIVE_SOULGOLD_V1_2_1, SG121_ROM, GameKind.SOULGOLD, 25)
    @Test fun soulGoldDex() = hackDex("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD)
    @Test fun soulGoldDexEntry() = hackDex("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, 155)

    /** Pokémon Yellow (Game Boy): the Gen 1 reader on a GB fixture (wram.bin). */
    private fun yellow(
        fixture: String, tab: String, controls: Boolean = false, dexEntry: Int? = null, statusBar: Boolean = false, page: String? = null,
        /** A European cart: its config and file (in the gbc folder beside the GBA ROMs). */
        cfg: com.pokedaisy.app.companion.data.Gen1Config = com.pokedaisy.app.companion.data.GEN1_YELLOW,
        romFile: String = "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb",
        game: GameKind = GameKind.YELLOW,
        /** PARTY with this slot's summary open. */
        monIndex: Int? = null,
        guidePage: String? = null,
        /** The save as if it stood on this map (wCurMap): the fixtures are all in a Pokémon Center. */
        onMap: Int? = null,
    ) {
        activeGame = game
        romLanguage = cfg.language   // another test's ROM may have left Japanese names on
        romGameCode = cfg.gameCode
        val rom = java.io.File(java.io.File(RETAIL_ROM_DIR).parentFile, "gbc/$romFile")
        if (rom.isFile) com.pokedaisy.app.companion.data.Gen1Art.extractTo(rom.readBytes(), RomArt.dirOverride!!)
        com.pokedaisy.app.companion.data.Gen1Dex.rom = if (rom.isFile) rom.readBytes() else null
        val t = com.pokedaisy.app.companion.data.readGen1Telemetry(FixtureMemoryReader.load(fixture), cfg)
            .let { if (onMap == null) it else it.copy(mapNum = onMap, regionMapSectionId = onMap) }
        val v = buildSnapshotView(t).copy(game = game)
        // The icon sheets load asynchronously in the app; decode them before the snapshot.
        (v.party + v.battlePlayer + v.battleOpponent).forEach { m -> m.iconAsset?.let { GameArt.get(paparazzi.context, it) } }
        // The touch controls (FIGHT / moves) show with a battle input to drive.
        val input = if (!controls) null else object : com.pokedaisy.app.companion.BattleInput {
            override val busy = false
            override fun selectAction(actionIndex: Int) {}
            override fun selectMove(moveIndex: Int) {}
            override fun back() {}
        }
        val dex = v.pokedex
        if (dex != null) (1..12).forEach { PokedexSource.entry(dex.tables, it); PokedexSource.frontSprite(dex.tables, it) }
        if (guidePage != null) {
            val world = gameGuide(guideId(game, null))!!.world!!
            val here = world.encounters(v.mapGroup, v.mapNum, v.progress)
            val teams = gameGuide(guideId(game, null))?.bosses.orEmpty().flatMap { b -> v.progress?.let { p -> b.trainers(p).flatMap { world.party(it, p) } }.orEmpty() }
            ((here.grass + here.water + here.oldRod + here.goodRod + here.superRod).map { it.species } + teams.map { it.species })
                .forEach { sp -> com.pokedaisy.app.companion.data.spriteAsset("pokemon", sp)?.let { GameArt.get(paparazzi.context, it) } }
            snapshot {
                AppTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pokedaisy.app.companion.ui.theme.LocalGameFont provides com.pokedaisy.app.companion.ui.theme.rememberGameFont(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameTextScale provides com.pokedaisy.app.companion.ui.theme.gameTextScale(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth provides com.pokedaisy.app.companion.ui.theme.gameFontWidth(game),
                    ) {
                        val ui = androidx.compose.runtime.remember { GuideUiState().apply { this.page = guidePage } }
                        GuideScreen(rememberGuide(v.game, v.pokedex, v.guideTables)!!, ui, v)
                    }
                }
            }
            return
        }
        if (dexEntry != null && dex != null) {
            PokedexSource.entry(dex.tables, dexEntry); PokedexSource.frontSprite(dex.tables, dexEntry)
            snapshot {
                AppTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pokedaisy.app.companion.ui.theme.LocalGameFont provides com.pokedaisy.app.companion.ui.theme.rememberGameFont(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameTextScale provides com.pokedaisy.app.companion.ui.theme.gameTextScale(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth provides com.pokedaisy.app.companion.ui.theme.gameFontWidth(game),
                    ) {
                        val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                        ui.open = dexEntry
                        PokedexEntryScreen(dex, ui, dexEntry)
                    }
                }
            }
            return
        }
        snapshot {
            CompanionScreen(
                v, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab, battleInput = input, initialSettingsPage = page,
                initialMonIndex = monIndex,
                statusBar = if (!statusBar) null else ({ GameStatusBar("Pokémon Yellow", v.location.mapSecName, 3175, "12:34") }),
            )
        }
    }

    /** Pokémon Crystal (Game Boy Color): the Gen 2 reader on a GB fixture (wram.bin + wramx.bin), art from the rev 1 ROM;
     * [clear]: Crystal Clear v2.6.0's instead. */
    private fun crystal(fixture: String, tab: String, controls: Boolean = false, dexEntry: Int? = null, statusBar: Boolean = false,
                        clear: Boolean = false, prism: Boolean = false, gold: Boolean = false, silver: Boolean = false,
                        polished: Boolean = false, describe: Boolean = false, guidePage: String? = null,
                        guideOpen: Pair<String, Int>? = null) {
        val game = if (polished) GameKind.POLISHED_CRYSTAL else if (prism) GameKind.PRISM else if (clear) GameKind.CRYSTAL_CLEAR else if (gold) GameKind.GOLD
            else if (silver) GameKind.SILVER else GameKind.CRYSTAL
        var cfg = if (polished) com.pokedaisy.app.companion.data.GEN2_POLISHED_CRYSTAL
            else if (prism) com.pokedaisy.app.companion.data.GEN2_PRISM
            else if (clear) com.pokedaisy.app.companion.data.GEN2_CRYSTAL_CLEAR_V2_6_0
            else if (gold) com.pokedaisy.app.companion.data.GEN2_GOLD else if (silver) com.pokedaisy.app.companion.data.GEN2_SILVER
            else com.pokedaisy.app.companion.data.GEN2_CRYSTAL
        activeGame = game
        com.pokedaisy.app.companion.data.activeGen2 = cfg
        romLanguage = 'E'
        romGameCode = ""
        val rom = java.io.File(java.io.File(RETAIL_ROM_DIR).parentFile,
            if (polished) "gbc/Pokemon Polished Crystal (v3.2.3).gbc"
            else if (prism) "gbc/Pokemon Prism (v0.95 build 254 Hotfix 5).gbc"
            else if (gold) "gbc/Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"
            else if (silver) "gbc/Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc"
            else if (clear) "gbc/Pokemon Crystal Clear (v2.6.0).gbc" else "gbc/Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc")
        if (rom.isFile) com.pokedaisy.app.companion.data.Gen2Art.extractTo(rom.readBytes(), RomArt.dirOverride!!)
        // The cart's bytes (dex pages, item text), as PokeDaisyActivity sets them for a Game Boy ROM.
        com.pokedaisy.app.companion.data.Gen1Dex.rom = if (rom.isFile) rom.readBytes() else null
        // Polished Crystal's dex tables come from the ROM by build (the Poller sets them on detection).
        if (polished && rom.isFile) {
            cfg = cfg.copy(pokedex = com.pokedaisy.app.companion.data.polishedCrystalPokedex(com.pokedaisy.app.companion.data.TelemetrySampler.POLISHED_CRYSTAL_V3_2_3_SHA1))
            com.pokedaisy.app.companion.data.activeGen2 = cfg
        }
        val t = com.pokedaisy.app.companion.data.readGen2Telemetry(FixtureMemoryReader.load(fixture), cfg)
        val v = buildSnapshotView(t).copy(game = game)
        (v.party + v.battlePlayer + v.battleOpponent).forEach { m -> m.iconAsset?.let { GameArt.get(paparazzi.context, it) } }
        val input = if (!controls) null else object : com.pokedaisy.app.companion.BattleInput {
            override val busy = false
            override fun selectAction(actionIndex: Int) {}
            override fun selectMove(moveIndex: Int) {}
            override fun back() {}
        }
        val dex = v.pokedex
        // Pages by dex number, pictures by species (the same numbers but in Prism, whose dex has its own order).
        fun warm(n: Int) = dex?.let { PokedexSource.entry(it.tables, n); PokedexSource.frontSprite(it.tables, PokedexSource.speciesFor(it.tables, n)) }
        (1..12).forEach { warm(it) }
        listOf(16, 97, 152, 161).forEach { warm(it) }
        if (guidePage != null) {
            // The guide's own icons (HERE's wild Pokémon, the bosses' teams), loaded as the app's tab would have them.
            val world = gameGuide(guideId(game, null))!!.world!!
            val here = world.encounters(v.mapGroup, v.mapNum, v.progress)
            val teams = gameGuide(guideId(game, null))?.bosses.orEmpty().take(3).flatMap { b -> v.progress?.let { world.party(b.trainer(it), it) }.orEmpty() }
            ((here.grass + here.headbutt + here.headbuttRare).map { it.species } + teams.map { it.species })
                .forEach { sp -> com.pokedaisy.app.companion.data.spriteAsset("pokemon", sp)?.let { GameArt.get(paparazzi.context, it) } }
            snapshot {
                AppTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pokedaisy.app.companion.ui.theme.LocalGameFont provides com.pokedaisy.app.companion.ui.theme.rememberGameFont(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameTextScale provides com.pokedaisy.app.companion.ui.theme.gameTextScale(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth provides com.pokedaisy.app.companion.ui.theme.gameFontWidth(game),
                    ) {
                        val ui = androidx.compose.runtime.remember {
                            GuideUiState().apply { page = guidePage; guideOpen?.let { open = it.first; level = it.second } }
                        }
                        GuideScreen(rememberGuide(v.game, v.pokedex, v.guideTables)!!, ui, v)
                    }
                }
            }
            return
        }
        if (dexEntry != null && dex != null) {
            warm(dexEntry)
            snapshot {
                AppTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pokedaisy.app.companion.ui.theme.LocalGameFont provides com.pokedaisy.app.companion.ui.theme.rememberGameFont(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameTextScale provides com.pokedaisy.app.companion.ui.theme.gameTextScale(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth provides com.pokedaisy.app.companion.ui.theme.gameFontWidth(game),
                    ) {
                        val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                        ui.open = dexEntry
                        PokedexEntryScreen(dex, ui, dexEntry)
                    }
                }
            }
            return
        }
        if (describe) {
            // The bag with its first item open: its description read from the ROM.
            snapshot {
                AppTheme {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.pokedaisy.app.companion.ui.theme.LocalGameFont provides com.pokedaisy.app.companion.ui.theme.rememberGameFont(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameTextScale provides com.pokedaisy.app.companion.ui.theme.gameTextScale(game),
                        com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth provides com.pokedaisy.app.companion.ui.theme.gameFontWidth(game),
                    ) { ItemsScreen(v.items, androidx.compose.ui.Modifier.fillMaxSize(), initialItemId = v.items.first().itemId) }
                }
            }
            return
        }
        snapshot {
            CompanionScreen(
                v, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab, battleInput = input,
                statusBar = if (!statusBar) null else ({ GameStatusBar(if (clear) "Pokémon Crystal Clear" else "Pokémon Crystal", v.location.mapSecName, t.money, "12:34") }),
            )
        }
    }

    /** [describe]: the ITEMS tab with the bag's first item open, its description read from the ROM. */
    private fun retail(
        fixture: String, cfg: NativeConfig, romFile: String, kind: GameKind, tab: String, entry: Int? = null, art: Boolean = false,
        describe: Boolean = false, romMap: Boolean = false,
        /** The move advice: PARTY > LEARN on the first Pokémon with something to replace, or a TM's party row. */
        learn: Boolean = false, tmRow: Boolean = false,
        /** The battle tab's touch controls (FIGHT / BAG / POKéMON / RUN), with a battle input to drive. */
        controls: Boolean = false,
        /** BOXES: SWAP's animation over the tab at this moment (ms), the box's first Pokémon trading with the party's lead. */
        swapAnimAt: Long? = null,
        /** With [swapAnimAt]: "swap", or "deposit" (the party's second into the box) / "withdraw" (the box's first out). */
        pcMove: String = "swap",
        /** A summary with the PC's button: "deposit" (the party's second), "pick" (its box pick), "withdraw" (the box's first). */
        summary: String? = null,
    ) {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + romFile) ?: return
        if (art) RomArt.extractTo(java.io.File(RETAIL_ROM_DIR + romFile).readBytes(), RomArt.dirOverride!!)
        // A FireRed-engine hack's own region map, read from its ROM (the app does this on every launch).
        com.pokedaisy.app.companion.data.RomRegionMap.current =
            if (romMap) com.pokedaisy.app.companion.data.RomRegionMap.loadNow(RomArt.dirOverride!!, fixture, java.io.File(RETAIL_ROM_DIR + romFile)) else null
        activeGame = kind
        amethystV141 = cfg === NATIVE_AMETHYST_V1_4_1 // the Poller sets these with activeGame
        com.pokedaisy.app.companion.data.fireRedHack = com.pokedaisy.app.companion.data.fireRedHackFor(cfg)
        com.pokedaisy.app.companion.data.emeraldHack = com.pokedaisy.app.companion.data.emeraldHackFor(cfg)
        com.pokedaisy.app.companion.data.mariomonV13 = cfg === NATIVE_MARIOMON_V1_3
        com.pokedaisy.app.companion.data.quetzalV084 = cfg.quetzalV084
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
        com.pokedaisy.app.companion.data.quetzalNames =
            com.pokedaisy.app.companion.data.readQuetzalNames(FixtureMemoryReader.load(fixture), cfg)
        resetNativeBagCache()
        // Item descriptions are read from the ROM as the snapshot is built (RomItemText).
        PokedexSource.reader = rom
        RomItemText.use(cfg.itemDescs)
        val t = readNativeTelemetry(rom.withRam(FixtureMemoryReader.load(fixture)), cfg)
        val v = buildSnapshotView(t).copy(game = kind)
        DecompIconSource.reader = rom
        DecompIconSource.tables = cfg.iconTables
        val dex = v.pokedex
        (v.party.map { it.species } + t.battleMons.map { it.species }.filter { it != 0 } +
            (if (dex == null) emptyList() else (1..10).map { PokedexSource.speciesFor(dex.tables, it) } +
                (PokedexSource.regionalOrder(dex.tables) ?: IntArray(0)).take(10).map { PokedexSource.speciesFor(dex.tables, it) }))
            .forEach { DecompIconSource.get(it) }
        // BOXES: every box Pokémon's icon, and PKMN DATA's first picture.
        t.pcBoxes?.boxes?.flatMap { it.mons }?.filterNotNull()?.forEach { DecompIconSource.get(it.species) }
        if (dex != null) t.pcBoxes?.boxes?.get(t.pcBoxes!!.currentBox)?.mons?.firstOrNull { it != null }?.let { PokedexSource.frontSprite(dex.tables, it.species) }
        val swapBoxMon = v.pcBoxes?.let { b -> b.boxes[b.currentBox].mons.firstOrNull { it != null } }
        val boxName = v.pcBoxes?.let { b -> b.boxes[b.currentBox].name }.orEmpty()
        val ok = com.pokedaisy.app.companion.data.BoxSwapResult.SWAPPED
        val pcAnim = when (pcMove) {
            "deposit" -> BoxSwapAnim(null, v.party[1], boxName, boxDepositMessage(ok, v.party[1], boxName))
            "withdraw" -> swapBoxMon?.let { BoxSwapAnim(it, null, boxName, boxWithdrawMessage(ok, it, boxName)) }
            else -> swapBoxMon?.let { BoxSwapAnim(it, v.party.first(), boxName, boxSwapMessage(ok, it, v.party.first(), boxName)) }
        }
        if (swapAnimAt != null && dex != null) listOfNotNull(pcAnim?.boxMon, pcAnim?.partyMon).forEach { PokedexSource.frontSprite(dex.tables, it.species) }
        v.items.forEach { DecompIconSource.getItem(it.itemId) }
        if (entry != null && dex != null) {
            val species = PokedexSource.speciesFor(dex.tables, entry)
            PokedexSource.entry(dex.tables, entry); PokedexSource.frontSprite(dex.tables, species); PokedexSource.footprint(dex.tables, species)
        }
        val ctx = if (learn || tmRow) LearnContext(dex!!.tables, v.guideTables, v.items, showHints = true) else null
        // The advice is read on IO in the app; worked out here first, the screens start from it.
        val learnAt = if (!learn) -1 else v.party.indices.firstOrNull { i ->
            com.pokedaisy.app.companion.data.MoveLearning.options(ctx!!.tables, ctx.guide, v.party[i], v.items)
                .any { it.advice.verdict == com.pokedaisy.app.companion.data.Verdict.REPLACE }
        } ?: 0
        val learnPick = if (!learn) null else com.pokedaisy.app.companion.data.MoveLearning.options(ctx!!.tables, ctx.guide, v.party[learnAt], v.items)
            .let { o -> o.firstOrNull { it.advice.verdict == com.pokedaisy.app.companion.data.Verdict.REPLACE } ?: o.firstOrNull() }?.move ?: 0
        // A TM someone in the party is BEST for, else the first.
        val tms = if (!tmRow) emptyList() else v.items.filter { com.pokedaisy.app.companion.data.MoveLearning.isTm(it.itemId) }
        val tm = tms.firstOrNull { item ->
            com.pokedaisy.app.companion.data.MoveLearning.learners(ctx!!.tables, ctx.guide, v.party, item.itemId)
                ?.let { com.pokedaisy.app.companion.data.MoveLearning.best(it) } != null
        } ?: tms.firstOrNull()
        snapshot {
            if (learn) AppTheme { MonDetailScreen(v.party, learnAt, {}, {}, learn = ctx, initialLearnPick = learnPick) }
            else if (tmRow) AppTheme {
                // The TMs & HMs alone, so their rows ("TM06 TOXIC") show above the bar.
                ItemsScreen(
                    v.items.filter { com.pokedaisy.app.companion.data.MoveLearning.isTm(it.itemId) },
                    androidx.compose.ui.Modifier.fillMaxSize(), initialItemId = tm?.itemId,
                    tmMoves = com.pokedaisy.app.companion.data.MoveLearning.tmMoveNames(ctx!!.tables),
                    itemExtra = { item, text, shadow -> TmLearnersRow(ctx!!, v.party, item, text, shadow, rememberGbaTextMetrics()) },
                )
            }
            else if (describe) AppTheme { ItemsScreen(v.items, androidx.compose.ui.Modifier.fillMaxSize(), initialItemId = v.items.first().itemId) }
            else if (swapAnimAt != null) androidx.compose.foundation.layout.Box {
                CompanionScreen(v, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab)
                AppTheme {
                    BoxSwapAnimation(pcAnim!!, dex?.tables, onDone = {}, at = swapAnimAt)
                }
            }
            else if (summary == "withdraw") AppTheme {
                MonDetailScreen(boxSummaryList(v.pcBoxes!!.boxes[v.pcBoxes!!.currentBox]), 0, {}, {}, onWithdraw = {})
            }
            else if (summary != null) androidx.compose.foundation.layout.Box {
                // The party summary as the tab opens it, its PC moves on.
                val pc = object : com.pokedaisy.app.companion.ItemUse {
                    override fun canUse(itemId: Int) = false
                    override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) = Unit
                    override fun canSwapBox() = true
                }
                CompanionScreen(v, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab, initialMonIndex = 1, itemUse = pc)
                if (summary == "pick") AppTheme { BoxDepositPicker(v.pcBoxes!!, v.party[1], onPick = {}, onDismiss = {}) }
            }
            else if (entry == null) CompanionScreen(
                v, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = tab,
                battleInput = if (!controls) null else object : com.pokedaisy.app.companion.BattleInput {
                    override val busy = false
                    override fun selectAction(actionIndex: Int) {}
                    override fun selectMove(moveIndex: Int) {}
                    override fun back() {}
                },
            )
            else AppTheme {
                val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                ui.open = entry
                PokedexEntryScreen(dex!!, ui, entry)
            }
        }
    }

    // The ROM hacks' DEX: each `<game>_dex` fixture's real flags (see
    // HackPokedexTest) with the hack's own ROM tables - the list, and one entry.
    @Test fun odysseyDexEntry() = hackDex("odyssey_dex", NATIVE_ODYSSEY, "Pokemon Odyssey (v4.1.1).gba", GameKind.ODYSSEY, 387)
    @Test fun radicalRedDexEntry() = hackDex("radical_red_dex", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", GameKind.RADICAL_RED, 35)
    @Test fun amethystDex() = hackDex("amethyst_dex", NATIVE_AMETHYST, "Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST)
    @Test fun amethystDexEntry() = hackDex("amethyst_dex", NATIVE_AMETHYST, "Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST, 340)
    @Test fun amethystParty() = retail("amethyst", NATIVE_AMETHYST, "Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST, "PARTY", art = true)
    @Test fun amethystMap() = retail("amethyst", NATIVE_AMETHYST, "Pokemon Amethyst (v1.3.0).gba", GameKind.AMETHYST, "MAP", art = true)
    @Test fun amethystV141Dex() = hackDex("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, GameKind.AMETHYST)
    @Test fun amethystV141Party() = retail("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, GameKind.AMETHYST, "PARTY", art = true)
    @Test fun amethystV141Items() = retail("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, GameKind.AMETHYST, "ITEMS", art = true)
    @Test fun amethystV141Map() = retail("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, GameKind.AMETHYST, "MAP", art = true)
    @Test fun amethystV141DexEntry() = hackDex("amethyst_v141", NATIVE_AMETHYST_V1_4_1, AM141_ROM, GameKind.AMETHYST, 340)
    // The European Emeralds: English's save (emerald_<lang>) with each game's own names and party art.
    @Test fun emeraldDeParty() = retail("emerald_de", NATIVE_EMERALD_DE, EM_DE_ROM, GameKind.EMERALD, "PARTY", art = true)
    @Test fun emeraldDeBattle() = retail("emerald_de_battle", NATIVE_EMERALD_DE, EM_DE_ROM, GameKind.EMERALD, "BATTLE", art = true)
    @Test fun emeraldDeItems() = retail("emerald_de", NATIVE_EMERALD_DE, EM_DE_ROM, GameKind.EMERALD, "ITEMS", art = true)
    @Test fun emeraldDeMap() = retail("emerald_de", NATIVE_EMERALD_DE, EM_DE_ROM, GameKind.EMERALD, "MAP", art = true)
    @Test fun emeraldDeDexEntry() = retail("emerald_de", NATIVE_EMERALD_DE, EM_DE_ROM, GameKind.EMERALD, "DEX", entry = 255)
    @Test fun emeraldDeGuideHere() =
        retailGuide("emerald_de", NATIVE_EMERALD_DE, EM_DE_ROM, "HERE", kind = GameKind.EMERALD)
    @Test fun emeraldEsParty() = retail("emerald_es", NATIVE_EMERALD_ES, EM_ES_ROM, GameKind.EMERALD, "PARTY", art = true)
    @Test fun emeraldEsDexEntry() = retail("emerald_es", NATIVE_EMERALD_ES, EM_ES_ROM, GameKind.EMERALD, "DEX", entry = 255)
    @Test fun emeraldFrParty() = retail("emerald_fr", NATIVE_EMERALD_FR, EM_FR_ROM, GameKind.EMERALD, "PARTY", art = true)
    @Test fun emeraldFrItems() = retail("emerald_fr", NATIVE_EMERALD_FR, EM_FR_ROM, GameKind.EMERALD, "ITEMS", art = true)
    @Test fun emeraldItParty() = retail("emerald_it", NATIVE_EMERALD_IT, EM_IT_ROM, GameKind.EMERALD, "PARTY", art = true)
    // Japanese Emerald: kana names, the app's own party slot.
    @Test fun emeraldJaParty() = retail("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, GameKind.EMERALD, "PARTY", art = true)
    @Test fun emeraldJaItems() = retail("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, GameKind.EMERALD, "ITEMS", art = true)
    @Test fun emeraldJaMap() = retail("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, GameKind.EMERALD, "MAP", art = true)
    @Test fun emeraldJaBattle() = retail("emerald_ja_battle", NATIVE_EMERALD_JA, EM_JA_ROM, GameKind.EMERALD, "BATTLE", art = true)
    @Test fun emeraldJaDexEntry() = retail("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, GameKind.EMERALD, "DEX", entry = 255)
    @Test fun emeraldJaGuideHere() = retailGuide("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, "HERE", kind = GameKind.EMERALD)
    @Test fun emeraldJaGuideNextBoss() = retailGuide("emerald_ja", NATIVE_EMERALD_JA, EM_JA_ROM, "NEXT BOSS", kind = GameKind.EMERALD)
    // scripts/port_retail.py's ports (RETAIL_PORTS): the port_* fixtures, each game's own names and art.
    @Test fun portFireRedDeParty() = retail("port_bprd0", port("BPRD0"), ML_FR_DE, GameKind.FIRERED, "PARTY", art = true)
    @Test fun portFireRedDeDexEntry() = retail("port_bprd0", port("BPRD0"), ML_FR_DE, GameKind.FIRERED, "DEX", entry = 6)
    @Test fun portLeafGreenEsItems() = retail("port_bpgs0", port("BPGS0"), ML_LG_ES, GameKind.FIRERED, "ITEMS", art = true)
    @Test fun portSapphireFrDexEntry() = retail("port_axpf1", port("AXPF1"), ML_SA_FR, GameKind.EMERALD, "DEX", entry = 321)
    @Test fun portRubyJaParty() = retail("port_axvj1", port("AXVJ1"), ML_RU_JA, GameKind.EMERALD, "PARTY", art = true)
    @Test fun portFireRedJaParty() = retail("port_bprj1", port("BPRJ1"), ML_FR_JA, GameKind.FIRERED, "PARTY", art = true)
    @Test fun portFireRedJaItems() = retail("port_bprj1", port("BPRJ1"), ML_FR_JA, GameKind.FIRERED, "ITEMS", art = true)
    @Test fun portFireRedJaDexEntry() = retail("port_bprj1", port("BPRJ1"), ML_FR_JA, GameKind.FIRERED, "DEX", entry = 6)
    @Test fun portRubyJaMap() = retail("port_axvj1", port("AXVJ1"), ML_RU_JA, GameKind.EMERALD, "MAP", art = true)
    @Test fun portRubyJaDex() = retail("port_axvj1", port("AXVJ1"), ML_RU_JA, GameKind.EMERALD, "DEX")
    @Test fun portRubyEnDex() = retail("port_en_axve1", com.pokedaisy.app.companion.data.NATIVE_RUBY, ML + "Pokemon - Ruby Version (USA, Europe) (Rev 1)/Pokemon - Ruby Version (USA, Europe) (Rev 1).gba", GameKind.EMERALD, "DEX")
    @Test fun portRubyJaBattle() = retail("port_axvj1/battle", port("AXVJ1"), ML_RU_JA, GameKind.EMERALD, "BATTLE", art = true)
    @Test fun gaiaDex() = hackDex("gaia_dex", NATIVE_GAIA_V3_2, "Pokemon Gaia (v3.2).gba", GameKind.GAIA)
    @Test fun gaiaDexEntry() = hackDex("gaia_dex", NATIVE_GAIA_V3_2, "Pokemon Gaia (v3.2).gba", GameKind.GAIA, 390)
    @Test fun celiaDex() = hackDex("celia_dex", NATIVE_CELIA, "Pokemon Celia's Stupid Romhack (v1.1.4).gba", GameKind.CELIA)
    @Test fun celiaDexEntry() = hackDex("celia_dex", NATIVE_CELIA, "Pokemon Celia's Stupid Romhack (v1.1.4).gba", GameKind.CELIA, 2)
    @Test fun heartAndSoulDex() = hackDex("heart_and_soul_dex", NATIVE_HEART_AND_SOUL, "Pokemon Heart and Soul (v2.0.6).gba", GameKind.HEART_AND_SOUL)
    @Test fun heartAndSoulDexEntry() = hackDex("heart_and_soul_dex", NATIVE_HEART_AND_SOUL, "Pokemon Heart and Soul (v2.0.6).gba", GameKind.HEART_AND_SOUL, 155)
    @Test fun lazarusDexEntry() = hackDex("lazarus_dex", NATIVE_LAZARUS, "Pokemon Lazarus (v2.0).gba", GameKind.LAZARUS, 653)
    @Test fun seaglassDex() = hackDex("emerald_seaglass_dex", NATIVE_EMERALD_SEAGLASS, "Pokemon Emerald Seaglass (v3.0).gba", GameKind.EMERALD_SEAGLASS)
    @Test fun seaglassDexEntry() = hackDex("emerald_seaglass_dex", NATIVE_EMERALD_SEAGLASS, "Pokemon Emerald Seaglass (v3.0).gba", GameKind.EMERALD_SEAGLASS, 4)
    @Test fun tmt2Dex() = hackDex("tmt2_dex", NATIVE_TMT2, "Pokemon Too Many Types 2 (v1.5.2).gba", GameKind.TMT2)
    @Test fun tmt2DexEntry() = hackDex("tmt2_dex", NATIVE_TMT2, "Pokemon Too Many Types 2 (v1.5.2).gba", GameKind.TMT2, 397)

    private fun hackDex(fixture: String, cfg: NativeConfig, romFile: String, kind: GameKind, entry: Int? = null) {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + romFile) ?: return
        activeGame = kind
        amethystV141 = cfg === NATIVE_AMETHYST_V1_4_1 // the Poller sets these with activeGame
        com.pokedaisy.app.companion.data.fireRedHack = com.pokedaisy.app.companion.data.fireRedHackFor(cfg)
        com.pokedaisy.app.companion.data.emeraldHack = com.pokedaisy.app.companion.data.emeraldHackFor(cfg)
        com.pokedaisy.app.companion.data.mariomonV13 = cfg === NATIVE_MARIOMON_V1_3
        com.pokedaisy.app.companion.data.quetzalV084 = cfg.quetzalV084
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
        val t = cfg.pokedex!!
        PokedexSource.reader = rom // before the flags: Rogue's are by species, read through the ROM
        val dex = readPokedexState(rom.withRam(FixtureMemoryReader.load(fixture)), cfg, t)!!
        DecompIconSource.reader = rom
        DecompIconSource.tables = cfg.iconTables
        val shown = if (!t.hasRegional || dex.national) (1..t.nationalCount).toList() else PokedexSource.regionalOrder(t)!!.toList()
        (shown.take(12) + listOfNotNull(entry)).forEach { n ->
            val species = PokedexSource.speciesFor(t, n)
            PokedexSource.entry(t, n); PokedexSource.frontSprite(t, species); PokedexSource.footprint(t, species)
            DecompIconSource.get(species)
        }
        val v = SampleCompanion.snapshot.copy(game = kind, party = emptyList(), pokedex = dex)
        snapshot {
            if (entry == null) CompanionScreen(v, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "DEX")
            else AppTheme {
                val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                ui.open = entry
                PokedexEntryScreen(dex!!, ui, entry)
            }
        }
    }

    // The dex page's EVOLVE / AREA / MOVES (GitHub #28), each from the game's own ROM and save.
    // The move advice (original games): PARTY > LEARN with a pick, and a TM's party row on ITEMS.
    // The PC's boxes on FireRed's own box screen (its art rebuilt from the ROM), at every size the subclasses run.
    @Test fun fireRedBoxes() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true,
    )
    @Test fun emeraldBoxes() = retail(
        "emerald_vanilla", NATIVE_EMERALD_RETAIL.copy(pokemonStoragePtr = NATIVE_EMERALD_RETAIL.saveBlock2Ptr + 4), EM_ROM, GameKind.EMERALD, "BOXES", art = true,
    )
    @Test fun leafGreenBoxes() = retail(
        "leafgreen_rev1", NATIVE_LEAFGREEN_REV1.copy(pokemonStoragePtr = NATIVE_LEAFGREEN_REV1.saveBlock2Ptr + 4), LG_ROM, GameKind.FIRERED, "BOXES", art = true,
    )
    @Test fun soulGoldBoxes() = retail("soulgold_party", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, "BOXES", art = true)
    @Test fun unboundBoxes() = retail("unbound_boxes", NATIVE_UNBOUND_WITH_DEX, "Pokemon Unbound (v2.1.1.1).gba", GameKind.UNBOUND, "BOXES", art = true)
    /** SWAP's animation, held at the pictures turning white, the sparks crossing and the landing. */
    @Test fun fireRedBoxesSwapWhite() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true, swapAnimAt = 330,
    )
    @Test fun fireRedBoxesSwapCrossing() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true, swapAnimAt = 840,
    )
    @Test fun fireRedBoxesSwapLanding() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true, swapAnimAt = 1250,
    )
    @Test fun fireRedBoxesSwapLanded() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true, swapAnimAt = 1600,
    )
    /** The swap's end: what happened, in a window under the two wells. */
    @Test fun fireRedBoxesSwapMessage() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true, swapAnimAt = 2000,
    )
    /** WITHDRAW with room in the party: the box's Pokémon alone, over the top, then its message. */
    @Test fun fireRedBoxesWithdrawing() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true,
        swapAnimAt = 840, pcMove = "withdraw",
    )
    @Test fun fireRedBoxesWithdrawn() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true,
        swapAnimAt = 2000, pcMove = "withdraw",
    )
    /** DEPOSIT over the PARTY tab: the party's second into the box, then its message. */
    @Test fun fireRedPartyDeposited() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "PARTY", art = true,
        swapAnimAt = 2000, pcMove = "deposit",
    )
    /** The summaries with the PC's buttons: a party Pokémon's DEPOSIT (and its box pick), a box one's WITHDRAW; the
     * Pokémon in a column on the right. */
    @Test fun fireRedPartySummaryDeposit() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "PARTY", art = true,
        summary = "deposit",
    )
    @Test fun fireRedPartyDepositPick() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "PARTY", art = true,
        summary = "pick",
    )
    @Test fun fireRedBoxSummaryWithdraw() = retail(
        "firered_vanilla", NATIVE_FIRERED_REV1.copy(pokemonStoragePtr = NATIVE_FIRERED_REV1.saveBlock2Ptr + 4), FR_ROM, GameKind.FIRERED, "BOXES", art = true,
        summary = "withdraw",
    )
    @Test fun fireRedLearn() = retail("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, "PARTY", art = true, learn = true)
    @Test fun fireRedTmRow() = retail("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, "ITEMS", art = true, tmRow = true)
    @Test fun emeraldLearn() = retail("emerald_vanilla", NATIVE_EMERALD_RETAIL, EM_ROM, GameKind.EMERALD, "PARTY", art = true, learn = true)
    @Test fun emeraldTmRow() = retail("emerald_vanilla", NATIVE_EMERALD_RETAIL, EM_ROM, GameKind.EMERALD, "ITEMS", art = true, tmRow = true)
    @Test fun fireRedDexInfo() = dexSection("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, 133, DexSection.INFO)
    @Test fun fireRedDexEvolve() = dexSection("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, 133, DexSection.EVOLVE)
    @Test fun fireRedDexArea() = dexSection("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, 129, DexSection.AREA)
    /** EEVEE isn't wild in FireRed: the gift on the CELADON CONDOMINIUMS roof, from the area data. */
    @Test fun fireRedDexAreaGift() = dexSection("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, 133, DexSection.AREA)
    @Test fun fireRedDexMoves() = dexSection("firered_vanilla", NATIVE_FIRERED_REV1, FR_ROM, GameKind.FIRERED, 1, DexSection.MOVES)
    @Test fun emeraldDexEvolve() = dexSection("emerald_vanilla", NATIVE_EMERALD_RETAIL, EM_ROM, GameKind.EMERALD, 265, DexSection.EVOLVE)
    @Test fun emeraldDexArea() = dexSection("emerald_vanilla", NATIVE_EMERALD_RETAIL, EM_ROM, GameKind.EMERALD, 263, DexSection.AREA)
    @Test fun emeraldDexMoves() = dexSection("emerald_vanilla", NATIVE_EMERALD_RETAIL, EM_ROM, GameKind.EMERALD, 252, DexSection.MOVES)
    @Test fun soulGoldDexEvolve() = dexSection("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, 133, DexSection.EVOLVE)
    @Test fun soulGoldDexEvolveGligar() = dexSection("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, 207, DexSection.EVOLVE)
    @Test fun soulGoldDexArea() = dexSection("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, 19, DexSection.AREA)
    @Test fun soulGoldDexMoves() = dexSection("soulgold", NATIVE_SOULGOLD, SG_ROM, GameKind.SOULGOLD, 152, DexSection.MOVES)
    @Test fun radicalRedDexEvolve() = dexSection("radical_red_dex", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", GameKind.RADICAL_RED, 133, DexSection.EVOLVE)
    @Test fun radicalRedDexArea() = dexSection("radical_red_dex", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", GameKind.RADICAL_RED, 16, DexSection.AREA)
    @Test fun radicalRedDexMoves() = dexSection("radical_red_dex", NATIVE_RADICAL_RED_V4_1, "Pokemon Radical Red (v4.1).gba", GameKind.RADICAL_RED, 1, DexSection.MOVES)

    private fun dexSection(fixture: String, cfg: NativeConfig, romFile: String, kind: GameKind, entry: Int, section: DexSection) {
        val rom = RomFileReader.load(RETAIL_ROM_DIR + romFile) ?: return
        activeGame = kind
        amethystV141 = false
        com.pokedaisy.app.companion.data.fireRedHack = com.pokedaisy.app.companion.data.fireRedHackFor(cfg)
        com.pokedaisy.app.companion.data.emeraldHack = com.pokedaisy.app.companion.data.emeraldHackFor(cfg)
        romLanguage = cfg.language
        romGameCode = cfg.gameCode
        val t = cfg.pokedex!!
        val g = cfg.guideTables
        PokedexSource.reader = rom
        DecompIconSource.reader = rom
        DecompIconSource.tables = cfg.iconTables
        val dex = readPokedexState(rom.withRam(FixtureMemoryReader.load(fixture)), cfg, t)!!
        PokedexSource.regionalOrder(t)
        val species = PokedexSource.speciesFor(t, entry)
        PokedexSource.entry(t, entry); PokedexSource.frontSprite(t, species); PokedexSource.footprint(t, species)
        // Paparazzi draws one frame: everything the section shows, loaded up front.
        DexDetails.family(t, species)?.forEach { DecompIconSource.get(it.from); DecompIconSource.get(it.to) }
        g?.let { DexDetails.catchSpots(it, species) }
        DexDetails.levelUp(t, g, species); DexDetails.teachable(t, g, species)
        snapshot {
            AppTheme {
                val ui = DexUiState(androidx.compose.foundation.lazy.rememberLazyListState())
                ui.open = entry
                ui.section = section
                PokedexEntryScreen(dex, ui, entry, guide = g)
            }
        }
    }

    @Test fun map() = tab("MAP")
    @Test fun items() = tab("ITEMS")

    /** A USE that went through: the popup over the bag, with the item and a tick. */
    @Test fun itemsUsedNotice() = snapshot {
        val items = listOf(ItemView(13, "Potion", 2, spriteAsset("items", 13)), ItemView(86, "Repel", 3, spriteAsset("items", 86)))
        AppTheme {
            GameBackdrop(GameKind.FIRERED)
            ItemsScreen(items, androidx.compose.ui.Modifier.fillMaxSize(), itemUse = PartyItemUse(emptyList()), initialNotice = ItemNotice(13, "Used on CHARMANDER."))
        }
    }

    /** FLY (Fly.kt): PIDGEY knows it, so the MAP tab gets the button... */
    private val flyer = SampleCompanion.snapshot.let { s ->
        s.copy(party = s.party.mapIndexed { i, m -> if (i == 1) m.copy(moves = m.moves + MoveView("Fly", "Flying", 15, 70, id = 19)) else m })
    }
    private val flyAction = object : com.pokedaisy.app.companion.FlyAction {
        override fun canFly() = true
        override fun targets(done: (com.pokedaisy.app.companion.data.FlyTargets) -> Unit) {}
        override fun fly(mapsec: Int, targets: com.pokedaisy.app.companion.data.FlyTargets, done: (com.pokedaisy.app.companion.data.FlyResult) -> Unit) {}
    }

    @Test fun mapFly() = snapshot {
        CompanionScreen(flyer, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "MAP", fly = flyAction)
    }

    /** ... which lists the towns visited (PALLET, VIRIDIAN, PEWTER, CERULEAN), the flyer by the title. */
    @Test fun mapFlyList() = snapshot {
        val t = com.pokedaisy.app.companion.data.FlyTargets(com.pokedaisy.app.companion.data.FlyResult.OK, listOf(0x58, 0x59, 0x5A, 0x5B), 1, 0x222)
        AppTheme { GameBackdrop(GameKind.FIRERED); MapScreen(flyer, androidx.compose.ui.Modifier.fillMaxSize(), fly = flyAction, initialFly = t) }
    }

    /** A Repel's description with USE (FieldItems.kt). */
    @Test fun itemsRepelUse() = snapshot {
        activeGame = GameKind.EMERALD
        val use = object : com.pokedaisy.app.companion.ItemUse {
            override fun canUse(itemId: Int) = itemId in com.pokedaisy.app.companion.data.REPEL_ITEMS
            override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {}
        }
        val items = listOf(
            ItemView(86, "Repel", 3, spriteAsset("items", 86), description = "Prevents weak wild POKéMON from appearing for 100 steps."),
            ItemView(13, "Potion", 2, spriteAsset("items", 13)),
        )
        AppTheme { ItemsScreen(items, androidx.compose.ui.Modifier.fillMaxSize(), itemUse = use, initialItemId = 86) }
    }

    /** FireRed rev 1's USE / GIVE (FieldItems.kt) as the app answers them, with the party pick's slots fixed. */
    private class PartyItemUse(
        val targets: List<com.pokedaisy.app.companion.data.PartyTarget>,
        val result: com.pokedaisy.app.companion.data.ItemUseResult = com.pokedaisy.app.companion.data.ItemUseResult.USED,
    ) : com.pokedaisy.app.companion.ItemUse {
        // LEFTOVERS, EXP. SHARE, POTION, ESCAPE ROPE, POKé BALL hold; key items and TMs don't.
        val support = com.pokedaisy.app.companion.data.FieldItemSupport(
            com.pokedaisy.app.companion.data.FIELD_ITEMS_FIRERED_REV1, setOf(200, 182, 13, 85, 4, 34),
        )
        override fun canUse(itemId: Int) = support.canUse(itemId)
        override fun canGive(itemId: Int) = support.canGive(itemId)
        override fun marked(itemId: Int) = support.marked(itemId)
        override fun picksMon(itemId: Int) = support.picksMon(itemId)
        override fun picksMove(itemId: Int) = support.picksMove(itemId)
        override fun use(itemId: Int, done: (com.pokedaisy.app.companion.data.ItemUseOutcome) -> Unit) {}
        override fun targets(itemId: Int, give: Boolean, done: (com.pokedaisy.app.companion.data.PartyTargets) -> Unit) =
            done(com.pokedaisy.app.companion.data.PartyTargets(result, targets))
    }

    /** A party for the pick: hurt, poisoned, an egg, fainted, full, and two holding items. */
    private val pickParty get() = SampleCompanion.snapshot.party.mapIndexed { i, m -> m.copy(personality = i + 1L) } + listOf(
        MonView(0, "EGG", 0, 0, 0, "", emptyList(), null, isEgg = true, personality = 3),
        MonView(25, "PIKACHU", 14, 0, 38, "", listOf("Electric"), null, GENDER_SYMBOL_MALE, personality = 4),
        MonView(7, "SQUIRTLE", 13, 37, 37, "", listOf("Water"), null, GENDER_SYMBOL_FEMALE, personality = 5,
            moves = listOf(MoveView("Tackle", "Normal", 35, 35), MoveView("Bubble", "Water", 2, 20), MoveView("Withdraw", "Water", 40, 0))),
        MonView(10, "CATERPIE", 6, 20, 20, "", listOf("Bug"), null, GENDER_SYMBOL_MALE, personality = 6),
    ).map { it.copy(iconAsset = spriteAsset("pokemon", if (it.isEgg) 412 else it.species)) }

    private fun pickTargets(vararg v: SlotVerdict, held: List<Int> = List(6) { 0 }) =
        v.mapIndexed { i, verdict -> com.pokedaisy.app.companion.data.PartyTarget(i, pickParty[i].personality, held[i], verdict, moves = listOf(false, true, false, false)) }

    /** The bag with the row marks: a mark and a star (POTION), a mark (LEFTOVERS, a holdable REPEL), a star alone
     * (BICYCLE, nothing to do with it) and neither (OLD ROD). */
    @Test fun itemsMarks() {
        com.pokedaisy.app.companion.FavoriteItems.load(setOf(13, 360)) {}
        snapshot { itemsMarksScreen() }
        com.pokedaisy.app.companion.FavoriteItems.load(emptySet()) {}
    }

    @androidx.compose.runtime.Composable
    private fun itemsMarksScreen() {
        val items = listOf(
            ItemView(13, "Potion", 3, spriteAsset("items", 13), POCKET_ITEMS),
            ItemView(200, "Leftovers", 1, spriteAsset("items", 200), POCKET_ITEMS),
            ItemView(86, "Repel", 4, spriteAsset("items", 86), POCKET_ITEMS),
            ItemView(68, "Rare Candy", 2, spriteAsset("items", 68), POCKET_ITEMS),
            ItemView(4, "Poké Ball", 10, spriteAsset("items", 4), POCKET_POKE_BALLS),
            ItemView(360, "Bicycle", 1, spriteAsset("items", 360), POCKET_KEY_ITEMS),
            ItemView(262, "Old Rod", 1, spriteAsset("items", 262), POCKET_KEY_ITEMS),
        )
        AppTheme { GameBackdrop(GameKind.FIRERED); ItemsScreen(items, androidx.compose.ui.Modifier.fillMaxSize(), itemUse = PartyItemUse(emptyList()), initialItemId = 13) }
    }

    /** USE on a POTION: who it would help lit (CHARMANDER and PIDGEY below full HP), the egg, the fainted PIKACHU
     * and the full ones greyed; held items in the corner. */
    @Test fun itemsUsePick() = snapshot {
        pickParty.forEach { DecompIconSource.get(it.species) }
        val use = PartyItemUse(pickTargets(SlotVerdict.OK, SlotVerdict.OK, SlotVerdict.EGG, SlotVerdict.NO_EFFECT, SlotVerdict.NO_EFFECT, SlotVerdict.NO_EFFECT, held = listOf(0, 183, 0, 0, 200, 0)))
        val items = listOf(ItemView(13, "Potion", 3, spriteAsset("items", 13), POCKET_ITEMS, "Restores the HP of a POKéMON by 20 points."))
        AppTheme {
            GameBackdrop(GameKind.FIRERED)
            ItemsScreen(items, androidx.compose.ui.Modifier.fillMaxSize(), itemUse = use, initialItemId = 13, party = pickParty, initialPick = false)
        }
    }

    /** GIVE a LEFTOVERS: the egg greyed, and the Pokémon holding LEFTOVERS already. */
    @Test fun itemsGivePick() = snapshot {
        pickParty.forEach { DecompIconSource.get(it.species) }
        val use = PartyItemUse(pickTargets(SlotVerdict.OK, SlotVerdict.OK, SlotVerdict.EGG, SlotVerdict.OK, SlotVerdict.HOLDS_IT, SlotVerdict.OK, held = listOf(0, 183, 0, 0, 200, 0)))
        val items = listOf(ItemView(200, "Leftovers", 1, spriteAsset("items", 200), POCKET_ITEMS, "An item to be held by a POKéMON."))
        AppTheme {
            GameBackdrop(GameKind.FIRERED)
            ItemsScreen(items, androidx.compose.ui.Modifier.fillMaxSize(), itemUse = use, initialItemId = 200, party = pickParty, initialPick = true)
        }
    }

    /** An ETHER's move pick on SQUIRTLE: only BUBBLE (2 PP) would get any back. */
    @Test fun itemsMovePick() = snapshot {
        pickParty.forEach { DecompIconSource.get(it.species) }
        val pick = ItemPick(
            ItemView(34, "Ether", 1, spriteAsset("items", 34)), give = false,
            pickTargets(SlotVerdict.NO_EFFECT, SlotVerdict.NO_EFFECT, SlotVerdict.EGG, SlotVerdict.NO_EFFECT, SlotVerdict.OK, SlotVerdict.NO_EFFECT),
        )
        AppTheme {
            GameBackdrop(GameKind.FIRERED)
            ItemPartyPicker(pick, pickParty, rememberGbaTextMetrics(), itemName = "Ether", picksMove = true, onCancel = {}, onPick = { _, _ -> }, initialMoveSlot = 4)
        }
    }

    /** USE tapped mid-battle: the description bar says NOT NOW, as the Repel's does. */
    @Test fun itemsUseNotNow() = snapshot {
        val use = PartyItemUse(emptyList(), com.pokedaisy.app.companion.data.ItemUseResult.NOT_NOW)
        val items = listOf(ItemView(13, "Potion", 3, spriteAsset("items", 13), POCKET_ITEMS, "Restores the HP of a POKéMON by 20 points."))
        AppTheme {
            GameBackdrop(GameKind.FIRERED)
            ItemsScreen(items, androidx.compose.ui.Modifier.fillMaxSize(), itemUse = use, initialItemId = 13, party = pickParty, initialPick = false)
        }
    }

    /** Two stacks of one item (99 Repels and 5 more): LazyColumn used to throw on the repeated key. */
    @Test fun itemsDuplicateStacks() = snapshot {
        val stacks = listOf(
            ItemView(86, "Repel", 99, null, POCKET_ITEMS), ItemView(86, "Repel", 5, null, POCKET_ITEMS),
            ItemView(4, "Poké Ball", 99, null, POCKET_POKE_BALLS), ItemView(4, "Poké Ball", 23, null, POCKET_POKE_BALLS),
        )
        CompanionScreen(
            SampleCompanion.snapshot.copy(items = stacks + SampleCompanion.snapshot.items),
            SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "ITEMS",
        )
    }
    @Test fun states() = tab("STATES")
    @Test fun settings() = tab("SETTINGS")

    /** SETTINGS > CHEATS: the master switch and a game's three cheats, then a game with none. */
    @Test fun settingsCheats() = snapshot {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(cheatList = SampleCompanion.cheats), initialTab = "SETTINGS", initialSettingsPage = "CHEATS")
    }

    /** SETTINGS > SHADERS: FILTER, GRID, SMOOTHING, GBA COLORS, ON COMPANION. */
    @Test fun settingsShaders() = snapshot {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "SETTINGS", initialSettingsPage = "SHADERS")
    }

    @Test fun settingsCheatsEmpty() = snapshot {
        CompanionScreen(SampleCompanion.snapshot, SampleCompanion.Slots, SampleCompanion.Settings(), initialTab = "SETTINGS", initialSettingsPage = "CHEATS")
    }

    @Test
    fun optionSelector() = snapshot {
        AppTheme {
            val m = rememberGbaTextMetrics()
            OptionSelector("FF SPEED", listOf("INFINITE", "2×", "3×", "4×", "6×"), "4×", { it }, m, onPick = {}, onDismiss = {})
        }
    }

    @Test
    fun optionConfirm() = snapshot {
        AppTheme {
            val m = rememberGbaTextMetrics()
            OptionConfirm("CLOSE GAME?", "Returns to the ROM list. Progress is saved automatically.", "CLOSE", m, onConfirm = {}, onDismiss = {})
        }
    }
}

/** Fake companion inputs for screenshots / previews. */
private const val LG_ROM = "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba"
private const val RUBY_ROM = "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba"
private const val RR_ROM = "Pokemon Radical Red (v4.1).gba"
private const val SG_ROM = "Pokemon SoulGold (v1.1.4).gba"
private const val SG12_ROM = "Pokemon SoulGold (v1.2).gba"
private const val SG12B_ROM = "Pokemon SoulGold (v1.2) (Alt 1).gba"
private const val SG121_ROM = "Pokemon SoulGold (v1.2.1).gba"
private const val AM141_ROM = "Pokemon Amethyst (v1.4.1).gba"
// The other-language ROMs: "Pokemon Multi Language" beside the retail dir, a folder per game.
private const val ML = "../Pokemon Multi Language/"
private const val EM_ES_ROM = ML + "Pokemon - Edicion Esmeralda (Spain)/Pokemon - Edicion Esmeralda (Spain).gba"
private const val EM_DE_ROM = ML + "Pokemon - Smaragd-Edition (Germany)/Pokemon - Smaragd-Edition (Germany).gba"
private const val EM_FR_ROM = ML + "Pokemon - Version Emeraude (France)/Pokemon - Version Emeraude (France).gba"
private const val EM_IT_ROM = ML + "Pokemon - Versione Smeraldo (Italy)/Pokemon - Versione Smeraldo (Italy).gba"
private const val EM_JA_ROM = ML + "Pocket Monsters - Emerald (Japan)/Pocket Monsters - Emerald (Japan).gba"
private const val ML_FR_DE = ML + "Pokemon - Feuerrote Edition (Germany)/Pokemon - Feuerrote Edition (Germany).gba"
private const val ML_LG_ES = ML + "Pokemon - Edicion Verde Hoja (Spain)/Pokemon - Edicion Verde Hoja (Spain).gba"
private const val ML_SA_FR = ML + "Pokemon - Version Saphir (France) (Rev 1)/Pokemon - Version Saphir (France) (Rev 1).gba"
private const val ML_FR_JA = ML + "Pocket Monsters - FireRed (Japan) (Rev 1)/Pocket Monsters - FireRed (Japan) (Rev 1).gba"
private const val ML_RU_JA = ML + "Pocket Monsters - Ruby (Japan) (Rev 1)/Pocket Monsters - Ruby (Japan) (Rev 1).gba"
private fun port(key: String) = com.pokedaisy.app.companion.data.RETAIL_PORTS.getValue(key)()
private const val SEAGLASS_ROM = "Pokemon Emerald Seaglass (v3.0).gba"
private const val LAZARUS_ROM = "Pokemon Lazarus (v2.0).gba"
private const val TMT2_ROM = "Pokemon Too Many Types 2 (v1.5.2).gba"
private const val GLAZED_ROM = "Pokemon Glazed (v9.2.0).gba"
private const val IMPERIUM_ROM = "Pokemon Emerald Imperium (v1.3.1).gba"
private const val QUETZAL_ROM = "Pokemon Quetzal (Alpha 9.0) (English).gba"
private val RE228 = com.pokedaisy.app.companion.data.NATIVE_RECHARGED_EMERALD_V2_2_8
private const val RCE_ROM = "Pokemon Recharged Emerald (v2.2.8).gba"
private const val QUETZAL_ES_ROM = "Pokemon Quetzal (Alpha 9.0) (Spanish).gba"
private const val QUETZAL_084_ROM = "Pokemon Quetzal (Alpha 0.8.4) (English).gba"
private const val QUETZAL_ES_084_ROM = "Pokemon Quetzal (Alpha 0.8.4) (Spanish).gba"
private const val OI_ROM = "Pokemon Orange Islands.gba"
private const val UV_ROM = "Pokemon Ultra Violet (v1.22).gba"
private const val SS_ROM = "Pokemon Sword and Shield Ultimate Plus (v1.2.1.2).gba"
private const val GSC_ROM = "Pokemon GS Chronicles (v2.7.6).gba"
private val NATIVE_GSC get() = com.pokedaisy.app.companion.data.NATIVE_GS_CHRONICLES
private val NATIVE_UV get() = com.pokedaisy.app.companion.data.NATIVE_ULTRA_VIOLET
private const val RE_ROM = "Pokemon FireRed Rocket Edition.gba"
private const val AG_ROM = "Pokemon Ash Gray (v4.5.3).gba"
private val NATIVE_RE get() = com.pokedaisy.app.companion.data.NATIVE_ROCKET_EDITION
private val NATIVE_AG get() = com.pokedaisy.app.companion.data.NATIVE_ASH_GRAY
private const val GG_ROM = "Pokemon Ghost Grey (v1.0.4).gba"
private const val ER_ROM = "Pokemon Elite Redux (2.65.4.1b).gba"
private const val TR_ROM = "Pokemon Team Rocket Dragonsden Version.gba"
private const val DSM_ROM = "Pokemon Dreamstone Mysteries.gba"
private val NATIVE_DSM get() = com.pokedaisy.app.companion.data.NATIVE_DREAMSTONE
private val NATIVE_TR get() = com.pokedaisy.app.companion.data.NATIVE_TEAM_ROCKET
private const val WOT_ROM = "Pokemon Wishes of Tomorrow (v1.2.6).gba"
private val NATIVE_WOT get() = com.pokedaisy.app.companion.data.NATIVE_WISHES
private val NATIVE_ER get() = com.pokedaisy.app.companion.data.NATIVE_ELITE_REDUX_V2_65_4_1B
private val NATIVE_GG get() = com.pokedaisy.app.companion.data.NATIVE_GHOST_GREY
private val NATIVE_SS get() = com.pokedaisy.app.companion.data.NATIVE_SWORD_SHIELD
private const val RY_ROM = "Pokemon Recharged Yellow (v1.9.7).gba"
private const val RY180_ROM = "Pokemon Recharged Yellow (v1.8.0).gba"
private const val UB_FR_ROM = "Pokemon Unbound (v2.1.1.1) (French).gba"
private const val ROWE_ROM = "Pokemon R.O.W.E. (v2.1.9.1 Experimental).gba"
private const val MARIOMON_ROM = "Super Mariomon (v1.5.1 Anniversary).gba"
private const val MARIOMON_V13_ROM = "Super Mariomon (v1.3).gba"
private const val SAPPHIRE_ROM = "Pokemon - Sapphire Version (USA, Europe) (Rev 1).gba"
private const val HNS_ROM = "Pokemon Heart and Soul (v2.0.6).gba"

object SampleCompanion {
    val snapshot = SnapshotView(
        connected = true, facingDirection = "down", x = 12, y = 7, game = GameKind.FIRERED,
        location = LocationView("PALLET TOWN", "kanto", 64, 88, 8, 8),
        party = listOf(
            MonView(
                4, "CHARMANDER", 12, 30, 35, "", listOf("Fire"), null, GENDER_SYMBOL_MALE,
                moves = listOf(
                    MoveView("Scratch", "Normal", 35, 40), MoveView("Growl", "Normal", 40, 0),
                    MoveView("Ember", "Fire", 25, 40), MoveView("Metal Claw", "Steel", 35, 50),
                ),
                weaknesses = listOf(TypeMatchup("Water", "2x", 200), TypeMatchup("Ground", "2x", 200), TypeMatchup("Rock", "2x", 200)),
                resistances = listOf(
                    TypeMatchup("Fire", "½x", 50), TypeMatchup("Grass", "½x", 50), TypeMatchup("Ice", "½x", 50),
                    TypeMatchup("Bug", "½x", 50), TypeMatchup("Steel", "½x", 50),
                ),
                // Adamant; the stats are what these IVs / EVs give at Lv 12.
                stats = com.pokedaisy.app.companion.data.MonStats(
                    ivs = listOf(28, 31, 9, 28, 17, 30), evs = listOf(12, 40, 6, 30, 18, 4), nature = 3,
                    stats = listOf(35, 24, 16, 24, 18, 20),
                ),
            ),
            MonView(
                16, "PIDGEY", 9, 5, 26, "PSN", listOf("Normal", "Flying"), null, GENDER_SYMBOL_FEMALE,
                moves = listOf(MoveView("Tackle", "Normal", 35, 35), MoveView("Sand-Attack", "Ground", 15, 0), MoveView("Gust", "Flying", 35, 40)),
                weaknesses = listOf(TypeMatchup("Electric", "2x", 200), TypeMatchup("Ice", "2x", 200), TypeMatchup("Rock", "2x", 200)),
                resistances = listOf(TypeMatchup("Grass", "½x", 50), TypeMatchup("Bug", "½x", 50)),
                immunities = listOf(TypeMatchup("Ground", "0x", 0), TypeMatchup("Ghost", "0x", 0)),
                // Timid, a perfect Speed IV.
                stats = com.pokedaisy.app.companion.data.MonStats(
                    ivs = listOf(5, 12, 20, 31, 3, 18), evs = List(6) { 0 }, nature = 10,
                    stats = listOf(26, 12, 14, 18, 11, 12),
                ),
            ),
        ),
        // ROUTE 24 (MAP_ROUTE24 = 3.43), BROCK's badge, the BICYCLE and OLD ROD in the bag.
        mapGroup = 3, mapNum = 43,
        guideTables = GUIDE_TABLES_FIRERED_REV1,
        progress = SaveProgress(ByteArray(0x120).also { it[0x820 / 8] = 1 }, ByteArray(0x200)),
        items = listOf(
            ItemView(13, "Potion", 3, null, POCKET_ITEMS, "A small spray that heals a little HP (preview text)."),
            ItemView(4, "Poké Ball", 10, null, POCKET_POKE_BALLS),
            ItemView(349, "Oak's Parcel", 1, null, POCKET_KEY_ITEMS),
            ItemView(360, "Bicycle", 1, null, POCKET_KEY_ITEMS),
            ItemView(262, "Old Rod", 1, null, POCKET_KEY_ITEMS),
        ),
        pokedex = PokedexState(
            POKEDEX_FIRERED_REV1,
            seen = (1..25).toSet() + setOf(129, 133, 147, 152),
            caught = setOf(1, 4, 6, 7, 10, 16, 19, 25, 129),
            national = true,
        ),
    )

    class Achievements(
        s: com.pokedaisy.app.companion.AchievementsState,
        page: com.pokedaisy.app.companion.LeaderboardPage? = null,
    ) : com.pokedaisy.app.companion.CompanionAchievements {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(s)
        override val leaderboardPage = kotlinx.coroutines.flow.MutableStateFlow(page)
        override val popups = kotlinx.coroutines.flow.emptyFlow<com.pokedaisy.app.companion.AchievementPopup>()
        override fun retry() {}
    }

    val achievementsState = run {
        fun a(id: Int, title: String, desc: String, pts: Int, unlocked: Boolean, section: String, progress: String = "", pct: Float = 0f) =
            com.pokedaisy.app.companion.Achievement(id, title, desc, pts, unlocked, section, null, null, progress, pct)
        val list = listOf(
            a(1, "Boulder Badge", "Defeat Brock in Pewter City", 5, true, "RECENTLY UNLOCKED"),
            a(2, "Gotta Catch 'Em", "Register 50 Pokémon in the Pokédex", 10, false, "ALMOST THERE", "38/50", 76f),
            a(3, "Cascade Badge", "Defeat Misty in Cerulean City", 5, false, "LOCKED"),
            a(4, "Snorlax Awakens", "Wake up a Snorlax with the Poké Flute and catch it", 10, false, "LOCKED"),
            a(5, "The Champion", "Defeat the Pokémon League Champion and enter the Hall of Fame", 25, false, "LOCKED"),
            a(6, "Starter", "Choose your first Pokémon", 1, true, "UNLOCKED"),
            a(7, "Rival Beaten", "Win your first battle against your rival", 3, true, "UNLOCKED"),
        )
        com.pokedaisy.app.companion.AchievementsState(
            user = "Lidor",
            game = com.pokedaisy.app.companion.AchievementGame(515, "Pokemon FireRed Version", "51901a6e40661b3914aa333c802e24e8", 60, 3, 600, 9),
            achievements = list,
            leaderboards = listOf(
                com.pokedaisy.app.companion.Leaderboard(11, "Elite Four Speedrun", "Fastest time from entering the League to the Hall of Fame", "POKEMON FIRERED VERSION", true),
                com.pokedaisy.app.companion.Leaderboard(12, "Fewest Steps to Pewter", "Reach Pewter City in the fewest steps", "POKEMON FIRERED VERSION", true),
                com.pokedaisy.app.companion.Leaderboard(13, "Safari Zone Haul", "Most Pokemon caught in one Safari Zone visit", "BONUS", false),
            ),
        )
    }

    val leaderboardPage = run {
        fun e(rank: Int, user: String, score: String, me: Boolean = false) = com.pokedaisy.app.companion.LeaderboardEntry(rank, user, score, me)
        com.pokedaisy.app.companion.LeaderboardPage(
            id = 11, loading = false, total = 214,
            top = listOf(e(1, "Speedy", "12:04.33"), e(2, "AshK", "12:58.10"), e(3, "MistyFan", "13:21.47"), e(4, "Gary0ak", "14:02.95"), e(5, "Brock", "14:40.12")),
            nearMe = listOf(e(16, "Nurse_Joy", "15:11.68"), e(17, "Lidor", "15:30.02", me = true), e(18, "Oddish", "15:44.90")),
        )
    }

    object Slots : StateSlots {
        override fun list() = (0..9).map { StateSlots.Slot(it, false, 0L, null) }
        override val currentIndex = 1
        override fun requestSave(index: Int) {}
        override fun requestLoad(index: Int) {}
        override fun requestUndoSave() {}
        override fun requestUndoLoad() {}
    }

    /** A typed code and two from a RetroArch .cht. */
    val cheats = listOf(
        com.pokedaisy.app.cheats.Cheat("INFINITE MONEY", listOf("82025838 FFFF"), "", true),
        com.pokedaisy.app.cheats.Cheat("Master Code (must be on)", listOf("000014D1 000A", "1003DBB8 0007"), "", false),
        com.pokedaisy.app.cheats.Cheat("Wild Pokemon are always shiny", listOf("12345678 9ABCDEF0"), "GSAv1", false),
    )

    class Settings(
        private var tabs: List<String> = com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS,
        private var cheatList: List<com.pokedaisy.app.cheats.Cheat> = emptyList(),
    ) : CompanionSettings {
        override val cheats get() = cheatList
        override val cheatsEnabled get() = cheatList.isNotEmpty()
        override val companionTabs get() = tabs
        override fun setCompanionTabs(tabs: List<String>) { this.tabs = tabs }
        var noticeAccepted = true
        override fun guideNoticeAccepted(game: String) = noticeAccepted
        override fun acceptGuideNotice(game: String) { noticeAccepted = true }
        override val ffMaxSpeed = 4f
        override fun setFfMaxSpeed(v: Float) {}
        override val ffToggled = false
        override fun setFfToggled(on: Boolean) {}
        override val ffMusicMode = com.pokedaisy.app.companion.FfMusicMode.STEADY
        override fun setFfMusicMode(mode: com.pokedaisy.app.companion.FfMusicMode) {}
        override val ffMode = com.pokedaisy.app.companion.FfMode.SMART
        override fun setFfMode(mode: com.pokedaisy.app.companion.FfMode) {}
        override val touchStyle = com.pokedaisy.app.companion.TouchControlsStyle()
        override fun setTouchStyle(style: com.pokedaisy.app.companion.TouchControlsStyle) {}
        override fun gbaControlBindings() = GbaControls.Btn.entries.associateWith {
        listOf(when (it) { GbaControls.Btn.X, GbaControls.Btn.START_2, GbaControls.Btn.TO_COMPANION -> "BUTTON_X"; GbaControls.Btn.Y, GbaControls.Btn.SELECT_2 -> "BUTTON_Y"; else -> "BUTTON_${it.name}" })
    }
        override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {}
        override val hotkeysEnabled = true
        override fun setHotkeysEnabled(on: Boolean) {}
        override fun hotkeyBindings() = Hotkeys.Action.entries.associateWith { listOf("BUTTON_Y") }
        override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {}
        override fun restartGame() {}
        override fun closeGame() {}
        override val showHints = true
        override fun setShowHints(on: Boolean) {}
        override val clickSound = true
        override fun setClickSound(on: Boolean) {}
        override val statusBar = false
        override fun setStatusBar(on: Boolean) {}
        override val gameAspect = com.pokedaisy.app.companion.GameAspect.ORIGINAL
        override fun setGameAspect(aspect: com.pokedaisy.app.companion.GameAspect) {}
        override val gbaColors = false
        override fun setGbaColors(on: Boolean) {}
        override val screenFilter = com.pokedaisy.app.companion.ScreenFilter.NONE
        override fun setScreenFilter(filter: com.pokedaisy.app.companion.ScreenFilter) {}
        override val gridStrength = com.pokedaisy.app.companion.GridStrength.MEDIUM
        override fun setGridStrength(strength: com.pokedaisy.app.companion.GridStrength) {}
        override val smoothing = com.pokedaisy.app.companion.Smoothing.OFF
        override fun setSmoothing(mode: com.pokedaisy.app.companion.Smoothing) {}
        override val companionShaders = true
        override fun setCompanionShaders(on: Boolean) {}
        override val gameName = "Pokémon FireRed"
        override val romFileName = "firered-hack.gba"
    }
}
private const val FR_ROM = "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"
private const val EM_ROM = "Pokemon - Emerald Version (USA, Europe).gba"
