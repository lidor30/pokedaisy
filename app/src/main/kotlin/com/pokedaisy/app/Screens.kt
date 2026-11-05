package com.pokedaisy.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** The device's screens: is there a second one, and which. */
object Screens {
    /**
     * The second screen, or null with one. The Thor's bottom screen is a
     * presentation display. Other dual-screen handhelds (Retroid Pocket Duo /
     * Duo Lite) may expose theirs as a plain secondary display without that
     * flag, so fall back to any valid public display other than the built-in
     * main one. Never the main display itself: the AYANEO Pocket DS marks both of
     * its screens as presentation displays, and the companion went over the game.
     */
    fun second(context: Context): Display? {
        val displays = context.getSystemService(DisplayManager::class.java) ?: return null
        return displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }
            ?: displays.displays.firstOrNull {
                it.isValid && it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRIVATE) == 0
            }
    }

    /**
     * Start options that put the game on the main display, for an [activity] that isn't on it - the
     * library or a frontend opened on the bottom screen. A started activity otherwise opens on its
     * starter's display: the game ran on the bottom screen, and [second] (that same screen) got the
     * companion over it, the top screen nothing. With these (and FLAG_ACTIVITY_NEW_TASK) Android
     * moves the app's task to the main display, so the library comes back there after the game too.
     * SWAP SCREENS still works from there: the activity swaps its windows' contents itself. Null on
     * the main display already.
     */
    fun mainDisplayLaunch(activity: Activity): Bundle? {
        if (isOnMainDisplay(activity)) return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
    }

    /** Whether [activity]'s window is on the device's main display (the Thor's top screen). */
    fun isOnMainDisplay(activity: Activity): Boolean = displayOf(activity) == Display.DEFAULT_DISPLAY

    /** The display [activity] is on now (it follows a move to another display: [moveToDisplay]). */
    fun displayOf(activity: Activity): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.displayId

    /**
     * Moves [activity]'s task, running, to display [displayId] (its singleTask instance stays: onNewIntent and a
     * configuration change, no restart - checked on the Thor). CLOSE COMPANION with SWAP SCREENS: the game goes to the
     * second screen, so the main one is free for other apps. False if Android refused.
     */
    fun moveToDisplay(activity: Activity, displayId: Int): Boolean = runCatching {
        activity.startActivity(
            Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
        )
    }.isSuccess

    /** On an activity reopened by [reopenOnMainDisplay]: when, so a move that didn't take isn't tried again in a loop. */
    private const val EXTRA_MOVED_AT = "com.pokedaisy.app.MOVED_TO_MAIN_DISPLAY_AT"
    private const val MOVE_GUARD_MS = 5_000L

    /**
     * The app opened on the second screen (its icon tapped on the bottom screen's launcher, or its task moved there):
     * [activity] reopens as a fresh task of its own intent on the main display and this one closes, so the app uses
     * both screens - the activity on the main one, its Presentation (the launcher's media) on the other. Never twice
     * within a few seconds, in case Android keeps it where it was. True = it's moving: the caller stops there.
     */
    fun reopenOnMainDisplay(activity: Activity): Boolean {
        val options = mainDisplayLaunch(activity) ?: return false
        if (!hasSecond(activity)) return false
        val since = System.currentTimeMillis() - (activity.intent?.getLongExtra(EXTRA_MOVED_AT, 0L) ?: 0L)
        if (since in 0 until MOVE_GUARD_MS) return false
        val next = Intent(activity.intent ?: Intent()).setClass(activity, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_MOVED_AT, System.currentTimeMillis())
        if (runCatching { activity.startActivity(next, options) }.isFailure) return false
        activity.finishAndRemoveTask()
        return true
    }

    @Volatile private var lastBring = 0L

    /**
     * A running game whose task went to the second screen: asks Android to bring the task back to the main display
     * (its singleTask instance stays - no restart). At most once every few seconds: an attempt that doesn't move it
     * resumes it again, and that mustn't loop.
     */
    fun bringToMainDisplay(activity: Activity) {
        if (isOnMainDisplay(activity) || !hasSecond(activity)) return
        val now = System.currentTimeMillis()
        if (now - lastBring < MOVE_GUARD_MS) return
        lastBring = now
        moveToDisplay(activity, Display.DEFAULT_DISPLAY)
    }

    /** SWAP SCREENS only means something with two. */
    fun hasSecond(context: Context) = second(context) != null

    private val displayType = runCatching { Display::class.java.getMethod("getType") }.getOrNull()

    /**
     * [display] is plugged in or cast to rather than built into the device: HDMI / USB (Display.TYPE_EXTERNAL, 2), a
     * Wi-Fi display such as Samsung's Smart View (TYPE_WIFI, 3) or a cast's virtual one (TYPE_VIRTUAL, 5) - read through
     * the hidden Display.getType(). The Thor's bottom screen is TYPE_INTERNAL; the emulator's overlay displays (4) stand
     * in for one. Unknown (the method gone) = built in, as before.
     */
    fun isExternal(display: Display): Boolean = when (typeOf(display)) {
        2, 3, 5 -> true
        else -> false
    }

    /** Display.getType(), or null where it can't be read. */
    fun typeOf(display: Display): Int? = runCatching { displayType?.invoke(display) as? Int }.getOrNull()

    /** SWAP SCREENS for the second screen there is: [Prefs.swapScreensExternal] for a TV, else [Prefs.swapScreens]. */
    fun swapped(context: Context, prefs: Prefs, display: Display? = second(context)): Boolean =
        if (display != null && isExternal(display)) prefs.swapScreensExternal else prefs.swapScreens

    fun setSwapped(context: Context, prefs: Prefs, on: Boolean) {
        val display = second(context)
        if (display != null && isExternal(display)) prefs.swapScreensExternal = on else prefs.swapScreens = on
    }

    /** The second screen's width / height, or null with one. */
    fun secondAspect(context: Context): Float? {
        val size = Point().also { p -> second(context)?.let { @Suppress("DEPRECATION") it.getRealSize(p) } ?: return null }
        return if (size.x > 0 && size.y > 0) size.x.toFloat() / size.y else null
    }
}
