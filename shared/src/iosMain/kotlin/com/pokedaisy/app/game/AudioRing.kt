@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.game

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set

/**
 * The game's sound between the game's thread, which [write]s each frame's samples, and the audio device's, which [read]s
 * them: one writer, one reader, no lock and nothing allocated after it's made (the device's thread must never wait).
 * Stereo frames, a left and a right sample each. A frame that doesn't fit is dropped; a read past what's there is
 * silence. [capacityFrames] is a power of two, so the counts may wrap.
 */
class AudioRing(val capacityFrames: Int) {
    init {
        require(capacityFrames > 0 && (capacityFrames and (capacityFrames - 1)) == 0) { "not a power of two: $capacityFrames" }
    }

    private val mask = capacityFrames - 1
    private val samples: CPointer<ShortVar> = nativeHeap.allocArray(capacityFrames * 2)
    // Frames ever written and ever read; only their difference matters.
    private val written = atomic(0)
    private val readCount = atomic(0)

    /** Stereo frames waiting to be heard. */
    val available: Int get() = written.value - readCount.value

    /** Adds [count] samples (count / 2 stereo frames) of [src]; returns the frames kept. */
    fun write(src: ShortArray, count: Int): Int {
        val w = written.value
        val frames = minOf(count / 2, capacityFrames - (w - readCount.value))
        for (i in 0 until frames) {
            val at = ((w + i) and mask) * 2
            samples[at] = src[i * 2]
            samples[at + 1] = src[i * 2 + 1]
        }
        written.value = w + frames
        return frames
    }

    /** Fills [frames] stereo frames of [dst] (interleaved), silence past what's there; returns the frames that were. */
    fun read(dst: CPointer<ShortVar>, frames: Int): Int {
        val r = readCount.value
        val got = minOf(frames, written.value - r)
        for (i in 0 until got) {
            val at = ((r + i) and mask) * 2
            dst[i * 2] = samples[at]
            dst[i * 2 + 1] = samples[at + 1]
        }
        for (i in got until frames) {
            dst[i * 2] = 0
            dst[i * 2 + 1] = 0
        }
        readCount.value = r + got
        return got
    }

    /** Drops what's waiting (the reader's side: call it only while nothing reads). */
    fun clear() {
        readCount.value = written.value
    }

    /** Frees the samples; nothing may use the ring after. */
    fun free() = nativeHeap.free(samples.rawValue)
}
