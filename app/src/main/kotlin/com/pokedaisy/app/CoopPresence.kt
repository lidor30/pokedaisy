package com.pokedaisy.app

import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.coop.CoopPresenceLink
import com.pokedaisy.app.companion.data.coop.PartnerPlacement
import com.pokedaisy.app.companion.data.coop.PresenceCodec
import com.pokedaisy.app.companion.data.coop.PresenceLook
import com.pokedaisy.app.companion.data.coop.PresenceMode
import com.pokedaisy.app.companion.data.coop.PresenceSmoother
import com.pokedaisy.app.companion.data.coop.PresenceUpdate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The native half of co-op presence ([MgbaCore] in the app, a fake in tests). Friends are drawn in [slots] native slots;
 * the one-argument [setArt] / [place] are slot 0's (all a single-partner core has).
 */
interface PartnerCore {
    /** pk_partner_self: PK_PARTNER_SELF_* order (VALID, GROUP, NUM, FX, FY, FACING, MODE, STEP), null = not a known field. */
    fun self(): IntArray?
    fun setArt(art: ByteArray?)
    /** null = hide the partner. */
    fun place(p: PartnerPlacement?)

    /** How many friends can be drawn at once. */
    val slots: Int get() = 1
    fun setArt(slot: Int, art: ByteArray?) { if (slot == 0) setArt(art) }
    fun place(slot: Int, p: PartnerPlacement?) { if (slot == 0) place(p) }
}

/** [MgbaCore] as a [PartnerCore]: PK_PARTNER_MAX (4) slots. */
object MgbaPartnerCore : PartnerCore {
    override fun self(): IntArray? = MgbaCore.pkPartnerSelf()
    override fun setArt(art: ByteArray?) = setArt(0, art)
    override fun place(p: PartnerPlacement?) = place(0, p)
    override val slots: Int get() = 4
    override fun setArt(slot: Int, art: ByteArray?) = MgbaCore.pkPartnerSetN(slot, art)
    override fun place(slot: Int, p: PartnerPlacement?) {
        if (p == null) MgbaCore.pkPartnerPlaceN(slot, false, 0, 0, 0, false, -1, 0)
        else MgbaCore.pkPartnerPlaceN(slot, true, p.fineX, p.fineY, p.frame, p.hflip, p.blobFrame, p.bob)
    }
}

/**
 * A link that also says who sent each update: the friends' presence ([CoopPresenceChannel] over the co-op friends). Its
 * [CoopPresenceLink.send] goes to every friend; [receivedFrom] is (friend key, update) and [gone] names a friend whose
 * link dropped (their hero goes at once instead of after [CoopPresence.STALE_MS]).
 */
interface CoopPresenceFriendsLink : CoopPresenceLink {
    val receivedFrom: kotlinx.coroutines.flow.Flow<Pair<String, ByteArray>>
    val gone: kotlinx.coroutines.flow.Flow<String>
}

/**
 * Co-op presence on the emu thread: reads the local player every game frame ([PartnerCore.self]: its step counter needs
 * every frame, FF included), sends an update about every [SEND_MS] of wall clock (and at once on a map change) through
 * [link], and places every friend on this map from their own [PresenceSmoother] each frame. Does nothing while [link] is null.
 *
 * Friends: each sender (a key: the friend's peer id; [onReceived] alone is the key "") has its own smoother and art. Up to
 * [PartnerCore.slots] are drawn at once: a friend keeps its slot while shown; a free slot goes to the nearest friend not
 * shown yet. A slot's art is set when it changes hands or its friend's look changes. A friend silent for [STALE_MS] is
 * forgotten (so is one [forget] names).
 *
 * [onReceived] / [onReceivedFrom] are called from the link's collector thread: they only queue; the smoothers are touched
 * under [lock] by [onFrame] / [stop] (the emu thread). [art] must not block (the app's builds off-thread and answers null
 * until it's ready): it is asked again every [ART_RETRY_FRAMES] frames until the wanted look is had, the friend's own plain
 * hero standing in meanwhile.
 */
