package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Plays the running game's own menu click (SE_SELECT, rendered from the ROM -
 * see GameClickSound). Silent unless the host provides it, so the
 * top-screen Library / Settings, Paparazzi and ui-preview stay quiet.
 */
val LocalClickSound = staticCompositionLocalOf<() -> Unit> { {} }

/** Whether the game can't be heard right now ([com.pokedaisy.app.companion.BattleInput.gameSilent]): asked on a tap. */
val LocalGameSilent = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * [clickable] that plays the game's click on every tap - the companion's
 * buttons use this. Not for buttons that press the game's own buttons
 * (battle FIGHT / moves / BACK): the game plays its own sound for those.
 * The click follows the action, so the CLICK SOUND row itself clicks as it
 * turns on and stays quiet as it turns off.
 */
fun Modifier.soundClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val sound = LocalClickSound.current
    val click = { onClick(); sound() }
    menuFocusAction(enabled, click).clickable(enabled = enabled, onClick = click)
}

/** [soundClickable] with the caller's [interactionSource] / [indication], like [clickable]'s overload. */
fun Modifier.soundClickable(
    interactionSource: MutableInteractionSource,
    indication: Indication?,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val sound = LocalClickSound.current
    val click = { onClick(); sound() }
    menuFocusAction(enabled, click).clickable(interactionSource = interactionSource, indication = indication, enabled = enabled, onClick = click)
}

/** A silent clickable with the same portable controller activation as [soundClickable]. */
fun Modifier.menuClickable(enabled: Boolean = true, role: androidx.compose.ui.semantics.Role? = null,
                           onClick: () -> Unit): Modifier = composed {
    menuFocusAction(enabled, onClick).clickable(enabled = enabled, role = role, onClick = onClick)
}

fun Modifier.menuClickable(interactionSource: MutableInteractionSource, indication: Indication?,
                           enabled: Boolean = true, role: androidx.compose.ui.semantics.Role? = null,
                           onClick: () -> Unit): Modifier = composed {
    menuFocusAction(enabled, onClick).clickable(interactionSource = interactionSource, indication = indication,
        enabled = enabled, role = role, onClick = onClick)
}
