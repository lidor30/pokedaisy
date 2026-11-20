@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.SYSTEM
import okio.buffer
import okio.use
import okio.Path
import org.jetbrains.skia.Image
import platform.CoreGraphics.*
import platform.CoreFoundation.CFURLCreateFromFileSystemRepresentation
import platform.CoreFoundation.CFRelease
import platform.UIKit.*
import platform.posix.memcpy

/** Native PDF rasterization; shared SkinImages owns requested dimensions, cache and drawing. */
internal object IosSkinImages {
    fun decode(file: Path, w: Int, h: Int): ImageBitmap? {
        val header = FileSystem.SYSTEM.source(file).buffer().use { it.readByteArray(4) }
        if (!header.contentEquals(byteArrayOf(37, 80, 68, 70))) {
            val source = UIImage.imageWithContentsOfFile(file.toString()) ?: return null
            return source.imageByPreparingThumbnailOfSize(CGSizeMake(w.toDouble(), h.toDouble()))?.let(::bitmap)
        }
        val name = file.toString().encodeToByteArray()
        val url = name.usePinned { CFURLCreateFromFileSystemRepresentation(null, it.addressOf(0).reinterpret(), name.size.toLong(), false) } ?: return null
        val document = try { CGPDFDocumentCreateWithURL(url) } finally { CFRelease(url) } ?: return null
        try {
            val page = CGPDFDocumentGetPage(document, 1u) ?: return null
            val box = CGPDFPageGetBoxRect(page, kCGPDFMediaBox)
            val valid = box.useContents { size.width > 0 && size.height > 0 }
            if (!valid) return null
            val format = UIGraphicsImageRendererFormat().apply { opaque = false; scale = 1.0 }
            val renderer = UIGraphicsImageRenderer(CGSizeMake(w.toDouble(), h.toDouble()), format)
            val image = renderer.imageWithActions { context ->
                val cg = context!!.CGContext
                box.useContents {
                    CGContextTranslateCTM(cg, 0.0, h.toDouble())
                    CGContextScaleCTM(cg, w / size.width, -h / size.height)
                    CGContextTranslateCTM(cg, -origin.x, -origin.y)
                }
                CGContextDrawPDFPage(cg, page)
            }
            return bitmap(image)
        } finally { CGPDFDocumentRelease(document) }
    }

    private fun bitmap(image: UIImage): ImageBitmap? {
        val data = UIImagePNGRepresentation(image) ?: return null
        val png = ByteArray(data.length.toInt())
        png.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return Image.makeFromEncoded(png).toComposeImageBitmap()
    }
}
