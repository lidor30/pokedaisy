package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.Path

/** The existing owned-ROM appearance scan/cache, independent of library pickers and game hosts.
 * Run off the UI/core thread. Platform sources retain archive access; raw files use [file]. */
class AppearanceAssets(private val filesDir: Path) {
    class Source(val identity: String, val stamp: String, val name: String, val code: () -> String?, val read: () -> ByteArray)
    class Kept(val scan: PlayerLook.Scan, val heroes: List<LookChar>, val text: List<LookEngine>, val followers: Int)

    fun keep(bytes: ByteArray, scan: PlayerLook.Scan = PlayerLook.scan(bytes, TextBlob.entries)): Kept =
        Kept(scan, LookArt.keep(filesDir, scan), TextArt.keep(filesDir, scan), MonArt.keep(filesDir, bytes))

    fun keepFollowers(bytes: ByteArray) = MonArt.keep(filesDir, bytes)

    fun prepareFollowers(running: Source, library: () -> List<Source>, current: () -> Boolean = { true }) {
        if (!current()) return
        keepFollowers(running.read())
        if (current() && MonArt.sources(filesDir).size < MonArt.Source.entries.size)
            scanMissing(running.identity, library(), current)
    }

    /** Same versioned scanned.txt as Android; a changed file is revisited and the running source is skipped.
     * A retired worker stops before the next source and cannot mark unvisited sources as scanned. */
    fun scanMissing(runningIdentity: String, sources: List<Source>, current: () -> Boolean = { true }) {
        val seenFile = LookArt.dir(filesDir).apply { mkdirs() } / "scanned.txt"
        val seen = runCatching { seenFile.readLines().toMutableSet() }.getOrDefault(mutableSetOf())
        fun complete() = LookArt.available(filesDir).size == LookChar.entries.size &&
            TextLook.STYLES.all { TextArt.load(filesDir, it) != null } && MonArt.sources(filesDir).size == MonArt.Source.entries.size
        for (source in sources) {
            if (!current() || complete()) break
            val id = "$SCAN_VERSION|${source.stamp}"
            if (id in seen || source.identity == runningIdentity) continue
            seen += id
            if (PlayerLook.engineFor(runCatching(source.code).getOrNull()) == null) continue
            val bytes = runCatching(source.read).getOrNull() ?: continue
            if (!current()) { seen.remove(id); break }
            runCatching { keep(bytes) }
                .onSuccess { result -> Logs.i("player look: ${source.name} gave ${result.heroes}, text ${result.text}, ${result.followers} Pokémon") }
                .onFailure { Logs.w("player look: ${source.name} scan failed", it) }
        }
        runCatching { seenFile.writeText(seen.joinToString("\n")) }
    }

    companion object {
        /** Preserve Android's cache version: Ruby/Sapphire heroes, text and the four follower sources. */
        private const val SCAN_VERSION = 6

        /** Raw ROMs on either platform. Header and inventory reads belong on the caller's asset worker. */
        fun file(rom: Path): Source {
            val identity = FileSystem.SYSTEM.canonicalize(rom).toString()
            val metadata = FileSystem.SYSTEM.metadata(rom)
            val code = {
                val header = withRomFile(rom) { it.readUpTo(0, 0xC0) }
                if (header.size >= 0xC0 && header[0xB2] == 0x96.toByte())
                    header.copyOfRange(0xAC, 0xB0).decodeToString() else null
            }
            return Source(identity, "$rom|${metadata.size ?: 0}|${metadata.lastModifiedAtMillis ?: 0}", rom.name, code) { readRomFile(rom) }
        }
    }
}
