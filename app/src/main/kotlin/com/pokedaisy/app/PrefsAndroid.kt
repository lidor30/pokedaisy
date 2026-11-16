package com.pokedaisy.app

import android.content.Context
import com.pokedaisy.app.companion.PreferenceStore
import java.io.File

/** Preserve Android callers and the installed SharedPreferences file while sharing all preference policy. */
fun Prefs(context: Context): Prefs = Prefs(AndroidPreferenceStore(context),
    dsCpuBoostDefault = { !DevicePerformance.looksWeak(context.applicationContext ?: context) })

private class AndroidPreferenceStore(context: Context) : PreferenceStore {
    private val p = context.getSharedPreferences("pokedaisy", Context.MODE_PRIVATE)
    override fun keys(): Set<String> = p.all.keys
    override fun contains(key: String) = p.contains(key)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getLong(key: String, default: Long) = p.getLong(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun write(values: Map<String, Any?>) {
        val edit = p.edit()
        for ((key, value) in values) when (value) {
            null -> edit.remove(key)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            else -> error("Unsupported preference type")
        }
        edit.apply()
    }
}

fun Prefs.romCoverManual(file: File) = romCoverManual(file.name)
fun Prefs.setRomCoverManual(file: File, manual: Boolean) = setRomCoverManual(file.name, manual)
fun Prefs.romCoverSource(file: File) = romCoverSource(file.name)
fun Prefs.setRomCoverSource(file: File, source: String?) = setRomCoverSource(file.name, source)
fun Prefs.romDisplayName(file: File) = romDisplayName(file.name)
fun Prefs.setRomDisplayName(file: File, name: String?) = setRomDisplayName(file.name, name)
fun Prefs.romSourcePath(file: File) = romSourcePath(file.name)
fun Prefs.setRomSourcePath(file: File, path: String?) = setRomSourcePath(file.name, path)
fun Prefs.romSaveDir(file: File) = romSaveDir(file.name)
fun Prefs.setRomSaveDir(file: File, path: String?) = setRomSaveDir(file.name, path)
