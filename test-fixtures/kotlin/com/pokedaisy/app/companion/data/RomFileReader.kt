package com.pokedaisy.app.companion.data

import java.io.File

/**
 * Serves ROM reads (0x08000000+) from a ROM file on this machine, for tests
 * of code that reads the cartridge ([PokedexSource]). ROMs aren't in the repo:
 * [load] returns null when the file isn't there, so callers skip (Assume).
 */
class RomFileReader(private val rom: ByteArray) : MemoryReader {
    override fun readCoreMemory(addr: Long, size: Int): ByteArray {
        val off = addr - ROM_BASE
        if (off < 0 || off + size > rom.size) {
            throw TelemetryDecodeException("ROM file has no data for 0x${addr.toString(16)}..+$size")
        }
        return rom.copyOfRange(off.toInt(), off.toInt() + size)
    }

    companion object {
        private const val ROM_BASE = 0x08000000L

        /** Retail FireRed rev 1 (sha1 dd5945db…); override with -DfireredRom=/path. */
        val FIRERED_REV1_PATH: String = System.getProperty("fireredRom")
            ?: RETAIL_ROM_DIR + "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"

        /** Retail Emerald (sha1 f3ae0881…); override with -DemeraldRom=/path. */
        val EMERALD_PATH: String = System.getProperty("emeraldRom")
            ?: RETAIL_ROM_DIR + "Pokemon - Emerald Version (USA, Europe).gba"

        /** Pokémon Unbound v2.1.1.1 (sha1 b4776b82…); override with -DunboundRom=/path. */
        val UNBOUND_PATH: String = System.getProperty("unboundRom")
            ?: RETAIL_ROM_DIR + "Pokemon Unbound (v2.1.1.1).gba"

        fun load(path: String): RomFileReader? = File(path).takeIf { it.isFile }?.let { RomFileReader(it.readBytes()) }
    }

    /** RAM from [ram] (a fixture), cartridge reads from this ROM - what the
     * running core serves. Ruby/Sapphire's bag pocket table is in ROM. */
    fun withRam(ram: MemoryReader): MemoryReader = object : MemoryReader {
        override fun readCoreMemory(addr: Long, size: Int): ByteArray =
            if (addr >= ROM_BASE) this@RomFileReader.readCoreMemory(addr, size) else ram.readCoreMemory(addr, size)
    }
}
