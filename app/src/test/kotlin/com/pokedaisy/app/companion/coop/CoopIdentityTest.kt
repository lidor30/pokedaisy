package com.pokedaisy.app.companion.coop

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test

class CoopIdentityTest {
    private fun dir() = java.nio.file.Files.createTempDirectory("coop-id").toFile()

    @Test fun aReadErrorOnAValidKeyPropagatesAndKeepsTheFile() {
        val d = dir()
        val key = CoopIdentity.secretKey(d)
        val before = File(d, "secret.key").readBytes()
        assertThrows(IOException::class.java) { CoopIdentity.secretKey(d) { throw IOException("transient") } }
        assertArrayEquals(before, File(d, "secret.key").readBytes())
        assertArrayEquals(key, CoopIdentity.secretKey(d))
    }

    @Test fun aWrongLengthFileIsReplaced() {
        val d = dir()
        File(d, "secret.key").writeBytes(ByteArray(5))
        assertEquals(32, CoopIdentity.secretKey(d).size)
        assertEquals(32L, File(d, "secret.key").length())
    }

    @Test fun aMissingFileMakesOneKeptKey() {
        val d = dir()
        val a = CoopIdentity.secretKey(d)
        assertArrayEquals(a, CoopIdentity.secretKey(d))
        assertFalse(a.contentEquals(CoopIdentity.secretKey(dir())))
    }
}
