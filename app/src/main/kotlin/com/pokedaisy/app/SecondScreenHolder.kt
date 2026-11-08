package com.pokedaisy.app

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

/**
 * An empty app window on the second screen, under the companion's Presentation, for AYANEO's handhelds: their bottom
 * screen's launcher keeps a full-screen panel over everything until an app opens there, and a Presentation doesn't
 * count as one (GitHub discussion #249). It takes no keys or touches, so the game keeps its buttons.
 */
class SecondScreenHolder : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Covering an app it has to be able to take focus, on its own screen: Android gives the say over a screen's bars to the
        // window focused there, and one that can't be left it with the app below. Touches still go to the companion above.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or if (coverBars) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        held = WeakReference(this)
        if (coverBars) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shown = true
        // The newest task holds the input focus, even with a window that can't take keys: the game's buttons went nowhere until
        // its own task came to the front again. From here, once this window is really up: on a timer from the game's side it
        // ran first on the Thor, with Chrome to pause, and this window then took the focus for good.
        val decor = window.decorView
        decor.postDelayed({ gameToFront() }, REFOCUS_MS)
        decor.postDelayed({ gameToFront() }, REFOCUS_MS * 3)
        decor.postDelayed({
            // Buttons matter more than the bar: a game that didn't get them back loses this window instead.
            if (coverBars && game?.get()?.hasWindowFocus() == false) {
                android.util.Log.w("pokedaisy", "second screen cover: the game didn't get its focus back, giving up")
                gaveUp = true
                close()
                gameToFront()
            }
        }, GIVE_UP_MS)
    }

    private fun gameToFront() {
        val task = game?.get()?.taskId ?: return
        runCatching { getSystemService(ActivityManager::class.java)?.moveTaskToFront(task, 0) }
    }

    override fun onPause() { super.onPause(); shown = false }

    // Covering an app it holds its screen's focus, and on the Thor that screen can stay the one keys go to, whatever the game's
    // task does: every key and stick goes on to the game, as the Presentation's do.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = game?.get()?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        game?.get()?.dispatchGenericMotionEvent(event) ?: super.dispatchGenericMotionEvent(event)

    override fun onStop() {
        super.onStop()
        // HOME reached this screen, not the game's: the player left, so the game goes too (the screen going off isn't that).
        if (coverBars && !isFinishing && getSystemService(PowerManager::class.java)?.isInteractive == true) {
            game?.get()?.moveTaskToBack(true)
        }
    }

    companion object {
        private var held: WeakReference<SecondScreenHolder>? = null
        private const val REFOCUS_MS = 300L
        private const val GIVE_UP_MS = 2000L
        /** The game this window hands the focus back to. */
        private var game: WeakReference<Activity>? = null
        /** It took the game's buttons once: not again until the app is restarted. */
        private var gaveUp = false
        /** It's up on the second screen; HOME leaves it behind the launcher, so the game's return opens it again. */
        private var shown = false

        /** An app on the second screen under the companion (Chrome on the Thor's bottom one): that app has the say over
         * the screen's bars, so the white gesture handle stayed on the companion whatever the Presentation asked. This window
         * covers the app while the game runs, and hides them. */
        private var coverBars = false

        private val needed get() = coverBars || Build.MANUFACTURER.equals("AYANEO", ignoreCase = true)

        /** The bars are showing over the companion on [display]: covers what's under it. */
        fun coverApp(from: Activity, display: Display) {
            if (coverBars || gaveUp) return
            coverBars = true
            open(from, display)
        }

        /** The game has left the front: the app under the companion comes back. Not on AYANEO's, where it stays. */
        fun uncoverApp() {
            if (!coverBars) return
            coverBars = false
            close()
        }

        /** Opens it on [display] where it's needed, or brings it back up. */
        fun open(from: Activity, display: Display) {
            if (!needed || shown) return
            game = WeakReference(from)
            runCatching {
                from.startActivity(
                    Intent(from, SecondScreenHolder::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle(),
                )
            }
        }

        fun close() {
            coverBars = false
            held?.get()?.finish()
            held = null
        }
    }
}
