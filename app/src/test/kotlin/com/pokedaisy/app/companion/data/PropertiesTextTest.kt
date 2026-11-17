package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/** PropertiesText reads what java.util.Properties writes and writes what it reads back, the same entries. */
class PropertiesTextTest {
    private val samples = listOf(
        "a" to "BUTTON_A, Z",
        "save_state" to "BUTTON_SELECT+BUTTON_R1, F1",
        "empty" to "",
        "spaced key" to "  leading spaces",
        "eq=key:colon" to "value = with: separators # and ! marks",
        "back\\slash" to "C:\\path\\to",
        "tabs" to "a\tb\nc\rd\u000Ce",
        "unicode" to "é — ポケ 가",
        "#notacomment" to "!neither",
    )

    private fun javaText(entries: List<Pair<String, String>>, comment: String?): String {
        val p = Properties()
        for ((k, v) in entries) p.setProperty(k, v)
        return StringWriter().also { p.store(it, comment) }.toString()
    }

    private fun javaRead(text: String): Map<String, String> {
        val p = Properties()
        p.load(StringReader(text))
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }

    @Test fun readsWhatPropertiesWrites() {
        val text = javaText(samples, "PokeDaisy GBA button map — Android key names, comma-separated")
        assertEquals(samples.toMap(), PropertiesText.parse(text))
    }

    @Test fun writesWhatPropertiesReads() {
        val text = PropertiesText.format(samples.toMap(), "PokeDaisy hotkeys — comma-separated chords,\n'+' joins keys")
        assertEquals(samples.toMap(), javaRead(text))
        assertEquals(samples.map { it.first }, PropertiesText.parse(text).keys.toList())
    }

    @Test fun readsHandWrittenFiles() {
        val text = """
            # a comment
              ! another
            a = BUTTON_A, Z
            b:BUTTON_B
            c BUTTON_C
            long = one, \
                   two, \
                   three
            dup=first
            dup=second
            trailing=end\
        """.trimIndent()
        assertEquals(javaRead(text), PropertiesText.parse(text))
    }
}
