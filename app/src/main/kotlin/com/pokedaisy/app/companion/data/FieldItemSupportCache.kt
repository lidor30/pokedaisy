package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/** The same verified per-ROM field tables for the two hosts. Failed reads can be retried after boot. */
class FieldItemSupportCache(private val reader: MemoryReader = InProcessReader) {
    @Volatile private var cached: Pair<String, FieldItemSupport?>? = null

    fun get(snapshot: SnapshotView, code: String, revision: Int, configured: FieldItemFns?): FieldItemSupport? {
        // A recognized map alone is not proof of gameplay: the title/intro can expose those defaults too.
        if (!snapshot.hasData || snapshot.party.isEmpty()) return null
        val game = snapshot.game
        val candidates = fieldItemsCandidatesForGame(game, code, revision, configured).ifEmpty { return null }
        val key = "$code/$revision/$game/$romGameCode/" + candidates.joinToString(",") { "${it.varGet}/${it.items}" }
        cached?.let { (k, support) -> if (k == key) return support }
        val support = runCatching {
            candidates.firstNotNullOfOrNull { fieldItemSupport(it, reader::readCoreMemory) }
        }.getOrElse { return null }
        cached = key to support
        return support
    }
}
