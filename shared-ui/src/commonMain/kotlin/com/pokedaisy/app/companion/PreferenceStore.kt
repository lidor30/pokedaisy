package com.pokedaisy.app.companion

/** Primitive settings storage. Writes publish one batch; null removes a key. Values are Boolean, Int, Long,
 * Float or String, as in the installed Android preferences. No settings policy belongs in the adapters. */
interface PreferenceStore {
    fun keys(): Set<String> = emptySet()
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float
    fun getString(key: String, default: String?): String?
    fun write(values: Map<String, Any?>)
}

/** Keeps compound settings updates together without exposing a platform's editor. */
class PreferenceEdit internal constructor(private val store: PreferenceStore) {
    private val values = linkedMapOf<String, Any?>()
    fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
    fun putInt(key: String, value: Int) = apply { values[key] = value }
    fun putLong(key: String, value: Long) = apply { values[key] = value }
    fun putFloat(key: String, value: Float) = apply { values[key] = value }
    fun putString(key: String, value: String?) = apply { values[key] = value }
    fun remove(key: String) = apply { values[key] = null }
    fun apply() = store.write(values.toMap())
}

fun PreferenceStore.edit() = PreferenceEdit(this)
