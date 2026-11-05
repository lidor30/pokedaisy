package com.pokedaisy.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import java.io.File

/**
 * A game's menu > ADD TO HOME (GitHub #182): a pinned shortcut on Android's home screen, through [LaunchActivity] as a
 * frontend's launch is, so leaving the game goes back to the home screen. Its icon is the game's cover.
 */
object HomeShortcut {
    /** Icons are at most this wide: a launcher draws them small, and they're stored with the shortcut. */
    private const val ICON_PX = 256

    /** Asks the launcher to pin it (it shows its own confirmation). False where the launcher can't. */
    fun request(context: Context, rom: File, label: String, cover: File): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val intent = Intent(context, LaunchActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("rom", rom.absolutePath)
        val info = ShortcutInfo.Builder(context, "rom:${rom.absolutePath.hashCode()}")
            .setShortLabel(label.take(MAX_LABEL).ifEmpty { rom.nameWithoutExtension.take(MAX_LABEL) })
            .setLongLabel(label.ifEmpty { rom.nameWithoutExtension })
            .setIcon(iconOf(cover)?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return runCatching { manager.requestPinShortcut(info, null) }.getOrDefault(false)
    }

    /** Android asks for short labels of about this length. */
    private const val MAX_LABEL = 25

    /** [cover] cut to a centred square. */
    private fun iconOf(cover: File): Bitmap? {
        if (!cover.isFile) return null
        val art = runCatching { BitmapFactory.decodeFile(cover.path) }.getOrNull() ?: return null
        val side = minOf(art.width, art.height)
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val from = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
        Canvas(out).drawBitmap(art, from, Rect(0, 0, ICON_PX, ICON_PX), Paint(Paint.FILTER_BITMAP_FLAG))
        art.recycle()
        return out
    }
}
