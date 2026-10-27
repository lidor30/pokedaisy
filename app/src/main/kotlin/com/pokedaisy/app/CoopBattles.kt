package com.pokedaisy.app

import com.pokedaisy.app.companion.coop.CoopLink
import com.pokedaisy.app.companion.coop.CoopState
import com.pokedaisy.app.companion.coop.EMERALD_COOP_SHA1
import com.pokedaisy.app.companion.data.coop.CoopBattleChannel
import com.pokedaisy.app.companion.data.coop.CoopBattleMsg
import com.pokedaisy.app.companion.data.coop.CoopBattleWire
import com.pokedaisy.app.companion.data.coop.CoopCore
import com.pokedaisy.app.companion.data.coop.CoopHostUi
import com.pokedaisy.app.companion.data.coop.CoopIdentity
import com.pokedaisy.app.companion.data.coop.CoopJoinRules
import com.pokedaisy.app.companion.data.coop.CoopLinkEngine
import com.pokedaisy.app.companion.data.coop.CoopLinkEnv
import com.pokedaisy.app.companion.data.coop.CoopLinkFlow
import com.pokedaisy.app.companion.data.coop.CoopPartnerUi
import com.pokedaisy.app.companion.data.coop.CoopPresenceLink
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.CoopJoinPromptState
import com.pokedaisy.app.companion.ui.CoopOverlayState
import com.pokedaisy.app.companion.ui.MenuKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Emerald co-op joined battles in the running game: every joined battle is the game's own link battle on both devices (the
 * link bridge, [CoopLinkFlow]), hooked into the engine's frame loop, over plan 2's link, with plan 5's CoopGuards for holds
 * and state blocks. The logic lives in [CoopBattleGlue] (plain Kotlin, tested with fakes); [CoopBattles] holds the one live
 * instance; the activity and the engine only call it. Everything is behind FeatureFlags.coop, a link and retail English
 * Emerald's SHA-1: for anyone else the engine's hook is null and nothing here runs.
 */

/** What the engine asks of a hook every tick (see EmulatorEngine.coopBattles). Emu thread. */
interface CoopFrameHook {
    /** The keys for the next frame ([keys] = the player's own); null = no frame now. Called every tick, held or not. */
    fun beforeFrame(keys: Int): Int?

    /** A frame ran with the keys [beforeFrame] gave. */
    fun afterFrame()
}

/** The engine as the glue sees it. */
interface CoopEmu {
    /** Runs [job] on the emu thread between frames (a held game too). */
    fun post(job: () -> Unit)
    val running: Boolean
}

/** What the glue needs from the app: the game's RAM reads, the player's facts, the save backup. Emu thread unless said. */
interface CoopGlueEnv {
    val range: Int
    fun mySpot(): CoopJoinRules.Spot?
    fun partnerSpot(): CoopJoinRules.Spot?
    fun hostParty(): List<ByteArray>
    fun foeSpecies(): Int
    fun partnerName(): String
    fun identity(): CoopIdentity
    /** The running save copied to the backups folder; false if the copy failed (nothing is written to the save then). */
    fun beforeSave(): Boolean
    /** The co-op busy state changed (RetroAchievements idle). */
    fun busyChanged(busy: Boolean) {}
    /** The story spot the player stands at (or waits parked at): [CoopJoinRules.StoryAt.NONE] when none. */
    fun storyAt(): CoopJoinRules.StoryAt = CoopJoinRules.StoryAt.NONE
    /** The save is in "play together only" mode (a story battle dropped for good blacks the player out). */
    fun playTogetherOnly(): Boolean = false
    /** The pair the partner's un-acknowledged catches belong to, and where they are kept (null = in memory only). */
    fun pairKey(): String = ""
    fun catchStore(): com.pokedaisy.app.companion.data.coop.CoopCatchStore? = null
    fun log(text: String) {}
}

private const val STATUS_MS = 1_000L
private const val SPOTS_MS = 250L
private const val STOP_WAIT_MS = 2_500L

/**
 * One device's co-op battle side: the link flow (the join offer, the link battle, the after-battle changes), the STATUS beat,
 * the messages (hopped onto the emu thread), the nearby spots, CoopGuards and the overlays.
 */
