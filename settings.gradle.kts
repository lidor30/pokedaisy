pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PokeDaisy"
include(":app")
// The companion's data and language code, for Android and iOS (shared/build.gradle.kts).
include(":shared")
// The companion's screens for iOS, compiled where they live (shared-ui/build.gradle.kts).
include(":shared-ui")

// 3DS games (Azahar, n3ds/): its own module, because Azahar's CMake wants 3.25+ and the app's native build is on 3.22.
// Only with the flag (`-Ppokedaisy.n3ds=true` or `pokedaisy.n3ds=true` in local.properties): Azahar takes many minutes to
// build, and no other build should pay for it until 3DS games ship (FeatureFlags.n3ds).
val n3dsFlag = (providers.gradleProperty("pokedaisy.n3ds").orNull
    ?: file("local.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.trim().startsWith("pokedaisy.n3ds=") }?.substringAfter("="))
    ?.trim()?.toBoolean() == true
if (n3dsFlag) include(":n3ds")
