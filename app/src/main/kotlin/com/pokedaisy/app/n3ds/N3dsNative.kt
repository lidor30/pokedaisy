package com.pokedaisy.app.n3ds

import android.util.Log
import android.view.Surface
import com.pokedaisy.app.FeatureFlags

/**
 * The 3DS core (Azahar) through libpk_n3ds.so (n3ds/src/main/cpp/pk_n3ds_jni.cpp), which the n3ds module builds only
 * with the n3ds flag. Every use checks [available] first: without the flag the library isn't in the APK at all.
 */
object N3dsNative {
    /** Whether the library is built in and loaded. */
    val available: Boolean by lazy {
        FeatureFlags.n3ds() && runCatching { System.loadLibrary("pk_n3ds") }
            .onFailure { Log.w("pokedaisy/n3ds", "libpk_n3ds didn't load", it) }.isSuccess
    }

    /** What [probeCart] read off a cart. [status] is Azahar's Loader::ResultStatus: 0 read, 8 encrypted. */
    data class Probe(val status: Int, val programId: Long?, val title: String) {
        val encrypted: Boolean get() = status == STATUS_ENCRYPTED
    }

    /** The cart at [path], read without booting it; null when it isn't a 3DS file Azahar knows, or no core. */
    fun probeCart(path: String): Probe? {
        if (!available) return null
        val parts = probe(path)?.split('\t') ?: return null
        return Probe(
            status = parts.getOrNull(0)?.toIntOrNull() ?: return null,
            programId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toULong(16)?.toLong(),
            title = parts.getOrNull(2).orEmpty(),
        )
    }

    const val STATUS_ENCRYPTED = 8

    /** The 3DS buttons' bits for [setButtons] (Azahar's Settings::NativeButton). */
    object Button {
        const val A = 1 shl 0
        const val B = 1 shl 1
        const val X = 1 shl 2
        const val Y = 1 shl 3
        const val UP = 1 shl 4
        const val DOWN = 1 shl 5
        const val LEFT = 1 shl 6
        const val RIGHT = 1 shl 7
        const val L = 1 shl 8
        const val R = 1 shl 9
        const val START = 1 shl 10
        const val SELECT = 1 shl 11
        const val ZL = 1 shl 14
        const val ZR = 1 shl 15
        const val HOME = 1 shl 16
    }

    /** [touch]'s actions. */
    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2

    @JvmStatic private external fun probe(path: String): String?
    /** Once per process, before anything else: Azahar's user folder ([N3dsSaves.root]). */
    fun init(userDir: String): Boolean {
        N3dsAndroidUtils.root = userDir
        return init(userDir, N3dsAndroidUtils::class.java)
    }
    @JvmStatic private external fun init(userDir: String, utils: Class<*>): Boolean
    /** The main (0) or second (1) surface; null when it goes away. Two at boot = top screen + touch screen apart. */
    @JvmStatic external fun setSurface(which: Int, surface: Surface?)
    /** Boots the cart and runs it on the calling thread until [stop]; Azahar's Core::System::ResultStatus (0 = stopped).
     * [oneSurfaceLayout]: Azahar's Settings::LayoutOption for both screens on the main surface, used when there's no
     * second surface (with one, the top screen goes alone on the main surface). */
    @JvmStatic external fun run(path: String, oneSurfaceLayout: Int): Int
    @JvmStatic external fun stop()
    @JvmStatic external fun setPaused(paused: Boolean)
    @JvmStatic external fun setButtons(bits: Int)
    /** The circle pad, -1..1, up positive. */
    @JvmStatic external fun setStick(x: Float, y: Float)
    /** A touch on surface [which] at its pixels; [action] [TOUCH_DOWN] / [TOUCH_MOVE] / [TOUCH_UP]. */
    @JvmStatic external fun touch(which: Int, x: Int, y: Int, action: Int)
    /** Blocking until the game thread has taken / loaded it. */
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    /** The 3D picture at [factor] x the 3DS's own resolution (0 = the window's size); from the next boot. */
    @JvmStatic external fun setResolution(factor: Int)
        /** The game's memory: [ranges] as (address, length) pairs, copied one after another between two frames; null without
     * a game. Blocks for up to a frame: never on the UI thread. */
    @JvmStatic external fun readMemory(ranges: IntArray): ByteArray?
        /** Percent of real time; 0 = flat out. */
    @JvmStatic external fun setSpeed(percent: Int)
    @JvmStatic external fun frames(): Long
    @JvmStatic external fun gameFps(): Double
    /** The top screen of the next frame at [width] x [height], ARGB, top row first; null when there's no frame to take
     * (no game, paused, another one pending). Blocks up to a second: never on the UI thread. */
    @JvmStatic external fun screenshot(width: Int, height: Int): IntArray?
    /** The names of the modules (CROs) loaded after the static one whose header is at [root]: the game's screens (a
     * Pokemon X field is `DllField`, its party `DllPokeList`). Null without a game; blocks a frame at most. */
    @JvmStatic external fun modules(root: Int): String?
}
