package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import kotlin.random.Random

/** What the link flow knows that the core doesn't (the glue maps its own env onto it). Emu thread unless said. */
interface CoopLinkEnv {
    fun selfReady(): Boolean
    fun foeSpecies(): Int
    /** The player's party, each Pokémon 100 bytes (count = size), as the game has it now. */
    fun party(): List<ByteArray>
    fun partnerName(): String
    fun identity(): CoopIdentity
    /** The story spot this player stands at (or waits parked at): [CoopJoinRules.StoryAt.NONE] when none. */
    fun storyAt(): CoopJoinRules.StoryAt = CoopJoinRules.StoryAt.NONE
    /** The save is in "play together only" mode: a story battle dropped for good blacks the player out. */
    fun playTogetherOnly(): Boolean = false
    /** The pair (own save + partner) the catches that are still on their way belong to. */
    fun pairKey(): String = ""
    fun log(text: String) {}
}

/**
 * A joined battle as an Emerald link battle, from the join prompt to the result: the offer and the answer (kinds 1 wild, 2 two
 * trainers, 3 one trainer, 4 a story trainer), the start of the game's link battle on both devices (host_go / partner_go / the story
 * hook's pick and go), the block pump ([CoopLinkBattle]), and the after-battle changes (a catch given to its thrower, the save).
 * **Emu thread only**: [beforeFrame] is called every engine tick (also while [holdGame], when no frame runs), [afterFrame] after every
 * emulated frame; [onMessage] and [linkChanged] are hopped onto that thread by the glue. Never touches CoopGuards: the glue maps
 * [busy] / [holdGame] / [blocksStates] onto them.
 *
 * Order of a wild / trainer join: the host's game is held where the battle starts; the partner answers yes and enters its own link
 * battle first (its blocks wait for the host: [CoopLinkBattle.expect]) and says [CoopBattleMsg.LinkReady]; only then does the host's game
 * begin (a refusal there ends the partner's half at once with a CANCEL). A story join arms the pick hook on both; each game runs its own
 * `trainerbattle`, both pick a team, and with both READY the host begins and says GO, the partner begins after it.
 */
