package com.pokedaisy.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import com.pokedaisy.app.companion.ui.CompanionBack
import com.pokedaisy.app.companion.ui.CompanionKeys
import com.pokedaisy.app.companion.ui.MenuKey
import com.pokedaisy.app.companion.ui.SidePanelHost
import com.pokedaisy.app.companion.ui.COMPACT_TEXT_INCHES

/** Android focus, storage and hardware-key adapter for the shared landscape companion. No native panel layout. */
class SidePanel(
    private val root: GameScreenView,
    private val prefs: Prefs,
    private val back: CompanionBack,
    private val keys: CompanionKeys,
    private val clickSound: () -> Unit,
    private val onWindow: (Boolean) -> Unit,
    companion: @Composable () -> Unit,
) {
    private val state get() = root.panels
    private val panel = CompanionColors.track(ComposeView(root.context)).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val side = state.side
            val dm = resources.displayMetrics
            val inches = minOf(root.width / dm.xdpi, root.height / dm.ydpi)
            androidx.compose.runtime.CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalClickSound provides clickSound) {
                SidePanelHost(side.isWindow, onClose = state::hide, keys = keys,
                    focusKeys = { key, repeat, start -> sendFocusKey(this, key, repeat, start) },
                    compactText = side.isWindow && inches >= COMPACT_TEXT_INCHES,
                    fullScreen = side.fullScreen, content = companion)
            }
        }
    }
    init {
        root.onWindow = { on ->
            if (!on) panel.findFocus()?.clearFocus() else panel.requestFocusFromTouch()
            onWindow(on)
        }
    }
    val enabled get() = state.side.enabled
    val closed = derivedStateOf { state.side.closed }
    val windowMode get() = state.side.isWindow
    val windowOpen get() = state.side.windowOpen
    val overGame get() = state.side.overGame
    var book: IntRange?
        get() = state.side.book
        set(v) { state.setSide(state.side.withBook(v)) }
    var openTabShown: Boolean
        get() = state.openTabShown
        set(v) { state.openTabShown = v }
    var tabInHud: Boolean
        get() = state.tabInHud
        set(v) { state.tabInHud = v }
    var tabRaised: Boolean
        get() = state.tabRaised
        set(v) { state.tabRaised = v }
    fun setFullScreen(on: Boolean) = state.setSide(state.side.withFullScreen(on))
    fun setWindow(on: Boolean) = state.setSide(state.side.copy(window = on))
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        if (!on) panel.disposeComposition()
        state.setSide(state.side.withEnabled(on, prefs.companionWindow, prefs.sidePanelDocked))
        root.sideCompanion(if (on) ({ AndroidView(factory = { panel }, modifier = Modifier.fillMaxSize()) }) else null)
    }
    fun onBack(): Boolean {
        if (!enabled) return false
        when {
            state.side.book != null -> back.back()
            !state.side.open -> state.show()
            back.back() -> Unit
            state.side.isWindow || !state.side.isDocked -> state.hide()
        }
        return true
    }
    fun menuKey(key: MenuKey, repeat: Boolean = false) {
        if (!windowOpen) return
        when (key) {
            MenuKey.B -> if (!repeat) onBack()
            MenuKey.START -> if (!repeat) state.hide()
            else -> keys.key(key, repeat)
        }
    }
    fun menuStick(x: Float, y: Float) = windowOpen && keys.stick(x, y)
    fun closeWindow() { if (windowOpen) state.hide() }
    fun openPanel() = state.show()
}
