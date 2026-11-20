package com.pokedaisy.app.companion

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.WideCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExpandedZoomTest {
    @Test fun selectingClampsAndCyclingWrapsTheSavedZoomForThisRom() {
        val prefs = Prefs(PrefsCompanionSettingsTest.Store())
        val camera = WideCamera().apply {
            viewW = 1920
            viewH = 1080
        }
        prefs.setZoomLevelFor("other", 2)
        val zooms = camera.zoomFactors()
        val last = zooms.lastIndex
        assertEquals(zooms.last(), camera.changeExpandedZoom(prefs, "rom", Int.MAX_VALUE))
        assertEquals(last, camera.level)
        assertEquals(last, prefs.zoomLevelFor("rom"))

        assertEquals(1f, camera.changeExpandedZoom(prefs, "rom"))
        assertEquals(0, prefs.zoomLevelFor("rom"))
        assertEquals(zooms[1], camera.changeExpandedZoom(prefs, "rom"))
        assertEquals(1f, camera.changeExpandedZoom(prefs, "rom", -1))
        assertEquals(2, prefs.zoomLevelFor("other"))
    }

    @Test fun aCameraWithoutARomKeyCanChangeZoomWithoutWritingAPreference() {
        val store = PrefsCompanionSettingsTest.Store()
        val camera = WideCamera().apply {
            viewW = 1920
            viewH = 1080
        }
        camera.changeExpandedZoom(Prefs(store), "", 1)
        assertEquals(1, camera.level)
        assertFalse(store.contains("zoom_"))
    }
}
