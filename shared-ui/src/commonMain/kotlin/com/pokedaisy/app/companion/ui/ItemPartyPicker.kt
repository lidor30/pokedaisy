package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.MonView
import com.pokedaisy.app.companion.data.PP_BOOST_ITEMS
import com.pokedaisy.app.companion.data.PartyTarget
import com.pokedaisy.app.companion.data.SlotVerdict
import com.pokedaisy.app.companion.data.spriteAsset
import com.pokedaisy.app.companion.i18n.tr

/** A USE ([give] false) or GIVE of [item] waiting for its Pokémon: [targets] are the game's answers, slot by slot. */
data class ItemPick(val item: com.pokedaisy.app.companion.data.ItemView, val give: Boolean, val targets: List<PartyTarget>)

/**
 * Who gets the item: the PARTY tab's own slots, the ones it won't help (or that can't hold it - an egg,
 * a Mail holder, one holding it already) greyed out, each slot's held item in its corner. Below, the
 * game's prompt beside the item and CANCEL. A one-move PP item ([picksMove]) then asks for the move.
 * [onPick] gets the slot and the move (0 where there's none to pick).
 */
@Composable
fun ItemPartyPicker(
    pick: ItemPick,
    party: List<MonView>,
    m: GbaTextMetrics,
    modifier: Modifier = Modifier,
    itemName: String,
    picksMove: Boolean,
    onCancel: () -> Unit,
    onPick: (PartyTarget, Int) -> Unit,
    /** The move pick open first, for this slot - for screenshot tests, which can't tap. */
    initialMoveSlot: Int? = null,
) {
    var moveFor by remember(pick) { mutableStateOf(initialMoveSlot?.let { pick.targets.getOrNull(it) }) }
    CompanionBackHandler(onBack = onCancel)
    // A slot is only what the pick saw: the same Pokémon (by personality, where the party read has it).
    fun targetOf(index: Int, mon: MonView): PartyTarget? =
        pick.targets.getOrNull(index)?.takeIf { mon.personality == 0L || it.personality == mon.personality }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PartyGrid(
            party, selected = -1,
            onSlotClick = { mon ->
                val t = targetOf(party.indexOf(mon), mon)
                if (t != null && t.verdict == SlotVerdict.OK) {
                    if (picksMove) moveFor = t else onPick(t, 0)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            dimmed = { mon -> targetOf(party.indexOf(mon), mon)?.verdict != SlotVerdict.OK },
        ) { mon ->
            targetOf(party.indexOf(mon), mon)?.heldItem?.takeIf { it != 0 }?.let { held ->
                HeldItemTag(held, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp))
            }
        }
        OptionTitleWindow(
            if (pick.give) tr("Give {0} to which POKéMON?", itemName) else tr("Use {0} on which POKéMON?", itemName), m,
            leading = { ItemIcon(spriteAsset("items", pick.item.itemId), size = m.lineHeight) },
            titleMaxLines = 2,
        ) {
            OptionButton(tr("CANCEL"), m, onClick = onCancel, modifier = Modifier.padding(start = m.u * 8))
        }
    }
    moveFor?.let { t ->
        MovePicker(
            t, party.getOrNull(t.slot), m,
            title = if (pick.item.itemId in PP_BOOST_ITEMS) "Boost PP of which move?" else "Restore which move?",
            onBack = { moveFor = null },
            onPick = { move -> moveFor = null; onPick(t, move) },
        )
    }
}

/** A party slot's held item: its icon and name on a small white tag, like the title window (also the PARTY tab's). */
@Composable
internal fun HeldItemTag(itemId: Int, modifier: Modifier = Modifier) {
    val m = rememberGbaTextMetrics(0.75f)
    val u = m.u
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .drawBehind { drawLayeredBox(OptionColors.titleLayers.inPx(u.toPx()), OptionColors.titleFill, radius = 2 * u.toPx()) }
            .padding(horizontal = u * 5, vertical = u * 2),
    ) {
        val ownNames = ownItemNames()
        if (ownNames == null) {
            ItemIcon(spriteAsset("items", itemId), size = m.lineHeight)
            Spacer(Modifier.padding(start = u * 3))
        }
        GbaText(ownNames?.invoke(itemId) ?: bagItemName(itemId), OptionColors.label, OptionColors.labelShadow, m)
    }
}

/** Which of [target]'s moves a one-move PP item goes on: its moves with their PP, the ones it won't help greyed. */
@Composable
private fun MovePicker(target: PartyTarget, mon: MonView?, m: GbaTextMetrics, title: String, onBack: () -> Unit, onPick: (Int) -> Unit) {
    OptionOverlay(onBack, Modifier.widthIn(max = 520.dp)) {
        Column {
            OptionTitleWindow(tr(title), m, onBack = onBack, trailing = mon?.name)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    (0 until 4).forEach { i ->
                        val move = mon?.moves?.getOrNull(i)
                        OptionLine(
                            move?.name ?: "-", move?.let { tr("PP {0}", it.pp) }, selected = false, m,
                            height = m.rowHeight * 1.2f, labelWeight = 0.7f, divider = true,
                            enabled = move != null && target.moves.getOrNull(i) == true,
                        ) { onPick(i) }
                    }
                    OptionLine(tr("CANCEL"), null, selected = false, m, height = m.rowHeight * 1.2f, onClick = onBack)
                }
            }
        }
    }
}
