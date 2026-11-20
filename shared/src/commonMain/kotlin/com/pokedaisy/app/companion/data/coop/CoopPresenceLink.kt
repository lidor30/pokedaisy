package com.pokedaisy.app.companion.data.coop

import kotlinx.coroutines.flow.Flow

/**
 * The co-op presence seam: how the 20-byte [PresenceCodec] updates reach the other device and come back from it.
 * The transport (the linked connection) implements it; presence does nothing while no link is set.
 */
interface CoopPresenceLink {
    /** Sends one encoded update. Best effort: a lost one is replaced by the next, ~100 ms later. */
    fun send(bytes: ByteArray)

    /** Every message the partner sent, raw: [PresenceCodec.decode] checks it before anything uses it. */
    val received: Flow<ByteArray>
}
