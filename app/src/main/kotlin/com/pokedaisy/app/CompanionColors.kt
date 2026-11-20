package com.pokedaisy.app

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.pokedaisy.app.companion.ScreenFilter
import java.util.Collections
import java.util.WeakHashMap

/**
 * SHADERS on the companion and the status bar (SHADERS > ON COMPANION, [Prefs.companionShaders]):
 * every view [track]ed here is drawn through one AGSL [RenderEffect] - GBA COLORS
 * ([ScreenShaders.GBA_COLOR]'s formula), then FILTER with cells the size of the game's own pixels
 * on screen ([setCell]: 6.75 px on the Thor), so both screens show the same grid - the companion's
 * own 3 px GBA pixel was too fine to see on the bottom screen, it only darkened it. ROM art and the
 * FireRed-style windows shift with the game instead of each colour being restyled. The filters are
 * the game's, fitted to UI text (picked in the browser harness on ui-preview renders at 1240x1080):
 * cells rounded to whole pixels (7 on the Thor) so every line is the same; LCD and SCANLINES with a
 * 2 px line, LCD PAPER as is, all with GRID's strength; SCANLINES at 0.7 (the game's 0.5 cut through letters);
 * CRT's beams and RGB mask without its horizontal blend (it would blur the text). Android 13+
 * (RuntimeShader); older devices keep the companion's own look.
 */
object CompanionColors {
    private val views: MutableSet<View> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private var settings = CompanionShaders.Settings(false, ScreenFilter.NONE, 0f, 0f)
    /** The grid's cell in view px: the game's own pixel on screen ([setCell]), 6.75 on the Thor. */
    private var cell = 0f
    private var revision by mutableIntStateOf(0)
    /** Views that are the game's own picture, a DS game's touch screen: FILTER whatever ON COMPANION says ([trackGame]). */
    private val gameViews: MutableSet<View> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private var gameSettings = settings

    /** Follows [set] from now on (UI thread). */
    fun <T : View> track(view: T): T {
        views += view
        apply(view)
        return view
    }

    /** As [track], for a view showing the game itself (GitHub #443: a DS game's bottom screen had no grid): SHADERS' FILTER
     * reaches it with ON COMPANION off too. */
    fun <T : View> trackGame(view: T): T {
        gameViews += view
        apply(view)
        return view
    }

    /** The game's pixel height on screen ([EmulatorView.onGamePixel]). */
    fun setCell(px: Float) {
        if (px == cell) return
        cell = px
        applyAll()
    }

    /** Shared settings for companion views and, separately, game touch-screen views. */
    fun set(settings: CompanionShaders.Settings, gameSettings: CompanionShaders.Settings) {
        if (settings == this.settings && gameSettings == this.gameSettings) return
        this.settings = settings
        this.gameSettings = gameSettings
        applyAll()
    }

    /** Bumped each time the effects change: a Compose layer drawn with [gameEffect] reads it to follow them. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun applyAll() {
        revision++
        views.toList().forEach(::apply)
        gameViews.toList().forEach(::apply)
        version.intValue++
    }

    /**
     * What [trackGame]'s views are drawn through, for a Compose layer that stands in for the game's own screen: a DS
     * battle's strip on one screen (its buttons and message over the game's text box rows), which kept no grid while the
     * game around it had one (Discord, 2026-11-17). Null = none (or Android 12 and older). Read [version] to follow it.
     */
    fun gameEffect(density: Float): RenderEffect? = effect(game = true, density)

    private fun apply(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        view.setRenderEffect(effect(view in gameViews, view.resources.displayMetrics.density))
    }

    /** The same shader for Compose-owned chrome/HUD layers, kept off the native game surface and controls. */
    @Composable
    fun composeEffect(view: View): androidx.compose.ui.graphics.RenderEffect? {
        val changed = revision
        return remember(view, changed, view.resources.displayMetrics.density) {
            effect(false, view.resources.displayMetrics.density)?.asComposeRenderEffect()
        }
    }

    private fun effect(game: Boolean, density: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val settings = if (game) gameSettings else settings
        if (!settings.active) return null
        val unit = CompanionShaders.cellSize(cell, density)
        // AGSL compiles here, on the device: a bad shader leaves the companion as it is, not a crash.
        val shader = runCatching { RuntimeShader(CompanionShaders.SOURCE) }
            .onFailure { Log.w("pokedaisy", "companion shader failed to compile", it) }
            .getOrNull() ?: return null
        shader.setFloatUniform("uColors", if (settings.colors) 1f else 0f)
        shader.setFloatUniform("uFilter", settings.filter.ordinal.toFloat())
        shader.setFloatUniform("uUnit", unit)
        shader.setFloatUniform("uGrid", settings.gridX, settings.gridY)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

}
