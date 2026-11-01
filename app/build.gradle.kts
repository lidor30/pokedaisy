import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

// Crash reports (Firebase Crashlytics, opt-in - CrashReports.kt): only with the project's
// app/google-services.json, which stays out of this public repo (gitignored; the release
// workflow writes it from a secret). Without it the app builds the same, reports off.
val crashlytics = file("google-services.json").isFile
if (crashlytics) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Release signing comes from an untracked keystore.properties (storeFile, storePassword,
// keyAlias, keyPassword) - the key itself lives outside this public repo. Without it,
// release builds come out unsigned. Every GitHub release must be signed with the same
// key, or the in-app updater's APK won't install over the previous one.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

// A password left out of keystore.properties comes from the macOS Keychain instead, so it needn't sit
// in a plain file: `security add-generic-password -a pokedaisy -s pokedaisy-<storePassword|keyPassword> -w`.
fun signingSecret(name: String): String? =
    keystoreProps?.getProperty(name)?.takeIf { it.isNotBlank() } ?: runCatching {
        val p = ProcessBuilder("security", "find-generic-password", "-a", "pokedaisy", "-s", "pokedaisy-$name", "-w")
            .redirectErrorStream(false).start()
        p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 && it.isNotEmpty() }
    }.getOrNull()

// ScreenScraper's developer credentials (ScreenScraperClient: every API call carries the app's own
// as well as the player's account). Never in this public repo: from the untracked local.properties
// (screenscraper.devId / screenscraper.devPassword) or the environment (SCREENSCRAPER_DEV_ID /
// SCREENSCRAPER_DEV_PASSWORD - the release workflow's secrets). Without them a build still sends the
// player's account alone, and the sign-in says what the server answered.
val localProps = rootProject.file("local.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}
fun screenScraperSecret(prop: String, env: String): String =
    (localProps?.getProperty(prop) ?: System.getenv(env)).orEmpty()
        .replace("\\", "\\\\").replace("\"", "\\\"")

// Features merged but off until asked for (FeatureFlags.kt): `-Ppokedaisy.<name>=true` on the command line, or the same
// key in local.properties. A marker file on the device turns them on for an APK already installed.
fun featureFlag(name: String): Boolean =
    (findProperty("pokedaisy.$name") ?: localProps?.getProperty("pokedaisy.$name"))?.toString()?.trim()?.toBoolean() == true

android {
    namespace = "com.pokedaisy.app"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.pokedaisy.app"
        minSdk = 26
        targetSdk = 34
        // Bump both for every GitHub release: the updater compares versionName
        // against the release tag (v<versionName>), Android needs versionCode to grow.
        versionCode = 16
        versionName = "1.3.3"
        // Whether this build can send crash reports at all (CrashReports.available).
        buildConfigField("boolean", "CRASHLYTICS", crashlytics.toString())
        buildConfigField("String", "SCREENSCRAPER_DEV_ID", "\"${screenScraperSecret("screenscraper.devId", "SCREENSCRAPER_DEV_ID")}\"")
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD", "\"${screenScraperSecret("screenscraper.devPassword", "SCREENSCRAPER_DEV_PASSWORD")}\"")
        // ScreenScraper (game media, its setup step and the update's popup): off until the app has its developer login
        // (FeatureFlags.screenScraper).
        buildConfigField("boolean", "FEATURE_SCREENSCRAPER", featureFlag("screenScraper").toString())

        ndk {
            // Thor is arm64; add armeabi-v7a later only if a target device needs it.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // mGBA core is C-only, but keep a real STL around in case a
                // transitive piece needs it; it's cheap.
                arguments += "-DANDROID_STL=c++_shared"
                cFlags += "-O2"
                // melonDS (the DS core) is C++: unoptimised, a DS game runs far below full speed even in debug builds.
                cppFlags += "-O2"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // Stored, so the fonts are mapped straight from the APK instead of
        // inflated into memory each time one loads (PixelTypeface.kt).
        noCompress += "ttf"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = signingSecret("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = signingSecret("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            // libpokedaisy's symbols, so native crashes (mGBA, the JNI bridge) read as
            // functions, not addresses: `make release` runs uploadCrashlyticsSymbolFileRelease.
            if (crashlytics) (this as ExtensionAware).extensions.configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
                mappingFileUploadEnabled = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenses"))
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

// Settings > LICENSES: NOTICE, then the license text of everything the APK carries, as one asset
// (licenses.txt; a line "=== <name>" starts each part).
val licensesAsset by tasks.registering {
    val parts = listOf(
        "PokeDaisy (NOTICE)" to "NOTICE",
        "GNU General Public License v3 (PokeDaisy)" to "LICENSE",
        "mGBA - Mozilla Public License 2.0" to "third_party/mgba/LICENSE",
        "blip_buf (in mGBA) - GNU LGPL 2.1" to "third_party/mgba/src/third-party/blip_buf/license.txt",
        "inih (in mGBA) - BSD" to "third_party/mgba/src/third-party/inih/LICENSE.txt",
        "rcheevos - MIT" to "third_party/rcheevos/LICENSE",
        "melonDS - GNU GPL 3.0" to "third_party/melonds/LICENSE",
        "FatFs (in melonDS)" to "third_party/melonds/src/fatfs/LICENSE.txt",
        "Teakra (in melonDS) - MIT" to "third_party/melonds/src/teakra/LICENSE",
        "xxHash (in melonDS) - BSD 2-Clause" to "third_party/licenses/xxHash-BSD-2-Clause.txt",
        "tiny-AES-c (in melonDS) - The Unlicense" to "third_party/melonds/src/tiny-AES-c/unlicense.txt",
        "blip_buf (in melonDS) - GNU LGPL 2.1" to "third_party/melonds/src/blip-buf/license.txt",
        "Dolphin's ARM64 emitter (in melonDS) - GNU GPL 2.0 or later" to "third_party/melonds/src/dolphin/license_dolphin.txt",
        "LCD and Scanlines shaders - MIT" to "third_party/licenses/mGBA-shaders-MIT.txt",
        "xBR, xBRZ and ScaleFX shaders (libretro glsl-shaders) - MIT, xBRZ also GNU GPL 3.0" to "third_party/licenses/libretro-shaders.txt",
        "PixelMplus - M+ FONT LICENSE" to "app/src/main/assets/fonts/LICENSE-PixelMplus.txt",
        "Atkinson Hyperlegible - SIL Open Font License 1.1" to "app/src/main/assets/fonts/LICENSE-AtkinsonHyperlegible.txt",
        "PokeAPI data - BSD 3-Clause" to "third_party/licenses/PokeAPI-BSD-3-Clause.txt",
        "AndroidX, Jetpack Compose, Kotlin, Apache Commons Compress / IO / Codec / Lang, Firebase Crashlytics - Apache License 2.0" to "third_party/licenses/Apache-2.0.txt",
        "Apache Commons - NOTICE" to "third_party/licenses/Apache-Commons-NOTICE.txt",
        "XZ for Java - BSD Zero Clause" to "third_party/licenses/XZ-Java-0BSD.txt",
    ).map { (name, path) -> name to rootProject.file(path) }
    inputs.files(parts.map { it.second })
    val out = layout.buildDirectory.file("generated/licenses/licenses.txt")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            parts.joinToString("\n\n") { (name, f) -> "=== $name\n\n" + f.readText().trim() } +
                "\n\n=== Pixel Operator - CC0 1.0\n\nJayvee Enaguas dedicated the font to the public domain (CC0 1.0).\n",
        )
    }
}
tasks.named("preBuild") { dependsOn(licensesAsset) }

tasks.withType<Test> {
    // readNativeTelemetry's bag-read throttle (NativeReader.kt) caches its
    // last result in file-level (process-global) state - harmless in the
    // real app (one NativeConfig per process) but it leaks between different
    // games' tests if they share a JVM. One fresh JVM per test class keeps
    // each game's decode test isolated without touching production code.
    forkEvery = 1
    // Tests that read real ROMs skip without them (Assume). Point them at a folder of
    // the player's own dumps with POKEDAISY_ROM_DIR (the release workflow's runner does).
    System.getenv("POKEDAISY_ROM_DIR")?.takeIf { it.isNotBlank() }?.let { systemProperty("romDir", it) }
    // `make test-quick` (-Pquick): the everyday run. The Paparazzi renders assert nothing and were 86% of the
    // full run's time; BestEffortTest scans 26 whole ROMs. The forks run side by side (still one class each).
    if (project.hasProperty("quick")) {
        filter {
            excludeTestsMatching("*ScreenshotTest")
            excludeTestsMatching("*BestEffortTest")
        }
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    // Firebase (play-services-basement) brings androidx.fragment 1.1.0, too old for registerForActivityResult
    // (lintVitalRelease's InvalidFragmentVersionForActivityResult): ask for one that matches activity 1.9.
    implementation("androidx.fragment:fragment:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Bottom-screen companion UI (ported from tools/android-companion).
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")

    // ROMs in .7z archives (RomArchive; .zip is java.util.zip). xz is 7z's LZMA / LZMA2.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    // Crash reports, opt-in (CrashReports.kt). -ndk catches native crashes too (mGBA, JNI),
    // which is where most of this app's crashes have been.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")

    // Decode-logic regression tests (app/src/test) - see scripts/capture_fixture.sh
    // for how the fixtures they read (app/src/test/resources/fixtures/) get made.
    testImplementation("junit:junit:4.13.2")
    // The real org.json (android.jar's is a stub in unit tests): ScreenScraperTest and DocsSearchTest parse JSON.
    testImplementation("org.json:json:20240303")
}
