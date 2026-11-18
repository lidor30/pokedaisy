package com.pokedaisy.app.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import com.pokedaisy.app.companion.*

/** Shared editor session, persistence and overlay. Hosts supply native input/pause effects when opening/closing. */
class TouchEditorState(
    private val read: () -> TouchControlsStyle,
    private val write: (TouchControlsStyle) -> Unit,
    private val closed: () -> Unit = {},
) {
    private class Request(val stage: TouchStage, val buttons: Set<TouchButton>, val shot: ImageBitmap?, val backdrop: Boolean)
    private var request by mutableStateOf<Request?>(null)
    val isOpen get() = request != null
    val back = CompanionBack()

    fun open(stage: TouchStage, buttons: Set<TouchButton> = TouchButton.entries.toSet(),
             shot: ImageBitmap? = null, drawBackdrop: Boolean = false): Boolean {
        if (isOpen) return false
        request = Request(stage, buttons, shot, drawBackdrop)
        return true
    }

    fun done(layout: TouchLayout) { request?.let { finish(it, layout) } }
    private fun finish(owner: Request, layout: TouchLayout) {
        if (request !== owner) return
        val style = read()
        val available = style.forButtons(owner.buttons).shown(owner.stage.gameBoy, owner.stage.ds).toSet()
        // A Game Boy game or a host without an extra cannot erase its stored position while editing other controls.
        val kept = style.layoutFor(owner.stage.portrait).places.filterKeys { it !in available }
        write(style.withLayout(owner.stage.portrait, TouchLayout(kept + layout.places)))
        dismiss()
    }

    /** Discard an unfinished edit, e.g. when its game closes. DONE/BACK use [done] instead. */
    fun dismiss() {
        if (!isOpen) return
        request = null
        closed()
    }

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        val owner = request ?: return
        key(owner) {
            CompositionLocalProvider(LocalCompanionBack provides back) {
                Box(modifier.fillMaxSize().background(if (owner.backdrop) Color.Black else Color.Transparent)
                    .pointerInput(owner) { detectTapGestures {} }) {
                    TouchLayoutEditor(owner.stage, read().forButtons(owner.buttons), rememberGbaTextMetrics(),
                        shot = owner.shot, drawBackdrop = owner.backdrop, onDone = { finish(owner, it) })
                }
            }
        }
    }
}
