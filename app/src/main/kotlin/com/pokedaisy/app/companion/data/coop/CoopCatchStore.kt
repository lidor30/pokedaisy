package com.pokedaisy.app.companion.data.coop

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The partner's catches that are still on their way (the host's side) and the ones already given (the partner's side), so a
 * catch survives a link drop, a restart and a duplicate: whoever catches keeps it. Entries are keyed by [key] (the pair).
 */
interface CoopCatchStore {
    /** Catches the host sent that the partner has not acknowledged: (battle id, the Pokémon's 100 bytes). */
    fun pending(key: String): List<Pair<Int, ByteArray>>
    fun addPending(key: String, battleId: Int, mon: ByteArray)
    fun removePending(key: String, battleId: Int)
    /** Tokens ([givenToken]) of the catches this game gave itself. */
    fun given(key: String): Set<String>
    fun addGiven(key: String, token: String)

    companion object {
        /** One given catch: the battle and the Pokémon's personality (a resent message is the same token). */
        fun givenToken(battleId: Int, mon: ByteArray): String =
            "$battleId:" + ((mon[0].toInt() and 0xFF) or ((mon[1].toInt() and 0xFF) shl 8) or ((mon[2].toInt() and 0xFF) shl 16) or ((mon[3].toInt() and 0xFF) shl 24)).toUInt()
    }
}

/** In memory only (tests, and the glue without a place to write). */
class MemoryCatchStore : CoopCatchStore {
    private val pend = LinkedHashMap<String, ArrayList<Pair<Int, ByteArray>>>()
    private val gave = HashMap<String, LinkedHashSet<String>>()
    override fun pending(key: String) = pend[key]?.toList() ?: emptyList()
    override fun addPending(key: String, battleId: Int, mon: ByteArray) {
        val l = pend.getOrPut(key) { ArrayList() }
        l.removeAll { it.first == battleId }
        l.add(battleId to mon.copyOf())
    }
    override fun removePending(key: String, battleId: Int) { pend[key]?.removeAll { it.first == battleId } }
    override fun given(key: String): Set<String> = gave[key]?.toSet() ?: emptySet()
    override fun addGiven(key: String, token: String) { gave.getOrPut(key) { LinkedHashSet() }.add(token) }
}

/**
 * A text file (`P key battleId hex` / `G key token` per line), rewritten whole on every change through a temp file and a rename,
 * so a crash leaves the old file or the new one, never half of either. The last [MAX_GIVEN] given tokens per pair are kept.
 * All file IO runs on [io] (the file is read once in the background, and every change is written there as a snapshot, the newest
 * one winning), never on the caller's thread: the flow calls this on the emu thread, between frames.
 */
class FileCatchStore(private val file: File, private val io: Executor = defaultIo) : CoopCatchStore {
    private val mem = MemoryCatchStore()
    private val keys = LinkedHashSet<String>()
    private var loaded = false
    private var latest = ""
    private var writeQueued = false

    init { io.execute { synchronized(this) { load() } } }

    private fun load() {
        if (loaded) return
        loaded = true
        val lines = try { if (file.exists()) file.readLines() else emptyList() } catch (e: java.io.IOException) { emptyList() }
        for (line in lines) {
            val p = line.split(' ')
            try {
                when {
                    p.size == 4 && p[0] == "P" -> { mem.addPending(p[1], p[2].toInt(), hex(p[3])); keys += p[1] }
                    p.size == 3 && p[0] == "G" -> { mem.addGiven(p[1], p[2]); keys += p[1] }
                }
            } catch (e: RuntimeException) { /* a bad line is skipped, never fatal */ }
        }
    }

    /** Under the lock: the text of the whole file now, handed to [io] (a write already queued writes the newest text). */
    private fun write() {
        val sb = StringBuilder()
        for (k in keys) {
            for ((id, mon) in mem.pending(k)) sb.append("P ").append(k).append(' ').append(id).append(' ').append(mon.joinToString("") { "%02x".format(it) }).append('\n')
            for (t in mem.given(k).toList().takeLast(MAX_GIVEN)) sb.append("G ").append(k).append(' ').append(t).append('\n')
        }
        latest = sb.toString()
        if (!writeQueued) { writeQueued = true; io.execute { flushLatest() } }
    }

    private fun flushLatest() {
        val text = synchronized(this) { writeQueued = false; latest }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: java.io.IOException) { /* kept in memory; written with the next change */ }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun safe(key: String) = key.replace(' ', '_')

    @Synchronized override fun pending(key: String): List<Pair<Int, ByteArray>> { load(); return mem.pending(safe(key)) }
    @Synchronized override fun addPending(key: String, battleId: Int, mon: ByteArray) { load(); mem.addPending(safe(key), battleId, mon); keys += safe(key); write() }
    @Synchronized override fun removePending(key: String, battleId: Int) { load(); mem.removePending(safe(key), battleId); write() }
    @Synchronized override fun given(key: String): Set<String> { load(); return mem.given(safe(key)) }
    @Synchronized override fun addGiven(key: String, token: String) { load(); mem.addGiven(safe(key), token); keys += safe(key); write() }

    companion object {
        private const val MAX_GIVEN = 50
        /** One background thread for every store: the files are tiny, the order of writes is the order of changes. */
        val defaultIo: Executor by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "pokedaisy-coop-catches").apply { isDaemon = true } } }
    }
}
