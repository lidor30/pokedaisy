package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.random.Random

/** Crc32 / Sha1 give java.util.zip.CRC32's and MessageDigest's SHA-1 values, whole or in pieces. */
class HashesTest {
    private val data = Random(3).nextBytes(100_003)

    @Test fun `crc32 matches java_util_zip`() {
        for (n in listOf(0, 1, 31, 32, 1000, data.size)) {
            assertEquals(CRC32().apply { update(data, 0, n) }.value, crc32(data, 0, n))
        }
        assertEquals(CRC32().apply { update(data, 17, 500) }.value, crc32(data, 17, 500))
        val pieces = Crc32().apply { update(data, 0, 10); update(0xAB); update(data, 10, 90) }
        assertEquals(CRC32().apply { update(data, 0, 10); update(0xAB); update(data, 10, 90) }.value, pieces.value)
        assertEquals(0xCBF43926L, crc32("123456789".encodeToByteArray()))
    }

    @Test fun `sha1 matches MessageDigest`() {
        assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(data), sha1(data))
        assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(ByteArray(0)), sha1(ByteArray(0)))
        val jdk = MessageDigest.getInstance("SHA-1").apply { update(data, 0, 70_000); update(data, 70_000, data.size - 70_000) }.digest()
        assertArrayEquals(jdk, Sha1().apply { update(data, 0, 70_000); update(data, 70_000, data.size - 70_000) }.digest())
    }
}
