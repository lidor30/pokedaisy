package com.pokedaisy.app

import android.app.Application
import okio.Path.Companion.toOkioPath

/** Process start: counts the app open (for CrashReports' one-time ask) and applies the crash-report choice. */
class PokeDaisyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The data code reads the emulator through MgbaCore (companion/data/EmulatorCore.kt).
        com.pokedaisy.app.companion.data.EmulatorCore.current = MgbaEmulatorCore
        // The app's files folder, where the screens find the art RomArt rebuilt from the player's ROMs.
        com.pokedaisy.app.companion.data.RomArt.filesDir = filesDir.toOkioPath()
        // The data code's log lines (companion/data/Logs.kt) go to Android's log.
        com.pokedaisy.app.companion.data.Logs.sink = com.pokedaisy.app.companion.data.Logs.Sink { tag, warning, message, error ->
            if (warning) android.util.Log.w(tag, message, error) else android.util.Log.i(tag, message)
        }
        // THEME and DARK MODE are saved in the app's Prefs.
        val themePrefs = Prefs(this)
        com.pokedaisy.app.companion.ui.theme.ThemeStore.current = object : com.pokedaisy.app.companion.ui.theme.ThemeStore {
            override var appTheme by themePrefs::appTheme
            override var themeBeforeSeasonal by themePrefs::themeBeforeSeasonal
            override var darkMode by themePrefs::darkMode
        }
        com.pokedaisy.app.companion.ui.DocsSources.current = StoreDocsSource(this)
        // The screens' fonts (Pixel Operator and its fallbacks, the manual's, a game's own) from the app's assets.
        com.pokedaisy.app.companion.ui.theme.FontSource.current = AndroidFonts(this)
        // The screens' pictures: Bitmaps made from pixels, image files decoded by BitmapFactory.
        com.pokedaisy.app.companion.ui.PictureCodec.current = AndroidPictures
        // The NEW tags' week starts with the first launch of a build that has the feature.
        Prefs(this).let { p -> com.pokedaisy.app.companion.NewFeatures.init(p.newFeatureSince, { p.newFeatureSince = it }) }
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
