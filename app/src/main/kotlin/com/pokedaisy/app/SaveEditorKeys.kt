package com.pokedaisy.app

// Android views (KeyEvent, the content view): app-side, outside the sources ui-preview compiles (its shims stub this).

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.pokedaisy.app.companion.ui.FocusStarts
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.companion.ui.OverlayStack

/**
 * The SAVE EDITOR ([SaveEditorActivity]) steered by a controller or keyboard, like the top-screen Settings: the D-pad
 * moves an orange cursor ring (its first press puts it on the selected slot, else the open page's chip: [starts]), A
 * presses what it's on (a D-pad centre for Compose) and B goes back - a pop-up first ([overlays]), then the page's own
 * BACK (a MOVE pending, unsaved edits, a Pokémon's page). Touch works as before. Null = not taken, Android's way.
 */
fun saveEditorKey(
    activity: ComponentActivity, event: KeyEvent, overlays: OverlayStack, starts: FocusStarts, composeHasFocus: Boolean,
    backTaken: () -> Boolean, setBackTaken: (Boolean) -> Unit,
): Boolean? {
    val code = event.keyCode
    val down = event.action == KeyEvent.ACTION_DOWN
    if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
        if (down) {
            if (event.repeatCount == 0) setBackTaken(overlays.dismissTop())
            if (backTaken()) return true
        } else if (event.action == KeyEvent.ACTION_UP && backTaken()) {
            setBackTaken(false)
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B) {
            if (!down) activity.onBackPressedDispatcher.onBackPressed()
            return true
        }
        return null
    }
    val key = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> MenuKey.A
        else -> return null
    }
    // Arrows with the cursor already somewhere are Compose's own; the first one, and A always, go through here.
    if (key != MenuKey.A && composeHasFocus) return null
    val view = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return null
    // A with no cursor yet shows it rather than pressing something unseen.
    if (key == MenuKey.A && !composeHasFocus) {
        if (down) {
            if (view.isInTouchMode) view.requestFocusFromTouch() else view.requestFocus()
            starts.focusNewest()
        }
        return true
    }
    if (down) sendFocusKey(view, key, event.repeatCount > 0) { if (!composeHasFocus) starts.focusNewest() }
    return true
}
