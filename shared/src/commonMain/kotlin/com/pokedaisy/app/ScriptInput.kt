package com.pokedaisy.app

/** The battle controller's synthetic key channel, implemented by each platform's game input. */
fun interface ScriptInput {
    fun setScript(bits: Int, exclusive: Boolean)
}
