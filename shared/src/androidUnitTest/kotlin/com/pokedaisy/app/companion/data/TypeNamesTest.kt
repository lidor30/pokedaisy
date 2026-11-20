package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** GitHub #40: a type is drawn in the game's language, while its English name stays the key. */
class TypeNamesTest {
    @After fun english() { romLanguage = 'E' }

    @Test
    fun `each language's own words`() {
        assertEquals("FIRE", typeLabel("Fire"))
        assertEquals("Fire", typeInText("Fire"))
        romLanguage = 'F'
        assertEquals("FEU", typeLabel("Fire"))
        assertEquals("ÉLECTRIK", typeLabel("Electric"))
        assertEquals("FEU", typeInText("Fire"))
        romLanguage = 'D'
        assertEquals("UNLICHT", typeLabel("Dark"))
        romLanguage = 'I'
        assertEquals("COLEOTTERO", typeLabel("Bug"))
        romLanguage = 'S'
        assertEquals("SINIESTRO", typeLabel("Dark"))
        romLanguage = 'J'
        assertEquals("ほのお", typeLabel("Fire"))
        // Not a type every language names (a hack's own): English, in capitals.
        assertEquals("???", typeLabel("???"))
    }

    @Test
    fun `every vanilla type has a word in every language`() {
        val vanilla = activeTypeNames.values.filter { it != "???" }.map { it.uppercase() }
        for (lang in "FDISJ") {
            romLanguage = lang
            vanilla.forEach { t -> assert(typeLabel(t) != t || t == "NORMAL" || t == "POISON" || t == "DRAGON") { "$lang: $t" } }
        }
    }
}
