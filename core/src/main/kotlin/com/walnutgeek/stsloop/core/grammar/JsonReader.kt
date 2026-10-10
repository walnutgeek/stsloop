package com.walnutgeek.stsloop.core.grammar

/**
 * A JSON object as read: entries in file order, duplicate keys kept, so the
 * caller can report them.
 */
internal class JsonObject(val entries: List<Pair<String, Any?>>) {
    val keys: List<String> get() = entries.map { it.first }
}

/**
 * Just enough JSON for small, hand-edited config files: objects
 * ([JsonObject]), arrays ([List]), strings (with escapes), numbers
 * ([Double]), `true`/`false` and `null`. Throws [IllegalArgumentException],
 * with the offset, on anything else.
 */
internal class JsonReader private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun read(json: String): Any? = JsonReader(json).run {
            ws()
            val v = value()
            ws()
            if (i != s.length) fail("unexpected trailing content")
            v
        }
    }

    private fun value(): Any? = when (peek()) {
        '{' -> obj()
        '[' -> array()
        '"' -> string()
        't' -> literal("true", true)
        'f' -> literal("false", false)
        'n' -> literal("null", null)
        else -> number()
    }

    private fun obj(): JsonObject {
        expect('{')
        val out = mutableListOf<Pair<String, Any?>>()
        ws()
        if (peek() == '}') {
            i++
            return JsonObject(out)
        }
        while (true) {
            ws()
            val key = string()
            ws()
            expect(':')
            ws()
            out += key to value()
            ws()
            when (next()) {
                ',' -> continue
                '}' -> return JsonObject(out)
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun array(): List<Any?> {
        expect('[')
        val out = mutableListOf<Any?>()
        ws()
        if (peek() == ']') {
            i++
            return out
        }
        while (true) {
            ws()
            out += value()
            ws()
            when (next()) {
                ',' -> continue
                ']' -> return out
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val b = StringBuilder()
        while (true) {
            val c = next() ?: fail("unterminated string")
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = next()) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000c')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail("bad \\u escape")
                        b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                        i += 4
                    }
                    else -> fail("bad escape")
                }
                else -> if (c < ' ') fail("control character in string") else b.append(c)
            }
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("not a JSON value")
        i += word.length
        return v
    }

    private fun number(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() } ?: run {
            i = start
            fail("not a JSON value")
        }
    }

    private fun ws() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    private fun peek(): Char? = s.getOrNull(i)

    private fun next(): Char? = s.getOrNull(i++)

    private fun expect(c: Char) {
        if (next() != c) {
            i--
            fail("expected '$c'")
        }
    }

    private fun fail(why: String): Nothing = throw IllegalArgumentException("$why at offset ${i.coerceIn(0, s.length)}")
}
