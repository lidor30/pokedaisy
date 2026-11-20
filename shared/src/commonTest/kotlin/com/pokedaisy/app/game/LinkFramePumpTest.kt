package com.pokedaisy.app.game

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkFramePumpTest {
    @Test
    fun focusClearsOldKeysAndDropsPreviouslyUnheardAudio() {
        val events = mutableListOf<String>()
        val pump = LinkFramePump(2, { peer, mask -> events += "keys $peer $mask" }, { peer, audio ->
            events += "audio $peer"
            audio[0] = peer.toShort()
            4
        })
        pump.prepare(0, 1)
        assertEquals(4, pump.readFocusedAudio())
        pump.prepare(0, 2)
        pump.prepare(1, 4)
        assertEquals(4, pump.readFocusedAudio())
        assertEquals(1.toShort(), pump.audio[0])
        assertEquals(listOf(
            "keys 0 0", "keys 1 0", "audio 0", "keys 0 1", "audio 0",
            "keys 0 2", "keys 0 0", "keys 1 0", "audio 1", "keys 1 4", "audio 1",
        ), events)
    }
}