class CoopPresence(
    private val core: PartnerCore,
    private val clock: () -> Long,
    private val art: (female: Boolean, look: LookChar?) -> ByteArray?,
) {
    @Volatile var link: CoopPresenceLink? = null
    @Volatile var myLook: LookChar? = null
    @Volatile var myFemale: Boolean = false

    private class Arrival(val key: String, val u: PresenceUpdate, val atMs: Long)

    /** One sender: its buffer, the look it wears and when it was last heard. */
    private class Remote {
        val smoother = PresenceSmoother()
        var female = false
        var look = 0
        var heardMs = 0L
        var place: PartnerPlacement? = null   // this frame's
    }

    /** What a native slot shows: whose hero, and how far its art got. */
    private class Slot {
        var key: String? = null
        var female = false
        var look = -1
        var haveArt = ART_NONE
        var artFrames = 0
        var shown = true        // the last place() was non-null - or unknown (see [cleared])
    }

    private val inbox = ConcurrentLinkedQueue<Arrival>()
    private val forgotten = ConcurrentLinkedQueue<String>()
    private val lock = Any()
    private val remotes = LinkedHashMap<String, Remote>()
    private val slots = Array(maxOf(1, core.slots)) { Slot() }

    private var lastSendMs = 0L
    private var sentOnce = false
    private var sentGroup = -1
    private var sentNum = -1

    private var cleared = false        // place(null) went to every slot since the link went / stop
    private var lastField: IntArray? = null   // self() on the last field frame

    private val lastByKey = ConcurrentHashMap<String, PresenceUpdate>()

    /** The single partner's last valid update ([onReceived]'s; any thread). */
    @Volatile var lastReceived: PresenceUpdate? = null
        private set

    /** [key]'s last update (any thread): where co-op battles judge "nearby" from. */
    fun lastReceivedOf(key: String): PresenceUpdate? = lastByKey[key]

    /** Every sender heard lately, with its last update (any thread). */
    fun lastReceivedAll(): Map<String, PresenceUpdate> = HashMap(lastByKey)

    fun onReceived(bytes: ByteArray) = onReceivedFrom(SINGLE, bytes)

    fun onReceivedFrom(key: String, bytes: ByteArray) {
        val u = PresenceCodec.decode(bytes) ?: return
        if (key == SINGLE) lastReceived = u
        lastByKey[key] = u
        inbox.add(Arrival(key, u, clock()))
    }

    /** [key]'s link dropped: their hero goes on the next frame. */
    fun forget(key: String) {
        lastByKey.remove(key)
        forgotten.add(key)
    }

    fun onFrame(field: Boolean) = synchronized(lock) {
        val link = link
        if (link == null) {
            inbox.clear()
            if (!cleared) { for (i in slots.indices) placeSlot(i, null); cleared = true }
            return@synchronized
        }
        cleared = false
        val now = clock()
        while (true) {
            val key = forgotten.poll() ?: break
            drop(key)
        }
        while (true) {
            val a = inbox.poll() ?: break
            val r = remotes.getOrPut(a.key) { Remote() }
            r.smoother.push(a.u, a.atMs)
            r.heardMs = a.atMs
            if (a.u.valid) { r.female = a.u.female; r.look = a.u.lookId }
        }
        for ((key, r) in remotes.toList()) if (now - r.heardMs > STALE_MS) drop(key)
        val self = core.self()
        val group = self?.getOrNull(GROUP) ?: -1
        val num = self?.getOrNull(NUM) ?: -1
        if (!sentOnce || now - lastSendMs >= SEND_MS || group != sentGroup || num != sentNum) {
            sentOnce = true
            lastSendMs = now
            sentGroup = group
            sentNum = num
            link.send(PresenceCodec.encode(update(self, field, now)))
        }
        for (r in remotes.values) r.place = if (field) r.smoother.at(now, group, num) else null
        assignSlots(self)
        for (i in slots.indices) {
            val s = slots[i]
            val r = s.key?.let { remotes[it] }
            if (r != null) serviceArt(i, s, r)
            val p = r?.place?.takeIf { s.haveArt != ART_NONE }
            if (p != null) placeSlot(i, p)
            else if (s.shown) placeSlot(i, null)
        }
    }

    /** Link gone / game closed: nothing drawn, no art kept, the buffers empty. */
    fun stop() = synchronized(lock) {
        inbox.clear()
        forgotten.clear()
        lastReceived = null
        lastByKey.clear()
        remotes.clear()
        for (i in slots.indices) {
            placeSlot(i, null)
            core.setArt(i, null)
            slots[i].apply { key = null; look = -1; haveArt = ART_NONE; shown = true }
        }
        cleared = true
        sentOnce = false
        lastField = null
    }

    private fun drop(key: String) {
        remotes.remove(key) ?: return
        lastByKey.remove(key)
        if (key == SINGLE) lastReceived = null
        for (i in slots.indices) if (slots[i].key == key) release(i)
    }

    private fun release(i: Int) {
        val s = slots[i]
        s.key = null
        if (s.shown) placeSlot(i, null)
    }

    /** Shown friends keep their slots; the free slots go to the nearest friends that want one (on this map now). */
    private fun assignSlots(self: IntArray?) {
        for (i in slots.indices) {
            val k = slots[i].key ?: continue
            if (remotes[k]?.place == null) release(i)
        }
        val holding = slots.mapNotNull { it.key }.toSet()
        val fx = self?.getOrNull(FX) ?: 0
        val fy = self?.getOrNull(FY) ?: 0
        val waiting = remotes.entries
            .filter { it.key !in holding && it.value.place != null }
            .sortedBy { e -> e.value.place!!.let { Math.abs(it.fineX - fx) + Math.abs(it.fineY - fy) } }
        var next = 0
        for (i in slots.indices) {
            if (slots[i].key != null) continue
            if (next >= waiting.size) break
            slots[i].key = waiting[next++].key
            slots[i].look = -1   // new hands: its art again
        }
    }

    private fun placeSlot(i: Int, p: PartnerPlacement?) {
        core.place(i, p)
        slots[i].shown = p != null
    }

    // What self() gives, as an update. On the field it is remembered; in a battle or menu the player stays valid, standing
    // at that last field place (a value outside the wire ranges, or no map, sends "not on a map" instead of crashing).
    private fun update(self: IntArray?, field: Boolean, now: Long): PresenceUpdate {
        val s = self
        val ok = s != null && s.size > STEP && s[VALID] != 0 &&
            s[GROUP] in 0..255 && s[NUM] in 0..255 && s[FX] in 0..MAX_FINE && s[FY] in 0..MAX_FINE &&
            s[FACING] in 1..4 && s[MODE] in PresenceMode.WALK..PresenceMode.SURF && s[STEP] in 0..255
        val t = now and 0xFFFFFFFFL
        val female = myFemale
        val look = PresenceLook.idOf(myLook)
        if (!ok) {
            lastField = null
            return PresenceUpdate(t, false, female, 0, 0, 0, 0, 1, PresenceMode.WALK, 0, look)
        }
        s!!
        if (field) lastField = s.copyOf()
        val f = if (field) s else lastField ?: s
        val mode = if (field) f[MODE] else PresenceMode.WALK
        return PresenceUpdate(t, true, female, s[GROUP], s[NUM], f[FX], f[FY], f[FACING], mode, f[STEP], look)
    }

    /** The slot's art: its friend's look, asked for until had (their plain hero meanwhile). */
    private fun serviceArt(i: Int, s: Slot, r: Remote) {
        if (s.look != r.look || s.female != r.female) {
            s.female = r.female
            s.look = r.look
            s.haveArt = ART_NONE
            s.artFrames = ART_RETRY_FRAMES   // ask on this frame
        }
        if (s.haveArt == ART_FULL) return
        if (s.artFrames++ < ART_RETRY_FRAMES) return
        s.artFrames = 0
        val look = PresenceLook.charOf(s.look)
        art(s.female, look)?.let { core.setArt(i, it); s.haveArt = ART_FULL; return }
        if (look == null || s.haveArt != ART_NONE) return
        // The device lacks that hero: the friend's own Brendan / May until it has it.
        art(s.female, null)?.let { core.setArt(i, it); s.haveArt = ART_PLAIN }
    }

    companion object {
        /** The key of [onReceived]'s sender (a single partner). */
        const val SINGLE = ""
        /** A friend not heard from this long is forgotten (the smoother already hides them after 3 s). */
        const val STALE_MS = 10_000L
        private const val SEND_MS = 100L
        private const val ART_RETRY_FRAMES = 30
        private const val ART_NONE = 0
        private const val ART_PLAIN = 1
        private const val ART_FULL = 2
        private const val MAX_FINE = 32767
        // PK_PARTNER_SELF_*
        private const val VALID = 0
        private const val GROUP = 1
        private const val NUM = 2
        private const val FX = 3
        private const val FY = 4
        private const val FACING = 5
        private const val MODE = 6
        private const val STEP = 7
    }
}
