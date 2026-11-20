package com.pokedaisy.app

import com.pokedaisy.app.companion.data.MemoryReader
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path

/** The disposable emulator that the song workers drive. The worker owns the handle from open through close. */
interface SoundRenderCore : MemoryReader {
    val sampleRate: Int
    fun frame()
    fun audio(out: ShortArray): Int
    fun parkGba(spin: Long): Boolean
    fun gbaSong(fn: Long, song: Int, alt: Int): Boolean
    fun parkGb(spin: Int): Boolean
    fun gbCall(fn: Int, a: Int, c: Int, ret: Int): Boolean
    fun close()
}

interface RenderedGameSound {
    val hasOwn: Boolean
    fun write(pcm: ShortArray, len: Int, sampleRate: Int)
}

class MusicRenderHost(
    val open: (Path) -> SoundRenderCore,
    val prepareCache: (FfMusicCache, () -> Boolean) -> Unit = { cache, waiting -> cache.prepare(waiting) },
    private val configureWorker: () -> Unit = {},
    private val report: (String, Throwable?) -> Unit = { _, _ -> },
) {
    fun log(message: String, error: Throwable? = null) = report(message, error)

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    internal fun start(name: String, onFailure: (Throwable) -> Unit, work: suspend () -> Unit) {
        val dispatcher = newSingleThreadContext(name)
        CoroutineScope(dispatcher).launch {
            try {
                configureWorker()
                work()
            } catch (error: Exception) {
                onFailure(error)
            } finally {
                dispatcher.close()
            }
        }
    }
}

/** Foreground requests enter at the front, and background jobs wait at the back. */
internal class SongRenderQueue {
    private val lock = SynchronizedObject()
    private val jobs = ArrayDeque<Any>()
    private val changed = Channel<Unit>(Channel.CONFLATED)

    fun offerFirst(job: Any) {
        synchronized(lock) { jobs.addFirst(job) }
        changed.trySend(Unit)
    }

    fun offerLast(job: Any) {
        synchronized(lock) { jobs.addLast(job) }
        changed.trySend(Unit)
    }

    fun peekFirst(): Any? = synchronized(lock) { jobs.firstOrNull() }

    private fun next(): Any? = synchronized(lock) { jobs.removeFirstOrNull() }

    suspend fun take(): Any {
        while (true) {
            next()?.let { return it }
            changed.receive()
        }
    }

    suspend fun poll(idleMillis: Long): Any? {
        // Time out only while waiting for a signal, so a job already removed cannot be lost.
        withTimeoutOrNull(idleMillis) {
            while (peekFirst() == null) changed.receive()
        }
        return next()
    }
}

internal class SeenSongs {
    private val lock = SynchronizedObject()
    private val keys = mutableSetOf<String>()
    fun add(key: String): Boolean = synchronized(lock) { keys.add(key) }
    operator fun contains(key: String): Boolean = synchronized(lock) { key in keys }
}
