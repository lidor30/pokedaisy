package com.pokedaisy.app.ios

import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.PreferenceStore
import platform.Foundation.NSUserDefaults

/** The existing iOS defaults domain. Shared settings retain the three defaults of installed iOS builds. */
internal val iosPreferences = Prefs(DefaultsPreferenceStore(NSUserDefaults.standardUserDefaults),
    Prefs.Defaults(hotkeysEnabled = false, sidePanelDocked = true, companionWindow = false, battleStyleNames = true))

internal class DefaultsPreferenceStore(private val d: NSUserDefaults) : PreferenceStore {
    override fun keys(): Set<String> = d.dictionaryRepresentation().keys.filterIsInstance<String>().toSet()
    override fun contains(key: String) = d.objectForKey(key) != null
    override fun getBoolean(key: String, default: Boolean) = if (contains(key)) d.boolForKey(key) else default
    override fun getInt(key: String, default: Int) = if (contains(key)) d.integerForKey(key).toInt() else default
    override fun getLong(key: String, default: Long) = if (contains(key)) d.integerForKey(key) else default
    override fun getFloat(key: String, default: Float) = if (contains(key)) d.floatForKey(key) else default
    override fun getString(key: String, default: String?) = d.stringForKey(key) ?: default
    override fun write(values: Map<String, Any?>) {
        for ((key, value) in values) when (value) {
            null -> d.removeObjectForKey(key)
            is Boolean -> d.setBool(value, key)
            is Int -> d.setInteger(value.toLong(), key)
            is Long -> d.setInteger(value, key)
            is Float -> d.setFloat(value, key)
            is String -> d.setObject(value, key)
            else -> error("Unsupported preference type")
        }
    }
}
