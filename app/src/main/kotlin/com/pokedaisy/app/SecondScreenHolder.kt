package com.pokedaisy.app

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * An empty app window on the second screen, under the companion's Presentation, for AYANEO's handhelds: their bottom
 * screen's launcher keeps a full-screen panel over everything until an app opens there, and a Presentation doesn't
 * count as one (GitHub discussion #249). It takes no keys or touches, so the game keeps its buttons.
 */
class SecondScreenHolder : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        held = WeakReference(this)
    }

    override fun onResume() { super.onResume(); shown = true }

    override fun onPause() { super.onPause(); shown = false }

    companion object {
        private var held: WeakReference<SecondScreenHolder>? = null
        private const val REFOCUS_MS = 400L
        /** It's up on the second screen; HOME leaves it behind the launcher, so the game's return opens it again. */
        private var shown = false

        private val needed get() = Build.MANUFACTURER.equals("AYANEO", ignoreCase = true)

        /** Opens it on [display] where it's needed, or brings it back up. */
        fun open(from: Activity, display: Display) {
            if (!needed || shown) return
            runCatching {
                from.startActivity(
                    Intent(from, SecondScreenHolder::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle(),
                )
                // The newest task holds the input focus, even with a window that can't take keys: the game's buttons
                // went nowhere until its own task came to the front again.
                from.window.decorView.postDelayed({
                    runCatching { from.getSystemService(ActivityManager::class.java)?.moveTaskToFront(from.taskId, 0) }
                }, REFOCUS_MS)
            }
        }

        fun close() {
            held?.get()?.finish()
            held = null
        }
    }
}