class CoopBattleGlue(
    private val core: CoopCore,
    private val channel: CoopBattleChannel,
    private val emu: CoopEmu,
    private val clock: () -> Long,
    val ui: CoopOverlayState,
    private val env: CoopGlueEnv,
    private val translate: (String) -> String = { tr(it) },
    private val stopWaitMs: Long = STOP_WAIT_MS,
    /** The native link bridge: every joined battle runs through it ([flow]). */
    private val linkEngine: CoopLinkEngine,
) : CoopFrameHook {
    private val joinState = CoopJoinPromptState()

    private val hostUi = object : CoopHostUi {
        override fun asking(partner: String, secondsLeft: Int) { ui.hostWait = partner to secondsLeft }
        override fun linkWait(secondsLeft: Int) { ui.linkWait = env.partnerName() to secondsLeft }
        override fun notice(text: String) { ui.showNotice(translate(text)) }
        override fun clear() { ui.hostWait = null; ui.linkWait = null }
    }

    private val partnerUi = object : CoopPartnerUi {
        override fun prompt(offer: CoopBattleMsg.Offer, secondsLeft: Int) {
            joinState.hostName = env.partnerName()
            joinState.kind = offer.kind
            joinState.secondsLeft = secondsLeft
            if (ui.join !== joinState) { joinState.cursorJoin = true; ui.join = joinState }
        }
        override fun linkWait(secondsLeft: Int) { ui.linkWait = env.partnerName() to secondsLeft }
        override fun notice(text: String) { ui.showNotice(translate(text)) }
        override fun clear() { ui.join = null; ui.linkWait = null }
    }

    private val linkEnv = object : CoopLinkEnv {
        override fun selfReady() = ready
        override fun foeSpecies() = env.foeSpecies()
        override fun party() = env.hostParty()
        override fun partnerName() = env.partnerName()
        override fun identity() = env.identity()
        override fun storyAt() = env.storyAt()
        override fun playTogetherOnly() = env.playTogetherOnly()
        override fun pairKey() = env.pairKey()
        override fun log(text: String) = env.log(text)
    }

    /** A joined battle as a link battle, from the join prompt to the result. */
    val flow = CoopLinkFlow(
        core, linkEngine, channel, clock, hostUi, partnerUi, { env.beforeSave() }, linkEnv,
        store = env.catchStore() ?: com.pokedaisy.app.companion.data.coop.MemoryCatchStore(),
    )

    @Volatile private var stopping = false
    private var ready = false
    private var lastStatus = -STATUS_MS
    private var lastProbe = -STATUS_MS
    private var sentFree = false
    private var sentReady = false
    private var lastSpots = -SPOTS_MS
    private var busyNow = false

    /** From the offer's hold (host) / the answered prompt (partner) to the results saved. */
    val busy: Boolean get() = flow.busy

    /** LOAD STATE and its kin are refused while a co-op battle runs (CoopGuards says the same to the engine). */
    fun loadStateBlocked(): Boolean = busy

    init {
        ui.onJoin = { emu.post { flow.answerPrompt(true) } }
        ui.onDecline = { emu.post { flow.answerPrompt(false) } }
        ui.onCancelAsk = { emu.post { flow.cancelAsking() } }
    }

    // --- messages and the link: arrive on the link's thread, handled on the emu thread ---

    /** One battle message from the link ([type] 0x0200-0x02FF); anything undecodable is dropped. */
    fun onReceived(type: Int, payload: ByteArray) {
        val m = CoopBattleWire.decode(type, payload) ?: return
        if (m is CoopBattleMsg.LinkBlock) { flow.onMessage(m); return }   // thread-safe queue, drained on the emu thread
        emu.post { if (!stopping) flow.onMessage(m) }
    }

    /** The link state last seen, also while stopping (a sleep drops the changes below; [resume] replays this one). */
    @Volatile private var linkUp = false

    fun onLinkState(connected: Boolean) {
        linkUp = connected
        emu.post { if (!stopping) flow.linkChanged(connected) }
    }

    // --- the frame hook ---

    override fun beforeFrame(keys: Int): Int? {
        if (stopping) return if (busy) null else keys
        val now = clock()
        if (now - lastSpots >= SPOTS_MS) {
            lastSpots = now
            flow.mySpot = env.mySpot(); flow.partnerSpot = env.partnerSpot(); flow.range = env.range
            storyNow = env.storyAt().let { if (it.beaten) 0 else it.eventId }
        }
        status(now)
        // The link battle runs the game on its own keys: the player's, always.
        flow.beforeFrame()
        syncGuards()
        return keys
    }

    override fun afterFrame() {
        if (stopping) return
        flow.afterFrame()   // the block pump, after EVERY frame (the bridge's end steps ride it)
    }

    /** STATUS on change and every second: free = on the field / START menu, ready = co-op installs (no PLAYER LOOK / TEXT LOOK). */
    private fun status(now: Long) {
        if (!channel.connected) { lastStatus = -STATUS_MS; return }
        val b = busy
        val free = !b && core.partnerFree()
        // The install probe: once a second and only on a free field / START menu (never into a battle's running code).
        if (free && now - lastProbe >= STATUS_MS) {
            lastProbe = now
            val was = ready
            ready = linkEngine.install()
            if (ready != was) env.log("install probe: ready $was -> $ready (${linkEngine.refused()})")
        }
        val rdy = ready && !b
        val story = storyNow
        if (free != sentFree || rdy != sentReady || story != sentStory || now - lastStatus >= STATUS_MS) {
            lastStatus = now; sentFree = free; sentReady = rdy; sentStory = story
            val (t, p) = CoopBattleWire.encode(CoopBattleMsg.Status(free, rdy, story))
            channel.send(t, p)
        }
    }

    private var sentStory = 0
    private var storyNow = 0

    private fun syncGuards() {
        CoopGuards.hold(CoopGuards.Hold.BATTLE, flow.holdGame)
        val b = busy
        CoopGuards.blockStates(CoopGuards.StateBlock.BATTLE, b || flow.armed)
        if (b != busyNow) { busyNow = b; env.busyChanged(b) }
    }

    /**
     * The app is leaving (or sleeping, or restarting): the game put back before any state is written. UI thread; waits for the
     * emu thread. False = the game could not be confirmed put back (the emu thread did not answer, or a step threw): the caller
     * must write no state.
     */
    fun stopNow(): Boolean {
        if (stopping) return stopOk
        stopping = true
        val wasBusy = busy
        val job = {
            try { flow.stopNow(); clearOverlays(); syncGuards() }
            catch (t: Throwable) { stopOk = false; env.log("stop failed: $t") }
        }
        if (!emu.running) { job(); return stopOk }
        val done = CountDownLatch(1)
        emu.post { try { job() } finally { done.countDown() } }
        if (!done.await(stopWaitMs, TimeUnit.MILLISECONDS)) { if (wasBusy) stopOk = false; env.log("stop: the emu thread did not answer") }
        return stopOk
    }
    @Volatile private var stopOk = true

    /** After a sleep: battles may start again (nothing was running: [stopNow] ended it). */
    fun resume() {
        stopping = false; stopOk = true
        val up = linkUp   // changes during the sleep were dropped: re-send the current state
        emu.post { if (!stopping) flow.linkChanged(up) }
    }

    /** The glue goes (the game closed, the link detached): holds and blocks off. */
    fun release() {
        clearOverlays()
        CoopGuards.hold(CoopGuards.Hold.BATTLE, false)
        CoopGuards.blockStates(CoopGuards.StateBlock.BATTLE, false)
        if (busyNow) { busyNow = false; env.busyChanged(false) }
    }

    private fun clearOverlays() {
        ui.join = null; ui.hostWait = null; ui.linkWait = null
    }

    /** A game button (UI thread) while [CoopOverlayState.takesKeys]: true = it was the overlay's. */
    fun takeKey(k: MenuKey): Boolean = !stopping && ui.takesKeys && ui.key(k)
}

