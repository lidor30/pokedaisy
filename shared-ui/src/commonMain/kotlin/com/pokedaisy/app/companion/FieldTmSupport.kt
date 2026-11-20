package com.pokedaisy.app.companion

import com.pokedaisy.app.companion.data.*
import kotlin.concurrent.Volatile

/** Existing retail/rebuild TM call selection and code verification, shared by both game hosts. */
class FieldTmSupport(private val read: (Long, Int) -> ByteArray) {
    @Volatile private var cached: Pair<String, TmFns?>? = null

    fun get(snapshot: SnapshotView, code: String?, revision: Int): TmFns? {
        if (code == null || !snapshot.hasData) return null
        val rebuild = runningEmeraldHack != null
        val key = "$code/$revision/${snapshot.game}/$romGameCode/$rebuild"
        cached?.let { if (it.first == key) return it.second }
        val candidates = tmCandidatesFor(snapshot.game, code, revision, rebuild)
        val found = runCatching { candidates.firstOrNull { tmCodeMatches(it, read) } }.getOrElse { return null }
        cached = key to found
        return found
    }
}
