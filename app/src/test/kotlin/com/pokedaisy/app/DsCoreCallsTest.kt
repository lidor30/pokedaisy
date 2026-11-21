package com.pokedaisy.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every call in pk_nds_cores.h's list is defined by each DS core: melonDS (pk_ndsm_), DSperate (pk_ndsd_) and BananaDS
 * (pk_ndsb_). BananaDS is a library looked up call by call (pk_nds_bnd_load.cpp): one it lacks and it doesn't load, so no
 * DS game starts on it - EXP SHARE's set_exp_share did that in 1.6.2-beta.1.
 */
class DsCoreCallsTest {
    private val cpp = listOf(File("src/main/cpp"), File("app/src/main/cpp")).first { it.isDirectory }

    @Test
    fun everyCoreDefinesEveryCall() {
        val list = File(cpp, "nds/pk_nds_cores.h").readText()
        val calls = Regex("""^\s*X\(\s*[^,]+,\s*(\w+)\s*,""", RegexOption.MULTILINE).findAll(list).map { it.groupValues[1] }.toList()
        assertTrue("pk_nds_cores.h's list read: ${calls.size} calls", calls.size > 20)
        for ((prefix, file) in listOf("pk_ndsm_" to "nds/pk_nds.cpp", "pk_ndsd_" to "nds/pk_nds_dsp.cpp", "pk_ndsb_" to "bananads/pk_nds_bnd.cpp")) {
            val text = File(cpp, file).readText()
            val missing = calls.filterNot { Regex("""\b$prefix$it\s*\(""").containsMatchIn(text) }
            assertTrue("$file defines no $prefix${missing.joinToString(", $prefix")}", missing.isEmpty())
        }
    }
}