class CoopLinkFlow(
    private val core: CoopCore,
    private val engine: CoopLinkEngine,
    private val channel: CoopBattleChannel,
    private val now: () -> Long,
    private val hostUi: CoopHostUi,
    private val partnerUi: CoopPartnerUi,
    beforeSave: () -> Boolean,
    private val env: CoopLinkEnv,
    private val newId: () -> Int = { Random.nextInt(1, Int.MAX_VALUE) },
    private val token: Int = Random.nextInt(0, 1000),
    private val store: CoopCatchStore = MemoryCatchStore(),
) {
    var range: Int = CoopJoinRules.DEFAULT_RANGE
    var partnerSpot: CoopJoinRules.Spot? = null
    var mySpot: CoopJoinRules.Spot? = null

    private enum class Phase { IDLE, H_ASKING, H_WAIT, H_GO, S_ARMED, P_PROMPT, P_START, RUNNING, AFTER }

    private val sink = object : CoopBattleUiSink {
        override fun linkWait(secondsLeft: Int) = hostUi.linkWait(secondsLeft)
        override fun notice(text: String) = hostUi.notice(text)
        override fun clear() { hostUi.clear(); partnerUi.clear() }
    }

    /** The block pump; [CoopLinkBattle.active] is the battle itself. */
    val battle = CoopLinkBattle(engine, channel, sink, now)
    private val ops = CoopOpQueue(core, beforeSave, { hostUi.notice(it) }, { env.log(it) }, now, onDone = { op, code -> opDone(op, code) })

    @Volatile private var phase = Phase.IDLE
        set(value) { if (field != value) { log("phase ${field} -> ${value} (battle ${battleId}, kind ${kind}, ${if (isHost) "host" else "partner"})"); field = value } }
    private fun log(text: String) = env.log("flow: $text")
    /** What the native bridge last refused with, for the log. */
    private fun why(): String = engine.refused().let { if (it.isEmpty()) "no native refusal text" else it }
    private var isHost = false
    private var battleId = 0
    private var kind = 0
    private var trainerA = 0
    private var trainerB = 0
    private var otherName = COOP_NO_NAME
    private var otherGender = 0
    private var otherOt = 0
    private var phaseAt = 0L
    private var tries = 0
    private var partnerTries = 0
    private var lastPartnerGo = Int.MIN_VALUE

    // the host's offer
    private var join = CoopHostJoin(now)
    private var heard = false
    private var readyEarly = false   // a LinkReady heard while the host was still reading the Answer (H_ASKING)
    private var partnerLeft = false
    private var startHandled = false
    private var partnerFree = false
    private var partnerReady = false
    private var partnerStory = 0

    // the partner's prompt
    private var partnerJoin = CoopPartnerJoin(now)
    private var offer: CoopBattleMsg.Offer? = null

    // story
    private var storyDone = 0
    private var storySeenAt = 0L
    private var storySent = -1
    private var otherStory = CoopLinkEngine.STORY_IDLE
    private var storyGoSeen = false

    // the end
    private var wantSave = false
    private var caughtSent = 0L
    private val lateGive = ArrayDeque<Pair<Int, ByteArray>>()
    private val refusedCatches = HashSet<String>()
    /** A partner's catch this game gave itself (in RAM) whose in-game save has not completed yet: token -> the host's battle id. Acknowledged
     * (and remembered as given for good) only once a Save answered 1, so a close or a crash before it never loses the Pokemon. */
    private val unsaved = LinkedHashMap<String, Int>()
    /** The host's own catches (battle ids) in this game whose save has not completed; and those already tried this session. */
    private val ownUnsaved = LinkedHashSet<Int>()
    private val ownTried = HashSet<Int>()
    /** A story join that dropped for good while its game never freed: the blackout is still owed and runs once the game does. */
    private var blackoutOwed = false
    private var noRoomAt = 0L

    /** Called when this game's link battle is over (before its changes are written): tests and logs. */
    var onEnded: (CoopLinkEnd) -> Unit = {}

    init {
        battle.onEnded = { ended(it) }
        battle.log = { env.log(it) }
    }

    /** From the offer's hold (host) / the answered prompt (partner) to the changes saved. */
    val busy: Boolean get() = when (phase) {
        Phase.IDLE -> blackoutOwed
        Phase.P_PROMPT, Phase.S_ARMED -> false
        Phase.H_ASKING -> kind != STORY
        else -> true
    }

    /** A story join is armed (the pick hook is in): states are not loaded meanwhile, but the lock's hold may still wait. */
    val armed: Boolean get() = phase == Phase.S_ARMED

    /** The engine runs no frames while the partner is asked (a battle start only) or answered and its half is starting. */
    val holdGame: Boolean get() = (phase == Phase.H_ASKING && kind != STORY && join.holdGame) || phase == Phase.H_WAIT

    /** The lock's story hold must wait for this event's join decision (the offer, its prompt, its answer): both players are parked at it. */
    fun storyPending(eventId: Int): Boolean {
        if (eventId == 0) return false
        return when (phase) {
            Phase.H_ASKING, Phase.P_PROMPT -> kind == STORY || offer?.kind == STORY
            Phase.IDLE -> eventId == partnerStory && eventId != storyDone && channel.connected && partnerReady && partnerFree &&
                env.selfReady() && storySeenAt != 0L && now() - storySeenAt < STORY_PENDING_MS
            else -> false
        }
    }

    fun beforeFrame(mayOffer: Boolean = true) {
        when (phase) {
            Phase.IDLE -> { lateStep(); owedStep(); if (mayOffer && !battle.pumping && !blackoutOwed) idle() }
            Phase.H_ASKING -> asking()
            Phase.H_WAIT -> hostWaiting()
            Phase.H_GO -> hostGo()
            Phase.S_ARMED -> storyTick()
            Phase.P_PROMPT -> prompting()
            Phase.P_START -> partnerStart()
            Phase.RUNNING -> if (!battle.active && !battle.pumping) toAfter()
            Phase.AFTER -> after()
        }
        resendCaught()
    }

    /** After EVERY emulated frame: the block pump (also after the battle went inactive, until the result is read). */
    fun afterFrame() { battle.afterFrame() }

    fun onMessage(m: CoopBattleMsg) {
        when (m) {
            is CoopBattleMsg.Offer, is CoopBattleMsg.Answer, is CoopBattleMsg.Cancel, is CoopBattleMsg.LinkReady -> log("heard ${describe(m)} in phase $phase")
            else -> {}
        }
        when (m) {
            is CoopBattleMsg.Status -> { partnerFree = m.free; partnerReady = m.ready; partnerStory = m.story }
            is CoopBattleMsg.Offer -> offered(m)
            is CoopBattleMsg.Answer -> if (phase == Phase.H_ASKING && m.battleId == battleId) { heard = true; join.onAnswer(m) }
            is CoopBattleMsg.Cancel -> if (m.battleId == battleId) cancelled(m.reason)
            is CoopBattleMsg.LinkReady -> if (m.battleId == battleId) {
                if (phase == Phase.H_WAIT) { phase = Phase.H_GO; phaseAt = now(); tries = 0 }
                // The partner's game can be in before the host has read its Answer (both came in one poll): remembered, the host goes the moment it waits
                else if (phase == Phase.H_ASKING && isHost) readyEarly = true
            }
            is CoopBattleMsg.LinkBlock -> battle.onMessage(m)
            is CoopBattleMsg.StoryState -> storyMessage(m)
            is CoopBattleMsg.CaughtMon -> caughtMessage(m)
            is CoopBattleMsg.ResultAck -> store.removePending(env.pairKey(), m.battleId)
            else -> {}
        }
    }

    fun linkChanged(connected: Boolean) {
        log("link ${if (connected) "up" else "down"} in phase $phase")
        if (connected) caughtSent = 0L   // an un-acknowledged catch of the partner goes out again at once
        when (phase) {
            Phase.H_ASKING -> if (!connected) { heard = true; join.linkLost() }
            Phase.H_WAIT, Phase.H_GO -> if (!connected) failStart(CoopCancel.LINK, tell = false)
            Phase.P_PROMPT -> if (!connected) { partnerUi.clear(); reset() }
            Phase.P_START -> if (!connected) failStart(CoopCancel.LINK, tell = false)
            Phase.RUNNING -> if (connected) battle.linkUp() else battle.linkDown()
            // A story join waits for nobody on a dead link: the hook goes out and the game goes on as solo.
            Phase.S_ARMED -> if (!connected) { if (armDownAt == 0L) armDownAt = now() } else if (armDownAt != 0L) { armDownAt = 0L; hostUi.clear() }
            else -> {}
        }
    }

    /** The host's CANCEL button while the partner is being asked (or answered and starting): the battle goes on single. */
    fun cancelAsking() {
        if (phase != Phase.H_ASKING && phase != Phase.H_WAIT) return
        send(CoopBattleMsg.Cancel(battleId, CoopCancel.TIMEOUT))
        join.onCancel(battleId)
        hostUi.clear(); battle.forget(); reset()
    }

    /** The partner's yes / no to the prompt. */
    fun answerPrompt(accept: Boolean) {
        val o = offer ?: return
        if (phase != Phase.P_PROMPT) return
        partnerUi.clear()
        val party = env.party()
        val packed = party.fold(ByteArray(0)) { acc, mon -> acc + mon }
        val args = if (accept) CoopBattle.armArguments(packed, party.size) else null
        val installed = args != null && engine.install()
        val armed = installed && (o.kind != STORY || engine.storyArm(o.trainerA))
        if (!armed || args == null) {
            log("partner declines its own accept: team ${if (args == null) "refused" else "ok"}, install $installed, story arm $armed (${why()})")
            partnerJoin.decline()
            send(CoopBattleMsg.Answer(o.battleId, false))
            reset(); return
        }
        val id = partnerJoin.accept() ?: return
        val who = env.identity()
        val mons = args.partner
        val slots = CoopBattle.ableSlots(packed, party.size)
        battleId = id; kind = o.kind; trainerA = o.trainerA; trainerB = o.trainerB
        otherName = o.hostName; otherGender = o.hostGender; otherOt = o.hostOtId
        isHost = false; phaseAt = now(); tries = 0; partnerTries = 0; lastPartnerGo = Int.MIN_VALUE; storyGoSeen = false; storySent = -1; otherStory = CoopLinkEngine.STORY_IDLE
        if (!send(CoopBattleMsg.Answer(id, true, mons, args.count, who.name, who.gender, who.otId, slots))) {
            if (kind == STORY) engine.storyDisarm()
            reset(); return
        }
        battle.expect(id)
        if (kind == STORY) { phase = Phase.S_ARMED; hostUi.notice(CoopNotices.STORY_ARMED) }
        else phase = Phase.P_START
    }

    /** The app is leaving: the link battle ends at once (the game leaves it), the other side hears CANCEL(CLOSED). */
    fun stopNow() {
        when (phase) {
            Phase.IDLE -> { if (blackoutOwed) { env.log("closed with a story blackout still owed"); blackoutOwed = false }; battle.stop() }   // a bridge still pumping goes
            Phase.H_ASKING, Phase.H_WAIT, Phase.H_GO -> { send(CoopBattleMsg.Cancel(battleId, CoopCancel.CLOSED)); hostUi.clear(); battle.forget(); reset() }
            Phase.S_ARMED -> { send(CoopBattleMsg.Cancel(battleId, CoopCancel.CLOSED)); stopDisarm(); battle.forget(); reset() }
            Phase.P_PROMPT -> { partnerUi.clear(); reset() }
            Phase.P_START -> { send(CoopBattleMsg.Cancel(battleId, CoopCancel.CLOSED)); battle.forget(); reset() }
            Phase.RUNNING -> battle.stop()
            Phase.AFTER -> { if (!ops.empty) env.log("closed with changes still to write"); ops.clear(); battle.stop(); reset() }
        }
    }

    // --- the host's offer ---

    private fun idle() {
        if (engine.hostHold()) {
            if (startHandled) return
            startHandled = true
            offerBattle()
            return
        }
        startHandled = false
        storyIdle()
    }

    private fun offerBattle() {
        // One trainer (kind 3) who is one of the story's (a gym leader, the League, a rival...) is never offered here: the story's badge /
        // flag steps run by its own script (a story join, kind 4, is offered where the player stands before it).
        val trainers = core.joinTrainers()
        val k = core.joinKind().let {
            if (it == 3 && CoopStory.isStoryTrainer(trainers.getOrElse(0) { 0 })) { env.log("a story trainer battle is not offered as an everyday one (trainer ${trainers.getOrElse(0) { 0 }})"); 0 } else it
        }
        val nearby = CoopJoinRules.nearby(mySpot, partnerSpot, range)
        val may = CoopJoinRules.mayOffer(k, channel.connected, partnerReady, partnerFree, nearby, env.selfReady())
        log("battle start held: kind $k, connected ${channel.connected}, partner ready $partnerReady free $partnerFree, nearby $nearby, self ready ${env.selfReady()} -> ${if (may) "offering" else "no offer"}")
        if (!may) return
        sendOffer(k, trainers.getOrElse(0) { 0 }, trainers.getOrElse(1) { 0 }, env.foeSpecies())
    }

    private fun sendOffer(k: Int, a: Int, b: Int, species: Int): Boolean {
        val who = env.identity()
        kind = k; trainerA = a; trainerB = b
        battleId = newId()
        heard = false; partnerLeft = false; isHost = true; readyEarly = false
        join = CoopHostJoin(now).also { it.battleStarting(battleId, true) }
        if (!send(CoopBattleMsg.Offer(battleId, k, a, b, species, who.name, who.gender, who.otId))) { reset(); return false }
        phase = Phase.H_ASKING
        return true
    }

    private fun asking() {
        join.tick()
        when (val s = join.state) {
            is CoopHostJoin.Asking -> hostUi.asking(env.partnerName(), secondsLeft(s.since))
            is CoopHostJoin.Accepted -> accepted(s.answer)
            else -> {
                log("the offer ended without a start: ${s::class.simpleName}, heard $heard")
                if (!heard) send(CoopBattleMsg.Cancel(battleId, CoopCancel.TIMEOUT))
                hostUi.clear()
                if (kind == STORY) storyDone = ownStoryId()
                reset()
            }
        }
    }

    private fun secondsLeft(since: Long): Int {
        val left = CoopJoinRules.PROMPT_MS - (now() - since)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** The partner said yes: check each Pokémon again, then wait for its half (a battle start) or arm the pick hook (a story). */
    private fun accepted(a: CoopBattleMsg.Answer) {
        hostUi.clear()
        if (partnerLeft) { reset(); return }
        val bad = (0 until a.count).any {
            CoopBattle.check(a.team.copyOfRange(it * CoopBattle.MON_SIZE, (it + 1) * CoopBattle.MON_SIZE)) != CoopMonCheck.Ok
        }
        if (bad) { log("the partner team failed the check (${a.count} Pokemon)"); return failStart(CoopCancel.DECLINED, tell = true) }
        otherName = a.name; otherGender = a.gender; otherOt = a.otId
        phaseAt = now(); tries = 0
        val installed = engine.install()
        log("host install: $installed (${why()})")
        if (!installed) return failStart(CoopCancel.ENGINE, tell = true)
        battle.expect(battleId)
        if (kind == STORY) {
            if (!engine.storyArm(trainerA)) return failStart(CoopCancel.ENGINE, tell = true)
            phase = Phase.S_ARMED; storySent = -1; otherStory = CoopLinkEngine.STORY_IDLE; storyGoSeen = false
            hostUi.notice(CoopNotices.STORY_ARMED)
            return
        }
        phase = Phase.H_WAIT
        if (readyEarly) { readyEarly = false; phase = Phase.H_GO; phaseAt = now(); tries = 0 }
    }

    /** The partner's own battle is in: waiting for its LinkReady (the game stays held). */
    private fun hostWaiting() {
        if (now() - phaseAt >= START_WAIT_MS) return failStart(CoopCancel.TIMEOUT, tell = true)
        hostUi.asking(env.partnerName(), ((START_WAIT_MS - (now() - phaseAt) + 999) / 1000).toInt())
    }

    /** The partner is in: the held battle becomes the link battle, frames running so a safe one comes up. */
    private fun hostGo() {
        hostUi.clear()
        if (!engine.hostHold()) { log("host hold gone before try $tries (the held battle start is not there any more)"); return failStart(CoopCancel.ENGINE, tell = true) }
        val r = battle.start(battleId, false) { if (engine.hostGo(otherName, otherGender, otherOt)) 1 else 0 }
        if (r == 1 || tries == 0 || tries % 30 == 0) log("hostGo try $tries: $r (${why()})")
        when {
            r == 1 -> { phase = Phase.RUNNING; phaseAt = now() }
            ++tries >= MAX_GO_TRIES -> failStart(CoopCancel.ENGINE, tell = true)
        }
    }

    // --- the partner ---

    private fun offered(m: CoopBattleMsg.Offer) {
        // Two story offers crossed: the lower battle id yields and takes the other's prompt.
        if (phase == Phase.H_ASKING && kind == STORY && m.kind == STORY && m.battleId < battleId) {
            send(CoopBattleMsg.Cancel(battleId, CoopCancel.BUSY)); hostUi.clear(); reset()
        }
        val busyNow = phase != Phase.IDLE || battle.pumping || !core.partnerFree()
        val reply = partnerJoin.onOffer(m, !busyNow, env.selfReady())
        if (reply != null) { send(reply); return }
        offer = m; battleId = m.battleId; kind = m.kind
        phase = Phase.P_PROMPT
    }

    private fun prompting() {
        partnerJoin.tick()
        val o = offer
        if (partnerJoin.state is CoopPartnerJoin.Prompt && o != null) partnerUi.prompt(o, partnerJoin.secondsLeft) else { partnerUi.clear(); reset() }
    }

    /** The partner's half starts from its own free field, retried while that is not free yet. */
    private fun partnerStart() {
        val r = battle.start(battleId, false) { engine.partnerGo(kind, trainerA, trainerB, otherName, otherGender, otherOt) }
        if (r != lastPartnerGo || r == 1 || (++partnerTries) % 30 == 0) log("partnerGo after ${now() - phaseAt} ms: $r (${why()})")
        lastPartnerGo = r
        when {
            r == 1 -> { send(CoopBattleMsg.LinkReady(battleId)); phase = Phase.RUNNING; phaseAt = now() }
            r == 0 -> failStart(CoopCancel.ENGINE, tell = true)
            now() - phaseAt >= START_WAIT_MS -> failStart(CoopCancel.TIMEOUT, tell = true)
        }
    }

    // --- story ---

    private fun ownStoryId(): Int = env.storyAt().eventId

    private var lastStoryLook = 0L

    private fun storyIdle() {
        if (now() - lastStoryLook < STORY_LOOK_MS) return
        lastStoryLook = now()
        val mine = env.storyAt()
        if (mine.eventId != storyDone) storyDone = 0
        val partnerAt = if (partnerStory != 0) CoopJoinRules.StoryAt(partnerStory, storyTrainerOf(partnerStory), false) else CoopJoinRules.StoryAt.NONE
        if (!CoopJoinRules.storyOffer(mine, partnerAt, partnerFree && partnerReady) || !channel.connected || !env.selfReady() || mine.eventId == storyDone) {
            storySeenAt = 0; return
        }
        if (storySeenAt == 0L) { storySeenAt = now(); return }
        // The one who stood there first asks; a tie is broken by a per-device wait.
        if (now() - storySeenAt < token) return
        if (sendOffer(STORY, mine.trainerId, 0, 0)) storyDone = mine.eventId
    }

    private fun storyTrainerOf(eventId: Int): Int = CoopStory.byId(eventId)?.trainers?.singleOrNull() ?: 0

    private var armDownAt = 0L
    /** A story join that dropped for good and waits for the game to reach the hook (see [storyDropped]). */
    private var storyDrop = false
    private var abortRelease = false

    private fun storyTick() {
        if (storyDrop) return dropTick()
        if (armDownAt != 0L) {
            // The link dropped while the join waits (before the battle starts): the same minute as in a battle, then it counts as a loss.
            val left = CoopLinkBattle.DROP_MS - (now() - armDownAt)
            if (left > 0) { hostUi.linkWait(((left + 999) / 1000).toInt()); return }
            return storyDropped()
        }
        val st = engine.storyState()
        if (st != storySent) { storySent = st; send(CoopBattleMsg.StoryState(battleId, st)) }
        when {
            st == CoopLinkEngine.STORY_CANCELLED || st == CoopLinkEngine.STORY_MISMATCH -> return cancelStory(tell = true)
            st == CoopLinkEngine.STORY_IDLE -> return cancelStory(tell = true)
            now() - phaseAt >= STORY_ARM_MS -> return cancelStory(tell = true)
        }
        if (st != CoopLinkEngine.STORY_READY) return
        if (isHost && otherStory == CoopLinkEngine.STORY_READY) {
            val r = battle.start(battleId, true) { if (engine.storyGo(true, otherName, otherGender, otherOt)) 1 else 0 }
            if (r == 1) { send(CoopBattleMsg.StoryState(battleId, CoopBattleWire.STORY_GO)); phase = Phase.RUNNING; phaseAt = now() }
            else if (++tries >= MAX_GO_TRIES) cancelStory(tell = true)
        } else if (!isHost && storyGoSeen) {
            val r = battle.start(battleId, true) { if (engine.storyGo(false, otherName, otherGender, otherOt)) 1 else 0 }
            if (r == 1) { phase = Phase.RUNNING; phaseAt = now() }
            else if (++tries >= MAX_GO_TRIES) cancelStory(tell = true)
        }
    }

    private fun storyMessage(m: CoopBattleMsg.StoryState) {
        if (m.battleId != battleId || phase != Phase.S_ARMED) return
        if (m.state == CoopBattleWire.STORY_GO) { if (!isHost) { storyGoSeen = true; tries = 0 } } else otherStory = m.state
    }

    /**
     * A join dropped for good before its battle began: a story battle dropped is a loss for both, never a solo story battle. The hook
     * stays in until the game reaches it (ARMED: its script is still on its way; PICKING: the team screen is up) - taking it out earlier
     * would let the script start the real trainer battle. Once a team is picked or the screen backed out of ([dropTick]: READY /
     * CANCELLED, the script held), the hook goes out WITHOUT the game's own battle ([CoopLinkEngine.storyDisarm], never storyCancel),
     * the held script is let go and the game blacks the player out.
     */
    private fun storyDropped() {
        env.log("story join: the link was down ${CoopLinkBattle.DROP_MS / 1000} s")
        hostUi.clear(); hostUi.notice(CoopNotices.UNDONE)
        battle.forget()
        armDownAt = 0L
        storyDrop = true
    }

    private fun dropTick() {
        when (engine.storyState()) {
            CoopLinkEngine.STORY_READY, CoopLinkEngine.STORY_CANCELLED -> {
                engine.storyDisarm()
                ops.clear()
                abortRelease = true
                storyDrop = false
                phase = Phase.AFTER; phaseAt = now()
            }
            // The hook is gone by itself (another trainer's battle went by it): the join is over, nothing was started.
            CoopLinkEngine.STORY_IDLE, CoopLinkEngine.STORY_MISMATCH -> { log("dropped story join: the hook is gone, nothing to black out"); reset() }
            else -> {}   // ARMED / PICKING: the hook stays until the game gets there
        }
    }

    private fun cancelStory(tell: Boolean) {
        if (tell) send(CoopBattleMsg.Cancel(battleId, CoopCancel.DECLINED))
        disarmStory()
        hostUi.notice(CoopNotices.STORY_CALLED_OFF)
        battle.forget(); reset()
    }

    /** The pick hook goes out: with a team picked or the screen backed out of, the game's own battle starts; else it is just disarmed. */
    private fun disarmStory() {
        val st = engine.storyState()
        if (st == CoopLinkEngine.STORY_READY || st == CoopLinkEngine.STORY_CANCELLED) engine.storyCancel() else engine.storyDisarm()
    }

    /**
     * The app closes with a story join armed: the hook goes out and NO solo battle starts from here (never [CoopLinkEngine.storyCancel]):
     * a held script (READY / CANCELLED) is let go, a script still on its way (ARMED / PICKING) is not ours to stop, so closing then just takes
     * the hook out and the game goes on as if no join had been (the other player has its own drop rule).
     */
    private fun stopDisarm() {
        val st = engine.storyState()
        engine.storyDisarm()
        if (st == CoopLinkEngine.STORY_READY || st == CoopLinkEngine.STORY_CANCELLED) engine.releaseScript()
    }

    // --- cancels and failures ---

    private fun cancelled(reason: CoopCancel) {
        heard = true
        when (phase) {
            Phase.H_ASKING -> { join.onCancel(battleId); if (join.state is CoopHostJoin.Accepted) partnerLeft = true }
            Phase.H_WAIT, Phase.H_GO -> { hostUi.clear(); hostUi.notice(CoopNotices.PARTNER_LEFT); battle.forget(); reset() }
            Phase.S_ARMED -> if (!storyDrop) { disarmStory(); hostUi.notice(CoopNotices.STORY_CALLED_OFF); battle.forget(); reset() }   // a dropped join stays dropped
            Phase.P_PROMPT -> { partnerJoin.onCancel(battleId); partnerUi.clear(); reset() }
            Phase.P_START -> { battle.forget(); partnerUi.notice(CoopNotices.PARTNER_LEFT); reset() }
            Phase.RUNNING -> {
                // A half that could not start: this game leaves at once. Any other cancel is the drop rule's (CoopLinkBattle).
                if (reason == CoopCancel.ENGINE || reason == CoopCancel.TIMEOUT || reason == CoopCancel.DECLINED || reason == CoopCancel.BUSY) {
                    log("the other side could not start its half: $reason"); hostUi.notice(CoopNotices.START_FAILED); battle.abandon()
                } else battle.onMessage(CoopBattleMsg.Cancel(battleId, reason))
            }
            else -> {}
        }
    }

    /** The start did not go through: the other side hears why, this device goes on as it was (the held battle continues solo). */
    private fun failStart(reason: CoopCancel, tell: Boolean) {
        log("start failed: $reason (${if (isHost) "host" else "partner"}, phase $phase, told $tell, ${now() - phaseAt} ms in, native: ${why()})")
        if (tell) send(CoopBattleMsg.Cancel(battleId, reason))
        hostUi.clear(); partnerUi.clear()
        if (reason != CoopCancel.DECLINED && reason != CoopCancel.LINK) { if (isHost) hostUi.notice(CoopNotices.START_FAILED) else partnerUi.notice(CoopNotices.START_FAILED) }
        battle.forget()
        reset()
    }

    // --- the end ---

    private fun toAfter() { if (phase == Phase.RUNNING) { phase = Phase.AFTER; phaseAt = now() } }

    /** The battle is over (the result is ready) or was left: the changes this game needs, then the save. */
    private fun ended(end: CoopLinkEnd) {
        onEnded(end)
        val r = end.result
        val endOutcome = r?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        wantSave = false
        val list = ArrayList<CoopOp>()
        if (isHost) {
            when (end.caughtBy) {
                1 -> end.caught?.let { mon ->
                    if (CoopBattle.check(mon) == CoopMonCheck.Ok) { store.addPending(ownKey(), battleId, mon); list += CoopOp.GiveMon(mon); wantSave = true }   // kept on disk until given and saved
                    else env.log("the host's own catch is refused by the check")
                }
                // The partner's ball: kept until the partner has acknowledged giving it to itself (a drop, a restart, a leave on its side
                // never loses it), resent on every reconnect.
                2 -> end.caught?.let { mon -> store.addPending(env.pairKey(), battleId, mon); caughtSent = 0L }
            }
        }
        if (r != null) {
            val trainers = (r[4].toInt() and 0xFF) or ((r[5].toInt() and 0xFF) shl 8) or (r[6].toInt() and 0xFF) or ((r[7].toInt() and 0xFF) shl 8)
            val paid = (r[8].toInt() and 0xFF) or ((r[9].toInt() and 0xFF) shl 8) or ((r[10].toInt() and 0xFF) shl 16) or ((r[11].toInt() and 0xFF) shl 24)
            val blackout = r[2].toInt() and 1 != 0
            if (trainers != 0 || paid != 0 || blackout || (kind == STORY && endOutcome == CoopAfter.WON)) wantSave = true
        }
        ops.load(list)
        if (isHost && end.caughtBy == 1 && list.any { it is CoopOp.GiveMon }) { ownUnsaved += battleId; ownTried += battleId }
        phase = Phase.AFTER; phaseAt = now()
    }

    private fun after() {
        if (abortRelease) {
            // the pick screen may still be up: the script is let go, and the blackout runs, once the game stands on its field
            val r = engine.releaseScript()
            if (r < 0) {
                if (now() - phaseAt > ABORT_WAIT_MS) {
                    // Not a bare reset (the game would go on solo): the blackout is owed and runs as soon as the game frees.
                    env.log("story join: the game never freed in ${ABORT_WAIT_MS / 1000} s: the blackout waits for it")
                    abortRelease = false; reset(); blackoutOwed = true
                }
                return
            }
            abortRelease = false
            ops.load(listOf(CoopOp.WhiteOut, CoopOp.Save))
            return
        }
        if (!wantSave && ops.empty) { reset(); return }
        if (wantSave) { wantSave = false; ops.append(CoopOp.Save) }
        if (ops.empty) reset() else ops.step()
    }

    // --- a partner's catch: the host keeps it until acknowledged, the partner gives it whatever its own battle said ---

    /** The host named a catch of ours: queued once (a resend, or one already given, only re-acknowledges), given on the free field. */
    private fun caughtMessage(m: CoopBattleMsg.CaughtMon) {
        if (m.by != 2) return
        val token = CoopCatchStore.givenToken(m.battleId, m.mon)
        if (token in store.given(env.pairKey())) { send(CoopBattleMsg.ResultAck(m.battleId)); return }   // given before (also after a restart)
        if (token in unsaved) { if (ops.empty && phase == Phase.IDLE) ops.load(listOf(CoopOp.Save)); return }   // in this game, its save is on the way
        if (token in refusedCatches || (noRoomAt != 0L && now() - noRoomAt < NO_ROOM_BACKOFF_MS)) return
        if (lateGive.any { it.first == m.battleId && it.second.contentEquals(m.mon) }) return
        val why = (CoopBattle.check(m.mon) as? CoopMonCheck.Refused)?.reason
            ?: if (Gen3BoxMon.read(m.mon, 0).otId.toInt() == env.identity().otId) null else "carries another OT ID"
        if (why != null) { env.log("caught Pokemon refused: $why"); refusedCatches += token; return }
        lateGive += m.battleId to m.mon.copyOf()
    }

    /** On a free field with nothing running: the next queued catch goes into the party (else the PC); acknowledged only once it is in. */
    private fun lateStep() {
        if (!ops.empty) { ops.step(); return }
        ownStep()
        val next = lateGive.firstOrNull() ?: return
        if (!core.onFreeField()) return
        when (core.giveMon(next.second)) {
            -1 -> {}
            2 -> { env.log("no room for the caught Pokemon"); partnerUi.notice(CoopNotices.NO_ROOM); lateGive.removeFirst(); noRoomAt = now() }
            else -> {
                lateGive.removeFirst()
                // In the game now, but not safe: acknowledged (and remembered as given) only once the in-game save has gone through ([opDone]).
                unsaved[CoopCatchStore.givenToken(next.first, next.second)] = next.first
                ops.load(listOf(CoopOp.Save))
            }
        }
    }

    /** Where this game's own catches wait on disk (the host's ball): beside the partner's, under a key of its own. */
    private fun ownKey() = env.pairKey() + "#own"

    /**
     * The host's own catch that never reached the game's save (the app stopped after the battle, the queue gave up): given on a free
     * field, once per session, then saved. A Pokémon already in the party (the resume state carried it) is only saved.
     */
    private fun ownStep() {
        if (!core.onFreeField()) return
        val next = store.pending(ownKey()).firstOrNull { it.first !in ownTried } ?: return
        ownTried += next.first
        val mon = next.second
        if (CoopBattle.check(mon) != CoopMonCheck.Ok) { env.log("a kept catch is refused by the check: dropped"); store.removePending(ownKey(), next.first); return }
        val inParty = env.party().any { it.size >= 8 && it.copyOfRange(0, 8).contentEquals(mon.copyOfRange(0, 8)) }
        env.log("own catch from battle ${next.first} was not saved: ${if (inParty) "already in the party, saving" else "giving it now"}")
        ops.load(if (inParty) listOf(CoopOp.Save) else listOf(CoopOp.GiveMon(mon), CoopOp.Save))
        ownUnsaved += next.first   // a Save that answers 1 settles it
    }

    /** A save that went through (code 1) acknowledges every catch that was waiting for one; a failed one leaves them for the next. */
    private fun opDone(op: CoopOp, code: Int) {
        if (op is CoopOp.GiveMon && code == 2) {   // no room: stays on disk for the next launch, not retried now
            store.pending(ownKey()).firstOrNull { it.second.contentEquals(op.mon) }?.let { ownUnsaved -= it.first }
            return
        }
        if (op == CoopOp.Save && ownUnsaved.isNotEmpty() && code == 1) {
            for (id in ownUnsaved) store.removePending(ownKey(), id)
            ownUnsaved.clear()
        }
        if (op != CoopOp.Save || unsaved.isEmpty()) return
        if (code != 1) { env.log("the caught Pokemon is in the game but its save did not complete: not acknowledged yet"); return }
        for ((token, id) in unsaved) {
            store.addGiven(env.pairKey(), token)
            send(CoopBattleMsg.ResultAck(id))
        }
        unsaved.clear()
    }

    /** A story join's blackout that is owed (the game never freed): the held script is let go and the blackout runs once the game stands free. */
    private fun owedStep() {
        if (!blackoutOwed || !ops.empty) return
        if (engine.releaseScript() < 0) return
        blackoutOwed = false
        ops.load(listOf(CoopOp.WhiteOut, CoopOp.Save))
    }

    /** The host's side: every un-acknowledged catch goes out again every few seconds while linked, and at once on a reconnect. */
    private fun resendCaught() {
        if (!channel.connected) return
        if (caughtSent != 0L && now() - caughtSent < CAUGHT_RESEND_MS) return
        val all = store.pending(env.pairKey())
        if (all.isEmpty()) return
        caughtSent = now()
        for ((id, mon) in all) send(CoopBattleMsg.CaughtMon(id, 2, mon))
    }

    private fun send(m: CoopBattleMsg): Boolean {
        val (type, payload) = try { CoopBattleWire.encode(m) } catch (e: IllegalArgumentException) { env.log("not sent: ${e.message}"); return false }
        val sent = channel.send(type, payload)
        when (m) {
            is CoopBattleMsg.Offer, is CoopBattleMsg.Answer, is CoopBattleMsg.Cancel, is CoopBattleMsg.LinkReady -> log("sent ${describe(m)}: ${if (sent) "ok" else "the link refused it"}")
            else -> {}
        }
        return sent
    }

    private fun describe(m: CoopBattleMsg): String = when (m) {
        is CoopBattleMsg.Offer -> "Offer(battle ${m.battleId}, kind ${m.kind}, trainers ${m.trainerA}/${m.trainerB})"
        is CoopBattleMsg.Answer -> "Answer(battle ${m.battleId}, accept ${m.accept}, ${m.count} Pokemon)"
        is CoopBattleMsg.Cancel -> "Cancel(battle ${m.battleId}, ${m.reason})"
        is CoopBattleMsg.LinkReady -> "LinkReady(battle ${m.battleId})"
        else -> m.toString()
    }

    private fun reset() {
        partnerJoin = CoopPartnerJoin(now)
        offer = null; phase = Phase.IDLE; kind = 0; ops.clear(); abortRelease = false; armDownAt = 0L; storyDrop = false
        wantSave = false
        isHost = false; readyEarly = false
    }

    companion object {
        const val STORY = 4
        const val START_WAIT_MS = 15_000L
        const val STORY_ARM_MS = 300_000L
        const val ABORT_WAIT_MS = 120_000L
        const val STORY_PENDING_MS = 8_000L
        const val STORY_LOOK_MS = 100L
        const val MAX_GO_TRIES = 120
        const val CAUGHT_RESEND_MS = 2_000L
        const val NO_ROOM_BACKOFF_MS = 60_000L
    }
}
