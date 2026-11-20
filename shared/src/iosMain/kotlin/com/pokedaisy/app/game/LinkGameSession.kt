@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import com.pokedaisy.app.core.PkLinkCore
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.GameController.GCController
import platform.QuartzCore.CADisplayLink
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/** One serial worker owns the link core. A display tick may have at most one frame batch in flight.
 * UIKit, controller polling, Metal submission and AVAudio stay on the main thread. */
class LinkGameSession(
    private val roms: List<String>,
    private val saves: List<String>,
    volume: Float,
    private val onOpen: (Boolean) -> Unit,
    private val onFocus: (Int) -> Unit,
    private val onFault: () -> Unit,
    private val finishSaves: () -> Unit,
    private val onClosed: (Boolean) -> Unit,
) {
    private class Frame(val width: Int, val height: Int) {
        val pixels = ByteArray(width * height * 4)
    }

    private val worker = dispatch_queue_create("com.pokedaisy.link", null)
    private val audio = GameAudio().also { it.volume = volume }
    private val input = GameInput()
    private val pictures = List(roms.size) { LinkGamePicture() }
    private val pacer = FramePacer()
    private val pump = LinkFramePump(roms.size, PkLinkCore::keys, PkLinkCore::readAudio)
    private val samples = ShortArray(pump.audio.size)

    // Only the worker reads or changes these.
    private var frames = emptyList<Frame>()
    private var workerOpen = false
    private var lastGeneration = -1

    private val observers = mutableListOf<NSObjectProtocol>()
    private var displayLink: CADisplayLink? = null
    private var requested = false
    private var running = false
    private var active = false
    private var closed = false
    private var busy = false
    private var generation = 0
    private var focused = 0
    private var leftDown = false
    private var rightDown = false
    private var blocked = false

    var fast = false
        set(value) {
            if (field == value) return
            field = value
            generation++
            audio.liveMuted = value
        }

    fun picture(slot: Int): UIView = pictures[slot].view

    fun start() {
        check(!requested && !closed)
        requested = true
        if (pictures.any { !it.available }) {
            onOpen(false)
            return
        }
        dispatch_async(worker) {
            workerOpen = PkLinkCore.open(roms, saves)
            if (workerOpen) {
                frames = List(roms.size) { slot -> Frame(PkLinkCore.width(slot), PkLinkCore.height(slot)) }
            }
            val opened = workerOpen
            dispatch_async(dispatch_get_main_queue()) {
                if (!closed) {
                    running = opened
                    if (opened) beginDisplay()
                    onOpen(opened)
                }
            }
        }
    }

    fun focus(slot: Int) {
        if (slot == focused || slot !in roms.indices || blocked) return
        input.releaseManualInput()
        input.touchKeys = 0
        input.touchTurbo = 0
        focused = slot
        generation++
        onFocus(slot)
    }

    fun touch(keys: Int, turbo: Int) {
        input.touchKeys = keys
        input.touchTurbo = turbo
    }

    fun blockInput(value: Boolean) {
        blocked = value
        input.editorCaptured(value)
    }

    fun close() {
        if (closed) return
        closed = true
        running = false
        pause()
        displayLink?.invalidate()
        displayLink = null
        val center = NSNotificationCenter.defaultCenter
        observers.forEach(center::removeObserver)
        observers.clear()
        audio.release()
        dispatch_async(worker) {
            val finished = if (workerOpen) {
                PkLinkCore.close(CLOSE_FRAMES)
                workerOpen = false
                runCatching { finishSaves() }.isSuccess
            } else true
            frames = emptyList()
            dispatch_async(dispatch_get_main_queue()) { onClosed(finished) }
        }
    }

    private val tickTarget = object : NSObject() {
        @Suppress("unused")
        @kotlinx.cinterop.ObjCAction
        fun tick(link: CADisplayLink) = refresh(link.timestamp)
    }

    private fun beginDisplay() {
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { pause() }
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { resume() }
        displayLink = CADisplayLink.displayLinkWithTarget(tickTarget, NSSelectorFromString("tick:")).also {
            it.paused = true
            it.preferredFramesPerSecond = 60
            it.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        }
        if (UIApplication.sharedApplication.applicationState == UIApplicationStateActive) resume()
    }

    private fun pause() {
        active = false
        generation++
        displayLink?.paused = true
        input.backgroundCaptured(true)
        audio.stop()
    }

    private fun resume() {
        if (!running) return
        active = true
        input.backgroundCaptured(false)
        audio.start()
        displayLink?.paused = false
    }

    private fun pollFocus() {
        val pads = GCController.controllers().mapNotNull { (it as? GCController)?.extendedGamepad }
        val left = pads.any { it.leftTrigger.pressed || it.leftThumbstickButton?.pressed == true }
        val right = pads.any { it.rightTrigger.pressed || it.rightThumbstickButton?.pressed == true }
        val movement = (if (right && !rightDown) 1 else 0) - (if (left && !leftDown) 1 else 0)
        leftDown = left
        rightDown = right
        if (movement != 0) focus((focused + movement + roms.size) % roms.size)
    }

    private fun refresh(now: Double) {
        if (!running || !active) return
        pollFocus()
        input.poll()
        if (busy) return
        busy = true
        val revision = generation
        val peer = focused
        val keys = input.keys()
        val fast = fast
        dispatch_async(worker) {
            if (revision != lastGeneration) {
                pacer.reset()
                lastGeneration = revision
            }
            pump.prepare(peer, keys)
            val due = pacer.framesAt(now, if (fast) 4.0 else 1.0)
            var count = 0
            var failed = false
            for (step in 0 until due) {
                if (!PkLinkCore.frame()) {
                    NSLog("PokeDaisy: link session failed: %@", PkLinkCore.status())
                    failed = true
                    break
                }
                val ready = pump.readFocusedAudio() * 2
                if (!fast && ready <= samples.size - count) {
                    pump.audio.copyInto(samples, count, endIndex = ready)
                    count += ready
                }
            }
            val copied = due > 0 && !failed && frames.withIndex().all { (slot, frame) ->
                PkLinkCore.copyFrame(slot, frame.pixels)
            }
            val batch = frames
            dispatch_async(dispatch_get_main_queue()) {
                if (!closed && failed) {
                    running = false
                    pause()
                    onFault()
                } else if (!closed && active && revision == generation) {
                    if (count > 0) audio.ring.write(samples, count)
                    if (copied) batch.forEachIndexed { slot, frame ->
                        pictures[slot].publish(frame.pixels, frame.width, frame.height)
                    }
                }
                busy = false
            }
        }
    }

    private companion object {
        const val CLOSE_FRAMES = 900
    }
}
