package com.pokedaisy.app

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.View
import com.pokedaisy.app.companion.ScreenFilter
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

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
    private var gbaColors = false
    private var filter = ScreenFilter.NONE
    private var grid = floatArrayOf(0f, 0f)
    /** The grid's cell in view px: the game's own pixel on screen ([setCell]), 6.75 on the Thor. */
    private var cell = 0f
    /** Views that are the game's own picture, a DS game's touch screen: FILTER whatever ON COMPANION says ([trackGame]). */
    private val gameViews: MutableSet<View> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private var gameFilter = ScreenFilter.NONE
    private var gameColors = false

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

    /** GBA COLORS, and the filter to draw ([ScreenFilter.NONE] when ON COMPANION is off) at GRID's [grid]; [gameColors] and
     * [gameFilter] are SHADERS' own either way, for [trackGame]'s views. */
    fun set(gbaColors: Boolean, filter: ScreenFilter, grid: FloatArray, gameColors: Boolean, gameFilter: ScreenFilter) {
        if (gbaColors == this.gbaColors && filter == this.filter && gameColors == this.gameColors &&
            gameFilter == this.gameFilter && grid.contentEquals(this.grid)
        ) return
        this.gbaColors = gbaColors
        this.filter = filter
        this.gameColors = gameColors
        this.gameFilter = gameFilter
        this.grid = grid
        applyAll()
    }

    private fun applyAll() {
        views.toList().forEach(::apply)
        gameViews.toList().forEach(::apply)
    }

    private fun apply(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val game = view in gameViews
        val filter = if (game) gameFilter else filter
        val colors = if (game) gameColors else gbaColors
        if (!colors && filter == ScreenFilter.NONE) {
            view.setRenderEffect(null)
            return
        }
        // Until the game is laid out: the Thor's 6.75 px, scaled to this display. Rounded to whole view
        // pixels (7 on the Thor): at 6.75 cells came out 6 or 7 px and the lines fell at different
        // sub-pixel phases, so some looked thicker than others.
        val unit = (if (cell > 0f) cell else 6.75f * view.resources.displayMetrics.density / 2.3f)
            .roundToInt().coerceAtLeast(2).toFloat()
        // AGSL compiles here, on the device: a bad shader leaves the companion as it is, not a crash.
        val shader = runCatching { RuntimeShader(AGSL) }
            .onFailure { Log.w("pokedaisy", "companion shader failed to compile", it) }
            .getOrNull() ?: return
        shader.setFloatUniform("uColors", if (colors) 1f else 0f)
        shader.setFloatUniform("uFilter", filter.ordinal.toFloat())
        shader.setFloatUniform("uUnit", unit)
        shader.setFloatUniform("uGrid", grid[0], grid[1])
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }

    /**
     * GBA COLORS then the filter ([ScreenFilter] ordinal: 1 LCD, 2 LCD PAPER, 3 SCANLINES, 4 CRT),
     * per view pixel `p` (its centre, top-left origin), cells `uUnit` px wide (the game's pixel).
     * The view's pixels come premultiplied.
     */
    private const val AGSL = """
        uniform shader content;
        uniform float uColors;
        uniform float uFilter;
        uniform float uUnit;
        uniform float2 uGrid;
        const float3 luma709 = float3(0.2126, 0.7152, 0.0722);

        float3 gbaColors(float3 c) {
            float3x3 lcd = float3x3(
                0.84, 0.09, 0.15,
                0.18, 0.67, 0.10,
                0.00, 0.26, 0.73);
            c = pow(c, float3(2.7));
            c = lcd * clamp(c * 0.99, 0.0, 1.0);
            return pow(c, float3(0.4625));
        }
        float hash(float2 p) {
            p = fract(p * float2(0.1031, 0.1030));
            p += dot(p, p.yx + 33.33);
            return fract((p.x + p.y) * p.x);
        }
        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
                       mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
        }
        float3 paper(float2 p) {
            float n = 0.2 * noise(p / 90.0) + 0.3 * noise(p / 22.0) + 0.3 * noise(p / 5.0) + 0.2 * hash(p);
            float fibre = max(smoothstep(0.7, 0.95, noise(float2(p.x / 2.0, p.y / 14.0) + 17.0)),
                              smoothstep(0.7, 0.95, noise(float2(p.x / 14.0, p.y / 2.0) + 51.0)));
            return float3(0.98, 0.96, 0.90) * (0.88 + 0.12 * n - 0.025 * fibre);
        }

        half4 main(float2 p) {
            half4 s = content.eval(p);
            if (s.a <= 0.0) return s;
            float3 c = clamp(float3(s.rgb) / s.a, 0.0, 1.0);
            if (uColors > 0.5) c = gbaColors(c);
            if (uFilter > 0.5 && uFilter < 1.5) {
                // LCD: a 2 px line at the top and left of each cell.
                float2 at = fract(p / uUnit) * uUnit;
                if (at.x < 2.0 || at.y < 2.0) c *= 1.0 - uGrid.x;
            } else if (uFilter < 2.5 && uFilter > 1.5) {
                // LCD PAPER: the game's soft grid, desaturation and paper.
                float2 d = abs(fract(p / uUnit) - 0.5);
                float x2 = max(d.x, d.y);
                x2 *= x2;
                float w = 48.0 * (x2 * x2 - 8.0 / 3.0 * x2 * x2 * x2);
                c = mix(c, float3(dot(c, luma709)), 0.15);
                c *= 1.0 - uGrid.x * clamp(mix(w * w, w, uGrid.y) * 0.85, 0.0, 1.0);
                c = mix(c, paper(p) * c, dot(c, luma709));
            } else if (uFilter < 3.5 && uFilter > 2.5) {
                // SCANLINES: a 2 px line at the top of each cell row, lighter than the game's.
                if (fract(p.y / uUnit) * uUnit < 2.0) c *= 0.7;
            } else if (uFilter > 3.5) {
                // CRT: a beam per cell row and the RGB mask.
                c = pow(c, float3(2.2));
                float dy = fract(p.y / uUnit) - 0.5;
                float3 w = mix(float3(0.22), float3(0.32), c);
                c *= exp(-dy * dy / (2.0 * w * w));
                float stripe = mod(floor(p.x), 3.0);
                float3 mask = float3(0.6);
                if (stripe < 0.5) mask.r = 1.0;
                else if (stripe < 1.5) mask.g = 1.0;
                else mask.b = 1.0;
                c = pow(clamp(c * mask * 1.5, 0.0, 1.0), float3(1.0 / 2.2));
            }
            return half4(half3(c) * s.a, s.a);
        }
    """
}
