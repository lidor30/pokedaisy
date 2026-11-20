package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SmolTest {
    // Heart and Soul v2.0.6's POTION icon tiles (gItemsInfo[28].iconPic): mode 3
    // (delta-coded tANS symbols, raw lo bytes), 288 bytes = a 24x24 4bpp icon.
    // The expected hash is from an independent Python port of the expansion's
    // SmolDecompressData(), whose output was rendered and checked by eye.
    private val potion = hex(
        "8304e80188096000a51004c38220040282200401e791209d84ba143925e28188fe40e903b56ebf6c17d5bd287cf6" +
            "9dc15fd61edc7a70db8922653fd35a23b1acc9df7160ac264998249966d17e1e37cbd7be66e9420599359d6f4f58" +
            "d3e99c146b3a9fe745f8e715a097c4b7fc75e402b71eee7552fa794d14b1af3f12199348181c879b289f5e2c6979" +
            "c5e21cd8e409d9133d981f037d39162ca6ff987ebb9eff8cd9e7f25afc398901000008010016081f004106480020",
    )

    @Test
    fun `decodes an item icon`() {
        assertTrue(Smol.isSmol(potion))
        val out = Smol.decompress(potion)
        assertEquals(288, out.size)
        assertEquals("aad01e66e3584419de4dd0861877b3cade39268e", sha1(out))
    }

    @Test
    fun `LZ77 data is not mistaken for smol`() {
        assertFalse(Smol.isSmol(byteArrayOf(0x10, 0x20, 0x01, 0x00, 0, 0, 0, 0)))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun sha1(b: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
