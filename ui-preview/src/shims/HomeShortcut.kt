package com.pokedaisy.app

import android.content.Context
import java.io.File

/** Shim: desktop has no home screen. */
object HomeShortcut {
    fun request(context: Context, rom: File, label: String, cover: File): Boolean = false
}
