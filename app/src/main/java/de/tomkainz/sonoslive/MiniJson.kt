package de.tomkainz.sonoslive

/**
 * Just enough JSON for Cast's small messages. Android's org.json does not run in plain
 * unit tests, and this protocol is what most needs them.
 */
object MiniJson {

    fun parse(text: String): Any? = Reader(text).run {
        val value = value()
        space()
        check(at == text.length) { "Trailing data at $at" }
        value
    }

    fun write(value: Any?): String = buildString { put(this, value) }

    private fun put(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean, is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    quote(out, k.toString())
                    out.append(':')
                    put(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    put(out, v)
                }
                out.append(']')
            }
            else -> error("Cannot write ${value.javaClass}")
        }
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append(String.format("\\u%04x", c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    private class Reader(val s: String) {
        var at = 0

        fun space() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        fun value(): Any? {
            space()
            check(at < s.length) { "Unexpected end" }
            return when (val c = s[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $at")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            check(s.startsWith(w, at)) { "Bad word at $at" }
            at += w.length
            return v
        }

        private fun number(): Number {
            val start = at
            while (at < s.length && (s[at].isDigit() || s[at] in "+-.eE")) at++
            val t = s.substring(start, at)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun string(): String {
            at++
            val out = StringBuilder()
            while (true) {
                check(at < s.length) { "Unterminated string" }
                when (val c = s[at++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = s[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            out.append(s.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            at++
            val map = LinkedHashMap<String, Any?>()
            space()
            if (s[at] == '}') {
                at++
                return map
            }
            while (true) {
                space()
                val key = string()
                space()
                check(s[at++] == ':') { "Expected : at $at" }
                map[key] = value()
                space()
                when (s[at++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> error("Expected , or } at $at")
                }
            }
        }

        private fun array(): List<Any?> {
            at++
            val list = ArrayList<Any?>()
            space()
            if (s[at] == ']') {
                at++
                return list
            }
            while (true) {
                list += value()
                space()
                when (s[at++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> error("Expected , or ] at $at")
                }
            }
        }
    }
}
