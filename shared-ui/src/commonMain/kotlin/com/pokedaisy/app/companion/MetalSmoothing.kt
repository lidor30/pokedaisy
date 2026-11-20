package com.pokedaisy.app.companion

import com.pokedaisy.app.game.PictureEffects

/** Metal transport for the existing xBRZ asset. The edge/blend algorithm stays in its original GLSL. */
object MetalSmoothing {
    fun program(mode: Smoothing, source: String, pass: Int = 0): PictureEffects.Program = when (mode) {
        Smoothing.XBRZ -> xbrz(source)
        Smoothing.XBR -> xbr(source)
        Smoothing.SCALEFX -> scalefx(source, pass)
        Smoothing.OFF -> error("OFF has no smoothing program")
    }

    fun xbrz(source: String): PictureEffects.Program {
        val at = source.indexOf("#define BLEND_NONE")
        require(at >= 0) { "Unknown xBRZ fragment" }
        val coordinates = Regex("TEX0\\.xy\\s*=\\s*(TexCoord\\.xy[^;]*);").find(source)?.groupValues?.get(1)
            ?: error("Unknown xBRZ vertex coordinates")
        var body = source.substring(at).substringBeforeLast("#endif")
            .replace("void main()", "float4 shade()")
            .replace("FragColor =", "return")
        // GLSL's vector == / != return one boolean; Metal's return a vector of booleans.
        body = body.replace("#define eq(a,b)  (a == b)", "#define eq(a,b)  all(a == b)")
            .replace("#define neq(a,b) (a != b)", "#define neq(a,b) any(a != b)")
        require("#define eq(a,b)  all(a == b)" in body && "#define neq(a,b) any(a != b)" in body)
        val shader = """
${source.substringBefore("#if defined(VERTEX)")}
#define vec2 float2
#define vec3 float3
#define vec4 float4
#define ivec4 int4
#define COMPAT_TEXTURE sample
#define Source Texture
#define SourceSize vec4(TextureSize, 1.0 / TextureSize)
#define vTexCoord TEX0.xy
struct SmoothEffect {
    texture2d<float> Texture;
    sampler s;
    float2 OutputSize;
    float2 TextureSize;
    float4 TEX0;
    float4 sample(texture2d<float> t, float2 uv) {
        return t.sample(s, clamp(uv * TextureSize, float2(0.5), TextureSize - 0.5) / float2(t.get_width(), t.get_height()));
    }
    $body
};
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    SmoothEffect effect{tex, s, q.output.xy, q.textureSize.zw, float4(${coordinates.replace("TexCoord.xy", "in.uv")}, 0, 0)};
    return effect.shade();
}
""".trimIndent()
        return PictureEffects.Program("xBRZ", shader)
    }

    /** Original xBR vertex offsets are affine in UV, so the fragment can derive the same interpolated taps. */
    fun xbr(source: String): PictureEffects.Program {
        val stage = source.substringAfter("#elif defined(FRAGMENT)", "")
        require(stage.isNotEmpty()) { "Unknown xBR fragment" }
        val fields = Regex("\\bIN\\s+(vec[234])\\s+(\\w+)\\s*;").findAll(stage).toList()
        require(fields.size == 8) { "Unknown xBR varyings" }
        val vertex = source.substringBefore("#elif defined(FRAGMENT)").substringAfter("void main()")
            .substringAfter('{').substringBeforeLast('}')
            .replace(Regex("^\\s*(gl_Position|color)\\s*=.*;", RegexOption.MULTILINE), "")
        val at = stage.indexOf("#ifdef PARAMETER_UNIFORM")
        require(at >= 0) { "Unknown xBR defaults" }
        val body = stage.substring(at).substringBeforeLast("#endif").replace("void main()", "void shade()")
        val prefix = source.substringBefore("#if defined(VERTEX)")
            .replace(Regex("^\\s*#version[^\\n]*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*#pragma parameter[^\\n]*", RegexOption.MULTILINE), "")
        val shader = """
$prefix
#define vec2 float2
#define vec3 float3
#define vec4 float4
#define mat4x3 float4x3
#define notEqual(a,b) ((a)!=(b))
#define tex2D sample
struct SmoothEffect {
    texture2d<float> decal;
    sampler s;
    float2 OutputSize;
    float2 TextureSize;
    float4 FragColor;
    ${fields.joinToString("\n") { "${it.groupValues[1]} ${it.groupValues[2]};" }}
    float4 sample(texture2d<float> t, float2 uv) {
        return t.sample(s, clamp(uv * TextureSize, float2(0.5), TextureSize - 0.5) / float2(t.get_width(), t.get_height()));
    }
    void coordinates(vec2 TexCoord) {
        $vertex
    }
    $body
};
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    SmoothEffect effect{tex, s, q.output.xy, q.textureSize.zw};
    effect.coordinates(in.uv);
    effect.shade();
    return float4(effect.FragColor.rgb, 1.0);
}
""".trimIndent()
        return PictureEffects.Program("xBR", shader)
    }


