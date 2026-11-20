package com.pokedaisy.app.ios

import com.pokedaisy.app.ScreenShaders
import com.pokedaisy.app.companion.MetalSmoothing
import com.pokedaisy.app.companion.Smoothing
import com.pokedaisy.app.game.PictureEffects
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSLog

/** Reads original bundled assets once; common presets own sizing and Metal compiles off the draw thread. */
internal object IosSmoothing {
    private fun load(mode: Smoothing): List<PictureEffects.SmoothPass>? = runCatching {
        val preset = ScreenShaders.smoothingPasses(mode) ?: return@runCatching null
        preset.mapIndexed { index, pass ->
            val path = NSBundle.mainBundle.pathForResource(pass.asset.substringBeforeLast('.'), "glsl", "shaders")
                ?: return@runCatching null
            val source = FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }
            val program = MetalSmoothing.program(mode, source, index)
            PictureEffects.SmoothPass(program, pass.size, pass.float, MetalSmoothing.history(source))
        }
    }.onFailure { NSLog("PokeDaisy: smoothing asset failed: %@", it.message) }.getOrNull()

    private val xbrz by lazy { load(Smoothing.XBRZ) }
    private val xbr by lazy { load(Smoothing.XBR) }
    private val scalefx by lazy { load(Smoothing.SCALEFX) }
    val modes get() = listOf(Smoothing.OFF) + Smoothing.entries.filter { it != Smoothing.OFF && program(it).isNotEmpty() }
    fun program(mode: Smoothing): List<PictureEffects.SmoothPass> = when (mode) {
        Smoothing.XBRZ -> xbrz.orEmpty()
        Smoothing.XBR -> xbr.orEmpty()
        Smoothing.SCALEFX -> scalefx.orEmpty()
        else -> emptyList()
    }
}
