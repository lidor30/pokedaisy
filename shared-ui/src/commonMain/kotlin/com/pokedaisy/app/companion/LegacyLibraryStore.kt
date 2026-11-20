package com.pokedaisy.app.companion

import kotlinx.serialization.json.*

/** Compatibility codec for the installed iOS library JSON. The model and Prefs rules are shared with Android. */
internal class LegacyLibraryStore(read: () -> String?, private val save: (String) -> Unit) : PreferenceStore {
    private var json = runCatching { read()?.let(Json::parseToJsonElement) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    private fun dates(key: String) = (json[key] as? JsonObject)?.mapNotNull { (id, value) ->
        (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { id to it }
    }?.toMap().orEmpty()
    private fun strings(value: JsonElement?) = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    private fun value(key: String): JsonPrimitive? = when (key) {
        "library_view_mode" -> JsonPrimitive(if ((json["grid"] as? JsonPrimitive)?.booleanOrNull != false) 1 else 0)
        "library_sort" -> json["sort"] as? JsonPrimitive
        "library_view" -> json["view"] as? JsonPrimitive
        "favorite_roms" -> JsonPrimitive(JsonArray(strings(json["favorites"]).map(::JsonPrimitive)).toString())
        "last_rom" -> json["last"] as? JsonPrimitive
        "recent_roms" -> JsonPrimitive((json["recent"] as? JsonArray ?: JsonArray(
            dates("played").entries.sortedByDescending { it.value }.map { JsonPrimitive(it.key) })).toString())
        else -> key.takeIf { it.startsWith("played_") }?.removePrefix("played_")?.let {
            ((json["played"] as? JsonObject)?.get(it) as? JsonPrimitive)?.longOrNull?.takeIf { time -> time >= 0 }
        }?.let(::JsonPrimitive)
    }
    override fun keys(): Set<String> = setOf("library_view_mode", "library_sort", "library_view", "favorite_roms", "last_rom", "recent_roms") +
        dates("played").keys.map { "played_$it" }
    override fun contains(key: String) = value(key) != null
    override fun getBoolean(key: String, default: Boolean) = value(key)?.booleanOrNull ?: default
    override fun getInt(key: String, default: Int) = value(key)?.intOrNull ?: default
    override fun getLong(key: String, default: Long) = value(key)?.longOrNull ?: default
    override fun getFloat(key: String, default: Float) = value(key)?.floatOrNull ?: default
    override fun getString(key: String, default: String?) = value(key)?.contentOrNull ?: default
    override fun write(values: Map<String, Any?>) {
        val updated = json.toMutableMap()
        for ((key, value) in values) when (key) {
            "library_view_mode" -> updated["grid"] = JsonPrimitive(value == 1)
            "library_sort" -> updated["sort"] = JsonPrimitive(value as Int)
            "library_view" -> updated["view"] = JsonPrimitive(value as Int)
            "favorite_roms", "recent_roms" -> updated[if (key == "favorite_roms") "favorites" else "recent"] =
                Json.parseToJsonElement(value as String)
            "last_rom" -> if (value == null) updated.remove("last") else updated["last"] = JsonPrimitive(value as String)
            else -> if (key.startsWith("played_")) {
                val played = (updated["played"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                played[key.removePrefix("played_")] = JsonPrimitive(value as Long)
                updated["played"] = JsonObject(played)
            }
        }
        commit(JsonObject(updated))
    }
    val added = LibraryAddedDates(read = {
        val initial = json["added_initial"] as? JsonObject
        dates("added").mapValues { (id, time) -> LibraryAddedDates.Entry(time,
            initial = (initial?.get(id) as? JsonPrimitive)?.booleanOrNull == true) }
    }, write = { entries ->
        commit(JsonObject(json + mapOf(
            "added" to JsonObject(entries.mapValues { JsonPrimitive(it.value.millis) }),
            "added_initial" to JsonObject(entries.filterValues { it.initial }.mapValues { JsonPrimitive(true) }))))
    }, firstAsInitial = false)

    private fun commit(value: JsonObject) { save(value.toString()); json = value }
}