    /** The owned ScaleFX preset has at most one history sampler in a pass. */
    fun history(source: String): Int? {
        val inputs = Regex("uniform\\s+sampler2D\\s+PassPrev(\\d+)Texture\\s*;").findAll(source)
            .map { it.groupValues[1].toInt() }.distinct().toList()
        require(inputs.size <= 1) { "Unexpected smoothing history inputs" }
        return inputs.singleOrNull()
    }

    /** Same five GLSL algorithms, including their four-channel metrics; only the transport dialect changes. */
    fun scalefx(source: String, pass: Int): PictureEffects.Program {
        require(pass in 0..4)
        val stage = source.substringAfter("#elif defined(FRAGMENT)", "")
        val fields = Regex("COMPAT_VARYING\\s+(vec[234])\\s+(\\w+)\\s*;").findAll(stage).toList()
        require(fields.size == intArrayOf(3, 4, 4, 5, 5)[pass]) { "Unknown ScaleFX varyings" }
        val at = stage.indexOf("#define Source Texture")
        require(at >= 0) { "Unknown ScaleFX fragment" }
        val body = stage.substring(at).substringBeforeLast("#endif").replace("void main()", "void shade()")
            .replace("__VERSION__", "PD_GLSL_VERSION").replace(Regex("\\bor\\b"), "pd_or")
        val vertex = source.substringBefore("#elif defined(FRAGMENT)").substringAfter("void main()")
            .substringAfter('{').substringBeforeLast('}')
            .replace(Regex("^\\s*(gl_Position|COL0)\\s*=.*;", RegexOption.MULTILINE), "")
        val prefix = source.substringBefore("#if defined(VERTEX)")
            .replace(Regex("^\\s*#version[^\\n]*", RegexOption.MULTILINE), "")
        val shader = """
$prefix
#define PD_GLSL_VERSION 130
#define COMPAT_TEXTURE sample
#define vec2 float2
#define vec3 float3
#define vec4 float4
#define bvec2 bool2
#define bvec3 bool3
#define bvec4 bool4
#define ivec2 int2
#define equal(a,b) ((a)==(b))
#define notEqual(a,b) ((a)!=(b))
#define lessThan(a,b) ((a)<(b))
#define greaterThan(a,b) ((a)>(b))
#define lessThanEqual(a,b) ((a)<=(b))
#define greaterThanEqual(a,b) ((a)>=(b))
#define mod(a,b) ((a)-(b)*floor((a)/(b)))
#define PassPrev2Texture History
#define PassPrev5Texture History
#define PassPrev2TextureSize HistorySize
#define PassPrev5TextureSize HistorySize
struct SmoothEffect {
    texture2d<float> Texture;
    texture2d<float> History;
    sampler s;
    float2 OutputSize;
    float2 TextureSize;
    float2 HistorySize;
    float4 FragColor;
    ${fields.joinToString("\n") { "${it.groupValues[1]} ${it.groupValues[2]};" }}
    // Both sampled inputs have original-frame dimensions in the five owned ScaleFX passes.
    float4 sample(texture2d<float> t, float2 uv) {
        return t.sample(s, clamp(uv * TextureSize, float2(0.5), TextureSize - 0.5) / float2(t.get_width(), t.get_height()));
    }
    float4 textureOffset(texture2d<float> t, float2 uv, int2 offset) { return sample(t, uv + float2(offset) / TextureSize); }
    float4 mix(float4 a, float4 b, bool4 t) { return select(a, b, t); }
    float4 mix(float4 a, float4 b, float4 t) { return metal::mix(a, b, t); }
    float3 mix(float3 a, float3 b, float t) { return metal::mix(a, b, t); }
    $body
    void coordinates(vec4 TexCoord) {
        $vertex
    }
};
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], texture2d<float> history [[texture(1)]], sampler s [[sampler(0)]]) {
    SmoothEffect effect{tex, history, s, q.output.xy, q.textureSize.zw, q.history.xy};
    effect.coordinates(float4(in.uv, 0, 1));
    effect.shade();
    return effect.FragColor;
}
""".trimIndent()
        return PictureEffects.Program("ScaleFX pass $pass", shader)
    }

}
