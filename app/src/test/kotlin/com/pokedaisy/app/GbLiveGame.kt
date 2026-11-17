package com.pokedaisy.app

import com.pokedaisy.app.companion.data.MemoryReader
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Writer

/**
 * A Game Boy game running headless in native-capture/mgba_dump (the pokedaisy-capture Docker image), driven one frame at a
 * time through its stdin: the harness the battle controls were run through against the real game (docs/ROM_FEATURES.md's
 * notes on the Game Boy games). [ram] is the bus as the last [frame] left it (C000-DFFF and FF80-FFFE, mgba_dump's
 * `gbwram`), so the app's own readers and [BattleInputController] see what the game shows; [frame] runs one frame with
 * the keys given held.
 *
 * Skipped unless POKEDAISY_GB_LIVE is set (it needs Docker and the player's ROM and save): the ROM and save are copied
 * into a temporary folder first - mgba_dump writes to the save it is given.
 */
class GbLiveGame(rom: File, save: File?, private val log: (String) -> Unit = {}) : MemoryReader, AutoCloseable {
    private val dir = File.createTempFile("gblive", "").also { it.delete(); it.mkdirs() }
    private val proc: Process
    private val out: Writer
    private val inp: BufferedReader
    val ram = ByteArray(0x10000)
    var frames = 0
        private set

    init {
        rom.copyTo(File(dir, "rom.gb"))
        save?.copyTo(File(dir, "save.sav"))
        val repo = repoRoot()
        val build = ProcessBuilder("docker", "run", "--rm", "-v", "${repo.path}:/repo", "-v", "${dir.path}:/work", "-w",
            "/repo/native-capture", "pokedaisy-capture", "-c",
            "gcc -O2 -Wall -o /work/mgba_dump mgba_dump.c -I/usr/include -L/usr/lib/aarch64-linux-gnu -lmgba -lm")
            .redirectErrorStream(true).start()
        val built = build.inputStream.bufferedReader().readText()
        check(build.waitFor() == 0) { "mgba_dump didn't build: $built" }
        proc = ProcessBuilder("docker", "run", "-i", "--rm", "-v", "${dir.path}:/work", "pokedaisy-capture", "-c",
            "/work/mgba_dump /work/rom.gb " + (if (save != null) "/work/save.sav" else ""))
            .redirectError(File(dir, "stderr.txt")).start()
        out = proc.outputStream.bufferedWriter()
        inp = BufferedReader(InputStreamReader(proc.inputStream), 1 shl 16)
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "native-capture/mgba_dump.c").isFile) d = d.parentFile
        return d ?: error("repo root not found")
    }

    /** Raw mgba_dump commands (a boot script's lines), then a fresh read of RAM. */
    fun run(vararg commands: String) {
        for (c in commands) {
            out.write(c + "\n")
            if (c.startsWith("wait ")) frames += c.substringAfter(' ').trim().toInt()
            if (c.startsWith("press ")) frames += 2
        }
        read()
    }

    /** One frame with [keys] held (mgba_dump's names, comma separated; empty = none), then a fresh read of RAM. */
    fun frame(keys: String) {
        out.write("keys ${keys.ifEmpty { "NONE" }}\nwait 1\n")
        frames++
        read()
    }

    private fun read() {
        out.write("gbwram\n")
        out.flush()
        while (true) {
            val line = inp.readLine() ?: error("mgba_dump ended: " + File(dir, "stderr.txt").readText())
            if (!line.startsWith("gbwram ")) { if (line.isNotBlank()) log(line); continue }
            val hex = line.substring(7)
            for (i in 0 until 0x2000) ram[0xC000 + i] = hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
            for (i in 0 until 0x7F) ram[0xFF80 + i] = hex.substring(0x4000 + 2 * i, 0x4000 + 2 * i + 2).toInt(16).toByte()
            return
        }
    }

    fun u8(addr: Int) = ram[addr].toInt() and 0xFF
    fun u16be(addr: Int) = (u8(addr) shl 8) or u8(addr + 1)

    override fun readCoreMemory(addr: Long, size: Int): ByteArray = ram.copyOfRange(addr.toInt(), addr.toInt() + size)

    override fun close() {
        runCatching { out.write("quit\n"); out.flush(); out.close() }
        proc.destroy()
        dir.deleteRecursively()
    }
}
