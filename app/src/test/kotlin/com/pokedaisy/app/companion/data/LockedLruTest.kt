package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LockedLru] keeps the most recently used entries, as the access-ordered LinkedHashMaps it replaced did. */
class LockedLruTest {
    @Test fun dropsTheLeastRecentlyUsed() {
        val lru = LockedLru<String, Int>(2)
        lru["a"] = 1
        lru["b"] = 2
        assertEquals(1, lru["a"]) // a is now the newest
        lru["c"] = 3 // drops b
        assertNull(lru["b"])
        assertEquals(1, lru["a"])
        assertEquals(3, lru["c"])
        lru["c"] = 4 // a replaced key keeps the size
        assertEquals(1, lru["a"])
        assertEquals(4, lru["c"])
    }
}
