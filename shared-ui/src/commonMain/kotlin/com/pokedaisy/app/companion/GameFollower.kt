package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.*
import com.pokedaisy.app.game.FollowerCore
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import okio.Path

/** Owns one game's lead sprite and original field-renderer resend policy. Asset and national-number
 * work run off the core thread; frames consume only a cached sprite and cached enabled choice. */
class GameFollower(
    private val prefs: Prefs,
    private val core: FollowerCore,
    filesDir: Path,
    private val allowed: () -> Boolean,
    private val prepareAssets: ((() -> Boolean) -> Unit) = {},
    private val available: () -> Set<Int> = { MonArt.available(filesDir) },
    private val load: (Int) -> MonArt.Mon? = { MonArt.load(filesDir, it) },
    private val national: (PokedexTables, Int) -> Int = PokedexSource::nationalOf,
    delivery: CoroutineDispatcher = Dispatchers.Main,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + delivery)
    private val closed = atomic(false)
    private val generation = atomic(0)
    private val enabled = atomic(prefs.follower)
    private val desired = atomic<MonArt.Mon?>(null)
    private val latest = atomic<SnapshotView?>(null)
    private var wanted: Triple<Int?, PokedexTables?, Set<Int>>? = null
    private var loading: Job? = null
    private var sent: MonArt.Mon? = null // game thread
    private var shown = false
    private var frames = 0
    var ready by mutableStateOf(false)
        private set
    val supported get() = !closed.value && allowed()

    init {
        scope.launch {
            try { withContext(worker) { prepareAssets { !closed.value } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { Logs.w("follower assets failed", error) }
            if (!closed.value) { wanted = null; refresh() }
        }
    }

    fun set(on: Boolean) {
        if (closed.value) return
        prefs.follower = on; enabled.value = on
        wanted = null
        if (!on) { generation.incrementAndGet(); loading?.cancel(); desired.value = null }
        else refresh()
    }

    /** Sampler delivery thread. The first non-egg lead retains Android's existing selection policy. */
    fun observe(snapshot: SnapshotView) {
        if (closed.value) return
        latest.value = snapshot
        refresh()
    }

    private fun refresh() {
        scope.launch {
            val ids = try { withContext(worker) { available() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { Logs.w("follower inventory failed", error); emptySet() }
            if (closed.value) return@launch
            ready = ids.isNotEmpty()
            latest.value?.let { observeNow(it, ids) }
        }
    }

    private fun observeNow(snapshot: SnapshotView, ids: Set<Int>) {
        val nextEnabled = prefs.follower
        if (enabled.getAndSet(nextEnabled) != nextEnabled) wanted = null
        val species = snapshot.party.firstOrNull { !it.isEgg }?.species
        val tables = snapshot.pokedex?.tables
        val key = Triple(species, tables, ids)
        if (!supported || !nextEnabled || species == null || tables == null) {
            generation.incrementAndGet(); loading?.cancel(); desired.value = null; wanted = null
            return
        }
        if (wanted == key) return
        wanted = key
        val version = generation.incrementAndGet()
        loading?.cancel()
        loading = scope.launch {
            val sprite = withContext(worker) {
                runCatching { national(tables, species).takeIf { it in 1..MonArt.LAST && it in ids }?.let(load) }
                    .onFailure { Logs.w("follower lead failed", it) }.getOrNull()
            }
            val current = latest.value
            if (!closed.value && version == generation.value && enabled.value && supported &&
                current?.party?.firstOrNull { !it.isEgg }?.species == species && current?.pokedex?.tables == tables)
                desired.value = sprite
        }
    }

    /** Game thread, after field/menu observation. Graphics copy only when the desired sprite changes. */
    fun step(field: Boolean) {
        val sprite = desired.value.takeIf { !closed.value && enabled.value && allowed() }
        if (sprite !== sent) { core.setFollower(sprite?.frames, sprite?.owPal); sent = sprite }
        val show = field && sprite != null
        if (show != shown || show && ++frames % 60 == 0) { core.showFollower(show); shown = show }
    }

    /** A restarted core begins with no follower. */
    fun reset() { sent = null; shown = false; frames = 0 }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet(); desired.value = null; scope.cancel(); ready = false
    }

    companion object {
        fun supported(gba: Boolean, blocked: Boolean, fieldReadable: Boolean, game: GameKind?): Boolean =
            gba && !blocked && fieldReadable && game !in OWN_FOLLOWERS
        private val OWN_FOLLOWERS = setOf(GameKind.HEART_AND_SOUL, GameKind.SOULGOLD, GameKind.ROWE,
            GameKind.EMERALD_SEAGLASS, GameKind.RECHARGED_YELLOW)
    }
}