/** Plan 2's link as the battle code sees it: message types 0x0200-0x02FF only. */
class CoopLinkChannel(private val link: CoopLink) : CoopBattleChannel {
    override val connected: Boolean get() = link.state.value is CoopState.Connected

    override fun send(type: Int, payload: ByteArray): Boolean {
        if (type !in BATTLE_TYPES) return false
        return try { link.send(type, payload) } catch (e: IllegalArgumentException) { false }
    }

    override val received: Flow<Pair<Int, ByteArray>> =
        link.messages.filter { it.type in BATTLE_TYPES }.map { it.type to it.payload }

    companion object { val BATTLE_TYPES = 0x0200..0x02FF }
}

/** Plan 3's presence over the same link (type 0x0100): plain 20-byte updates. */
class CoopPresenceChannel(private val link: CoopLink) : CoopPresenceLink {
    override fun send(bytes: ByteArray) { try { link.send(PRESENCE_TYPE, bytes) } catch (e: IllegalArgumentException) {} }
    override val received: Flow<ByteArray> = link.messages.filter { it.type == PRESENCE_TYPE }.map { it.payload }

    companion object { const val PRESENCE_TYPE = 0x0100 }
}

/** The app side [CoopBattles.attach] needs: the engine, the overlays, the player's facts. Implemented by the activity. */
interface CoopApp {
    /** FeatureFlags.coop. */
    val coopOn: Boolean
    val emu: CoopEmu
    val overlay: CoopOverlayState
    val env: CoopGlueEnv
    fun core(): CoopCore
    /** Puts the hook into the engine (null takes it out). */
    fun setHook(hook: CoopFrameHook?)
}

