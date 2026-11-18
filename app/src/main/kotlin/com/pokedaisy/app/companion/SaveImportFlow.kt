package com.pokedaisy.app.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One host's picker/read/confirmation lifetime. Native adapters read bytes and run the save transaction;
 * only the current request can publish a confirmation, and only one confirmed import can run at a time.
 * All methods are called on the UI thread, including delivery of native read/import results.
 */
class SaveImportFlow<T>(private val maxBytes: Int) {
    class Request<T> internal constructor(val target: T)
    class Pending<T> internal constructor(val target: T, val bytes: ByteArray, val fileName: String)
    enum class Failure { UNREADABLE, TOO_LARGE }
    data class Problem(val fileName: String, val failure: Failure)

    private var active = true
    private var request: Request<T>? = null
    var pending by mutableStateOf<Pending<T>?>(null)
        private set
    var importing by mutableStateOf<Pending<T>?>(null)
        private set

    fun begin(target: T): Request<T>? {
        if (!active || importing != null) return null
        pending = null
        return Request(target).also { request = it }
    }

    /** Null means either accepted or stale; errors are returned only for the current request. */
    fun ready(pick: Request<T>, bytes: ByteArray?, fileName: String): Problem? {
        if (!active || request !== pick) return null
        request = null
        val failure = when {
            bytes == null || bytes.isEmpty() -> Failure.UNREADABLE
            bytes.size > maxBytes -> Failure.TOO_LARGE
            else -> null
        }
        if (failure != null) return Problem(fileName, failure)
        pending = Pending(pick.target, bytes!!, fileName)
        return null
    }

    fun cancel(pick: Request<T>) { if (request === pick) request = null }
    fun dismiss(candidate: Pending<T>) { if (pending === candidate) pending = null }

    /** Claim before starting native work; duplicate or superseded confirmation callbacks do nothing. */
    fun confirm(candidate: Pending<T>): Pending<T>? {
        if (!active || pending !== candidate || importing != null) return null
        pending = null
        importing = candidate
        return candidate
    }

    /** An accepted transaction can finish after close; its retired host must not publish the result. */
    fun finished(candidate: Pending<T>): Boolean {
        if (importing !== candidate) return false
        importing = null
        return active
    }

    fun close() { active = false; request = null; pending = null; importing = null }
}
