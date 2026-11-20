import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// Portable companion screens, settings, controls and co-op, with Android and iOS hosts.
plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The iPad app (iosApp/) links the screens as one static framework, PokeDaisyUI: its Swift side calls
    // `PokeDaisyHost.shared` (src/iosMain: the iOS hosts and the companion's view controller).
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "PokeDaisyUI"
            isStatic = true
        }
    }

    sourceSets {
        commonTest { dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.6.1")
        } }
        androidMain { dependencies { implementation("io.ktor:ktor-client-android:3.6.1") } }
        iosMain { dependencies { implementation("io.ktor:ktor-client-darwin:3.6.1") } }
        commonMain {
            dependencies {
                api(project(":shared"))
                // Compose Multiplatform 1.7.x, as shared/ (Compose 1.7 on Android, the app's BOM picks the exact version).
                api("org.jetbrains.compose.foundation:foundation:1.7.3")
                api("org.jetbrains.compose.material3:material3:1.7.3")
                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("io.ktor:ktor-client-core:3.6.1")
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

// Native shader tests use the exact bundled GLSL assets, without copying their algorithms into fixtures.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    val shaders = rootDir.resolve("app/src/main/assets/shaders").path
    inputs.dir(rootDir.resolve("app/src/main/assets/shaders"))
    environment("PK_SHADER_DIR", shaders)
    environment("SIMCTL_CHILD_PK_SHADER_DIR", shaders)
}
