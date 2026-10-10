package com.walnutgeek.stsloop.core.corpus

class JsonException(message: String) : IllegalArgumentException(message)

/**
 * A small, strict RFC 8259 reader for reading `turn.json` back. Stdlib-only,
 * to keep `:core` free of a serialisation dependency and ready for the KMP lift.
 *
 * Values come back as `Map<String, Any?>` (key order kept), `List<Any?>`,
 * `String`, `Long` (integers that fit), `Double`, `Boolean` or `null`.
 */
object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) p.fail("trailing content")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(what: String): Nothing = throw JsonException("$what at offset $i")

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("unexpected token")
            i += w.length
            return v
        }

        fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("expected '$c'")
            i++
        }

        fun obj(depth: Int): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val k = str()
                ws()
                expect(':')
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect('}')
                return m
            }
        }

        fun arr(depth: Int): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value(depth + 1))
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                expect(']')
                return l
            }
        }

        fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c < ' ' -> fail("control character in string")
                    c != '\\' -> b.append(c)
                    else -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("short \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                i += 4
                                b.append(code.toChar())
                            }
                            else -> fail("bad escape")
                        }
                    }
                }
            }
        }

        fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                digits()
            } else {
                fail("unexpected token")
            }
            var integral = true
            if (i < s.length && s[i] == '.') { i++; integral = false; digits() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                integral = false
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                digits()
            }
            if (i < s.length && s[i] in '0'..'9') fail("leading zero")
            val text = s.substring(start, i)
            return (if (integral) text.toLongOrNull() else null) ?: text.toDouble()
        }

        fun digits() {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start) fail("expected a digit")
        }
    }
}
