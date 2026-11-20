package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pokedaisy.app.companion.i18n.tk

/**
 * LINK PLAY's games (LinkActivity's setup): a row per game picked ([games]: label, game; a tap swaps or removes it), then
 * ADD A GAME as a framed button with a plus while there's room - as a row like the games it didn't read as one (the
 * user's report).
 */
@Composable
fun LinkSlots(
    games: List<Pair<String, String>>,
    canAdd: Boolean,
    m: GbaTextMetrics,
    onPick: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (games.isNotEmpty()) {
            OptionRows(games.mapIndexed { i, (label, game) -> Triple(label, game as String?, { onPick(i) }) }, m, Modifier.weight(1f, fill = false))
        }
        if (canAdd) {
            OptionButton(
                tk("ADD A GAME"), m, onClick = onAdd, pixelIcon = PLUS, focusStart = games.isEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = m.u * 4, vertical = m.u * 3).height(m.rowHeight * 1.2f),
            )
        }
    }
}

/** ADD A GAME's plus. */
private val PLUS = listOf(
    "...1...",
    "...1...",
    "...1...",
    "1111111",
    "...1...",
    "...1...",
    "...1...",
)
