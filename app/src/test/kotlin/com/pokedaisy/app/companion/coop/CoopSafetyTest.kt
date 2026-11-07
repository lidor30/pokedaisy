package com.pokedaisy.app.companion.coop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopSafetyTest {
    @Test fun coopCodeNeverTouchesGameMemory() {
        val dir = File("src/main/kotlin/com/pokedaisy/app/companion/coop")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the co-op sources weren't found at ${dir.absolutePath}", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            for (word in listOf("MgbaCore", "pkWrite", "pkCall", "EmulatorEngine")) {
                assertTrue("${f.name} mentions $word", !text.contains(word))
            }
        }
    }
}
