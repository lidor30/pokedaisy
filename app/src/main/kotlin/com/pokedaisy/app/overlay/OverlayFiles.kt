package com.pokedaisy.app.overlay

import java.io.File
import okio.Path.Companion.toOkioPath

/** JVM hosts keep their platform file root; overlay parsing and storage are shared. */
fun OverlayStore(root: File): OverlayStore = OverlayStore(root.toOkioPath())
