import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Compose Desktop build of the app's real UI sources, for rendering PNGs
// without the Android SDK - see README.md.
plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.5.12"
}

val app = rootDir.resolve("../app/src/main/kotlin/com/pokedaisy/app")
val appIncludes = listOf(
    "companion/ui/**", "SettingsActivity.kt", "LibraryActivity.kt", "SavesLocation.kt",
    "CoverArtSync.kt", "CoverSync.kt", "RomFolder.kt", "StorageAccess.kt", "SetupScreen.kt", "AppUpdater.kt", "AppUpdate.kt", "GameSaves.kt", "SaveBackups.kt", "GameInfo.kt", "RomSupportRequest.kt", "RomIntake.kt", "BestEffortShare.kt", "SaveStates.kt", "RomIdentity.kt", "RomArchive.kt", "CompanionSupport.kt", "CoverPicker.kt", "SteamGridDbGames.kt", "SteamGridDbClient.kt", "GameTitles.kt",
    "achievements/**", "overlay/Overlays.kt", "overlay/OverlayLabels.kt",
)

// Copied (not referenced in place) so the few Android-only APIs desktop
// Compose 1.5 lacks can be rewritten on the way.
val prepSrc = tasks.register<Sync>("prepSrc") {
    from(app) { appIncludes.forEach { include(it) } }
    into(layout.buildDirectory.dir("appsrc"))
    filter { line: String ->
        line.replace("Icons.AutoMirrored.Filled.", "Icons.Filled.")
            .replace("icons.automirrored.filled.", "icons.filled.")
    }
}
sourceSets["main"].kotlin.srcDir("src/render")
sourceSets["main"].kotlin.srcDir("src/shims")
tasks.withType<KotlinCompile>().configureEach {
    source(files(layout.buildDirectory.dir("appsrc")).builtBy(prepSrc))
    kotlinOptions.jvmTarget = "17"
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    implementation(project(":data"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.5.12")
}

tasks.register<JavaExec>("render") {
    description = "Renders every screen to build/shots (or -Pout=DIR); -Ponly=a,b filters by name, -Pgame=EMERALD picks the game, -Prom=PATH feeds the GUIDE's live pages."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.MainKt")
    args = listOf(
        (project.findProperty("out") as String?) ?: layout.buildDirectory.dir("shots").get().asFile.absolutePath,
        (project.findProperty("only") as String?) ?: "",
    )
    // The shimmed AssetManager reads from here, like the APK's assets/.
    workingDir = rootDir.resolve("../app/src/main/assets")
    systemProperty("java.awt.headless", "true")
    systemProperty("game", (project.findProperty("game") as String?) ?: "FIRERED")
    // -Plang=JA / FR / DE / IT / ES: the app's text in that language.
    systemProperty("lang", (project.findProperty("lang") as String?) ?: "")
    // -PcompanionW=960 [-PcompanionH=1080]: the companion as a single-screen device's side panel
    // of that size, at the density the app gives it there (sidePanelDensity).
    systemProperty("companionW", (project.findProperty("companionW") as String?) ?: "")
    systemProperty("companionH", (project.findProperty("companionH") as String?) ?: "")
    // Optional: a ROM the GUIDE's live pages (HERE, NEXT BOSS) read from, with the
    // repo's save fixture for that game - see Main.kt's liveGuide().
    systemProperty("rom", (project.findProperty("rom") as String?) ?: "")
    // FireRed / Emerald art is rebuilt from ROMs (see Main.kt's romArt()).
    systemProperty("artRoms", (project.findProperty("artRoms") as String?) ?: "")
    systemProperty("decomps", System.getenv("DECOMPS").orEmpty())
    // Asset paths are opened relative to workingDir, so pass the icon dir relative to it.
    val monIcons = (project.findProperty("monIcons") as String?) ?: System.getenv("MON_ICONS").orEmpty()
    systemProperty("monIcons", if (monIcons.isBlank()) "" else workingDir.toPath().relativize(file(monIcons).toPath()).toString())
    systemProperty("scratch", layout.buildDirectory.dir("scratch").get().asFile.absolutePath)
}

tasks.register<JavaExec>("stress") {
    description = "Switches the companion's tabs rapidly and fails over its memory / time budgets (src/render/Stress.kt); -Pgame=EMERALD picks the game."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.StressKt")
    workingDir = rootDir.resolve("../app/src/main/assets")
    systemProperty("java.awt.headless", "true")
    systemProperty("game", (project.findProperty("game") as String?) ?: "FIRERED")
    systemProperty("artRoms", (project.findProperty("artRoms") as String?) ?: "")
    systemProperty("rom", "")
    systemProperty("decomps", System.getenv("DECOMPS").orEmpty())
    systemProperty("monIcons", "")
    systemProperty("framesPerTap", (project.findProperty("framesPerTap") as String?) ?: "")
    systemProperty("trace", (project.findProperty("trace") as String?) ?: "")
    systemProperty("bars", (project.findProperty("bars") as String?) ?: "")
    systemProperty("scratch", layout.buildDirectory.dir("scratch").get().asFile.absolutePath)
    // A fixed heap, like a phone's per-app limit, so a leak fails instead of growing the heap.
    maxHeapSize = "512m"
}
