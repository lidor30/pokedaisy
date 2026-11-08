package com.pokedaisy.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * A Delta skin's picture on Android (companion.ui.SkinImages.decode): a PDF's first page rendered at the size it's shown
 * (Delta's "resizable" assets are vector PDFs, so they stay sharp at any size), a PNG decoded no bigger than needed.
 * Transparent where the picture is.
 */
object SkinBitmaps {
    fun decode(file: File, w: Int, h: Int): ImageBitmap? = when {
        isPdf(file) -> pdf(file, w, h)
        else -> png(file, w, h)
    }?.asImageBitmap()

    private fun isPdf(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "%PDF" }
    }.getOrDefault(false)

    private fun pdf(file: File, w: Int, h: Int): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { doc ->
                if (doc.pageCount == 0) return null
                doc.openPage(0).use { page ->
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // The page stretched over the whole bitmap: the skin's mapping size is the page's.
                    val m = android.graphics.Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    private fun png(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Halve while it stays at least the size it's drawn at.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