/** The one live co-op battle glue, if any. */
object CoopBattles {
    @Volatile private var glue: CoopBattleGlue? = null
    private var scope: CoroutineScope? = null

    /** A co-op battle is running on this device (host from its offer, partner from its JOIN, to the results saved). */
    val busy: Boolean get() = glue?.busy == true

    fun loadStateBlocked(): Boolean = glue?.loadStateBlocked() == true

    /** The lock's story hold at [eventId] waits for the join decision of that story battle (the offer, its prompt, its answer). */
    fun storyPending(eventId: Int): Boolean = glue?.flow?.storyPending(eventId) == true

    /** Only retail English Emerald (its SHA-1, a retail dump counts), with the flag on and a link; nothing else gets a hook. */
    fun mayHook(romSha1: String?, flagOn: Boolean, link: CoopLink?): Boolean =
        flagOn && link != null && romSha1 == EMERALD_COOP_SHA1

    /** The glue for this game, hooked into the engine; false (nothing touched) for anything but retail Emerald with the flag. */
    fun attach(app: CoopApp, link: CoopLink?, romSha1: String?): Boolean {
        detach()
        if (link == null || !mayHook(romSha1, app.coopOn, link)) return false
        val channel = CoopLinkChannel(link)
        val g = CoopBattleGlue(app.core(), channel, app.emu, android.os.SystemClock::elapsedRealtime, app.overlay, app.env, linkEngine = MgbaLinkEngine())
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        s.launch { channel.received.collect { runCatching { g.onReceived(it.first, it.second) } } }
        s.launch { link.state.map { it is CoopState.Connected }.distinctUntilChanged().collect { g.onLinkState(it) } }
        scope = s
        glue = g
        CoopGuards.battleActive = { busy }
        app.setHook(g)
        return true
    }

    /** The game closed or the link was detached: holds and blocks off, the hook out. */
    fun detach(app: CoopApp? = null) {
        val g = glue ?: return
        glue = null
        scope?.cancel()
        scope = null
        app?.setHook(null)
        g.release()
        CoopGuards.battleActive = { false }
    }

    /** The engine's per-tick calls go straight to the hook ([CoopFrameHook]); these are the same through the object. */
    fun beforeFrame(keys: Int): Int? { val g = glue ?: return keys; return g.beforeFrame(keys) }
    fun afterFrame() { glue?.afterFrame() }

    /** The games put back before any state is written (pause, sleep, destroy, restart): UI thread, waits for the emu thread. */
    fun stopNow(): Boolean = glue?.stopNow() ?: true

    fun resume() { glue?.resume() }

    /** A game button while an overlay wants it. */
    fun takeKey(k: MenuKey): Boolean = glue?.takeKey(k) == true
}

