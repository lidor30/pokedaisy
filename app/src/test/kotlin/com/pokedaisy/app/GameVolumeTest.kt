package com.pokedaisy.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** SOUND > GAME VOLUME / MUTE GAME: the gain every player of the game's sound uses. */
class GameVolumeTest {
    @After fun reset() = GameVolume.set(100, false)

    @Test fun fullVolumeIsTheDevicesOwn() {
        GameVolume.set(100, false)
        assertEquals(1f, GameVolume.gain, 0f)
    }

    @Test fun lowerStepsFollowAnAudioTaper() {
        GameVolume.set(50, false)
        assertEquals(0.25f, GameVolume.gain, 1e-6f)
        GameVolume.set(10, false)
        assertEquals(0.01f, GameVolume.gain, 1e-6f)
    }

    @Test fun muteSilencesAndKeepsTheVolume() {
        var heard = 0
        val l: () -> Unit = { heard++ }
        GameVolume.addListener(l)
        GameVolume.set(70, true)
        assertEquals(0f, GameVolume.gain, 0f)
        assertEquals(70, GameVolume.percent)
        GameVolume.set(GameVolume.percent, false)
        assertEquals(0.49f, GameVolume.gain, 1e-6f)
        GameVolume.removeListener(l)
        assertEquals(2, heard)
    }
}
