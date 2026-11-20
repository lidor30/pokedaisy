package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.WideCamera

/** The zoom choices in Settings, expressed as multiples of the original frame. */
fun WideCamera.zoomFactors(): List<Float> {
    val scales = levels()
    return scales.map { scales[0] / it }
}

/** Applies a chosen step, or cycles away from the current step when [step] is absent. */
fun WideCamera.changeExpandedZoom(prefs: Prefs, romKey: String, step: Int? = null): Float {
    if (step == null) cycle()
    else level = step.coerceIn(0, levels().lastIndex.coerceAtLeast(0))
    FeatureTips.used(TipId.ZOOM)
    if (romKey.isNotEmpty()) prefs.setZoomLevelFor(romKey, level)
    return factor(level)
}
