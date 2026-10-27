import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The app's data layer + Android shims, in its own module so its huge
// generated tables compile once instead of on every UI edit.
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

val app = rootDir.resolve("../app/src/main/kotlin/com/pokedaisy/app")
val appSrc = fileTree(app) {
    listOf("companion/data/**", "companion/*.kt", "companion/i18n/**", "companion/coop/**", "FeatureFlags.kt", "MgbaCore.kt", "Prefs.kt", "cheats/Cheats.kt", "GbaControls.kt", "Hotkeys.kt", "GameVolume.kt")
        .forEach { include(it) }
}
sourceSets["main"].kotlin.srcDir("shims")
tasks.withType<KotlinCompile>().configureEach {
    source(appSrc)
    kotlinOptions.jvmTarget = "17"
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(compose.desktop.currentOs)
    // The co-op invite as a QR code (CoopQr).
    implementation("com.google.zxing:core:3.5.3")
    // CoopPairs reads and writes pairs.json.
    implementation("org.json:json:20240303")
    // CoopRelaySocket (the relay over WebSocket).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
