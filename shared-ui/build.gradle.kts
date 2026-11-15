import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The companion's screens (app/.../companion/ui, the companion's settings and state next to them, the co-op
// screens' model, the manual reader, cheats, the controls' bindings) as a Kotlin Multiplatform module, compiled
// where they live: Android for the app, iOS for the iPad app. The app links it and leaves these files out of its own
// compile (the root build's sharedUiSources). Nothing in these files may use java.* or android.*: what differs by
// platform comes in through hosts set at start (FontSource, PictureCodec, ThemeStore, DocsSources, CheatParser,
// DeviceDates, Logs' sink). Their tests stay the app's (Paparazzi runs there), so what they reach is public.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
}

val app = rootDir.resolve("app/src/main/kotlin")

@Suppress("UNCHECKED_CAST")
val sharedUiSources = rootProject.extra["sharedUiSources"] as List<String>
@Suppress("UNCHECKED_CAST")
val sharedUiAppOnly = rootProject.extra["sharedUiAppOnly"] as List<String>

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(app)
            kotlin.include(sharedUiSources)
            kotlin.exclude(sharedUiAppOnly)
            dependencies {
                api(project(":shared"))
                // Compose Multiplatform 1.7.x, as shared/ (Compose 1.7 on Android, the app's BOM picks the exact version).
                api("org.jetbrains.compose.foundation:foundation:1.7.3")
                api("org.jetbrains.compose.material3:material3:1.7.3")
                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            }
        }
    }
}

android {
    namespace = "com.pokedaisy.sharedui"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
