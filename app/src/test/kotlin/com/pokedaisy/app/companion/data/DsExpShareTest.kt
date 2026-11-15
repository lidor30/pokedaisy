package com.pokedaisy.app.companion.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EXP SHARE on the DS ([ExpShare.dsFind]): Gen 4's US carts on melonDS (the native side's patch is theirs alone). */
class DsExpShareTest {
    @Test
    fun `the Gen 4 US carts on melonDS, nothing else`() {
        for ((code, rev) in listOf("CPUE" to 1, "IPKE" to 0, "IPGE" to 0)) {
            assertTrue("$code rev $rev", ExpShare.dsFind(code, rev, dsperate = false, renegade = false)?.ds == true)
            assertNull("$code on DSperate", ExpShare.dsFind(code, rev, dsperate = true, renegade = false))
        }
        assertNull("Renegade Platinum", ExpShare.dsFind("CPUE", 1, dsperate = false, renegade = true))
        // Platinum rev 0, the French / German carts (other addresses) and Gen 5: not checked.
        for ((code, rev) in listOf("CPUE" to 0, "CPUF" to 0, "IPKF" to 0, "IPKD" to 0, "IRBO" to 0, "IREO" to 0))
            assertNull("$code rev $rev", ExpShare.dsFind(code, rev, dsperate = false, renegade = false))
    }
}
