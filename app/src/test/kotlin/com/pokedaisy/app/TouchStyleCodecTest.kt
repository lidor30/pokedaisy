package com.pokedaisy.app

import com.pokedaisy.app.companion.*
import org.junit.Assert.*
import org.junit.Test

class TouchStyleCodecTest {
    private val saved = mutableMapOf<String, Any?>()
    private fun read() = TouchStyleCodec.read(
        text = { saved[it] as? String },
        int = { k, default -> saved[k] as? Int ?: default },
        bool = { k, default -> saved[k] as? Boolean ?: default })
    private fun write(style: TouchControlsStyle) = TouchStyleCodec.write(style,
        text = { k, v -> saved[k] = v }, int = { k, v -> saved[k] = v }, bool = { k, v -> saved[k] = v })

    @Test fun everyOptionAndBothOrientationsSurviveReopening() {
        assertEquals(TouchControlsStyle(), read())
        val portrait = TouchLayout(mapOf(TouchButton.A to TouchPlace(
            TouchPlace.Anchor.END, -31f, TouchPlace.Anchor.CENTER, 18f, 1.25f, false)))
        val landscape = TouchLayout(mapOf(TouchButton.SELECT to TouchPlace(
            TouchPlace.Anchor.START, 12f, TouchPlace.Anchor.END, -20f, 0.75f, true)))
        val style = TouchControlsStyle(show = TouchShow.NEVER, preset = TouchPreset.MIRRORED,
            size = TouchSize.entries.last(), look = TouchLook.entries.last(), colors = TouchColors.entries.last(),
            skinGba = "gba-skin", skinGb = "gb-skin", opacity = 75, extras = setOf(TouchButton.TURBO_B),
            xyButtons = false, position = TouchPosition.BOTTOM, eightWay = false, fadeIdle = true,
            vibrate = true, vibrationStrength = 3, useCustom = true, portrait = portrait, landscape = landscape)
        saved["unrelated"] = "keep"
        write(style)
        assertEquals(style, read())
        assertEquals("keep", saved["unrelated"])
        write(style.copy(skinGba = null, extras = emptySet()))
        assertNull(read().skinGba); assertTrue(read().extras.isEmpty())
    }

    @Test fun oldVibrationAndExtraButtonPreferencesKeepTheirMeaning() {
        saved["touch_vibration"] = true
        saved["touch_extras"] = "FF,TURBO_A,UNKNOWN,A"
        val old = read()
        assertTrue(old.vibrate); assertEquals(1, old.vibrationStrength)
        assertEquals(setOf(TouchButton.FF, TouchButton.TURBO_A, TouchButton.ZOOM, TouchButton.REWIND), old.extras)
        saved["touch_vibration_level"] = 3
        assertEquals(3, read().vibrationStrength)
        saved["touch_vibrate"] = false
        assertFalse(read().vibrate)
    }

    @Test fun unknownChoicesAndDamagedLayoutsUseDefaults() {
        saved["touch_controls"] = 99
        saved["touch_preset"] = "FUTURE"
        saved["touch_layout_portrait"] = "A:broken;UNKNOWN:0,1,0,1,1,0"
        assertEquals(TouchShow.AUTO, read().show)
        assertEquals(TouchPreset.SIDES, read().preset)
        assertTrue(read().portrait.isEmpty)
    }
}
