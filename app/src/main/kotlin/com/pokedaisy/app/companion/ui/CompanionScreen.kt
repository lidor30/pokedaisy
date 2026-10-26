package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokedaisy.app.companion.data.BATTLE_INPUT_ACTION_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_BAG_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_MOVE_SELECT
import com.pokedaisy.app.companion.data.BATTLE_INPUT_PARTY_OPEN
import com.pokedaisy.app.companion.data.BATTLE_INPUT_TARGET_SELECT
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.SnapshotView
import com.pokedaisy.app.companion.data.showsBattle
import com.pokedaisy.app.companion.data.hasData
import com.pokedaisy.app.companion.AchievementPopup
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.withMovesVs
import com.pokedaisy.app.companion.COMPANION_TABS
import com.pokedaisy.app.companion.DEFAULT_COMPANION_TABS
import com.pokedaisy.app.companion.MAX_BAR_TABS
import com.pokedaisy.app.companion.ui.theme.AppColors
import com.pokedaisy.app.companion.ui.theme.AppTheme
import com.pokedaisy.app.companion.ui.theme.LocalGameFont
import com.pokedaisy.app.companion.ui.theme.LocalGameTextScale
import com.pokedaisy.app.companion.ui.theme.LocalGameFontWidth
import com.pokedaisy.app.companion.ui.theme.gameFontWidth
import com.pokedaisy.app.companion.ui.theme.gameTextScale
import com.pokedaisy.app.companion.ui.theme.rememberGameFont
import com.pokedaisy.app.companion.ui.theme.gbaFocusRing
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.focusProperties

