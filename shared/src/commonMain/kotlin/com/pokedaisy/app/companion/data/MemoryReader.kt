package com.pokedaisy.app.companion.data

/**
 * The one memory primitive the telemetry decoders need. In android-companion
 * this was `RetroArchClient.readCoreMemory` over UDP; here it's an in-process
 * bus read straight from the embedded mGBA core.
 */
interface MemoryReader {
    /** @throws TelemetryDecodeException if the read can't be satisfied. */
    fun readCoreMemory(addr: Long, size: Int): ByteArray
}

/**
 * Reads emulated GBA memory directly from the running core. Cheap (no sockets,
 * no datagram pairing), but [readCoreMemory] must be called on the emulator
 * thread — see EmulatorEngine.onSample — so it never races a running frame.
 */
object InProcessReader : MemoryReader {
    override fun readCoreMemory(addr: Long, size: Int): ByteArray {
        return EmulatorCore.current.readBytes(addr, size)
            ?: throw TelemetryDecodeException("core not running / read of $size B @ 0x${addr.toString(16)} failed")
    }
}

/**
 * The game's RAM as it was at one frame's end, for a sample decoded off the emu thread (GitHub #215: the companion's
 * once-a-second read and decode took 3-15 ms between two frames on the RP6, and every one past ~8 ms cost the screen a
 * refresh). [capture] copies the RAM on the emu thread (the GBA's EWRAM + IWRAM, a DS's main RAM) into buffers kept for
 * the next one; reads inside it come from the copy, anything else (the ROM, which doesn't change) from the core. One
 * sample at a time: the buffers are only refilled once the last decode is done (PokeDaisyActivity.startSample).
 */
class RamSnapshot private constructor(private val regions: List<Pair<Long, ByteArray>>) : MemoryReader {
    override fun readCoreMemory(addr: Long, size: Int): ByteArray {
        for ((base, bytes) in regions) {
            val off = addr - base
            if (off >= 0 && off + size <= bytes.size) return bytes.copyOfRange(off.toInt(), off.toInt() + size)
        }
        return InProcessReader.readCoreMemory(addr, size)
    }

    class Buffers(val nds: Boolean) {
        val regions: List<Pair<Long, ByteArray>> =
            if (nds) listOf(0x02000000L to ByteArray(0x400000))
            else listOf(0x02000000L to ByteArray(0x40000), 0x03000000L to ByteArray(0x8000))
    }

    companion object {
        /** Copies the RAM into [buffers] (emu thread, between frames); null when the core isn't up. */
        fun capture(buffers: Buffers): RamSnapshot? =
            if (buffers.regions.all { (base, bytes) -> EmulatorCore.current.readInto(base, bytes) }) RamSnapshot(buffers.regions) else null
    }
}
