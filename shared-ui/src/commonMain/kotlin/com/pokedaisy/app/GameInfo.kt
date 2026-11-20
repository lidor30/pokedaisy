package com.pokedaisy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.OptionSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import com.pokedaisy.app.companion.ui.InfoRows

/** The library menu's INFO: everything known about a game, its ROM and its saves, by section. */
class GameInfo(
    val title: String,
    val sections: List<Section>,
    /** A ROM the companion can't read: ASK FOR SUPPORT's request form. */
    val supportUrl: String? = null,
    /** The ROM's SHA-1 when a best-effort match is kept for it: FORGET MATCH. */
    val bestEffortSha1: String? = null,
    /** A ROM of a Gen 3 base game the app doesn't know: PICK A GAME (WHICH GAME IS THIS?). */
    val gamePick: GamePick? = null,
    /** A game its makers asked PokéDaisy not to read ([com.pokedaisy.app.companion.data.BlockedGames]): its button. */
    val blocked: com.pokedaisy.app.companion.data.BlockedGame? = null,
) {
    class Section(val title: String, val rows: List<Pair<String, String>>)

    /** The ROM ([sha1], header [gameCode]) and the game it's read as now ([current], a candidate id). */
    class GamePick(val sha1: String, val gameCode: String, val current: String?)
}

/** PICK A GAME: let best effort decide / NONE OF THESE (else a candidate id). */
const val GAME_AUTO = "AUTO"
const val GAME_NONE = "-"

/** [info] over the library: a title window, its sections in one scrolling list window, CLOSE. */
@Composable
fun GameInfoDialog(
    info: GameInfo, m: GbaTextMetrics, small: GbaTextMetrics, onDismiss: () -> Unit, onOpenUrl: (String) -> Unit = {},
    onForgetBestEffort: (String) -> Unit = {},
    /** PICK A GAME's answer: a candidate id, [GAME_AUTO] (best effort decides) or [GAME_NONE] (NONE OF THESE). */
    onPickGame: (GameInfo.GamePick, String) -> Unit = { _, _ -> },
) {
    var picking by remember { mutableStateOf(false) }
    OptionOverlay(onDismiss, Modifier.widthIn(max = 1100.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OptionTitleWindow(tr("INFO"), m, trailing = info.title, onBack = onDismiss)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth().weight(1f)) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = m.u * 4)) {
                    val scroll = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    if (maxWidth < 560.dp) {
                        Column(scroll) { InfoSections(info.sections, m, small) }
                    } else {
                        val half = (info.sections.size + 1) / 2
                        Row(scroll, horizontalArrangement = Arrangement.spacedBy(m.u * 12)) {
                            Column(Modifier.weight(1f)) { InfoSections(info.sections.take(half), m, small) }
                            Column(Modifier.weight(1f)) { InfoSections(info.sections.drop(half), m, small) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(m.u * 4))
            Row(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                ) {
                    info.supportUrl?.let { url ->
                        // Not read by the companion: a game request with the file's name and SHA-1 (never the ROM).
                        OptionButton(tr("ASK FOR SUPPORT"), m, onClick = { onOpenUrl(url) })
                    }
                    info.blocked?.action?.let { a ->
                        // A blocked game's own link (its makers' Discord): where to ask about it, not us.
                        OptionButton(tr(a.label), m, onClick = { onOpenUrl(a.url) })
                    }
                    info.gamePick?.let {
                        // The game it's a version of, picked here rather than on the companion.
                        OptionButton(tk("PICK A GAME"), m, onClick = { picking = true })
                    }
                    info.bestEffortSha1?.let { sha1 ->
                        // Back to NOT SUPPORTED: the companion asks again next time.
                        OptionButton(tr("FORGET MATCH"), m, onClick = { onForgetBestEffort(sha1) })
                    }
                }
                OptionButton(tr("CLOSE"), m, emphasis = true, modifier = Modifier.widthIn(min = 160.dp), onClick = onDismiss)
            }
        }
    }
    info.gamePick?.takeIf { picking }?.let { gp ->
        // AUTO, then every config of its base game, one row per title (two builds can share one), then NONE OF THESE.
        val games = com.pokedaisy.app.companion.data.BestEffort.candidatesFor(gp.gameCode).distinctBy { it.title }
        val current = gp.current?.let { id -> games.firstOrNull { it.id == id }?.id ?: games.firstOrNull { c -> c.title == com.pokedaisy.app.companion.data.BestEffort.candidate(id)?.title }?.id } ?: GAME_AUTO
        // The one in use right under AUTO, in view without scrolling.
        val options = listOf(GAME_AUTO) + listOfNotNull(current.takeIf { it != GAME_AUTO }) + games.map { it.id }.filter { it != current } + GAME_NONE
        OptionSelector(
            tk("WHICH GAME IS THIS?"), options, current,
            { id -> when (id) { GAME_AUTO -> tr("AUTO"); GAME_NONE -> tr("NONE OF THESE"); else -> games.first { it.id == id }.title } }, m,
            onPick = { picking = false; onPickGame(gp, it) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun InfoSections(sections: List<GameInfo.Section>, m: GbaTextMetrics, small: GbaTextMetrics) {
    sections.forEachIndexed { index, section ->
        InfoRows(section.title, section.rows, first = index == 0, m, small)
    }
}
