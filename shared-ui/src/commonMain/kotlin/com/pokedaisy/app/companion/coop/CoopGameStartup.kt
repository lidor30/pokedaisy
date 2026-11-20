package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.RetailDumps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.IOException
import okio.Source

/** Keeps the frame gate closed from lookup through adoption. Called on the host's UI dispatcher. */
class CoopGameStartup(private val pending: (Boolean) -> Unit) {
    private val resolving = MutableStateFlow(false)
    private var lookup: Job? = null
    private var generation = 0

    internal suspend fun awaitLookup() {
        resolving.first { !it }
    }

    private fun setPending(on: Boolean) {
        pending(on)
        resolving.value = on
    }

    fun <T> start(scope: CoroutineScope, resolve: suspend () -> T, adopt: (T) -> Unit) {
        val current = ++generation
        lookup?.cancel()
        setPending(true)
        lookup = scope.launch {
            try {
                val result = resolve()
                if (generation == current) adopt(result)
            } finally {
                if (generation == current) setPending(false)
            }
        }
    }

    fun cancel() {
        generation++
        val previous = lookup
        lookup = null
        previous?.cancel()
        setPending(false)
    }
}

/** One lookup supplies the endpoint, story lock, joined battles and presence. */
class CoopGameSelection internal constructor(
    val romSha1: String?,
    internal val save: CoopSaveId?,
    internal val pair: CoopPair?,
    internal val female: Boolean,
) {
    fun decide(cheatsLoaded: Boolean): CoopGameDecision = CoopGameDecision(
        this, CoopEndpointSession.gate(romSha1, save, pair != null, cheatsLoaded),
    )
}

class CoopGameDecision internal constructor(
    internal val selection: CoopGameSelection,
    val reason: CoopOffReason?,
) {
    val pair: CoopPair? get() = selection.pair.takeIf { reason == null }
}

/** A trimmed retail dump counts as the retail ROM. Run the reads off the UI dispatcher. */
fun coopRetailSha1(code: String?, revision: Int, size: Long, sha1: () -> String?, source: () -> Source): String? {
    if (!CoopEndpointSession.mayBeRetailEmerald(code, size)) return null
    val actual = sha1()
    if (actual == null || actual == EMERALD_COOP_SHA1) return actual
    return try {
        RetailDumps.matchStream(code, revision, size, source) ?: actual
    } catch (e: IOException) {
        coopLog("couldn't check the Emerald ROM: ${e.message}")
        actual
    }
}
