@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.darwin.NSObject
import platform.Metal.MTLClearColor
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol

/** UIKit presents the same Metal renderer used by single-cartridge play. */
internal class LinkGamePicture {
    private val renderer = GameRenderer.create()
    private val queue = renderer?.device?.newCommandQueue()
    val available get() = queue != null

    private val delegate = object : NSObject(), MTKViewDelegateProtocol {
        override fun drawInMTKView(view: MTKView) {
            val renderer = renderer ?: return
            val pass = view.currentRenderPassDescriptor ?: return
            val drawable = view.currentDrawable ?: return
            val buffer = queue?.commandBuffer() ?: return
            val encoder = buffer.renderCommandEncoderWithDescriptor(pass) ?: return
            view.drawableSize.useContents { renderer.encode(encoder, width, height) }
            encoder.endEncoding()
            buffer.presentDrawable(drawable)
            buffer.commit()
        }

        override fun mtkView(view: MTKView, drawableSizeWillChange: CValue<CGSize>) = view.draw()
    }

    val view = MTKView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), device = renderer?.device).apply {
        paused = true
        enableSetNeedsDisplay = false
        userInteractionEnabled = false
        clearColor = cValue<MTLClearColor> { alpha = 1.0 }
        delegate = this@LinkGamePicture.delegate
    }

    fun publish(pixels: ByteArray, width: Int, height: Int) {
        val uploaded = pixels.usePinned { renderer?.uploadFrame(it.addressOf(0), width, height) == true }
        if (uploaded) view.draw()
    }
}
