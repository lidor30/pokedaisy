@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pokedaisy.app.companion.GameActions
import com.pokedaisy.app.companion.TouchButton
import com.pokedaisy.app.companion.TouchStage
import com.pokedaisy.app.companion.ui.gamePanelLayout
import com.pokedaisy.app.companion.ui.TouchControls
import com.pokedaisy.app.companion.ui.TouchInput
import com.pokedaisy.app.companion.ui.decodeImage
import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameViewController

/** The host's native presentation/input bridge. Drawing, hit testing, settings and editing come from shared UI. */
internal class IosTouchControls(
    private val game: () -> GameViewController?,
    private val settings: () -> IosCompanionSettings?,
) {
    var placed by mutableStateOf<com.pokedaisy.app.companion.ui.TouchGeometry.Placed?>(null)
        private set
    var stage by mutableStateOf<TouchStage?>(null)
    private var editingGame: GameViewController? = null
    private val editor = com.pokedaisy.app.companion.ui.TouchEditorState(
        read = { IosTouchPreferences.style }, write = { IosTouchPreferences.style = it },
        closed = {
            releaseKeys()
            editingGame?.let { it.uiPaused = false; it.resume() }
            editingGame = null
        })

    @Composable fun EditorOverlay() = editor.Content()
    var actions: GameActions? = null
    var quickMenu: com.pokedaisy.app.companion.ui.QuickMenuState? = null
    var triggerQuickMenu: (Boolean) -> Unit = {}
    private var inputEpoch by mutableIntStateOf(0)
    var previewStage: ((Boolean) -> TouchStage?)? = null
    var beforeEdit: () -> Unit = {}

    fun releaseKeys() {
        inputEpoch++
        game()?.let { it.touchKeys = 0; it.touchTurbo = 0; it.touchButtonKeys = emptySet() }
        actions?.cancelTouch()
    }

    fun close() {
        actions?.clear()
        settings()?.actions = null
        actions = null
        releaseKeys()
        editingGame = null
        editor.dismiss()
        stage = null
    }

    @Composable
    fun Controls(gameBoy: Boolean, portrait: Boolean) {
        val input = remember {
            TouchInput(
                onKeys = { keys, turbo -> game()?.takeUnless { it.uiPaused }?.let { it.touchKeys = keys; it.touchTurbo = turbo } },
                onAction = ::action,
                onPress = { IosTouchPreferences.feel(IosTouchPreferences.style.vibration) })
        }
        DisposableEffect(Unit) { onDispose { placed = null; releaseKeys() } }
        // Changing ownership cancels existing fingers; only a new DOWN may play after a modal closes.
        key(inputEpoch) {
            TouchControls(IosTouchPreferences.drawingStyle, portrait, input, Modifier.fillMaxSize(),
                gameAspect = if (gameBoy) 160f / 144 else 1.5f, gameBoy = gameBoy, ffOn = settings()?.ffActive == true, onPlaced = { placed = it })
        }
    }

    /** A hidden pad still has an editable stand-in, using the same layout with controls enabled. */
    fun readStage(): TouchStage? = stage?.let {
        previewStage?.invoke(false) ?: if (IosTouchPreferences.visible) it else
            gamePanelLayout(it.w, it.h, it.dp, gameBoy = it.gameBoy, underGame = IosTouchPreferences.underGame, style = IosTouchPreferences.style)
    }

    fun edit() {
        if (editor.isOpen) return
        val current = game() ?: return
        beforeEdit()
        val measured = readStage() ?: return
        releaseKeys()
        current.uiPaused = true
        current.pause()
        editingGame = current
        editor.open(measured, IosTouchPreferences.buttons, PkApiCore.stateThumbnail()?.let(::decodeImage), drawBackdrop = true)
    }

    fun editStandalone(stage: TouchStage? = null) {
        if (stage != null) editor.open(stage, IosTouchPreferences.buttons, drawBackdrop = true)
    }

    private fun action(button: TouchButton, down: Boolean) {
        if (button == TouchButton.QUICK_MENU) {
            if (game()?.let { it.isRunning && !it.uiPaused && (!it.companionInputCaptured || quickMenu?.open == true) } == true)
                triggerQuickMenu(down)
            return
        }
        if (game()?.let { it.isRunning && !it.uiPaused && !it.companionInputCaptured } != true) return
        if (settings() == null || actions?.touch(button, down) == true) return
        when (button) {
            TouchButton.X, TouchButton.Y -> game()?.let { g ->
                val code = if (button == TouchButton.X) com.pokedaisy.app.companion.data.KeyCodes.BUTTON_X
                    else com.pokedaisy.app.companion.data.KeyCodes.BUTTON_Y
                g.touchButtonKeys = if (down) g.touchButtonKeys + code else g.touchButtonKeys - code
            }
            else -> Unit
        }
    }
}
