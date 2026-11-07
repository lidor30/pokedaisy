package com.pokedaisy.app.companion.coop

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

const val COOP_IP_PORT = 47321

private val GREETING = "PDCO?1\n".toByteArray(Charsets.US_ASCII)

/**
 * BY IP: a tiny listener that hands the invite link to whoever sends exactly the greeting.
 * Hostile input is expected: bounded reads, a 3 s deadline, one connection at a time and at
 * most 2 answers a second; anything but the exact greeting is closed with no answer.
 */
class CoopIpHelloServer(
    private val nanoTime: () -> Long = System::nanoTime,   // injectable for the rate-limit test
    private val link: () -> String?,   // last, so `CoopIpHelloServer { link }` still reads
) {
    @Volatile private var server: ServerSocket? = null
    private var lastAnswerNanos = 0L
    private var answered = false

    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val s = try {
            ServerSocket(COOP_IP_PORT)
        } catch (e: java.io.IOException) {
            ServerSocket(0)
        }
        server = s
        Thread({ loop(s) }, "pokedaisy-coop-ip").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: java.io.IOException) { return }
            try { serve(c) } catch (_: Exception) { } finally { runCatching { c.close() } }
        }
    }

    private fun serve(c: Socket) {
        val deadline = System.nanoTime() + READ_MS * 1_000_000L
        val input = c.getInputStream()
        // At most 16 bytes, up to a newline, inside one overall deadline (a slow drip can't hold us).
        val got = ByteArrayOutputStream()
        while (got.size() < MAX_READ) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) return
            c.soTimeout = left.toInt()
            val b = try { input.read() } catch (e: java.net.SocketTimeoutException) { return }
            if (b < 0) return
            got.write(b)
            if (b == '\n'.code) break
        }
        if (!got.toByteArray().contentEquals(GREETING)) return
        val now = nanoTime()
        if (answered && now - lastAnswerNanos < MIN_GAP_NANOS) return
        val text = link() ?: return
        c.getOutputStream().apply { write((text + "\n").toByteArray(Charsets.UTF_8)); flush() }
        answered = true
        lastAnswerNanos = now
    }

    private companion object {
        const val READ_MS = 3_000L
        const val MAX_READ = 16
        const val MIN_GAP_NANOS = 500_000_000L
    }
}

object CoopIpHelloClient {
    private const val MAX_REPLY = 4_096

    /** The ticket the host at [host]:[port] hands out, or null (no answer, bad answer, any error). */
    fun fetch(host: String, port: Int, timeoutMs: Int = 5_000): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            s.getOutputStream().apply { write(GREETING); flush() }
            val input = s.getInputStream()
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(512)
            while (out.size() < MAX_REPLY) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                s.soTimeout = left.toInt()
                val n = input.read(chunk, 0, minOf(chunk.size, MAX_REPLY - out.size()))
                if (n < 0) break
                out.write(chunk, 0, n)
            }
            CoopInvite.parse(String(out.toByteArray(), Charsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }
}

/** "192.168.1.20:47321" or "[fe80::1]:47321" -> host and port; no port or one outside 1..65535 -> null. */
fun parseHostPort(text: String): Pair<String, Int>? {
    val t = text.trim()
    val host: String
    val portText: String
    if (t.startsWith("[")) {
        val end = t.indexOf("]:")
        if (end < 2) return null
        host = t.substring(1, end)
        portText = t.substring(end + 2)
    } else {
        val i = t.lastIndexOf(':')
        if (i <= 0 || t.indexOf(':') != i) return null
        host = t.substring(0, i)
        portText = t.substring(i + 1)
    }
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
    val port = portText.toInt()
    return if (port in 1..65_535) host to port else null
}
