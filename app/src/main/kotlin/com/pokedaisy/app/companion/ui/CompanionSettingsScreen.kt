package com.pokedaisy.app.companion.ui

import com.pokedaisy.app.companion.NewFeatures

import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.ui.theme.DarkMode
import com.pokedaisy.app.companion.i18n.L10n
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.companion.SCENE_SPEEDS
import com.pokedaisy.app.companion.data.WildEncounters
import com.pokedaisy.app.companion.COMPANION_TABS
import com.pokedaisy.app.companion.CompanionSettings
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.FfMode
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.hasGrid
import com.pokedaisy.app.companion.next
import com.pokedaisy.app.companion.FfMusicMode
import com.pokedaisy.app.companion.MAX_BAR_TABS

private val FF_RATES = floatArrayOf(0f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f)

/** SETTINGS > STATUS BAR's choices: off, over the game, over the companion's tabs. */
internal val STATUS_BAR_PLACES = listOf(tk("OFF"), tk("GAME"), tk("COMPANION"))

internal fun statusBarLabel(on: Boolean, onCompanion: Boolean) = STATUS_BAR_PLACES[if (!on) 0 else if (onCompanion) 2 else 1]

internal enum class Page(val title: String) { HOME(tk("SETTINGS")), TABS(tk("TAB BAR")), BUTTONS(tk("GAME BUTTONS")), HOTKEYS(tk("HOTKEYS")), SHADERS(tk("SHADERS")), FIELD_HUD(tk("HUD")), QUICK_MENU(tk("QUICK MENU")), TOUCH(tk("TOUCH CONTROLS")), SPEED(tk("SPEED OPTIONS")), DISPLAY(tk("DISPLAY OPTIONS")), CHEATS(tk("CHEATS")), TWEAKS(tk("TWEAKS")), HELP(tk("HELP")) }

/** Where BACK from a page goes: SHADERS opens from DISPLAY OPTIONS, the rest from HOME. */
internal val Page.parent: Page get() = if (this == Page.SHADERS) Page.DISPLAY else Page.HOME

/**
 * The bottom-screen SETTINGS tab, laid out like FireRed's OPTION screen over
 * the game's own party-menu backdrop: every setting is a `LABEL  VALUE` row.
 * Tapping an on/off row flips it; rows with more choices (FF SPEED, FF MODE,
 * SHOW) open a pick-one list ([OptionSelector]) instead of stepping
 * through every value. Key bindings are two sub-pages ending in CANCEL, like
 * the game's own list; a tap on a binding waits for the keys themselves
 * ([CompanionSettings.keyCapture]: Screen-2 gets no key events, so the game's
 * activity takes them and asks for them over the game).
 *
 * TAB BAR picks which tabs sit on the tab bar (the rest open from the MORE
 * tab) and reports each change through [onTabsChanged]. CLOSE GAME / RESTART GAME end the scrolling list. Only the tabs this
 * game has ([availableTabs], ids) count towards the bar's [MAX_BAR_TABS].
 * The options come in titled groups (fast-forward, controls, companion, screen).
 */
