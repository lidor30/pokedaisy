package spike

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.RelayMode
import computer.iroh.presetN0
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The spike's one screen: binds an iroh endpoint, dials the Mac peer (extras `id`, `relay`, `addrs`, `mode`
 * n0 | none | <relay url>, `count`), ping-pongs 64-byte frames over one bidi stream and logs to `irohspike`.
 */
class MainActivity : Activity() {
    private lateinit var text: TextView

    private fun log(s: String) {
        Log.i("irohspike", s)
        runOnUiThread { text.append(s + "\n\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setTextIsSelectable(true); textSize = 13f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(text) })
        val id = intent.getStringExtra("id")
        val relay = intent.getStringExtra("relay")?.takeIf { it != "-" }
        val addrs = intent.getStringExtra("addrs")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val mode = intent.getStringExtra("mode") ?: "n0"
        val count = intent.getIntExtra("count", 200)
        CoroutineScope(Dispatchers.IO).launch {
            try { run(id, relay, addrs, mode, count) } catch (t: Throwable) { log("FAIL $t") }
        }
    }

    private suspend fun run(id: String?, relay: String?, addrs: List<String>, mode: String, count: Int) {
        val t0 = System.nanoTime()
        IrohAndroid.installAndroidContext(applicationContext)
        val relayMode = when (mode) {
            "n0" -> null
            "none" -> RelayMode.disabled()
            else -> RelayMode.customFromUrls(listOf(mode))
        }
        val ep = withTimeout(20_000) {
            Endpoint.bind(EndpointOptions(preset = presetN0(), alpns = listOf(ALPN), relayMode = relayMode))
        }
        log("BIND ok in ${(System.nanoTime() - t0) / 1_000_000} ms, id=${ep.id()}")
        if (mode != "none") {
            val t1 = System.nanoTime()
            runCatching { withTimeout(15_000) { ep.online() } }
            log("ONLINE in ${(System.nanoTime() - t1) / 1_000_000} ms, addr=${ep.addr()}")
        }
        if (id == null) { listen(ep); return }
        val t2 = System.nanoTime()
        val conn = withTimeout(30_000) { ep.connect(EndpointAddr(EndpointId.fromString(id), relay, addrs), ALPN) }
        log("CONNECT in ${(System.nanoTime() - t2) / 1_000_000} ms")
        val bi = conn.openBi()
        val send = bi.send()
        val recv = bi.recv()
        val rtts = ArrayList<Double>(count)
        val firstRelay = ArrayList<Boolean>()
        val frame = ByteArray(64)
        for (i in 0 until count) {
            frame[0] = i.toByte()
            val s = System.nanoTime()
            send.writeAll(frame)
            var got = 0
            while (got < frame.size) got += recv.readExact((frame.size - got).toUInt()).size
            rtts += (System.nanoTime() - s) / 1e6
            if (i < 40) firstRelay += conn.paths().firstOrNull { it.isSelected }?.isRelay == true
            if (i % 50 == 0) log("PATHS @$i " + conn.paths().joinToString { "${if (it.isRelay) "relay" else "ip"}${if (it.isSelected) "*" else ""} ${it.rttMs}ms ${it.remoteAddr}" })
            Thread.sleep(16)   // about one frame apart, like a link battle's blocks
        }
        val sorted = rtts.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        log("RTT n=${sorted.size} p50=${"%.1f".format(pct(0.5))} p90=${"%.1f".format(pct(0.9))} max=${"%.1f".format(sorted.last())} ms; " +
            "first ${firstRelay.size} pings on relay: ${firstRelay.count { it }}")
        log("PATHS end " + conn.paths().joinToString { "${if (it.isRelay) "relay" else "ip"}${if (it.isSelected) "*" else ""} ${it.rttMs}ms" })
        send.finish()
        conn.close(0, ByteArray(0))
        ep.shutdown()
        log("DONE")
    }

    /** No peer given: wait for one and echo, like the Mac peer. The line to dial this device with is logged as PEER. */
    private suspend fun listen(ep: Endpoint) {
        val a = ep.addr()
        log("PEER ${ep.id()} ${a.relayUrl() ?: "-"} ${a.directAddresses().joinToString(",")}")
        while (true) {
            val incoming = ep.acceptNext() ?: break
            val conn = incoming.accept().connect()
            log("CONN ${conn.remoteId()}")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bi = conn.acceptBi()
                    while (true) {
                        val b = bi.recv().read(65536u)
                        if (b.isEmpty()) break
                        bi.send().writeAll(b)
                    }
                } catch (t: Throwable) { log("CLOSED $t") }
                log("PATHS " + conn.paths().joinToString { "${if (it.isRelay) "relay" else "ip"}${if (it.isSelected) "*" else ""} ${it.rttMs}ms" })
            }
        }
    }

    private companion object {
        val ALPN = "pokedaisy-coop/1".toByteArray()
    }
}