/** The whole bottom-screen companion, driven by a single [SnapshotView]. */
@Composable
fun CompanionScreen(
    snapshot: SnapshotView,
    slots: com.pokedaisy.app.companion.StateSlots? = null,
    settings: com.pokedaisy.app.companion.CompanionSettings? = null,
    battleInput: com.pokedaisy.app.companion.BattleInput? = null,
    /** Tab shown first ("PARTY", "DEX", "MAP", "ITEMS", "CARD", "STATES", "SETTINGS", "MORE") - for
     * screenshot tests, which can't tap the tab bar. */
    initialTab: String = "PARTY",
    /** Party index whose summary is open on the PARTY tab - for screenshot tests too. */
    initialMonIndex: Int? = null,
    /** The CARD tab opens on the card's back - for screenshot tests too. */
    initialCardBack: Boolean = false,
    /** SETTINGS opens on this sub-page ("CHEATS") - for screenshot tests, which can't tap. */
    initialSettingsPage: String? = null,
    /** SETTINGS' row to open on (its English label): the cursor on it, scrolled to - SWAP SCREENS after a swap. */
    initialSettingsRow: String? = null,
    /** The device's BACK, fed by the game's activity (see [CompanionBack]). */
    back: CompanionBack? = null,
    /** The game's buttons while the companion is a window over the game (see [CompanionKeys]): L / R step tabs. */
    keys: CompanionKeys? = null,
    /** Plays the game's click, for every companion button (see [LocalClickSound]). */
    clickSound: () -> Unit = {},
    /** RetroAchievements: the ACHIEVEMENTS tab and the unlock popups. Null = no such tab. */
    achievements: com.pokedaisy.app.companion.CompanionAchievements? = null,
    /** A popup shown from the start, without timers - for screenshot tests. */
    initialPopup: com.pokedaisy.app.companion.AchievementPopup? = null,
    /** The game's status bar ([GameStatusBar]), drawn over the tabs while [CompanionStatusBar.shown]. */
    statusBar: (@Composable () -> Unit)? = null,
    /** Always over the tabs, above [statusBar]. */
    topStrip: (@Composable () -> Unit)? = null,
    /** USE on the ITEMS tab (a Repel on the field); null = no such button. */
    itemUse: com.pokedaisy.app.companion.ItemUse? = null,
    /** FLY on the MAP tab (Fly.kt); null = no button. */
    fly: com.pokedaisy.app.companion.FlyAction? = null,
    /** NOT SUPPORTED's ASK FOR SUPPORT (a game request with the ROM's name and SHA-1); null hides it. */
    askForSupport: (() -> Unit)? = null,
    /** WHICH GAME IS THIS? ([com.pokedaisy.app.companion.data.BestEffort]): the player's answers; null = no picker. */
    gamePick: com.pokedaisy.app.companion.GamePickActions? = null,
    /** The match notice's SHARE (the player's yes): sends exactly what it lists. Null = no ask. */
    shareBestEffort: ((com.pokedaisy.app.companion.data.BestEffortReport) -> Unit)? = null,
) {
    // Everything below is keyed on the snapshot's game: the per-game look
    // (backdrops, party slots, bag) reads the plain `activeGame` global, which
    // Compose can't observe - without the key a freshly launched game kept the
    // previous game's backdrop until something else happened to recompose.
    val gameFont = rememberGameFont(snapshot.game)
    // The tab picked stays picked through the key below: the game being detected
    // (null -> its kind, a few seconds into a launch) once reset it to PARTY under a
    // player who had already opened SETTINGS. Tracked by label, not index: the BATTLE
    // tab appears/disappears and would otherwise shift every other tab's index.
    var selectedLabel by remember { mutableStateOf(initialTab) }
    // Where BACK from a tab opened from MORE (or the GUIDE notice's GO BACK) returns.
    var backTab by remember { mutableStateOf("PARTY") }
    // SETTINGS' page / row / scroll, kept while other tabs are open.
    val settingsUi = remember { SettingsUiState(initialSettingsPage, initialSettingsRow) }
    // A tapped achievement popup: the achievement to open ACHIEVEMENTS on, and the tab BACK returns to.
    var achievementFocus by remember { mutableStateOf<AchievementFocus?>(null) }
    var popupReturn by remember { mutableStateOf<String?>(null) }
    // WHICH GAME IS THIS? over the tabs (WRONG GAME?, PICK AGAIN, SETTINGS > GAME, PICK A GAME).
    var pickerOpen by remember { mutableStateOf(false) }
    val gameSilent = remember(battleInput) { { battleInput?.gameSilent == true } }
    CompanionScreenSize {
    CompositionLocalProvider(
        LocalHgssBag provides remember { HgssBagState() },
        LocalCompanionBack provides back, LocalCompanionKeys provides keys, LocalClickSound provides clickSound, LocalGameSilent provides gameSilent,
        LocalGameFont provides gameFont,
        LocalGameTextScale provides if (gameFont != null) gameTextScale(snapshot.game) else 1f,
        LocalGameFontWidth provides if (gameFont != null) gameFontWidth(snapshot.game) else 1f,
    ) { AppTheme { key(snapshot.game) {
        val game = snapshot.game
        // Until the first real data arrives (the ROM takes a few seconds to
        // boot / be detected) the data tabs show a loading animation.
        val ready = snapshot.hasData
        var loaded by remember { mutableStateOf(ready) }
        LaunchedEffect(ready) { if (ready) loaded = true }
        // The PARTY tab's open summary (MonDetailScreen), by party index so it
        // follows live data; null = the party slots.
        var detailIndex by remember { mutableStateOf(initialMonIndex) }
        // BATTLE tab: touch Controls (Gen 4 style) vs. info-dense Panel — see
        // BattleControlsScreen.kt. Resets to Controls each time the BATTLE tab
        // becomes available (a fresh battle), matching selectedLabel's own reset.
        var battleShowControls by remember { mutableStateOf(true) }
        // BATTLE tab's SUGGESTIONS pane - see SuggestionsScreen.kt. A plain
        // flag rather than another battleInputState-driven sub-screen since
        // it's pure information (ranks the whole party, not just whoever's
        // currently sent out) and isn't gated by whose turn it is the way
        // Controls/Info are.
        var showSuggestions by remember { mutableStateOf(false) }
        // BATTLE INFO's STATS page (FOE IVS on): IVs / EVs of the battlers.
        var showBattleStats by remember { mutableStateOf(false) }
        // BATTLE INFO's FOE TEAM: the party indexes sent out or announced so
        // far this battle, and the slot tapped (null = follow the battle).
        val seenFoes = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
        var selectedFoe by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(snapshot.enemyActive, snapshot.enemyNext) {
            listOf(snapshot.enemyActive, snapshot.enemyNext).filter { it >= 0 && it !in seenFoes }.forEach { seenFoes.add(it) }
            // The trainer picked its next Pokémon: show that one.
            if (snapshot.enemyNext >= 0) selectedFoe = null
        }
        // The foe INFO and SUGGESTIONS are about: the tapped one, else the one
        // about to come in, else the one that's out.
        val shownFoe = selectedFoe ?: snapshot.enemyNext.takeIf { it >= 0 } ?: snapshot.enemyActive
        val shownFoeMon = snapshot.enemyParty.getOrNull(shownFoe)?.takeIf { shownFoe != snapshot.enemyActive }
        // English ids ("SIDE" or "SIDE · STATE"): the panes split it on " · " and
        // translate each half where they draw it.
        val foeHeading = when {
            shownFoeMon == null -> tk("FOE")
            shownFoe == snapshot.enemyNext -> "FOE · " + tk("NEXT")
            shownFoeMon.hp == 0 -> "FOE · " + tk("FAINTED")
            shownFoe in seenFoes -> "FOE · " + tk("RESERVE")
            else -> "FOE · " + tk("UNSEEN")
        }
        val infoFoes = shownFoeMon?.let { listOf(it) } ?: snapshot.battleOpponent
        val infoPlayer = shownFoeMon?.let { foe -> snapshot.battlePlayer.map { it.withMovesVs(foe) } } ?: snapshot.battlePlayer
        // BATTLE FOCUS keeps its own foe state so the normal INFO page is untouched. Any foe that's
        // been out counts as seen, and a foe that's out is shown with its battle stats.
        val battleFocus = settings?.battleFocus == true
        val focusSeen = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
        LaunchedEffect(snapshot.focusEnemyActive, snapshot.focusEnemyNext, snapshot.enemyBattlers) {
            (listOf(snapshot.focusEnemyActive, snapshot.focusEnemyNext) + snapshot.enemyBattlers)
                .filter { it >= 0 && it !in focusSeen }.forEach { focusSeen.add(it) }
        }
        val focusShown = selectedFoe ?: snapshot.focusEnemyNext.takeIf { it >= 0 } ?: snapshot.focusEnemyActive
        val focusBattler = snapshot.enemyBattlers.indexOf(focusShown).takeIf { it >= 0 && focusShown >= 0 }
            ?.let { snapshot.battleOpponent.getOrNull(it) }
        val focusPartyMon = if (focusBattler != null) null
            else snapshot.enemyParty.getOrNull(focusShown)?.takeIf { focusShown != snapshot.focusEnemyActive }
        val focusFoe = focusBattler ?: focusPartyMon ?: snapshot.battleOpponent.firstOrNull()
        val focusHeading = when {
            focusPartyMon == null -> "FOE"
            focusShown == snapshot.focusEnemyNext -> "FOE · NEXT"
            focusPartyMon.hp == 0 -> "FOE · FAINTED"
            focusShown in focusSeen -> "FOE · RESERVE"
            else -> "FOE · UNSEEN"
        }
        // DEX tab: filter / dex / scroll position / open entry, kept across tab switches.
        val dexListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val dexUi = remember { DexUiState(dexListState) }
        val dex = snapshot.pokedex
        // GUIDE tab: page, reveals and scroll position, kept across tab switches.
        val guideUi = remember { GuideUiState() }
        val guide = rememberGuide(game, dex, snapshot.guideTables)
        // The GUIDE's AI notice, once per game; GO BACK returns to [backTab].
        var noticeAccepted by remember { mutableStateOf(game == null || settings?.guideNoticeAccepted(game.name) != false) }

        // The player's pick of tabs (SETTINGS > TAB BAR). Settings aren't
        // Compose state, so the SETTINGS tab reports changes back here.
        var chosenTabs by remember { mutableStateOf(settings?.companionTabs ?: DEFAULT_COMPANION_TABS) }
        // CARD: the save's card data and the card's art from the ROM.
        val card = snapshot.trainerCard
        val cardArt = rememberTrainerCardArt(card?.style)
        // Tabs this game has: DEX needs the ROM's dex tables, GUIDE a guide or evolutions.
        // The launch's saved copy names the ones it had, which wait on LOADING for the live data.
        val cachedTabs = snapshot.cachedTabs.orEmpty()
        val available = COMPANION_TABS.filter { id ->
            when (id) {
                "DEX" -> dex != null || id in cachedTabs
                "GUIDE" -> guide != null || id in cachedTabs
                "CARD" -> card != null && cardArt?.style == card.style || id in cachedTabs
                "ACHIEVEMENTS" -> achievements != null
                else -> true
            }
        }
        // At most MAX_BAR_TABS next to SETTINGS: in battle BATTLE leads the
        // list and takes the last chosen tab's place. (The pick is shared by
        // every game, so one with more of the chosen tabs can overflow too.)
        // No BATTLE tab for a battle that read nothing (a game whose battle memory isn't mapped).
        val battle = snapshot.showsBattle
        val room = if (battle) MAX_BAR_TABS - 1 else MAX_BAR_TABS
        // The chosen tabs this game has, then - while there's room - its others: a game without a DEX or GUIDE still
        // fills the bar. In the tabs' own order.
        val picked = available.filter { it in chosenTabs }.take(room).let { c -> c + available.filter { it !in c }.take(room - c.size) }
        val bar = available.filter { it in picked }
        // The rest open from MORE, left of the gear (still tabs, just without a chip); no MORE chip with none.
        // Tabs go by id ("ITEMS"); the bar shows each game's own word (companionTabLabel).
        val hiddenTabs = available.filter { it !in bar }
        val tabs = buildList {
            if (battle) add("BATTLE")
            addAll(bar)
            if (hiddenTabs.isNotEmpty()) add("MORE")
            // The gear always ends the bar.
            add("SETTINGS")
        }
        // A tab's place along the bar: a MORE one sits where MORE does (the slide's direction, L / R).
        val barIndex = { id: String -> tabs.indexOf(if (id in hiddenTabs) "MORE" else id) }

        // Jump to the battle helper when a battle starts, back to the tab that
        // was open before it when it ends. Not on first composition when out of
        // battle, so [initialTab] sticks.
        var seenInBattle by remember { mutableStateOf<Boolean?>(null) }
        var preBattleTab by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(battle) {
            // TWEAKS > JUMP TO BATTLE off: the BATTLE chip shows up, the open tab stays.
            if ((seenInBattle != null || battle) && CompanionTweaks[CompanionTweaks.Tweak.JUMP_TO_BATTLE]) {
                if (battle) {
                    if (selectedLabel != "BATTLE") preBattleTab = selectedLabel
                    selectedLabel = "BATTLE"
                } else {
                    selectedLabel = preBattleTab ?: "PARTY"
                    preBattleTab = null
                }
                if (battle) battleShowControls = true
                showSuggestions = false; showBattleStats = false
                seenFoes.clear(); selectedFoe = null
                focusSeen.clear()
            }
            seenInBattle = battle
        }
        // Doubles' battler-id-to-position mapping isn't handled yet (see
        // PLAN.md) - touch control is single-battle only for v1.
        // "My turn" = there's something for the player to actually do right
        // now via touch - the action/move menus, or the Bag/Party screen
        // that was opened from them (so BACK stays reachable) - not the
        // busy/animation/opponent's-turn states in between.
        val isMyTurn = when (snapshot.battleInputState) {
            BATTLE_INPUT_ACTION_SELECT, BATTLE_INPUT_MOVE_SELECT,
            BATTLE_INPUT_BAG_OPEN, BATTLE_INPUT_PARTY_OPEN, BATTLE_INPUT_TARGET_SELECT -> true
            else -> false
        }
        // The window over the game (one screen): no battle controls, the game's own menu is right there.
        val canUseControls = battleInput != null && !snapshot.isDoubleBattle && isMyTurn && !LocalCompanionWindow.current
        val showHints = settings?.showHints ?: true
        val showFoeIvs = settings?.showFoeIvs ?: false
        // The move advice (PARTY > LEARN, a TM's party row on ITEMS): the original games for now.
        val learnContext = snapshot.pokedex?.tables
            ?.takeIf { com.pokedaisy.app.companion.data.MoveLearning.available(it, snapshot.guideTables) }
            ?.let { LearnContext(it, snapshot.guideTables, snapshot.items, showHints) }
        // FOE DETAILS: the button's last state, for every battle after.
        var foeDetails by remember { mutableStateOf(settings?.foeDetails ?: false) }
        val current = if (selectedLabel in tabs || selectedLabel in hiddenTabs) selectedLabel else tabs[0]
        // A tab opened from MORE: MORE stays lit.
        val selectedIdx = barIndex(current)
        // BACK from a tab opened from MORE returns there. Registered before
        // the tabs' own handlers, so an open summary / overlay closes first.
        // ACHIEVEMENTS opened by tapping a popup goes back to the tab it was opened over.
        val popupBack = popupReturn?.takeIf { current == "ACHIEVEMENTS" && it != current }
        CompanionBackHandler(enabled = current in hiddenTabs || popupBack != null) {
            if (popupBack != null) {
                selectedLabel = popupBack
                popupReturn = null
                return@CompanionBackHandler
            }
            backTab = current
            selectedLabel = "MORE"; detailIndex = null; showSuggestions = false; showBattleStats = false; dexUi.open = null
        }
        // A chip's tap (PARTY's own included) closes an open summary; BATTLE's also returns to the controls.
        val openTab: (String) -> Unit = { title ->
            popupReturn = null
            if (title != current) backTab = current
            selectedLabel = title; detailIndex = null; showSuggestions = false; showBattleStats = false; dexUi.open = null
            if (title == "BATTLE") battleShowControls = true
        }
        // The window over the game (one screen): L / R step along the bar (round, like the game's own pages), from the gear
        // when a tab opened from MORE is up. Under everything a tab registers, so a page of its own can take them.
        val stepTab: (Int) -> Unit = { step ->
            val usable = tabs.filter { !snapshot.unsupported || it == "ACHIEVEMENTS" || it == "SETTINGS" || it == "MORE" }
            val at = usable.indexOf(tabs.getOrNull(selectedIdx)).coerceAtLeast(0)
            openTab(usable[(at + step).mod(usable.size)])
        }
        CompanionKeyHandler { key, repeat ->
            if (repeat || key != MenuKey.L && key != MenuKey.R) return@CompanionKeyHandler false
            clickSound()
            stepTab(if (key == MenuKey.R) 1 else -1)
            true
        }
        // The window over the game, or the buttons brought down here by X: L / R at the bar's ends, the buttons that
        // step it - slid in from the screen's sides as the buttons arrive, out as they leave.
        val shoulderHints by androidx.compose.animation.core.animateFloatAsState(
            if (LocalCompanionWindow.current || LocalShoulderHints.current) 1f else 0f, tween(SHOULDER_HINT_MS), label = "shoulderHints",
        )

        Box(modifier = Modifier.fillMaxSize().background(AppColors.bgBottom)) {
            // Per-game party-menu background, pixel-scaled to fill - behind
            // every tab (SETTINGS and STATES' OPTION-style windows included),
            // except behind a bag screen with its own backdrop (Emerald),
            // faded in while ITEMS is showing.
            // No game detected yet: the app's own stripes, not a guess.
            // The party backdrop stays put under the bag's, which fades in over it: a
            // crossfade dimmed both halfway and showed the dark fill between them (a
            // flicker on every BAG visit, plainest on Yellow's white on white).
            if (game == null) {
                AppBackdrop()
            } else {
                GameBackdrop(game)
                AnimatedVisibility(
                    visible = current == "ITEMS" && hasItemsBackdrop(game),
                    enter = fadeIn(tween(200)), exit = fadeOut(tween(200)), label = "tab-backdrop",
                ) { ItemsBackdrop(game, Modifier.fillMaxSize()) }
            }

            val tabBar: @Composable () -> Unit = {
                // IntrinsicSize.Min + fillMaxHeight on every chip: the icon-only
                // SETTINGS chip stretches to the text chips' height.
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (shoulderHints > 0f) ShoulderHint("L", shoulderHints) { stepTab(-1) }
                    tabs.forEachIndexed { i, title ->
                        val open = { openTab(title) }
                        if (title == "SETTINGS") {
                            IconTabChip(PixelIcons.gear, tr("Settings"), selectedIdx == i, open)
                        } else if (title == "MORE") {
                            IconTabChip(PixelIcons.more, tr("MORE"), selectedIdx == i, open, Modifier.testTag("tab-MORE"))
                        } else {
                            val usable = !snapshot.unsupported || title == "ACHIEVEMENTS"
                            TabChip(companionTabLabel(title), selectedIdx == i && usable, Modifier.weight(1f).testTag("tab-$title"), enabled = usable, open)
                        }
                    }
                    if (shoulderHints > 0f) ShoulderHint("R", shoulderHints) { stepTab(1) }
                }
            }
            // A phone upright with no room above the companion: its grip sits in here, over the backdrop.
            val topInset = with(LocalDensity.current) { LocalCompanionTopInset.current.toDp() }
            // A short screen: less margin round the tabs, every pixel of its height counts.
            Column(modifier = Modifier.fillMaxSize().padding(top = topInset).padding(if (LocalShortCompanion.current) 8.dp else 12.dp)) {
                if (topStrip != null) {
                    topStrip()
                    Spacer(Modifier.height(8.dp))
                }
                // SETTINGS > STATUS BAR on the companion: the game's strip over the tabs.
                if (statusBar != null && CompanionStatusBar.shown) {
                    statusBar()
                    Spacer(Modifier.height(8.dp))
                }
                if (loaded && !snapshot.connected) {
                    ErrorBanner(snapshot.error ?: tk("no data yet"))
                    Spacer(Modifier.height(8.dp))
                }

                // One AnimatedContent for the whole tab area (not just a
                // plain `when`) so switching tabs - the most common
                // navigation in this screen - slides/crossfades instead of
                // cutting instantly. Direction follows `tabs`' own order
                // (forward = moving right along the tab bar) so it reads as
                // a coherent strip rather than a random left/right flicker.
                // An open summary is its own pane of the PARTY tab, so opening
                // and closing it animates too (a zoom rather than a slide).
                val openDetail = detailIndex?.takeIf { it in snapshot.party.indices }
                // Kept past closing, for the summary's exit animation.
                val lastDetail = remember { mutableStateOf(0) }
                openDetail?.let { lastDetail.value = it }
                val lastDexEntry = remember { mutableStateOf(1) }
                dexUi.open?.let { lastDexEntry.value = it }
                val pane = when {
                    // A ROM the companion can't read: only SETTINGS stays usable.
                    snapshot.unsupported && current != "SETTINGS" && current != "ACHIEVEMENTS" && current != "MORE" -> "UNSUPPORTED"
                    // STATES / SETTINGS / ACHIEVEMENTS / MORE work before the game's data does.
                    !loaded && current != "STATES" && current != "SETTINGS" && current != "ACHIEVEMENTS" && current != "MORE" -> "LOADING"
                    current in cachedTabs -> "LOADING"
                    GameStart.waiting && current in WAITING_TABS -> "WAITING"
                    current == "PARTY" && openDetail != null -> "PARTY/DETAIL"
                    current == "DEX" && dexUi.open != null -> "DEX/DETAIL"
                    else -> current
                }
                // When the last switch was moments ago (a player tapping through tabs), cut
                // instead of animating: every pane still sliding out stays composed, and
                // a run of quick taps stacked up several full tabs at once.
                val lastSwitchNanos = remember { longArrayOf(0L) }
                // The tab area, with RetroAchievements' live indicators in its corner.
                Box(Modifier.weight(1f).fillMaxSize()) {
                    AnimatedContent(
                        targetState = pane,
                        transitionSpec = {
                            val now = System.nanoTime()
                            // TWEAKS > TAB ANIMATIONS off: every switch cuts.
                            val rapid = now - lastSwitchNanos[0] < RAPID_SWITCH_NANOS || !CompanionTweaks[CompanionTweaks.Tweak.TAB_ANIMATIONS]
                            lastSwitchNanos[0] = now
                            if (rapid) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (targetState == "LOADING" || initialState == "LOADING" || targetState == "WAITING" || initialState == "WAITING") {
                                fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                            } else if (targetState.substringBefore('/') == initialState.substringBefore('/')) {
                                // A tab's own summary opening / closing: a zoom rather than a slide.
                                (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.94f)) togetherWith
                                    (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.94f))
                            } else {
                                val forward = barIndex(targetState.substringBefore('/')) >= barIndex(initialState.substringBefore('/'))
                                (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { w -> if (forward) w / 6 else -w / 6 }) togetherWith
                                    (fadeOut(tween(150)) + slideOutHorizontally(tween(180)) { w -> if (forward) -w / 6 else w / 6 })
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "tab-content",
                    ) { paneKey ->
                        val tab = paneKey.substringBefore('/')
                        // MAP and BATTLE both bypass the GbaWindow card entirely -
                        // MAP wants the full tab area for the map image itself
                        // (see MapScreen's own semi-transparent label instead of
                        // a bordered/padded frame), and BATTLE's own chunky
                        // colored buttons/cards already carry their own borders,
                        // so the cream window behind them just read as a
                        // mismatched extra layer.
                        if (paneKey == "UNSUPPORTED") {
                            val picker = snapshot.gamePicker?.takeIf { gamePick != null }
                            if (picker != null && !picker.declined) {
                                GamePickerPage(picker, gamePick!!, askForSupport, Modifier.fillMaxSize())
                            } else {
                                UnsupportedView(snapshot.error, askForSupport, picker?.let { { pickerOpen = true } }, Modifier.fillMaxSize())
                            }
                        } else if (paneKey == "LOADING") {
                            LoadingView(snapshot.error, Modifier.fillMaxSize())
                        } else if (paneKey == "WAITING") {
                            WaitingView(Modifier.fillMaxSize())
                        } else if (paneKey == "PARTY/DETAIL") {
                            // A mon's summary takes over the whole tab (back returns to the slots).
                            MonDetailScreen(
                                snapshot.party, lastDetail.value.coerceIn(0, (snapshot.party.size - 1).coerceAtLeast(0)),
                                onIndexChange = { detailIndex = it },
                                onBack = { detailIndex = null },
                                learn = learnContext,
                                itemUse = itemUse,
                            )
                        } else if (tab == "DEX" && dex != null) {
                            if (paneKey == "DEX/DETAIL") PokedexEntryScreen(dex, dexUi, lastDexEntry.value, guide = snapshot.guideTables)
                            else PokedexScreen(dex, dexUi)
                        } else if (tab == "GUIDE" && guide != null) {
                            if (noticeAccepted) GuideScreen(guide, guideUi, snapshot)
                            else GuideNotice(
                                verified = guide.text?.verified != false,
                                onAccept = { game?.let { settings?.acceptGuideNotice(it.name) }; noticeAccepted = true },
                                onBack = { selectedLabel = backTab },
                            )
                        } else if (tab == "CARD" && card != null && cardArt?.style == card.style) {
                            // The game's own card fills the tab, over the backdrop.
                            TrainerCardScreen(card, cardArt, Modifier.fillMaxSize(), initialBack = initialCardBack)
                        } else if (tab == "MAP") {
                            MapScreen(snapshot, modifier = Modifier.fillMaxSize(), fly = fly)
                        } else if (tab == "PARTY") {
                            // Party slots are the game's own boxes (or FireRed-like
                            // ones) over the party-menu backdrop - no extra window
                            // behind them, and the full tab area for bigger slots.
                            PartyScreen(snapshot.party, onMonClick = { detailIndex = snapshot.party.indexOf(it) })
                        } else if (tab == "SETTINGS") {
                            // Draws its own OPTION-screen windows, no cream card behind them.
                            CompanionSettingsScreen(
                                settings, available,
                                onTabsChanged = { chosenTabs = it },
                                initialPage = initialSettingsPage,
                                ui = settingsUi,
                                statusBarShown = statusBar != null && CompanionStatusBar.shown,
                                playsAs = snapshot.gamePicker?.takeIf { gamePick != null }?.let { p ->
                                    p.options.firstOrNull { it.id == p.current }?.title?.let(::shortGameTitle) ?: tk("NOT SET")
                                },
                                onPlaysAs = { pickerOpen = true },
                            )
                        } else if (tab == "MORE") {
                            MoreScreen(
                                hiddenTabs,
                                onOpen = { backTab = "MORE"; popupReturn = null; selectedLabel = it },
                                // The link to SETTINGS > TAB BAR; its BACK returns to SETTINGS' options, like any page.
                                onConfigure = { settingsUi.page = Page.TABS; openTab("SETTINGS") },
                                usable = { !snapshot.unsupported || it == "ACHIEVEMENTS" },
                            )
                        } else if (tab == "ACHIEVEMENTS") {
                            AchievementsScreen(achievements, focus = achievementFocus, onFocused = { achievementFocus = null })
                        } else if (tab == "STATES") {
                            // Same: its own OPTION-style windows over the backdrop.
                            StatesScreen(slots, snapshot.frameCounter)
                        } else if (tab == "ITEMS") {
                            // Draws its own bag-screen panels (folder tabs + list
                            // window) straight over the game backdrop, like PARTY.
                            val tmTables = learnContext?.tables
                            val tmMoves = rememberLoaded(tmTables, cached = { null }, load = {
                                tmTables?.let { com.pokedaisy.app.companion.data.MoveLearning.tmMoveNames(it) }
                            }).orEmpty()
                            ItemsScreen(
                                snapshot.items, modifier = Modifier.fillMaxSize(), itemUse = itemUse, tmMoves = tmMoves, party = snapshot.party,
                                itemExtra = learnContext?.let { ctx -> { item, text, shadow -> TmLearnersRow(ctx, snapshot.party, item, text, shadow, rememberGbaTextMetrics()) } },
                            )
                        } else if (tab == "BATTLE") {
                            // Three full-tab panes: the touch controls, and the
                            // INFO / SUGGESTIONS summaries they open (each with
                            // its own Back). Controls come and go with every turn
                            // change, so the crossfade is also the "battle screen
                            // showing and hiding" animation.
                            val battlePane = when {
                                showSuggestions -> "SUGGESTIONS"
                                showBattleStats -> "STATS"
                                canUseControls && battleShowControls -> "CONTROLS"
                                else -> "INFO"
                            }
                            AnimatedContent(
                                targetState = battlePane,
                                transitionSpec = {
                                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f)) togetherWith
                                        fadeOut(tween(140))
                                },
                                modifier = Modifier.fillMaxSize(),
                                label = "battle-pane",
                            ) { p ->
                                when (p) {
                                    "CONTROLS" -> BattleControlsScreen(
                                        activeMon = snapshot.battlePlayer.getOrNull(0),
                                        party = snapshot.party,
                                        battleInput = battleInput,
                                        battleInputState = snapshot.battleInputState,
                                        modifier = Modifier.fillMaxSize(),
                                        showHints = showHints,
                                        oneTapMoves = settings?.oneTapMoves ?: false,
                                        onShowInfo = { battleShowControls = false },
                                        onShowSuggestions = { showSuggestions = true },
                                        foeParty = snapshot.enemyParty,
                                        foe = infoFoes.getOrNull(0),
                                        active = snapshot.battlePlayer,
                                    )
                                    "SUGGESTIONS" -> SuggestionsScreen(
                                        party = snapshot.party,
                                        foe = infoFoes.getOrNull(0),
                                        // Back = the battle controls, whichever pane led here.
                                        onBack = { showSuggestions = false; battleShowControls = true },
                                        onShowInfo = { showSuggestions = false; battleShowControls = false },
                                        active = snapshot.battlePlayer,
                                        foeHeading = foeHeading,
                                        foeTeam = snapshot.enemyParty,
                                        seenFoes = seenFoes.toSet(),
                                        shownFoe = shownFoe,
                                        onSelectFoe = { selectedFoe = if (it == shownFoe && selectedFoe != null) null else it },
                                    )
                                    "STATS" -> BattleStatsScreen(
                                        infoPlayer, infoFoes, foeHeading,
                                        onBack = { showBattleStats = false },
                                    )
                                    else -> if (battleFocus) BattleFocusScreen(
                                        focusFoe,
                                        party = snapshot.party,
                                        // focusPlayer is empty in previews, which build the snapshot by hand
                                        battlePlayer = snapshot.focusPlayer.ifEmpty { snapshot.battlePlayer },
                                        playerBattlers = snapshot.playerBattlers,
                                        showHints = showHints,
                                        onBack = if (canUseControls) ({ battleShowControls = true }) else null,
                                        foeTeam = snapshot.enemyParty,
                                        seenFoes = focusSeen.toSet(),
                                        shownFoe = focusShown,
                                        foeHeading = focusHeading,
                                        onSelectFoe = { selectedFoe = if (it == focusShown && selectedFoe != null) null else it },
                                        dexTables = snapshot.pokedex?.tables,
                                    )
                                    // Back only while there are controls to go back to.
                                    else BattleInfoScreen(
                                        infoPlayer, infoFoes, snapshot.isDoubleBattle,
                                        showHints = showHints,
                                        onBack = if (canUseControls) ({ battleShowControls = true }) else null,
                                        onShowSuggestions = { showSuggestions = true },
                                        onShowStats = if (showFoeIvs && infoFoes.any { it.stats != null }) ({ showBattleStats = true }) else null,
                                        foeTeam = snapshot.enemyParty,
                                        seenFoes = seenFoes.toSet(),
                                        shownFoe = shownFoe,
                                        foeHeading = foeHeading,
                                        // Tapping the one shown goes back to following the battle.
                                        onSelectFoe = { selectedFoe = if (it == shownFoe && selectedFoe != null) null else it },
                                        foeDetails = foeDetails,
                                        onToggleFoeDetails = { foeDetails = !foeDetails; settings?.setFoeDetails(foeDetails) },
                                        dexTables = snapshot.pokedex?.tables,
                                    )
                                }
                            }
                        }
                    }
                    AchievementIndicators(achievements, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }

                tabBar()
            }

            // Unlocks and the like, over every tab.
            AchievementPopupHost(
                achievements, Modifier.padding(top = 12.dp), initial = initialPopup,
                onOpen = { p ->
                    if (current != "ACHIEVEMENTS") popupReturn = current
                    selectedLabel = "ACHIEVEMENTS"; detailIndex = null; showSuggestions = false; showBattleStats = false; dexUi.open = null
                    achievementFocus = AchievementFocus(p.achievementId, unlocked = p.kind != AchievementPopup.Kind.PROGRESS)
                },
            )

            // EXPAND's one-time offer, in a game it knows (the Library's "NOT NOW" doesn't stop this one, once).
            if (settings?.expandOffer == true) {
                ExpandIntro(
                    rememberGbaTextMetrics(), rememberGbaTextMetrics(textScale = 1f), inGame = true,
                    onTurnOn = { settings.answerExpandOffer(true) }, onNotNow = { settings.answerExpandOffer(false) },
                )
            }

            // BEST EFFORT's notices (a match it made, a party that doubts it) and WHICH GAME IS THIS? over the tabs.
            if (gamePick != null) {
                snapshot.bestEffort?.let { be -> if (!pickerOpen) BestEffortNotices(be, gamePick, shareBestEffort, onPick = { pickerOpen = true }) }
                snapshot.gamePicker?.takeIf { pickerOpen }?.let { picker ->
                    GamePickerOverlay(picker, gamePick, askForSupport, onDismiss = { pickerOpen = false })
                }
            }
        }
    } } } }
}

