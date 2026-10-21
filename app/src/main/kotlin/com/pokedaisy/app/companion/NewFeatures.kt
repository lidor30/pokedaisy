package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.i18n.tk

/**
 * The NEW tag on a just-shipped feature's settings row (both screens: `SettingRow(labelBadge = NewFeatures.badge(id))`,
 * drawn green like the Library's NEW), for [DAYS] days from the first launch of a build that has the feature - every
 * id in [ALL] is dated at app start ([init]), so a feature that comes with an update is new to that player for a week
 * from the update. Every new feature gets one (CLAUDE.md): add its id to [ALL] and the badge to its row.
 */
object NewFeatures {
    /** SETTINGS > HUD (companion.ui.FieldHud). */
    const val HUD = "hud"

    /** SETTINGS > BATTLE BUTTONS (companion.ui.BattleButtons). */
    const val BATTLE_BUTTONS = "battle_buttons"

    /** HOTKEYS > RESTART GAME and CLOSE APP (Hotkeys.Action.badge). */
    const val GAME_HOTKEYS = "game_hotkeys"

    /** SETTINGS > COMPANION VIEW: the companion as a window over the game on one screen (SidePanel). */
    const val COMPANION_WINDOW = "companion_window"

    /** SETTINGS > HUD > PC: the game's PC from a button beside BAG. */
    const val HUD_PC = "hud_pc"

    /** SETTINGS > QUICK MENU and HOTKEYS > QUICK MENU (companion.ui.QuickMenu). */
    const val QUICK_MENU = "quick_menu"

    /** SETTINGS > HUD > REPEL STEPS: a REPEL's steps left, counting down beside the balls. */
    const val HUD_REPEL = "hud_repel"

    /** SETTINGS > SINGLE SCREEN: a two-screen device played on one, the companion over the game. */
    const val SINGLE_SCREEN = "single_screen"

    /** The MORE tab right of the gear (companion.ui.MoreScreen): its row is SETTINGS > TAB BAR. */
    const val MORE_TAB = "more_tab"

    /** SETTINGS > TOUCH CONTROLS: layouts, size, opacity, extra buttons and EDIT LAYOUT (companion.ui.TouchControls). */
    const val TOUCH_CONTROLS = "touch_controls"

    /** SETTINGS > COMPANION BUTTON: hide the edge button that opens the companion on one screen (SidePanel). */
    const val COMPANION_TAB = "companion_tab"

    /** SETTINGS > REUSABLE TMS: a TM stays in the bag after it teaches a move (companion.data.ReusableTms). */
    const val REUSABLE_TMS = "reusable_tms"

    /** SETTINGS > SHADERS > UNLIT SCREEN: the GBA's screen with no light of its own (ScreenShaders.UNLIT). */
    const val UNLIT_SCREEN = "unlit_screen"

    /** SETTINGS > DISPLAY OPTIONS > LIGHT COMPANION: a lighter companion for slow devices (LightCompanion). */
    const val LIGHT_COMPANION = "light_companion"

    /** HOTKEYS > COMPANION: opens the companion over the game on one screen (Hotkeys.Action.COMPANION). */
    const val COMPANION_HOTKEY = "companion_hotkey"

    /** Every feature that gets the tag. */
    val ALL = listOf(
        HUD, BATTLE_BUTTONS, GAME_HOTKEYS, COMPANION_WINDOW, HUD_PC, QUICK_MENU, HUD_REPEL, SINGLE_SCREEN, MORE_TAB, TOUCH_CONTROLS,
        COMPANION_TAB, REUSABLE_TMS, UNLIT_SCREEN, LIGHT_COMPANION, COMPANION_HOTKEY,
    )

    private const val DAYS = 7
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var since: Map<String, Long> = emptyMap()

    @Volatile
    private var started = false

    /** App start: date every feature not seen before (now); the ones seen keep their date. */
    fun init(prefs: Prefs, now: Long = System.currentTimeMillis()) {
        val seen = prefs.newFeatureSince
        val all = seen + ALL.filter { it !in seen }.associateWith { now }
        if (all != seen) prefs.newFeatureSince = all
        since = all
        started = true
    }

    /** Still new: dated less than [DAYS] days ago. Before [init] (screenshot tests, ui-preview) every feature is. */
    fun isNew(id: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!started) return true
        val at = since[id] ?: return false
        return now - at < DAYS * DAY_MS
    }

    /** The row's label badge: NEW while [isNew], else none. */
    fun badge(id: String): String? = if (isNew(id)) NEW_TAG else null

    /** The tag's text, which OptionLine draws green (OptionColors.newBadge). */
    val NEW_TAG = tk("NEW")
}
