import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Compose Desktop build of the app's real UI sources, for rendering PNGs
// without the Android SDK - see README.md.
plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.5.12"
}

val app = rootDir.resolve("../app/src/main/kotlin/com/pokedaisy/app")
val appIncludes = listOf(
    "companion/ui/**", "SettingsActivity.kt", "CoopSettingsPages.kt", "LibraryActivity.kt", "SavesLocation.kt",
    "CoverArtSync.kt", "CoverSync.kt", "DocsApi.kt", "DocsSearch.kt", "RomFolder.kt", "StorageAccess.kt", "SetupScreen.kt", "AppUpdater.kt", "AppUpdate.kt", "GameSaves.kt", "RzipSave.kt", "SaveBackups.kt", "GameInfo.kt", "LibraryOrder.kt", "RomSupportRequest.kt", "RomIntake.kt", "BestEffortShare.kt", "SaveStates.kt", "RomIdentity.kt", "RomArchive.kt", "CompanionSupport.kt", "CoverPicker.kt", "SteamGridDbGames.kt", "SteamGridDbClient.kt", "GameTitles.kt", "SaveEditorActivity.kt", "SaveFileEdits.kt",
    "BpsPatch.kt", "RomPatcher.kt", "RomPatchFlow.kt", "RomCrcs.kt",
    "ScreenScraperClient.kt", "ScreenScraperUi.kt", "GameMedia.kt", "LauncherScreen.kt",
    "achievements/**", "overlay/Overlays.kt", "overlay/OverlayLabels.kt",
)

// Copied (not referenced in place) so the few Android-only APIs desktop
// Compose 1.5 lacks can be rewritten on the way.
val prepSrc = tasks.register<Sync>("prepSrc") {
    from(app) { appIncludes.forEach { include(it) } }
    // PC BOXES' demo save is built with the save tests' own writer.
    from(rootDir.resolve("../app/src/test/kotlin/com/pokedaisy/app/companion/data/save")) { include("SyntheticSave.kt"); into("synthetic") }
    into(layout.buildDirectory.dir("appsrc"))
    filter { line: String ->
        line.replace("Icons.AutoMirrored.Filled.", "Icons.Filled.")
            .replace("icons.automirrored.filled.", "icons.filled.")
    }
}
sourceSets["main"].kotlin.srcDir("src/render")
sourceSets["main"].kotlin.srcDir("src/shims")
// GameExport.kt needs -PgameExport's stubbed core (data/build.gradle.kts).
if (!project.hasProperty("gameExport")) sourceSets["main"].kotlin.exclude("**/GameExport.kt")
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

// The game data the 3DS port builds from, with -PgameExport: every ROM under -Proms through the app's own detection.
tasks.register<JavaExec>("gameExport") {
    description = "Writes every ROM's detected config and tables as JSON (see src/render/GameExport.kt); needs -PgameExport."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.GameExportKt")
    args = listOf((project.findProperty("roms") as String?) ?: "", (project.findProperty("out") as String?) ?: "")
    workingDir = rootDir.resolve("../app/src/main/assets")
    maxHeapSize = "3g"
    systemProperty("java.awt.headless", "true")
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
    // -PgameText=EMERALD_LEGACY: a hack that runs as -Pgame's kind under its own names and config (romGameCode).
    systemProperty("gameText", (project.findProperty("gameText") as String?) ?: "")
    // -Plang=JA / FR / DE / IT / ES / BR / PT / KO: the app's text in that language.
    systemProperty("lang", (project.findProperty("lang") as String?) ?: "")
    // -Pdark=true: every screen in DARK MODE (pair with -Pout=build/shots-dark).
    systemProperty("dark", (project.findProperty("dark") as String?) ?: "")
    // -Ptheme=CHERRY: every screen in that THEME (its English name; limited-time ones too, whatever the date).
    systemProperty("theme", (project.findProperty("theme") as String?) ?: "")
    // -PcompanionW=960 [-PcompanionH=1080]: the companion as a single-screen device's side panel
    // of that size, at the density the app gives it there (sidePanelDensity).
    systemProperty("companionW", (project.findProperty("companionW") as String?) ?: "")
    systemProperty("companionH", (project.findProperty("companionH") as String?) ?: "")
    systemProperty("companionDensity", (project.findProperty("companionDensity") as String?) ?: "")
    // Optional: a ROM the GUIDE's live pages (HERE, NEXT BOSS) read from, with the
    // repo's save fixture for that game - see Main.kt's liveGuide().
    systemProperty("rom", (project.findProperty("rom") as String?) ?: "")
    systemProperty("hour", (project.findProperty("hour") as String?) ?: "")
    // FireRed / Emerald art is rebuilt from ROMs (see Main.kt's romArt()).
    systemProperty("artRoms", (project.findProperty("artRoms") as String?) ?: "")
    // -PndsRom=<.nds> -PndsFixture=heartgold: a DS game's real-data shots ($game-real-*): its art rebuilt from the cart
    // (NdsArt), the snapshot read from that save fixture (app/src/test/resources/fixtures/<name>/mainram.bin).
    systemProperty("ndsRom", (project.findProperty("ndsRom") as String?) ?: "")
    systemProperty("ndsFixture", (project.findProperty("ndsFixture") as String?) ?: "")
    // -PdsBattleShots=<dir>: the ds-battle-* shots over that folder's <game>.png (a DS top screen, 256x192, e.g. nds_dump's
    // battle frames cropped) instead of a plain stand-in. Game art: look at the PNGs, never commit them.
    systemProperty("dsBattleShots", (project.findProperty("dsBattleShots") as String?) ?: "")
    systemProperty("decomps", System.getenv("DECOMPS").orEmpty())
    // Asset paths are opened relative to workingDir, so pass the icon dir relative to it.
    val monIcons = (project.findProperty("monIcons") as String?) ?: System.getenv("MON_ICONS").orEmpty()
    systemProperty("monIcons", if (monIcons.isBlank()) "" else workingDir.toPath().relativize(file(monIcons).toPath()).toString())
    systemProperty("scratch", layout.buildDirectory.dir("scratch").get().asFile.absolutePath)
    // -PssMedia=build/sscheck/media: the launcher shots show real ScreenScraper media (from `gradle sscheck`).
    val ssMedia = (project.findProperty("ssMedia") as String?).orEmpty()
    systemProperty("ssMedia", if (ssMedia.isBlank()) "" else file(ssMedia).absolutePath)
}

