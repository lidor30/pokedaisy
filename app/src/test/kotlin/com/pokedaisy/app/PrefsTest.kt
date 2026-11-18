package com.pokedaisy.app

import com.pokedaisy.app.companion.PreferenceStore
import com.pokedaisy.app.companion.TouchControlsStyle
import org.junit.Assert.*
import org.junit.Test

class PrefsTest {
    private class Store(val values: MutableMap<String, Any?> = mutableMapOf()) : PreferenceStore {
        val batches = mutableListOf<Map<String, Any?>>()
        override fun contains(key: String) = values.containsKey(key)
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
        override fun getString(key: String, default: String?) = values[key] as? String ?: default
        override fun write(values: Map<String, Any?>) {
            batches += values
            for ((key, value) in values) if (value == null) this.values.remove(key) else this.values[key] = value
        }
    }

    @Test fun installedKeysReadWithoutRewritingOrChangingTheirMeaning() {
        val store = Store(mutableMapOf("game_volume" to 0, "game_muted" to false, "ff_max_speed" to 0f,
            "wild_encounters" to 25, "ff_menu_cb2_v2_abcd" to "134217729,134217733",
            "new_feature_since" to "old:123,broken,group:feature:456", "companion_tabs" to "PARTY,,MAP"))
        val p = Prefs(store)
        assertEquals(0, p.gameVolume); assertFalse(p.gameMuted); assertEquals(0f, p.ffMaxSpeed)
        assertEquals(100, p.wildEncounters)
        assertEquals(134217729L to 134217733L, p.ffMenuCallbacks("abcd"))
        assertEquals(mapOf("old" to 123L, "group:feature" to 456L), p.newFeatureSince)
        assertEquals(listOf("PARTY", "MAP"), p.companionTabs)
        assertTrue(store.batches.isEmpty())
    }

    @Test fun hostDefaultsNeverOverrideExplicitFalseOrZero() {
        val store = Store()
        var weak = true
        val android = Prefs(store, dsCpuBoostDefault = { !weak })
        val ios = Prefs(store, Prefs.Defaults(hotkeysEnabled = false, sidePanelDocked = true, companionWindow = false))
        assertTrue(android.hotkeysEnabled); assertFalse(ios.hotkeysEnabled)
        assertFalse(android.sidePanelDocked); assertTrue(ios.sidePanelDocked)
        assertTrue(android.companionWindow); assertFalse(ios.companionWindow)
        assertFalse(android.dsCpuBoost); weak = false; assertTrue(android.dsCpuBoost)
        ios.hotkeysEnabled = true; ios.sidePanelDocked = false; ios.companionWindow = false
        android.dsCpuBoost = false
        assertTrue(ios.hotkeysEnabled); assertFalse(ios.sidePanelDocked); assertFalse(android.companionWindow)
        assertFalse(android.dsCpuBoost)
    }

    @Test fun legacyMigrationsAndCompoundEditsKeepTheirExistingKeys() {
        val store = Store(mutableMapOf("roms_folder" to "legacy", "app_theme" to 3))
        val p = Prefs(store)
        assertEquals(listOf("legacy"), p.romsFolders); assertEquals(8, p.appTheme)
        p.romsFolders = listOf("one", "one", "two")
        assertEquals(mapOf("roms_folders" to "one\ntwo", "roms_folder" to null), store.batches.last())
        p.flyStyleId = 1
        assertEquals(mapOf("fly_style" to 1, "fly_style_asked" to true), store.batches.last())
        p.touchControlsStyle = TouchControlsStyle()
        assertEquals(p.touchControlsStyle, Prefs(store).touchControlsStyle)
        assertEquals(3, store.batches.size)
    }

    @Test fun metadataUsesTheSameFileNameKeysAndNullDeletes() {
        val store = Store(); val p = Prefs(store)
        p.setRomDisplayName("cart.gba", "  My cart  ")
        assertEquals("My cart", store.values["romname_cart.gba"])
        p.setRomDisplayName("cart.gba", null)
        assertFalse(store.contains("romname_cart.gba"))
        p.setRomCoverSource("cart.gba", "MANUAL")
        p.setRomCoverSource("cart.gba", null)
        assertNull(p.romCoverSource("cart.gba"))
        p.playMillis = 4_000_000_001L
        assertEquals(4_000_000_001L, Prefs(store).playMillis)
    }
}
