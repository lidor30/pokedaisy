package com.pokedaisy.app

import com.pokedaisy.app.companion.i18n.stripAccents
import kotlin.math.ln
import kotlin.math.min

/**
 * Search over the manual: website/src/lib/docsearch.js ported line for line, so the app and /docs/ find
 * the same things. Keep the two in step - website/test/docsearch-cases.json holds queries both test
 * suites (DocsSearchTest, docsearch.test.js) must rank alike.
 *
 * One record per page and one per ## section. Query words are normalised (case, accents), lightly stemmed
 * and matched against each record's words: exact, then as the start of a word, then with a typo or two.
 * The manual's synonyms widen a word; rarer words count more, a title more than the text. Every word has to
 * match somewhere; when nothing matches them all, records matching most of them come back.
 */
class DocsSearch(manual: DocsApi.Manual) {

    class Hit(
        val page: String,
        val anchor: String?,
        val heading: String?,
        val pageTitle: String,
        val score: Double,
        val snippet: String,
        /** [start, end) ranges of [snippet] to highlight. */
        val marks: List<IntRange>,
    )

    private class Record(
        val page: String,
        val anchor: String?,
        val heading: String?,
        val pageTitle: String,
        val body: String,
        val fields: Map<String, List<String>>,
    )

    private class Alt(val t: String, val w: Double)

    private val records: List<Record>
    private val df = HashMap<String, Int>()
    private val synonyms: Map<String, List<String>>

    init {
        records = manual.pages.flatMap { page ->
            sectionsOf(page).map { r ->
                Record(
                    page = page.slug, anchor = r.anchor, heading = r.heading, pageTitle = page.title, body = r.text,
                    fields = mapOf(
                        "title" to terms(r.heading ?: page.title),
                        "keywords" to if (r.anchor != null) emptyList() else page.keywords.flatMap(::terms),
                        "context" to if (r.anchor != null) terms(page.title) else emptyList(),
                        "summary" to if (r.anchor != null) emptyList() else terms(page.summary),
                        "text" to terms(r.text),
                    ),
                )
            }
        }
        for (r in records) for (t in r.fields.values.flatten().toSet()) df[t] = (df[t] ?: 0) + 1
        synonyms = manual.synonyms.entries.associate { (k, v) -> stem(k) to v.flatMap(::terms) }
    }

    private class Section(val anchor: String?, val heading: String?, val text: String)

    /** One record per page (its text before the first ##) and one per ## section. */
    private fun sectionsOf(page: DocsApi.Page): List<Section> {
        class Acc(val anchor: String?, val heading: String?) { val text = mutableListOf<String>() }
        val recs = mutableListOf(Acc(null, null))
        var cur = recs[0]
        fun plain(spans: List<DocsApi.Span>) = spans.joinToString("") { it.text }
        fun walk(blocks: List<DocsApi.Block>) {
            for (b in blocks) when (b) {
                is DocsApi.Block.Heading -> if (b.level == 2) { cur = Acc(b.id, b.text); recs += cur } else cur.text += b.text
                is DocsApi.Block.Paragraph -> cur.text += plain(b.spans)
                is DocsApi.Block.Bullets -> b.items.forEach(::walk)
                is DocsApi.Block.Note -> walk(b.blocks)
                is DocsApi.Block.Table -> (listOf(b.head) + b.rows).forEach { row -> cur.text += row.joinToString(" ") { plain(it) } }
                is DocsApi.Block.Image -> cur.text += (b.caption ?: b.alt)
                DocsApi.Block.Rule -> {}
            }
        }
        walk(page.blocks)
        return recs.map { Section(it.anchor, it.heading, it.text.joinToString(" ")) }
    }

    /** How well [q] matches [t]: 1 the same, 0.75 the start of it, 0.5 a typo away, else 0. */
    private fun matchScore(q: String, t: String): Double {
        if (q == t) return 1.0
        if (q.length >= 2 && t.startsWith(q)) return 0.75
        val max = if (q.length >= 8) 2 else if (q.length >= 4) 1 else 0
        if (max > 0 && distance(q, t, max) <= max) return 0.5
        return 0.0
    }

    /** Each query word's alternatives: itself, plus its synonyms (worth a little less). */
    private fun expand(query: String): List<List<Alt>> = terms(query).map { q ->
        listOf(Alt(q, 1.0)) + (synonyms[q] ?: emptyList()).map { Alt(it, 0.8) }
    }

