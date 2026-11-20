package com.pokedaisy.app.companion

/** The existing Android preference keys and migrations, also used by other hosts. Native storage stays outside UI. */
object TouchStyleCodec {
    private val EXTRAS_BEFORE_ZOOM = setOf("FF", "QUICK_MENU", "TURBO_A", "TURBO_B", "SAVE_STATE", "LOAD_STATE")

    fun read(text: (String) -> String?, int: (String, Int) -> Int, bool: (String, Boolean) -> Boolean): TouchControlsStyle {
        val d = TouchControlsStyle()
        fun <T : Enum<T>> pick(all: Array<T>, key: String, default: T) = text(key)?.let { n -> all.firstOrNull { it.name == n } } ?: default
        val oldVibration = int("touch_vibration_level", if (bool("touch_vibration", false)) 1 else 0)
        return TouchControlsStyle(
            show = TouchShow.entries.getOrElse(int("touch_controls", 0)) { d.show },
            preset = pick(TouchPreset.values(), "touch_preset", d.preset),
            size = pick(TouchSize.values(), "touch_size", d.size),
            look = pick(TouchLook.values(), "touch_look", d.look),
            colors = pick(TouchColors.values(), "touch_colors", d.colors),
            skinGba = text("touch_skin_gba"),
            skinGb = text("touch_skin_gb"),
            position = pick(TouchPosition.values(), "touch_position", d.position),
            opacity = int("touch_opacity", d.opacity),
            extras = text("touch_extras")?.let { s ->
                val saved = s.split(',').mapNotNull { n -> TouchButton.values().firstOrNull { it.name == n && it.extra } }.toSet()
                // Extras that came after this set was saved (ZOOM, REWIND) start as their default, on.
                val known = text("touch_extras_known")?.split(',')?.toSet() ?: EXTRAS_BEFORE_ZOOM
                saved + d.extras.filter { it.name !in known }
            } ?: d.extras,
            xyButtons = bool("touch_xy", d.xyButtons),
            eightWay = bool("touch_eight_way", d.eightWay),
            fadeIdle = bool("touch_fade_idle", d.fadeIdle),
            // VIBRATION was one row of OFF / LIGHT / MEDIUM / STRONG (touch_vibration_level), and a switch before that.
            vibrate = bool("touch_vibrate", oldVibration > 0),
            vibrationStrength = int("touch_vibration_strength", if (oldVibration > 0) oldVibration else d.vibrationStrength),
            useCustom = bool("touch_use_custom", d.useCustom),
            portrait = TouchLayout.decode(text("touch_layout_portrait")),
            landscape = TouchLayout.decode(text("touch_layout_landscape")),
        )
    }

    fun write(v: TouchControlsStyle, text: (String, String?) -> Unit, int: (String, Int) -> Unit,
              bool: (String, Boolean) -> Unit) {
        int("touch_controls", v.show.ordinal)
        text("touch_preset", v.preset.name)
        text("touch_size", v.size.name)
        text("touch_look", v.look.name)
        text("touch_colors", v.colors.name)
        text("touch_skin_gba", v.skinGba)
        text("touch_skin_gb", v.skinGb)
        text("touch_position", v.position.name)
        int("touch_opacity", v.opacity)
        text("touch_extras", v.extras.joinToString(",") { it.name })
        text("touch_extras_known", TouchButton.extras.joinToString(",") { it.name })
        bool("touch_xy", v.xyButtons)
        bool("touch_eight_way", v.eightWay)
        bool("touch_fade_idle", v.fadeIdle)
        bool("touch_vibrate", v.vibrate)
        int("touch_vibration_strength", v.vibrationStrength)
        bool("touch_use_custom", v.useCustom)
        text("touch_layout_portrait", v.portrait.encode())
        text("touch_layout_landscape", v.landscape.encode())
    }
}
