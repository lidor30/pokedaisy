package com.pokedaisy.app.companion.i18n

import com.pokedaisy.app.companion.data.GuideId
import com.pokedaisy.app.companion.data.SaveProgress
import com.pokedaisy.app.companion.data.allAreaThings
import com.pokedaisy.app.companion.data.gameGuide
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #35: a French / German / ... game's GUIDE was half English. Every line the guides of the
 * games that come in other languages can show - pages, bosses, HERE's buildings - must be in
 * [guideTranslations] (or the app's own tables) with all five languages. The lines still missing,
 * per guide, are written to app/build/guide-missing/<GUIDE>.txt (one English line each).
 */
class GuideTranslationsTest {
    private val localized = listOf(
        GuideId.FIRERED, GuideId.LEAFGREEN, GuideId.EMERALD, GuideId.RUBY, GuideId.SAPPHIRE, GuideId.UNBOUND, GuideId.QUETZAL, GuideId.QUETZAL_V084,
    )

    private fun hasLetters(s: String) = s.any { it.isLetter() }

    /** Every hand-written line [id]'s GUIDE can show. */
    private fun lines(id: GuideId): Set<String> {
        val out = linkedSetOf<String>()
        val guide = gameGuide(id, randomized = null) ?: return out
        for (page in guide.pages) for (section in page.sections) {
            out += section.heading
            section.note?.let { out += it }
            for (e in section.entries) {
                out += e.title
                e.hint?.let { out += it }
                out += e.answer
                e.detail?.let { out += it }
            }
        }
        val none = SaveProgress(ByteArray(0x400), ByteArray(0x800))
        val bosses = guide.bosses + (0..80).flatMap { g -> guide.bossesFor?.invoke(g).orEmpty() }
        for (b in bosses.distinct()) {
            out += b.title
            out += b.where
            b.teams(none).mapNotNull { it.first }.forEach { out += it }
            b.variants.forEach { out += it.first }
        }
        allAreaThings(id).values.flatten().forEach { if (it.where.isNotEmpty()) out += it.where }
        return out.filter { it.isNotBlank() && hasLetters(it) }.toCollection(linkedSetOf())
    }

    @Test fun everyLineIsTranslated() {
        val dir = File("build/guide-missing").apply { mkdirs() }
        val report = localized.associateWith { id ->
            lines(id).filter { s ->
                val t = guideTranslations[s] ?: translations[s]
                t == null || listOf(t.ja, t.fr, t.de, t.it, t.es).any { it.isBlank() }
            }
        }
        report.forEach { (id, missing) ->
            File(dir, "$id.txt").writeText(missing.joinToString("") { "$it\n" })
        }
        val total = report.values.sumOf { it.size }
        assertTrue(
            "untranslated guide lines: " + report.entries.joinToString { "${it.key} ${it.value.size}" } + " (see app/build/guide-missing/)",
            total == 0,
        )
    }
}
