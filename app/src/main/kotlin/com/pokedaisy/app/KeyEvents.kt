package com.pokedaisy.app

import android.content.Context
import android.view.KeyEvent
import com.pokedaisy.app.companion.KeyCapture
import okio.Path
import okio.Path.Companion.toOkioPath

/** Android's key event to the key capture (which goes by key code alone); true when it's the capture's. */
fun KeyCapture.onKey(event: KeyEvent): Boolean = when (event.action) {
    KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP -> onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
    else -> onOtherKey(event.keyCode)
}

/** Where GAME BUTTONS and HOTKEYS keep their files (controls.properties, hotkeys.properties). */
val Context.controlsDir: Path get() = (getExternalFilesDir(null) ?: filesDir).toOkioPath()
