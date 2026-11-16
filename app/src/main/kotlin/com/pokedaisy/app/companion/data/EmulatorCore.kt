package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/**
 * What the data code reads from the running emulator: its memory and the loaded ROM. On Android that's mGBA / melonDS
 * through MgbaCore's JNI calls (PokeDaisyApp plugs in [com.pokedaisy.app.MgbaEmulatorCore]); the iPad app plugs in its
 * own. Until one is set nothing is running ([None]), which is also what the unit tests see.
 */
interface EmulatorCore {
    /** [len] bytes of the bus at [addr], or null (no core / unmapped). */
    fun readBytes(addr: Long, len: Int): ByteArray?
    /** [readBytes] into [dst] (its whole length); false with no core up. */
    fun readInto(addr: Long, dst: ByteArray): Boolean
    /** The ROM header's 4-char game code (`BPRE`, `BPEE`, …). */
    fun romCode(): String?
    fun romSize(): Long
    /** [PLATFORM_GBA], [PLATFORM_GB], [PLATFORM_NDS] or -1 (no core). */
    fun platform(): Int
    /** A Game Boy / Color cart's ROM bytes (bank-switched, so not on the bus whole); null on GBA. */
    fun romRead(off: Long, len: Int): ByteArray?

    /** No emulator running. */
    object None : EmulatorCore {
        override fun readBytes(addr: Long, len: Int): ByteArray? = null
        override fun readInto(addr: Long, dst: ByteArray) = false
        override fun romCode(): String? = null
        override fun romSize() = 0L
        override fun platform() = -1
        override fun romRead(off: Long, len: Int): ByteArray? = null
    }

    companion object {
        const val PLATFORM_GBA = 0
        const val PLATFORM_GB = 1
        const val PLATFORM_NDS = 2

        /** The running emulator's calls; set once at start-up. */
        @Volatile var current: EmulatorCore = None
    }
}
