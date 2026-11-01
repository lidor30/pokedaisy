package com.pokedaisy.app.companion.coop.lock

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Which saves are locked to a partner, in `filesDir/coop/locks.json` (array of {own, partner, since}, temp file + rename). */
class CoopLockStore(private val file: File) {
    private data class Entry(val own: String, val partner: String, val since: Long)

    private var blocked = false

    @Synchronized
    fun isLocked(own: String, partner: String): Boolean = load().any { it.own == own && it.partner == partner }

    @Synchronized
    fun lock(own: String, partner: String) {
        val list = loadForWrite().filterNot { it.own == own }
        store(list + Entry(own, partner, System.currentTimeMillis()))
    }

    @Synchronized
    fun unlock(own: String) {
        val list = loadForWrite()
        if (list.none { it.own == own }) return
        store(list.filterNot { it.own == own })
    }

    private fun loadForWrite(): List<Entry> {
        val list = load()
        if (blocked) throw IOException("the locks file can't be read, so it won't be overwritten")
        return list
    }

    private fun load(): List<Entry> {
        blocked = false
        if (!file.isFile) return emptyList()
        val text = try { file.readText(Charsets.UTF_8) } catch (e: IOException) { blocked = true; return emptyList() }
        return try {
            val a = JSONArray(text)
            List(a.length()) { val o = a.getJSONObject(it); Entry(o.getString("own"), o.getString("partner"), o.getLong("since")) }
        } catch (e: JSONException) {
            keepAside()
        }
    }

    private fun keepAside(): List<Entry> {
        val dir = file.parentFile
        var bad = File(dir, file.name + ".bad")
        var n = 1
        while (bad.exists()) bad = File(dir, file.name + ".bad." + n++)
        if (!runCatching { file.renameTo(bad) }.getOrDefault(false)) blocked = true
        return emptyList()
    }

    private fun store(list: List<Entry>) {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("own", it.own).put("partner", it.partner).put("since", it.since)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { it.write(a.toString().toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
