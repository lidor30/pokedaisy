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
    val skipping = when (s.step) {
        SetupState.Step.ROMS -> s.romsFolders.isEmpty()
        SetupState.Step.SAVES, SetupState.Step.BUTTONS -> false
        SetupState.Step.MEDIA -> s.ssLogin.signedInAs == null
        SetupState.Step.COVERS -> !s.keySaved
    }
    com.pokedaisy.app.companion.ui.SetupFrame(s.index, steps.size, s.canGoBack, s.scanning,
        last = s.step == steps.last(), skipping = skipping, m, onBack, onNext) {
        when (s.step) {
            SetupState.Step.ROMS -> com.pokedaisy.app.companion.ui.SetupRomsPage(
                com.pokedaisy.app.companion.ui.SetupRomsModel(s.romsFolders, s.found, s.scanning, s.checked, s.toCheck, s.hasAccess),
                m, small, onChooseRoms, onRemoveRoms)
            SetupState.Step.SAVES -> com.pokedaisy.app.companion.ui.SetupSavesPage(
                com.pokedaisy.app.companion.ui.SetupSavesModel(s.savesDir,
                    s.suggestions.map { com.pokedaisy.app.companion.ui.SetupSaveFolder(it.dir.absolutePath, it.saves) }, s.hasAccess),
                m, small, onChooseSaves, onUseSaves)
            SetupState.Step.BUTTONS -> com.pokedaisy.app.companion.ui.SetupButtonsPage(s.buttons, s.capture, m, small, buttonRequest)
            SetupState.Step.MEDIA -> ScreenScraperSetupStep(s.ssLogin, s.covers, m, small,
                onSignIn = onScreenScraperSignIn, onSignOut = onScreenScraperSignOut,
                onSignUp = { onOpenPage(ScreenScraperClient.SIGN_UP_PAGE) })
            SetupState.Step.COVERS -> CoversStep(s, m, small, onOpenPage, onSaveKey)
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
internal fun shortPath(path: String): String = com.pokedaisy.app.companion.ui.setupPath(path)
