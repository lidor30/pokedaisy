@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import com.pokedaisy.app.WideCamera
import com.pokedaisy.app.companion.*
import com.pokedaisy.app.game.GameRenderer
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.Metal.*
import kotlin.math.abs
import kotlin.test.*

class ScreenEffectsTest {
    private fun renderer(): GameRenderer? = GameRenderer.create()?.apply { stretch = true }.also {
        if (it == null) println("ScreenEffectsTest: no Metal device; GPU checks not run")
    }

    private fun solid(w: Int, h: Int) = ByteArray(w*h*4) { i -> when(i%4) { 0 -> 100;1 -> 140;2 -> 200;else -> 0 }.toByte() }
    private fun upload(r: GameRenderer, bytes: ByteArray, w: Int, h: Int, expanded: Boolean = false) {
        bytes.usePinned { assertTrue(r.uploadFrame(it.addressOf(0),w,h,
            if(expanded) WideCamera.Window(0,0,w,h,0f,0f,16f) else null)) }
    }

    @Test fun everySharedFilterCompilesAndDrawsWithColorsAndUnlit() = runBlocking {
        val r=renderer() ?: return@runBlocking
        upload(r,solid(8,8),8,8)
        val pictures=ArrayList<List<Byte>>()
        for(filter in ScreenFilter.entries) {
            assertTrue(r.configureEffects(MetalScreenEffects.picture(filter,GridStrength.MEDIUM,true,true)),filter.name)
            val pixels=renderGamePixels(r,64,32)
            assertTrue(pixels.indices.filter { it%4!=3 }.any { pixels[it]!=0.toByte() },filter.name)
            assertTrue(pixels.indices.filter { it%4==3 }.all { pixels[it]==(-1).toByte() },filter.name)
            pictures+=pixels.toList()
        }
        assertEquals(ScreenFilter.entries.size,pictures.distinct().size,"Every filter must have its own visible result")
        println("ScreenEffectsTest: all shared filter/color/unlit programs rendered with Metal")
    }

    @Test fun scanlinesKeepHalfOfEachGameRowDark() = runBlocking {
        val r=renderer() ?: return@runBlocking
        upload(r,solid(2,2),2,2)
        assertTrue(r.configureEffects(MetalScreenEffects.picture(ScreenFilter.SCANLINES,GridStrength.MEDIUM,false,false)))
        val pixels=renderGamePixels(r,64,32)
        for(y in 0 until 32) {
            val dim=y%16<8
            val expected=if(dim) listOf(100,70,50) else listOf(200,140,100)
            for(c in 0..2) assertTrue(abs((pixels[(y*64+32)*4+c].toInt() and 255)-expected[c])<=1,"row $y channel $c")
        }
    }

    @Test fun colorCorrectionRunsBeforeScalingAndRetainsItsGoldenSolid() = runBlocking {
        val r=renderer() ?: return@runBlocking
        upload(r,solid(2,2),2,2)
        assertTrue(r.configureEffects(MetalScreenEffects.picture(ScreenFilter.NONE,GridStrength.MEDIUM,true,false)))
        val pixels=renderGamePixels(r,64,32)
        // The existing GBA LCD shader's solid RGB(100,140,200), quantized to RGB(89,140,168).
        val expected=listOf(168,140,89,255)
        for(i in pixels.indices) assertTrue(abs((pixels[i].toInt() and 255)-expected[i%4])<=1,"byte $i")
    }

    @Test fun expandedCapacityNeverLeaksIntoCrtSamplingAfterResize() = runBlocking {
        val reused=renderer() ?: return@runBlocking
        val effects=MetalScreenEffects.picture(ScreenFilter.CRT,GridStrength.MEDIUM,false,false)
        assertTrue(reused.configureEffects(effects))
        for(w in listOf(2,3,2)) {
            val fresh=assertNotNull(renderer())
            assertTrue(fresh.configureEffects(effects))
            val bytes=ByteArray(w*2*4) { i -> if(i%4==3) 0 else ((i/4+1)*(i%4+1)*17).toByte() }
            upload(reused,bytes,w,2,true);upload(fresh,bytes,w,2)
            assertContentEquals(renderGamePixels(fresh,w*16,32),renderGamePixels(reused,w*16,32),"logical width $w")
        }
    }

}

internal fun renderGamePixels(r: GameRenderer,w: Int,h: Int): ByteArray {
    val device=r.device
    val desc=MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatBGRA8Unorm,w.toULong(),h.toULong(),false)
    desc.usage=MTLTextureUsageRenderTarget;desc.storageMode=MTLStorageModePrivate
    val target=assertNotNull(device.newTextureWithDescriptor(desc))
    val stride=((w*4+255)/256)*256
    val out=assertNotNull(device.newBufferWithLength((stride*h).toULong(),MTLResourceStorageModeShared))
    val pass=MTLRenderPassDescriptor.renderPassDescriptor()
    val color=assertNotNull(pass.colorAttachments.objectAtIndexedSubscript(0u))
    color.texture=target;color.loadAction=MTLLoadActionClear;color.storeAction=MTLStoreActionStore
    color.clearColor=cValue<MTLClearColor> { alpha=1.0 }
    val buffer=assertNotNull(assertNotNull(device.newCommandQueue()).commandBuffer())
    r.prepare(buffer,w.toDouble(),h.toDouble())
    val encoder=assertNotNull(buffer.renderCommandEncoderWithDescriptor(pass))
    r.encode(encoder,w.toDouble(),h.toDouble());encoder.endEncoding()
    val blit=assertNotNull(buffer.blitCommandEncoder())
    blit.copyFromTexture(target,0u,0u,cValue<MTLOrigin> {},cValue<MTLSize> { width=w.toULong();height=h.toULong();depth=1u },
        out,0u,stride.toULong(),(stride*h).toULong())
    blit.endEncoding();buffer.commit();buffer.waitUntilCompleted()
    assertEquals(MTLCommandBufferStatusCompleted,buffer.status,buffer.error?.localizedDescription)
    val p=assertNotNull(out.contents()).reinterpret<ByteVar>()
    return ByteArray(w*h*4) { i -> p[(i/(w*4))*stride+i%(w*4)] }
}
