package com.pokedaisy.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user manual, as the website serves it: docs/manual's Markdown turned into blocks by website/src/lib/docs.js
 * and published at [URL] with each release of the site (so it matches the app people can download). The
 * same blocks are the website's /docs/ pages, so the Markdown is the one place the manual is written.
 *
 * The app keeps the last copy it fetched (and every image it showed) under filesDir/docs, for reading
 * offline; [DocsStore.refresh] asks again with the copy's ETag, so an unchanged manual costs one small reply.
 */
object DocsApi {
    const val URL = "https://pokedaisy.com/api/docs/v1/docs.json"
    /** The API version this reader draws; a newer one gets its own path (/v2/) and older apps keep /v1/. */
    const val VERSION = 1

    class Manual(
        val rev: String,
        val site: String,
        val sections: List<Section>,
        val pages: List<Page>,
        val synonyms: Map<String, List<String>>,
    ) {
        private val bySlug = pages.associateBy { it.slug }
        fun page(slug: String): Page? = bySlug[slug]
        fun next(slug: String): Page? = pages.getOrNull(pages.indexOfFirst { it.slug == slug } + 1)?.takeIf { slug in bySlug }
        fun previous(slug: String): Page? = pages.indexOfFirst { it.slug == slug }.takeIf { it > 0 }?.let { pages[it - 1] }
        fun section(id: String): Section? = sections.firstOrNull { it.id == id }
    }

    class Section(val id: String, val title: String, val pages: List<String>)

    class Page(
        val slug: String,
        val title: String,
        val summary: String,
        val section: String,
        val keywords: List<String>,
        val blocks: List<Block>,
    )

    /** A run of text with its marks; [page] / [anchor] link inside the manual, [href] outside it. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val page: String? = null,
        val anchor: String? = null,
        val href: String? = null,
    )

    sealed class Block {
        data class Heading(val level: Int, val id: String, val text: String) : Block()
        data class Paragraph(val spans: List<Span>) : Block()
        data class Bullets(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block()
        /** [version]: a hash of the picture, so a changed screenshot is fetched again and an unchanged one never is. */
        data class Image(val src: String, val alt: String, val caption: String?, val width: Int, val height: Int, val version: String) : Block()
        data class Note(val kind: String, val blocks: List<Block>) : Block()
        data class Table(val head: List<List<Span>>, val rows: List<List<List<Span>>>) : Block()
        object Rule : Block()
    }

    /** What asking the site for the manual came to. */
    sealed class Refresh {
        class Updated(val manual: Manual) : Refresh()
        object Unchanged : Refresh()
        class Failed(val offline: Boolean) : Refresh()
    }

    /** [json] as the API serves it. Throws on a reply this reader can't use (another version, not JSON). */
    fun parse(json: String): Manual {
        val o = Json.parseToJsonElement(json).jsonObject
        require(o.int("version") == VERSION) { "docs API version ${o.int("version")}" }
        val synonyms = o.obj("synonyms")?.let { s -> s.keys.associateWith { k -> strings(s.array(k)) } }.orEmpty()
        return Manual(
            rev = o.string("rev"),
            site = o.string("site", "https://pokedaisy.com").trimEnd('/'),
            sections = objects(o.array("sections")).map { Section(it.required("id"), it.required("title"), strings(it.array("pages"))) },
            pages = objects(o.array("pages")).map { p ->
                Page(
                    slug = p.required("slug"),
                    title = p.required("title"),
                    summary = p.string("summary"),
                    section = p.string("section"),
                    keywords = strings(p.array("keywords")),
                    blocks = blocks(p.array("blocks")),
                )
            },
            synonyms = synonyms,
        )
    }

    // org.json's opt* rules, which the reader was written against: a missing or mistyped field is its default.
    private fun JsonObject.primitive(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.string(key: String, default: String = "") = primitive(key)?.content ?: default
    private fun JsonObject.required(key: String) = primitive(key)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("no \"$key\"")
    private fun JsonObject.int(key: String, default: Int = 0) = primitive(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: default
    private fun JsonObject.bool(key: String) = primitive(key)?.booleanOrNull ?: false
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun objects(a: JsonArray?): List<JsonObject> = a.orEmpty().map { it.jsonObject }
    private fun strings(a: JsonArray?): List<String> = a.orEmpty().map { it.jsonPrimitive.content }

    private fun spans(a: JsonArray?): List<Span> = objects(a).map { s ->
        Span(
            text = s.string("text"),
            bold = s.bool("b"),
            italic = s.bool("i"),
            code = s.bool("code"),
            page = s.string("page").ifEmpty { null },
            anchor = s.string("anchor").ifEmpty { null },
            href = s.string("href").ifEmpty { null },
        )
    }

    /** Blocks this reader doesn't know (a newer site) are skipped rather than failing the whole page. */
    private fun blocks(a: JsonArray?): List<Block> = objects(a).mapNotNull { b ->
        when (val t = b.string("t")) {
            "h2", "h3" -> Block.Heading(t.drop(1).toInt(), b.string("id"), b.string("text"))
            "p" -> Block.Paragraph(spans(b.array("c")))
            "ul", "ol" -> Block.Bullets(
                ordered = t == "ol",
                start = b.int("start", 1),
                items = b.array("items").orEmpty().map { blocks(it.jsonArray) },
            )
            "img" -> Block.Image(b.string("src"), b.string("alt"), b.string("caption").ifEmpty { null }, b.int("w"), b.int("h"), b.string("v"))
            "note" -> Block.Note(b.string("kind", "note"), blocks(b.array("c")))
            "table" -> Block.Table(
                head = b.array("head").orEmpty().map { spans(it.jsonArray) },
                rows = b.array("rows").orEmpty().map { row -> row.jsonArray.map { spans(it.jsonArray) } },
            )
            "hr" -> Block.Rule
            else -> null
        }
    }
}
