package com.pokedaisy.app.companion.data.coop

import com.pokedaisy.app.companion.data.FixtureMemoryReader
import com.pokedaisy.app.companion.data.save.Gen3BoxMon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game's own calls a finished joined battle makes, logged. */
private class FlowCore : CoopCore {
    val log = ArrayList<String>()
    var kind = 0
    var trainers = intArrayOf(0, 0)
    var free = true
    var field = true
    var giveAnswer = 0
    var saveAnswers = ArrayDeque<Int>()
    override fun joinKind() = kind
    override fun joinTrainers() = trainers
    override fun partnerFree() = free
    override fun giveMon(mon: ByteArray): Int { log += "giveMon"; return giveAnswer }
    override fun removeItem(item: Int, count: Int) = 1
    override fun setTrainerFlag(trainerId: Int): Int { log += "flag $trainerId"; return 1 }
    override fun prize(trainerId: Int, single: Boolean) = 0
    override fun addMoney(amount: Int) = 1
    override fun writePartyMon(slot: Int, mon: ByteArray) = 1
    override fun whiteOut(): Int { log += "whiteOut"; return 1 }
    override fun evolve(slot: Int) = 0
    override fun saveGame(): Int { log += "save"; return saveAnswers.removeFirstOrNull() ?: 1 }
    override fun onFreeField() = field
}

private class FlowUi : CoopHostUi, CoopPartnerUi {
    var asked: Pair<String, Int>? = null
    var prompt: CoopBattleMsg.Offer? = null
    val notices = ArrayList<String>()
    val waits = ArrayList<Int>()
    override fun asking(partner: String, secondsLeft: Int) { asked = partner to secondsLeft }
    override fun prompt(offer: CoopBattleMsg.Offer, secondsLeft: Int) { prompt = offer }
    override fun linkWait(secondsLeft: Int) { waits += secondsLeft }
    override fun notice(text: String) { notices += text }
    override fun clear() { asked = null; prompt = null }
}

private class FlowEnv(val ownParty: List<ByteArray>, val name: String, val otId: Int, val gender: Int) : CoopLinkEnv {
    var story = CoopJoinRules.StoryAt.NONE
    var lock = false
    val logs = ArrayList<String>()
    var ready = true
    override fun selfReady() = ready
    override fun foeSpecies() = 25
    override fun party() = ownParty
    override fun partnerName() = name
    override fun identity() = CoopIdentity(ByteArray(8) { if (it == 7) 0xFF.toByte() else (0xC0 + otId).toByte() }, gender, otId)
    override fun storyAt() = story
    override fun playTogetherOnly() = lock
    override fun pairKey() = "pair"
    override fun log(text: String) { logs += text }
}

class CoopLinkFlowTest {
    private val party: List<ByteArray> = run {
        val mem = FixtureMemoryReader.load("emerald_vanilla")
        val n = mem.readCoreMemory(0x02000000L + 0x244E9, 1)[0].toInt() and 0xFF
        val all = mem.readCoreMemory(0x02000000L + 0x244EC, 6 * 100)
        List(n) { all.copyOfRange(it * 100, (it + 1) * 100) }
    }
    private var t = 1_000_000L

    /** One device. */
    private inner class Dev(val label: String, val otId: Int, val gender: Int, val other: String, ownParty: List<ByteArray> = party, id: Int = 4242, val store: CoopCatchStore = MemoryCatchStore()) {
        val core = FlowCore()
        val engine = FakeLinkEngine()
        val channel = FakeLinkChannel()
        val ui = FlowUi()
        val env = FlowEnv(ownParty, other, otId, gender)
        var saves = 0
        var backups = 0
        val flow = CoopLinkFlow(core, engine, channel, { t }, ui, ui, { backups++; true }, env, newId = { id }, token = 0, store = store)
        val ends = ArrayList<CoopLinkEnd>()
        var read = 0

        init {
            flow.onEnded = { ends += it }
        }

        fun tick(n: Int = 1) { repeat(n) { t += 17; flow.beforeFrame(); flow.afterFrame() } }

        /** Everything this device sent since the last call, as messages. */
        fun outgoing(): List<CoopBattleMsg> {
            val all = channel.messages
            val new = all.drop(read)
            read = all.size
            return new
        }
    }

    private fun link(a: Dev, b: Dev) {
        // the status beat each device sends the other (free, ready, story)
        a.flow.onMessage(CoopBattleMsg.Status(b.core.free, b.env.ready, if (b.env.story.beaten) 0 else b.env.story.eventId))
        b.flow.onMessage(CoopBattleMsg.Status(a.core.free, a.env.ready, if (a.env.story.beaten) 0 else a.env.story.eventId))
    }

    /** Moves what [from] sent to [to]. */
    private fun pass(from: Dev, to: Dev): List<CoopBattleMsg> {
        val msgs = from.outgoing()
        msgs.forEach { to.flow.onMessage(it) }
        return msgs
    }

    private fun both(host: Dev, partner: Dev, rounds: Int = 1) {
        repeat(rounds) { host.tick(); pass(host, partner); partner.tick(); pass(partner, host) }
    }

    private fun trainersRig(partnerOt: Int = 2222, hostStore: CoopCatchStore = MemoryCatchStore()): Pair<Dev, Dev> {
        val host = Dev("host", 1111, 0, "ASH", store = hostStore); val partner = Dev("partner", partnerOt, 1, "BRENDAN")
        host.core.kind = 2; host.core.trainers = intArrayOf(333, 318); host.engine.hold = true
        host.flow.mySpot = CoopJoinRules.Spot(0, 9, 5, 5); host.flow.partnerSpot = CoopJoinRules.Spot(0, 9, 6, 5)
        link(host, partner)
        return host to partner
    }

    // ---- the host ----


    @Test fun acceptRunsTheWholeStartPartnerFirst() {
        val (host, partner) = trainersRig()
        partner.engine.partnerGoAnswers += listOf(-1, -1)   // its field is not free yet: asked again
        host.tick()
        pass(host, partner)
        partner.tick()
        assertEquals(333, partner.ui.prompt!!.trainerA)
        partner.flow.answerPrompt(true)
        pass(partner, host)                                  // the answer
        host.tick()
        assertTrue("accepted: the game stays held for the partner's half", host.flow.holdGame)
        assertTrue(host.engine.log.none { it.startsWith("hostGo") })
        partner.tick(); partner.tick()
        assertFalse("not started while its field is not free", partner.flow.battle.active)
        partner.tick()
        assertTrue(partner.flow.battle.active)
        assertTrue(partner.engine.log.contains("partnerGo 2 333 318 1"))
        assertEquals("partner_go gets the HOST's identity", "partner:0:1111", partner.engine.goArgs.last())
        val ready = pass(partner, host)
        assertTrue(ready.any { it is CoopBattleMsg.LinkReady })
        host.tick()                                          // LinkReady: the held battle becomes the link battle
        host.tick()
        assertTrue(host.engine.log.contains("hostGo true"))
        assertEquals("host_go gets the PARTNER's identity", "host:1:2222", host.engine.goArgs.last())
        assertTrue(host.flow.battle.active)
        assertFalse("frames run once the link battle is in", host.flow.holdGame)
    }

