package com.pokedaisy.app.companion.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

/** [stripAccents] gives what java.text.Normalizer's NFD without its marks gave, for every letter it can meet. */
class FoldTest {
    private fun jdk(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    @Test fun sameAsTheJdkOnLatinKanaAndHangul() {
        val ranges = listOf(0x20..0x24F, 0x300..0x36F, 0x1E00..0x1EFF, 0x3040..0x30FF, 0xAC00..0xD7A3)
        for (r in ranges) for (code in r) {
            val s = code.toChar().toString()
            assertEquals("U+%04X".format(code), jdk(s), stripAccents(s))
        }
    }

    @Test fun wordsInEveryLanguage() {
        for (w in listOf("Pokémon", "DÉFINITIONS", "ÉCRAN", "MÄRZ", "ESPAÑOL", "GUARDAR", "ガブリアス", "ポケモン", "가방", "도감", "é"))
            assertEquals(w, jdk(w), stripAccents(w))
    }
}