@Composable
fun CompanionSettingsScreen(
    settings: CompanionSettings?,
    availableTabs: List<String> = COMPANION_TABS,
    onTabsChanged: (List<String>) -> Unit = {},
    /** A sub-page to open on (a [Page] name, "CHEATS") - for screenshot tests. */
    initialPage: String? = null,
    /** Where SETTINGS was (page, row, scroll), held by CompanionScreen so another tab and back keeps it. */
    ui: SettingsUiState = remember { SettingsUiState(initialPage) },
    /** The game's status bar is drawn over the companion's tabs (it shows the game and battery). */
    statusBarShown: Boolean = false,
    /** A ROM the app doesn't know: the game it's read as (BEST EFFORT), a GAME row at the top; [onPlaysAs] opens the picker. */
    playsAs: String? = null,
    onPlaysAs: () -> Unit = {},
) {
    val m = rememberGbaTextMetrics()
    var page by ui::page
    // Settings aren't Compose state: bump this after each change to redraw the values.
    var tick by remember { mutableStateOf(0) }
    // HOME starts with a group title, so its first row is index 1. HOME's cursor and
    // scroll outlive its sub-pages: coming back lands on the row that opened one.
    var homeCursor by ui::homeCursor
    // GAME BUTTONS opens on a group title (ALL GAMES): its cursor starts on the row under it, like HOME's.
    var pageCursor by remember(page) { mutableStateOf(if (page == Page.BUTTONS) 1 else 0) }
    val cursor = if (page == Page.HOME) homeCursor else pageCursor
    fun moveCursor(i: Int) { if (page == Page.HOME) homeCursor = i else pageCursor = i }
    val homeScroll = ui.homeScroll
    // GAME BUTTONS / HOTKEYS: a row waiting for keys. Leaving the page (or SETTINGS) stops waiting.
    val capture = settings?.keyCapture
    val capturing = capture?.request
    val capturedKeys = capture?.keys
    DisposableEffect(page, capture) { onDispose { capture?.cancel() } }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var selector by remember { mutableStateOf<Selector?>(null) }

    // The games' own spelling: "POKéMON", not "POKÉMON".
    val gameName = settings?.gameName?.uppercase()?.replace('É', 'é') ?: ""
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // With the status bar over the tabs (STATUS BAR > COMPANION) it already shows the game and
            // the battery: HOME drops its title window, a sub-page keeps just its name and back arrow.
            val trailing = when {
                page == Page.TABS && settings != null -> "${barCount(settings, availableTabs)}/$MAX_BAR_TABS"
                statusBarShown -> null
                else -> gameName.ifEmpty { null }
            }
            if (!(statusBarShown && page == Page.HOME)) {
                // A sub-page's arrow (and BACK, through it) returns to the options.
                val titleM = rememberGbaTextMetrics()
                OptionTitleWindow(page.title, titleM, trailing = trailing, onBack = if (page != Page.HOME) ({ if (page != Page.HELP || !ui.docs.back()) page = page.parent }) else null) {
                    if (!statusBarShown) {
                        Spacer(Modifier.width(titleM.u * 8))
                        BatteryIndicator(titleM)
                    }
                }
                Spacer(Modifier.height(m.u * 4))
            }
            // While a row waits for keys: what to press (the game's screen asks for them too), CLEAR / CANCEL for a touch.
            if (capture != null && capturing != null && (page == Page.BUTTONS || page == Page.HOTKEYS)) {
                OptionListWindow(m, Modifier.fillMaxWidth()) {
                    KeyCaptureBar(capture, capturing, m, rememberGbaTextMetrics(1f), Modifier.padding(horizontal = m.u * 4, vertical = m.u * 2))
                }
                Spacer(Modifier.height(m.u * 4))
            }
            // EDIT LAYOUT saves over the game, outside this page: its revision redraws TOUCH CONTROLS.
            val touchRevision = settings?.touchRevision ?: 0
            val rows = remember(settings, page, tick, availableTabs, playsAs, capturing, capturedKeys, touchRevision) {
                if (settings == null) return@remember listOf(SettingRow(tk("NO GAME RUNNING"), null) {})
                when (page) {
                    Page.HOME -> playsAs?.let { game ->
                        listOf(groupTitle(tk("GAME")), SettingRow(tk("PLAYS AS"), game, subtitle = "Which game this ROM is read as", opensPage = true) { onPlaysAs() })
                    }.orEmpty() + homeRows(settings, barCount(settings, availableTabs), { tick++ }, { page = it }, { selector = it })
                    Page.TABS -> COMPANION_TABS.map { id ->
                        val shown = id in settings.companionTabs
                        // A tab this game doesn't have (DEX, GUIDE) keeps its setting for the games that do.
                        if (id !in availableTabs) return@map SettingRow(companionTabLabel(id), tk("N/A")) {}
                        SettingRow(
                            companionTabLabel(id), if (shown) tk("SHOWN") else tk("HIDDEN"),
                            labelBadge = if (id == "BOXES") NewFeatures.badge(NewFeatures.BOXES_TAB) else null,
                        ) {
                            val now = settings.companionTabs
                            when {
                                shown -> setTabs(settings, now - id, onTabsChanged)
                                barCount(settings, availableTabs) >= MAX_BAR_TABS -> confirm = Confirm(
                                    tk("TAB BAR IS FULL"),
                                    tr("Up to {0} tabs fit next to SETTINGS. Hide one first - hidden tabs open from MORE.", MAX_BAR_TABS),
                                    tk("OK"), {},
                                )
                                else -> setTabs(settings, now + id, onTabsChanged)
                            }
                            tick++
                        }
                    } + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    // Every game's buttons, then the game running's own group (TO COMPANION only with two screens).
                    Page.BUTTONS -> GbaControls.Group.entries.filter { it == GbaControls.Group.ALL || settings.buttonGroup.let { g -> g == null || g == it } }.flatMap { group ->
                        val btns = GbaControls.Btn.entries.filter {
                            it.group == group && (it != GbaControls.Btn.TO_COMPANION || settings.usesSecondScreen || settings.buttonGroup == null)
                        }
                        listOf(groupTitle(group.title)) + btns.map { btn ->
                            val title = btn.title
                            SettingRow(title, bindingValue(capture, title, settings.gbaControlBindings()[btn]), subtitle = btn.subtitle, labelBadge = btn.badge) {
                                capture?.toggle(title) {
                                    KeyCapture.Request(
                                        title, chord = false,
                                        onDone = { keys -> settings.setGbaControlBinding(btn, KeyCapture.keyName(keys.first())); tick++ },
                                        onClear = if (btn.canClear) ({ settings.clearGbaControlBinding(btn); tick++ }) else null,
                                    )
                                }
                            }
                        }
                    } + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    // Off, the binds stay as they are, greyed out, and every key goes to the game.
                    Page.HOTKEYS -> listOf(
                        SettingRow(tk("HOTKEYS"), if (settings.hotkeysEnabled) tk("ON") else tk("OFF")) {
                            settings.setHotkeysEnabled(!settings.hotkeysEnabled); tick++
                        },
                    ) + Hotkeys.Action.entries.map { action ->
                        val title = action.title
                        SettingRow(title, bindingValue(capture, title, settings.hotkeyBindings()[action]), enabled = settings.hotkeysEnabled, labelBadge = action.badge) {
                            capture?.toggle(title) {
                                KeyCapture.Request(
                                    title, chord = true,
                                    onDone = { keys ->
                                        // A chord another hotkey already has exactly moves only on MOVE.
                                        val names = keys.map(KeyCapture::keyName)
                                        val taken = Hotkeys.clashes(settings.hotkeyBindings(), action, names)
                                        if (taken.isEmpty()) { settings.setHotkeyBinding(action, names); tick++ }
                                        else confirm = Confirm(
                                            tk("KEY IN USE"),
                                            tr("{0} is already {1}. Move it to {2}?", KeyCapture.chordLabel(keys), taken.joinToString(", ") { tr(it.title) }, tr(title)),
                                            tk("MOVE"),
                                        ) { settings.setHotkeyBinding(action, names, taken); tick++ }
                                    },
                                    // CLEAR leaves it unbound.
                                    onClear = { settings.setHotkeyBinding(action, null); tick++ },
                                )
                            }
                        }
                    } + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.SHADERS -> shaderRows(settings) { tick++ } + SettingRow(tk("CANCEL"), null) { page = Page.DISPLAY }
                    Page.FIELD_HUD -> fieldHudRows(
                        settings.fieldHud, settings.fieldHudStyle,
                        setOn = { settings.setFieldHud(it); tick++ },
                        setStyle = { settings.setFieldHudStyle(it); tick++ },
                    ) + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.QUICK_MENU -> quickMenuRows(
                        settings.quickMenuActions, KeyCapture.labels(settings.hotkeyBindings()[Hotkeys.Action.QUICK_MENU]), settings.quickMenuStyle,
                        settings.quickMenuLabels,
                        setShown = { settings.setQuickMenuActions(it); tick++ },
                        setStyle = { settings.setQuickMenuStyle(it); tick++ },
                        setLabels = { settings.setQuickMenuLabels(it); tick++ },
                        onHotkey = { page = Page.HOTKEYS },
                    ) + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.TOUCH -> touchControlsRows(
                        settings.touchStyle, set = { settings.setTouchStyle(it); tick++ },
                        onEdit = settings::editTouchLayout,
                        underGame = settings.uprightUnderGame, setUnderGame = { settings.setPortraitUnderGame(it); tick++ },
                        expand = settings.gameAspect == GameAspect.WIDE && settings.wideSupported, touchShown = settings.touchControlsShown,
                    ) + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.SPEED -> speedRows(settings, { tick++ }, { selector = it }) + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.DISPLAY -> displayRows(settings, { tick++ }, { page = it }, { selector = it }) + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.CHEATS -> cheatRows(settings) { tick++ } + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    // Small preferences, each on by default (CompanionTweaks).
                    Page.TWEAKS -> CompanionTweaks.Tweak.entries.map { t ->
                        // LIGHT COMPANION keeps the animations off whatever these say (CompanionTweaks.get).
                        val held = t.motion && com.pokedaisy.app.companion.LightCompanion.on
                        SettingRow(t.label, if (CompanionTweaks.stored(t)) tk("ON") else tk("OFF"),
                                   subtitle = if (held) "Off while LIGHT COMPANION is on" else t.description) {
                            CompanionTweaks[t] = !CompanionTweaks.stored(t)
                            settings.setTweak(t.key, CompanionTweaks.stored(t))
                            tick++
                        }
                    } + SettingRow(tk("CANCEL"), null) { page = Page.HOME }
                    Page.HELP -> emptyList()
                }
            }
            // A page of its own each time: the rows sit in slots by position, so the row the D-pad had focused on HOME
            // (HUD) kept the focus in the next page's row at the same place - near the bottom of SETTINGS > HUD - while the
            // white cursor showed its top row: DOWN went nowhere, UP walked up from the bottom.
            androidx.compose.runtime.key(page) {
                // HELP: the manual, read from the docs API (no search box: the bottom screen can't host a keyboard).
                if (page == Page.HELP) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val source = remember { DocsSources.of(context) }
                    DocsReader(ui.docs, m, source, Modifier.fillMaxWidth().weight(1f), searchable = false)
                } else if (page == Page.TOUCH && settings != null) {
                    // The preview beside / above the rows; it follows each change (tick).
                    @Suppress("UNUSED_EXPRESSION") tick
                    @Suppress("UNUSED_EXPRESSION") touchRevision
                    val style = settings.touchStyle
                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    val stage = settings.touchStage ?: remember(density) { simulateTouchStage(2400f, 1080f, density.coerceAtLeast(2f)) }
                    val shot = remember(settings) { settings.touchPreviewShot() }
                    TouchControlsPage(
                        stage, style, shot, m, Modifier.fillMaxWidth().weight(1f), underGame = settings.uprightUnderGame == true,
                        expand = settings.gameAspect == GameAspect.WIDE && settings.wideSupported, touchShown = settings.touchControlsShown,
                    ) { mod ->
                        OptionListWindow(m, mod) {
                            GroupedRows(rows, m, cursor, onClick = { i -> moveCursor(i); rows[i].onClick() }, onFocused = ::moveCursor)
                        }
                    }
                } else OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                    // Rows share out the window's height (big touch targets) down
                    // to a floor, past which the list scrolls instead.
                    // SHADERS too: OptionRows shares the window out, so its 4 rows came out taller than SETTINGS'.
                    // TWEAKS: OptionRows has no subtitle line for each tweak's description; CHEATS' master switch has one too.
                    if (rows.any { it.header } || page == Page.SHADERS || page == Page.DISPLAY || page == Page.TWEAKS || page == Page.CHEATS) {
                        // The row the page was opened for (SWAP SCREENS after the swap): the cursor onto it, scrolled to.
                        val reveal = if (page == Page.HOME) ui.revealRow?.let { r -> rows.indexOfFirst { !it.header && it.label == r } } ?: -1 else -1
                        if (reveal >= 0 && homeCursor != reveal) SideEffect { homeCursor = reveal }
                        GroupedRows(rows, m, cursor, onClick = { i -> moveCursor(i); rows[i].onClick() }, scroll = if (page == Page.HOME) homeScroll else rememberScrollState(),
                            // Cheat names are the player's own, often long: more room before ON / OFF.
                            labelWeight = when (page) { Page.TWEAKS -> 0.8f; Page.CHEATS -> 0.75f; else -> 0.58f }, reveal = reveal, onRevealed = { ui.revealRow = null },
                            onFocused = ::moveCursor, groupTabs = page == Page.HOME) {
                            // Actions, not settings: the list's last line, out of the way of the options.
                            if (page == Page.HOME && settings != null) {
                                // A summary-window line sets them apart from the last group.
                                Separator(m, Modifier.fillMaxWidth().padding(start = m.u * 4, end = m.u * 4, top = m.u * 8))
                                // Room around them, so they don't read as one more row of the list.
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(m.u * 8),
                                    modifier = Modifier.padding(start = m.u * 10, end = m.u * 10, top = m.u * 10, bottom = if (settings.canCloseCompanion) m.u * 8 else m.u * 10),
                                ) {
                                    OptionButton(tk("CLOSE GAME"), m, modifier = Modifier.weight(1f), onClick = {
                                        confirm = Confirm(
                                            tk("CLOSE GAME?"),
                                            if (settings.autoResume) tr("Returns to the ROM list. Progress is saved automatically.")
                                            else tr("Returns to the ROM list. RESUME GAMES is off: progress since your last in-game save will be lost."),
                                            tk("CLOSE"), settings::closeGame,
                                        )
                                    })
                                    OptionButton(tk("RESTART GAME"), m, emphasis = true, modifier = Modifier.weight(1f), onClick = {
                                        confirm = Confirm(
                                            tk("RESTART GAME?"),
                                            if (settings.autoResume) tr("Reboots the game and reloads its save from disk. Unsaved progress is lost.")
                                            else tr("Reboots the game and reloads its save from disk. Progress since your last in-game save will be lost."),
                                            tk("RESTART"), settings::restartGame,
                                        )
                                    })
                                }
                                // Its screen for other apps while the game keeps running; BACK on the game reopens it. Closed while
                                // swapped, this companion is the one over the game: OPEN COMPANION puts it back on its own screen.
                                if (settings.canCloseCompanion) {
                                    val closed = settings.companionClosed
                                    OptionButton(
                                        tk(if (closed) "OPEN COMPANION" else "CLOSE COMPANION"), m,
                                        modifier = Modifier.fillMaxWidth().padding(start = m.u * 10, end = m.u * 10, bottom = m.u * 10),
                                        onClick = if (closed) settings::openCompanion else settings::closeCompanion,
                                    )
                                }
                            }
                        }
                    } else {
                        OptionRows(
                            rows.mapIndexed { i, row -> Triple(row.label, row.value) { moveCursor(i); row.onClick() } },
                            m, Modifier.fillMaxSize(), selected = cursor, minRow = m.rowHeight * 1.2f,
                            // Cheat names are the player's own, often long: more room before ON / OFF.
                            labelWeight = if (page == Page.CHEATS) 0.75f else 0.58f,
                            valueBadge = { rows[it].badge },
                            enabled = { rows[it].enabled },
                            labelBadge = { rows[it].labelBadge },
                            onFocused = ::moveCursor,
                        )
                    }
                }
            }
        }

        selector?.takeIf { it.tiles }?.let { sel ->
            PlayerLookPicker(
                sel.title, settings?.playerLookOwn, sel.options.drop(1), sel.current.takeIf { it != sel.options.first() }, m,
                onPick = { sel.onPick(it ?: sel.options.first()); selector = null; tick++ },
                onDismiss = { selector = null },
                label = { settings?.playerLookLabel(it) ?: it },
            )
        }
        selector?.takeIf { !it.tiles }?.let { sel ->
            OptionSelector(
                sel.title, sel.options, sel.current, { it }, m,
                onPick = { sel.onPick(it); selector = null; tick++ },
                onDismiss = { selector = null },
                badge = sel.badge, note = sel.note,
            )
        }
        confirm?.let { c ->
            OptionConfirm(
                c.title, c.message, c.confirmLabel, m,
                onConfirm = { confirm = null; c.action() }, onDismiss = { confirm = null },
            )
        }
    }
}

