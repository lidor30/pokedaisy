package com.pokedaisy.app

import android.view.InputDevice
import android.view.MotionEvent

/** Android events end here; the mappings and input state live in the shared GbaInput. */
fun GbaInput.onMotion(event: MotionEvent): Boolean {
    setAxes(GbaInput.motionBits(event) ?: return false)
    return true
}

fun GbaInput.Companion.motionBits(event: MotionEvent): Int? {
    if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
    return axisBits(
        event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y),
        event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y),
    )
}
