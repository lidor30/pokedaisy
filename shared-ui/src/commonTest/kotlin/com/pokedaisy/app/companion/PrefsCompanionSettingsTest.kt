package com.pokedaisy.app.companion

import com.pokedaisy.app.GbaControls
import com.pokedaisy.app.Hotkeys
import com.pokedaisy.app.Prefs
import kotlin.test.*

class PrefsCompanionSettingsTest {
    internal class Store : PreferenceStore {
        val data = mutableMapOf<String, Any?>()
        override fun keys(): Set<String> = data.keys.toSet()
        override fun contains(key: String) = key in data
        override fun getBoolean(key: String, default: Boolean) = data[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = data[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = data[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = data[key] as? Float ?: default
        override fun getString(key: String, default: String?) = data[key] as? String ?: default
        override fun write(values: Map<String, Any?>) { values.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v } }
    }
    internal class Settings(p: Prefs, allowDisableResume: () -> Boolean = { false },
        private val stored: PrefsCompanionSettings = PrefsCompanionSettings(p, allowDisableResume = allowDisableResume),
    ) : CompanionSettings, StoredCompanionSettings by stored {
        val speeds = mutableListOf<Float>()
        val fieldOptions = mutableListOf<Boolean>()
        var blocked = false
        init {
            stored.changed = { change -> when (change) {
                PrefsCompanionSettings.Change.PLAYBACK -> speeds += ffMaxSpeed
                PrefsCompanionSettings.Change.FIELD_OPTIONS -> fieldOptions += reusableTms
            } }
        }
        override fun setReusableTms(on: Boolean) { if (!blocked) stored.setReusableTms(on) }
        override val gameName = "test"
        override val romFileName = "test.gba"
        override fun gbaControlBindings(): Map<GbaControls.Btn, List<String>> = emptyMap()
        override fun setGbaControlBinding(btn: GbaControls.Btn, keyName: String) {}
        override fun hotkeyBindings(): Map<Hotkeys.Action, List<String>> = emptyMap()
        override fun setHotkeyBinding(action: Hotkeys.Action, keyNames: List<String>?, takeFrom: List<Hotkeys.Action>) {}
        override fun closeGame() {}
        override fun restartGame() {}
    }

    @Test fun effectsObserveCommittedValuesAndInvalidSpeedChoicesDoNotFire() {
        val p = Prefs(Store()); val s = Settings(p)
        s.setFfMaxSpeed(4f)
        assertEquals(4f, p.ffMaxSpeed); assertEquals(listOf(4f), s.speeds)
        s.setFfMaxSpeed(Float.NaN); s.setBattleSpeed(-1f)
        assertEquals(listOf(4f), s.speeds); assertEquals(0f, s.battleSpeed)
        s.setBattleSpeed(3f)
        assertEquals(3f, p.battleSpeed)
        p.menuSpeed = 99f
        assertEquals(0f, s.menuSpeed)
    }

    @Test fun hostVetoesProtectBothStoredValuesAndRuntimeEffects() {
        val p = Prefs(Store())
        var canDiscard = false
        val s = Settings(p, allowDisableResume = { canDiscard })
        s.setAutoResume(false)
        assertTrue(p.autoResume)
        canDiscard = true; s.setAutoResume(false)
        assertFalse(p.autoResume)
        s.blocked = true; s.setReusableTms(true)
        assertFalse(p.reusableTms); assertTrue(s.fieldOptions.isEmpty())
        s.blocked = false; s.setReusableTms(true)
        assertTrue(p.reusableTms); assertEquals(listOf(true), s.fieldOptions)
    }

    @Test fun bothInstalledBattleStyleFormatsKeepTheirRepresentation() {
        val androidStore = Store().apply { data["battle_style"] = BattleStyle.GEN4.id }
        val iosStore = Store().apply { data["battle_style"] = BattleStyle.GEN4.name }
        val android = Settings(Prefs(androidStore))
        val ios = Settings(Prefs(iosStore, Prefs.Defaults(battleStyleNames = true)))
        val style = BattleStyle.entries.first { it != BattleStyle.GEN4 }
        android.setBattleStyle(style); ios.setBattleStyle(style)
        assertEquals(style, android.battleStyle); assertEquals(style, ios.battleStyle)
        assertEquals(style.id, androidStore.data["battle_style"])
        assertEquals(style.name, iosStore.data["battle_style"])
    }
    @Test fun newGameOptionsUseTheSharedKeysAndCommitBeforeNotification() {
        val prefs = Prefs(Store()); val observed = mutableListOf<Triple<Boolean, Int, Boolean>>()
        val stored = PrefsCompanionSettings(prefs)
        stored.changed = { change ->
            assertEquals(PrefsCompanionSettings.Change.FIELD_OPTIONS, change)
            observed += Triple(prefs.hmFree, prefs.expShare, prefs.levelCap)
        }
        stored.setHmFree(true)
        stored.setExpShare(com.pokedaisy.app.companion.data.ExpShare.Mode.FULL)
        stored.setLevelCap(true)
        assertEquals(listOf(Triple(true, 0, false), Triple(true, com.pokedaisy.app.companion.data.ExpShare.Mode.FULL.id, false),
            Triple(true, com.pokedaisy.app.companion.data.ExpShare.Mode.FULL.id, true)), observed)
    }

    @Test fun fontChoicePersistsThroughTheSharedThemeStoreAndRejectsUnknownOrdinals() {
        val prefs = Prefs(Store())
        val oldStore = com.pokedaisy.app.companion.ui.theme.ThemeStore.current
        val oldFont = com.pokedaisy.app.companion.ui.theme.AppFonts.setting
        try {
            com.pokedaisy.app.companion.ui.theme.ThemeStore.current = com.pokedaisy.app.companion.ui.theme.PrefsThemeStore(prefs)
            PrefsCompanionSettings(prefs).setAppFont(2)
            com.pokedaisy.app.companion.ui.theme.AppFonts.load()
            assertEquals(com.pokedaisy.app.companion.ui.theme.AppFont.SMOOTH_BOLD, com.pokedaisy.app.companion.ui.theme.AppFonts.setting)
            com.pokedaisy.app.companion.ui.theme.AppFonts.set(com.pokedaisy.app.companion.ui.theme.AppFont.SYSTEM)
            assertEquals(3, prefs.appFont)
            prefs.appFont = 99
            com.pokedaisy.app.companion.ui.theme.AppFonts.load()
            assertEquals(com.pokedaisy.app.companion.ui.theme.AppFont.PIXEL, com.pokedaisy.app.companion.ui.theme.AppFonts.setting)
        } finally {
            com.pokedaisy.app.companion.ui.theme.ThemeStore.current = oldStore
            com.pokedaisy.app.companion.ui.theme.AppFonts.setting = oldFont
        }
    }

}