/**
 * SETTINGS' place - the page, HOME's highlighted row and its scroll - kept outside the tab
 * (CompanionScreen remembers it), so a visit to MAP and back lands where the player was.
 */
class SettingsUiState(initialPage: String? = null, initialRow: String? = null) {
    internal var page by mutableStateOf(Page.entries.firstOrNull { it.name == initialPage } ?: Page.HOME)
    /** HOME's row (by its English label) to open on, its cursor there and scrolled to (null once done). */
    internal var revealRow by mutableStateOf(initialRow)
    // HOME starts with a group title, so its first row is index 1.
    internal var homeCursor by mutableStateOf(1)
    internal val homeScroll = androidx.compose.foundation.ScrollState(0)
    /** HELP's page and place in the manual. */
    internal val docs = DocsUiState()
}

/** How many chips the player's pick puts in this game's tab bar. */
private fun barCount(s: CompanionSettings, available: List<String>) = s.companionTabs.count { it in available }

/** Saves [tabs] in [COMPANION_TABS] order and tells the tab bar. */
private fun setTabs(s: CompanionSettings, tabs: List<String>, changed: (List<String>) -> Unit) {
    val ordered = COMPANION_TABS.filter { it in tabs }
    s.setCompanionTabs(ordered)
    changed(ordered)
}

