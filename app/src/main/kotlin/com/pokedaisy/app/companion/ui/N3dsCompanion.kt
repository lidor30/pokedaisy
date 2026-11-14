package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.ItemView
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.i18n.tr

/**
 * A 3DS game's companion, first version (docs/3DS_GAMES_PLAN.md): PARTY and BAG over the app's backdrop, built from the
 * companion's own screens ([PartyScreen], [ItemsScreen]) with what the game's memory gives (n3ds/Gen6Reader.kt). No other
 * tabs, icons or summaries yet. [itemNames]: the cart's item names by id ([LocalItemNames]).
 */
@Composable
fun N3dsCompanion(party: List<MonView>, bag: List<ItemView>, itemNames: (Int) -> String?, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.CompositionLocalProvider(LocalItemNames provides itemNames) { Box(modifier.fillMaxSize()) {
        DaisyBackdrop()
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // The companion's own tab bar's shape: the chips as tall as their text (IntrinsicSize.Min), sharing the width.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TabChip(tr("PARTY"), tab == 0, Modifier.weight(1f), slot = 0) { tab = 0 }
                TabChip(tr("BAG"), tab == 1, Modifier.weight(1f), slot = 1) { tab = 1 }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) PartyScreen(party, onMonClick = {}) else ItemsScreen(bag)
            }
        }
    } }
}
