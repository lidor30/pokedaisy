import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The app's data layer + Android shims, in its own module so its huge
// generated tables compile once instead of on every UI edit.
plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
}

// Compose Multiplatform's libraries, by coordinates (the root module uses the same version): its Gradle plugin
// isn't applied, the Compose compiler is Kotlin's own.
val cmp: String by rootProject.extra
val desktopTarget = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "macos-arm64" else "macos-x64"
        os.contains("win") -> "windows-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

val app = rootDir.resolve("../app/src/main/kotlin/com/pokedaisy/app")
val appSrc = fileTree(app) {
    listOf("companion/data/**", "companion/*.kt", "companion/i18n/**", "companion/coop/**", "FeatureFlags.kt", "MgbaCore.kt", "Prefs.kt", "cheats/Cheats.kt", "GbaControls.kt", "Hotkeys.kt", "GameVolume.kt")
        .forEach { include(it) }
}
sourceSets["main"].kotlin.srcDir("shims")
// -PgameExport (the game data the 3DS port builds from: 3ds/tools/gen_games.py): MgbaCore without its native library,
// so the app's own detection runs over ROM files on a desktop JVM. See src/render/GameExport.kt.
val gameExport = project.hasProperty("gameExport")
val exportCore = layout.buildDirectory.file("export/MgbaCore.kt")
val stubCore = tasks.register("stubCore") {
    val src = app.resolve("MgbaCore.kt")
    inputs.file(src)
    outputs.file(exportCore)
    doLast {
        val delegated = setOf("pkRomCode", "pkRomSize", "pkPlatform", "pkReadBytes", "pkRomRead")
        val text = src.readText()
            .replace("System.loadLibrary(\"pokedaisy\")", "")
            .replace(Regex("external fun (\\w+)\\((.*?)\\)(: ([\\w.<>?]+))?", RegexOption.DOT_MATCHES_ALL)) { m ->
                val name = m.groupValues[1]
                val args = m.groupValues[2]
                val type = m.groupValues[4]
                val body = when {
                    name in delegated -> "ExportCore.$name(" + args.split(",").filter { it.isNotBlank() }.joinToString(", ") { it.substringBefore(":").trim() } + ")"
                    type.isEmpty() -> "Unit"
                    type.endsWith("?") -> "null"
                    type == "Boolean" -> "false"
                    type == "Int" -> "0"
                    type == "Long" -> "0L"
                    type == "Float" -> "0f"
                    type == "Double" -> "0.0"
                    type == "String" -> "\"\""
                    type == "IntArray" -> "IntArray(0)"
                    type == "ByteArray" -> "ByteArray(0)"
                    type == "LongArray" -> "LongArray(0)"
                    type == "ShortArray" -> "ShortArray(0)"
                    type.startsWith("Array<") -> "emptyArray()"
                    else -> "throw UnsupportedOperationException()"
                }
                "fun $name($args)" + (if (type.isEmpty()) "" else ": $type") + " = $body"
            }
        val out = exportCore.get().asFile
        out.parentFile.mkdirs()
        out.writeText(text + """

/** What the stubbed core answers: one ROM file (GameExport.kt). */
object ExportCore {
    var rom = ByteArray(0)
    var platform = 0
    fun pkRomCode(): String? = if (rom.size > 0xB0) String(rom, 0xAC, 4, Charsets.ISO_8859_1) else null
    fun pkRomSize(): Long = rom.size.toLong()
    fun pkPlatform(): Int = platform
    fun pkReadBytes(addr: Long, len: Int): ByteArray? {
        if (addr >= 0x08000000L) {
            val off = (addr - 0x08000000L).toInt()
            return if (off + len <= rom.size) rom.copyOfRange(off, off + len) else ByteArray(len)
        }
        return ByteArray(len)
    }
    fun pkRomRead(off: Long, len: Int): ByteArray? = rom.copyOfRange(off.toInt(), minOf(rom.size, off.toInt() + len))
}
""")
    }
}
tasks.withType<KotlinCompile>().configureEach {
    if (gameExport) {
        dependsOn(stubCore)
        source(appSrc.matching { exclude("MgbaCore.kt") })
        source(exportCore)
    } else {
        source(appSrc)
    }
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api("org.jetbrains.compose.desktop:desktop-jvm-$desktopTarget:$cmp")
    // The co-op invite as a QR code (CoopQr).
    implementation("com.google.zxing:core:3.5.3")
    // CoopPairs reads and writes pairs.json.
    implementation("org.json:json:20240303")
    // CoopRelaySocket (the relay over WebSocket).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // CoopKeys' X25519 (the co-op connection's Noise handshake).
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
}