/** WIDE on a game it doesn't know yet (it shows as ORIGINAL there). */
private fun wideBadge(s: CompanionSettings, aspect: GameAspect): String? = when {
    aspect != GameAspect.WIDE -> null
    !s.wideSupported -> tk("NOT YET")
    else -> tk("BETA")   // still not perfect (the user's call)
}

/** A pick-one list for a multi-choice setting; [options] are display labels. */
private class Selector(
    val title: String,
    val options: List<String>,
    val current: String,
    val badge: (String) -> String? = { null },
    /** PLAYER LOOK's tiles ([PlayerLookPicker]): [options] are OFF then the heroes' names. */
    val tiles: Boolean = false,
    /** A few words above the options ([OptionSelector]'s note), or null. */
    val note: String? = null,
    val onPick: (String) -> Unit,
)

private class Confirm(val title: String, val message: String, val confirmLabel: String, val action: () -> Unit)

private fun homeRows(
    s: CompanionSettings,
    barTabs: Int,
    changed: () -> Unit,
    navigate: (Page) -> Unit,
    select: (Selector) -> Unit,
): List<SettingRow> {
    fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
    val rateLabels = FF_RATES.map(::rateLabel)
    // MAKEOVER: the game's look from other games (the user's call: its own group, first and standing out, only in a
    // game it works in).
    val looks = s.playerLookSupported
    val makeover = if (!looks && !s.dayNightSupported && !s.followerSupported) emptyList() else listOfNotNull(
        featuredGroupTitle(tk("MAKEOVER"), if (looks) "Other games' looks in this one" else "A fresh look for this game"),
        // Another game's hero for the player (PlayerLook.kt: their sprites from the player's own ROM of that game,
        // patched into this one in memory). The heroes' names are the games' own, so they aren't translated.
        if (!looks) null else SettingRow(
            tk("PLAYER LOOK"),
            if (s.playerLooks.isEmpty()) tk("N/A") else s.playerLook?.let { s.playerLookLabel(it) } ?: tk("OFF"),
            enabled = s.playerLooks.isNotEmpty(),
            labelBadge = NewFeatures.badge(NewFeatures.PLAYER_LOOK),
            // No description line; only why it's greyed out.
            subtitle = if (s.playerLooks.isEmpty()) "Needs FireRed, LeafGreen, Ruby, Sapphire or Emerald too" else null,
        ) {
            // Tiles with each hero's sprite (PlayerLookPicker), not a list of names.
            if (s.playerLooks.isNotEmpty()) {
                val off = tk("OFF")
                select(Selector(tk("PLAYER LOOK"), listOf(off) + s.playerLooks, s.playerLook ?: off, tiles = true) { s.setPlayerLook(it.takeIf { l -> l != off }) })
            }
        },
        // The rival of the other gender (Emerald, Ruby, Sapphire) wears the same game's other hero.
        if (looks && s.rivalLookSupported) {
            val on = s.rivalLook && s.playerLook != null
            SettingRow(
                tk("RIVAL LOOK"), if (s.playerLook == null) tk("N/A") else onOff(s.rivalLook), enabled = s.playerLook != null,
                labelBadge = NewFeatures.badge(NewFeatures.RIVAL_LOOK),
                subtitle = if (s.playerLook == null) "Pick a PLAYER LOOK first" else null,
            ) { if (s.playerLook != null) { s.setRivalLook(!s.rivalLook); changed() } }
        } else null,
        // Another game's dialogue box and letters (TextLook.kt). One style kept: a flip; more: a pick-list.
        if (looks && s.textLooks.isNotEmpty()) {
            val off = tk("OFF")
            SettingRow(
                tk("TEXT LOOK"), s.textLook ?: off, labelBadge = NewFeatures.badge(NewFeatures.TEXT_LOOK),
            ) {
                if (s.textLooks.size == 1) s.setTextLook(if (s.textLook == null) s.textLooks[0] else null).also { changed() }
                else select(Selector(tk("TEXT LOOK"), listOf(off) + s.textLooks, s.textLook ?: off) { s.setTextLook(it.takeIf { l -> l != off }) })
            }
        } else null,
        // The party's lead walking behind the player (pk_wide.c's follower: a hack's following Pokémon, MonArt).
        if (s.followerSupported) {
            SettingRow(
                tk("FOLLOWER"), if (s.followerReady) onOff(s.follower) else tk("N/A"), enabled = s.followerReady,
                labelBadge = NewFeatures.badge(NewFeatures.FOLLOWER),
                subtitle = if (s.followerReady) null else "Needs a game with following Pokémon too",
            ) { if (s.followerReady) { s.setFollower(!s.follower); changed() } }
        } else null,
        // The overworld by the device's clock (DayNight.kt): every GBA game but those with their own.
        if (s.dayNightSupported) {
            SettingRow(tk("DAY AND NIGHT"), onOff(s.dayNight), labelBadge = NewFeatures.badge(NewFeatures.DAY_NIGHT)) {
                s.setDayNight(!s.dayNight); changed()
            }
        } else null,
    )
    return makeover + listOfNotNull(
        groupTitle(tk("FF / SPEED")),
        // On / off - the group's title already says fast-forward.
        SettingRow("FF", onOff(s.ffToggled)) { s.setFfToggled(!s.ffToggled); changed() },
        SettingRow(tk("FF SPEED"), rateLabel(s.ffMaxSpeed), subtitle = "How fast FF runs") {
            select(Selector(tk("FF SPEED"), rateLabels, rateLabel(s.ffMaxSpeed)) { s.setFfMaxSpeed(FF_RATES[rateLabels.indexOf(it)]) })
        },
        // The game's own pace, kept per game: slow-motion that stays (0.5x, 0.25x) up to 4x. FF, when on, wins.
        SettingRow(tk("GAME SPEED"), speedLabel(s.gameSpeed), subtitle = "The pace with FF off, kept per game") {
            val speeds = s.gameSpeeds
            val labels = speeds.map(::speedLabel)
            select(Selector(tk("GAME SPEED"), labels, speedLabel(s.gameSpeed)) { l -> s.setGameSpeed(speeds[labels.indexOf(l)]) })
        },
        // Keeps the last ~20 s; the REWIND HOLD hotkey plays them backwards.
        if (s.rewindSupported) {
            SettingRow(tk("REWIND"), onOff(s.rewind)) { s.setRewind(!s.rewind); changed() }
        } else null,
        // SCENE SPEEDS, FF MODE and FF MUSIC: set once and rarely touched, on their own page.
        SettingRow(tk("SPEED OPTIONS"), null, subtitle = "Scene speeds, FF mode and FF music", opensPage = true) { navigate(Page.SPEED) },
        groupTitle(tk("GAMEPLAY")),
        // The game's own switch for random encounters (WildEncounters.kt): ON (the game's rate) or OFF.
        SettingRow(tk("WILD ENCOUNTERS"), if (s.wildEncountersSupported) wildEncountersLabel(s.wildEncounters) else tk("N/A"),
            enabled = s.wildEncountersSupported) {
            if (s.wildEncountersSupported) { s.setWildEncounters(if (s.wildEncounters == 0) 100 else 0); changed() }
        },
        // A TM stays in the bag after it teaches a move, as an HM does (ReusableTms.kt: the ROM's own check patched).
        // A game whose own TMs never run out (ReusableTms.BUILT_IN) shows ON, greyed, and says so.
        SettingRow(
            tk("REUSABLE TMS"),
            when { s.reusableTmsBuiltIn -> tk("ON"); s.reusableTmsSupported -> onOff(s.reusableTms); else -> tk("N/A") },
            enabled = s.reusableTmsSupported && !s.reusableTmsBuiltIn,
            labelBadge = NewFeatures.badge(NewFeatures.REUSABLE_TMS),
            subtitle = if (s.reusableTmsBuiltIn) "This game's TMs never run out" else null,
        ) {
            if (s.reusableTmsSupported) { s.setReusableTms(!s.reusableTms); changed() }
        },
        // A new wild foe made shiny at these odds before its battle draws it (ShinyOdds.kt; the versions it knows by hash).
        SettingRow(
            tk("SHINY ODDS"), if (s.shinyOddsSupported) shinyOddsLabel(s.shinyOdds) else tk("N/A"),
            enabled = s.shinyOddsSupported, labelBadge = NewFeatures.badge(NewFeatures.SHINY_ODDS),
        ) {
            if (s.shinyOddsSupported) {
                val odds = s.shinyOddsChoices
                val labels = odds.map(::shinyOddsLabel)
                select(
                    Selector(tk("SHINY ODDS"), labels, shinyOddsLabel(s.shinyOdds), note = SHINY_ODDS_NOTE) { l ->
                        s.setShinyOdds(odds[labels.indexOf(l)])
                    },
                )
            }
        },
        // The NUZLOCKE tab and its tracker for this game (Nuzlocke.kt): first encounter per area, fainted = out of the run.
        SettingRow(
            tk("NUZLOCKE"), if (s.nuzlockeSupported) onOff(s.nuzlocke) else tk("N/A"), enabled = s.nuzlockeSupported,
            labelBadge = NewFeatures.badge(NewFeatures.NUZLOCKE),
            subtitle = tk("Track a run: one catch per area, a faint is final"),
        ) {
            if (s.nuzlockeSupported) { s.setNuzlocke(!s.nuzlocke); changed() }
        },
        // B held for the player while walking on the field; holding it walks. Never on a bike or in a menu.
        SettingRow(tk("AUTO-RUN"), if (s.fieldReadable) onOff(s.autoRun) else tk("N/A"), enabled = s.fieldReadable) { if (s.fieldReadable) { s.setAutoRun(!s.autoRun); changed() } },
        // Every map lets the player run (buildings too); FireRed / Emerald engines.
        SettingRow(tk("RUN INDOORS"), if (s.runIndoorsSupported) onOff(s.runIndoors) else tk("N/A"), enabled = s.runIndoorsSupported) { if (s.runIndoorsSupported) { s.setRunIndoors(!s.runIndoors); changed() } },
        // This game's cheats on / off (the codes are typed or imported in the top screen's Settings).
        SettingRow(tk("CHEATS"), cheatsValue(s), opensPage = true) { navigate(Page.CHEATS) },
        groupTitle(tk("SCREEN")),
        // The game at the GBA's 3:2, stretched to fill the top screen, or WIDE (the overworld drawn past
        // its edges); WIDE is tagged BETA, or NOT YET on a game it doesn't know (which shows it as ORIGINAL).
        SettingRow(tk("ASPECT"), aspectLabel(s.gameAspect), wideBadge(s, s.gameAspect), subtitle = "Original shape, stretched, or more of the map") {
            val aspects = GameAspect.entries
            select(
                Selector(
                    tk("ASPECT"), aspects.map { it.label }, s.gameAspect.label,
                    badge = { l -> wideBadge(s, aspects.first { it.label == l }) },
                ) { l -> s.setGameAspect(aspects.first { it.label == l }) },
            )
        },
        // EXPAND only: whether a conversation, sign, menu or picture over the field pulls the zoom in (battles always do).
        SettingRow(tk("AUTO ZOOM IN"), onOff(s.expandZoomOnText), subtitle = "1x on dialogue, signs, menus") { s.setExpandZoomOnText(!s.expandZoomOnText); changed() }
            .takeIf { s.gameAspect == GameAspect.WIDE },
        // A DS game: its 3D at 2x to 8x the DS's own on the GPU, or ORIGINAL (the CPU's, pixel for pixel).
        s.dsResolution?.let { scale ->
            SettingRow(
                tk("DS RESOLUTION"), if (s.dsGpuAvailable) dsResolutionLabel(scale) else tk("N/A"), enabled = s.dsGpuAvailable,
                labelBadge = NewFeatures.badge(NewFeatures.DS_RESOLUTION), subtitle = "Sharper 3D in DS games",
            ) {
                if (!s.dsGpuAvailable) return@SettingRow
                val labels = dsResolutionLabels()
                select(Selector(tk("DS RESOLUTION"), labels, dsResolutionLabel(scale)) { l -> s.setDsResolution(labels.indexOf(l) + 1); changed() })
            }
        },
        // The party, a minimap, the balls and BAG / WILD over the game on the field: its own page (layout, look, size, parts).
        SettingRow(tk("HUD"), onOff(s.fieldHud), labelBadge = NewFeatures.badge(NewFeatures.HUD), opensPage = true, subtitle = "Party, minimap and bag over the game") { navigate(Page.FIELD_HUD) },
        // One screen: FIGHT, POKéMON and RUN in the room beside a battle (BattleButtons).
        SettingRow(
            tk("BATTLE BUTTONS"), onOff(s.battleButtons), subtitle = "FIGHT, POKéMON and RUN beside a battle",
            labelBadge = NewFeatures.badge(NewFeatures.BATTLE_BUTTONS),
        ) { s.setBattleButtons(!s.battleButtons); changed() }.takeIf { !s.usesSecondScreen },
        // STATUS BAR, SHADERS, OVERLAY (and an upright phone's COMPANION): on their own page.
        SettingRow(tk("DISPLAY OPTIONS"), null, subtitle = "Status bar, shaders, overlay", opensPage = true) { navigate(Page.DISPLAY) },
        // One screen sideways: a window in the middle over the game, or the side panel (this companion switches at once).
        s.companionWindow?.let { window ->
            SettingRow(
                tk("COMPANION VIEW"), companionViewLabel(window),
                labelBadge = NewFeatures.badge(NewFeatures.COMPANION_WINDOW), subtitle = "A window over the game, or a side panel",
            ) { s.setCompanionWindow(!window); changed() }
        },
        // The white tab at the screen's right edge that opens it (BACK and the HUD's COMPANION still do).
        s.companionTab?.let { tab ->
            SettingRow(
                tk("COMPANION BUTTON"), onOff(tab),
                labelBadge = NewFeatures.badge(NewFeatures.COMPANION_TAB), subtitle = "The tab at the right edge that opens it",
            ) { s.setCompanionTab(!tab); changed() }
        },
        // Two screens: the game and the companion trade screens (this companion moves with them).
        SettingRow(tk("SWAP SCREENS"), onOff(s.swapScreens)) { s.setSwapScreens(!s.swapScreens); changed() }
            .takeIf { s.usesSecondScreen },
        // Two screens played on one: the second is free for other apps, the companion opens over the game.
        SettingRow(
            tk("SINGLE SCREEN"), onOff(s.singleScreen), subtitle = "Second screen free; companion over the game",
            labelBadge = NewFeatures.badge(NewFeatures.SINGLE_SCREEN),
        ) { s.setSingleScreen(!s.singleScreen); changed() }.takeIf { s.hasSecondScreen },
        // OFF / ON / AUTO (the device's), cycled on a tap so each look shows at once: both screens switch together.
        SettingRow(tk("DARK MODE"), DarkMode.setting.label) {
            val pick = DarkMode.setting.next()
            DarkMode.setting = pick
            s.setDarkMode(pick.ordinal)
        },
        // The game's frames per second and the app's CPU share, in the game's corner.
        SettingRow(tk("FPS / CPU"), onOff(s.showPerformance), subtitle = "Speed and CPU use in the game's corner") { s.setShowPerformance(!s.showPerformance); changed() },
        groupTitle(tk("COMPANION")),
        // Move effectiveness and the foe's weak-to/resists during battle.
        SettingRow(tk("BATTLE HINTS"), onOff(s.showHints), subtitle = "Move ratings and the best pick in battle") { s.setShowHints(!s.showHints); changed() },
        // The battle controls' action menu shows the moves: one tap picks FIGHT and the move.
        SettingRow(tk("ONE-TAP MOVES"), onOff(s.oneTapMoves), subtitle = "Your moves right on the battle menu") { s.setOneTapMoves(!s.oneTapMoves); changed() },
        // A STATS page in battle INFO with the foe's IVs / EVs / nature.
        SettingRow(tk("FOE IVS"), onOff(s.showFoeIvs), subtitle = "The foe's hidden stats in battle") { s.setShowFoeIvs(!s.showFoeIvs); changed() },
        SettingRow(tk("TAB BAR"), tr("{0} TABS", barTabs), labelBadge = NewFeatures.badge(NewFeatures.MORE_TAB) ?: NewFeatures.badge(NewFeatures.BOXES_TAB), opensPage = true, subtitle = "Which tabs sit next to the gear") { navigate(Page.TABS) },
        // The game's menu click on every companion button.
        SettingRow(tk("CLICK SOUND"), onOff(s.clickSound)) { s.setClickSound(!s.clickSound); changed() },
        // Small on / off preferences: the icons' bounce, the map cursor's blink, tab slides, the battle jump.
        SettingRow(tk("TWEAKS"), null, opensPage = true, subtitle = "Small animations and the battle jump") { navigate(Page.TWEAKS) },
        groupTitle(tk("CONTROLS")),
        SettingRow(tk("GAME BUTTONS"), null, subtitle = "Key bindings: which button presses A, B, START...", opensPage = true) { navigate(Page.BUTTONS) },
        SettingRow(tk("HOTKEYS"), onOff(s.hotkeysEnabled), opensPage = true, subtitle = "Button combos for states, FF and more") { navigate(Page.HOTKEYS) },
        // PC, FLY, a Repel, the bike, a rod, states, FF... on a held button (L3 by default): its own page.
        SettingRow(tk("QUICK MENU"), null, subtitle = "Actions on a held button", labelBadge = quickMenuBadge(), opensPage = true) { navigate(Page.QUICK_MENU) },
        // The on-screen buttons: when they show, layouts, size, extras, EDIT LAYOUT - its own page, with a preview.
        SettingRow(tk("TOUCH CONTROLS"), s.touchStyle.show.label, labelBadge = touchControlsBadge(), opensPage = true, subtitle = "The on-screen buttons: layout, size, extras") { navigate(Page.TOUCH) },
        groupTitle(tk("APP")),
        // A card now and then about a feature the player hasn't found (FeatureTips).
        tipsRow(s.tips) { s.setTips(it); changed() },
        // AUTO: the ROM's own language. Applies at once, both screens.
        SettingRow("LANGUAGE", L10n.settingLabel(s.appLanguage)) {
            val opts = L10n.options
            select(Selector("LANGUAGE", opts.map { it.second }, opts.first { it.first == s.appLanguage }.second) { l ->
                s.setAppLanguage(opts.first { it.second == l }.first)
            })
        },
        // PokeDaisy's Discord server, opened in the browser (or the Discord app) on the main screen.
        SettingRow(
            "DISCORD", null, subtitle = "Help, bug reports, ideas and chat",
            labelBadge = NewFeatures.badge(NewFeatures.DISCORD), labelIcon = { DiscordMark() },
        ) { s.openDiscord() },
        // The manual: every tab and setting explained (served by the website, kept for offline reading).
        SettingRow(tk("HELP"), null, subtitle = "The manual: how every screen and setting works", opensPage = true) { navigate(Page.HELP) },
    )
}

