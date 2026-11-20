package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.Path
import okio.Source
import okio.use

/** Verifies which existing EXPAND/field table a ROM can use. Both hosts supply the same native registry. */
class WideTables(
    private val knowsWide: (Int) -> Boolean,
    private val knowsField: (Int) -> Boolean,
    private val addDerived: (Int, Int, IntArray) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    /** Blocking: known tables need no I/O; randomized ROMs and in-place hacks must prove their engine first. */
    fun resolve(
        crc: String,
        identity: RomIdentityReader,
        size: Long,
        open: () -> Source,
        read: (Long, Int) -> ByteArray?,
    ): String {
        val value = crc.toLongOrNull(16)?.toInt() ?: return crc
        if (runCatching { knowsWide(value) || knowsField(value) }.getOrDefault(false)) return crc
        val hit = runCatching {
            RandomizedRetail.matchStream(identity.gameCode(), identity.revision(), size, open)
        }.getOrNull()
        if (hit != null) return hit.retailCrc.hex(8)
        if (size !in 0L..0x2000000L) return crc
        val proof = runCatching {
            val header = read(0, 0xC0)?.takeIf { it.size == 0xC0 } ?: return@runCatching null
            val code = asciiString(header, 0xAC, 4)
            if (code != "BPRE" && code != "BPEE") return@runCatching null
            RetailEngine.check(code, header[0xBC].toInt() and 0xFF) { off, count ->
                if (off < 0 || off >= size) null else read(off.toLong(), minOf(count.toLong(), size - off).toInt())
            }
        }.getOrNull() ?: return crc
        val row = proof.wideRow
        if (!proof.ramProven || row == null) {
            log("retail engine: ${proof.build.id} not proven for ${value.hex(8)}: ${proof.why.take(4)}")
            return crc
        }
        val ok = runCatching { addDerived(value, proof.build.retailCrc, row) }.getOrDefault(false)
        log("retail engine: ${value.hex(8)} keeps ${proof.build.id}'s engine, EXPAND row ${if (ok) "set" else "refused"}")
        return crc
    }

    /** Both hosts play raw files: Android extracts an archive before selecting its tables. */
    fun resolve(path: Path, crc: String, fs: FileSystem = FileSystem.SYSTEM): String = runCatching {
        val value = crc.toLongOrNull(16)?.toInt() ?: return@runCatching crc
        if (runCatching { knowsWide(value) || knowsField(value) }.getOrDefault(false)) return@runCatching crc
        val size = fs.metadata(path).size ?: return@runCatching crc
        fs.openReadOnly(path).use { file ->
            val read: (Long, Int) -> ByteArray? = { at, count -> file.readUpTo(at, count) }
            resolve(crc, RomIdentityReader(read) { fs.source(path) }, size, { fs.source(path) }, read)
        }
    }.getOrDefault(crc)
}