/**
 * SETTINGS > STATUS BAR > COMPANION: the game's status bar over the companion's tabs instead
 * of over the game - [Prefs.statusBarOnCompanion], set by the activity at start and by SETTINGS.
 * Compose state, so a change moves it at once.
 */
object CompanionStatusBar {
    var shown by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * The game hasn't been played yet this boot (the title screen, the intro, CONTINUE loading the save): the RAM holds no
 * party yet, or the last one, so PARTY / BAG / MAP / CARD show [WaitingView] instead ([WAITING_TABS]). Set by the game's
 * activity; false everywhere else (previews, tests).
 */
object GameStart {
    var waiting by androidx.compose.runtime.mutableStateOf(false)
}

/** The tabs that wait for the game ([GameStart]): what they show is the save's. */
private val WAITING_TABS = setOf("PARTY", "ITEMS", "MAP", "CARD")

/**
 * A tab id's label: the bag tab takes the game's own word from its START menu
 * (every ROM's checked headless, native-capture/menu-shots/<rom>/start.png) -
 * BAG nearly everywhere, Unbound's CUBE, R.O.W.E.'s INVENTORY, Super Mariomon's POCKETS. ACHIEVEMENTS
 * doesn't fit a chip: CHEEVOS, RetroAchievements' own nickname for them.
 */
internal fun companionTabLabel(id: String): String = when (id) {
    "ACHIEVEMENTS" -> tr("CHEEVOS")
    "PARTY" -> tr("PARTY")
    "DEX" -> tr("DEX")
    "MAP" -> tr("MAP")
    "GUIDE" -> tr("GUIDE")
    "CARD" -> tr("CARD")
    "STATES" -> tr("STATES")
    "BATTLE" -> tr("BATTLE")
    "SETTINGS" -> tr("SETTINGS")
    // The hacks' own words for the bag stay theirs.
    "ITEMS" -> when (activeGame) {
        GameKind.UNBOUND -> "CUBE"
        GameKind.ROWE -> "INVENTORY"
        GameKind.MARIOMON -> "POCKETS"
        GameKind.CRYSTAL, GameKind.CRYSTAL_CLEAR, GameKind.PRISM, GameKind.GOLD, GameKind.SILVER -> "PACK"
        GameKind.YELLOW, GameKind.RED, GameKind.BLUE -> tr("ITEM")   // Gen 1's START menu (the European carts' own word)
        else -> tr("BAG")
    }
    else -> id
}

/**
 * Tab text: between the list and caption sizes. The tab bar's height also
 * decides the party slots' integer pixel scale (a FireRed/Emerald slot needs
 * 57 GBA px per row for 5x), so keep it lean - a taller bar once dropped the
 * slots to 4x on the Thor.
 */
@Composable
private fun rememberTabMetrics(): GbaTextMetrics {
    // A fixed-width game font (Gen 1's 8px letters, condensed): its own size at whole pixels, crisp.
    val wide = LocalGameFont.current != null
    return rememberGbaTextMetrics(if (wide) 1f else 1.15f, wholePixels = wide)
}

/** The gear and MORE chips' fixed width (narrower on a short screen, which is narrow too: the text tabs keep their words). */
internal val SETTINGS_CHIP_WIDTH: androidx.compose.ui.unit.Dp
    @Composable get() = if (LocalShortCompanion.current) 40.dp else 52.dp

/** Tab switches closer together than this cut instead of sliding (see the tab AnimatedContent). */
private const val RAPID_SWITCH_NANOS = 300_000_000L

/** The gap between tab chips, and above the tab bar. */
internal val TAB_GAP = 4.dp

/**
 * The companion is the window over the game (one screen, SidePanel): L / R hints at the tab bar's ends, and BATTLE is
 * INFO / SUGGESTIONS only - the window is over the game's own battle menu, which the player's buttons drive there.
 */
val LocalCompanionWindow = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The companion on the other screen of a two-screen device has the game's buttons (X on the field, PokeDaisyActivity's
 * bottom focus): L / R hints at the tab bar's ends, as in [LocalCompanionWindow], but BATTLE keeps its controls.
 */
