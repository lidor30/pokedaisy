package com.pokedaisy.app

import com.pokedaisy.app.companion.GridStrength
import com.pokedaisy.app.companion.ScreenFilter

/**
 * Fragment shaders for [EmulatorView]'s passes. Each reads `uTex` at `vUv`; `uTexSize` is
 * the GBA frame's size in pixels, so `vUv * uTexSize` is the position in GBA pixels (y
 * running down the game's rows, like the frame's own). `uGrid` is SHADERS > GRID's strength for
 * the grid filters ([gridFor]: how much a line darkens its pixel, and how wide it is).
 */
internal object ScreenShaders {

    /** A screen effect ([ScreenFilter]): [prescale] draws it at a whole multiple of the GBA's
     * size and scales that smoothly onto the view (hard-edged grids and lines, which would come
     * out uneven at the Thor's 6.75x); without it, it draws at the view's own pixels. */
    class Effect(val frag: String, val prescale: Boolean)

    fun effectFor(filter: ScreenFilter): Effect? = when (filter) {
        ScreenFilter.NONE -> null
        ScreenFilter.LCD -> LCD
        ScreenFilter.LCD_PAPER -> LCD_PAPER
        ScreenFilter.SCANLINES -> SCANLINES
        ScreenFilter.CRT -> CRT
    }

    /** `uGrid` for [filter] at [strength]: x = how much a grid line darkens its pixel, y = line
     * width (LCD PAPER: 0 = the thin squared curve, 1 = simpletex's full one). Picked in the
     * browser harness on Emerald's intro and FireRed's naming screen at the Thor's 1620x1080. */
    fun gridFor(filter: ScreenFilter, strength: GridStrength): FloatArray = when (filter) {
        ScreenFilter.LCD -> when (strength) {
            GridStrength.SOFT -> floatArrayOf(0.2f, 0f)
            GridStrength.MEDIUM -> floatArrayOf(0.35f, 0f)
            GridStrength.STRONG -> floatArrayOf(0.5f, 0f)
        }
        ScreenFilter.LCD_PAPER -> when (strength) {
            GridStrength.SOFT -> floatArrayOf(0.3f, 0f)
            GridStrength.MEDIUM -> floatArrayOf(0.45f, 0.5f)
            GridStrength.STRONG -> floatArrayOf(0.6f, 1f)
        }
        else -> floatArrayOf(0f, 0f)
    }

