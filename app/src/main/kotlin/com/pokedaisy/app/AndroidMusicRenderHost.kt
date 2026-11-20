package com.pokedaisy.app

import android.os.Process
import android.util.Log
import com.pokedaisy.app.companion.data.TelemetryDecodeException
import java.util.concurrent.locks.ReentrantLock
import okio.Path

// Android's JNI entry points own one renderer. A retired worker closes it before the next worker can open one.
private val renderLock = ReentrantLock()

internal val androidMusicRenderHost = MusicRenderHost(
    open = ::openAndroidRenderCore,
    prepareCache = { cache, waiting -> cache.prepareOnAndroid(waiting) },
    configureWorker = { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) },
    report = { message, error ->
        if (error == null) Log.i("pokedaisy", message) else Log.w("pokedaisy", message, error)
    },
)

private fun openAndroidRenderCore(path: Path): SoundRenderCore {
    renderLock.lock()
    try {
        check(MgbaCore.pkRenderInit(path.toString())) { "render core failed to init" }
    } catch (error: Throwable) {
        renderLock.unlock()
        throw error
    }
    return object : SoundRenderCore {
        override val sampleRate get() = MgbaCore.pkRenderSampleRate()
        override fun frame() = MgbaCore.pkRenderRunFrame()
        override fun audio(out: ShortArray) = MgbaCore.pkRenderReadAudio(out)
        override fun parkGba(spin: Long) = MgbaCore.pkRenderPark(spin)
        override fun gbaSong(fn: Long, song: Int, alt: Int) = MgbaCore.pkRenderForceSong(fn, song, alt)
        override fun parkGb(spin: Int) = MgbaCore.pkRenderGbPark(spin)
        override fun gbCall(fn: Int, a: Int, c: Int, ret: Int) = MgbaCore.pkRenderGbCall(fn, a, c, ret)
        override fun readCoreMemory(addr: Long, size: Int) =
            MgbaCore.pkRenderReadBytes(addr, size) ?: throw TelemetryDecodeException("render core read @ 0x${addr.toString(16)} failed")

        override fun close() {
            try {
                MgbaCore.pkRenderDeinit()
            } finally {
                renderLock.unlock()
            }
        }
    }
}
