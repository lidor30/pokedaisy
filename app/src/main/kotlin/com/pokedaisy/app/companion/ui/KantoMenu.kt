package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.pokedaisy.app.companion.data.GameKind
import com.pokedaisy.app.companion.data.activeGame
import com.pokedaisy.app.companion.data.runningFireRedHack

/** LeafGreen shares FIRERED's reader. Hacks and the launcher keep their own menu treatments. */
internal val usesKantoMenu: Boolean
    get() = OptionColors.inGame && activeGame == GameKind.FIRERED && runningFireRedHack == null

/** FireRed/LeafGreen's standard blue text-box frame, drawn in whole GBA pixels.
 * Colours follow graphics/text_window/stdpal_0.pal in pret/pokefirered, expanded as the renderer does.
 * No game tiles or fonts are bundled: those still come from the player's ROM.
 */
internal object KantoMenuColors {
    val layers get() = if (OptionColors.dark) listOf(
        Color(0xFF101C31) to 1, Color(0xFF52739C) to 2,
        Color(0xFF8CA5BD) to 1, Color(0xFF314A63) to 1,
    ) else listOf(
        Color(0xFF4A73A5) to 1, Color(0xFF73A5C6) to 2,
        Color(0xFFD6E7F7) to 1, Color(0xFFA5D6E7) to 1,
    )
}

@Composable
internal fun KantoMenuWindow(m: GbaTextMetrics, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val layers = KantoMenuColors.layers
    Box(modifier.drawBehind {
        drawLayeredBox(layers.inPx(m.u.toPx()), OptionColors.titleFill, radius = 3 * m.u.toPx())
    }.padding(m.u * 7)) { content() }
}

/** The solid, shadowed START-menu cursor, never a fallback-font triangle. */
@Composable
internal fun KantoMenuCursor(m: GbaTextMetrics, modifier: Modifier = Modifier) {
    ShadowedPixelIcon(PixelIcons.cursorRight, OptionColors.label, OptionColors.labelShadow, m, modifier)
}

/** START-menu command: the cursor moves without moving the label or changing the touch target. */
@Composable
internal fun KantoMenuCommand(
    label: String,
    m: GbaTextMetrics,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    Row(
        modifier.fillMaxWidth().heightIn(min = m.rowHeight * 1.25f)
            .alpha(if (enabled) 1f else 0.4f)
            .soundClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = m.u * 3, vertical = m.u * 3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(m.u * 12), contentAlignment = Alignment.CenterStart) {
            if (enabled && (selected || focused)) KantoMenuCursor(m)
        }
        GbaText(label, OptionColors.label, OptionColors.labelShadow, m, modifier = Modifier.weight(1f, fill = false).narrowToFit(m))
    }
}
