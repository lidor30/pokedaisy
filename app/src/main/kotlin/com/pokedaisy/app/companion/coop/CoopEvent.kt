package com.pokedaisy.app.companion.coop

/**
 * What [CoopTransport.poll] hands back. A repeated [Connected] for the same peer is a new
 * connection, not a duplicate.
 */
sealed interface CoopEvent {
    data class Ready(val ticket: String) : CoopEvent

    /** [kind]: 0 none (LAN and BY IP only), 1 custom. */
    data class Relay(val kind: Int, val url: String) : CoopEvent

    data class Connected(val peer: CoopPeerId, val pending: Boolean, val outgoing: Boolean, val direct: Boolean) : CoopEvent

    class Frame(val peer: CoopPeerId, val bytes: ByteArray) : CoopEvent

    /** [code]: a reason code of the shared table (0 CLOSED .. 8 DUPLICATE). */
    data class Disconnected(val peer: CoopPeerId, val code: Int) : CoopEvent

    data class DialFailed(val code: Int, val message: String) : CoopEvent

    data class LanPeer(val peer: CoopPeerId, val userData: String) : CoopEvent

    data class LanGone(val peer: CoopPeerId) : CoopEvent

    /** Not emitted any more (the transport has no path changes to report); kept for the link's handling of it. */
    data class Path(val peer: CoopPeerId, val direct: Boolean) : CoopEvent

    /** Logged only. */
    data class Error(val message: String) : CoopEvent
}
