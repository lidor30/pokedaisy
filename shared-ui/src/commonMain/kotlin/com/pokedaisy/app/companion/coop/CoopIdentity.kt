package com.pokedaisy.app.companion.coop

import com.pokedaisy.app.companion.data.readBytes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.IOException
import okio.Path

/** The device's private key, created once in the app data directory and never sent or logged. */
object CoopIdentity {
    private const val KEY_SIZE = 32
    private val lock = SynchronizedObject()

    /** Only a missing or wrong-sized key is replaced. A read error on a valid-sized key propagates. */
    fun secretKey(
        dir: Path,
        crypto: CoopCrypto,
        read: (Path) -> ByteArray = { it.readBytes() },
    ): ByteArray = synchronized(lock) {
        val fs = FileSystem.SYSTEM
        val file = dir / "secret.key"
        val metadata = fs.metadataOrNull(file)
        if (metadata?.isRegularFile == true && metadata.size == KEY_SIZE.toLong()) {
            val bytes = read(file)
            if (bytes.size == KEY_SIZE) return@synchronized bytes
        }
        val key = crypto.randomBytes(KEY_SIZE)?.takeIf { it.size == KEY_SIZE }
            ?: throw IOException("could not create device key")
        fs.createDirectories(dir)
        val tmp = dir / "secret.key.tmp"
        writeDurably(tmp, key)
        replaceFile(tmp, file)
        key
    }
}
