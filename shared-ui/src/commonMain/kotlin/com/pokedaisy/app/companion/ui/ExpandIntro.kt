package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.GameAspect
import com.pokedaisy.app.companion.i18n.tr

/**
 * Shown once, on the Library, to a player whose ASPECT isn't EXPAND yet (an update that brought it, or a new
 * install once setup is done), and once more - [inGame], on the companion - the first time they open a game EXPAND
 * knows while it's still off: what EXPAND does - the map past the game's edges, ZOOM, the look-around - with
 * TURN ON / NOT NOW. Either answer marks it seen ([com.pokedaisy.app.Prefs.expandIntroSeen]); SETTINGS > ASPECT
 * changes it later. The picture is the ASPECT picker's own EXPAND stand-in.
 */
@Composable
fun ExpandIntro(
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    /** The companion's offer, in a game EXPAND knows: titled for it. */
    inGame: Boolean = false,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
) {
    // The companion's screen is short: its paragraphs take the smaller text, so the buttons keep their height.
    val body = if (inGame) small else m
    OptionOverlay(onNotNow, Modifier.widthIn(max = 760.dp)) {
        Column {
            OptionTitleWindow(if (inGame) tr("EXPAND WORKS IN THIS GAME") else tr("NEW: EXPAND"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                BoxWithConstraints(Modifier.padding(m.u * 4)) {
                // A narrow window (a phone held upright): the picture over the text, not beside it.
                val narrow = maxWidth < m.u * 260
                Column {
                    // The words scroll on a small screen; the buttons stay below them, on screen.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        val intro = tr("See more of the world around you. EXPAND draws the map past the game's edges, so the whole screen is the game: no black bars, nothing stretched.")
                        if (narrow) {
                            ScreenPreview(
                                GameAspect.WIDE, statusBar = false, shot = null,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 4).aspectRatio(16f / 9f),
                            )
                            Spacer(Modifier.height(m.u * 6))
                            GbaText(intro, OptionColors.label, OptionColors.labelShadow, body, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(horizontal = m.u * 4))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScreenPreview(GameAspect.WIDE, statusBar = false, shot = null, modifier = Modifier.width(m.u * 110).aspectRatio(16f / 9f))
                                Spacer(Modifier.width(m.u * 8))
                                GbaText(intro, OptionColors.label, OptionColors.labelShadow, body, maxLines = Int.MAX_VALUE, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(m.u * 6))
                        GbaText(
                            tr("Press R3 to zoom out and see further, and look around with the right stick. Battles, menus and conversations zoom back in by themselves."),
                            OptionColors.label, OptionColors.labelShadow, body, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(horizontal = m.u * 4),
                        )
                        Spacer(Modifier.height(m.u * 6))
                        GbaText(
                            if (inGame) tr("Change it any time in SETTINGS > ASPECT.")
                            else tr("Works in selected games, with more added all the time. Change it any time in SETTINGS > ASPECT."),
                            OptionColors.muted, OptionColors.mutedShadow, small, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(horizontal = m.u * 4),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                        modifier = Modifier.fillMaxWidth().padding(top = m.u * 8),
                    ) {
                        OptionButton(tr("NOT NOW"), m, onClick = onNotNow, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                        OptionButton(tr("TURN ON"), m, onClick = onTurnOn, emphasis = true, modifier = Modifier.weight(1f).height(m.rowHeight * 1.4f))
                    }
                }
                }
            }
        }
    }
}
