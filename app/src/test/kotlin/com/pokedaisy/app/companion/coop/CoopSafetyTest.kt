package com.pokedaisy.app.companion.coop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopSafetyTest {
    @Test fun coopCodeNeverTouchesGameMemory() {
        val packagePath = "com/pokedaisy/app/companion/coop"
        val roots = listOf(File("src/main/kotlin"), File("../shared-ui/src/commonMain/kotlin"))
        val files = roots.flatMap { root ->
            val dir = File(root, packagePath)
            assertTrue("the co-op sources weren't found at ${dir.absolutePath}", dir.isDirectory)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        for (f in files) {
            val text = f.readText()
            for (word in listOf("MgbaCore", "pkWrite", "pkCall", "EmulatorEngine")) {
                assertTrue("${f.name} mentions $word", !text.contains(word))
            }
        }
    }
}
