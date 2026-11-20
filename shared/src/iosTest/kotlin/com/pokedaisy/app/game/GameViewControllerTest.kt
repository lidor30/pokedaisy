@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkApiCore
import com.pokedaisy.app.game.GameSession.FrameWork
import kotlinx.cinterop.toKString
import platform.MetalKit.MTKView
import platform.posix.getenv
import platform.posix.unlink
import kotlin.test.*

class GameViewControllerTest {
    @Test fun nativeFramesResumeAfterHoldPauseAndRewind() {
        val dir = assertNotNull(getenv("PK_DIAGNOSTIC_DIR")?.toKString()).trimEnd('/')
        val path = "$dir/view-controller-resume.ss"
        val controller = GameViewController("$dir/diagnostic.gb", null) { error("Diagnostic ROM failed to open") }
        val session = GameSession(PkApiCore, PkApiCore::callSafe) { _, e -> throw e }
        val events = mutableListOf<String>()
        val passes = mutableListOf<FrameWork>()
        var runningFrames = 0
        var suspends = 0
        var held = true
        controller.speed = 0.0
        controller.rewindEnabled = true
        controller.frameGate = { keys -> keys.takeUnless { held } }
        controller.beforeFrame = { work ->
            passes += work
            session.servicePending(work, gba = false, gbOnField = { true })
        }
        controller.afterFrame = { runningFrames++ }
        controller.onForeground = { events += "foreground" }
        controller.beforeSuspend = {
            assertFalse(controller.playingForward)
            suspends++
            controller.withState { PkApiCore.saveState(path) }
        }

        try {
            controller.loadViewIfNeeded()
            assertTrue(controller.isRunning)
            val view = controller.view as MTKView
            val delegate = assertNotNull(view.delegate)
            fun refresh() = delegate.drawInMTKView(view)

            session.postForward { events += "forward patch" }
            session.post { events += "held callback" }
            session.requestField(false, delayFrames = 1) { _, _ -> events += "field job" }

            repeat(3) { refresh() }
            assertEquals(List(3) { FrameWork.HELD }, passes)
            assertEquals(listOf("held callback"), events)
            assertEquals(0, runningFrames)

            assertTrue(controller.pause())
            assertTrue(view.paused)
            assertEquals(1, suspends)
            refresh()
            assertEquals(3, passes.size)
            assertEquals(0, runningFrames)

            held = false
            controller.speed = 1.0
            controller.resume()
            assertFalse(view.paused)
            refresh()
            assertEquals(FrameWork.ADVANCING, passes.last())
            assertEquals(listOf("held callback", "foreground", "forward patch"), events)
            assertEquals(1, runningFrames)

            controller.speed = 0.0
            refresh()
            assertEquals("field job", events.last())
            assertTrue(runningFrames > 1)

            session.postForward { events += "after rewind" }
            session.post { events += "rewind callback" }
            assertTrue(controller.canRewind)
            controller.rewindHold.update(RewindHold.Source.TOUCH, true)
            controller.applyRewindHold()
            refresh()
            assertEquals(FrameWork.REWINDING, passes.last())
            assertEquals("rewind callback", events.last())
            assertFalse(controller.playingForward)

            controller.rewindHold.update(RewindHold.Source.TOUCH, false)
            controller.applyRewindHold()
            refresh()
            assertTrue(controller.playingForward)
            assertEquals(FrameWork.ADVANCING, passes.last())
            assertEquals("after rewind", events.last())

            controller.close()
            assertEquals(2, suspends)
            assertFalse(controller.isRunning)
            assertNull(PkApiCore.readBytes(0xC000, 1))
            val framesAtClose = runningFrames
            refresh()
            assertEquals(framesAtClose, runningFrames)
        } finally {
            controller.close()
            session.close()
            unlink(path)
        }
    }
}
