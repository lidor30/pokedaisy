package com.pokedaisy.app.n3ds

import com.pokedaisy.app.companion.data.RETAIL_ROM_DIR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class N3dsCartTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A cart image's first bytes: NCSD at 0x100 with [titleId], the first partition at 0x4000, its NCCH's flags. */
    private fun cart(titleId: Long, noCrypto: Boolean, name: String = "game.3ds"): File {
        val b = ByteArray(0x4200)
        "NCSD".toByteArray().copyInto(b, 0x100)
        for (i in 0 until 8) b[0x108 + i] = (titleId ushr (8 * i)).toByte()
        b[0x120] = 0x20 // partition 0 at 0x20 media units = 0x4000
        "NCCH".toByteArray().copyInto(b, 0x4100)
        if (noCrypto) b[0x4000 + 0x18F] = 0x04
        return tmp.newFile(name).apply { writeBytes(b) }
    }

    @Test fun readsTitleIdAndCrypto() {
        val h = N3dsCart.header(cart(N3dsCart.POKEMON_X_USA, noCrypto = true))!!
        assertEquals(N3dsCart.POKEMON_X_USA, h.titleId)
        assertTrue(h.decrypted)
        assertFalse(N3dsCart.header(cart(N3dsCart.POKEMON_X_USA, noCrypto = false, "enc.3ds"))!!.decrypted)
    }

    @Test fun supportsOnlyDecryptedKnownCarts() {
        assertTrue(N3dsCart.supported(cart(N3dsCart.POKEMON_X_USA, noCrypto = true)))
        assertFalse(N3dsCart.supported(cart(N3dsCart.POKEMON_X_USA, noCrypto = false, "enc.3ds")))
        assertFalse(N3dsCart.supported(cart(0x0004000000033500L, noCrypto = true, "other.3ds")))
    }

    @Test fun refusesWhatIsNoCart() {
        assertNull(N3dsCart.header(tmp.newFile("x.3ds").apply { writeBytes(ByteArray(0x4200)) }))
        assertNull(N3dsCart.header(tmp.newFile("short.3ds").apply { writeBytes(ByteArray(0x10)) }))
    }

    /** The player's Pokemon X, where the ROMs sit (`3ds/` beside the GBA folder): skipped without it. */
    @Test fun pokemonX() {
        val f = File(File(RETAIL_ROM_DIR).parentFile, "3ds/Pokemon X (USA) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assumeTrue(f.isFile)
        val h = N3dsCart.header(f)!!
        assertEquals(N3dsCart.POKEMON_X_USA, h.titleId)
        assertTrue(h.decrypted)
    }
}