    @Test fun partnerBlocksSentBeforeTheHostBeginsAreKeptAndGiven() {
        val (host, partner) = trainersRig()
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick(); pass(partner, host)
        // the partner's game already sent blocks; the host is still held
        partner.engine.outgoing += linkBlock(1, 0); partner.engine.outgoing += linkBlock(2, 1)
        partner.tick(); pass(partner, host)
        host.tick(2)
        assertTrue(host.flow.battle.active)
        host.tick()
        assertEquals(listOf(0, 1), host.engine.given.map { it[1].toInt() })
    }

    @Test fun declinedPartnerLeavesTheHeldBattleToTheGame() {
        val (host, partner) = trainersRig()
        host.tick(); pass(host, partner); partner.tick()
        partner.flow.answerPrompt(false)
        val a = pass(partner, host).filterIsInstance<CoopBattleMsg.Answer>().single()
        assertFalse(a.accept)
        host.tick()
        assertFalse(host.flow.busy); assertFalse(host.flow.holdGame)
        assertTrue(host.engine.log.none { it.startsWith("hostGo") })
        assertEquals(0, host.engine.stateCalls)
    }

    @Test fun noAnswerForThirtySecondsWithdrawsTheOffer() {
        val (host, partner) = trainersRig()
        host.tick()
        t += CoopJoinRules.PROMPT_MS
        host.tick()
        assertFalse(host.flow.holdGame)
        val cancel = host.outgoing().filterIsInstance<CoopBattleMsg.Cancel>().single()
        assertEquals(CoopCancel.TIMEOUT, cancel.reason)
        assertNotNull(partner)
    }

    @Test fun hostCancelButtonWithdrawsTheOffer() {
        val (host, _) = trainersRig()
        host.tick()
        host.flow.cancelAsking()
        assertFalse(host.flow.holdGame)
        assertTrue(host.outgoing().any { it is CoopBattleMsg.Cancel })
    }

    @Test fun refusedHostGoLetsTheHeldBattleGoOnAndEndsThePartnersHalf() {
        val (host, partner) = trainersRig()
        host.engine.hostGoDefault = false
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick(); pass(partner, host)
        host.tick(CoopLinkFlow.MAX_GO_TRIES + 2)
        assertFalse(host.flow.busy)
        assertFalse(host.flow.battle.active)
        val cancels = host.outgoing().filterIsInstance<CoopBattleMsg.Cancel>()
        assertEquals(CoopCancel.ENGINE, cancels.last().reason)
        assertTrue(host.ui.notices.contains(CoopNotices.START_FAILED))
        // the partner leaves at once, no 60 s wait, and says nothing back
        cancels.forEach { partner.flow.onMessage(it) }
        assertEquals(listOf(false), partner.engine.leaves)
        assertFalse(partner.flow.battle.active)
        assertTrue(partner.ui.notices.contains(CoopNotices.START_FAILED))
    }

    @Test fun aFailedStartIsLoggedWithItsReasonAndTheNativeRefusal() {
        val (host, partner) = trainersRig()
        host.engine.hostGoDefault = false
        host.engine.refusedText = "bridge: no safe frame"
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick(); pass(partner, host)
        host.tick(CoopLinkFlow.MAX_GO_TRIES + 2)
        val logs = host.env.logs
        assertTrue(logs.toString(), logs.any { it.contains("start failed: ENGINE") && it.contains("bridge: no safe frame") })
        assertTrue(logs.toString(), logs.any { it.contains("hostGo try 0: 0") })
        assertTrue(logs.toString(), logs.any { it.contains("phase H_GO -> IDLE") || it.contains("-> IDLE") })
        assertTrue(logs.toString(), logs.any { it.startsWith("flow: sent Offer(") })
        assertTrue(logs.toString(), logs.any { it.startsWith("flow: heard Answer(") })
        assertTrue(logs.toString(), logs.any { it.startsWith("flow: sent Cancel(") && it.contains("ENGINE") })
        // bounded: the retries log the first and every 30th, never every frame
        assertTrue(logs.toString(), logs.count { it.contains("hostGo try") } <= 6)
    }

    @Test fun hostGoNeedsASafeFrameSoItIsAskedAgain() {
        val (host, partner) = trainersRig()
        host.engine.hostGoAnswers += listOf(false, false, false)
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick(); pass(partner, host)
        host.tick(8)
        assertEquals(4, host.engine.log.count { it.startsWith("hostGo") })
        assertTrue(host.flow.battle.active)
    }

    @Test fun heldBattleThatMovedOnEndsTheStart() {
        val (host, partner) = trainersRig()
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick(); pass(partner, host)
        host.engine.hold = false       // the transition ran on without us
        host.tick(2)
        assertFalse(host.flow.busy)
        assertEquals(CoopCancel.ENGINE, host.outgoing().filterIsInstance<CoopBattleMsg.Cancel>().last().reason)
    }

    @Test fun partnerThatNeverAnswersReadyTimesOut() {
        val (host, partner) = trainersRig()
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick()
        t += CoopLinkFlow.START_WAIT_MS
        host.tick()
        assertFalse(host.flow.busy)
        assertEquals(CoopCancel.TIMEOUT, host.outgoing().filterIsInstance<CoopBattleMsg.Cancel>().last().reason)
    }

    @Test fun badTeamInTheAnswerIsRefused() {
        val (host, _) = trainersRig()
        host.tick()
        val id = host.outgoing().filterIsInstance<CoopBattleMsg.Offer>().single().battleId
        val broken = party[0].copyOf().also { it[0x1C] = (it[0x1C] + 1).toByte() }   // a growth word changed: the checksum fails
        host.flow.onMessage(CoopBattleMsg.Answer(id, true, broken, 1, COOP_NO_NAME, 0, 1, intArrayOf(0)))
        host.tick()
        assertFalse(host.flow.busy)
        assertEquals(CoopCancel.DECLINED, host.outgoing().filterIsInstance<CoopBattleMsg.Cancel>().last().reason)
    }

    @Test fun wildBattleOfferHasNoTrainers() {
        val host = Dev("host", 1, 0, "ASH"); val partner = Dev("partner", 2, 1, "BRENDAN")
        host.core.kind = 1; host.engine.hold = true
        host.flow.mySpot = CoopJoinRules.Spot(0, 9, 5, 5); host.flow.partnerSpot = CoopJoinRules.Spot(0, 9, 6, 5)
        link(host, partner)
        host.tick()
        val o = host.outgoing().filterIsInstance<CoopBattleMsg.Offer>().single()
        assertEquals(1, o.kind); assertEquals(0, o.trainerA)
        partner.flow.onMessage(o); partner.tick(); partner.flow.answerPrompt(true)
        partner.tick()
        assertTrue(partner.engine.log.contains("partnerGo 1 0 0 1"))
    }

