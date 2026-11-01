package com.pokedaisy.app.companion.coop

import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopNoiseTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun matchesPublishedVector() {
        val text = javaClass.getResourceAsStream("/coop/noise-ik-25519-aesgcm-sha256.json")!!.readBytes().decodeToString()
        val v = JSONObject(text)
        assertEquals(CoopNoise.PROTOCOL, v.getString("protocol_name"))
        val initStatic = hex(v.getString("init_static"))
        val init = CoopNoise.initiator(
            initStatic, hex(v.getString("init_remote_static")), hex(v.getString("init_prologue")), hex(v.getString("init_ephemeral")),
        )
        val resp = CoopNoise.responder(hex(v.getString("resp_static")), hex(v.getString("resp_prologue")), hex(v.getString("resp_ephemeral")))
        val msgs = v.getJSONArray("messages")
        fun payload(i: Int) = hex(msgs.getJSONObject(i).getString("payload"))
        fun cipher(i: Int) = msgs.getJSONObject(i).getString("ciphertext")

        val m0 = init.writeMessage(payload(0))
        assertEquals(cipher(0), hex(m0))
        assertArrayEquals(payload(0), resp.readMessage(m0))
        val m1 = resp.writeMessage(payload(1))
        assertEquals(cipher(1), hex(m1))
        assertArrayEquals(payload(1), init.readMessage(m1))
        assertTrue(init.isDone && resp.isDone)
        if (v.has("handshake_hash")) {
            assertEquals(v.getString("handshake_hash"), hex(init.handshakeHash))
            assertEquals(v.getString("handshake_hash"), hex(resp.handshakeHash))
        }
        assertArrayEquals(CoopKeys.publicKey(initStatic), resp.remoteStatic)

        val (iSend, iRecv) = init.split()
        val (rSend, rRecv) = resp.split()
        for (i in 2 until msgs.length()) {
            val fromInit = i % 2 == 0
            val sealed = (if (fromInit) iSend else rSend).encrypt(payload(i))
            assertEquals(cipher(i), hex(sealed))
            assertArrayEquals(payload(i), (if (fromInit) rRecv else iRecv).decrypt(sealed))
        }
    }

    private val prologue = "p".toByteArray()

    private fun pair(): Pair<CoopNoise, CoopNoise> {
        val rnd = SecureRandom()
        val a = ByteArray(32).also(rnd::nextBytes)
        val b = ByteArray(32).also(rnd::nextBytes)
        return CoopNoise.initiator(a, CoopKeys.publicKey(b), prologue) to CoopNoise.responder(b, prologue)
    }

    @Test fun roundTripWithRandomKeys() {
        val (i, r) = pair()
        assertArrayEquals(byteArrayOf(0, 1), r.readMessage(i.writeMessage(byteArrayOf(0, 1))))
        assertArrayEquals(byteArrayOf(0), i.readMessage(r.writeMessage(byteArrayOf(0))))
        assertArrayEquals(i.handshakeHash, r.handshakeHash)
        val (s, _) = i.split()
        val (_, rr) = r.split()
        assertArrayEquals(byteArrayOf(5, 6, 7), rr.decrypt(s.encrypt(byteArrayOf(5, 6, 7))))
    }

    @Test fun wrongResponderKeyFailsAtResponder() {
        val rnd = SecureRandom()
        val a = ByteArray(32).also(rnd::nextBytes)
        val b = ByteArray(32).also(rnd::nextBytes)
        val other = ByteArray(32).also(rnd::nextBytes)
        val init = CoopNoise.initiator(a, CoopKeys.publicKey(other), prologue)
        val resp = CoopNoise.responder(b, prologue)
        assertNull(resp.readMessage(init.writeMessage(byteArrayOf(1))))
    }

    @Test fun tamperedHandshakeFails() {
        val (i, r) = pair()
        val m = i.writeMessage(byteArrayOf(1, 2))
        m[m.size - 1] = (m[m.size - 1].toInt() xor 1).toByte()
        assertNull(r.readMessage(m))
        val (i2, r2) = pair()
        assertNotNull(r2.readMessage(i2.writeMessage(byteArrayOf(1))))
        val reply = r2.writeMessage(byteArrayOf(0))
        reply[40] = (reply[40].toInt() xor 1).toByte()
        assertNull(i2.readMessage(reply))
    }

    @Test fun differentPrologueFails() {
        val rnd = SecureRandom()
        val a = ByteArray(32).also(rnd::nextBytes)
        val b = ByteArray(32).also(rnd::nextBytes)
        val i = CoopNoise.initiator(a, CoopKeys.publicKey(b), "x".toByteArray())
        assertNull(CoopNoise.responder(b, "y".toByteArray()).readMessage(i.writeMessage(byteArrayOf())))
    }

    @Test fun cipherRejectsTamperAndReplay() {
        val (i, r) = pair()
        r.readMessage(i.writeMessage(byteArrayOf()))
        i.readMessage(r.writeMessage(byteArrayOf()))
        val (s, _) = i.split()
        val (_, rr) = r.split()
        val sealed = s.encrypt(byteArrayOf(9, 9))
        val bad = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(rr.decrypt(bad))
        assertNotNull(rr.decrypt(sealed))
        assertNull(rr.decrypt(sealed))
    }
}
