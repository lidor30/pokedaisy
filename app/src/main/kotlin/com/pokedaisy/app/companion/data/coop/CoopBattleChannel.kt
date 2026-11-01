package com.pokedaisy.app.companion.data.coop

import kotlinx.coroutines.flow.Flow

/** The link as the battle code sees it (plan 2's CoopLink behind an adapter): message types 0x0200..0x02FF only. */
interface CoopBattleChannel {
    val connected: Boolean

    /** False while not connected. */
    fun send(type: Int, payload: ByteArray): Boolean

    /** (type, payload) of every battle message that arrives. */
    val received: Flow<Pair<Int, ByteArray>>
}