val LocalShoulderHints = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The companion's height in GBA pixels (rememberGbaTextMetrics' unit) below which its main text goes a step smaller: the
 * RG DS's 480 px are 240, the Thor's 1080 px 360, the Duo Lite's 960 px 320. */
private const val SHORT_COMPANION_UNITS = 280

/** The companion is on a short screen ([isShortCompanion]): its text a step smaller, its fixed-size buttons smaller too
 * ([NavButtonSize], [SETTINGS_CHIP_WIDTH]). */
val LocalShortCompanion = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The companion's size class around [content]: on a short screen (the RG DS's 640x480, [isShortCompanion]) the main text
 * a step smaller ([LocalTextStep]), as the one-screen window does on a tall one - its pixel font can't go below two
 * screen pixels a GBA pixel, so at its usual size it was as large for the screen as on a GBA, and words, rows and the
 * battle panes didn't fit - and [LocalShortCompanion] for the layouts that squeeze. [CompanionScreen] runs in it; so do
 * the screenshot tests' panes drawn on their own.
 */
@Composable
fun CompanionScreenSize(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = isShortCompanion(constraints.maxHeight, LocalDensity.current.density)
        CompositionLocalProvider(
            LocalTextStep provides if (short) -1 else LocalTextStep.current,
            LocalShortCompanion provides short,
            content = content,
        )
    }
}

