package com.pokedaisy.app.companion.data

import kotlin.concurrent.Volatile

/**
 * The data code's log lines, instead of android.util.Log, which the iPad build doesn't have. They go to [sink]:
 * Android's Log in the app (PokeDaisyApp sets it), standard output until then, in tests and in ui-preview.
 */
object Logs {
    /** Writes one line: [tag], a warning or not, the text, the error if there is one. */
    fun interface Sink { fun write(tag: String, warning: Boolean, message: String, error: Throwable?) }

    const val TAG = "pokedaisy"

    @Volatile var sink: Sink = Sink { tag, warning, message, error ->
        println("${if (warning) "W" else "I"}/$tag: $message")
        error?.printStackTrace()
    }

    fun i(message: String, tag: String = TAG) = sink.write(tag, false, message, null)

    fun w(message: String, error: Throwable? = null, tag: String = TAG) = sink.write(tag, true, message, error)
}
