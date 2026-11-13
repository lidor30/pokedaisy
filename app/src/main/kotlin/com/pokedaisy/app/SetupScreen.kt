package com.pokedaisy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.AppBackdrop
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.KeyBindRow
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionLine
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionTextField
import com.pokedaisy.app.companion.ui.OptionTitleWindow

/**
 * First-time setup, on the library's screen: link ROMs folders, pick where saves
 * go, check the buttons, sign in to ScreenScraper (game media), add a cover key - each one
 * skippable, each one also in Settings.
 * [LibraryActivity] owns the folder pickers and background work and feeds this.
 */
internal class SetupState {
    enum class Step { ROMS, SAVES, BUTTONS, MEDIA, COVERS }

    /** The steps shown: GAME MEDIA only while ScreenScraper is on. */
    val steps: List<Step> = Step.entries.filter { it != Step.MEDIA || ScreenScraperClient.enabled }
    var step by mutableStateOf(Step.ROMS)
    val index get() = steps.indexOf(step)
    /** Opened by Settings' RUN SETUP: BACK on the first step returns there. */
    var fromSettings by mutableStateOf(false)
    /** Whether BACK has somewhere to go: an earlier step, or Settings. */
    val canGoBack get() = index > 0 || fromSettings
    /** False until All files access is granted - picking a folder asks for it first. */
    var hasAccess by mutableStateOf(true)
    /** The folder pick waiting on Android's All files access page, resumed on return. */
    var awaitingAccess: Step? = null

    /** The linked ROMs folders, as [Prefs.romsFolders] has them. */
    var romsFolders by mutableStateOf<List<String>>(emptyList())
    var scanning by mutableStateOf(false)
    var checked by mutableIntStateOf(0)
    var toCheck by mutableIntStateOf(0)
    /** Names of the supported games in [romsFolders]; null until a scan finishes. */
    var found by mutableStateOf<List<String>?>(null)

    /** Null = the app's own saves folder. */
    var savesDir by mutableStateOf<String?>(null)
    var suggestions by mutableStateOf<List<SavesLocation.Suggestion>>(emptyList())

    /** GAME BUTTONS: each GBA button's keys as bound now, read again after every change. */
    var buttons by mutableStateOf<Map<GbaControls.Btn, List<String>>>(emptyMap())
    /** GAME BUTTONS: the row waiting for a button press (every key goes to it meanwhile). */
    val capture = KeyCapture()

    /** GAME MEDIA: the ScreenScraper sign-in (its media pass runs in [covers]). */
    var ssLogin by mutableStateOf(ScreenScraperLogin())

    var apiKey by mutableStateOf("")
    var raKey by mutableStateOf("")
    var keySaved by mutableStateOf(false)
    val covers = CoverSync()
}

