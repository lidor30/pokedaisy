package com.pokedaisy.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pokedaisy.app.companion.ui.PictureCodec

/** The screens' pictures on Android (companion.ui.PictureCodec): Bitmaps, decoded by BitmapFactory. */
object AndroidPictures : PictureCodec {
    override fun fromArgb(argb: IntArray, width: Int, height: Int): ImageBitmap =
        Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

    override fun decode(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