    private const val HEADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform vec2 uGrid;
    """

    /** The texture as is. */
    val PLAIN = """
        $HEADER
        void main() {
            gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
        }
    """.trimIndent()

    /** SCREEN > OVERLAY's image, alpha and all (blended over the game: its window is see-through). */
    val OVERLAY = """
        $HEADER
        void main() {
            gl_FragColor = texture2D(uTex, vUv);
        }
    """.trimIndent()

    /**
     * SHADERS > GBA COLORS ([Prefs.gbaColors]): the colours as the GBA's own LCD showed
     * them - darker, less saturated, with the colour bleed the game's palettes were made for.
     * mGBA's `res/shaders/gba-color.shader` (Pokefan531 and hunterk, MPL-2.0 like the rest
     * of third_party/mgba) at its defaults (darken_screen 0.5, sat / contrast 1, lum 0.99),
     * with its identity saturation and contrast steps folded away.
     */
    val GBA_COLOR = """
        $HEADER
        const float darken = 0.5;
        const float targetGamma = 2.2;
        const float displayGamma = 2.5;
        const float lum = 0.99;
        const mat3 lcd = mat3(
            0.84, 0.09, 0.15,
            0.18, 0.67, 0.10,
            0.00, 0.26, 0.73);
        void main() {
            vec3 c = pow(texture2D(uTex, vUv).rgb, vec3(targetGamma + darken));
            c = lcd * clamp(c * lum, 0.0, 1.0);
            gl_FragColor = vec4(pow(c, vec3(1.0 / displayGamma + darken * 0.125)), 1.0);
        }
    """.trimIndent()

    /**
     * LCD: a dark grid line along the top and left third of every GBA pixel. mGBA's
     * `res/shaders/lcd.shader` (Copyright (C) 2017 Dominus Iniquitatis, MIT - see NOTICE),
     * its line brightness from GRID (`uGrid.x` darkening: SOFT 0.2 is about its 0.9 default doubled).
     */
    private val LCD = Effect(
        """
        $HEADER
        void main() {
            vec3 c = texture2D(uTex, vUv).rgb;
            vec2 sub = vUv * uTexSize * 3.0;
            if (int(mod(sub.x, 3.0)) == 0 || int(mod(sub.y, 3.0)) == 0) c *= 1.0 - uGrid.x;
            gl_FragColor = vec4(c, 1.0);
        }
        """.trimIndent(),
        prescale = true,
    )

    /**
     * SCANLINES: the top half of every GBA row darkened. mGBA's `res/shaders/scanlines.shader`
     * (Copyright (C) 2017 Dominus Iniquitatis, MIT - see NOTICE) at its 0.5 default.
     */
    private val SCANLINES = Effect(
        """
        $HEADER
        const float lineBrightness = 0.5;
        void main() {
            vec3 c = texture2D(uTex, vUv).rgb;
            if (int(mod(vUv.y * uTexSize.y * 2.0, 2.0)) == 0) c *= lineBrightness;
            gl_FragColor = vec4(c, 1.0);
        }
        """.trimIndent(),
        prescale = true,
    )

    /**
     * LCD PAPER: a reflective, unlit LCD in the style of RetroArch's simpletex_lcd (jdgleaver,
     * GPL-2.0-or-later; its grid curve from Greg Hogan's zfast_lcd - see NOTICE). Soft grid lines
     * from each view pixel's distance to its GBA pixel's centre, 48(x^4 - 8/3 x^6) - squared for
     * GRID's SOFT thin lines, the full curve at STRONG (`uGrid.y`) - over colours 15% desaturated, a
     * reflective screen's duller look (simpletex's DARKEN_COLOUR deepened them past plain LCD's, too
     * vibrant). Unlike simpletex, a line darkens its own pixel (`uGrid.x`) rather
     * than mixing towards a fixed colour: white lines (its default), then a fixed grey, both lifted
     * every darker colour and looked washed out on the Thor (Emerald's intro grass); then light pixels
     * take on an off-white paper by their brightness while dark ones stay solid ink. The paper is
     * noise in view pixels (faint blotches, grain, a few short fibres; the big blotches toned down
     * after they read as stains), not a bundled texture. Smooth, so
     * drawn at the view's own pixels like CRT; picked in the browser harness at 1620x1080.
     */
    private val LCD_PAPER = Effect(
        """
        $HEADER
        const float gridIntensity = 0.85;
        const float desaturate = 0.15;
        const vec3 luma709 = vec3(0.2126, 0.7152, 0.0722);
        float hash(vec2 p) {
            p = fract(p * vec2(0.1031, 0.1030));
            p += dot(p, p.yx + 33.33);
            return fract((p.x + p.y) * p.x);
        }
        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        vec3 paper(vec2 p) {
            float n = 0.2 * noise(p / 90.0) + 0.3 * noise(p / 22.0) + 0.3 * noise(p / 5.0) + 0.2 * hash(p);
            float fibre = max(smoothstep(0.7, 0.95, noise(vec2(p.x / 2.0, p.y / 14.0) + 17.0)),
                              smoothstep(0.7, 0.95, noise(vec2(p.x / 14.0, p.y / 2.0) + 51.0)));
            return vec3(0.98, 0.96, 0.90) * (0.88 + 0.12 * n - 0.025 * fibre);
        }
        void main() {
            vec2 pos = vUv * uTexSize;
            vec2 d = abs(fract(pos) - 0.5);
            float x2 = max(d.x, d.y);
            x2 *= x2;
            float w = 48.0 * (x2 * x2 - 8.0 / 3.0 * x2 * x2 * x2);
            vec3 c = texture2D(uTex, (floor(pos) + 0.5) / uTexSize).rgb;
            c = mix(c, vec3(dot(c, luma709)), desaturate);
            c *= 1.0 - uGrid.x * clamp(mix(w * w, w, uGrid.y) * gridIntensity, 0.0, 1.0);
            c = mix(c, paper(gl_FragCoord.xy) * c, dot(c, luma709));
            gl_FragColor = vec4(c, 1.0);
        }
        """.trimIndent(),
        prescale = false,
    )

    /**
     * CRT: each GBA row a horizontal beam with a soft gaussian profile that widens as it gets
     * brighter (so bright rows bloom into the gaps), neighbouring pixels blended a little along
     * the row, and an aperture-grille mask of R / G / B stripes one view pixel wide. Worked out
     * in linear light, then brightened back to about the plain picture's level. Drawn at the
     * view's own pixels: the beams are smooth, so the non-whole scale doesn't show, and the mask
     * needs real view pixels.
     */
    private val CRT = Effect(
        """
        $HEADER
        const float gamma = 2.2;
        const float maskDim = 0.6;
        const float boost = 1.5;
        vec3 fetch(vec2 px) {
            return pow(texture2D(uTex, (px + 0.5) / uTexSize).rgb, vec3(gamma));
        }
        vec3 beam(float dist, vec3 c) {
            vec3 w = mix(vec3(0.22), vec3(0.32), c);
            return exp(-dist * dist / (2.0 * w * w));
        }
        void main() {
            vec2 pos = vUv * uTexSize - 0.5;
            vec2 base = floor(pos);
            vec2 f = pos - base;
            float fx = smoothstep(0.2, 0.8, f.x);
            vec3 row0 = mix(fetch(base), fetch(base + vec2(1.0, 0.0)), fx);
            vec3 row1 = mix(fetch(base + vec2(0.0, 1.0)), fetch(base + vec2(1.0, 1.0)), fx);
            vec3 c = row0 * beam(f.y, row0) + row1 * beam(1.0 - f.y, row1);
            float stripe = mod(floor(gl_FragCoord.x), 3.0);
            vec3 mask = vec3(maskDim);
            if (stripe < 0.5) mask.r = 1.0;
            else if (stripe < 1.5) mask.g = 1.0;
            else mask.b = 1.0;
            c = clamp(c * mask * boost, 0.0, 1.0);
            gl_FragColor = vec4(pow(c, vec3(1.0 / gamma)), 1.0);
        }
        """.trimIndent(),
        prescale = false,
    )
}
