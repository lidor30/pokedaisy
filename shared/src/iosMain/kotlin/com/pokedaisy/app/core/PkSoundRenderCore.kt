@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.SoundRenderCore
import com.pokedaisy.app.companion.data.TelemetryDecodeException
import com.pokedaisy.app.core.pkapi.PK_API_SAMPLE_RATE
import com.pokedaisy.app.core.pkapi.pk_api_sound_audio
import com.pokedaisy.app.core.pkapi.pk_api_sound_call_gb
import com.pokedaisy.app.core.pkapi.pk_api_sound_close
import com.pokedaisy.app.core.pkapi.pk_api_sound_frame
import com.pokedaisy.app.core.pkapi.pk_api_sound_open
import com.pokedaisy.app.core.pkapi.pk_api_sound_park_gb
import com.pokedaisy.app.core.pkapi.pk_api_sound_park_gba
import com.pokedaisy.app.core.pkapi.pk_api_sound_read
import com.pokedaisy.app.core.pkapi.pk_api_sound_song
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import okio.Path

/** Opens only a render handle. PkApiCore continues to own the player's game and save. */
fun openSoundRenderCore(path: Path): SoundRenderCore {
    val handle = checkNotNull(pk_api_sound_open(path.toString())) { "render core failed to init" }
    return object : SoundRenderCore {
        override val sampleRate get() = PK_API_SAMPLE_RATE
        override fun frame() = pk_api_sound_frame(handle)
        override fun audio(out: ShortArray): Int {
            if (out.isEmpty()) return 0
            return out.usePinned { pk_api_sound_audio(handle, it.addressOf(0), out.size) }
        }

        override fun parkGba(spin: Long) = pk_api_sound_park_gba(handle, spin.toUInt())
        override fun gbaSong(fn: Long, song: Int, alt: Int) = pk_api_sound_song(handle, fn.toUInt(), song, alt)
        override fun parkGb(spin: Int) = pk_api_sound_park_gb(handle, spin.toUShort())
        override fun gbCall(fn: Int, a: Int, c: Int, ret: Int) =
            pk_api_sound_call_gb(handle, fn.toUShort(), a.toUByte(), c.toUByte(), ret.toUShort())

        override fun readCoreMemory(addr: Long, size: Int): ByteArray {
            if (size <= 0 || size > 1 shl 24) throw TelemetryDecodeException("render core read size $size")
            return ByteArray(size).also { out ->
                if (!out.usePinned { pk_api_sound_read(handle, addr.toUInt(), it.addressOf(0).reinterpret(), size) }) {
                    throw TelemetryDecodeException("render core read @ 0x${addr.toString(16)} failed")
                }
            }
        }

        override fun close() = pk_api_sound_close(handle)
    }
}
