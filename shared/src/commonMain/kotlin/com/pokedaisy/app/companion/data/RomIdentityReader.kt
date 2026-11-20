package com.pokedaisy.app.companion.data

import okio.*

/** Header and fingerprint rules shared by raw files and Android's archive reader. Reads never require a core. */
class RomIdentityReader(
    private val readAtSource: (Long, Int) -> ByteArray?,
    private val openSource: () -> Source,
) {
    private fun readAt(at: Long, count: Int) = runCatching { readAtSource(at, count) }.getOrNull()
    private fun code(at: Long) = readAt(at, 4)?.takeIf { it.size == 4 }?.let(::asciiString)
    fun gameCode(): String? = code(0xAC)
    fun ndsGameCode(): String? = code(0x0C)
    fun revision(): Int? = readAt(0xBC, 1)?.firstOrNull()?.toInt()?.and(0xFF)
    fun ndsRevision(): Int? = readAt(0x1E, 1)?.firstOrNull()?.toInt()?.and(0xFF)
    fun header(nds: Boolean = false): Pair<String, Int>? {
        val bytes = readAt(if (nds) 0x0C else 0xAC, if (nds) 0x13 else 0x11) ?: return null
        val rev = if (nds) 0x12 else 0x10
        if (bytes.size <= rev) return null
        return asciiString(bytes, 0, 4) to (bytes[rev].toInt() and 0xFF)
    }
    fun isGameBoy(): Boolean = readAt(0x104, 4)?.contentEquals(GB_LOGO_START) == true
    fun isGameBoyColor(): Boolean = readAt(0x143, 1)?.firstOrNull()?.toInt()?.and(0xFF) == 0xC0
    fun isNds(): Boolean = readAt(0x15C, 2)?.let {
        it.size == 2 && it[0] == 0x56.toByte() && it[1] == 0xCF.toByte()
    } == true
    fun looksLikeRom(): Boolean = readAt(0xB2, 1)?.firstOrNull() == 0x96.toByte() ||
        (TelemetrySampler.GAME_BOY_SUPPORT && isGameBoy()) || (TelemetrySampler.NDS_SUPPORT && isNds())
    fun headerTitle(): String? = readAt(0xA0, 12)?.takeIf { it.size == 12 }?.let {
        asciiString(it).trimEnd('\u0000', ' ').takeIf { title -> title.all { c -> c in ' '..'~' } }
    }
    fun sha1(): String? = digest { HashingSource.sha1(it) }
    fun md5(): String? = digest { HashingSource.md5(it) }
    fun raHash(): String? = if (isNds()) ndsRaHash() else md5()
    fun raConsole(): Int = when {
        isNds() -> 18
        isGameBoy() -> if (isGameBoyColor()) 6 else 4
        else -> 5
    }

    /** rcheevos' DS hash: header + ARM9 + ARM7 + zero-padded icon/title block, after any SuperCard header. */
    fun ndsRaHash(): String? = runCatching {
        fun read(at: Long, count: Int) = readAt(at, count)?.copyOf(count) ?: ByteArray(count)
        var base = 0L
        var h = (readAt(0, 512) ?: return@runCatching null).copyOf(512)
        fun u8(i: Int) = h[i].toInt() and 0xFF
        if (u8(0) == 0x2E && u8(1) == 0 && u8(2) == 0 && u8(3) == 0xEA &&
            u8(0xB0) == 0x44 && u8(0xB1) == 0x46 && u8(0xB2) == 0x96 && u8(0xB3) == 0) {
            base = 512; h = read(base, 512)
        }
        fun u32(i: Int): Long = u8(i).toLong() or (u8(i + 1).toLong() shl 8) or
            (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
        val arm9Size = u32(0x2C); val arm7Size = u32(0x3C)
        if (arm9Size + arm7Size > 16L shl 20) return@runCatching null
        val hash = HashingSink.md5(blackholeSink())
        hash.buffer().use { sink ->
            sink.write(h, 0, 0x160)
            sink.write(read(base + u32(0x20), arm9Size.toInt()))
            sink.write(read(base + u32(0x30), arm7Size.toInt()))
            sink.write(read(base + u32(0x68), 0xA00))
            sink.flush(); hash.hash.hex()
        }
    }.getOrNull()

    private fun digest(hashing: (Source) -> HashingSource): String? = runCatching {
        hashing(openSource()).use { source ->
            val buffer = Buffer()
            while (source.read(buffer, 1L shl 16) != -1L) buffer.clear()
            source.hash.hex()
        }
    }.getOrNull()

    companion object {
        private val GB_LOGO_START = byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66, 0x66)
        fun forPath(path: Path): RomIdentityReader = RomIdentityReader(
            readAtSource = { at, count -> FileSystem.SYSTEM.openReadOnly(path).use { file -> file.readUpTo(at, count) } },
            openSource = { FileSystem.SYSTEM.source(path) })
    }
}