/** [heightPx] tall at [density] is short ([SHORT_COMPANION_UNITS]). */
fun isShortCompanion(heightPx: Int, density: Float): Boolean {
    val unit = kotlin.math.floor(20f * density / 16f + 0.5f).toInt().coerceAtLeast(2)
    return heightPx in 1 until SHORT_COMPANION_UNITS * unit
}

/** A [ShoulderHint]'s width. */
internal val SHOULDER_HINT_WIDTH = 30.dp

/** How long the L / R hints take to slide in / out. */
private const val SHOULDER_HINT_MS = 220

/**
 * L or R at an end of the tab bar while the companion is the window: the shoulder button that steps the tabs, drawn
 * as one - a small grey key whose top outer corner (L's left, R's right) is the big rounded one, like the buttons. A tap
 * steps the tabs too; not a D-pad stop (the buttons do it).
 */
@Composable
private fun ShoulderHint(letter: String, shown: Float, onClick: () -> Unit) {
    val m = rememberGbaTextMetrics(1f)
    val u = m.u
    val left = letter == "L"
    val noRipple = remember { MutableInteractionSource() }
    // [shown] < 1: on its way in or out - the room for it opens from the screen's side, the key sliding in from there.
    Box(
        contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .width(SHOULDER_HINT_WIDTH * shown)
            .fillMaxHeight()
            .clipToBounds()
            .graphicsLayer { alpha = shown }
            .focusProperties { canFocus = false }
            .soundClickable(interactionSource = noRipple, indication = null, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .wrapContentWidth(if (left) Alignment.End else Alignment.Start, unbounded = true)
                .requiredSize(SHOULDER_HINT_WIDTH, m.lineHeight + u * 6)
                .drawBehind {
                    val px = u.toPx()
                    // As round as the key allows (half its narrower side), like a shoulder button's top.
                    val big = minOf(size.width, size.height) / 2f
                    val small = 2 * px
                    // The chip's own layers, then its idle fill, each a layer's width in.
                    var inset = 0f
                    val layers = OptionColors.chipLayers.inPx(px)
                    for ((color, width) in layers + (OptionColors.tabIdleFill to 0f)) {
                        val r = { r: Float -> (r - inset).coerceAtLeast(0f) }
                        drawPath(
                            pixelCornersPath(
                                androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                                topLeft = r(if (left) big else small), topRight = r(if (left) small else big),
                                bottomRight = r(small), bottomLeft = r(small),
                                step = px, offset = androidx.compose.ui.geometry.Offset(inset, inset),
                            ),
                            color,
                        )
                        inset += width
                    }
                },
        ) { GbaText(letter, OptionColors.label, OptionColors.labelShadow, m, bold = gameBoldLabels()) }
    }
}

