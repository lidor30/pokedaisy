package com.pokedaisy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The docs API's JSON read into the manual's blocks, every block type the reader draws. */
class DocsApiTest {
    private val json = """
        {"version": 1, "rev": "abc", "site": "https://example.test/",
         "synonyms": {"ff": ["fast forward", "speed"]},
         "sections": [{"id": "start", "title": "START", "pages": ["intro"]}],
         "pages": [{"slug": "intro", "title": "Intro", "summary": "Hello", "section": "start", "keywords": ["first"],
           "blocks": [
             {"t": "h2", "id": "top", "text": "Top"},
             {"t": "h3", "id": "sub", "text": "Sub"},
             {"t": "p", "c": [{"text": "plain"}, {"text": "bold", "b": true}, {"text": "it", "i": true}, {"text": "x", "code": true},
                              {"text": "go", "page": "intro", "anchor": "top"}, {"text": "out", "href": "https://example.test"}]},
             {"t": "ol", "start": 3, "items": [[{"t": "p", "c": [{"text": "one"}]}], []]},
             {"t": "ul", "items": [[{"t": "hr"}]]},
             {"t": "img", "src": "/docs/a.png", "alt": "A", "w": 240, "h": 160.0, "v": "7"},
             {"t": "note", "kind": "tip", "c": [{"t": "p", "c": []}]},
             {"t": "note", "c": []},
             {"t": "table", "head": [[{"text": "H"}]], "rows": [[[{"text": "a"}], [{"text": "b"}]]]},
             {"t": "hr"},
             {"t": "video", "src": "skipped: a newer site's block"}
           ]}]}
    """

    @Test fun readsEveryBlock() {
        val m = DocsApi.parse(json)
        assertEquals("abc", m.rev)
        assertEquals("https://example.test", m.site)
        assertEquals(mapOf("ff" to listOf("fast forward", "speed")), m.synonyms)
        assertEquals(listOf("intro"), m.section("start")!!.pages)
        val p = m.page("intro")!!
        assertEquals(listOf("first"), p.keywords)
        val b = p.blocks
        assertEquals(10, b.size)
        assertEquals(DocsApi.Block.Heading(2, "top", "Top"), b[0])
        assertEquals(DocsApi.Block.Heading(3, "sub", "Sub"), b[1])
        val spans = (b[2] as DocsApi.Block.Paragraph).spans
        assertEquals(DocsApi.Span("bold", bold = true), spans[1])
        assertEquals(DocsApi.Span("go", page = "intro", anchor = "top"), spans[4])
        assertEquals(DocsApi.Span("out", href = "https://example.test"), spans[5])
        assertEquals(DocsApi.Block.Bullets(true, 3, listOf(listOf(DocsApi.Block.Paragraph(listOf(DocsApi.Span("one")))), emptyList())), b[3])
        assertEquals(DocsApi.Block.Bullets(false, 1, listOf(listOf(DocsApi.Block.Rule))), b[4])
        assertEquals(DocsApi.Block.Image("/docs/a.png", "A", null, 240, 160, "7"), b[5])
        assertEquals("tip", (b[6] as DocsApi.Block.Note).kind)
        assertEquals("note", (b[7] as DocsApi.Block.Note).kind)
        assertEquals(DocsApi.Block.Table(listOf(listOf(DocsApi.Span("H"))), listOf(listOf(listOf(DocsApi.Span("a")), listOf(DocsApi.Span("b"))))), b[8])
        assertEquals(DocsApi.Block.Rule, b[9])
        assertNull(m.page("missing"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnotherVersion() { DocsApi.parse("""{"version": 2}""") }
}
