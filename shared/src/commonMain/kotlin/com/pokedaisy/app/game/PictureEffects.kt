package com.pokedaisy.app.game

import kotlin.math.roundToInt

/** Renderer programs supplied by the shared presentation layer; no frame transport through Compose. */
data class PictureEffects(
    val colors: Program? = null,
    val smoothing: List<SmoothPass> = emptyList(),
    val unlit: Program? = null,
    val filter: Program? = null,
    val prescale: Boolean = false,
    val gridX: Float = 0f,
    val gridY: Float = 0f,
) {
    data class Program(val name: String, val fragment: String)
    data class SmoothPass(val program: Program, val size: PictureScale,
        val halfFloat: Boolean = false, val history: Int? = null)
}

/** Android GL and Metal choose the same whole-pixel filter size and reflective-screen pass size. */
data class PicturePassSizes(val kx: Int, val ky: Int, val integerW: Int, val integerH: Int, val unlitW: Int, val unlitH: Int)

fun picturePassSizes(frameW: Int, frameH: Int, drawW: Float, drawH: Float, prescale: Boolean): PicturePassSizes {
    val kx = maxOf(1, (drawW / frameW).toInt())
    val ky = maxOf(1, (drawH / frameH).toInt())
    return PicturePassSizes(kx, ky, frameW * kx, frameH * ky,
        if (prescale) frameW * kx else maxOf(1, drawW.roundToInt()),
        if (prescale) frameH * ky else maxOf(1, drawH.roundToInt()))
}

/** The existing RetroArch preset sizes, shared by Android GL and Metal. */
sealed class PictureScale {
    /** Relative to the preceding pass (ScaleFX's 1x metrics and final 3x). */
    data class Source(val scale: Int) : PictureScale()
    /** The largest whole multiple of the original frame inside the picture. */
    data object Whole : PictureScale()
    /** Exact picture size, or Whole when the selected filter needs an even pixel grid. */
    data object View : PictureScale()
}

fun picturePassSize(scale: PictureScale, sourceW: Int, sourceH: Int, sizes: PicturePassSizes): Pair<Int, Int> = when (scale) {
    is PictureScale.Source -> sourceW * scale.scale to sourceH * scale.scale
    PictureScale.Whole -> sizes.integerW to sizes.integerH
    PictureScale.View -> sizes.unlitW to sizes.unlitH
}
