@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Metal.*
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [GameRenderer] in the iPad simulator: where the game goes, and the diagnostic GBA ROM's frame drawn with Metal at 2x
 * into a texture, every game pixel landing whole. Without a Metal device (some CI machines) the drawing is skipped.
 */
class GameRendererTest {
    @AfterTest
    fun close() = PkApiCore.stop()

    @Test
    fun fitsTheGameKeepingItsShape() {
        // A GBA on an iPad mini held sideways (2266 x 1488 pixels): as tall as the screen, centred across.
        assertContentEquals(doubleArrayOf(17.0, 0.0, 2249.0, 1488.0), GameRenderer.fitRect(240, 160, 2266.0, 1488.0))
        // A Game Boy upright (1488 x 2266): as wide as the screen, centred down.
        assertContentEquals(doubleArrayOf(0.0, 463.0, 1488.0, 1802.0), GameRenderer.fitRect(160, 144, 1488.0, 2266.0))
        assertContentEquals(doubleArrayOf(0.0, 0.0, 480.0, 320.0), GameRenderer.fitRect(240, 160, 480.0, 320.0))
    }

    @Test
    fun drawsEveryPixelOfTheFrame() {
        val renderer = GameRenderer.create() ?: run {
            println("GameRendererTest: no Metal device here, drawing not checked")
            return
        }
        val dir = assertNotNull(getenv("PK_DIAGNOSTIC_DIR")?.toKString(), "PK_DIAGNOSTIC_DIR not set").trimEnd('/')
        assertTrue(PkApiCore.start("$dir/diagnostic.gba", null))
        repeat(30) { PkApiCore.runFrame() }
        assertTrue(renderer.upload())
        assertEquals(240, renderer.frameWidth)
        val frame = PkApiCore.video()!!.reinterpret<ByteVar>()

        val w = 480
        val h = 320
        val device = renderer.device
        val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
            MTLPixelFormatBGRA8Unorm, w.toULong(), h.toULong(), mipmapped = false,
        )
        desc.usage = MTLTextureUsageRenderTarget
        desc.storageMode = MTLStorageModePrivate
        val target = assertNotNull(device.newTextureWithDescriptor(desc))
        val out = assertNotNull(device.newBufferWithLength((w * h * 4).toULong(), MTLResourceStorageModeShared))
        val pass = MTLRenderPassDescriptor.renderPassDescriptor()
        val color = assertNotNull(pass.colorAttachments.objectAtIndexedSubscript(0u))
        color.texture = target
        color.loadAction = MTLLoadActionClear
        color.storeAction = MTLStoreActionStore
        color.clearColor = cValue<MTLClearColor> { alpha = 1.0 }

        val buffer = assertNotNull(assertNotNull(device.newCommandQueue()).commandBuffer())
        val encoder = assertNotNull(buffer.renderCommandEncoderWithDescriptor(pass))
        renderer.encode(encoder, w.toDouble(), h.toDouble())
        encoder.endEncoding()
        val blit = assertNotNull(buffer.blitCommandEncoder())
        blit.copyFromTexture(
            target, sourceSlice = 0u, sourceLevel = 0u,
            sourceOrigin = cValue<MTLOrigin> { },
            sourceSize = cValue<MTLSize> { width = w.toULong(); height = h.toULong(); depth = 1u },
            toBuffer = out, destinationOffset = 0u,
            destinationBytesPerRow = (w * 4).toULong(), destinationBytesPerImage = (w * h * 4).toULong(),
        )
        blit.endEncoding()
        buffer.commit()
        buffer.waitUntilCompleted()

        val px = assertNotNull(out.contents()).reinterpret<ByteVar>()
        var lit = 0
        for (y in 0 until h) for (x in 0 until w) {
            val src = ((y / 2) * 240 + x / 2) * 4
            val dst = (y * w + x) * 4
            // RGBX in, BGRA out.
            val want = listOf(frame[src + 2], frame[src + 1], frame[src], (-1).toByte())
            val got = listOf(px[dst], px[dst + 1], px[dst + 2], px[dst + 3])
            assertEquals(want, got, "pixel $x, $y")
            if (want[0] != 0.toByte() || want[1] != 0.toByte() || want[2] != 0.toByte()) lit++
        }
        assertTrue(lit > 0, "the diagnostic frame is all black")
    }
}
