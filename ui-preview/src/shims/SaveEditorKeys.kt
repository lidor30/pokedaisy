package com.pokedaisy.app

// Desktop stand-in for the app's SaveEditorKeys.kt (Android views): the previews press nothing with keys.
fun saveEditorKey(
    activity: androidx.activity.ComponentActivity, event: android.view.KeyEvent, overlays: com.pokedaisy.app.companion.ui.OverlayStack,
    starts: com.pokedaisy.app.companion.ui.FocusStarts, composeHasFocus: Boolean, backTaken: () -> Boolean, setBackTaken: (Boolean) -> Unit,
): Boolean? = null
