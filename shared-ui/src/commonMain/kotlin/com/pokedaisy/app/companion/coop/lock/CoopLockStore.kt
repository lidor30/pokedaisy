package com.pokedaisy.app.companion.coop.lock

import com.pokedaisy.app.companion.coop.replaceFile
import com.pokedaisy.app.companion.coop.writeDurably
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** Keeps the existing array of {own, partner, since} in coop/locks.json. */
class CoopLockStore(private val file: Path) {
    private data class Entry(val own: String, val partner: String, val since: Long)

    private val lock = SynchronizedObject()
    private val fs = FileSystem.SYSTEM
    private var blocked = false

    fun isLocked(own: String, partner: String): Boolean = synchronized(lock) {
        load().any { it.own == own && it.partner == partner }
    }

    fun lock(own: String, partner: String): Unit = synchronized(lock) {
        store(loadForWrite().filterNot { it.own == own } + Entry(own, partner, Clock.System.now().toEpochMilliseconds()))
    }

    fun unlock(own: String): Unit = synchronized(lock) {
        val list = loadForWrite()
        if (list.any { it.own == own }) store(list.filterNot { it.own == own })
    }

    private fun loadForWrite(): List<Entry> {
        val list = load()
        if (blocked) throw IOException("the locks file can't be read, so it won't be overwritten")
        return list
    }

    private fun load(): List<Entry> {
        blocked = false
        val text = try {
            if (fs.metadataOrNull(file)?.isRegularFile != true) return emptyList()
            fs.read(file) { readUtf8() }
        } catch (_: IOException) {
            blocked = true
            return emptyList()
        }
        return try {
            Json.parseToJsonElement(text).jsonArray.map {
                val row = it.jsonObject
                Entry(row.getValue("own").jsonPrimitive.content, row.getValue("partner").jsonPrimitive.content,
                    row.getValue("since").jsonPrimitive.long)
            }
        } catch (_: IllegalArgumentException) {
            keepAside()
        } catch (_: NoSuchElementException) {
            keepAside()
        }
    }

    private fun sibling(suffix: String): Path = file.parent?.div(file.name + suffix) ?: (file.name + suffix).toPath()

    private fun keepAside(): List<Entry> {
        blocked = !runCatching {
            var bad = sibling(".bad")
            var n = 1
            while (fs.exists(bad)) bad = sibling(".bad." + n++)
            replaceFile(file, bad)
        }.isSuccess
        return emptyList()
    }

    private fun store(list: List<Entry>) {
        file.parent?.let { fs.createDirectories(it) }
        val json = JsonArray(list.map { row ->
            buildJsonObject {
                put("own", row.own)
                put("partner", row.partner)
                put("since", row.since)
            }
        })
        val tmp = sibling(".tmp")
        writeDurably(tmp, json.toString().encodeToByteArray())
        replaceFile(tmp, file)
    }
}
