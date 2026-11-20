package com.pokedaisy.app.companion.data

/**
 * DAY AND NIGHT (SETTINGS > MAKEOVER): the overworld follows the device's clock - a warm dawn, plain day, an orange dusk
 * and a blue night - in any GBA game. It's a tint of the map's palettes and the sprites' as the core draws them
 * ([com.pokedaisy.app.MgbaCore.pkSetTint], pk_wide.c's `tintPalettes`): the text windows' palettes (13-15) keep their
 * colours, nothing of the game's memory or save changes, and EXPAND's margins (drawn from the same palettes) follow.
 * Only outdoors ([outdoors]) and only on the field: the engine eases it in and out ([com.pokedaisy.app.EmulatorEngine.dayNightTint]).
 */
object DayNight {
    /** No tint (red, green, blue of 256). */
    val NONE = intArrayOf(256, 256, 256)

    private val NIGHT = intArrayOf(135, 150, 225)
    private val DAWN = intArrayOf(245, 220, 220)
    private val DUSK = intArrayOf(255, 200, 165)

    /** The day's colours by hour: between two the tint goes linearly (picked on headless field shots of FireRed and
     * Emerald, text boxes up and not). */
    private val KEYS = listOf(
        0f to NIGHT, 4.5f to NIGHT, 6f to DAWN, 7.5f to NONE, 16.5f to NONE, 18f to DUSK, 19.5f to NIGHT, 24f to NIGHT,
    )

    /** The tint at [hour] (0 until 24, minutes as its fraction). */
    fun at(hour: Float): IntArray {
        val h = ((hour % 24f) + 24f) % 24f
        val i = KEYS.indexOfLast { it.first <= h }.coerceIn(0, KEYS.size - 2)
        val (h0, a) = KEYS[i]
        val (h1, b) = KEYS[i + 1]
        val t = if (h1 > h0) (h - h0) / (h1 - h0) else 0f
        return IntArray(3) { c -> (a[c] + (b[c] - a[c]) * t).toInt() }
    }

    /** The tint now, by the device's clock. */
    fun now(): IntArray {
        val c = localNow()
        return at(c.hour + c.minute / 60f)
    }

    /** gMapHeader.mapType under the sky: town, city, route, ocean route (pret's MAP_TYPE_*: the same in FireRed and
     * Emerald). Unknown (-1) counts as outdoors: the field read doesn't know every game. */
    fun outdoors(mapType: Int) = mapType < 0 || mapType == 1 || mapType == 2 || mapType == 3 || mapType == 6

    /** Games that change with the time of day themselves: no second tint on top. */
    val OWN_DAY_NIGHT = setOf(GameKind.UNBOUND, GameKind.HEART_AND_SOUL, GameKind.ELITE_REDUX)
}
