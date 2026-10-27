package com.pokedaisy.app.companion.coop

/** The link's seam to the network: [RelayCoopTransport] in the app, an in-memory pair in tests. */
interface CoopTransport {
    /** 0 started; -4 already started; other negatives, a failure (see [RelayCoopTransport.start]). */
    fun start(secretKey: ByteArray, relayUrl: String?, relayOnly: Boolean): Int
    fun endpointId(): CoopPeerId?
    fun ticket(): String?
    fun setPolicy(allowed: Set<CoopPeerId>, pairingOpen: Boolean, lanUserData: String?)
    fun connect(ticket: String): Int
    fun accept(peer: CoopPeerId)
    fun deny(peer: CoopPeerId)
    fun disconnect(peer: CoopPeerId)
    fun send(peer: CoopPeerId, frame: ByteArray): Boolean
    fun poll(): CoopEvent?
    fun close()
}

/** A log line that never throws (the JVM tests have no android.util.Log). */
internal fun coopLog(message: String) {
    try {
        android.util.Log.w("pokedaisy/coop", message)
    } catch (_: RuntimeException) {
    } catch (_: LinkageError) {
    }
}
