plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.4.21's lowest supported AGP is 8.5.2, this build's: a newer Kotlin needs an AGP bump with it.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // The Compose compiler ships with Kotlin since 2.0: always the same version as Kotlin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // Screenshot tests for the Compose UI (app/src/test/.../ui/*ScreenshotTest.kt).
    // 1.3.5 is the last release on layoutlib for compileSdk 34; 2.x needs compileSdk 35+ (and a newer AGP).
    id("app.cash.paparazzi") version "1.3.5" apply false
    // Crash reports (opt-in, off by default): applied by app/ only when its
    // google-services.json is there - see CrashReports.kt.
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}
