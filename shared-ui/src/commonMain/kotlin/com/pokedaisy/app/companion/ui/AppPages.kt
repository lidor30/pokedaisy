package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import com.pokedaisy.app.companion.Discord
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.companion.KeyCapture
import com.pokedaisy.app.companion.i18n.tr

data class SetupRomsModel(
    val romsFolders: List<String>, val found: List<String>?, val scanning: Boolean = false,
    val checked: Int = 0, val toCheck: Int = 0, val hasAccess: Boolean = true, val linked: Boolean = true,
)
data class SetupSaveFolder(val path: String, val saves: Int)
data class SetupSavesModel(
    val savesDir: String?, val suggestions: List<SetupSaveFolder> = emptyList(), val hasAccess: Boolean = true,
)

/** The existing setup frame, independent of file pickers, credentials and background services. */
@Composable
fun SetupFrame(
    index: Int, total: Int, canGoBack: Boolean, busy: Boolean, last: Boolean, skipping: Boolean,
    m: GbaTextMetrics, onBack: () -> Unit, onNext: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AppBackdrop()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OptionTitleWindow(tr("WELCOME TO POKéDAISY"), m, trailing = "${index + 1} / $total",
                onBack = if (canGoBack) onBack else null)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.u * 4), content = content)
            }
            Spacer(Modifier.height(m.u * 4))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack) OptionButton(tr("BACK"), m, enabled = !busy, modifier = Modifier.widthIn(min = 160.dp), onClick = onBack)
                Spacer(Modifier.weight(1f))
                OptionButton(when { skipping -> tr("SKIP"); last -> tr("DONE"); else -> tr("NEXT|continue") }, m,
                    emphasis = !skipping, enabled = !busy, modifier = Modifier.widthIn(min = 160.dp), onClick = onNext)
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
fun SetupRomsPage(s: SetupRomsModel, m: GbaTextMetrics, small: GbaTextMetrics, onChoose: () -> Unit, onRemove: ((String) -> Unit)? = null) {
    Heading(tr(if (s.linked) "YOUR ROMS FOLDERS" else "ADD GAMES"), m)
    Para(
        if (s.linked) tr("Pick the folders you keep your games in. PokéDaisy adds every game from them that the second screen supports, plays them right where they are (nothing is copied), and looks for new ones each time you open the app.")
        else tr("Add your own GBA or Game Boy ROMs from Files. Copies are kept in PokeDaisy's folder."),
        small,
    )
    if (s.linked) Para(
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
            GbaText(setupPath(folder), OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(1f), maxLines = 2)
            if (onRemove != null) {
                Spacer(Modifier.width(m.u * 4))
                OptionButton(tr("REMOVE"), small, enabled = !s.scanning, onClick = { onRemove(folder) })
            }
        }
    }
    OptionButton(if (!s.linked) tr("ADD GAMES") else if (s.romsFolders.isEmpty()) tr("CHOOSE FOLDER") else tr("ADD FOLDER"), m, emphasis = s.romsFolders.isEmpty(), enabled = !s.scanning, onClick = onChoose)
    Spacer(Modifier.height(m.u * 4))
    if (s.romsFolders.isNotEmpty()) {
        RomsFound(s, m, small)
        Spacer(Modifier.height(m.u * 4))
    }
    Para(
        if (s.linked) tr("This is optional: skip it and add games one at a time with + in the library. You can add folders later with + or in SETTINGS > FOLDERS.")
        else tr("You can add more games later with + in the library."),
        small, muted = true,
    )
}

