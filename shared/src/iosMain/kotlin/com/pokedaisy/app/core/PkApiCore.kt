@file:OptIn(ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.companion.data.GameCalls
import com.pokedaisy.app.companion.data.InProcessReader
import com.pokedaisy.app.companion.data.EmulatorCore
import com.pokedaisy.app.companion.data.RomArt
import com.pokedaisy.app.core.pkapi.pk_api_call
import com.pokedaisy.app.core.pkapi.pk_api_call_safe
import com.pokedaisy.app.core.pkapi.pk_api_write
import com.pokedaisy.app.core.pkapi.pk_api_gb_call_at
import com.pokedaisy.app.core.pkapi.pk_api_save_state
import com.pokedaisy.app.core.pkapi.pk_api_load_state
import com.pokedaisy.app.core.pkapi.pk_api_state_matches_save
import com.pokedaisy.app.core.pkapi.pk_api_rom_patch
import com.pokedaisy.app.core.pkapi.pk_api_rom_unpatch
import com.pokedaisy.app.core.pkapi.pk_api_cheats_check
import com.pokedaisy.app.core.pkapi.pk_api_cheats_apply
import com.pokedaisy.app.companion.data.RomOptionPatcher
import com.pokedaisy.app.core.pkapi.pk_api_field_wild
import com.pokedaisy.app.core.pkapi.pk_api_field_step
import com.pokedaisy.app.core.pkapi.pk_api_field_configure
import com.pokedaisy.app.core.pkapi.pk_api_field_state
import com.pokedaisy.app.core.pkapi.pk_api_audio
import com.pokedaisy.app.core.pkapi.pk_api_deinit
import com.pokedaisy.app.core.pkapi.pk_api_finish_save
import com.pokedaisy.app.core.pkapi.pk_api_init
import com.pokedaisy.app.core.pkapi.pk_api_platform
import com.pokedaisy.app.core.pkapi.pk_api_read
import com.pokedaisy.app.core.pkapi.pk_api_rom_code
import com.pokedaisy.app.core.pkapi.pk_api_rom_read
import com.pokedaisy.app.core.pkapi.pk_api_rom_size
import com.pokedaisy.app.core.pkapi.pk_api_run_frame
import com.pokedaisy.app.core.pkapi.pk_api_set_audio_rate
import com.pokedaisy.app.core.pkapi.pk_api_set_rewind
import com.pokedaisy.app.core.pkapi.pk_api_set_rewinding
import com.pokedaisy.app.core.pkapi.pk_api_set_keys
import com.pokedaisy.app.core.pkapi.pk_api_video
import com.pokedaisy.app.core.pkapi.pk_api_video_height
import com.pokedaisy.app.core.pkapi.pk_api_video_width
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * The iPad's emulator core: a GBA or Game Boy game on mGBA through the plain C API in app/src/main/cpp/pk_api.h (built
 * by shared/native/build_ios_core.sh, reached through cinterop), the same code Android's JNI bridge calls. [start] opens
 * a game and makes it the data code's [EmulatorCore.current]; [stop] closes it.
 *
 * Threads, as pk_api.h says: one thread (the game's) starts, runs and stops it; the reads may come from any thread.
 */
object PkApiCore : EmulatorCore, RomOptionPatcher, GameCalls {
    /** Existing field actions use this same interface on Android. Game thread between frames only. */
    fun callSafe(): Boolean = pk_api_call_safe()
    override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long =
        pk_api_call(fn.toUInt(), a0.toUInt(), a1.toUInt(), a2.toUInt(), a3.toUInt())
    override fun read(addr: Long, len: Int): ByteArray = InProcessReader.readCoreMemory(addr, len)
    override fun rom(off: Long, len: Int): ByteArray? = romRead(off, len)
    override fun write(addr: Long, bytes: ByteArray): Boolean {
        if (addr !in 0L..0xFFFFFFFFL || bytes.isEmpty()) return false
        return bytes.usePinned { pk_api_write(addr.toUInt(), it.addressOf(0).reinterpret(), bytes.size) }
    }
    override fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean =
        pk_api_gb_call_at(at.toUShort(), fn.toUShort(), a.toUByte(), b.toUByte(), hl.toUShort())


    /** Existing mGBA GBA parser, independent of a running game. */
    fun checkCheat(code: String, type: Int, directive: String): String? {
        val result = pk_api_cheats_check(code, type, directive) ?: return null
        return try { result.toKString() } finally { platform.posix.free(result) }
    }

    /** Replace enabled GBA cheats between frames; the C API owns the existing core lock and patch bookkeeping. */
    fun applyCheats(text: String): Int = pk_api_cheats_apply(text)

    /** The keys as [setKeys] takes them (pk_api.h's PK_API_KEY_*; a Game Boy game ignores L / R). */
    const val KEY_A = 1 shl 0
    const val KEY_B = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_RIGHT = 1 shl 4
    const val KEY_LEFT = 1 shl 5
    const val KEY_UP = 1 shl 6
    const val KEY_DOWN = 1 shl 7
    const val KEY_R = 1 shl 8
    const val KEY_L = 1 shl 9

    /** The rate [audio]'s samples come at, unless [setAudioRate] moves it. */
    const val SAMPLE_RATE = 48000

    /** The longest read, as on Android (MgbaCore's pkReadBytes). */
    private const val MAX_READ = 1 shl 24

    /** Starts the game at [romPath] (a .gba, .gb or .gbc), closing any before it; [savePath] is its battery save. */
    fun start(romPath: String, savePath: String?): Boolean {
        val ok = pk_api_init(romPath, savePath)
        EmulatorCore.current = if (ok) this else EmulatorCore.None
        return ok
    }

    /** Closes the game, its save written. */
    fun stop() {
        if (EmulatorCore.current === this) EmulatorCore.current = EmulatorCore.None
        pk_api_deinit()
    }

    fun runFrame() = pk_api_run_frame()

    /** The keys held from the next frame on ([KEY_A] ...). */
    fun setKeys(keys: Int) = pk_api_set_keys(keys.toUInt())

    /** The last frame: [videoWidth] x [videoHeight] pixels of 4 bytes (R, G, B, unused), rows packed; null with no game. */
    fun video(): COpaquePointer? = pk_api_video()
    val videoWidth: Int get() = pk_api_video_width()
    val videoHeight: Int get() = pk_api_video_height()

    /** The last frame's sound into [dst]: interleaved stereo samples; returns how many (at most [dst]'s size). */
    fun audio(dst: ShortArray): Int = memScoped {
        val count = alloc<IntVar>()
        val samples = pk_api_audio(count.ptr) ?: return 0
        val n = minOf(count.value, dst.size)
        for (i in 0 until n) dst[i] = samples[i]
        n
    }

    fun setAudioRate(rate: Double) = pk_api_set_audio_rate(rate)

    /** Lets a save the game is writing finish (up to [maxFrames] frames): before [stop] or a state. */
    fun finishSave(maxFrames: Int): Int = pk_api_finish_save(maxFrames)

    /** A small opaque screenshot for the shared STATES cards, made only on a state save. */
    fun stateThumbnail(): ByteArray? {
        val src = video()?.reinterpret<ByteVar>() ?: return null
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return null
        val tw = 160
        val th = tw * height / width
        val pixels = IntArray(tw * th) { i ->
            val at = (((i / tw) * height / th) * width + (i % tw) * width / tw) * 4
            (0xFF shl 24) or ((src[at].toInt() and 0xFF) shl 16) or
                ((src[at + 1].toInt() and 0xFF) shl 8) or (src[at + 2].toInt() and 0xFF)
        }
        return RomArt.Image(tw, th, pixels).png()
    }

    override fun unpatch() = pk_api_rom_unpatch()
    override fun patch(expectAt: Long, expect: ByteArray, at: Long, value: ByteArray): Boolean {
        if (expect.size !in 1..64 || value.size !in 1..4 || expectAt !in 0x08000000L..0x0DFFFFFFL || at !in 0x08000000L..0x0DFFFFFFL) return false
        return expect.usePinned { expected -> value.usePinned { replacement ->
            pk_api_rom_patch(expectAt.toUInt(), expected.addressOf(0).reinterpret(), expect.size,
                at.toUInt(), replacement.addressOf(0).reinterpret(), value.size)
        } }
    }

    /** Read-only field observation for scene speeds; never enables patches or wild encounter changes. */
    fun configureField(crc: Long): Boolean = pk_api_field_configure(crc.toUInt())
    fun fieldState(): Int = pk_api_field_state()
    fun configureWild(addr: Long, wasOff: Boolean): Boolean =
        addr in 0L..0xFFFFFFFFL && pk_api_field_wild(addr.toUInt(), wasOff)
    fun stepField(runIndoors: Boolean, wildKeep: Int): Int = pk_api_field_step(runIndoors, wildKeep)

    /** The existing diff ring, with battery data excluded. Main/game thread only. */
    fun setRewind(entries: Int) = pk_api_set_rewind(entries)
    fun setRewinding(on: Boolean) = pk_api_set_rewinding(on)

    /** Extended states include the battery save: a manual load rewinds it too. Game thread only. */
    fun saveState(path: String): Boolean = pk_api_save_state(path)
    fun loadState(path: String): Boolean = pk_api_load_state(path)
    /** 1 = matches the battery file, 0 = changed, -1 = absent/unreadable; only 1 permits automatic resume. */
    fun stateMatchesSave(path: String): Int = pk_api_state_matches_save(path)

    override fun readBytes(addr: Long, len: Int): ByteArray? {
        if (len <= 0 || len > MAX_READ) return null
        val out = ByteArray(len)
        return if (readInto(addr, out)) out else null
    }

    override fun readInto(addr: Long, dst: ByteArray): Boolean {
        if (dst.isEmpty() || dst.size > MAX_READ) return false
        return dst.usePinned { pk_api_read(addr.toUInt(), it.addressOf(0).reinterpret(), dst.size) }
    }

    override fun romCode(): String? = memScoped {
        val code = allocArray<ByteVar>(5)
        if (pk_api_rom_code(code)) code.toKString() else null
    }

    override fun romSize(): Long = pk_api_rom_size()

    override fun platform(): Int = pk_api_platform()

    override fun romRead(off: Long, len: Int): ByteArray? {
        if (off < 0 || len <= 0 || len > MAX_READ) return null
        val out = ByteArray(len)
        val ok = out.usePinned { pk_api_rom_read(off, it.addressOf(0).reinterpret(), len) }
        return if (ok) out else null
    }
}