/**
 * A tab in the OPTION-screen look (see GbaMenu): framed like [OptionButton],
 * grey when idle, white with the value-red label when selected.
 */
@Composable
internal fun TabChip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val m = rememberTabMetrics()
    TabChipShell(selected, m, modifier.fillMaxHeight().alpha(if (enabled) 1f else 0.4f), onClick, enabled) {
        GbaText(
            label,
            if (selected) OptionColors.value else OptionColors.label,
            if (selected) OptionColors.valueShadow else OptionColors.labelShadow,
            m,
            bold = gameBoldLabels(),
        )
    }
}

/** The SETTINGS and MORE tabs: a fixed-width chip (narrower than the weighted text tabs)
 * showing only a pixel-art [icon] (the gear, MORE's nine squares) rather than a word. Same
 * height as the text tabs (the tab row stretches it). */
@Composable
private fun IconTabChip(icon: List<String>, description: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val m = rememberTabMetrics()
    TabChipShell(selected, m, modifier.width(SETTINGS_CHIP_WIDTH).fillMaxHeight().semantics { contentDescription = description }, onClick) {
        PixelIcon(
            icon,
            color = if (selected) OptionColors.value else OptionColors.label,
            modifier = Modifier.size(m.u * 20),
        )
    }
}

@Composable
private fun TabChipShell(
    selected: Boolean,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val u = m.u
    val noRipple = remember { MutableInteractionSource() }
    val focused by noRipple.collectIsFocusedAsState()
    Box(
        modifier = modifier
            // The D-pad's focus (the companion's window): a ring flush inside the chip's 3u corner.
            .gbaFocusRing(focused, PixelRoundedShape(u * 3))
            .drawBehind {
                // Idle tabs take the list window's frame + grey; the selected
                // one the title window's white, like the OPTION screen's cursor row.
                val px = u.toPx()
                drawLayeredBox(OptionColors.chipLayers.inPx(px), if (selected) OptionColors.tabSelectedFill else OptionColors.tabIdleFill, radius = 3 * px)
            }
            .soundClickable(interactionSource = noRipple, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (LocalGameFont.current != null) u else u * 2, vertical = u * 6),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Every tab but SETTINGS while the running ROM is one the companion can't
 * read ([SnapshotView.unsupported]); [detail] is the reader's reason. */
@Composable
private fun UnsupportedView(
    detail: String?, askForSupport: (() -> Unit)?, pickGame: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val m = rememberGbaTextMetrics()
    val big = rememberGbaTextMetrics(1.6f)
    val small = rememberGbaTextMetrics(1f)
    Column(modifier) {
        OptionTitleWindow(tr("NOT SUPPORTED"), m, Modifier.fillMaxWidth())
        Spacer(Modifier.height(m.u * 4))
        OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.u * 16, vertical = m.u * 8),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GbaText(tr("THIS ROM ISN'T SUPPORTED"), OptionColors.value, OptionColors.valueShadow, big)
                Spacer(Modifier.height(m.u * 8))
                GbaText(
                    tr("The game plays normally on the top screen, but the second screen can't read its party, map, items or battles."),
                    OptionColors.label, OptionColors.labelShadow, m, maxLines = 4,
                )
                Spacer(Modifier.height(m.u * 6))
                GbaText(
                    if (pickGame == null) tr("SETTINGS (the gear below) still works.")
                    else tr("You said it's none of the games PokéDaisy knows. If it's a version of one, pick it."),
                    OptionColors.label, OptionColors.labelShadow, if (pickGame == null) m else small, maxLines = 3,
                )
                Spacer(Modifier.height(m.u * 6))
                Row(horizontalArrangement = Arrangement.spacedBy(m.u * 6)) {
                    pickGame?.let { OptionButton(tk("PICK A GAME"), m, emphasis = true, onClick = it) }
                    askForSupport?.let { OptionButton(tk("ASK FOR SUPPORT"), m, emphasis = pickGame == null, onClick = it) }
                }
                if (askForSupport != null) {
                    Spacer(Modifier.height(m.u * 4))
                    GbaText(
                        tr("Opens a game request with the ROM's file name and SHA-1 - never the ROM itself."),
                        OptionColors.label.copy(alpha = 0.7f), OptionColors.labelShadow, small, maxLines = 2,
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(m.u * 6))
                    GbaText(detail, OptionColors.label.copy(alpha = 0.7f), OptionColors.labelShadow, small, maxLines = 3)
                }
            }
        }
    }
}

