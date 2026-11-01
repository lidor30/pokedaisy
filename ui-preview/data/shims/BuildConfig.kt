package com.pokedaisy.app

/** The app's generated BuildConfig (AGP makes it; the preview has no AGP). */
object BuildConfig {
    const val DEBUG = true
    const val VERSION_NAME = "1.1.0"
    const val SCREENSCRAPER_DEV_ID = ""
    const val SCREENSCRAPER_DEV_PASSWORD = ""
    /** As a default build: off (the library menu's shots show what players get). */
    const val FEATURE_SAVE_EDITOR = false
    /** Off as in a default build; the ScreenScraper shots turn it on (`withScreenScraper`). */
    @JvmField var FEATURE_SCREENSCRAPER = false
    /** On in the preview: the CO-OP group and the invite pages are what the co-op shots show. */
    @JvmField var FEATURE_COOP = true
    const val COOP_RELAY_URL = ""
}