    @Test fun nothingIsOfferedFarAwayOrWhenThePartnerIsBusy() {
        val (host, _) = trainersRig()
        host.flow.partnerSpot = CoopJoinRules.Spot(0, 9, 60, 5)
        host.tick()
        assertTrue(host.outgoing().none { it is CoopBattleMsg.Offer })
        val (h2, p2) = trainersRig()
        p2.core.free = false
        link(h2, p2)
        h2.tick()
        assertTrue(h2.outgoing().none { it is CoopBattleMsg.Offer })
    }

    @Test fun aStoryTrainerAloneIsNotOfferedAsAnEverydayBattle() {
        val (host, _) = trainersRig()
        host.core.kind = 3; host.core.trainers = intArrayOf(265, 0)   // Roxanne
        host.tick()
        assertTrue(host.outgoing().none { it is CoopBattleMsg.Offer })
    }

    // ---- the partner ----

    @Test fun partnerBusyOrNotReadyAnswersBusy() {
        val (host, partner) = trainersRig()
        partner.core.free = false
        host.tick(); pass(host, partner)
        val c = partner.outgoing().filterIsInstance<CoopBattleMsg.Cancel>().single()
        assertEquals(CoopCancel.BUSY, c.reason)
        assertNull(partner.ui.prompt)
    }

    @Test fun partnerRefusedStartSaysSoAndTheHostGoesOn() {
        val (host, partner) = trainersRig()
        partner.engine.partnerGoDefault = 0
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick()
        assertFalse(partner.flow.battle.active)
        val c = pass(partner, host).filterIsInstance<CoopBattleMsg.Cancel>().single()
        assertEquals(CoopCancel.ENGINE, c.reason)
        host.tick()
        assertFalse(host.flow.busy); assertFalse(host.flow.holdGame)
        assertTrue(host.engine.log.none { it.startsWith("hostGo") })
    }

    @Test fun partnerFieldNeverFreeTimesOut() {
        val (host, partner) = trainersRig()
        partner.engine.partnerGoDefault = -1
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        partner.tick()
        t += CoopLinkFlow.START_WAIT_MS
        partner.tick()
        assertFalse(partner.flow.busy)
        assertEquals(CoopCancel.TIMEOUT, pass(partner, host).filterIsInstance<CoopBattleMsg.Cancel>().last().reason)
    }


    // ---- the end ----

