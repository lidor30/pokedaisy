package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind

@Composable
fun InfoRows(title: String, rows: List<Pair<String, String>>, first: Boolean, m: GbaTextMetrics, small: GbaTextMetrics) {
    GbaText(
        title, OptionColors.value, OptionColors.valueShadow, m,
        Modifier.padding(top = if (first) m.u * 2 else m.u * 8, bottom = m.u * 2),
    )
    rows.forEachIndexed { j, (label, value) ->
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (j < rows.lastIndex) {
                        Modifier.drawBehind { drawRowDivider(OptionColors.divider, m.u.toPx(), 0f) }
                    } else Modifier,
                )
                .padding(vertical = small.u * 3),
            horizontalArrangement = Arrangement.spacedBy(m.u * 6),
        ) {
            GbaText(label, OptionColors.muted, OptionColors.mutedShadow, small, Modifier.weight(0.34f))
            GbaText(value, OptionColors.label, OptionColors.labelShadow, small, Modifier.weight(0.66f), maxLines = 3)
        }
    }
}
