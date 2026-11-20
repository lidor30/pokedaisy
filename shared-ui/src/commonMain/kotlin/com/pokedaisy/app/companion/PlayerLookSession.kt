package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.GameTitleCatalog
import com.pokedaisy.app.Prefs
import com.pokedaisy.app.companion.data.AppearanceAssets
import com.pokedaisy.app.companion.data.Logs
import com.pokedaisy.app.companion.data.LookEngine
import com.pokedaisy.app.companion.data.MonArt
import com.pokedaisy.app.companion.data.PlayerAppearance
import com.pokedaisy.app.companion.data.PlayerLook
import com.pokedaisy.app.companion.data.PlayerLookAssets
import com.pokedaisy.app.companion.data.PokedexTables
import com.pokedaisy.app.companion.data.PokemonCostumes
import com.pokedaisy.app.companion.data.RideVehicle
import com.pokedaisy.app.companion.data.SnapshotView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/** Owns a running ROM's appearance choices. Call on the main thread. */
class PlayerLookSession internal constructor(
    private val prefs: Prefs,
    files: Path,
    mainDispatcher: CoroutineDispatcher,
    private val workerDispatcher: CoroutineDispatcher,
    private val prepareCatalog: (
        Path, LookEngine?, String, Boolean, () -> List<AppearanceAssets.Source>, () -> Boolean,
    ) -> PlayerLookAssets.Catalog? = { rom, engine, code, prepareFollowers, library, current ->
        val running = AppearanceAssets.file(rom)
        if (engine != null) {
            PlayerLookAssets(files, GameTitleCatalog::isRetail).prepare(running, engine, code, library, current)
        } else {
            if (prepareFollowers) AppearanceAssets(files).prepareFollowers(running, library, current)
            null
        }
    },
    private val offeredCostumes: (PokedexTables, List<Int>) -> Map<Int, String> = { tables, species ->
        val available = MonArt.available(files)
        PokemonCostumes.party(tables, species).filterKeys { it in available }
    },
    private val prepareCostume: (PlayerAppearance, PlayerAppearance.CostumeRequest) -> PlayerLook.Patch? = { appearance, request ->
        appearance.prepareCostume(files, request)
    },
    private val onPatch: (PlayerLook.Patch?) -> Unit,
) {
    constructor(prefs: Prefs, files: Path, onPatch: (PlayerLook.Patch?) -> Unit) :
        this(prefs, files, Dispatchers.Main, Dispatchers.Default, onPatch = onPatch)

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val scanLock = Mutex()
    val vehicle get() = RideVehicle.of(prefs.rideVehicleId)
    private var appearance by mutableStateOf<PlayerAppearance?>(null)
    private var key: String? = null
    private var female by mutableStateOf<Boolean?>(null)
    private var selection by mutableStateOf<PlayerAppearance.Selection?>(null)
    val supported get() = appearance != null
    val isFemale get() = female == true
    val rivalSupported get() = appearance?.rivalSupported == true
    val wornHero get() = selection?.wornHero
    private var party: Pair<PokedexTables, List<Int>>? = null
    private var scanJob: Job? = null
    private var partyJob: Job? = null
    private var costumeInventoryReady = false
    private var costumeChoices by mutableStateOf<Map<Int, String>>(emptyMap())
    private var costumeJob: Job? = null

    val choices get() = selection?.heroes.orEmpty().map { it.name } + costumeChoices.keys.map { "MON:$it" }
    val selected get() = selection?.let { it.pickedCostume?.let { national -> "MON:$national" } ?: it.pickedHero?.name }
    fun costumeLabel(name: String): String? = MonArt.monOf(name)?.let { costumeChoices[it] ?: "#$it" }
    val textChoices get() = appearance?.textStyles.orEmpty().map { it.name }
    val textSelected get() = selection?.textStyle?.name
    val own get() = selection?.own?.name

    fun load(
        rom: Path,
        code: String,
        romKey: String,
        initialFemale: Boolean? = null,
        prepareFollowers: Boolean = false,
        library: () -> List<AppearanceAssets.Source>,
    ) {
        if (!scope.isActive) return
        // Telemetry may already have arrived before the first load. A running ROM's observations
        // belong to that ROM, so discard them when replacing it.
        val earlyParty = party.takeIf { key == null }
        val earlyFemale = female.takeIf { key == null }
        clear()
        party = earlyParty
        female = earlyFemale ?: initialFemale
        key = romKey

        val engine = PlayerLook.engineFor(code)
        if (engine == null && !prepareFollowers) return
        scanJob = scope.launch {
            try {
                val catalog = withContext(workerDispatcher) {
                    val worker = currentCoroutineContext()
                    // A canceled scan can still be finishing a cache write.
                    scanLock.withLock { prepareCatalog(rom, engine, code, prepareFollowers, library) { worker.isActive } }
                }
                appearance = catalog?.let(::PlayerAppearance)
                costumeInventoryReady = catalog != null || engine == null
                refreshParty()
                apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: scan failed", error)
            }
        }
    }

    fun observe(snapshot: SnapshotView) {
        if (!scope.isActive || !snapshot.connected || snapshot.blocked != null) return
        val nextParty = snapshot.pokedex?.tables?.let { tables -> tables to snapshot.party.map { it.species } }
        val gender = snapshot.playerGender
        if (gender in 0..1 && female != (gender == 1)) {
            female = gender == 1
            apply()
        }
        if (nextParty != party) {
            party = nextParty
            refreshParty()
        }
    }

    private fun refreshParty() {
        partyJob?.cancel()
        if (!costumeInventoryReady) return
        val input = party
        if (input == null) {
            if (costumeChoices.isNotEmpty()) {
                costumeChoices = emptyMap()
                apply()
            }
            return
        }
        partyJob = scope.launch {
            try {
                val offered = withContext(workerDispatcher) { offeredCostumes(input.first, input.second) }
                if (costumeChoices != offered) {
                    costumeChoices = offered
                    apply()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: costume inventory failed", error)
            }
        }
    }

    fun select(look: String?) {
        val currentKey = key ?: return
        if (look != null && look !in choices) return
        prefs.setPlayerLookFor(currentKey, look)
        apply()
    }

    fun selectText(style: String?) {
        val currentKey = key ?: return
        if (style != null && style !in textChoices) return
        prefs.setTextLookFor(currentKey, style)
        apply()
    }

    fun selectRide(vehicle: RideVehicle) {
        if (!supported) return
        prefs.rideVehicleId = vehicle.id
        apply()
    }

    fun apply() {
        val current = appearance ?: return
        val currentKey = key ?: return
        val next = current.select(
            female, prefs.playerLookFor(currentKey), costumeChoices.keys, prefs.rivalLook,
            vehicle, prefs.textLookFor(currentKey),
        )
        selection = next
        PlayerLook.worn.value = next.wornHero
        onPatch(next.patch)
        if (next.pickedCostume == null) costumeJob?.cancel()
        next.costumeToPrepare?.let { prepareCostumeAsync(current, it) }
    }

    private fun prepareCostumeAsync(current: PlayerAppearance, request: PlayerAppearance.CostumeRequest) {
        costumeJob?.cancel()
        costumeJob = scope.launch {
            val patch = try {
                withContext(workerDispatcher) { prepareCostume(current, request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logs.w("player look: costume patch failed", error)
                null
            }
            if (appearance === current && current.completeCostume(request, patch)) apply()
        }
    }

    fun clear() {
        if (scope.isActive) reset()
    }

    fun close() {
        if (!scope.isActive) return
        scope.cancel()
        reset()
    }

    private fun reset() {
        scanJob?.cancel()
        partyJob?.cancel()
        costumeJob?.cancel()
        scanJob = null
        partyJob = null
        costumeJob = null
        key = null
        female = null
        party = null
        costumeInventoryReady = false
        appearance = null
        costumeChoices = emptyMap()
        selection = null
        PlayerLook.worn.value = null
        onPatch(null)
    }
}
