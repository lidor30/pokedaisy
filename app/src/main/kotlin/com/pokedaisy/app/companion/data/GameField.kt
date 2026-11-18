package com.pokedaisy.app.companion.data

/** pk_wide.h's field bits, shared by Android's JNI and the portable core. Unknown games return zero. */
object GameField {
    const val KNOWN = 1
    const val LOCKED = 2
    const val BATTLE = 4
    const val TEXT = 8
    const val BIKE = 16
    const val WILD_HELD = 32
    const val SURF = 64
    const val AVATAR = 128
    /** DS only; GBA uses FfMenuWatch. */
    const val MENU = 512
    const val WINDOW = 262144

    /** Ruby/Sapphire's map type controls weather and lighting too, so it must not be changed for indoor running. */
    fun supportsRunIndoors(code: String) = !code.startsWith("AXV") && !code.startsWith("AXP")
}
