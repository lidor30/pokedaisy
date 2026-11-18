package com.pokedaisy.app.companion.data

/**
 * The `.properties` text java.util.Properties reads and writes, in plain Kotlin: the key bindings' files
 * (controls.properties, hotkeys.properties) are kept in it, and this code is shared with the iPad app. [parse] reads
 * what Properties.store wrote (comments, `=` / `:` / space separators, backslash escapes, `\uXXXX`, continued lines);
 * [format] writes what Properties.load reads back. PropertiesTextTest checks both against java.util.Properties.
 */
object PropertiesText {
    /** The file's entries in their order; a key given twice keeps its last value, as Properties does. */
    fun parse(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = text.split("\r\n", "\n", "\r")
        var i = 0
        while (i < lines.size) {
            var line = lines[i++].trimStart(' ', '\t', '\u000C')
            if (line.isEmpty() || line[0] == '#' || line[0] == '!') continue
            // A line ending in an odd number of backslashes goes on in the next, its leading blanks dropped.
            while (endsContinued(line) && i < lines.size) line = line.dropLast(1) + lines[i++].trimStart(' ', '\t', '\u000C')
            if (endsContinued(line)) line = line.dropLast(1)
            var k = 0
            while (k < line.length) {
                val c = line[k]
                if (c == '\\') { k += 2; continue }
                if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\u000C') break
                k++
            }
            val key = unescape(line.substring(0, minOf(k, line.length)))
            var v = minOf(k, line.length)
            while (v < line.length && (line[v] == ' ' || line[v] == '\t' || line[v] == '\u000C')) v++
            if (v < line.length && (line[v] == '=' || line[v] == ':')) v++
            while (v < line.length && (line[v] == ' ' || line[v] == '\t' || line[v] == '\u000C')) v++
            out.remove(key)
            out[key] = unescape(line.substring(v))
        }
        return out
    }

    /** [entries] as lines Properties.load reads back, after a `#` [comment] line if there is one. */
    fun format(entries: Map<String, String>, comment: String? = null): String = buildString {
        comment?.let { append('#').append(escapeComment(it)).append('\n') }
        for ((k, v) in entries) append(escape(k, key = true)).append('=').append(escape(v, key = false)).append('\n')
    }

    private fun endsContinued(line: String): Boolean {
        var n = 0
        while (n < line.length && line[line.length - 1 - n] == '\\') n++
        return n % 2 == 1
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i >= s.length) { if (c != '\\') b.append(c); continue }
            when (val e = s[i++]) {
                't' -> b.append('\t')
                'n' -> b.append('\n')
                'r' -> b.append('\r')
                'f' -> b.append('\u000C')
                'u' -> {
                    val hex = s.substring(i, minOf(i + 4, s.length))
                    require(hex.length == 4) { "malformed \\uxxxx" }
                    b.append(hex.toInt(16).toChar())
                    i += 4
                }
                else -> b.append(e)
            }
        }
        return b.toString()
    }

    private fun escape(s: String, key: Boolean): String = buildString {
        s.forEachIndexed { i, c ->
            when {
                c == ' ' && (key || i == 0) -> append("\\ ")
                c == '\\' -> append("\\\\")
                c == '\t' -> append("\\t")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\u000C' -> append("\\f")
                c == '=' || c == ':' || c == '#' || c == '!' -> append('\\').append(c)
                c < ' ' || c > '~' -> append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
                else -> append(c)
            }
        }
    }

    private fun escapeComment(s: String): String = buildString {
        for (c in s) when {
            c == '\n' || c == '\r' -> append("\n#")
            c > '~' -> append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
            else -> append(c)
        }
    }
}
