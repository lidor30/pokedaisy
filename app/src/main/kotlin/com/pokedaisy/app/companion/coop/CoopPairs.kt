package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.Gen3Text
import com.pokedaisy.app.companion.data.isFile
import com.pokedaisy.app.companion.data.mkdirs
import com.pokedaisy.app.companion.data.moveTo
import com.pokedaisy.app.companion.data.readBytes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.use

/**
 * The pairings, one partner per save, in `filesDir/coop/pairs.json` (a JSON array, written to a
 * temp file and renamed). A file that doesn't read is kept aside as `pairs.json.bad` and reads as
 * empty, so it is never overwritten while unread.
 */
class CoopPairs(
    private val file: Path,
    private val read: (Path) -> String = { it.readBytes().decodeToString() },
    private val moveAside: (Path, Path) -> Boolean = { from, to -> from.moveTo(to); true },
) {
    private val lock = SynchronizedObject()

    /** Set when the file exists but could be neither read nor moved aside: nothing may be written over it. */
    private var blocked = false

    fun all(): List<CoopPair> = synchronized(lock) { load() }

    fun forSave(save: CoopSaveId): CoopPair? = synchronized(lock) { load().firstOrNull { it.ownSave == save } }

    /** False (nothing written) when that save already has a partner. Throws [IOException] while the file is unreadable. */
    fun add(pair: CoopPair): Boolean = synchronized(lock) {
        val list = loadForWrite()
        if (list.any { it.ownSave == pair.ownSave }) return@synchronized false
        store(list + pair)
        true
    }

    fun updateTicket(save: CoopSaveId, ticket: String) = synchronized(lock) {
        val list = loadForWrite()
        if (list.none { it.ownSave == save }) return@synchronized
        store(list.map { if (it.ownSave == save) it.copy(lastTicket = ticket) else it })
    }

    fun remove(save: CoopSaveId) = synchronized(lock) {
        val list = loadForWrite()
        if (list.none { it.ownSave == save }) return@synchronized
        store(list.filterNot { it.ownSave == save })
    }

    fun allowedPeers(): Set<CoopPeerId> = synchronized(lock) { load().map { it.partner.peer }.toSet() }

    /** [name] beside the pairings file. */
    private fun sibling(name: String): Path = file.parent?.div(name) ?: name.toPath()

    private fun loadForWrite(): List<CoopPair> {
        val list = load()
        if (blocked) throw IOException("the pairings file can't be read, so it won't be overwritten")
        return list
    }

    private fun load(): List<CoopPair> {
        blocked = false
        if (!file.isFile) return emptyList()
        val text = try {
            read(file)
        } catch (e: IOException) {
            blocked = true   // transient or not, the file is untouched
            return emptyList()
        }
        return try {
            Json.parseToJsonElement(text).jsonArray.map { decode(it.jsonObject) }
                .filter { it.lastTicket == null || CoopInvite.isTicket(it.lastTicket) }   // tickets of an older build are no use now
        } catch (e: IllegalArgumentException) {   // includes kotlinx's SerializationException and NumberFormatException
            keepAside()
        }
    }

    /** Moves the unreadable file to the first free `pairs.json.bad[.N]`; never overwrites one. */
    private fun keepAside(): List<CoopPair> {
        var bad = sibling(file.name + ".bad")
        var n = 1
        while (FileSystem.SYSTEM.exists(bad)) bad = sibling(file.name + ".bad." + n++)
        if (!(runCatching { moveAside(file, bad) }.getOrDefault(false))) blocked = true
        return emptyList()
    }

    private fun store(list: List<CoopPair>) {
        file.parent?.mkdirs()
        val tmp = sibling(file.name + ".tmp")
        writeDurably(tmp, JsonArray(list.map { encode(it) }).toString().encodeToByteArray())
        replaceFile(tmp, file)
    }

    private fun encode(p: CoopPair) = buildJsonObject {
        put("ownSave", p.ownSave.key)
        put("ownLabel", p.ownLabel)
        put("peer", p.partner.peer.hex)
        put("partnerName", toHex(p.partner.save.name))
        put("partnerTrainerId", p.partner.trainerId)
        put("partnerFemale", p.partner.female)
        put("partnerSave", p.partner.save.key)
        put("lastTicket", p.lastTicket)
        put("pairedAt", p.pairedAt)
    }

    private fun decode(o: JsonObject): CoopPair {
        val partnerSave = parseKey(o.string("partnerSave"))
        val partner = CoopPartner(
            peer = CoopPeerId(o.string("peer")),
            name = Gen3Text.decode(partnerSave.name, 0, 7),
            trainerId = o.value("partnerTrainerId").int,
            female = o.value("partnerFemale").boolean,
            save = partnerSave,
        )
        return CoopPair(
            ownSave = parseKey(o.string("ownSave")),
            ownLabel = o.string("ownLabel"),
            partner = partner,
            lastTicket = o["lastTicket"].let { if (it == null || it is JsonNull) null else o.string("lastTicket") },
            pairedAt = o.value("pairedAt").long,
        )
    }

    /** [key]'s value, which must be there and not an array or object (as org.json's getters asked). */
    private fun JsonObject.value(key: String): JsonPrimitive = requireNotNull(this[key]) { "no $key" }.jsonPrimitive

    private fun JsonObject.string(key: String): String = value(key).also { require(it.isString) { "$key: not a string" } }.content

    /** The inverse of [CoopSaveId.key]: `%04x%04x-` and 16 hex characters. */
    private fun parseKey(key: String): CoopSaveId {
        require(key.length == 25 && key[8] == '-') { "bad save key" }
        val name = ByteArray(8) { key.substring(9 + it * 2, 11 + it * 2).toInt(16).toByte() }
        return CoopSaveId(key.substring(0, 4).toInt(16), key.substring(4, 8).toInt(16), name)
    }
}

/** Moves [tmp] over [dest] in one step (okio's atomicMove: a rename that replaces [dest]). */
fun replaceFile(tmp: Path, dest: Path) = FileSystem.SYSTEM.atomicMove(tmp, dest)

/** Writes [bytes] to [tmp] and forces them to disk (okio's FileHandle.flush syncs), so a rename over the real file
 * never leaves an empty one after a crash. */
fun writeDurably(tmp: Path, bytes: ByteArray) {
    FileSystem.SYSTEM.openReadWrite(tmp).use { h ->
        h.resize(0)
        h.write(0, bytes, 0, bytes.size)
        h.flush()
    }
}
