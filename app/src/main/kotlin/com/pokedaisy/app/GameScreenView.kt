package com.pokedaisy.app

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.viewinterop.AndroidView
import com.pokedaisy.app.companion.ui.GameScreen
import com.pokedaisy.app.companion.ui.GameScreenFrames
import com.pokedaisy.app.companion.ui.GameScreenState

/**
 * Android's game-screen adapter. Shared Compose owns the renderer/control areas, panels and chrome. Native views
 * retain GL/DS rendering, shaders, fast input windows and HUD dispatch. This root moves intact between displays.
 */
class GameScreenView(
    context: Context,
    val game: View,
    bar: View,
) : FrameLayout(context) {
    private val prefs = Prefs(context)
    var onWindow: (Boolean) -> Unit = {}
    var onCompanionHidden: () -> Unit = {}
    val panels = GameScreenState(prefs,
        windowChanged = { onWindow(it) },
        companionHidden = { contentView.findFocus()?.clearFocus(); onCompanionHidden() },
    )
    val viewport = GameViewportView(context, game, bar).apply {
        addView(game, LayoutParams(-1, -1))
        addView(bar, LayoutParams(-2, -2))
    }
    val contentView = ComposeView(context).apply {
        // SWAP/HOME detach this root from one window before attaching it to the next. Keep the same composition,
        // native renderer view and activity-owned recomposer throughout that move.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }
    private var sideContent by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var portraitContent by mutableStateOf<(@Composable () -> Unit)?>(null)
    var portraitInset by mutableIntStateOf(0)
        private set
    var frames: GameScreenFrames? = null
        private set
    private var reserved: List<IntRect> = emptyList()

    init { addView(contentView, LayoutParams(-1, -1)) }

    /** Install native rendering/input leaves and the existing shared HUD composables before first composition. */
    fun install(controls: View, backdrop: View, dsTouch: View, click: () -> Unit,
                underlay: @Composable () -> Unit, gameOverlay: @Composable () -> Unit,
                screenOverlay: @Composable () -> Unit, pictureOverlay: @Composable () -> Unit,
                message: View, onPicture: (Rect) -> Unit, editorOverlay: @Composable () -> Unit = {}) {
        viewport.dsBackdrop = backdrop
        viewport.dsTouch = dsTouch
        viewport.addView(backdrop, 0, LayoutParams(-1, -1))
        viewport.addView(dsTouch, LayoutParams(-1, -1))
        message.layoutParams = android.view.ViewGroup.LayoutParams(-2, -2)
        contentView.setContent {
            val effect = CompanionColors.composeEffect(this)
            val chrome = Modifier.graphicsLayer { renderEffect = effect }
            androidx.compose.runtime.CompositionLocalProvider(com.pokedaisy.app.companion.ui.LocalClickSound provides click) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    GameScreen(panels, viewport::gameHeightFor,
                        game = { Native(viewport) }, controls = { Native(controls) },
                        companion = { if (panels.portrait.enabled) portraitContent?.invoke() else sideContent?.invoke() },
                        gameUnderlay = underlay,
                        gameOverlay = { androidx.compose.foundation.layout.Box(chrome, propagateMinConstraints = true) { gameOverlay() } },
                        pictureOverlay = pictureOverlay, chromeModifier = chrome,
                        dismissSideOnGameTap = controls.visibility != View.VISIBLE,
                        overlayRect = { viewport.overlayBounds().let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) } },
                        screenOverlayTop = { viewport.hudTop },
                        screenOverlay = {
                            screenOverlay()
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                                AndroidView(factory = { message }, modifier = Modifier
                                    .align(androidx.compose.ui.Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
                            }
                        },
                        onFrames = { current, chrome ->
                            frames = current
                            portraitInset = current.portrait?.topInset(panels.gripHeight) ?: 0
                            reserved = chrome.map { IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
                            val area = current.controls.translate(-current.game.left, -current.game.top)
                            if (viewport.controlsArea != area) { viewport.controlsArea = area; viewport.requestLayout() }
                            onPicture(viewport.overlayBounds().let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                                .translate(current.game.left, current.game.top))
                        })
                    editorOverlay()
                }
            }
        }
    }

    fun sideCompanion(content: (@Composable () -> Unit)?) { sideContent = content }
    fun portraitCompanion(content: (@Composable () -> Unit)?) { portraitContent = content }

    fun inputExclusions(): List<IntRect> {
        val at = IntArray(2)
        getLocationInWindow(at)
        return reserved.map { IntRect(it.left + at[0], it.top + at[1], it.right + at[0], it.bottom + at[1]) }
    }

    /** Native game picture in screen coordinates for the editor. Child Views now live inside interop holders. */
    fun gameBounds(): Rect {
        val origin = IntArray(2); val child = IntArray(2)
        getLocationInWindow(origin); game.getLocationInWindow(child)
        return Rect((child[0] - origin[0]).toFloat(), (child[1] - origin[1]).toFloat(),
            (child[0] - origin[0] + game.width).toFloat(), (child[1] - origin[1] + game.height).toFloat())
    }

    fun reserveRendererRight(width: Int) {
        (game.layoutParams as LayoutParams).apply { rightMargin = width }.also { game.layoutParams = it }
    }

    @Composable private fun Native(view: View) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }

}
