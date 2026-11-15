package com.pokedaisy.app.companion.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.pokedaisy.app.companion.data.LockedLru
import com.pokedaisy.app.companion.data.RomArt
import kotlin.concurrent.Volatile

/*
 * The screens' pictures are Compose ImageBitmaps (common to Android and iOS), read through readPixels. Making one from
 * pixels or decoding a file is the platform's ([PictureCodec]: Android's Bitmap / BitmapFactory, AndroidPictures.kt);
 * the screens go through the two functions below.
 */

/** How the platform makes a picture: from pixels, or from an image file's bytes. Set by the app at start. */
interface PictureCodec {
    /** An [width] x [height] picture of [argb] (ARGB, row by row). */
    fun fromArgb(argb: IntArray, width: Int, height: Int): ImageBitmap

    /** A PNG / JPEG / WebP file's bytes as a picture, or null when they aren't one. */
    fun decode(bytes: ByteArray): ImageBitmap?

    companion object {
        /** Set by the app at start (and by ui-preview / the screenshot tests). */
        @Volatile var current: PictureCodec? = null

        fun get(): PictureCodec = checkNotNull(current) { "PictureCodec.current isn't set" }
    }
}

/** An [width] x [height] picture of [argb] (ARGB, row by row). */
fun imageBitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap = PictureCodec.get().fromArgb(argb, width, height)

/** A PNG / JPEG / WebP file's bytes as a picture, or null when they aren't one. */
fun decodeImage(bytes: ByteArray): ImageBitmap? = PictureCodec.get().decode(bytes)

/** Every pixel, ARGB, row by row. */
fun ImageBitmap.argb(): IntArray = IntArray(width * height).also { readPixels(it) }

/** The [w] x [h] part from ([x], [y]). */
fun ImageBitmap.crop(x: Int, y: Int, w: Int, h: Int): ImageBitmap =
    imageBitmapOf(IntArray(w * h).also { readPixels(it, x, y, w, h) }, w, h)

/** The data code's [RomArt.Image] as a picture, made once per image while it's in use: the icon and dex caches hand
 * out the same Image each time, so a list scrolling back doesn't copy its pixels again. */
fun RomArt.Image.imageBitmap(): ImageBitmap = drawn.getOrPut(this) { imageBitmapOf(argb, width, height) }

// By the Image itself (it has no equals of its own); the recent ones, as the weak map it replaced kept the live ones.
private val drawn = LockedLru<RomArt.Image, ImageBitmap>(512)
