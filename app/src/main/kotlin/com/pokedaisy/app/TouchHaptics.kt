package com.pokedaisy.app

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * TOUCH CONTROLS > VIBRATION's buzz on a press, at STRENGTH [level] (1 LIGHT, 2 MEDIUM, 3 STRONG; 0 none). The motor
 * is driven directly at every strength: the phone's own touch tick (performHapticFeedback) follows its touch vibration
 * setting and did nothing where that was off. Pulses long enough for a handheld's rumble motor to spin up, softer where
 * the motor can set its strength.
 */
object TouchHaptics {
    fun buzz(context: Context, level: Int) {
        if (level <= 0) return
        val motor = context.getSystemService(Vibrator::class.java) ?: return
        if (!motor.hasVibrator()) return
        val ms = when (level) { 1 -> 14L; 2 -> 22L; else -> 40L }
        val amp = if (motor.hasAmplitudeControl()) when (level) { 1 -> 90; 2 -> 170; else -> 255 } else VibrationEffect.DEFAULT_AMPLITUDE
        runCatching { motor.vibrate(VibrationEffect.createOneShot(ms, amp)) }
    }
}
