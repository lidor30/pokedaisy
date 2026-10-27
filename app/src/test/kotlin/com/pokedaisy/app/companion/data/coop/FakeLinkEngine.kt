package com.pokedaisy.app.companion.data.coop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The native bridge as the link driver and flow see it, every call logged; the answers are set by the test. */
class FakeLinkEngine : CoopLinkEngine {
    val log = ArrayList<String>()
    val outgoing = ArrayDeque<ByteArray>()
    val given = ArrayList<ByteArray>()
    val leaves = ArrayList<Boolean>()
    var state = CoopLinkEngine.STATE_BATTLE
    var stateCalls = 0
    var acceptGive = true
    var result: ByteArray? = null
    var caught: ByteArray? = null
    var resets = 0
    var settled = false
    var installOk = true
    var refusedText = ""
    override fun refused() = refusedText

    var hold = false
    var hostGoAnswers = ArrayDeque<Boolean>()
    var hostGoDefault = true
    var partnerGoAnswers = ArrayDeque<Int>()
    var partnerGoDefault = 1
    var storyArmOk = true
    var story = CoopLinkEngine.STORY_IDLE
    var storyGoOk = true
    var storyGoCalls = 0
    var storyCancels = 0
    var storyDisarms = 0
    val goArgs = ArrayList<String>()

    override fun take() = outgoing.removeFirstOrNull()
    override fun give(block: ByteArray): Boolean { if (!acceptGive) return false; given += block; return true }
    override fun state(): Int { stateCalls++; return state }
    var bad: ByteArray? = null
    var badCalls = 0
    override fun badBlocks(): ByteArray? { badCalls++; return bad }
    override fun leave(blackout: Boolean) { leaves += blackout; log += "leave $blackout" }
    override fun result() = result
    override fun caught() = caught
    override fun resetBridge() { resets++; log += "reset" }
    override fun settled() = settled
    override fun install(): Boolean { log += "install"; return installOk }
    override fun hostHold() = hold
    override fun hostGo(name: ByteArray, gender: Int, otId: Int): Boolean {
        val r = hostGoAnswers.removeFirstOrNull() ?: hostGoDefault
        log += "hostGo $r"; goArgs += "host:$gender:$otId"
        if (r) hold = false
        return r
    }
    override fun partnerGo(kind: Int, trainerA: Int, trainerB: Int, name: ByteArray, gender: Int, otId: Int): Int {
        val r = partnerGoAnswers.removeFirstOrNull() ?: partnerGoDefault
        log += "partnerGo $kind $trainerA $trainerB $r"; goArgs += "partner:$gender:$otId"
        return r
    }
    override fun storyArm(trainerId: Int): Boolean { log += "storyArm $trainerId"; if (storyArmOk) story = CoopLinkEngine.STORY_ARMED; return storyArmOk }
    override fun storyState() = story
    override fun storyGo(master: Boolean, name: ByteArray, gender: Int, otId: Int): Boolean {
        storyGoCalls++; log += "storyGo $master $storyGoOk"; goArgs += "story:$master:$gender:$otId"
        return storyGoOk
    }
    override fun storyCancel(): Boolean { storyCancels++; log += "storyCancel"; story = CoopLinkEngine.STORY_IDLE; return true }
    override fun storyDisarm() { storyDisarms++; log += "storyDisarm"; story = CoopLinkEngine.STORY_IDLE }
    var releaseAnswers = ArrayDeque<Int>()
    /** What releaseScript answers once [releaseAnswers] is empty (-1 = the game never frees). */
    var releaseDefault = 1
    override fun releaseScript(): Int { val r = releaseAnswers.removeFirstOrNull() ?: releaseDefault; log += "release $r"; return r }
}

class FakeLinkChannel : CoopBattleChannel {
    override var connected = true
    val sent = ArrayList<Pair<Int, ByteArray>>()
    override fun send(type: Int, payload: ByteArray): Boolean { if (!connected) return false; sent += type to payload; return true }
    override val received: Flow<Pair<Int, ByteArray>> = emptyFlow()
    val messages: List<CoopBattleMsg> get() = sent.map { CoopBattleWire.decode(it.first, it.second) ?: throw AssertionError("undecodable ${it.first}") }
}

class FakeLinkUi : CoopBattleUiSink {
    val waits = ArrayList<Int>()
    val notices = ArrayList<String>()
    var clears = 0
    override fun linkWait(secondsLeft: Int) { waits += secondsLeft }
    override fun notice(text: String) { notices += text }
    override fun clear() { clears++ }
}

fun linkBlock(kind: Int, seq: Int, n: Int = 4) =
    byteArrayOf(kind.toByte(), seq.toByte(), (n and 0xFF).toByte(), (n shr 8).toByte()) + ByteArray(n) { (seq + it).toByte() }

/** A 16-byte result as pk_coop_link_result writes it: outcome, blackout flag, trainers, prize paid. */
fun linkResult(outcome: Int, flags: Int = 0, trainerA: Int = 0, trainerB: Int = 0, paid: Int = 0): ByteArray {
    val r = ByteArray(16)
    r[0] = 1; r[1] = outcome.toByte(); r[2] = flags.toByte()
    r[4] = trainerA.toByte(); r[5] = (trainerA shr 8).toByte(); r[6] = trainerB.toByte(); r[7] = (trainerB shr 8).toByte()
    r[8] = paid.toByte(); r[9] = (paid shr 8).toByte(); r[10] = (paid shr 16).toByte(); r[11] = (paid shr 24).toByte()
    return r
}
