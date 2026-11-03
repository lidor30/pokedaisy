package com.pokedaisy.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AudioEffect

/**
 * What [AudioEffects] changes in a track of the game's sound (the game's own and STEADY's clips), and the audio session they
 * share. On, they play as media, not low latency: that's the output system-wide effects run on, as they did on STEADY's
 * clips while a MediaPlayer played those. It costs some delay. Apps that attach to a session (an equalizer's other way in)
 * hear of this one by Android's own open / close broadcasts: the session id and our package, nothing else.
 */
internal object AudioEffectsTrack {
    @Volatile private var session = 0

    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (AudioEffects.on) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun mode(builder: AudioTrack.Builder): AudioTrack.Builder {
        builder.setPerformanceMode(if (AudioEffects.on) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        if (AudioEffects.on && session > 0) builder.setSessionId(session)
        return builder
    }

    /** The game is starting: with AUDIO EFFECTS on, its session, announced. */
    fun open(context: Context) {
        if (!AudioEffects.on) return
        if (session <= 0) {
            session = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        }
        announce(context, AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
    }

    /** The game has stopped. */
    fun close(context: Context) {
        if (session > 0) announce(context, AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
    }

    private fun announce(context: Context, action: String) {
        runCatching {
            context.sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME),
            )
        }
    }
}