/** The data tabs until the first telemetry arrives: a rocking Poké Ball and
 * "LOADING...". [status] (the reader's own message) only shows up if it
 * drags on, so a normal boot stays quiet. */
@Composable
private fun LoadingView(status: String?, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    // The dots count up with the ball's rock (same 1.4s cycle).
    val transition = rememberInfiniteTransition(label = "loading")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "loading-dots",
    )
    val dots = (phase * 4).toInt().coerceAtMost(3)
    var showStatus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(10_000); showStatus = true }
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        RockingPokeBall(Modifier.size(m.u * 36))
        Spacer(Modifier.height(m.u * 6))
        // Fixed-width dots so the word doesn't shift as they appear.
        Box {
            BackdropText(tr("LOADING") + "...", m, Modifier.alpha(0f))
            BackdropText(tr("LOADING") + ".".repeat(dots), m)
        }
        if (showStatus && !status.isNullOrBlank()) {
            Spacer(Modifier.height(m.u * 4))
            GbaText(tr(status), OptionColors.onBackdrop.copy(alpha = 0.8f), OptionColors.onBackdropShadow, small, maxLines = 3)
        }
    }
}

/**
 * PARTY / BAG / MAP / CARD before the player is in the game ([GameStart]): the game's own party slots, empty and dimmed,
 * under an OPTION window - a rocking Poké Ball, what's coming, and a PRESS START blinking like the title screen's.
 */
