package com.pokedaisy.app.companion.data

/**
 * Android's key codes and their names (KeyEvent's KEYCODE_ constants, Android 14), in plain Kotlin: the key bindings
 * (GAME BUTTONS, HOTKEYS) are kept by these names and handled by these numbers on every platform, so a host without
 * Android's KeyEvent (the iPad app) maps its own keys onto them. KeyCodesTest checks the table against KeyEvent.
 */
object KeyCodes {
    const val UNKNOWN = 0
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val APP_SWITCH = 187
    const val VOLUME_MUTE = 164
    const val DPAD_UP_LEFT = 268
    const val DPAD_DOWN_LEFT = 269
    const val DPAD_UP_RIGHT = 270
    const val DPAD_DOWN_RIGHT = 271

    /** A key's name without "KEYCODE_" (96 -> "BUTTON_A"), as KeyEvent.keyCodeToString gives it; an unknown code is its number. */
    fun name(code: Int): String = NAMES.getOrNull(code) ?: code.toString()

    /** The code for a [name] (no "KEYCODE_"), or a number written as one; null for a name Android 14 doesn't have. */
    fun code(name: String): Int? = BY_NAME[name] ?: name.toIntOrNull()?.takeIf { it >= 0 }

    private val NAMES = arrayOf(
        "UNKNOWN", "SOFT_LEFT", "SOFT_RIGHT", "HOME", "BACK", "CALL", "ENDCALL", "0", "1", "2", "3", "4", "5", "6", "7",
        "8", "9", "STAR", "POUND", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "VOLUME_UP",
        "VOLUME_DOWN", "POWER", "CAMERA", "CLEAR", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N",
        "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "COMMA", "PERIOD", "ALT_LEFT", "ALT_RIGHT",
        "SHIFT_LEFT", "SHIFT_RIGHT", "TAB", "SPACE", "SYM", "EXPLORER", "ENVELOPE", "ENTER", "DEL", "GRAVE", "MINUS",
        "EQUALS", "LEFT_BRACKET", "RIGHT_BRACKET", "BACKSLASH", "SEMICOLON", "APOSTROPHE", "SLASH", "AT", "NUM",
        "HEADSETHOOK", "FOCUS", "PLUS", "MENU", "NOTIFICATION", "SEARCH", "MEDIA_PLAY_PAUSE", "MEDIA_STOP",
        "MEDIA_NEXT", "MEDIA_PREVIOUS", "MEDIA_REWIND", "MEDIA_FAST_FORWARD", "MUTE", "PAGE_UP", "PAGE_DOWN",
        "PICTSYMBOLS", "SWITCH_CHARSET", "BUTTON_A", "BUTTON_B", "BUTTON_C", "BUTTON_X", "BUTTON_Y", "BUTTON_Z",
        "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2", "BUTTON_THUMBL", "BUTTON_THUMBR", "BUTTON_START",
        "BUTTON_SELECT", "BUTTON_MODE", "ESCAPE", "FORWARD_DEL", "CTRL_LEFT", "CTRL_RIGHT", "CAPS_LOCK", "SCROLL_LOCK",
        "META_LEFT", "META_RIGHT", "FUNCTION", "SYSRQ", "BREAK", "MOVE_HOME", "MOVE_END", "INSERT", "FORWARD",
        "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_CLOSE", "MEDIA_EJECT", "MEDIA_RECORD", "F1", "F2", "F3", "F4", "F5", "F6",
        "F7", "F8", "F9", "F10", "F11", "F12", "NUM_LOCK", "NUMPAD_0", "NUMPAD_1", "NUMPAD_2", "NUMPAD_3", "NUMPAD_4",
        "NUMPAD_5", "NUMPAD_6", "NUMPAD_7", "NUMPAD_8", "NUMPAD_9", "NUMPAD_DIVIDE", "NUMPAD_MULTIPLY",
        "NUMPAD_SUBTRACT", "NUMPAD_ADD", "NUMPAD_DOT", "NUMPAD_COMMA", "NUMPAD_ENTER", "NUMPAD_EQUALS",
        "NUMPAD_LEFT_PAREN", "NUMPAD_RIGHT_PAREN", "VOLUME_MUTE", "INFO", "CHANNEL_UP", "CHANNEL_DOWN", "ZOOM_IN",
        "ZOOM_OUT", "TV", "WINDOW", "GUIDE", "DVR", "BOOKMARK", "CAPTIONS", "SETTINGS", "TV_POWER", "TV_INPUT",
        "STB_POWER", "STB_INPUT", "AVR_POWER", "AVR_INPUT", "PROG_RED", "PROG_GREEN", "PROG_YELLOW", "PROG_BLUE",
        "APP_SWITCH", "BUTTON_1", "BUTTON_2", "BUTTON_3", "BUTTON_4", "BUTTON_5", "BUTTON_6", "BUTTON_7", "BUTTON_8",
        "BUTTON_9", "BUTTON_10", "BUTTON_11", "BUTTON_12", "BUTTON_13", "BUTTON_14", "BUTTON_15", "BUTTON_16",
        "LANGUAGE_SWITCH", "MANNER_MODE", "3D_MODE", "CONTACTS", "CALENDAR", "MUSIC", "CALCULATOR", "ZENKAKU_HANKAKU",
        "EISU", "MUHENKAN", "HENKAN", "KATAKANA_HIRAGANA", "YEN", "RO", "KANA", "ASSIST", "BRIGHTNESS_DOWN",
        "BRIGHTNESS_UP", "MEDIA_AUDIO_TRACK", "SLEEP", "WAKEUP", "PAIRING", "MEDIA_TOP_MENU", "11", "12",
        "LAST_CHANNEL", "TV_DATA_SERVICE", "VOICE_ASSIST", "TV_RADIO_SERVICE", "TV_TELETEXT", "TV_NUMBER_ENTRY",
        "TV_TERRESTRIAL_ANALOG", "TV_TERRESTRIAL_DIGITAL", "TV_SATELLITE", "TV_SATELLITE_BS", "TV_SATELLITE_CS",
        "TV_SATELLITE_SERVICE", "TV_NETWORK", "TV_ANTENNA_CABLE", "TV_INPUT_HDMI_1", "TV_INPUT_HDMI_2",
        "TV_INPUT_HDMI_3", "TV_INPUT_HDMI_4", "TV_INPUT_COMPOSITE_1", "TV_INPUT_COMPOSITE_2", "TV_INPUT_COMPONENT_1",
        "TV_INPUT_COMPONENT_2", "TV_INPUT_VGA_1", "TV_AUDIO_DESCRIPTION", "TV_AUDIO_DESCRIPTION_MIX_UP",
        "TV_AUDIO_DESCRIPTION_MIX_DOWN", "TV_ZOOM_MODE", "TV_CONTENTS_MENU", "TV_MEDIA_CONTEXT_MENU",
        "TV_TIMER_PROGRAMMING", "HELP", "NAVIGATE_PREVIOUS", "NAVIGATE_NEXT", "NAVIGATE_IN", "NAVIGATE_OUT",
        "STEM_PRIMARY", "STEM_1", "STEM_2", "STEM_3", "DPAD_UP_LEFT", "DPAD_DOWN_LEFT", "DPAD_UP_RIGHT",
        "DPAD_DOWN_RIGHT", "MEDIA_SKIP_FORWARD", "MEDIA_SKIP_BACKWARD", "MEDIA_STEP_FORWARD", "MEDIA_STEP_BACKWARD",
        "SOFT_SLEEP", "CUT", "COPY", "PASTE", "SYSTEM_NAVIGATION_UP", "SYSTEM_NAVIGATION_DOWN",
        "SYSTEM_NAVIGATION_LEFT", "SYSTEM_NAVIGATION_RIGHT", "ALL_APPS", "REFRESH", "THUMBS_UP", "THUMBS_DOWN",
        "PROFILE_SWITCH", "VIDEO_APP_1", "VIDEO_APP_2", "VIDEO_APP_3", "VIDEO_APP_4", "VIDEO_APP_5", "VIDEO_APP_6",
        "VIDEO_APP_7", "VIDEO_APP_8", "FEATURED_APP_1", "FEATURED_APP_2", "FEATURED_APP_3", "FEATURED_APP_4",
        "DEMO_APP_1", "DEMO_APP_2", "DEMO_APP_3", "DEMO_APP_4", "KEYBOARD_BACKLIGHT_DOWN", "KEYBOARD_BACKLIGHT_UP",
        "KEYBOARD_BACKLIGHT_TOGGLE", "STYLUS_BUTTON_PRIMARY", "STYLUS_BUTTON_SECONDARY", "STYLUS_BUTTON_TERTIARY",
        "STYLUS_BUTTON_TAIL", "RECENT_APPS", "MACRO_1", "MACRO_2", "MACRO_3", "MACRO_4",
    )

    private val BY_NAME: Map<String, Int> = NAMES.withIndex().associate { (i, n) -> n to i }
}