    /** The best [limit] records for [query], best first. An empty query gives nothing. */
    fun search(query: String, limit: Int = 8): List<Hit> {
        val groups = expand(query)
        if (groups.isEmpty()) return emptyList()
        val n = records.size.toDouble()
        fun idf(t: String) = ln(1 + n / (df[t] ?: 1))
        class Scored(val r: Record, var total: Double, val matched: Int)
        val scored = mutableListOf<Scored>()
        for (r in records) {
            var total = 0.0
            var matched = 0
            for (alts in groups) {
                var best = 0.0
                for (alt in alts) {
                    for ((field, weight) in FIELDS) {
                        var fieldBest = 0.0
                        var fieldTerm: String? = null
                        var hits = 0
                        for (t in r.fields.getValue(field)) {
                            val m = matchScore(alt.t, t)
                            if (m > 0) hits++
                            if (m > fieldBest) { fieldBest = m; fieldTerm = t }
                        }
                        if (fieldBest == 0.0) continue
                        val s = weight * alt.w * fieldBest * idf(fieldTerm!!) * (1 + 0.15 * min(hits - 1, 4))
                        if (s > best) best = s
                    }
                }
                if (best > 0) matched++
                total += best
            }
            if (matched == 0) continue
            // How much of the title the query covers: "game speed" is all of a "Game speed" section but half
            // of "Fast-forward and game speed", so the section comes first.
            val title = r.fields.getValue("title").toSet()
            val covered = title.count { t -> groups.any { alts -> alts.any { matchScore(it.t, t) > 0 } } }
            if (title.isNotEmpty()) total *= 1 + 0.6 * (covered.toDouble() / title.size)
            // A page's own record a little ahead of its sections on a tie, so "fast forward" opens the page.
            if (r.anchor == null) total *= 1.05
            scored += Scored(r, total, matched)
        }
        val most = scored.maxOfOrNull { it.matched } ?: return emptyList()
        return scored
            .filter { it.matched == most }
            .sortedWith(compareByDescending<Scored> { it.total }.thenBy { it.r.page + (it.r.anchor ?: "") })
            .take(limit)
            .map { s ->
                val (text, marks) = snippet(s.r.body, groups)
                Hit(s.r.page, s.r.anchor, s.r.heading, s.r.pageTitle, Math.round(s.total * 1000) / 1000.0, text, marks)
            }
    }

    /** ~160 characters of [body] around the first word that matched, with the ranges to highlight. */
    private fun snippet(body: String, groups: List<List<Alt>>, size: Int = 160): Pair<String, List<IntRange>> {
        if (body.isEmpty()) return "" to emptyList()
        val qs = groups.flatten().map { it.t }
        val hits = WORD.findAll(body).mapNotNull { m ->
            val t = stem(words(m.value).firstOrNull() ?: "")
            if (t.isNotEmpty() && qs.any { matchScore(it, t) > 0 }) m.range.first to m.range.last + 1 else null
        }.toList()
        val first = hits.firstOrNull()?.first ?: 0
        var start = maxOf(0, first - size / 3)
        if (start > 0) {
            val space = body.indexOf(' ', start)
            if (space in 0 until first) start = space + 1
        }
        var end = min(body.length, start + size)
        if (end < body.length) {
            val space = body.lastIndexOf(' ', end)
            if (space > first) end = space
        }
        val pre = if (start > 0) "…" else ""
        val text = pre + body.substring(start, end) + if (end < body.length) "…" else ""
        val marks = hits.filter { (a, b) -> a >= start && b <= end }
            .map { (a, b) -> (a - start + pre.length) until (b - start + pre.length) }
        return text to marks
    }

    companion object {
        private val FIELDS = listOf("title" to 6.0, "keywords" to 4.0, "context" to 1.5, "summary" to 2.0, "text" to 1.0)
        private val WORD = Regex("[A-Za-z0-9À-ɏ]+")
        private val SPLIT = Regex("[^a-z0-9]+")
        private val STOP = setOf(
            "a", "an", "the", "to", "of", "in", "on", "at", "and", "or", "is", "it", "its", "how", "do", "does",
            "i", "me", "my", "what", "can", "for", "with", "you", "your", "be", "are", "from", "by", "this", "that", "there", "when",
        )

        /** Lowercase words without accents ("Poké Ball" -> [poke, ball]). */
        fun words(text: String): List<String> =
            stripAccents(text).lowercase()
                .split(SPLIT).filter { it.isNotEmpty() }

        /** A light English stemmer: plurals, -ing, -ed. */
        fun stem(w: String): String = when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }

        fun terms(text: String): List<String> = words(text).filter { it !in STOP }.map(::stem)

        /** Edit distance with transpositions, giving up past [max]. */
        fun distance(a: String, b: String, max: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > max) return max + 1
            val d = Array(a.length + 1) { i -> IntArray(b.length + 1).also { it[0] = i } }
            for (j in 1..b.length) d[0][j] = j
            for (i in 1..a.length) {
                var rowMin = Int.MAX_VALUE
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                    d[i][j] = v
                    rowMin = min(rowMin, v)
                }
                if (rowMin > max) return max + 1
            }
            return d[a.length][b.length]
        }
    }
}