@Composable
private fun WaitingView(modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics()
    val small = rememberGbaTextMetrics(1f)
    val transition = rememberInfiniteTransition(label = "waiting")
    val blink by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "press-start")
    Box(modifier) {
        // The game's look, none of its data: its slots with no one in them.
        Box(Modifier.fillMaxSize().alpha(0.45f)) { PartyScreen(emptyList(), onMonClick = {}) }
        OptionListWindow(m, Modifier.align(Alignment.Center).padding(horizontal = m.u * 16)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = m.u * 4)) {
                RockingPokeBall(Modifier.size(m.u * 28))
                Spacer(Modifier.height(m.u * 6))
                GbaText(
                    tr("Your party shows up here once you're in the game."),
                    OptionColors.label, OptionColors.labelShadow, small, maxLines = 3,
                )
                Spacer(Modifier.height(m.u * 6))
                GbaText(tr("PRESS START"), OptionColors.value, OptionColors.valueShadow, m, modifier = Modifier.alpha(if (blink < 0.6f) 1f else 0f))
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PixelRoundedShape(6.dp))
            .background(AppColors.windowFrame)
            .padding(2.dp)
            .clip(PixelRoundedShape(4.dp))
            .background(if (OptionColors.dark) Color(0xFF3A2A1E) else Color(0xFFFCEFD8))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(tr(message), color = if (OptionColors.dark) Color(0xFFF2B48E) else Color(0xFF9A4A28), fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
