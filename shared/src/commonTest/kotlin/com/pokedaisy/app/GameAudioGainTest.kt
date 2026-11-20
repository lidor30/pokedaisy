package com.pokedaisy.app

import kotlin.test.Test
import kotlin.test.assertEquals

class GameAudioGainTest {
    @Test fun sliderUsesTheSameAudioTaperAcrossPlayers() {
        assertEquals(0f, gameAudioGain(-1, false))
        assertEquals(0.25f, gameAudioGain(50, false))
        assertEquals(1f, gameAudioGain(101, false))
    }

    @Test fun muteOverridesAnyVolume() {
        assertEquals(0f, gameAudioGain(100, true))
    }
}
