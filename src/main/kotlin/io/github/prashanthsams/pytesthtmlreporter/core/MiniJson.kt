package io.github.prashanthsams.pytesthtmlreporter.core

/**
 * A small, strict JSON reader for the documents this plugin reads: the probe's output and a report's `output.json`.
 *
 * Kept in the plugin rather than borrowed from a library on the IDE's classpath, so that what it can read does not
 * change with the IDE version. Objects become [Map]s (insertion-ordered), arrays [List]s, integers [Long],
 * other numbers [Double].
 */
internal object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("unexpected trailing content")
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun fail(message: String): Nothing = throw JsonException("$message at offset $pos")

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun readValue(): Any? {
            if (atEnd()) fail("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected character '$c'")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) fail("invalid literal")
            pos += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("truncated unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail("invalid unicode escape"))
                                pos += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && text[pos].isDigit()) pos++
            var integral = true
            if (!atEnd() && text[pos] == '.') {
                integral = false
                pos++
                while (!atEnd() && text[pos].isDigit()) pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                integral = false
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                while (!atEnd() && text[pos].isDigit()) pos++
            }
            val literal = text.substring(start, pos)
            return (if (integral) literal.toLongOrNull() else null)
                ?: literal.toDoubleOrNull()
                ?: fail("invalid number '$literal'")
        }

        private fun peek(): Char = if (atEnd()) fail("unexpected end of input") else text[pos]

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            pos++
        }
    }
}

internal fun Map<*, *>.stringValue(key: String): String? = this[key] as? String

internal fun Map<*, *>.objectValue(key: String): Map<*, *>? = this[key] as? Map<*, *>

internal fun Map<*, *>.listValue(key: String): List<*>? = this[key] as? List<*>

internal fun Map<*, *>.booleanValue(key: String): Boolean? = this[key] as? Boolean