@Composable
internal fun SetupScreen(
    s: SetupState,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onChooseRoms: () -> Unit,
    /** REMOVE on one of the ROMS step's folders. */
    onRemoveRoms: (String) -> Unit = {},
    onChooseSaves: () -> Unit,
    onUseSaves: (String?) -> Unit,
    onOpenPage: (String) -> Unit,
    onSaveKey: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    /** GAME BUTTONS: what binding [GbaControls.Btn] does once its button is pressed (the step's rows bind in place). */
    buttonRequest: (GbaControls.Btn) -> KeyCapture.Request = { KeyCapture.Request(it.title, chord = false, onDone = {}) },
    onScreenScraperSignIn: () -> Unit = {},
    onScreenScraperSignOut: () -> Unit = {},
) {
    val steps = s.steps
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(
                tr("WELCOME TO POKéDAISY"), m,
                trailing = "${s.index + 1} / ${steps.size}",
                onBack = if (s.canGoBack) onBack else null,
            )
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.u * 4)) {
                    when (s.step) {
                        SetupState.Step.ROMS -> RomsStep(s, m, small, onChooseRoms, onRemoveRoms)
                        SetupState.Step.SAVES -> SavesStep(s, m, small, onChooseSaves, onUseSaves)
                        SetupState.Step.BUTTONS -> ButtonsStep(s, m, small, buttonRequest)
                        SetupState.Step.MEDIA -> ScreenScraperSetupStep(
                            s.ssLogin, s.covers, m, small,
                            onSignIn = onScreenScraperSignIn, onSignOut = onScreenScraperSignOut,
                            onSignUp = { onOpenPage(ScreenScraperClient.SIGN_UP_PAGE) },
                        )
                        SetupState.Step.COVERS -> CoversStep(s, m, small, onOpenPage, onSaveKey)
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (s.canGoBack) {
                    OptionButton(tr("BACK"), m, enabled = !s.scanning, modifier = Modifier.widthIn(min = 160.dp), onClick = onBack)
                }
                Spacer(Modifier.weight(1f))
                val last = s.step == steps.last()
                val skipping = when (s.step) {
                    SetupState.Step.ROMS -> s.romsFolders.isEmpty()
                    SetupState.Step.SAVES -> false
                    SetupState.Step.BUTTONS -> false
                    SetupState.Step.MEDIA -> s.ssLogin.signedInAs == null
                    SetupState.Step.COVERS -> !s.keySaved
                }
                OptionButton(
                    when {
                        skipping -> tr("SKIP")
                        last -> tr("DONE")
                        else -> tr("NEXT|continue")
                    },
                    m, emphasis = !skipping, enabled = !s.scanning,
                    modifier = Modifier.widthIn(min = 160.dp),
                    onClick = onNext,
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String, m: GbaTextMetrics) {
    GbaText(text, OptionColors.label, OptionColors.labelShadow, m, Modifier.padding(top = m.u * 2, bottom = m.u * 2))
}

@Composable
private fun Para(text: String, small: GbaTextMetrics, muted: Boolean = false) {
    GbaText(
        text,
        if (muted) OptionColors.muted else OptionColors.label,
        if (muted) OptionColors.mutedShadow else OptionColors.labelShadow,
        small, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(bottom = small.u * 4),
    )
}

@Composable
private fun RomsStep(s: SetupState, m: GbaTextMetrics, small: GbaTextMetrics, onChoose: () -> Unit, onRemove: (String) -> Unit) {
    Heading(tr("YOUR ROMS FOLDERS"), m)
    Para(
        tr("Pick the folders you keep your games in. PokéDaisy adds every game from them that the second screen supports, plays them right where they are (nothing is copied), and looks for new ones each time you open the app."),
        small,
    )
    Para(
        tr("You can add several. Picking just the folders with your games (gba, gbc, nds) is quicker to look through than one big folder of everything."),
        small, muted = true,
    )
    if (!s.hasAccess) {
        Para(
            tr("Android asks you to allow All files access first, so PokéDaisy can open games and saves outside its own folder. Turn it on and come back here."),
            small, muted = true,
        )
    }
    s.romsFolders.forEach { folder ->
        Row(Modifier.fillMaxWidth().padding(bottom = m.u * 3), verticalAlignment = Alignment.CenterVertically) {
            GbaText(shortPath(folder), OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(m.u * 4))
            OptionButton(tr("REMOVE"), small, enabled = !s.scanning, onClick = { onRemove(folder) })
        }
    }
    OptionButton(if (s.romsFolders.isEmpty()) tr("CHOOSE FOLDER") else tr("ADD FOLDER"), m, emphasis = s.romsFolders.isEmpty(), enabled = !s.scanning, onClick = onChoose)
    Spacer(Modifier.height(m.u * 4))
    if (s.romsFolders.isNotEmpty()) {
        RomsFound(s, m, small)
        Spacer(Modifier.height(m.u * 4))
    }
    Para(
        tr("This is optional: skip it and add games one at a time with + in the library. You can add folders later with + or in SETTINGS > FOLDERS."),
        small, muted = true,
    )
}

/** The ROMS step's scan: its progress, then the games it found. */
@Composable
private fun RomsFound(s: SetupState, m: GbaTextMetrics, small: GbaTextMetrics) {
    val found = s.found
    when {
        s.scanning -> {
            GbaText(tr("LOOKING FOR GAMES… {0} / {1}", s.checked, s.toCheck), OptionColors.label, OptionColors.labelShadow, m)
            Spacer(Modifier.height(m.u * 3))
            SyncProgressBar(if (s.toCheck > 0) s.checked.toFloat() / s.toCheck else 0f, m)
        }
        found == null -> {}
        found.isEmpty() -> Para(
            tr("No supported games in there. Add another folder, or skip and add games with + later."), small,
        )
        else -> {
            GbaText(if (found.size == 1) tr("FOUND 1 GAME") else tr("FOUND {0} GAMES", found.size), OptionColors.value, OptionColors.valueShadow, m)
            Spacer(Modifier.height(m.u * 2))
            // Two columns: the landscape screen fits more games without scrolling.
            found.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = small.u), horizontalArrangement = Arrangement.spacedBy(m.u * 8)) {
                    pair.forEach { GbaText(it, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SavesStep(
    s: SetupState,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onChoose: () -> Unit,
    onUse: (String?) -> Unit,
) {
    Heading(tr("GAME SAVES"), m)
    Para(
        tr("Where should your saves go? If you already play these games in another emulator, pick its save folder (RetroArch's, for one) and carry on with the same saves: a game finds its save by the ROM's file name (.sav or .srm)."),
        small,
    )
    val listed = s.suggestions.map { it.dir.absolutePath }
    OptionLine(tr("KEEP THEM IN POKéDAISY"), tr("DEFAULT"), selected = s.savesDir == null, m, labelWeight = 0.75f, divider = true) { onUse(null) }
    s.suggestions.forEach { sug ->
        OptionLine(
            shortPath(sug.dir.absolutePath), if (sug.saves == 1) tr("1 SAVE") else tr("{0} SAVES", sug.saves),
            selected = s.savesDir == sug.dir.absolutePath, m, labelWeight = 0.75f, divider = true,
        ) { onUse(sug.dir.absolutePath) }
    }
    s.savesDir?.takeIf { it !in listed }?.let { dir ->
        OptionLine(shortPath(dir), null, selected = true, m, labelWeight = 0.75f, divider = true) { }
    }
    OptionLine(tr("CHOOSE ANOTHER FOLDER…"), null, selected = false, m, onClick = onChoose)
    Spacer(Modifier.height(m.u * 4))
    if (!s.hasAccess) {
        Para(tr("Choosing a folder asks for All files access first, as games and saves are opened by path."), small, muted = true)
    }
    Para(tr("You can change this later in SETTINGS > FOLDERS."), small, muted = true)
}

/** GAME BUTTONS (key bindings): what each GBA button is now; a tap on one waits for the device's button, right here. */
@Composable
private fun ButtonsStep(s: SetupState, m: GbaTextMetrics, small: GbaTextMetrics, request: (GbaControls.Btn) -> KeyCapture.Request) {
    Heading(tr("GAME BUTTONS"), m)
    Para(
        tr("Which button on your device presses each GBA button - key bindings, or remapping, in other emulators. Check A and B first: some devices print them the other way round."),
        small,
    )
    Para(tr("To change one, tap it and press the button you want for it. The D-pad and analog stick are fixed."), small, muted = true)
    GbaControls.Group.entries.forEach { group ->
        com.pokedaisy.app.companion.ui.GroupTitleLine(group.title, m, first = group == GbaControls.Group.ALL)
        val btns = GbaControls.Btn.entries.filter { it.group == group && !it.dpad }   // (the D-pad rows: SETTINGS only)
        btns.forEachIndexed { i, btn ->
            KeyBindRow(
                btn.title, s.buttons[btn], s.capture, m, small, divider = i < btns.lastIndex, subtitle = btn.subtitle,
            ) { request(btn) }
        }
    }
    Spacer(Modifier.height(m.u * 4))
    Para(tr("You can change this later in SETTINGS > GAME BUTTONS."), small, muted = true)
}

@Composable
private fun CoversStep(
    s: SetupState,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onOpenPage: (String) -> Unit,
    onSaveKey: () -> Unit,
) {
    Heading(tr("COVER ART (OPTIONAL)"), m)
    Para(
        tr("Library covers are box art from RetroAchievements, or icons from SteamGridDB for games it doesn't have. Each needs your own free key: open the site, copy the key and paste it below - one is enough."),
        small,
    )
    Para(tr("Skip this if you like - you can add a key any time in SETTINGS > COVER ART."), small, muted = true)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
        OptionButton(tr("OPEN {0}", "RetroAchievements"), m, onClick = { onOpenPage(CoverArtSync.RA_KEY_PAGE) })
        OptionTextField(s.raKey, { s.raKey = it }, m, Modifier.weight(1f), placeholder = tr("paste key here"))
    }
    Spacer(Modifier.height(m.u * 4))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u * 4)) {
        OptionButton(tr("OPEN {0}", "SteamGridDB"), m, onClick = { onOpenPage(CoverArtSync.STEAMGRIDDB_KEY_PAGE) })
        OptionTextField(s.apiKey, { s.apiKey = it }, m, Modifier.weight(1f), placeholder = tr("paste key here"))
    }
    Spacer(Modifier.height(m.u * 4))
    Row {
        Spacer(Modifier.weight(1f))
        OptionButton(
            if (s.covers.running) tr("FETCHING…") else tr("SAVE"), m, emphasis = true,
            enabled = !s.covers.running && (s.apiKey.isNotBlank() || s.raKey.isNotBlank()), onClick = onSaveKey,
        )
    }
    if (s.covers.running || s.covers.log.isNotEmpty()) {
        Spacer(Modifier.height(m.u * 6))
        CoverSyncStatus(s.covers, m)
    }
}

/** A folder as the player knows it: internal storage's prefix dropped, an SD card named as one. */
internal fun shortPath(path: String): String = when {
    path.startsWith("/storage/emulated/0/") -> path.removePrefix("/storage/emulated/0/")
    path.startsWith("/storage/") -> tr("SD CARD") + "/" + path.removePrefix("/storage/").substringAfter('/')
    else -> path
}
