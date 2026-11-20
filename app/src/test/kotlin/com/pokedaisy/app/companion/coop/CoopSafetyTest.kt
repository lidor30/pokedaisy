package com.pokedaisy.app.companion.coop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopSafetyTest {
    @Test fun coopCodeNeverTouchesGameMemory() {
        val root = listOf(File("."), File("..")).first { File(it, "settings.gradle.kts").isFile }
        val packagePath = "com/pokedaisy/app/companion/coop"
        val files = listOf("app/src/main/kotlin", "shared/src/commonMain/kotlin", "shared-ui/src/commonMain/kotlin")
            .flatMap { source -> File(root, "$source/$packagePath").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("Android co-op transport sources weren't found", files.any { it.name == "AndroidCoopNetwork.kt" })
        assertTrue("Shared co-op session sources weren't found", files.any { it.name == "CoopLinkImpl.kt" })
        assertTrue("Shared co-op transport framing was not found", files.any { it.name == "CoopFrames.kt" })
        for (f in files) {
            val text = f.readText()
            for (word in listOf("MgbaCore", "pkWrite", "pkCall", "EmulatorEngine")) {
                assertTrue("${f.name} mentions $word", !text.contains(word))
            }
        }
    }
}
