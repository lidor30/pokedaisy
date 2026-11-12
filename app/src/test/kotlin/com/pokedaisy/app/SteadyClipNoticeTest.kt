package com.pokedaisy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SteadyClipNoticeTest {
    private val clip = File("song.flac")

    @Test fun aClipIsToldOnce() {
        val notice = SteadyClipNotice()
        assertTrue(notice.changed(clip))
        assertFalse(notice.changed(clip))
        assertTrue(notice.changed(null))
        assertFalse(notice.changed(null))
    }

    @Test fun theSameClipIsToldAgainAfterASleep() {
        val notice = SteadyClipNotice()
        notice.changed(clip)
        notice.forget()   // the player was released as the screen went off
        assertTrue(notice.changed(clip))
    }

    @Test fun noClipAfterASleepTellsNothing() {
        val notice = SteadyClipNotice()
        notice.changed(clip)
        notice.forget()
        assertFalse(notice.changed(null))   // the released player is already silent
    }
}
