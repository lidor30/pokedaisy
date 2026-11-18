package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.pokedaisy.app.companion.data.*
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import okio.Path
import kotlin.time.TimeSource
import kotlin.time.TimeMark

/** Shared option discovery and desired state. [tick], [coreStarted] and [close] run between frames;
 * the host retains frame scheduling and co-op guards. UI setters never patch the core. */
class GameOptions internal constructor(
    private val core: RomOptionPatcher,
    private val allowed: () -> Boolean,
    private val publish: CoroutineDispatcher,
    private val capLookup: (SnapshotView) -> LevelCap.Cap?,
    private val bossesLookup: (SnapshotView) -> Boolean?,
) {
    constructor(core: RomOptionPatcher, allowed: () -> Boolean, publish: CoroutineDispatcher = Dispatchers.Main) :
        this(core, allowed, publish, LevelCap::next, LevelCap::hasBosses)

    data class Discovery(val wildFlag: Long = 0, val sites: List<ReusableTms.Site> = emptyList(),
                         val text: InstantText.Patch? = null, val hm: HmFree.Patch? = null,
                         val exp: ExpShare.Found? = null, val cap: LevelCap.Found? = null,
                         val center: PokemonCenter.Found? = null,
                         /** A Game Boy cart's LOW HP BEEP off ([GbGamePatches]: the GBA's comes from QuietPatches, by the host). */
                         val gbBeep: InstantText.Patch? = null,
                         val gbPoison: InstantText.Patch? = null)
    private data class Patches(val sites: List<ReusableTms.Site> = emptyList(), val tms: Boolean = false,
                               val text: InstantText.Patch? = null, val instant: Boolean = false,
                               val hm: HmFree.Patch? = null, val full: ExpShare.Found? = null,
                               val cap: LevelCap.Found? = null, val level: Int = 0,
                               val beep: InstantText.Patch? = null, val poison: InstantText.Patch? = null)
    private data class Inputs(val autoRun: Boolean = false, val indoors: Boolean = false, val wild: Int = 100)
    private val desired = atomic(Patches())
    private val inputs = atomic(Inputs())
    private val found = atomic(Discovery())
    private val wildAvailable = atomic(false)
    private val closed = atomic(false)
    private val generation = atomic(0)
    private var revision by mutableIntStateOf(0)
    private var applied: Patches? = null
    private var appliedAny = false
    private var scan: Job? = null
    private var capJob: Job? = null
    private val capBusy = atomic(false)
    private var capAt: TimeMark? = null
    private val capEnabled = atomic(false)
    private val capNow = atomic<LevelCap.Cap?>(null)
    private val capBosses = atomic<Boolean?>(null)
    private val expMode = atomic(ExpShare.Mode.OFF)
    private val expMetadata = atomic<ExpShare.Found?>(null)
    private val partyMask = atomic(0)
    /** A Game Boy Gen 2 game's battle byte (wBattleMode) for [markExp]'s gate; 0 = none known. */
    private val gbBattle = atomic(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val discovery: Discovery get() { revision; return found.value }
    val wildFlagFound: Boolean get() { revision; return allowed() && wildAvailable.value }
    val instantTextSupported get() = allowed() && discovery.text != null
    val reusableTmsSupported get() = allowed() && discovery.sites.isNotEmpty()
    val hmFreeSupported get() = allowed() && discovery.hm != null
    val expShareSupported get() = allowed() && discovery.exp != null
    val expShareActive get() = !closed.value && allowed() && expMetadata.value != null && expMode.value != ExpShare.Mode.OFF
    /** A DS game's EXP SHARE ([ExpShare.Found.ds]: its native side marks the battle and patches FULL EXP itself, each
     * frame): the mode's id while it's on, else 0; [expSharePartyMask] the party it marks. */
    val dsExpShareMode: Int get() = if (expShareActive && expMetadata.value?.ds == true) expMode.value.id else 0
    val expSharePartyMask: Int get() = partyMask.value
    val levelCapSupported get() = allowed() && discovery.cap != null && capBosses.value != false
    val levelCapNow: LevelCap.Cap? get() { revision; return capNow.value }
    val dirty get() = !closed.value && if (allowed()) desired.value != applied else appliedAny

    var autoRun: Boolean
        get() = inputs.value.autoRun
        set(v) { inputs.update { it.copy(autoRun = v) } }
    var runIndoors: Boolean
        get() = inputs.value.indoors
        set(v) { inputs.update { it.copy(indoors = v) } }
    var wildEncounters: Int
        get() = inputs.value.wild
        set(v) { inputs.update { it.copy(wild = if (v == 0) 0 else 100) } }

    fun setReusableTms(sites: List<ReusableTms.Site>, on: Boolean) {
        desired.update { it.copy(sites = sites.toList(), tms = on) }
    }
    fun setInstantText(patch: InstantText.Patch?, on: Boolean) {
        desired.update { it.copy(text = patch, instant = on) }
    }
    fun setHmFree(patch: HmFree.Patch?, on: Boolean) {
        if (closed.value) return
        desired.update { it.copy(hm = patch.takeIf { on }) }
        HmFree.active = !closed.value && allowed() && on && patch != null
    }
    fun setFullExp(found: ExpShare.Found?, on: Boolean) {
        desired.update { it.copy(full = found.takeIf { on }) }
    }
    fun setLevelCap(found: LevelCap.Found?, level: Int) {
        desired.update { it.copy(cap = found.takeIf { level in 1..99 }, level = level.takeIf { it in 1..99 } ?: 0) }
    }
    /** LOW HP BEEP / FIELD POISON off: the ROM's patches ([QuietPatches.find], by the host) and which go in. */
    fun setQuietPatches(found: QuietPatches.Found?, beepOff: Boolean, poisonOff: Boolean) {
        desired.update { it.copy(beep = found?.beep.takeIf { beepOff }, poison = found?.poison.takeIf { poisonOff }) }
    }
    fun setExpShare(found: ExpShare.Found?, mode: ExpShare.Mode) {
        expMetadata.value = found; expMode.value = mode
        setFullExp(found, mode == ExpShare.Mode.FULL)
    }
    /** EXP SHARE found by the host outside [start]'s GBA scan (a DS cart by its header: [ExpShare.dsFind]). */
    fun setExpFound(exp: ExpShare.Found?) {
        if (closed.value) return
        found.update { it.copy(exp = exp) }
        revision++
    }
    fun setLevelCapEnabled(on: Boolean) {
        if (capEnabled.getAndSet(on) != on) capAt = null
        setLevelCap(found.value.cap, if (on && capBosses.value != false) capNow.value?.level ?: 0 else 0)
    }

    /** Fresh samples and workers belong to this game; only native reads/writes are supplied by the hosts. */
    fun observe(snapshot: SnapshotView) {
        if (closed.value || !allowed()) return
        val gen2 = snapshot.game.isGen2
        // Gen 2 pays a held Exp. Share in a pass of its own: GiveExperiencePoints run again with the participants byte set to
        // the holders, then put back. A per-frame mark would overwrite that pass, so no mark while one is held (pokegold /
        // pokecrystal's EXP_SHARE, 0x39; a hack that numbers it otherwise only loses this guard).
        val holdsExpShare = gen2 && snapshot.party.any { !it.isEgg && it.heldItemId == GEN2_EXP_SHARE }
        partyMask.value = if (snapshot.connected && !holdsExpShare) ExpShare.maskOf(snapshot.party) else 0
        // Gen 2's battle byte, from the running game's config (Gen 1's comes with the ROM's own [ExpShare.Found.battle]).
        gbBattle.value = if (snapshot.connected && gen2) activeGen2.battleMode else 0L
        val source = found.value.cap ?: desired.value.cap ?: return
        if (!snapshot.connected || capBosses.value == false) return
        if (!capEnabled.value && capBosses.value != null) return
        if (capAt?.elapsedNow()?.inWholeMilliseconds?.let { it < 2000 } == true || !capBusy.compareAndSet(false, true)) return
        capAt = TimeSource.Monotonic.markNow()
        val ticket = generation.value
        capJob = scope.launch {
            try {
                val bosses = runCatching { bossesLookup(snapshot) }.getOrNull()
                val cap = if (capEnabled.value && bosses != false) runCatching { capLookup(snapshot) }.getOrNull() else null
                withContext(publish) {
                    if (closed.value || ticket != generation.value || !allowed()) return@withContext
                    capBosses.value = bosses; capNow.value = cap
                    setLevelCap(source, if (capEnabled.value && bosses != false) cap?.level ?: 0 else 0)
                    revision++
                }
            } finally { if (ticket == generation.value) capBusy.value = false }
        }
    }

    /** Runs between native frames, with the same party policy on both platforms. */
    fun markExp(read: (Long, Int) -> ByteArray, write: (Long, ByteArray) -> Boolean) {
        if (!expShareActive || partyMask.value == 0) return
        // A DS game's are marked by its native side ([dsExpShareMode]).
        expMetadata.value?.takeIf { !it.ds }?.let { metadata -> runCatching { ExpShare.mark(metadata, partyMask.value, read, write, gbBattle.value) } }
    }

    fun resetMetadata() {
        if (closed.value) return
        cancelDiscovery()
        found.value = Discovery(); desired.value = Patches(); wildAvailable.value = false
        expMetadata.value = null; expMode.value = ExpShare.Mode.OFF; partyMask.value = 0; gbBattle.value = 0L
        capBosses.value = null; capNow.value = null; capEnabled.value = false
        HmFree.active = false; revision++
    }

    fun settingsChanged(s: CompanionSettings) {
        autoRun = s.autoRun; runIndoors = s.runIndoors; wildEncounters = s.wildEncounters
        val metadata = found.value
        setReusableTms(metadata.sites, s.reusableTms && !s.reusableTmsBuiltIn)
        setInstantText(metadata.text, s.instantText)
        setHmFree(metadata.hm, s.hmFree)
        setExpShare(metadata.exp, s.expShare)
        setLevelCapEnabled(s.levelCap)
    }

    /** Discovery reads files off-thread; publication, availability checks and host notifications use its UI dispatcher. */
    fun start(path: Path, configureWild: (Long, Boolean) -> Boolean, ready: (Discovery) -> Unit = {}, gameBoy: Boolean = false) {
        cancelDiscovery()
        if (closed.value || !allowed()) return
        val ticket = generation.value
        scan = scope.launch {
            // A Game Boy cart: its own three options (GbGamePatches), nothing of the GBA's finders.
            val gb = if (gameBoy) runCatching { GbGamePatches.find(path) }.getOrNull() else null
            val result = if (gameBoy) Discovery(sites = gb?.tms.orEmpty(), text = gb?.text, exp = gb?.exp, gbBeep = gb?.beep, gbPoison = gb?.poison) else Discovery(
                runCatching { WildEncounters.find(path) }.getOrDefault(0L),
                runCatching { ReusableTms.find(path) }.getOrDefault(emptyList()),
                runCatching { InstantText.find(path) }.getOrNull(),
                runCatching { HmFree.find(path) }.getOrNull(),
                runCatching { ExpShare.find(path) }.getOrNull(),
                runCatching { LevelCap.find(path) }.getOrNull(),
                runCatching { PokemonCenter.find(path) }.getOrNull())
            withContext(publish) {
                if (closed.value || ticket != generation.value || !allowed()) return@withContext
                found.value = result
                desired.update { it.copy(sites = result.sites, text = result.text) }
                wildAvailable.value = configureWild(result.wildFlag, wildEncounters == 0)
                revision++
                ready(result)
            }
        }
    }

    /** A new core has no patches, even if its desired settings equal the previous run. */
    fun coreStarted() { applied = null; appliedAny = false }

    fun tick(): GameOptionPatches.Result? {
        if (closed.value) return null
        if (!allowed()) {
            if (!appliedAny) return null
            core.unpatch(); applied = null; appliedAny = false
            return GameOptionPatches.Result(false, false)
        }
        val state = desired.value
        if (state == applied) return null
        val result = GameOptionPatches.apply(core, state.sites.takeIf { state.tms }.orEmpty(), state.text.takeIf { state.instant },
            state.hm, state.full, state.cap, state.level, state.beep, state.poison)
        applied = state; appliedAny = result.tms || result.text || result.hm || result.full || result.cap || result.beep || result.poison
        Logs.i("game-option patches: text=${result.text}, reusableTms=${result.tms}")
        return result
    }

    fun fieldState(step: (Boolean, Int) -> Int, indoorsSupported: Boolean = true,
                   wildKnown: Boolean = wildAvailable.value): Int {
        if (closed.value || !allowed()) return 0
        val state = inputs.value
        return step(state.indoors && indoorsSupported, if (wildKnown) state.wild else 100)
    }
    fun keys(held: Int, field: Int, onField: Boolean, ownAutoRun: Boolean): Int =
        if (!closed.value && allowed() && autoRun) AutoRunPolicy.apply(held, field, onField, ownAutoRun) else held

    fun cancelDiscovery() {
        generation.incrementAndGet(); scan?.cancel(); scan = null
        capJob?.cancel(); capJob = null; capBusy.value = false; capAt = null
    }
    /** Retire portable work. Hosts whose engine frees the core retain its native patch cleanup. */
    fun close(restorePatches: Boolean = true) {
        if (closed.getAndSet(true)) return
        HmFree.active = false
        cancelDiscovery(); scope.cancel()
        if (restorePatches) {
            if (appliedAny) core.unpatch()
            appliedAny = false; applied = null
        }
    }
}

/** pokegold / pokecrystal's EXP_SHARE item id (constants/item_constants.asm). */
private const val GEN2_EXP_SHARE = 0x39
