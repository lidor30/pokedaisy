package com.pokedaisy.app

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pokedaisy.app.companion.TelemetryStore
import com.pokedaisy.app.companion.ui.CompanionScreen

/**
 * The second screen, via the Android Presentation API: normally the
 * companion (the Thor's bottom screen), the game with SWAP SCREENS on
 * ([PokeDaisyActivity]'s arrangeScreens). [content] builds what it shows, in
 * this Presentation's display context. A Presentation is a Dialog, so it
 * supplies its own ViewTree lifecycle / saved-state / view-model owners for
 * Compose to run - on a frame around the content, so a view that brings its
 * own (the game's stage) keeps them when it moves back to the activity.
 */
class DualScreenPresentation(
    /** The game's activity (a Presentation's own [getContext] is a display context, not it). */
    private val host: Context,
    display: Display,
    private val content: (Context) -> View,
) : Presentation(host, display) {

    private val owner = ComposeHostOwner()
    private var frame: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner.create()
        frame = FrameLayout(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            addView(content(context).also { (it.parent as? ViewGroup)?.removeView(it) })
        }
        setContentView(frame!!)
        // Never takes key focus: touching the bottom screen used to make it the focused
        // display, and the device's HOME (the Thor's double press closes the app) then
        // acted there - on no activity - instead of on the game. Touches still land here,
        // and every key goes to the activity anyway (dispatchKeyEvent below).
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        goFullScreen()
    }

    /** Lets go of the content (the game's stage, on its way back to the activity). */
    fun releaseContent() {
        frame?.removeAllViews()
    }

    /**
     * The whole bottom screen is the companion's: no navigation bar (its white
     * gesture handle sat on a black strip under the tab bar) or status bar
     * band. A swipe from the edge still shows them for a moment, as on the
     * game's screen ([PokeDaisyActivity]'s goImmersive).
     */
    private fun goFullScreen() {
        val w = window ?: return
        WindowCompat.setDecorFitsSystemWindows(w, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(w, w.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The bars are still up after [BARS_SETTLE_MS], so they aren't a swipe's: another app has the say over them. */
    var onBarsShowing: (() -> Unit)? = null

    private val checkBars = Runnable {
        val decor = window?.decorView ?: return@Runnable
        if (isShowing && ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true) {
            onBarsShowing?.invoke()
        }
    }

    /** Looks at the bars again in a moment (the game came back to the front, say). */
    fun watchBars() {
        val decor = window?.decorView ?: return
        decor.removeCallbacks(checkBars)
        decor.postDelayed(checkBars, BARS_SETTLE_MS)
    }

    /**
     * Keys belong to the game: once the bottom screen is touched it holds key
     * focus, and a Dialog's own BACK would dismiss the companion while the game
     * keeps running. Handing every key to the activity makes BACK (tap = the
     * companion's back, hold = the library) and the buttons work the same from
     * either screen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (host as? Activity)?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)

    override fun onStart() {
        super.onStart()
        goFullScreen()
        window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
                if (insets.isVisible(WindowInsetsCompat.Type.navigationBars())) watchBars()
                ViewCompat.onApplyWindowInsets(v, insets)
            }
        }
        watchBars()
        owner.resume()
        android.util.Log.i("pokedaisy", "companion presentation shown on display ${display.displayId} (${display.name}, type ${Screens.typeOf(display)})")
    }

    override fun onStop() {
        super.onStop()
        owner.destroy()
    }

    companion object {
        private const val BARS_SETTLE_MS = 1500L

        /** The companion, for a second screen (or the main one, swapped). */
        fun companionView(
            context: Context,
            store: TelemetryStore,
            slots: com.pokedaisy.app.companion.StateSlots?,
            settings: com.pokedaisy.app.companion.CompanionSettings?,
            battleInput: com.pokedaisy.app.companion.BattleInput?,
            back: com.pokedaisy.app.companion.ui.CompanionBack?,
            clickSound: () -> Unit,
            achievements: com.pokedaisy.app.companion.CompanionAchievements?,
            initialTab: String = "PARTY",
            /** SETTINGS' row to open on ([CompanionScreen]'s initialSettingsRow). */
            initialSettingsRow: String? = null,
            statusBar: (@androidx.compose.runtime.Composable () -> Unit)? = null,
            itemUse: com.pokedaisy.app.companion.ItemUse? = null,
            fly: com.pokedaisy.app.companion.FlyAction? = null,
            askForSupport: (() -> Unit)? = null,
            gamePick: com.pokedaisy.app.companion.GamePickActions? = null,
            shareBestEffort: ((com.pokedaisy.app.companion.data.BestEffortReport) -> Unit)? = null,
            topStrip: (@androidx.compose.runtime.Composable () -> Unit)? = null,
            /** The game's buttons, while they steer this companion (X on the field: [focus]). */
            keys: com.pokedaisy.app.companion.ui.CompanionKeys? = null,
            /** True while the game's buttons are this companion's ([CompanionFocusHost]). */
            focus: (() -> Boolean)? = null,
        ): View = CompanionColors.track(ComposeView(context)).apply {
            setContent {
                val snap by store.snapshot.collectAsState()
                val screen = @androidx.compose.runtime.Composable {
                    CompanionScreen(
                        snap, slots, settings, battleInput, initialTab = initialTab, initialSettingsRow = initialSettingsRow,
                        back = back, keys = keys, clickSound = clickSound, achievements = achievements, statusBar = statusBar,
                        itemUse = itemUse, fly = fly, askForSupport = askForSupport, gamePick = gamePick,
                        shareBestEffort = shareBestEffort, topStrip = topStrip,
                    )
                }
                if (keys != null && focus != null) CompanionFocusHost(focus(), keys, screen)
                else screen()
            }
        }
    }
}
