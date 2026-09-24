package jtap.storage

/** Minimal dependency-free JSON codec used for stable export/import archives. */
object JsonCodec {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> encodeString(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            "${encodeString(k.toString())}:${encode(v)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> encodeString(value.toString())
    }

    private fun encodeString(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): Any? = Parser(text).parseValue().also { /* complete */ }

    private class Parser(val s: String) {
        var i = 0

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skip()
            if (i >= s.length) error("unexpected end of json")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) error("expected '$lit' at $i")
            i += lit.length
        }

        private fun parseBoolean(): Boolean =
            if (s.startsWith("true", i)) { i += 4; true } else { expect("false"); false }

        private fun parseNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull()?.takeIf { '.' in raw || 'e' in raw || 'E' in raw }
                ?: raw.toLongOrNull() ?: raw
        }

        fun parseString(): String {
            if (s[i] != '"') error("expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            else -> error("bad escape $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("unterminated string")
        }

        private fun parseObject(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            skip()
            if (s[i] == '}') { i++; return map }
            while (true) {
                skip()
                val key = parseString()
                skip(); if (s[i] != ':') error("expected ':' at $i"); i++
                map[key] = parseValue()
                skip()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> error("expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++
            val list = mutableListOf<Any?>()
            skip()
            if (s[i] == ']') { i++; return list }
            while (true) {
                list += parseValue()
                skip()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> error("expected ',' or ']' at $i")
                }
            }
        }
    }
}
