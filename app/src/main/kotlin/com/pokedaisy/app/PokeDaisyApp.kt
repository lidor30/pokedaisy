package com.pokedaisy.app

import android.app.Application

/** Process start: counts the app open (for CrashReports' one-time ask) and applies the crash-report choice. */
class PokeDaisyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The NEW tags' week starts with the first launch of a build that has the feature.
        com.pokedaisy.app.companion.NewFeatures.init(Prefs(this))
        val prefs = Prefs(this)
        prefs.appOpenCount = (prefs.appOpenCount + 1).coerceAtMost(1000)
        CrashReports.apply(prefs.crashReportsEnabled)
        // SOUND > GAME VOLUME / MUTE GAME: every player of the game's sound reads it from here.
        GameVolume.load(prefs)
        AudioEffects.load(prefs)
        // TOUCH CONTROLS > SKIN: Delta skins' PDF / PNG pictures.
        com.pokedaisy.app.companion.ui.SkinImages.decode = SkinBitmaps::decode
        com.pokedaisy.app.companion.data.onGameIdentified = { kind, how -> CrashReports.gameIdentified(kind, how) }
        SaveBackups.settings = SaveBackups.Settings.from(prefs)
        ScreenScraperClient.enabled = FeatureFlags.screenScraper(this)
        FeatureFlags.applyBlocked(this)
        // LIGHT COMPANION: the player's pick, and AUTO's verdict (a device that looks weak, or whose buttons came late before).
        com.pokedaisy.app.companion.LightCompanion.mode = com.pokedaisy.app.companion.LightCompanionMode.of(prefs.lightCompanion)
        com.pokedaisy.app.companion.LightCompanion.deviceWeak = prefs.lightCompanionMeasured || DevicePerformance.looksWeak(this)
        // DS CORE's AUTO (companion.DsCore): DSperate on a weak device or a 32-bit Android.
        com.pokedaisy.app.companion.DsCore.deviceWeak = DevicePerformance.looksWeak(this)
        com.pokedaisy.app.companion.DsCore.is64Bit = android.os.Process.is64Bit()
    }
}
