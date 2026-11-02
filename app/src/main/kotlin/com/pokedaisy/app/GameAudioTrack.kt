package com.pokedaisy.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Shared output policy for live game audio, STEADY clips and link play. */
internal object GameAudioTrack {
    fun build(sampleRate: Int, bufferSizeInBytes: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setBufferSizeInBytes(bufferSizeInBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        // Request buffered media output, not the fast game output that bypasses system effects on the Thor
        // (GitHub #336). POWER_SAVING requests deep buffering; NONE alone does not request that output.
        // This is a routing hint, not a guarantee: Android's device policy decides the actual output and effects.
        // Keep this policy shared so switching to STEADY cannot switch the user's EQ off or on.
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
        .build()
}
