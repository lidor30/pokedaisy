package com.pokedaisy.app.companion.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** A native keyboard poller must leave text entry to Compose, including overlapping focus/disposal callbacks. */
class TextInputFocus(private val changed: (Boolean) -> Unit) {
    private val fields = mutableSetOf<Any>()
    fun set(field: Any, focused: Boolean) {
        val was = fields.isNotEmpty()
        if (focused) fields.add(field) else fields.remove(field)
        if (was != fields.isNotEmpty()) changed(fields.isNotEmpty())
    }
    fun clear() {
        if (fields.isEmpty()) return
        fields.clear()
        changed(false)
    }
}

val LocalTextInputFocus = staticCompositionLocalOf<TextInputFocus?> { null }