/** The ROMS step's scan: its progress, then the games it found. */
@Composable
private fun RomsFound(s: SetupRomsModel, m: GbaTextMetrics, small: GbaTextMetrics) {
    val found = s.found
    when {
        s.scanning -> {
            GbaText(tr("LOOKING FOR GAMES… {0} / {1}", s.checked, s.toCheck), OptionColors.label, OptionColors.labelShadow, m)
            Spacer(Modifier.height(m.u * 3))
            SetupProgressBar(if (s.toCheck > 0) s.checked.toFloat() / s.toCheck else 0f, m)
        }
        found == null -> {}
        found.isEmpty() && s.linked -> Para(
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
fun SetupSavesPage(
    s: SetupSavesModel,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onChoose: (() -> Unit)?,
    onUse: (String?) -> Unit,
) {
    Heading(tr("GAME SAVES"), m)
    Para(
        if (onChoose != null) tr("Where should your saves go? If you already play these games in another emulator, pick its save folder (RetroArch's, for one) and carry on with the same saves: a game finds its save by the ROM's file name (.sav or .srm).")
        else tr("Your saves stay in PokeDaisy's folder. Use Files to copy them to or from another emulator."),
        small,
    )
    val listed = s.suggestions.map { it.path }
    OptionLine(tr("KEEP THEM IN POKéDAISY"), tr("DEFAULT"), selected = s.savesDir == null, m, labelWeight = 0.75f, divider = true) { onUse(null) }
    s.suggestions.forEach { sug ->
        OptionLine(
            setupPath(sug.path), if (sug.saves == 1) tr("1 SAVE") else tr("{0} SAVES", sug.saves),
            selected = s.savesDir == sug.path, m, labelWeight = 0.75f, divider = true,
        ) { onUse(sug.path) }
    }
    s.savesDir?.takeIf { it !in listed }?.let { dir ->
        OptionLine(setupPath(dir), null, selected = true, m, labelWeight = 0.75f, divider = true) { }
    }
    if (onChoose != null) OptionLine(tr("CHOOSE ANOTHER FOLDER…"), null, selected = false, m, onClick = onChoose)
    Spacer(Modifier.height(m.u * 4))
    if (!s.hasAccess) {
        Para(tr("Choosing a folder asks for All files access first, as games and saves are opened by path."), small, muted = true)
    }
    if (onChoose != null) Para(tr("You can change this later in SETTINGS > FOLDERS."), small, muted = true)
}

/** GAME BUTTONS (key bindings): what each GBA button is now; a tap on one waits for the device's button, right here. */
@Composable
fun SetupButtonsPage(buttons: Map<GbaControls.Btn, List<String>>, capture: KeyCapture, m: GbaTextMetrics, small: GbaTextMetrics, request: (GbaControls.Btn) -> KeyCapture.Request, group: GbaControls.Group? = null, usesSecondScreen: Boolean = true) {
    Heading(tr("GAME BUTTONS"), m)
    Para(
        tr("Which button on your device presses each GBA button - key bindings, or remapping, in other emulators. Check A and B first: some devices print them the other way round."),
        small,
    )
    Para(tr("To change one, tap it and press the button you want for it. The D-pad and analog stick are fixed."), small, muted = true)
    GbaControls.Group.entries.filter { group == null || it.overlaps(group) }.forEach { group ->
        com.pokedaisy.app.companion.ui.GroupTitleLine(group.title, m, first = group == GbaControls.Group.ALL)
        val btns = GbaControls.Btn.entries.filter { it.group == group && !it.dpad && (it != GbaControls.Btn.TO_COMPANION || usesSecondScreen) }   // (the D-pad rows: SETTINGS only)
        btns.forEachIndexed { i, btn ->
            KeyBindRow(
                btn.title, buttons[btn], capture, m, small, divider = i < btns.lastIndex, subtitle = btn.subtitle,
            ) { request(btn) }
        }
    }
    Spacer(Modifier.height(m.u * 4))
    Para(tr("You can change this later in SETTINGS > GAME BUTTONS."), small, muted = true)
}

@Composable
fun SetupProgressBar(fraction: Float, m: GbaTextMetrics) {
    val u = m.u
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The 3u frame on each side leaves a 4u-tall fill.
            .height(u * 10)
            .drawBehind {
                val px = u.toPx()
                drawLayeredBox(OptionColors.titleLayers.inPx(px), OptionColors.titleFill, radius = 3 * px)
                val inset = 3 * px
                val w = (size.width - 2 * inset) * fraction.coerceIn(0f, 1f)
                if (w > 0f) {
                    drawPixelRoundRect(
                        OptionColors.value, Offset(inset, inset), Size(w, size.height - 2 * inset),
                        radius = px,
                    )
                }
            },
    )
}

fun setupPath(path: String): String = when {
    path.startsWith("/storage/emulated/0/") -> path.removePrefix("/storage/emulated/0/")
    path.startsWith("/storage/") -> tr("SD CARD") + "/" + path.removePrefix("/storage/").substringAfter('/')
    else -> path
}

/** Shared application-settings home: the same searchable grouped rows on Android and iPad. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun AppSettingsHome(
    rows: List<SettingRow>, m: GbaTextMetrics, query: String, onQuery: (String) -> Unit,
    modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState(),
    firstRowFocus: FocusRequester = remember { FocusRequester() }, footer: @Composable () -> Unit = {},
) {
    val shown = filterRows(rows, query)
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.fillMaxSize()) {
        OptionTextField(
            query, onQuery, m,
            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                val back = e.key == androidx.compose.ui.input.key.Key.Back || e.key == androidx.compose.ui.input.key.Key.ButtonB
                if (!back) return@onPreviewKeyEvent false
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    keyboard?.hide()
                    runCatching { firstRowFocus.requestFocus() }
                }
                true
            },
            placeholder = tr("SEARCH SETTINGS (e.g. KEY BINDINGS)"),
            leadingIcon = PixelIcons.search,
        )
        Spacer(Modifier.height(m.u * 4))
        RailedWindow(m, Modifier.fillMaxWidth().weight(1f), rail = shown.isNotEmpty()) {
            if (shown.isEmpty()) {
                GbaText(
                    tr("NOTHING MATCHES \"{0}\"", query.trim()), OptionColors.muted, OptionColors.mutedShadow, m,
                    modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 4),
                )
            } else if (query.isBlank()) {
                GroupedRows(rows, m, cursor = -1, onClick = { rows[it].onClick() }, scroll = scroll, firstRowFocus = firstRowFocus, groupTabs = true)
            } else {
                GroupedRows(shown, m, cursor = -1, onClick = { shown[it].onClick() }, firstRowFocus = firstRowFocus)
            }
        }
        Spacer(Modifier.height(m.u * 4))
        footer()
    }
}

/** Application title chrome, shared by global settings on both platforms. */
@Composable
fun AppPageTitle(title: String, m: GbaTextMetrics, onBack: (() -> Unit)? = null,
                 home: Boolean = false, onHelp: () -> Unit = {}) {
    if (home) {
        OptionTitleWindow("POKéDAISY", m,
            leading = { PixelArt(LOGO_ROWS, LOGO_PALETTE, Modifier.size(m.lineHeight)) }) {
            Spacer(Modifier.width(m.u * 4))
            OptionButton(null, m, onClick = onHelp, icon = LibraryIcons.HelpOutline, contentDescription = tr("Help"))
        }
        Spacer(Modifier.height(m.u * 8))
    } else {
        OptionTitleWindow(title, m, onBack = onBack)
        Spacer(Modifier.height(m.u * 4))
    }
}

object AppLinks {
    const val REPO_URL = "https://github.com/lidor30/pokedaisy"
    const val COFFEE_URL = "https://buymeacoffee.com/lidor30g"
}

@Composable
fun AppAboutFooter(version: String, m: GbaTextMetrics, onOpenUrl: (String) -> Unit) {
    val small = rememberGbaTextMetrics(textScale = 1f)
    OptionListWindow(m, Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 8, vertical = m.u * 3),
        ) {
            GbaText(
                tr("POKéDAISY {0}  ·  by {1}", version, "Lidor Itzhari (@lidor30)"),
                OptionColors.label, OptionColors.labelShadow, small, modifier = Modifier.weight(1f),
            )
            FooterLink(tr("Buy me a coffee"), m, small, Modifier.padding(start = m.u * 6), { CoffeeCup() }) {
                onOpenUrl(AppLinks.COFFEE_URL)
            }
            GbaText("  ·  ", OptionColors.label, OptionColors.labelShadow, small)
            FooterLink("Discord", m, small, icon = { DiscordMark() }) { onOpenUrl(Discord.URL) }
            GbaText("  ·  ", OptionColors.label, OptionColors.labelShadow, small)
            FooterLink("GitHub", m, small, icon = { GitHubMark() }) { onOpenUrl(AppLinks.REPO_URL) }
        }
    }
}

/** A pixel [icon] then [text] in the value red, the whole of it one tap target. */
@Composable
private fun FooterLink(
    text: String, m: GbaTextMetrics, small: GbaTextMetrics, modifier: Modifier = Modifier,
    icon: @Composable () -> Unit, onClick: () -> Unit,
) {
    Row(modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(small.lineHeight)) { icon() }
        Spacer(Modifier.width(m.u * 3))
        GbaText(text, OptionColors.value, OptionColors.valueShadow, small)
    }
}