/** CHEATS' value on the options: OFF, or how many of this game's cheats are on. */
private fun cheatsValue(s: CompanionSettings): String =
    if (!s.cheatsEnabled) tk("OFF") else tr("{0} ON", s.cheats.count { it.enabled })

/**
 * CHEATS: the master switch, then one row per cheat of this game, each flipping
 * live (the game loads it on the next frame). Adding codes needs a keyboard, so
 * that's the top screen's Settings > CHEATS; with none yet, this page says so.
 */
private fun cheatRows(s: CompanionSettings, changed: () -> Unit): List<SettingRow> {
    fun onOff(on: Boolean) = if (on) tk("ON") else tk("OFF")
    val master = s.cheatsEnabled
    val cheats = s.cheats
    return listOf(SettingRow(tk("CHEATS"), onOff(master), subtitle = "GameShark, Action Replay and CodeBreaker codes") { s.setCheatsEnabled(!master); changed() }) +
        if (cheats.isEmpty()) {
            listOf(
                SettingRow(tk("NO CHEATS FOR THIS GAME"), null, enabled = false) {},
                SettingRow(tk("ADD THEM IN SETTINGS ON THE TOP SCREEN"), null, enabled = false) {},
            )
        } else {
            // Greyed out with the master switch off, like HOTKEYS' binds.
            cheats.mapIndexed { i, c ->
                SettingRow(c.name, onOff(c.enabled), enabled = master) { s.setCheatEnabled(i, !c.enabled); changed() }
            }
        }
}


