import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The companion's data and language code (app/.../companion/data and companion/i18n) as a Kotlin Multiplatform
// module, compiled where it lives: Android for the app, iOS for the iPad app. Nothing in those folders may use
// java.* or android.* (the iOS compile fails on it); what differs by platform comes in from outside
// (EmulatorCore, Logs' sink). The app depends on it and leaves those folders out of its own compile.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

val appSrc = rootDir.resolve("app/src/main/kotlin/com/pokedaisy/app/companion")
val appTest = rootDir.resolve("app/src/test/kotlin/com/pokedaisy/app/companion")

// The data / i18n tests that also use the app's own classes stay the app's (root build.gradle.kts).
@Suppress("UNCHECKED_CAST")
val appOnlyTests = rootProject.extra["appOnlyTests"] as List<String>

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(appSrc.resolve("data"))
            kotlin.srcDir(appSrc.resolve("i18n"))
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:atomicfu:0.23.1")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                // okio's Path / FileHandle / BufferedSource appear in the data code's functions.
                api("com.squareup.okio:okio:3.10.2")
                // L10n.language and PlayerLook's state are Compose state; L10n reads the device's Locale.
                // Compose Multiplatform 1.7.x = Compose 1.7 on Android (the app's BOM picks the exact version).
                api("org.jetbrains.compose.runtime:runtime:1.7.3")
                api("org.jetbrains.compose.ui:ui-text:1.7.3")
            }
        }
        commonTest {
            dependencies { implementation(kotlin("test")) }
        }
        androidUnitTest {
            kotlin.srcDir(appTest.resolve("data"))
            kotlin.srcDir(appTest.resolve("i18n"))
            kotlin.exclude(appOnlyTests.map { "**/$it.kt" })
            dependencies {
                implementation("junit:junit:4.13.2")
                // The real org.json (android.jar's is a stub in unit tests).
                implementation("org.json:json:20240303")
            }
        }
    }
}

android {
    namespace = "com.pokedaisy.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    // The fixtures the data tests load from the classpath. Set on Android's own test source set: a resource
    // folder added to the Kotlin androidUnitTest source set doesn't reach AGP's unit-test resources.
    sourceSets["test"].resources.srcDir(rootDir.resolve("app/src/test/resources"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test> {
    // The tests were written to run from app/ (fixture paths, build/ outputs, ../build), as before the split.
    workingDir = rootDir.resolve("app")
    // One fresh JVM per test class: NativeReader's bag cache is process-global (see app/build.gradle.kts).
    forkEvery = 1
    System.getenv("POKEDAISY_ROM_DIR")?.takeIf { it.isNotBlank() }?.let { systemProperty("romDir", it) }
    // `make test-quick` (-Pquick): BestEffortTest scans 26 whole ROMs; the forks run side by side.
    if (project.hasProperty("quick")) {
        filter { excludeTestsMatching("*BestEffortTest") }
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }
}
