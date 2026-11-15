plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.4.21's lowest supported AGP is 8.5.2, this build's: a newer Kotlin needs an AGP bump with it.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // shared/: the companion's data and language code as a Kotlin Multiplatform module (Android + iOS).
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("com.android.library") version "8.5.2" apply false
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

// The companion's data / i18n tests that also use the app's own classes: they compile with the app, the rest
// with shared/ (shared/build.gradle.kts), from the same folder.
val appOnlyTests by extra(
    listOf(
        "Black2ReduxTest", "DsPortsTest", "FrenchDsTest", "HeartGoldGermanTest", "LegacyHacksTest",
        "NuzlockeFixturesTest", "PartyReorderConfigTest", "PolishedCrystalDecodeTest", "PrismDecodeTest",
        "RenegadePlatinumTest", "ShinyOddsTest",
    ),
)
// The companion's screens and what they need (shared-ui/build.gradle.kts), as paths under app/src/main/kotlin: the
// app leaves them out of its own compile and links shared-ui instead. The co-op transport stays the app's (sockets,
// Android NSD, Bouncy Castle, OkHttp).
val sharedUiSources by extra(
    listOf(
        "com/pokedaisy/app/companion/ui/**",
        "com/pokedaisy/app/companion/*.kt",
        "com/pokedaisy/app/companion/coop/**",
        "com/pokedaisy/app/cheats/Cheats.kt",
        "com/pokedaisy/app/DocsApi.kt",
        "com/pokedaisy/app/DocsSearch.kt",
        "com/pokedaisy/app/GbaControls.kt",
        "com/pokedaisy/app/Hotkeys.kt",
    ),
)
val sharedUiAppOnly by extra(
    listOf(
        "CoopChannel", "CoopDirect", "CoopFrames", "CoopIdentity", "CoopIpHello", "CoopKeys", "CoopLan", "CoopNoise",
        "CoopSession", "OkHttpRelayConnector", "RelayCoopTransport", "lock/CoopLockStore",
    ).map { "com/pokedaisy/app/companion/coop/$it.kt" },
)
// Test helpers in those folders that the app's own tests use too: compiled in both modules' tests.
val sharedTestHelpers by extra(
    listOf("FixtureMemoryReader", "RomFileReader", "Gen3RetailSupport", "NativeDecodeSupport", "SyntheticSave", "FakeLinkEngine"),
)