    private fun running(partnerOt: Int = 2222, hostStore: CoopCatchStore = MemoryCatchStore()): Pair<Dev, Dev> {
        val (host, partner) = trainersRig(partnerOt, hostStore)
        host.tick(); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); partner.tick(); pass(partner, host); host.tick(2)
        assertTrue(host.flow.battle.active && partner.flow.battle.active)
        host.outgoing(); partner.outgoing()
        return host to partner
    }

    @Test fun blocksFlowBetweenTheTwoGames() {
        val (host, partner) = running()
        host.engine.outgoing += linkBlock(1, 0); partner.engine.outgoing += linkBlock(2, 0)
        both(host, partner, 2)
        assertEquals(1, host.engine.given.size); assertEquals(2, host.engine.given[0][0].toInt())
        assertEquals(1, partner.engine.given.size); assertEquals(1, partner.engine.given[0][0].toInt())
    }

    @Test fun wonTwoTrainerBattleSavesAfterABackup() {
        val (host, partner) = running()
        host.engine.state = CoopLinkEngine.STATE_ENDED; partner.engine.state = CoopLinkEngine.STATE_ENDED
        host.engine.result = linkResult(CoopAfter.WON, flags = 2, trainerA = 333, trainerB = 318, paid = 500)
        partner.engine.result = linkResult(CoopAfter.WON, flags = 2, trainerA = 333, trainerB = 318, paid = 500)
        both(host, partner, 4)
        assertEquals(1, host.ends.size); assertEquals(1, partner.ends.size)
        assertFalse(host.flow.busy); assertFalse(partner.flow.busy)
        assertEquals(listOf("save"), host.core.log.filter { it == "save" })
        assertEquals(1, host.backups); assertEquals(1, partner.backups)
        assertEquals(1, host.engine.resets); assertEquals(1, partner.engine.resets)
    }

    @Test fun anEscapedBattleChangesNothingSoNothingIsSaved() {
        val (host, partner) = running()
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.RAN)
        partner.engine.state = CoopLinkEngine.STATE_ENDED; partner.engine.result = linkResult(CoopAfter.RAN)
        both(host, partner, 4)
        assertTrue(host.core.log.none { it == "save" }); assertEquals(0, host.backups)
        assertTrue(partner.core.log.none { it == "save" })
        assertFalse(host.flow.busy)
    }

    @Test fun blackoutResultSaves() {
        val (host, partner) = running()
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.LOST, flags = 1)
        both(host, partner, 4)
        assertEquals(1, host.core.log.count { it == "save" })
    }

    // Device log (run 6, attempt 1): the Answer and the partner's LinkReady came in one poll, while the host was still in H_ASKING; the LinkReady
    // was dropped, the host waited its 15 s out and cancelled a battle the partner's game was already in.
    @Test fun aLinkReadyHeardWithTheAnswerStartsTheHostAtOnce() {
        val host = Dev("host", 1, 0, "ASH")
        val f = CoopLinkFlow(host.core, host.engine, host.channel, { t }, host.ui, host.ui, { false }, host.env, newId = { 1 }, token = 0)
        host.core.kind = 2; host.core.trainers = intArrayOf(333, 318); host.engine.hold = true
        f.mySpot = CoopJoinRules.Spot(0, 9, 5, 5); f.partnerSpot = CoopJoinRules.Spot(0, 9, 6, 5)
        f.onMessage(CoopBattleMsg.Status(true, true, 0))
        f.beforeFrame()
        val id = host.channel.messages.filterIsInstance<CoopBattleMsg.Offer>().single().battleId
        f.onMessage(CoopBattleMsg.Answer(id, true, party[0], 1, COOP_NO_NAME, 0, 2, intArrayOf(0)))
        f.onMessage(CoopBattleMsg.LinkReady(id))   // the same poll, before the host has read the Answer
        f.beforeFrame(); f.beforeFrame(); f.beforeFrame()
        assertTrue(host.engine.log.contains("hostGo true"))
        assertTrue(f.battle.active)
        assertTrue(host.channel.messages.none { it is CoopBattleMsg.Cancel })
    }

    @Test fun aSaveThatFailsToBackUpIsNotWritten() {
        val host = Dev("host", 1, 0, "ASH")
        val f = CoopLinkFlow(host.core, host.engine, host.channel, { t }, host.ui, host.ui, { false }, host.env, newId = { 1 }, token = 0)
        host.core.kind = 2; host.core.trainers = intArrayOf(333, 318); host.engine.hold = true
        f.mySpot = CoopJoinRules.Spot(0, 9, 5, 5); f.partnerSpot = CoopJoinRules.Spot(0, 9, 6, 5)
        f.onMessage(CoopBattleMsg.Status(true, true, 0))
        f.beforeFrame()
        val id = host.channel.messages.filterIsInstance<CoopBattleMsg.Offer>().single().battleId
        f.onMessage(CoopBattleMsg.Answer(id, true, party[0], 1, COOP_NO_NAME, 0, 2, intArrayOf(0)))
        f.beforeFrame(); f.onMessage(CoopBattleMsg.LinkReady(id)); f.beforeFrame(); f.beforeFrame()
        assertTrue(f.battle.active)
        host.engine.state = CoopLinkEngine.STATE_ENDED
        host.engine.result = linkResult(CoopAfter.WON, trainerA = 333, trainerB = 318, paid = 5)
        repeat(6) { f.beforeFrame(); f.afterFrame() }
        assertTrue(host.core.log.none { it == "save" })
        assertTrue(host.ui.notices.contains(CoopNotices.SAVE_FAILED))
    }



    /** A partner device already in a running link battle (battle id 4242) whose trainer ID is [ot]. */
    private fun partnerWithOt(ot: Int): Pair<CoopLinkFlow, Dev> {
        val d = Dev("pt", ot, 1, "ASH")
        d.core.kind = 0
        d.flow.onMessage(CoopBattleMsg.Status(true, true, 0))
        d.flow.onMessage(CoopBattleMsg.Offer(4242, 2, 333, 318, 25))
        d.flow.beforeFrame()
        d.flow.answerPrompt(true)
        d.tick(2)
        assertTrue(d.flow.battle.active)
        return d.flow to d
    }

    @Test fun partnerGivesTheCatchOnlyAfterTheHostNamedIt() {
        val caught = party[0].copyOf()
        val ot = Gen3BoxMon.read(caught, 0).otId.toInt()
        val (flow, d) = partnerWithOt(ot)
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        d.tick(3)
        assertTrue("waits for the host's word", d.core.log.none { it == "giveMon" })
        flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caught))
        d.tick(6)
        assertEquals(1, d.core.log.count { it == "giveMon" })
        assertEquals("a catch is progress: saved after", 1, d.core.log.count { it == "save" })
        assertTrue(d.core.log.indexOf("giveMon") < d.core.log.indexOf("save"))
    }

    @Test fun aCatchWithAnotherTrainersIdIsRefused() {
        val caught = party[0].copyOf()
        val (flow, d) = partnerWithOt(0x1234ABCD)    // not the mon's OT ID
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caught))
        d.tick(8)
        assertTrue(d.core.log.none { it == "giveMon" })
        assertTrue(d.env.logs.any { it.contains("another OT ID") })
    }

    @Test fun aBadCaughtPokemonIsRefused() {
        val caught = party[0].copyOf()
        val ot = Gen3BoxMon.read(caught, 0).otId.toInt()
        val (flow, d) = partnerWithOt(ot)
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        val bad = caught.copyOf().also { it[0x1C] = (it[0x1C] + 1).toByte() }
        flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, bad))
        d.tick(8)
        assertTrue(d.core.log.none { it == "giveMon" })
        assertTrue(d.env.logs.any { it.contains("refused") })
    }




    @Test fun droppedLinkEndsAfterAMinuteWithNoSaveAndALeave() {
        val (host, partner) = running()
        host.flow.linkChanged(false); partner.flow.linkChanged(false)
        t += 59_000; host.tick()
        assertTrue(host.engine.leaves.isEmpty()); assertTrue(host.ui.waits.isNotEmpty())
        t += 1_000; host.tick()
        assertEquals(listOf(false), host.engine.leaves)
        assertTrue(host.core.log.none { it == "save" })
        assertEquals(1, host.ends.size); assertTrue(host.ends[0].left)
        assertNotNull(partner)
    }


    @Test fun stopWhileRunningLeavesAndTellsThePartner() {
        val (host, partner) = running()
        host.flow.stopNow()
        assertEquals(listOf(false), host.engine.leaves)
        assertTrue(host.outgoing().any { it is CoopBattleMsg.Cancel && it.reason == CoopCancel.CLOSED })
        assertNotNull(partner)
    }

    @Test fun stopWhileAskingWithdrawsTheOfferAndFreesTheGame() {
        val (host, _) = trainersRig()
        host.tick()
        host.flow.stopNow()
        assertFalse(host.flow.busy); assertFalse(host.flow.holdGame)
        assertTrue(host.outgoing().any { it is CoopBattleMsg.Cancel && it.reason == CoopCancel.CLOSED })
    }

    @Test fun pumpRunsEveryFrameUntilTheResultIsRead() {
        val (host, _) = running()
        host.engine.state = CoopLinkEngine.STATE_ENDED
        val calls = host.engine.stateCalls
        host.tick(10)
        assertEquals(calls + 10, host.engine.stateCalls)
        host.engine.result = linkResult(CoopAfter.RAN)
        host.tick(3)
        val settled = host.engine.stateCalls
        host.tick(5)
        assertEquals("no polling once the battle is over", settled, host.engine.stateCalls)
    }

    // ---- story battles ----

    private val roxanne = CoopJoinRules.StoryAt(166, 265, false)

    private fun storyRig(lock: Boolean = false): Pair<Dev, Dev> {
        val host = Dev("host", 1111, 0, "ASH"); val partner = Dev("partner", 2222, 1, "BRENDAN")
        host.env.story = roxanne; partner.env.story = roxanne
        host.env.lock = lock; partner.env.lock = lock
        link(host, partner)
        return host to partner
    }

    private fun storyRunning(lock: Boolean): Pair<Dev, Dev> {
        val (host, partner) = storyRig(lock)
        host.tick(14)                                  // the spot is seen, then offered
        pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        host.engine.story = CoopLinkEngine.STORY_READY; partner.engine.story = CoopLinkEngine.STORY_READY
        both(host, partner, 4)
        assertTrue(host.flow.battle.active && partner.flow.battle.active)
        return host to partner
    }


    @Test fun storyBeginsOnBothWhenBothPickedAndHostGoesFirst() {
        val (host, partner) = storyRig()
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        host.engine.story = CoopLinkEngine.STORY_READY
        both(host, partner, 3)
        assertFalse("the partner has not picked yet", host.flow.battle.active)
        partner.engine.story = CoopLinkEngine.STORY_READY
        both(host, partner, 4)
        assertTrue(host.flow.battle.active); assertTrue(partner.flow.battle.active)
        assertEquals("story:true:1:2222", host.engine.goArgs.last())
        assertEquals("story:false:0:1111", partner.engine.goArgs.last())
        assertTrue("host first", host.engine.log.indexOf("storyGo true true") >= 0)
    }

    @Test fun storyGoIsAskedAgainWhenRefused() {
        val (host, partner) = storyRig()
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        host.engine.storyGoOk = false
        host.engine.story = CoopLinkEngine.STORY_READY; partner.engine.story = CoopLinkEngine.STORY_READY
        both(host, partner, 4)
        assertFalse(host.flow.battle.active)
        assertTrue(host.engine.storyGoCalls >= 2)
        host.engine.storyGoOk = true
        both(host, partner, 3)
        assertTrue(host.flow.battle.active)
    }

    @Test fun storyCancelledPickCancelsBoth() {
        val (host, partner) = storyRig()
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        partner.engine.story = CoopLinkEngine.STORY_CANCELLED     // backed out of the pick screen
        both(host, partner, 3)
        assertFalse(partner.flow.armed); assertFalse(host.flow.armed)
        assertEquals("the game's own battle starts on the one that backed out", 1, partner.engine.storyCancels)
        assertTrue(host.engine.storyDisarms + host.engine.storyCancels >= 1)
    }

    @Test fun storyMismatchDisarms() {
        val (host, partner) = storyRig()
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        host.engine.story = CoopLinkEngine.STORY_MISMATCH
        both(host, partner, 3)
        assertFalse(host.flow.armed); assertFalse(partner.flow.armed)
        assertTrue(host.ui.notices.contains(CoopNotices.STORY_CALLED_OFF))
    }

    @Test fun storyTimesOutAndDisarms() {
        val (host, partner) = storyRig()
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        t += CoopLinkFlow.STORY_ARM_MS
        both(host, partner, 2)
        assertFalse(host.flow.armed); assertFalse(partner.flow.armed)
        assertTrue(host.engine.storyDisarms >= 1); assertTrue(partner.engine.storyDisarms >= 1)
    }

    @Test fun storyNotOfferedWhenBeatenElsewhereOrBusy() {
        val (host, partner) = storyRig()
        partner.env.story = roxanne.copy(beaten = true)
        link(host, partner)
        host.tick(14)
        assertTrue(host.outgoing().none { it is CoopBattleMsg.Offer })
        partner.env.story = CoopJoinRules.StoryAt(6, 266, false)
        link(host, partner)
        host.tick(14)
        assertTrue("another spot", host.outgoing().none { it is CoopBattleMsg.Offer })
        partner.env.story = roxanne; partner.core.free = false
        link(host, partner)
        host.tick(14)
        assertTrue("partner busy", host.outgoing().none { it is CoopBattleMsg.Offer })
    }





    @Test fun storyWonSavesAfterABackup() {
        val (host, partner) = storyRunning(lock = false)
        host.engine.state = CoopLinkEngine.STATE_ENDED; partner.engine.state = CoopLinkEngine.STATE_ENDED
        host.engine.result = linkResult(CoopAfter.WON); partner.engine.result = linkResult(CoopAfter.WON)
        both(host, partner, 5)
        assertEquals(1, host.core.log.count { it == "save" }); assertEquals(1, partner.core.log.count { it == "save" })
    }

    @Test fun hostOffersAJoinableBattleAndHoldsTheGame() {
        val (host, partner) = trainersRig()
        host.tick(2)
        val offer = host.outgoing().filterIsInstance<CoopBattleMsg.Offer>().single()
        assertEquals(2, offer.kind); assertEquals(333, offer.trainerA); assertEquals(318, offer.trainerB)
        assertEquals("the host's identity rides the offer", 1111, offer.hostOtId)
        assertTrue(host.flow.holdGame); assertTrue(host.flow.busy)
        assertNotNull(host.ui.asked)
        assertNotNull(partner)
    }

    @Test fun partnerWithNoAbleTeamDeclines() {
        val fainted = party.map { it.copyOf().also { m -> m[0x56] = 0; m[0x57] = 0 } }   // every HP zeroed
        val host = Dev("host", 1111, 0, "ASH")
        val p = Dev("p", 2222, 1, "ASH", ownParty = fainted)
        host.core.kind = 2; host.core.trainers = intArrayOf(333, 318); host.engine.hold = true
        host.flow.mySpot = CoopJoinRules.Spot(0, 9, 5, 5); host.flow.partnerSpot = CoopJoinRules.Spot(0, 9, 6, 5)
        link(host, p)
        host.tick(); pass(host, p); p.tick()
        assertNotNull(p.ui.prompt)
        p.flow.answerPrompt(true)
        val a = p.outgoing().filterIsInstance<CoopBattleMsg.Answer>().single()
        assertFalse(a.accept)
        assertTrue(p.engine.log.none { it.startsWith("partnerGo") })
    }

    @Test fun partnersCatchIsSentByTheHost() {
        val (host, partner) = running()
        host.engine.caught = byteArrayOf(2) + party[0]
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.CAUGHT)
        host.tick(3)
        val m = host.outgoing().filterIsInstance<CoopBattleMsg.CaughtMon>().single()
        assertEquals(2, m.by); assertEquals(100, m.mon.size)
        assertTrue("the host gives itself nothing for the partner's catch", host.core.log.none { it == "giveMon" })
        assertNotNull(partner)
    }

    @Test fun crossedStoryOffersAreResolvedByTheLowerIdYielding() {
        val a = Dev("a", 1111, 0, "B", id = 5); val b = Dev("b", 2222, 1, "A", id = 9)
        a.env.story = roxanne; b.env.story = roxanne
        link(a, b)
        a.tick(14); b.tick(14)                          // both asked at once
        val oa = a.outgoing().filterIsInstance<CoopBattleMsg.Offer>().single()
        val ob = b.outgoing().filterIsInstance<CoopBattleMsg.Offer>().single()
        assertEquals(5, oa.battleId); assertEquals(9, ob.battleId)
        b.flow.onMessage(oa)                            // the higher id receives the lower one's offer: it yields
        a.flow.onMessage(ob)                            // the lower id keeps asking
        b.tick(); a.tick()
        assertEquals("the yielding side answers the surviving offer", 4, b.ui.prompt?.kind)
        assertEquals(5, b.ui.prompt?.battleId)
        assertTrue(b.outgoing().any { it is CoopBattleMsg.Cancel && it.battleId == 9 })
        assertTrue("the other keeps its offer", a.outgoing().none { it is CoopBattleMsg.Cancel && it.battleId == 5 })
    }

    @Test fun storyIsOfferedAtTheSameSpotAndBothArm() {
        val (host, partner) = storyRig()
        host.tick(14)
        val offer = host.outgoing().filterIsInstance<CoopBattleMsg.Offer>().single()
        assertEquals(4, offer.kind); assertEquals(265, offer.trainerA)
        assertFalse("a story join never holds the game", host.flow.holdGame)
        partner.flow.onMessage(offer)
        partner.tick()
        assertEquals(4, partner.ui.prompt!!.kind)
        partner.flow.answerPrompt(true)
        assertTrue(partner.engine.log.contains("storyArm 265"))
        val ans = pass(partner, host).filterIsInstance<CoopBattleMsg.Answer>().single()
        assertTrue(ans.accept)
        host.tick()
        assertTrue(host.engine.log.contains("storyArm 265"))
        assertTrue(host.flow.armed); assertTrue(partner.flow.armed)
        assertTrue("armed, not busy: the lock's hold keeps waiting", !host.flow.busy && host.flow.armed)
        assertTrue(host.ui.notices.contains(CoopNotices.STORY_ARMED))
    }

    @Test fun storyArmRefusedDeclinesTheOffer() {
        val (host, partner) = storyRig()
        partner.engine.storyArmOk = false
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true)
        assertFalse(partner.outgoing().filterIsInstance<CoopBattleMsg.Answer>().single().accept)
        assertFalse(partner.flow.armed)
    }

    @Test fun storyOfferedOnceUntilThePlayerLeavesTheSpot() {
        val (host, partner) = storyRig()
        host.tick(14)
        host.outgoing()
        t += CoopJoinRules.PROMPT_MS                   // the partner never answers
        host.tick(2)
        host.outgoing()
        host.tick(30)
        assertTrue("not asked again at the same spot", host.outgoing().none { it is CoopBattleMsg.Offer })
        host.env.story = CoopJoinRules.StoryAt.NONE; host.tick(14)
        host.env.story = roxanne; link(host, partner)
        host.tick(30)
        assertTrue("asked again on coming back", host.outgoing().any { it is CoopBattleMsg.Offer })
    }

    @Test fun storyPendingHoldsTheLockOnlyWhileTheJoinIsBeingDecided() {
        val (host, partner) = storyRig()
        assertFalse("not seen yet", host.flow.storyPending(166))
        host.tick()    // the spot is seen
        assertTrue("both parked: the hold waits for the offer", host.flow.storyPending(166))
        assertFalse(host.flow.storyPending(0))
        host.tick(14)  // offered
        assertTrue(host.flow.storyPending(166))
        pass(host, partner); partner.tick()
        assertTrue("the prompt is up on the partner", partner.flow.storyPending(166))
        partner.flow.answerPrompt(true); pass(partner, host)
        host.tick()
        assertFalse("armed: the hold may go", host.flow.storyPending(166))
        assertFalse(partner.flow.storyPending(166))
    }

    @Test fun storyPendingGivesUpAfterAWhile() {
        val (host, _) = storyRig()
        host.tick()
        t += CoopLinkFlow.STORY_PENDING_MS + 1
        assertFalse(host.flow.storyPending(166))
    }

    @Test fun hostsOwnCatchIsGivenToTheHostOnly() {
        val (host, partner) = running()
        host.engine.caught = byteArrayOf(1) + party[1]
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.CAUGHT)
        host.tick(6)
        assertEquals(1, host.core.log.count { it == "giveMon" })
        assertTrue(host.outgoing().none { it is CoopBattleMsg.CaughtMon })
        assertNotNull(partner)
    }

    @Test fun partnerDoesNotWaitForACatchNobodyNamed() {
        val (_, d) = partnerWithOt(Gen3BoxMon.read(party[0], 0).otId.toInt())
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        d.tick(4)
        assertFalse(d.flow.busy)
        assertTrue(d.core.log.none { it == "giveMon" })
    }

    private val caughtMon get() = party[0].copyOf()
    private val caughtOt get() = Gen3BoxMon.read(party[0], 0).otId.toInt()

    @Test fun linkDownAtTheEndThenReconnectGivesTheCatch() {
        val (host, partner) = running(partnerOt = caughtOt)
        host.channel.connected = false; partner.channel.connected = false
        host.flow.linkChanged(false); partner.flow.linkChanged(false)
        host.engine.caught = byteArrayOf(2) + caughtMon
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.CAUGHT)
        partner.engine.state = CoopLinkEngine.STATE_ENDED; partner.engine.result = linkResult(CoopAfter.CAUGHT)
        t += 30_000
        both(host, partner, 6)
        assertTrue("nothing could be sent", host.outgoing().none { it is CoopBattleMsg.CaughtMon })
        assertEquals(1, host.store.pending("pair").size)
        host.channel.connected = true; partner.channel.connected = true
        host.flow.linkChanged(true); partner.flow.linkChanged(true)
        both(host, partner, 4)
        assertEquals(1, partner.core.log.count { it == "giveMon" })
        assertTrue("acknowledged: nothing pending any more", host.store.pending("pair").isEmpty())
    }

    @Test fun partnerThatLeftTheBattleStillGetsItsCatch() {
        val (host, partner) = running(partnerOt = caughtOt)
        partner.flow.linkChanged(false)
        t += 60_000; partner.tick()                       // its own battle was left: no result
        assertEquals(listOf(false), partner.engine.leaves)
        partner.tick(3)
        host.engine.caught = byteArrayOf(2) + caughtMon
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.CAUGHT)
        both(host, partner, 6)
        assertEquals(1, partner.core.log.count { it == "giveMon" })
    }

    @Test fun aDuplicateCatchIsGivenOnceAndAcknowledgedEachTime() {
        val (_, d) = partnerWithOt(caughtOt)
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        d.tick(4)
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(3)
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(3)
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(3)
        assertEquals(1, d.core.log.count { it == "giveMon" })
        assertEquals(3, d.outgoing().count { it is CoopBattleMsg.ResultAck })
    }

    @Test fun theAcknowledgementWaitsForTheGive() {
        val (_, d) = partnerWithOt(caughtOt)
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        d.tick(4)
        d.core.field = false
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(5)
        assertTrue("the field is not free: not given, not acknowledged", d.outgoing().none { it is CoopBattleMsg.ResultAck })
        d.core.field = true
        d.tick(3)
        assertEquals(1, d.core.log.count { it == "giveMon" })
        assertEquals(1, d.outgoing().count { it is CoopBattleMsg.ResultAck })
    }

    @Test fun theAcknowledgementWaitsForTheInGameSave() {
        val (_, d) = partnerWithOt(caughtOt)
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        d.tick(4)
        d.core.saveAnswers += 0                                   // the first save fails
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(4)
        assertEquals(1, d.core.log.count { it == "giveMon" })
        assertTrue("given in RAM, but its save failed: not acknowledged, not remembered as given", d.outgoing().none { it is CoopBattleMsg.ResultAck })
        assertTrue(d.store.given("pair").isEmpty())
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(4)   // the host sends it again
        assertEquals("never given twice", 1, d.core.log.count { it == "giveMon" })
        assertEquals("the save is tried again, and only then acknowledged", 1, d.outgoing().count { it is CoopBattleMsg.ResultAck })
        assertEquals(2, d.core.log.count { it == "save" })
        assertEquals(1, d.store.given("pair").size)
    }

    @Test fun noRoomIsNotAcknowledged() {
        val (_, d) = partnerWithOt(caughtOt)
        d.core.giveAnswer = 2
        d.engine.state = CoopLinkEngine.STATE_ENDED; d.engine.result = linkResult(CoopAfter.CAUGHT)
        d.tick(4)
        d.flow.onMessage(CoopBattleMsg.CaughtMon(4242, 2, caughtMon)); d.tick(4)
        assertTrue(d.outgoing().none { it is CoopBattleMsg.ResultAck })
        assertTrue(d.ui.notices.contains(CoopNotices.NO_ROOM))
    }

    @Test fun hostResendsAfterARestartUntilAcknowledged() {
        val store = MemoryCatchStore()
        store.addPending("pair", 99, caughtMon)
        val host = Dev("host", 1111, 0, "ASH", store = store)      // a fresh process: nothing running, a catch still pending
        host.tick(2)
        assertEquals(99, host.outgoing().filterIsInstance<CoopBattleMsg.CaughtMon>().single().battleId)
        t += CoopLinkFlow.CAUGHT_RESEND_MS * 20                      // well past the old 20 s cap
        host.tick(); host.tick()
        assertTrue(host.outgoing().any { it is CoopBattleMsg.CaughtMon })
        host.flow.onMessage(CoopBattleMsg.ResultAck(99))
        t += CoopLinkFlow.CAUGHT_RESEND_MS * 2
        host.tick(3)
        assertTrue(host.outgoing().none { it is CoopBattleMsg.CaughtMon })
        assertTrue(store.pending("pair").isEmpty())
    }

    @Test fun reconnectSendsAtOnce() {
        val store = MemoryCatchStore(); store.addPending("pair", 7, caughtMon)
        val host = Dev("host", 1111, 0, "ASH", store = store)
        host.tick(); host.outgoing()
        host.flow.linkChanged(true)
        host.tick()
        assertEquals(1, host.outgoing().count { it is CoopBattleMsg.CaughtMon })
    }


    @Test fun nothingIsOfferedWhileALeftBattleIsStillBeingTidied() {
        val (host, partner) = running()
        host.flow.linkChanged(false)
        t += 60_000; host.tick()
        assertTrue(host.flow.battle.pumping)
        host.core.kind = 2; host.engine.hold = true
        link(host, partner); host.outgoing()
        host.tick(3)
        assertTrue(host.outgoing().none { it is CoopBattleMsg.Offer })
    }

    @Test fun anOfferDuringATidyUpIsAnsweredBusy() {
        val (host, partner) = running()
        partner.flow.linkChanged(false)
        t += 60_000; partner.tick()
        assertTrue(partner.flow.battle.pumping)
        partner.flow.onMessage(CoopBattleMsg.Offer(5, 2, 333, 318, 25))
        assertTrue(partner.outgoing().any { it is CoopBattleMsg.Cancel && it.reason == CoopCancel.BUSY })
        assertNotNull(host)
    }

    @Test fun catchStoreFileSurvivesARestartAndIsAtomic() {
        val dir = java.nio.file.Files.createTempDirectory("catches").toFile()
        val file = java.io.File(dir, "c.txt")
        val a = FileCatchStore(file) { it.run() }
        a.addPending("k 1", 5, caughtMon)
        a.addGiven("k 1", "5:1")
        val b = FileCatchStore(file) { it.run() }
        assertEquals(1, b.pending("k 1").size); assertEquals(5, b.pending("k 1")[0].first)
        assertTrue(b.pending("k 1")[0].second.contentEquals(caughtMon))
        assertTrue("5:1" in b.given("k 1"))
        b.removePending("k 1", 5)
        assertTrue(FileCatchStore(file) { it.run() }.pending("k 1").isEmpty())
        assertFalse("no temp file left behind", java.io.File(dir, "c.txt.tmp").exists())
        file.writeText("garbage\nP only two\nP k 3 zz\n")
        assertTrue(FileCatchStore(file) { it.run() }.pending("k").isEmpty())
        dir.deleteRecursively()
    }

    @Test fun aDroppedStoryBattleBlacksOutInAnyOrdinarySave() {
        for (lock in listOf(false, true)) {
            val (host, _) = storyRunning(lock)
            host.flow.linkChanged(false)
            t += 60_000; host.tick()
            assertEquals("lock=$lock", listOf(true), host.engine.leaves)
        }
    }

    @Test fun aDroppedWildOrTrainerBattleNeverBlacksOut() {
        val (host, _) = running()
        host.flow.linkChanged(false)
        t += 60_000; host.tick()
        assertEquals(listOf(false), host.engine.leaves)
    }

    /** A host with its story join armed on both sides (the hook in, nothing picked). */
    private fun armedHost(): Pair<Dev, Dev> {
        val (host, partner) = storyRig()
        host.tick(14); pass(host, partner); partner.tick(); partner.flow.answerPrompt(true); pass(partner, host)
        host.tick(); pass(host, partner); partner.tick()
        assertTrue(host.flow.armed)
        return host to partner
    }

    private fun dropForAMinute(host: Dev) { host.flow.linkChanged(false); t += 30_000; host.tick(); t += 30_000; host.tick() }

    private fun assertBlackedOutWithoutABattle(host: Dev) {
        assertTrue("the hook is out without the game's battle", host.engine.storyDisarms >= 1)
        assertEquals("never the solo battle", 0, host.engine.storyCancels)
        host.tick(4)
        assertTrue("the held script is let go", host.engine.log.contains("release 1"))
        assertEquals(1, host.core.log.count { it == "whiteOut" })
        assertTrue(host.core.log.contains("save"))
        assertTrue(host.ui.notices.contains(CoopNotices.UNDONE))
        assertFalse(host.flow.armed)
    }

    @Test fun readyStoryJoinDroppedBlacksOutWithoutTheSoloBattle() {
        val (host, _) = armedHost()
        host.engine.story = CoopLinkEngine.STORY_READY      // a team is picked, the script is held
        host.flow.linkChanged(false)
        t += 30_000; host.tick()
        assertTrue("still waiting, nothing decided", host.flow.armed)
        assertTrue(host.ui.waits.isNotEmpty())
        t += 30_000; host.tick(2)
        assertBlackedOutWithoutABattle(host)
    }

    @Test fun cancelledPickOfADroppedStoryJoinIsTheSameAsReady() {
        val (host, _) = armedHost()
        dropForAMinute(host)
        assertTrue("nothing yet: the game is not at the hook", host.flow.armed)
        host.engine.story = CoopLinkEngine.STORY_CANCELLED  // the player backed out of the team screen
        host.tick(2)
        assertBlackedOutWithoutABattle(host)
    }

    @Test fun pickingStoryJoinDroppedKeepsTheHookUntilThePickFinishes() {
        val (host, _) = armedHost()
        host.engine.story = CoopLinkEngine.STORY_PICKING
        dropForAMinute(host); host.tick(10)
        assertEquals("the hook stays: taking it out would let the pick script start the real battle", 0, host.engine.storyDisarms)
        assertEquals(0, host.engine.storyCancels)
        assertTrue(host.core.log.none { it == "whiteOut" })
        assertTrue(host.flow.armed)
        host.engine.story = CoopLinkEngine.STORY_READY      // the pick finished: the script is held
        host.tick(2)
        assertBlackedOutWithoutABattle(host)
    }

    @Test fun armedStoryJoinDroppedKeepsTheHookUntilTheGameReachesIt() {
        val (host, _) = armedHost()
        dropForAMinute(host); host.tick(10)
        assertEquals(0, host.engine.storyDisarms); assertEquals(0, host.engine.storyCancels)
        assertTrue(host.core.log.none { it == "whiteOut" })
        assertTrue(host.ui.notices.contains(CoopNotices.UNDONE))
        // the game reaches the hook (the pick screen opens and is backed out of): the loss runs
        host.engine.story = CoopLinkEngine.STORY_PICKING; host.tick(2)
        host.engine.story = CoopLinkEngine.STORY_CANCELLED; host.tick(2)
        assertBlackedOutWithoutABattle(host)
    }

    @Test fun droppedStoryJoinWhoseHookWentByItselfStartsNothing() {
        val (host, _) = armedHost()
        dropForAMinute(host)
        host.engine.story = CoopLinkEngine.STORY_IDLE       // another trainer's battle went by the hook
        host.tick(5)
        assertFalse(host.flow.armed)
        assertEquals(0, host.engine.storyCancels)
        assertTrue(host.core.log.none { it == "whiteOut" })
    }

    @Test fun aDroppedJoinIgnoresThePartnersCancel() {
        val (host, _) = armedHost()
        dropForAMinute(host)
        host.flow.onMessage(CoopBattleMsg.Cancel(4242, CoopCancel.CLOSED))
        host.tick(3)
        assertEquals("no solo battle from the cancel either", 0, host.engine.storyCancels)
        assertEquals(0, host.engine.storyDisarms)
        assertTrue(host.flow.armed)
    }

    @Test fun armedStoryJoinCarriesOnWhenTheLinkIsBackWithinAMinute() {
        val (host, partner) = armedHost()
        host.flow.linkChanged(false)
        t += 45_000; host.tick()
        host.flow.linkChanged(true)
        t += 60_000; host.tick(3)
        assertTrue(host.flow.armed)
        assertTrue(host.core.log.none { it == "whiteOut" })
        host.engine.story = CoopLinkEngine.STORY_READY; partner.engine.story = CoopLinkEngine.STORY_READY
        both(host, partner, 4)
        assertTrue(host.flow.battle.active)
    }

    @Test fun readyDropWhoseGameNeverFreesStillEndsInABlackout() {
        val (host, _) = armedHost()
        host.engine.story = CoopLinkEngine.STORY_READY
        host.engine.releaseDefault = -1                          // the game never frees
        dropForAMinute(host); host.tick(3)
        t += CoopLinkFlow.ABORT_WAIT_MS + 1_000; host.tick(3)
        assertTrue(host.core.log.none { it == "whiteOut" })
        assertTrue("the blackout is still owed: the battle counts as busy, no new offers", host.flow.busy)
        host.tick(20)
        assertTrue(host.core.log.none { it == "whiteOut" })
        assertEquals("never the solo battle", 0, host.engine.storyCancels)
        host.engine.releaseDefault = 1                           // the game is free
        host.tick(5)
        assertEquals(1, host.core.log.count { it == "whiteOut" })
        assertTrue(host.core.log.contains("save"))
        assertFalse(host.flow.busy)
    }

    @Test fun readyBlackoutWaitsForTheGameToFree() {
        val (host, _) = armedHost()
        host.engine.story = CoopLinkEngine.STORY_READY
        host.engine.releaseAnswers += listOf(-1, -1)          // the pick screen is still up
        dropForAMinute(host); host.tick(3)
        assertTrue(host.core.log.none { it == "whiteOut" })
        host.tick(5)
        assertEquals(1, host.core.log.count { it == "whiteOut" })
        assertEquals(0, host.engine.storyCancels)
    }

    @Test fun closingAnArmedStoryJoinNeverStartsTheSoloBattle() {
        for (st in listOf(CoopLinkEngine.STORY_ARMED, CoopLinkEngine.STORY_PICKING)) {
            val (host, _) = armedHost()
            host.engine.story = st
            host.flow.stopNow()
            assertEquals("state $st", 0, host.engine.storyCancels)
            assertEquals(1, host.engine.storyDisarms)
            assertFalse(host.engine.log.contains("release 1"))
        }
        val (host, _) = armedHost()
        host.engine.story = CoopLinkEngine.STORY_READY          // a held script is let go, no battle
        host.flow.stopNow()
        assertEquals(0, host.engine.storyCancels); assertEquals(1, host.engine.storyDisarms)
        assertTrue(host.engine.log.contains("release 1"))
    }

    // ---- the host's own catch survives a stop ----

    @Test fun hostsOwnCatchIsKeptOnDiskUntilGivenAndSaved() {
        val (host, partner) = running()
        host.core.field = false                               // the give waits: the store is what holds the Pokemon
        host.engine.caught = byteArrayOf(1) + caughtMon
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.CAUGHT)
        both(host, partner, 4)
        assertEquals("kept until it is saved", 1, host.store.pending("pair#own").size)
        host.core.field = true
        host.tick(8)
        assertTrue(host.core.log.contains("giveMon")); assertTrue(host.core.log.contains("save"))
        assertTrue("given and saved: gone from the store", host.store.pending("pair#own").isEmpty())
    }

    @Test fun hostsOwnCatchIsGivenOnTheNextLaunchWhenTheAppStoppedFirst() {
        val store = MemoryCatchStore()
        val (host, partner) = running(hostStore = store)
        host.core.field = false
        host.engine.caught = byteArrayOf(1) + caughtMon
        host.engine.state = CoopLinkEngine.STATE_ENDED; host.engine.result = linkResult(CoopAfter.CAUGHT)
        both(host, partner, 4)
        host.flow.stopNow()                                   // the app stops before the give
        assertEquals(1, store.pending("pair#own").size)
        val again = Dev("host2", 1111, 0, "ASH", ownParty = emptyList(), store = store)   // a new launch over the same file
        again.tick(8)
        assertTrue("given on the next launch", again.core.log.contains("giveMon"))
        assertTrue(again.core.log.contains("save"))
        assertTrue(store.pending("pair#own").isEmpty())
    }

    @Test fun hostsOwnCatchAlreadyInThePartyIsOnlySavedOnTheNextLaunch() {
        val store = MemoryCatchStore()
        store.addPending("pair#own", 5, caughtMon)
        val again = Dev("host2", 1111, 0, "ASH", store = store)      // the party (a resume state) already has it
        again.tick(8)
        assertTrue(again.core.log.none { it == "giveMon" })
        assertTrue(again.core.log.contains("save"))
        assertTrue(store.pending("pair#own").isEmpty())
    }
}
