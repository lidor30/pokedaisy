package com.pokedaisy.app.companion.coop

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.IOException

/** One friend of the running save and how its link stands. */
data class CoopFriendState(val pair: CoopPair, val state: CoopState) {
    val peer: CoopPeerId get() = pair.partner.peer
    val connected: Boolean get() = state is CoopState.Connected
}

/** An app message and the friend it came from. */
class CoopFriendMessage(val peer: CoopPeerId, val message: CoopMessage)

/**
 * The links to every friend of the running save, at once: one [CoopLinkImpl] (shared mode) per pairing, so a friend who
 * plays the same game sees this player while both are online. The transport keeps one connection per peer already (a pair
 * room each on the relay); this class only fans the session's events and ticks out to the links, allows exactly the save's
 * friends ([policy]) and notices a friend added or removed meanwhile ([CoopPairs.version]). No threads of its own: the
 * session's poll loop calls [onEvent] and [tick], its control thread [startFor] / [stop].
 *
 * [friends] is redrawn after every call that can change a link's state. [messages] carries every friend's app messages,
 * each with its sender ([CoopLink.messages]' rules: types >= [CoopWire.FIRST_APP_TYPE], after a good HELLO).
 */
class CoopFriends(
    private val transport: CoopTransport,
    private val pairs: CoopPairs,
    private val ownHello: () -> CoopHello?,
    private val clock: () -> Long,
    /** Allows exactly these peers (the session skips it while an invite page holds the policy for pairing). */
    private val policy: (Set<CoopPeerId>) -> Unit = { transport.setPolicy(it, false, null) },
    private val backoffs: () -> CoopBackoff = { CoopBackoff() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {
    private class Entry(val pair: CoopPair, val link: CoopLinkImpl, val job: Job)

    private val lock = SynchronizedObject()

    private val links = LinkedHashMap<CoopPeerId, Entry>()
    private var save: CoopSaveId? = null
    private var seenVersion = -1

    private val _friends = MutableStateFlow<List<CoopFriendState>>(emptyList())

    /** The running save's friends, oldest pairing first; empty while no game runs. */
    val friends: StateFlow<List<CoopFriendState>> = _friends.asStateFlow()

    private val _messages = MutableSharedFlow<CoopFriendMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<CoopFriendMessage> = _messages.asSharedFlow()

    /** Starts a link to every friend of [save] (none: [friends] empty) and allows exactly them. */
    fun startFor(save: CoopSaveId) {
        synchronized(lock) {
            stopAll(bye = false)
            this.save = save
            reload(force = true)
        }
    }

    /** BYE to every connected friend, every link stopped, nothing allowed by this class any more. */
    fun stop() {
        synchronized(lock) {
            stopAll(bye = true)
            save = null
            seenVersion = -1
            publish()
        }
    }

    fun onEvent(e: CoopEvent) {
        synchronized(lock) {
            if (links.isEmpty()) return
            val peer = when (e) {
                is CoopEvent.Connected -> e.peer
                is CoopEvent.Frame -> e.peer
                is CoopEvent.Disconnected -> e.peer
                is CoopEvent.Path -> e.peer
                is CoopEvent.DialFailed -> e.peer
                else -> null
            }
            if (peer != null) {
                links[peer]?.link?.onEvent(e) ?: return
            } else {
                // A failed dial names no peer: every link that is dialing takes it as its own (CoopLinkImpl checks).
                for (entry in links.values) entry.link.onEvent(e)
            }
            publish()
        }
    }

    /** Due redials and HELLO timeouts of every link, and a friend added / removed since; every 250 ms. */
    fun tick() {
        synchronized(lock) {
            if (save != null && pairs.version != seenVersion) reload(force = false)
            for (entry in links.values) entry.link.tick()
            publish()
        }
    }

    /** One app message to [peer]; false unless that friend is connected (see [CoopLink.send]). */
    fun send(peer: CoopPeerId, type: Int, payload: ByteArray): Boolean =
        synchronized(lock) { links[peer]?.link?.send(type, payload) ?: false }

    /** One app message to every connected friend; how many took it. */
    fun broadcast(type: Int, payload: ByteArray): Int =
        synchronized(lock) { links.values.count { it.link.send(type, payload) } }

    /** The link to [peer], while it is a friend of the running save. */
    fun link(peer: CoopPeerId): CoopLink? = synchronized(lock) { links[peer]?.link }

    /** The running save's friends again: new ones get a link, removed ones lose theirs (BYE first). */
    private fun reload(force: Boolean) {
        val s = save ?: return
        seenVersion = pairs.version
        val now = try {
            pairs.friendsOf(s)
        } catch (e: IOException) {
            coopLog("pairs.json unreadable: ${e.message}")
            return
        }
        val wanted = now.associateBy { it.partner.peer }
        var removed = false
        for ((peer, entry) in links.toList()) {
            if (peer !in wanted) {
                links.remove(peer)
                end(entry, bye = true)
                removed = true
            }
        }
        val added = ArrayList<CoopLinkImpl>()
        for (p in now) {
            if (p.partner.peer in links) continue
            val link = CoopLinkImpl(transport, pairs, ownHello, clock, backoffs(), shared = true)
            val job = scope.launch { link.messages.collect { _messages.tryEmit(CoopFriendMessage(p.partner.peer, it)) } }
            links[p.partner.peer] = Entry(p, link, job)
            added.add(link)
        }
        if (force || removed || added.isNotEmpty()) policy(links.keys.toSet())
        for (p in now) if (links[p.partner.peer]?.link in added) links[p.partner.peer]!!.link.startFor(p)
        publish()
    }

    private fun stopAll(bye: Boolean) {
        val all = links.values.toList()
        links.clear()
        for (entry in all) end(entry, bye)
        if (all.isNotEmpty()) policy(emptySet())
    }

    private fun end(entry: Entry, bye: Boolean) {
        if (bye) entry.link.stop() else entry.link.startFor(null as CoopPair?)
        entry.job.cancel()
        if (!bye) transport.disconnect(entry.pair.partner.peer)
    }

    private fun publish() {
        _friends.value = links.values.map { e ->
            // The pair as the link last knew it (a refreshed ticket), else as loaded.
            CoopFriendState(e.pair, e.link.state.value)
        }
    }
}
