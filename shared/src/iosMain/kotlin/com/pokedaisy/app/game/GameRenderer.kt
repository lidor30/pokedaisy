@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Metal.*

/**
 * Draws the running game ([PkApiCore.video]) with Metal: its frame as a texture, shown as large as fits, centred, its
 * own shape kept (a GBA's 3:2, a Game Boy's 10:9). Pixels are scaled "sharp bilinear" (whole pixels, a one-screen-pixel
 * blend at their edges), so a game shown at 4.3x looks even, as the proof of concept and the 3DS port's SHARP do.
 *
 * Made with [create]; [upload] after each frame, then [encode] into a pass (GameView draws into the view's drawable).
 */
class GameRenderer private constructor(
    val device: MTLDeviceProtocol,
    private val pipeline: MTLRenderPipelineStateProtocol,
    private val sampler: MTLSamplerStateProtocol,
) {
    private var texture: MTLTextureProtocol? = null
    private var frameW = 0
    private var frameH = 0
    // rect (NDC x0, y0, x1, y1), then the texture's width / height and screen pixels per texel across / down.
    private val uniforms = FloatArray(8)

    /** The last frame's size in game pixels; 0 before one is uploaded. */
    val frameWidth: Int get() = frameW
    val frameHeight: Int get() = frameH

    /** Copies the game's last frame into the texture; false with no game running. */
    fun upload(): Boolean {
        val px = PkApiCore.video() ?: return false
        val w = PkApiCore.videoWidth
        val h = PkApiCore.videoHeight
        if (w <= 0 || h <= 0) return false
        var tex = texture
        if (tex == null || tex.width.toInt() != w || tex.height.toInt() != h) {
            val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
                // pk_api's R, G, B, unused bytes; the shader ignores the unused one.
                MTLPixelFormatRGBA8Unorm, w.toULong(), h.toULong(), mipmapped = false,
            )
            desc.usage = MTLTextureUsageShaderRead
            desc.storageMode = MTLStorageModeShared
            tex = device.newTextureWithDescriptor(desc) ?: return false
            texture = tex
        }
        val region = cValue<MTLRegion> {
            size.width = w.toULong()
            size.height = h.toULong()
            size.depth = 1u
        }
        tex.replaceRegion(region, mipmapLevel = 0u, withBytes = px, bytesPerRow = (w * 4).toULong())
        frameW = w
        frameH = h
        return true
    }

    /** Draws the last uploaded frame into [encoder]'s pass, a target of [targetW] x [targetH] pixels. */
    fun encode(encoder: MTLRenderCommandEncoderProtocol, targetW: Double, targetH: Double) {
        val tex = texture ?: return
        if (frameW <= 0 || targetW <= 0 || targetH <= 0) return
        val r = fitRect(frameW, frameH, targetW, targetH)
        uniforms[0] = (r[0] / targetW * 2 - 1).toFloat()
        uniforms[1] = (1 - r[1] / targetH * 2).toFloat()
        uniforms[2] = (r[2] / targetW * 2 - 1).toFloat()
        uniforms[3] = (1 - r[3] / targetH * 2).toFloat()
        uniforms[4] = frameW.toFloat()
        uniforms[5] = frameH.toFloat()
        uniforms[6] = ((r[2] - r[0]) / frameW).toFloat()
        uniforms[7] = ((r[3] - r[1]) / frameH).toFloat()
        encoder.setRenderPipelineState(pipeline)
        uniforms.usePinned {
            val bytes = it.addressOf(0)
            val length = (uniforms.size * 4).toULong()
            encoder.setVertexBytes(bytes, length, atIndex = 0u)
            encoder.setFragmentBytes(bytes, length, atIndex = 0u)
        }
        encoder.setFragmentTexture(tex, atIndex = 0u)
        encoder.setFragmentSamplerState(sampler, atIndex = 0u)
        encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, vertexStart = 0u, vertexCount = 4u)
    }

    companion object {
        /** Black round the game. */
        const val CLEAR_RED = 0.0
        const val CLEAR_GREEN = 0.0
        const val CLEAR_BLUE = 0.0

        private const val SHADER = """
#include <metal_stdlib>
using namespace metal;
struct Quad { float4 rect; float4 size; };
struct V { float4 pos [[position]]; float2 uv; };
vertex V game_vertex(uint vid [[vertex_id]], constant Quad& q [[buffer(0)]]) {
    float2 c = float2(vid & 1, vid >> 1);
    V out;
    out.pos = float4(mix(q.rect.x, q.rect.z, c.x), mix(q.rect.y, q.rect.w, c.y), 0, 1);
    out.uv = c;
    return out;
}
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    float2 texSize = q.size.xy;
    float2 scale = max(q.size.zw, float2(1.0));
    float2 texel = in.uv * texSize;
    float2 base = floor(texel);
    float2 d = fract(texel) - 0.5;
    float2 range = 0.5 - 0.5 / scale;
    float2 f = (d - clamp(d, -range, range)) * scale + 0.5;
    return float4(tex.sample(s, (base + f) / texSize).rgb, 1.0);
}
"""

        /** The renderer for drawing into targets of [pixelFormat]; null where Metal isn't there or won't build it. */
        fun create(pixelFormat: MTLPixelFormat = MTLPixelFormatBGRA8Unorm): GameRenderer? {
            val device = MTLCreateSystemDefaultDevice() ?: return null
            return memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val library = device.newLibraryWithSource(SHADER, options = null, error = error.ptr)
                if (library == null) {
                    NSLog("PokeDaisy: the game's shader didn't build: %@", error.value?.localizedDescription)
                    return@memScoped null
                }
                val desc = MTLRenderPipelineDescriptor()
                desc.vertexFunction = library.newFunctionWithName("game_vertex")
                desc.fragmentFunction = library.newFunctionWithName("game_fragment")
                desc.colorAttachments.objectAtIndexedSubscript(0u).pixelFormat = pixelFormat
                val pipeline = device.newRenderPipelineStateWithDescriptor(desc, error = error.ptr)
                if (pipeline == null) {
                    NSLog("PokeDaisy: the game's pipeline didn't build: %@", error.value?.localizedDescription)
                    return@memScoped null
                }
                val sd = MTLSamplerDescriptor()
                sd.minFilter = MTLSamplerMinMagFilterLinear
                sd.magFilter = MTLSamplerMinMagFilterLinear
                sd.sAddressMode = MTLSamplerAddressModeClampToEdge
                sd.tAddressMode = MTLSamplerAddressModeClampToEdge
                val sampler = device.newSamplerStateWithDescriptor(sd) ?: return@memScoped null
                GameRenderer(device, pipeline, sampler)
            }
        }

        /**
         * Where a [frameW] x [frameH] game goes in a [targetW] x [targetH] target: as large as fits with its shape kept,
         * centred, its edges on whole pixels. Returns x0, y0, x1, y1 in the target's pixels, y down.
         */
        fun fitRect(frameW: Int, frameH: Int, targetW: Double, targetH: Double): DoubleArray {
            val scale = minOf(targetW / frameW, targetH / frameH)
            val w = kotlin.math.floor(frameW * scale)
            val h = kotlin.math.floor(frameH * scale)
            val x = kotlin.math.floor((targetW - w) / 2)
            val y = kotlin.math.floor((targetH - h) / 2)
            return doubleArrayOf(x, y, x + w, y + h)
        }
    }
}
