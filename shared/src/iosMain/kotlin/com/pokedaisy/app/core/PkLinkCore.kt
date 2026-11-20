@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pokedaisy.app.core

import com.pokedaisy.app.core.pkapi.PK_API_SAMPLE_RATE
import com.pokedaisy.app.core.pkapi.PK_LINK_MAX
import com.pokedaisy.app.core.pkapi.pk_link_close
import com.pokedaisy.app.core.pkapi.pk_link_copy_video
import com.pokedaisy.app.core.pkapi.pk_link_count
import com.pokedaisy.app.core.pkapi.pk_link_open_with_rtc
import com.pokedaisy.app.core.pkapi.pk_link_read_audio
import com.pokedaisy.app.core.pkapi.pk_link_run_frame
import com.pokedaisy.app.core.pkapi.pk_link_saving
import com.pokedaisy.app.core.pkapi.pk_link_set_audio_rate
import com.pokedaisy.app.core.pkapi.pk_link_set_keys
import com.pokedaisy.app.core.pkapi.pk_link_status
import com.pokedaisy.app.core.pkapi.pk_link_video_h
import com.pokedaisy.app.core.pkapi.pk_link_video_w
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

/** One link thread owns all calls, including copies of the most recent rendered frames. */
object PkLinkCore {
    const val maxGames = PK_LINK_MAX
    const val sampleRate = PK_API_SAMPLE_RATE
    val count get() = pk_link_count()
    val saving get() = pk_link_saving()

    fun open(roms: List<String>, saves: List<String>): Boolean {
        if (roms.size !in 2..maxGames || roms.size != saves.size) return false
        return memScoped {
            val cartridges = allocArray<CPointerVar<ByteVar>>(roms.size)
            val batteries = allocArray<CPointerVar<ByteVar>>(saves.size)
            for (slot in roms.indices) {
                cartridges[slot] = roms[slot].cstr.getPointer(this)
                batteries[slot] = saves[slot].cstr.getPointer(this)
            }
            pk_link_open_with_rtc(roms.size, cartridges, batteries)
        }
    }

    fun close(maxFrames: Int = 0) = pk_link_close(maxFrames)
    fun frame() = pk_link_run_frame()
    fun keys(slot: Int, mask: Int) = pk_link_set_keys(slot, mask.toUInt())
    fun width(slot: Int) = pk_link_video_w(slot).toInt()
    fun height(slot: Int) = pk_link_video_h(slot).toInt()

    fun copyFrame(slot: Int, out: ByteArray): Boolean = out.isNotEmpty() && out.usePinned {
        pk_link_copy_video(slot, it.addressOf(0), out.size.convert())
    }

    fun readAudio(slot: Int, out: ShortArray): Int = if (out.size < 2) 0 else out.usePinned {
        pk_link_read_audio(slot, it.addressOf(0), out.size / 2)
    }

    fun audioRate(rate: Double) = pk_link_set_audio_rate(rate)

    fun status(): String = memScoped {
        val length = 512
        val output = allocArray<ByteVar>(length)
        pk_link_status(output, length.convert())
        output.toKString()
    }
}
