package com.pokedaisy.app.companion

import com.pokedaisy.app.ScreenShaders
import com.pokedaisy.app.game.PictureEffects

/** Bounded GLSL-to-Metal dialect bridge for the app's six owned screen shaders, retaining their actual bodies. */
object MetalScreenEffects {
    fun picture(filter: ScreenFilter, grid: GridStrength, colors: Boolean, unlit: Boolean): PictureEffects {
        val effect = ScreenShaders.effectFor(filter)
        val strength = ScreenShaders.gridFor(filter, grid)
        return PictureEffects(
            colors = if (colors) program("GBA COLORS", ScreenShaders.GBA_COLOR) else null,
            unlit = if (unlit) program("UNLIT", ScreenShaders.UNLIT) else null,
            filter = effect?.let { program(filter.name, it.frag) },
            prescale = effect?.prescale == true, gridX = strength[0], gridY = strength[1],
        )
    }

    private fun program(name: String, fragment: String) = PictureEffects.Program(name, source(fragment))

    /** Source uses GameRenderer's Quad/V/vertex interface. Not a compiler for arbitrary imported GLSL. */
    fun source(fragment: String): String {
        val at = fragment.indexOf("vec2 texelCentre(vec2 uv)")
        require(at >= 0) { "Unknown screen shader header" }
        var body = fragment.substring(at)
            .replace("void main()", "float4 shade()")
            .replace("gl_FragColor =", "return")
        // Metal constructs matrices by columns; GLSL accepts the same columns as nine scalars.
        body = body.replace(Regex("mat3\\(([^()]*)\\)")) { match ->
            val values = match.groupValues[1].split(',')
            require(values.size == 9)
            "mat3(" + values.chunked(3).joinToString { "vec3(${it.joinToString()})" } + ")"
        }
        return """
#define vec2 float2
#define vec3 float3
#define vec4 float4
#define mat3 float3x3
struct ScreenEffect {
    texture2d<float> uTex;
    sampler s;
    float2 vUv;
    float2 uTexSize;
    float2 uGrid;
    float uSmoothed;
    float4 gl_FragCoord;
    float2 inputSize;
    float4 texture2D(texture2d<float> t, float2 uv) { return t.sample(s, clamp(uv * inputSize, float2(0.5), inputSize - 0.5) / float2(t.get_width(), t.get_height())); }
    float mod(float x, float y) { return x - y * floor(x / y); }
    $body
};
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    ScreenEffect effect{tex, s, in.uv, q.size.xy, q.grid.xy, q.grid.z, in.pos, q.textureSize.zw};
    return effect.shade();
}
""".trimIndent()
    }
}
