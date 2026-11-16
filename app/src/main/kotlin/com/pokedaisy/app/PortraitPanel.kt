package com.pokedaisy.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import com.pokedaisy.app.companion.ui.CompanionBack
import com.pokedaisy.app.companion.ui.LocalCompanionTopInset
import com.pokedaisy.app.companion.ui.SidePanelCompanion

/** Android storage, DS content and back-button adapter for the shared upright/tabletop companion. */
class PortraitPanel(
    private val root: GameScreenView,
    private val prefs: Prefs,
    private val back: CompanionBack,
    companion: @Composable () -> Unit,
) {
    private val state get() = root.panels
    private val portrait get() = state.portrait
    private val panel = CompanionColors.track(ComposeView(root.context)).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            CompositionLocalProvider(LocalCompanionTopInset provides root.portraitInset) {
                SidePanelCompanion(content = companion)
            }
        }
    }
    val enabled get() = portrait.enabled
    val hidden get() = portrait.hidden
    var ds: Boolean
        get() = !portrait.canHide
        set(v) { state.setPortrait(portrait.copy(canHide = !v, ratio = if (v) prefs.portraitDsRatio else prefs.portraitCompanionRatio)) }
    var expand: Boolean
        get() = portrait.expand
        set(v) { root.viewport.expand = v; state.setPortrait(portrait.copy(expand = v)) }
    var padStrip: Int
        get() = portrait.padStrip
        set(v) { state.setPortrait(portrait.copy(padStrip = v)) }
    var hinge: IntRange?
        get() = portrait.hinge
        set(v) { state.setPortrait(portrait.copy(hinge = v)) }
    val tabletop get() = enabled && hinge != null
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        if (!on) panel.disposeComposition()
        state.setPortrait(portrait.copy(enabled = on))
        root.viewport.topAligned = on
        root.portraitCompanion(if (on) ({ AndroidView(factory = { panel }, modifier = Modifier.fillMaxSize()) }) else null)
        if (on) refresh()
    }
    fun toggleHidden() = state.togglePortrait()
    fun reveal() = state.setPortrait(portrait.copy(hidden = false), save = true)
    fun refresh() = state.setPortrait(portrait.copy(
        ratio = if (ds) prefs.portraitDsRatio else prefs.portraitCompanionRatio,
        underGame = prefs.portraitCompanionUnderGame, hidden = prefs.portraitCompanionHidden))
    fun companionBounds() = root.frames?.companion?.takeIf { enabled && root.frames?.companionShown == true }
    fun onBack(): Boolean {
        if (!enabled) return false
        back.back()
        return true
    }
}
