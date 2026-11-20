@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.WideCamera
import com.pokedaisy.app.core.PkApiCore
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Metal.*

/** Native GPU transport and effect passes. Shared policy supplies the shader bodies and pass sizes. */
class GameRenderer private constructor(
    val device: MTLDeviceProtocol,
    private val pipeline: MTLRenderPipelineStateProtocol,
    private val linearPipeline: MTLRenderPipelineStateProtocol,
    private val sampler: MTLSamplerStateProtocol,
    private val nearestSampler: MTLSamplerStateProtocol,
    private val outputFormat: MTLPixelFormat,
) {
    private var texture: MTLTextureProtocol? = null
    private var ordinaryTexture: MTLTextureProtocol? = null
    private var expandedTexture: MTLTextureProtocol? = null
    private var window: WideCamera.Window? = null
    var stretch = false
    private var frameW = 0
    private var frameH = 0
    private val uniforms = FloatArray(24)
    val frameWidth: Int get() = frameW
    val frameHeight: Int get() = frameH

    private data class Surface(val texture: MTLTextureProtocol, val w: Int, val h: Int)
    private data class Effects(val settings: PictureEffects = PictureEffects(),
        val colors: MTLRenderPipelineStateProtocol? = null, val unlit: MTLRenderPipelineStateProtocol? = null,
        val filter: MTLRenderPipelineStateProtocol? = null, val smoothing: List<MTLRenderPipelineStateProtocol?> = emptyList())
    private var effects = Effects()
    private val compiling = Mutex()
    private val programs = mutableMapOf<Pair<PictureEffects.Program, MTLPixelFormat>, MTLRenderPipelineStateProtocol>()
    private var prepared: Surface? = null
    private var finalPipeline = pipeline
    private var finalSampler = sampler
    private var readsPicture = false

    /** Call on the draw thread. Compilation runs off it, and cancellation never publishes an old request. */
    suspend fun configureEffects(settings: PictureEffects): Boolean {
        val next = withContext(Dispatchers.Default) {
            val compilationContext = coroutineContext
            compiling.withLock {
                fun program(p: PictureEffects.Program?, format: MTLPixelFormat): MTLRenderPipelineStateProtocol? = p?.let {
                    compilationContext.ensureActive()
                    programs[it to format] ?: makePipeline(device, HEADER + "\n" + it.fragment, format)?.also { built -> programs[it to format] = built }
                }
                Effects(settings, program(settings.colors, MTLPixelFormatRGBA8Unorm), program(settings.unlit, MTLPixelFormatRGBA8Unorm),
                    program(settings.filter, if (settings.prescale) MTLPixelFormatRGBA8Unorm else outputFormat),
                    settings.smoothing.map { program(it.program, if (it.halfFloat) MTLPixelFormatRGBA16Float else MTLPixelFormatRGBA8Unorm) })
            }
        }
        effects = next
        return (settings.colors == null || next.colors != null) && (settings.unlit == null || next.unlit != null) &&
            (settings.filter == null || next.filter != null) && next.smoothing.all { it != null }
    }

    private inner class Target {
        var texture: MTLTextureProtocol? = null
        fun ensure(w: Int, h: Int, halfFloat: Boolean = false): MTLTextureProtocol? {
            val format = if (halfFloat) MTLPixelFormatRGBA16Float else MTLPixelFormatRGBA8Unorm
            val previous = texture?.takeIf { it.pixelFormat == format }
            previous?.takeIf { it.width.toInt() >= w && it.height.toInt() >= h }?.let { return it }
            // Grow in bounded steps, so a smooth camera transition doesn't allocate a texture every frame.
            fun capacity(n: Int) = ((n + 255) / 256) * 256
            val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(format,
                capacity(maxOf(w, previous?.width?.toInt() ?: 0)).toULong(), capacity(maxOf(h, previous?.height?.toInt() ?: 0)).toULong(), false)
            desc.usage = MTLTextureUsageRenderTarget or MTLTextureUsageShaderRead
            desc.storageMode = MTLStorageModePrivate
            return device.newTextureWithDescriptor(desc)?.also { texture = it }
        }
    }
    private val colorsTarget = Target()
    private val smoothingTargets = mutableListOf<Target>()
    private val unlitTarget = Target()
    private val filterTarget = Target()

    fun upload(expandedWindow: WideCamera.Window? = null): Boolean {
        val expanded = expandedWindow?.takeIf { PkApiCore.wideWidth == it.w && PkApiCore.wideHeight == it.h }
        val px = (if (expanded != null) PkApiCore.wideVideo() else PkApiCore.video()) ?: return false
        return uploadFrame(px, expanded?.w ?: PkApiCore.videoWidth, expanded?.h ?: PkApiCore.videoHeight, expanded)
    }

    /** Copies caller-owned pixels once; effects stay entirely on the GPU. */
    fun uploadFrame(px: COpaquePointer, w: Int, h: Int, expanded: WideCamera.Window? = null): Boolean {
        if (w <= 0 || h <= 0) return false
        val capacityW = if (expanded != null) WideCamera.MAX_W else w
        val capacityH = if (expanded != null) WideCamera.MAX_H else h
        var tex = if (expanded != null) expandedTexture else ordinaryTexture
        if (tex == null || tex.width.toInt() != capacityW || tex.height.toInt() != capacityH) {
            val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatRGBA8Unorm,capacityW.toULong(),capacityH.toULong(),false)
            desc.usage = MTLTextureUsageShaderRead
            desc.storageMode = MTLStorageModeShared
            tex = device.newTextureWithDescriptor(desc) ?: return false
            if (expanded != null) expandedTexture = tex else ordinaryTexture = tex
        }
        val region = cValue<MTLRegion> { size.width=w.toULong();size.height=h.toULong();size.depth=1u }
        tex.replaceRegion(region,0u,px,(w*4).toULong())
        texture=tex;window=expanded;frameW=w;frameH=h;prepared=null
        return true
    }

    private fun rect(targetW: Double, targetH: Double): DoubleArray = window?.let {
        doubleArrayOf(it.offX.toDouble(),it.offY.toDouble(),(it.offX+frameW*it.scale).toDouble(),(it.offY+frameH*it.scale).toDouble())
    } ?: if (stretch) doubleArrayOf(0.0,0.0,targetW,targetH) else fitRect(frameW,frameH,targetW,targetH)

    /** Height of one emulated pixel in drawable pixels, including camera zoom and stretch. */
    fun gamePixelHeight(targetW: Double, targetH: Double): Float {
        if (frameH <= 0) return 0f
        val r = rect(targetW, targetH)
        return ((r[3] - r[1]) / frameH).toFloat()
    }

    /** Encode intermediate passes before the drawable's encoder is opened. */
    fun prepare(buffer: MTLCommandBufferProtocol, targetW: Double, targetH: Double) {
        val original = texture ?: return
        if (frameW <= 0 || frameH <= 0 || targetW <= 0 || targetH <= 0) return
        var input = Surface(original,frameW,frameH)
        val e = effects
        val r = rect(targetW,targetH)
        val sizes = picturePassSizes(frameW,frameH,(r[2]-r[0]).toFloat(),(r[3]-r[1]).toFloat(),e.filter!=null && e.settings.prescale)
        e.colors?.let { pass(buffer,it,input,colorsTarget,frameW,frameH,false,false)?.let { out -> input=out } }
        var smooth = false
        if (e.smoothing.isNotEmpty() && e.smoothing.all { it != null }) {
            var result: Surface? = input
            val previous = mutableListOf<Surface>()
            for ((i, program) in e.smoothing.withIndex()) {
                val source = result ?: break
                while (smoothingTargets.size <= i) smoothingTargets += Target()
                val spec = e.settings.smoothing[i]
                val history = spec.history?.let { back -> if (i - back == -1) input else previous.getOrNull(i - back) }
                if (spec.history != null && history == null) { result = null; break }
                val (w, h) = picturePassSize(spec.size, source.w, source.h, sizes)
                result = pass(buffer, requireNotNull(program), source, smoothingTargets[i], w, h, false, false,
                    halfFloat = spec.halfFloat, history = history ?: source)
                result?.let { previous += it }
            }
            // A partial chain may contain metrics rather than colors: never publish it after a failed pass.
            result?.let { input=it; smooth=true }
        }
        e.unlit?.let { pass(buffer,it,input,unlitTarget,sizes.unlitW,sizes.unlitH,true,smooth)?.let { out -> input=out;smooth=true } }
        if (e.filter != null && e.settings.prescale) {
            pass(buffer,e.filter,input,filterTarget,sizes.integerW,sizes.integerH,smooth,smooth)?.let { out -> input=out;smooth=true }
        }
        prepared=input
        readsPicture=smooth
        finalPipeline = if (e.filter!=null && !e.settings.prescale) e.filter else if (smooth) linearPipeline else pipeline
        finalSampler = if (e.filter!=null && !e.settings.prescale && !smooth) nearestSampler else sampler
    }

    private fun pass(buffer: MTLCommandBufferProtocol, program: MTLRenderPipelineStateProtocol, input: Surface,
                     target: Target, w: Int, h: Int, smooth: Boolean, smoothed: Boolean,
                     halfFloat: Boolean = false, history: Surface? = null): Surface? {
        val out=target.ensure(w,h,halfFloat) ?: return null
        val desc=MTLRenderPassDescriptor.renderPassDescriptor()
        val color=desc.colorAttachments.objectAtIndexedSubscript(0u)
        color.texture=out;color.loadAction=MTLLoadActionDontCare;color.storeAction=MTLStoreActionStore
        val encoder=buffer.renderCommandEncoderWithDescriptor(desc) ?: return null
        encoder.setViewport(cValue<MTLViewport> { width=w.toDouble();height=h.toDouble();znear=0.0;zfar=1.0 })
        bind(encoder,program,input,doubleArrayOf(-1.0,1.0,1.0,-1.0),w.toDouble()/frameW,h.toDouble()/frameH,
            if(smooth) sampler else nearestSampler,smoothed,history)
        encoder.endEncoding()
        return Surface(out,w,h)
    }

    fun encode(encoder: MTLRenderCommandEncoderProtocol, targetW: Double, targetH: Double) {
        val tex=texture ?: return
        if(frameW<=0 || targetW<=0 || targetH<=0) return
        val input=prepared ?: Surface(tex,frameW,frameH)
        val r=rect(targetW,targetH)
        val ndc=doubleArrayOf(r[0]/targetW*2-1,1-r[1]/targetH*2,r[2]/targetW*2-1,1-r[3]/targetH*2)
        bind(encoder,if(prepared==null) pipeline else finalPipeline,input,ndc,(r[2]-r[0])/frameW,(r[3]-r[1])/frameH,
            if(prepared==null) sampler else finalSampler,readsPicture)
    }

    private fun bind(encoder: MTLRenderCommandEncoderProtocol, program: MTLRenderPipelineStateProtocol, input: Surface,
                     ndc: DoubleArray, scaleX: Double, scaleY: Double, sampling: MTLSamplerStateProtocol, smoothed: Boolean,
                     history: Surface? = null) {
        for(i in 0..3) uniforms[i]=ndc[i].toFloat()
        uniforms[4]=frameW.toFloat();uniforms[5]=frameH.toFloat();uniforms[6]=scaleX.toFloat();uniforms[7]=scaleY.toFloat()
        uniforms[8]=input.texture.width.toFloat();uniforms[9]=input.texture.height.toFloat()
        uniforms[10]=input.w.toFloat();uniforms[11]=input.h.toFloat()
        uniforms[12]=effects.settings.gridX;uniforms[13]=effects.settings.gridY;uniforms[14]=if(smoothed)1f else 0f;uniforms[15]=0f
        uniforms[16]=(scaleX*frameW).toFloat();uniforms[17]=(scaleY*frameH).toFloat();uniforms[18]=0f;uniforms[19]=0f
        uniforms[20]=history?.w?.toFloat() ?: 0f;uniforms[21]=history?.h?.toFloat() ?: 0f;uniforms[22]=0f;uniforms[23]=0f
        encoder.setRenderPipelineState(program)
        uniforms.usePinned { val p=it.addressOf(0);val n=(uniforms.size*4).toULong();encoder.setVertexBytes(p,n,0u);encoder.setFragmentBytes(p,n,0u) }
        encoder.setFragmentTexture(input.texture,0u);encoder.setFragmentSamplerState(sampling,0u)
        history?.let { encoder.setFragmentTexture(it.texture,1u) }
        encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip,0u,4u)
    }

    companion object {
        const val CLEAR_RED=0.0
        const val CLEAR_GREEN=0.0
        const val CLEAR_BLUE=0.0
        private const val HEADER="""
#include <metal_stdlib>
using namespace metal;
struct Quad { float4 rect; float4 size; float4 textureSize; float4 grid; float4 output; float4 history; };
struct V { float4 pos [[position]]; float2 uv; };
vertex V game_vertex(uint vid [[vertex_id]], constant Quad& q [[buffer(0)]]) {
    float2 c = float2(vid & 1, vid >> 1);
    V out;
    out.pos = float4(mix(q.rect.x, q.rect.z, c.x), mix(q.rect.y, q.rect.w, c.y), 0, 1);
    out.uv = c;
    return out;
}

"""
        private const val SHARP="""
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    float2 texSize = q.size.xy;
    float2 scale = max(q.size.zw, float2(1.0));
    float2 texel = in.uv * texSize;
    float2 base = floor(texel);
    float2 d = fract(texel) - 0.5;
    float2 range = 0.5 - 0.5 / scale;
    float2 f = (d - clamp(d, -range, range)) * scale + 0.5;
    float2 samplePx = clamp(base + f, float2(0.5), texSize - 0.5);
    return float4(tex.sample(s, samplePx / q.textureSize.xy).rgb, 1.0);
}
"""
        private const val LINEAR="""
fragment float4 game_fragment(V in [[stage_in]], constant Quad& q [[buffer(0)]],
                              texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
    return float4(tex.sample(s,clamp(in.uv*q.textureSize.zw,float2(0.5),q.textureSize.zw-0.5)/q.textureSize.xy).rgb,1.0);
}
"""
        private fun makePipeline(device: MTLDeviceProtocol, source: String, format: MTLPixelFormat): MTLRenderPipelineStateProtocol? = memScoped {
            val error=alloc<ObjCObjectVar<NSError?>>()
            val library=device.newLibraryWithSource(source,null,error.ptr)
            if(library==null) { NSLog("PokeDaisy: screen shader failed: %@",error.value?.localizedDescription);return@memScoped null }
            val desc=MTLRenderPipelineDescriptor()
            desc.vertexFunction=library.newFunctionWithName("game_vertex");desc.fragmentFunction=library.newFunctionWithName("game_fragment")
            desc.colorAttachments.objectAtIndexedSubscript(0u).pixelFormat=format
            device.newRenderPipelineStateWithDescriptor(desc,error.ptr).also { if(it==null) NSLog("PokeDaisy: screen pipeline failed: %@",error.value?.localizedDescription) }
        }
        fun create(pixelFormat: MTLPixelFormat = MTLPixelFormatBGRA8Unorm): GameRenderer? {
            val device=MTLCreateSystemDefaultDevice() ?: return null
            val sharp=makePipeline(device,HEADER+SHARP,pixelFormat) ?: return null
            val plain=makePipeline(device,HEADER+LINEAR,pixelFormat) ?: return null
            fun sampling(filter: MTLSamplerMinMagFilter): MTLSamplerStateProtocol? {
                val desc=MTLSamplerDescriptor();desc.minFilter=filter;desc.magFilter=filter
                desc.sAddressMode=MTLSamplerAddressModeClampToEdge;desc.tAddressMode=MTLSamplerAddressModeClampToEdge
                return device.newSamplerStateWithDescriptor(desc)
            }
            return GameRenderer(device,sharp,plain,sampling(MTLSamplerMinMagFilterLinear) ?: return null,
                sampling(MTLSamplerMinMagFilterNearest) ?: return null,pixelFormat)
        }
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
