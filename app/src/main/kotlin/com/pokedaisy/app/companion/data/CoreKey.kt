package com.pokedaisy.app.companion.data

/** The game's buttons as the emulator cores take them: bit positions in the key mask the native bridge (MgbaCore) gets. */
object CoreKey {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
    /** The DS's X / Y (melonDS's key mask bits 10 and 11); a GBA core ignores them. */
    const val X = 1 shl 10
    const val Y = 1 shl 11
}
