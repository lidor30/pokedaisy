package com.pokedaisy.app.companion.data

import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The okio file helpers behave as the java.io.File calls they replaced. */
class CacheFilesTest {
    private fun <T> inTemp(block: (okio.Path) -> T): T {
        val dir = Files.createTempDirectory("cachefiles").toFile()
        return try { block(dir.toOkioPath()) } finally { dir.deleteRecursively() }
    }

    @Test fun `writes create their folders and replace whole files`() = inTemp { dir ->
        val f = dir / "a" / "b" / "c.bin"
        assertFalse(f.isFile)
        f.writeAtomically(byteArrayOf(1, 2, 3))
        assertTrue(f.isFile)
        assertTrue((dir / "a" / "b").isDirectory)
        assertFalse("no .tmp left", (dir / "a" / "b" / "c.bin.tmp").isFile)
        f.writeAtomically(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), f.readBytes())
        assertEquals(1L, f.length)
        assertTrue(f.lastModified > 0)
    }

    @Test fun `lines read as File readLines`() = inTemp { dir ->
        val f = dir / "t.txt"
        f.writeText("a\nb\r\nc\n")
        assertEquals(listOf("a", "b", "c"), f.readLines())
        f.writeTextAtomically("")
        assertEquals(emptyList<String>(), f.readLines())
        assertEquals("", f.readText())
    }

    @Test fun `missing files read as File did`() = inTemp { dir ->
        val f = dir / "none"
        assertFalse(f.isFile)
        assertFalse(f.isDirectory)
        assertEquals(0L, f.length)
        assertEquals(0L, f.lastModified)
        assertEquals(emptyList<okio.Path>(), f.listFiles())
        f.delete()   // quiet
    }

    @Test fun `copy, list and delete`() = inTemp { dir ->
        val a = dir / "x" / "a"
        a.writeBytes(byteArrayOf(5))
        val b = dir / "y" / "b"
        a.copyTo(b)
        a.writeBytes(byteArrayOf(6))
        a.copyTo(b)   // over it
        assertArrayEquals(byteArrayOf(6), b.readBytes())
        assertEquals(listOf("a"), (dir / "x").listFiles().map { it.name })
        (dir / "y").deleteRecursively()
        assertFalse((dir / "y").isDirectory)
        (dir / "z").mkdirs()
        (dir / "z").mkdirs()   // quiet when it's there
        assertTrue((dir / "z").isDirectory)
    }
}