/** SPEED OPTIONS: SCENE SPEEDS, then how fast-forward behaves and sounds. */
private fun speedRows(s: CompanionSettings, changed: () -> Unit, select: (Selector) -> Unit): List<SettingRow> =
    listOf(groupTitle(tk("SCENE SPEEDS"))) + sceneRows(s, changed, select) + listOf(
        groupTitle(tk("FAST-FORWARD")),
        // SMART: menus (party, bag, ... in battle too) and the region map at 1x; NORMAL: fast everywhere.
        SettingRow(tk("FF MODE"), s.ffMode.label, subtitle = "SMART slows down for menus and the map") {
            val modes = FfMode.entries
            select(Selector(tk("FF MODE"), modes.map { it.label }, s.ffMode.label) { l -> s.setFfMode(modes.first { it.label == l }); changed() })
        },
        // STEADY / SPED-UP / OFF (FfMusicMode's order).
        SettingRow(tk("FF MUSIC"), s.ffMusicMode.label, subtitle = "What you hear while fast-forwarding") {
            val modes = FfMusicMode.entries
            select(Selector(tk("FF MUSIC"), modes.map { it.label }, s.ffMusicMode.label) { l -> s.setFfMusicMode(modes.first { it.label == l }); changed() })
        },
    )

/** DISPLAY OPTIONS: what's around the game (the status bar, the shaders, an overlay) and an upright phone's companion. */
private fun displayRows(s: CompanionSettings, changed: () -> Unit, navigate: (Page) -> Unit, select: (Selector) -> Unit): List<SettingRow> =
    listOfNotNull(
        // Game, location, money, clock and battery: OFF, over the game, or over these tabs.
        SettingRow(tk("STATUS BAR"), statusBarLabel(s.statusBar, s.statusBarOnCompanion), subtitle = "Place, money, clock and battery") {
            select(Selector(tk("STATUS BAR"), STATUS_BAR_PLACES, statusBarLabel(s.statusBar, s.statusBarOnCompanion)) {
                val i = STATUS_BAR_PLACES.indexOf(it)
                if (i > 0) s.setStatusBarOnCompanion(i == 2)
                s.setStatusBar(i > 0)
            })
        },
        // FILTER (LCD / LCD PAPER / SCANLINES / CRT) and GBA COLORS, on their own page.
        SettingRow(tk("SHADERS"), s.screenFilter.label, opensPage = true, subtitle = "LCD, CRT and scanline looks, smoothing") { navigate(Page.SHADERS) },
        // A frame around the game: NONE, the built-in ones, the player's imports (added on the top screen). Live.
        s.overlayChoices.takeIf { it.isNotEmpty() }?.let { choices ->
            val current = choices.firstOrNull { it.first == s.overlay }?.second ?: choices.first().second
            SettingRow(tk("OVERLAY"), current, subtitle = "A frame around the game") {
                select(Selector(tk("OVERLAY"), choices.map { it.second }, current) { l ->
                    choices.firstOrNull { it.second == l }?.let { s.setOverlay(it.first) }
                })
            }
        },
        // A slow device: the companion redrawn less, without animations, so the game's buttons don't wait on it.
        lightCompanionRow({ title, choices, current, onPick -> select(Selector(title, choices, current) { onPick(it); changed() }) }) { s.setLightCompanion(it) },
        // A phone upright: the companion along the screen's bottom, or right under the game (the touch controls below it).
        s.portraitUnderGame?.let { under ->
            uprightCompanionRow(under, s.gameAspect == GameAspect.WIDE && s.wideSupported, s.touchControlsShown) { s.setPortraitUnderGame(it); changed() }
        },
    )

