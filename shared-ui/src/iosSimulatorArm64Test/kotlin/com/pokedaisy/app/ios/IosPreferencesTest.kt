package com.pokedaisy.app.ios

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.edit
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosPreferencesTest {
    @Test fun existingAppleValuesAndSharedWritesUseTheSameDomainAndTypes() {
        val name = "com.pokedaisy.tests.common-preferences"
        val d = NSUserDefaults(suiteName = name)
        d.removePersistentDomainForName(name)
        try {
            d.setBool(false, "game_muted"); d.setInteger(0, "game_volume")
            d.setDouble(0.625, "side_panel_width"); d.setObject("134217729,134217733", "ff_menu_cb2_v2_test")
            val store = DefaultsPreferenceStore(d)
            assertTrue("game_muted" in store.keys())
            val prefs = Prefs(store, Prefs.Defaults(hotkeysEnabled = false))
            assertFalse(prefs.gameMuted); assertEquals(0, prefs.gameVolume)
            assertEquals(0.625f, prefs.sidePanelWidth)
            assertEquals(134217729L to 134217733L, prefs.ffMenuCallbacks("test"))
            prefs.gameMuted = true; prefs.ffMaxSpeed = 0f; prefs.playMillis = 4_000_000_001L
            assertTrue(d.boolForKey("game_muted")); assertEquals(0f, d.floatForKey("ff_max_speed"))
            assertEquals(4_000_000_001L, d.integerForKey("play_millis"))
            store.edit().putString("remove", "value").apply()
            store.edit().putString("remove", null).apply()
            assertNull(d.objectForKey("remove"))
            assertFalse(prefs.hotkeysEnabled); prefs.hotkeysEnabled = true; assertTrue(prefs.hotkeysEnabled)
        } finally { d.removePersistentDomainForName(name) }
    }
}
