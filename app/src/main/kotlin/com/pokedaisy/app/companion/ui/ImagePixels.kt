package com.pokedaisy.app.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pokedaisy.app.companion.data.LockedLru
import com.pokedaisy.app.companion.data.RomArt

/*
 * The screens' pictures are Compose ImageBitmaps (common to Android and iOS), read through readPixels. Making one from
 * pixels or decoding a file is the platform's: those two are here, the only place the screens touch
 * android.graphics, so the iPad build replaces this file alone.
 */

/** An [width] x [height] picture of [argb] (ARGB, row by row). */
fun imageBitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

/** A PNG / JPEG / WebP file's bytes as a picture, or null when they aren't one. */
fun decodeImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

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