tasks.register<JavaExec>("sscheck") {
    description = "Asks ScreenScraper for every supported game (SS_USER / SS_PASSWORD / SS_DEV_ID / SS_DEV_PASSWORD from the environment) -> build/sscheck/report.md + media/; -Ponly=unbound,emerald, -Pvideos=true."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.SsCheckKt")
    args = listOf(layout.buildDirectory.dir("sscheck").get().asFile.absolutePath, (project.findProperty("only") as String?) ?: "")
    systemProperty("videos", (project.findProperty("videos") as String?) ?: "")
    systemProperty("scratch", layout.buildDirectory.dir("scratch").get().asFile.absolutePath)
}

// The docs' screenshots (DocsShots.kt): invented creatures and names, no ROM art, written into
// docs/manual/shots. Run `npm run docs-art` (creature icons, the map) and `npm run build` (the HELP
// shots read the docs API it builds) in website/ first. -Ponly=a,b renders just those.
tasks.register<JavaExec>("docsShots") {
    description = "Renders the docs' screenshots into ../docs/manual/shots (see DocsShots.kt)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.MainKt")
    val manual = rootDir.resolve("../docs/manual")
    args = listOf(manual.resolve("shots").absolutePath, (project.findProperty("only") as String?) ?: "")
    workingDir = rootDir.resolve("../app/src/main/assets")
    systemProperty("java.awt.headless", "true")
    systemProperty("docs", "true")
    systemProperty("game", "FIRERED")
    systemProperty("lang", "")
    systemProperty("dark", (project.findProperty("dark") as String?) ?: "")
    systemProperty("companionW", "")
    systemProperty("companionH", "")
    systemProperty("rom", "")
    systemProperty("artRoms", "")
    systemProperty("decomps", "")
    systemProperty("monIcons", workingDir.toPath().relativize(manual.resolve("art/creatures").toPath()).toString())
    systemProperty("docsDir", manual.absolutePath)
    systemProperty("docsApi", rootDir.resolve("../website/dist/api/docs/v1/docs.json").absolutePath)
    // A device-like folder: the library shows each ROM's path, and a screenshot mustn't show the render
    // machine's home folder.
    systemProperty("scratch", "/tmp/storage/emulated/0/Android/data/com.pokedaisy.app")
}

// The app's docs search against the website's cases (DocsSearchCheck.kt), where the app's unit tests can't run.
tasks.register<JavaExec>("docsSearchCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.DocsSearchCheckKt")
    args = listOf(rootDir.resolve("../website/test/docsearch-cases.json").absolutePath)
}

// GitHub #229: produceKeyedState starts over when its keys change (KeyedStateCheck.kt), on the bare Compose runtime.
tasks.register<JavaExec>("keyedStateCheck") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("render.KeyedStateCheckKt")
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