/** SCENE SPEEDS (GitHub #43 / #85): each one SAME (GAME SPEED's) or 1x ... 4x, while FF is off; it applies at once. */
private fun sceneRows(s: CompanionSettings, changed: () -> Unit, select: (Selector) -> Unit): List<SettingRow> {
    val labels = SCENE_SPEEDS.map(::sceneSpeedLabel)
    fun row(label: String, value: Float, enabled: Boolean = true, set: (Float) -> Unit) =
        SettingRow(label, if (enabled) sceneSpeedLabel(value) else tk("N/A"), enabled = enabled) {
            if (enabled) select(Selector(label, labels, sceneSpeedLabel(value)) { l -> set(SCENE_SPEEDS[labels.indexOf(l)]); changed() })
        }
    return listOf(
        // The whole battle: its text, its animations, the turns (its bag and party screens are MENU's).
        row(tk("BATTLE SPEED"), s.battleSpeed, set = s::setBattleSpeed),
        // A text box (or the START menu) up on the field: the words come out that much faster.
        row(tk("TEXT SPEED"), s.textSpeed, s.fieldReadable, s::setTextSpeed),
        // Menu screens: the party, the bag, the summary, the PC, the region map...
        row(tk("MENU SPEED"), s.menuSpeed, set = s::setMenuSpeed),
        // Riding a bike / surfing on the field: the whole game at that speed meanwhile (the player's own state, read
        // where the game's player struct is found - N/A until then).
        row(tk("BIKE SPEED"), s.bikeSpeed, s.fieldMovesReadable, s::setBikeSpeed),
        row(tk("SURF SPEED"), s.surfSpeed, s.fieldMovesReadable, s::setSurfSpeed),
    )
}

