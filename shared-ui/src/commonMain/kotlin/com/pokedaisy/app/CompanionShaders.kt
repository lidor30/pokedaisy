package com.pokedaisy.app

import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter
import kotlin.math.roundToInt

/** One companion color/filter program for Android RuntimeShader and Skia. It preserves premultiplied alpha. */
object CompanionShaders {
    data class Settings(val colors: Boolean, val filter: ScreenFilter, val gridX: Float, val gridY: Float) {
        val active get() = colors || filter != ScreenFilter.NONE
    }

    fun settings(colors: Boolean, filter: ScreenFilter, grid: GridStrength, enabled: Boolean): Settings {
        val strength = ScreenShaders.gridFor(filter, grid)
        return Settings(colors && enabled, if (enabled) filter else ScreenFilter.NONE, strength[0], strength[1])
    }

    /** Whole view-pixel cells keep UI grid lines even; before layout use the original density-scaled default. */
    fun cellSize(gamePixel: Float, density: Float): Float =
        (if (gamePixel > 0f) gamePixel else 6.75f * density / 2.3f).roundToInt().coerceAtLeast(2).toFloat()

    const val SOURCE = """
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
