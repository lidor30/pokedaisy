import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// The companion's data and language code (app/.../companion/data and companion/i18n) as a Kotlin Multiplatform
// module, compiled where it lives: Android for the app, iOS for the iPad app. Nothing in those folders may use
// java.* or android.* (the iOS compile fails on it); what differs by platform comes in from outside
// (EmulatorCore, Logs' sink). The app depends on it and leaves those folders out of its own compile.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appSrc = rootDir.resolve("app/src/main/kotlin")
@Suppress("UNCHECKED_CAST")
val sharedCoreSources = rootProject.extra["sharedCoreSources"] as List<String>
val appTest = rootDir.resolve("app/src/test/kotlin/com/pokedaisy/app/companion")

// The emulator core for iOS (pk_api.h: mGBA + the app's portable C), built with Xcode's tools by
// native/build_ios_core.sh into build/pkcore/<slice>/libpkcore.a, reached from iosMain through cinterop (PkApiCore).
// So the iOS compile needs a Mac; the Android build never runs it.
val pkCoreDir = layout.buildDirectory.dir("pkcore")
val buildIosCore by tasks.registering(Exec::class) {
    description = "Builds the emulator core (pk_api.h) for the iPad and the iPad simulator."
    val cpp = rootDir.resolve("app/src/main/cpp")
    inputs.files(fileTree(cpp) { include("pk_*.c", "pk_*.h", "pk_api.cmake") })
    inputs.files(fileTree(projectDir.resolve("native")))
    outputs.dir(pkCoreDir)
    commandLine(projectDir.resolve("native/build_ios_core.sh").path, pkCoreDir.get().asFile.path)
}

// linux/make_diagnostic.py's two test ROMs (no commercial content) for the iOS core's test (PkApiCoreTest).
val diagnosticDir = layout.buildDirectory.dir("diagnostic")
val diagnosticRoms by tasks.registering(Exec::class) {
    val script = rootDir.resolve("linux/make_diagnostic.py")
    inputs.file(script)
    outputs.dir(diagnosticDir)
    doFirst { diagnosticDir.get().asFile.mkdirs() }
    workingDir(diagnosticDir)
    commandLine("python3", script.path)
}

// The data / i18n tests that also use the app's own classes stay the app's (root build.gradle.kts).
@Suppress("UNCHECKED_CAST")
val appOnlyTests = rootProject.extra["appOnlyTests"] as List<String>

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-arm64-simulator").forEach { (target, slice) ->
        target.compilations.getByName("main").cinterops.create("pkapi") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pkapi.def"))
            includeDirs(rootDir.resolve("app/src/main/cpp"))
            extraOpts("-libraryPath", pkCoreDir.get().dir(slice).asFile.path)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(appSrc)
            kotlin.include(sharedCoreSources)
            kotlin.include("com/pokedaisy/app/game/**")
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

// The cinterop step embeds the library: rerun it when the library changes.
tasks.matching { it.name.startsWith("cinteropPkapi") }.configureEach {
    dependsOn(buildIosCore)
    inputs.dir(pkCoreDir)
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(diagnosticRoms)
    val dir = diagnosticDir.get().asFile.path
    // The simulator passes a SIMCTL_CHILD_ variable on to the test without its prefix.
    environment("SIMCTL_CHILD_PK_DIAGNOSTIC_DIR", dir)
    environment("PK_DIAGNOSTIC_DIR", dir)
}

// -PcomposeReports: the Compose compiler's stability report (build/compose-reports/*-classes.txt).
if (project.hasProperty("composeReports")) {
    composeCompiler { reportsDestination = layout.buildDirectory.dir("compose-reports") }
}
