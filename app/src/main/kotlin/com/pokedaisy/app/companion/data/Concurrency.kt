package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/*
 * Threads and locks for the data code, through kotlinx (coroutines, atomicfu: Kotlin Multiplatform) instead of
 * java.lang.Thread and java.util.concurrent, which the iPad build doesn't have. Locks are atomicfu's
 * `synchronized(SynchronizedObject)`, plain `synchronized` on the JVM.
 */

private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block] on an IO thread, as the `Thread(block, name).apply { isDaemon = true; start() }` it replaced; [name]
 * shows in a debugger. A throw ends that job alone. */
internal fun background(name: String, block: () -> Unit): Job = backgroundScope.launch(CoroutineName(name)) { block() }

/** Runs the jobs [post]ed to it one at a time, in order, on an IO thread: a single-thread executor's queue. */
class SerialQueue(name: String) {
    private val jobs = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        backgroundScope.launch(CoroutineName(name)) { for (job in jobs) runCatching(job) }
    }

    fun post(job: () -> Unit) { jobs.trySend(job) }
}

/**
 * A map that several threads read and fill (the caches the data code keeps per ROM), each call under one lock: what
 * java.util.concurrent.ConcurrentHashMap was used for. [getOrPut] computes outside the lock, so two threads may both
 * compute a missing value; the first one stored wins and both get it.
 */
class LockedMap<K, V : Any> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) { map[key] }

    operator fun set(key: K, value: V) { synchronized(lock) { map[key] = value } }

    operator fun contains(key: K): Boolean = synchronized(lock) { key in map }

    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }

    fun clear() = synchronized(lock) { map.clear() }

    val size: Int get() = synchronized(lock) { map.size }

    /** A copy of the values now. */
    val values: List<V> get() = synchronized(lock) { map.values.toList() }

    /** [key]'s value, else [make]'s, stored (unless another thread stored one meanwhile: then that one). */
    inline fun getOrPut(key: K, make: () -> V): V = get(key) ?: make().let { putIfAbsent(key, it) ?: it }

    /** Stores [value] unless [key] has one; returns the one it had, else null. */
    fun putIfAbsent(key: K, value: V): V? = synchronized(lock) { map[key] ?: null.also { map[key] = value } }
}
