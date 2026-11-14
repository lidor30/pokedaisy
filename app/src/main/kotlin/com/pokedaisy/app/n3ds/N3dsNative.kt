package com.pokedaisy.app.n3ds

import android.util.Log
import com.pokedaisy.app.FeatureFlags

/**
 * The 3DS core (Azahar) through libpk_n3ds.so, which the n3ds module builds only with the n3ds flag. Every call checks
 * [available] first: without the flag the library isn't in the APK at all.
 */
object N3dsNative {
    /** Whether the library is built in and loaded. */
    val available: Boolean by lazy {
        FeatureFlags.n3ds() && runCatching { System.loadLibrary("pk_n3ds") }
            .onFailure { Log.w("pokedaisy/n3ds", "libpk_n3ds didn't load", it) }.isSuccess
    }

    /** What [probe] read off a cart. [status] is Azahar's Loader::ResultStatus: 0 read, 8 encrypted. */
    data class Probe(val status: Int, val programId: Long?, val title: String) {
        val encrypted: Boolean get() = status == STATUS_ENCRYPTED
    }

    /** The cart at [path], read without booting it; null when it isn't a 3DS file Azahar knows, or no core. */
    fun probeCart(path: String): Probe? {
        if (!available) return null
        val parts = probe(path)?.split('\t') ?: return null
        return Probe(
            status = parts.getOrNull(0)?.toIntOrNull() ?: return null,
            programId = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toULong(16)?.toLong(),
            title = parts.getOrNull(2).orEmpty(),
        )
    }

    const val STATUS_ENCRYPTED = 8

    @JvmStatic private external fun probe(path: String): String?
}
