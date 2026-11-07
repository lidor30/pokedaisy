package com.pokedaisy.app.companion.coop

import java.io.File
import java.security.SecureRandom

/** The device's co-op identity: the X25519 private key, made once and kept in `filesDir/coop/secret.key`. Never logged or sent. */
object CoopIdentity {
    private const val KEY_SIZE = 32

    /**
     * 32 random bytes on the first call, the same ones after. Only a missing file or one of another size is replaced;
     * a read error on a valid-sized file propagates (a new key would make every partner say NOT RECOGNISED).
     */
    @Synchronized
    fun secretKey(dir: File, read: (File) -> ByteArray = File::readBytes): ByteArray {
        val file = File(dir, "secret.key")
        if (file.isFile && file.length() == KEY_SIZE.toLong()) {
            val bytes = read(file)
            if (bytes.size == KEY_SIZE) return bytes
        }
        dir.mkdirs()
        val key = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        val tmp = File(dir, "secret.key.tmp")
        writeDurably(tmp, key)
        replaceFile(tmp, file)
        return key
    }
}