/** The retail Emerald's RAM addresses the app side reads (the co-op bridge's own are in pk_coop_emerald.h). */
object CoopEmeraldRam {
    const val PLAYER_PARTY = 0x020244ECL
    const val PLAYER_PARTY_COUNT = 0x020244E9L
    const val ENEMY_PARTY = 0x02024744L
    const val MON_SIZE = 100
}

/** [MgbaCore]'s link-battle externals as a [com.pokedaisy.app.companion.data.coop.CoopLinkEngine]: straight to the native bridge, emu thread. */
class MgbaLinkEngine : com.pokedaisy.app.companion.data.coop.CoopLinkEngine {
    override fun take(): ByteArray? = MgbaCore.pkCoopLinkTake()
    override fun give(block: ByteArray) = MgbaCore.pkCoopLinkGive(block)
    override fun state() = MgbaCore.pkCoopLinkState()
    override fun badBlocks(): ByteArray? = MgbaCore.pkCoopLinkBadBlocks()
    override fun leave(blackout: Boolean) = MgbaCore.pkCoopLinkLeave(blackout)
    override fun result(): ByteArray? = MgbaCore.pkCoopLinkResult()
    override fun caught(): ByteArray? = MgbaCore.pkCoopLinkCaught()
    override fun resetBridge() = MgbaCore.pkCoopLinkReset()
    override fun settled() = MgbaCore.pkCoopOnFreeField()
    override fun install() = MgbaCore.pkCoopInstall() && MgbaCore.pkCoopLinkInstall()
    override fun refused(): String = listOf("check: " + MgbaCore.pkCoopRefused(), "bridge: " + MgbaCore.pkCoopLinkRefused()).joinToString("; ")
    override fun hostHold() = MgbaCore.pkCoopLinkHostHold()
    override fun hostGo(name: ByteArray, gender: Int, otId: Int) = MgbaCore.pkCoopLinkHostGo(name, gender, otId)
    override fun partnerGo(kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int) =
        MgbaCore.pkCoopLinkPartnerGo(kind, trainerA, trainerB, name, gender, otId)
    override fun storyArm(trainerId: Int) = MgbaCore.pkCoopStoryArm(trainerId)
    override fun storyState() = MgbaCore.pkCoopStoryState()
    override fun storyGo(master: Boolean, name: ByteArray, gender: Int, otId: Int) = MgbaCore.pkCoopStoryGo(master, name, gender, otId)
    override fun storyCancel() = MgbaCore.pkCoopStoryCancel()
    override fun storyDisarm() = MgbaCore.pkCoopStoryDisarm()
    override fun releaseScript() = MgbaCore.pkCoopReleaseScript()
}

/** [MgbaCore] as a [CoopCore]: every call goes straight to its external, on the emu thread. */
class MgbaCoopCore : CoopCore {
    override fun joinKind() = MgbaCore.pkCoopJoinKind()
    override fun joinTrainers(): IntArray = MgbaCore.pkCoopJoinTrainers()
    override fun partnerFree() = MgbaCore.pkCoopPartnerFree()
    override fun giveMon(mon: ByteArray) = MgbaCore.pkCoopGiveMon(mon)
    override fun removeItem(item: Int, count: Int) = MgbaCore.pkCoopRemoveItem(item, count)
    override fun setTrainerFlag(trainerId: Int) = MgbaCore.pkCoopSetTrainerFlag(trainerId)
    override fun prize(trainerId: Int, single: Boolean) = MgbaCore.pkCoopPrize(trainerId, single)
    override fun addMoney(amount: Int) = MgbaCore.pkCoopAddMoney(amount)
    override fun writePartyMon(slot: Int, mon: ByteArray) = MgbaCore.pkCoopWritePartyMon(slot, mon)
    override fun whiteOut() = MgbaCore.pkCoopWhiteOut()
    override fun evolve(slot: Int) = MgbaCore.pkCoopEvolve(slot)
    override fun saveGame() = MgbaCore.pkCoopSaveGame()
    override fun onFreeField() = MgbaCore.pkCoopOnFreeField()
}
