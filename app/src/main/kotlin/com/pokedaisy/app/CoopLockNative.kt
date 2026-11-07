package com.pokedaisy.app

/**
 * JNI surface of `pk_coop_lock_jni.c` / `pk_coop_lock.c`: the co-op story lock. While it is on, a story script (a cutscene, a
 * gym leader, a rival...) doesn't start by itself: it waits in a hook on the game's ScriptContext_SetupScript until [go].
 * Emu thread, like [MgbaCore]'s pkCoop calls; every call is a no-op (0 / empty) on a ROM that isn't retail English Emerald.
 *
 * The hold's life: [held] says which story event (a `StoryEvent.id` of the generated table) waits and of what kind
 * (0 COORD, 1 OBJECT, 2 FRAME); the native tick drops it when the player walks away or the map changes; [go] starts it.
 */
object CoopLockNative {
    init {
        System.loadLibrary("pokedaisy")
    }

    /** Puts the hook in (every function's code CRC-checked first). 1 = in (or already in), 0 = nothing changed: another ROM,
     * a changed ScriptContext_SetupScript, or another block in the lock's code area. */
    external fun install(): Int

    /** The hook, its code block and its RAM out. */
    external fun uninstall()

    /** Turns the lock on or off; off also drops any hold. */
    external fun setActive(on: Boolean)

    /** `[id, kind]` of the held story event; id 0 = none. */
    external fun held(): IntArray

    /** Starts the held event [id]: 1 = started / allowed, 0 = not held (or another id), -1 = not now (a script runs, the field
     * controls are locked, or there is no safe frame): nothing changed, ask again on a later frame. */
    external fun go(id: Int): Int

    /** Drops the hold. */
    external fun cancel()
}
