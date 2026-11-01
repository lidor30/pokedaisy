package com.pokedaisy.app

import com.pokedaisy.app.companion.data.LookChar
import com.pokedaisy.app.companion.data.coop.CoopPresenceLink
import com.pokedaisy.app.companion.data.coop.PartnerPlacement
import com.pokedaisy.app.companion.data.coop.PresenceCodec
import com.pokedaisy.app.companion.data.coop.PresenceLook
import com.pokedaisy.app.companion.data.coop.PresenceMode
import com.pokedaisy.app.companion.data.coop.PresenceSmoother
import com.pokedaisy.app.companion.data.coop.PresenceUpdate
import java.util.concurrent.ConcurrentLinkedQueue

/** The native half of co-op presence ([MgbaCore] in the app, a fake in tests). */
interface PartnerCore {
    /** pk_partner_self: PK_PARTNER_SELF_* order (VALID, GROUP, NUM, FX, FY, FACING, MODE, STEP), null = not a known field. */
    fun self(): IntArray?
    fun setArt(art: ByteArray?)
    /** null = hide the partner. */
    fun place(p: PartnerPlacement?)
}

/** [MgbaCore] as a [PartnerCore]. */
object MgbaPartnerCore : PartnerCore {
    override fun self(): IntArray? = MgbaCore.pkPartnerSelf()
    override fun setArt(art: ByteArray?) = MgbaCore.pkPartnerSet(art)
    override fun place(p: PartnerPlacement?) {
        if (p == null) MgbaCore.pkPartnerPlace(false, 0, 0, 0, false, -1, 0)
        else MgbaCore.pkPartnerPlace(true, p.fineX, p.fineY, p.frame, p.hflip, p.blobFrame, p.bob)
    }
}

/**
 * Co-op presence on the emu thread: reads the local player every game frame ([PartnerCore.self]: its step counter needs
 * every frame, FF included), sends an update about every [SEND_MS] of wall clock (and at once on a map change) through
 * [link], and places the partner from [PresenceSmoother] each frame. Does nothing while [link] is null.
 *
 * [onReceived] is called from the link's collector thread: it only queues; the smoother is touched under [lock] by
 * [onFrame] / [stop] (the emu thread). [art] must not block (the app's builds off-thread and answers null until it's
 * ready): it is asked again every [ART_RETRY_FRAMES] frames until the wanted look is had, the partner's own plain hero
 * standing in meanwhile.
 */
class CoopPresence(
    private val core: PartnerCore,
    private val clock: () -> Long,
    private val art: (female: Boolean, look: LookChar?) -> ByteArray?,
) {
    @Volatile var link: CoopPresenceLink? = null
    @Volatile var myLook: LookChar? = null
    @Volatile var myFemale: Boolean = false

    private class Arrival(val u: PresenceUpdate, val atMs: Long)

    private val inbox = ConcurrentLinkedQueue<Arrival>()
    private val lock = Any()
    private val smoother = PresenceSmoother()

    private var lastSendMs = 0L
    private var sentOnce = false
    private var sentGroup = -1
    private var sentNum = -1

    // The last place() was non-null - or unknown: true until this presence has said otherwise, so its first frame with a link
    // places null once (the native request may still hold a previous game's or session's partner).
    private var shown = true
    private var cleared = false        // place(null) went to the core since the link went / stop
    private var wantFemale = false
    private var wantLook = 0
    private var haveArt = ART_NONE
    private var artFrames = 0
    private var haveUpdate = false
    private var lastField: IntArray? = null   // self() on the last field frame

    /** The partner's last valid update (any thread): where co-op battles judge "nearby" from. */
    @Volatile var lastReceived: PresenceUpdate? = null
        private set

    fun onReceived(bytes: ByteArray) {
        val u = PresenceCodec.decode(bytes) ?: return
        lastReceived = u
        inbox.add(Arrival(u, clock()))
    }

    fun onFrame(field: Boolean) = synchronized(lock) {
        val link = link
        if (link == null) {
            inbox.clear()
            if (!cleared) { core.place(null); shown = false; cleared = true }
            return@synchronized
        }
        cleared = false
        while (true) {
            val a = inbox.poll() ?: break
            smoother.push(a.u, a.atMs)
            if (a.u.valid) noteLook(a.u)
        }
        val now = clock()
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
        if (haveUpdate) serviceArt()
        val p = if (field) smoother.at(now, group, num) else null
        if (p != null) { core.place(p); shown = true }
        else if (shown) { core.place(null); shown = false }
    }

    /** Link gone / game closed: nothing drawn, no art kept, the buffer empty. */
    fun stop() = synchronized(lock) {
        inbox.clear()
        lastReceived = null
        smoother.reset()
        core.place(null)
        core.setArt(null)
        shown = true
        cleared = true
        sentOnce = false
        haveArt = ART_NONE
        haveUpdate = false
        lastField = null
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

    private fun noteLook(u: PresenceUpdate) {
        if (haveUpdate && u.female == wantFemale && u.lookId == wantLook) return
        haveUpdate = true
        wantFemale = u.female
        wantLook = u.lookId
        haveArt = ART_NONE
        artFrames = ART_RETRY_FRAMES   // ask on this frame
    }

    private fun serviceArt() {
        if (haveArt == ART_FULL) return
        if (artFrames++ < ART_RETRY_FRAMES) return
        artFrames = 0
        val look = PresenceLook.charOf(wantLook)
        art(wantFemale, look)?.let { core.setArt(it); haveArt = ART_FULL; return }
        if (look == null || haveArt != ART_NONE) return
        // The device lacks that hero: the partner's own Brendan / May until it has it.
        art(wantFemale, null)?.let { core.setArt(it); haveArt = ART_PLAIN }
    }

    private companion object {
        const val SEND_MS = 100L
        const val ART_RETRY_FRAMES = 30
        const val ART_NONE = 0
        const val ART_PLAIN = 1
        const val ART_FULL = 2
        const val MAX_FINE = 32767
        // PK_PARTNER_SELF_*
        const val VALID = 0
        const val GROUP = 1
        const val NUM = 2
        const val FX = 3
        const val FY = 4
        const val FACING = 5
        const val MODE = 6
        const val STEP = 7
    }
}
