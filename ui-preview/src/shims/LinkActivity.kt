package com.pokedaisy.app

/** Stand-in for LINK PLAY's activity (two to four cores on one link cable, JNI + GL), which the Library only starts. */
class LinkActivity : androidx.activity.ComponentActivity() {
    companion object {
        const val EXTRA_ROM = "rom"
    }
}