private fun sceneSpeedLabel(v: Float) = if (v <= 0f) tk("SAME") else speedLabel(v)

/** SHADERS: what the game is drawn through - flips live, the game shows it at once. */
private fun shaderRows(s: CompanionSettings, changed: () -> Unit): List<SettingRow> {
    return listOf(
        // NONE / LCD grid / LCD PAPER / SCANLINES / CRT.
        // Each tap steps to the next filter, so the game shows each look in turn.
        SettingRow(tk("FILTER"), s.screenFilter.label) { s.setScreenFilter(s.screenFilter.next()); changed() },
        // SOFT / MEDIUM / STRONG, cycling too; greyed out for a filter with no grid.
        SettingRow(tk("GRID"), s.gridStrength.label, enabled = s.screenFilter.hasGrid) {
            if (s.screenFilter.hasGrid) { s.setGridStrength(s.gridStrength.next()); changed() }
        },
        // OFF / xBRZ / xBR / SCALEFX, cycling too: smooths the game's edges before FILTER (the game only).
        SettingRow(tk("SMOOTHING"), if (s.smoothingAvailable) s.smoothing.label else tk("N/A"), enabled = s.smoothingAvailable) {
            if (s.smoothingAvailable) { s.setSmoothing(s.smoothing.next()); changed() }
        },
        // The colours as the GBA's own LCD showed them; stacks with any filter.
        SettingRow(tk("GBA COLORS"), if (s.gbaColors) tk("ON") else tk("OFF")) { s.setGbaColors(!s.gbaColors); changed() },
        // The GBA's screen with no light of its own: white shows its backing, dark pixels cast a shadow; stacks too.
        SettingRow(tk("UNLIT SCREEN"), if (s.unlitScreen) tk("ON") else tk("OFF"), labelBadge = NewFeatures.badge(NewFeatures.UNLIT_SCREEN)) {
            s.setUnlitScreen(!s.unlitScreen); changed()
        },
        // FILTER and GBA COLORS over this screen too (and the status bar).
        SettingRow(tk("ON COMPANION"), if (s.companionShaders) tk("ON") else tk("OFF")) { s.setCompanionShaders(!s.companionShaders); changed() },
    )
}


private fun rateLabel(v: Float) = if (v <= 0f) tk("INFINITE") else speedLabel(v)

/** 2×, 1.5×, 0.25× (Pixel Operator has no ½ / ¼). */
internal fun speedLabel(v: Float) = (if (v % 1f == 0f) "${v.toInt()}" else "$v") + "×"

/** A binding row's value: its keys, or (while it waits) what to press and the keys held so far. */
private fun bindingValue(capture: KeyCapture?, title: String, keys: List<String>?): String {
    val r = capture?.request?.takeIf { capture.isFor(title) } ?: return KeyCapture.labels(keys)
    return keyCaptureValue(capture, r)
}

/** A tap on a binding row: waits for its keys, or stops waiting when it already was. */
private fun KeyCapture.toggle(title: String, request: () -> KeyCapture.Request) {
    if (isFor(title)) cancel() else start(request())
}

/** WILD ENCOUNTERS' value: ON (the game's own rate) or OFF. */
internal fun wildEncountersLabel(percent: Int) = if (percent == 0) tk("OFF") else tk("ON")

/** SHINY ODDS' value: NORMAL (the game's own 1 in 8192), else "1 IN n". */
private fun shinyOddsLabel(odds: Int): String =
    if (odds >= com.pokedaisy.app.companion.data.ShinyOdds.NORMAL) tk("NORMAL") else tr("1 IN {0}", odds)

/** SHINY ODDS' pick-list disclaimer: what a save checker may say, and the achievements' pause. */
private const val SHINY_ODDS_NOTE = "Save checkers may flag these shinies. Achievements pause."
