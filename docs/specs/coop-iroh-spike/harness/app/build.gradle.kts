plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "spike.iroh"
    compileSdk = 34
    defaultConfig {
        applicationId = "spike.iroh"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
val own = providers.gradleProperty("own").isPresent
android {
    packaging { resources { excludes += listOf("darwin-*/**", "win32-*/**", "linux-*/**") } }
    if (own) sourceSets["main"].jniLibs.srcDir("own/jniLibs")
}
dependencies {
    if (own) {
        // Our own size-tuned libiroh_ffi.so + the plain Kotlin API + IrohAndroid's class + JNA's Android build.
        implementation("computer.iroh:iroh:1.2.0") { exclude(group = "net.java.dev.jna", module = "jna") }
        implementation("net.java.dev.jna:jna:5.15.0@aar")
        implementation(files("own/libs/iroh-android-classes.jar"))
    } else {
        implementation("computer.iroh:iroh-android:1.2.0")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
