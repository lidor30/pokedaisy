package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.i18n.tr

@Composable
fun LibraryRenameDialog(
    current: String,
    m: GbaTextMetrics,
    small: GbaTextMetrics,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    OptionOverlay(onDismiss, Modifier.widthIn(max = 520.dp)) {
        Column {
            OptionTitleWindow(tr("RENAME"), m)
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column {
                    GbaText(
                        tr("Name shown in the list (display only - the file keeps its name)."),
                        OptionColors.label, OptionColors.labelShadow, small, maxLines = Int.MAX_VALUE,
                        modifier = Modifier.padding(horizontal = m.u * 8, vertical = m.u * 2),
                    )
                    OptionTextField(text, { text = it }, small, Modifier.padding(horizontal = m.u * 8, vertical = m.u * 2))
                    OptionLine(tr("SAVE"), null, selected = false, m, height = m.rowHeight * 1.3f) { onConfirm(text) }
                    OptionLine(tr("CANCEL"), null, selected = true, m, height = m.rowHeight * 1.3f, onClick = onDismiss)
                }
            }
        }
    }
}
