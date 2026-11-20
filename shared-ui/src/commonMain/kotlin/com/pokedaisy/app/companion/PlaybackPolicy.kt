package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*

/** Shared scene priority and speed choices. Renderer pacing and exceptional modes stay with the host. */
object PlaybackPolicy {
    val SPEED_STEPS = listOf(1f, 1.5f, 2f, 3f, 4f, .5f, .25f)
    const val CYCLE_STEPS = 5

    /** The hotkey cycles normal/fast steps; a manually selected slow step returns to 1x. */
    fun cycleSpeed(current: Float): Float {
        val index = SPEED_STEPS.indexOf(current)
        return SPEED_STEPS[if (index !in 0 until CYCLE_STEPS) 0 else (index + 1) % CYCLE_STEPS]
    }

    fun requestedSpeed(fastForward: Boolean, ffMax: Float, slowmo: Boolean, scene: Float): Float = when {
        fastForward -> if (ffMax > 0f) ffMax else Float.POSITIVE_INFINITY
        slowmo -> .5f
        else -> scene
    }

    enum class Scene { FIELD, TEXT, BATTLE, MENU, BIKE, SURF }

    fun scene(battleState: Int, menu: Boolean, map: Boolean, battling: Boolean, field: Int): Scene = when {
        battleState == BATTLE_INPUT_PARTY_OPEN || battleState == BATTLE_INPUT_BAG_OPEN || menu || map ||
            field and GameField.MENU != 0 -> Scene.MENU
        battleState != BATTLE_INPUT_NONE || battling || field and GameField.BATTLE != 0 -> Scene.BATTLE
        field and GameField.TEXT != 0 -> Scene.TEXT
        field and (GameField.LOCKED or GameField.AVATAR) == GameField.AVATAR && field and GameField.SURF != 0 -> Scene.SURF
        field and (GameField.LOCKED or GameField.AVATAR) == GameField.AVATAR && field and GameField.BIKE != 0 -> Scene.BIKE
        else -> Scene.FIELD
    }

    fun sceneSpeed(scene: Scene, game: Float, battle: Float, text: Float, menu: Float, bike: Float, surf: Float): Float {
        val selected = when (scene) {
            Scene.BATTLE -> battle
            Scene.TEXT -> text
            Scene.MENU -> menu
            Scene.BIKE -> bike
            Scene.SURF -> surf
            Scene.FIELD -> 0f
        }
        return if (selected > 0f) selected else game
    }

    fun smartSpeed(requested: Float, smart: Boolean, battleState: Int, menu: Boolean, map: Boolean,
                   battleWaiting: Boolean = false): Float =
        if (smart && requested > 1f && smartSlows(battleState, menu, map, battleWaiting)) 1f else requested
}
